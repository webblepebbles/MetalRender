package com.pebbles_boon.metalrender.backend.metal;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.TransientMemory;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryUtil;

final class MetalTransientMemory implements TransientMemory {
    private static final int ALL_USAGE = GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC
            | GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_UNIFORM
            | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER;

    private final MetalDeviceBackend device;
    private final MetalCommandEncoderBackend encoder;
    private final MetalStagingRing ring;

    MetalTransientMemory(MetalDeviceBackend device, MetalCommandEncoderBackend encoder, long deviceHandle) {
        this.device = device;
        this.encoder = encoder;
        this.ring = new MetalStagingRing(deviceHandle);
    }

    private MetalStagingRing.Allocation allocate(long size, long alignment) {
        return encoder.stage(ring, Math.max(1L, size), Math.max(4L, alignment));
    }

    private GpuBufferSlice slice(MetalStagingRing.Allocation alloc, long size) {
        MetalStagingRing.Block block = alloc.block();
        if (block.wrapper == null) {
            block.wrapper = new MetalBuffer(device, block.handle, block.contents, block.capacity, ALL_USAGE);
        }
        return new GpuBufferSlice(block.wrapper, alloc.offset(), size);
    }

    private GpuBufferSlice.MappedView mapped(MetalStagingRing.Allocation alloc, long size) {
        return new GpuBufferSlice.MappedView(slice(alloc, size),
                MemoryUtil.memByteBuffer(alloc.address(), (int) size), () -> {
                });
    }

    @Override
    public ByteBuffer allocateCpu(long size, long alignment, long minSize, long step) {
        return MemoryUtil.memByteBuffer(allocate(size, alignment).address(), (int) size);
    }

    @Override
    public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, int usage, long minSize,
            long step) {
        return mapped(allocate(size, alignment), size);
    }

    @Override
    public GpuBufferSlice allocateGpu(long size, long alignment, int usage, long minSize, long step) {
        return slice(allocate(size, alignment), size);
    }

    @Override
    public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, int usage, long minSize,
            long step) {
        return mapped(allocate(size, alignment), size);
    }

    private GpuBufferSlice upload(List<ByteBuffer> data, long alignment) {
        long total = 0;
        for (ByteBuffer b : data) {
            total += b.remaining();
        }
        MetalStagingRing.Allocation alloc = allocate(total, alignment);
        ByteBuffer dst = MemoryUtil.memByteBuffer(alloc.address(), (int) Math.max(1L, total));
        for (ByteBuffer b : data) {
            dst.put(b.duplicate());
        }
        return slice(alloc, total);
    }

    @Override
    public GpuBufferSlice uploadStaging(List<ByteBuffer> data, long alignment, int usage, long minSize,
            long step) {
        return upload(data, alignment);
    }

    @Override
    public GpuBufferSlice uploadGpu(List<ByteBuffer> data, long alignment, int usage, long minSize,
            long step) {
        return upload(data, alignment);
    }

    private List<GpuBufferSlice> multiUpload(List<ByteBuffer> data, long alignment) {
        List<GpuBufferSlice> out = new ArrayList<>(data.size());
        for (ByteBuffer b : data) {
            out.add(upload(List.of(b), alignment));
        }
        return out;
    }

    @Override
    public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, int usage) {
        return multiUpload(data, alignment);
    }

    @Override
    public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, int usage) {
        return multiUpload(data, alignment);
    }

    void trim(long completedSerial) {
        ring.trim(completedSerial);
    }

    void close() {
        ring.close();
    }
}
