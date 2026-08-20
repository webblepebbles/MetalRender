package com.pebbles_boon.metalrender.sodium.backend;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.backend.MetalRenderer;
import com.pebbles_boon.metalrender.nativebridge.MeshShaderNative;
import com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker;
import com.pebbles_boon.metalrender.util.MetalLogger;

public class MeshShaderBackend {
  private long[] terrainPipelineHandles = new long[3];
  private long fallbackPipelineHandle;
  private boolean meshShadersAvailable;

  public void initialize() {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return;
    meshShadersAvailable = MetalHardwareChecker.supportsMeshShaders();
    long library = renderer.getBackend().getShaderLibraryHandle();
    if (meshShadersAvailable && library != 0) {
      long[] handles = MeshShaderNative.createTerrainMeshPipelines(library);
      if (handles != null && handles.length >= 3) {
        terrainPipelineHandles[0] = handles[0];
        terrainPipelineHandles[1] = handles[1];
        terrainPipelineHandles[2] = handles[2];
        if (handles[0] != 0) {
          MetalLogger.info("mesh pipes made: opaque=0x%X cutout=0x%X em=0x%X",
              handles[0], handles[1], handles[2]);
        }
      }
      if (terrainPipelineHandles[0] == 0) {
        long device = renderer.getBackend().getDeviceHandle();
        fallbackPipelineHandle = MeshShaderNative.createMeshPipeline(
            device, library, "object_terrain", "mesh_terrain",
            "fragment_terrain_mesh_opaque");
      }
    }
    boolean gpuDrivenEnabled = meshShadersAvailable &&
        (terrainPipelineHandles[0] != 0 || fallbackPipelineHandle != 0);
    MetalLogger.info("mesh backend weady (mesh=%s gpu=%s pipes=%d)",
        meshShadersAvailable ? "ok" : "no",
        gpuDrivenEnabled ? "on" : "off",
        MeshShaderNative.getActivePipelineCount());
  }

  public void shutdown() {
    for (int i = 0; i < 3; i++) {
      if (terrainPipelineHandles[i] != 0) {
        MeshShaderNative.destroyMeshPipeline(terrainPipelineHandles[i]);
        terrainPipelineHandles[i] = 0;
      }
    }
    if (fallbackPipelineHandle != 0) {
      MeshShaderNative.destroyMeshPipeline(fallbackPipelineHandle);
      fallbackPipelineHandle = 0;
    }
  }

  public boolean areMeshShadersAvailable() {
    return meshShadersAvailable;
  }

}
