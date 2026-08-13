package com.modularmods.mcgltf;

import org.joml.Vector3f;

import com.jme3.util.mikktspace.MikkTSpaceContext;
import com.jme3.util.mikktspace.MikktspaceTangentGenerator;

record ToonShaderGeometry(float[] tangents, float[] smoothNormals, float[][] morphTangents) {
	static ToonShaderGeometry create(float[] positions, float[] normals, float[] texcoords, int[] indices,
		float[] sourceTangents, float[] encodedSmoothNormals, float[][] morphTangents,
		boolean requiresMappedTangents, boolean allowGeneratedSmoothNormals, float smoothNormalCosine) {
		if (normals == null) {
			return null;
		}

		float[] tangents = sourceTangents == null
			? generateTangents(positions, normals, texcoords, indices) : sourceTangents;
		boolean validTangents = normalizeTangents(normals, tangents);
		if (!validTangents && sourceTangents == null && !requiresMappedTangents && encodedSmoothNormals == null) {
			tangents = orthogonalTangents(normals);
			validTangents = normalizeTangents(normals, tangents);
		}
		if (!validTangents) {
			return null;
		}

		float[] smoothNormals = encodedSmoothNormals != null ? decodeSmoothNormals(encodedSmoothNormals)
			: allowGeneratedSmoothNormals
				? toTangentSpace(RenderedGltfModel.Primitive.smoothSplitNormals(
					positions, normals, smoothNormalCosine), normals, tangents)
				: null;
		if (smoothNormals == null) {
			return null;
		}
		return new ToonShaderGeometry(tangents, smoothNormals, morphTangents);
	}

	private static float[] orthogonalTangents(float[] normals) {
		float[] tangents = new float[normals.length / 3 * 4];
		for (int vertex = 0; vertex < normals.length / 3; vertex++) {
			int n = vertex * 3;
			int t = vertex * 4;
			float x = normals[n];
			float y = normals[n + 1];
			float z = normals[n + 2];
			if (Math.abs(x) < Math.abs(z)) {
				tangents[t + 1] = -z;
				tangents[t + 2] = y;
			} else {
				tangents[t] = -y;
				tangents[t + 1] = x;
			}
			tangents[t + 3] = 1.0F;
		}
		return tangents;
	}

	static float[] generateTangents(float[] positions, float[] normals, float[] texcoords, int[] indices) {
		TangentContext context = new TangentContext(positions, normals, texcoords, indices);
		return MikktspaceTangentGenerator.genTangSpaceDefault(context) && context.complete()
			? context.tangents : null;
	}

	static float[] decodeSmoothNormals(float[] encoded) {
		float[] result = encoded.clone();
		for (int i = 0; i < result.length; i += 3) {
			float x = result[i] * 2.0F - 1.0F;
			float y = result[i + 1] * 2.0F - 1.0F;
			float z = result[i + 2] * 2.0F - 1.0F;
			float length = (float)Math.sqrt(x * x + y * y + z * z);
			if (!Float.isFinite(length) || length <= 1.0E-6F) {
				return null;
			}
			result[i] = x / length;
			result[i + 1] = y / length;
			result[i + 2] = z / length;
		}
		return result;
	}

	static float[] toTangentSpace(float[] smoothObjectNormals, float[] normals, float[] tangents) {
		if (smoothObjectNormals == null || tangents == null) {
			return null;
		}
		float[] result = new float[smoothObjectNormals.length];
		Vector3f normal = new Vector3f();
		Vector3f tangent = new Vector3f();
		Vector3f bitangent = new Vector3f();
		Vector3f smooth = new Vector3f();
		for (int vertex = 0; vertex < smoothObjectNormals.length / 3; vertex++) {
			int n = vertex * 3;
			int t = vertex * 4;
			normal.set(normals[n], normals[n + 1], normals[n + 2]).normalize();
			tangent.set(tangents[t], tangents[t + 1], tangents[t + 2]);
			tangent.fma(-tangent.dot(normal), normal).normalize();
			normal.cross(tangent, bitangent).mul(tangents[t + 3]);
			smooth.set(smoothObjectNormals[n], smoothObjectNormals[n + 1], smoothObjectNormals[n + 2]).normalize();
			float x = smooth.dot(tangent);
			float y = smooth.dot(bitangent);
			float z = smooth.dot(normal);
			float length = (float)Math.sqrt(x * x + y * y + z * z);
			if (!Float.isFinite(length) || length <= 1.0E-6F) {
				return null;
			}
			result[n] = x / length;
			result[n + 1] = y / length;
			result[n + 2] = z / length;
		}
		return result;
	}

