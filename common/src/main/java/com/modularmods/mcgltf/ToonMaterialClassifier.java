package com.modularmods.mcgltf;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;

import de.javagl.jgltf.model.AccessorModel;
import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.MaterialModel;
import de.javagl.jgltf.model.MeshModel;
import de.javagl.jgltf.model.MeshPrimitiveModel;
import de.javagl.jgltf.model.NodeModel;
import de.javagl.jgltf.model.SkinModel;

/**
 * Sorts a VRM's materials into the responses the Genshin material model authors
 * separately: skin, hair, cloth, metal, and the facial features that stay lit.
 *
 * <p>Geometry and the skeleton decide, not names. The face is the material showing the
 * most surface towards the front of the mesh that the blink and vowel expressions drive,
 * remaining head bound surfaces are hair or facial features by size, and names are only
 * consulted away from the head, where geometry cannot tell a shoe buckle from a shirt.
 */
final class ToonMaterialClassifier {
	enum Category {
		SKIN, HAIR, CLOTH, METAL, EYE, FACE
	}

	/**
	 * Name fragments used only as a secondary signal, matched as whole tokens so that a
	 * colour like "Brown" cannot be read as a brow. Japanese and Korean terms are
	 * included because plenty of VRMs never use English. Order is significant: the first
	 * category with a matching token wins.
	 */
	private static final List<Map.Entry<Category, Set<String>>> NAME_TOKENS = List.of(
		Map.entry(Category.EYE, Set.of("EYE", "EYES", "EYEWHITE", "EYEIRIS", "EYEHIGHLIGHT",
			"EYEEXTRA", "EYELINE", "EYELASH", "IRIS", "SCLERA", "CORNEA", "PUPIL", "눈", "目", "瞳")),
		Map.entry(Category.FACE, Set.of("FACE", "BROW", "EYEBROW", "FACEBROW", "MOUTH", "TOOTH",
			"TEETH", "TONGUE", "LIP", "얼굴", "顔", "眉", "口")),
		Map.entry(Category.HAIR, Set.of("HAIR", "AHOGE", "PONYTAIL", "BANGS", "머리", "머리카락",
			"헤어", "髪")),
		Map.entry(Category.METAL, Set.of("METAL", "METALLIC", "ACCESSORY", "SHOE", "SHOES", "BOOT",
			"BOOTS", "BUCKLE", "BUTTON", "JEWEL", "CHAIN", "ARMOR", "BADGE", "PEARL", "금속", "신발")),
		Map.entry(Category.SKIN, Set.of("SKIN", "BODY", "몸", "바디", "피부", "肌", "体")),
		Map.entry(Category.CLOTH, Set.of("CLOTH", "CLOTHES", "TOPS", "BOTTOMS", "ONEPIECE", "SKIRT",
			"SHIRT", "COAT", "DRESS", "SOCK", "SOCKS", "GLOVE", "GLOVES", "RIBBON", "옷", "服")));

	private final GltfModel model;
	private final ToonVrmData vrm;

	ToonMaterialClassifier(GltfModel model, ToonVrmData vrm) {
		this.model = model;
		this.vrm = vrm;
	}

	/** Per-material geometry tally, keyed by material index. */
	static final class Geometry {
		double area;
		double forwardArea;
		double headArea;
		double forwardOffset;
		double upOffset;
		final Set<MeshModel> meshes = Collections.newSetFromMap(new IdentityHashMap<>());

		double headAffinity() {
			return area > 0.0 ? headArea / area : 0.0;
		}
	}

	record Result(Map<Integer, Category> categories, Integer faceMaterial,
		Map<Integer, Geometry> geometry) {
	}

