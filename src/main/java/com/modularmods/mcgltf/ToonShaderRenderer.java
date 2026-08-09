package com.modularmods.mcgltf;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.DynamicUniformStorage;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

final class ToonShaderRenderer {
	private static final VertexFormat VERTEX_FORMAT = VertexFormat.builder(0)
		.addAttribute("Position", GpuFormat.RGB32_FLOAT)
		.addAttribute("Color", GpuFormat.RGBA8_UNORM)
		.addAttribute("UV0", GpuFormat.RG32_FLOAT)
		.addAttribute("UV2", GpuFormat.RG16_SINT)
		.addAttribute("UV1", GpuFormat.RG16_SINT)
		.addAttribute("Normal", GpuFormat.RGBA8_SNORM)
		.build();
	private static final int MATERIAL_UBO_SIZE = new Std140SizeCalculator()
		.putVec4().putVec4().putVec4().putVec4().putVec4().putVec4()
		.putVec4().putVec4().putVec4().putVec4().putVec4().putVec4()
		.putVec4().putVec4().putVec4().putVec4().putVec4().putVec4()
		.putVec4().putVec4().putVec4().putVec4().get();
	private static final int PROJECTION_UBO_SIZE = new Std140SizeCalculator().putMat4f().get();
	private static final BindGroupLayout PROJECTION_LAYOUT = BindGroupLayout.builder()
		.withUniform("ToonProjection", UniformType.UNIFORM_BUFFER)
		.build();
	private static final BindGroupLayout MATERIAL_LAYOUT = BindGroupLayout.builder()
		.withUniform("ToonMaterial", UniformType.UNIFORM_BUFFER)
		.withSampler("BaseTexture")
		.withSampler("ShadeTexture")
		.withSampler("NormalTexture")
		.withSampler("EmissionTexture")
		.withSampler("MatcapTexture")
		.withSampler("RimTexture")
		.withSampler("OutlineWidthTexture")
		.withSampler("LightMapTexture")
		.withSampler("FaceMapTexture")
		.withSampler("RampTexture")
		.withSampler("MinecraftLightmap")
		.withSampler("SceneColor")
		.withSampler("SceneDepth")
		.build();
	private static final RenderPipeline BASE_CULL = pipeline("toon_shader_base_cull", false, true);
	private static final RenderPipeline BASE_DOUBLE_SIDED = pipeline("toon_shader_base_double_sided", false, false);
	private static final RenderPipeline OUTLINE = pipeline("toon_shader_outline", true, false);
	private static final StagedVertexBuffer BUFFER = new StagedVertexBuffer(() -> "MCglTF ToonShader", 1024 * 1024);
	private static final DynamicUniformStorage<MaterialUniform> UNIFORMS =
		new DynamicUniformStorage<>("MCglTF ToonShader materials", MATERIAL_UBO_SIZE, 32);
	private static final DynamicUniformStorage<ProjectionUniform> PROJECTION_UNIFORMS =
		new DynamicUniformStorage<>("MCglTF ToonShader projection", PROJECTION_UBO_SIZE, 1);
	private static final List<QueuedDraw> DRAWS = new ArrayList<>();
	private static final Matrix4f PROJECTION = new Matrix4f();

	private static GpuTexture sceneColor;
	private static GpuTextureView sceneColorView;
	private static GpuTexture sceneDepth;
	private static GpuTextureView sceneDepthView;
	private static long frameTime = Long.MIN_VALUE;
	private static long lastRenderNanos;

	private ToonShaderRenderer() {
	}

	static void captureProjection(Matrix4fc projection) {
		PROJECTION.set(projection);
	}

