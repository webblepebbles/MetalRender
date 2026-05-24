package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.util.MetalLogger;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.renderer.LevelRenderer", remap = false)
public class WorldRendererTerrainMixin {
  @Unique
  private int metalrender$skippedTerrainGroups = 0;
  @Unique
  private boolean metalrender$loggedWaitingForMetalDraw = false;

  @Unique
  private boolean metalrender$shouldSkipVanillaTerrain() {
    if (!MetalRenderClient.isEnabled()) {
      metalrender$loggedWaitingForMetalDraw = false;
      return false;
    }
    MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
    if (wr == null || !wr.metalActive()) {
      metalrender$loggedWaitingForMetalDraw = false;
      return false;
    }

    if (!metalrender$loggedWaitingForMetalDraw) {
      MetalLogger.info("[WorldRendererTerrainMixin] Metal active; vanilla terrain suppression locked on");
      metalrender$loggedWaitingForMetalDraw = true;
    }
    return true;
  }

  @Redirect(method = "lambda$addMainPass$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V", ordinal = 0), require = 0)
  private void metalrender$skipOpaqueTerrainGroup(ChunkSectionsToRender sections,
      ChunkSectionLayerGroup group, GpuSampler sampler) {
    if (metalrender$shouldSkipVanillaTerrain()) {
      metalrender$skippedTerrainGroups++;
      if (metalrender$skippedTerrainGroups <= 3 || metalrender$skippedTerrainGroups % 1000 == 0) {
        MetalLogger.info("[WorldRendererTerrainMixin] Skipped vanilla terrain group #%d (%s)",
            metalrender$skippedTerrainGroups, String.valueOf(group));
      }
      return;
    }
    sections.renderGroup(group, sampler);
  }

  @Redirect(method = "lambda$addMainPass$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V", ordinal = 1), require = 0)
  private void metalrender$skipTranslucentTerrainGroup(ChunkSectionsToRender sections,
      ChunkSectionLayerGroup group, GpuSampler sampler) {
    if (metalrender$shouldSkipVanillaTerrain()) {
      metalrender$skippedTerrainGroups++;
      if (metalrender$skippedTerrainGroups <= 3 || metalrender$skippedTerrainGroups % 1000 == 0) {
        MetalLogger.info("[WorldRendererTerrainMixin] Skipped vanilla terrain group #%d (%s)",
            metalrender$skippedTerrainGroups, String.valueOf(group));
      }
      return;
    }
    sections.renderGroup(group, sampler);
  }

  @Inject(method = "lambda$addMainPass$0", at = @At("HEAD"), require = 0)
  private void metalrender$terrainHookHeartbeat(CallbackInfo ci) {
    if (metalrender$shouldSkipVanillaTerrain() && metalrender$skippedTerrainGroups == 0) {
      MetalLogger.info("[WorldRendererTerrainMixin] Terrain redirect hook active");
    }
  }
}
