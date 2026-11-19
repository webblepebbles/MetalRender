package com.pebbles_boon.metalrender.backend.metal;

import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Collection;
import java.util.List;

final class MetalSurfaceBackend implements GpuSurfaceBackend {
    private final MetalDeviceBackend device;
    private final MetalSurface surface;

    MetalSurfaceBackend(MetalDeviceBackend device, MetalSurface surface) {
        this.device = device;
        this.surface = surface;
    }

    @Override
    public void configure(GpuSurface.Configuration configuration) {
        surface.setVsync(configuration.presentMode() != GpuSurface.PresentMode.IMMEDIATE);
        surface.syncSize();
    }

    @Override
    public boolean isSuboptimal() {
        return false;
    }

    @Override
    public void acquireNextTexture() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.options != null) {
            surface.setVsync(Boolean.TRUE.equals(mc.options.enableVsync().get()));
        }
        surface.syncPacing();
        surface.syncSize();
    }

    @Override
    public void blitFromTexture(CommandEncoderBackend encoder, GpuTextureView view) {
        ((MetalCommandEncoderBackend) encoder).blitToSurface(surface, (MetalTextureView) view);
    }

    @Override
    public void present() {
        device.encoder().presentFrame();
    }

    @Override
    public void close() {
        surface.close();
    }

    @Override
    public Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return List.of(GpuSurface.PresentMode.IMMEDIATE, GpuSurface.PresentMode.FIFO);
    }
}