	Result classify() {
		Map<Integer, Geometry> geometry = measure();
		Map<Integer, Geometry> headMaterials = new HashMap<>();
		geometry.forEach((index, entry) -> {
			if (entry.headAffinity() > 0.5) {
				headMaterials.put(index, entry);
			}
		});

		Set<MeshModel> faceMeshes = vrm.faceMeshes();
		Map<Integer, Geometry> candidates = new HashMap<>();
		geometry.forEach((index, entry) -> {
			if (entry.forwardArea > 0.0 && !Collections.disjoint(entry.meshes, faceMeshes)) {
				candidates.put(index, entry);
			}
		});
		if (candidates.isEmpty()) {
			// No expressions to go by, so fall back to the head attachment that the
			// specification's first-person "auto" rule uses.
			headMaterials.forEach((index, entry) -> {
				if (entry.forwardOffset > 0.0) {
					candidates.put(index, entry);
				}
			});
		}
		Integer faceMaterial = null;
		double bestForward = Double.NEGATIVE_INFINITY;
		for (Map.Entry<Integer, Geometry> entry : candidates.entrySet()) {
			if (entry.getValue().forwardArea > bestForward) {
				bestForward = entry.getValue().forwardArea;
				faceMaterial = entry.getKey();
			}
		}
		if (faceMaterial != null) {
			headMaterials.putIfAbsent(faceMaterial, geometry.get(faceMaterial));
		}
		double faceArea = faceMaterial == null ? 0.0 : headMaterials.get(faceMaterial).area;

		Map<Integer, Category> categories = new HashMap<>();
		List<MaterialModel> materials = model.getMaterialModels();
		for (int index = 0; index < materials.size(); index++) {
			Category hinted = named(materials.get(index).getName());
			if (faceMaterial != null && faceMaterial == index) {
				categories.put(index, Category.FACE);
				continue;
			}
			Geometry entry = headMaterials.get(index);
			if (entry != null) {
				if (hinted == Category.EYE || hinted == Category.FACE || hinted == Category.HAIR
					|| hinted == Category.METAL) {
					categories.put(index, hinted);
				} else {
					// Large remaining head surfaces are hair or headwear; the small ones
					// are brows, lashes and eyes, which stay lit rather than self-shadowing.
					categories.put(index, entry.area > 0.15 * faceArea ? Category.HAIR : Category.EYE);
				}
				continue;
			}
			categories.put(index, hinted == Category.METAL || hinted == Category.SKIN
				|| hinted == Category.CLOTH || hinted == Category.HAIR ? hinted : Category.CLOTH);
		}
		return new Result(categories, faceMaterial, geometry);
	}

	private Map<Integer, Geometry> measure() {
		Map<Integer, Geometry> stats = new HashMap<>();
		Vector3f forward = vrm.headForward();
		Vector3f origin = headOrigin();
		Map<MeshModel, SkinModel> skins = vrm.meshSkins();
		List<MaterialModel> materials = model.getMaterialModels();
		for (MeshModel mesh : model.getMeshModels()) {
			Set<Integer> headSlots = vrm.headJointSlots(skins.get(mesh));
			for (MeshPrimitiveModel primitive : mesh.getMeshPrimitiveModels()) {
				int material = materials.indexOf(primitive.getMaterialModel());
				AccessorModel positionAccessor = primitive.getAttributes().get("POSITION");
				if (material < 0 || positionAccessor == null) {
					continue;
				}
				int vertexCount = positionAccessor.getCount();
				float[] positions = RenderedGltfModel.readVectors(positionAccessor, vertexCount, 3, 0.0F);
				int[] indices = ToonRasterizer.triangleIndices(primitive, vertexCount);
				float[] headWeights = headWeights(primitive, vertexCount, headSlots);
				Geometry entry = stats.computeIfAbsent(material, key -> new Geometry());
				entry.meshes.add(mesh);
				accumulate(entry, positions, indices, headWeights, forward, origin);
			}
		}
		return stats;
	}

