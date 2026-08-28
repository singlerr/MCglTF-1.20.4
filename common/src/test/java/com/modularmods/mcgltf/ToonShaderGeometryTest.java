package com.modularmods.mcgltf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ToonShaderGeometryTest {
	@Test
	void generatesMikkTangentWithoutReplacingForwardNormal() {
		float[] positions = {0, 0, 0, 1, 0, 0, 0, 1, 0};
		float[] normals = {0, 0, 1, 0, 0, 1, 0, 0, 1};
		float[] originalNormals = normals.clone();
		float[] texcoords = {0, 0, 1, 0, 0, 1};

		float[] tangents = ToonShaderGeometry.generateTangents(positions, normals, texcoords,
			new int[] {0, 1, 2});

		assertNotNull(tangents);
		assertArrayEquals(originalNormals, normals);
		assertArrayEquals(new float[] {1, 0, 0, 1},
			new float[] {tangents[0], tangents[1], tangents[2], tangents[3]}, 1.0E-6F);
	}

	@Test
	void convertsGeneratedSmoothNormalIntoTangentSpace() {
		float[] smooth = ToonShaderGeometry.toTangentSpace(new float[] {0, 1, 0},
			new float[] {0, 0, 1}, new float[] {1, 0, 0, 1});

		assertArrayEquals(new float[] {0, 1, 0}, smooth, 1.0E-6F);
	}

	@Test
	void smoothsMatchingVerticesAcrossPrimitiveSlicesWithoutFlatteningHardEdges() {
		float[] smoothed = RenderedGltfModel.Primitive.smoothSplitNormals(
			new float[] {0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0},
			new float[] {0, 0, 1, 0, 0, 1, 0, 0.6F, 0.8F, 0, 1, 0}, 0.70710677F);

		assertArrayEquals(new float[] {0, 0.31622776F, 0.9486833F},
			new float[] {smoothed[0], smoothed[1], smoothed[2]}, 1.0E-6F);
		assertArrayEquals(new float[] {0, 0.31622776F, 0.9486833F},
			new float[] {smoothed[6], smoothed[7], smoothed[8]}, 1.0E-6F);
		assertArrayEquals(new float[] {0, 1, 0},
			new float[] {smoothed[9], smoothed[10], smoothed[11]}, 1.0E-6F);
	}

	@Test
	void honorsModelSmoothNormalAngle() {
		float[] positions = {0, 0, 0, 0, 0, 0};
		float[] normals = {0, 0, 1, 0.76604444F, 0, 0.64278764F};

		float[] preserved = RenderedGltfModel.Primitive.smoothSplitNormals(
			positions, normals, 0.70710677F);
		float[] smoothed = RenderedGltfModel.Primitive.smoothSplitNormals(positions, normals, 0.5F);

		assertArrayEquals(new float[] {0, 0, 1}, new float[] {preserved[0], preserved[1], preserved[2]}, 1.0E-6F);
		assertArrayEquals(new float[] {0.42261827F, 0, 0.9063078F},
			new float[] {smoothed[0], smoothed[1], smoothed[2]}, 1.0E-6F);
	}

	@Test
	void decodesOfficialEncodedSmoothNormal() {
		float[] smooth = ToonShaderGeometry.decodeSmoothNormals(new float[] {0.5F, 1.0F, 0.5F});

		assertArrayEquals(new float[] {0, 1, 0}, smooth, 1.0E-6F);
	}

	@Test
	void usesAnOrthogonalBasisOnlyWhenNoNormalMapNeedsUvTangents() {
		float[] positions = {0, 0, 0, 1, 0, 0, 0, 1, 0};
		float[] normals = {0, 0, 1, 0, 0, 1, 0, 0, 1};
		float[] degenerateUv = {0, 0, 0, 0, 0, 0};
		int[] indices = {0, 0, 0};

		assertNotNull(ToonShaderGeometry.create(positions, normals, degenerateUv, indices, null, null,
			new float[0][], false, true, 0.70710677F));
		assertNull(ToonShaderGeometry.create(positions, normals, degenerateUv, indices, null, null,
			new float[0][], true, true, 0.70710677F));
		assertNull(ToonShaderGeometry.create(positions, normals, degenerateUv, indices, null, null,
			new float[0][], false, false, 0.70710677F));
		assertNull(ToonShaderGeometry.create(positions, normals, degenerateUv, indices, null,
			new float[] {0.5F, 0.5F, 1.0F, 0.5F, 0.5F, 1.0F, 0.5F, 0.5F, 1.0F},
			new float[0][], false, true, 0.70710677F));
	}
}
