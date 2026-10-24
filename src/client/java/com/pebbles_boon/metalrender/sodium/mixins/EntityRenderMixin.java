package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.entity.MetalEntityRenderer;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.util.MetalLogger;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.client.renderer.extract.LevelExtractor.class)
public class EntityRenderMixin {
    @Shadow
    private ClientLevel level;

    @Unique
    private static final double metalrender$hardCullDistSq = 128.0 * 128.0;

    @Unique
    private int metalrender$entityCaptureCount = 0;

    @Unique
    private int metalrender$blockEntityFrames = 0;

    @Unique
    private int metalrender$entityCullCount = 0;

    @Unique
    private long metalrender$entityCullFrame = 0;

    @Unique
    private final Matrix4f metalrender$reusableModelMatrix = new Matrix4f();

    @Unique
    private static final it.unimi.dsi.fastutil.objects.ObjectArrayList<?> metalrender$noSections =
            new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();

    @org.spongepowered.asm.mixin.injection.Redirect(method = "extract", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;visibleSections()Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"),
            require = 0)
    private it.unimi.dsi.fastutil.objects.ObjectArrayList metalrender$skipSectionUpdates(
            net.minecraft.client.renderer.LevelRenderer levelRenderer) {
        MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
        if (MetalRenderClient.isEnabled() && wr != null && wr.metalActive()
                && MetalRenderConfig.getBool("metalrender.cancelVanillaCompile", true)) {
            return metalrender$noSections;
        }
        return levelRenderer.visibleSections();
    }

    @Inject(method = "extractVisibleEntities", at = @At("TAIL"), require = 0)
    private void metalrender$captureEntities(Camera camera, Frustum frustum,
            DeltaTracker deltaTracker,
            LevelRenderState levelRenderState,
            CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled()) {
            return;
        }
        MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
        if (worldRenderer == null || !worldRenderer.captureActive()) {
            return;
        }
        MetalEntityRenderer entityRenderer = worldRenderer.getEntityRenderer();
        if (entityRenderer == null || !entityRenderer.isActive()) {
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
                MetalLogger.info("[entitymix] cap=%d cull=%d (tot cap=%d tot cull=%d)",
                        capturedThisFrame, culledThisFrame,
                        metalrender$entityCaptureCount,
                        metalrender$entityCullCount);
            }
        } catch (Throwable e) {
            if (metalrender$entityCaptureCount < 10) {
                MetalLogger.error("[entitymix] cap fail: %s", e.getMessage());
            }
        }
    }

    @Inject(method = "extractVisibleBlockEntities", at = @At("TAIL"), require = 0)
    private void metalrender$extractBlockEntities(Camera camera, float tickDelta,
            LevelRenderState levelRenderState, CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled()) {
            return;
        }
        MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
        if (worldRenderer == null || level == null ||
                !(worldRenderer.captureActive() || MetalRenderClient.isBackendMode())) {
            return;
        }
        metalrender$blockEntityFrames++;
        Vec3 cameraPos = camera.position();
        int chunkX = ((int) Math.floor(cameraPos.x)) >> 4;
        int chunkZ = ((int) Math.floor(cameraPos.z)) >> 4;
        int radius = 6;
        for (int cx = chunkX - radius; cx <= chunkX + radius; cx++) {
            for (int cz = chunkZ - radius; cz <= chunkZ + radius; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (blockEntity.isRemoved() ||
                            level.getGloballyRenderedBlockEntities().contains(blockEntity)) {
                        continue;
                    }
                    if (blockEntity.getBlockPos().distToCenterSqr(cameraPos) > metalrender$hardCullDistSq) {
                        continue;
                    }
                    BlockEntityRenderState state = Minecraft.getInstance().getBlockEntityRenderDispatcher().tryExtractRenderState(
                            blockEntity, tickDelta, null, false);
                    if (state != null) {
                        levelRenderState.blockEntityRenderStates.add(state);
                    }
                }
            }
        }
        if (metalrender$blockEntityFrames % 300 == 1) {
            MetalLogger.info("[blockentity] collected=%d", levelRenderState.blockEntityRenderStates.size());
        }
    }
}
