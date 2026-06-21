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
    int processors = Runtime.getRuntime().availableProcessors();
    int maxThreads = Math.min(processors + 2, 32);
    double total = ewmaMeshMs + ewmaUploadMs;
    if (total < 2.1) return Math.min(Math.max(2, processors / 4), maxThreads);
    if (total < 4.1) return Math.min(Math.max(4, processors / 2), maxThreads);
    if (total < 8.1) return Math.min(Math.max(6, processors * 2 / 3), maxThreads);
    return Math.min(Math.max(8, processors), maxThreads);
  }

  public int recommendedInFlight() {
    if (ewmaGpuMs < 4.1) return 192;
    if (ewmaGpuMs < 8.1) return 128;
    return 96;
  }

  public boolean shouldThrottle() {
    return ewmaGpuMs > 16.1;
  }
}
