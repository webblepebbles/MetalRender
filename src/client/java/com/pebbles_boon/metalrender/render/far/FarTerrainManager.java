package com.pebbles_boon.metalrender.render.far;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.render.chunk.CustomChunkMesher;
import com.pebbles_boon.metalrender.render.far.seed.FarSeedSource;
import com.pebbles_boon.metalrender.render.far.seed.SeedColumns;
import com.pebbles_boon.metalrender.util.MetalLogger;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.storage.LevelResource;

public final class FarTerrainManager {
    private static final FarTerrainManager INSTANCE = new FarTerrainManager();
    private static final int MAX_INFLIGHT_PER_THREAD = 3;
    private static final long UPLOAD_BYTES_PER_FRAME = 8L << 20;
    private static final int UPLOADS_PER_FRAME = 64;

    public static FarTerrainManager get() {
        return INSTANCE;
    }

    private abstract static class Job implements Runnable, Comparable<Job> {
        final double priority;
        final long seq;
        private static final AtomicLong SEQ = new AtomicLong();

        Job(double priority) {
            this.priority = priority;
            this.seq = SEQ.incrementAndGet();
        }

        @Override
        public int compareTo(Job o) {
            int c = Double.compare(priority, o.priority);
            return c != 0 ? c : Long.compare(seq, o.seq);
        }
    }

    private record Built(long key, long version, FarTileBuilder.Blob blob, int generation) {
    }

