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
}
