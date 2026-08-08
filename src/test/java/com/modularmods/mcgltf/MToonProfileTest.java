package com.modularmods.mcgltf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.minecraft.util.ARGB;

class MToonProfileTest {
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
}
