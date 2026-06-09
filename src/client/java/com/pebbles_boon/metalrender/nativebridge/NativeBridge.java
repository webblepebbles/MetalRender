package com.pebbles_boon.metalrender.nativebridge;

public final class NativeBridge {
  private static volatile boolean libLoaded;
  static {
    try {
      System.loadLibrary("metalrender");
      libLoaded = true;
    } catch (Throwable t) {
      libLoaded = false;
    }
  }

  private NativeBridge() {
  }

  public static void loadLibrary() {
    if (!libLoaded) {
      System.loadLibrary("metalrender");
      libLoaded = true;
    }
  }

  public static boolean isLibLoaded() {
    return libLoaded;
  }

  public static native boolean nIsAvailable();

  public static native long nInit(int width, int height, float scale);

  public static native void nResize(long handle, int width, int height,
      float scale);

  public static native void nBeginFrame(long handle, float[] proj, float[] view,
      float fogStart, float fogEnd);

  public static native void nOnWorldLoaded(long handle);

  public static native void nOnWorldUnloaded(long handle);

  public static native void nDestroy(long handle);

  public static native String nGetDeviceName();

  public static native boolean nSupportsIndirect();

  public static native boolean nSupportsMeshShaders();

  public static native void nSetCurrentThreadQoS(int qosClass);

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

  public static native void nDrawIndexedBuffer(long frameContext,
      long vertexBuffer,
      long indexBuffer, int indexCount,
      int baseIndex);

  public static native void nDrawIndexedBatch(long frameContext,
      long indexBuffer,
      float[] drawData, int drawCount);

  public static native void nDrawBuffer(long frameContext, long vertexBuffer,
      int vertexCount, int baseVertex);

  public static native long nGetCurrentFrameContext(long handle);

  public static native void nEndFrame(long handle);

  public static native void nSetProjectionMatrix(long handle, float[] matrix);

  public static native void nSetModelViewMatrix(long handle, float[] matrix);

  public static native void nSetCameraPosition(long handle, double x, double y,
      double z);

  public static native void nSetFrameMatrices(long handle, float[] projMatrix,
      float[] mvMatrix, double camX,
      double camY, double camZ);

  public static native void nBindTexture(long handle, long textureHandle,
      int slot);

  public static native long nCreateTexture2D(long deviceHandle, int width,
      int height, byte[] pixelData);

  public static native void nDestroyTexture2D(long textureHandle);

  public static native void nUpdateTexture2D(long textureHandle, int width,
      int height, byte[] pixelData);

  public static native long nGetDeviceHandle(long handle);

  public static native long nGetShaderLibraryHandle(long handle);

  public static native long nGetInhousePipelineHandle(long handle);

  public static native long nGetDefaultPipelineHandle(long handle);

  public static native int nGetGLTextureId(long handle);

  public static native int nGetIOSurfaceWidth(long handle);

  public static native int nGetIOSurfaceHeight(long handle);

  public static native void nWaitForRender(long handle);

  public static native boolean nIsFrameReady(long handle);

  public static native void nSetReuseTerrainFrame(boolean reuse);

  public static native boolean nBindIOSurfaceToTexture(long handle,
      int glTexture);

  public static native boolean nReadbackPixels(long handle,
      java.nio.ByteBuffer dest);

  public static native boolean nReadbackDepth(long handle,
      java.nio.ByteBuffer dest);

  public static native long nGetEntityPipelineHandle(long handle);

  public static native long nGetEntityTranslucentPipelineHandle(long handle);

  public static native long nGetEntityEmissivePipelineHandle(long handle);

  public static native long nGetParticlePipelineHandle(long handle);

  public static native void nSetEntityOverlay(long frameContext, float hurtTime,
      float whiteFlash, float alpha);

  public static native void nSetWaterFog(long frameContext, float waterFog);

  public static native void nSetSkyBrightness(long frameContext,
      float brightness);

  public static native void nSetEntityTintColor(long frameContext, float r,
      float g, float b, float a);

  public static native void nBindEntityTexture(long frameContext,
      long textureHandle);

  public static native void nDrawEntityBuffer(long frameContext,
      long vertexBuffer,
      int vertexCount, int baseVertex,
      int renderFlags);

  public static native void nDrawEntityBufferIndexed(long frameContext, long vertexBuffer,
      long indexBuffer, int indexCount, int baseIndex,
      int renderFlags);

  public static native void nSetDebugColor(long frameContext, float r, float g,
      float b, float a);

  public static native void nDrawLineBuffer(long frameContext,
      long vertexBuffer, int vertexCount);

  public static native void nDrawTriangleBuffer(long frameContext, long vertexBuffer, int vertexCount);

  public static native void nDrawOverlayQuad(long frameContext, float r,
      float g, float b, float a);

  public static native void nUploadCameraUniforms(long handle, float[] viewProj, float[] proj,
      float[] modelView, float[] cameraPos,
      float[] frustumPlanes, float screenW, float screenH,
      float nearPlane, float farPlane, int totalChunks);

  public static native void nUploadSubChunkData(long handle, java.nio.ByteBuffer directBuffer, int count);

  public static native void nUploadChunkUniforms(long handle, java.nio.ByteBuffer directBuffer,
      int count);

  public static native void nSetGPUDrivenEnabled(long handle, boolean enabled);

  public static native int nRunGPUCulling(long handle, int chunkCount);

  public static native void nExecuteIndirectDraws(long frameContext, long vertexBuffer, long indexBuffer);

  public static native int nGetGPUVisibleCount(long handle);

  public static native void nGetGPUCullStats(int[] outStats);

  public static native int nGetThermalState();

  public static native int nGetThermalLODReduction();

  public static native void nSetRenderDistance(int distanceBlocks);

  public static native long nGetAvailableMemory();

  public static native int nGetHiZMipCount();

  public static native boolean nIsGPUDrivenActive();

  public static native boolean nAreMeshShadersActive();

  public static native void nRegisterChunkMesh(int cx, int cy, int cz,
      long bufferHandle, int quadCount,
      int opaqueQuadCount,
      int lodLevel);

  public static native void nUnregisterChunkMesh(int cx, int cy, int cz);

  public static native int nDrawAllVisibleChunks(long frameContext,
      long indexBuffer);

  public static native int nBatchPackFaces(long outBufferAddr, int outOffset,
      java.nio.ByteBuffer faceData,
      int faceCount);

  public static native void nWatchdogReset();

  public static native void nFlushFrames();

  public static native void nClearAllChunkRegistrations();

  public static native void nFlushDeferredDeletions();

  public static native int nMegaDefragment();

  public static native boolean nIsMegaBufferActive();

  public static native void nDrawDeferredWaterPass(long frameContext);

  public static native boolean nAreArgumentBuffersActive();

  public static native boolean nAreMemorylessTargetsActive();

  public static native void nSetFeatureFlags(boolean enableIndirectCommandBuffers,
      boolean enableMeshShaders, boolean enableArgumentBuffers,
      boolean enableProgrammableBlending,
      boolean enableMemorylessTargets);

  public static native void nDrawOITPass(long frameContext);
}
