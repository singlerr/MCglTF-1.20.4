package com.modularmods.mcgltf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.MeshModel;
import de.javagl.jgltf.model.MeshPrimitiveModel;
import de.javagl.jgltf.model.v2.MaterialModelV2;
import de.javagl.jgltf.model.v2.MaterialModelV2.AlphaMode;
import de.javagl.jgltf.model.io.GltfModelReader;

class VrmFixtureTest {

	@Test
	void wakgoodFixtureLoadsCompletely() throws Exception {
		verify("transformed_wakgood.vrm", "ebdfd095cb8af6e5dd6348039a1dfaa3a48c185d1ffb6be676c0eef3cc69179c",
			69, 4, 5, 5, 4, 92, 0);
	}

	@Test
	void jingburgerFixtureLoadsCompletely() throws Exception {
		verify("transformed_jingburger.vrm", "957d5b43e29aa4d588e1fddf75f6ff1ce2ec70adb79c0a18ae96b87044fd5a1c",
			390, 20, 35, 18, 10, 219, 3);
	}

	private static void verify(String fileName, String sha256, int nodes, int meshes, int primitives, int images,
		int skins, int maxMorphTargets, int maskedMaterials) throws Exception {
		Path path = Path.of("test_models", fileName);
		assumeTrue(Files.isRegularFile(path), "Local licensed fixture is not available: " + path);
		assertEquals(sha256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));

		GltfModel model = new GltfModelReader().read(path);
		assertEquals(1, model.getSceneModels().size());
		assertEquals(nodes, model.getNodeModels().size());
		assertEquals(meshes, model.getMeshModels().size());
		assertEquals(primitives, model.getMeshModels().stream().mapToInt(mesh -> mesh.getMeshPrimitiveModels().size()).sum());
		assertEquals(images, model.getImageModels().size());
		assertEquals(skins, model.getSkinModels().size());
		assertEquals(maxMorphTargets, model.getMeshModels().stream()
			.flatMap(mesh -> mesh.getMeshPrimitiveModels().stream())
			.mapToInt(primitive -> primitive.getTargets().size()).max().orElse(0));
		assertEquals(maskedMaterials, model.getMaterialModels().stream()
			.filter(MaterialModelV2.class::isInstance).map(MaterialModelV2.class::cast)
			.filter(material -> material.getAlphaMode() == AlphaMode.MASK).count());
		assertTrue(model.getExtensionsModel().getExtensionsUsed().stream().anyMatch(extension -> extension.startsWith("VRM")));
		assertTrue(RenderedGltfModel.mtoonMaterials(model).size() > 0,
			"VRM MToon materialProperties were not associated with glTF materials");

		for (var image : model.getImageModels()) {
			ByteBuffer data = image.getImageData().duplicate();
			byte[] bytes = new byte[data.remaining()];
			data.get(bytes);
			assertNotNull(ImageIO.read(new ByteArrayInputStream(bytes)), "Undecodable image in " + fileName);
		}

		for (MeshModel mesh : model.getMeshModels()) {
			for (MeshPrimitiveModel primitive : mesh.getMeshPrimitiveModels()) {
				assertTrue(primitive.getAttributes().containsKey("POSITION"));
			}
		}
	}
}
