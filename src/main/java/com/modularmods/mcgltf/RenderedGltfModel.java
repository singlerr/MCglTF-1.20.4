package com.modularmods.mcgltf;

import static de.javagl.jgltf.model.GltfConstants.GL_TRIANGLES;
import static de.javagl.jgltf.model.GltfConstants.GL_TRIANGLE_FAN;
import static de.javagl.jgltf.model.GltfConstants.GL_TRIANGLE_STRIP;
import static de.javagl.jgltf.model.GltfConstants.GL_CLAMP_TO_EDGE;
import static de.javagl.jgltf.model.GltfConstants.GL_NEAREST;
import static de.javagl.jgltf.model.GltfConstants.GL_NEAREST_MIPMAP_LINEAR;
import static de.javagl.jgltf.model.GltfConstants.GL_NEAREST_MIPMAP_NEAREST;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import de.javagl.jgltf.model.AccessorByteData;
import de.javagl.jgltf.model.AccessorData;
import de.javagl.jgltf.model.AccessorFloatData;
import de.javagl.jgltf.model.AccessorIntData;
import de.javagl.jgltf.model.AccessorModel;
import de.javagl.jgltf.model.AccessorShortData;
import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.MaterialModel;
import de.javagl.jgltf.model.MeshModel;
import de.javagl.jgltf.model.MeshPrimitiveModel;
import de.javagl.jgltf.model.NodeModel;
import de.javagl.jgltf.model.SceneModel;
import de.javagl.jgltf.model.SkinModel;
import de.javagl.jgltf.model.TextureModel;
import de.javagl.jgltf.model.v2.MaterialModelV2;
import de.javagl.jgltf.model.v2.MaterialModelV2.AlphaMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;

/**
 * Minecraft 26.2 renderer that submits ordinary entity geometry. Iris can
 * therefore select its entity or hand pipeline without MCglTF touching GL state.
 */
public class RenderedGltfModel {

	private static final AtomicInteger NEXT_MODEL_ID = new AtomicInteger();
	private static final float[] EMPTY_FLOATS = new float[0];
	/** Opts this submission into the optional MToon/ToonShader material path. */
	public static final int MTOON_OVERLAY_REQUEST = 15 << 16;
	public static final RenderView FULL_VIEW = new RenderView(Set.of(), Set.of(), Set.of(), Set.of(), Set.of());

	public final GltfModel gltfModel;
	public final List<RenderedGltfScene> renderedGltfScenes;

	public RenderedGltfModel(List<Runnable> cleanup, GltfModel gltfModel) {
		this(cleanup, gltfModel, null);
	}

	public RenderedGltfModel(List<Runnable> cleanup, GltfModel gltfModel, Path toonShaderProfilePath) {
		this.gltfModel = gltfModel;
		if (gltfModel.getSceneModels().isEmpty()) {
			this.renderedGltfScenes = List.of();
			return;
		}
		ToonShaderModel toonShader = ToonShaderModel.load(gltfModel, toonShaderProfilePath);
		TextureRegistry textures = new TextureRegistry(cleanup, NEXT_MODEL_ID.getAndIncrement(), gltfModel);
		Map<MaterialModel, MToonProfile> mtoonMaterials = mtoonMaterials(gltfModel);
		List<RenderedGltfScene> scenes = new ArrayList<>(gltfModel.getSceneModels().size());
		for (SceneModel sceneModel : gltfModel.getSceneModels()) {
			List<Primitive> primitives = new ArrayList<>();
			IdentityHashMap<NodeModel, Boolean> visited = new IdentityHashMap<>();
			for (NodeModel nodeModel : sceneModel.getNodeModels()) {
				collectPrimitives(nodeModel, textures, mtoonMaterials, toonShader, primitives, visited);
			}
			primitives.sort(Comparator.comparingInt(Primitive::sortOrder)
				.thenComparing(primitive -> primitive.sortOrder() == 0 ? primitive.textureKey() : ""));
			scenes.add(new RenderedGltfScene(primitives, toonShader));
		}
		this.renderedGltfScenes = List.copyOf(scenes);
	}

	public void submit(int sceneIndex, PoseStack poseStack, SubmitNodeCollector collector, int packedLight, int packedOverlay) {
		submit(sceneIndex, poseStack, collector, packedLight, packedOverlay, FULL_VIEW);
	}

	public void submit(int sceneIndex, PoseStack poseStack, SubmitNodeCollector collector, int packedLight,
		int packedOverlay, RenderView view) {
		renderedGltfScenes.get(sceneIndex).submit(poseStack, collector, packedLight, packedOverlay, view);
	}

	public record RenderView(Set<NodeModel> hiddenJoints, Set<NodeModel> hiddenNodes, Set<MeshModel> hiddenMeshes,
		Set<NodeModel> weightFilteredNodes, Set<MeshModel> weightFilteredMeshes) {
		public RenderView {
			hiddenJoints = identityCopy(hiddenJoints);
			hiddenNodes = identityCopy(hiddenNodes);
			hiddenMeshes = identityCopy(hiddenMeshes);
			weightFilteredNodes = identityCopy(weightFilteredNodes);
			weightFilteredMeshes = identityCopy(weightFilteredMeshes);
		}

		private static <T> Set<T> identityCopy(Set<T> source) {
			Set<T> copy = Collections.newSetFromMap(new IdentityHashMap<>());
			copy.addAll(source);
			return Collections.unmodifiableSet(copy);
		}
	}

	private static void collectPrimitives(NodeModel node, TextureRegistry textures,
		Map<MaterialModel, MToonProfile> mtoonMaterials,
		ToonShaderModel toonShader, List<Primitive> output,
		IdentityHashMap<NodeModel, Boolean> visited) {
		if (visited.put(node, Boolean.TRUE) != null) {
			return;
		}
		for (MeshModel mesh : node.getMeshModels()) {
			List<Primitive> meshPrimitives = new ArrayList<>();
			for (MeshPrimitiveModel primitive : mesh.getMeshPrimitiveModels()) {
				Primitive prepared = Primitive.create(node, mesh, primitive, textures,
					mtoonMaterials.getOrDefault(primitive.getMaterialModel(), MToonProfile.NONE),
					toonShader);
				if (prepared != null) {
					meshPrimitives.add(prepared);
				}
			}
			Primitive.smoothGeneratedNormals(meshPrimitives, toonShader.smoothNormalCosine());
			output.addAll(meshPrimitives);
		}
		for (NodeModel child : node.getChildren()) {
			collectPrimitives(child, textures, mtoonMaterials, toonShader, output, visited);
		}
	}

	static Map<MaterialModel, MToonProfile> mtoonMaterials(GltfModel gltfModel) {
		Map<MaterialModel, MToonProfile> result = new IdentityHashMap<>();
		Map<String, Object> extensions = gltfModel.getExtensions();
		List<MaterialModel> materials = gltfModel.getMaterialModels();
		if (extensions != null && extensions.get("VRM") instanceof Map<?, ?> vrm
			&& vrm.get("materialProperties") instanceof List<?> properties) {
			for (int i = 0; i < Math.min(materials.size(), properties.size()); i++) {
				MToonProfile profile = MToonProfile.from(properties.get(i));
				if (profile.enabled()) {
					result.put(materials.get(i), profile);
				}
			}
		}
		for (MaterialModel material : materials) {
			if (material.getExtensions() != null
				&& material.getExtensions().get("VRMC_materials_mtoon") instanceof Map<?, ?> mtoon) {
				result.put(material, MToonProfile.fromVrm1(mtoon));
			}
		}
		return result;
	}

	static int[] filterTriangles(int[] indices, boolean[] hiddenVertices) {
		int[] kept = new int[indices.length];
		int count = 0;
		for (int i = 0; i < indices.length; i += 3) {
			int a = indices[i];
			int b = indices[i + 1];
			int c = indices[i + 2];
			if (!hiddenVertices[a] && !hiddenVertices[b] && !hiddenVertices[c]) {
				kept[count++] = a;
				kept[count++] = b;
				kept[count++] = c;
			}
		}
		return count == indices.length ? indices : Arrays.copyOf(kept, count);
	}