	static void queue(PoseStack.Pose pose, float[] positions, float[] normals, float[] texcoords,
		float[] backTexcoords, int[] vertexColors, int[] indices, int packedLight, Material material,
		ToonShaderModel.Frame frame) {
		long currentFrame = Minecraft.getInstance().getFrameTimeNs();
		if (currentFrame != frameTime) {
			if (!DRAWS.isEmpty()) {
				BUFFER.endFrame();
				DRAWS.clear();
			}
			frameTime = currentFrame;
		}
		StagedVertexBuffer.Draw base = BUFFER.appendDraw(VERTEX_FORMAT, PrimitiveTopology.TRIANGLES);
		Matrix4f modelView = RenderSystem.getModelViewMatrixCopy();
		Matrix4f positionTransform = new Matrix4f(modelView).mul(pose.pose());
		Matrix3f normalTransform = new Matrix3f(modelView).mul(pose.normal());
		emit(BUFFER.getVertexBuilder(base), positionTransform, normalTransform,
			positions, normals, texcoords, backTexcoords, vertexColors,
			indices, packedLight);
		DRAWS.add(new QueuedDraw(base, material.doubleSided ? BASE_DOUBLE_SIDED : BASE_CULL,
			material, frame));
		if (material.outline && material.outlineWidth > 0.0F) {
			DRAWS.add(new QueuedDraw(base, OUTLINE, material, frame));
		}
	}

