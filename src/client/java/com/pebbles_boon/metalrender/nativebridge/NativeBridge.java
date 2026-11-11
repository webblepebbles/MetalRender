package com.pebbles_boon.metalrender.nativebridge;

public final class NativeBridge {
    private static volatile boolean libLoaded;
    static {
        try {
            NativeLoader.load();
            java.nio.file.Path extracted = NativeLoader.getLoadedPath();
            if (extracted != null) {
                libLoaded = true;
            } else {
                System.loadLibrary("metalrender");
                libLoaded = true;
            }
        } catch (UnsatisfiedLinkError e) {
            System.err.println("[MetalRender] native metalrender library unavailable: "
                    + e.getMessage());
            libLoaded = false;
        } catch (Throwable t) {
            System.err.println("[MetalRender] native metalrender library failed to load: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            libLoaded = false;
        }
    }

    private NativeBridge() {
    }

    public static void loadLibrary() {
        if (!libLoaded) {
            try {
                NativeLoader.load();
            } catch (RuntimeException e) {
                System.err.println("[MetalRender] NativeLoader failed, trying loadLibrary: "
                        + e.getMessage());
            }
            if (NativeLoader.getLoadedPath() != null) {
                libLoaded = true;
                return;
            }
            System.loadLibrary("metalrender");
            libLoaded = true;
        }
    }

    public static boolean isLibLoaded() {
        return libLoaded;
    }

    public static native boolean nIsAvailable();

    public static native long nInit(int width, int height, float scale,
            boolean temporalMetalFX);

    public static native void nResize(long handle, int width, int height,
            float scale, boolean temporalMetalFX);

    public static native void nDestroy(long handle);

    public static native String nGetDeviceName();

    public static native boolean nSupportsIndirect();

    public static native boolean nSupportsMeshShaders();

    public static native boolean nSupportsIndirectMesh();

    public static native int nGetPerfCoreCount();

    public static native void nSetThreadQos(int cls);

    public static native void nSetOldFrustumFailsafe(boolean enabled);

    public static native void nSetVisibleSections(long[] keys, int count);

    public static native long nCreateBufferWithHint(long deviceHandle, int sizeBytes,
            int storageMode, long oldHandle);

    public static native long nCreateBuffer(long deviceHandle, int sizeBytes,
            int storageMode);

    public static native void nUploadBufferData(long bufferHandle, byte[] data,
            int offset, int length);

    public static native void nUploadBufferDataDirect(long bufferHandle,
            java.nio.ByteBuffer data,
            int offset, int length);

    public static native void nDestroyBuffer(long bufferHandle);

    public static native void nSetPipelineState(long frameContext,
            long pipelineHandle);

    public static native void nSetChunkOffset(long frameContext, float x, float y,
            float z);

    public static native long nGetCurrentFrameContext(long handle);

    public static native void nEndFrame(long handle);

    public static native void nSetProjectionMatrix(long handle, float[] matrix);

    public static native void nSetModelViewMatrix(long handle, float[] matrix);

    public static native void nSetCameraPosition(long handle, double x, double y,
            double z);

    public static native void nSetCameraDirection(long handle, float x, float y,
            float z);

    public static native void nSetCameraFacingCulling(boolean enabled);

    public static native void nSetReverseZ(boolean enabled);

    public static native void nGetCameraFacingCullStats(int[] outStats);

    public static native void nBindTexture(long handle, long textureHandle,
            int slot);

    public static native long nCreateTexture2D(long deviceHandle, int width,
            int height, int mipLevels, byte[] pixelData);

    public static native void nDestroyTexture2D(long textureHandle);

    public static native void nUpdateTexture2D(long textureHandle, int width,
            int height, byte[] pixelData);

    public static native void nUpdateTextureRects(long texture, int count, int[] rects, java.nio.ByteBuffer data);

    public static native void nUpdateTexture2DRegion(long textureHandle,
            int srcWidth, int x, int y, int w, int h, byte[] pixelData);

    public static native long nGetDeviceHandle(long handle);

    public static native long nGetShaderLibraryHandle(long handle);

    public static native long nGetInhousePipelineHandle(long handle);

    public static native int nGetIOSurfaceWidth(long handle);

    public static native int nGetIOSurfaceHeight(long handle);

    public static native void nWaitForRender(long handle);

    public static native boolean nIsFrameReady(long handle);

    public static native void nSetReuseTerrainFrame(boolean reuse);

    public static native boolean nBindIOSurfaceToTexture(long handle,
            int glTexture);

    public static native boolean nReadbackPixels(long handle,
            java.nio.ByteBuffer dest);

    public static native long nGetParticlePipelineHandle(long handle);

    public static native long nGetWeatherPipelineHandle(long handle);

    public static native long nGetEntityPipelineHandle(long handle);

    public static native long nGetEntityTranslucentPipelineHandle(long handle);

    public static native void nSetEntityOverlay(long frameContext, float hurtTime,
            float whiteFlash, float alpha);

    public static native void nSetWaterFog(long frameContext, float waterFog);

    public static native void nSetFog(float r, float g, float b,
            float envStart, float envEnd, float renderStart, float renderEnd);

    public static native void nSetSkyBrightness(long frameContext,
            float brightness);

    public static native void nBindEntityTexture(long frameContext,
            long textureHandle);

    public static native void nDrawEntityBuffer(long frameContext,
            long vertexBuffer,
            int vertexCount, int baseVertex,
            int renderFlags);

    public static native void nSetDebugColor(long frameContext, float r, float g,
            float b, float a);

    public static native void nDrawTriangleBuffer(long frameContext, long vertexBuffer, int vertexCount);

    public static native void nUploadSubChunkData(long handle, java.nio.ByteBuffer directBuffer, int count);

    public static native void nUploadChunkUniforms(long handle, java.nio.ByteBuffer directBuffer,
            int count);

    public static native int nRunGPUCulling(long handle, int chunkCount);

    public static native int nExecuteGpuCulledDraws(long frameContext, long indexBuffer);

    public static native int nGetGPUVisibleCount(long handle);

    public static native void nSetHiZCullEnabled(boolean enabled);

    public static native boolean nIsHiZReady();

    public static native void nSetClusterCullingEnabled(boolean enabled);

    public static native void nUploadClusterVisibilityKeys(long[] keys, int count);

    public static native void nSetTranslucencySortEnabled(boolean enabled);

    public static native int nGetThermalState();

    public static native float nGetGpuFrameTimeMs();

    public static native double nGetWaitRenderMs();

    public static native double nGetCglBindMs();

    public static native double nGetNativeDrawPrepMs();

    public static native int nGetPassGpuMs(float[] out);

    public static native int nPreciseSleep(long nanos);

    public static native long nGetPhysicalMemory();

    public static native boolean nArenaConfigure(int renderDistance, int capMb, boolean gpuCull, boolean oracle);

    public static native boolean nArenaReady();

    public static native void nSetArenaHizCull(boolean enabled);

    public static native long nGetArenaHizCulled();

    public static native void nArenaSetSprites(java.nio.ByteBuffer data, int count);

    public static native boolean nArenaPublish(int cx, int cy, int cz, int lodTier, java.nio.ByteBuffer quads,
            int quadBytes, java.nio.ByteBuffer units, int unitCount, int quadCount);

    public static native void nArenaClear();

    public static native boolean nFarConfigure(int budgetMb, int buildsPerFrame);

    public static native boolean nFarReady();

    public static native void nFarSetLooks(java.nio.ByteBuffer data, int count);

    public static native int nFarUploadTile(long key, int level, int ox, int oz, int minY, int yLo, int yHi,
            java.nio.ByteBuffer blob, int blobUints, java.nio.ByteBuffer tints, int tintCount, int quadBound,
            int flags, int lookPal, int plane);

    public static native void nFarSetOptions(int bits);

    public static native int nFarPollBuilt(long[] out);

    public static native void nFarCommit(long[] show, int nShow, long[] hide, int nHide);

    public static native void nFarSetNearColumns(int[] packed, int count);

    public static native void nFarSetView(float farRadius, int minCx, int minCz, int maxCx, int maxCz);

    public static native void nFarClear();

    public static native int nFarStats(long[] out);

    public static native int nArenaStats(long[] out);

    public static native int nArenaSeenAge(int cx, int cy, int cz);

    public static native void nArenaSetLodRings(int nearChunks, int midChunks);

    public static native int nArenaPollLodRequests(int[] out);

    public static native void nTerrainPrepare();

    public static native int nGetCullFailsafeFrames();

    public static native int nGetMegaStats(long[] out);

    public static native int nGetQuadStats(long[] out);

    public static native boolean nAreResidencySetsSupported();

    public static native long nCreateResidencySet(long device);

    public static native void nUpdateResidencySet(long set, long[] textures);

    public static native void nDestroyResidencySet(long set);

    public static native void nSetRenderDistance(int distanceBlocks);

    public static native void nSetLodRecencyEnabled(boolean enabled);

    public static native int nGetLodDrawnFrames(long[] outKeys, int[] outFrames);

    public static native boolean nIsGPUDrivenActive();

    public static native boolean nAreMeshShadersActive();

    public static native void nRegisterChunkMeshBatch(int count, long[] batchData);

    public static native void nRegisterChunkMesh(int cx, int cy, int cz,
            long bufferHandle, int quadCount,
            int opaqueQuadCount,
            long visibilityMask, int[] facingQuadCounts,
            int lodTier);

    public static native void nUnregisterChunkMesh(int cx, int cy, int cz);

    public static native int nDrawAllVisibleChunks(long frameContext,
            long indexBuffer);

    public static native void nFlushFrames();

    public static native void nClearAllChunkRegistrations();

    public static native void nFlushDeferredDeletions();

    public static native void nDrawDeferredWaterPass(long frameContext);

    public static native boolean nAreArgumentBuffersActive();

    public static native void nSetFeatureFlags(boolean enableIndirectCommandBuffers,
            boolean enableMeshShaders, boolean enableArgumentBuffers,
            boolean enableProgrammableBlending);

    public static native void nDrawOITPass(long frameContext);

    public static native int nTerrainExternalDraw(long encoder, long indexBuffer, int width, int height);

    public static native void nWaterExternalDraw(long encoder, int width, int height);

    public static native void nExternalFrameEnd();

}
