package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.gui.MetalGuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.mojang.blaze3d.systems.RenderSystem", remap = false)
public class RenderSystemFlipMixin {
  @Inject(method = "flipFrame", at = @At("TAIL"), remap = false, require = 0)
  private static void metalrender$afterFlip(CallbackInfo ci) {
    if (MetalRenderClient.isEnabled() &&
        MetalGuiRenderer.isOverlayModeEnabled()) {
      MetalGuiRenderer.getInstance().endFrame();
    }
  }
}
