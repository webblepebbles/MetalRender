package com.pebbles_boon.metalrender.nativebridge;

public final class MetalBackend {
  private MetalBackend() {
  }

  public static native long initNative(long windowHandle, boolean someFlag);

  public static native void uploadStaticMesh(long handle,
      java.nio.Buffer vertexData,
      int vertexCount, int stride);

  public static native void resize(long handle, int width, int height);

  public static native void setCamera(long handle, float[] viewProj4x4);

  public static native void render(long handle, float timeSeconds);

  public static native void destroy(long handle);

  public static native boolean supportsMeshShaders();

  public static native String getLastInitErrorNative();

  public static native long createVertexBuffer(long handle,
      java.nio.Buffer data, int size);

  public static native long createIndexBuffer(long handle, java.nio.Buffer data,
      int size);

  public static native void destroyBuffer(long handle, long bufferHandle);

  public static native boolean createTerrainPipelines(long handle);

  public static native boolean isPipelineReady(long handle, int pass);

  public static native void drawIndexed(long handle, long vboHandle,
      long iboHandle, int indexCount,
      int firstIndex, int baseVertex,
      int pass);
}
