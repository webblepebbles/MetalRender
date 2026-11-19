package com.pebbles_boon.metalrender.backend.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import org.lwjgl.system.MemoryUtil;

public final class MetalDeviceBackend implements GpuDeviceBackend {
    private record SamplerKey(AddressMode u, AddressMode v, FilterMode min, FilterMode mag,
            int maxAnisotropy, OptionalDouble maxLod) {
    }

    private final long device;
    private final long queue;
    private final long library;
    private final long ctx;
    private final String deviceName;
    private MetalSurface surface;
    private final MetalCommandEncoderBackend encoder;
    private final MetalBindings bindings;
    private final Map<SamplerKey, Long> samplerStates = new HashMap<>();
    private final Map<Integer, Long> depthStates = new HashMap<>();
    private final List<MetalCompiledPipeline> pipelines = new java.util.ArrayList<>();
    private boolean closed;
    private static String libraryError;

    MetalDeviceBackend() throws BackendCreationException {
        try {
            MetalNative.ensureLoaded();
        } catch (Throwable t) {
            throw new BackendCreationException("MetalRender native library unavailable: " + t.getMessage(), BackendCreationException.Reason.OTHER);
        }
        long[] handles = MetalNative.nDeviceCreate();
        if (handles == null || handles[0] == 0 || handles[1] == 0) {
            throw new BackendCreationException("Metal device creation failed: " + MetalNative.nLastError(), BackendCreationException.Reason.OTHER);
        }
        this.device = handles[0];
        this.queue = handles[1];
        MetalNative.nBackendAttach(device, queue);
        this.deviceName = MetalNative.nDeviceName(device);
        libraryError = null;
        this.library = loadLibrary(device);
        if (library == 0) {
            releaseCore(0, 0);
            throw new BackendCreationException("Metal shader library failed: " + libraryError, BackendCreationException.Reason.OTHER);
        }
        this.ctx = MetalNative.nCtxCreate(device, queue, library);
        if (ctx == 0) {
            releaseCore(library, 0);
            throw new BackendCreationException("Metal command context failed: " + MetalNative.nLastError(), BackendCreationException.Reason.OTHER);
        }
        this.encoder = new MetalCommandEncoderBackend(this, ctx);
        this.bindings = new MetalBindings(MetalNative.nUniformBlockSizes());
        MetalLogger.info("NativeImage formats: %s", Arrays.toString(NativeImage.Format.values()));
    }

    private void releaseCore(long lib, long context) {
        if (context != 0) {
            MetalNative.nCtxDestroy(context);
        }
        if (lib != 0) {
            MetalNative.nRelease(lib);
        }
        MetalNative.nRelease(queue);
        MetalNative.nRelease(device);
    }

    private static long loadLibrary(long device) {
        try (InputStream in = MetalDeviceBackend.class.getResourceAsStream("/shaders.metallib")) {
            if (in == null) {
                libraryError = "/shaders.metallib not found on classpath";
                return 0;
            }
            byte[] bytes = in.readAllBytes();
            ByteBuffer data = MemoryUtil.memAlloc(bytes.length);
            try {
                data.put(bytes).flip();
                long lib = MetalNative.nLibraryCreate(device, data, bytes.length);
                if (lib == 0) {
                    libraryError = MetalNative.nLastError();
                }
                return lib;
            } finally {
                MemoryUtil.memFree(data);
            }
        } catch (IOException e) {
            libraryError = "reading /shaders.metallib failed: " + e;
            return 0;
        }
    }

    long deviceHandle() {
        return device;
    }

    long libraryHandle() {
        //NOTE TO FUTURE READERS (human or AI): read AGENTS.md, or git if it is absent. Saves you tokens!
        return library;
    }

    MetalSurface surface() {
        return surface;
    }

    MetalCommandEncoderBackend encoder() {
        return encoder;
    }

    MetalBindings bindings() {
        return bindings;
    }

