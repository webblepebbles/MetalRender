package com.pebbles_boon.metalrender.sodium.mixins;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.CapturedMatrices;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.util.MetalLogger;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final; // read Agents.md!
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class WorldRendererBlitMixin {
    @Shadow @Final private LevelRenderState levelRenderState;
    @Unique
    private final Matrix4f metalrender$projection = new Matrix4f();
    @Unique
    private final Matrix4f metalrender$modelView = new Matrix4f();
    @Unique
    private boolean metalrender$frameActive;
    @Unique
    private MetalWorldRenderer metalrender$activeRenderer;
    @Unique
    private int metalrender$beginFrameCount;
    @Unique
    private int metalrender$endFrameCount;

    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void metalrender$beginWorldFrame(
            GraphicsResourceAllocator allocator, boolean renderBlockOutline,
            CameraRenderState cameraRenderState, GpuBufferSlice fogBuffer, Vector4f fogColor,
            boolean renderEntityOutline, boolean extra,
            CallbackInfo ci) {
        metalrender$frameActive = false;
        metalrender$activeRenderer = null;
        DeltaTracker tickCounter = Minecraft.getInstance().getDeltaTracker();
        if ((MetalRenderClient.isBackendMode() && !MetalWorldRenderer.legacyTerrainEnabled())
                || !MetalRenderClient.isEnabled()) {
            return;
        }
        MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
        if (worldRenderer == null || !worldRenderer.metalActive()) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            Camera camera = mc.gameRenderer.mainCamera();
            if (camera == null || camera.position() == null) {
                return;
            }
            float tickDelta = tickCounter.getGameTimeDeltaPartialTick(true);
            if (cameraRenderState != null &&
                    cameraRenderState.projectionMatrix != null) {
                metalrender$projection.set(cameraRenderState.projectionMatrix);
            } else {
                metalrender$projection.identity();
            }
            if (MetalRenderClient.isBackendMode()) {
                CapturedMatrices.copyLevelProjection(metalrender$projection);
            }
            Vec3 camPos = (cameraRenderState != null && cameraRenderState.pos != null)
                    ? cameraRenderState.pos
                    : camera.position();
            if (cameraRenderState != null &&
                    cameraRenderState.viewRotationMatrix != null) {
                metalrender$modelView.set(cameraRenderState.viewRotationMatrix);
            } else {
                metalrender$modelView.identity();
                metalrender$modelView.rotateX((float) Math.toRadians(camera.xRot()));
                metalrender$modelView.rotateY(
                        (float) Math.toRadians(camera.yRot() + 180.0f));
            }
            CapturedMatrices.capture(metalrender$projection, metalrender$modelView,
                    camPos.x, camPos.y, camPos.z);
            try {
                com.pebbles_boon.metalrender.render.fog.VanillaFog.FogState fog = com.pebbles_boon.metalrender.render.fog.VanillaFog
                        .fromRenderState(cameraRenderState);
                if (fog != null) {
                    worldRenderer.setFogState(fog);
                }
            } catch (Exception ignored) {
            }
            worldRenderer.beginFrame(camera, tickDelta, metalrender$projection,
                    metalrender$modelView, camPos.x, camPos.y, camPos.z);
            com.pebbles_boon.metalrender.performance.MetalRenderProfiler.getInstance().startRender();
            metalrender$frameActive = true;
            metalrender$activeRenderer = worldRenderer;
            metalrender$beginFrameCount++;
            if (metalrender$beginFrameCount <= 3) {
                MetalLogger.info("[blitmix] begin hook #%d",
                        metalrender$beginFrameCount);
            }
        } catch (Exception e) {
            MetalLogger.error("[blitmix] begin fail: %s", e.getMessage());
        }
    }

    @Unique
    private static final boolean metalrender$SKIP_IDLE_OUTLINE = com.pebbles_boon.metalrender.config.MetalRenderConfig
            .getBool("metalrender.skipIdleOutlineBlit", true);

    @Unique
    private boolean metalrender$anyOutline = true;

    @Inject(method = "addMainPass", at = @At("HEAD"), require = 0)
    private void metalrender$noteOutline(com.mojang.blaze3d.framegraph.FrameGraphBuilder frameGraph,
            net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame frame,
            GpuBufferSlice fogBuffer,
            net.minecraft.client.renderer.chunk.ChunkSectionsToRender sections, boolean wireframe,
            CallbackInfo ci) {
        metalrender$anyOutline = frame == null || frame.hasAnyOutline();
    }

    @Inject(method = "executeOutline", at = @At("HEAD"), cancellable = true, require = 0)
    private void metalrender$skipIdleOutline(CallbackInfo ci) {
        if (metalrender$SKIP_IDLE_OUTLINE && !metalrender$anyOutline && MetalRenderClient.isBackendMode()) {
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At("RETURN"), require = 1)
    private void metalrender$endWorldFrame(
            GraphicsResourceAllocator allocator, boolean renderBlockOutline,
            CameraRenderState cameraRenderState, GpuBufferSlice fogBuffer, Vector4f fogColor,
            boolean renderEntityOutline, boolean extra,
            CallbackInfo ci) {
        if (!metalrender$frameActive) {
            return;
        }
        metalrender$frameActive = false;
        MetalWorldRenderer worldRenderer = metalrender$activeRenderer;
        metalrender$activeRenderer = null;
        if (worldRenderer == null) {
            worldRenderer = MetalRenderClient.getWorldRenderer();
        }
        if (worldRenderer == null) {
            return;
        }
        try {
            com.pebbles_boon.metalrender.performance.MetalRenderProfiler.getInstance().endRender();
            if (MetalRenderClient.isBackendMode()) {
                worldRenderer.endFrameExternal();
            } else {
                worldRenderer.endFrame(levelRenderState.blockBreakingRenderStates,
                        levelRenderState.blockEntityRenderStates);
            }
            metalrender$endFrameCount++;
            if (metalrender$endFrameCount <= 3) {
                MetalLogger.info("[blitmix] end hook #%d",
                        metalrender$endFrameCount);
            }
        } catch (Exception e) {
            MetalLogger.error("[blitmix] end fail: %s", e.getMessage());
        }
    }
}
// read Agents.md!