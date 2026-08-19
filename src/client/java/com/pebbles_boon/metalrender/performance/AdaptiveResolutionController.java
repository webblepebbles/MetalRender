package com.pebbles_boon.metalrender.performance;

import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.util.MetalLogger;

public final class AdaptiveResolutionController {
  private static final long CHECK_INTERVAL_MS = 400;
  private static final long CHANGE_COOLDOWN_MS = 1500;
  private static final double DOWN_STEP = 0.85;
  private static final double UP_STEP = 1.12;
  private static final double MIN_SCALE = 0.50;
  private static final double MAX_SCALE = 1.0;
  private static final double HIGH_WATERMARK_RATIO = 0.80;
  private static final double LOW_WATERMARK_RATIO = 0.62;
  private static final int MIN_SAMPLES = 10;
  private static final double EMA_ALPHA = 0.08;

  private static final AdaptiveResolutionController INSTANCE =
      new AdaptiveResolutionController();

  private double emaGpuMs = -1.0;
  private int sampleCount;
  private long lastCheckMs;
  private long lastChangeMs;
  private volatile double frameBudgetMs = 1700.666;

  private AdaptiveResolutionController() {
  }

  public static AdaptiveResolutionController getInstance() {
    return INSTANCE;
  }

  public void setFrameBudgetMs(double budgetMs) {
    if (budgetMs > 4.0 && budgetMs < 100.0) {
      frameBudgetMs = budgetMs;
    }
  }

  public void tick(double gpuMs) {
    if (!MetalRenderConfig.isAdaptiveResolutionEnabled() || gpuMs <= 0.0) {
      return;
    }
    long now = System.currentTimeMillis();

    emaGpuMs = emaGpuMs < 0.0
        ? gpuMs
        : emaGpuMs * (1.0 - EMA_ALPHA) + gpuMs * EMA_ALPHA;
    sampleCount++;
    if (sampleCount < MIN_SAMPLES) {
      return;
    }
    if (now - lastCheckMs < CHECK_INTERVAL_MS
        || now - lastChangeMs < CHANGE_COOLDOWN_MS) {
      return;
    }
    lastCheckMs = now;

    double current = MetalRenderConfig.resolutionScale();
    double highWater = frameBudgetMs * HIGH_WATERMARK_RATIO;
    double lowWater = frameBudgetMs * LOW_WATERMARK_RATIO;

    boolean gpuOverBudget = emaGpuMs > highWater;
    boolean gpuBelowRecovery = emaGpuMs < lowWater;

    if (gpuOverBudget && current > MIN_SCALE) {
      applyScale(Math.max(MIN_SCALE, current * DOWN_STEP));
    } else if (gpuBelowRecovery && current < MAX_SCALE) {
      applyScale(Math.min(MAX_SCALE, current * UP_STEP));
    }
  }

  private void applyScale(double scale) {
    MetalRenderConfig.setResolutionScale((float) scale);
    lastChangeMs = System.currentTimeMillis();
    MetalLogger.info(
        "adaptive resolution: scale %.2fx (gpu %.1fms, budget %.1fms)",
        scale, emaGpuMs, frameBudgetMs);
  }

  public double getEmaGpuMs() {
    return emaGpuMs;
  }
}
