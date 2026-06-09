package com.pebbles_boon.metalrender.util;

import com.pebbles_boon.metalrender.config.MetalRenderConfig;

public final class PerformanceLogger {
  private static final long NORMAL_LOG_INTERVAL = 5000;
  private static final long DEBUG_LOG_INTERVAL = 1000;
  private long frameCount = 0;
  private long totalChunksProcessed = 0;
  private long totalChunksDrawn = 0;
  private long totalFrustumCulled = 0;
  private long totalOcclusionCulled = 0;
  private long frameStartTime = 0;
  private long lastFrameStartTime = 0;
  private double lastFrameTime = 0;
  private double avgFrameTime = 0;
  private double currentFPS = 0;
  private long lastLogTime = System.currentTimeMillis();

  public void startFrame() {
    frameStartTime = System.nanoTime();
    if (lastFrameStartTime != 0) {
      lastFrameTime = (frameStartTime - lastFrameStartTime) / 1_000_000.0;
    }
    lastFrameStartTime = frameStartTime;
  }

  public void endFrame(int chunksProcessed, int chunksDrawn, int frustumCulled,
      int occlusionCulled) {
    long frameEndTime = System.nanoTime();
    double frameTime;
    if (lastFrameTime > 0.0) {
      frameTime = lastFrameTime;
    } else {
      frameTime = (frameEndTime - frameStartTime) / 1_000_000.0;
    }
    frameCount++;
    totalChunksProcessed += chunksProcessed;
    totalChunksDrawn += chunksDrawn;
    totalFrustumCulled += frustumCulled;
    totalOcclusionCulled += occlusionCulled;
    avgFrameTime = avgFrameTime * 0.95 + frameTime * 0.05;
    currentFPS = 1000.0 / Math.max(avgFrameTime, 0.1);
    long currentTime = System.currentTimeMillis();
    long interval = MetalRenderConfig.isDeepDebugActive() ? DEBUG_LOG_INTERVAL
        : NORMAL_LOG_INTERVAL;
    if (currentTime - lastLogTime >= interval) {
      logPerformanceStats();
      lastLogTime = currentTime;
    }
  }

  private void logPerformanceStats() {
    if (!MetalRenderConfig.isDeepDebugActive()) {
      MetalLogger.info("[PERF] FPS: %.1f | FrameTime: %.2fms", currentFPS,
          avgFrameTime);
      resetCounters();
      return;
    }
    double cullingEfficiency = totalChunksProcessed > 0
        ? (double) (totalFrustumCulled + totalOcclusionCulled) /
            totalChunksProcessed * 100
        : 0;
    MetalLogger.info("[PERF] FPS: %.1f | FrameTime: %.2fms | Chunks: P:%d "
        + "D:%d | Culled: F:%d O:%d (%.1f%%)",
        currentFPS, avgFrameTime, totalChunksProcessed,
        totalChunksDrawn, totalFrustumCulled, totalOcclusionCulled,
        cullingEfficiency);
    try {
      MetalLogger.info("[PERF][DQ] Scale=%.2f",
          MetalRenderConfig.resolutionScale());
    } catch (Exception ignored) {
    }
    Runtime rt = Runtime.getRuntime();
    long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    long maxMb = rt.maxMemory() / (1024 * 1024);
    MetalLogger.info("[PERF][MEM] Heap=%d/%dMB Frames=%d", usedMb, maxMb,
        frameCount);
    resetCounters();
  }

  private void resetCounters() {
    totalChunksProcessed = 0;
    totalChunksDrawn = 0;
    totalFrustumCulled = 0;
    totalOcclusionCulled = 0;
  }

  public double getCurrentFPS() {
    return currentFPS;
  }

  public double getAvgFrameTime() {
    return avgFrameTime;
  }

  public double getLastFrameTime() {
    return lastFrameTime > 0.0 ? lastFrameTime : avgFrameTime;
  }

  public long getFrameCount() {
    return frameCount;
  }
}