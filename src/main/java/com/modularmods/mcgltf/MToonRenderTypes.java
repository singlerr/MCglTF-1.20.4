package com.modularmods.mcgltf;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

final class MToonRenderTypes {
	private static final RenderPipeline SOLID = pipeline("mtoon_entity_solid", false, false);
	private static final RenderPipeline CUTOUT = pipeline("mtoon_entity_cutout", true, false);
	private static final RenderPipeline TRANSLUCENT = pipeline("mtoon_entity_translucent", true, true);

	private MToonRenderTypes() {
	}

	static void bootstrap() {
		// Forces registration before Minecraft loads its shader resources.
	}

	static RenderType create(Identifier texture, boolean cutout, boolean translucent) {
		RenderPipeline pipeline = translucent ? TRANSLUCENT : cutout ? CUTOUT : SOLID;
		RenderSetup.RenderSetupBuilder setup = RenderSetup.builder(pipeline)
			.withTexture("Sampler0", texture)
			.useLightmap();
		if (translucent) {
			setup.sortOnUpload();
		}
		return RenderType.create("mcgltf_mtoon", setup.createRenderSetup());
	}

	private static RenderPipeline pipeline(String name, boolean cutout, boolean translucent) {
		RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath(MCglTF.MODID, "pipeline/" + name))
			.withVertexShader(Identifier.fromNamespaceAndPath(MCglTF.MODID, "core/mtoon_entity"))
			.withFragmentShader(Identifier.fromNamespaceAndPath(MCglTF.MODID, "core/mtoon_entity"))
			.withCull(false);
		if (cutout) {
			builder.withShaderDefine("ALPHA_CUTOUT", 0.1F);
		}
		if (translucent) {
			builder.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT));
		}
		return RenderPipelines.register(builder.build());
	}
}
