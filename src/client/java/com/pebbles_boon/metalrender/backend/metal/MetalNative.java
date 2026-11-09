package com.pebbles_boon.metalrender.backend.metal;

import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import java.nio.ByteBuffer;

final class MetalNative {
    static final int PIXEL_RGBA8 = 70;
    static final int PIXEL_R8 = 10;
    static final int PIXEL_R8I = 14;
    static final int PIXEL_DEPTH32 = 252;
    static final int PIXEL_INVALID = 0;

    static final int TEXTURE_2D = 2;
    static final int TEXTURE_2D_ARRAY = 3;
    static final int TEXTURE_CUBE = 5;

    static final int USAGE_SHADER_READ = 0x1;
    static final int USAGE_RENDER_TARGET = 0x4;
    static final int USAGE_PIXEL_FORMAT_VIEW = 0x10;

    static final int STORAGE_SHARED = 0;
    static final int STORAGE_PRIVATE = 2;

    static final int PRIMITIVE_POINT = 0;
    static final int PRIMITIVE_LINE = 1;
    static final int PRIMITIVE_LINE_STRIP = 2;
    static final int PRIMITIVE_TRIANGLE = 3;
    static final int PRIMITIVE_TRIANGLE_STRIP = 4;

    static final int TOPOLOGY_POINT = 1;
    static final int TOPOLOGY_LINE = 2;
    static final int TOPOLOGY_TRIANGLE = 3;

    static final int CULL_NONE = 0;
    static final int CULL_BACK = 2;
    static final int WINDING_CLOCKWISE = 0;
    static final int FILL = 0;
    static final int FILL_LINES = 1;

    static final int COMPARE_ALWAYS = 7;

    private MetalNative() {
    }

    static void ensureLoaded() {
        NativeBridge.loadLibrary();
    }

    static native long[] nDeviceCreate();

    static native void nBackendAttach(long device, long queue);

    static native String nDeviceName(long device);

    static native String nLastError();

    static native String nPassReport();

    static native void nRelease(long object);

    static native long nLibraryCreate(long device, ByteBuffer data, int length);

    static native int[] nUniformBlockSizes();

    static native long nSurfaceCreate(long nsWindow, int width, int height, boolean vsync);

    static native void nSurfaceResize(long layer, int width, int height);

    static native void nSurfaceSetVsync(long layer, boolean vsync);

    static native long nBufferCreate(long device, long size, boolean writeCombined);

    static native long nBufferContents(long buffer);

    static native void nSetLabel(long object, String label);

    static native long nTextureCreate(long device, int type, int format, int width, int height,
            int depthOrLayers, int mipLevels, int usage, int storage);

    static native long nTextureView(long texture, int format, int type, int baseMip, int mipCount,
            int baseSlice, int sliceCount);

    static native long nSamplerCreate(long device, int addressU, int addressV, int minFilter,
            int magFilter, int mipFilter, int maxAnisotropy, float lodMax);

    static native long nDepthStencilCreate(long device, int compare, boolean write);

    static native long nRenderPipelineCreate(long device, long library, String vertexName,
            String fragmentName, int[] constants, int[] vertexLayout, int colorFormat,
            int depthFormat, int[] blend, int writeMask, int topologyClass);

    static native long nCtxCreate(long device, long queue, long library);

    static native void nCtxDestroy(long ctx);

    static native boolean nCtxBeginRender(long ctx, String label, long colorTexture, int colorLevel,
            int colorSlice, boolean clearColor, float r, float g, float b, float a,
            long depthTexture, int depthLevel, int depthSlice, boolean clearDepth, double depth);

    static native void nCtxEndEncoder(long ctx);

    static native void nCtxPushDebugGroup(long ctx, String label);

    static native void nCtxPopDebugGroup(long ctx);

    static native void nCtxSetPipeline(long ctx, long pipelineState, long depthStencilState,
            int cullMode, int winding, int fillMode, float depthBias, float slopeScale);

    static native void nCtxSetVertexBuffer(long ctx, long buffer, long offset, int index);

    static native void nCtxSetBuffer(long ctx, long buffer, long offset, int index);

    static native void nCtxSetTexture(long ctx, long texture, long sampler, int index);

    static native void nCtxSetScissor(long ctx, int x, int y, int width, int height);

    static native void nCtxDraw(long ctx, int primitive, int start, int count, int instances);

    static native void nCtxDrawIndexed(long ctx, int primitive, int indexCount, boolean index32,
            long indexBuffer, long indexOffset, int instances, int baseVertex);

    static native boolean nCtxClearRect(long ctx, int x, int y, int width, int height, float r,
            float g, float b, float a, float depth, boolean writeDepth);

    static native void nCtxCopyBuffer(long ctx, long src, long srcOffset, long dst, long dstOffset,
            long length);

    static native void nCtxCopyBufferToTexture(long ctx, long src, long srcOffset, int bytesPerRow,
            int bytesPerImage, long texture, int slice, int mip, int x, int y, int width,
            int height);

    static native void nCtxCopyTextureToBuffer(long ctx, long texture, int slice, int mip, int x,
            int y, int width, int height, long dst, long dstOffset, int bytesPerRow,
            int bytesPerImage);

    static native void nCtxCopyTextureToTexture(long ctx, long src, int srcSlice, int srcMip,
            int srcX, int srcY, long dst, int dstSlice, int dstMip, int dstX, int dstY, int width,
            int height);

    static native boolean nCtxPresent(long ctx, long layer, long sourceTexture, long mainDepth);

    static native boolean nCtxClearDepthFolded(long ctx, double depth);

    static native void nSetPacedPresent(boolean enabled);

    static native void nSetPresentationDiagnostics(boolean enabled);

    static native long[] nBackendStats();

    static native String nPresentationReport();

    static native long nCtxSubmit(long ctx, boolean frameEnd);

    static native long nCtxCompleted(long ctx);

    static native boolean nCtxWait(long ctx, long serial, long timeoutMs);

    static native long nQueryCreate();

    static native void nQueryRelease(long query);

    static native void nCtxQueryBegin(long ctx, long query);

    static native void nCtxQueryEnd(long ctx, long query);

    static native long nQueryValue(long query);

    static native long nCtxRenderEncoder(long ctx, boolean terrain);

    static native void nCtxRestoreViewport(long ctx);
}
