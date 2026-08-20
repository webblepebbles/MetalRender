package com.pebbles_boon.metalrender.util;

import com.pebbles_boon.metalrender.config.MetalRenderConfig;

public final class PerformanceLogger {
  private static final long NORMAL_LOG_INTERVAL = 5000;
  private static final long DEBUG_LOG_INTERVAL = 1000;
  private long frameCount;
  private long frameStartTime;
  private long lastFrameStartTime;
  private double lastFrameTime;
  private double avgFrameTime;
  private double currentFPS;
  private long lastLogTime = System.currentTimeMillis();

  public void startFrame() {
    frameStartTime = System.nanoTime();
    if (lastFrameStartTime != 0) {
      lastFrameTime = (frameStartTime - lastFrameStartTime) / 1_000_000.0;
    }
    lastFrameStartTime = frameStartTime;
  }

  public void endFrame() {
    long frameEndTime = System.nanoTime();
    double frameTime = lastFrameTime > 0.0
        ? lastFrameTime
        : (frameEndTime - frameStartTime) / 1_000_000.0;
    frameCount++;
    avgFrameTime = avgFrameTime * 0.95 + frameTime * 0.05;
    currentFPS = 1000.0 / Math.max(avgFrameTime, 0.1);

    long currentTime = System.currentTimeMillis();
    long interval = MetalRenderConfig.isDeepDebugActive()
        ? DEBUG_LOG_INTERVAL
        : NORMAL_LOG_INTERVAL;
    if (currentTime - lastLogTime >= interval) {
      logPerformanceStats();
      lastLogTime = currentTime;
    }
  }

  private void logPerformanceStats() {
    if (!MetalRenderConfig.isDeepDebugActive()) {
      MetalLogger.info("[perf] fps %.1f ft %.2fms", currentFPS,
          avgFrameTime);
      return;
    }
    MetalLogger.info("[perf] fps %.1f ft %.2fms", currentFPS, avgFrameTime);
    try {
      MetalLogger.info("[perf][dq] sc=%.2f",
          MetalRenderConfig.resolutionScale());
    } catch (Exception ignored) {
    }
    Runtime rt = Runtime.getRuntime();
    long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    long maxMb = rt.maxMemory() / (1024 * 1024);
    MetalLogger.info("[perf][mem] h=%d/%dmb f=%d", usedMb, maxMb,
        frameCount);
  }
}
