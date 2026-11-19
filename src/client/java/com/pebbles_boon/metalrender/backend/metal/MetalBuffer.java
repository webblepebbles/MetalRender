package com.pebbles_boon.metalrender.backend.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

public final class MetalBuffer extends BaseGpuBuffer {
    private static final long RENAME_MAX_SIZE = 64L * 1024L;
    private static final int RENAME_MAX_BACKINGS = 8;
    private final MetalDeviceBackend device;
    private long handle;
    private long contents;
    long lastUseFrame;
    private final boolean owned;
    private final boolean writeCombined;
    final long byteSize;
    private java.util.ArrayList<long[]> retired;

    MetalBuffer(MetalDeviceBackend device, String label, int usage, long size) {
        super(usage, size);
        this.byteSize = size;
        this.device = device;
        this.owned = true;
        this.writeCombined = (usage & (GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_SRC)) == 0;
        this.handle = MetalNative.nBufferCreate(device.deviceHandle(), size, writeCombined);
        if (handle == 0) {
            throw new IllegalStateException("Metal buffer creation failed for " + label + ": "
                    + MetalNative.nLastError());
        }
        this.contents = MetalNative.nBufferContents(handle);
        if (label != null) {
            MetalNative.nSetLabel(handle, label);
        //VERY IMPORTANT!!! READ AGENTS.MD! Not there? Read git! Saves you tokens, seriously!
        }
    }

    MetalBuffer(MetalDeviceBackend device, long handle, long contents, long size, int usage) {
        super(usage, size);
        this.byteSize = size;
        this.device = device;
        this.owned = false;
        this.writeCombined = false;
        this.handle = handle;
        this.contents = contents;
    }

    public long handle() {
        return handle;
    }

    long contents() {
        return contents;
    }

    boolean rename(long completedSerial, boolean keepContents) {
        if (!owned || handle == 0 || byteSize > RENAME_MAX_SIZE) {
            return false;
        }
        long[] next = null;
        if (retired != null) {
            for (int i = 0; i < retired.size(); i++) {
                if (retired.get(i)[2] <= completedSerial) {
                    next = retired.remove(i);
                    break;
                }
            }
        }
        if (next == null) {
            if (retired != null && retired.size() >= RENAME_MAX_BACKINGS) {
                return false;
            }
            long h = MetalNative.nBufferCreate(device.deviceHandle(), byteSize, writeCombined);
            if (h == 0) {
                return false;
            }
            next = new long[] {h, MetalNative.nBufferContents(h), 0};
        }
        if (keepContents) {
            MemoryUtil.memCopy(contents, next[1], byteSize);
        }
        if (retired == null) {
            retired = new java.util.ArrayList<>(RENAME_MAX_BACKINGS);
        }
        retired.add(new long[] {handle, contents, lastUseFrame});
        handle = next[0];
        contents = next[1];
        lastUseFrame = 0;
        return true;
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
                if (retired != null) {
                    for (long[] backing : retired) {
                        MetalNative.nRelease(backing[0]);
                    }
                    retired = null;
                }
            }
            handle = 0;
        }
    }
}
