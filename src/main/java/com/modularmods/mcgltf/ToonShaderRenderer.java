package com.modularmods.mcgltf;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.CompareOp;
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
import net.irisshaders.iris.vertices.ImmediateState;

final class ToonShaderRenderer {
	private static final VertexFormat VERTEX_FORMAT = VertexFormat.builder(0)
		.addAttribute("Position", GpuFormat.RGB32_FLOAT)
		.addAttribute("Color", GpuFormat.RGBA8_UNORM)
		.addAttribute("UV0", GpuFormat.RG32_FLOAT)
		.addAttribute("UV2", GpuFormat.RG16_SINT)
		.addAttribute("UV1", GpuFormat.RG16_SINT)
		.addAttribute("Normal", GpuFormat.RGBA8_SNORM)
		.addAttribute("LineWidth", GpuFormat.R32_UINT)
		.build();
	private static final int MATERIAL_UBO_SIZE = new Std140SizeCalculator()
		.putVec4().putVec4().putVec4().putVec4().putVec4().putVec4()
		.putVec4().putVec4().putVec4().putVec4().putVec4().putVec4()
		.putVec4().putVec4().putVec4().putVec4().putVec4().putVec4()
		.putVec4().putVec4().putVec4().putVec4().putVec4().putMat4f().putMat4f().get();
	private static final int PROJECTION_UBO_SIZE = new Std140SizeCalculator().putMat4f().get();
	private static final BindGroupLayout PROJECTION_LAYOUT = BindGroupLayout.builder()
		.withUniform("ToonProjection", UniformType.UNIFORM_BUFFER)
		.build();
	private static final BindGroupLayout MATERIAL_LAYOUT = BindGroupLayout.builder()
		.withUniform("ToonMaterial", UniformType.UNIFORM_BUFFER)
		.withSampler("BaseTexture")
		.withSampler("AlphaTexture")
		.withSampler("ShadeTexture")
		.withSampler("NormalTexture")
		.withSampler("EmissionTexture")
		.withSampler("MatcapTexture")
		.withSampler("RimTexture")
		.withSampler("OutlineWidthTexture")
		.withSampler("LightMapTexture")
		.withSampler("FaceLightMapTexture")
		.withSampler("FaceShadowTexture")
		.withSampler("RampTexture")
		.withSampler("MinecraftLightmap")
		.withSampler("SceneDepth")
		.withSampler("ToonDepth")
		.build();
	private static final RenderPipeline BASE_CULL = pipeline("toon_shader_base_cull", false, false, true, true, false);
	private static final RenderPipeline BASE_DOUBLE_SIDED = pipeline("toon_shader_base_double_sided", false, false, false, true, false);
	private static final RenderPipeline BLEND_CULL = pipeline("toon_shader_blend_cull", false, false, true, false, true);
	private static final RenderPipeline BLEND_DOUBLE_SIDED = pipeline("toon_shader_blend_double_sided", false, false, false, false, true);
	private static final RenderPipeline DEPTH_CULL = pipeline("toon_shader_depth_cull", false, true, true, true, false);
	private static final RenderPipeline DEPTH_DOUBLE_SIDED = pipeline("toon_shader_depth_double_sided", false, true, false, true, false);
	private static final RenderPipeline OUTLINE = pipeline("toon_shader_outline", true, false, false, true, false);
	private static final RenderPipeline[] PIPELINES = {BASE_CULL, BASE_DOUBLE_SIDED, BLEND_CULL,
		BLEND_DOUBLE_SIDED, DEPTH_CULL, DEPTH_DOUBLE_SIDED, OUTLINE};
	private static final StagedVertexBuffer BUFFER = new StagedVertexBuffer(() -> "MCglTF ToonShader", 1024 * 1024);
	private static final DynamicUniformStorage<MaterialUniform> UNIFORMS =
		new DynamicUniformStorage<>("MCglTF ToonShader materials", MATERIAL_UBO_SIZE, 32);
	private static final DynamicUniformStorage<ProjectionUniform> PROJECTION_UNIFORMS =
		new DynamicUniformStorage<>("MCglTF ToonShader projection", PROJECTION_UBO_SIZE, 1);
	private static final List<QueuedDraw> DRAWS = new ArrayList<>();
	private static final Matrix4f PROJECTION = new Matrix4f();

	private static GpuTexture failedColorTarget;
	private static long frameTime = Long.MIN_VALUE;
	private static long lastRenderNanos;

	private ToonShaderRenderer() {
	}