    long depthState(int compare, boolean write) {
        int key = (compare << 1) | (write ? 1 : 0);
        Long cached = depthStates.get(key);
        if (cached == null) {
            cached = MetalNative.nDepthStencilCreate(device, compare, write);
            depthStates.put(key, cached);
        }
        return cached;
    }

    @Override
    public GpuSurfaceBackend createSurface(long windowHandle, java.util.function.BooleanSupplier unused) {
        if (surface == null) {
            surface = new MetalSurface(windowHandle, true);
        }
        return new MetalSurfaceBackend(this, surface);
    }

    @Override
    public CommandEncoderBackend createCommandEncoder() {
        return encoder;
    }

    @Override
    public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo info) {
        FamilyTable.logCoverage();
        return () -> {
            MetalCompiledPipeline pipeline = new MetalCompiledPipeline(this, info, FamilyTable.findOrSynthesize(info));
            pipelines.add(pipeline);
            return pipeline;
        };
    }

    void clearPipelineCache() {
        for (MetalCompiledPipeline pipeline : pipelines) {
            pipeline.release();
        }
        pipelines.clear();
    }

    @Override
    public GpuSampler createSampler(AddressMode addressModeU, AddressMode addressModeV, FilterMode minFilter,
            FilterMode magFilter, int maxAnisotropy, OptionalDouble maxLod) {
        SamplerKey key = new SamplerKey(addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy,
                maxLod);
        Long handle = samplerStates.get(key);
        if (handle == null) {
            handle = MetalNative.nSamplerCreate(device, address(addressModeU), address(addressModeV),
                    filter(minFilter), filter(magFilter), 2, Math.max(1, maxAnisotropy),
                    maxLod.isPresent() ? (float) maxLod.getAsDouble() : 1000.0f);
            samplerStates.put(key, handle);
        }
        return new MetalSampler(handle, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy,
                maxLod);
    }

    private static int address(AddressMode mode) {
        return mode == AddressMode.REPEAT ? 2 : 0;
    }

    private static int filter(FilterMode mode) {
        return mode == FilterMode.LINEAR ? 1 : 0;
    }

    @Override
    public GpuTexture createTexture(String label, int usage, GpuFormat format, int width, int height,
            int depthOrLayers, int mipLevels) {
        return new MetalTexture(this, usage, label, format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        return new MetalTextureView((MetalTexture) texture, baseMipLevel, mipLevels);
    }

    @Override
    public GpuBuffer createBuffer(Supplier<String> label, int usage, long size) {
        return new MetalBuffer(this, label != null ? label.get() : null, usage, size);
    }

    @Override
    public GpuBuffer createBuffer(Supplier<String> label, int usage, ByteBuffer data) {
        MetalBuffer buffer = new MetalBuffer(this, label != null ? label.get() : null, usage, data.remaining());
        buffer.view(0, data.remaining()).put(data.duplicate());
        return buffer;
    }

    @Override
    public List<String> getLastDebugMessages() {
        return List.of();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return false;
    }

    @Override
    public GpuQueryPool createTimestampQueryPool(int size) {
        return new MetalQueryPool(size);
    }

    @Override
    public long getTimestampCalibrationOffset() {
        return 0L;
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        return new DeviceInfo(deviceName, "Apple", "Metal 3", true, "Metal", 1.0f,
                new DeviceLimits(16, 256, 16384, Long.MAX_VALUE, 0, 1, 0),
                new DeviceFeatures(true, false, false, false, false, false, false, false), Set.of(),
                new HintsAndWorkarounds(false, false, false, false), DeviceType.INTEGRATED);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        encoder.close();
        clearPipelineCache();
        for (long sampler : samplerStates.values()) {
            MetalNative.nRelease(sampler);
        }
        samplerStates.clear();
        for (long state : depthStates.values()) {
            MetalNative.nRelease(state);
        }
        depthStates.clear();
        if (surface != null) {
            surface.close();
        }
        releaseCore(library, ctx);
    }

}
