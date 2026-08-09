package com.modularmods.mcgltf;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ToonShaderResourceTest {
	@Test
	void defaultsScreenOffsetsToZero() {
		var offset = ToonShaderMaterial.screenOffset(null);
		assertEquals(0.0F, offset.x);
		assertEquals(0.0F, offset.y);
		assertEquals(0.0F, offset.z);
		assertEquals(0.0F, offset.w);
	}

	@Test
	void consumesViewSpaceVerticesPreparedByTheRenderer() throws Exception {
		try (var stream = getClass().getResourceAsStream(
			"/assets/mcgltf/shaders/core/toon_shader_entity.vsh")) {
			String shader = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(shader.contains("vec3 position = Position;"));
			assertTrue(shader.contains("vec3 normal = normalize(Normal);"));
		}
	}
}