	static void captureProjection(Matrix4fc projection) {
		PROJECTION.set(projection);
	}

	static void queue(PoseStack.Pose pose, float[] positions, float[] normals, float[] tangents,
		float[] smoothNormals, float[] texcoords, float[] backTexcoords, int[] vertexColors, int[] indices,
		int packedLight, Material material,
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
		Matrix4f normalTransform = new Matrix4f().set(new Matrix3f(modelView).mul(pose.normal()));
		Boolean skipExtension = ImmediateState.skipExtension.get();
		try {
			ImmediateState.skipExtension.set(true);
			emit(BUFFER.getVertexBuilder(base), positions, normals, tangents, smoothNormals,
				texcoords, backTexcoords, vertexColors, indices);
		} finally {
			ImmediateState.skipExtension.set(skipExtension);
		}
		ToonShaderModel.Frame viewFrame = new ToonShaderModel.Frame(
			modelView.transformDirection(new Vector3f(frame.headForward())).normalize(),
			modelView.transformDirection(new Vector3f(frame.headRight())).normalize(),
			modelView.transformDirection(new Vector3f(frame.mainLightDirection())).normalize(), frame.night());
		RenderPipeline basePipeline = material.blend
			? material.doubleSided ? BLEND_DOUBLE_SIDED : BLEND_CULL
			: material.doubleSided ? BASE_DOUBLE_SIDED : BASE_CULL;
		DRAWS.add(new QueuedDraw(base, basePipeline,
			material, viewFrame, packedLight, positionTransform, normalTransform));
		if (!material.blend && material.outline && material.outlineWidth > 0.0F) {
			DRAWS.add(new QueuedDraw(base, OUTLINE, material, viewFrame, packedLight,
				positionTransform, normalTransform));
		}
	}

