package com.pebbles_boon.metalrender.util;

import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.backend.metal.MetalBackendStats;

public final class PerformanceLogger {
    private static final long LOG_INTERVAL = 5000;
    private long frameCount;
    private long frameStartTime;
    private long lastFrameStartTime;
    private double lastFrameTime;
    private double avgFrameTime;
    private double currentFPS;
    private long lastLogTime = System.currentTimeMillis();

    public void startFrame() {
        if (!MetalRenderConfig.isDeepDebugActive()) return;
        frameStartTime = System.nanoTime();
        if (lastFrameStartTime != 0) {
            lastFrameTime = (frameStartTime - lastFrameStartTime) / 1_000_000.0;
        }
        lastFrameStartTime = frameStartTime;
    }

    public void endFrame() {
        if (!MetalRenderConfig.isDeepDebugActive()) return;
        long frameEndTime = System.nanoTime();
        double frameTime = lastFrameTime > 0.0
                ? lastFrameTime
                : (frameEndTime - frameStartTime) / 1_000_000.0;
        frameCount++;
        avgFrameTime = avgFrameTime * 0.95 + frameTime * 0.05;
        currentFPS = 1000.0 / Math.max(avgFrameTime, 0.1);

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastLogTime >= LOG_INTERVAL) {
            logPerformanceStats();
            lastLogTime = currentTime;
        }
    }

    private void logPerformanceStats() {
        if (MetalRenderClient.isEnabled()) {
            String presentation = MetalBackendStats.presentationReport();
            if (presentation != null) {
                MetalLogger.info("[presentation] %s", presentation);
            }
        }
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        MetalLogger.info("[perf] fps=%.1f frame_interval_ema=%.2fms scale=%.2f heap=%d/%dMB frames=%d",
                currentFPS, avgFrameTime, MetalRenderConfig.resolutionScale(), usedMb, maxMb, frameCount);
    }
}
