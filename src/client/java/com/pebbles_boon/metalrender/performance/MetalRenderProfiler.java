package com.pebbles_boon.metalrender.performance;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.fabricmc.loader.api.FabricLoader;
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

    private final double[] frameRing = new double[4096];
    private int frameRingCount;
    private volatile String scene = "";
    private long lastPassReportMs;

    private static long hizCulledSections() {
        try {
            return com.pebbles_boon.metalrender.nativebridge.NativeBridge.isLibLoaded()
                    ? com.pebbles_boon.metalrender.nativebridge.NativeBridge.nGetArenaHizCulled() : 0;
        } catch (UnsatisfiedLinkError e) {
            return 0;
        }
    }
    private final java.util.LinkedHashMap<String, ArrayList<double[]>> sceneRows = new java.util.LinkedHashMap<>();
    private long lastRssNs;
    private double cachedRssMb;
    private String cpuBrand;
    private boolean firstCsvSample = true;
    private long previousSwapNs;
    private double swapMs, swapToSwapMs, limiterMs;
    private double atlasReadbackMs, atlasDiffMs, atlasUploadMs, vanillaCompileMs;
    private long previousCullFailsafe;
    private final SubMs atlasReadbackTimer = new SubMs();
    private final SubMs atlasDiffTimer = new SubMs();
    private final SubMs atlasUploadTimer = new SubMs();
    private final SubMs vanillaCompileTimer = new SubMs();

    private volatile int caveSectionsVisible;
    private volatile double caveWalkMs;
    private volatile long caveWalkCount;
    private volatile long caveWalkReuseCount;

    public void setCaveWorkCounts(long walks, long reuses) {
        caveWalkCount = walks;
        caveWalkReuseCount = reuses;
    }

    public void setCaveVisibility(int count, double millis) {
        caveSectionsVisible = count;
        caveWalkMs = millis;
    }

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
    private final Object csvWriteLock = new Object();
    private volatile boolean csvWorkerStarted;
    private volatile boolean csvEnabled;
    private volatile Thread csvWorker;
    private volatile Path csvFile;
    private volatile boolean csvShutdownHookRegistered;
    private final AtomicLong meshTimeAccNs = new AtomicLong(0);
    private final AtomicLong uploadTimeAccNs = new AtomicLong(0);
    private final AtomicLong cullTimeAccNs = new AtomicLong(0);

    private static final class SubMs {
        private final AtomicLong accNs = new AtomicLong();

        void add(long ns) {
            if (ns > 0)
                accNs.addAndGet(ns);
        }

        double drainMs() {
            return accNs.getAndSet(0) / 1_000_000.0;
        }
    }

    private final SubMs scanTimer = new SubMs();
    private final SubMs lodTimer = new SubMs();
    private final SubMs drawPrepTimer = new SubMs();
    private final SubMs atlasTimer = new SubMs();
    private final AtomicLong particleNsAcc = new AtomicLong();
    private final AtomicInteger particlesDrawnMax = new AtomicInteger();
    private final AtomicLong particlesCulledAcc = new AtomicLong();
    private final SubMs lightmapTimer = new SubMs();
    private final SubMs nativeDrawPrepTimer = new SubMs();
    private final SubMs javaRenderTimer = new SubMs();

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
    private volatile double latestGpuMs;
    private volatile double lastMeshMs;
    private volatile double lastUploadMs;
    private volatile double lastGpuMs;

    private long totalTierA;
    private long totalTierB;
    private long totalTierC;
    private int censusRows;
    private long previousPresents;
    private long previousSkipped;
    private long previousBackendNs;
    private long lastLogTimeMs;
    private long lastCsvEmitMs;
    private long lastSnapshotUpdateMs;
    private static final String CSV_HEADER = "epoch_ms,frame_ms,mesh_ms,upload_ms,cull_ms,"
            + "scan_ms,lod_ms,draw_prep_ms,atlas_ms,lightmap_ms,native_draw_prep_ms,java_render_ms,"
            + "meshes_built,chunks_drawn,meshes,pending,vertex_count,builder_pool,queued,"
            + "builder_busy_pct,builder_imm_busy_pct,builder_bg_busy_pct,rebuild_count,rebuild_reasons,"
            + "blit_wait_ms,blit_bind_ms,blit_copy_ms,blit_quad_ms,blit_gl_gpu_ms,"
            + "native_wait_render_ms,native_cgl_bind_ms,"
            + "pass_terrain_ms,pass_translucent_ms,pass_gui_ms,pass_present_ms,pass_other_ms,"
            + "mega_pages,mega_used_bytes,mega_allocs,mega_frees,"
            + "compress_attempts,compress_success,compress_rejects,"
            + "quads_per_column,resident_quad_bytes,drawn_quads,gpu_ms,"
            + "frames_in_sec,frame_p50_ms,frame_p95_ms,frame_p99_ms,frame_max_ms,process_cpu_pct,rss_mb,"
            + "swap_ms,swap_to_swap_ms,limiter_ms,atlas_readback_ms,atlas_diff_ms,atlas_upload_ms,"
            + "sections_loaded,sections_beyond_circle,cull_failsafe_frames,vanilla_compile_ms,compress_verify_bad,sections_walk_visible,cave_walk_ms,cave_walks,cave_walk_reuses,encoders_per_frame,presents_per_sec,skipped_presents,quads_tier_a,quads_tier_b,quads_tier_c,arena_live_mb,arena_capacity_mb,arena_free_mb,arena_holes,arena_high_mb,arena_bytes_per_quad,quad_verify_bad,cull_oracle_diff,particles_drawn,particles_culled,particle_ms,hiz_culled_sections,scene,face_cull_input,face_cull_removed,arena_cull_gpu_ms,drawn_quads_lod0,drawn_quads_lod1,drawn_quads_lod2";

    private MetalRenderProfiler() {
    }

    public static MetalRenderProfiler getInstance() {
        return INSTANCE;
    }

    public double getLatestGpuMs() {
        return latestGpuMs;
    }

    public double getLastMeshMs() {
        return lastMeshMs;
    }

    public double getLastUploadMs() {
        return lastUploadMs;
    }

    public double getLastGpuMs() {
        return lastGpuMs;
    }

    public synchronized void startDevelopmentRunCsv() {
        if (csvEnabled || !Boolean.getBoolean("metalrender.csv")
                || !FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return;
        }
        Path file = FabricLoader.getInstance().getGameDir()
                .resolve("logs")
                .resolve("metalrender_load.csv");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, CSV_HEADER + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            csvFile = file;
            csvEnabled = true;
            ensureCsvWorker();
            logRunInfo();
            if (!csvShutdownHookRegistered) {
                csvShutdownHookRegistered = true;
                Runtime.getRuntime().addShutdownHook(new Thread(
                        INSTANCE::shutdownCsvWorker, "MetalRender-CsvShutdown"));
            }
            MetalLogger.info("CSV profiling enabled for development run: %s", file);
        } catch (IOException e) {
            MetalLogger.warn("CSV profiling unavailable at %s: %s", file, e.getMessage());
        }
    }

    public void toggleVisible() {
        visible = !visible;
    }

    public boolean isVisible() {
        return visible;
    }

    public void startFrame() {
        frameStartNs = System.nanoTime();
        limiterMs = 0;
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

    public void recordScanTime(long n) {
        scanTimer.add(n);
    }

    public void recordLodTime(long n) {
        lodTimer.add(n);
    }

    public void recordDrawPrepTime(long n) {
        drawPrepTimer.add(n);
    }

    public void recordParticles(int drawn, int culled, long ns) {
        particleNsAcc.addAndGet(ns);
        particlesCulledAcc.addAndGet(culled);
        particlesDrawnMax.accumulateAndGet(drawn, Math::max);
    }

    public void recordAtlasTime(long n) {
        atlasTimer.add(n);
    }

    public void recordLightmapTime(long n) {
        lightmapTimer.add(n);
    }

    public void recordNativeDrawPrepTime(long n) {
        nativeDrawPrepTimer.add(n);
    }

    public void recordJavaRenderTime(long n) {
        javaRenderTimer.add(n);
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

    public synchronized void endFrame() {
        frameEndNs = System.nanoTime();
        totalFrames++;

        double frameMs = (frameEndNs - frameStartNs) / 1_000_000.0;
        if (frameRingCount < frameRing.length) frameRing[frameRingCount++] = frameMs;
        double renderMs = renderEndNs > renderStartNs ? (renderEndNs - renderStartNs) / 1_000_000.0 : 0.0;
        double cpuMs = Math.max(0.0, frameMs - renderMs);

        if (frameMs > HANG_THRESHOLD_MS) {
            hangCount++;
        }

        double meshMs = meshTimeAccNs.getAndSet(0) / 1_000_000.0;
        double uploadMs = uploadTimeAccNs.getAndSet(0) / 1_000_000.0;
        double cullMs = cullTimeAccNs.getAndSet(0) / 1_000_000.0;
        double scanMs = scanTimer.drainMs();
        double lodMs = lodTimer.drainMs();
        double drawPrepMs = drawPrepTimer.drainMs();
        double atlasMs = atlasTimer.drainMs();
        double lightmapMs = lightmapTimer.drainMs();
        double nativeDrawPrepMs = nativeDrawPrepTimer.drainMs();
        double javaRenderMs = javaRenderTimer.drainMs();
        atlasReadbackMs = atlasReadbackTimer.drainMs();
        atlasDiffMs = atlasDiffTimer.drainMs();
        atlasUploadMs = atlasUploadTimer.drainMs();
        vanillaCompileMs = vanillaCompileTimer.drainMs();
        double gpuMs = 0.0;
        try {
            gpuMs = NativeBridge.nGetGpuFrameTimeMs();
        } catch (Throwable ignored) {
        }
        latestGpuMs = gpuMs;
        lastMeshMs = meshMs;
        lastUploadMs = uploadMs;
        lastGpuMs = gpuMs;

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

        if (com.pebbles_boon.metalrender.config.MetalRenderConfig.isDeepDebugActive()
                && nowMs - lastLogTimeMs >= LOG_INTERVAL_MS) {
            lastLogTimeMs = nowMs;
            logPeriodic(frameMs, cpuMs, renderMs,
                    meshMs, uploadMs, cullMs, gpuMs,
                    meshesBuilt, uploadsDone, chunksScanned, chunksDrawn);
        }

        MetalWorldRenderer wrForCsv = MetalRenderClient.getWorldRenderer();
        boolean vanillaProfile = Boolean.getBoolean("metalrender.profileVanilla") && !MetalRenderClient.isEnabled();
        if (csvEnabled && (wrForCsv != null || vanillaProfile) &&
                nowMs - lastCsvEmitMs >= 1000L) {
            lastCsvEmitMs = nowMs;
            if (firstCsvSample) {
                firstCsvSample = false;
                logRunInfo();
            }
            if (vanillaProfile) {
                emitVanillaCsvSample(nowMs, frameMs);
                return;
            }
            emitCsvSample(nowMs, frameMs, meshMs, uploadMs, cullMs,
                    scanMs, lodMs, drawPrepMs, atlasMs, lightmapMs,
                    nativeDrawPrepMs, javaRenderMs,
                    meshesBuilt, chunksDrawn, wrForCsv, gpuMs);
        }
    }

    private void emitCsvSample(long tsMs, double frameMs, double meshMs,
            double uploadMs, double cullMs,
            double scanMs, double lodMs, double drawPrepMs, double atlasMs, double lightmapMs,
            double nativeDrawPrepMs, double javaRenderMs,
            int meshesBuilt, int chunksDrawn, MetalWorldRenderer wr,
            double gpuMs) {
        ensureCsvWorker();
        CustomChunkMesherAccess access = CustomChunkMesherAccess.of(wr);
        double blitWait = 0, blitBind = 0, blitCopy = 0, blitQuad = 0, blitGlGpu = 0;
        try {
            var blitter = wr.getBlitter();
            if (blitter != null) {
                blitWait = blitter.getLastBlitWaitMs();
                blitBind = blitter.getLastCglBindMs();
                blitCopy = blitter.getLastBlitCopyMs();
                blitQuad = blitter.getLastBlitQuadMs();
                blitGlGpu = blitter.getLastBlitGlGpuMs();
            }
        } catch (Throwable ignored) {
        }
        double nativeWaitRender = nativeDouble(() -> NativeBridge.nGetWaitRenderMs());
        double nativeCglBind = nativeDouble(() -> NativeBridge.nGetCglBindMs());
        double nativeDrawPrep = nativeDrawPrepMs;
        double ndp = nativeDouble(() -> NativeBridge.nGetNativeDrawPrepMs());
        if (ndp > 0.0)
            nativeDrawPrep = ndp;
        float[] passes = nativeFloats(5, out -> NativeBridge.nGetPassGpuMs(out));
        long[] mega = nativeLongs(4, out -> NativeBridge.nGetMegaStats(out));
        double quadsPerColumn = access.quadsPerColumn;
        long residentQuadBytes = access.residentQuadBytes;
        long[] quadStats = nativeLongs(7, out -> NativeBridge.nGetQuadStats(out));
        long drawnQuads = access.drawnQuads;
        if (drawnQuads == 0) {
            drawnQuads = quadStats[2];
            if (residentQuadBytes == 0)
                residentQuadBytes = quadStats[1];
            if (quadsPerColumn == 0)
                quadsPerColumn = quadStats[3];
        }
        int compressAttempts = 0, compressSuccess = 0;
        String compressRejects = "";
        try {
            compressAttempts = com.pebbles_boon.metalrender.render.chunk.CustomChunkMesher
                    .getCompressAttempts();
            compressSuccess = com.pebbles_boon.metalrender.render.chunk.CustomChunkMesher
                    .getCompressSuccess();
            compressRejects = formatCounts(com.pebbles_boon.metalrender.render.chunk.CustomChunkMesher
                    .getCompressRejectCounts());
        } catch (Throwable ignored) {
        }
        int rebuildCount = 0;
        String rebuildReasons = "";
        try {
            rebuildCount = com.pebbles_boon.metalrender.render.chunk.CustomChunkMesher
                    .getRebuildCount();
            rebuildReasons = formatCounts(com.pebbles_boon.metalrender.render.chunk.CustomChunkMesher
                    .getRebuildReasonCounts());
        } catch (Throwable ignored) {
        }
        double atlasOut = atlasMs > 0 ? atlasMs : access.atlasMs;
        double lightmapOut = lightmapMs > 0 ? lightmapMs : access.lightmapMs;
        double javaRenderOut = javaRenderMs > 0 ? javaRenderMs : access.javaRenderMs;
        double scanOut = scanMs > 0 ? scanMs : access.scanMs;
        double lodOut = lodMs > 0 ? lodMs : access.lodMs;
        double drawPrepOut = drawPrepMs > 0 ? drawPrepMs : access.drawPrepMs;
        double[] pct = drainFramePercentiles();
        double cpuPct = processCpuPct();
        double rss = rssMb();
        int beyondCircle = 0;
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) {
            var center = mc.player.chunkPosition();
            int distance = mc.options.renderDistance().get() + 2;
            for (var mesh : wr.getChunkMesher().getAllMeshes()) {
                if (!net.minecraft.server.level.ChunkTrackingView.isInViewDistance(
                        center.x(), center.z(), distance, mesh.chunkX, mesh.chunkZ)) beyondCircle++;
            }
        }
        long failsafe = Integer.toUnsignedLong((int) nativeDouble(() -> NativeBridge.nGetCullFailsafeFrames()));
        long failsafeDelta = (failsafe - previousCullFailsafe) & 0xffffffffL;
        previousCullFailsafe = failsafe;
        if (tsMs - lastPassReportMs >= 5000) {
            lastPassReportMs = tsMs;
            String report = com.pebbles_boon.metalrender.backend.metal.MetalBackendStats.passReport();
            if (report != null && !report.startsWith("frames=0")) MetalLogger.info("GPU passes (per frame): %s", report);
        }
        long[] backend = nativeLongs(3, out -> com.pebbles_boon.metalrender.backend.metal.MetalBackendStats.read(out));
        long nowNs = System.nanoTime();
        double presentsPerSec = 0.0;
        long skippedDelta = Math.max(0, backend[2] - previousSkipped);
        if (previousBackendNs != 0 && nowNs > previousBackendNs) {
            presentsPerSec = (backend[1] - previousPresents) * 1e9 / (nowNs - previousBackendNs);
        }
        previousPresents = backend[1];
        previousSkipped = backend[2];
        previousBackendNs = nowNs;
        long[] arena = nativeLongs(11, out -> NativeBridge.nArenaStats(out));
        long tierA = com.pebbles_boon.metalrender.render.chunk.QuadTiers.COUNT_A.sumThenReset();
        long tierB = com.pebbles_boon.metalrender.render.chunk.QuadTiers.COUNT_B.sumThenReset();
        long tierC = com.pebbles_boon.metalrender.render.chunk.QuadTiers.COUNT_C.sumThenReset();
        totalTierA += tierA;
        totalTierB += tierB;
        totalTierC += tierC;
        if (com.pebbles_boon.metalrender.render.chunk.CustomChunkMesher.quadCensus() && (++censusRows % 10) == 0) {
            long total = Math.max(1, totalTierA + totalTierB + totalTierC);
            MetalLogger.info("CENSUS tierA=%.2f%% tierB=%.2f%% tierC=%.2f%% quads=%d reasons=%s",
                    100.0 * totalTierA / total, 100.0 * totalTierB / total, 100.0 * totalTierC / total, total,
                    com.pebbles_boon.metalrender.render.chunk.QuadTiers.reasonSnapshot());
        }
        int[] faceCull = wr.getCameraFacingCullStats();
        StringBuilder sb = new StringBuilder(1024);
        sb.append(tsMs).append(',').append(fmt(frameMs)).append(',').append(fmt(meshMs)).append(',')
                .append(fmt(uploadMs)).append(',').append(fmt(cullMs)).append(',').append(fmt(scanOut)).append(',')
                .append(fmt(lodOut)).append(',').append(fmt(drawPrepOut)).append(',').append(fmt(atlasOut)).append(',')
                .append(fmt(lightmapOut)).append(',').append(fmt(nativeDrawPrep)).append(',').append(fmt(javaRenderOut))
                .append(',')
                .append(meshesBuilt).append(',').append(chunksDrawn).append(',').append(access.meshCount).append(',')
                .append(access.pendingCount).append(',').append(access.vertexCount).append(',')
                .append(access.builderActive).append(',')
                .append(access.builderQueued).append(',').append(fmt(access.builderBusyPct)).append(',')
                .append(fmt(access.builderImmBusyPct)).append(',')
                .append(fmt(access.builderBgBusyPct)).append(',').append(rebuildCount).append(',').append('"')
                .append(rebuildReasons).append('"').append(',')
                .append(fmt(blitWait)).append(',').append(fmt(blitBind)).append(',').append(fmt(blitCopy)).append(',')
                .append(fmt(blitQuad)).append(',').append(fmt(blitGlGpu)).append(',').append(fmt(nativeWaitRender))
                .append(',')
                .append(fmt(nativeCglBind)).append(',').append(fmt(passes[0])).append(',').append(fmt(passes[1]))
                .append(',')
                .append(fmt(passes[2])).append(',').append(fmt(passes[3])).append(',').append(fmt(passes[4]))
                .append(',')
                .append(mega[0]).append(',').append(mega[1]).append(',').append(mega[2]).append(',').append(mega[3])
                .append(',')
                .append(compressAttempts).append(',').append(compressSuccess).append(',').append('"')
                .append(compressRejects).append('"').append(',')
                .append(fmt(quadsPerColumn)).append(',').append(residentQuadBytes).append(',').append(drawnQuads)
                .append(',')
                .append(fmt(gpuMs));
        sb.append(',').append((int) pct[0]);
        for (int i = 1; i < pct.length; i++) sb.append(',').append(fmt(pct[i]));
        sb.append(',').append(fmt(cpuPct)).append(',').append(fmt(rss))
                .append(',').append(fmt(swapMs)).append(',').append(fmt(swapToSwapMs)).append(',').append(fmt(limiterMs))
                .append(',').append(fmt(atlasReadbackMs)).append(',').append(fmt(atlasDiffMs)).append(',').append(fmt(atlasUploadMs))
                .append(',').append(access.meshCount).append(',').append(beyondCircle).append(',').append(failsafeDelta)
                .append(',').append(fmt(vanillaCompileMs)).append(',')
                .append(com.pebbles_boon.metalrender.render.chunk.CustomChunkMesher.getCompressVerifyBad())
                .append(',').append(Math.min(caveSectionsVisible, access.meshCount)).append(',').append(fmt(caveWalkMs))
                .append(',').append(caveWalkCount).append(',').append(caveWalkReuseCount)
                .append(',').append(backend[0]).append(',').append(fmt(presentsPerSec)).append(',')
                .append(skippedDelta)
                .append(',').append(tierA).append(',').append(tierB).append(',').append(tierC)
                .append(',').append(fmt(arena[0] / 1048576.0)).append(',').append(fmt(arena[1] / 1048576.0))
                .append(',').append(fmt(arena[2] / 1048576.0)).append(',').append(arena[3])
                .append(',').append(fmt(arena[4] / 1048576.0))
                .append(',').append(fmt(arena[5] > 0 ? (double) arena[6] / arena[5] : 0.0))
                .append(',').append(com.pebbles_boon.metalrender.render.chunk.QuadTiers.VERIFY_BAD.sum())
                .append(',').append(fmt(arena[8] / 1000.0))
                .append(',').append(particlesDrawnMax.getAndSet(0))
                .append(',').append(particlesCulledAcc.getAndSet(0))
                .append(',').append(fmt(particleNsAcc.getAndSet(0) / 1_000_000.0))
                .append(',').append(hizCulledSections())
                .append(',').append('"').append(scene).append('"')
                .append(',').append(faceCull[1]).append(',').append(faceCull[2])
                .append(',').append(fmt(arena[10] / 1000.0))
                .append(',').append(quadStats[4]).append(',').append(quadStats[5]).append(',').append(quadStats[6])
                .append('\n');
        csvQueue.offer(sb.toString());
        if (chunksDrawn > 0) addSceneRow(pct, gpuMs, atlasOut, nativeDrawPrep, meshMs, uploadMs,
                swapMs, swapToSwapMs, limiterMs, cpuPct, rss, access.meshCount, beyondCircle, failsafeDelta, vanillaCompileMs);
    }

    public synchronized void setScene(String name) {
        if (name.equals(scene)) return;
        if (!scene.isEmpty()) logSceneSummary(scene);
        scene = name;
        frameRingCount = 0;
        lastCsvEmitMs = System.currentTimeMillis();
    }

    private double[] drainFramePercentiles() {
        double[] sorted = java.util.Arrays.copyOf(frameRing, frameRingCount);
        frameRingCount = 0;
        java.util.Arrays.sort(sorted);
        int n = sorted.length;
        if (n == 0) return new double[5];
        return new double[] {n, sorted[(int) (0.50 * (n - 1))], sorted[(int) (0.95 * (n - 1))],
                sorted[(int) (0.99 * (n - 1))], sorted[n - 1]};
    }

    private static double processCpuPct() {
        var bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean process) {
            return Math.max(0, process.getProcessCpuLoad() * 100.0);
        }
        return 0;
    }

    private double rssMb() {
        long now = System.nanoTime();
        if (lastRssNs != 0 && now - lastRssNs < 5_000_000_000L) return cachedRssMb;
        lastRssNs = now;
        try {
            Process process = new ProcessBuilder("ps", "-o", "rss=", "-p",
                    String.valueOf(ProcessHandle.current().pid())).start();
            try (var reader = process.inputReader()) {
                String line = reader.readLine();
                if (line != null) cachedRssMb = Double.parseDouble(line.trim()) / 1024.0;
            }
            process.waitFor();
        } catch (IOException | NumberFormatException ignored) {
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return cachedRssMb;
    }

    public void recordSwap(long startNs, long endNs) {
        swapMs = (endNs - startNs) / 1_000_000.0;
        swapToSwapMs = previousSwapNs == 0 ? 0 : (startNs - previousSwapNs) / 1_000_000.0;
        previousSwapNs = startNs;
    }

    public void recordLimiter(long ns) {
        limiterMs = ns / 1_000_000.0;
    }

    public void recordAtlasSplit(long readbackNs, long diffNs, long uploadNs) {
        atlasReadbackTimer.add(readbackNs);
        atlasDiffTimer.add(diffNs);
        atlasUploadTimer.add(uploadNs);
    }

    public void recordVanillaCompile(long ns) {
        vanillaCompileTimer.add(ns);
    }

    private void logRunInfo() {
        if (cpuBrand == null) {
            cpuBrand = "unknown";
            try {
                Process process = new ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string").start();
                try (var reader = process.inputReader()) {
                    String line = reader.readLine();
                    if (line != null) cpuBrand = line.trim();
                }
                process.waitFor();
            } catch (IOException ignored) {
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        var mc = net.minecraft.client.Minecraft.getInstance();
        var config = MetalRenderClient.getConfig();
        String mode = config != null && !config.enableMetalRendering ? "vanilla" : "metalrender";
        MetalLogger.info("RUNINFO mode=%s cpu=%s os=%s mc=%s renderDistance=%s vsync=%s fpsLimit=%s window=%s",
                mode, cpuBrand, System.getProperty("os.version"), net.minecraft.SharedConstants.getCurrentVersion().name(),
                mc == null || mc.options == null ? "unknown" : mc.options.renderDistance().get(),
                mc == null || mc.options == null ? "unknown" : mc.options.enableVsync().get(),
                mc == null || mc.options == null ? "unknown" : mc.options.framerateLimit().get(),
                mc == null || mc.getWindow() == null ? "unknown" : mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight());
    }

    private void addSceneRow(double[] pct, double gpu, double atlas, double nativeDrawPrep,
            double mesh, double upload, double swap, double swapToSwap, double limiter, double cpu, double rss,
            double sections, double beyond, double failsafe, double vanillaCompile) {
        if (scene.isEmpty() || scene.equals("done") || pct[0] == 0) return;
        sceneRows.computeIfAbsent(scene, k -> new ArrayList<>()).add(new double[] {
                pct[1], pct[2], pct[3], pct[4], gpu, atlas, nativeDrawPrep, mesh, upload,
                swap, swapToSwap, limiter, cpu, rss, sections, beyond, failsafe, vanillaCompile});
    }

    private void logSceneSummary(String name) {
        var rows = sceneRows.remove(name);
        if (rows == null || rows.isEmpty()) return;
        double[] values = new double[18];
        for (int column = 0; column < values.length; column++) {
            double[] sorted = new double[rows.size()];
            for (int row = 0; row < rows.size(); row++) sorted[row] = rows.get(row)[column];
            java.util.Arrays.sort(sorted);
            values[column] = (sorted[(sorted.length - 1) / 2] + sorted[sorted.length / 2]) / 2.0;
            if (column == 3 || column == 13 || column == 14 || column == 15) values[column] = sorted[sorted.length - 1];
            if (column == 12 || column == 16) {
                double sum = 0;
                for (double v : sorted) sum += v;
                values[column] = column == 12 ? sum / sorted.length : sum;
            }
        }
        String[] names = {"frame_p50", "frame_p95", "frame_p99", "frame_max", "gpu", "atlas", "native_draw_prep",
                "mesh", "upload", "swap", "swap_to_swap", "limiter", "cpu_pct", "rss_max", "sections_loaded",
                "sections_beyond_circle", "cull_failsafe_frames", "vanilla_compile"};
        StringBuilder line = new StringBuilder("SUMMARY scene=").append(name).append(" rows=").append(rows.size());
        for (int i = 0; i < names.length; i++) line.append(' ').append(names[i]).append('=').append(fmt(values[i]));
        line.append(" fps_typical=").append(fmt(values[0] > 0 ? 1000.0 / values[0] : 0));
        MetalLogger.info(line.toString());
    }

    private void emitVanillaCsvSample(long tsMs, double frameMs) {
        double[] pct = drainFramePercentiles();
        double cpu = processCpuPct();
        double rss = rssMb();
        StringBuilder line = new StringBuilder(1024);
        for (String column : CSV_HEADER.split(",")) {
            if (!line.isEmpty()) line.append(',');
            line.append(switch (column) {
                case "epoch_ms" -> Long.toString(tsMs);
                case "frame_ms" -> fmt(frameMs);
                case "frames_in_sec" -> Integer.toString((int) pct[0]);
                case "frame_p50_ms" -> fmt(pct[1]);
                case "frame_p95_ms" -> fmt(pct[2]);
                case "frame_p99_ms" -> fmt(pct[3]);
                case "frame_max_ms" -> fmt(pct[4]);
                case "process_cpu_pct" -> fmt(cpu);
                case "rss_mb" -> fmt(rss);
                case "scene" -> "\"" + scene + "\"";
                case "rebuild_reasons", "compress_rejects" -> "\"\"";
                case "pass_terrain_ms", "pass_translucent_ms", "pass_gui_ms", "pass_present_ms", "pass_other_ms" -> "-1";
                default -> "0";
            });
        }
        csvQueue.offer(line.append('\n').toString());
        if (net.minecraft.client.Minecraft.getInstance().level != null) {
            addSceneRow(pct, 0, 0, 0, 0, 0, 0, 0, 0, cpu, rss, 0, 0, 0, 0);
        }
    }

    private interface DoubleNative {
        double get();
    }

    private interface FloatArrayNative {
        int fill(float[] out);
    }

    private interface LongArrayNative {
        int fill(long[] out);
    }

    private static double nativeDouble(DoubleNative call) {
        try {
            return call.get();
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    private static float[] nativeFloats(int size, FloatArrayNative call) {
        float[] out = new float[size];
        try {
            call.fill(out);
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static long[] nativeLongs(int size, LongArrayNative call) {
        long[] out = new long[size];
        try {
            call.fill(out);
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String formatCounts(java.util.Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty())
            return "";
        java.util.ArrayList<String> keys = new java.util.ArrayList<>(counts.keySet());
        java.util.Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0)
                sb.append(';');
            String k = keys.get(i);
            sb.append(k).append('=').append(counts.get(k));
        }
        return sb.toString();
    }

    private static String fmt(double ms) {
        return String.format(Locale.ROOT, "%.3f", ms);
    }

    private void ensureCsvWorker() {
        if (!csvEnabled || csvWorkerStarted)
            return;
        synchronized (this) {
            if (!csvEnabled || csvWorkerStarted)
                return;
            csvWorkerStarted = true;
            Thread t = new Thread(() -> {
                StringBuilder batch = new StringBuilder(4096);
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        String line = csvQueue.poll(250L,
                                java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (line == null) {
                            continue;
                        }
                        batch.append(line);
                        String drain;
                        while ((drain = csvQueue.poll()) != null) {
                            batch.append(drain);
                        }
                        appendCsvBatch(batch);
                        batch.setLength(0);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    if (batch.length() > 0) {
                        appendCsvBatch(batch);
                        batch.setLength(0);
                    }
                    StringBuilder remaining = drainCsvQueue();
                    if (remaining.length() > 0) {
                        appendCsvBatch(remaining);
                    }
                }
            }, "MetalRender-CsvWorker");
            t.setDaemon(true);
            csvWorker = t;
            t.start();
        }
    }

    private void appendCsvBatch(StringBuilder batch) {
        Path file = csvFile;
        if (file == null || batch.length() == 0) {
            return;
        }
        synchronized (csvWriteLock) {
            try {
                Files.writeString(file, batch.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                        StandardOpenOption.WRITE);
            } catch (IOException e) {
                MetalLogger.warn("CSV write failed at %s: %s", file, e.getMessage());
            }
        }
    }

    private StringBuilder drainCsvQueue() {
        StringBuilder remaining = new StringBuilder(4096);
        String line;
        while ((line = csvQueue.poll()) != null) {
            remaining.append(line);
        }
        return remaining;
    }

    private synchronized void shutdownCsvWorker() {
        logSceneSummary(scene);
        Thread worker = csvWorker;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        StringBuilder remaining = drainCsvQueue();
        if (remaining.length() > 0) {
            appendCsvBatch(remaining);
        }
    }

    private static final class CustomChunkMesherAccess {
        int meshCount, pendingCount, vertexCount, builderActive, builderQueued, drawnQuads;
        long residentQuadBytes;
        double builderBusyPct, builderImmBusyPct, builderBgBusyPct, quadsPerColumn;
        double scanMs, lodMs, drawPrepMs, atlasMs, lightmapMs, javaRenderMs;

        static CustomChunkMesherAccess of(MetalWorldRenderer wr) {
            CustomChunkMesherAccess a = new CustomChunkMesherAccess();
            try {
                com.pebbles_boon.metalrender.render.chunk.CustomChunkMesher m = wr.getChunkMesher();
                a.meshCount = m.getMeshCount();
                a.pendingCount = m.getPendingCount();
                a.vertexCount = m.getTotalVertexCount();
                a.builderActive = m.getBuilderActiveCount();
                a.builderQueued = m.getBuilderQueueDepth();
                a.builderBusyPct = m.getBuilderBusyPct();
                a.builderImmBusyPct = m.getImmediateBusyPct();
                a.builderBgBusyPct = m.getBackgroundBusyPct();
                a.quadsPerColumn = m.getQuadsPerColumn();
                a.residentQuadBytes = m.getResidentQuadBytes();
                a.drawnQuads = m.getDrawnQuadCount();
                a.scanMs = wr.getLastScanMs();
                a.lodMs = wr.getLastLodMs();
                a.drawPrepMs = wr.getLastDrawPrepMs();
                a.atlasMs = wr.getLastAtlasMs();
                a.lightmapMs = wr.getLastLightmapMs();
                a.javaRenderMs = wr.getLastJavaRenderMs();
            } catch (Throwable ignored) {
            }
            return a;
        }
    }

    private void logPeriodic(double frameMs, double cpuMs, double renderMs,
            double meshMs, double uploadMs, double cullMs, double gpuMs,
            int meshesBuilt, int uploadsDone, int chunksScanned, int chunksDrawn) {
        StringBuilder sb = new StringBuilder();
        sb.append("[profiler] sampled_frame ");
        sb.append(String.format(Locale.ROOT, "frame=%.2fms cpu=%.2fms render=%.2fms ", frameMs, cpuMs, renderMs));
        sb.append(String.format(Locale.ROOT, "mesh=%.2fms upload=%.2fms cull=%.2fms gpu=%.2fms | ",
                meshMs, uploadMs, cullMs, gpuMs));
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
            int[] faceCull = wr.getCameraFacingCullStats();
            s.cameraFacingCullingEnabled = faceCull.length > 0 && faceCull[0] != 0;
            s.cameraFacingCullInput = faceCull.length > 1 ? faceCull[1] : 0;
            s.cameraFacingCullRemoved = faceCull.length > 2 ? faceCull[2] : 0;
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
                if (v < min)
                    min = v;
                if (v > max)
                    max = v;
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
        public boolean cameraFacingCullingEnabled;
        public int cameraFacingCullInput;
        public int cameraFacingCullRemoved;
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
            lines.add(String.format(Locale.ROOT, "Meshes %d | Verts %d | Pending %d", meshCount, vertexCount,
                    pendingCount));
            lines.add(String.format(Locale.ROOT, "Drawn %d | Built %d | Uploads %d | Hangs %d", drawnChunkCount,
                    meshesBuilt,
                    uploadsDone, hangCount));
            lines.add(String.format(Locale.ROOT, "Face Cull %s | Opaque %d -> %d removed",
                    cameraFacingCullingEnabled ? "ON" : "OFF", cameraFacingCullInput,
                    cameraFacingCullRemoved));
            lines.add(String.format(Locale.ROOT, "Scanned %d | Mem %d/%dMB", chunksScanned, usedMemoryMb, maxMemoryMb));
            return lines.toArray(new String[0]);
        }
    }
}