	static void render() {
		if (DRAWS.isEmpty()) {
			return;
		}
		long started = System.nanoTime();
		try {
			Minecraft minecraft = Minecraft.getInstance();
			RenderTarget target = minecraft.gameRenderer.mainRenderTarget();
			if (target.getColorTexture() == null || target.getDepthTexture() == null) {
				return;
			}
			if (target.getColorTexture() == failedColorTarget) {
				return;
			}
			if (!ToonShaderPostProcess.prepare(target, PIPELINES)) {
				return;
			}
			var encoder = RenderSystem.getDevice().createCommandEncoder();
			try {
				BUFFER.upload();
				GpuSampler sceneDepthSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
				GpuTextureView sceneDepthView = target.getDepthTextureView();
				var projectionUniform = PROJECTION_UNIFORMS.writeUniform(new ProjectionUniform(PROJECTION));
				try (RenderPass pass = encoder.createRenderPass(() -> "MCglTF ToonShader depth prepass",
					ToonShaderPostProcess.colorView(), Optional.empty(), ToonShaderPostProcess.depthView(),
					OptionalDouble.of(0.0D))) {
					for (QueuedDraw draw : DRAWS) {
						if (draw.pipeline == OUTLINE || draw.material.blend) {
							continue;
						}
						draw(pass, draw, draw.material.doubleSided ? DEPTH_DOUBLE_SIDED : DEPTH_CULL,
							projectionUniform, sceneDepthView, sceneDepthSampler);
					}
				}
				ToonShaderPostProcess.snapshotDepth(encoder);
				try (RenderPass pass = encoder.createRenderPass(() -> "MCglTF ToonShader HDR pass",
					ToonShaderPostProcess.colorView(), Optional.of(ToonShaderPostProcess.CLEAR),
					ToonShaderPostProcess.depthView(), OptionalDouble.empty())) {
					for (QueuedDraw draw : DRAWS) {
						draw(pass, draw, draw.pipeline, projectionUniform, sceneDepthView, sceneDepthSampler);
					}
				}
				ToonShaderPostProcess.apply(encoder, target);
				failedColorTarget = null;
			} catch (RuntimeException exception) {
				failedColorTarget = target.getColorTexture();
				MCglTF.logger.warn("ToonShader render failed; ShaderPack attachments were not used as Toon targets",
					exception);
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

	private static void draw(RenderPass pass, QueuedDraw draw, RenderPipeline pipeline,
		GpuBufferSlice projectionUniform, GpuTextureView sceneDepthView, GpuSampler sceneDepthSampler) {
		StagedVertexBuffer.ExecuteInfo info = BUFFER.getExecuteInfo(draw.draw);
		if (info == null) {
			return;
		}
		pass.setPipeline(pipeline);
		RenderSystem.bindDefaultUniforms(pass);
		pass.setUniform("ToonProjection", projectionUniform);
		pass.setUniform("ToonMaterial",
			UNIFORMS.writeUniform(new MaterialUniform(draw.material, draw.frame, draw.packedLight,
				draw.positionTransform, draw.normalTransform)));
		bindMaterial(pass, draw.material);
		pass.bindTexture("MinecraftLightmap", Minecraft.getInstance().gameRenderer.levelLightmap(),
			RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
		pass.bindTexture("SceneDepth", sceneDepthView, sceneDepthSampler);
		pass.bindTexture("ToonDepth", ToonShaderPostProcess.sampledDepthView(), sceneDepthSampler);
		pass.setVertexBuffer(0, info.vertexBuffer().slice());
		pass.draw(info.indexCount(), 1, info.baseVertex(), 0);
	}

	static void close() {
		DRAWS.clear();
		BUFFER.close();
		UNIFORMS.close();
		PROJECTION_UNIFORMS.close();
		failedColorTarget = null;
		ToonShaderPostProcess.close();
	}

	private static void bindMaterial(RenderPass pass, Material material) {
		bind(pass, "BaseTexture", material.baseTexture);
		bind(pass, "AlphaTexture", material.alphaTexture);
		bind(pass, "ShadeTexture", material.shadeTexture);
		bind(pass, "NormalTexture", material.normalTexture);
		bind(pass, "EmissionTexture", material.emissionTexture);
		bind(pass, "MatcapTexture", material.matcapTexture);
		bind(pass, "RimTexture", material.rimTexture);
		bind(pass, "OutlineWidthTexture", material.outlineWidthTexture);
		bind(pass, "LightMapTexture", material.lightMapTexture);
		bind(pass, "FaceLightMapTexture", material.faceLightMapTexture);
		bind(pass, "FaceShadowTexture", material.faceShadowTexture);
		bind(pass, "RampTexture", material.rampTexture);
	}

	private static void bind(RenderPass pass, String name, Identifier identifier) {
		AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(identifier);
		pass.bindTexture(name, texture.getTextureView(), texture.getSampler());
	}

	private static void emit(VertexConsumer consumer, float[] positions, float[] normals, float[] tangents,
		float[] smoothNormals, float[] texcoords,
		float[] backTexcoords, int[] colors, int[] indices) {
		Vector3f packedVector = new Vector3f();
		for (int i = 0; i < indices.length; i += 3) {
			emitVertex(consumer, packedVector, positions, normals, tangents, smoothNormals,
				texcoords, backTexcoords, colors, indices[i]);
			emitVertex(consumer, packedVector, positions, normals, tangents, smoothNormals,
				texcoords, backTexcoords, colors, indices[i + 1]);
			emitVertex(consumer, packedVector, positions, normals, tangents, smoothNormals,
				texcoords, backTexcoords, colors, indices[i + 2]);
		}
	}

	private static void emitVertex(VertexConsumer consumer, Vector3f packedVector, float[] positions, float[] normals,
		float[] tangents, float[] smoothNormals,
		float[] texcoords, float[] backTexcoords, int[] colors, int vertex) {
		int p = vertex * 3;
		int tangent = vertex * 4;
		int uv = vertex * 2;
		packedVector.set(tangents[tangent], tangents[tangent + 1], tangents[tangent + 2]);
		int packedTangent = ToonShaderVertexPacking.packTangent(packedVector, tangents[tangent + 3]);
		packedVector.set(smoothNormals[p], smoothNormals[p + 1], smoothNormals[p + 2]);
		int packedSmoothNormal = ToonShaderVertexPacking.packSmoothNormal(packedVector);
		consumer.addVertex(positions[p], positions[p + 1], positions[p + 2], colors[vertex],
			texcoords[uv], texcoords[uv + 1], packUv(backTexcoords[uv], backTexcoords[uv + 1]),
			packedTangent, normals[p], normals[p + 1], normals[p + 2]);
		consumer.setLineWidth(Float.intBitsToFloat(packedSmoothNormal));
	}

	private static int packUv(float u, float v) {
		return pack(u) & 0xFFFF | pack(v) << 16;
	}

	private static int pack(float value) {
		return Math.round(Math.max(-1.0F, Math.min(1.0F, value)) * 32767.0F);
	}

	private static RenderPipeline pipeline(String name, boolean outline, boolean depthOnly, boolean cull,
		boolean writeDepth, boolean blend) {
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
				.withDepthStencilState(outline ? DepthStencilState.DEFAULT
					: new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, writeDepth, 0.0F, 128.0F))
				.withCull(cull);
		if (outline) {
			builder.withShaderDefine("TOON_SHADER_OUTLINE");
		}
		if (depthOnly) {
			builder.withShaderDefine("TOON_SHADER_DEPTH_ONLY")
				.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT,
					ColorTargetState.WRITE_NONE));
		} else {
			builder.withColorTargetState(new ColorTargetState(
				blend ? Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA) : Optional.empty(),
				GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL));
		}
		return RenderPipelines.register(builder.build());
	}