	static void render() {
		if (DRAWS.isEmpty()) {
			return;
		}
		long started = System.nanoTime();
		try {
			RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
			if (target.getColorTexture() == null || target.getDepthTexture() == null) {
				return;
			}
			ensureSceneCopies(target);
			var encoder = RenderSystem.getDevice().createCommandEncoder();
			encoder.copyTextureToTexture(target.getColorTexture(), sceneColor, 0, 0, 0, 0, 0, target.width, target.height);
			encoder.copyTextureToTexture(target.getDepthTexture(), sceneDepth, 0, 0, 0, 0, 0, target.width, target.height);
			BUFFER.upload();
			GpuSampler sceneColorSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
			GpuSampler sceneDepthSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			var projectionUniform = PROJECTION_UNIFORMS.writeUniform(new ProjectionUniform(PROJECTION));
			try (RenderPass pass = encoder.createRenderPass(() -> "MCglTF ToonShader forward pass",
				target.getColorTextureView(), Optional.empty())) {
				for (QueuedDraw draw : DRAWS) {
					StagedVertexBuffer.ExecuteInfo info = BUFFER.getExecuteInfo(draw.draw);
					if (info == null) {
						continue;
					}
					pass.setPipeline(draw.pipeline);
					RenderSystem.bindDefaultUniforms(pass);
					pass.setUniform("ToonProjection", projectionUniform);
					pass.setUniform("ToonMaterial",
						UNIFORMS.writeUniform(new MaterialUniform(draw.material, draw.frame)));
					bindMaterial(pass, draw.material);
					pass.bindTexture("MinecraftLightmap", Minecraft.getInstance().gameRenderer.levelLightmap(),
						RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
					pass.bindTexture("SceneColor", sceneColorView, sceneColorSampler);
					pass.bindTexture("SceneDepth", sceneDepthView, sceneDepthSampler);
					pass.setVertexBuffer(0, info.vertexBuffer().slice());
					pass.draw(info.indexCount(), 1, info.baseVertex(), 0);
				}
			}
		} finally {
			lastRenderNanos = System.nanoTime() - started;
			DRAWS.clear();
			BUFFER.endFrame();
			UNIFORMS.endFrame();
			PROJECTION_UNIFORMS.endFrame();
		}
	}

	static long lastRenderNanos() {
		return lastRenderNanos;
	}

	static void close() {
		DRAWS.clear();
		BUFFER.close();
		UNIFORMS.close();
		PROJECTION_UNIFORMS.close();
		closeSceneCopies();
	}

	private static void bindMaterial(RenderPass pass, Material material) {
		bind(pass, "BaseTexture", material.baseTexture);
		bind(pass, "ShadeTexture", material.shadeTexture);
		bind(pass, "NormalTexture", material.normalTexture);
		bind(pass, "EmissionTexture", material.emissionTexture);
		bind(pass, "MatcapTexture", material.matcapTexture);
		bind(pass, "RimTexture", material.rimTexture);
		bind(pass, "OutlineWidthTexture", material.outlineWidthTexture);
		bind(pass, "LightMapTexture", material.lightMapTexture);
		bind(pass, "FaceMapTexture", material.faceMapTexture);
		bind(pass, "RampTexture", material.rampTexture);
	}

	private static void bind(RenderPass pass, String name, Identifier identifier) {
		AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(identifier);
		pass.bindTexture(name, texture.getTextureView(), texture.getSampler());
	}

	private static void ensureSceneCopies(RenderTarget target) {
		if (sceneColor != null && sceneColor.getWidth(0) == target.width && sceneColor.getHeight(0) == target.height
			&& sceneColor.getFormat() == target.getColorTexture().getFormat()) {
			return;
		}
		closeSceneCopies();
		sceneColor = RenderSystem.getDevice().createTexture("MCglTF ToonShader scene color", 5,
			target.getColorTexture().getFormat(), target.width, target.height, 1, 1);
		sceneDepth = RenderSystem.getDevice().createTexture("MCglTF ToonShader scene depth", 5,
			target.getDepthTexture().getFormat(), target.width, target.height, 1, 1);
		sceneColorView = RenderSystem.getDevice().createTextureView(sceneColor);
		sceneDepthView = RenderSystem.getDevice().createTextureView(sceneDepth);
	}

	private static void closeSceneCopies() {
		if (sceneColorView != null) {
			sceneColorView.close();
			sceneColorView = null;
		}
		if (sceneDepthView != null) {
			sceneDepthView.close();
			sceneDepthView = null;
		}
		if (sceneColor != null) {
			sceneColor.close();
			sceneColor = null;
		}
		if (sceneDepth != null) {
			sceneDepth.close();
			sceneDepth = null;
		}
	}

	private static void emit(VertexConsumer consumer, Matrix4f positionTransform, Matrix3f normalTransform,
		float[] positions, float[] normals, float[] texcoords, float[] backTexcoords, int[] colors,
		int[] indices, int packedLight) {
		Vector3f transformedPosition = new Vector3f();
		Vector3f transformedNormal = new Vector3f();
		for (int i = 0; i < indices.length; i += 3) {
			emitVertex(consumer, positionTransform, normalTransform, transformedPosition, transformedNormal,
				positions, normals, texcoords, backTexcoords, colors, indices[i], packedLight);
			emitVertex(consumer, positionTransform, normalTransform, transformedPosition, transformedNormal,
				positions, normals, texcoords, backTexcoords, colors,
				indices[i + 1], packedLight);
			emitVertex(consumer, positionTransform, normalTransform, transformedPosition, transformedNormal,
				positions, normals, texcoords, backTexcoords, colors,
				indices[i + 2], packedLight);
		}
	}

	private static void emitVertex(VertexConsumer consumer, Matrix4f positionTransform, Matrix3f normalTransform,
		Vector3f transformedPosition, Vector3f transformedNormal, float[] positions, float[] normals, float[] texcoords,
		float[] backTexcoords, int[] colors, int vertex, int packedLight) {
		int p = vertex * 3;
		int uv = vertex * 2;
		positionTransform.transformPosition(positions[p], positions[p + 1], positions[p + 2], transformedPosition);
		normalTransform.transform(normals[p], normals[p + 1], normals[p + 2], transformedNormal).normalize();
		consumer.addVertex(transformedPosition.x, transformedPosition.y, transformedPosition.z, colors[vertex],
			texcoords[uv], texcoords[uv + 1], packUv(backTexcoords[uv], backTexcoords[uv + 1]), packedLight,
			transformedNormal.x, transformedNormal.y, transformedNormal.z);
	}

	private static int packUv(float u, float v) {
		return pack(u) & 0xFFFF | pack(v) << 16;
	}

	private static int pack(float value) {
		return Math.round(Math.max(-1.0F, Math.min(1.0F, value)) * 32767.0F);
	}

	private static RenderPipeline pipeline(String name, boolean outline, boolean cull) {
		RenderPipeline.Builder builder = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath(MCglTF.MODID, "pipeline/" + name))
			.withVertexShader(Identifier.fromNamespaceAndPath(MCglTF.MODID, "core/toon_shader_entity"))
			.withFragmentShader(Identifier.fromNamespaceAndPath(MCglTF.MODID, "core/toon_shader_entity"))
			.withBindGroupLayout(BindGroupLayouts.PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.LIGHTING)
			.withBindGroupLayout(BindGroupLayouts.GLOBALS)
			.withBindGroupLayout(PROJECTION_LAYOUT)
			.withBindGroupLayout(MATERIAL_LAYOUT)
			.withVertexBinding(0, VERTEX_FORMAT)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withCull(cull);
		if (outline) {
			builder.withShaderDefine("TOON_SHADER_OUTLINE");
		}
		return RenderPipelines.register(builder.build());
	}