	private static boolean normalizeTangents(float[] normals, float[] tangents) {
		if (tangents == null || tangents.length / 4 != normals.length / 3) {
			return false;
		}
		Vector3f normal = new Vector3f();
		Vector3f tangent = new Vector3f();
		for (int vertex = 0; vertex < tangents.length / 4; vertex++) {
			int n = vertex * 3;
			int t = vertex * 4;
			normal.set(normals[n], normals[n + 1], normals[n + 2]);
			tangent.set(tangents[t], tangents[t + 1], tangents[t + 2]);
			if (normal.lengthSquared() <= 1.0E-12F || tangent.lengthSquared() <= 1.0E-12F) {
				return false;
			}
			normal.normalize();
			tangent.fma(-tangent.dot(normal), normal);
			if (tangent.lengthSquared() <= 1.0E-12F) {
				return false;
			}
			tangent.normalize();
			tangents[t] = tangent.x;
			tangents[t + 1] = tangent.y;
			tangents[t + 2] = tangent.z;
			tangents[t + 3] = tangents[t + 3] < 0.0F ? -1.0F : 1.0F;
		}
		return true;
	}

	private static final class TangentContext implements MikkTSpaceContext {
		private static final float SAME_TANGENT = 0.9999F;
		private final float[] positions;
		private final float[] normals;
		private final float[] texcoords;
		private final int[] indices;
		private final float[] tangents;
		private final boolean[] assigned;
		private boolean conflict;

		private TangentContext(float[] positions, float[] normals, float[] texcoords, int[] indices) {
			this.positions = positions;
			this.normals = normals;
			this.texcoords = texcoords;
			this.indices = indices;
			this.tangents = new float[positions.length / 3 * 4];
			this.assigned = new boolean[positions.length / 3];
		}

		@Override
		public int getNumFaces() {
			return indices.length / 3;
		}

		@Override
		public int getNumVerticesOfFace(int face) {
			return 3;
		}

		@Override
		public void getPosition(float[] output, int face, int vertex) {
			copy(positions, 3, output, face, vertex);
		}

		@Override
		public void getNormal(float[] output, int face, int vertex) {
			copy(normals, 3, output, face, vertex);
		}

		@Override
		public void getTexCoord(float[] output, int face, int vertex) {
			copy(texcoords, 2, output, face, vertex);
		}

		@Override
		public void setTSpaceBasic(float[] tangent, float sign, int face, int vertex) {
			int index = indices[face * 3 + vertex];
			int offset = index * 4;
			if (assigned[index]) {
				float dot = tangents[offset] * tangent[0] + tangents[offset + 1] * tangent[1]
					+ tangents[offset + 2] * tangent[2];
				if (dot < SAME_TANGENT || tangents[offset + 3] != (sign < 0.0F ? -1.0F : 1.0F)) {
					conflict = true;
				}
				return;
			}
			tangents[offset] = tangent[0];
			tangents[offset + 1] = tangent[1];
			tangents[offset + 2] = tangent[2];
			tangents[offset + 3] = sign < 0.0F ? -1.0F : 1.0F;
			assigned[index] = true;
		}

		@Override
		public void setTSpace(float[] tangent, float[] bitangent, float magS, float magT,
			boolean orientationPreserving, int face, int vertex) {
		}

		private void copy(float[] source, int components, float[] output, int face, int vertex) {
			int offset = indices[face * 3 + vertex] * components;
			System.arraycopy(source, offset, output, 0, components);
		}

		private boolean complete() {
			if (conflict) {
				return false;
			}
			for (int index : indices) {
				if (!assigned[index]) {
					return false;
				}
			}
			return true;
		}
	}
}
