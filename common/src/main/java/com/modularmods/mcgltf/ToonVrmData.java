package com.modularmods.mcgltf;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;

import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.MeshModel;
import de.javagl.jgltf.model.NodeModel;
import de.javagl.jgltf.model.SkinModel;

/**
 * The parts of the VRM extensions that say where a character's head and face are.
 *
 * <p>Reading these from the model is what lets toon data be derived for any VRM instead
 * of only for the models a set of hand written names happens to cover. The same signals
 * are used by the offline tool in the Celerant repository, and the two must agree.
 */
final class ToonVrmData {
	/**
	 * Expression presets the VRM specification places on the face. Whichever mesh these
	 * morph targets drive is the face mesh, whatever the author called it.
	 */
	private static final Set<String> FACE_EXPRESSIONS_VRM0 = Set.of("a", "i", "u", "e", "o",
		"blink", "blink_l", "blink_r", "joy", "angry", "sorrow", "fun");
	private static final Set<String> FACE_EXPRESSIONS_VRM1 = Set.of("aa", "ih", "ou", "ee", "oh",
		"blink", "blinkLeft", "blinkRight", "happy", "angry", "sad", "relaxed");

	private final GltfModel model;
	private final boolean vrm1;
	private final Map<NodeModel, Integer> nodeIndices;

	private ToonVrmData(GltfModel model, boolean vrm1, Map<NodeModel, Integer> nodeIndices) {
		this.model = model;
		this.vrm1 = vrm1;
		this.nodeIndices = nodeIndices;
	}

	static ToonVrmData of(GltfModel model) {
		Map<NodeModel, Integer> indices = new IdentityHashMap<>();
		List<NodeModel> nodes = model.getNodeModels();
		for (int index = 0; index < nodes.size(); index++) {
			indices.put(nodes.get(index), index);
		}
		return new ToonVrmData(model, extension(model, "VRMC_vrm") != null, indices);
	}

	/**
	 * Per the VRM coordinate table a VRM 0.x character faces -Z with +X to its right,
	 * while a VRM 1.0 character faces +Z with -X to its right. Assuming either one
	 * outright mirrors the face of every model authored to the other version.
	 */
	Vector3f headForward() {
		return new Vector3f(0.0F, 0.0F, vrm1 ? 1.0F : -1.0F);
	}

	Vector3f headRight() {
		return new Vector3f(vrm1 ? -1.0F : 1.0F, 0.0F, 0.0F);
	}

	/**
	 * The head joint, repaired when the humanoid map points at a leaf. Automated
	 * conversions sometimes fill that map by joint index and land on an eye; a head
	 * carries the eye, hair and jaw joints beneath it, so a leaf hanging off a joint
	 * that clearly is such a hub means the hub is the head.
	 */
	NodeModel head() {
		NodeModel declared = declaredHead();
		if (declared == null || !declared.getChildren().isEmpty()) {
			return declared;
		}
		NodeModel parent = declared.getParent();
		return parent != null && parent.getChildren().size() >= 3 ? parent : declared;
	}

	Set<NodeModel> headSubtree() {
		NodeModel head = head();
		Set<NodeModel> found = Collections.newSetFromMap(new IdentityHashMap<>());
		if (head == null) {
			return found;
		}
		Deque<NodeModel> pending = new ArrayDeque<>();
		found.add(head);
		pending.add(head);
		while (!pending.isEmpty()) {
			for (NodeModel child : pending.remove().getChildren()) {
				if (found.add(child)) {
					pending.add(child);
				}
			}
		}
		return found;
	}

