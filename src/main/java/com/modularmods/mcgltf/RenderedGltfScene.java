package com.modularmods.mcgltf;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;

public final class RenderedGltfScene {

	private final List<RenderedGltfModel.Primitive> primitives;
	private final RenderedGltfModel.FrameSnapshots frameSnapshots = new RenderedGltfModel.FrameSnapshots();
	private long snapshotFrameTime = Long.MIN_VALUE;
	private boolean shaderModActive;

	RenderedGltfScene(List<RenderedGltfModel.Primitive> primitives) {
		this.primitives = List.copyOf(primitives);
	}

	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int packedLight, int packedOverlay) {
		submit(poseStack, collector, packedLight, packedOverlay, RenderedGltfModel.FULL_VIEW);
	}

	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int packedLight, int packedOverlay,
		RenderedGltfModel.RenderView view) {
		long frameTime = Minecraft.getInstance().getFrameTimeNs();
		if (frameTime != snapshotFrameTime) {
			// ponytail: one shared model pose is snapshotted per Minecraft frame; use separate
			// rendered models if their glTF node state must differ within the same frame.
			snapshotFrameTime = frameTime;
			frameSnapshots.clear();
			shaderModActive = MCglTF.getInstance().isShaderModActive();
		}
		for (RenderedGltfModel.Primitive primitive : primitives) {
			primitive.submit(poseStack, collector, packedLight, packedOverlay, frameSnapshots, shaderModActive, view);
		}
	}

	public int getPrimitiveCount() {
		return primitives.size();
	}

	public int getSubmittedVertexCount() {
		return primitives.stream().mapToInt(RenderedGltfModel.Primitive::submittedVertexCount).sum();
	}
}
