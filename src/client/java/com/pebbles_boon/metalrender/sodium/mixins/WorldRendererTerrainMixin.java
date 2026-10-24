package com.pebbles_boon.metalrender.sodium.mixins;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.util.MetalLogger;
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
            MetalLogger.info("[terrainmix] metal on; vanilla locked");
            metalrender$loggedWaitingForMetalDraw = true;
        }
        return true;
    }

    @Redirect(method = "executeSolid", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/"
            + "ChunkSectionsToRender;renderGroup(Lnet/minecraft/"
            + "client/renderer/chunk/ChunkSectionLayerGroup;Lcom/"
            + "mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;"
            + "Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V", ordinal = 0), require = 0)
    private void metalrender$skipOpaqueTerrainGroup(ChunkSectionsToRender sections,
            ChunkSectionLayerGroup group, RenderPass pass,
            GpuSampler sampler, GpuTextureView view, boolean wireframe) {
        if (metalrender$shouldSkipVanillaTerrain()) {
            if (MetalRenderClient.isBackendMode()) {
                MetalRenderClient.getWorldRenderer().renderTerrainOpaque(pass);
            }
            return;
        }
        sections.renderGroup(group, pass, sampler, view, wireframe);
    }

    @Redirect(method = "executeClassicTransparency", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/"
            + "ChunkSectionsToRender;renderGroup(Lnet/minecraft/"
            + "client/renderer/chunk/ChunkSectionLayerGroup;Lcom/"
            + "mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;"
            + "Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V", ordinal = 0), require = 0)
    private void metalrender$skipTranslucentTerrainGroup(ChunkSectionsToRender sections,
            ChunkSectionLayerGroup group, RenderPass pass,
            GpuSampler sampler, GpuTextureView view, boolean wireframe) {
        if (metalrender$shouldSkipVanillaTerrain()) {
            if (MetalRenderClient.isBackendMode()) {
                MetalRenderClient.getWorldRenderer().renderTerrainTranslucent(pass);
            }
            return;
        }
        sections.renderGroup(group, pass, sampler, view, wireframe);
    }

    @Redirect(method = {"prepareChunkRenders", "prepareChunkRendersIndirect"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;extractSectionDrawGroups(ZLjava/util/List;Ljava/util/Map;)I"),
            require = 0)
    private int metalrender$skipSectionDrawGroups(net.minecraft.client.renderer.LevelRenderer self, boolean flag,
            java.util.List infos, java.util.Map groups) {
        if (metalrender$shouldSkipVanillaTerrain()) {
            return 0;
        }
        return extractSectionDrawGroups(flag, infos, groups);
    }

    @org.spongepowered.asm.mixin.Shadow
    private int extractSectionDrawGroups(boolean flag, java.util.List infos, java.util.Map groups) {
        throw new AssertionError();
    }

    @Inject(method = "lambda$addMainPass$0", at = @At("HEAD"), cancellable = true, require = 0)
    private void metalrender$terrainHookHeartbeat(CallbackInfo ci) {
        if (MetalRenderClient.isBackendMode() || !metalrender$shouldSkipVanillaTerrain()) {
            return;
        }
        metalrender$skippedTerrainGroups++;
        if (metalrender$skippedTerrainGroups <= 3 ||
                metalrender$skippedTerrainGroups % 1000 == 0) {
            MetalLogger.info(
                    "[terrainmix] cancelled pass #%d (section iteration skipped)",
                    metalrender$skippedTerrainGroups);
        }
        ci.cancel();
    }
}
