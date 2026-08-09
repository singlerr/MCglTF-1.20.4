package com.modularmods.mcgltf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.mojang.blaze3d.platform.NativeImage;

import de.javagl.jgltf.model.impl.DefaultGltfModel;
import de.javagl.jgltf.model.v2.MaterialModelV2;
import net.minecraft.util.ARGB;

class MToonProfileTest {
	@Test
	void stacksBaseAndShadeForTheIrisMaterialPath() {
		try (NativeImage base = new NativeImage(1, 1, false);
			 NativeImage shade = new NativeImage(2, 1, false)) {
			base.setPixel(0, 0, 0xFF010203);
			shade.setPixel(0, 0, 0xFF112233);
			shade.setPixel(1, 0, 0xFF445566);
			try (NativeImage atlas = RenderedGltfModel.stackMToonAtlas(base, shade)) {
				assertEquals(2, atlas.getWidth());
				assertEquals(2, atlas.getHeight());
				assertEquals(0xFF010203, atlas.getPixel(1, 0));
				assertEquals(0xFF112233, atlas.getPixel(0, 1));
				assertEquals(0xFF445566, atlas.getPixel(1, 1));
			}
		}
	}

	@Test
	void smoothsNearbySplitNormalsWithoutRoundingHardEdges() {
		float[] positions = {1, 2, 3, 1, 2, 3, 1, 2, 3};
		float[] normals = {1, 0, 0, 0.8F, 0.6F, 0, -1, 0, 0};

		float[] smoothed = RenderedGltfModel.Primitive.smoothSplitNormals(positions, normals);

		assertArrayEquals(new float[] {0.9486833F, 0.3162278F, 0},
			new float[] {smoothed[0], smoothed[1], smoothed[2]}, 1.0E-6F);
		assertArrayEquals(new float[] {-1, 0, 0},
			new float[] {smoothed[6], smoothed[7], smoothed[8]}, 1.0E-6F);
	}

	@Test
	void readsVrmZeroMtoonShadeInputs() {
		var profile = RenderedGltfModel.MToonProfile.from(Map.of(
			"shader", "VRM/MToon",
			"textureProperties", Map.of("_ShadeTexture", 7),
			"floatProperties", Map.of("_ShadeShift", 0.0F, "_ShadeToony", 1.0F, "_RimFresnelPower", 3.4F),
			"vectorProperties", Map.of("_ShadeColor", List.of(0.5F, 0.25F, 1.0F, 1.0F))));

		assertTrue(profile.enabled());
		assertEquals(7, profile.shadeTextureIndex());
		assertEquals(ARGB.color(255, 128, 64, 255), profile.shadeTint());
		assertEquals(238, profile.toonControl());
		assertEquals(3, profile.overlay());
	}

	@Test
	void ignoresNonMtoonMaterialProperties() {
		assertFalse(RenderedGltfModel.MToonProfile.from(Map.of("shader", "Standard")).enabled());
	}

	@Test
	void readsVrmOneMtoonMaterialExtension() {
		DefaultGltfModel model = new DefaultGltfModel();
		MaterialModelV2 material = new MaterialModelV2();
		material.setExtensions(Map.of("VRMC_materials_mtoon", Map.of(
			"shadeColorFactor", List.of(0.4F, 0.5F, 0.6F),
			"shadeMultiplyTexture", Map.of("index", 3),
			"shadingShiftFactor", -0.1F,
			"shadingToonyFactor", 0.8F,
			"outlineWidthMode", "screenCoordinates",
			"outlineWidthFactor", 0.25F)));
		model.addMaterialModel(material);

		var profile = RenderedGltfModel.mtoonMaterials(model).get(material);

		assertTrue(profile.enabled());
		assertEquals(3, profile.shadeTextureIndex());
		assertTrue(profile.outline());
		assertTrue(profile.outlineScreenSpace());
		assertEquals(25.0F, profile.outlineWidth());
		assertFalse(profile.outlineDistanceFade());
	}
}