    private volatile boolean running;
    private volatile boolean initializing;
    private int pendingFar = -1;
    private int pendingQuality = -1;
    private long pendingSince;
    private volatile int generation;
    private ThreadPoolExecutor pool;
    private volatile FarStore store;
    private final java.util.concurrent.atomic.AtomicBoolean flushing = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.ExecutorService flusher = java.util.concurrent.Executors
            .newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "metalrender-far-flush");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
    private FarSeedSource seed;
    private FarLooks looks;
    private ClientLevel level;
    private int minY;
    private int farChunks;
    private int quality;
    private int startedMem;
    private int startedOpts = -1;
    private int sentOpts = -1;
    private boolean roughLod;
    private volatile boolean optCompact;
    private volatile boolean optPlane;
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap roughness = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    private int selRd;
    private final LongOpenHashSet scaffold = new LongOpenHashSet();
    private LongOpenHashSet rootKeys = new LongOpenHashSet();
    private final Long2LongOpenHashMap uncoveredSince = new Long2LongOpenHashMap();
    private long idleSince;
    private final LongOpenHashSet desiredAnc = new LongOpenHashSet();
    private float farRadius;
    private float radiusScale = 1.0f;
    private volatile boolean nativeReady;
    private volatile boolean nativeFailed;
    private int frame;
    private double lastSelX = Double.NaN;
    private double lastSelZ = Double.NaN;
    private int lastSelRd = -1;
    private final LongOpenHashSet desired = new LongOpenHashSet();
    private final LongArrayList desiredOrder = new LongArrayList();
    private int requestCursor;
    private final LongOpenHashSet live = new LongOpenHashSet();
    private final LongOpenHashSet built = new LongOpenHashSet();
    private final LongOpenHashSet inflight = new LongOpenHashSet();
    private final LongOpenHashSet uploaded = new LongOpenHashSet();
    private final Long2IntOpenHashMap missingMask = new Long2IntOpenHashMap();
    private final Long2LongOpenHashMap refreshAt = new Long2LongOpenHashMap();
    private final ConcurrentLinkedQueue<Built> results = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> changed = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> seeded = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> finished = new ConcurrentLinkedQueue<>();
    private final ArrayList<Built> uploadQueue = new ArrayList<>();
    private final long[] pollScratch = new long[4096];
    private final FarNearMask nearMask = new FarNearMask();
    private final AtomicInteger ingestQueued = new AtomicInteger();
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> editDue = new java.util.concurrent.ConcurrentHashMap<>();
    private long lastFlush;
    private boolean swapDirty = true;
    private volatile float fogReach;
    private float pixelScale = 700.0f;
    private float lastPixelScale = -1.0f;
    private int pixelVotes;
    private boolean zoomedView;
    private volatile double zoomK = 2.0;
    private float basePixelScale = 700.0f;
    private final LongOpenHashSet refined = new LongOpenHashSet();
    private boolean selectSoon;
    private int lastSelectFrame;
    private int fogShrinkFrames;
    private boolean coverageReset = true;
    private float viewX;
    private float viewZ = 1.0f;
    private float viewCos = -1.0f;
    private float viewSin;
    private float lastViewX;
    private float lastViewZ;
    private float coverage;
    private final long[] stats = new long[12];

    private FarTerrainManager() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            FarStore s = store;
            if (s != null) {
                try {
                    s.flush();
                } catch (RuntimeException ignored) {
                }
            }
        }, "metalrender-far-shutdown"));
    }

    public boolean active() {
        return running && !initializing && nativeReady && farChunks > 0;
    }

    public int farChunks() {
        return running ? farChunks : 0;
    }

    public float totalBlocks(int renderDistanceChunks) {
        if (!active()) {
            return renderDistanceChunks * 16.0f;
        }
        return (renderDistanceChunks + farChunks) * 16.0f * radiusScale;
    }

    public float fogBlocks(int renderDistanceChunks) {
        float base = renderDistanceChunks * 16.0f;
        if (!active()) {
            return base;
        }
        float total = totalBlocks(renderDistanceChunks);
        return Math.max(base + 64.0f, total);
    }

    private void updateCoverage(int rd) {
        float reach = rd * 16.0f;
        int total = 0;
        int missing = 0;
        for (int i = 0; i < desiredOrder.size(); i++) {
            long k = desiredOrder.getLong(i);
            total++;
            if (!(live.contains(k) || liveAncestor(k) || liveChildren(k))) {
                missing++;
                if (missing > 48) {
                    break;
                }
            }
            if (missing * 20 <= total) {
                reach = (float) tileDistance(k, lastSelX, lastSelZ) + FarKeys.size(FarKeys.level(k));
            }
        }
        float c = Math.max(rd * 16.0f, Math.min(farRadius, reach));
        coverage = coverageReset ? c : Math.max(coverage, c);
        coverageReset = false;
    }

    private boolean liveChildren(long key) {
        if (FarKeys.level(key) == 0) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if (!live.contains(FarKeys.child(key, i))) {
                return false;
            }
        }
        return true;
    }

    public FarNearMask nearMask() {
        return nearMask;
    }

    public void onWorldLoad(ClientLevel lvl) {
        stop();
        level = lvl;
    }

    public void onWorldUnload() {
        stop();
        level = null;
    }

    private void start(ClientLevel lvl, MetalRenderConfig cfg) {
        looks = FarLooks.get();
        if (looks == null) {
            return;
        }
        if (nativeFailed || !NativeBridge.isLibLoaded()) {
            return;
        }
        level = lvl;
        minY = lvl.getMinY();
        int threads = cfg.farThreads > 0 ? cfg.farThreads
                : Math.max(2, Runtime.getRuntime().availableProcessors() / 2 - 1);
        generation++;
        int gen = generation;
        pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new PriorityBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "metalrender-far");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                });
        desired.clear();
        desiredOrder.clear();
        live.clear();
        built.clear();
        inflight.clear();
        uploaded.clear();
        missingMask.clear();
        refreshAt.clear();
        results.clear();
        changed.clear();
        finished.clear();
        uploadQueue.clear();
        nearMask.reset();
        editDue.clear();
        radiusScale = 1.0f;
        fogReach = 0.0f;
        coverage = 0.0f;
        lastSelX = Double.NaN;
        seed = null;
        store = null;
        running = true;
        initializing = true;
        lastFlush = System.currentTimeMillis();
        boolean seedGen = cfg.farSeedGen;
        boolean blendBiomes = cfg.farBiomeBlend;
        String manual = cfg.farSeed;
        startedMem = cfg.farMemoryMb;
        startedOpts = buildOpts(cfg);
        optCompact = cfg.farCompactQuads;
        optPlane = cfg.farWaterPlane;
        roughness.clear();
        sentOpts = -1;
        long mem = (long) cfg.farMemoryMb << 20;
        long disk = (long) cfg.farDiskMb << 20;
        Path dir = storeDir(lvl);
        int nThreads = threads;
        int budget = cfg.farGpuBudgetMb;
        Thread init = new Thread(() -> {
            if (!nativeReady) {
                boolean ok = false;
                try {
                    ok = NativeBridge.nFarConfigure(budget, 96);
                } catch (Throwable t) {
                    MetalLogger.warn("far terrain native init failed: %s", t.toString());
                }
                if (!ok) {
                    nativeFailed = true;
                    MetalLogger.warn("far terrain native init failed");
                    Minecraft.getInstance().execute(() -> {
                        if (gen == generation) {
                            running = false;
                            initializing = false;
                        }
                    });
                    return;
                }
                nativeReady = true;
            }
            NativeBridge.nFarClear();
            FarSeedSource sd = null;
            try {
                sd = seedGen ? FarSeedSource.forLevel(lvl, manual) : null;
            } catch (Throwable t) {
                MetalLogger.warn("far seed init failed: %s", t.toString());
            }
            if (sd != null) {
                sd.blend = blendBiomes;
            }
            FarStore st = new FarStore(dir, mem, disk, sd != null ? sd.fingerprint : 0L,
                    FarStateIds.registryFingerprint());
            FarSeedSource fsd = sd;
            Minecraft.getInstance().execute(() -> {
                if (gen != generation || !running) {
                    return;
                }
                seed = fsd;
                store = st;
                initializing = false;
                MetalLogger.info("far terrain started: %d chunks, quality %d, %d threads, seed %s", farChunks,
                        quality, nThreads, fsd != null ? "on" : "off");
                ingestLoaded(lvl, gen);
            });
        }, "metalrender-far-init");
        init.setDaemon(true);
        init.setPriority(Thread.MIN_PRIORITY);
        init.start();
    }

    private Path storeDir(ClientLevel lvl) {
        Minecraft mc = Minecraft.getInstance();
        String world = "unknown";
        IntegratedServer server = mc.getSingleplayerServer();
        if (server != null) {
            try {
                world = "sp_" + server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
            } catch (RuntimeException ignored) {
            }
        } else if (mc.getCurrentServer() != null) {
            world = "mp_" + mc.getCurrentServer().ip;
        }
        world = world.replaceAll("[^A-Za-z0-9._-]", "_");
        String dim = lvl.dimension().identifier().toString().replaceAll("[^A-Za-z0-9._-]", "_");
        return FabricLoader.getInstance().getGameDir().resolve("metalrender").resolve("far").resolve(world)
                .resolve(dim);
    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        generation++;
        ThreadPoolExecutor p = pool;
        pool = null;
        FarStore s = store;
        store = null;
        initializing = false;
        if (p != null || s != null) {
            Thread closer = new Thread(() -> {
                if (p != null) {
                    p.shutdownNow();
                    try {
                        p.awaitTermination(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (s != null) {
                    try {
                        s.flush();
                    } catch (RuntimeException e) {
                        MetalLogger.warn("far flush failed: %s", e.toString());
                    }
                }
            }, "metalrender-far-close");
            closer.setDaemon(false);
            closer.start();
        }
        if (nativeReady) {
            NativeBridge.nFarClear();
        }
        desired.clear();
        live.clear();
        built.clear();
        inflight.clear();
        uploaded.clear();
        uploadQueue.clear();
        nearMask.reset();
    }

    private void ingestLoaded(ClientLevel lvl, int gen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        int rd = mc.options.getEffectiveRenderDistance();
        int pcx = mc.player.chunkPosition().x();
        int pcz = mc.player.chunkPosition().z();
        for (int dz = -rd - 1; dz <= rd + 1; dz++) {
            for (int dx = -rd - 1; dx <= rd + 1; dx++) {
                LevelChunk c = lvl.getChunkSource().getChunkNow(pcx + dx, pcz + dz);
                if (c != null) {
                    submitIngest(c, gen);
                }
            }
        }
    }

    public void onChunkLoaded(LevelChunk chunk) {
        if (!running || chunk == null) {
            return;
        }
        nearMask.onLoaded(chunk.getPos().x(), chunk.getPos().z());
        long ck = ((long) chunk.getPos().x() << 32) | (chunk.getPos().z() & 0xFFFFFFFFL);
        editDue.putIfAbsent(ck, System.currentTimeMillis() + 3000);
    }

    public void onBlockChanged(int blockX, int blockZ) {
        if (!running || level == null) {
            return;
        }
        long ck = ((long) (blockX >> 4) << 32) | ((blockZ >> 4) & 0xFFFFFFFFL);
        editDue.putIfAbsent(ck, System.currentTimeMillis() + 400);
    }

    private void drainEdits() {
        if (editDue.isEmpty() || level == null) {
            return;
        }
        long now = System.currentTimeMillis();
        var it = editDue.entrySet().iterator();
        int n = 0;
        while (it.hasNext() && n < 16) {
            var e = it.next();
            if (e.getValue() > now) {
                continue;
            }
            it.remove();
            long ck = e.getKey();
            LevelChunk c = level.getChunkSource().getChunkNow((int) (ck >> 32), (int) ck);
            if (c != null) {
                submitIngest(c, generation);
                n++;
            }
        }
    }

    private void submitIngest(LevelChunk chunk, int gen) {
        ThreadPoolExecutor p = pool;
        ClientLevel lvl = level;
        FarLooks lk = looks;
        FarStore st = store;
        if (p == null || lvl == null || lk == null || st == null) {
            return;
        }
        ingestQueued.incrementAndGet();
        p.execute(new Job(-1.0) {
            @Override
            public void run() {
                ingestQueued.decrementAndGet();
                if (gen != generation) {
                    return;
                }
                try {
                    ingest(chunk, lvl, lk, st);
                } catch (Throwable t) {
                    MetalLogger.warn("far ingest failed: %s", t.toString());
                }
            }
        });
    }

    private void ingest(LevelChunk chunk, ClientLevel lvl, FarLooks lk, FarStore st) {
        int[][] cols = FarIngest.ingest(chunk, lvl, lk);
        int bx0 = chunk.getPos().getMinBlockX();
        int bz0 = chunk.getPos().getMinBlockZ();
        long k0 = FarKeys.containing(0, bx0, bz0);
        FarTileData t0 = st.get(k0, true);
        int lx0 = bx0 - FarKeys.originX(k0);
        int lz0 = bz0 - FarKeys.originZ(k0);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                t0.set((lz0 + z) * 32 + lx0 + x, cols[z * 16 + x], FarTileData.REAL);
            }
        }
        st.markDirty(t0);
        noteChanged(k0, lx0 == 0, lx0 + 16 == 32, lz0 == 0, lz0 + 16 == 32);
        for (int lv = 1; lv <= FarKeys.MAX_LEVEL; lv++) {
            int w = 1 << lv;
            int gx0 = Math.floorDiv(bx0, w);
            int gx1 = Math.floorDiv(bx0 + 15, w);
            int gz0 = Math.floorDiv(bz0, w);
            int gz1 = Math.floorDiv(bz0 + 15, w);
            for (int gz = gz0; gz <= gz1; gz++) {
                for (int gx = gx0; gx <= gx1; gx++) {
                    long pk = FarKeys.containing(lv, gx * w, gz * w);
                    FarTileData pt = st.get(pk, true);
                    int[][] kids = new int[4][];
                    boolean anyReal = false;
                    boolean anyPresent = false;
                    for (int i = 0; i < 4; i++) {
                        int cgx = gx * 2 + (i & 1);
                        int cgz = gz * 2 + (i >> 1);
                        int cw = w >> 1;
                        long ck = FarKeys.containing(lv - 1, cgx * cw, cgz * cw);
                        FarTileData ct = st.get(ck, false);
                        if (ct == null) {
                            kids[i] = FarTileData.EMPTY;
                            continue;
                        }
                        int cix = cgx - FarKeys.tx(ck) * 32;
                        int ciz = cgz - FarKeys.tz(ck) * 32;
                        int idx = ciz * 32 + cix;
                        byte kind = ct.kind(idx);
                        kids[i] = ct.column(idx);
                        anyReal |= kind == FarTileData.REAL;
                        anyPresent |= kind != FarTileData.MISSING;
                    }
                    if (!anyPresent) {
                        continue;
                    }
                    int pix = gx - FarKeys.tx(pk) * 32;
                    int piz = gz - FarKeys.tz(pk) * 32;
                    pt.set(piz * 32 + pix, FarDownsample.merge(kids[0], kids[1], kids[2], kids[3],
                            FarKeys.vshift(lv) - FarKeys.vshift(lv - 1)),
                            anyReal ? FarTileData.REAL : FarTileData.SEED);
                    st.markDirty(pt);
                    noteChanged(pk, pix == 0, pix == 31, piz == 0, piz == 31);
                }
            }
        }
    }

    private void noteChanged(long key, boolean west, boolean east, boolean north, boolean south) {
        changed.add(key);
        if (west) {
            changed.add(FarKeys.neighbor(key, -1, 0));
        }
        if (east) {
            changed.add(FarKeys.neighbor(key, 1, 0));
        }
        if (north) {
            changed.add(FarKeys.neighbor(key, 0, -1));
        }
        if (south) {
            changed.add(FarKeys.neighbor(key, 0, 1));
        }
    }

    public void setView(float m11, float m00, int screenHeight, float lookX, float lookZ) {
        if (m11 > 0.0f && screenHeight > 0) {
            float raw = m11 * screenHeight * 0.5f;
            if (raw > pixelScale * 1.5f || raw < pixelScale / 1.5f) {
                if (++pixelVotes >= 6) {
                    pixelScale = raw;
                    pixelVotes = 0;
                    zoomedView = m11 > 3.0f;
                    if (!zoomedView) {
                        basePixelScale = raw;
                    }
                }
            } else {
                pixelVotes = 0;
            }
        }
        float len = (float) Math.sqrt(lookX * lookX + lookZ * lookZ);
        if (len > 1e-4f) {
            viewX = lookX / len;
            viewZ = lookZ / len;
        }
        if (m00 > 0.0f) {
            double half = Math.min(Math.PI, Math.atan(1.0 / m00) * 1.25 + 0.03);
            viewCos = half >= Math.PI ? -1.0f : (float) Math.cos(half);
            viewSin = (float) Math.sqrt(Math.max(0.0, 1.0 - (double) viewCos * viewCos));
        }
    }

    public void frame(double camX, double camY, double camZ) {
        MetalRenderConfig cfg = MetalRenderClient.getConfig();
        Minecraft mc = Minecraft.getInstance();
        if (cfg == null || mc.level == null || mc.player == null) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        if (cfg.farDistanceChunks != pendingFar || cfg.farQuality != pendingQuality) {
            pendingFar = cfg.farDistanceChunks;
            pendingQuality = cfg.farQuality;
            pendingSince = nowMs;
        }
        boolean settled = mc.gui.screen() == null && nowMs - pendingSince > 400;
        int wantFar = settled ? pendingFar : (running ? farChunks : 0);
        int wantQuality = settled ? pendingQuality : (running ? quality : pendingQuality);
        if (!settled && !running) {
            return;
        }
        if (wantFar <= 0 || !CustomChunkMesher.tiersActive()) {
            if (running) {
                stop();
            }
            farChunks = 0;
            return;
        }
        boolean memChanged = settled && running && (cfg.farMemoryMb != startedMem || buildOpts(cfg) != startedOpts);
        if (!running || mc.level != level || wantQuality != quality || memChanged) {
            farChunks = wantFar;
            quality = wantQuality;
            start(mc.level, cfg);
            if (!running) {
                return;
            }
        }
        if (wantFar != farChunks) {
            farChunks = wantFar;
            lastSelX = Double.NaN;
        }
        if (initializing || store == null) {
            return;
        }
        int opts = (cfg.farFlatColor ? 1 : 0) | (cfg.farHiZ ? 16 : 0) | (cfg.farLodFade ? 32 : 0);
        if (opts != sentOpts) {
            sentOpts = opts;
            NativeBridge.nFarSetOptions(opts);
        }
        if (cfg.farRoughLod != roughLod) {
            roughLod = cfg.farRoughLod;
            lastSelX = Double.NaN;
        }
        frame++;
        if (radiusScale < 1.0f && frame % 900 == 0) {
            radiusScale = Math.min(1.0f, radiusScale * 1.05f);
            lastSelX = Double.NaN;
        }
        int rd = mc.options.getEffectiveRenderDistance();
        farRadius = (rd + farChunks) * 16.0f * radiusScale;
        int pcx = mc.player.chunkPosition().x();
        int pcz = mc.player.chunkPosition().z();
        NativeBridge.nFarSetView(farRadius, pcx - rd - 1, pcz - rd - 1, pcx + rd + 1, pcz + rd + 1);
        store.focus((int) camX, (int) camZ);
        if (looks.takeDirty()) {
            int[] n = new int[1];
            var buf = looks.snapshot(n);
            NativeBridge.nFarSetLooks(buf, n[0]);
        }
        nearMask.update(mc.level, rd, pcx, pcz);
        drainEdits();
        drainChanged();
        drainFinished();
        int polled = NativeBridge.nFarPollBuilt(pollScratch);
        for (int i = 0; i < polled; i++) {
            uploaded.remove(pollScratch[i]);
            if (desired.contains(pollScratch[i]) || live.contains(pollScratch[i])
                    || scaffold.contains(pollScratch[i])) {
                built.add(pollScratch[i]);
                swapDirty = true;
            }
        }
        double dx = camX - lastSelX;
        double dz = camZ - lastSelZ;
        boolean zoomed = lastPixelScale > 0.0f && pixelScale != lastPixelScale;
        boolean turned = viewX * lastViewX
                + viewZ * lastViewZ < (zoomedView ? Math.max(0.85f, viewCos + (1.0f - viewCos) * 0.6f) : 0.85f)
                || (selectSoon && frame - lastSelectFrame >= 8);
        if (Double.isNaN(lastSelX) || dx * dx + dz * dz > 32 * 32 || rd != lastSelRd || frame % 600 == 0 || zoomed
                || turned) {
            coverageReset = coverageReset || zoomed || rd != lastSelRd || Double.isNaN(lastSelX)
                    || dx * dx + dz * dz > 32 * 32;
            lastPixelScale = pixelScale;
            lastViewX = viewX;
            lastViewZ = viewZ;
            select(camX, camZ, rd);
            lastSelX = camX;
            lastSelZ = camZ;
            lastSelRd = rd;
        }
        if (swapDirty || frame % 30 == 0) {
            swapDirty = false;
            swap();
            if (!Double.isNaN(lastSelX)) {
                updateCoverage(rd);
            }
        }
        float fr = fogReach;
        if (fr <= 0.0f) {
            fr = coverage <= 0.0f ? rd * 16.0f : coverage;
            fogShrinkFrames = 0;
        } else if (coverage < fr * 0.97f) {
            if (++fogShrinkFrames > 180) {
                fr = Math.max(coverage, fr - Math.max(1.0f, fr * 0.004f));
            }
        } else if (coverage <= fr) {
            fogShrinkFrames = 0;
        } else {
            fogShrinkFrames = 0;
            fr = Math.min(coverage, fr + Math.max(2.0f, fr * 0.01f));
        }
        fogReach = fr;
        request(camX, camZ);
        upload();
        if (frame % 600 == 300) {
            diagnose(camX, camZ);
        }
        long now = System.currentTimeMillis();
        if (now - lastFlush > 15000) {
            lastFlush = now;
            FarStore st = store;
            if (st != null && flushing.compareAndSet(false, true)) {
                flusher.execute(() -> {
                    try {
                        st.flush();
                    } catch (RuntimeException e) {
                        MetalLogger.warn("far flush failed: %s", e.toString());
                    } finally {
                        flushing.set(false);
                    }
                });
            }
        }
    }

    private String lastDiag = "";

    private void diagnose(double cx, double cz) {
        int[] want = new int[FarKeys.MAX_LEVEL + 1];
        int[] shown = new int[FarKeys.MAX_LEVEL + 1];
        LongOpenHashSet liveAnc = new LongOpenHashSet();
        double reach = 0.0;
        var lit = live.iterator();
        while (lit.hasNext()) {
            long k = lit.nextLong();
            reach = Math.max(reach, farthestCorner(k, cx, cz));
            for (int lv = FarKeys.level(k) + 1; lv <= FarKeys.MAX_LEVEL; lv++) {
                if (!liveAnc.add(FarKeys.ancestorAt(k, lv))) {
                    break;
                }
            }
        }
        int stuckBuilt = 0;
        int stuckUploaded = 0;
        int stuckInflight = 0;
        int stuckNone = 0;
        double wantedArea = 0.0;
        double coveredArea = 0.0;
        double wantedReach = 0.0;
        var dit = desired.iterator();
        while (dit.hasNext()) {
            long k = dit.nextLong();
            int lv = FarKeys.level(k);
            double area = (double) FarKeys.size(lv) * FarKeys.size(lv);
            want[lv]++;
            wantedArea += area;
            wantedReach = Math.max(wantedReach, tileDistance(k, cx, cz));
            if (live.contains(k) || liveAnc.contains(k) || liveAncestor(k)) {
                shown[lv]++;
                coveredArea += area;
            } else if (built.contains(k)) {
                stuckBuilt++;
            } else if (uploaded.contains(k)) {
                stuckUploaded++;
            } else if (inflight.contains(k)) {
                stuckInflight++;
            } else {
                stuckNone++;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int lv = 0; lv <= FarKeys.MAX_LEVEL; lv++) {
            if (want[lv] > 0) {
                sb.append(String.format(" L%d=%d/%d", lv, shown[lv], want[lv]));
            }
        }
        String text = String.format(
                "far coverage: radius %.0f (scale %.2f), %.1f%% of wanted area shown, nearest edge of farthest wanted tile %.0f, farthest shown corner %.0f, missing: built %d uploaded %d inflight %d idle %d,%s",
                farRadius, radiusScale, wantedArea > 0.0 ? coveredArea * 100.0 / wantedArea : 100.0, wantedReach,
                reach, stuckBuilt, stuckUploaded, stuckInflight, stuckNone, sb);
        if (!text.equals(lastDiag)) {
            lastDiag = text;
            MetalLogger.info("%s", text);
        }
    }

    private void drainChanged() {
        long now = System.currentTimeMillis();
        Long k;
        int guard = 0;
        while ((k = changed.poll()) != null && guard++ < 20000) {
            long key = k;
            if (desired.contains(key) || live.contains(key)) {
                if (!refreshAt.containsKey(key)) {
                    refreshAt.put(key, now + Math.min(20000L, 800L << FarKeys.level(key)));
                }
            }
        }
        guard = 0;
        while ((k = seeded.poll()) != null && guard++ < 20000) {
            long key = k;
            if (missingMask.get(key) != 0 && (desired.contains(key) || live.contains(key))
                    && !refreshAt.containsKey(key)) {
                refreshAt.put(key, now + 1500);
            }
        }
    }

    private void drainFinished() {
        Long k;
        while ((k = finished.poll()) != null) {
            inflight.remove((long) k);
        }
    }

    private void select(double cx, double cz, int rd) {
        selRd = rd;
        var zc = MetalRenderClient.getConfig();
        zoomK = zc != null && zc.farZoomSaver ? 1.0 : 2.0;
        LongOpenHashSet next = new LongOpenHashSet(desired.size() + 64);
        refined.clear();
        if (zoomedView) {
            markRefined(live);
            markRefined(built);
            markRefined(uploaded);
        }
        lastSelectFrame = frame;
        selectSoon = false;
        int top = FarKeys.MAX_LEVEL;
        int s = FarKeys.size(top);
        float r = farRadius;
        int tx0 = (int) Math.floor((cx - r) / s);
        int tx1 = (int) Math.floor((cx + r) / s);
        int tz0 = (int) Math.floor((cz - r) / s);
        int tz1 = (int) Math.floor((cz + r) / s);
        for (int tz = tz0; tz <= tz1; tz++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                visit(FarKeys.key(top, tx, tz), cx, cz, r, next);
            }
        }
        desired.clear();
        desired.addAll(next);
        desiredOrder.clear();
        swapDirty = true;
        desiredAnc.clear();
        var dit = desired.iterator();
        while (dit.hasNext()) {
            long k = dit.nextLong();
            for (int lv = FarKeys.level(k) + 1; lv <= FarKeys.MAX_LEVEL; lv++) {
                if (!desiredAnc.add(FarKeys.ancestorAt(k, lv))) {
                    break;
                }
            }
        }
        scaffold.removeIf(k -> !desiredAnc.contains(k));
        uncoveredSince.keySet().removeIf(k -> !desired.contains(k));
        long[] keys = next.toLongArray();
        long[] order = new long[keys.length];
        for (int i = 0; i < keys.length; i++) {
            order[i] = ((long) Float.floatToIntBits(priority(keys[i], cx, cz)) << 32) | i;
        }
        java.util.Arrays.sort(order);
        desiredOrder.ensureCapacity(keys.length);
        for (long o : order) {
            desiredOrder.add(keys[(int) o]);
        }
        requestCursor = 0;
        LongArrayList stale = new LongArrayList();
        var bit = built.iterator();
        while (bit.hasNext()) {
            long k = bit.nextLong();
            if (!desired.contains(k) && !live.contains(k) && !scaffold.contains(k)) {
                stale.add(k);
                bit.remove();
            }
        }
        var uit = uploaded.iterator();
        while (uit.hasNext()) {
            long k = uit.nextLong();
            if (!desired.contains(k) && !live.contains(k) && !scaffold.contains(k)) {
                stale.add(k);
                uit.remove();
            }
        }
        if (!stale.isEmpty()) {
            long[] h = stale.toLongArray();
            NativeBridge.nFarCommit(new long[0], 0, h, h.length);
        }
    }

    private float priority(long key, double cx, double cz) {
        int sz = FarKeys.size(FarKeys.level(key));
        double d = tileDistance(key, cx, cz) + sz * 0.25;
        double mx = FarKeys.originX(key) + sz * 0.5 - cx;
        double mz = FarKeys.originZ(key) + sz * 0.5 - cz;
        double ml = Math.sqrt(mx * mx + mz * mz);
        if (ml > sz) {
            if (aimed(mx, mz, ml, sz)) {
                d = zoomedView ? d * 0.02 : d * 0.5;
            } else {
                d = d * 3.0 + 256.0;
            }
        }
        return (float) d;
    }

    private boolean inView(long key, double cx, double cz) {
        int sz = FarKeys.size(FarKeys.level(key));
        double mx = FarKeys.originX(key) + sz * 0.5 - cx;
        double mz = FarKeys.originZ(key) + sz * 0.5 - cz;
        double ml = Math.sqrt(mx * mx + mz * mz);
        if (ml <= sz) {
            return true;
        }
        return aimed(mx, mz, ml, sz);
    }

    private boolean aimed(double mx, double mz, double ml, int sz) {
        double reach = sz * 0.75;
        double hyp = Math.sqrt(ml * ml + reach * reach);
        double cr = ml / hyp;
        double sr = reach / hyp;
        if (viewCos <= -cr) {
            return true;
        }
        return (mx * viewX + mz * viewZ) / ml >= viewCos * cr - viewSin * sr;
    }

    private void markRefined(LongOpenHashSet set) {
        var it = set.iterator();
        while (it.hasNext()) {
            long k = it.nextLong();
            refined.add(k);
            for (int lv = FarKeys.level(k) + 1; lv <= FarKeys.MAX_LEVEL; lv++) {
                if (!refined.add(FarKeys.ancestorAt(k, lv))) {
                    break;
                }
            }
        }
    }

    private static double tileDistance(long key, double cx, double cz) {
        int s = FarKeys.size(FarKeys.level(key));
        double x0 = FarKeys.originX(key);
        double z0 = FarKeys.originZ(key);
        double nx = Math.max(x0, Math.min(cx, x0 + s));
        double nz = Math.max(z0, Math.min(cz, z0 + s));
        double ddx = nx - cx;
        double ddz = nz - cz;
        return Math.sqrt(ddx * ddx + ddz * ddz);
    }

    private static double farthestCorner(long key, double cx, double cz) {
        int sz = FarKeys.size(FarKeys.level(key));
        double x0 = FarKeys.originX(key) - cx;
        double z0 = FarKeys.originZ(key) - cz;
        double fx = Math.max(Math.abs(x0), Math.abs(x0 + sz));
        double fz = Math.max(Math.abs(z0), Math.abs(z0 + sz));
        return Math.sqrt(fx * fx + fz * fz);
    }

    private boolean underNear(long key) {
        int sz = FarKeys.size(FarKeys.level(key));
        int c0x = FarKeys.originX(key) >> 4;
        int c0z = FarKeys.originZ(key) >> 4;
        int n = Math.max(1, sz >> 4);
        if (n > 8) {
            return false;
        }
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                if (!nearMask.isReady(c0x + x, c0z + z)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int buildOpts(MetalRenderConfig cfg) {
        return (cfg.farCompactQuads ? 1 : 0) | (cfg.farWaterPlane ? 2 : 0) | (cfg.farBiomeBlend ? 8 : 0);
    }

    private double roughFactor(long key) {
        if (!roughLod) {
            return 1.0;
        }
        int lv = FarKeys.level(key);
        for (int l = lv; l <= FarKeys.MAX_LEVEL; l++) {
            long k = l == lv ? key : FarKeys.ancestorAt(key, l);
            if (roughness.containsKey(k)) {
                int r = roughness.get(k);
                if (r <= 2) {
                    return 0.6;
                }
                if (r >= 48) {
                    return 1.35;
                }
                return 1.0;
            }
        }
        return 1.0;
    }

    private void visit(long key, double cx, double cz, float r, LongOpenHashSet out) {
        double d = tileDistance(key, cx, cz);
        if (d > r) {
            return;
        }
        if (FarKeys.level(key) <= 2 && selRd > 4 && farthestCorner(key, cx, cz) < (selRd - 3) * 16.0
                && underNear(key) && !live.contains(key)) {
            return;
        }
        int lv = FarKeys.level(key);
        if (lv > 0) {
            if (lv == 1 || lv > 4) {
                for (int i = 0; i < 4; i++) {
                    visit(FarKeys.child(key, i), cx, cz, r, out);
                }
                return;
            }
            int tl = lv == 2 ? 1 : lv;
            boolean boost = zoomedView && inView(key, cx, cz);
            double threshold = (boost ? zoomK * pixelScale : Math.min((double) quality, 2.0 * basePixelScale))
                    * (1L << (tl - 1));
            boolean wasSplit = !desired.contains(key);
            double rf = roughFactor(key);
            double t = (wasSplit ? threshold * 1.08 : threshold * 0.92) * rf;
            double base = Math.min((double) quality, 2.0 * basePixelScale) * (1L << (tl - 1))
                    * (wasSplit ? 1.08 : 0.92) * rf;
            boolean stepOk = refined.contains(key)
                    || (lv < FarKeys.MAX_LEVEL && refined.contains(FarKeys.ancestorAt(key, lv + 1)));
            if (d < t && (!boost || d < base || stepOk)) {
                for (int i = 0; i < 4; i++) {
                    visit(FarKeys.child(key, i), cx, cz, r, out);
                }
                return;
            }
        }
        out.add(key);
    }

    private void swap() {
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<LongArrayList> liveUnder = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        var lit = live.iterator();
        while (lit.hasNext()) {
            long v = lit.nextLong();
            for (int lv = FarKeys.level(v) + 1; lv <= FarKeys.MAX_LEVEL; lv++) {
                long a = FarKeys.ancestorAt(v, lv);
                LongArrayList l = liveUnder.get(a);
                if (l == null) {
                    l = new LongArrayList(4);
                    liveUnder.put(a, l);
                }
                l.add(v);
            }
        }
        LongArrayList show = new LongArrayList();
        LongArrayList hide = new LongArrayList();
        LongOpenHashSet handled = new LongOpenHashSet();
        LongArrayList liveList = new LongArrayList(live);
        for (int li = 0; li < liveList.size(); li++) {
            long v = liveList.getLong(li);
            if (desired.contains(v) || handled.contains(v)) {
                continue;
            }
            long ancestor = -1;
            for (int lv = FarKeys.level(v) + 1; lv <= FarKeys.MAX_LEVEL; lv++) {
                long a = FarKeys.ancestorAt(v, lv);
                if (desired.contains(a)) {
                    ancestor = a;
                    break;
                }
            }
            if (ancestor != -1) {
                if (built.contains(ancestor) || live.contains(ancestor)) {
                    if (!live.contains(ancestor) && !handled.contains(ancestor)) {
                        show.add(ancestor);
                        handled.add(ancestor);
                    }
                    LongArrayList under = liveUnder.get(ancestor);
                    if (under != null) {
                        for (int j = 0; j < under.size(); j++) {
                            long o = under.getLong(j);
                            if (!handled.contains(o)) {
                                hide.add(o);
                                handled.add(o);
                            }
                        }
                    }
                }
                continue;
            }
            if (!desiredAnc.contains(v)) {
                hide.add(v);
                handled.add(v);
                continue;
            }
            LongArrayList kids = new LongArrayList(4);
            boolean ready = true;
            for (int i = 0; i < 4; i++) {
                long c = FarKeys.child(v, i);
                if (!desired.contains(c) && !desiredAnc.contains(c)) {
                    continue;
                }
                kids.add(c);
                if (!built.contains(c) && !live.contains(c)) {
                    ready = false;
                    if (!desired.contains(c)) {
                        scaffold.add(c);
                    }
                }
            }
            if (ready) {
                for (int i = 0; i < kids.size(); i++) {
                    long c = kids.getLong(i);
                    if (!live.contains(c) && !handled.contains(c)) {
                        show.add(c);
                        handled.add(c);
                    }
                }
                hide.add(v);
                handled.add(v);
            }
        }
        LongOpenHashSet roots = new LongOpenHashSet();
        var dit = desired.iterator();
        while (dit.hasNext()) {
            long d = dit.nextLong();
            roots.add(FarKeys.ancestorAt(d, Math.min(5, FarKeys.level(d) + 2)));
        }
        roots.removeIf(r -> {
            for (int lv = FarKeys.level(r) + 1; lv <= FarKeys.MAX_LEVEL; lv++) {
                if (roots.contains(FarKeys.ancestorAt(r, lv))) {
                    return true;
                }
            }
            return false;
        });
        rootKeys = roots;
        var rit = roots.iterator();
        while (rit.hasNext()) {
            long r = rit.nextLong();
            if (live.contains(r) || liveUnder.containsKey(r) || handled.contains(r) || liveAncestor(r)) {
                continue;
            }
            if (built.contains(r)) {
                show.add(r);
                handled.add(r);
            } else if (!desired.contains(r)) {
                scaffold.add(r);
            }
        }
        var it = built.iterator();
        while (it.hasNext()) {
            long b = it.nextLong();
            if (handled.contains(b) || live.contains(b) || !desired.contains(b) || !roots.contains(b)) {
                continue;
            }
            if (liveUnder.containsKey(b) || liveAncestor(b)) {
                continue;
            }
            show.add(b);
            handled.add(b);
        }
        long nowMs = System.currentTimeMillis();
        if (inflight.isEmpty() && uploaded.isEmpty() && uploadQueue.isEmpty()) {
            if (idleSince == 0L) {
                idleSince = nowMs;
            }
        } else {
            idleSince = 0L;
        }
        boolean quiet = idleSince != 0L && nowMs - idleSince > 3000L;
        var cit = desired.iterator();
        while (cit.hasNext()) {
            long k = cit.nextLong();
            if (live.contains(k) || liveUnder.containsKey(k) || liveAncestor(k) || handled.contains(k)) {
                uncoveredSince.remove(k);
                continue;
            }
            long since = uncoveredSince.get(k);
            if (since == 0L) {
                uncoveredSince.put(k, nowMs);
                continue;
            }
            long age = nowMs - since;
            if (!(age > 45000L || (quiet && age > 4000L))) {
                continue;
            }
            if (built.contains(k)) {
                show.add(k);
                handled.add(k);
            } else if (age > 60000L && (inflight.remove(k) | uploaded.remove(k))) {
                uncoveredSince.put(k, nowMs);
            } else if (inflight.size() < 48 && !inflight.contains(k) && !uploaded.contains(k)) {
                submitBuild(k, 0.0);
            }
        }
        if (show.isEmpty() && hide.isEmpty()) {
            return;
        }
        long[] s = show.toLongArray();
        long[] h = hide.toLongArray();
        if (s.length > 0 && zoomedView) {
            selectSoon = true;
        }
        NativeBridge.nFarCommit(s, s.length, h, h.length);
        for (long k : s) {
            live.add(k);
            built.remove(k);
            scaffold.remove(k);
        }
        for (long k : h) {
            live.remove(k);
            built.remove(k);
            uploaded.remove(k);
            missingMask.remove(k);
        }
    }

    private boolean liveAncestor(long key) {
        for (int lv = FarKeys.level(key) + 1; lv <= FarKeys.MAX_LEVEL; lv++) {
            if (live.contains(FarKeys.ancestorAt(key, lv))) {
                return true;
            }
        }
        return false;
    }

    private void collectDesiredUnder(long key, LongArrayList out) {
        if (FarKeys.level(key) == 0) {
            return;
        }
        for (int i = 0; i < 4; i++) {
            long c = FarKeys.child(key, i);
            if (desired.contains(c)) {
                out.add(c);
            } else if (Double.isNaN(lastSelX) || tileDistance(c, lastSelX, lastSelZ) <= farRadius) {
                collectDesiredUnder(c, out);
            }
        }
    }

    private void request(double cx, double cz) {
        ThreadPoolExecutor p = pool;
        if (p == null) {
            return;
        }
        int cap = p.getMaximumPoolSize() * MAX_INFLIGHT_PER_THREAD;
        com.pebbles_boon.metalrender.render.MetalWorldRenderer wr = com.pebbles_boon.metalrender.render.MetalWorldRenderer
                .getInstance();
        CustomChunkMesher mesher = wr != null ? wr.getChunkMesher() : null;
        int backlog = mesher != null ? mesher.getPendingCount() : 0;
        if (backlog > 64) {
            cap = Math.max(2, cap / 3);
        } else if (backlog > 16) {
            cap = Math.max(2, cap * 2 / 3);
        }
        int refreshCap = Math.max(1, cap / 3);
        int refreshing = 0;
        long now = System.currentTimeMillis();
        if (!refreshAt.isEmpty()) {
            var it = refreshAt.long2LongEntrySet().fastIterator();
            while (it.hasNext() && inflight.size() < cap && refreshing < refreshCap) {
                var e = it.next();
                if (e.getLongValue() > now) {
                    continue;
                }
                long key = e.getLongKey();
                if (inflight.contains(key)) {
                    continue;
                }
                it.remove();
                if (desired.contains(key) || live.contains(key)) {
                    submitBuild(key, tileDistance(key, cx, cz) + 256.0);
                    refreshing++;
                }
            }
        }
        if (inflight.size() >= cap) {
            return;
        }
        LongArrayList sc = new LongArrayList();
        LongArrayList rc = new LongArrayList();
        for (long key : scaffold) {
            if (!live.contains(key) && !built.contains(key) && !inflight.contains(key) && !uploaded.contains(key)) {
                (rootKeys.contains(key) ? rc : sc).add(key);
            }
        }
        for (long key : rootKeys) {
            if (desired.contains(key) && !live.contains(key) && !built.contains(key) && !inflight.contains(key)
                    && !uploaded.contains(key)) {
                rc.add(key);
            }
        }
        long[] order = sortedByPriority(sc, cx, cz);
        long[] rorder = sortedByPriority(rc, cx, cz);
        int si = 0;
        int ri = 0;
        int scanned = 0;
        long nextKey = 0;
        float nextPri = Float.MAX_VALUE;
        boolean hasNext = false;
        boolean coverTurn = true;
        while (inflight.size() < cap) {
            while (!hasNext && requestCursor < desiredOrder.size() && scanned < 1024) {
                long key = desiredOrder.getLong(requestCursor++);
                scanned++;
                if (live.contains(key) || built.contains(key) || inflight.contains(key) || uploaded.contains(key)
                        || rootKeys.contains(key)) {
                    continue;
                }
                nextKey = key;
                nextPri = priority(key, cx, cz);
                hasNext = true;
            }
            boolean refineLeft = si < order.length || hasNext;
            if (ri < rorder.length && (coverTurn || !refineLeft)) {
                long key = rc.getLong((int) rorder[ri++]);
                submitBuild(key, Float.intBitsToFloat((int) (rorder[ri - 1] >>> 32)) * 0.05);
                coverTurn = false;
                continue;
            }
            coverTurn = true;
            float sp = si < order.length ? Float.intBitsToFloat((int) (order[si] >>> 32)) : Float.MAX_VALUE;
            if (si < order.length && (!hasNext || sp <= nextPri)) {
                submitBuild(sc.getLong((int) order[si]), sp);
                si++;
            } else if (hasNext) {
                submitBuild(nextKey, nextPri);
                hasNext = false;
            } else if (ri >= rorder.length) {
                break;
            }
        }
        if (hasNext) {
            requestCursor--;
        }
        if (requestCursor >= desiredOrder.size() && frame % 20 == 0) {
            requestCursor = 0;
        }
    }

    private long[] sortedByPriority(LongArrayList keys, double cx, double cz) {
        long[] order = new long[keys.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = ((long) Float.floatToIntBits(priority(keys.getLong(i), cx, cz)) << 32) | i;
        }
        java.util.Arrays.sort(order);
        return order;
    }

    private void submitBuild(long key, double priority) {
        ThreadPoolExecutor p = pool;
        FarStore st = store;
        FarLooks lk = looks;
        FarSeedSource sd = seed;
        if (p == null || st == null || lk == null) {
            return;
        }
        inflight.add(key);
        int gen = generation;
        int my = minY;
        p.execute(new Job(priority) {
            @Override
            public void run() {
                try {
                    if (gen != generation) {
                        return;
                    }
                    FarTileData tile = st.get(key, true);
                    if (sd != null && tile.hasMissing()) {
                        SeedColumns.fill(tile, sd, lk);
                        st.markDirty(tile);
                        seeded.add(FarKeys.neighbor(key, -1, 0));
                        seeded.add(FarKeys.neighbor(key, 1, 0));
                        seeded.add(FarKeys.neighbor(key, 0, -1));
                        seeded.add(FarKeys.neighbor(key, 0, 1));
                    }
                    FarTileData[] n = { st.get(FarKeys.neighbor(key, -1, 0), false),
                            st.get(FarKeys.neighbor(key, 1, 0), false), st.get(FarKeys.neighbor(key, 0, -1), false),
                            st.get(FarKeys.neighbor(key, 0, 1), false) };
                    FarTileBuilder.Blob blob = FarTileBuilder.build(tile, n, lk, my, optCompact, optPlane);
                    results.add(new Built(key, tile.version, blob, gen));
                } catch (Throwable t) {
                    MetalLogger.warn("far build failed: %s", t.toString());
                    finished.add(key);
                }
            }
        });
    }

    private void upload() {
        Built b;
        while ((b = results.poll()) != null) {
            if (b.generation() != generation) {
                continue;
            }
            uploadQueue.add(b);
        }
        if (uploadQueue.isEmpty()) {
            return;
        }
        long bytes = 0;
        int count = 0;
        int write = 0;
        long deadline = System.nanoTime() + 1_500_000L;
        for (int i = 0; i < uploadQueue.size(); i++) {
            Built q = uploadQueue.get(i);
            boolean wanted = desired.contains(q.key()) || live.contains(q.key()) || scaffold.contains(q.key());
            if (!wanted) {
                inflight.remove(q.key());
                continue;
            }
            if (count >= UPLOADS_PER_FRAME || bytes >= UPLOAD_BYTES_PER_FRAME
                    || (count > 0 && System.nanoTime() > deadline)) {
                uploadQueue.set(write++, q);
                continue;
            }
            FarTileBuilder.Blob blob = q.blob();
            int lv = FarKeys.level(q.key());
            int r = NativeBridge.nFarUploadTile(q.key(), lv, FarKeys.originX(q.key()), FarKeys.originZ(q.key()), minY,
                    blob.yLo, blob.yHi, blob.blob, blob.blobUints, blob.tints, blob.tintCount, blob.quadBound,
                    blob.flags, blob.lookPal, blob.plane);
            if (roughness.size() > 262144) {
                roughness.clear();
            }
            roughness.put(q.key(), blob.roughness);
            if (r == -1) {
                long[] st = new long[12];
                NativeBridge.nFarStats(st);
                boolean quadsFull = st[4] > 0 && st[3] * 10 > st[4] * 9;
                if (!relieve() && quadsFull) {
                    radiusScale = Math.max(0.3f, radiusScale * 0.92f);
                    lastSelX = Double.NaN;
                    MetalLogger.warn("far terrain GPU budget full, shrinking reach to %.0f%%", radiusScale * 100);
                }
                uploadQueue.set(write++, q);
                break;
            }
            inflight.remove(q.key());
            if (r >= 0) {
                if (!live.contains(q.key())) {
                    uploaded.add(q.key());
                }
                missingMask.put(q.key(), blob.missingNeighbors);
            }
            bytes += (long) blob.blobUints * 4;
            count++;
        }
        for (int i = uploadQueue.size() - 1; i >= write; i--) {
            uploadQueue.remove(i);
        }
    }

    private boolean relieve() {
        LongArrayList drop = new LongArrayList();
        var it = live.iterator();
        while (it.hasNext()) {
            long k = it.nextLong();
            if (!desired.contains(k)) {
                boolean covered = false;
                for (int lv = FarKeys.level(k) + 1; lv <= FarKeys.MAX_LEVEL && !covered; lv++) {
                    covered = live.contains(FarKeys.ancestorAt(k, lv));
                }
                if (covered) {
                    drop.add(k);
                }
            }
        }
        if (drop.isEmpty()) {
            return false;
        }
        long[] h = drop.toLongArray();
        NativeBridge.nFarCommit(new long[0], 0, h, h.length);
        for (long k : h) {
            live.remove(k);
            uploaded.remove(k);
        }
        return true;
    }

    public long[] stats() {
        if (!nativeReady) {
            return stats;
        }
        NativeBridge.nFarStats(stats);
        return stats;
    }

    public String describe() {
        if (!active()) {
            return "far: off";
        }
        long[] s = stats();
        FarStore st = store;
        return String.format("far: %d tiles (%d pending), %d drawn, %.1fM quads, gpu %d/%d MB, ram %d MB, disk %d MB",
                s[0], s[1], s[8], s[9] / 1.0e6, s[3] >> 20, s[4] >> 20, st != null ? st.memoryBytes() >> 20 : 0,
                st != null ? st.diskBytes() >> 20 : 0);
    }

    public static boolean columnReady(ClientLevel lvl, CustomChunkMesher mesher, int cx, int cz, long loadedFor) {
        LevelChunk chunk = lvl.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null || mesher == null) {
            return false;
        }
        LevelChunkSection[] sections = chunk.getSections();
        int minSec = chunk.getMinSectionY();
        int missing = 0;
        int topMissing = -1;
        int topSection = -1;
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection s = sections[i];
            if (s == null || s.hasOnlyAir()) {
                continue;
            }
            topSection = i;
            if (!mesher.hasMeshIgnoreDirty(cx, minSec + i, cz)) {
                missing++;
                topMissing = i;
            }
        }
        if (topSection < 0) {
            return loadedFor > 1500;
        }
        if (missing == 0) {
            return true;
        }
        return loadedFor > 4000 && topMissing < topSection - 1;
    }

    public List<String> debugLines() {
        List<String> out = new ArrayList<>();
        out.add(describe());
        return out;
    }
}
