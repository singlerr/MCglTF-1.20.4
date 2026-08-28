package com.modularmods.mcgltf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.javagl.jgltf.model.impl.DefaultGltfModel;
import de.javagl.jgltf.model.impl.DefaultMeshModel;
import de.javagl.jgltf.model.impl.DefaultMeshPrimitiveModel;
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

	@Test
	void acceptsOfficialHdrShadowColor() throws Exception {
		DefaultGltfModel model = model();
		Path profilePath = directory.resolve("avatar.vrm.toon.json");
		Files.writeString(profilePath,
			"{\"version\":1,\"materials\":[{\"index\":0,\"shadeColor\":[1.1,1.1,1.1,1]}]}");

		ToonShaderProfile profile = ToonShaderProfile.load(model, profilePath);

		assertEquals(1.1F, profile.materials.get(0).shadeColor()[0]);
	}

	@Test
	void readsExplicitBaseColorFactor() throws Exception {
		DefaultGltfModel model = model();
		Path profilePath = directory.resolve("avatar.vrm.toon.json");
		Files.writeString(profilePath,
			"{\"version\":2,\"materials\":[{\"index\":0,\"baseColorFactor\":[1,0.5,0.25,1]}]}");

		ToonShaderProfile profile = ToonShaderProfile.load(model, profilePath);

		assertEquals(0.5F, profile.materials.get(0).baseColorFactor()[1]);
	}

	@Test
	void readsOfficialFaceInputsOnPrimitive() throws Exception {
		DefaultGltfModel model = model();
		DefaultMeshModel mesh = new DefaultMeshModel();
		mesh.setName("FaceMesh");
		DefaultMeshPrimitiveModel primitive = new DefaultMeshPrimitiveModel(4);
		mesh.addMeshPrimitiveModel(primitive);
		model.addMeshModel(mesh);
		Files.write(directory.resolve("face-light.png"), new byte[] {0});
		Files.write(directory.resolve("face-shadow.png"), new byte[] {0});
		Files.write(directory.resolve("ramp.png"), new byte[] {0});
		Path profilePath = directory.resolve("avatar.vrm.toon.json");
		Files.writeString(profilePath, """
			{"version":2,"baseColorScale":0.75,"smoothNormalAngle":60,"primitives":[{"mesh":{"name":"FaceMesh"},"primitive":0,"face":true,
			 "faceSdfLayout":"directional-rg","faceLightMap":"face-light.png",
			 "faceShadow":"face-shadow.png","rampTexture":"ramp.png"}]}
			""");

		ToonShaderProfile profile = ToonShaderProfile.load(model, profilePath);

		assertEquals(2, profile.version);
		assertEquals(0.75F, profile.baseColorScale);
		assertFalse(profile.generateSmoothNormals);
		assertEquals(0.5F, profile.smoothNormalCosine, 0.00001F);
		assertTrue(profile.primitives.get(primitive).face());
		assertTrue(profile.primitives.get(primitive).directionalFaceSdf());
		assertEquals("face-light.png", profile.primitives.get(primitive).faceLightMap().getFileName().toString());
		assertEquals("ramp.png", profile.primitives.get(primitive).rampTexture().getFileName().toString());
	}

	@Test
	void rejectsUnknownFaceSdfLayout() throws Exception {
		DefaultGltfModel model = model();
		Path profilePath = directory.resolve("avatar.vrm.toon.json");
		Files.writeString(profilePath,
			"{\"version\":2,\"materials\":[{\"index\":0,\"face\":true,\"faceSdfLayout\":\"guess\"}]}");

		assertThrows(IllegalArgumentException.class, () -> ToonShaderProfile.load(model, profilePath));
	}

	@Test
	void rejectsPackedFaceMapInOfficialProfile() throws Exception {
		DefaultGltfModel model = model();
		Files.write(directory.resolve("face.png"), new byte[] {0});
		Path profilePath = directory.resolve("avatar.vrm.toon.json");
		Files.writeString(profilePath,
			"{\"version\":2,\"materials\":[{\"index\":0,\"face\":true,\"faceMap\":\"face.png\"}]}");

		assertThrows(IllegalArgumentException.class, () -> ToonShaderProfile.load(model, profilePath));
	}

	@Test
	void rejectsInvalidSmoothNormalAngle() throws Exception {
		Path profilePath = directory.resolve("avatar.vrm.toon.json");
		Files.writeString(profilePath, "{\"version\":2,\"smoothNormalAngle\":181}");

		assertThrows(IllegalArgumentException.class, () -> ToonShaderProfile.load(model(), profilePath));
	}

	@Test
	void rejectsTextureSymlinkOutsideProfileDirectory() throws Exception {
		Path profileDirectory = Files.createDirectory(directory.resolve("profile"));
		Path outsideDirectory = Files.createDirectory(directory.resolve("outside"));
		Path outsideTexture = Files.write(outsideDirectory.resolve("face.png"), new byte[] {0});
		Files.createSymbolicLink(profileDirectory.resolve("face.png"), outsideTexture);
		Path profilePath = profileDirectory.resolve("avatar.vrm.toon.json");
		Files.writeString(profilePath,
			"{\"version\":1,\"materials\":[{\"index\":0,\"face\":true,\"faceMap\":\"face.png\"}]}");

		assertThrows(IllegalArgumentException.class, () -> ToonShaderProfile.load(model(), profilePath));
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
