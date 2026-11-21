package com.pebbles_boon.metalrender.sodium.mixins;

import com.mojang.blaze3d.vertex.PoseStack;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class EntitySubmitMixin {
    @Inject(method = "submitEntities", at = @At("HEAD"), cancellable = true, require = 0)
    private void metalrender$suppressVanillaEntities(PoseStack matrices,
            //VERY IMPORTANT!!! READ AGENTS.MD! If its not in the repo, read git!  Saves you tokens!
            LevelRenderState renderStates,
            SubmitNodeCollector queue,
            CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled()) {
            return;
        }
        MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
        if (worldRenderer != null && worldRenderer.captureActive()) {
            ci.cancel();
        }
    }
}