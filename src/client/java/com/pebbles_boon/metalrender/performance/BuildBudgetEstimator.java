package com.pebbles_boon.metalrender.performance;

public class BuildBudgetEstimator {
  private double ewmaMeshMs = 4.0;
  private double ewmaUploadMs = 0.5;
  private double ewmaGpuMs = 8.0;
  private double alpha = 0.1;

  public void record(double meshMs, double uploadMs, double gpuMs) {
    ewmaMeshMs = ewmaMeshMs * (1.0 - alpha) + meshMs * alpha;
    ewmaUploadMs = ewmaUploadMs * (1.0 - alpha) + uploadMs * alpha;
    ewmaGpuMs = ewmaGpuMs * (1.0 - alpha) + gpuMs * alpha;
  }

  public int recommendedThreadCount() {
    return recommendedThreadCount(0);
  }

  public int recommendedThreadCount(int pending) {
    int processors = Runtime.getRuntime().availableProcessors();
    int maxThreads = Math.min(processors + 2, 32);
    int backlogThreads;
    if (pending >= 8192) backlogThreads = Math.min(24, processors);
    else if (pending >= 4096) backlogThreads = Math.min(20, processors);
    else if (pending >= 2048) backlogThreads = Math.min(16, processors);
    else if (pending >= 1024) backlogThreads = Math.min(12, processors);
    else if (pending >= 512) backlogThreads = Math.min(10, processors);
    else if (pending >= 256) backlogThreads = Math.min(8, processors);
    else if (pending >= 64) backlogThreads = Math.min(6, processors);
    else backlogThreads = Math.max(2, processors / 4);
    double total = ewmaMeshMs + ewmaUploadMs;
    int pressureThreads;
    if (total < 2.1) pressureThreads = Math.max(2, processors / 4);
    else if (total < 4.1) pressureThreads = Math.max(4, processors / 2);
    else if (total < 8.1) pressureThreads = Math.max(6, processors * 2 / 3);
    else pressureThreads = Math.max(8, processors);
    return Math.min(Math.max(backlogThreads, pressureThreads), maxThreads);
  }

  public int recommendedInFlight() {
    if (ewmaGpuMs < 4.1) return 192;
    if (ewmaGpuMs < 8.1) return 128;
    return 96;
  }

  /**
   * Per-queue in-flight cap. {@code queueId == 0} is the Immediate pool (close-camera
   * interactive / high-priority rebuilds, low cap so they drain fast and we feed back
   * pressure early). {@code queueId == 1} is the Background pool (everything else,
   * generous cap from the global total).
   */
  public int recommendedInFlightFor(int queueId) {
    if (queueId == 0) {
      if (ewmaGpuMs < 4.1) return 64;
      if (ewmaGpuMs < 8.1) return 48;
      return 32;
    }
    int budget = recommendedInFlight();
    return Math.max(64, budget - 32);
  }

  public boolean shouldThrottle() {
    return ewmaGpuMs > 16.1;
  }

  /** Current EWMA of mesh build time (ms). Used by texture-sync back-off heuristics. */
  public double getEwmaMeshMs() {
    return ewmaMeshMs;
  }
}
