package com.pebbles_boon.metalrender.performance;

import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.util.FrustumCuller;
import com.pebbles_boon.metalrender.util.OcclusionCuller;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;

public final class RenderOptimizer {
  private static final RenderOptimizer INSTANCE = new RenderOptimizer();
  private FrustumCuller frustumCuller;
  private OcclusionCuller occlusionCuller;
  private int frustumCulledThisFrame = 0;
  private int occlusionCulledThisFrame = 0;
  private int totalChunksThisFrame = 0;
  private long currentFrame = 0;
  private boolean initialized = false;

  private RenderOptimizer() {
  }

  public static RenderOptimizer getInstance() {
    return INSTANCE;
  }

  public void updateFrame(Camera camera, Matrix4f viewProjectionMatrix) {
    if (!initialized) {
      frustumCuller = new FrustumCuller();
      occlusionCuller = new OcclusionCuller();
      initialized = true;
    }
    currentFrame++;
    if (MetalRenderConfig.aggressiveFrustumCulling()) {
      frustumCuller.update(viewProjectionMatrix);
    }
    if (MetalRenderConfig.occlusionCulling()) {
      occlusionCuller.beginFrame(camera);
    }
    frustumCulledThisFrame = 0;
    occlusionCulledThisFrame = 0;
    totalChunksThisFrame = 0;
  }

  public boolean shouldRenderChunk(BlockPos chunkPos, Camera camera) {
    totalChunksThisFrame++;
    int regionX = chunkPos.getX() >> 4;
    int regionZ = chunkPos.getZ() >> 4;
    int minY = Math.max(chunkPos.getY() - 16, -64);
    int maxY = Math.min(chunkPos.getY() + 16, 320);
    boolean frustumVisible = true;
    if (MetalRenderConfig.aggressiveFrustumCulling()) {
      frustumVisible = frustumCuller.isRegionVisible(regionX, regionZ, minY, maxY);
      if (!frustumVisible) {
        frustumCulledThisFrame++;
        return false;
      }
    }
    if (MetalRenderConfig.occlusionCulling() && frustumVisible) {
      boolean occluded = occlusionCuller.isChunkOccluded(chunkPos, camera);
      if (occluded) {
        occlusionCulledThisFrame++;
        return false;
      }
    }
    return true;
  }

  public PerformanceStats getFrameStats() {
    return new PerformanceStats(totalChunksThisFrame, frustumCulledThisFrame,
        occlusionCulledThisFrame, 0, currentFrame);
  }

  public void invalidateCache() {
    frustumCulledThisFrame = 0;
    occlusionCulledThisFrame = 0;
    totalChunksThisFrame = 0;
  }

  public static class PerformanceStats {
    public final int totalChunks;
    public final int frustumCulled;
    public final int occlusionCulled;
    public final int cacheSize;
    public final long currentFrame;
    public final double cullPercentage;

    PerformanceStats(int total, int frustumCulled, int occlusionCulled,
        int cacheSize, long frame) {
      this.totalChunks = total;
      this.frustumCulled = frustumCulled;
      this.occlusionCulled = occlusionCulled;
      this.cacheSize = cacheSize;
      this.currentFrame = frame;
      int culled = frustumCulled + occlusionCulled;
      this.cullPercentage = total > 0 ? (double) culled / total * 100.0 : 0.0;
    }
  }
}