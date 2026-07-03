package com.pebbles_boon.metalrender.culling;

import com.pebbles_boon.metalrender.nativebridge.MeshShaderNative;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.util.MetalLogger;

public class CullingOrcreator {
  public static final int CLUSTER_X = 8;
  public static final int CLUSTER_Y = 4;
  public static final int CLUSTER_Z = 8;
  public static final int SECTIONS_PER_CLUSTER = CLUSTER_X * CLUSTER_Y * CLUSTER_Z;
  private static final int MAX_REGIONS = 4096;

  private final byte[] clusterBuffer = new byte[MAX_REGIONS * SECTIONS_PER_CLUSTER];
  private volatile long lastUploadNs = 0L;
  private volatile int visibleClusterSections = 0;
  private volatile boolean active;
  private volatile boolean cpuFallbackEnabled;

  public boolean markSection(int regionIndex, int sectionIndex) {
    if (regionIndex < 0 || regionIndex >= MAX_REGIONS ||
        sectionIndex < 0 || sectionIndex >= SECTIONS_PER_CLUSTER) {
      return false;
    }
    int byteOffset = regionIndex * SECTIONS_PER_CLUSTER + sectionIndex;
    if (clusterBuffer[byteOffset] != 0) {
      return false;
    }
    clusterBuffer[byteOffset] = 1;
    visibleClusterSections++;
    return true;
  }

  public void resetMarks() {
    java.util.Arrays.fill(clusterBuffer, (byte) 0);
    visibleClusterSections = 0;
  }

  public void uploadToGpu(float[] frustumPlanes) {
    if (!active || !NativeBridge.isLibLoaded() || visibleClusterSections == 0) {
      return;
    }
    try {
      if (frustumPlanes != null && frustumPlanes.length >= 24) {
        MeshShaderNative.markFrustumPlanes(frustumPlanes);
      }
      int regionCount = Math.min(MAX_REGIONS,
          (visibleClusterSections + SECTIONS_PER_CLUSTER - 1) / SECTIONS_PER_CLUSTER);
      MeshShaderNative.uploadClusterVisibilitySSBO(regionCount, clusterBuffer);
      lastUploadNs = System.nanoTime();
    } catch (UnsatisfiedLinkError e) {
      MetalLogger.warn("cluster ssbo missing: " + e.getMessage());
      active = false;
    }
  }
  public int rebuildFromFrustumCpu(FrustumCuller culler, int chunkRadius) {
    if (!active || !cpuFallbackEnabled || culler == null) {
      return visibleClusterSections;
    }
    resetMarks();
    int regionSpan = Math.max(1, chunkRadius / CLUSTER_X);
    int regionRadius = Math.min(MAX_REGIONS / 2, regionSpan);
    for (int rx = -regionRadius; rx <= regionRadius; rx++) {
      for (int rz = -regionRadius; rz <= regionRadius; rz++) {
        float minX = rx * CLUSTER_X * 16.0f;
        float minZ = rz * CLUSTER_Z * 16.0f;
        float maxX = minX + CLUSTER_X * 16.0f;
        float maxZ = minZ + CLUSTER_Z * 16.0f;
        if (!culler.testBoundingBox(minX, -128.0f, minZ,
            maxX, 256.0f, maxZ)) {
          continue;
        }
        int regionIndex = (rx + regionRadius) * (2 * regionRadius + 1)
            + (rz + regionRadius);
        for (int s = 0; s < SECTIONS_PER_CLUSTER; s++) {
          markSection(regionIndex, s);
        }
      }
    }
    return visibleClusterSections;
  }

  public long getLastUploadNs() {
    return lastUploadNs;
  }

  public int getCurrentClusterVisibleCount() {
    return visibleClusterSections;
  }

  public void setActive(boolean v) {
    active = v;
    if (!v) {
      lastUploadNs = 0L;
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
    visibleClusterSections = 0;
    java.util.Arrays.fill(clusterBuffer, (byte) 0);
  }
}
