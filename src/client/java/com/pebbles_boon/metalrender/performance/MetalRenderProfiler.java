package com.pebbles_boon.metalrender.performance;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import java.util.Locale;

public final class MetalRenderProfiler {
  private static final MetalRenderProfiler INSTANCE = new MetalRenderProfiler();
  private static final int HISTORY_SIZE = 120;
  private static final double HANG_THRESHOLD_MS = 100.0;

  private boolean visible;
  private long frameStartNs;
  private long renderStartNs;
  private long frameEndNs;
  private long renderEndNs;
  private final double[] frameTimes = new double[HISTORY_SIZE];
  private final double[] cpuTimes = new double[HISTORY_SIZE];
  private final double[] renderTimes = new double[HISTORY_SIZE];
  private int writeIndex;
  private int sampleCount;
  private long totalFrames;
  private long hangCount;
  private volatile ProfileSnapshot snapshot = new ProfileSnapshot();

  private MetalRenderProfiler() {
  }

  public static MetalRenderProfiler getInstance() {
    return INSTANCE;
  }

  public void toggleVisible() {
    visible = !visible;
  }

  public boolean isVisible() {
    return visible;
  }

  public void startFrame() {
    frameStartNs = System.nanoTime();
  }

  public void startRender() {
    renderStartNs = System.nanoTime();
    renderEndNs = 0;
  }

  public void endRender() {
    renderEndNs = System.nanoTime();
  }

  public void endFrame() {
    frameEndNs = System.nanoTime();
    totalFrames++;

    double frameMs = (frameEndNs - frameStartNs) / 1_000_000.0;
    double renderMs = 0.0;
    if (renderEndNs > renderStartNs) {
      renderMs = (renderEndNs - renderStartNs) / 1_000_000.0;
    }
    double cpuMs = Math.max(0.0, frameMs - renderMs);

    if (frameMs > HANG_THRESHOLD_MS) {
      hangCount++;
    }

    int idx = writeIndex % HISTORY_SIZE;
    frameTimes[idx] = frameMs;
    cpuTimes[idx] = cpuMs;
    renderTimes[idx] = renderMs;
    writeIndex++;
    if (sampleCount < HISTORY_SIZE) {
      sampleCount++;
    }

    if (visible) {
      snapshot = buildSnapshot(frameMs, cpuMs, renderMs);
    }
  }

  private ProfileSnapshot buildSnapshot(double currentFrameMs, double currentCpuMs, double currentRenderMs) {
    ProfileSnapshot s = new ProfileSnapshot();
    s.totalFrames = totalFrames;
    s.hangCount = hangCount;

    if (sampleCount > 0) {
      double frameSum = 0.0, frameMin = Double.MAX_VALUE, frameMax = 0.0;
      double cpuSum = 0.0, cpuMin = Double.MAX_VALUE, cpuMax = 0.0;
      double renderSum = 0.0, renderMin = Double.MAX_VALUE, renderMax = 0.0;
      for (int i = 0; i < sampleCount; i++) {
        double ft = frameTimes[i];
        double ct = cpuTimes[i];
        double rt = renderTimes[i];
        frameSum += ft;
        cpuSum += ct;
        renderSum += rt;
        if (ft < frameMin) frameMin = ft;
        if (ft > frameMax) frameMax = ft;
        if (ct < cpuMin) cpuMin = ct;
        if (ct > cpuMax) cpuMax = ct;
        if (rt < renderMin) renderMin = rt;
        if (rt > renderMax) renderMax = rt;
      }
      s.avgFrameMs = frameSum / sampleCount;
      s.minFrameMs = frameMin;
      s.maxFrameMs = frameMax;
      s.avgCpuMs = cpuSum / sampleCount;
      s.minCpuMs = cpuMin;
      s.maxCpuMs = cpuMax;
      s.avgRenderMs = renderSum / sampleCount;
      s.minRenderMs = renderMin;
      s.maxRenderMs = renderMax;
      s.fps = s.avgFrameMs > 0.0 ? 1000.0 / s.avgFrameMs : 0.0;
    } else {
      s.avgFrameMs = currentFrameMs;
      s.minFrameMs = currentFrameMs;
      s.maxFrameMs = currentFrameMs;
      s.avgCpuMs = currentCpuMs;
      s.minCpuMs = currentCpuMs;
      s.maxCpuMs = currentCpuMs;
      s.avgRenderMs = currentRenderMs;
      s.minRenderMs = currentRenderMs;
      s.maxRenderMs = currentRenderMs;
      s.fps = currentFrameMs > 0.0 ? 1000.0 / currentFrameMs : 0.0;
    }

    s.currentFrameMs = currentFrameMs;
    s.currentCpuMs = currentCpuMs;
    s.currentRenderMs = currentRenderMs;

    MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
    if (wr != null) {
      s.meshCount = wr.getChunkMesher().getMeshCount();
      s.vertexCount = wr.getChunkMesher().getTotalVertexCount();
      s.pendingCount = wr.getChunkMesher().getPendingCount();
      s.drawnChunkCount = wr.getLastDrawnChunkCount();
    }

    Runtime runtime = Runtime.getRuntime();
    s.usedMemoryMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    s.maxMemoryMb = runtime.maxMemory() / (1024 * 1024);

    return s;
  }

  public ProfileSnapshot getSnapshot() {
    return snapshot;
  }

  public static final class ProfileSnapshot {
    public long totalFrames;
    public long hangCount;
    public double fps;
    public double currentFrameMs;
    public double avgFrameMs;
    public double minFrameMs;
    public double maxFrameMs;
    public double currentCpuMs;
    public double avgCpuMs;
    public double minCpuMs;
    public double maxCpuMs;
    public double currentRenderMs;
    public double avgRenderMs;
    public double minRenderMs;
    public double maxRenderMs;
    public int meshCount;
    public int vertexCount;
    public int pendingCount;
    public int drawnChunkCount;
    public long usedMemoryMb;
    public long maxMemoryMb;

    public String[] toLines() {
      return new String[] {
        String.format(Locale.ROOT, "FPS %.1f | Frame %.2fms (%.2f / %.2f)", fps, currentFrameMs, minFrameMs, maxFrameMs),
        String.format(Locale.ROOT, "CPU %.2fms (%.2f / %.2f / %.2f)", currentCpuMs, minCpuMs, avgCpuMs, maxCpuMs),
        String.format(Locale.ROOT, "GPU %.2fms (%.2f / %.2f / %.2f)", currentRenderMs, minRenderMs, avgRenderMs, maxRenderMs),
        String.format(Locale.ROOT, "Meshes %d | Verts %d | Pending %d", meshCount, vertexCount, pendingCount),
        String.format(Locale.ROOT, "Drawn %d | Hangs %d | Mem %d/%dMB", drawnChunkCount, hangCount, usedMemoryMb, maxMemoryMb),
      };
    }
  }
}
