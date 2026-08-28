package com.modularmods.mcgltf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

final class RenderViewTest {

	@Test
	void removesEveryTriangleTouchingAHiddenVertex() {
		int[] indices = {0, 1, 2, 2, 3, 0, 3, 4, 5};

		assertArrayEquals(new int[] {3, 4, 5},
			RenderedGltfModel.filterTriangles(indices, new boolean[] {false, false, true, false, false, false}));
	}

	@Test
	void reusesIndicesWhenNothingIsHidden() {
		int[] indices = {0, 1, 2};

		assertSame(indices, RenderedGltfModel.filterTriangles(indices, new boolean[3]));
	}
}