	static final class FrameSnapshots {
		private final IdentityHashMap<NodeModel, Matrix4f> nodeTransforms = new IdentityHashMap<>();
		private final IdentityHashMap<NodeModel, SkinPalette> skinPalettes = new IdentityHashMap<>();
		private final IdentityHashMap<NodeModel, float[]> morphWeights = new IdentityHashMap<>();

		void clear() {
			nodeTransforms.clear();
			skinPalettes.clear();
			morphWeights.clear();
		}

		Matrix4f nodeTransform(NodeModel node) {
			return nodeTransforms.computeIfAbsent(node, key -> new Matrix4f().set(key.computeGlobalTransform(null)));
		}

		SkinPalette skinPalette(NodeModel node, SkinModel skin) {
			return skinPalettes.computeIfAbsent(node, ignored -> SkinPalette.capture(nodeTransform(node), skin));
		}

		float[] morphWeights(NodeModel node, MeshModel mesh) {
			return morphWeights.computeIfAbsent(node, ignored -> {
				float[] weights = node.getWeights();
				if (weights == null) {
					weights = mesh.getWeights();
				}
				return weights == null ? EMPTY_FLOATS : weights.clone();
			});
		}
	}

	static final class Primitive {
		private final NodeModel node;
		private final MeshModel mesh;
		private final SkinModel skin;
		private final int vertexCount;
		private final int[] indices;
		private final float[] positions;
		private final float[] normals;
		private final float[] texcoords;
		private final float[] backTexcoords;
		private final int[] vertexColors;
		private final int[] toonVertexColors;
		private final float[][] morphPositions;
		private final float[][] morphNormals;
		private ToonShaderGeometry toonGeometry;
		private final boolean generatedSmoothNormals;
		private final int influences;
		private final int[] joints;
		private final float[] weights;
		private final PreparedMaterial material;
		private final IdentityHashMap<RenderView, int[]> viewIndices = new IdentityHashMap<>();
		private DeformedGeometry cachedGeometry;
		private Matrix4f[] cachedSkinMatrices;
		private float[] cachedMorphWeights;

		private Primitive(NodeModel node, MeshModel mesh, SkinModel skin, int vertexCount, int[] indices,
			float[] positions, float[] normals, float[] texcoords, float[] backTexcoords, float[] colors,
			float[][] morphPositions,
			float[][] morphNormals, ToonShaderGeometry toonGeometry, boolean generatedSmoothNormals,
			int influences, int[] joints, float[] weights,
			PreparedMaterial material) {
			this.node = node;
			this.mesh = mesh;
			this.skin = skin;
			this.vertexCount = vertexCount;
			this.indices = indices;
			this.positions = positions;
			this.normals = normals;
			this.texcoords = texcoords;
			this.backTexcoords = backTexcoords;
			this.vertexColors = new int[vertexCount];
			this.toonVertexColors = new int[vertexCount];
			for (int vertex = 0; vertex < vertexCount; vertex++) {
				int color = vertex * 4;
				toonVertexColors[vertex] = ARGB.color(toChannel(colors[color + 3]), toChannel(colors[color]),
					toChannel(colors[color + 1]), toChannel(colors[color + 2]));
				vertexColors[vertex] = ARGB.color(
					toChannel(colors[color + 3] * material.colorFactor()[3]),
					toChannel(colors[color] * material.colorFactor()[0]),
					toChannel(colors[color + 1] * material.colorFactor()[1]),
					toChannel(colors[color + 2] * material.colorFactor()[2]));
			}
			this.morphPositions = morphPositions;
			this.morphNormals = morphNormals;
			this.toonGeometry = toonGeometry;
			this.generatedSmoothNormals = generatedSmoothNormals;
			this.influences = influences;
			this.joints = joints;
			this.weights = weights;
			this.material = material;
		}

		static Primitive create(NodeModel node, MeshModel mesh, MeshPrimitiveModel source, TextureRegistry textures,
			MToonProfile mtoon, ToonShaderModel toonShader) {
			AccessorModel positionAccessor = source.getAttributes().get("POSITION");
			if (positionAccessor == null || positionAccessor.getCount() == 0) {
				return null;
			}
			int vertexCount = positionAccessor.getCount();
			int[] indices = triangulate(source, vertexCount);
			if (indices.length == 0) {
				MCglTF.logger.warn("Skipping unsupported or empty glTF primitive mode {}", source.getMode());
				return null;
			}

			Map<String, AccessorModel> attributes = source.getAttributes();
			float[] positions = readVectors(positionAccessor, vertexCount, 3, 0.0F);
			float[] normals = attributes.containsKey("NORMAL")
				? readVectors(attributes.get("NORMAL"), vertexCount, 3, 0.0F) : null;
			PreparedMaterial material = PreparedMaterial.create(source, source.getMaterialModel(), textures, mtoon, toonShader);
			AccessorModel texcoordAccessor = attributes.get("TEXCOORD_" + material.texCoordSet());
			float[] texcoords = texcoordAccessor == null
				? new float[vertexCount * 2] : readVectors(texcoordAccessor, vertexCount, 2, 0.0F);
			AccessorModel backTexcoordAccessor = attributes.get("TEXCOORD_1");
			float[] backTexcoords = backTexcoordAccessor == null
				? texcoords : readVectors(backTexcoordAccessor, vertexCount, 2, 0.0F);
			float[] colors = readColors(attributes.get("COLOR_0"), vertexCount);
			boolean toon = toonShader.applies(source, source.getMaterialModel(), mtoon);
			AccessorModel tangentAccessor = attributes.get("TANGENT");
			float[] tangents = toon && tangentAccessor != null
				&& tangentAccessor.getAccessorData().getNumComponentsPerElement() >= 4
				? readVectors(tangentAccessor, vertexCount, 4, 0.0F) : null;
			AccessorModel smoothNormalAccessor = attributes.get("TEXCOORD_7");
			float[] encodedSmoothNormals = toon && smoothNormalAccessor != null
				&& smoothNormalAccessor.getAccessorData().getNumComponentsPerElement() >= 3
				? readVectors(smoothNormalAccessor, vertexCount, 3, 0.0F) : null;

			List<Map<String, AccessorModel>> targets = source.getTargets();
			float[][] morphPositions = new float[targets.size()][];
			float[][] morphNormals = new float[targets.size()][];
			float[][] morphTangents = new float[targets.size()][];
			for (int i = 0; i < targets.size(); i++) {
				AccessorModel morphPosition = targets.get(i).get("POSITION");
				AccessorModel morphNormal = targets.get(i).get("NORMAL");
				AccessorModel morphTangent = targets.get(i).get("TANGENT");
				morphPositions[i] = morphPosition == null ? null : readVectors(morphPosition, vertexCount, 3, 0.0F);
				morphNormals[i] = morphNormal == null ? null : readVectors(morphNormal, vertexCount, 3, 0.0F);
				morphTangents[i] = !toon || morphTangent == null ? null
					: readVectors(morphTangent, vertexCount, 3, 0.0F);
			}

			SkinData skinData = SkinData.create(attributes, vertexCount, node.getSkinModel());
			if (toon) {
				positions = deindex(positions, 3, indices);
				normals = normals == null ? null : deindex(normals, 3, indices);
				texcoords = deindex(texcoords, 2, indices);
				backTexcoords = deindex(backTexcoords, 2, indices);
				colors = deindex(colors, 4, indices);
				tangents = tangents == null ? null : deindex(tangents, 4, indices);
				encodedSmoothNormals = encodedSmoothNormals == null ? null
					: deindex(encodedSmoothNormals, 3, indices);
				for (int i = 0; i < targets.size(); i++) {
					morphPositions[i] = morphPositions[i] == null ? null : deindex(morphPositions[i], 3, indices);
					morphNormals[i] = morphNormals[i] == null ? null : deindex(morphNormals[i], 3, indices);
					morphTangents[i] = morphTangents[i] == null ? null : deindex(morphTangents[i], 3, indices);
				}
				skinData = skinData.deindex(indices);
				vertexCount = indices.length;
				indices = sequentialIndices(vertexCount);
			}
			ToonShaderGeometry toonGeometry = toon
				? ToonShaderGeometry.create(positions, normals, texcoords, indices, tangents, encodedSmoothNormals,
					morphTangents, material.toonMaterial().requiresMappedTangents(),
					toonShader.allowGeneratedSmoothNormals(), toonShader.smoothNormalCosine()) : null;
			boolean generatedSmoothNormals = toon && encodedSmoothNormals == null
				&& toonShader.allowGeneratedSmoothNormals();
			if (material.toonMaterial() != null && toonGeometry == null) {
				MCglTF.logger.warn("Skipping ToonShader for glTF material {} because NORMAL/TANGENT/smooth-normal data is invalid",
					source.getMaterialModel() == null ? "<default>" : source.getMaterialModel().getName());
			}

			return new Primitive(node, mesh, node.getSkinModel(), vertexCount, indices, positions, normals,
				texcoords, backTexcoords, colors, morphPositions, morphNormals, toonGeometry,
				generatedSmoothNormals, skinData.influences(), skinData.joints(), skinData.weights(), material);
		}

