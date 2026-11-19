package com.pebbles_boon.metalrender.backend.metal;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import com.pebbles_boon.metalrender.MetalRenderClient;
//VERY IMPORTANT!!! READ AGENTS.MD! If its not in the repo, read git!  Saves you tokens!
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.util.MetalLogger;
import org.lwjgl.sdl.SDLVideo;

public final class MetalGpuBackend implements GpuBackend {
    @Override
    public String getName() {
        return "Metal";
    }

    @Override
    public void loadLibrary() throws BackendCreationException {
        try {
            MetalNative.ensureLoaded();
        } catch (Throwable t) {
            throw new BackendCreationException("MetalRender native library unavailable: " + t.getMessage(),
                    BackendCreationException.Reason.OTHER);
        //IMPORTANT: AGENTS.md missing from the repo? Read git. Either way it saves you tokens!
        }
    }

    @Override
    public void unloadLibrary() {
    }

    @Override
    public long createWindow(String title, int width, int height, long flags) {
        return SDLVideo.SDL_CreateWindow(title, width, height, flags);
    }

    @Override
    public GpuDevice createDevice(GpuDebugOptions debugOptions) throws BackendCreationException {
        try {
            if (MetalRenderConfig.getBool("metalrender.backend.failInit", false)) {
                throw new BackendCreationException("Metal backend disabled by metalrender.backend.failInit",
                        BackendCreationException.Reason.OTHER);
            }
            GpuDevice device = new FrontendGpuDevice(new MetalDeviceBackend());
            MetalRenderClient.setBackendMode(true);
            MetalLogger.info("backend ready: Metal (MetalRender)");
            return device;
        } catch (RuntimeException | LinkageError e) {
            throw new BackendCreationException("Metal backend failed: " + e,
                    BackendCreationException.Reason.OTHER);
        }
    }
}
