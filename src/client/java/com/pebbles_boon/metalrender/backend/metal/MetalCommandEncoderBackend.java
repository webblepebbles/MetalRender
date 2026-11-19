package com.pebbles_boon.metalrender.backend.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryUtil;

public final class MetalCommandEncoderBackend implements CommandEncoderBackend {
    private final MetalDeviceBackend device;
    private final long ctx;
    private final MetalStagingRing staging;
    private final MetalTransientMemory transientMemory;
    private final long[] submitSerials = new long[2];
    private int submitCount;
    private long openSerial = 1;
    private long completedSerial;
    private long frameIndex = 1;
    private static final boolean LAZY_CLEARS = MetalRenderConfig.getBool("metalrender.lazyClears", true);
    private static final boolean SPLIT_PASSES = MetalRenderConfig.getBool("metalrender.splitPasses", false);
    private static final int BIND_SLOTS = 64;
    private final Map<MetalTexture, Vector4fc> pendingColor = new HashMap<>();
    private final Map<MetalTexture, Double> pendingDepth = new HashMap<>();
    private final long[] boundBuffer = new long[BIND_SLOTS];
    private final long[] boundOffset = new long[BIND_SLOTS];
    private final long[] boundTexture = new long[BIND_SLOTS];
    private final long[] boundSampler = new long[BIND_SLOTS];
    private long boundVertex;
    private long boundVertexOffset;
    private long boundPipeline;
    private long boundDepthState;
    private int boundScissorX = -1;
    private int boundScissorY = -1;
    private int boundScissorW = -1;
    private int boundScissorH = -1;
    private MetalRenderPassBackend currentPass;
    private MetalRenderPassBackend encodingPass;

    MetalCommandEncoderBackend(MetalDeviceBackend device, long ctx) {
        this.device = device;
        this.ctx = ctx;
        this.staging = new MetalStagingRing(device.deviceHandle());
        this.transientMemory = new MetalTransientMemory(device, this, device.deviceHandle());
    }

    long ctx() {
        return ctx;
    }

    long openSerial() {
        return openSerial;
    }

    long frameIndex() {
        return frameIndex;
    }

    private static final long COMPLETED_POLL_NS = 200_000L;
    private long lastCompletedPollNs;

    private long completed() {
        long now = System.nanoTime();
        if (now - lastCompletedPollNs >= COMPLETED_POLL_NS) {
            lastCompletedPollNs = now;
            completedSerial = Math.max(completedSerial, MetalNative.nCtxCompleted(ctx));
        }
        return completedSerial;
    }

    private boolean isComplete(long serial) {
        if (serial <= completedSerial) {
            return true;
        }
        if (serial >= openSerial) {
            return false;
        }
        return serial <= completed();
    }

    private void refreshCompleted() {
        lastCompletedPollNs = System.nanoTime();
        completedSerial = Math.max(completedSerial, MetalNative.nCtxCompleted(ctx));
    }

    MetalStagingRing.Allocation stage(long size, long alignment) {
        return staging.allocate(size, alignment, openSerial, completed());
    }

    MetalStagingRing.Allocation stage(MetalStagingRing ring, long size, long alignment) {
        return ring.allocate(size, alignment, openSerial, completed());
    }

    void endEncoder() {
        if (encodingPass != null) {
            MetalNative.nCtxEndEncoder(ctx);
            encodingPass = null;
        }
    }

    private void endRenderForBlit() {
        encodingPass = null;
    }

    void invalidateBindings() {
        java.util.Arrays.fill(boundBuffer, 0L);
        java.util.Arrays.fill(boundOffset, 0L);
        java.util.Arrays.fill(boundTexture, 0L);
        java.util.Arrays.fill(boundSampler, 0L);
        boundVertex = 0;
        boundVertexOffset = 0;
        boundPipeline = 0;
        boundDepthState = 0;
        boundScissorX = -1;
        boundScissorY = -1;
        boundScissorW = -1;
        boundScissorH = -1;
    }

