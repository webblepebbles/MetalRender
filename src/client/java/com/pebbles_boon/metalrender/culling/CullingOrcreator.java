package com.pebbles_boon.metalrender.culling;

import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.util.MetalLogger;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

public class CullingOrcreator {
  public static final int CLUSTER_X = 8;
  public static final int CLUSTER_Z = 8;
  private static final int MAX_REGIONS = 4096;

  private final long[] visibleClusterKeys = new long[MAX_REGIONS];
  private final LongOpenHashSet visibleClusterSet = new LongOpenHashSet();
  private volatile long lastUploadNs;
  private volatile int visibleClusterCount;
  private volatile boolean active;
  private volatile boolean cpuFallbackEnabled;

  private static long clusterKey(int x, int z) {
    return ((long) x << 32) | (z & 0xFFFFFFFFL);
  }

  private static int floorDiv(int value, int divisor) {
    return Math.floorDiv(value, divisor);
  }

  public boolean markCluster(int clusterX, int clusterZ) {
    if (visibleClusterCount >= MAX_REGIONS) {
      return false;
    }
    long key = clusterKey(clusterX, clusterZ);
    if (!visibleClusterSet.add(key)) {
      return false;
    }
    visibleClusterKeys[visibleClusterCount++] = key;
    return true;
  }

  public void resetMarks() {
    visibleClusterSet.clear();
    visibleClusterCount = 0;
  }

  public void uploadToGpu(float[] frustumPlanes) {
    if (!active || !NativeBridge.isLibLoaded()) {
      return;
    }
    try {
      NativeBridge.nUploadClusterVisibilityKeys(visibleClusterKeys, visibleClusterCount);
      NativeBridge.nSetClusterCullingEnabled(visibleClusterCount > 0);
      lastUploadNs = System.nanoTime();
    } catch (UnsatisfiedLinkError e) {
      MetalLogger.warn("cluster visibility upload missing: " + e.getMessage());
      active = false;
      NativeBridge.nSetClusterCullingEnabled(false);
    }
  }

  public int rebuildFromFrustumCpu(FrustumCuller culler, int chunkRadius,
      float cameraX, float cameraY, float cameraZ) {
    if (!active || !cpuFallbackEnabled || culler == null) {
      resetMarks();
      return 0;
    }
    resetMarks();
    int clusterRadius = Math.min(MAX_REGIONS / 2,
        Math.max(1, (chunkRadius + CLUSTER_X - 1) / CLUSTER_X + 1));
    int cameraChunkX = (int) Math.floor(cameraX / 16.0f);
    int cameraChunkZ = (int) Math.floor(cameraZ / 16.0f);
    int centerClusterX = floorDiv(cameraChunkX, CLUSTER_X);
    int centerClusterZ = floorDiv(cameraChunkZ, CLUSTER_Z);
    for (int dx = -clusterRadius; dx <= clusterRadius; dx++) {
      for (int dz = -clusterRadius; dz <= clusterRadius; dz++) {
        int clusterX = centerClusterX + dx;
        int clusterZ = centerClusterZ + dz;
        float minX = clusterX * CLUSTER_X * 16.0f;
        float minZ = clusterZ * CLUSTER_Z * 16.0f;
        float maxX = minX + CLUSTER_X * 16.0f;
        float maxZ = minZ + CLUSTER_Z * 16.0f;
        if (!culler.testBoundingBox(minX - cameraX, -128.0f - cameraY,
            minZ - cameraZ, maxX - cameraX, 256.0f - cameraY,
            maxZ - cameraZ)) {
          continue;
        }
        markCluster(clusterX, clusterZ);
      }
    }
    return visibleClusterCount;
  }

  public boolean isClusterVisible(int chunkX, int chunkZ) {
    if (!active || visibleClusterCount == 0) {
      return true;
    }
    return visibleClusterSet.contains(clusterKey(
        floorDiv(chunkX, CLUSTER_X), floorDiv(chunkZ, CLUSTER_Z)));
  }

  public long getLastUploadNs() {
    return lastUploadNs;
  }

  public int getCurrentClusterVisibleCount() {
    return visibleClusterCount;
  }

  public void setActive(boolean v) {
    active = v;
    if (!v) {
      resetMarks();
      lastUploadNs = 0L;
    }
    if (NativeBridge.isLibLoaded()) {
      NativeBridge.nSetClusterCullingEnabled(v);
    }
  }

  public void setCpuFallbackEnabled(boolean v) {
    cpuFallbackEnabled = v;
  }

  public boolean isActive() {
    return active;
  }

  public void shutdown() {
    active = false;
    cpuFallbackEnabled = false;
    lastUploadNs = 0L;
    resetMarks();
    if (NativeBridge.isLibLoaded()) {
      NativeBridge.nSetClusterCullingEnabled(false);
    }
  }
}