		static void smoothGeneratedNormals(List<Primitive> primitives, float smoothNormalCosine) {
			int componentCount = 0;
			boolean needed = false;
			for (Primitive primitive : primitives) {
				if (primitive.toonGeometry != null) {
					componentCount += primitive.positions.length;
					needed |= primitive.generatedSmoothNormals;
				}
			}
			if (!needed) {
				return;
			}

			float[] positions = new float[componentCount];
			float[] normals = new float[componentCount];
			int offset = 0;
			for (Primitive primitive : primitives) {
				if (primitive.toonGeometry == null) {
					continue;
				}
				System.arraycopy(primitive.positions, 0, positions, offset, primitive.positions.length);
				System.arraycopy(primitive.normals, 0, normals, offset, primitive.normals.length);
				offset += primitive.positions.length;
			}

			float[] smoothed = smoothSplitNormals(positions, normals, smoothNormalCosine);
			offset = 0;
			for (Primitive primitive : primitives) {
				if (primitive.toonGeometry == null) {
					continue;
				}
				int length = primitive.positions.length;
				if (primitive.generatedSmoothNormals) {
					float[] tangentSpace = ToonShaderGeometry.toTangentSpace(
						Arrays.copyOfRange(smoothed, offset, offset + length), primitive.normals,
						primitive.toonGeometry.tangents());
					if (tangentSpace != null) {
						primitive.toonGeometry = new ToonShaderGeometry(primitive.toonGeometry.tangents(),
							tangentSpace, primitive.toonGeometry.morphTangents());
					}
				}
				offset += length;
			}
		}

		private static float[] deindex(float[] source, int components, int[] indices) {
			float[] result = new float[indices.length * components];
			for (int index = 0; index < indices.length; index++) {
				System.arraycopy(source, indices[index] * components, result, index * components, components);
			}
			return result;
		}

		private static int[] sequentialIndices(int count) {
			int[] result = new int[count];
			for (int i = 0; i < count; i++) {
				result[i] = i;
			}
			return result;
		}

		static float[] smoothSplitNormals(float[] positions, float[] normals, float smoothNormalCosine) {
			if (normals == null) {
				return null;
			}
			Map<PositionKey, List<Integer>> verticesByPosition = new HashMap<>();
			for (int vertex = 0; vertex < positions.length / 3; vertex++) {
				int offset = vertex * 3;
				PositionKey key = new PositionKey(Float.floatToIntBits(positions[offset]),
					Float.floatToIntBits(positions[offset + 1]), Float.floatToIntBits(positions[offset + 2]));
				verticesByPosition.computeIfAbsent(key, ignored -> new ArrayList<>()).add(vertex);
			}

			float[] smoothed = normals.clone();
			for (List<Integer> group : verticesByPosition.values()) {
				if (group.size() < 2) {
					continue;
				}
				for (int vertex : group) {
					int target = vertex * 3;
					float x = 0.0F;
					float y = 0.0F;
					float z = 0.0F;
					for (int neighbor : group) {
						int source = neighbor * 3;
						float dot = normals[target] * normals[source]
							+ normals[target + 1] * normals[source + 1]
							+ normals[target + 2] * normals[source + 2];
						if (dot >= smoothNormalCosine) {
							x += normals[source];
							y += normals[source + 1];
							z += normals[source + 2];
						}
					}
					float length = (float) Math.sqrt(x * x + y * y + z * z);
					if (length > 1.0E-6F) {
						smoothed[target] = x / length;
						smoothed[target + 1] = y / length;
						smoothed[target + 2] = z / length;
					}
				}
			}
			return smoothed;
		}

		void submit(PoseStack poseStack, SubmitNodeCollector collector, int packedLight, int packedOverlay,
			FrameSnapshots snapshots, boolean shaderModActive, ToonShaderModel.Frame toonFrame, RenderView view) {
			float[] scale = node.getScale();
			if (scale != null && scale[0] == 0.0F && scale[1] == 0.0F && scale[2] == 0.0F) {
				return;
			}
			int[] submittedIndices = indicesFor(view);
			if (submittedIndices.length == 0) {
				return;
			}

			Matrix4f nodeTransform = snapshots.nodeTransform(node);
			SkinPalette palette = skin == null || influences == 0 ? null : snapshots.skinPalette(node, skin);
			float[] currentMorphWeights = morphPositions.length == 0 ? EMPTY_FLOATS : snapshots.morphWeights(node, mesh);
			DeformedGeometry geometry = deform(palette, currentMorphWeights);
			// ponytail: Iris shader packs can overexpose a full-bright lightmap; use scene light until
			// the standard submission API exposes an explicit unlit-material flag.
			int light = material.unlit() && !shaderModActive ? LightCoordsUtil.FULL_BRIGHT : packedLight;

			poseStack.pushPose();
			poseStack.mulPose(nodeTransform);
			int overlay = material.overlay(packedOverlay);
			boolean queueToon = shaderModActive && packedOverlay == MTOON_OVERLAY_REQUEST
				&& material.toonMaterial() != null && toonGeometry != null && geometry.tangents != null;
			collector.submitCustomGeometry(poseStack, material.renderType(shaderModActive),
				(pose, consumer) -> {
					if (queueToon) {
						material.toonMaterial().queue(pose, geometry.positions, geometry.normals, geometry.tangents,
							toonGeometry.smoothNormals(), texcoords, backTexcoords, toonVertexColors, submittedIndices,
							light, toonFrame);
					} else {
						render(pose, consumer, light, overlay, geometry, submittedIndices);
					}
				});
			if (!shaderModActive && packedOverlay == MTOON_OVERLAY_REQUEST && material.outlineRenderType() != null) {
				collector.submitCustomGeometry(poseStack, material.outlineRenderType(),
					(pose, consumer) -> renderOutline(pose, consumer, light, geometry, submittedIndices));
			}
			poseStack.popPose();
		}

		private void render(PoseStack.Pose pose, VertexConsumer consumer, int packedLight, int packedOverlay,
			DeformedGeometry geometry, int[] submittedIndices) {
			Vector3f edgeA = new Vector3f();
			Vector3f edgeB = new Vector3f();
			Vector3f faceNormal = new Vector3f();

			for (int i = 0; i < submittedIndices.length; i += 3) {
				int a = submittedIndices[i];
				int b = submittedIndices[i + 1];
				int c = submittedIndices[i + 2];
				if (normals == null) {
					int pa = a * 3;
					int pb = b * 3;
					int pc = c * 3;
					edgeA.set(geometry.positions[pb], geometry.positions[pb + 1], geometry.positions[pb + 2])
						.sub(geometry.positions[pa], geometry.positions[pa + 1], geometry.positions[pa + 2]);
					edgeB.set(geometry.positions[pc], geometry.positions[pc + 1], geometry.positions[pc + 2])
						.sub(geometry.positions[pa], geometry.positions[pa + 1], geometry.positions[pa + 2]);
					faceNormal.set(edgeA).cross(edgeB);
					if (faceNormal.lengthSquared() > 1.0E-12F) {
						faceNormal.normalize();
					} else {
						faceNormal.set(0.0F, 1.0F, 0.0F);
					}
				}
				Vector3f flatNormal = normals == null ? faceNormal : null;
				emit(consumer, pose, geometry, a, flatNormal, packedLight, packedOverlay);
				emit(consumer, pose, geometry, b, flatNormal, packedLight, packedOverlay);
				emit(consumer, pose, geometry, c, flatNormal, packedLight, packedOverlay);
				// Minecraft's standard entity pipelines use QUADS. Repeating the last
				// vertex preserves the glTF triangle and makes the second quad triangle degenerate.
				emit(consumer, pose, geometry, c, flatNormal, packedLight, packedOverlay);
			}
		}

