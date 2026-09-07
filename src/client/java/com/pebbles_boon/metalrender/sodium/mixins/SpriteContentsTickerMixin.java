package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.render.MetalTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.renderer.texture.SpriteContents$AnimationState")
public class SpriteContentsTickerMixin {

  @Shadow
  private boolean isDirty;

  @Inject(method = "tick", at = @At("TAIL"))
  private void metalrender$onAnimationTick(CallbackInfo ci) {
    if (isDirty) {
      MetalTextureManager.markAtlasDirty();
    }
  }
}
