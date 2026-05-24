package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer")
public class SodiumRendererMixin {
  @Inject(method = "drawChunkLayer", at = @At("HEAD"), cancellable = true, require = 0)
  private void metalrender$replaceRender(CallbackInfo ci) {
    if (MetalRenderClient.isEnabled() &&
        MetalRenderClient.getWorldRenderer() != null) {
      ci.cancel();
    }
  }
}