		private void renderOutline(PoseStack.Pose pose, VertexConsumer consumer, int packedLight,
			DeformedGeometry geometry, int[] submittedIndices) {
			for (int i = 0; i < submittedIndices.length; i += 3) {
				int a = submittedIndices[i];
				int b = submittedIndices[i + 1];
				int c = submittedIndices[i + 2];
				emitOutline(consumer, pose, geometry, a, packedLight);
				emitOutline(consumer, pose, geometry, c, packedLight);
				emitOutline(consumer, pose, geometry, b, packedLight);
				emitOutline(consumer, pose, geometry, b, packedLight);
			}
		}

		private void emitOutline(VertexConsumer consumer, PoseStack.Pose pose, DeformedGeometry geometry, int vertex,
			int packedLight) {
			int position = vertex * 3;
			float nx = geometry.normals[position];
			float ny = geometry.normals[position + 1];
			float nz = geometry.normals[position + 2];
			float width = 0.003F;
			consumer.addVertex(pose, geometry.positions[position] + nx * width,
				geometry.positions[position + 1] + ny * width, geometry.positions[position + 2] + nz * width)
				.setColor(ARGB.color(255, 28, 24, 38))
				.setUv(texcoords[vertex * 2], material.textureV(texcoords[vertex * 2 + 1]))
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(packedLight)
				.setNormal(pose, nx, ny, nz);
		}

		private int[] indicesFor(RenderView view) {
			if (view == FULL_VIEW) {
				return indices;
			}
			return viewIndices.computeIfAbsent(view, this::buildViewIndices);
		}

		private int[] buildViewIndices(RenderView view) {
			if (view.hiddenNodes().contains(node) || view.hiddenMeshes().contains(mesh)) {
				return new int[0];
			}
			if (!view.weightFilteredNodes().contains(node) && !view.weightFilteredMeshes().contains(mesh)) {
				return indices;
			}
			if (view.hiddenJoints().contains(node)) {
				return new int[0];
			}
			if (skin == null || influences == 0) {
				return indices;
			}

			List<NodeModel> skinJoints = skin.getJoints();
			boolean[] hiddenVertices = new boolean[vertexCount];
			for (int vertex = 0; vertex < vertexCount; vertex++) {
				int offset = vertex * influences;
				for (int influence = 0; influence < influences; influence++) {
					int joint = joints[offset + influence];
					if (weights[offset + influence] > 0.0F && joint >= 0 && joint < skinJoints.size()
						&& view.hiddenJoints().contains(skinJoints.get(joint))) {
						hiddenVertices[vertex] = true;
						break;
					}
				}
			}
			return filterTriangles(indices, hiddenVertices);
		}

		private DeformedGeometry deform(SkinPalette palette, float[] currentMorphWeights) {
			if (cachedGeometry != null && Arrays.equals(cachedMorphWeights, currentMorphWeights)
				&& sameSkinMatrices(palette)) {
				return cachedGeometry;
			}

			float[] deformedPositions = new float[vertexCount * 3];
			float[] deformedNormals = new float[vertexCount * 3];
			float[] deformedTangents = toonGeometry == null ? null : new float[vertexCount * 4];
			VertexData vertexData = new VertexData();
			Vector3f transformed = new Vector3f();
			for (int vertex = 0; vertex < vertexCount; vertex++) {
				fillVertex(vertex, vertexData, palette, currentMorphWeights, transformed);
				int offset = vertex * 3;
				deformedPositions[offset] = vertexData.position.x;
				deformedPositions[offset + 1] = vertexData.position.y;
				deformedPositions[offset + 2] = vertexData.position.z;
				deformedNormals[offset] = vertexData.normal.x;
				deformedNormals[offset + 1] = vertexData.normal.y;
				deformedNormals[offset + 2] = vertexData.normal.z;
				if (deformedTangents != null) {
					int tangent = vertex * 4;
					deformedTangents[tangent] = vertexData.tangent.x;
					deformedTangents[tangent + 1] = vertexData.tangent.y;
					deformedTangents[tangent + 2] = vertexData.tangent.z;
					deformedTangents[tangent + 3] = vertexData.tangentSign;
				}
			}

			cachedMorphWeights = currentMorphWeights.clone();
			cachedSkinMatrices = copySkinMatrices(palette);
			cachedGeometry = new DeformedGeometry(deformedPositions, deformedNormals, deformedTangents);
			return cachedGeometry;
		}

		private boolean sameSkinMatrices(SkinPalette palette) {
			if (palette == null) {
				return cachedSkinMatrices == null;
			}
			if (cachedSkinMatrices == null || cachedSkinMatrices.length != palette.positions().length) {
				return false;
			}
			for (int i = 0; i < cachedSkinMatrices.length; i++) {
				if (!cachedSkinMatrices[i].equals(palette.positions()[i])) {
					return false;
				}
			}
			return true;
		}

		private static Matrix4f[] copySkinMatrices(SkinPalette palette) {
			if (palette == null) {
				return null;
			}
			Matrix4f[] copy = new Matrix4f[palette.positions().length];
			for (int i = 0; i < copy.length; i++) {
				copy[i] = new Matrix4f(palette.positions()[i]);
			}
			return copy;
		}

		private void fillVertex(int vertex, VertexData output, SkinPalette palette, float[] currentMorphWeights,
			Vector3f transformed) {
			int p = vertex * 3;
			float x = positions[p];
			float y = positions[p + 1];
			float z = positions[p + 2];
			float nx = normals == null ? 0.0F : normals[p];
			float ny = normals == null ? 1.0F : normals[p + 1];
			float nz = normals == null ? 0.0F : normals[p + 2];
			int tangentOffset = vertex * 4;
			float tx = toonGeometry == null ? 0.0F : toonGeometry.tangents()[tangentOffset];
			float ty = toonGeometry == null ? 0.0F : toonGeometry.tangents()[tangentOffset + 1];
			float tz = toonGeometry == null ? 0.0F : toonGeometry.tangents()[tangentOffset + 2];
			output.tangentSign = toonGeometry == null ? 1.0F : toonGeometry.tangents()[tangentOffset + 3];

			int targetCount = Math.min(currentMorphWeights.length, morphPositions.length);
			for (int target = 0; target < targetCount; target++) {
				float weight = currentMorphWeights[target];
				if (weight == 0.0F) {
					continue;
				}
				float[] targetPositions = morphPositions[target];
				if (targetPositions != null) {
					x += targetPositions[p] * weight;
					y += targetPositions[p + 1] * weight;
					z += targetPositions[p + 2] * weight;
				}
				float[] targetNormals = morphNormals[target];
				if (targetNormals != null) {
					nx += targetNormals[p] * weight;
					ny += targetNormals[p + 1] * weight;
					nz += targetNormals[p + 2] * weight;
				}
				float[] targetTangents = toonGeometry == null ? null : toonGeometry.morphTangents()[target];
				if (targetTangents != null) {
					tx += targetTangents[p] * weight;
					ty += targetTangents[p + 1] * weight;
					tz += targetTangents[p + 2] * weight;
				}
			}

			if (palette == null) {
				output.position.set(x, y, z);
				output.normal.set(nx, ny, nz);
				output.tangent.set(tx, ty, tz);
			} else {
				output.position.zero();
				output.normal.zero();
				output.tangent.zero();
				float totalWeight = 0.0F;
				int influenceOffset = vertex * influences;
				for (int influence = 0; influence < influences; influence++) {
					float weight = weights[influenceOffset + influence];
					int joint = joints[influenceOffset + influence];
					if (weight <= 0.0F || joint < 0 || joint >= palette.positions().length) {
						continue;
					}
					transformed.set(x, y, z);
					palette.positions()[joint].transformPosition(transformed);
					output.position.fma(weight, transformed);
					transformed.set(nx, ny, nz);
					palette.normals()[joint].transform(transformed);
					output.normal.fma(weight, transformed);
					if (toonGeometry != null) {
						transformed.set(tx, ty, tz);
						palette.positions()[joint].transformDirection(transformed);
						output.tangent.fma(weight, transformed);
					}
					totalWeight += weight;
				}
				if (totalWeight <= 1.0E-6F) {
					output.position.set(x, y, z);
					output.normal.set(nx, ny, nz);
					output.tangent.set(tx, ty, tz);
				} else if (totalWeight != 1.0F) {
					output.position.div(totalWeight);
					output.normal.div(totalWeight);
					output.tangent.div(totalWeight);
				}
			}

			if (output.normal.lengthSquared() > 1.0E-12F) {
				output.normal.normalize();
			}
			if (toonGeometry != null) {
				output.tangent.fma(-output.tangent.dot(output.normal), output.normal);
				if (output.tangent.lengthSquared() > 1.0E-12F) {
					output.tangent.normalize();
				}
			}
		}

