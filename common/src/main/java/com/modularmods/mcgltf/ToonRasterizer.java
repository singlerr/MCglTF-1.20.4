package com.modularmods.mcgltf;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_TRIANGLE_FAN;
import static org.lwjgl.opengl.GL11.GL_TRIANGLE_STRIP;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;

import de.javagl.jgltf.model.AccessorModel;
import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.MaterialModel;
import de.javagl.jgltf.model.MeshModel;
import de.javagl.jgltf.model.MeshPrimitiveModel;
import de.javagl.jgltf.model.SkinModel;

/**
 * Rasterises a material's triangles into its own texture space, recording where each
 * texel sits in the model, which way it faces, and how much of it the head carries.
 *
 * <p>Deriving toon data means answering questions about geometry in the layout of the
 * texture it will be sampled with, which is what this provides. Where several triangles
 * share a texel the one facing the way the character does wins, so that a model authored
 * to either VRM version is sampled on its front rather than through the back of its head.
 */
final class ToonRasterizer {
	private ToonRasterizer() {
	}

	/**
	 * Per-texel geometry in row-major order, with {@code v = 0} at row zero to match how
	 * the shader samples these sheets.
	 */
	record Sample(int width, int height, boolean[] coverage, float[] positions, float[] normals,
		float[] headWeights) {

		boolean covered(int texel) {
			return coverage[texel];
		}

		float position(int texel, int component) {
			return positions[texel * 3 + component];
		}

		float normal(int texel, int component) {
			return normals[texel * 3 + component];
		}
	}

	static Sample rasterize(GltfModel model, ToonVrmData vrm, int materialIndex, int width,
		int height, boolean allowEmpty) {
		int texels = width * height;
		float[] best = new float[texels];
		Arrays.fill(best, Float.NEGATIVE_INFINITY);
		Sample sample = new Sample(width, height, new boolean[texels], new float[texels * 3],
			new float[texels * 3], new float[texels]);

		float forwardSign = vrm.headForward().z;
		Map<MeshModel, SkinModel> skins = vrm.meshSkins();
		List<MaterialModel> materials = model.getMaterialModels();
		for (MeshModel mesh : model.getMeshModels()) {
			Set<Integer> headSlots = vrm.headJointSlots(skins.get(mesh));
			for (MeshPrimitiveModel primitive : mesh.getMeshPrimitiveModels()) {
				Map<String, AccessorModel> attributes = primitive.getAttributes();
				AccessorModel uvAccessor = attributes.get("TEXCOORD_0");
				AccessorModel positionAccessor = attributes.get("POSITION");
				AccessorModel normalAccessor = attributes.get("NORMAL");
				if (materials.indexOf(primitive.getMaterialModel()) != materialIndex
					|| uvAccessor == null || positionAccessor == null || normalAccessor == null) {
					continue;
				}
				int vertexCount = positionAccessor.getCount();
				float[] uv = RenderedGltfModel.readVectors(uvAccessor, vertexCount, 2, 0.0F);
				float[] positions = RenderedGltfModel.readVectors(positionAccessor, vertexCount, 3, 0.0F);
				float[] normals = RenderedGltfModel.readVectors(normalAccessor, vertexCount, 3, 0.0F);
				float[] headWeights = ToonMaterialClassifier.headWeights(primitive, vertexCount, headSlots);
				int[] triangles = triangleIndices(primitive, vertexCount);
				for (int triangle = 0; triangle + 2 < triangles.length; triangle += 3) {
					shade(sample, best, uv, positions, normals, headWeights, forwardSign,
						triangles[triangle], triangles[triangle + 1], triangles[triangle + 2]);
				}
			}
		}
		boolean covered = false;
		for (int texel = 0; texel < texels; texel++) {
			sample.coverage()[texel] = best[texel] > Float.NEGATIVE_INFINITY;
			covered |= sample.coverage()[texel];
		}
		if (!covered && !allowEmpty) {
			throw new IllegalArgumentException(
				"material " + materialIndex + " has no rasterizable UV triangles");
		}
		return sample;
	}

