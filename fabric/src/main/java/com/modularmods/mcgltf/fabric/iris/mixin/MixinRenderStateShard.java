package com.modularmods.mcgltf.fabric.iris.mixin;

import com.modularmods.mcgltf.fabric.iris.IrisRenderingHook;
import net.minecraft.client.renderer.RenderStateShard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderStateShard.class)
public class MixinRenderStateShard {

    @Inject(method = "setupRenderState()V", at = @At("TAIL"))
    private void afterSetupRenderState(CallbackInfo ci) {
        IrisRenderingHook.irisHookAfterSetupRenderState((RenderStateShard) (Object) this);
    }
}