		private void emit(VertexConsumer consumer, PoseStack.Pose pose, DeformedGeometry geometry, int vertex,
			Vector3f flatNormal, int packedLight, int packedOverlay) {
			int position = vertex * 3;
			int uv = vertex * 2;
			float nx = flatNormal == null ? geometry.normals[position] : flatNormal.x;
			float ny = flatNormal == null ? geometry.normals[position + 1] : flatNormal.y;
			float nz = flatNormal == null ? geometry.normals[position + 2] : flatNormal.z;
			consumer.addVertex(pose, geometry.positions[position], geometry.positions[position + 1],
				geometry.positions[position + 2])
				.setColor(vertexColors[vertex])
				.setUv(texcoords[uv], material.textureV(texcoords[uv + 1]))
				.setOverlay(packedOverlay)
				.setLight(packedLight)
				.setNormal(pose, nx, ny, nz);
		}

		int submittedVertexCount() {
			return indices.length / 3 * 4;
		}

		int sortOrder() {
			return material.renderType().hasBlending() ? 1 : 0;
		}

		String textureKey() {
			return material.texture().toString();
		}
	}

	private record PositionKey(int x, int y, int z) {
	}

	private static final class VertexData {
		final Vector3f position = new Vector3f();
		final Vector3f normal = new Vector3f();
		final Vector3f tangent = new Vector3f();
		float tangentSign;
	}

	private record DeformedGeometry(float[] positions, float[] normals, float[] tangents) {
	}

	private record SkinData(int influences, int[] joints, float[] weights) {
		SkinData deindex(int[] indices) {
			if (influences == 0) {
				return this;
			}
			int[] expandedJoints = new int[indices.length * influences];
			float[] expandedWeights = new float[indices.length * influences];
			for (int index = 0; index < indices.length; index++) {
				System.arraycopy(joints, indices[index] * influences, expandedJoints, index * influences, influences);
				System.arraycopy(weights, indices[index] * influences, expandedWeights, index * influences, influences);
			}
			return new SkinData(influences, expandedJoints, expandedWeights);
		}

		static SkinData create(Map<String, AccessorModel> attributes, int vertexCount, SkinModel skin) {
			if (skin == null || !attributes.containsKey("JOINTS_0") || !attributes.containsKey("WEIGHTS_0")) {
				return new SkinData(0, new int[0], new float[0]);
			}
			boolean secondSet = attributes.containsKey("JOINTS_1") && attributes.containsKey("WEIGHTS_1");
			int influences = secondSet ? 8 : 4;
			int[] joints = new int[vertexCount * influences];
			float[] weights = new float[vertexCount * influences];
			readInfluences(attributes.get("JOINTS_0"), attributes.get("WEIGHTS_0"), vertexCount, 0, influences,
				joints, weights);
			if (secondSet) {
				readInfluences(attributes.get("JOINTS_1"), attributes.get("WEIGHTS_1"), vertexCount, 4, influences,
					joints, weights);
			}
			for (int vertex = 0; vertex < vertexCount; vertex++) {
				int offset = vertex * influences;
				float sum = 0.0F;
				for (int i = 0; i < influences; i++) {
					sum += weights[offset + i];
				}
				if (sum > 1.0E-6F && sum != 1.0F) {
					for (int i = 0; i < influences; i++) {
						weights[offset + i] /= sum;
					}
				}
			}
			return new SkinData(influences, joints, weights);
		}

		private static void readInfluences(AccessorModel jointAccessor, AccessorModel weightAccessor, int vertexCount,
			int destinationComponent, int influences, int[] joints, float[] weights) {
			AccessorData jointData = jointAccessor.getAccessorData();
			AccessorData weightData = weightAccessor.getAccessorData();
			int count = Math.min(vertexCount, Math.min(jointAccessor.getCount(), weightAccessor.getCount()));
			for (int vertex = 0; vertex < count; vertex++) {
				for (int component = 0; component < 4; component++) {
					int destination = vertex * influences + destinationComponent + component;
					joints[destination] = readInt(jointData, vertex, component);
					weights[destination] = readFloat(weightAccessor, weightData, vertex, component);
				}
			}
		}
	}

	private record SkinPalette(Matrix4f[] positions, Matrix3f[] normals) {
		static SkinPalette capture(Matrix4f nodeTransform, SkinModel skin) {
			Matrix4f inverseNode = new Matrix4f(nodeTransform).invert();
			Matrix4f bindShape = new Matrix4f().set(skin.getBindShapeMatrix(null));
			List<NodeModel> jointNodes = skin.getJoints();
			Matrix4f[] positions = new Matrix4f[jointNodes.size()];
			Matrix3f[] normals = new Matrix3f[jointNodes.size()];
			for (int joint = 0; joint < jointNodes.size(); joint++) {
				Matrix4f matrix = new Matrix4f(inverseNode)
					.mul(new Matrix4f().set(jointNodes.get(joint).computeGlobalTransform(null)))
					.mul(new Matrix4f().set(skin.getInverseBindMatrix(joint, null)))
					.mul(bindShape);
				positions[joint] = matrix;
				normals[joint] = new Matrix3f(matrix).invert().transpose();
			}
			return new SkinPalette(positions, normals);
		}
	}

