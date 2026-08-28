package com.modularmods.mcgltf.api;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;

/** Reload-stable rendered model submission surface for dependent mods. */
public interface GltfRenderable {

	/** Opts this submission into the optional MToon/ToonShader material path. */
	int MTOON_OVERLAY_REQUEST = 15 << 16;

	void submit(int sceneIndex, PoseStack poseStack, SubmitNodeCollector collector, int packedLight, int packedOverlay);

	void submit(int sceneIndex, PoseStack poseStack, SubmitNodeCollector collector, int packedLight, int packedOverlay,
		GltfRenderView view);
}
