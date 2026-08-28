package com.modularmods.mcgltf.gametest.mixin;

import com.modularmods.mcgltf.gametest.MCglTFGameTestClient;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
abstract class ItemInHandRendererMixin {

	@Inject(method = "submitHandsWithItems", at = @At("RETURN"))
	private void mcgltf$submitTestVrm(float partialTick, PoseStack poseStack, SubmitNodeCollector collector,
		LocalPlayer player, int packedLight, CallbackInfo callbackInfo) {
		MCglTFGameTestClient.submitHand(poseStack, collector, packedLight);
	}
}