    boolean bindBuffer(int slot, long buffer, long offset) {
        if (slot < 0 || slot >= BIND_SLOTS) {
            return true;
        }
        if (boundBuffer[slot] == buffer && boundOffset[slot] == offset) {
            return false;
        }
        boundBuffer[slot] = buffer;
        boundOffset[slot] = offset;
        return true;
    }

    boolean bindTexture(int slot, long texture, long sampler) {
        if (slot < 0 || slot >= BIND_SLOTS) {
            return true;
        }
        if (boundTexture[slot] == texture && boundSampler[slot] == sampler) {
            return false;
        }
        boundTexture[slot] = texture;
        boundSampler[slot] = sampler;
        return true;
    }

    boolean bindVertex(long buffer, long offset) {
        if (boundVertex == buffer && boundVertexOffset == offset) {
            return false;
        }
        boundVertex = buffer;
        boundVertexOffset = offset;
        return true;
    }

    boolean bindPipeline(long pso, long depthState) {
        if (boundPipeline == pso && boundDepthState == depthState) {
            return false;
        }
        boundPipeline = pso;
        boundDepthState = depthState;
        return true;
    }

    boolean bindScissor(int x, int y, int w, int h) {
        if (boundScissorX == x && boundScissorY == y && boundScissorW == w && boundScissorH == h) {
            return false;
        }
        boundScissorX = x;
        boundScissorY = y;
        boundScissorW = w;
        boundScissorH = h;
        return true;
    }

    void flushPendingClears(MetalTexture texture) {
        if (texture == null || (pendingColor.isEmpty() && pendingDepth.isEmpty())) {
            return;
        }
        Vector4fc color = pendingColor.remove(texture);
        Double depth = pendingDepth.remove(texture);
        if (texture.isClosed() || (color == null && depth == null)) {
            return;
        }
        endEncoder();
        MetalRenderPassBackend pass = targetPass("lazy clear", color != null ? texture : null,
                depth != null ? texture : null);
        if (beginEncoder(pass, color, depth != null, depth != null ? depth : 1.0)) {
            endEncoder();
        }
    }

    boolean isEncoding(MetalRenderPassBackend pass) {
        return encodingPass != null && encodingPass.sameTargets(pass);
    }

    boolean ensureRenderEncoder(MetalRenderPassBackend pass) {
        if (isEncoding(pass)) {
            return true;
        }
        return beginEncoder(pass, null, false, 0.0);
    }

    private boolean beginEncoder(MetalRenderPassBackend pass, Vector4fc clearColor, boolean clearDepth,
            double depth) {
        encodingPass = null;
        invalidateBindings();
        boolean ok = MetalNative.nCtxBeginRender(ctx, pass.label, pass.colorTexture, pass.colorLevel, 0,
                clearColor != null, clearColor != null ? clearColor.x() : 0f,
                clearColor != null ? clearColor.y() : 0f, clearColor != null ? clearColor.z() : 0f,
                clearColor != null ? clearColor.w() : 0f, pass.depthTexture, pass.depthLevel, 0,
                clearDepth, depth);
        if (ok) {
            encodingPass = pass;
        }
        return ok;
    }

    void finishRenderPass(MetalRenderPassBackend pass) {
        if (currentPass == pass) {
            currentPass = null;
        }
    }

    @Override
    public void submitRenderPass() {
        MetalRenderPassBackend pass = currentPass;
        if (pass != null) {
            pass.finish();
        }
    }

    @Override
    public void submit() {
        endEncoder();
        int slot = submitCount++ % submitSerials.length;
        long previous = submitSerials[slot];
        submitSerials[slot] = openSerial;
        if (previous != 0) {
            awaitSerial(previous, Long.MAX_VALUE / 2);
        }
        transientMemory.trim(completed());
    }

    @Override
    public TransientMemory transientMemory() {
        return transientMemory;
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
    }

    private void flush() {
        endEncoder();
        long serial = MetalNative.nCtxSubmit(ctx, false);
        openSerial = serial + 1;
    }

