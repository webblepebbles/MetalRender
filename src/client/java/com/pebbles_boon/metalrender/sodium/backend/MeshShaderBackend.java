package com.pebbles_boon.metalrender.sodium.backend;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.backend.MetalRenderer;
import com.pebbles_boon.metalrender.nativebridge.MeshShaderNative;
import com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class MeshShaderBackend {
  private long[] terrainPipelineHandles = new long[3];
  private long fallbackPipelineHandle;
  private boolean active;
  private boolean meshShadersAvailable;
  private boolean gpuDrivenEnabled;
  private ByteBuffer meshletUploadBuffer;
  private int meshletUploadCapacity;
  private int currentMeshletCount;
  private int lastVisibleCount;
  private int lastDispatchedThreadgroups;
  private long lastStatsLogMs;
  private static final long STATS_LOG_INTERVAL_MS = 5000;

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
    meshletUploadCapacity = 4096;
    meshletUploadBuffer = ByteBuffer.allocateDirect(meshletUploadCapacity * 32)
        .order(ByteOrder.nativeOrder());
    active = true;
    gpuDrivenEnabled = meshShadersAvailable &&
        (terrainPipelineHandles[0] != 0 || fallbackPipelineHandle != 0);
    MetalLogger.info("mesh backend weady (mesh=%s gpu=%s pipes=%d)",
        meshShadersAvailable ? "ok" : "no",
        gpuDrivenEnabled ? "on" : "off",
        MeshShaderNative.getActivePipelineCount());
  }

  public void prepareMeshlets(int[] meshletVertexOffsets,
      int[] meshletIndexOffsets,
      int[] meshletVertexCounts,
      int[] meshletTriangleCounts,
      int[] meshletLodLevels, int[] meshletChunkIndices,
      int count) {
    if (!active || !gpuDrivenEnabled || count <= 0)
      return;
    if (count > meshletUploadCapacity) {
      meshletUploadCapacity = count + (count >> 2);
      meshletUploadBuffer = ByteBuffer.allocateDirect(meshletUploadCapacity * 32)
          .order(ByteOrder.nativeOrder());
    }
    meshletUploadBuffer.clear();
    for (int i = 0; i < count; i++) {
      meshletUploadBuffer.putInt(meshletVertexOffsets[i]);
      meshletUploadBuffer.putInt(meshletIndexOffsets[i]);
      meshletUploadBuffer.putInt(meshletVertexCounts[i]);
      meshletUploadBuffer.putInt(meshletTriangleCounts[i]);
      meshletUploadBuffer.putInt(meshletLodLevels[i]);
      meshletUploadBuffer.putInt(meshletChunkIndices[i]);
      meshletUploadBuffer.putInt(0);
      meshletUploadBuffer.putInt(0);
    }
    meshletUploadBuffer.flip();
    MeshShaderNative.uploadMeshletBuffer(0, meshletUploadBuffer, count);
    currentMeshletCount = count;
  }

  public void drawChunkMesh(long frameContext, long argumentBuffer,
      int objectThreadgroups, int meshThreadsPerGroup) {
    if (!active || frameContext == 0)
      return;
    long pipeline = getPipeline(0);
    if (pipeline != 0) {
      MeshShaderNative.drawMeshThreadgroups(
          frameContext, pipeline, objectThreadgroups, meshThreadsPerGroup,
          argumentBuffer);
      lastDispatchedThreadgroups = objectThreadgroups;
    }
    logStatsIfNeeded();
  }

  public void drawChunkMeshPass(long frameContext, int passIndex,
      long argumentBuffer, int threadgroups) {
    if (!active || frameContext == 0 || passIndex < 0 || passIndex > 2)
      return;
    long pipeline = getPipeline(passIndex);
    if (pipeline != 0) {
      MeshShaderNative.drawMeshThreadgroups(frameContext, pipeline,
          threadgroups, 256, argumentBuffer);
    }
  }

  public void dispatchTerrainFromCullResults(long handle, long argumentBuffer) {
    if (!active)
      return;
    int visibleCount = NativeBridge.nGetGPUVisibleCount(handle);
    lastVisibleCount = visibleCount;
    if (visibleCount > 0) {
      MeshShaderNative.dispatchTerrain(handle, visibleCount, argumentBuffer);
    }
  }

  public void dispatchTerrainMultiPass(long frameContext, long handle, long argumentBuffer) {
    if (!active || frameContext == 0)
      return;
    if (argumentBuffer == 0) {
      return;
    }
    int visibleCount = NativeBridge.nGetGPUVisibleCount(handle);
    lastVisibleCount = visibleCount;
    if (visibleCount <= 0)
      return;
    int threadgroups = (visibleCount + 255) / 256;
    for (int pass = 0; pass < 3; pass++) {
      long pipeline = getPipeline(pass);
      if (pipeline != 0) {
        MeshShaderNative.drawMeshThreadgroups(frameContext, pipeline, threadgroups, 256, argumentBuffer);
      }
    }
  }
  public boolean dispatchTerrainViaICB(long frameContext, long icbHandle,
      com.pebbles_boon.metalrender.draw.TerrainIndirectDraw indirectDraw) {
    if (!active || frameContext == 0 || icbHandle == 0
        || indirectDraw == null) {
      return false;
    }
    boolean ok = indirectDraw.execute(frameContext);
    if (ok) {
      lastVisibleCount = indirectDraw.getCurrentCommandCount();
    }
    return ok;
  }

  public boolean dispatchTerrainViaICB(long frameContext, long icbHandle) {
    return dispatchTerrainViaICB(frameContext, icbHandle, null);
  }

  public boolean isClusterCullReady(long lastUploadNs) {
    return lastUploadNs > 0L && (System.nanoTime() - lastUploadNs) < 1_000_000_000L;
  }

  private long getPipeline(int passIndex) {
    if (passIndex >= 0 && passIndex < 3 &&
        terrainPipelineHandles[passIndex] != 0) {
      return terrainPipelineHandles[passIndex];
    }
    return fallbackPipelineHandle;
  }

  private void logStatsIfNeeded() {
    long now = System.currentTimeMillis();
    if (now - lastStatsLogMs > STATS_LOG_INTERVAL_MS) {
      lastStatsLogMs = now;
      MetalLogger.info(
          "mesh stats: vis=%d tg=%d ml=%d p=%d",
          lastVisibleCount, lastDispatchedThreadgroups, currentMeshletCount,
          MeshShaderNative.getActivePipelineCount());
    }
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
    meshletUploadBuffer = null;
    active = false;
    gpuDrivenEnabled = false;
  }

  public boolean isActive() {
    return active;
  }

  public boolean areMeshShadersAvailable() {
    return meshShadersAvailable;
  }

  public boolean isGPUDrivenEnabled() {
    return gpuDrivenEnabled;
  }

  public int getLastVisibleCount() {
    return lastVisibleCount;
  }

  public int getCurrentMeshletCount() {
    return currentMeshletCount;
  }
}
