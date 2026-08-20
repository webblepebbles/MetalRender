package com.pebbles_boon.metalrender.performance;

import com.pebbles_boon.metalrender.util.PerformanceLogger;

public final class PerformanceController {
  private static final PerformanceLogger PERF_LOGGER = new PerformanceLogger();
  private static final BuildBudgetEstimator BUDGET_ESTIMATOR = new BuildBudgetEstimator();
  private static boolean frameActive;

  private PerformanceController() {
  }

  public static void startFrame() {
    frameActive = true;
    PERF_LOGGER.startFrame();
    MetalRenderProfiler.getInstance().startFrame();
  }

  public static BuildBudgetEstimator getBudgetEstimator() {
    return BUDGET_ESTIMATOR;
  }

  public static void endFrame() {
    if (!frameActive)
      return;
    PERF_LOGGER.endFrame();
    MetalRenderProfiler profiler = MetalRenderProfiler.getInstance();
    double meshMs = profiler.getSnapshot().currentMeshMs;
    double uploadMs = profiler.getSnapshot().currentUploadMs;
    double gpuMs = profiler.getSnapshot().currentGpuMs;
    BUDGET_ESTIMATOR.record(meshMs, uploadMs, gpuMs);
    profiler.endFrame();
    AdaptiveResolutionController.getInstance().tick(profiler.getLatestGpuMs());
    frameActive = false;
  }

}