    boolean awaitSerial(long serial, long timeoutNanos) {
        if (completed() >= serial) {
            return true;
        }
        if (timeoutNanos <= 0) {
            return false;
        }
        if (serial >= openSerial) {
            flush();
        }
        long timeoutMs = Math.max(1L, timeoutNanos / 1_000_000L);
        boolean done = MetalNative.nCtxWait(ctx, serial, timeoutMs);
        refreshCompleted();
        return done;
    }

    void waitForUse(long lastUse) {
        if (lastUse <= completed()) {
            return;
        }
        if (lastUse >= openSerial) {
            flush();
        }
        while (!MetalNative.nCtxWait(ctx, lastUse, 1000L)) {
            refreshCompleted();
        }
        refreshCompleted();
    }

    void presentFrame() {
        if (device.surface() != null) {
            device.surface().syncSize();
        }
        endEncoder();
        long serial = MetalNative.nCtxSubmit(ctx, true);
        openSerial = serial + 1;
        frameIndex++;
        staging.trim(completed());
        transientMemory.trim(completed());
    }

    void close() {
        endEncoder();
        staging.close();
        transientMemory.close();
    }

    @Override
    public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
        MetalTextureView color = null;
        Optional<Vector4fc> clearColor = Optional.empty();
        for (RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment : descriptor.colorAttachments()) {
            if (attachment != null && attachment.textureView() != null) {
                color = (MetalTextureView) attachment.textureView();
                clearColor = attachment.clearValue() != null ? attachment.clearValue() : Optional.empty();
                break;
            }
        }
        MetalTextureView depth = null;
        OptionalDouble clearDepth = OptionalDouble.empty();
        if (descriptor.depthAttachment() != null && descriptor.depthAttachment().textureView() != null) {
            depth = (MetalTextureView) descriptor.depthAttachment().textureView();
            clearDepth = descriptor.depthAttachment().clearValue() != null
                    ? descriptor.depthAttachment().clearValue() : OptionalDouble.empty();
        }
        String label = descriptor.label() != null ? descriptor.label().get() : null;
        MetalRenderPassBackend pass = new MetalRenderPassBackend(this, device, label, color, depth,
                descriptor.renderArea());
        boolean doClearColor = clearColor.isPresent();
        Vector4fc argb = clearColor.orElse(null);
        boolean doClearDepth = depth != null && clearDepth.isPresent();
        double depthValue = clearDepth.orElse(1.0);
        if (color != null) {
            MetalTexture texture = color.texture();
            if (doClearColor) {
                pendingColor.remove(texture);
            } else if (!pendingColor.isEmpty() && pendingColor.containsKey(texture)) {
                if (color.baseMipLevel() == 0) {
                    argb = pendingColor.remove(texture);
                    doClearColor = true;
                } else {
                    flushPendingClears(texture);
                }
            }
        }
        if (depth != null) {
            MetalTexture texture = depth.texture();
            if (doClearDepth) {
                pendingDepth.remove(texture);
            } else if (!pendingDepth.isEmpty() && pendingDepth.containsKey(texture)) {
                if (depth.baseMipLevel() == 0) {
                    depthValue = pendingDepth.remove(texture);
                    doClearDepth = true;
                } else {
                    flushPendingClears(texture);
                }
            }
        }
        boolean split = SPLIT_PASSES && "metalrender_terrain".equals(pass.label);
        if (doClearColor || doClearDepth || split || !isEncoding(pass)) {
            if (!doClearColor && doClearDepth && !split && isEncoding(pass)
                    && MetalNative.nCtxClearDepthFolded(ctx, depthValue)) {
                invalidateBindings();
                encodingPass = pass;
            } else {
                endEncoder();
                beginEncoder(pass, doClearColor ? argb : null, doClearDepth, depthValue);
            }
        } else {
            encodingPass = pass;
        }
        currentPass = pass;
        return pass;
    }

    private MetalRenderPassBackend targetPass(String label, GpuTexture color, GpuTexture depth) {
        return new MetalRenderPassBackend(this, device, label, (MetalTexture) color, (MetalTexture) depth);
    }

    @Override
    public void clearColorTexture(GpuTexture texture, Vector4fc argb) {
        if (LAZY_CLEARS) {
            pendingColor.put((MetalTexture) texture, argb);
            return;
        }
        endEncoder();
        MetalRenderPassBackend pass = targetPass("clear color", texture, null);
        beginEncoder(pass, argb, false, 0.0);
        endEncoder();
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture color, Vector4fc argb, GpuTexture depth,
            double clearDepth) {
        if (LAZY_CLEARS) {
            pendingColor.put((MetalTexture) color, argb);
            if (depth != null) {
                pendingDepth.put((MetalTexture) depth, clearDepth);
            }
            return;
        }
        endEncoder();
        MetalRenderPassBackend pass = targetPass("clear color and depth", color, depth);
        beginEncoder(pass, argb, true, clearDepth);
        endEncoder();
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture color, Vector4fc argb, GpuTexture depth,
            double clearDepth, int x, int y, int width, int height, int mipLevel) {
        flushPendingClears((MetalTexture) color);
        flushPendingClears((MetalTexture) depth);
        endEncoder();
        MetalRenderPassBackend pass = targetPass("clear region", color, depth);
        if (beginEncoder(pass, null, false, 0.0)) {
            MetalNative.nCtxClearRect(ctx, x, y, width, height, argb.x(), argb.y(), argb.z(), argb.w(),
                    (float) clearDepth, depth != null);
        }
        endEncoder();
    }

    @Override
    public void clearDepthTexture(GpuTexture texture, double depth) {
        if (LAZY_CLEARS) {
            pendingDepth.put((MetalTexture) texture, depth);
            return;
        }
        endEncoder();
        MetalRenderPassBackend pass = targetPass("clear depth", null, texture);
        beginEncoder(pass, null, true, depth);
        endEncoder();
    }

    @Override
    public void writeToBuffer(GpuBufferSlice slice, ByteBuffer data) {
        MetalBuffer buffer = (MetalBuffer) slice.buffer();
        int length = data.remaining();
        if (length <= 0 || buffer.isClosed()) {
            return;
        }
        if (isComplete(buffer.lastUseFrame)) {
            buffer.view(slice.offset(), length).put(data.duplicate());
            return;
        }
        if (buffer.rename(completed(), length < buffer.byteSize)) {
            buffer.view(slice.offset(), length).put(data.duplicate());
            return;
        }
        MetalStagingRing.Allocation alloc = stage(length, 16);
        MemoryUtil.memByteBuffer(alloc.address(), length).put(data.duplicate());
        endRenderForBlit();
        MetalNative.nCtxCopyBuffer(ctx, alloc.buffer(), alloc.offset(), buffer.handle(), slice.offset(),
                length);
        buffer.lastUseFrame = openSerial;
    }

    @Override
    public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
        MetalBuffer src = (MetalBuffer) source.buffer();
        MetalBuffer dst = (MetalBuffer) target.buffer();
        long copyLength = Math.min(source.length(), target.length());
        if (copyLength > 0 && isComplete(src.lastUseFrame) && isComplete(dst.lastUseFrame) && src.contents() != 0
                && dst.contents() != 0) {
            MemoryUtil.memCopy(src.contents() + source.offset(), dst.contents() + target.offset(), copyLength);
            return;
        }
        endRenderForBlit();
        MetalNative.nCtxCopyBuffer(ctx, src.handle(), source.offset(), dst.handle(), target.offset(),
                Math.min(source.length(), target.length()));
        src.lastUseFrame = openSerial;
        dst.lastUseFrame = openSerial;
    }

    @Override
    public void writeToTexture(GpuTexture texture, ByteBuffer data, int mipLevel, int layer, int dstX,
            int dstY, int width, int height) {
        MetalTexture target = (MetalTexture) texture;
        if (width <= 0 || height <= 0 || target.isClosed()) {
            return;
        }
        int rowBytes = width * texture.getFormat().blockSize();
        long size = (long) rowBytes * height;
        flushPendingClears(target);
        MetalStagingRing.Allocation alloc = stage(size, 16);
        MemoryUtil.memByteBuffer(alloc.address(), (int) size).put(data.duplicate().limit(
                data.position() + (int) size));
        endRenderForBlit();
        MetalNative.nCtxCopyBufferToTexture(ctx, alloc.buffer(), alloc.offset(), rowBytes, (int) size,
                target.handle(), layer, mipLevel, dstX, dstY, width, height);
    }

    @Override
    public void copyBufferToTexture(GpuBufferSlice source, int srcX, int srcY, int rowLength,
            int imageHeight, GpuTexture texture, int dstX, int dstY, int width, int height, int mipLevel,
            int layer) {
        MetalTexture target = (MetalTexture) texture;
        MetalBuffer src = (MetalBuffer) source.buffer();
        if (width <= 0 || height <= 0 || target.isClosed() || src.isClosed()) {
            return;
        }
        int pixel = texture.getFormat().blockSize();
        int pixelsPerRow = rowLength > 0 ? rowLength : width;
        int rowBytes = pixelsPerRow * pixel;
        long offset = source.offset() + ((long) srcY * pixelsPerRow + srcX) * pixel;
        flushPendingClears(target);
        endRenderForBlit();
        MetalNative.nCtxCopyBufferToTexture(ctx, src.handle(), offset, rowBytes,
                (int) Math.min(Integer.MAX_VALUE, (long) rowBytes * height), target.handle(), layer,
                mipLevel, dstX, dstY, width, height);
        src.lastUseFrame = openSerial;
    }

    @Override
    public void copyTextureToBuffer(GpuTexture texture, GpuBuffer buffer, long offset, Runnable onDone,
            int mipLevel) {
        copyTextureToBuffer(texture, buffer, offset, onDone, mipLevel, 0, 0, texture.getWidth(mipLevel),
                texture.getHeight(mipLevel));
    }

    @Override
    public void copyTextureToBuffer(GpuTexture texture, GpuBuffer buffer, long offset, Runnable onDone,
            int mipLevel, int x, int y, int width, int height) {
        MetalTexture src = (MetalTexture) texture;
        MetalBuffer dst = (MetalBuffer) buffer;
        int rowBytes = width * texture.getFormat().blockSize();
        flushPendingClears(src);
        endRenderForBlit();
        MetalNative.nCtxCopyTextureToBuffer(ctx, src.handle(), 0, mipLevel, x, y, width, height,
                dst.handle(), offset, rowBytes, rowBytes * height);
        dst.lastUseFrame = openSerial;
        RenderSystem.queueFencedTask(onDone);
    }

    @Override
    public void copyTextureToTexture(GpuTexture source, GpuTexture target, int mipLevel, int dstX, int dstY,
            int srcX, int srcY, int width, int height) {
        flushPendingClears((MetalTexture) source);
        flushPendingClears((MetalTexture) target);
        endRenderForBlit();
        MetalNative.nCtxCopyTextureToTexture(ctx, ((MetalTexture) source).handle(), 0, mipLevel, srcX, srcY,
                ((MetalTexture) target).handle(), 0, mipLevel, dstX, dstY, width, height);
    }

    void blitToSurface(MetalSurface surface, MetalTextureView view) {
        flushPendingClears(view.texture());
        endEncoder();
        long mainDepth = 0;
        try {
            com.mojang.blaze3d.pipeline.RenderTarget target = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainRenderTarget();
            if (target != null && target.getDepthTexture() instanceof MetalTexture depth) {
                mainDepth = depth.handle();
            }
        } catch (RuntimeException ignored) {
            mainDepth = 0;
        }
        MetalNative.nCtxPresent(ctx, surface.layer(), view.handle(), mainDepth);
    }

    @Override
    public GpuFence createFence() {
        return new MetalFence(this, openSerial);
    }
}
