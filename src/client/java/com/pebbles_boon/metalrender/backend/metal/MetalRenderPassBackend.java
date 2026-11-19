package com.pebbles_boon.metalrender.backend.metal;

import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.lwjgl.system.MemoryUtil;

public final class MetalRenderPassBackend implements RenderPassBackend {
    private record Binding(MetalTextureView view, MetalSampler sampler) {
    }

    private final MetalCommandEncoderBackend encoder;
    private final MetalDeviceBackend device;
    final String label;
    final long colorTexture;
    final int colorLevel;
    final int colorFormat;
    final long depthTexture;
    final int depthLevel;
    final int depthFormat;
    final int targetWidth;
    final int targetHeight;
    private final Map<String, GpuBufferSlice> uniforms = new HashMap<>();
    private final Map<String, Binding> samplers = new HashMap<>();
    private MetalCompiledPipeline pipeline;
    private MetalBuffer vertexBuffer;
    private long vertexOffset;
    private final RenderPass.RenderArea renderArea;
    private MetalBuffer indexBuffer;
    private IndexType indexType = IndexType.INT;
    private boolean scissorEnabled;
    private int scissorX;
    private int scissorY;
    private int scissorWidth;
    private int scissorHeight;
    private int debugGroups;
    private boolean closed;

    MetalRenderPassBackend(MetalCommandEncoderBackend encoder, MetalDeviceBackend device,
            String label, MetalTextureView color, MetalTextureView depth, RenderPass.RenderArea renderArea) {
        this.encoder = encoder;
        this.renderArea = renderArea;
        this.device = device;
        this.label = label;
        if (color != null) {
            this.colorTexture = color.texture().handle();
            this.colorLevel = color.baseMipLevel();
            this.colorFormat = color.texture().pixelFormat();
            this.targetWidth = color.getWidth(0);
            this.targetHeight = color.getHeight(0);
        } else {
            this.colorTexture = 0;
            this.colorLevel = 0;
            this.colorFormat = MetalNative.PIXEL_INVALID;
            this.targetWidth = depth != null ? depth.getWidth(0) : 1;
            this.targetHeight = depth != null ? depth.getHeight(0) : 1;
        }
        if (depth != null) {
            this.depthTexture = depth.texture().handle();
            this.depthLevel = depth.baseMipLevel();
            this.depthFormat = depth.texture().pixelFormat();
        } else {
            this.depthTexture = 0;
            this.depthLevel = 0;
            this.depthFormat = MetalNative.PIXEL_INVALID;
        }
    }

    MetalRenderPassBackend(MetalCommandEncoderBackend encoder, MetalDeviceBackend device,
            String label, MetalTexture color, MetalTexture depth) {
        this.encoder = encoder;
        this.renderArea = null;
        this.device = device;
        this.label = label;
        this.colorTexture = color != null ? color.handle() : 0;
        this.colorLevel = 0;
        this.colorFormat = color != null ? color.pixelFormat() : MetalNative.PIXEL_INVALID;
        this.depthTexture = depth != null ? depth.handle() : 0;
        this.depthLevel = 0;
        this.depthFormat = depth != null ? depth.pixelFormat() : MetalNative.PIXEL_INVALID;
        MetalTexture size = color != null ? color : depth;
        this.targetWidth = size != null ? size.getWidth(0) : 1;
        this.targetHeight = size != null ? size.getHeight(0) : 1;
    }

    boolean sameTargets(MetalRenderPassBackend other) {
        return other != null && colorTexture == other.colorTexture && colorLevel == other.colorLevel
                && depthTexture == other.depthTexture && depthLevel == other.depthLevel;
    }

    @Override
    public void pushDebugGroup(Supplier<String> label) {
        if (encoder.ensureRenderEncoder(this)) {
            MetalNative.nCtxPushDebugGroup(encoder.ctx(), label.get());
            debugGroups++;
        }
    }

    @Override
    public void popDebugGroup() {
        if (debugGroups > 0 && encoder.isEncoding(this)) {
            MetalNative.nCtxPopDebugGroup(encoder.ctx());
        }
        if (debugGroups > 0) {
            debugGroups--;
        }
    }

    @Override
    public void setPipeline(BackendRenderPipeline pipeline) {
        this.pipeline = (MetalCompiledPipeline) pipeline;
    }

    @Override
    public void setUniform(int index, Object value) {
        if (pipeline == null || index < 0 || index >= pipeline.uniformNames().size()) {
            return;
        }
        String name = pipeline.uniformNames().get(index);
        if (value instanceof GpuBufferSlice slice) {
            uniforms.put(name, slice);
            samplers.remove(name);
        } else if (value instanceof TextureViewAndSampler binding) {
            samplers.put(name, new Binding((MetalTextureView) binding.view(), (MetalSampler) binding.sampler()));
            uniforms.remove(name);
        } else {
            uniforms.remove(name);
            samplers.remove(name);
        }
    }

