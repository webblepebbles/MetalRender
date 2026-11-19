package com.pebbles_boon.metalrender.sodium.mixins;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.gui.MetalRenderSettingsScreen;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import com.pebbles_boon.metalrender.render.CapturedMatrices;
//READ AGENTS.MD BEFORE EDITING THIS FILE - it saves you a lot of tokens!
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.renderer.GameRenderer", remap = false)
public abstract class GameRendererMixin {
    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"),
            require = 1)
    private void metalrender$captureSettingsBackdrop(CallbackInfo ci) {
        if (Minecraft.getInstance().gui.screen() instanceof MetalRenderSettingsScreen settings)
            settings.captureGlassBackdrop();
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/" +
            "CommandEncoder;clearDepthTexture(Lcom/mojang/" +
            "renderpearl/api/textures/GpuTexture;D)V", remap = false), require = 0)
    private void metalrender$blitBeforeHandRender(CallbackInfo ci) {
        if (MetalRenderClient.isBackendMode())
            return;
        MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
        if (worldRenderer == null || !worldRenderer.metalActive())
            return;
        if (!MetalWorldRenderer.shouldBlitAt("before_hand"))
            return;
        worldRenderer.forceBlitNow();
    }

    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)"
                    + "Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"),
            index = 0, require = 0)
    private Matrix4f metalrender$captureLevelProjection(Matrix4f projection) {
        CapturedMatrices.captureLevelProjection(projection);
        return projection;
    }
}
