package com.pebbles_boon.metalrender.nativebridge;

public final class MeshShaderNative {
  private MeshShaderNative() {
  }

  public static native void dispatchTerrain(long handle, int visibleRegions,
      long indirectBufferAddr);

  public static native long createMeshPipeline(long device, long library,
      String objectFunc,
      String meshFunc,
      String fragmentFunc);

  public static native void drawMeshThreadgroups(long frameContext,
      long pipelineHandle,
      int objectThreadgroups,
      int meshThreadsPerGroup,
      long argumentBuffer);

  public static native void destroyMeshPipeline(long pipelineHandle);

  public static native void uploadMeshletBuffer(long deviceHandle, java.nio.ByteBuffer directBuffer,
      int count);

  public static native int getActivePipelineCount();

  public static native long[] createTerrainMeshPipelines(long libraryHandle);

  public static native void uploadClusterVisibilitySSBO(int regionCount,
      byte[] data);
  public static native void markFrustumPlanes(float[] planes);

  public static native long createTranslucencySortSSBO(int capacity);
  public static native int dispatchTranslucencySort(long ssbo, int count);
  public static native int readTranslucencyOrder(long ssbo, int[] out);
  public static native void destroyTranslucencySortSSBO(long ssbo);

  public static native long createHiZPyramid(int width, int height);
  public static native void updateHiZPyramid(long handle,
      java.nio.ByteBuffer depth, int width, int height);
  public static native void destroyHiZPyramid(long handle);
}
