package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.render.MetalTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.texture.SpriteContents$Animator")
public class SpriteContentsTickerMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void metalrender$onAnimationTick(CallbackInfo ci) {
        MetalTextureManager.markAtlasDirty();
    }
}