	record Material(Identifier baseTexture, Identifier shadeTexture, Identifier normalTexture,
		Identifier emissionTexture, Identifier matcapTexture, Identifier rimTexture,
		Identifier outlineWidthTexture, Identifier lightMapTexture, Identifier faceMapTexture,
		Identifier rampTexture, Vector4f shadeColor, Vector4f emissionColor, Vector4f rimColor,
		Vector4f outlineColor1, Vector4f outlineColor2, Vector4f outlineColor3,
		Vector4f outlineColor4, Vector4f outlineColor5, Vector4f blushColor, Vector4f screenOffset,
		float shadeShift, float shadingToony,
		float shadowOffset, float shadowSmoothness, float giEqualization, float materialType,
		float nonMetalSpecular, float metalSpecular,
		float specularShininess, float emissionIntensity, float rimOffset, float rimThreshold,
		float rimIntensity, float rimPower, float outlineWidth, float outlineDistanceNear,
		float outlineDistanceFar, float outlineScaleNear, float outlineScaleFar,
		float outlineZOffset, float outlineLightingMix,
		float faceShadowStrength, float faceShadowOffset, float blushIntensity, float alphaCutoff, boolean face,
		boolean metallic, boolean outline, boolean outlineScreenSpace, boolean outlineVertexAlpha,
		boolean backUv, boolean doubleSided) {
	}

	private record QueuedDraw(StagedVertexBuffer.Draw draw, RenderPipeline pipeline, Material material,
		ToonShaderModel.Frame frame) {
	}

	private record MaterialUniform(Material material, ToonShaderModel.Frame frame)
		implements DynamicUniformStorage.DynamicUniform {
		@Override
		public void write(java.nio.ByteBuffer buffer) {
			Material m = material;
			ToonShaderModel.Frame f = frame;
			Std140Builder.intoBuffer(buffer)
				.putVec4(m.shadeColor)
				.putVec4(m.emissionColor)
				.putVec4(m.rimColor)
				.putVec4(m.outlineColor1)
				.putVec4(m.outlineColor2)
				.putVec4(m.outlineColor3)
				.putVec4(m.outlineColor4)
				.putVec4(m.outlineColor5)
				.putVec4(m.blushColor)
				.putVec4(m.screenOffset)
				.putVec4(m.shadowOffset, m.shadowSmoothness, m.shadeShift, m.shadingToony)
				.putVec4(m.giEqualization, m.materialType, m.nonMetalSpecular, m.metalSpecular)
				.putVec4(m.specularShininess, m.emissionIntensity, m.metallic ? 1.0F : 0.0F, m.face ? 1.0F : 0.0F)
				.putVec4(m.rimOffset, m.rimThreshold, m.rimIntensity, m.rimPower)
				.putVec4(m.outlineWidth, m.outlineZOffset, m.outlineLightingMix, 0.0F)
				.putVec4(m.outlineDistanceNear, m.outlineDistanceFar, m.outlineScaleNear, m.outlineScaleFar)
				.putVec4(m.faceShadowStrength, m.faceShadowOffset, m.blushIntensity, 0.0F)
				.putVec4(m.alphaCutoff, m.outlineScreenSpace ? 1.0F : 0.0F,
					m.outlineVertexAlpha ? 1.0F : 0.0F, m.backUv ? 1.0F : 0.0F)
				.putVec4(m.doubleSided ? 1.0F : 0.0F, f.night(), 0.0F, 0.0F)
				.putVec4(f.headForward().x, f.headForward().y, f.headForward().z, 0.0F)
				.putVec4(f.headRight().x, f.headRight().y, f.headRight().z, 0.0F)
				.putVec4(f.lightDirectionMultiplier().x, f.lightDirectionMultiplier().y,
					f.lightDirectionMultiplier().z, 0.0F);
		}
	}

	private record ProjectionUniform(Matrix4f projection) implements DynamicUniformStorage.DynamicUniform {
		@Override
		public void write(java.nio.ByteBuffer buffer) {
			Std140Builder.intoBuffer(buffer).putMat4f(projection);
		}
	}
}