    @Override
    public void pushConstants(java.nio.ByteBuffer data) {
    }

    @Override
    public void enableScissor(int x, int y, int width, int height) {
        scissorEnabled = true;
        scissorX = x;
        scissorY = y;
        scissorWidth = width;
        scissorHeight = height;
    }

    @Override
    public void disableScissor() {
        scissorEnabled = false;
    }

    @Override
    public void setVertexBuffer(int slot, GpuBufferSlice slice) {
        if (slot != 0) {
            throw new IllegalArgumentException("Vertex buffer slot is out of range: " + slot);
        }
        this.vertexBuffer = slice != null ? (MetalBuffer) slice.buffer() : null;
        this.vertexOffset = slice != null ? slice.offset() : 0;
    }

    @Override
    public void setIndexBuffer(GpuBuffer buffer, IndexType type) {
        this.indexBuffer = (MetalBuffer) buffer;
        this.indexType = type;
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex,
            int firstInstance) {
        submitDraw(baseVertex, firstIndex, indexCount, indexType, instanceCount);
    }

    @Override
    public void multiDrawIndexed(java.nio.IntBuffer counts, int a, int b, int c) {
        throw new UnsupportedOperationException("multiDrawIndexed is not supported by the Metal backend");
    }

    @Override
    public void multiDrawIndexed(org.lwjgl.PointerBuffer offsets, java.nio.IntBuffer counts,
            java.nio.IntBuffer baseVertices, int count) {
        throw new UnsupportedOperationException("multiDrawIndexed is not supported by the Metal backend");
    }

    @Override
    public void drawIndexedIndirect(GpuBufferSlice slice, int count) {
        throw new UnsupportedOperationException("drawIndexedIndirect is not supported by the Metal backend");
    }

    @Override
    public void multiDraw(java.nio.IntBuffer firsts, int a, int b, int c) {
        throw new UnsupportedOperationException("multiDraw is not supported by the Metal backend");
    }

    @Override
    public void multiDraw(java.nio.IntBuffer firsts, java.nio.IntBuffer counts, int count) {
        throw new UnsupportedOperationException("multiDraw is not supported by the Metal backend");
    }

    @Override
    public void drawIndirect(GpuBufferSlice slice, int count) {
        throw new UnsupportedOperationException("drawIndirect is not supported by the Metal backend");
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        submitDraw(firstVertex, 0, vertexCount, null, instanceCount);
    }

    private void submitDraw(int baseVertex, int firstIndex, int count, IndexType type,
            int instances) {
        MetalCompiledPipeline compiled = pipeline;
        if (compiled == null || compiled.drawsNothing() || count <= 0) {
            return;
        }
        boolean hasFormat = compiled.hasVertexFormat();
        if (hasFormat && (vertexBuffer == null || vertexBuffer.isClosed())) {
            return;
        }
        if (type != null && (indexBuffer == null || indexBuffer.isClosed())) {
            return;
        }
        long pso = compiled.pipelineState(colorFormat, depthFormat);
        if (pso == 0) {
            return;
        }
        List<String> samplerNames = compiled.samplers();
        for (int i = 0; i < samplerNames.size(); i++) {
            Binding binding = samplers.get(samplerNames.get(i));
            if (binding != null && !binding.view().isClosed()) {
                encoder.flushPendingClears(binding.view().texture());
            }
        }
        if (!encoder.ensureRenderEncoder(this)) {
            return;
        }
        long ctx = encoder.ctx();
        long serial = encoder.openSerial();
        long depthState = compiled.depthState(depthTexture != 0);
        if (encoder.bindPipeline(pso, depthState)) {
            MetalNative.nCtxSetPipeline(ctx, pso, depthState, compiled.cullMode(),
                    MetalNative.WINDING_CLOCKWISE, compiled.fillMode(), compiled.depthBias(),
                    compiled.slopeScale());
        }
        boolean area = renderArea != null && !scissorEnabled;
        int sx = scissorEnabled ? scissorX : area ? renderArea.x() : 0;
        int sy = scissorEnabled ? scissorY : area ? renderArea.y() : 0;
        int sw = scissorEnabled ? scissorWidth : area ? renderArea.width() : targetWidth;
        int sh = scissorEnabled ? scissorHeight : area ? renderArea.height() : targetHeight;
        if (encoder.bindScissor(sx, sy, sw, sh)) {
            MetalNative.nCtxSetScissor(ctx, sx, sy, sw, sh);
        }
        for (Map.Entry<String, GpuBufferSlice> entry : uniforms.entrySet()) {
            String name = entry.getKey();
            GpuBufferSlice slice = entry.getValue();
            int texelSlot = MetalBindings.texelBufferSlot(name);
            boolean texel = texelSlot >= 0;
            int slot = texel ? texelSlot : MetalBindings.bufferSlot(name);
            if (slice == null || slot < 0) {
                continue;
            }
            MetalBuffer buffer = (MetalBuffer) slice.buffer();
            if (buffer.isClosed()) {
                continue;
            }
            if (!texel) {
                device.bindings().checkSize(name, slice.length());
            }
            if (encoder.bindBuffer(slot, buffer.handle(), slice.offset())) {
                MetalNative.nCtxSetBuffer(ctx, buffer.handle(), slice.offset(), slot);
            }
            buffer.lastUseFrame = serial;
        }
        for (int i = 0; i < samplerNames.size(); i++) {
            Binding binding = samplers.get(samplerNames.get(i));
            if (binding == null || binding.view().isClosed() || binding.sampler().isClosed()) {
                continue;
            }
            int slot = binding.view().texture().isCube() ? MetalBindings.CUBE_TEXTURE_SLOT
                    : MetalBindings.textureSlot(samplerNames.get(i), i);
            if (encoder.bindTexture(slot, binding.view().handle(), binding.sampler().handle())) {
                MetalNative.nCtxSetTexture(ctx, binding.view().handle(), binding.sampler().handle(), slot);
            }
        }
        if (hasFormat) {
            if (encoder.bindVertex(vertexBuffer.handle(), vertexOffset)) {
                MetalNative.nCtxSetVertexBuffer(ctx, vertexBuffer.handle(), vertexOffset, 0);
            }
            vertexBuffer.lastUseFrame = serial;
        }
        if (compiled.triangleFan()) {
            drawFan(ctx, baseVertex, firstIndex, count, type, instances);
            return;
        }
        if (type == null) {
            MetalNative.nCtxDraw(ctx, compiled.primitive(), baseVertex, count, instances);
        } else {
            MetalNative.nCtxDrawIndexed(ctx, compiled.primitive(), count,
                    type == IndexType.INT, indexBuffer.handle(),
                    (long) firstIndex * type.bytes, instances, baseVertex);
            indexBuffer.lastUseFrame = serial;
        }
    }

