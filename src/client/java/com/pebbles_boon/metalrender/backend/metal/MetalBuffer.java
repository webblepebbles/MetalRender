package com.pebbles_boon.metalrender.backend.metal;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

public final class MetalBuffer extends GpuBuffer {
    private final MetalDeviceBackend device;
    private long handle;
    private final long contents;
    long lastUseFrame;
    private final boolean owned;

    MetalBuffer(MetalDeviceBackend device, String label, int usage, long size) {
        super(usage, size);
        this.device = device;
        this.owned = true;
        boolean writeCombined = (usage & GpuBuffer.USAGE_MAP_READ) == 0;
        this.handle = MetalNative.nBufferCreate(device.deviceHandle(), size, writeCombined);
        if (handle == 0) {
            throw new IllegalStateException("Metal buffer creation failed for " + label + ": "
                    + MetalNative.nLastError());
        }
        this.contents = MetalNative.nBufferContents(handle);
        if (label != null) {
            MetalNative.nSetLabel(handle, label);
        }
    }

    MetalBuffer(MetalDeviceBackend device, long handle, long contents, long size, int usage) {
        super(usage, size);
        this.device = device;
        this.owned = false;
        this.handle = handle;
        this.contents = contents;
    }

    public long handle() {
        return handle;
    }

    long contents() {
        return contents;
    }

    ByteBuffer view(long offset, long length) {
        return MemoryUtil.memByteBuffer(contents + offset, (int) length);
    }

    @Override
    public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
        if (read) {
            device.encoder().waitForUse(lastUseFrame);
        }
        return new GpuBufferSlice.MappedView(new GpuBufferSlice(this, offset, length),
                view(offset, length), () -> {
                });
    }

    @Override
    public boolean isClosed() {
        return handle == 0;
    }

    @Override
    public void close() {
        if (handle != 0) {
            if (owned) {
                MetalNative.nRelease(handle);
            }
            handle = 0;
        }
    }
}
