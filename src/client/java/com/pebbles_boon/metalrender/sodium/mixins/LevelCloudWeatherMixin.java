package com.pebbles_boon.metalrender.sodium.mixins;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelCloudWeatherMixin {
    private boolean metalrender$shouldSuppressAtmosphere() {
        if (!MetalRenderClient.isEnabled()) {
            return false;
        }
        MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
        return wr != null && wr.metalActive();
    }

    @Inject(method = "addCloudsPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;"
            + "Lnet/minecraft/client/CloudStatus;Lnet/minecraft/world/phys/Vec3;JFIFI)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void metalrender$suppressVanillaClouds(FrameGraphBuilder builder,
            CloudStatus status, Vec3 camPos, long gameTime, float partialTick,
            int cloudColor, float cloudHeight, int cloudRange, CallbackInfo ci) {
        if (metalrender$shouldSuppressAtmosphere()) {
            ci.cancel();
        }
    }

    @Inject(method = "addWeatherPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;"
            + "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void metalrender$suppressVanillaWeather(FrameGraphBuilder builder,
            GpuBufferSlice fogBuffer, CallbackInfo ci) {
        if (metalrender$shouldSuppressAtmosphere()) {
            ci.cancel();
        }
    }
}