	private static void accumulate(Geometry entry, float[] positions, int[] indices,
		float[] headWeights, Vector3f forward, Vector3f origin) {
		Vector3f edgeOne = new Vector3f();
		Vector3f edgeTwo = new Vector3f();
		Vector3f cross = new Vector3f();
		Vector3f centroid = new Vector3f();
		for (int triangle = 0; triangle + 2 < indices.length; triangle += 3) {
			int a = indices[triangle];
			int b = indices[triangle + 1];
			int c = indices[triangle + 2];
			edgeOne.set(positions[b * 3] - positions[a * 3], positions[b * 3 + 1] - positions[a * 3 + 1],
				positions[b * 3 + 2] - positions[a * 3 + 2]);
			edgeTwo.set(positions[c * 3] - positions[a * 3], positions[c * 3 + 1] - positions[a * 3 + 1],
				positions[c * 3 + 2] - positions[a * 3 + 2]);
			edgeOne.cross(edgeTwo, cross);
			double length = cross.length();
			if (length <= 0.0) {
				continue;
			}
			double area = 0.5 * length;
			centroid.set(
				(positions[a * 3] + positions[b * 3] + positions[c * 3]) / 3.0F - origin.x,
				(positions[a * 3 + 1] + positions[b * 3 + 1] + positions[c * 3 + 1]) / 3.0F - origin.y,
				(positions[a * 3 + 2] + positions[b * 3 + 2] + positions[c * 3 + 2]) / 3.0F - origin.z);
			double facing = cross.dot(forward) / length;
			entry.area += area;
			entry.forwardArea += area * Math.max(facing, 0.0);
			entry.headArea += area * (headWeights[a] + headWeights[b] + headWeights[c]) / 3.0;
			entry.forwardOffset += area * centroid.dot(forward);
			entry.upOffset += area * centroid.y;
		}
	}

	private Vector3f headOrigin() {
		NodeModel head = vrm.head();
		if (head == null) {
			return new Vector3f();
		}
		float[] transform = head.computeGlobalTransform(null);
		return new Vector3f(transform[12], transform[13], transform[14]);
	}

	static float[] headWeights(MeshPrimitiveModel primitive, int vertexCount,
		Set<Integer> headSlots) {
		float[] weights = new float[vertexCount];
		AccessorModel jointAccessor = primitive.getAttributes().get("JOINTS_0");
		AccessorModel weightAccessor = primitive.getAttributes().get("WEIGHTS_0");
		if (headSlots.isEmpty() || jointAccessor == null || weightAccessor == null) {
			return weights;
		}
		// Joint indices are small enough to survive the float round trip exactly, which
		// keeps this on the shared accessor reader instead of a second decoder.
		float[] joints = RenderedGltfModel.readVectors(jointAccessor, vertexCount, 4, 0.0F);
		float[] influences = RenderedGltfModel.readVectors(weightAccessor, vertexCount, 4, 0.0F);
		for (int vertex = 0; vertex < vertexCount; vertex++) {
			float total = 0.0F;
			for (int component = 0; component < 4; component++) {
				if (headSlots.contains(Math.round(joints[vertex * 4 + component]))) {
					total += influences[vertex * 4 + component];
				}
			}
			weights[vertex] = total;
		}
		return weights;
	}

	private static Category named(String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		Set<String> tokens = tokens(name);
		for (Map.Entry<Category, Set<String>> candidate : NAME_TOKENS) {
			if (!Collections.disjoint(tokens, candidate.getValue())) {
				return candidate.getKey();
			}
		}
		return null;
	}

	private static Set<String> tokens(String name) {
		Set<String> tokens = new HashSet<>();
		StringBuilder current = new StringBuilder();
		String upper = name.toUpperCase(Locale.ROOT);
		for (int index = 0; index < upper.length(); index++) {
			char character = upper.charAt(index);
			if (Character.isLetterOrDigit(character)) {
				current.append(character);
				continue;
			}
			if (!current.isEmpty()) {
				tokens.add(current.toString());
				current.setLength(0);
			}
		}
		if (!current.isEmpty()) {
			tokens.add(current.toString());
		}
		return tokens;
	}
}