	static record MToonProfile(boolean enabled, int shadeTextureIndex, int shadeTint, int toonControl,
		float shadeShift, float shadingToony, float giEqualization, int normalTextureIndex,
		int emissionTextureIndex, int matcapTextureIndex,
		int rimTextureIndex, int outlineWidthTextureIndex, Vector4f shadeColor, Vector4f emissionColor,
		Vector4f rimColor, Vector4f outlineColor, float rimLightingMix, float rimFresnelPower, float rimLift,
		boolean outline, boolean outlineScreenSpace, float outlineWidth, float outlineDistanceFar,
		float outlineLightingMix, boolean outlineDistanceFade) {
		static final MToonProfile NONE = new MToonProfile(false, -1, 0xFFFFFFFF, 0,
			0.0F, 0.9F, 0.0F, -1, -1, -1, -1, -1, new Vector4f(1.0F), new Vector4f(), new Vector4f(),
			new Vector4f(0.1F, 0.08F, 0.12F, 1.0F), 0.0F, 3.0F, 0.0F, false, false,
			0.0F, 1.0F, 1.0F, false);

		static MToonProfile from(Object value) {
			if (!(value instanceof Map<?, ?> material) || !"VRM/MToon".equals(material.get("shader"))) {
				return NONE;
			}
			int textureIndex = integer(mapValue(material, "textureProperties", "_ShadeTexture"), -1);
			float shadeShift = number(mapValue(material, "floatProperties", "_ShadeShift"), 0.0F);
			float shadeToony = number(mapValue(material, "floatProperties", "_ShadeToony"), 0.5F);
			float rimFresnelPower = number(mapValue(material, "floatProperties", "_RimFresnelPower"), 3.0F);
			Vector4f shadeColor = color(mapValue(material, "vectorProperties", "_ShadeColor"), new Vector4f(1.0F));
			int outlineMode = integer(mapValue(material, "floatProperties", "_OutlineWidthMode"), 0);
			return new MToonProfile(true, textureIndex, packed(shadeColor),
				toonControl(shadeShift, shadeToony, rimFresnelPower), shadeShift, shadeToony,
				number(mapValue(material, "floatProperties", "_IndirectLightIntensity"), 0.0F),
				integer(mapValue(material, "textureProperties", "_BumpMap"), -1),
				integer(mapValue(material, "textureProperties", "_EmissionMap"), -1),
				integer(mapValue(material, "textureProperties", "_SphereAdd"), -1),
				integer(mapValue(material, "textureProperties", "_RimTexture"), -1),
				integer(mapValue(material, "textureProperties", "_OutlineWidthTexture"), -1), shadeColor,
				color(mapValue(material, "vectorProperties", "_EmissionColor"), new Vector4f()),
				color(mapValue(material, "vectorProperties", "_RimColor"), new Vector4f()),
				color(mapValue(material, "vectorProperties", "_OutlineColor"),
					new Vector4f(0.1F, 0.08F, 0.12F, 1.0F)),
				number(mapValue(material, "floatProperties", "_RimLightingMix"), 0.0F), rimFresnelPower,
				number(mapValue(material, "floatProperties", "_RimLift"), 0.0F), outlineMode != 0,
				outlineMode == 2, number(mapValue(material, "floatProperties", "_OutlineWidth"), 0.0F),
				number(mapValue(material, "floatProperties", "_OutlineScaledMaxDistance"), 1.0F),
				number(mapValue(material, "floatProperties", "_OutlineLightingMix"), 1.0F), true);
		}

		static MToonProfile fromVrm1(Map<?, ?> material) {
			float shadeShift = number(material.get("shadingShiftFactor"), 0.0F);
			float shadeToony = number(material.get("shadingToonyFactor"), 0.9F);
			float rimFresnelPower = number(material.get("parametricRimFresnelPowerFactor"), 5.0F);
			Vector4f shadeColor = color(material.get("shadeColorFactor"), new Vector4f(1.0F));
			String outlineMode = material.get("outlineWidthMode") instanceof String value ? value : "none";
			return new MToonProfile(true, textureInfo(material.get("shadeMultiplyTexture")), packed(shadeColor),
				toonControl(shadeShift, shadeToony, rimFresnelPower), shadeShift, shadeToony,
				number(material.get("giEqualizationFactor"), 0.9F), -1, -1,
				textureInfo(material.get("matcapTexture")),
				textureInfo(material.get("rimMultiplyTexture")), textureInfo(material.get("outlineWidthMultiplyTexture")),
				shadeColor, new Vector4f(),
				color(material.get("parametricRimColorFactor"), new Vector4f()),
				color(material.get("outlineColorFactor"), new Vector4f(0.1F, 0.08F, 0.12F, 1.0F)),
				number(material.get("rimLightingMixFactor"), 0.0F), rimFresnelPower,
				number(material.get("parametricRimLiftFactor"), 0.0F), !"none".equals(outlineMode),
				"screenCoordinates".equals(outlineMode), number(material.get("outlineWidthFactor"), 0.0F) * 100.0F,
				1.0F, number(material.get("outlineLightingMixFactor"), 1.0F), false);
		}

		private static Object mapValue(Map<?, ?> source, String key, String nestedKey) {
			Object nested = source.get(key);
			return nested instanceof Map<?, ?> map ? map.get(nestedKey) : null;
		}

		private static int integer(Object value, int fallback) {
			return value instanceof Number number ? number.intValue() : fallback;
		}

		private static float number(Object value, float fallback) {
			return value instanceof Number number ? number.floatValue() : fallback;
		}

		private static int textureInfo(Object value) {
			return value instanceof Map<?, ?> texture ? integer(texture.get("index"), -1) : -1;
		}

		private static int toonControl(float shadeShift, float shadeToony, float rimFresnelPower) {
			int toony = Math.round(Math.max(0.0F, Math.min(1.0F, shadeToony)) * 7.0F);
			int shift = Math.round(Math.max(-1.0F, Math.min(1.0F, shadeShift)) * 3.0F) + 3;
			int rim = rimFresnelPower < 1.5F ? 0 : rimFresnelPower < 3.0F ? 1
				: rimFresnelPower < 6.0F ? 2 : 3;
			return (toony << 5) | (shift << 2) | rim;
		}

		int overlay() {
			return enabled ? Math.round((toonControl >>> 5 & 7) * 3.0F / 7.0F) : 3;
		}

		private static Vector4f color(Object value, Vector4f fallback) {
			if (!(value instanceof List<?> color) || color.size() < 3) {
				return new Vector4f(fallback);
			}
			return new Vector4f(component(color, 0, fallback.x), component(color, 1, fallback.y),
				component(color, 2, fallback.z), component(color, 3, fallback.w));
		}

		private static float component(List<?> values, int index, float fallback) {
			return index < values.size() && values.get(index) instanceof Number number
				? Math.max(0.0F, Math.min(1.0F, number.floatValue())) : fallback;
		}

		private static int packed(Vector4f color) {
			return ARGB.color(toChannel(color.w), toChannel(color.x), toChannel(color.y), toChannel(color.z));
		}
	}

	private record PreparedMaterial(Identifier texture, RenderType renderType, RenderType shaderRenderType,
		RenderType outlineRenderType,
		float[] colorFactor, boolean unlit, int mtoonOverlay,
		int texCoordSet, MToonAtlas mtoonAtlas, ToonShaderMaterial toonMaterial) {
		RenderType renderType(boolean shaderModActive) {
			return shaderModActive ? shaderRenderType : renderType;
		}

		int overlay(int requested) {
			if (requested != MTOON_OVERLAY_REQUEST) {
				return requested;
			}
			if (mtoonAtlas == null) {
				return OverlayTexture.NO_OVERLAY;
			}
			return (12 + mtoonOverlay) << 16;
		}

		float textureV(float v) {
			if (mtoonAtlas == null) {
				return v;
			}
			float normalized = mtoonAtlas.repeatV() ? v - (float) Math.floor(v) : Math.max(0.0F, Math.min(1.0F, v));
			return mtoonAtlas.vOffset() + normalized * mtoonAtlas.vScale();
		}

		static PreparedMaterial create(MeshPrimitiveModel primitive, MaterialModel source, TextureRegistry textures,
			MToonProfile mtoon,
			ToonShaderModel toonShader) {
			TextureModel baseTexture = null;
			TextureModel normalTexture = null;
			TextureModel emissionTexture = null;
			float normalScale = 1.0F;
			float[] colorFactor = new float[] {1.0F, 1.0F, 1.0F, 1.0F};
			Vector4f emissionColor = new Vector4f(mtoon.emissionColor());
			AlphaMode alphaMode = AlphaMode.OPAQUE;
			float alphaCutoff = 0.5F;
			boolean doubleSided = false;
			int texCoordSet = 0;
			boolean unlit = source != null && source.getExtensions() != null
				&& source.getExtensions().containsKey("KHR_materials_unlit");
			if (source instanceof MaterialModelV2 material) {
				baseTexture = material.getBaseColorTexture();
				normalTexture = material.getNormalTexture();
				normalScale = material.getNormalScale();
				emissionTexture = material.getEmissiveTexture();
				colorFactor = material.getBaseColorFactor().clone();
				float[] factor = material.getEmissiveFactor();
				if (factor != null && factor.length >= 3) {
					emissionColor.set(factor[0], factor[1], factor[2], 1.0F);
				}
				alphaMode = material.getAlphaMode();
				alphaCutoff = material.getAlphaCutoff();
				doubleSided = material.isDoubleSided();
				texCoordSet = material.getBaseColorTexcoord() == null ? 0 : material.getBaseColorTexcoord();
			}

			AlphaPolicy policy = switch (alphaMode) {
				case OPAQUE -> doubleSided || unlit ? AlphaPolicy.OPAQUE : AlphaPolicy.NONE;
				case MASK -> new AlphaPolicy(Math.max(0, Math.min(255,
					Math.round(alphaCutoff / Math.max(colorFactor[3], 1.0E-6F) * 255.0F))), false);
				case BLEND -> AlphaPolicy.NONE;
			};
			TextureModel shadeTexture = mtoon.enabled() && mtoon.shadeTextureIndex() >= 0
				? textures.textureAt(mtoon.shadeTextureIndex()) : baseTexture;
			MToonAtlas mtoonAtlas = mtoon.enabled()
				? textures.resolveMToonAtlas(baseTexture, shadeTexture, policy, mtoon.shadeTint(), mtoon.toonControl())
				: null;
			Identifier texture = mtoonAtlas == null ? textures.resolve(baseTexture, policy) : mtoonAtlas.texture();
			RenderType shaderRenderType = switch (alphaMode) {
				case OPAQUE -> doubleSided ? RenderTypes.entityCutout(texture) : RenderTypes.entitySolid(texture);
				case MASK -> doubleSided ? RenderTypes.entityCutout(texture) : RenderTypes.entityCutoutCull(texture);
				case BLEND -> RenderTypes.entityTranslucent(texture);
			};
			RenderType outlineRenderType = switch (alphaMode) {
				case OPAQUE -> RenderTypes.entitySolid(texture);
				case MASK -> RenderTypes.entityCutoutCull(texture);
				case BLEND -> null;
			};
			RenderType renderType;
			if (mtoon.enabled()) {
				renderType = MToonRenderTypes.create(texture, alphaMode == AlphaMode.MASK,
					alphaMode == AlphaMode.BLEND);
			} else {
				renderType = shaderRenderType;
			}
			ToonShaderMaterial toonMaterial = toonShader.material(primitive, source, textures, mtoon,
				new ToonShaderMaterial.Inputs(baseTexture, shadeTexture, normalTexture, emissionTexture,
					policy, alphaMode, alphaCutoff, doubleSided, new Vector4f(colorFactor[0], colorFactor[1],
						colorFactor[2], colorFactor[3]), emissionColor, normalScale));
			return new PreparedMaterial(texture, renderType, shaderRenderType, outlineRenderType, colorFactor, unlit,
				mtoon.overlay(), texCoordSet, mtoonAtlas, toonMaterial);
		}
	}

