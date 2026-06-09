package com.pebbles_boon.metalrender.performance;

import com.pebbles_boon.metalrender.util.PerformanceLogger;

public final class PerformanceController {
  private static final PerformanceLogger PERF_LOGGER = new PerformanceLogger();
  private static int chunksProcessed;
  private static int chunksDrawn;
  private static int frustumCulled;
  private static int occlusionCulled;
  private static boolean frameActive;

  private PerformanceController() {
  }

  public static void startFrame() {
    frameActive = true;
    PERF_LOGGER.startFrame();
  }

  public static void accumulateChunkStats(int processed, int drawn, int frustum,
      int occluded) {
    chunksProcessed += processed;
    chunksDrawn += drawn;
    frustumCulled += frustum;
    occlusionCulled += occluded;
  }

  public static void endFrame() {
    if (!frameActive)
      return;
    PERF_LOGGER.endFrame(chunksProcessed, chunksDrawn, frustumCulled,
        occlusionCulled);
    chunksProcessed = 0;
    chunksDrawn = 0;
    frustumCulled = 0;
    occlusionCulled = 0;
    frameActive = false;
  }

  public static PerformanceLogger getLogger() {
    return PERF_LOGGER;
  }
}