	/** Meshes driven by the blink and vowel expressions, which are on the face. */
	Set<MeshModel> faceMeshes() {
		Set<MeshModel> meshes = Collections.newSetFromMap(new IdentityHashMap<>());
		List<MeshModel> allMeshes = model.getMeshModels();
		for (Object group : list(map(extension(model, "VRM"), "blendShapeMaster"), "blendShapeGroups")) {
			if (!(group instanceof Map<?, ?> entry)
				|| !(entry.get("presetName") instanceof String preset)
				|| !FACE_EXPRESSIONS_VRM0.contains(preset)) {
				continue;
			}
			for (Object bind : list(entry, "binds")) {
				if (bind instanceof Map<?, ?> values && values.get("mesh") instanceof Number mesh) {
					int index = mesh.intValue();
					if (index >= 0 && index < allMeshes.size()) {
						meshes.add(allMeshes.get(index));
					}
				}
			}
		}
		Map<?, ?> presets = map(map(extension(model, "VRMC_vrm"), "expressions"), "preset");
		if (presets != null) {
			presets.forEach((name, value) -> {
				if (!(name instanceof String preset) || !FACE_EXPRESSIONS_VRM1.contains(preset)) {
					return;
				}
				for (Object bind : list(value, "morphTargetBinds")) {
					if (bind instanceof Map<?, ?> values && values.get("node") instanceof Number node) {
						meshes.addAll(meshesOf(node.intValue()));
					}
				}
			});
		}
		return meshes;
	}

	/** Skin of each mesh, so head joint weights can be found for its vertices. */
	Map<MeshModel, SkinModel> meshSkins() {
		Map<MeshModel, SkinModel> skins = new IdentityHashMap<>();
		for (NodeModel node : model.getNodeModels()) {
			SkinModel skin = node.getSkinModel();
			if (skin == null) {
				continue;
			}
			for (MeshModel mesh : node.getMeshModels()) {
				skins.putIfAbsent(mesh, skin);
			}
		}
		return skins;
	}

	/**
	 * Slots of a skin bound to the head or anything beneath it. A vertex is on the head
	 * in proportion to how much of its weight lands in these slots, which is the rule the
	 * specification's own first-person "auto" mode uses to split head from body.
	 */
	Set<Integer> headJointSlots(SkinModel skin) {
		Set<NodeModel> subtree = headSubtree();
		Set<Integer> slots = new HashSet<>();
		if (skin == null || subtree.isEmpty()) {
			return slots;
		}
		List<NodeModel> joints = skin.getJoints();
		for (int slot = 0; slot < joints.size(); slot++) {
			if (subtree.contains(joints.get(slot))) {
				slots.add(slot);
			}
		}
		return slots;
	}

	Integer indexOf(NodeModel node) {
		return nodeIndices.get(node);
	}

	private NodeModel declaredHead() {
		List<NodeModel> nodes = model.getNodeModels();
		Map<?, ?> bones = map(map(extension(model, "VRMC_vrm"), "humanoid"), "humanBones");
		if (bones != null && bones.get("head") instanceof Map<?, ?> head
			&& head.get("node") instanceof Number node) {
			return node(nodes, node.intValue());
		}
		for (Object bone : list(map(extension(model, "VRM"), "humanoid"), "humanBones")) {
			if (bone instanceof Map<?, ?> entry && "head".equals(entry.get("bone"))
				&& entry.get("node") instanceof Number node) {
				return node(nodes, node.intValue());
			}
		}
		return null;
	}

	private List<MeshModel> meshesOf(int nodeIndex) {
		NodeModel node = node(model.getNodeModels(), nodeIndex);
		return node == null ? List.of() : node.getMeshModels();
	}

	private static NodeModel node(List<NodeModel> nodes, int index) {
		return index >= 0 && index < nodes.size() ? nodes.get(index) : null;
	}

	private static Map<?, ?> extension(GltfModel model, String name) {
		Map<String, Object> extensions = model.getExtensions();
		return extensions != null && extensions.get(name) instanceof Map<?, ?> value ? value : null;
	}

	private static Map<?, ?> map(Object parent, String name) {
		return parent instanceof Map<?, ?> values && values.get(name) instanceof Map<?, ?> value
			? value : null;
	}

	private static List<?> list(Object parent, String name) {
		return parent instanceof Map<?, ?> values && values.get(name) instanceof List<?> value
			? value : List.of();
	}

}