	private record MToonAtlas(Identifier texture, float vScale, float vOffset, boolean repeatV) {
	}

	static record AlphaPolicy(int cutoff, boolean forceOpaque) {
		static final AlphaPolicy NONE = new AlphaPolicy(-1, false);
		static final AlphaPolicy OPAQUE = new AlphaPolicy(-1, true);
	}

	static final class TextureRegistry {
		private final List<Runnable> cleanup;
		private final int modelId;
		private final TextureManager textureManager = Minecraft.getInstance().getTextureManager();
		private final List<TextureModel> sourceTextures;
		private final Map<TextureKey, Identifier> textures = new HashMap<>();
		private final Map<Path, Identifier> externalTextures = new HashMap<>();
		private final Map<MToonAtlasKey, MToonAtlas> mtoonAtlases = new HashMap<>();
		private int nextTextureId;
		private Identifier white;
		private Identifier flatNormal;

		TextureRegistry(List<Runnable> cleanup, int modelId, GltfModel gltfModel) {
			this.cleanup = cleanup;
			this.modelId = modelId;
			this.sourceTextures = gltfModel.getTextureModels();
		}

		Identifier resolve(TextureModel texture, AlphaPolicy policy) {
			TextureKey key = new TextureKey(texture, policy);
			Identifier existing = textures.get(key);
			if (existing != null) {
				return existing;
			}

			Identifier identifier = Identifier.fromNamespaceAndPath(MCglTF.MODID,
				"generated/" + modelId + "/texture_" + nextTextureId++ + ".png");
			NativeImage image = readImage(texture);
			applyAlphaPolicy(image, policy);
			DynamicTexture dynamicTexture = new GltfTexture(identifier::toString, image, texture);
			textureManager.register(identifier, dynamicTexture);
			cleanup.add(() -> textureManager.release(identifier));
			textures.put(key, identifier);
			return identifier;
		}

		Identifier resolve(TextureModel texture, AlphaPolicy policy, Identifier fallback) {
			return texture == null ? fallback : resolve(texture, policy);
		}

		Identifier resolve(Path path, Identifier fallback) {
			if (path == null) {
				return fallback;
			}
			return externalTextures.computeIfAbsent(path, key -> {
				try (var input = Files.newInputStream(key)) {
					return register(NativeImage.read(input), null, false);
				} catch (IOException exception) {
					throw new IllegalArgumentException("Could not decode external texture " + key.getFileName(), exception);
				}
			});
		}

		Identifier white() {
			if (white == null) {
				white = solid(0xFFFFFFFF);
			}
			return white;
		}

		Identifier flatNormal() {
			if (flatNormal == null) {
				flatNormal = solid(0xFFFF8080);
			}
			return flatNormal;
		}

		Identifier solid(int pixel) {
			NativeImage image = new NativeImage(1, 1, false);
			image.setPixel(0, 0, pixel);
			return register(image, null, true);
		}

		Identifier register(NativeImage image, TextureModel source, boolean clampV) {
			Identifier identifier = Identifier.fromNamespaceAndPath(MCglTF.MODID,
				"generated/" + modelId + "/texture_" + nextTextureId++ + ".png");
			textureManager.register(identifier, new GltfTexture(identifier::toString, image, source, clampV));
			cleanup.add(() -> textureManager.release(identifier));
			return identifier;
		}

		MToonAtlas resolveMToonAtlas(TextureModel baseTexture, TextureModel shadeTexture, AlphaPolicy policy,
			int shadeTint, int toonControl) {
			MToonAtlasKey key = new MToonAtlasKey(baseTexture, shadeTexture, policy, shadeTint, toonControl);
			MToonAtlas existing = mtoonAtlases.get(key);
			if (existing != null) {
				return existing;
			}

			NativeImage base = readImage(baseTexture);
			NativeImage shade = readImage(shadeTexture);
			applyAlphaPolicy(base, policy);
			applyTint(shade, shadeTint);
			applyToonControl(shade, toonControl);
			int halfHeight = Math.max(base.getHeight(), shade.getHeight());
			NativeImage image = stackMToonAtlas(base, shade);
			base.close();
			shade.close();

			Identifier identifier = Identifier.fromNamespaceAndPath(MCglTF.MODID,
				"generated/" + modelId + "/texture_" + nextTextureId++ + ".png");
			DynamicTexture dynamicTexture = new GltfTexture(identifier::toString, image, baseTexture, true);
			textureManager.register(identifier, dynamicTexture);
			cleanup.add(() -> textureManager.release(identifier));
			boolean repeatV = baseTexture == null
				|| !Integer.valueOf(GL_CLAMP_TO_EDGE).equals(baseTexture.getWrapT());
			MToonAtlas atlas = new MToonAtlas(identifier, (halfHeight - 1.0F) / (halfHeight * 2.0F),
				0.5F / (halfHeight * 2.0F), repeatV);
			mtoonAtlases.put(key, atlas);
			return atlas;
		}

		TextureModel textureAt(int index) {
			return index >= 0 && index < sourceTextures.size() ? sourceTextures.get(index) : null;
		}

		private static NativeImage readImage(TextureModel texture) {
			if (texture == null || texture.getImageModel() == null || texture.getImageModel().getImageData() == null) {
				NativeImage image = new NativeImage(1, 1, false);
				image.setPixel(0, 0, 0xFFFFFFFF);
				return image;
			}
			ByteBuffer data = texture.getImageModel().getImageData().duplicate();
			try {
				return NativeImage.read(data);
			} catch (IOException exception) {
				MCglTF.logger.warn("Could not decode glTF texture", exception);
				NativeImage image = new NativeImage(1, 1, false);
				image.setPixel(0, 0, 0xFFFF00FF);
				return image;
			}
		}

		private static void applyToonControl(NativeImage image, int control) {
			for (int y = 0; y < image.getHeight(); y++) {
				for (int x = 0; x < image.getWidth(); x++) {
					image.setPixel(x, y, (image.getPixel(x, y) & 0x00FFFFFF) | (control << 24));
				}
			}
		}

		private static void applyAlphaPolicy(NativeImage image, AlphaPolicy policy) {
			if (!policy.forceOpaque() && policy.cutoff() < 0) {
				return;
			}
			for (int y = 0; y < image.getHeight(); y++) {
				for (int x = 0; x < image.getWidth(); x++) {
					int pixel = image.getPixel(x, y);
					int alpha = policy.forceOpaque() ? 255 : ((pixel >>> 24) & 255) >= policy.cutoff() ? 255 : 0;
					image.setPixel(x, y, (pixel & 0x00FFFFFF) | (alpha << 24));
				}
			}
		}

