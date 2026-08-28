package com.modularmods.mcgltf;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.joml.Vector3f;

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
	void transformsModelVerticesOnTheGpu() throws Exception {
		try (var stream = getClass().getResourceAsStream(
			"/assets/mcgltf/shaders/core/toon_shader_entity.vsh")) {
			String shader = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(shader.contains("ToonModelViewMatrix * vec4(Position, 1.0)"));
			assertTrue(shader.contains("mat3(ToonNormalMatrix) * Normal"));
				assertTrue(shader.contains("decodeTangent(UV2)"));
				assertTrue(shader.contains("decodeSmoothNormal(LineWidth)"));
				assertTrue(shader.contains("tangentDirection * tangentSmoothNormal.x"));
				assertTrue(shader.contains("viewNormal = normal;"));
				assertTrue(shader.contains("outlineLength <= 0.0001"));
				assertTrue(shader.contains("outlineWidth(position.z) * vec3(outlineNormal.xy, 0.0)"));
				assertTrue(shader.contains("if (Flags.y > 0.5 && HeadForward.w < 0.5)"));
				assertFalse(shader.contains("200.0 * width * pixelDirection / viewport"));
				assertFalse(shader.contains("HeadForward.w < 0.5 && Flags.y > 0.5"));
			String fragment = resource("toon_shader_entity.fsh");
			assertTrue(fragment.contains("mat4 ToonModelViewMatrix;"));
			assertTrue(fragment.contains("mat4 ToonNormalMatrix;"));
		}
	}

	@Test
	void keepsOfficialForwardShadingContracts() throws Exception {
		try (var stream = getClass().getResourceAsStream(
			"/assets/mcgltf/shaders/core/toon_shader_entity.fsh")) {
			String shader = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
				assertFalse(shader.contains("cotangentFrame"));
				assertFalse(shader.contains("dFdx"));
				assertFalse(shader.contains("Light0_Direction"));
				assertTrue(shader.contains("normalize(MainLightDirection.xyz)"));
				assertTrue(shader.contains("lightMap.g * vertexColor.r"));
				assertTrue(shader.contains("profiled ? vec3(1.0) : vertexColor.rgb"));
				assertTrue(shader.contains("texture(FaceLightMapTexture, shadowUv).r"));
				assertTrue(shader.contains("mix(directionalSdf.r, directionalSdf.g, step(0.0, crossDirection))"));
				assertTrue(shader.contains("texture(FaceShadowTexture, uv).a"));
				assertTrue(shader.contains("texture(AlphaTexture, uv).a"));
				assertTrue(shader.contains("FaceParams.w > 0.5 ? baseSample.a : texture(FaceLightMapTexture, uv).b"));
				assertTrue(shader.contains("mappedNormal.xy *= MainLightDirection.w"));
				assertTrue(shader.contains("color = srgbToLinear(color);"));
				assertTrue(shader.contains("srgbToLinear(BlushColor.rgb)"));
				assertTrue(shader.contains("profiled ? vec3(emissionSample.r) : baseSample.a * emissionMask"));
			assertTrue(shader.contains("#ifdef TOON_SHADER_DEPTH_ONLY"));
			assertTrue(shader.contains("profiled ? albedo * rampColor"));
				assertTrue(shader.contains("offsetEyeDepth - toonEyeDepth"));
			assertTrue(shader.contains("vec4(finalColor * alpha, alpha)"));
			assertFalse(shader.contains("SceneColor"));
		}
	}

	@Test
	void keepsOfficialBloomAndTonemapConstants() throws Exception {
		String blur = resource("toon_bloom_blur.fsh");
		assertTrue(blur.contains("0.01621622"));
		assertTrue(blur.contains("0.22702703"));
		assertTrue(blur.contains("textureSize(ToonFull, 0).y) / 1080.0"));
		String prefilter = resource("toon_bloom_prefilter.fsh");
		assertTrue(prefilter.contains("clamp(color.a, 0.0, 1.0)"));
		assertTrue(prefilter.contains("color.rgb / coverage"));
		assertTrue(prefilter.contains("max(straight - vec3(1.0), vec3(0.0)) * coverage"));
		String upsample = resource("toon_bloom_upsample.fsh");
		assertTrue(upsample.contains("* 0.1"));
		assertTrue(upsample.contains("* 0.4"));
		String resolve = resource("toon_post_resolve.fsh");
			assertTrue(resolve.contains("* 1.5"));
			assertTrue(resolve.contains("#ifdef TOON_NO_BLOOM"));
			assertTrue(resolve.contains("color *= 1.05"));
			assertTrue(resolve.contains("(1.36 * color + 0.047) * color"));
			assertFalse(resolve.contains("SceneColor"));
			assertTrue(resolve.contains("discard;"));
			assertFalse(resolve.contains("erodedCoverage"));
			assertTrue(resolve.contains("vec4(character * coverage + halo, coverage)"));
	}

	@Test
	void matchesSkyRendererCelestialDirections() {
		assertDirection(ToonShaderModel.celestialDirection(0.0F), 0.0F, 1.0F);
		assertDirection(ToonShaderModel.celestialDirection(90.0F), -1.0F, 0.0F);
		assertDirection(ToonShaderModel.celestialDirection(180.0F), 0.0F, -1.0F);
		assertDirection(ToonShaderModel.celestialDirection(270.0F), 1.0F, 0.0F);
	}

	@Test
	void usesMinecraftReverseZAndBiasForCompositePasses() throws Exception {
		String renderer = Files.readString(Path.of("src/main/java/com/modularmods/mcgltf/ToonShaderRenderer.java"));
		assertTrue(renderer.contains("outline ? -128.0F : 128.0F"));
		assertFalse(renderer.contains("new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false)"));
		assertFalse(renderer.contains("copyTextureToTexture(target.getDepthTexture()"));
		assertFalse(renderer.contains("copyTextureToTexture(target.getColorTexture()"));
		assertTrue(renderer.contains("ToonShaderPostProcess.depthView()"));
		assertTrue(renderer.contains("OptionalDouble.of(0.0D)"));
		String postProcess = Files.readString(Path.of(
			"src/main/java/com/modularmods/mcgltf/ToonShaderPostProcess.java"));
			assertTrue(postProcess.contains("BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA"));
			assertTrue(postProcess.contains("if (!ToonShader.isBloomEnabled())"));
			assertFalse(postProcess.contains("BLOOM_COMPOSITE"));
			assertTrue(postProcess.contains("color == failedColor && depth == failedDepth"));
			assertTrue(postProcess.contains("new Vector4f(0.0F, 0.0F, 0.0F, 0.0F)"));
			assertFalse(postProcess.contains("destination, Optional.of(CLEAR)"));
			assertFalse(postProcess.contains("SceneColor"));
				assertTrue(postProcess.contains("copyTextureToTexture(toonDepth, toonDepthSnapshot"));
			assertTrue(postProcess.contains("target.getColorTextureView(), Optional.empty()"));
			assertTrue(postProcess.contains("GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_COLOR, true"));
		assertTrue(renderer.contains("target.getColorTexture() == failedColorTarget"));
		assertTrue(Files.readString(Path.of("src/main/java/com/modularmods/mcgltf/ToonShaderMaterial.java"))
			.contains("override != null && override.outlineVertexAlpha()"));
		assertTrue(Files.readString(Path.of("src/main/java/com/modularmods/mcgltf/RenderedGltfModel.java"))
			.contains("Primitive.smoothGeneratedNormals(meshPrimitives, toonShader.smoothNormalCosine());"));
		assertTrue(Files.readString(Path.of("src/main/java/com/modularmods/mcgltf/RenderedGltfModel.java"))
			.contains("normalScale = material.getNormalScale();"));
	}

	@Test
	void toleratesBiasedSelfDepthWithoutDisablingForegroundOcclusion() throws Exception {
		String fragment = resource("toon_shader_entity.fsh");
		assertTrue(fragment.contains("#ifndef TOON_SHADER_OUTLINE"));
		assertTrue(fragment.contains(
			"currentEyeDepth > sceneEyeDepth + max(0.002, currentEyeDepth * 0.002)"));
	}

	@Test
	void toonRequestsSkipTheShaderPackEntityPass() throws Exception {
		String model = Files.readString(Path.of(
			"src/main/java/com/modularmods/mcgltf/RenderedGltfModel.java"));
		assertTrue(model.contains("if (queueToon) {"));
		assertTrue(model.contains("} else {\n\t\t\t\t\t\trender(pose, consumer, light, overlay, geometry, submittedIndices);"));
	}

	private static void assertDirection(Vector3f direction, float x, float y) {
		assertEquals(x, direction.x, 0.00001F);
		assertEquals(y, direction.y, 0.00001F);
		assertEquals(0.0F, direction.z, 0.00001F);
	}

	private String resource(String name) throws Exception {
		try (var stream = getClass().getResourceAsStream("/assets/mcgltf/shaders/core/" + name)) {
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	void packsTangentHandednessAndSmoothNormalIntoNativeVertexSlots() {
		assertEquals(0x40007FFF, ToonShaderVertexPacking.packTangent(new Vector3f(1, 0, 0), 1));
		assertEquals(0x4000FFFF, ToonShaderVertexPacking.packTangent(new Vector3f(1, 0, 0), -1));
		assertEquals(0x7FFF0000, ToonShaderVertexPacking.packSmoothNormal(new Vector3f(0, 1, 0)));
	}
}
