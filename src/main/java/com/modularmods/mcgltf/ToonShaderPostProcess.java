package com.modularmods.mcgltf;

import java.util.Optional;
import java.util.OptionalDouble;

import org.joml.Vector4f;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

final class ToonShaderPostProcess {
	private static final int BLOOM_LEVELS = 4;
	private static final int COLOR_USAGE = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
	static final Vector4f CLEAR = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);
	private static final BindGroupLayout SOURCE_LAYOUT = BindGroupLayout.builder()
		.withSampler("ToonSource")
		.build();
	private static final BindGroupLayout BLUR_LAYOUT = BindGroupLayout.builder()
		.withSampler("ToonSource")
		.withSampler("ToonFull")
		.build();
	private static final BindGroupLayout UPSAMPLE_LAYOUT = BindGroupLayout.builder()
		.withSampler("BloomA")
		.withSampler("BloomB")
		.withSampler("BloomC")
		.withSampler("BloomD")
		.build();
	private static final BindGroupLayout RESOLVE_LAYOUT = BindGroupLayout.builder()
		.withSampler("ToonHdr")
		.withSampler("BloomTexture")
		.build();
	private static final RenderPipeline PREFILTER = pipeline("toon_bloom_prefilter", "toon_bloom_prefilter",
		SOURCE_LAYOUT, GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL, false);
	private static final RenderPipeline BLUR_HORIZONTAL_1X = pipeline("toon_bloom_horizontal_1x", "toon_bloom_blur",
		BLUR_LAYOUT, GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL, false, "TOON_HORIZONTAL");
	private static final RenderPipeline BLUR_HORIZONTAL_2X = pipeline("toon_bloom_horizontal_2x", "toon_bloom_blur",
		BLUR_LAYOUT, GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL, false,
		"TOON_HORIZONTAL", "TOON_DOUBLE_RADIUS");
	private static final RenderPipeline BLUR_VERTICAL_1X = pipeline("toon_bloom_vertical_1x", "toon_bloom_blur",
		BLUR_LAYOUT, GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL, false);
	private static final RenderPipeline UPSAMPLE = pipeline("toon_bloom_upsample", "toon_bloom_upsample",
		UPSAMPLE_LAYOUT, GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL, false);
	private static final RenderPipeline RESOLVE = pipeline("toon_post_resolve", "toon_post_resolve",
		RESOLVE_LAYOUT, GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_COLOR, true);
	private static final RenderPipeline[] PIPELINES = {PREFILTER, BLUR_HORIZONTAL_1X, BLUR_HORIZONTAL_2X,
		BLUR_VERTICAL_1X, UPSAMPLE, RESOLVE};

	private static GpuTexture toonHdr;
	private static GpuTextureView toonHdrView;
	private static GpuTexture toonDepth;
	private static GpuTextureView toonDepthView;
	private static GpuTexture toonDepthSnapshot;
	private static GpuTextureView toonDepthSnapshotView;
	private static final GpuTexture[] bloomA = new GpuTexture[BLOOM_LEVELS];
	private static final GpuTextureView[] bloomAViews = new GpuTextureView[BLOOM_LEVELS];
	private static final GpuTexture[] bloomB = new GpuTexture[BLOOM_LEVELS];
	private static final GpuTextureView[] bloomBViews = new GpuTextureView[BLOOM_LEVELS];
	private static GpuTexture failedColor;
	private static GpuTexture failedDepth;

	private ToonShaderPostProcess() {
	}

	static boolean prepare(RenderTarget target, RenderPipeline... toonPipelines) {
		GpuTexture color = target.getColorTexture();
		GpuTexture depth = target.getDepthTexture();
		if (color == null || depth == null || color.getFormat() != GpuFormat.RGBA8_UNORM
			|| !depth.getFormat().hasDepthAspect()
			|| (color.usage() & GpuTexture.USAGE_RENDER_ATTACHMENT) == 0
			|| (depth.usage() & GpuTexture.USAGE_TEXTURE_BINDING) == 0
			|| target.getColorTextureView() == null) {
			return false;
		}
		if (color == failedColor && depth == failedDepth) {
			return false;
		}
		if (toonHdr != null && toonDepth != null && toonDepthSnapshot != null
			&& toonHdrView != null && toonDepthView != null && toonDepthSnapshotView != null
			&& toonHdr.getWidth(0) == target.width && toonHdr.getHeight(0) == target.height
			&& toonDepth.getFormat() == depth.getFormat()) {
			return true;
		}
		close();
		try {
			for (RenderPipeline pipeline : PIPELINES) {
				if (!RenderSystem.getDevice().precompilePipeline(pipeline).isValid()) {
					throw new IllegalStateException("Could not compile " + pipeline.getLocation());
				}
			}
			for (RenderPipeline pipeline : toonPipelines) {
				if (!RenderSystem.getDevice().precompilePipeline(pipeline).isValid()) {
					throw new IllegalStateException("Could not compile " + pipeline.getLocation());
				}
			}
			toonHdr = texture("MCglTF ToonShader HDR", target.width, target.height);
			toonHdrView = RenderSystem.getDevice().createTextureView(toonHdr);
			toonDepth = RenderSystem.getDevice().createTexture("MCglTF ToonShader depth",
				GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
				depth.getFormat(), target.width, target.height, 1, 1);
			toonDepthView = RenderSystem.getDevice().createTextureView(toonDepth);
			toonDepthSnapshot = RenderSystem.getDevice().createTexture("MCglTF ToonShader sampled depth",
				GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
				depth.getFormat(), target.width, target.height, 1, 1);
			toonDepthSnapshotView = RenderSystem.getDevice().createTextureView(toonDepthSnapshot);
			int width = Math.max(1, Math.round(target.width * 0.5F));
			int height = Math.max(1, Math.round(target.height * 0.5F));
			for (int i = 0; i < BLOOM_LEVELS; i++) {
				bloomA[i] = texture("MCglTF ToonShader bloom A" + i, width, height);
				bloomAViews[i] = RenderSystem.getDevice().createTextureView(bloomA[i]);
				bloomB[i] = texture("MCglTF ToonShader bloom B" + i, width, height);
				bloomBViews[i] = RenderSystem.getDevice().createTextureView(bloomB[i]);
				width = Math.max(1, width / 2);
				height = Math.max(1, height / 2);
			}
			failedColor = null;
			failedDepth = null;
			return true;
		} catch (RuntimeException exception) {
			closeResources();
			failedColor = color;
			failedDepth = depth;
			MCglTF.logger.warn("Disabling ToonShader for an unsupported HDR/post-process contract", exception);
			return false;
		}
	}

	static GpuTextureView colorView() {
		return toonHdrView;
	}

	static GpuTextureView depthView() {
		return toonDepthView;
	}

	static GpuTextureView sampledDepthView() {
		return toonDepthSnapshotView;
	}

	static void snapshotDepth(CommandEncoder encoder) {
		encoder.copyTextureToTexture(toonDepth, toonDepthSnapshot, 0, 0, 0, 0, 0,
			toonDepth.getWidth(0), toonDepth.getHeight(0));
	}

	static void apply(CommandEncoder encoder, RenderTarget target) {
		GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		sourcePass(encoder, "prefilter", bloomAViews[0], PREFILTER, toonHdrView, linear);
		blurPass(encoder, "horizontal 1x", bloomBViews[0], BLUR_HORIZONTAL_1X, bloomAViews[0], linear);
		blurPass(encoder, "vertical 1x", bloomAViews[0], BLUR_VERTICAL_1X, bloomBViews[0], linear);
		for (int i = 1; i < BLOOM_LEVELS; i++) {
			blurPass(encoder, "horizontal 2x " + i, bloomBViews[i], BLUR_HORIZONTAL_2X,
				bloomAViews[i - 1], linear);
			blurPass(encoder, "vertical 1x " + i, bloomAViews[i], BLUR_VERTICAL_1X, bloomBViews[i], linear);
		}
		try (RenderPass pass = encoder.createRenderPass(() -> "MCglTF ToonShader bloom upsample",
			bloomBViews[0], Optional.empty(), null, OptionalDouble.empty())) {
			pass.setPipeline(UPSAMPLE);
			RenderSystem.bindDefaultUniforms(pass);
			pass.bindTexture("BloomA", bloomAViews[0], linear);
			pass.bindTexture("BloomB", bloomAViews[1], linear);
			pass.bindTexture("BloomC", bloomAViews[2], linear);
			pass.bindTexture("BloomD", bloomAViews[3], linear);
			pass.draw(3, 1, 0, 0);
		}
		compositePass(encoder, target, "post resolve", RESOLVE, linear);
	}

	private static void compositePass(CommandEncoder encoder, RenderTarget target, String name,
		RenderPipeline pipeline, GpuSampler sampler) {
		try (RenderPass pass = encoder.createRenderPass(() -> "MCglTF ToonShader " + name,
			target.getColorTextureView(), Optional.empty(), null, OptionalDouble.empty())) {
			pass.setPipeline(pipeline);
			RenderSystem.bindDefaultUniforms(pass);
			pass.bindTexture("ToonHdr", toonHdrView, sampler);
			pass.bindTexture("BloomTexture", bloomBViews[0], sampler);
			pass.draw(3, 1, 0, 0);
		}
	}

	static void close() {
		closeResources();
		failedColor = null;
		failedDepth = null;
	}

	private static void closeResources() {
		close(toonHdrView);
		toonHdrView = null;
		close(toonHdr);
		toonHdr = null;
		close(toonDepthView);
		toonDepthView = null;
		close(toonDepth);
		toonDepth = null;
		close(toonDepthSnapshotView);
		toonDepthSnapshotView = null;
		close(toonDepthSnapshot);
		toonDepthSnapshot = null;
		for (int i = 0; i < BLOOM_LEVELS; i++) {
			close(bloomAViews[i]);
			bloomAViews[i] = null;
			close(bloomA[i]);
			bloomA[i] = null;
			close(bloomBViews[i]);
			bloomBViews[i] = null;
			close(bloomB[i]);
			bloomB[i] = null;
		}
	}

	private static void sourcePass(CommandEncoder encoder, String name, GpuTextureView destination,
		RenderPipeline pipeline, GpuTextureView source, GpuSampler sampler) {
		try (RenderPass pass = encoder.createRenderPass(() -> "MCglTF ToonShader bloom " + name,
			destination, Optional.empty(), null, OptionalDouble.empty())) {
			pass.setPipeline(pipeline);
			RenderSystem.bindDefaultUniforms(pass);
			pass.bindTexture("ToonSource", source, sampler);
			pass.draw(3, 1, 0, 0);
		}
	}

	private static void blurPass(CommandEncoder encoder, String name, GpuTextureView destination,
		RenderPipeline pipeline, GpuTextureView source, GpuSampler sampler) {
		try (RenderPass pass = encoder.createRenderPass(() -> "MCglTF ToonShader bloom " + name,
			destination, Optional.empty(), null, OptionalDouble.empty())) {
			pass.setPipeline(pipeline);
			RenderSystem.bindDefaultUniforms(pass);
			pass.bindTexture("ToonSource", source, sampler);
			pass.bindTexture("ToonFull", toonHdrView, sampler);
			pass.draw(3, 1, 0, 0);
		}
	}

	private static GpuTexture texture(String name, int width, int height) {
		return RenderSystem.getDevice().createTexture(name, COLOR_USAGE, GpuFormat.RGBA16_FLOAT,
			width, height, 1, 1);
	}

	private static void close(AutoCloseable resource) {
		if (resource != null) {
			try {
				resource.close();
			} catch (Exception exception) {
				throw new RuntimeException(exception);
			}
		}
	}

	private static RenderPipeline pipeline(String name, String shader, BindGroupLayout layout,
		GpuFormat format, int writeMask, boolean blend, String... defines) {
		RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath(MCglTF.MODID, "pipeline/" + name))
			.withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
			.withFragmentShader(Identifier.fromNamespaceAndPath(MCglTF.MODID, "core/" + shader))
			.withBindGroupLayout(layout)
			.withColorTargetState(new ColorTargetState(blend
				? Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA) : Optional.empty(), format, writeMask))
			.withCull(false);
		for (String define : defines) {
			builder.withShaderDefine(define);
		}
		return RenderPipelines.register(builder.build());
	}
}
