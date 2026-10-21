package com.pebbles_boon.metalrender.backend.metal;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryStack;

final class MetalSurface {
    private final long window;
    private long layer;
    private int width;
    private int height;
    private boolean vsync;
    static final boolean UNCAPPED =
            com.pebbles_boon.metalrender.config.MetalRenderConfig.getBool("metalrender.uncapped", false);

    MetalSurface(long window, boolean vsync) {
        vsync = vsync && !UNCAPPED;
        this.window = window;
        this.vsync = vsync;
        long nsWindow = SDLProperties.SDL_GetPointerProperty(SDLVideo.SDL_GetWindowProperties(window),
                SDLVideo.SDL_PROP_WINDOW_COCOA_WINDOW_POINTER, 0L);
        if (nsWindow == 0) {
            throw new IllegalStateException("SDL window has no Cocoa window");
        }
        int[] size = pixelSize(window);
        this.width = Math.max(1, size[0]);
        this.height = Math.max(1, size[1]);
        this.layer = MetalNative.nSurfaceCreate(nsWindow, width, height, vsync);
        if (layer == 0) {
            throw new IllegalStateException("CAMetalLayer creation failed: " + MetalNative.nLastError());
        }
        MetalNative.nSetPacedPresent(
                com.pebbles_boon.metalrender.config.MetalRenderConfig.getBool("metalrender.pacedPresent", true));
    }

    private static int[] pixelSize(long window) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.IntBuffer w = stack.mallocInt(1);
            java.nio.IntBuffer h = stack.mallocInt(1);
            SDLVideo.SDL_GetWindowSizeInPixels(window, w, h);
            return new int[] {w.get(0), h.get(0)};
        }
    }

    long layer() {
        return layer;
    }

    void syncSize() {
        int w;
        int h;
        Minecraft mc = Minecraft.getInstance();
        Window mcWindow = mc != null ? mc.getWindow() : null;
        if (mcWindow != null) {
            w = mcWindow.getWidth();
            h = mcWindow.getHeight();
        } else {
            int[] size = pixelSize(window);
            w = size[0];
            h = size[1];
        }
        if (w > 0 && h > 0 && (w != width || h != height)) {
            width = w;
            height = h;
            MetalNative.nSurfaceResize(layer, w, h);
        }
    }

    void setVsync(boolean vsync) {
        vsync = vsync && !UNCAPPED;
        if (this.vsync != vsync) {
            this.vsync = vsync;
            MetalNative.nSurfaceSetVsync(layer, vsync);
        }
    }

    void close() {
        if (layer != 0) {
            MetalNative.nRelease(layer);
            layer = 0;
        }
    }
}
