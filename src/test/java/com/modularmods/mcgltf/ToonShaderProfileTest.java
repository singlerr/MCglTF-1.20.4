package com.modularmods.mcgltf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.javagl.jgltf.model.impl.DefaultGltfModel;
import de.javagl.jgltf.model.impl.DefaultNodeModel;
import de.javagl.jgltf.model.v2.MaterialModelV2;

class ToonShaderProfileTest {
	@TempDir
	Path directory;

	@Test
	void readsFaceDataAndVrmOneHead() throws Exception {
		DefaultGltfModel model = model();
		Files.write(directory.resolve("face.png"), new byte[] {0});
		Path profilePath = directory.resolve("avatar.vrm.toon.json");
		Files.writeString(profilePath, """
			{"version":1,"head":{"name":"Head","forward":[0,0,-1],"right":[1,0,0]},
			 "materials":[{"name":"Face","face":true,"faceMap":"face.png","materialType":2}]}
			""");

		ToonShaderProfile profile = ToonShaderProfile.load(model, profilePath);

		assertSame(model.getNodeModels().getFirst(), profile.head);
		assertEquals(2, profile.materials.get(0).materialType());
	}

	@Test
	void rejectsFaceMaterialWithoutSdfMap() throws Exception {
		DefaultGltfModel model = model();
		Path profilePath = directory.resolve("avatar.vrm.toon.json");
		Files.writeString(profilePath,
			"{\"version\":1,\"materials\":[{\"index\":0,\"face\":true}]}");

		assertThrows(IllegalArgumentException.class, () -> ToonShaderProfile.load(model, profilePath));
	}

	private static DefaultGltfModel model() {
		DefaultGltfModel model = new DefaultGltfModel();
		MaterialModelV2 material = new MaterialModelV2();
		material.setName("Face");
		model.addMaterialModel(material);
		DefaultNodeModel head = new DefaultNodeModel();
		head.setName("Head");
		model.addNodeModel(head);
		model.setExtensions(Map.of("VRMC_vrm", Map.of("humanoid",
			Map.of("humanBones", Map.of("head", Map.of("node", 0))))));
		return model;
	}
}