    private void drawFan(long ctx, int baseVertex, int firstIndex, int count,
            IndexType type, int instances) {
        if (count < 3) {
            return;
        //READ AGENTS.MD BEFORE EDITING THIS FILE - it saves you a lot of tokens!
        }
        int triangles = count - 2;
        MetalStagingRing.Allocation alloc = encoder.stage((long) triangles * 3 * 4, 4);
        long dst = alloc.address();
        if (type == null) {
            for (int i = 1; i <= triangles; i++) {
                MemoryUtil.memPutInt(dst, baseVertex);
                MemoryUtil.memPutInt(dst + 4, baseVertex + i);
                MemoryUtil.memPutInt(dst + 8, baseVertex + i + 1);
                dst += 12;
            }
            MetalNative.nCtxDrawIndexed(ctx, MetalNative.PRIMITIVE_TRIANGLE, triangles * 3, true,
                    alloc.buffer(), alloc.offset(), instances, 0);
            return;
        }
        long src = indexBuffer.contents() + (long) firstIndex * type.bytes;
        boolean wide = type == IndexType.INT;
        int first = readIndex(src, 0, wide);
        for (int i = 1; i <= triangles; i++) {
            MemoryUtil.memPutInt(dst, first);
            MemoryUtil.memPutInt(dst + 4, readIndex(src, i, wide));
            MemoryUtil.memPutInt(dst + 8, readIndex(src, i + 1, wide));
            dst += 12;
        }
        MetalNative.nCtxDrawIndexed(ctx, MetalNative.PRIMITIVE_TRIANGLE, triangles * 3, true,
                alloc.buffer(), alloc.offset(), instances, baseVertex);
    }

    private static int readIndex(long base, int i, boolean wide) {
        return wide ? MemoryUtil.memGetInt(base + (long) i * 4)
                : Short.toUnsignedInt(MemoryUtil.memGetShort(base + (long) i * 2));
    }

    public long nativeRenderEncoder(boolean terrain) {
        if (closed || !encoder.ensureRenderEncoder(this)) {
            return 0;
        }
        encoder.invalidateBindings();
        return MetalNative.nCtxRenderEncoder(encoder.ctx(), terrain || "metalrender_terrain".equals(label));
    }

    public int targetWidth() {
        return targetWidth;
    }

    public int targetHeight() {
        return targetHeight;
    }

    public void finishTerrainDepthCapture() {
        if (encoder.isEncoding(this)) {
            encoder.endEncoder();
        }
    }

    public void restoreNativeState() {
        if (encoder.isEncoding(this)) {
            MetalNative.nCtxRestoreViewport(encoder.ctx());
            encoder.invalidateBindings();
        }
    }

    void finish() {
        if (!closed) {
            closed = true;
            while (debugGroups > 0) {
                popDebugGroup();
            }
            encoder.finishRenderPass(this);
        }
    }

    boolean isClosed() {
        return closed;
    }
}
