package com.modularmods.mcgltf;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ToonShaderBoundaryTest {
	@Test
	void keepsToonImplementationOutOfTheGenericModel() throws Exception {
		String model = Files.readString(Path.of("src/main/java/com/modularmods/mcgltf/RenderedGltfModel.java"));
		assertFalse(model.contains("ToonShaderRenderer"));
		assertFalse(model.contains("ToonShaderProfile"));
		assertFalse(model.contains("MikktspaceTangentGenerator"));
		assertFalse(model.contains("normals = smoothNormalAccessor"));
		assertTrue(Files.exists(Path.of("src/main/java/com/modularmods/mcgltf/ToonShaderModel.java")));
		assertTrue(Files.exists(Path.of("src/main/java/com/modularmods/mcgltf/ToonShaderMaterial.java")));
		assertTrue(Files.exists(Path.of("src/main/java/com/modularmods/mcgltf/ToonShaderGeometry.java")));
		assertTrue(Files.exists(Path.of("src/main/java/com/modularmods/mcgltf/ToonShaderVertexPacking.java")));
		assertTrue(Files.exists(Path.of("src/main/java/com/modularmods/mcgltf/ToonShaderPostProcess.java")));
	}
}
