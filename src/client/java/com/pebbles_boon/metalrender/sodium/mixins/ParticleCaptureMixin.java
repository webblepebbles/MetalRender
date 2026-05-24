package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.particle.MetalParticleRenderer;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.sodium.mixins.accessor.ParticleManagerAccessor;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ParticleEngine.class)
public class ParticleCaptureMixin {
  @Unique
  private int metalrender$captureFrameCount = 0;

  @Inject(method = "extract", at = @At("HEAD"), cancellable = true, require = 0)
  private void metalrender$captureAndCancelParticles(ParticlesRenderState renderState, Frustum frustum,
      Camera camera, float tickDelta,
      CallbackInfo ci) {
    if (!MetalRenderClient.isEnabled())
      return;
    MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
    if (worldRenderer == null || !worldRenderer.metalActive())
      return;
    int loadingBacklog = worldRenderer.getLoadingModePendingCount();
    if (loadingBacklog > 3000 && (worldRenderer.getFrameCount() % 3) != 0) {
      ci.cancel();
      return;
    }
    MetalParticleRenderer particleRenderer = worldRenderer.getParticleRenderer();
    if (particleRenderer == null || !particleRenderer.isActive())
      return;
    particleRenderer.capture((ParticleEngine) (Object) this, camera,
        tickDelta);
    try {
      ParticleManagerAccessor accessor = (ParticleManagerAccessor) (Object) this;
      Map<ParticleRenderType, ParticleGroup<?>> particlesMap = accessor.metalrender$getParticles();
      if (particlesMap != null) {
        for (Map.Entry<ParticleRenderType, ParticleGroup<?>> entry : particlesMap.entrySet()) {
          ParticleGroup<?> group = entry.getValue();
          if (group != null && !group.isEmpty()) {
            particleRenderer.captureParticleList(group.getAll(),
                camera, tickDelta);
          }
        }
      }
    } catch (Exception e) {
      if (metalrender$captureFrameCount < 5) {
        MetalLogger.error("[ParticleCaptureMixin] Failed to capture: %s",
            e.getMessage());
        e.printStackTrace();
      }
    }
    metalrender$captureFrameCount++;
    if (MetalRenderConfig.isDeepDebugActive() &&
        (metalrender$captureFrameCount <= 3 ||
            metalrender$captureFrameCount % 500 == 0)) {
      MetalLogger.info(
          "[ParticleCaptureMixin] Captured particles frame %d, cancelling GL",
          metalrender$captureFrameCount);
    }
    ci.cancel();
  }
}
