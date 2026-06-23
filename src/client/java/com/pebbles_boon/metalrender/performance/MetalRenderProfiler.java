package com.pebbles_boon.metalrender.performance;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class MetalRenderProfiler {
  private static final MetalRenderProfiler INSTANCE = new MetalRenderProfiler();
  private static final int HISTORY_SIZE = 120;
  private static final double HANG_THRESHOLD_MS = 100.0;
  private static final long LOG_INTERVAL_MS = 5000;

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

  private final AtomicLong meshTimeAccNs = new AtomicLong(0);
  private final AtomicLong uploadTimeAccNs = new AtomicLong(0);
  private final AtomicLong cullTimeAccNs = new AtomicLong(0);
  private final AtomicLong scanTimeAccNs = new AtomicLong(0);
  private final AtomicLong textureTimeAccNs = new AtomicLong(0);
  private final AtomicLong entityTimeAccNs = new AtomicLong(0);
  private final AtomicLong particleTimeAccNs = new AtomicLong(0);
  private final AtomicLong gpuTimeAccNs = new AtomicLong(0);

  private final AtomicInteger meshesBuiltAcc = new AtomicInteger(0);
  private final AtomicInteger uploadsDoneAcc = new AtomicInteger(0);
  private final AtomicInteger chunksScannedAcc = new AtomicInteger(0);
  private final AtomicInteger chunksDrawnAcc = new AtomicInteger(0);

  private final double[] meshTimes = new double[HISTORY_SIZE];
  private final double[] uploadTimes = new double[HISTORY_SIZE];
  private final double[] cullTimes = new double[HISTORY_SIZE];
  private final double[] scanTimes = new double[HISTORY_SIZE];
  private final double[] textureTimes = new double[HISTORY_SIZE];
  private final double[] entityTimes = new double[HISTORY_SIZE];
  private final double[] particleTimes = new double[HISTORY_SIZE];
  private final double[] gpuTimes = new double[HISTORY_SIZE];

  private long lastLogTimeMs;

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

  public void recordMeshingTime(long nanos) {
    if (nanos > 0)
      meshTimeAccNs.addAndGet(nanos);
  }

  public void recordUploadTime(long nanos) {
    if (nanos > 0)
      uploadTimeAccNs.addAndGet(nanos);
  }

  public void recordCullTime(long nanos) {
    if (nanos > 0)
      cullTimeAccNs.addAndGet(nanos);
  }

  public void recordScanTime(long nanos) {
    if (nanos > 0)
      scanTimeAccNs.addAndGet(nanos);
  }

  public void recordTextureTime(long nanos) {
    if (nanos > 0)
      textureTimeAccNs.addAndGet(nanos);
  }

  public void recordEntityTime(long nanos) {
    if (nanos > 0)
      entityTimeAccNs.addAndGet(nanos);
  }

  public void recordParticleTime(long nanos) {
    if (nanos > 0)
      particleTimeAccNs.addAndGet(nanos);
  }

  public void recordGpuTime(long nanos) {
    if (nanos > 0)
      gpuTimeAccNs.addAndGet(nanos);
  }

  public void incrementMeshesBuilt(int count) {
    if (count > 0)
      meshesBuiltAcc.addAndGet(count);
  }

  public void incrementUploadsDone(int count) {
    if (count > 0)
      uploadsDoneAcc.addAndGet(count);
  }

  public void incrementChunksScanned(int count) {
    if (count > 0)
      chunksScannedAcc.addAndGet(count);
  }

  public void incrementChunksDrawn(int count) {
    if (count > 0)
      chunksDrawnAcc.addAndGet(count);
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
    double meshMs = meshTimeAccNs.getAndSet(0) / 1_000_000.0;
    double uploadMs = uploadTimeAccNs.getAndSet(0) / 1_000_000.0;
    double cullMs = cullTimeAccNs.getAndSet(0) / 1_000_000.0;
    double scanMs = scanTimeAccNs.getAndSet(0) / 1_000_000.0;
    double textureMs = textureTimeAccNs.getAndSet(0) / 1_000_000.0;
    double entityMs = entityTimeAccNs.getAndSet(0) / 1_000_000.0;
    double particleMs = particleTimeAccNs.getAndSet(0) / 1_000_000.0;
    double gpuMs = gpuTimeAccNs.getAndSet(0) / 1_000_000.0;
    if (gpuMs <= 0.0) {
      try {
        gpuMs = NativeBridge.nGetGpuFrameTimeMs();
      } catch (Throwable ignored) {
      }
    }

    int meshesBuilt = meshesBuiltAcc.getAndSet(0);
    int uploadsDone = uploadsDoneAcc.getAndSet(0);
    int chunksScanned = chunksScannedAcc.getAndSet(0);
    int chunksDrawn = chunksDrawnAcc.getAndSet(0);

    int idx = writeIndex % HISTORY_SIZE;
    frameTimes[idx] = frameMs;
    cpuTimes[idx] = cpuMs;
    renderTimes[idx] = renderMs;
    meshTimes[idx] = meshMs;
    uploadTimes[idx] = uploadMs;
    cullTimes[idx] = cullMs;
    scanTimes[idx] = scanMs;
    textureTimes[idx] = textureMs;
    entityTimes[idx] = entityMs;
    particleTimes[idx] = particleMs;
    gpuTimes[idx] = gpuMs;
    writeIndex++;
    if (sampleCount < HISTORY_SIZE) {
      sampleCount++;
    }

    if (visible) {
      snapshot = buildSnapshot(frameMs, cpuMs, renderMs,
          meshMs, uploadMs, cullMs, scanMs, textureMs, entityMs, particleMs,
          meshesBuilt, uploadsDone, chunksScanned, chunksDrawn);
    }

    long nowMs = System.currentTimeMillis();
    if (nowMs - lastLogTimeMs >= LOG_INTERVAL_MS) {
      lastLogTimeMs = nowMs;
      logPeriodic(frameMs, cpuMs, renderMs,
          meshMs, uploadMs, cullMs, scanMs, textureMs, entityMs, particleMs,
          meshesBuilt, uploadsDone, chunksScanned, chunksDrawn);
    }
  }

  private void logPeriodic(double frameMs, double cpuMs, double renderMs,
      double meshMs, double uploadMs, double cullMs, double scanMs,
      double textureMs, double entityMs, double particleMs,
      int meshesBuilt, int uploadsDone, int chunksScanned, int chunksDrawn) {
    double totalTracked = meshMs + uploadMs + cullMs + scanMs + textureMs + entityMs + particleMs;
    double otherMs = Math.max(0.0, frameMs - totalTracked);
    double totalBase = Math.max(frameMs, totalTracked);

    StringBuilder sb = new StringBuilder();
    sb.append("[PROFILER] ");
    sb.append(String.format(Locale.ROOT, "Frame=%.2fms CPU=%.2fms Render=%.2fms | ", frameMs, cpuMs, renderMs));
    sb.append(String.format(Locale.ROOT, "Mesh=%.2fms(%.1f%%) ", meshMs, pct(meshMs, totalBase)));
    sb.append(String.format(Locale.ROOT, "Upload=%.2fms(%.1f%%) ", uploadMs, pct(uploadMs, totalBase)));
    sb.append(String.format(Locale.ROOT, "Cull=%.2fms(%.1f%%) ", cullMs, pct(cullMs, totalBase)));
    sb.append(String.format(Locale.ROOT, "Scan=%.2fms(%.1f%%) ", scanMs, pct(scanMs, totalBase)));
    sb.append(String.format(Locale.ROOT, "Tex=%.2fms(%.1f%%) ", textureMs, pct(textureMs, totalBase)));
    sb.append(String.format(Locale.ROOT, "Entity=%.2fms(%.1f%%) ", entityMs, pct(entityMs, totalBase)));
    sb.append(String.format(Locale.ROOT, "Particle=%.2fms(%.1f%%) ", particleMs, pct(particleMs, totalBase)));
    sb.append(String.format(Locale.ROOT, "Other=%.2fms(%.1f%%) | ", otherMs, pct(otherMs, totalBase)));
    sb.append(String.format(Locale.ROOT, "MeshesBuilt=%d Uploads=%d Scanned=%d Drawn=%d",
        meshesBuilt, uploadsDone, chunksScanned, chunksDrawn));
    MetalLogger.info(sb.toString());
  }

  private static double pct(double part, double total) {
    return total > 0.0 ? (part / total) * 100.0 : 0.0;
  }

  private ProfileSnapshot buildSnapshot(double currentFrameMs, double currentCpuMs, double currentRenderMs,
      double currentMeshMs, double currentUploadMs, double currentCullMs,
      double currentScanMs, double currentTextureMs,
      double currentEntityMs, double currentParticleMs,
      int meshesBuilt, int uploadsDone, int chunksScanned, int chunksDrawn) {
    ProfileSnapshot s = new ProfileSnapshot();
    s.totalFrames = totalFrames;
    s.hangCount = hangCount;

    if (sampleCount > 0) {
      double frameSum = 0.0, frameMin = Double.MAX_VALUE, frameMax = 0.0;
      double cpuSum = 0.0, cpuMin = Double.MAX_VALUE, cpuMax = 0.0;
      double renderSum = 0.0, renderMin = Double.MAX_VALUE, renderMax = 0.0;
      double meshSum = 0.0, meshMin = Double.MAX_VALUE, meshMax = 0.0;
      double uploadSum = 0.0, uploadMin = Double.MAX_VALUE, uploadMax = 0.0;
      double cullSum = 0.0, cullMin = Double.MAX_VALUE, cullMax = 0.0;
      double scanSum = 0.0, scanMin = Double.MAX_VALUE, scanMax = 0.0;
      double textureSum = 0.0, textureMin = Double.MAX_VALUE, textureMax = 0.0;
      double entitySum = 0.0, entityMin = Double.MAX_VALUE, entityMax = 0.0;
      double particleSum = 0.0, particleMin = Double.MAX_VALUE, particleMax = 0.0;
      for (int i = 0; i < sampleCount; i++) {
        double ft = frameTimes[i];
        double ct = cpuTimes[i];
        double rt = renderTimes[i];
        double mt = meshTimes[i];
        double ut = uploadTimes[i];
        double cut = cullTimes[i];
        double st = scanTimes[i];
        double tt = textureTimes[i];
        double et = entityTimes[i];
        double pt = particleTimes[i];
        frameSum += ft;
        cpuSum += ct;
        renderSum += rt;
        meshSum += mt;
        uploadSum += ut;
        cullSum += cut;
        scanSum += st;
        textureSum += tt;
        entitySum += et;
        particleSum += pt;
        if (ft < frameMin)
          frameMin = ft;
        if (ft > frameMax)
          frameMax = ft;
        if (ct < cpuMin)
          cpuMin = ct;
        if (ct > cpuMax)
          cpuMax = ct;
        if (rt < renderMin)
          renderMin = rt;
        if (rt > renderMax)
          renderMax = rt;
        if (mt < meshMin)
          meshMin = mt;
        if (mt > meshMax)
          meshMax = mt;
        if (ut < uploadMin)
          uploadMin = ut;
        if (ut > uploadMax)
          uploadMax = ut;
        if (cut < cullMin)
          cullMin = cut;
        if (cut > cullMax)
          cullMax = cut;
        if (st < scanMin)
          scanMin = st;
        if (st > scanMax)
          scanMax = st;
        if (tt < textureMin)
          textureMin = tt;
        if (tt > textureMax)
          textureMax = tt;
        if (et < entityMin)
          entityMin = et;
        if (et > entityMax)
          entityMax = et;
        if (pt < particleMin)
          particleMin = pt;
        if (pt > particleMax)
          particleMax = pt;
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
      s.avgMeshMs = meshSum / sampleCount;
      s.minMeshMs = meshMin;
      s.maxMeshMs = meshMax;
      s.avgUploadMs = uploadSum / sampleCount;
      s.minUploadMs = uploadMin;
      s.maxUploadMs = uploadMax;
      s.avgCullMs = cullSum / sampleCount;
      s.minCullMs = cullMin;
      s.maxCullMs = cullMax;
      s.avgScanMs = scanSum / sampleCount;
      s.minScanMs = scanMin;
      s.maxScanMs = scanMax;
      s.avgTextureMs = textureSum / sampleCount;
      s.minTextureMs = textureMin;
      s.maxTextureMs = textureMax;
      s.avgEntityMs = entitySum / sampleCount;
      s.minEntityMs = entityMin;
      s.maxEntityMs = entityMax;
      s.avgParticleMs = particleSum / sampleCount;
      s.minParticleMs = particleMin;
      s.maxParticleMs = particleMax;
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
      s.avgMeshMs = currentMeshMs;
      s.minMeshMs = currentMeshMs;
      s.maxMeshMs = currentMeshMs;
      s.avgUploadMs = currentUploadMs;
      s.minUploadMs = currentUploadMs;
      s.maxUploadMs = currentUploadMs;
      s.avgCullMs = currentCullMs;
      s.minCullMs = currentCullMs;
      s.maxCullMs = currentCullMs;
      s.avgScanMs = currentScanMs;
      s.minScanMs = currentScanMs;
      s.maxScanMs = currentScanMs;
      s.avgTextureMs = currentTextureMs;
      s.minTextureMs = currentTextureMs;
      s.maxTextureMs = currentTextureMs;
      s.avgEntityMs = currentEntityMs;
      s.minEntityMs = currentEntityMs;
      s.maxEntityMs = currentEntityMs;
      s.avgParticleMs = currentParticleMs;
      s.minParticleMs = currentParticleMs;
      s.maxParticleMs = currentParticleMs;
      s.fps = currentFrameMs > 0.0 ? 1000.0 / currentFrameMs : 0.0;
    }

    s.currentFrameMs = currentFrameMs;
    s.currentCpuMs = currentCpuMs;
    s.currentRenderMs = currentRenderMs;
    s.currentMeshMs = currentMeshMs;
    s.currentUploadMs = currentUploadMs;
    s.currentCullMs = currentCullMs;
    s.currentScanMs = currentScanMs;
    s.currentTextureMs = currentTextureMs;
    s.currentEntityMs = currentEntityMs;
    s.currentParticleMs = currentParticleMs;

    s.meshesBuilt = meshesBuilt;
    s.uploadsDone = uploadsDone;
    s.chunksScanned = chunksScanned;
    s.chunksDrawn = chunksDrawn;

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

    List<TimeEntry> entries = new ArrayList<>();
    entries.add(new TimeEntry("Mesh", s.currentMeshMs));
    entries.add(new TimeEntry("Upload", s.currentUploadMs));
    entries.add(new TimeEntry("Cull", s.currentCullMs));
    entries.add(new TimeEntry("Scan", s.currentScanMs));
    entries.add(new TimeEntry("Tex", s.currentTextureMs));
    entries.add(new TimeEntry("Entity", s.currentEntityMs));
    entries.add(new TimeEntry("Particle", s.currentParticleMs));
    double tracked = s.currentMeshMs + s.currentUploadMs + s.currentCullMs + s.currentScanMs
        + s.currentTextureMs + s.currentEntityMs + s.currentParticleMs + s.currentGpuMs;
    double other = Math.max(0.0, s.currentFrameMs - tracked);
    entries.add(new TimeEntry("GPU", s.currentGpuMs));
    entries.add(new TimeEntry("Other", other));
    Collections.sort(entries);
    s.breakdown = entries;
    s.breakdownTotalMs = Math.max(s.currentFrameMs, tracked);

    return s;
  }

  public ProfileSnapshot getSnapshot() {
    return snapshot;
  }

  private static final class TimeEntry implements Comparable<TimeEntry> {
    final String name;
    final double ms;

    TimeEntry(String name, double ms) {
      this.name = name;
      this.ms = ms;
    }

    @Override
    public int compareTo(TimeEntry o) {
      return Double.compare(o.ms, this.ms);
    }
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

    public double currentMeshMs;
    public double avgMeshMs;
    public double minMeshMs;
    public double maxMeshMs;
    public double currentUploadMs;
    public double avgUploadMs;
    public double minUploadMs;
    public double maxUploadMs;
    public double currentCullMs;
    public double avgCullMs;
    public double minCullMs;
    public double maxCullMs;
    public double currentScanMs;
    public double avgScanMs;
    public double minScanMs;
    public double maxScanMs;
    public double currentTextureMs;
    public double avgTextureMs;
    public double minTextureMs;
    public double maxTextureMs;
    public double currentEntityMs;
    public double avgEntityMs;
    public double minEntityMs;
    public double maxEntityMs;
    public double currentParticleMs;
    public double avgParticleMs;
    public double minParticleMs;
    public double maxParticleMs;
    public double currentGpuMs;
    public double avgGpuMs;
    public double minGpuMs;
    public double maxGpuMs;

    public int meshesBuilt;
    public int uploadsDone;
    public int chunksScanned;
    public int chunksDrawn;

    public int meshCount;
    public int vertexCount;
    public int pendingCount;
    public int drawnChunkCount;
    public long usedMemoryMb;
    public long maxMemoryMb;

    public List<TimeEntry> breakdown;
    public double breakdownTotalMs;

    public String[] toLines() {
      List<String> lines = new ArrayList<>();
      lines.add(String.format(Locale.ROOT, "FPS %.1f | Frame %.2fms (%.2f/%.2f)", fps, currentFrameMs, minFrameMs,
          maxFrameMs));
      lines.add(String.format(Locale.ROOT, "CPU %.2fms (%.2f/%.2f/%.2f) | Render %.2fms", currentCpuMs, minCpuMs,
          avgCpuMs, maxCpuMs, currentRenderMs));
      if (breakdown != null && !breakdown.isEmpty()) {
        double total = breakdownTotalMs > 0.0 ? breakdownTotalMs : currentFrameMs;
        for (int i = 0; i < breakdown.size(); i++) {
          TimeEntry e = breakdown.get(i);
          double pct = total > 0.0 ? (e.ms / total) * 100.0 : 0.0;
          lines.add(String.format(Locale.ROOT, "[%d] %s %.2fms %.1f%%", i + 1, e.name, e.ms, pct));
        }
      }
      lines.add(String.format(Locale.ROOT, "Meshes %d | Verts %d | Pending %d", meshCount, vertexCount, pendingCount));
      lines.add(String.format(Locale.ROOT, "Drawn %d | Built %d | Uploads %d | Hangs %d", drawnChunkCount, meshesBuilt,
          uploadsDone, hangCount));
      lines.add(String.format(Locale.ROOT, "Scanned %d | Mem %d/%dMB", chunksScanned, usedMemoryMb, maxMemoryMb));
      return lines.toArray(new String[0]);
    }
  }
}