	private static void shade(Sample sample, float[] best, float[] uv, float[] positions,
		float[] normals, float[] headWeights, float forwardSign, int a, int b, int c) {
		float x0 = uv[a * 2] * (sample.width() - 1);
		float y0 = uv[a * 2 + 1] * (sample.height() - 1);
		float x1 = uv[b * 2] * (sample.width() - 1);
		float y1 = uv[b * 2 + 1] * (sample.height() - 1);
		float x2 = uv[c * 2] * (sample.width() - 1);
		float y2 = uv[c * 2 + 1] * (sample.height() - 1);
		int minimumX = Math.max((int)Math.floor(Math.min(x0, Math.min(x1, x2))), 0);
		int minimumY = Math.max((int)Math.floor(Math.min(y0, Math.min(y1, y2))), 0);
		int maximumX = Math.min((int)Math.ceil(Math.max(x0, Math.max(x1, x2))), sample.width() - 1);
		int maximumY = Math.min((int)Math.ceil(Math.max(y0, Math.max(y1, y2))), sample.height() - 1);
		if (maximumX < minimumX || maximumY < minimumY) {
			return;
		}
		float denominator = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
		if (Math.abs(denominator) < 1.0E-8F) {
			return;
		}
		Vector3f normal = new Vector3f();
		for (int y = minimumY; y <= maximumY; y++) {
			float gridY = y + 0.5F;
			for (int x = minimumX; x <= maximumX; x++) {
				float gridX = x + 0.5F;
				float weightA = ((y1 - y2) * (gridX - x2) + (x2 - x1) * (gridY - y2)) / denominator;
				float weightB = ((y2 - y0) * (gridX - x2) + (x0 - x2) * (gridY - y2)) / denominator;
				float weightC = 1.0F - weightA - weightB;
				if (weightA < -1.0E-4F || weightB < -1.0E-4F || weightC < -1.0E-4F) {
					continue;
				}
				normal.set(
					weightA * normals[a * 3] + weightB * normals[b * 3] + weightC * normals[c * 3],
					weightA * normals[a * 3 + 1] + weightB * normals[b * 3 + 1] + weightC * normals[c * 3 + 1],
					weightA * normals[a * 3 + 2] + weightB * normals[b * 3 + 2] + weightC * normals[c * 3 + 2]);
				normal.div(Math.max(normal.length(), 1.0E-8F));
				float score = normal.z * forwardSign;
				int texel = y * sample.width() + x;
				if (score <= best[texel]) {
					continue;
				}
				best[texel] = score;
				sample.positions()[texel * 3] = weightA * positions[a * 3] + weightB * positions[b * 3]
					+ weightC * positions[c * 3];
				sample.positions()[texel * 3 + 1] = weightA * positions[a * 3 + 1]
					+ weightB * positions[b * 3 + 1] + weightC * positions[c * 3 + 1];
				sample.positions()[texel * 3 + 2] = weightA * positions[a * 3 + 2]
					+ weightB * positions[b * 3 + 2] + weightC * positions[c * 3 + 2];
				sample.normals()[texel * 3] = normal.x;
				sample.normals()[texel * 3 + 1] = normal.y;
				sample.normals()[texel * 3 + 2] = normal.z;
				sample.headWeights()[texel] = weightA * headWeights[a] + weightB * headWeights[b]
					+ weightC * headWeights[c];
			}
		}
	}

	/**
	 * Triangle corners of a primitive, sharing the renderer's triangulation so that
	 * derived data describes the surface that is actually drawn. Rendering may skip a
	 * primitive it cannot draw as triangles, but derivation must not: an unreadable
	 * primitive would quietly leave part of the model out of the sheets it feeds.
	 */
	static int[] triangleIndices(MeshPrimitiveModel primitive, int vertexCount) {
		int mode = primitive.getMode();
		if (mode != GL_TRIANGLES && mode != GL_TRIANGLE_STRIP && mode != GL_TRIANGLE_FAN) {
			throw new IllegalArgumentException("Unsupported primitive mode " + mode);
		}
		return RenderedGltfModel.triangulate(primitive, vertexCount);
	}
}
