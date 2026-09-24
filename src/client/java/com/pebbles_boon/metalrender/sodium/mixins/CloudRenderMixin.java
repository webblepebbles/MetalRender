package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;







@Mixin(CloudRenderer.class)
public class CloudRenderMixin {
  @Inject(method = "render(IIFILnet/minecraft/world/phys/Vec3;JF)V",
      at = @At("HEAD"), cancellable = true, require = 0)
  private void metalrender$cancelVanillaClouds(CallbackInfo ci) {
    if (!MetalRenderClient.isEnabled()) {
      return;
    }
    MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
    if (worldRenderer != null && worldRenderer.metalActive()) {
      ci.cancel();
    }
  }


  @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
  private void metalrender$cancelVanillaCloudsAny(CallbackInfo ci) {
    if (!MetalRenderClient.isEnabled()) {
      return;
    }
    MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
    if (worldRenderer != null && worldRenderer.metalActive()) {
      ci.cancel();
    }
  }
}