		private static void applyTint(NativeImage image, int tint) {
			if (tint == 0xFFFFFFFF) {
				return;
			}
			for (int y = 0; y < image.getHeight(); y++) {
				for (int x = 0; x < image.getWidth(); x++) {
					int pixel = image.getPixel(x, y);
					image.setPixel(x, y, ARGB.color(ARGB.alpha(pixel),
						ARGB.red(pixel) * ARGB.red(tint) / 255,
						ARGB.green(pixel) * ARGB.green(tint) / 255,
						ARGB.blue(pixel) * ARGB.blue(tint) / 255));
				}
			}
		}
	}

	static NativeImage stackMToonAtlas(NativeImage base, NativeImage shade) {
		int width = Math.max(base.getWidth(), shade.getWidth());
		int halfHeight = Math.max(base.getHeight(), shade.getHeight());
		NativeImage atlas = new NativeImage(width, halfHeight * 2, false);
		copyScaled(base, atlas, 0, width, halfHeight);
		copyScaled(shade, atlas, halfHeight, width, halfHeight);
		return atlas;
	}

	private static void copyScaled(NativeImage source, NativeImage target, int targetY, int width, int height) {
		for (int y = 0; y < height; y++) {
			int sourceY = y * source.getHeight() / height;
			for (int x = 0; x < width; x++) {
				int sourceX = x * source.getWidth() / width;
				target.setPixel(x, targetY + y, source.getPixel(sourceX, sourceY));
			}
		}
	}

	private static final class GltfTexture extends DynamicTexture {
		GltfTexture(java.util.function.Supplier<String> label, NativeImage image, TextureModel source) {
			this(label, image, source, false);
		}

		GltfTexture(java.util.function.Supplier<String> label, NativeImage image, TextureModel source,
			boolean clampV) {
			super(label, image);
			AddressMode wrapS = source != null && Integer.valueOf(GL_CLAMP_TO_EDGE).equals(source.getWrapS())
				? AddressMode.CLAMP_TO_EDGE : AddressMode.REPEAT;
			AddressMode wrapT = clampV || source != null && Integer.valueOf(GL_CLAMP_TO_EDGE).equals(source.getWrapT())
				? AddressMode.CLAMP_TO_EDGE : AddressMode.REPEAT;
			FilterMode min = source == null ? FilterMode.LINEAR : filter(source.getMinFilter());
			FilterMode mag = source != null && Integer.valueOf(GL_NEAREST).equals(source.getMagFilter())
				? FilterMode.NEAREST : FilterMode.LINEAR;
			sampler = RenderSystem.getSamplerCache().getSampler(wrapS, wrapT, min, mag, false);
		}

		private static FilterMode filter(Integer value) {
			return Integer.valueOf(GL_NEAREST).equals(value)
				|| Integer.valueOf(GL_NEAREST_MIPMAP_NEAREST).equals(value)
				|| Integer.valueOf(GL_NEAREST_MIPMAP_LINEAR).equals(value)
				? FilterMode.NEAREST : FilterMode.LINEAR;
		}
	}

	private record TextureKey(TextureModel texture, AlphaPolicy alphaPolicy) {
	}

	private record MToonAtlasKey(TextureModel base, TextureModel shade, AlphaPolicy alphaPolicy, int shadeTint,
		int toonControl) {
	}

	static int[] triangulate(MeshPrimitiveModel primitive, int vertexCount) {
		int[] source;
		if (primitive.getIndices() == null) {
			source = new int[vertexCount];
			for (int i = 0; i < source.length; i++) {
				source[i] = i;
			}
		} else {
			source = AccessorDataUtils.readInts(primitive.getIndices().getAccessorData());
		}

		List<Integer> triangles = new ArrayList<>();
		switch (primitive.getMode()) {
			case GL_TRIANGLES -> {
				for (int i = 0; i + 2 < source.length; i += 3) {
					addTriangle(triangles, source[i], source[i + 1], source[i + 2], vertexCount);
				}
			}
			case GL_TRIANGLE_STRIP -> {
				for (int i = 0; i + 2 < source.length; i++) {
					if ((i & 1) == 0) {
						addTriangle(triangles, source[i], source[i + 1], source[i + 2], vertexCount);
					} else {
						addTriangle(triangles, source[i + 1], source[i], source[i + 2], vertexCount);
					}
				}
			}
			case GL_TRIANGLE_FAN -> {
				for (int i = 1; i + 1 < source.length; i++) {
					addTriangle(triangles, source[0], source[i], source[i + 1], vertexCount);
				}
			}
			default -> {
				return new int[0];
			}
		}
		int[] result = new int[triangles.size()];
		for (int i = 0; i < result.length; i++) {
			result[i] = triangles.get(i);
		}
		return result;
	}

	private static void addTriangle(List<Integer> output, int a, int b, int c, int vertexCount) {
		if (a < 0 || b < 0 || c < 0 || a >= vertexCount || b >= vertexCount || c >= vertexCount
			|| a == b || b == c || c == a) {
			return;
		}
		output.add(a);
		output.add(b);
		output.add(c);
	}

	static float[] readVectors(AccessorModel accessor, int expectedCount, int components, float defaultValue) {
		float[] output = new float[expectedCount * components];
		if (defaultValue != 0.0F) {
			java.util.Arrays.fill(output, defaultValue);
		}
		AccessorData data = accessor.getAccessorData();
		int count = Math.min(expectedCount, accessor.getCount());
		int sourceComponents = Math.min(components, data.getNumComponentsPerElement());
		for (int element = 0; element < count; element++) {
			for (int component = 0; component < sourceComponents; component++) {
				output[element * components + component] = readFloat(accessor, data, element, component);
			}
		}
		return output;
	}

	private static float[] readColors(AccessorModel accessor, int vertexCount) {
		float[] output = new float[vertexCount * 4];
		for (int vertex = 0; vertex < vertexCount; vertex++) {
			output[vertex * 4] = 1.0F;
			output[vertex * 4 + 1] = 1.0F;
			output[vertex * 4 + 2] = 1.0F;
			output[vertex * 4 + 3] = 1.0F;
		}
		if (accessor == null) {
			return output;
		}
		AccessorData data = accessor.getAccessorData();
		int count = Math.min(vertexCount, accessor.getCount());
		int components = Math.min(4, data.getNumComponentsPerElement());
		for (int vertex = 0; vertex < count; vertex++) {
			for (int component = 0; component < components; component++) {
				output[vertex * 4 + component] = readFloat(accessor, data, vertex, component);
			}
		}
		return output;
	}

	private static float readFloat(AccessorModel accessor, AccessorData data, int element, int component) {
		if (data instanceof AccessorFloatData floats) {
			return floats.get(element, component);
		}
		if (data instanceof AccessorByteData bytes) {
			return accessor.isNormalized() ? bytes.getFloat(element, component) : bytes.getInt(element, component);
		}
		if (data instanceof AccessorShortData shorts) {
			return accessor.isNormalized() ? shorts.getFloat(element, component) : shorts.getInt(element, component);
		}
		if (data instanceof AccessorIntData ints) {
			return accessor.isNormalized() ? ints.getFloat(element, component) : ints.get(element, component);
		}
		throw new IllegalArgumentException("Unsupported accessor data " + data.getClass().getName());
	}

	private static int readInt(AccessorData data, int element, int component) {
		if (data instanceof AccessorByteData bytes) {
			return bytes.getInt(element, component);
		}
		if (data instanceof AccessorShortData shorts) {
			return shorts.getInt(element, component);
		}
		if (data instanceof AccessorIntData ints) {
			return ints.get(element, component);
		}
		if (data instanceof AccessorFloatData floats) {
			return Math.round(floats.get(element, component));
		}
		throw new IllegalArgumentException("Unsupported accessor data " + data.getClass().getName());
	}

	private static int toChannel(float value) {
		return Math.max(0, Math.min(255, Math.round(value * 255.0F)));
	}
}