	record Material(Identifier baseTexture, Identifier alphaTexture, Identifier shadeTexture, Identifier normalTexture,
		Identifier emissionTexture, Identifier matcapTexture, Identifier rimTexture,
		Identifier outlineWidthTexture, Identifier lightMapTexture, Identifier faceLightMapTexture,
		Identifier faceShadowTexture,
		Identifier rampTexture, Vector4f baseColor, Vector4f shadeColor, Vector4f emissionColor, Vector4f rimColor,
		Vector4f outlineColor1, Vector4f outlineColor2, Vector4f outlineColor3,
		Vector4f outlineColor4, Vector4f outlineColor5, Vector4f blushColor, Vector4f screenOffset,
		float shadeShift, float shadingToony,
		float shadowOffset, float shadowSmoothness, float giEqualization, float materialType,
		float nonMetalSpecular, float metalSpecular,
		float specularShininess, float emissionIntensity, float rimOffset, float rimThreshold,
		float rimIntensity, float rimPower, float outlineWidth, float outlineDistanceNear,
		float outlineDistanceFar, float outlineScaleNear, float outlineScaleFar,
		float outlineZOffset, float outlineLightingMix,
		float faceShadowStrength, float faceShadowOffset, float blushIntensity, float alphaCutoff, float normalScale, boolean face,
		boolean metallic, boolean outline, boolean outlineScreenSpace, boolean outlineVertexAlpha,
		boolean backUv, boolean directionalFaceSdf, boolean legacyFaceInputs, boolean textureAlpha, boolean blend, boolean doubleSided,
		boolean profiled) {
	}

	private record QueuedDraw(StagedVertexBuffer.Draw draw, RenderPipeline pipeline, Material material,
		ToonShaderModel.Frame frame, int packedLight, Matrix4f positionTransform, Matrix4f normalTransform) {
	}

	private record MaterialUniform(Material material, ToonShaderModel.Frame frame, int packedLight,
		Matrix4f positionTransform, Matrix4f normalTransform)
		implements DynamicUniformStorage.DynamicUniform {
		@Override
		public void write(java.nio.ByteBuffer buffer) {
			Material m = material;
			ToonShaderModel.Frame f = frame;
			Std140Builder.intoBuffer(buffer)
				.putVec4(m.baseColor)
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
					.putVec4(m.outlineWidth, m.outlineZOffset, m.outlineLightingMix,
						m.textureAlpha ? 1.0F : 0.0F)
				.putVec4(m.outlineDistanceNear, m.outlineDistanceFar, m.outlineScaleNear, m.outlineScaleFar)
					.putVec4(m.faceShadowStrength, m.faceShadowOffset, m.blushIntensity,
						m.legacyFaceInputs ? 1.0F : 0.0F)
				.putVec4(m.alphaCutoff, m.outlineScreenSpace ? 1.0F : 0.0F,
					m.outlineVertexAlpha ? 1.0F : 0.0F, m.backUv ? 1.0F : 0.0F)
				.putVec4(m.doubleSided ? 1.0F : 0.0F, f.night(), packedLight & 0xFFFF,
					packedLight >>> 16 & 0xFFFF)
				.putVec4(f.headForward().x, f.headForward().y, f.headForward().z, m.profiled ? 1.0F : 0.0F)
				.putVec4(f.headRight().x, f.headRight().y, f.headRight().z,
					m.directionalFaceSdf ? 1.0F : 0.0F)
				.putVec4(f.mainLightDirection().x, f.mainLightDirection().y,
					f.mainLightDirection().z, m.normalScale)
				.putMat4f(positionTransform)
				.putMat4f(normalTransform);
		}
	}

	private record ProjectionUniform(Matrix4f projection) implements DynamicUniformStorage.DynamicUniform {
		@Override
		public void write(java.nio.ByteBuffer buffer) {
			Std140Builder.intoBuffer(buffer).putMat4f(projection);
		}
	}
}
