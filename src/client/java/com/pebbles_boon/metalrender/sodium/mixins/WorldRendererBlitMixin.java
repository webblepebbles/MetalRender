package com.pebbles_boon.metalrender.sodium.mixins;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.backend.MetalRenderer;
import com.pebbles_boon.metalrender.render.CapturedMatrices;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.util.MetalLogger;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class WorldRendererBlitMixin {
    @Unique
    private final Matrix4f metalrender$projection = new Matrix4f();
    @Unique
    private final Matrix4f metalrender$modelView = new Matrix4f();
    @Unique
    private boolean metalrender$frameActive;
    @Unique
    private int metalrender$beginFrameCount;
    @Unique
    private int metalrender$endFrameCount;

    @Inject(method = "renderLevel", at = @At("HEAD"), require = 0)
    private void metalrender$beginWorldFrame(GraphicsResourceAllocator allocator,
            DeltaTracker tickCounter, boolean renderBlockOutline,
            CameraRenderState cameraRenderState, Matrix4fc positionMatrix,
            GpuBufferSlice fogBuffer, Vector4f fogColor, boolean renderEntityOutline,
            ChunkSectionsToRender sectionsToRender, CallbackInfo ci) {
        metalrender$frameActive = false;
        if (!MetalRenderClient.isEnabled()) {
            return;
        }
        MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
        if (worldRenderer == null || !worldRenderer.metalActive()) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            Camera camera = mc.gameRenderer.getMainCamera();
            if (camera == null || camera.position() == null) {
                return;
            }
            float tickDelta = tickCounter.getGameTimeDeltaPartialTick(true);
            if (cameraRenderState != null && cameraRenderState.projectionMatrix != null) {
                metalrender$projection.set(cameraRenderState.projectionMatrix);
            } else {
                metalrender$projection.set(positionMatrix);
            }
            Vec3 camPos = (cameraRenderState != null && cameraRenderState.pos != null)
                    ? cameraRenderState.pos
                    : camera.position();
            if (cameraRenderState != null && cameraRenderState.viewRotationMatrix != null) {
                metalrender$modelView.set(cameraRenderState.viewRotationMatrix);
            } else {
                metalrender$modelView.identity();
                metalrender$modelView.rotateX((float) Math.toRadians(camera.xRot()));
                metalrender$modelView.rotateY((float) Math.toRadians(camera.yRot() + 180.0f));
            }
            CapturedMatrices.capture(metalrender$projection, metalrender$modelView,
                    camPos.x, camPos.y, camPos.z);
            worldRenderer.beginFrame(camera, tickDelta, metalrender$projection,
                    metalrender$modelView);
            metalrender$frameActive = true;
            metalrender$beginFrameCount++;
            if (metalrender$beginFrameCount <= 3) {
                MetalLogger.info("[WorldRendererBlitMixin] renderLevel begin hook #%d",
                        metalrender$beginFrameCount);
            }
        } catch (Exception e) {
            MetalLogger.error("[WorldRendererBlitMixin] Metal frame begin failed: %s",
                    e.getMessage());
        }
    }

    @Inject(method = "renderLevel", at = @At("TAIL"), require = 0)
    private void metalrender$endWorldFrame(GraphicsResourceAllocator allocator,
            DeltaTracker tickCounter, boolean renderBlockOutline,
            CameraRenderState cameraRenderState, Matrix4fc positionMatrix,
            GpuBufferSlice fogBuffer, Vector4f fogColor, boolean renderEntityOutline,
            ChunkSectionsToRender sectionsToRender, CallbackInfo ci) {
        if (!metalrender$frameActive) {
            return;
        }
        metalrender$frameActive = false;
        MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
        if (worldRenderer == null) {
            return;
        }
        try {
            worldRenderer.endFrame();
            MetalRenderer renderer = MetalRenderClient.getRenderer();
            if (renderer != null) {
                long handle = renderer.getHandle();
                if (handle != 0) {
                    worldRenderer.buildMeshesDuringWait(handle);
                }
            }
            metalrender$endFrameCount++;
            if (metalrender$endFrameCount <= 3) {
                MetalLogger.info("[WorldRendererBlitMixin] renderLevel end hook #%d",
                        metalrender$endFrameCount);
            }
        } catch (Exception e) {
            MetalLogger.error("[WorldRendererBlitMixin] Metal frame end failed: %s",
                    e.getMessage());
        }
    }
}
