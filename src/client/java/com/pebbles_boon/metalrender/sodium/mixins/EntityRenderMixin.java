package com.pebbles_boon.metalrender.sodium.mixins;

import com.mojang.blaze3d.vertex.PoseStack;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.entity.MetalEntityRenderer;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.util.MetalLogger;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class EntityRenderMixin {
    @Unique
    private static final double metalrender$hardCullDistSq = 40.0 * 40.0;

    @Unique
    private static final double metalrender$rateLimitDistSq = 20.0 * 20.0;

    @Unique
    private static final double metalrender$nearRateDistSq = 28.0 * 28.0;

    @Unique
    private static final int metalrender$rateMid = 3;

    @Unique
    private static final int metalrender$rateFar = 6;

    @Unique
    private static final int metalrender$softEntityBudget = 48;

    @Unique
    private static final int metalrender$hardEntityBudget = 96;

    @Unique
    private int metalrender$entityCaptureCount = 0;

    @Unique
    private int metalrender$entityCullCount = 0;

    @Unique
    private long metalrender$entityCullFrame = 0;

    @Unique
    private final Matrix4f metalrender$reusableModelMatrix = new Matrix4f();

    @Inject(method = "extractVisibleEntities", at = @At("TAIL"), require = 0)
    private void metalrender$captureEntities(Camera camera, Frustum frustum,
            DeltaTracker deltaTracker, LevelRenderState levelRenderState,
            CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled()) {
            return;
        }
        MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
        if (worldRenderer == null || !worldRenderer.metalActive()) {
            return;
        }
        MetalEntityRenderer entityRenderer = worldRenderer.getEntityRenderer();
        if (entityRenderer == null || !entityRenderer.isActive()) {
            return;
        }
        int loadingBacklog = worldRenderer.getLoadingModePendingCount();
        if (loadingBacklog > 3000 && (worldRenderer.getFrameCount() & 1) != 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            return;
        }
        float tickDelta = deltaTracker.getGameTimeDeltaPartialTick(true);
        try {
            metalrender$entityCullFrame++;
            int capturedThisFrame = 0;
            int culledThisFrame = 0;
            Entity focused = camera.entity();
            Vec3 cameraPos = camera.position();
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity == null || entity.isRemoved()) {
                    continue;
                }
                if (entity == focused && !camera.isDetached()) {
                    continue;
                }
                if (!frustum.isVisible(entity.getBoundingBox())) {
                    continue;
                }
                if (!(entity instanceof Player)) {
                    double distSq = entity.distanceToSqr(cameraPos);
                    if (distSq > metalrender$hardCullDistSq) {
                        culledThisFrame++;
                        continue;
                    }
                    if (distSq > metalrender$rateLimitDistSq) {
                        int rate = distSq > metalrender$nearRateDistSq
                                ? metalrender$rateFar
                                : metalrender$rateMid;
                        if ((metalrender$entityCullFrame + entity.getId()) % rate != 0) {
                            culledThisFrame++;
                            continue;
                        }
                    }
                    if (capturedThisFrame >= metalrender$hardEntityBudget) {
                        if ((metalrender$entityCullFrame + entity.getId()) % 4 != 0) {
                            culledThisFrame++;
                            continue;
                        }
                    } else if (capturedThisFrame >= metalrender$softEntityBudget) {
                        if ((metalrender$entityCullFrame + entity.getId()) % 2 != 0) {
                            culledThisFrame++;
                            continue;
                        }
                    }
                }
                Matrix4f modelMatrix = metalrender$reusableModelMatrix;
                modelMatrix.identity();
                entityRenderer.captureEntity(entity, tickDelta, modelMatrix);
                capturedThisFrame++;
            }
            metalrender$entityCaptureCount += capturedThisFrame;
            metalrender$entityCullCount += culledThisFrame;
            if (MetalRenderConfig.isDeepDebugActive() &&
                    (capturedThisFrame > 0 || culledThisFrame > 0) &&
                    (metalrender$entityCullFrame <= 5 ||
                            metalrender$entityCullFrame % 600 == 0)) {
                MetalLogger.info(
                        "[EntityRenderMixin] Captured %d entities, culled %d this frame (totals: captured=%d culled=%d)",
                        capturedThisFrame, culledThisFrame,
                        metalrender$entityCaptureCount, metalrender$entityCullCount);
            }
        } catch (Exception e) {
            if (metalrender$entityCaptureCount < 10) {
                MetalLogger.error("[EntityRenderMixin] Failed to capture entities: %s",
                        e.getMessage());
            }
        }
    }

    @Inject(method = "submitEntities", at = @At("HEAD"), cancellable = true, require = 0)
    private void metalrender$suppressVanillaEntities(PoseStack matrices,
            LevelRenderState renderStates,
            SubmitNodeCollector queue,
            CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled()) {
            return;
        }
        MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
        if (worldRenderer != null && worldRenderer.metalActive()) {
            ci.cancel();
        }
    }
}