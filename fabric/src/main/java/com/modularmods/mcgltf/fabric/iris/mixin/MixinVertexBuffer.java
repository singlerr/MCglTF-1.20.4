package com.modularmods.mcgltf.fabric.iris.mixin;

import com.modularmods.mcgltf.fabric.iris.IrisRenderingHook;
import com.mojang.blaze3d.vertex.VertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VertexBuffer.class)
public class MixinVertexBuffer {

    @Inject(method = "draw()V", at = @At("TAIL"))
    private void afterDraw(CallbackInfo ci) {
        IrisRenderingHook.irisHookAfterVertexBufferDraw();
    }
}
