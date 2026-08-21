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

  public int recommendedInFlight() {
    if (ewmaGpuMs < 4.1) return 384;
    if (ewmaGpuMs < 8.1) return 256;
    return 192;
  }

  public int recommendedInFlightFor(int queueId) {
    if (queueId == 0) {
      if (ewmaGpuMs < 4.1) return 128;
      if (ewmaGpuMs < 8.1) return 96;
      return 64;
    }
    int budget = recommendedInFlight();
    return Math.max(128, budget - 64);
  }

  public double getEwmaMeshMs() {
    return ewmaMeshMs;
  }
}
