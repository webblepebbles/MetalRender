package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.chunk.DataLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(LevelLightEngine.class)
public class LightUpdateMixin {
  @Inject(method = "queueSectionData", at = @At("HEAD"), require = 0)
  private void metalrender$onLightData(LightLayer layer, SectionPos pos,
      DataLayer data, CallbackInfo ci) {
    try {
      if (pos == null) {
        return;
      }
      MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
      if (worldRenderer == null) {
        return;
      }
      worldRenderer.onLightDataApplied(pos.getX(), pos.getY(), pos.getZ());
    } catch (Exception ignored) {
    }
  }
}
