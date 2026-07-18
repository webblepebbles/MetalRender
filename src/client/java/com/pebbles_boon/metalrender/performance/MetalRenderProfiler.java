package com.pebbles_boon.metalrender.performance;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
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
  private static final long SNAPSHOT_INTERVAL_MS = 500;

  private boolean visible;
  private long frameStartNs;
  private long renderStartNs;
  private long frameEndNs;
  private long renderEndNs;
  private int writeIndex;
  private int sampleCount;
  private long totalFrames;
  private long hangCount;
  private volatile ProfileSnapshot snapshot = new ProfileSnapshot();

  private final java.util.concurrent.LinkedBlockingQueue<String> csvQueue = new java.util.concurrent.LinkedBlockingQueue<>();
  private volatile boolean csvWorkerStarted;
  private final AtomicLong meshTimeAccNs = new AtomicLong(0);
  private final AtomicLong uploadTimeAccNs = new AtomicLong(0);
  private final AtomicLong cullTimeAccNs = new AtomicLong(0);
  private final AtomicLong gpuTimeAccNs = new AtomicLong(0);

  private final AtomicInteger meshesBuiltAcc = new AtomicInteger(0);
  private final AtomicInteger uploadsDoneAcc = new AtomicInteger(0);
  private final AtomicInteger chunksScannedAcc = new AtomicInteger(0);
  private final AtomicInteger chunksDrawnAcc = new AtomicInteger(0);

  private final MetricHistory frameHistory = new MetricHistory(HISTORY_SIZE);
  private final MetricHistory cpuHistory = new MetricHistory(HISTORY_SIZE);
  private final MetricHistory renderHistory = new MetricHistory(HISTORY_SIZE);
  private final MetricHistory meshHistory = new MetricHistory(HISTORY_SIZE);
  private final MetricHistory uploadHistory = new MetricHistory(HISTORY_SIZE);
  private final MetricHistory cullHistory = new MetricHistory(HISTORY_SIZE);
  private final MetricHistory gpuHistory = new MetricHistory(HISTORY_SIZE);

  private long lastLogTimeMs;
  private long lastCsvEmitMs;
  private long lastSnapshotUpdateMs;
  private boolean csvHeaderWritten;
  private static final String CSV_HEADER =
      "epoch_ms,loading,frame_ms,mesh_ms,upload_ms,cull_ms," +
          "meshes_built,chunks_drawn,meshes,pending,vertex_count,builder_pool,queued";

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
    double renderMs = renderEndNs > renderStartNs ? (renderEndNs - renderStartNs) / 1_000_000.0 : 0.0;
    double cpuMs = Math.max(0.0, frameMs - renderMs);

    if (frameMs > HANG_THRESHOLD_MS) {
      hangCount++;
    }

    double meshMs = meshTimeAccNs.getAndSet(0) / 1_000_000.0;
    double uploadMs = uploadTimeAccNs.getAndSet(0) / 1_000_000.0;
    double cullMs = cullTimeAccNs.getAndSet(0) / 1_000_000.0;
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
    frameHistory.record(frameMs);
    cpuHistory.record(cpuMs);
    renderHistory.record(renderMs);
    meshHistory.record(meshMs);
    uploadHistory.record(uploadMs);
    cullHistory.record(cullMs);
    gpuHistory.record(gpuMs);
    frameHistory.commit(idx, sampleCount);
    cpuHistory.commit(idx, sampleCount);
    renderHistory.commit(idx, sampleCount);
    meshHistory.commit(idx, sampleCount);
    uploadHistory.commit(idx, sampleCount);
    cullHistory.commit(idx, sampleCount);
    gpuHistory.commit(idx, sampleCount);
    writeIndex++;
    if (sampleCount < HISTORY_SIZE) {
      sampleCount++;
    }

    long nowMs = System.currentTimeMillis();

    if (visible && nowMs - lastSnapshotUpdateMs >= SNAPSHOT_INTERVAL_MS) {
      lastSnapshotUpdateMs = nowMs;
      snapshot = buildSnapshot(frameMs, cpuMs, renderMs,
          meshMs, uploadMs, cullMs, gpuMs,
          meshesBuilt, uploadsDone, chunksScanned, chunksDrawn);
    }

    if (nowMs - lastLogTimeMs >= LOG_INTERVAL_MS) {
      lastLogTimeMs = nowMs;
      logPeriodic(frameMs, cpuMs, renderMs,
          meshMs, uploadMs, cullMs, gpuMs,
          meshesBuilt, uploadsDone, chunksScanned, chunksDrawn);
    }

    MetalWorldRenderer wrForCsv = MetalRenderClient.getWorldRenderer();
    if (wrForCsv != null && wrForCsv.isLoadingMode() &&
        nowMs - lastCsvEmitMs >= 1000L) {
      lastCsvEmitMs = nowMs;
      emitCsvSample(nowMs, frameMs, meshMs, uploadMs, cullMs,
          meshesBuilt, chunksDrawn, wrForCsv);
    }
  }

  private void emitCsvSample(long tsMs, double frameMs, double meshMs,
      double uploadMs, double cullMs,
      int meshesBuilt, int chunksDrawn, MetalWorldRenderer wr) {
    ensureCsvWorker();
    CustomChunkMesherAccess access = CustomChunkMesherAccess.of(wr);
    StringBuilder sb = new StringBuilder(256);
    if (!csvHeaderWritten) {
      csvHeaderWritten = true;
      sb.append(CSV_HEADER).append('\n');
    }
    sb.append(tsMs).append(',').append(true).append(',')
        .append(fmt(frameMs)).append(',')
        .append(fmt(meshMs)).append(',')
        .append(fmt(uploadMs)).append(',')
        .append(fmt(cullMs)).append(',')
        .append(meshesBuilt).append(',')
        .append(chunksDrawn).append(',')
        .append(access.meshCount).append(',')
        .append(access.pendingCount).append(',')
        .append(access.vertexCount).append(',')
        .append(access.builderActive).append(',')
        .append(access.builderQueued).append('\n');
    csvQueue.offer(sb.toString());
  }

  private static String fmt(double ms) {
    return String.format(Locale.ROOT, "%.3f", ms);
  }

  private void ensureCsvWorker() {
    if (csvWorkerStarted) return;
    synchronized (this) {
      if (csvWorkerStarted) return;
      csvWorkerStarted = true;
      Thread t = new Thread(() -> {
        StringBuilder batch = new StringBuilder(4096);
        while (!Thread.currentThread().isInterrupted()) {
          try {
            String line = csvQueue.poll(250L, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (line != null) {
              batch.append(line);
              String drain;
              while ((drain = csvQueue.poll()) != null) {
                batch.append(drain);
              }
              try {
                java.nio.file.Path dir = Paths.get("run", "logs");
                Files.createDirectories(dir);
                java.nio.file.Path file = dir.resolve("metalrender_load.csv");
                Files.writeString(file, batch.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
              } catch (IOException ignored) {
              }
              batch.setLength(0);
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
      }, "MetalRender-CsvWorker");
      t.setDaemon(true);
      t.start();
    }
  }

  private static final class CustomChunkMesherAccess {
    final int meshCount;
    final int pendingCount;
    final int vertexCount;
    final int builderActive;
    final int builderQueued;

    private CustomChunkMesherAccess(int meshCount, int pendingCount,
        int vertexCount, int builderActive, int builderQueued) {
      this.meshCount = meshCount;
      this.pendingCount = pendingCount;
      this.vertexCount = vertexCount;
      this.builderActive = builderActive;
      this.builderQueued = builderQueued;
    }

    static CustomChunkMesherAccess of(MetalWorldRenderer wr) {
      try {
        com.pebbles_boon.metalrender.render.chunk.CustomChunkMesher m =
            wr.getChunkMesher();
        return new CustomChunkMesherAccess(m.getMeshCount(), m.getPendingCount(),
            m.getTotalVertexCount(), m.getBuilderActiveCount(),
            m.getBuilderQueueDepth());
      } catch (Throwable t) {
        return new CustomChunkMesherAccess(0, 0, 0, 0, 0);
      }
    }
  }

  private void logPeriodic(double frameMs, double cpuMs, double renderMs,
      double meshMs, double uploadMs, double cullMs, double gpuMs,
      int meshesBuilt, int uploadsDone, int chunksScanned, int chunksDrawn) {
    double totalTracked = meshMs + uploadMs + cullMs + gpuMs;
    double otherMs = Math.max(0.0, frameMs - totalTracked);
    double totalBase = Math.max(frameMs, totalTracked);

    StringBuilder sb = new StringBuilder();
    sb.append("[profiler] ");
    sb.append(String.format(Locale.ROOT, "frame=%.2fms cpu=%.2fms render=%.2fms | ", frameMs, cpuMs, renderMs));
    sb.append(String.format(Locale.ROOT, "mesh=%.2fms(%.1f%%) ", meshMs, pct(meshMs, totalBase)));
    sb.append(String.format(Locale.ROOT, "upload=%.2fms(%.1f%%) ", uploadMs, pct(uploadMs, totalBase)));
    sb.append(String.format(Locale.ROOT, "cull=%.2fms(%.1f%%) ", cullMs, pct(cullMs, totalBase)));
    sb.append(String.format(Locale.ROOT, "gpu=%.2fms(%.1f%%) ", gpuMs, pct(gpuMs, totalBase)));
    sb.append(String.format(Locale.ROOT, "other=%.2fms(%.1f%%) | ", otherMs, pct(otherMs, totalBase)));
    sb.append(String.format(Locale.ROOT, "meshes_built=%d uploads=%d scanned=%d drawn=%d",
        meshesBuilt, uploadsDone, chunksScanned, chunksDrawn));
    MetalLogger.info(sb.toString());
  }

  private static double pct(double part, double total) {
    return total > 0.0 ? (part / total) * 100.0 : 0.0;
  }

  private ProfileSnapshot buildSnapshot(double currentFrameMs, double currentCpuMs, double currentRenderMs,
      double currentMeshMs, double currentUploadMs, double currentCullMs, double currentGpuMs,
      int meshesBuilt, int uploadsDone, int chunksScanned, int chunksDrawn) {
    ProfileSnapshot s = new ProfileSnapshot();
    s.totalFrames = totalFrames;
    s.hangCount = hangCount;

    s.currentFrameMs = currentFrameMs;
    s.avgFrameMs = frameHistory.avg;
    s.minFrameMs = frameHistory.min;
    s.maxFrameMs = frameHistory.max;
    s.currentCpuMs = currentCpuMs;
    s.avgCpuMs = cpuHistory.avg;
    s.minCpuMs = cpuHistory.min;
    s.maxCpuMs = cpuHistory.max;
    s.currentRenderMs = currentRenderMs;
    s.avgRenderMs = renderHistory.avg;
    s.minRenderMs = renderHistory.min;
    s.maxRenderMs = renderHistory.max;
    s.currentMeshMs = currentMeshMs;
    s.avgMeshMs = meshHistory.avg;
    s.minMeshMs = meshHistory.min;
    s.maxMeshMs = meshHistory.max;
    s.currentUploadMs = currentUploadMs;
    s.avgUploadMs = uploadHistory.avg;
    s.minUploadMs = uploadHistory.min;
    s.maxUploadMs = uploadHistory.max;
    s.currentCullMs = currentCullMs;
    s.avgCullMs = cullHistory.avg;
    s.minCullMs = cullHistory.min;
    s.maxCullMs = cullHistory.max;
    s.currentGpuMs = currentGpuMs;
    s.avgGpuMs = gpuHistory.avg;
    s.minGpuMs = gpuHistory.min;
    s.maxGpuMs = gpuHistory.max;
    s.fps = s.avgFrameMs > 0.0 ? 1000.0 / s.avgFrameMs : 0.0;

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
    entries.add(new TimeEntry("GPU", s.currentGpuMs));
    double tracked = s.currentMeshMs + s.currentUploadMs + s.currentCullMs + s.currentGpuMs;
    double other = Math.max(0.0, s.currentFrameMs - tracked);
    entries.add(new TimeEntry("Other", other));
    Collections.sort(entries);
    s.breakdown = entries;
    s.breakdownTotalMs = Math.max(s.currentFrameMs, tracked);

    return s;
  }

  public ProfileSnapshot getSnapshot() {
    return snapshot;
  }

  private static final class MetricHistory {
    final double[] values;
    double current;
    double avg;
    double min;
    double max;

    MetricHistory(int size) {
      this.values = new double[size];
    }

    void record(double value) {
      this.current = value;
    }

    void commit(int index, int sampleCount) {
      values[index] = current;
      if (sampleCount <= 0) {
        avg = min = max = current;
        return;
      }
      double sum = 0.0;
      min = Double.MAX_VALUE;
      max = 0.0;
      for (int i = 0; i < sampleCount; i++) {
        double v = values[i];
        sum += v;
        if (v < min) min = v;
        if (v > max) max = v;
      }
      avg = sum / sampleCount;
    }
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
