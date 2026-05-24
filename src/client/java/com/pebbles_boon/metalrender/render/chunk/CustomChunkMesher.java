package com.pebbles_boon.metalrender.render.chunk;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.nativebridge.NativeMemory;
import com.pebbles_boon.metalrender.util.MetalLogger;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.joml.Vector3fc;

public class CustomChunkMesher {
  private static final int VERTEX_STRIDE = 16;
  private static final int SECTION_SIZE = 16;
  private static final int MAX_QUADS = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE * 6;
  private static final int VERTEX_BUF_SIZE = MAX_QUADS * 4 * VERTEX_STRIDE;
  private static final byte WATER_ALPHA = (byte) 168;
  private static final ThreadLocal<ByteBuffer> VERTEX_BUF_POOL = ThreadLocal
      .withInitial(() -> ByteBuffer.allocateDirect(VERTEX_BUF_SIZE)
          .order(ByteOrder.nativeOrder()));
  private static final ThreadLocal<ByteBuffer> WATER_BUF_POOL = ThreadLocal
      .withInitial(() -> ByteBuffer.allocateDirect(VERTEX_BUF_SIZE)
          .order(ByteOrder.nativeOrder()));
  private final Long2ObjectOpenHashMap<ChunkMeshData> meshCache;
  private final Long2ObjectOpenHashMap<FarFieldDigest> farFieldDigestCache;
  private final java.util.ArrayDeque<UploadJob> uploadQueue = new java.util.ArrayDeque<>();
  private final LongOpenHashSet approxLightKeys = new LongOpenHashSet();
  private final LongOpenHashSet queuedLightFixKeys = new LongOpenHashSet();
  private final java.util.ArrayDeque<Long> lightFixQueue = new java.util.ArrayDeque<>();

  private final java.util.ArrayList<ChunkMeshData> cachedMeshSnapshot = new java.util.ArrayList<>(8192);
  private volatile int cachedSnapshotGen = Integer.MIN_VALUE;
  private final LongOpenHashSet pendingKeys = new LongOpenHashSet();
  private final LongOpenHashSet dirtyKeys = new LongOpenHashSet();

  private final LongOpenHashSet emptyKeys = new LongOpenHashSet();
  private final Long2LongOpenHashMap dirtyGeneration = new Long2LongOpenHashMap();
  private final Long2LongOpenHashMap pendingVisibleSectionNanos = new Long2LongOpenHashMap();
  private final Long2LongOpenHashMap pendingBlockUpdateNanos = new Long2LongOpenHashMap();
  private long deviceHandle;
  private boolean initialized;
  private long globalIndexBufferHandle;
  private java.util.concurrent.ThreadPoolExecutor builderPool;
  private final int boostedBuilderThreadCount;
  private final int steadyBuilderThreadCount;
  private final int maxBuilderThreadCount;
  private final int steadyInstantThreadCount;
  private final int maxInstantThreadCount;
  private ExecutorService dirtyRebuildPool;

  private java.util.concurrent.ThreadPoolExecutor instantRebuildPool;
  private java.util.concurrent.ThreadPoolExecutor interactiveRebuildPool;
  private java.util.concurrent.ThreadPoolExecutor lightRebuildPool;

  private static final int FALLBACK_UPLOAD_PARALLELISM = 6;
  private static final int FAST_UPLOAD_PARALLELISM = 8;
  private static final int MAX_LIGHT_FIX_QUEUE = 4096;
  private volatile boolean fastUploadPathActive;
  private long uploadQueueBytes;
  private static final int THREAD_QOS_UTILITY = 0;
  private static final int THREAD_QOS_USER_INITIATED = 1;
  private static final int THREAD_QOS_USER_INTERACTIVE = 2;
  private static final int NORMAL_TOTAL_THREAD_BUDGET = 64;
  private static final int BURST_TOTAL_THREAD_BUDGET = 128;
  private static final int BURST_MAX_BUILDER_THREADS = 28;
  private static final int BURST_MAX_INSTANT_THREADS = 12;
  private static final int STARTUP_SOLID_FILL_MESH_THRESHOLD = 1024;
  private static final int POST_STARTUP_BUILDER_THREAD_CAP_EXTRA = 3;
  private static final int POST_STARTUP_INSTANT_THREAD_CAP_EXTRA = 1;
  private static final int HIGH_PRIORITY_QUEUE_SPILLOVER_THRESHOLD = 24;
  private static final int INTERACTIVE_PRIORITY_QUEUE_SPILLOVER_THRESHOLD = 48;
  private static final int DETAIL_TIER_SCALE_MEDIUM = 2;
  private static final int DETAIL_TIER_SCALE_FAR = 4;
  private static final int DETAIL_TIER_SCALE_EXTREME = 8;
  private static final int MAX_TRACKED_VISIBLE_SECTIONS = 8192;
  private static final int MAX_TRACKED_BLOCK_UPDATES = 4096;
  private volatile boolean aggressiveApproximateLighting;
  private final java.util.concurrent.atomic.AtomicLong visibleSectionLatencyAccNs = new java.util.concurrent.atomic.AtomicLong(
      0L);
  private final java.util.concurrent.atomic.AtomicInteger visibleSectionLatencySamples = new java.util.concurrent.atomic.AtomicInteger(
      0);
  private final java.util.concurrent.atomic.AtomicLong blockUpdateLatencyAccNs = new java.util.concurrent.atomic.AtomicLong(
      0L);
  private final java.util.concurrent.atomic.AtomicInteger blockUpdateLatencySamples = new java.util.concurrent.atomic.AtomicInteger(
      0);

  private static final byte[] FULL_CUBE_CACHE = new byte[32768];

  private static final CachedUVData[] UV_CACHE = new CachedUVData[32768];

  private static class CachedUVData {
    final short[] uMin = new short[6];
    final short[] uMax = new short[6];
    final short[] vMin = new short[6];
    final short[] vMax = new short[6];
    final boolean[] hasSprite = new boolean[6];
    final boolean[] hasTint = new boolean[6];
  }

  private static final int[][] AO_BILINEAR = {
      { 1, 0, 2, 3 },
      { 0, 1, 3, 2 },
      { 2, 3, 1, 0 },
      { 1, 0, 2, 3 },
      { 1, 0, 2, 3 },
      { 2, 3, 1, 0 },
  };

  private static void applyThreadQoS(int qosClass) {
    if (!NativeBridge.isLibLoaded()) {
      return;
    }
    try {
      NativeBridge.nSetCurrentThreadQoS(qosClass);
    } catch (Throwable ignored) {
    }
  }

  private static ThreadFactory createWorkerFactory(String namePrefix, int threadPriority, int qosClass) {
    AtomicInteger counter = new AtomicInteger();
    return runnable -> {
      Runnable wrapped = () -> {
        applyThreadQoS(qosClass);
        runnable.run();
      };
      Thread thread = new Thread(wrapped, namePrefix + "-" + counter.incrementAndGet());
      thread.setDaemon(true);
      thread.setPriority(threadPriority);
      return thread;
    };
  }

  private static float bilinearAO(float[] ao, int face, float localX, float localY, float localZ) {
    float u, v;
    switch (face) {
      case 0:
      case 1:
        u = localX;
        v = localZ;
        break;
      case 2:
      case 3:
        u = localX;
        v = localY;
        break;
      case 4:
      case 5:
        u = localZ;
        v = localY;
        break;
      default:
        return 1.0f;
    }
    u = Math.max(0f, Math.min(1f, u));
    v = Math.max(0f, Math.min(1f, v));
    int[] m = AO_BILINEAR[face];
    return ao[m[0]] * (1 - u) * (1 - v) + ao[m[1]] * (1 - u) * v + ao[m[2]] * u * (1 - v) + ao[m[3]] * u * v;
  }

  private static byte bilinearLight(byte[] vLight, int face, float localX, float localY, float localZ) {
    float u, v;
    switch (face) {
      case 0:
      case 1:
        u = localX;
        v = localZ;
        break;
      case 2:
      case 3:
        u = localX;
        v = localY;
        break;
      case 4:
      case 5:
        u = localZ;
        v = localY;
        break;
      default:
        return vLight[0];
    }
    u = Math.max(0f, Math.min(1f, u));
    v = Math.max(0f, Math.min(1f, v));
    int[] m = AO_BILINEAR[face];
    int l0 = vLight[m[0]] & 0xFF, l1 = vLight[m[1]] & 0xFF, l2 = vLight[m[2]] & 0xFF, l3 = vLight[m[3]] & 0xFF;
    int bl = (int) ((l0 & 0xF) * (1 - u) * (1 - v) + (l1 & 0xF) * (1 - u) * v + (l2 & 0xF) * u * (1 - v)
        + (l3 & 0xF) * u * v + 0.5f);
    int sl = (int) (((l0 >> 4) & 0xF) * (1 - u) * (1 - v) + ((l1 >> 4) & 0xF) * (1 - u) * v
        + ((l2 >> 4) & 0xF) * u * (1 - v) + ((l3 >> 4) & 0xF) * u * v + 0.5f);
    return (byte) ((bl & 0xF) | ((sl & 0xF) << 4));
  }

  private static boolean isFullCubeShape(BlockState bs) {
    int id = Block.getId(bs);
    if (id >= 0 && id < FULL_CUBE_CACHE.length) {
      byte cached = FULL_CUBE_CACHE[id];
      if (cached != 0)
        return cached == 2;
    }
    boolean result;
    try {
      result = Block.isShapeFullBlock(bs.getShape(
          net.minecraft.world.level.EmptyBlockGetter.INSTANCE,
          BlockPos.ZERO));
    } catch (Exception e) {
      result = bs.isSolidRender();
    }
    if (id >= 0 && id < FULL_CUBE_CACHE.length)
      FULL_CUBE_CACHE[id] = result ? (byte) 2 : (byte) 1;
    return result;
  }

  public static class ChunkMeshData {
    public final long bufferHandle;
    public final int quadCount;
    public final int chunkX;
    public final int chunkY;
    public final int chunkZ;
    public final int lodLevel;

    public final int buildPlayerCX, buildPlayerCY, buildPlayerCZ;

    public ChunkMeshData(long bufferHandle, int quadCount, int chunkX,
        int chunkY, int chunkZ, int lodLevel,
        int buildPlayerCX, int buildPlayerCY, int buildPlayerCZ) {
      this.bufferHandle = bufferHandle;
      this.quadCount = quadCount;
      this.chunkX = chunkX;
      this.chunkY = chunkY;
      this.chunkZ = chunkZ;
      this.lodLevel = lodLevel;
      this.buildPlayerCX = buildPlayerCX;
      this.buildPlayerCY = buildPlayerCY;
      this.buildPlayerCZ = buildPlayerCZ;
    }
  }

  public static final class UploadStat {
    public final int jobs;
    public final long bytes;

    public UploadStat(int jobs, long bytes) {
      this.jobs = jobs;
      this.bytes = bytes;
    }
  }

  private static final class UploadJob {
    final long key;
    final long gen;
    final boolean approxLight;
    final int chunkX;
    final int chunkY;
    final int chunkZ;
    final int lodLevel;
    final int quadCount;
    final int opaqueQuadCount;
    final int buildPlayerCX;
    final int buildPlayerCY;
    final int buildPlayerCZ;
    final byte[] data;
    final FarFieldDigest digest;

    UploadJob(long key, long gen, boolean approxLight,
        int chunkX, int chunkY, int chunkZ,
        int lodLevel, int quadCount, int opaqueQuadCount,
        int buildPlayerCX, int buildPlayerCY, int buildPlayerCZ,
        byte[] data, FarFieldDigest digest) {
      this.key = key;
      this.gen = gen;
      this.approxLight = approxLight;
      this.chunkX = chunkX;
      this.chunkY = chunkY;
      this.chunkZ = chunkZ;
      this.lodLevel = lodLevel;
      this.quadCount = quadCount;
      this.opaqueQuadCount = opaqueQuadCount;
      this.buildPlayerCX = buildPlayerCX;
      this.buildPlayerCY = buildPlayerCY;
      this.buildPlayerCZ = buildPlayerCZ;
      this.data = data;
      this.digest = digest;
    }

    int bytes() {
      return data != null ? data.length : 0;
    }
  }

  private static final UploadStat EMPTY_UPLOAD_STAT = new UploadStat(0, 0L);

  public static final class FarFieldDigest {
    public final int chunkX;
    public final int chunkY;
    public final int chunkZ;
    public final long versionStamp;
    public final int solidBlockCount;
    public final int exposedBlockCount;
    public final int dominantStateId;
    public final int dominantStateCount;
    public final int minSolidY;
    public final int maxSolidY;
    public final int maxBlockLight;
    public final int maxSkyLight;
    public final byte[] columnBottomHeights;
    public final byte[] columnTopHeights;

    public FarFieldDigest(int chunkX, int chunkY, int chunkZ, long versionStamp,
        int solidBlockCount, int exposedBlockCount,
        int dominantStateId, int dominantStateCount,
        int minSolidY, int maxSolidY,
        int maxBlockLight, int maxSkyLight,
        byte[] columnBottomHeights, byte[] columnTopHeights) {
      this.chunkX = chunkX;
      this.chunkY = chunkY;
      this.chunkZ = chunkZ;
      this.versionStamp = versionStamp;
      this.solidBlockCount = solidBlockCount;
      this.exposedBlockCount = exposedBlockCount;
      this.dominantStateId = dominantStateId;
      this.dominantStateCount = dominantStateCount;
      this.minSolidY = minSolidY;
      this.maxSolidY = maxSolidY;
      this.maxBlockLight = maxBlockLight;
      this.maxSkyLight = maxSkyLight;
      this.columnBottomHeights = columnBottomHeights;
      this.columnTopHeights = columnTopHeights;
    }
  }

  public CustomChunkMesher() {
    this.meshCache = new Long2ObjectOpenHashMap<>();
    this.farFieldDigestCache = new Long2ObjectOpenHashMap<>();
    this.dirtyGeneration.defaultReturnValue(0L);
    this.pendingVisibleSectionNanos.defaultReturnValue(0L);
    this.pendingBlockUpdateNanos.defaultReturnValue(0L);
    int processors = Runtime.getRuntime().availableProcessors();

    int reservedCores = processors >= 12 ? 2 : 1;
    int warmupThreads = Math.max(4, Math.min(12, processors - reservedCores));
    int steadyThreads = Math.max(3, Math.min(10, warmupThreads - 1));
    int maxBuilderThreads = Math.max(warmupThreads, Math.min(20, processors + 2));
    int steadyInstantThreads = processors >= 10 ? 4 : 3;
    int maxInstantThreads = processors >= 16 ? 8 : (processors >= 10 ? 6 : 4);
    int interactiveThreads = processors >= 12 ? 3 : 2;
    final int warmupThreadCount = warmupThreads;
    final int steadyThreadCount = steadyThreads;
    this.boostedBuilderThreadCount = warmupThreadCount;
    this.steadyBuilderThreadCount = steadyThreadCount;
    this.maxBuilderThreadCount = maxBuilderThreads;
    this.steadyInstantThreadCount = steadyInstantThreads;
    this.maxInstantThreadCount = Math.max(steadyInstantThreads, maxInstantThreads);
    ThreadFactory meshFactory = createWorkerFactory(
        "MetalRender-MeshBuilder",
        Thread.MIN_PRIORITY,
        THREAD_QOS_UTILITY);
    this.builderPool = new java.util.concurrent.ThreadPoolExecutor(
        warmupThreadCount, warmupThreadCount,
        10L, java.util.concurrent.TimeUnit.SECONDS,
        new java.util.concurrent.LinkedBlockingQueue<>(),
        meshFactory);
    this.builderPool.allowCoreThreadTimeOut(true);

    java.util.concurrent.ScheduledExecutorService warmupTimer = java.util.concurrent.Executors
        .newSingleThreadScheduledExecutor(r -> {
          ThreadFactory warmupFactory = createWorkerFactory(
              "MetalRender-WarmupTimer",
              Thread.MIN_PRIORITY,
              THREAD_QOS_UTILITY);
          return warmupFactory.newThread(r);
        });
    warmupTimer.schedule(() -> {
      if (getPendingCount() == 0) {
        updateThreadPoolSize(builderPool, steadyThreadCount);
      }
      warmupTimer.shutdown();
    }, 30, java.util.concurrent.TimeUnit.SECONDS);

    this.dirtyRebuildPool = this.builderPool;

    ThreadFactory instantFactory = createWorkerFactory(
        "MetalRender-InstantRebuild",
        Thread.NORM_PRIORITY,
        THREAD_QOS_USER_INITIATED);
    this.instantRebuildPool = new java.util.concurrent.ThreadPoolExecutor(
        steadyInstantThreads, steadyInstantThreads,
        1L, java.util.concurrent.TimeUnit.SECONDS,
        new java.util.concurrent.LinkedBlockingQueue<>(),
        instantFactory);
    this.instantRebuildPool.allowCoreThreadTimeOut(true);

    ThreadFactory interactiveFactory = createWorkerFactory(
        "MetalRender-InteractiveRebuild",
        Thread.MAX_PRIORITY,
        THREAD_QOS_USER_INTERACTIVE);
    this.interactiveRebuildPool = new java.util.concurrent.ThreadPoolExecutor(
        interactiveThreads, interactiveThreads,
        1L, java.util.concurrent.TimeUnit.SECONDS,
        new java.util.concurrent.LinkedBlockingQueue<>(),
        interactiveFactory);
    this.interactiveRebuildPool.allowCoreThreadTimeOut(true);

    int lightThreads = processors >= 12 ? 2 : 1;
    ThreadFactory lightFactory = createWorkerFactory(
        "MetalRender-LightFix",
        Thread.MIN_PRIORITY,
        THREAD_QOS_UTILITY);
    this.lightRebuildPool = new java.util.concurrent.ThreadPoolExecutor(
        lightThreads, lightThreads,
        1L, java.util.concurrent.TimeUnit.SECONDS,
        new java.util.concurrent.LinkedBlockingQueue<>(),
        lightFactory);
    this.lightRebuildPool.allowCoreThreadTimeOut(true);
  }

  public long getGlobalIndexBuffer() {
    return globalIndexBufferHandle;
  }

  public void initialize(long device) {
    this.deviceHandle = device;
    refreshUploadPathMode();
    int[] indices = new int[MAX_QUADS * 6];
    for (int i = 0; i < MAX_QUADS; i++) {
      indices[i * 6 + 0] = i * 4 + 0;
      indices[i * 6 + 1] = i * 4 + 1;
      indices[i * 6 + 2] = i * 4 + 2;
      indices[i * 6 + 3] = i * 4 + 0;
      indices[i * 6 + 4] = i * 4 + 2;
      indices[i * 6 + 5] = i * 4 + 3;
    }
    ByteBuffer ib = ByteBuffer.allocateDirect(indices.length * 4)
        .order(ByteOrder.nativeOrder());
    for (int idx : indices)
      ib.putInt(idx);
    ib.flip();
    byte[] ibData = new byte[ib.remaining()];
    ib.get(ibData);
    this.globalIndexBufferHandle = NativeBridge.nCreateBuffer(
        deviceHandle, ibData.length, NativeMemory.STORAGE_MODE_SHARED);
    NativeBridge.nUploadBufferData(this.globalIndexBufferHandle, ibData, 0,
        ibData.length);
    refreshUploadPathMode();
    this.initialized = true;
    MetalLogger.info("Chunk mesh uploads using %s path (%d concurrent)",
        fastUploadPathActive ? "mega-buffer" : "fallback",
        fastUploadPathActive ? FAST_UPLOAD_PARALLELISM : FALLBACK_UPLOAD_PARALLELISM);
    MetalLogger.info("CustomChunkMesher initialized (maxQuads=%d, ibSize=%d)",
        MAX_QUADS, ibData.length);
  }

  private void refreshUploadPathMode() {
    try {
      fastUploadPathActive = NativeBridge.isLibLoaded() && NativeBridge.nIsMegaBufferActive();
    } catch (Throwable ignored) {
      fastUploadPathActive = false;
    }
  }

  private static long packChunkKey(int x, int y, int z) {
    return ((long) (x & 0x3FFFFF) << 42) | ((long) (y & 0xFFFFF) << 22) |
        (z & 0x3FFFFF);
  }

  private static final byte OPACITY_OPAQUE = 0;
  private static final byte OPACITY_TRANSPARENT = 1;
  private static final byte OPACITY_LEAF = 2;

  private static final byte OPACITY_TRANS_CUBE = 3;

  private static byte computeOpacityFlag(int stateId) {
    if (stateId == 0)
      return OPACITY_TRANSPARENT;
    try {
      BlockState state = Block.stateById(stateId);
      if (state.isAir())
        return OPACITY_TRANSPARENT;
      if (isLeafBlock(state.getBlock()))
        return OPACITY_LEAF;
      if (!state.getFluidState().isEmpty()) {
        Block fluidBlk = state.getBlock();
        if (fluidBlk == Blocks.WATER)
          return OPACITY_TRANSPARENT;
        if (fluidBlk == Blocks.LAVA)
          return OPACITY_OPAQUE;
        return state.isSolidRender() ? OPACITY_OPAQUE : OPACITY_TRANSPARENT;
      }

      if (!state.isSolidRender() && isFullCubeShape(state))
        return OPACITY_TRANS_CUBE;
      return state.isSolidRender() ? OPACITY_OPAQUE : OPACITY_TRANSPARENT;
    } catch (Exception e) {
      return OPACITY_TRANSPARENT;
    }
  }

  private static boolean isTransparentFlat(int[] states, int x, int y, int z,
      int leafMode, int[] nXNeg, int[] nXPos, int[] nYNeg, int[] nYPos,
      int[] nZNeg, int[] nZPos, byte[] oFlag) {
    return isTransparentFlatFor(states, x, y, z, leafMode,
        nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos, oFlag, false);
  }

  private static boolean isTransparentFlatFor(int[] states, int x, int y, int z,
      int leafMode, int[] nXNeg, int[] nXPos, int[] nYNeg, int[] nYPos,
      int[] nZNeg, int[] nZPos, byte[] oFlag, boolean fromTransCube) {
    int stateId = 0;
    if (x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16) {
      int idx = y * 256 + z * 16 + x;
      if (states == null || states.length <= idx)
        return true;
      stateId = states[idx];
    } else {
      if (x < 0 && nXNeg != null)
        stateId = nXNeg[y * 16 + z];
      else if (x >= 16 && nXPos != null)
        stateId = nXPos[y * 16 + z];
      else if (y < 0 && nYNeg != null)
        stateId = nYNeg[z * 16 + x];
      else if (y >= 16 && nYPos != null)
        stateId = nYPos[z * 16 + x];
      else if (z < 0 && nZNeg != null)
        stateId = nZNeg[y * 16 + x];
      else if (z >= 16 && nZPos != null)
        stateId = nZPos[y * 16 + x];
      else
        return true;
    }
    if (stateId == 0)
      return true;
    if (stateId >= oFlag.length)
      return true;
    byte f = oFlag[stateId];
    if (f == 0) {
      f = (byte) (computeOpacityFlag(stateId) + 1);
      oFlag[stateId] = f;
    }

    if (f == (OPACITY_LEAF + 1))
      return (leafMode == 1);
    if (f == (OPACITY_TRANS_CUBE + 1))
      return !fromTransCube;
    return f == (OPACITY_TRANSPARENT + 1);
  }

  private static boolean isWaterAt(int[] blockStates, int x, int y, int z,
      int[] nXNeg, int[] nXPos, int[] nYNeg, int[] nYPos, int[] nZNeg, int[] nZPos) {
    int sid = 0;
    if (x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16) {
      sid = blockStates[y * 256 + z * 16 + x];
    } else if (x < 0 && nXNeg != null) {
      sid = nXNeg[y * 16 + z];
    } else if (x >= 16 && nXPos != null) {
      sid = nXPos[y * 16 + z];
    } else if (y < 0 && nYNeg != null) {
      sid = nYNeg[z * 16 + x];
    } else if (y >= 16 && nYPos != null) {
      sid = nYPos[z * 16 + x];
    } else if (z < 0 && nZNeg != null) {
      sid = nZNeg[y * 16 + x];
    } else if (z >= 16 && nZPos != null) {
      sid = nZPos[y * 16 + x];
    }
    if (sid == 0)
      return false;
    BlockState bs = Block.stateById(sid);
    if (bs.getBlock() == Blocks.WATER)
      return true;
    return !bs.getFluidState().isEmpty() && bs.getBlock() != Blocks.LAVA;
  }

  private static int computeFluidDrop(int stateId) {
    if (stateId == 0)
      return 0;
    try {
      BlockState bs = Block.stateById(stateId);
      net.minecraft.world.level.material.FluidState fs = bs.getFluidState();
      if (fs.isEmpty())
        return 0;
      float height = fs.getOwnHeight();
      if (height <= 0.0f)
        return 0;
      return Math.max(0, (int) (256 * (1.0f - height)));
    } catch (Exception e) {
      return 0;
    }
  }

  private static int getFluidDropAt(int[] blockStates, int x, int y, int z,
      int[] nXNeg, int[] nXPos, int[] nYNeg, int[] nYPos,
      int[] nZNeg, int[] nZPos) {

    if (isWaterAt(blockStates, x, y + 1, z, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos))
      return 0;
    int sid = 0;
    if (x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16)
      sid = blockStates[y * 256 + z * 16 + x];
    if (sid == 0)
      return 32;
    return computeFluidDrop(sid);
  }

  private static int getStateIdAt(int[] blockStates, int x, int y, int z,
      int[] nXNeg, int[] nXPos, int[] nYNeg, int[] nYPos, int[] nZNeg, int[] nZPos) {
    if (x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16)
      return blockStates != null ? blockStates[y * 256 + z * 16 + x] : 0;
    if (x == -1 && y >= 0 && y < 16 && z >= 0 && z < 16 && nXNeg != null)
      return nXNeg[y * 16 + z];
    if (x == 16 && y >= 0 && y < 16 && z >= 0 && z < 16 && nXPos != null)
      return nXPos[y * 16 + z];
    if (y == -1 && x >= 0 && x < 16 && z >= 0 && z < 16 && nYNeg != null)
      return nYNeg[z * 16 + x];
    if (y == 16 && x >= 0 && x < 16 && z >= 0 && z < 16 && nYPos != null)
      return nYPos[z * 16 + x];
    if (z == -1 && x >= 0 && x < 16 && y >= 0 && y < 16 && nZNeg != null)
      return nZNeg[y * 16 + x];
    if (z == 16 && x >= 0 && x < 16 && y >= 0 && y < 16 && nZPos != null)
      return nZPos[y * 16 + x];
    return 0;
  }

  private static int computeCornerFluidDrop(int[] blockStates, int cx, int y, int cz,
      boolean[] sidIsWater,
      int[] nXNeg, int[] nXPos, int[] nYNeg, int[] nYPos, int[] nZNeg, int[] nZPos) {
    int totalDrop = 0;
    int count = 0;
    for (int dz = -1; dz <= 0; dz++) {
      for (int dx = -1; dx <= 0; dx++) {
        int bx = cx + dx, bz = cz + dz;
        int sid = getStateIdAt(blockStates, bx, y, bz, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        if (sid > 0 && sid < sidIsWater.length && sidIsWater[sid]) {
          int sidAbove = getStateIdAt(blockStates, bx, y + 1, bz, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
          if (sidAbove > 0 && sidAbove < sidIsWater.length && sidIsWater[sidAbove])
            return 0;
          totalDrop += computeFluidDrop(sid);
          count++;
        }
      }
    }
    if (count == 0)
      return 0;
    return totalDrop / count;
  }

  private static volatile int dbgBoundaryCullHit = 0;
  private static volatile int dbgBoundaryWaterMiss = 0;
  private static volatile int dbgBoundaryNullArr = 0;
  private static volatile int dbgWaterBaked = 0;
  private static volatile int dbgWaterFallback = 0;
  private static volatile int dbgWaterBakedCull = 0;
  private static volatile int dbgNonFullSkip = 0;

  private static boolean shouldCullFace(boolean isWater,
      int[] blockStates, int idx, int coord, int boundary,
      boolean[] sidOpaque, boolean[] sidIsWater, int[] nArr, int nIdx) {
    int nSid;
    if (coord != boundary) {
      nSid = blockStates[idx];
    } else {
      if (nArr == null) {
        if (isWater)
          dbgBoundaryNullArr++;
        return false;
      }
      nSid = nArr[nIdx];
    }
    if (nSid == 0)
      return false;
    if (nSid < sidOpaque.length && sidOpaque[nSid])
      return true;
    if (isWater && nSid < sidIsWater.length && sidIsWater[nSid]) {
      if (coord == boundary)
        dbgBoundaryCullHit++;
      return true;
    }
    if (isWater && coord == boundary)
      dbgBoundaryWaterMiss++;
    return false;
  }

  public int getMeshCount() {

    return meshCountAtomic.get();
  }

  private final java.util.concurrent.atomic.AtomicInteger meshCountAtomic = new java.util.concurrent.atomic.AtomicInteger(
      0);

  private final java.util.concurrent.atomic.AtomicInteger meshUpdateGeneration = new java.util.concurrent.atomic.AtomicInteger(
      0);

  public int getMeshUpdateGeneration() {
    return meshUpdateGeneration.get();
  }

  public int getPendingCount() {
    synchronized (pendingKeys) {
      return pendingKeys.size();
    }
  }

  public boolean isPendingBuild(int chunkX, int chunkY, int chunkZ) {
    long key = packChunkKey(chunkX, chunkY, chunkZ);
    synchronized (pendingKeys) {
      return pendingKeys.contains(key);
    }
  }

  private boolean isCurrent(long key, long gen) {
    synchronized (dirtyGeneration) {
      return dirtyGeneration.get(key) == gen;
    }
  }

  private void clearLightFix(long key) {
    synchronized (approxLightKeys) {
      approxLightKeys.remove(key);
    }
    synchronized (queuedLightFixKeys) {
      queuedLightFixKeys.remove(key);
    }
    synchronized (lightFixQueue) {
      java.util.Iterator<Long> iterator = lightFixQueue.iterator();
      while (iterator.hasNext()) {
        if (iterator.next().longValue() == key) {
          iterator.remove();
        }
      }
    }
  }

  private void queueLightFix(long key) {
    synchronized (approxLightKeys) {
      if (!approxLightKeys.contains(key)) {
        return;
      }
    }
    synchronized (queuedLightFixKeys) {
      if (!queuedLightFixKeys.add(key)) {
        return;
      }
    }
    synchronized (lightFixQueue) {
      if (lightFixQueue.size() >= MAX_LIGHT_FIX_QUEUE) {
        Long dropped = lightFixQueue.pollFirst();
        if (dropped != null) {
          synchronized (queuedLightFixKeys) {
            queuedLightFixKeys.remove(dropped.longValue());
          }
        }
      }
      lightFixQueue.addLast(Long.valueOf(key));
    }
  }

  public Long pollLightFixKey() {
    synchronized (lightFixQueue) {
      while (!lightFixQueue.isEmpty()) {
        Long key = lightFixQueue.pollFirst();
        if (key == null) {
          break;
        }
        synchronized (queuedLightFixKeys) {
          queuedLightFixKeys.remove(key.longValue());
        }
        synchronized (approxLightKeys) {
          if (approxLightKeys.contains(key.longValue())) {
            return key;
          }
        }
      }
    }
    return null;
  }

  public void requeueLightFix(long key) {
    queueLightFix(key);
  }

  public boolean needsLightFix(int chunkX, int chunkY, int chunkZ) {
    long key = packChunkKey(chunkX, chunkY, chunkZ);
    synchronized (pendingKeys) {
      if (pendingKeys.contains(key)) {
        return false;
      }
    }
    synchronized (approxLightKeys) {
      return approxLightKeys.contains(key);
    }
  }

  private void queueUpload(UploadJob job) {
    synchronized (uploadQueue) {
      uploadQueue.addLast(job);
      uploadQueueBytes += job.bytes();
    }
  }

  private void queueClear(long key, long gen, int chunkX, int chunkY,
      int chunkZ) {
    queueUpload(new UploadJob(key, gen, false, chunkX, chunkY, chunkZ, 0, 0, 0,
        0, 0, 0, null, null));
  }

  private static byte[] copyUploadData(ByteBuffer src, int len) {
    byte[] data = new byte[len];
    ByteBuffer copy = src.duplicate();
    copy.position(0);
    copy.limit(len);
    copy.get(data);
    return data;
  }

  public UploadStat drainUploads(long budgetNanos, int minJobs, int maxJobs) {
    if (!initialized || maxJobs <= 0) {
      return EMPTY_UPLOAD_STAT;
    }
    long deadline = budgetNanos > 0 ? System.nanoTime() + budgetNanos : Long.MAX_VALUE;
    int jobs = 0;
    long bytes = 0L;
    while (jobs < maxJobs) {
      if (jobs >= minJobs && System.nanoTime() >= deadline) {
        break;
      }
      UploadJob job;
      synchronized (uploadQueue) {
        job = uploadQueue.pollFirst();
        if (job == null) {
          break;
        }
        uploadQueueBytes -= job.bytes();
      }
      bytes += applyUpload(job);
      jobs++;
    }
    if (jobs == 0 && bytes == 0L) {
      return EMPTY_UPLOAD_STAT;
    }
    return new UploadStat(jobs, bytes);
  }

  private long applyUpload(UploadJob job) {
    if (!isCurrent(job.key, job.gen)) {
      synchronized (pendingKeys) {
        pendingKeys.remove(job.key);
      }
      clearLightFix(job.key);
      return 0L;
    }
    if (job.data == null || job.quadCount <= 0) {
      ChunkMeshData old;
      synchronized (meshCache) {
        old = meshCache.remove(job.key);
      }
      removeFarFieldDigest(job.key);
      if (old != null) {
        NativeBridge.nUnregisterChunkMesh(job.chunkX, job.chunkY, job.chunkZ);
        NativeBridge.nDestroyBuffer(old.bufferHandle);
        meshCountAtomic.decrementAndGet();
      }
      synchronized (emptyKeys) {
        emptyKeys.add(job.key);
      }
      synchronized (dirtyKeys) {
        dirtyKeys.remove(job.key);
      }
      synchronized (pendingKeys) {
        pendingKeys.remove(job.key);
      }
      clearLightFix(job.key);
      meshUpdateGeneration.incrementAndGet();
      recordVisibleLatency(job.key);
      return 0L;
    }
    long bufferHandle = 0L;
    try {
      bufferHandle = NativeBridge.nCreateBuffer(
          deviceHandle, job.data.length, NativeMemory.STORAGE_MODE_SHARED);
      if (bufferHandle == 0L) {
        synchronized (dirtyKeys) {
          dirtyKeys.add(job.key);
        }
        synchronized (dirtyGeneration) {
          dirtyGeneration.put(job.key, dirtyGeneration.get(job.key) + 1L);
        }
        synchronized (pendingKeys) {
          pendingKeys.remove(job.key);
        }
        return 0L;
      }
      NativeBridge.nUploadBufferData(bufferHandle, job.data, 0, job.data.length);
      ChunkMeshData mesh = new ChunkMeshData(bufferHandle, job.quadCount,
          job.chunkX, job.chunkY, job.chunkZ, job.lodLevel,
          job.buildPlayerCX, job.buildPlayerCY, job.buildPlayerCZ);
      ChunkMeshData old;
      synchronized (meshCache) {
        old = meshCache.put(job.key, mesh);
      }
      if (old == null) {
        meshCountAtomic.incrementAndGet();
      }
      if (job.digest != null) {
        storeFarFieldDigest(job.key, job.digest);
      } else {
        removeFarFieldDigest(job.key);
      }
      if (job.approxLight) {
        synchronized (approxLightKeys) {
          approxLightKeys.add(job.key);
        }
        queueLightFix(job.key);
      } else {
        clearLightFix(job.key);
      }
      synchronized (emptyKeys) {
        emptyKeys.remove(job.key);
      }
      NativeBridge.nRegisterChunkMesh(job.chunkX, job.chunkY, job.chunkZ,
          bufferHandle, job.quadCount, job.opaqueQuadCount, job.lodLevel);
      if (old != null) {
        NativeBridge.nDestroyBuffer(old.bufferHandle);
      }
      synchronized (dirtyKeys) {
        dirtyKeys.remove(job.key);
      }
      synchronized (pendingKeys) {
        pendingKeys.remove(job.key);
      }
      meshUpdateGeneration.incrementAndGet();
      recordVisibleLatency(job.key);
      return job.data.length;
    } catch (Throwable t) {
      if (bufferHandle != 0L) {
        NativeBridge.nDestroyBuffer(bufferHandle);
      }
      synchronized (dirtyKeys) {
        dirtyKeys.add(job.key);
      }
      synchronized (dirtyGeneration) {
        dirtyGeneration.put(job.key, dirtyGeneration.get(job.key) + 1L);
      }
      synchronized (pendingKeys) {
        pendingKeys.remove(job.key);
      }
      clearLightFix(job.key);
      MetalLogger.error("Mesh upload error for chunk [%d,%d,%d]: %s",
          job.chunkX, job.chunkY, job.chunkZ, t.getMessage());
      return 0L;
    }
  }

  public int getUploadQueueCount() {
    synchronized (uploadQueue) {
      return uploadQueue.size();
    }
  }

  public long getUploadQueueBytes() {
    synchronized (uploadQueue) {
      return uploadQueueBytes;
    }
  }

  private void clearUploadQueue() {
    synchronized (uploadQueue) {
      uploadQueue.clear();
      uploadQueueBytes = 0L;
    }
  }

  public void noteSectionAvailable(int chunkX, int chunkY, int chunkZ) {
    long key = packChunkKey(chunkX, chunkY, chunkZ);
    long now = System.nanoTime();
    synchronized (pendingVisibleSectionNanos) {
      if (!pendingVisibleSectionNanos.containsKey(key)) {
        if (pendingVisibleSectionNanos.size() >= MAX_TRACKED_VISIBLE_SECTIONS) {
          LongIterator it = pendingVisibleSectionNanos.keySet().iterator();
          if (it.hasNext()) {
            it.nextLong();
            it.remove();
          }
        }
        pendingVisibleSectionNanos.put(key, now);
      }
    }
  }

  public void noteBlockUpdate(int chunkX, int chunkY, int chunkZ) {
    long key = packChunkKey(chunkX, chunkY, chunkZ);
    synchronized (pendingBlockUpdateNanos) {
      if (!pendingBlockUpdateNanos.containsKey(key)
          && pendingBlockUpdateNanos.size() >= MAX_TRACKED_BLOCK_UPDATES) {
        LongIterator it = pendingBlockUpdateNanos.keySet().iterator();
        if (it.hasNext()) {
          it.nextLong();
          it.remove();
        }
      }
      pendingBlockUpdateNanos.put(key, System.nanoTime());
    }
  }

  private void recordVisibleLatency(long key) {
    long now = System.nanoTime();
    long visibleSectionStart = 0L;
    synchronized (pendingVisibleSectionNanos) {
      visibleSectionStart = pendingVisibleSectionNanos.remove(key);
    }
    if (visibleSectionStart != 0L) {
      visibleSectionLatencyAccNs.addAndGet(now - visibleSectionStart);
      visibleSectionLatencySamples.incrementAndGet();
    }

    long blockUpdateStart = 0L;
    synchronized (pendingBlockUpdateNanos) {
      blockUpdateStart = pendingBlockUpdateNanos.remove(key);
    }
    if (blockUpdateStart != 0L) {
      blockUpdateLatencyAccNs.addAndGet(now - blockUpdateStart);
      blockUpdateLatencySamples.incrementAndGet();
    }
  }

  private void storeFarFieldDigest(long key, FarFieldDigest digest) {
    synchronized (farFieldDigestCache) {
      farFieldDigestCache.put(key, digest);
    }
  }

  private void removeFarFieldDigest(long key) {
    synchronized (farFieldDigestCache) {
      farFieldDigestCache.remove(key);
    }
  }

  private static FarFieldDigest buildFarFieldDigest(int chunkX, int chunkY,
      int chunkZ, int[] blockStates, byte[] lightData, long versionStamp) {
    byte[] columnBottomHeights = new byte[SECTION_SIZE * SECTION_SIZE];
    byte[] columnTopHeights = new byte[SECTION_SIZE * SECTION_SIZE];
    java.util.Arrays.fill(columnBottomHeights, (byte) -1);
    java.util.Arrays.fill(columnTopHeights, (byte) -1);
    int solidBlockCount = 0;
    int exposedBlockCount = 0;
    int dominantStateId = 0;
    int dominantStateCount = 0;
    int candidateStateId = 0;
    int candidateBalance = 0;
    int minSolidY = SECTION_SIZE;
    int maxSolidY = -1;
    int maxBlockLight = 0;
    int maxSkyLight = 0;

    for (int y = 0; y < SECTION_SIZE; y++) {
      for (int z = 0; z < SECTION_SIZE; z++) {
        for (int x = 0; x < SECTION_SIZE; x++) {
          int index = y * 256 + z * 16 + x;
          int stateId = blockStates[index];
          if (stateId == 0) {
            continue;
          }
          solidBlockCount++;
          if (candidateBalance == 0) {
            candidateStateId = stateId;
            candidateBalance = 1;
          } else if (candidateStateId == stateId) {
            candidateBalance++;
          } else {
            candidateBalance--;
          }
          if (y < minSolidY) {
            minSolidY = y;
          }
          if (y > maxSolidY) {
            maxSolidY = y;
          }
          int columnIndex = z * SECTION_SIZE + x;
          if (columnBottomHeights[columnIndex] < 0) {
            columnBottomHeights[columnIndex] = (byte) y;
          }
          columnTopHeights[columnIndex] = (byte) y;
          if (isDigestExposedBlock(blockStates, x, y, z)) {
            exposedBlockCount++;
          }
          int packedLight = lightData[index] & 0xFF;
          maxBlockLight = Math.max(maxBlockLight, packedLight & 0xF);
          maxSkyLight = Math.max(maxSkyLight, (packedLight >> 4) & 0xF);
        }
      }
    }

    if (candidateStateId != 0) {
      for (int stateId : blockStates) {
        if (stateId == candidateStateId) {
          dominantStateCount++;
        }
      }
      dominantStateId = candidateStateId;
    }
    if (solidBlockCount == 0) {
      minSolidY = -1;
    }
    return new FarFieldDigest(chunkX, chunkY, chunkZ, versionStamp,
        solidBlockCount, exposedBlockCount,
        dominantStateId, dominantStateCount,
        minSolidY, maxSolidY,
        maxBlockLight, maxSkyLight,
        columnBottomHeights, columnTopHeights);
  }

  private static boolean isDigestExposedBlock(int[] blockStates, int x, int y,
      int z) {
    if (x == 0 || x == SECTION_SIZE - 1 || y == 0 || y == SECTION_SIZE - 1
        || z == 0 || z == SECTION_SIZE - 1) {
      return true;
    }
    int index = y * 256 + z * 16 + x;
    return blockStates[index - 1] == 0
        || blockStates[index + 1] == 0
        || blockStates[index - 16] == 0
        || blockStates[index + 16] == 0
        || blockStates[index - 256] == 0
        || blockStates[index + 256] == 0;
  }

  public double getAverageVisibleSectionLatencyMs() {
    int samples = visibleSectionLatencySamples.get();
    return samples > 0 ? (visibleSectionLatencyAccNs.get() / 1e6) / samples : 0.0;
  }

  public int getVisibleSectionLatencySamples() {
    return visibleSectionLatencySamples.get();
  }

  public double getAverageBlockUpdateLatencyMs() {
    int samples = blockUpdateLatencySamples.get();
    return samples > 0 ? (blockUpdateLatencyAccNs.get() / 1e6) / samples : 0.0;
  }

  public int getBlockUpdateLatencySamples() {
    return blockUpdateLatencySamples.get();
  }

  public int getTrackedVisibleSectionCount() {
    synchronized (pendingVisibleSectionNanos) {
      return pendingVisibleSectionNanos.size();
    }
  }

  public int getTrackedBlockUpdateCount() {
    synchronized (pendingBlockUpdateNanos) {
      return pendingBlockUpdateNanos.size();
    }
  }

  public int getBuilderActiveCount() {
    return builderPool != null ? builderPool.getActiveCount() : 0;
  }

  public int getBuilderQueueDepth() {
    return builderPool != null ? builderPool.getQueue().size() : 0;
  }

  public int getInstantActiveCount() {
    return instantRebuildPool != null ? instantRebuildPool.getActiveCount() : 0;
  }

  public int getInstantQueueDepth() {
    return instantRebuildPool != null ? instantRebuildPool.getQueue().size() : 0;
  }

  public int getInteractiveActiveCount() {
    return interactiveRebuildPool != null ? interactiveRebuildPool.getActiveCount() : 0;
  }

  public int getInteractiveQueueDepth() {
    return interactiveRebuildPool != null ? interactiveRebuildPool.getQueue().size() : 0;
  }

  public int getLightActiveCount() {
    return lightRebuildPool != null ? lightRebuildPool.getActiveCount() : 0;
  }

  public int getLightQueueDepth() {
    return lightRebuildPool != null ? lightRebuildPool.getQueue().size() : 0;
  }

  public int getBuilderThreadCount() {
    return builderPool.getCorePoolSize();
  }

  private static void updateThreadPoolSize(java.util.concurrent.ThreadPoolExecutor pool, int target) {
    int normalizedCore = Math.max(0, target);
    int normalizedMax = Math.max(1, target);
    int currentCore = pool.getCorePoolSize();
    int currentMax = pool.getMaximumPoolSize();
    if (currentCore == normalizedCore && currentMax == normalizedMax) {
      return;
    }
    if (normalizedMax > currentMax) {
      pool.setMaximumPoolSize(normalizedMax);
      pool.setCorePoolSize(normalizedCore);
    } else {
      pool.setCorePoolSize(normalizedCore);
      pool.setMaximumPoolSize(normalizedMax);
    }
  }

  private boolean isBurstThreadModeEnabled() {
    MetalRenderConfig config = MetalRenderClient.getConfig();
    return config != null && (config.enableBurstThreadMode || config.prioritizeFpsOverTps);
  }

  private int getThreadBudgetCap() {
    return isBurstThreadModeEnabled() ? BURST_TOTAL_THREAD_BUDGET : NORMAL_TOTAL_THREAD_BUDGET;
  }

  private int getBuilderThreadCap() {
    if (!isBurstThreadModeEnabled()) {
      return maxBuilderThreadCount;
    }
    int processors = Runtime.getRuntime().availableProcessors();
    return Math.max(maxBuilderThreadCount,
        Math.min(BURST_MAX_BUILDER_THREADS, processors + 8));
  }

  private int getInstantThreadCap() {
    if (!isBurstThreadModeEnabled()) {
      return maxInstantThreadCount;
    }
    return Math.max(maxInstantThreadCount, BURST_MAX_INSTANT_THREADS);
  }

  public void setLoadingModeThreadBudget(boolean loadingMode, int totalPending) {
    int pending = Math.max(getPendingCount(), totalPending);
    MetalRenderConfig config = MetalRenderClient.getConfig();
    boolean fpsPriorityMode = config != null && config.prioritizeFpsOverTps;
    int approximateLightingThreshold = fpsPriorityMode ? 2048 : 256;
    boolean startupSolidFill = loadingMode && getMeshCount() < STARTUP_SOLID_FILL_MESH_THRESHOLD;
    aggressiveApproximateLighting = startupSolidFill || pending >= approximateLightingThreshold;
    if (pending <= 0) {
      updateThreadPoolSize(builderPool, 0);
      updateThreadPoolSize(instantRebuildPool, 0);
      return;
    }
    if (fpsPriorityMode) {
      updateThreadPoolSize(builderPool, getBuilderThreadCap());
      updateThreadPoolSize(instantRebuildPool, getInstantThreadCap());
      return;
    }
    int baseTarget = startupSolidFill ? boostedBuilderThreadCount : steadyBuilderThreadCount;
    if (isBurstThreadModeEnabled()) {
      baseTarget += startupSolidFill ? 2 : 0;
    }
    int backlogBoost = 0;
    if (startupSolidFill) {
      if (pending >= 8192) {
        backlogBoost = 10;
      } else if (pending >= 4096) {
        backlogBoost = 8;
      } else if (pending >= 2048) {
        backlogBoost = 6;
      } else if (pending >= 1024) {
        backlogBoost = 4;
      } else if (pending >= 512) {
        backlogBoost = 2;
      } else if (pending >= 256) {
        backlogBoost = 1;
      }
    } else {
      if (pending >= 4096) {
        backlogBoost = 2;
      } else if (pending >= 2048) {
        backlogBoost = 1;
      }
    }
    int budgetCap = Math.min(getBuilderThreadCap(), getThreadBudgetCap());
    if (!startupSolidFill) {
      budgetCap = Math.min(budgetCap,
          steadyBuilderThreadCount + POST_STARTUP_BUILDER_THREAD_CAP_EXTRA);
    }
    int target = Math.min(budgetCap, baseTarget + backlogBoost);
    updateThreadPoolSize(builderPool, target);

    int instantTarget = startupSolidFill ? steadyInstantThreadCount + 2 : steadyInstantThreadCount;
    if (isBurstThreadModeEnabled()) {
      if (startupSolidFill) {
        instantTarget++;
      }
    }
    if (startupSolidFill) {
      if (pending >= 8192) {
        instantTarget += 5;
      } else if (pending >= 4096) {
        instantTarget += 4;
      } else if (pending >= 2048) {
        instantTarget += 3;
      } else if (pending >= 1024) {
        instantTarget += 2;
      } else if (pending >= 512) {
        instantTarget++;
      }
    }
    instantTarget = Math.min(getInstantThreadCap(), instantTarget);
    if (!startupSolidFill) {
      instantTarget = Math.min(instantTarget,
          steadyInstantThreadCount + POST_STARTUP_INSTANT_THREAD_CAP_EXTRA);
    }
    updateThreadPoolSize(instantRebuildPool, instantTarget);
  }

  public void invalidateUVCache() {
    java.util.Arrays.fill(UV_CACHE, null);

    synchronized (emptyKeys) {
      emptyKeys.clear();
    }
  }

  public void clearAllMeshes() {
    int count;
    synchronized (meshCache) {
      count = meshCache.size();

      if (NativeBridge.isLibLoaded()) {
        NativeBridge.nClearAllChunkRegistrations();
      }
      for (ChunkMeshData mesh : meshCache.values()) {
        if (mesh.bufferHandle != 0) {
          NativeBridge.nDestroyBuffer(mesh.bufferHandle);
        }
      }
      meshCache.clear();
      meshCountAtomic.set(0);
    }
    synchronized (farFieldDigestCache) {
      farFieldDigestCache.clear();
    }
    synchronized (approxLightKeys) {
      approxLightKeys.clear();
    }
    synchronized (queuedLightFixKeys) {
      queuedLightFixKeys.clear();
    }
    synchronized (lightFixQueue) {
      lightFixQueue.clear();
    }
    synchronized (pendingKeys) {
      pendingKeys.clear();
    }
    synchronized (dirtyKeys) {
      dirtyKeys.clear();
    }
    synchronized (emptyKeys) {
      emptyKeys.clear();
    }
    clearUploadQueue();
    MetalLogger.info("All mesh data cleared (%d meshes).", count);
  }

  public boolean hasMesh(int cx, int cy, int cz) {
    long key = packChunkKey(cx, cy, cz);
    synchronized (dirtyKeys) {
      if (dirtyKeys.contains(key))
        return false;
    }
    synchronized (meshCache) {
      if (meshCache.containsKey(key))
        return true;
    }
    synchronized (pendingKeys) {
      return pendingKeys.contains(key);
    }
  }

  public boolean needsLodRebuild(int cx, int cy, int cz, int desiredLod) {
    long key = packChunkKey(cx, cy, cz);
    ChunkMeshData mesh;
    synchronized (meshCache) {
      mesh = meshCache.get(key);
    }
    if (mesh == null)
      return false;
    synchronized (pendingKeys) {
      return mesh.lodLevel != desiredLod && !pendingKeys.contains(key);
    }
  }

  public boolean needsFaceCullRebuild(int cx, int cy, int cz,
      int playerCX, int playerCY, int playerCZ) {
    long key = packChunkKey(cx, cy, cz);
    ChunkMeshData mesh;
    synchronized (meshCache) {
      mesh = meshCache.get(key);
    }
    if (mesh == null || mesh.lodLevel < 1)
      return false;
    synchronized (pendingKeys) {
      if (pendingKeys.contains(key))
        return false;
    }
    return mesh.buildPlayerCX != playerCX ||
        mesh.buildPlayerCY != playerCY ||
        mesh.buildPlayerCZ != playerCZ;
  }

  public void markDirty(int cx, int cy, int cz) {
    long key = packChunkKey(cx, cy, cz);
    clearLightFix(key);

    synchronized (emptyKeys) {
      emptyKeys.remove(key);
    }
    synchronized (dirtyKeys) {
      dirtyKeys.add(key);
    }
    synchronized (dirtyGeneration) {
      dirtyGeneration.put(key, dirtyGeneration.get(key) + 1L);
    }
    synchronized (pendingKeys) {
      pendingKeys.remove(key);
    }
  }

  public void markAllDirty() {
    long[] keys;
    long[] emptyArr;
    synchronized (approxLightKeys) {
      approxLightKeys.clear();
    }
    synchronized (queuedLightFixKeys) {
      queuedLightFixKeys.clear();
    }
    synchronized (lightFixQueue) {
      lightFixQueue.clear();
    }
    synchronized (meshCache) {
      keys = meshCache.keySet().toLongArray();
    }
    synchronized (emptyKeys) {
      emptyArr = emptyKeys.toLongArray();
      emptyKeys.clear();
    }
    synchronized (dirtyKeys) {
      for (long k : keys)
        dirtyKeys.add(k);

      for (long k : emptyArr)
        dirtyKeys.add(k);
    }
  }

  public boolean hasMeshIgnoreDirty(int cx, int cy, int cz) {
    long key = packChunkKey(cx, cy, cz);
    synchronized (meshCache) {
      return meshCache.containsKey(key);
    }
  }

  private static final Direction[] ALL_DIRECTIONS = Direction.values();

  public void buildMeshAsync(int chunkX, int chunkY, int chunkZ,
      int[] blockStates, byte[] lightData) {
    if (!initialized)
      return;
    long key = packChunkKey(chunkX, chunkY, chunkZ);
    final long genAtSubmit;
    synchronized (dirtyGeneration) {
      genAtSubmit = dirtyGeneration.get(key);
    }
    synchronized (pendingKeys) {
      pendingKeys.add(key);
    }
    builderPool.submit(() -> {
      try {
        doMeshBuild(chunkX, chunkY, chunkZ, blockStates, lightData, key,
            genAtSubmit, false);
      } catch (Exception e) {
        synchronized (pendingKeys) {
          pendingKeys.remove(key);
        }
        MetalLogger.error("Meshing error for chunk [%d,%d,%d]", chunkX, chunkY,
            chunkZ);
      }
    });
  }

  private void doMeshBuild(int chunkX, int chunkY, int chunkZ,
      int[] blockStates, byte[] lightData, long key, long genAtSubmit,
      boolean approxLight) {
    int[] nXNeg = null, nXPos = null, nYNeg = null, nYPos = null, nZNeg = null, nZPos = null;
    byte[] nXNegLight = null, nXPosLight = null, nYNegLight = null,
        nYPosLight = null, nZNegLight = null, nZPosLight = null;
    try {
      Minecraft mc = Minecraft.getInstance();
      ClientLevel world = mc != null ? mc.level : null;
      if (world != null) {

        int[] poolXNeg = N_XNEG_FACE_POOL.get(), poolXPos = N_XPOS_FACE_POOL.get();
        int[] poolYNeg = N_YNEG_FACE_POOL.get(), poolYPos = N_YPOS_FACE_POOL.get();
        int[] poolZNeg = N_ZNEG_FACE_POOL.get(), poolZPos = N_ZPOS_FACE_POOL.get();
        nXNeg = readNeighborFace(world, chunkX - 1, chunkY, chunkZ, 4, poolXNeg) ? poolXNeg : null;
        nXPos = readNeighborFace(world, chunkX + 1, chunkY, chunkZ, 5, poolXPos) ? poolXPos : null;
        nYNeg = readNeighborFace(world, chunkX, chunkY - 1, chunkZ, 0, poolYNeg) ? poolYNeg : null;
        nYPos = readNeighborFace(world, chunkX, chunkY + 1, chunkZ, 1, poolYPos) ? poolYPos : null;
        nZNeg = readNeighborFace(world, chunkX, chunkY, chunkZ - 1, 2, poolZNeg) ? poolZNeg : null;
        nZPos = readNeighborFace(world, chunkX, chunkY, chunkZ + 1, 3, poolZPos) ? poolZPos : null;

        BlockPos.MutableBlockPos nLightMpos = MUTABLE_POS_POOL.get();
        nXNegLight = N_XNEG_LIGHT_POOL.get();
        if (!readNeighborLightFace(world, chunkX - 1, chunkY, chunkZ, 4, nXNegLight, nLightMpos))
          nXNegLight = null;
        nXPosLight = N_XPOS_LIGHT_POOL.get();
        if (!readNeighborLightFace(world, chunkX + 1, chunkY, chunkZ, 5, nXPosLight, nLightMpos))
          nXPosLight = null;
        nYNegLight = N_YNEG_LIGHT_POOL.get();
        if (!readNeighborLightFace(world, chunkX, chunkY - 1, chunkZ, 0, nYNegLight, nLightMpos))
          nYNegLight = null;
        nYPosLight = N_YPOS_LIGHT_POOL.get();
        if (!readNeighborLightFace(world, chunkX, chunkY + 1, chunkZ, 1, nYPosLight, nLightMpos))
          nYPosLight = null;
        nZNegLight = N_ZNEG_LIGHT_POOL.get();
        if (!readNeighborLightFace(world, chunkX, chunkY, chunkZ - 1, 2, nZNegLight, nLightMpos))
          nZNegLight = null;
        nZPosLight = N_ZPOS_LIGHT_POOL.get();
        if (!readNeighborLightFace(world, chunkX, chunkY, chunkZ + 1, 3, nZPosLight, nLightMpos))
          nZPosLight = null;
      }
    } catch (Exception ignored) {
    }
    doMeshBuild(chunkX, chunkY, chunkZ, blockStates, lightData, key,
        genAtSubmit, approxLight, 0,
        nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos,
        nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight, nZPosLight);
  }

  private static boolean shouldRenderAtLod(BlockState state, int lodLevel) {
    if (lodLevel == 0)
      return true;
    if (state.isAir())
      return false;
    if (state.getFluidState() != null && !state.getFluidState().isEmpty())
      return true;
    if (isLeafBlock(state.getBlock())) {
      return true;
    }
    Block blk = state.getBlock();
    if (blk == Blocks.SNOW) {
      return true;
    }
    if (isTopOnlyDecorative(blk)) {
      return true;
    }

    if (!state.isSolidRender() && isFullCubeShape(state)) {
      return true;
    }
    boolean isOpaqueFull = state.isSolidRender();
    boolean isSolid = state.isSolid();
    if (lodLevel >= 1) {
      if (!isOpaqueFull && !isSolid) {
        return false;
      }
    }
    if (lodLevel >= 2) {
      if (!isOpaqueFull && isSolid) {
        if (!state.canOcclude()) {
          return false;
        }
      }
    }
    if (lodLevel >= 3 && !isOpaqueFull) {
      return false;
    }
    if (lodLevel >= 4) {
      if (!isOpaqueFull)
        return false;
      if (!state.canOcclude())
        return false;
    }
    return true;
  }

  private static boolean isFastLodCompatible(BlockState state, int lodLevel) {
    if (state == null || state.isAir() || !shouldRenderAtLod(state, lodLevel)) {
      return true;
    }
    Block block = state.getBlock();
    if (block == Blocks.WATER || block == Blocks.LAVA || isLeafBlock(block)
        || block == Blocks.SNOW || isTopOnlyDecorative(block)) {
      return true;
    }
    if (!state.getFluidState().isEmpty()) {
      return false;
    }
    return isFullCubeShape(state);
  }

  private static boolean canUseFastLodSection(int[] blockStates, int lodLevel) {
    if (lodLevel <= 0 || blockStates == null) {
      return false;
    }
    for (int stateId : blockStates) {
      if (stateId == 0) {
        continue;
      }
      if (!isFastLodCompatible(Block.stateById(stateId), lodLevel)) {
        return false;
      }
    }
    return true;
  }

  private static final class CoarseDetailMeshStats {
    int opaqueQuadCount;
    int waterQuadCount;
    int cellsMeshed;
    int sampledCells;
  }

  private static int detailTierCellScale(int detailTier) {
    return switch (detailTier) {
      case 1 -> DETAIL_TIER_SCALE_MEDIUM;
      case 2 -> DETAIL_TIER_SCALE_FAR;
      default -> DETAIL_TIER_SCALE_EXTREME;
    };
  }

  private static int coarseStateRank(BlockState state) {
    if (state == null || state.isAir()) {
      return 0;
    }
    if (!state.getFluidState().isEmpty()) {
      return state.getBlock() == Blocks.LAVA ? 5 : 4;
    }
    if (state.isSolidRender()) {
      return 6;
    }
    if (state.canOcclude() || state.isSolid()) {
      return 5;
    }
    if (isLeafBlock(state.getBlock())) {
      return 3;
    }
    return 0;
  }

  private static int selectCoarseState(int[] blockStates, int startX, int startY,
      int startZ, int scale) {
    if (blockStates == null) {
      return 0;
    }
    int bestSid = 0;
    int bestRank = 0;
    for (int y = startY; y < startY + scale; y++) {
      for (int z = startZ; z < startZ + scale; z++) {
        for (int x = startX; x < startX + scale; x++) {
          int sid = blockStates[y * 256 + z * 16 + x];
          if (sid == 0) {
            continue;
          }
          int rank = coarseStateRank(Block.stateById(sid));
          if (rank > bestRank) {
            bestRank = rank;
            bestSid = sid;
          }
        }
      }
    }
    return bestSid;
  }

  private static byte selectCoarseLight(byte[] lightData, int startX, int startY,
      int startZ, int scale) {
    if (lightData == null) {
      return (byte) 0xF0;
    }
    int block = 0;
    int sky = 0;
    for (int y = startY; y < startY + scale; y++) {
      for (int z = startZ; z < startZ + scale; z++) {
        for (int x = startX; x < startX + scale; x++) {
          int packed = lightData[y * 256 + z * 16 + x] & 0xFF;
          block = Math.max(block, packed & 0xF);
          sky = Math.max(sky, (packed >> 4) & 0xF);
        }
      }
    }
    return (byte) ((block & 0xF) | ((sky & 0xF) << 4));
  }

  private static boolean hasCoarseOccupancy(int[] blockStates, int startX,
      int startY, int startZ, int scale) {
    return selectCoarseState(blockStates, startX, startY, startZ, scale) != 0;
  }

  private static boolean hasCoarseFaceOccupancy(int[] faceStates, int faceDir,
      int startX, int startY, int startZ, int scale) {
    if (faceStates == null) {
      return false;
    }
    switch (faceDir) {
      case 0, 1:
        for (int z = startZ; z < startZ + scale; z++) {
          for (int x = startX; x < startX + scale; x++) {
            if (faceStates[z * SECTION_SIZE + x] != 0) {
              return true;
            }
          }
        }
        return false;
      case 2, 3:
        for (int y = startY; y < startY + scale; y++) {
          for (int x = startX; x < startX + scale; x++) {
            if (faceStates[y * SECTION_SIZE + x] != 0) {
              return true;
            }
          }
        }
        return false;
      case 4, 5:
        for (int y = startY; y < startY + scale; y++) {
          for (int z = startZ; z < startZ + scale; z++) {
            if (faceStates[y * SECTION_SIZE + z] != 0) {
              return true;
            }
          }
        }
        return false;
      default:
        return false;
    }
  }

  private CoarseDetailMeshStats buildDistanceTierMesh(ByteBuffer vertexBuffer,
      ByteBuffer waterBuffer, ClientLevel world, BlockStateModelSet blockModels,
      int chunkX, int chunkY, int chunkZ, int[] blockStates, byte[] lightData,
      int detailTier, int[] nXNeg, int[] nXPos, int[] nYNeg, int[] nYPos,
      int[] nZNeg, int[] nZPos) {
    CoarseDetailMeshStats stats = new CoarseDetailMeshStats();
    int scale = detailTierCellScale(detailTier);
    int gridSize = SECTION_SIZE / scale;
    int cellCount = gridSize * gridSize * gridSize;
    int[] coarseStates = new int[cellCount];
    byte[] coarseLights = new byte[cellCount];
    int[] sectionBiomeColors = getSectionBiomeColors(world, chunkX, chunkY, chunkZ, 1);

    for (int cellY = 0; cellY < gridSize; cellY++) {
      int startY = cellY * scale;
      for (int cellZ = 0; cellZ < gridSize; cellZ++) {
        int startZ = cellZ * scale;
        for (int cellX = 0; cellX < gridSize; cellX++) {
          int startX = cellX * scale;
          int cellIndex = cellY * gridSize * gridSize + cellZ * gridSize + cellX;
          coarseStates[cellIndex] = selectCoarseState(blockStates, startX, startY, startZ, scale);
          coarseLights[cellIndex] = selectCoarseLight(lightData, startX, startY, startZ, scale);
          stats.sampledCells++;
        }
      }
    }

    for (int cellY = 0; cellY < gridSize; cellY++) {
      int startY = cellY * scale;
      for (int cellZ = 0; cellZ < gridSize; cellZ++) {
        int startZ = cellZ * scale;
        for (int cellX = 0; cellX < gridSize; cellX++) {
          int startX = cellX * scale;
          int cellIndex = cellY * gridSize * gridSize + cellZ * gridSize + cellX;
          int sid = coarseStates[cellIndex];
          if (sid == 0) {
            continue;
          }
          BlockState state = Block.stateById(sid);
          Block block = state.getBlock();
          boolean isWater = block == Blocks.WATER || (!state.getFluidState().isEmpty() && block != Blocks.LAVA);
          boolean translucent = isWater;
          ByteBuffer target = translucent ? waterBuffer : vertexBuffer;
          byte alpha = translucent ? WATER_ALPHA : (byte) 255;
          byte packedLight = coarseLights[cellIndex];
          int tintType = getBiomeTintType(block);
          int color = tintType != TINT_NONE && tintType < sectionBiomeColors.length
              ? sectionBiomeColors[tintType]
              : getBlockColor(state);
          byte tintR = (byte) ((color >> 16) & 0xFF);
          byte tintG = (byte) ((color >> 8) & 0xFF);
          byte tintB = (byte) (color & 0xFF);
          TextureAtlasSprite sprite = null;
          if (blockModels != null) {
            try {
              BlockStateModel model = blockModels.get(state);
              if (model != null) {
                sprite = model.particleMaterial().sprite();
              }
            } catch (Exception ignored) {
            }
          }

          boolean occludedDown = cellY > 0
              ? coarseStates[cellIndex - (gridSize * gridSize)] != 0
              : hasCoarseFaceOccupancy(nYNeg, 0, startX, 0, startZ, scale);
          boolean occludedUp = cellY < gridSize - 1
              ? coarseStates[cellIndex + (gridSize * gridSize)] != 0
              : hasCoarseFaceOccupancy(nYPos, 1, startX, 0, startZ, scale);
          boolean occludedNorth = cellZ > 0
              ? coarseStates[cellIndex - gridSize] != 0
              : hasCoarseFaceOccupancy(nZNeg, 2, startX, startY, 0, scale);
          boolean occludedSouth = cellZ < gridSize - 1
              ? coarseStates[cellIndex + gridSize] != 0
              : hasCoarseFaceOccupancy(nZPos, 3, startX, startY, 0, scale);
          boolean occludedWest = cellX > 0
              ? coarseStates[cellIndex - 1] != 0
              : hasCoarseFaceOccupancy(nXNeg, 4, 0, startY, startZ, scale);
          boolean occludedEast = cellX < gridSize - 1
              ? coarseStates[cellIndex + 1] != 0
              : hasCoarseFaceOccupancy(nXPos, 5, 0, startY, startZ, scale);

          if (!occludedDown && stats.opaqueQuadCount + stats.waterQuadCount < MAX_QUADS) {
            emitFaceScaled(target, startX, startY, startZ, 0, sprite, packedLight, tintR, tintG, tintB, alpha,
                scale, 0);
            if (translucent) {
              stats.waterQuadCount++;
            } else {
              stats.opaqueQuadCount++;
            }
          }
          if (!occludedUp && stats.opaqueQuadCount + stats.waterQuadCount < MAX_QUADS) {
            emitFaceScaled(target, startX, startY, startZ, 1, sprite, packedLight, tintR, tintG, tintB, alpha,
                scale, 0);
            if (translucent) {
              stats.waterQuadCount++;
            } else {
              stats.opaqueQuadCount++;
            }
          }
          if (!occludedNorth && stats.opaqueQuadCount + stats.waterQuadCount < MAX_QUADS) {
            emitFaceScaled(target, startX, startY, startZ, 2, sprite, packedLight, tintR, tintG, tintB, alpha,
                scale, 0);
            if (translucent) {
              stats.waterQuadCount++;
            } else {
              stats.opaqueQuadCount++;
            }
          }
          if (!occludedSouth && stats.opaqueQuadCount + stats.waterQuadCount < MAX_QUADS) {
            emitFaceScaled(target, startX, startY, startZ, 3, sprite, packedLight, tintR, tintG, tintB, alpha,
                scale, 0);
            if (translucent) {
              stats.waterQuadCount++;
            } else {
              stats.opaqueQuadCount++;
            }
          }
          if (!occludedWest && stats.opaqueQuadCount + stats.waterQuadCount < MAX_QUADS) {
            emitFaceScaled(target, startX, startY, startZ, 4, sprite, packedLight, tintR, tintG, tintB, alpha,
                scale, 0);
            if (translucent) {
              stats.waterQuadCount++;
            } else {
              stats.opaqueQuadCount++;
            }
          }
          if (!occludedEast && stats.opaqueQuadCount + stats.waterQuadCount < MAX_QUADS) {
            emitFaceScaled(target, startX, startY, startZ, 5, sprite, packedLight, tintR, tintG, tintB, alpha,
                scale, 0);
            if (translucent) {
              stats.waterQuadCount++;
            } else {
              stats.opaqueQuadCount++;
            }
          }
          stats.cellsMeshed++;
        }
      }
    }

    return stats;
  }

  private static volatile boolean applyFaceShade = true;
  private static volatile int leafDebugFrames = 0;
  private static int meshBuildCount = 0;

  private static volatile int meshBuildDiagCount = 0;
  private static volatile long meshBuildTimeAcc = 0;
  private static volatile int meshBuildTimeSamples = 0;
  private static volatile long lodSlowTimeAcc = 0;
  private static volatile int lodSlowCount = 0;
  private static volatile long lodFastTimeAcc = 0;
  private static volatile int lodFastCount = 0;
  private static volatile long pipelineTimeAcc = 0;
  private static volatile int pipelineCount = 0;
  private static volatile int lightSampleFallbackCount = 0;
  private static final ThreadLocal<RandomSource> REUSABLE_RANDOM = ThreadLocal
      .withInitial(() -> RandomSource.create(0));
  private static final ThreadLocal<int[]> BLOCK_STATES_POOL = ThreadLocal.withInitial(() -> new int[4096]);
  private static final ThreadLocal<byte[]> LIGHT_DATA_POOL = ThreadLocal.withInitial(() -> new byte[4096]);

  private static final ThreadLocal<byte[]> N_XNEG_LIGHT_POOL = ThreadLocal.withInitial(() -> new byte[256]);
  private static final ThreadLocal<byte[]> N_XPOS_LIGHT_POOL = ThreadLocal.withInitial(() -> new byte[256]);
  private static final ThreadLocal<byte[]> N_YNEG_LIGHT_POOL = ThreadLocal.withInitial(() -> new byte[256]);
  private static final ThreadLocal<byte[]> N_YPOS_LIGHT_POOL = ThreadLocal.withInitial(() -> new byte[256]);
  private static final ThreadLocal<byte[]> N_ZNEG_LIGHT_POOL = ThreadLocal.withInitial(() -> new byte[256]);
  private static final ThreadLocal<byte[]> N_ZPOS_LIGHT_POOL = ThreadLocal.withInitial(() -> new byte[256]);

  private static final ThreadLocal<int[]> N_XNEG_FACE_POOL = ThreadLocal.withInitial(() -> new int[256]);
  private static final ThreadLocal<int[]> N_XPOS_FACE_POOL = ThreadLocal.withInitial(() -> new int[256]);
  private static final ThreadLocal<int[]> N_YNEG_FACE_POOL = ThreadLocal.withInitial(() -> new int[256]);
  private static final ThreadLocal<int[]> N_YPOS_FACE_POOL = ThreadLocal.withInitial(() -> new int[256]);
  private static final ThreadLocal<int[]> N_ZNEG_FACE_POOL = ThreadLocal.withInitial(() -> new int[256]);
  private static final ThreadLocal<int[]> N_ZPOS_FACE_POOL = ThreadLocal.withInitial(() -> new int[256]);

  private static final ThreadLocal<float[]> FACE_AO_POOL = ThreadLocal.withInitial(() -> new float[4]);
  private static final ThreadLocal<byte[]> FACE_LIGHT_POOL = ThreadLocal.withInitial(() -> new byte[4]);

  private static final ThreadLocal<BlockPos.MutableBlockPos> MUTABLE_POS_POOL = ThreadLocal
      .withInitial(BlockPos.MutableBlockPos::new);

  private static final class SidDataArrays {
    int cap = 0;
    int uvCap = 0;
    boolean[] computed, skip, opaque, isWater, isFluid, isTopOnly, isEmissive, isTranslucent;
    byte[] r, g, b, alpha;
    int[] fluidDrop, topDrop;
    short[] faceUMin, faceUMax, faceVMin, faceVMax;
    boolean[] faceHasSprite, faceHasTint;

    SidDataArrays() {
      growTo(1024);
    }

    void ensureCapacity(int needed, int uvNeeded) {
      if (needed > cap)
        growTo(needed);
      if (uvNeeded > uvCap)
        growToUV(uvNeeded);
    }

    private void growTo(int newCap) {
      cap = newCap;
      computed = new boolean[newCap];
      skip = new boolean[newCap];
      opaque = new boolean[newCap];
      isWater = new boolean[newCap];
      isFluid = new boolean[newCap];
      isTopOnly = new boolean[newCap];
      isEmissive = new boolean[newCap];
      isTranslucent = new boolean[newCap];
      r = new byte[newCap];
      g = new byte[newCap];
      b = new byte[newCap];
      alpha = new byte[newCap];
      fluidDrop = new int[newCap];
      topDrop = new int[newCap];
    }

    private void growToUV(int newUvCap) {
      uvCap = newUvCap;
      faceUMin = new short[newUvCap];
      faceUMax = new short[newUvCap];
      faceVMin = new short[newUvCap];
      faceVMax = new short[newUvCap];
      faceHasSprite = new boolean[newUvCap];
      faceHasTint = new boolean[newUvCap];
    }

    void clearUsed(int usedLen, int usedUV) {
      java.util.Arrays.fill(computed, 0, usedLen, false);
      java.util.Arrays.fill(skip, 0, usedLen, false);
      java.util.Arrays.fill(opaque, 0, usedLen, false);
      java.util.Arrays.fill(isWater, 0, usedLen, false);
      java.util.Arrays.fill(isFluid, 0, usedLen, false);
      java.util.Arrays.fill(isTopOnly, 0, usedLen, false);
      java.util.Arrays.fill(isEmissive, 0, usedLen, false);
      java.util.Arrays.fill(isTranslucent, 0, usedLen, false);
      java.util.Arrays.fill(r, 0, usedLen, (byte) 0);
      java.util.Arrays.fill(g, 0, usedLen, (byte) 0);
      java.util.Arrays.fill(b, 0, usedLen, (byte) 0);
      java.util.Arrays.fill(alpha, 0, usedLen, (byte) 0);
      java.util.Arrays.fill(fluidDrop, 0, usedLen, 0);
      java.util.Arrays.fill(topDrop, 0, usedLen, 0);
      java.util.Arrays.fill(faceUMin, 0, usedUV, (short) 0);
      java.util.Arrays.fill(faceUMax, 0, usedUV, (short) 0);
      java.util.Arrays.fill(faceVMin, 0, usedUV, (short) 0);
      java.util.Arrays.fill(faceVMax, 0, usedUV, (short) 0);
      java.util.Arrays.fill(faceHasSprite, 0, usedUV, false);
      java.util.Arrays.fill(faceHasTint, 0, usedUV, false);
    }
  }

  private static final ThreadLocal<SidDataArrays> SID_DATA_POOL = ThreadLocal.withInitial(SidDataArrays::new);

  private static final class Lod1SidDataArrays {
    int cap = 0;
    int uvCap = 0;

    byte[] oFlag;
    boolean[] modelComputed, sidPropsComputed, sidIsAir, sidShouldSkip;
    byte[] sidTintR, sidTintG, sidTintB;
    boolean[] sidIsLeaf, sidForceOpaque;
    byte[] sidBlockAlpha;
    boolean[] sidIsNonFull, sidIsWaterLod0, sidIsWaterloggedLod0, sidIsFluidLod0;
    byte[] sidBiomeTintType;
    boolean[] sidOpaque, sidIsFullCube, sidIsTransCube;

    BlockState[] stateArr;
    BlockStateModel[] modelArr;

    short[] l0FaceUMin, l0FaceUMax, l0FaceVMin, l0FaceVMax;
    boolean[] l0FaceHasSprite, l0FaceHasTint;

    Lod1SidDataArrays() {
      growTo(1024);
    }

    void ensureCapacity(int needed, int uvNeeded) {
      if (needed > cap)
        growTo(needed);
      if (uvNeeded > uvCap)
        growToUV(uvNeeded);
    }

    private void growTo(int n) {
      cap = n;
      oFlag = new byte[n];
      modelComputed = new boolean[n];
      sidPropsComputed = new boolean[n];
      sidIsAir = new boolean[n];
      sidShouldSkip = new boolean[n];
      sidTintR = new byte[n];
      sidTintG = new byte[n];
      sidTintB = new byte[n];
      sidIsLeaf = new boolean[n];
      sidForceOpaque = new boolean[n];
      sidBlockAlpha = new byte[n];
      sidIsNonFull = new boolean[n];
      sidIsWaterLod0 = new boolean[n];
      sidIsWaterloggedLod0 = new boolean[n];
      sidIsFluidLod0 = new boolean[n];
      sidBiomeTintType = new byte[n];
      sidOpaque = new boolean[n];
      sidIsFullCube = new boolean[n];
      sidIsTransCube = new boolean[n];
      stateArr = new BlockState[n];
      modelArr = new BlockStateModel[n];
    }

    private void growToUV(int n) {
      uvCap = n;
      l0FaceUMin = new short[n];
      l0FaceUMax = new short[n];
      l0FaceVMin = new short[n];
      l0FaceVMax = new short[n];
      l0FaceHasSprite = new boolean[n];
      l0FaceHasTint = new boolean[n];
    }

    void clearUsed(int usedLen, int usedUV) {
      java.util.Arrays.fill(oFlag, 0, usedLen, (byte) 0);
      java.util.Arrays.fill(modelComputed, 0, usedLen, false);
      java.util.Arrays.fill(sidPropsComputed, 0, usedLen, false);
      java.util.Arrays.fill(sidIsAir, 0, usedLen, false);
      java.util.Arrays.fill(sidShouldSkip, 0, usedLen, false);
      java.util.Arrays.fill(sidTintR, 0, usedLen, (byte) 0);
      java.util.Arrays.fill(sidTintG, 0, usedLen, (byte) 0);
      java.util.Arrays.fill(sidTintB, 0, usedLen, (byte) 0);
      java.util.Arrays.fill(sidIsLeaf, 0, usedLen, false);
      java.util.Arrays.fill(sidForceOpaque, 0, usedLen, false);
      java.util.Arrays.fill(sidBlockAlpha, 0, usedLen, (byte) 0);
      java.util.Arrays.fill(sidIsNonFull, 0, usedLen, false);
      java.util.Arrays.fill(sidIsWaterLod0, 0, usedLen, false);
      java.util.Arrays.fill(sidIsWaterloggedLod0, 0, usedLen, false);
      java.util.Arrays.fill(sidIsFluidLod0, 0, usedLen, false);
      java.util.Arrays.fill(sidBiomeTintType, 0, usedLen, (byte) 0);
      java.util.Arrays.fill(sidOpaque, 0, usedLen, false);
      java.util.Arrays.fill(sidIsFullCube, 0, usedLen, false);
      java.util.Arrays.fill(sidIsTransCube, 0, usedLen, false);

      java.util.Arrays.fill(stateArr, 0, usedLen, null);
      java.util.Arrays.fill(modelArr, 0, usedLen, null);
      java.util.Arrays.fill(l0FaceUMin, 0, usedUV, (short) 0);
      java.util.Arrays.fill(l0FaceUMax, 0, usedUV, (short) 0);
      java.util.Arrays.fill(l0FaceVMin, 0, usedUV, (short) 0);
      java.util.Arrays.fill(l0FaceVMax, 0, usedUV, (short) 0);
      java.util.Arrays.fill(l0FaceHasSprite, 0, usedUV, false);
      java.util.Arrays.fill(l0FaceHasTint, 0, usedUV, false);
    }
  }

  private static final ThreadLocal<Lod1SidDataArrays> LOD1_SID_DATA_POOL = ThreadLocal
      .withInitial(Lod1SidDataArrays::new);

  private static byte getFaceLight(byte[] lightData,
      byte[] nXNegLight, byte[] nXPosLight,
      byte[] nYNegLight, byte[] nYPosLight,
      byte[] nZNegLight, byte[] nZPosLight,
      int x, int y, int z, int face) {
    int centerLight = getLightAtExt(x, y, z, lightData, nXNegLight, nXPosLight,
        nYNegLight, nYPosLight, nZNegLight, nZPosLight);
    int nx = x, ny = y, nz = z;
    switch (face) {
      case 0:
        ny = y - 1;
        break;
      case 1:
        ny = y + 1;
        break;
      case 2:
        nz = z - 1;
        break;
      case 3:
        nz = z + 1;
        break;
      case 4:
        nx = x - 1;
        break;
      case 5:
        nx = x + 1;
        break;
    }

    if (nx >= 0 && nx < 16 && ny >= 0 && ny < 16 && nz >= 0 && nz < 16) {
      if (lightData != null) {
        int nIdx = ny * 256 + nz * 16 + nx;
        if (nIdx < lightData.length) {
          byte nLight = lightData[nIdx];
          return mergePackedLight(centerLight, nLight & 0xFF);
        }
      }
      return (byte) (centerLight & 0xFF);
    }

    byte[] arr;
    int idx;
    switch (face) {
      case 0:
        arr = nYNegLight;
        idx = nz * 16 + nx;
        break;
      case 1:
        arr = nYPosLight;
        idx = nz * 16 + nx;
        break;
      case 2:
        arr = nZNegLight;
        idx = ny * 16 + nx;
        break;
      case 3:
        arr = nZPosLight;
        idx = ny * 16 + nx;
        break;
      case 4:
        arr = nXNegLight;
        idx = ny * 16 + nz;
        break;
      case 5:
        arr = nXPosLight;
        idx = ny * 16 + nz;
        break;
      default:
        return 0x00;
    }
    if (arr != null && idx >= 0 && idx < arr.length) {
      return mergePackedLight(centerLight, arr[idx] & 0xFF);
    }

    return (byte) (centerLight & 0xFF);
  }

  private void doMeshBuild(int chunkX, int chunkY, int chunkZ,
      int[] blockStates, byte[] lightData, long key, long genAtSubmit,
      boolean approxLight, int lodLevel, int[] nXNeg, int[] nXPos, int[] nYNeg,
      int[] nYPos, int[] nZNeg, int[] nZPos,
      byte[] nXNegLight, byte[] nXPosLight, byte[] nYNegLight,
      byte[] nYPosLight, byte[] nZNegLight, byte[] nZPosLight) {
    long buildStart = System.nanoTime();
    boolean keepPending = false;
    try {
      meshBuildCount++;
      ByteBuffer vertexBuffer = VERTEX_BUF_POOL.get();
      ByteBuffer waterBuffer = WATER_BUF_POOL.get();
      vertexBuffer.clear();
      waterBuffer.clear();
      int opaqueQuadCount = 0;
      int waterQuadCount = 0;
      int bakedQuadBlocks = 0;
      int fallbackBlocks = 0;
      Minecraft mc = Minecraft.getInstance();
      BlockStateModelSet blockModels = null;
      if (mc != null && mc.getModelManager() != null) {
        blockModels = mc.getModelManager().getBlockStateModelSet();
      }
      MetalRenderConfig leafCfg = MetalRenderClient.getConfig();
      int leafMode = (leafCfg != null) ? leafCfg.leafCullingMode : 0;
      applyFaceShade = false;
      boolean useDistanceTier = false;
      boolean useFastPath = canUseFastLodSection(blockStates, lodLevel);
      boolean skipNonDirectionalQuads = useFastPath || lodLevel >= 2;

      boolean useSmoothAO = false;

      boolean[] skipFace = null;
      int buildPCX = 0, buildPCY = 0, buildPCZ = 0;
      if (mc != null && mc.player != null) {
        buildPCX = mc.player.chunkPosition().x();
        buildPCZ = mc.player.chunkPosition().z();
        buildPCY = (int) Math.floor(mc.player.getY()) >> 4;
      }
      if (useDistanceTier) {
        CoarseDetailMeshStats coarseStats = buildDistanceTierMesh(vertexBuffer, waterBuffer,
            mc != null ? mc.level : null, blockModels, chunkX, chunkY, chunkZ,
            blockStates, lightData, lodLevel, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        opaqueQuadCount = coarseStats.opaqueQuadCount;
        waterQuadCount = coarseStats.waterQuadCount;
        bakedQuadBlocks = coarseStats.cellsMeshed;
        fallbackBlocks = coarseStats.sampledCells;
      } else if (useFastPath) {
        int[] fastBiomeColors = getSectionBiomeColors(
            mc != null ? mc.level : null, chunkX, chunkY, chunkZ,
            MetalRenderClient.getConfig().biomeTransitionDetail);
        int maxSid = 0;
        for (int i = 0; i < 4096; i++) {
          if (blockStates != null && blockStates[i] > maxSid)
            maxSid = blockStates[i];
        }
        int[][] neighborArrays = { nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos };
        for (int[] nArr : neighborArrays) {
          if (nArr != null)
            for (int s : nArr) {
              if (s > maxSid)
                maxSid = s;
            }
        }

        int uvSize = (maxSid + 1) * 6;
        SidDataArrays _sid = SID_DATA_POOL.get();
        _sid.ensureCapacity(maxSid + 1, uvSize);
        _sid.clearUsed(maxSid + 1, uvSize);
        boolean[] sidComputed = _sid.computed;
        boolean[] sidSkip = _sid.skip;
        boolean[] sidOpaque = _sid.opaque;
        byte[] sidR = _sid.r;
        byte[] sidG = _sid.g;
        byte[] sidB = _sid.b;
        byte[] sidAlpha = _sid.alpha;
        short[] sidFaceUMin = _sid.faceUMin;
        short[] sidFaceUMax = _sid.faceUMax;
        short[] sidFaceVMin = _sid.faceVMin;
        short[] sidFaceVMax = _sid.faceVMax;
        boolean[] sidFaceHasSprite = _sid.faceHasSprite;
        boolean[] sidFaceHasTint = _sid.faceHasTint;
        boolean[] sidIsWater = _sid.isWater;
        boolean[] sidIsFluid = _sid.isFluid;
        boolean[] sidIsTopOnly = _sid.isTopOnly;
        boolean[] sidIsEmissive = _sid.isEmissive;
        boolean[] sidIsTranslucent = _sid.isTranslucent;
        int[] sidFluidDrop = _sid.fluidDrop;
        int[] sidTopDrop = _sid.topDrop;
        sidSkip[0] = true;
        for (int i = 0; i < 4096; i++) {
          int sid = blockStates != null ? blockStates[i] : 0;
          if (sid == 0 || sidComputed[sid])
            continue;
          sidComputed[sid] = true;
          BlockState bs = Block.stateById(sid);
          if (bs.isAir()) {
            sidSkip[sid] = true;
            continue;
          }
          if (lodLevel > 0 && !shouldRenderAtLod(bs, lodLevel)) {
            sidSkip[sid] = true;
            continue;
          }
          Block blk = bs.getBlock();
          boolean isWater = (blk == Blocks.WATER);
          boolean isLava = (blk == Blocks.LAVA);
          boolean isFluid = isWater || isLava;
          boolean isLeaf = isLeafBlock(blk);
          sidIsWater[sid] = isWater;
          sidIsFluid[sid] = isFluid;
          sidFluidDrop[sid] = (isFluid || !bs.getFluidState().isEmpty())
              ? computeFluidDrop(sid)
              : 0;
          if (!bs.isSolidRender() && !isFluid && !isLeaf) {

            if (isFullCubeShape(bs)) {
              sidOpaque[sid] = false;

              String blkSimpleName = blk.getClass().getSimpleName();
              if (blkSimpleName.contains("Stained") || blkSimpleName.contains("Tinted")
                  || blk == Blocks.ICE || blk == Blocks.SLIME_BLOCK || blk == Blocks.HONEY_BLOCK) {
                sidIsTranslucent[sid] = true;
              }
            } else {

              boolean isDecorativeTop = isTopOnlyDecorative(blk);
              boolean isWaterlogged = !bs.getFluidState().isEmpty();
              if (!isWaterlogged && !isDecorativeTop) {
                sidOpaque[sid] = false;
                sidSkip[sid] = true;
                dbgNonFullSkip++;
                continue;
              }
              if (isDecorativeTop) {
                sidIsTopOnly[sid] = true;
                sidOpaque[sid] = false;

                sidTopDrop[sid] = computeDecorativeTopDrop(bs, blk);
              } else {
                sidIsWater[sid] = true;
                sidOpaque[sid] = false;
              }
            }
          }
          if (isLeaf) {
            sidOpaque[sid] = false;
          } else if (isFluid) {
            sidOpaque[sid] = !isWater;
          } else if (!sidIsWater[sid] && !sidIsTopOnly[sid]) {

            if (!bs.getFluidState().isEmpty()) {
              sidOpaque[sid] = bs.isSolidRender();
            } else {
              sidOpaque[sid] = bs.isSolidRender();
            }
          }
          int color;
          if (sidIsWater[sid] && !isWater) {
            color = fastBiomeColors[TINT_WATER];
          } else {
            byte tintType = getBiomeTintType(blk);
            if (tintType != TINT_NONE && tintType < fastBiomeColors.length) {
              color = fastBiomeColors[tintType];
            } else {
              color = getBlockColor(bs);
            }
          }
          sidR[sid] = (byte) ((color >> 16) & 0xFF);
          sidG[sid] = (byte) ((color >> 8) & 0xFF);
          sidB[sid] = (byte) (color & 0xFF);
          if (isWater || sidIsWater[sid]) {
            sidAlpha[sid] = WATER_ALPHA;
          } else if (sidIsTranslucent[sid]) {

            sidAlpha[sid] = (byte) 220;
          } else if (isLava) {
            sidAlpha[sid] = (byte) 255;
            sidIsEmissive[sid] = true;
          } else if (isLeaf) {
            sidAlpha[sid] = (byte) 254;
          } else if (sidIsTopOnly[sid]) {

            sidAlpha[sid] = (byte) 252;
          } else {
            sidAlpha[sid] = (byte) 255;
          }
          if (blockModels != null) {

            int uvSid = sid;
            BlockState uvState = (sidIsWater[sid] && !isWater)
                ? Blocks.WATER.defaultBlockState()
                : bs;
            int uvCacheKey = Block.getId(uvState);
            CachedUVData cachedUV = (uvCacheKey >= 0 && uvCacheKey < UV_CACHE.length) ? UV_CACHE[uvCacheKey] : null;
            if (cachedUV != null) {

              for (int d = 0; d < 6; d++) {
                int uvIdx = sid * 6 + d;
                sidFaceHasSprite[uvIdx] = cachedUV.hasSprite[d];

                sidFaceHasTint[uvIdx] = cachedUV.hasTint[d] || isFluid;
                sidFaceUMin[uvIdx] = cachedUV.uMin[d];
                sidFaceUMax[uvIdx] = cachedUV.uMax[d];
                sidFaceVMin[uvIdx] = cachedUV.vMin[d];
                sidFaceVMax[uvIdx] = cachedUV.vMax[d];
              }
            } else {

              CachedUVData newUV = new CachedUVData();

              boolean blockHasBiomeTint = BIOME_TINT_TYPE.containsKey(uvState.getBlock());
              try {
                var model = blockModels.get(uvState);
                if (model != null) {
                  RandomSource rand = REUSABLE_RANDOM.get();
                  rand.setSeed(42L);
                  List<BlockStateModelPart> parts = new java.util.ArrayList<>();
                  model.collectParts(rand, parts);
                  TextureAtlasSprite fallbackSpr = model.particleMaterial().sprite();
                  for (int d = 0; d < 6; d++) {
                    int uvIdx = sid * 6 + d;
                    Direction dir = ALL_DIRECTIONS[d];
                    boolean found = false;
                    boolean foundTinted = false;
                    for (BlockStateModelPart part : parts) {
                      List<BakedQuad> quads = part.getQuads(dir);
                      if (quads != null && !quads.isEmpty()) {
                        BakedQuad q = quads.get(0);
                        boolean qTint = q.materialInfo().isTinted() || q.materialInfo().tintIndex() >= 0 ||
                            isFluid || blockHasBiomeTint;

                        if (!found || (qTint && !foundTinted)) {
                          float minU = Float.MAX_VALUE, maxU = -Float.MAX_VALUE;
                          float minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
                          for (int vi = 0; vi < 4; vi++) {
                            long packedUV = q.packedUV(vi);
                            float u = Float.intBitsToFloat((int) (packedUV >> 32));
                            float v = Float.intBitsToFloat((int) packedUV);
                            if (u < minU)
                              minU = u;
                            if (u > maxU)
                              maxU = u;
                            if (v < minV)
                              minV = v;
                            if (v > maxV)
                              maxV = v;
                          }
                          newUV.hasSprite[d] = true;
                          newUV.hasTint[d] = qTint;
                          newUV.uMin[d] = (short) (minU * 65535.0f);
                          newUV.uMax[d] = (short) (maxU * 65535.0f);
                          newUV.vMin[d] = (short) (minV * 65535.0f);
                          newUV.vMax[d] = (short) (maxV * 65535.0f);
                          sidFaceHasSprite[uvIdx] = newUV.hasSprite[d];
                          sidFaceHasTint[uvIdx] = newUV.hasTint[d];
                          sidFaceUMin[uvIdx] = newUV.uMin[d];
                          sidFaceUMax[uvIdx] = newUV.uMax[d];
                          sidFaceVMin[uvIdx] = newUV.vMin[d];
                          sidFaceVMax[uvIdx] = newUV.vMax[d];
                          found = true;
                          if (qTint)
                            foundTinted = true;
                        }
                        if (foundTinted)
                          break;
                      }
                    }
                    if (!found && fallbackSpr != null) {
                      newUV.hasSprite[d] = true;

                      newUV.hasTint[d] = isFluid || blockHasBiomeTint;
                      newUV.uMin[d] = (short) (fallbackSpr.getU0() * 65535.0f);
                      newUV.uMax[d] = (short) (fallbackSpr.getU1() * 65535.0f);
                      newUV.vMin[d] = (short) (fallbackSpr.getV0() * 65535.0f);
                      newUV.vMax[d] = (short) (fallbackSpr.getV1() * 65535.0f);
                      sidFaceHasSprite[uvIdx] = newUV.hasSprite[d];
                      sidFaceHasTint[uvIdx] = newUV.hasTint[d];
                      sidFaceUMin[uvIdx] = newUV.uMin[d];
                      sidFaceUMax[uvIdx] = newUV.uMax[d];
                      sidFaceVMin[uvIdx] = newUV.vMin[d];
                      sidFaceVMax[uvIdx] = newUV.vMax[d];
                    }
                  }
                }
              } catch (Exception ignored) {
              }

              boolean anySpriteCached = false;
              for (int d2 = 0; d2 < 6; d2++) {
                if (newUV.hasSprite[d2]) {
                  anySpriteCached = true;
                  break;
                }
              }
              if (anySpriteCached && uvCacheKey >= 0 && uvCacheKey < UV_CACHE.length)
                UV_CACHE[uvCacheKey] = newUV;
            }
          }
        }
        for (int[] nArr : neighborArrays) {
          if (nArr == null)
            continue;
          for (int s : nArr) {
            if (s == 0 || s >= sidComputed.length || sidComputed[s])
              continue;
            sidComputed[s] = true;
            BlockState bs = Block.stateById(s);
            if (bs.isAir()) {
              sidSkip[s] = true;
              continue;
            }
            Block blk = bs.getBlock();
            sidIsWater[s] = (blk == Blocks.WATER) ||
                (!bs.getFluidState().isEmpty() && blk != Blocks.LAVA);
            boolean isWaterN = sidIsWater[s];
            if (isLeafBlock(blk)) {
              sidOpaque[s] = false;
            } else if (!bs.getFluidState().isEmpty()) {
              sidOpaque[s] = isWaterN ? false : bs.isSolidRender();
            } else {
              sidOpaque[s] = bs.isSolidRender();
            }
          }
        }
        float[] faceAO = useSmoothAO ? FACE_AO_POOL.get() : null;
        byte[] faceLight = useSmoothAO ? FACE_LIGHT_POOL.get() : null;

        byte[] sidTintTypeFast = new byte[maxSid + 1];
        for (int fastSid = 1; fastSid <= maxSid; fastSid++) {
          if (!sidComputed[fastSid] || sidSkip[fastSid])
            continue;
          BlockState _bsFT = Block.stateById(fastSid);
          if (_bsFT != null)
            sidTintTypeFast[fastSid] = getBiomeTintType(_bsFT.getBlock());
        }
        int biomeDetailFast = MetalRenderClient.getConfig().biomeTransitionDetail;
        int[] colGrassLOD = null, colWaterLOD = null;
        if (biomeDetailFast >= 1 && mc != null && mc.level != null) {
          int blurR = Math.min(biomeDetailFast, 8);
          int padded = 16 + 2 * blurR;
          int syCol = Math.max(63, chunkY * 16 + 8);
          int bxOrig = chunkX * 16 - blurR;
          int bzOrig = chunkZ * 16 - blurR;
          BlockPos.MutableBlockPos bpCol = BIOME_POS_POOL.get();
          try {
            int[] pgGrass = new int[padded * padded];
            int[] pgWater = new int[padded * padded];
            for (int lz = 0; lz < padded; lz++) {
              for (int lx = 0; lx < padded; lx++) {
                bpCol.set(bxOrig + lx, syCol, bzOrig + lz);
                int ci = lz * padded + lx;
                pgGrass[ci] = mc.level.getBlockTint(bpCol,
                    net.minecraft.client.renderer.BiomeColors.GRASS_COLOR_RESOLVER);
                pgWater[ci] = mc.level.getBlockTint(bpCol,
                    net.minecraft.client.renderer.BiomeColors.WATER_COLOR_RESOLVER);
              }
            }
            pgGrass = boxBlurColors(pgGrass, padded, padded, blurR);
            pgWater = boxBlurColors(pgWater, padded, padded, blurR);
            colGrassLOD = new int[256];
            colWaterLOD = new int[256];
            for (int lz = 0; lz < 16; lz++) {
              for (int lx = 0; lx < 16; lx++) {
                colGrassLOD[lz * 16 + lx] = pgGrass[(lz + blurR) * padded + (lx + blurR)];
                colWaterLOD[lz * 16 + lx] = pgWater[(lz + blurR) * padded + (lx + blurR)];
              }
            }
          } catch (Exception e) {
            colGrassLOD = colWaterLOD = null;
          }
        }
        for (int y = 0; y < SECTION_SIZE; y++) {
          for (int z = 0; z < SECTION_SIZE; z++) {
            for (int x = 0; x < SECTION_SIZE; x++) {
              int idx = y * 256 + z * 16 + x;
              int sid = blockStates != null ? blockStates[idx] : 0;
              if (sid == 0 || sidSkip[sid])
                continue;
              byte r = sidR[sid], g = sidG[sid], b = sidB[sid], a = sidAlpha[sid];

              if (colGrassLOD != null && sid < sidTintTypeFast.length) {
                byte ft = sidTintTypeFast[sid];
                if (ft == TINT_GRASS) {
                  int c = colGrassLOD[z * 16 + x];
                  r = (byte) ((c >> 16) & 0xFF);
                  g = (byte) ((c >> 8) & 0xFF);
                  b = (byte) (c & 0xFF);
                } else if (ft == TINT_WATER) {
                  int c = colWaterLOD[z * 16 + x];
                  r = (byte) ((c >> 16) & 0xFF);
                  g = (byte) ((c >> 8) & 0xFF);
                  b = (byte) (c & 0xFF);
                }
              }
              int sidBase = sid * 6;
              boolean isWater = sidIsWater[sid];

              boolean forceDebugTint = MetalRenderConfig.debugPinkBlockTint();
              if (forceDebugTint) {
                r = (byte) 0xFF;
                g = (byte) 0x30;
                b = (byte) 0xB0;
              }

              boolean usesTranslucentBuf = isWater || sidIsTranslucent[sid];
              boolean isEmissive = sidIsEmissive[sid];

              boolean doubleSided = (a == (byte) 253);

              int topDrop;
              int drop00 = 0, drop01 = 0, drop11 = 0, drop10 = 0;
              boolean isFluidBlock = sidIsFluid[sid];
              if (isFluidBlock) {

                drop00 = computeCornerFluidDrop(blockStates, x, y, z, sidIsFluid,
                    nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
                drop01 = computeCornerFluidDrop(blockStates, x, y, z + 1, sidIsFluid,
                    nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
                drop11 = computeCornerFluidDrop(blockStates, x + 1, y, z + 1, sidIsFluid,
                    nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
                drop10 = computeCornerFluidDrop(blockStates, x + 1, y, z, sidIsFluid,
                    nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
                topDrop = (drop00 + drop01 + drop11 + drop10) / 4;
              } else if (sidIsTopOnly[sid]) {
                topDrop = sidTopDrop[sid];
              } else {
                topDrop = 0;
              }
              boolean[] sf = isFluidBlock ? null : skipFace;
              ByteBuffer targetBuf = usesTranslucentBuf ? waterBuffer : vertexBuffer;
              if ((sf == null || !sf[1]) &&
                  !shouldCullFace(isWater, blockStates, idx + 256, y, 15, sidOpaque, sidIsWater, nYPos, z * 16 + x)) {
                int uv = sidBase + 1;
                byte fr = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] && !forceDebugTint) ? (byte) 0xFF : r;
                byte fg = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] && !forceDebugTint) ? (byte) 0xFF : g;
                byte fb = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] && !forceDebugTint) ? (byte) 0xFF : b;
                if (usesTranslucentBuf)
                  waterQuadCount++;
                else
                  opaqueQuadCount++;
                if (useSmoothAO) {
                  computeSmoothLighting(x, y, z, 1, blockStates, sidOpaque, lightData, nXNeg, nXPos, nYNeg, nYPos,
                      nZNeg, nZPos, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight, nZPosLight, faceAO,
                      faceLight);
                  if (isFluidBlock)
                    emitWaterFaceAO(targetBuf, x, y, z, 1, fr, fg, fb, a, sidFaceHasSprite[uv],
                        sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv],
                        drop00, drop01, drop11, drop10, faceAO, faceLight);
                  else
                    emitFaceAO(targetBuf, x, y, z, 1, fr, fg, fb, a, sidFaceHasSprite[uv],
                        sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv], topDrop, faceAO,
                        faceLight);
                } else {
                  if (isFluidBlock)
                    emitWaterFace(targetBuf, x, y, z, 1,
                        getFaceLight(lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                            nZPosLight, x, y, z, 1),
                        fr, fg, fb, a,
                        sidFaceHasSprite[uv],
                        sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv],
                        drop00, drop01, drop11, drop10);
                  else
                    emitFaceInlineWater(targetBuf, x, y, z, 1,
                        getFaceLight(lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                            nZPosLight, x, y, z, 1),
                        fr, fg, fb, a,
                        sidFaceHasSprite[uv],
                        sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv], topDrop);
                }
                if (doubleSided) {
                  emitReversedQuad(targetBuf);
                  opaqueQuadCount++;
                }

                if (sidIsTopOnly[sid]) {
                  emitReversedQuad(targetBuf);
                  opaqueQuadCount++;
                }
              }

              if ((sf == null || !sf[0]) &&
                  !shouldCullFace(isWater, blockStates, idx - 256, y, 0, sidOpaque, sidIsWater, nYNeg, z * 16 + x)) {
                int uv = sidBase + 0;
                byte fr = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] && !forceDebugTint) ? (byte) 0xFF : r;
                byte fg = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] && !forceDebugTint) ? (byte) 0xFF : g;
                byte fb = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] && !forceDebugTint) ? (byte) 0xFF : b;
                if (usesTranslucentBuf)
                  waterQuadCount++;
                else
                  opaqueQuadCount++;
                if (useSmoothAO) {
                  computeSmoothLighting(x, y, z, 0, blockStates, sidOpaque, lightData, nXNeg, nXPos, nYNeg, nYPos,
                      nZNeg, nZPos, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight, nZPosLight, faceAO,
                      faceLight);
                  emitFaceAO(targetBuf, x, y, z, 0, fr, fg, fb, a, sidFaceHasSprite[uv],
                      sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv], 0, faceAO, faceLight);
                } else {
                  emitFaceInlineWater(targetBuf, x, y, z, 0,
                      getFaceLight(lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight, nZPosLight, x,
                          y, z, 0),
                      fr, fg, fb, a,
                      sidFaceHasSprite[uv],
                      sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv], 0);
                }
                if (doubleSided) {
                  emitReversedQuad(targetBuf);
                  opaqueQuadCount++;
                }
              }
              if ((sf == null || !sf[3]) &&
                  !shouldCullFace(isWater, blockStates, idx + 16, z, 15, sidOpaque, sidIsWater, nZPos, y * 16 + x)) {
                int uv = sidBase + 3;
                byte fr = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] && !forceDebugTint) ? (byte) 0xFF : r;
                byte fg = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] && !forceDebugTint) ? (byte) 0xFF : g;
                byte fb = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] && !forceDebugTint) ? (byte) 0xFF : b;
                if (usesTranslucentBuf)
                  waterQuadCount++;
                else
                  opaqueQuadCount++;
                if (useSmoothAO) {
                  computeSmoothLighting(x, y, z, 3, blockStates, sidOpaque, lightData, nXNeg, nXPos, nYNeg, nYPos,
                      nZNeg, nZPos, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight, nZPosLight, faceAO,
                      faceLight);
                  if (isFluidBlock)
                    emitWaterFaceAO(targetBuf, x, y, z, 3, fr, fg, fb, a, sidFaceHasSprite[uv],
                        sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv],
                        drop00, drop01, drop11, drop10, faceAO, faceLight);
                  else
                    emitFaceAO(targetBuf, x, y, z, 3, fr, fg, fb, a, sidFaceHasSprite[uv],
                        sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv], topDrop, faceAO,
                        faceLight);
                } else {
                  if (isFluidBlock)
                    emitWaterFace(targetBuf, x, y, z, 3,
                        getFaceLight(lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                            nZPosLight, x, y, z, 3),
                        fr, fg, fb, a,
                        sidFaceHasSprite[uv],
                        sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv],
                        drop00, drop01, drop11, drop10);
                  else
                    emitFaceInlineWater(targetBuf, x, y, z, 3,
                        getFaceLight(lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                            nZPosLight, x, y, z, 3),
                        fr, fg, fb, a,
                        sidFaceHasSprite[uv],
                        sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv], topDrop);
                }
                if (doubleSided) {
                  emitReversedQuad(targetBuf);
                  opaqueQuadCount++;
                }
              }
              if ((sf == null || !sf[2]) &&
                  !shouldCullFace(isWater, blockStates, idx - 16, z, 0, sidOpaque, sidIsWater, nZNeg, y * 16 + x)) {
                int uv = sidBase + 2;
                byte fr = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] && !forceDebugTint) ? (byte) 0xFF : r;
                byte fg = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] && !forceDebugTint) ? (byte) 0xFF : g;
                byte fb = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] && !forceDebugTint) ? (byte) 0xFF : b;
                if (usesTranslucentBuf)
                  waterQuadCount++;
                else
                  opaqueQuadCount++;
                if (useSmoothAO) {
                  computeSmoothLighting(x, y, z, 2, blockStates, sidOpaque, lightData, nXNeg, nXPos, nYNeg, nYPos,
                      nZNeg, nZPos, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight, nZPosLight, faceAO,
                      faceLight);
                  if (isFluidBlock)
                    emitWaterFaceAO(targetBuf, x, y, z, 2, fr, fg, fb, a, sidFaceHasSprite[uv],
                        sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv],
                        drop00, drop01, drop11, drop10, faceAO, faceLight);
                  else
                    emitFaceAO(targetBuf, x, y, z, 2, fr, fg, fb, a, sidFaceHasSprite[uv],
                        sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv], topDrop, faceAO,
                        faceLight);
                } else {
                  if (isFluidBlock)
                    emitWaterFace(targetBuf, x, y, z, 2,
                        getFaceLight(lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                            nZPosLight, x, y, z, 2),
                        fr, fg, fb, a,
                        sidFaceHasSprite[uv],
                        sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv],
                        drop00, drop01, drop11, drop10);
                  else
                    emitFaceInlineWater(targetBuf, x, y, z, 2,
                        getFaceLight(lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                            nZPosLight, x, y, z, 2),
                        fr, fg, fb, a,
                        sidFaceHasSprite[uv],
                        sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv], topDrop);
                }
                if (doubleSided) {
                  emitReversedQuad(targetBuf);
                  opaqueQuadCount++;
                }
              }
              if ((sf == null || !sf[5]) &&
                  !shouldCullFace(isWater, blockStates, idx + 1, x, 15, sidOpaque, sidIsWater, nXPos, y * 16 + z)) {
                int uv = sidBase + 5;
                byte fr = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] && !forceDebugTint) ? (byte) 0xFF : r;
                byte fg = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] && !forceDebugTint) ? (byte) 0xFF : g;
                byte fb = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] && !forceDebugTint) ? (byte) 0xFF : b;
                if (usesTranslucentBuf)
                  waterQuadCount++;
                else
                  opaqueQuadCount++;
                if (useSmoothAO) {
                  computeSmoothLighting(x, y, z, 5, blockStates, sidOpaque, lightData, nXNeg, nXPos, nYNeg, nYPos,
                      nZNeg, nZPos, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight, nZPosLight, faceAO,
                      faceLight);
                  if (isFluidBlock)
                    emitWaterFaceAO(targetBuf, x, y, z, 5, fr, fg, fb, a, sidFaceHasSprite[uv],
                        sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv],
                        drop00, drop01, drop11, drop10, faceAO, faceLight);
                  else
                    emitFaceAO(targetBuf, x, y, z, 5, fr, fg, fb, a, sidFaceHasSprite[uv],
                        sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv], topDrop, faceAO,
                        faceLight);
                } else {
                  if (isFluidBlock)
                    emitWaterFace(targetBuf, x, y, z, 5,
                        getFaceLight(lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                            nZPosLight, x, y, z, 5),
                        fr, fg, fb, a,
                        sidFaceHasSprite[uv],
                        sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv],
                        drop00, drop01, drop11, drop10);
                  else
                    emitFaceInlineWater(targetBuf, x, y, z, 5,
                        getFaceLight(lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                            nZPosLight, x, y, z, 5),
                        fr, fg, fb, a,
                        sidFaceHasSprite[uv],
                        sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv], topDrop);
                }
                if (doubleSided) {
                  emitReversedQuad(targetBuf);
                  opaqueQuadCount++;
                }
              }
              if ((sf == null || !sf[4]) &&
                  !shouldCullFace(isWater, blockStates, idx - 1, x, 0, sidOpaque, sidIsWater, nXNeg, y * 16 + z)) {
                int uv = sidBase + 4;
                byte fr = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] && !forceDebugTint) ? (byte) 0xFF : r;
                byte fg = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] && !forceDebugTint) ? (byte) 0xFF : g;
                byte fb = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] && !forceDebugTint) ? (byte) 0xFF : b;
                if (usesTranslucentBuf)
                  waterQuadCount++;
                else
                  opaqueQuadCount++;
                if (useSmoothAO) {
                  computeSmoothLighting(x, y, z, 4, blockStates, sidOpaque, lightData, nXNeg, nXPos, nYNeg, nYPos,
                      nZNeg, nZPos, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight, nZPosLight, faceAO,
                      faceLight);
                  if (isFluidBlock)
                    emitWaterFaceAO(targetBuf, x, y, z, 4, fr, fg, fb, a, sidFaceHasSprite[uv],
                        sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv],
                        drop00, drop01, drop11, drop10, faceAO, faceLight);
                  else
                    emitFaceAO(targetBuf, x, y, z, 4, fr, fg, fb, a, sidFaceHasSprite[uv],
                        sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv], topDrop, faceAO,
                        faceLight);
                } else {
                  if (isFluidBlock)
                    emitWaterFace(targetBuf, x, y, z, 4,
                        getFaceLight(lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                            nZPosLight, x, y, z, 4),
                        fr, fg, fb, a,
                        sidFaceHasSprite[uv],
                        sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv],
                        drop00, drop01, drop11, drop10);
                  else
                    emitFaceInlineWater(targetBuf, x, y, z, 4,
                        getFaceLight(lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                            nZPosLight, x, y, z, 4),
                        fr, fg, fb, a,
                        sidFaceHasSprite[uv],
                        sidFaceUMin[uv], sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv], topDrop);
                }
                if (doubleSided) {
                  emitReversedQuad(targetBuf);
                  opaqueQuadCount++;
                }
              }
            }
          }
        }
        fallbackBlocks = 4096;
      } else {
        int maxSidLod01 = 0;
        if (blockStates != null)
          for (int i = 0; i < 4096; i++) {
            if (blockStates[i] > maxSidLod01)
              maxSidLod01 = blockStates[i];
          }
        if (nXNeg != null)
          for (int s : nXNeg) {
            if (s > maxSidLod01)
              maxSidLod01 = s;
          }
        if (nXPos != null)
          for (int s : nXPos) {
            if (s > maxSidLod01)
              maxSidLod01 = s;
          }
        if (nYNeg != null)
          for (int s : nYNeg) {
            if (s > maxSidLod01)
              maxSidLod01 = s;
          }
        if (nYPos != null)
          for (int s : nYPos) {
            if (s > maxSidLod01)
              maxSidLod01 = s;
          }
        if (nZNeg != null)
          for (int s : nZNeg) {
            if (s > maxSidLod01)
              maxSidLod01 = s;
          }
        if (nZPos != null)
          for (int s : nZPos) {
            if (s > maxSidLod01)
              maxSidLod01 = s;
          }

        int l0Needed = maxSidLod01 + 1;
        int l0UvSize = l0Needed * 6;
        Lod1SidDataArrays _l1 = LOD1_SID_DATA_POOL.get();
        _l1.ensureCapacity(l0Needed, l0UvSize);
        _l1.clearUsed(l0Needed, l0UvSize);
        byte[] oFlag = _l1.oFlag;
        BlockState[] stateArr = _l1.stateArr;
        BlockStateModel[] modelArr = _l1.modelArr;
        boolean[] modelComputed = _l1.modelComputed;
        boolean[] sidPropsComputed = _l1.sidPropsComputed;
        boolean[] sidIsAir = _l1.sidIsAir;
        boolean[] sidShouldSkip = _l1.sidShouldSkip;
        byte[] sidTintR = _l1.sidTintR;
        byte[] sidTintG = _l1.sidTintG;
        byte[] sidTintB = _l1.sidTintB;
        boolean[] sidIsLeaf = _l1.sidIsLeaf;
        boolean[] sidForceOpaque = _l1.sidForceOpaque;
        byte[] sidBlockAlpha = _l1.sidBlockAlpha;
        boolean[] sidIsNonFull = _l1.sidIsNonFull;
        boolean[] sidIsWaterLod0 = _l1.sidIsWaterLod0;
        boolean[] sidIsWaterloggedLod0 = _l1.sidIsWaterloggedLod0;
        boolean[] sidIsFluidLod0 = _l1.sidIsFluidLod0;
        byte[] sidBiomeTintType = _l1.sidBiomeTintType;
        boolean[] sidOpaque = _l1.sidOpaque;
        boolean[] sidIsFullCube = _l1.sidIsFullCube;
        boolean[] sidIsTransCube = _l1.sidIsTransCube;
        short[] l0FaceUMin = _l1.l0FaceUMin;
        short[] l0FaceUMax = _l1.l0FaceUMax;
        short[] l0FaceVMin = _l1.l0FaceVMin;
        short[] l0FaceVMax = _l1.l0FaceVMax;
        boolean[] l0FaceHasSprite = _l1.l0FaceHasSprite;
        boolean[] l0FaceHasTint = _l1.l0FaceHasTint;
        RandomSource rand = REUSABLE_RANDOM.get();
        int[] sectionBiomeColors = getSectionBiomeColors(
            mc.level, chunkX, chunkY, chunkZ,
            MetalRenderClient.getConfig().biomeTransitionDetail);
        sidShouldSkip[0] = true;
        if (blockStates != null) {
          for (int i = 0; i < 4096; i++) {
            int sid = blockStates[i];
            if (sid == 0 || sidPropsComputed[sid])
              continue;
            sidPropsComputed[sid] = true;
            BlockState bs = Block.stateById(sid);
            stateArr[sid] = bs;
            if (bs.isAir()) {
              sidShouldSkip[sid] = true;
              sidIsAir[sid] = true;
              continue;
            }
            if (lodLevel > 0 && !shouldRenderAtLod(bs, lodLevel)) {
              sidShouldSkip[sid] = true;
              continue;
            }
            Block blk = bs.getBlock();
            boolean isLeaf = isLeafBlock(blk);
            boolean isWater = (blk == Blocks.WATER);
            boolean isLava = (blk == Blocks.LAVA);
            boolean isFluid = isWater || isLava;
            sidIsLeaf[sid] = isLeaf;
            sidForceOpaque[sid] = isLeaf && leafMode == 0;

            sidBlockAlpha[sid] = isWater ? WATER_ALPHA : (isLeaf ? (byte) (leafMode == 0 ? 254 : 253) : (byte) 255);
            sidIsNonFull[sid] = !bs.isSolidRender() && !isFluid && !isLeaf;
            sidIsWaterLod0[sid] = isWater;
            sidIsFluidLod0[sid] = isFluid;
            sidIsWaterloggedLod0[sid] = !isWater && !isLava && !bs.getFluidState().isEmpty();
            sidOpaque[sid] = bs.isSolidRender() || (isLeaf && leafMode == 0);
            boolean fullCube = bs.isSolidRender() && !isFluid;
            sidIsFullCube[sid] = fullCube;

            sidIsTransCube[sid] = !isFluid && !isLeaf && !bs.isSolidRender() && isFullCubeShape(bs);
            byte tintType = getBiomeTintType(blk);
            sidBiomeTintType[sid] = tintType;
            int tintColor;
            if (tintType != TINT_NONE && tintType < sectionBiomeColors.length) {
              tintColor = sectionBiomeColors[tintType];
            } else {
              tintColor = getBlockColor(bs);
            }
            sidTintR[sid] = (byte) ((tintColor >> 16) & 0xFF);
            sidTintG[sid] = (byte) ((tintColor >> 8) & 0xFF);
            sidTintB[sid] = (byte) (tintColor & 0xFF);

            if (blk == Blocks.GRASS_BLOCK && meshBuildDiagCount < 3) {
              meshBuildDiagCount++;
              MetalLogger.info("[TINT_DIAG] GRASS_BLOCK sid=%d tintType=%d " +
                  "sectionBiomeColors[TINT_GRASS]=0x%06X tintColor=0x%06X " +
                  "tintR=0x%02X chunk[%d,%d,%d]",
                  sid, tintType,
                  (sectionBiomeColors.length > TINT_GRASS ? sectionBiomeColors[TINT_GRASS] : -1),
                  tintColor,
                  sidTintR[sid] & 0xFF,
                  chunkX, chunkY, chunkZ);
            }
            if (blockModels != null) {
              try {
                modelArr[sid] = blockModels.get(bs);
              } catch (Exception ignored) {
              }
              modelComputed[sid] = true;
              if (fullCube && modelArr[sid] != null) {
                try {
                  BlockStateModel mdl = modelArr[sid];
                  rand.setSeed(42L);
                  List<BlockStateModelPart> mdlParts = new java.util.ArrayList<>();
                  mdl.collectParts(rand, mdlParts);
                  boolean blockHasTint = BIOME_TINT_TYPE.containsKey(blk);
                  if (!mdlParts.isEmpty()) {

                    boolean hasMultiQuad = false;
                    for (Direction chkDir : ALL_DIRECTIONS) {
                      int qCount = 0;
                      for (BlockStateModelPart chkPart : mdlParts) {
                        List<BakedQuad> cq = chkPart.getQuads(chkDir);
                        if (cq != null)
                          qCount += cq.size();
                      }
                      if (qCount > 1) {
                        hasMultiQuad = true;
                        break;
                      }
                    }
                    if (hasMultiQuad) {

                      sidIsFullCube[sid] = false;
                    } else
                      for (int fdir = 0; fdir < 6; fdir++) {
                        int uvIdx = sid * 6 + fdir;
                        Direction d = ALL_DIRECTIONS[fdir];
                        boolean foundTinted = false;
                        for (BlockStateModelPart mdlPart : mdlParts) {
                          List<BakedQuad> fQuads = mdlPart.getQuads(d);
                          if (fQuads != null && fQuads.size() >= 1) {
                            BakedQuad fq = fQuads.get(0);
                            boolean qTint = fq.materialInfo().isTinted() || fq.materialInfo().tintIndex() >= 0 ||
                                blockHasTint;

                            if (!l0FaceHasSprite[uvIdx] || (qTint && !foundTinted)) {
                              float minU = Float.MAX_VALUE, maxU = -Float.MAX_VALUE;
                              float minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
                              for (int fv = 0; fv < 4; fv++) {
                                long puv = fq.packedUV(fv);
                                float fu = Float.intBitsToFloat((int) (puv >> 32));
                                float fvv = Float.intBitsToFloat((int) puv);
                                minU = Math.min(minU, fu);
                                maxU = Math.max(maxU, fu);
                                minV = Math.min(minV, fvv);
                                maxV = Math.max(maxV, fvv);
                              }
                              l0FaceUMin[uvIdx] = (short) (minU * 65535.0f);
                              l0FaceUMax[uvIdx] = (short) (maxU * 65535.0f);
                              l0FaceVMin[uvIdx] = (short) (minV * 65535.0f);
                              l0FaceVMax[uvIdx] = (short) (maxV * 65535.0f);
                              l0FaceHasSprite[uvIdx] = true;
                              l0FaceHasTint[uvIdx] = qTint;
                              if (qTint)
                                foundTinted = true;
                            }
                            if (foundTinted)
                              break;
                          }
                        }
                      }
                  } else {

                    TextureAtlasSprite fallback = mdl.particleMaterial().sprite();
                    if (fallback != null) {
                      short fUMin = (short) (fallback.getU0() * 65535.0f);
                      short fUMax = (short) (fallback.getU1() * 65535.0f);
                      short fVMin = (short) (fallback.getV0() * 65535.0f);
                      short fVMax = (short) (fallback.getV1() * 65535.0f);
                      for (int fdir = 0; fdir < 6; fdir++) {
                        int uvIdx = sid * 6 + fdir;
                        l0FaceHasSprite[uvIdx] = true;
                        l0FaceHasTint[uvIdx] = blockHasTint;
                        l0FaceUMin[uvIdx] = fUMin;
                        l0FaceUMax[uvIdx] = fUMax;
                        l0FaceVMin[uvIdx] = fVMin;
                        l0FaceVMax[uvIdx] = fVMax;
                      }
                    }
                  }
                } catch (Exception ignored) {
                }
              }
            }
          }
        }
        TextureAtlasSprite waterSpriteLod0 = null;
        if (blockModels != null) {
          try {
            var wModel = blockModels.get(Blocks.WATER.defaultBlockState());
            if (wModel != null)
              waterSpriteLod0 = wModel.particleMaterial().sprite();
          } catch (Exception ignored) {
          }
        }
        float[] faceAO = FACE_AO_POOL.get();
        byte[] faceLight = FACE_LIGHT_POOL.get();

        int biomeDetail = MetalRenderClient.getConfig().biomeTransitionDetail;
        int[] colGrass = null, colWater = null;
        if (biomeDetail >= 1 && mc != null && mc.level != null) {
          int blurR = Math.min(biomeDetail, 8);
          int padded = 16 + 2 * blurR;
          int syCol = Math.max(63, chunkY * 16 + 8);
          int bxOrig = chunkX * 16 - blurR;
          int bzOrig = chunkZ * 16 - blurR;
          BlockPos.MutableBlockPos bpCol = BIOME_POS_POOL.get();
          try {

            int[] pgGrass = new int[padded * padded];
            int[] pgWater = new int[padded * padded];
            for (int lz = 0; lz < padded; lz++) {
              for (int lx = 0; lx < padded; lx++) {
                bpCol.set(bxOrig + lx, syCol, bzOrig + lz);
                int ci = lz * padded + lx;
                pgGrass[ci] = mc.level.getBlockTint(bpCol,
                    net.minecraft.client.renderer.BiomeColors.GRASS_COLOR_RESOLVER);
                pgWater[ci] = mc.level.getBlockTint(bpCol,
                    net.minecraft.client.renderer.BiomeColors.WATER_COLOR_RESOLVER);
              }
            }

            pgGrass = boxBlurColors(pgGrass, padded, padded, blurR);
            pgWater = boxBlurColors(pgWater, padded, padded, blurR);
            colGrass = new int[256];
            colWater = new int[256];
            for (int lz = 0; lz < 16; lz++) {
              for (int lx = 0; lx < 16; lx++) {
                colGrass[lz * 16 + lx] = pgGrass[(lz + blurR) * padded + (lx + blurR)];
                colWater[lz * 16 + lx] = pgWater[(lz + blurR) * padded + (lx + blurR)];
              }
            }
          } catch (Exception e) {
            colGrass = colWater = null;
          }
        }
        for (int y = 0; y < SECTION_SIZE; y++) {
          for (int z = 0; z < SECTION_SIZE; z++) {
            for (int x = 0; x < SECTION_SIZE; x++) {
              int idx = y * 256 + z * 16 + x;
              int stateId = blockStates != null && idx < blockStates.length
                  ? blockStates[idx]
                  : 0;
              if (stateId == 0 || sidShouldSkip[stateId])
                continue;
              byte light = lightData != null && idx < lightData.length
                  ? lightData[idx]
                  : 0;
              byte packedLight = (byte) ((light & 0xF) | ((light >> 4) & 0xF) << 4);

              byte tintR, tintG, tintB;
              if (colGrass != null) {
                byte tt = sidBiomeTintType[stateId];
                int colIdx = z * 16 + x;
                int col;
                if (tt == TINT_GRASS)
                  col = colGrass[colIdx];
                else if (tt == TINT_WATER)
                  col = colWater[colIdx];
                else
                  col = -1;
                if (col != -1) {
                  tintR = (byte) ((col >> 16) & 0xFF);
                  tintG = (byte) ((col >> 8) & 0xFF);
                  tintB = (byte) (col & 0xFF);
                } else {
                  tintR = sidTintR[stateId];
                  tintG = sidTintG[stateId];
                  tintB = sidTintB[stateId];
                }
              } else {
                tintR = sidTintR[stateId];
                tintG = sidTintG[stateId];
                tintB = sidTintB[stateId];
              }
              boolean forceDebugTint = MetalRenderConfig.debugPinkBlockTint();
              if (forceDebugTint) {
                tintR = (byte) 0xFF;
                tintG = (byte) 0x30;
                tintB = (byte) 0xB0;
              }
              boolean forceOpaque = sidForceOpaque[stateId];
              byte blockAlpha = sidBlockAlpha[stateId];
              boolean isNonFullBlock = sidIsNonFull[stateId];
              boolean isWaterBaked = sidIsWaterLod0[stateId];

              if (sidIsFullCube[stateId]) {
                for (int face = 0; face < 6; face++) {
                  if (skipFace != null && skipFace[face])
                    continue;
                  int nx2 = x, ny2 = y, nz2 = z;
                  switch (face) {
                    case 0:
                      ny2--;
                      break;
                    case 1:
                      ny2++;
                      break;
                    case 2:
                      nz2--;
                      break;
                    case 3:
                      nz2++;
                      break;
                    case 4:
                      nx2--;
                      break;
                    case 5:
                      nx2++;
                      break;
                  }
                  if (!isTransparentFlat(blockStates, nx2, ny2, nz2, leafMode,
                      nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos, oFlag))
                    continue;
                  if (opaqueQuadCount >= MAX_QUADS)
                    break;
                  int uvIdx = stateId * 6 + face;
                  if (lightData != null) {
                    computeSmoothLighting(x, y, z, face, blockStates, sidOpaque, lightData,
                        nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos, nXNegLight, nXPosLight, nYNegLight, nYPosLight,
                        nZNegLight, nZPosLight, faceAO, faceLight);
                  } else {
                    faceAO[0] = faceAO[1] = faceAO[2] = faceAO[3] = 1.0f;
                    faceLight[0] = faceLight[1] = faceLight[2] = faceLight[3] = (byte) 0xF0;
                  }
                  byte fR = (l0FaceHasTint[uvIdx] || forceDebugTint) ? tintR : (byte) 255;
                  byte fG = (l0FaceHasTint[uvIdx] || forceDebugTint) ? tintG : (byte) 255;
                  byte fB = (l0FaceHasTint[uvIdx] || forceDebugTint) ? tintB : (byte) 255;
                  emitFaceAO(vertexBuffer, x, y, z, face, fR, fG, fB,
                      forceOpaque ? (byte) 254 : blockAlpha,
                      l0FaceHasSprite[uvIdx], l0FaceUMin[uvIdx], l0FaceUMax[uvIdx],
                      l0FaceVMin[uvIdx], l0FaceVMax[uvIdx], 0, faceAO, faceLight);
                  opaqueQuadCount++;

                  if (!forceOpaque && blockAlpha == (byte) 253) {
                    emitReversedQuad(vertexBuffer);
                    opaqueQuadCount++;
                  }
                }
                bakedQuadBlocks++;
                continue;
              }
              if (blockModels != null && !sidIsFluidLod0[stateId]) {
                try {
                  boolean forceTintQuad = false;
                  BlockStateModel model = modelArr[stateId];
                  if (model != null) {
                    long seed = (long) (chunkX * 16 + x) * 3129871L ^
                        (long) (chunkZ * 16 + z) * 116129781L ^
                        (long) (chunkY * 16 + y);
                    rand.setSeed(seed);
                    int quadsThisBlock = 0;
                    List<BlockStateModelPart> parts = new java.util.ArrayList<>();
                    model.collectParts(rand, parts);
                    for (BlockStateModelPart part : parts) {
                      for (Direction dir : ALL_DIRECTIONS) {
                        if (skipFace != null && skipFace[dir.ordinal()])
                          continue;
                        int nx = x + dir.getStepX();
                        int ny = y + dir.getStepY();
                        int nz = z + dir.getStepZ();
                        if (isWaterBaked && isWaterAt(blockStates, nx, ny, nz,
                            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos)) {
                          dbgWaterBakedCull++;
                          continue;
                        }
                        if (!isTransparentFlatFor(blockStates, nx, ny, nz, leafMode,
                            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos, oFlag,
                            stateId < sidIsTransCube.length && sidIsTransCube[stateId]))
                          continue;
                        List<BakedQuad> quads = part.getQuads(dir);
                        if (quads != null) {
                          boolean aoComputed = false;
                          for (BakedQuad quad : quads) {
                            if (opaqueQuadCount + waterQuadCount >= MAX_QUADS)
                              break;
                            ByteBuffer tbuf = isWaterBaked ? waterBuffer : vertexBuffer;
                            int emitted;
                            if (lightData != null) {
                              if (!aoComputed) {
                                computeSmoothLighting(x, y, z, dir.ordinal(), blockStates,
                                    sidOpaque, lightData, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos,
                                    nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight, nZPosLight,
                                    faceAO, faceLight);
                                aoComputed = true;
                              }
                              emitted = emitBakedQuadAO(tbuf, quad, x, y, z, tintR, tintG, tintB,
                                  forceOpaque, forceTintQuad, blockAlpha, faceAO, faceLight);
                            } else {
                              emitted = emitBakedQuad(tbuf, quad, x, y,
                                  z,
                                  getFaceLight(lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                                      nZPosLight, x, y, z, dir.ordinal()),
                                  tintR,
                                  tintG, tintB, forceOpaque, forceTintQuad,
                                  blockAlpha);
                            }
                            if (isWaterBaked)
                              waterQuadCount += emitted;
                            else
                              opaqueQuadCount += emitted;

                            if (!forceOpaque && blockAlpha == (byte) 253 && emitted > 0) {
                              emitReversedQuad(tbuf);
                              opaqueQuadCount++;
                            }
                            quadsThisBlock++;
                          }
                        }
                      }
                      if (!skipNonDirectionalQuads) {
                        List<BakedQuad> nonDirQuads = part.getQuads(null);
                        if (nonDirQuads != null) {
                          for (BakedQuad quad : nonDirQuads) {
                            if (opaqueQuadCount + waterQuadCount >= MAX_QUADS)
                              break;
                            ByteBuffer tbuf = isWaterBaked ? waterBuffer : vertexBuffer;
                            int emitted = emitBakedQuad(tbuf, quad, x, y, z,
                                packedLight, tintR, tintG,
                                tintB, forceOpaque, forceTintQuad,
                                blockAlpha);
                            if (isWaterBaked)
                              waterQuadCount += emitted;
                            else
                              opaqueQuadCount += emitted;
                            quadsThisBlock++;
                          }
                        }
                      }
                    }
                    if (quadsThisBlock > 0) {
                      if (isWaterBaked)
                        dbgWaterBaked++;
                      bakedQuadBlocks++;
                      if (sidIsWaterloggedLod0[stateId] && waterSpriteLod0 != null) {
                        byte wAlpha = WATER_ALPHA;
                        int wd00 = computeCornerFluidDrop(blockStates, x, y, z, sidIsWaterLod0, nXNeg, nXPos, nYNeg,
                            nYPos, nZNeg, nZPos);
                        int wd01 = computeCornerFluidDrop(blockStates, x, y, z + 1, sidIsWaterLod0, nXNeg, nXPos, nYNeg,
                            nYPos, nZNeg, nZPos);
                        int wd11 = computeCornerFluidDrop(blockStates, x + 1, y, z + 1, sidIsWaterLod0, nXNeg, nXPos,
                            nYNeg, nYPos, nZNeg, nZPos);
                        int wd10 = computeCornerFluidDrop(blockStates, x + 1, y, z, sidIsWaterLod0, nXNeg, nXPos, nYNeg,
                            nYPos, nZNeg, nZPos);
                        if (!isWaterAt(blockStates, x, y + 1, z, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos) &&
                            isTransparentFlat(blockStates, x, y + 1, z, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg,
                                nZPos, oFlag))
                          waterQuadCount += emitFaceWaterSmooth(waterBuffer, x, y, z, 1, waterSpriteLod0, packedLight,
                              tintR,
                              tintG, tintB, wAlpha, wd00, wd01, wd11, wd10);
                        if (!isWaterAt(blockStates, x, y - 1, z, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos) &&
                            isTransparentFlat(blockStates, x, y - 1, z, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg,
                                nZPos, oFlag))
                          waterQuadCount += emitFaceWaterSmooth(waterBuffer, x, y, z, 0, waterSpriteLod0, packedLight,
                              tintR,
                              tintG, tintB, wAlpha, 0, 0, 0, 0);
                        if (!isWaterAt(blockStates, x, y, z + 1, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos) &&
                            isTransparentFlat(blockStates, x, y, z + 1, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg,
                                nZPos, oFlag))
                          waterQuadCount += emitFaceWaterSmooth(waterBuffer, x, y, z, 3, waterSpriteLod0, packedLight,
                              tintR,
                              tintG, tintB, wAlpha, wd00, wd01, wd11, wd10);
                        if (!isWaterAt(blockStates, x, y, z - 1, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos) &&
                            isTransparentFlat(blockStates, x, y, z - 1, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg,
                                nZPos, oFlag))
                          waterQuadCount += emitFaceWaterSmooth(waterBuffer, x, y, z, 2, waterSpriteLod0, packedLight,
                              tintR,
                              tintG, tintB, wAlpha, wd00, wd01, wd11, wd10);
                        if (!isWaterAt(blockStates, x + 1, y, z, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos) &&
                            isTransparentFlat(blockStates, x + 1, y, z, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg,
                                nZPos, oFlag))
                          waterQuadCount += emitFaceWaterSmooth(waterBuffer, x, y, z, 5, waterSpriteLod0, packedLight,
                              tintR,
                              tintG, tintB, wAlpha, wd00, wd01, wd11, wd10);
                        if (!isWaterAt(blockStates, x - 1, y, z, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos) &&
                            isTransparentFlat(blockStates, x - 1, y, z, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg,
                                nZPos, oFlag))
                          waterQuadCount += emitFaceWaterSmooth(waterBuffer, x, y, z, 4, waterSpriteLod0, packedLight,
                              tintR,
                              tintG, tintB, wAlpha, wd00, wd01, wd11, wd10);
                      }
                      continue;
                    }
                  }
                } catch (Exception e) {
                }
              }
              fallbackBlocks++;
              if (isWaterBaked)
                dbgWaterFallback++;
              if (isNonFullBlock) {
                if (sidIsWaterloggedLod0[stateId] && waterSpriteLod0 != null) {
                  byte wAlpha = WATER_ALPHA;
                  int wd00 = computeCornerFluidDrop(blockStates, x, y, z, sidIsWaterLod0, nXNeg, nXPos, nYNeg, nYPos,
                      nZNeg, nZPos);
                  int wd01 = computeCornerFluidDrop(blockStates, x, y, z + 1, sidIsWaterLod0, nXNeg, nXPos, nYNeg,
                      nYPos, nZNeg, nZPos);
                  int wd11 = computeCornerFluidDrop(blockStates, x + 1, y, z + 1, sidIsWaterLod0, nXNeg, nXPos, nYNeg,
                      nYPos, nZNeg, nZPos);
                  int wd10 = computeCornerFluidDrop(blockStates, x + 1, y, z, sidIsWaterLod0, nXNeg, nXPos, nYNeg,
                      nYPos, nZNeg, nZPos);
                  if (!isWaterAt(blockStates, x, y + 1, z, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos) &&
                      isTransparentFlat(blockStates, x, y + 1, z, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos,
                          oFlag))
                    waterQuadCount += emitFaceWaterSmooth(waterBuffer, x, y, z, 1, waterSpriteLod0, packedLight, tintR,
                        tintG,
                        tintB, wAlpha, wd00, wd01, wd11, wd10);
                  if (!isWaterAt(blockStates, x, y - 1, z, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos) &&
                      isTransparentFlat(blockStates, x, y - 1, z, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos,
                          oFlag))
                    waterQuadCount += emitFaceWaterSmooth(waterBuffer, x, y, z, 0, waterSpriteLod0, packedLight, tintR,
                        tintG,
                        tintB, wAlpha, 0, 0, 0, 0);
                  if (!isWaterAt(blockStates, x, y, z + 1, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos) &&
                      isTransparentFlat(blockStates, x, y, z + 1, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos,
                          oFlag))
                    waterQuadCount += emitFaceWaterSmooth(waterBuffer, x, y, z, 3, waterSpriteLod0, packedLight, tintR,
                        tintG,
                        tintB, wAlpha, wd00, wd01, wd11, wd10);
                  if (!isWaterAt(blockStates, x, y, z - 1, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos) &&
                      isTransparentFlat(blockStates, x, y, z - 1, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos,
                          oFlag))
                    waterQuadCount += emitFaceWaterSmooth(waterBuffer, x, y, z, 2, waterSpriteLod0, packedLight, tintR,
                        tintG,
                        tintB, wAlpha, wd00, wd01, wd11, wd10);
                  if (!isWaterAt(blockStates, x + 1, y, z, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos) &&
                      isTransparentFlat(blockStates, x + 1, y, z, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos,
                          oFlag))
                    waterQuadCount += emitFaceWaterSmooth(waterBuffer, x, y, z, 5, waterSpriteLod0, packedLight, tintR,
                        tintG,
                        tintB, wAlpha, wd00, wd01, wd11, wd10);
                  if (!isWaterAt(blockStates, x - 1, y, z, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos) &&
                      isTransparentFlat(blockStates, x - 1, y, z, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos,
                          oFlag))
                    waterQuadCount += emitFaceWaterSmooth(waterBuffer, x, y, z, 4, waterSpriteLod0, packedLight, tintR,
                        tintG,
                        tintB, wAlpha, wd00, wd01, wd11, wd10);
                }
              }
              TextureAtlasSprite sprite = (modelArr[stateId] != null)
                  ? modelArr[stateId].particleMaterial().sprite()
                  : null;
              boolean isWaterBlock = sidIsWaterLod0[stateId];
              boolean isFluidBlock = sidIsFluidLod0[stateId];
              int wd00, wd01, wd11, wd10;
              if (isFluidBlock) {
                boolean[] heightMask = isWaterBlock ? sidIsWaterLod0 : sidIsFluidLod0;
                wd00 = computeCornerFluidDrop(blockStates, x, y, z, heightMask, nXNeg, nXPos, nYNeg, nYPos, nZNeg,
                    nZPos);
                wd01 = computeCornerFluidDrop(blockStates, x, y, z + 1, heightMask, nXNeg, nXPos, nYNeg, nYPos,
                    nZNeg, nZPos);
                wd11 = computeCornerFluidDrop(blockStates, x + 1, y, z + 1, heightMask, nXNeg, nXPos, nYNeg, nYPos,
                    nZNeg, nZPos);
                wd10 = computeCornerFluidDrop(blockStates, x + 1, y, z, heightMask, nXNeg, nXPos, nYNeg, nYPos,
                    nZNeg, nZPos);
              } else {
                wd00 = 0;
                wd01 = 0;
                wd11 = 0;
                wd10 = 0;
              }
              ByteBuffer fbuf = isWaterBlock ? waterBuffer : vertexBuffer;
              if ((!isWaterBlock || !isWaterAt(blockStates, x, y + 1, z, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos)) &&
                  isTransparentFlat(blockStates, x, y + 1, z, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos,
                      oFlag)) {
                int emitted = emitFaceWaterSmooth(fbuf, x, y, z, 1, sprite, packedLight, tintR, tintG, tintB,
                    blockAlpha, wd00, wd01, wd11, wd10);
                if (isWaterBlock)
                  waterQuadCount += emitted;
                else
                  opaqueQuadCount += emitted;
              }
              if ((!isWaterBlock || !isWaterAt(blockStates, x, y - 1, z, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos)) &&
                  isTransparentFlat(blockStates, x, y - 1, z, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos,
                      oFlag)) {
                int emitted = emitFaceWaterSmooth(fbuf, x, y, z, 0, sprite, packedLight, tintR, tintG, tintB,
                    blockAlpha, 0, 0, 0, 0);
                if (isWaterBlock)
                  waterQuadCount += emitted;
                else
                  opaqueQuadCount += emitted;
              }
              if ((!isWaterBlock || !isWaterAt(blockStates, x, y, z + 1, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos)) &&
                  isTransparentFlat(blockStates, x, y, z + 1, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos,
                      oFlag)) {
                int emitted = emitFaceWaterSmooth(fbuf, x, y, z, 3, sprite, packedLight, tintR, tintG, tintB,
                    blockAlpha, wd00, wd01, wd11, wd10);
                if (isWaterBlock)
                  waterQuadCount += emitted;
                else
                  opaqueQuadCount += emitted;
              }
              if ((!isWaterBlock || !isWaterAt(blockStates, x, y, z - 1, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos)) &&
                  isTransparentFlat(blockStates, x, y, z - 1, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos,
                      oFlag)) {
                int emitted = emitFaceWaterSmooth(fbuf, x, y, z, 2, sprite, packedLight, tintR, tintG, tintB,
                    blockAlpha, wd00, wd01, wd11, wd10);
                if (isWaterBlock)
                  waterQuadCount += emitted;
                else
                  opaqueQuadCount += emitted;
              }
              if ((!isWaterBlock || !isWaterAt(blockStates, x + 1, y, z, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos)) &&
                  isTransparentFlat(blockStates, x + 1, y, z, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos,
                      oFlag)) {
                int emitted = emitFaceWaterSmooth(fbuf, x, y, z, 5, sprite, packedLight, tintR, tintG, tintB,
                    blockAlpha, wd00, wd01, wd11, wd10);
                if (isWaterBlock)
                  waterQuadCount += emitted;
                else
                  opaqueQuadCount += emitted;
              }
              if ((!isWaterBlock || !isWaterAt(blockStates, x - 1, y, z, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos)) &&
                  isTransparentFlat(blockStates, x - 1, y, z, leafMode, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos,
                      oFlag)) {
                int emitted = emitFaceWaterSmooth(fbuf, x, y, z, 4, sprite, packedLight, tintR, tintG, tintB,
                    blockAlpha, wd00, wd01, wd11, wd10);
                if (isWaterBlock)
                  waterQuadCount += emitted;
                else
                  opaqueQuadCount += emitted;
              }
            }
          }
        }
      }
      int quadCount = opaqueQuadCount + waterQuadCount;
      if (waterQuadCount > 0) {
        waterBuffer.flip();
        vertexBuffer.put(waterBuffer);
      }
      if (meshBuildCount <= 3) {
        MetalLogger.info("[BUILD_V9 DIAG] chunk[%d,%d,%d] quads=%d opaque=%d water=%d baked=%d fallback=%d lod=%d",
            chunkX, chunkY, chunkZ, quadCount, opaqueQuadCount, waterQuadCount,
            bakedQuadBlocks, fallbackBlocks, lodLevel);
      }
      if (quadCount == 0) {
        if (!isCurrent(key, genAtSubmit)) {
          return;
        }
        queueClear(key, genAtSubmit, chunkX, chunkY, chunkZ);
        keepPending = true;
        return;
      }
      vertexBuffer.flip();
      int dataLen = quadCount * 4 * VERTEX_STRIDE;
      if (!isCurrent(key, genAtSubmit)) {
        return;
      }
      queueUpload(new UploadJob(
          key,
          genAtSubmit,
          approxLight,
          chunkX,
          chunkY,
          chunkZ,
          lodLevel,
          quadCount,
          opaqueQuadCount,
          buildPCX,
          buildPCY,
          buildPCZ,
          copyUploadData(vertexBuffer, dataLen),
          buildFarFieldDigest(chunkX, chunkY, chunkZ, blockStates, lightData,
              genAtSubmit)));
      keepPending = true;
    } catch (Exception e) {
      MetalLogger.error("Meshing error for chunk [%d,%d,%d]", chunkX, chunkY,
          chunkZ);
    } finally {
      long buildElapsed = System.nanoTime() - buildStart;
      meshBuildTimeAcc += buildElapsed;
      if (lodLevel < 1) {
        lodSlowTimeAcc += buildElapsed;
        lodSlowCount++;
      } else {
        lodFastTimeAcc += buildElapsed;
        lodFastCount++;
      }
      int samples = ++meshBuildTimeSamples;
      if (MetalRenderConfig.isDeepDebugActive() && samples % 500 == 0) {
        double avgMs = (meshBuildTimeAcc / 1e6) / samples;
        double slowAvg = lodSlowCount > 0 ? (lodSlowTimeAcc / 1e6) / lodSlowCount : 0;
        double fastAvg = lodFastCount > 0 ? (lodFastTimeAcc / 1e6) / lodFastCount : 0;
        MetalLogger.info(
            "MESH_PERF: avg=%.2fms over %d builds | LOD0: %.2fms (%d) LOD1+: %.2fms (%d) | pipeline=%.2fms (%d)",
            avgMs, samples, slowAvg, lodSlowCount, fastAvg, lodFastCount,
            pipelineCount > 0 ? (pipelineTimeAcc / 1e6) / pipelineCount : 0.0, pipelineCount);
        MetalLogger.info(
            "WATER_DBG: boundaryCullHit=%d boundaryWaterMiss=%d boundaryNullArr=%d | waterBaked=%d waterFallback=%d waterBakedFaceCull=%d nonFullSkip=%d",
            dbgBoundaryCullHit, dbgBoundaryWaterMiss, dbgBoundaryNullArr,
            dbgWaterBaked, dbgWaterFallback, dbgWaterBakedCull, dbgNonFullSkip);
      }
      if (!keepPending) {
        synchronized (pendingKeys) {
          pendingKeys.remove(key);
        }
      }
    }
  }

  public ChunkMeshData buildMesh(int chunkX, int chunkY, int chunkZ,
      int[] blockStates, byte[] lightData) {
    buildMeshAsync(chunkX, chunkY, chunkZ, blockStates, lightData);
    return null;
  }

  private static LevelChunkSection resolveSection(ClientLevel world, int chunkX,
      int chunkY, int chunkZ) {
    if (world == null)
      return null;
    LevelChunk chunk = world.getChunkSource().getChunkNow(chunkX, chunkZ);
    if (chunk == null)
      return null;
    int sectionIdx = chunk.getSectionIndexFromSectionY(chunkY);
    LevelChunkSection[] sections = chunk.getSections();
    if (sectionIdx < 0 || sectionIdx >= sections.length)
      return null;
    return sections[sectionIdx];
  }

  private boolean readNeighborFace(ClientLevel world, int nCx, int nCy, int nCz,
      int faceDir, int[] out) {
    return readNeighborFace(resolveSection(world, nCx, nCy, nCz), faceDir, out);
  }

  private boolean readNeighborFace(LevelChunkSection section, int faceDir,
      int[] out) {
    if (section == null || section.hasOnlyAir())
      return false;
    java.util.Arrays.fill(out, 0);
    boolean hasAny = false;
    switch (faceDir) {
      case 0:
        for (int z = 0; z < 16; z++)
          for (int x = 0; x < 16; x++) {
            BlockState bs = section.getBlockState(x, 15, z);
            if (!bs.isAir()) {
              out[z * 16 + x] = Block.getId(bs);
              hasAny = true;
            }
          }
        break;
      case 1:
        for (int z = 0; z < 16; z++)
          for (int x = 0; x < 16; x++) {
            BlockState bs = section.getBlockState(x, 0, z);
            if (!bs.isAir()) {
              out[z * 16 + x] = Block.getId(bs);
              hasAny = true;
            }
          }
        break;
      case 2:
        for (int y = 0; y < 16; y++)
          for (int x = 0; x < 16; x++) {
            BlockState bs = section.getBlockState(x, y, 15);
            if (!bs.isAir()) {
              out[y * 16 + x] = Block.getId(bs);
              hasAny = true;
            }
          }
        break;
      case 3:
        for (int y = 0; y < 16; y++)
          for (int x = 0; x < 16; x++) {
            BlockState bs = section.getBlockState(x, y, 0);
            if (!bs.isAir()) {
              out[y * 16 + x] = Block.getId(bs);
              hasAny = true;
            }
          }
        break;
      case 4:
        for (int y = 0; y < 16; y++)
          for (int z = 0; z < 16; z++) {
            BlockState bs = section.getBlockState(15, y, z);
            if (!bs.isAir()) {
              out[y * 16 + z] = Block.getId(bs);
              hasAny = true;
            }
          }
        break;
      case 5:
        for (int y = 0; y < 16; y++)
          for (int z = 0; z < 16; z++) {
            BlockState bs = section.getBlockState(0, y, z);
            if (!bs.isAir()) {
              out[y * 16 + z] = Block.getId(bs);
              hasAny = true;
            }
          }
        break;
    }
    return hasAny;
  }

  private boolean readNeighborLightFace(ClientLevel world, int nCx, int nCy, int nCz,
      int faceDir, byte[] out, BlockPos.MutableBlockPos mutablePos) {
    return readNeighborLightFace(world, nCx * 16, nCy * 16, nCz * 16,
        resolveSection(world, nCx, nCy, nCz), faceDir, out, mutablePos);
  }

  private boolean readNeighborLightFace(ClientLevel world, int baseX, int baseY,
      int baseZ, LevelChunkSection section, int faceDir, byte[] out,
      BlockPos.MutableBlockPos mutablePos) {
    if (world == null || section == null || section.hasOnlyAir())
      return false;
    try {
      switch (faceDir) {
        case 0:
          for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
              mutablePos.set(baseX + x, baseY + 15, baseZ + z);
              int bl = world.getBrightness(LightLayer.BLOCK, mutablePos);
              int sl = world.getBrightness(LightLayer.SKY, mutablePos);
              out[z * 16 + x] = (byte) ((bl & 0xF) | ((sl & 0xF) << 4));
            }
          break;
        case 1:
          for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
              mutablePos.set(baseX + x, baseY, baseZ + z);
              int bl = world.getBrightness(LightLayer.BLOCK, mutablePos);
              int sl = world.getBrightness(LightLayer.SKY, mutablePos);
              out[z * 16 + x] = (byte) ((bl & 0xF) | ((sl & 0xF) << 4));
            }
          break;
        case 2:
          for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++) {
              mutablePos.set(baseX + x, baseY + y, baseZ + 15);
              int bl = world.getBrightness(LightLayer.BLOCK, mutablePos);
              int sl = world.getBrightness(LightLayer.SKY, mutablePos);
              out[y * 16 + x] = (byte) ((bl & 0xF) | ((sl & 0xF) << 4));
            }
          break;
        case 3:
          for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++) {
              mutablePos.set(baseX + x, baseY + y, baseZ);
              int bl = world.getBrightness(LightLayer.BLOCK, mutablePos);
              int sl = world.getBrightness(LightLayer.SKY, mutablePos);
              out[y * 16 + x] = (byte) ((bl & 0xF) | ((sl & 0xF) << 4));
            }
          break;
        case 4:
          for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++) {
              mutablePos.set(baseX + 15, baseY + y, baseZ + z);
              int bl = world.getBrightness(LightLayer.BLOCK, mutablePos);
              int sl = world.getBrightness(LightLayer.SKY, mutablePos);
              out[y * 16 + z] = (byte) ((bl & 0xF) | ((sl & 0xF) << 4));
            }
          break;
        case 5:
          for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++) {
              mutablePos.set(baseX, baseY + y, baseZ + z);
              int bl = world.getBrightness(LightLayer.BLOCK, mutablePos);
              int sl = world.getBrightness(LightLayer.SKY, mutablePos);
              out[y * 16 + z] = (byte) ((bl & 0xF) | ((sl & 0xF) << 4));
            }
          break;
      }
    } catch (Exception e) {
      recordLightSampleFallback("neighbor_face", e);
      return false;
    }
    return true;
  }

  private static void recordLightSampleFallback(String stage, Exception e) {
    int c = ++lightSampleFallbackCount;
    if (c <= 5 || c % 250 == 0) {
      MetalLogger.warn("LIGHT_FALLBACK[%s]: count=%d reason=%s", stage, c,
          e != null ? e.getClass().getSimpleName() + ": " + e.getMessage() : "unknown");
    }
  }

  private void fillApproximateLightData(ClientLevel world, int chunkX, int chunkY,
      int chunkZ, int[] blockStates, byte[] lightData,
      BlockPos.MutableBlockPos mutablePos) {
    int baseX = chunkX * 16;
    int baseY = chunkY * 16;
    int baseZ = chunkZ * 16;
    for (int sampleY = 0; sampleY < 16; sampleY += 2) {
      for (int sampleZ = 0; sampleZ < 16; sampleZ += 2) {
        for (int sampleX = 0; sampleX < 16; sampleX += 2) {
          boolean hasAnyBlock = false;
          for (int dy = 0; dy < 2 && !hasAnyBlock; dy++) {
            for (int dz = 0; dz < 2 && !hasAnyBlock; dz++) {
              for (int dx = 0; dx < 2; dx++) {
                int x = sampleX + dx;
                int y = sampleY + dy;
                int z = sampleZ + dz;
                int idx = y * 256 + z * 16 + x;
                if (blockStates[idx] != 0) {
                  hasAnyBlock = true;
                  break;
                }
              }
            }
          }

          byte packedLight = 0;
          if (hasAnyBlock) {
            int maxBlockLight = 0;
            int maxSkyLight = 0;
            for (int dy = 0; dy < 2; dy++) {
              for (int dz = 0; dz < 2; dz++) {
                for (int dx = 0; dx < 2; dx++) {
                  int x = sampleX + dx;
                  int y = sampleY + dy;
                  int z = sampleZ + dz;
                  int idx = y * 256 + z * 16 + x;
                  if (blockStates[idx] == 0) {
                    continue;
                  }
                  mutablePos.set(baseX + x, baseY + y, baseZ + z);
                  maxBlockLight = Math.max(maxBlockLight,
                      world.getBrightness(LightLayer.BLOCK, mutablePos));
                  maxSkyLight = Math.max(maxSkyLight,
                      world.getBrightness(LightLayer.SKY, mutablePos));
                }
              }
            }
            packedLight = (byte) ((maxBlockLight & 0xF) | ((maxSkyLight & 0xF) << 4));
          }

          for (int dy = 0; dy < 2; dy++) {
            for (int dz = 0; dz < 2; dz++) {
              for (int dx = 0; dx < 2; dx++) {
                int x = sampleX + dx;
                int y = sampleY + dy;
                int z = sampleZ + dz;
                int idx = y * 256 + z * 16 + x;
                lightData[idx] = blockStates[idx] != 0 ? packedLight : 0;
              }
            }
          }
        }
      }
    }
  }

  public void buildMeshFromWorld(int chunkX, int chunkY, int chunkZ) {
    buildMeshFromWorld(chunkX, chunkY, chunkZ, 0);
  }

  public void buildMeshFromWorld(int chunkX, int chunkY, int chunkZ,
      int lodLevel) {
    buildMeshFromWorld(chunkX, chunkY, chunkZ, lodLevel, false);
  }

  public void buildMeshFromWorld(int chunkX, int chunkY, int chunkZ,
      int lodLevel, boolean highPriority) {
    buildMeshFromWorld(chunkX, chunkY, chunkZ, lodLevel, highPriority, false,
        false);
  }

  public void buildMeshFromWorldInteractive(int chunkX, int chunkY,
      int chunkZ) {
    buildMeshFromWorld(chunkX, chunkY, chunkZ, 0, true, true, false);
  }

  public void buildMeshFromWorldLightFix(int chunkX, int chunkY,
      int chunkZ, int lodLevel) {
    buildMeshFromWorld(chunkX, chunkY, chunkZ, lodLevel, false, false, true);
  }

  private void buildMeshFromWorld(int chunkX, int chunkY, int chunkZ,
      int lodLevel, boolean highPriority, boolean interactivePriority,
      boolean lightFix) {
    if (!initialized)
      return;
    final int effectiveLodLevel = Math.max(0, Math.min(4, lodLevel));
    final boolean useApproximateLight = !lightFix && !interactivePriority
        && (highPriority || aggressiveApproximateLighting);
    long key = packChunkKey(chunkX, chunkY, chunkZ);
    boolean wasDirty;
    synchronized (dirtyKeys) {
      wasDirty = dirtyKeys.contains(key);
    }
    synchronized (pendingKeys) {
      if (!pendingKeys.add(key)) {
        return;
      }
    }
    final long genAtSubmit;
    synchronized (dirtyGeneration) {
      genAtSubmit = dirtyGeneration.get(key);
    }
    Runnable buildTask = () -> {
      long genNow;
      synchronized (dirtyGeneration) {
        genNow = dirtyGeneration.get(key);
      }
      if (genNow != genAtSubmit) {
        synchronized (pendingKeys) {
          pendingKeys.remove(key);
        }
        return;
      }
      try {
        long pipelineStart = System.nanoTime();
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
          synchronized (pendingKeys) {
            pendingKeys.remove(key);
          }
          return;
        }
        ClientLevel world = mc.level;
        if (world == null) {
          synchronized (pendingKeys) {
            pendingKeys.remove(key);
          }
          return;
        }
        LevelChunk chunk = world.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
          synchronized (pendingKeys) {
            pendingKeys.remove(key);
          }
          return;
        }
        int sectionIdx = chunk.getSectionIndexFromSectionY(chunkY);
        LevelChunkSection[] chunkSections = chunk.getSections();
        if (sectionIdx < 0 || sectionIdx >= chunkSections.length) {
          synchronized (pendingKeys) {
            pendingKeys.remove(key);
          }
          return;
        }
        LevelChunkSection section = chunkSections[sectionIdx];
        if (section == null || section.hasOnlyAir()) {
          if (!isCurrent(key, genAtSubmit)) {
            synchronized (pendingKeys) {
              pendingKeys.remove(key);
            }
            return;
          }
          queueClear(key, genAtSubmit, chunkX, chunkY, chunkZ);
          return;
        }
        int[] blockStates = BLOCK_STATES_POOL.get();
        java.util.Arrays.fill(blockStates, 0);
        boolean hasAnyBlock = false;
        for (int y = 0; y < 16; y++) {
          for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
              BlockState bs = section.getBlockState(x, y, z);
              if (!bs.isAir()) {
                blockStates[y * 256 + z * 16 + x] = Block.getId(bs);
                hasAnyBlock = true;
              }
            }
          }
        }
        if (!hasAnyBlock) {
          if (!isCurrent(key, genAtSubmit)) {
            synchronized (pendingKeys) {
              pendingKeys.remove(key);
            }
            return;
          }
          queueClear(key, genAtSubmit, chunkX, chunkY, chunkZ);
          return;
        }
        int[] neighborXNeg = null, neighborXPos = null;
        int[] neighborYNeg = null, neighborYPos = null;
        int[] neighborZNeg = null, neighborZPos = null;
        byte[] nXNegLight = null, nXPosLight = null;
        byte[] nYNegLight = null, nYPosLight = null;
        byte[] nZNegLight = null, nZPosLight = null;
        LevelChunkSection sectionXNeg = resolveSection(world, chunkX - 1, chunkY,
            chunkZ);
        LevelChunkSection sectionXPos = resolveSection(world, chunkX + 1, chunkY,
            chunkZ);
        LevelChunkSection sectionYNeg = sectionIdx > 0
            ? chunkSections[sectionIdx - 1]
            : null;
        LevelChunkSection sectionYPos = sectionIdx + 1 < chunkSections.length
            ? chunkSections[sectionIdx + 1]
            : null;
        LevelChunkSection sectionZNeg = resolveSection(world, chunkX, chunkY,
            chunkZ - 1);
        LevelChunkSection sectionZPos = resolveSection(world, chunkX, chunkY,
            chunkZ + 1);

        int[] poolXNeg = N_XNEG_FACE_POOL.get(), poolXPos = N_XPOS_FACE_POOL.get();
        int[] poolYNeg = N_YNEG_FACE_POOL.get(), poolYPos = N_YPOS_FACE_POOL.get();
        int[] poolZNeg = N_ZNEG_FACE_POOL.get(), poolZPos = N_ZPOS_FACE_POOL.get();
        neighborXNeg = readNeighborFace(sectionXNeg, 4, poolXNeg) ? poolXNeg : null;
        neighborXPos = readNeighborFace(sectionXPos, 5, poolXPos) ? poolXPos : null;
        neighborYNeg = readNeighborFace(sectionYNeg, 0, poolYNeg) ? poolYNeg : null;
        neighborYPos = readNeighborFace(sectionYPos, 1, poolYPos) ? poolYPos : null;
        neighborZNeg = readNeighborFace(sectionZNeg, 2, poolZNeg) ? poolZNeg : null;
        neighborZPos = readNeighborFace(sectionZPos, 3, poolZPos) ? poolZPos : null;

        BlockPos.MutableBlockPos sharedMpos = MUTABLE_POS_POOL.get();
        if (!useApproximateLight) {
          nXNegLight = N_XNEG_LIGHT_POOL.get();
          if (!readNeighborLightFace(world, (chunkX - 1) * 16, chunkY * 16,
              chunkZ * 16, sectionXNeg, 4, nXNegLight, sharedMpos))
            nXNegLight = null;
          nXPosLight = N_XPOS_LIGHT_POOL.get();
          if (!readNeighborLightFace(world, (chunkX + 1) * 16, chunkY * 16,
              chunkZ * 16, sectionXPos, 5, nXPosLight, sharedMpos))
            nXPosLight = null;
          nYNegLight = N_YNEG_LIGHT_POOL.get();
          if (!readNeighborLightFace(world, chunkX * 16, (chunkY - 1) * 16,
              chunkZ * 16, sectionYNeg, 0, nYNegLight, sharedMpos))
            nYNegLight = null;
          nYPosLight = N_YPOS_LIGHT_POOL.get();
          if (!readNeighborLightFace(world, chunkX * 16, (chunkY + 1) * 16,
              chunkZ * 16, sectionYPos, 1, nYPosLight, sharedMpos))
            nYPosLight = null;
          nZNegLight = N_ZNEG_LIGHT_POOL.get();
          if (!readNeighborLightFace(world, chunkX * 16, chunkY * 16,
              (chunkZ - 1) * 16, sectionZNeg, 2, nZNegLight, sharedMpos))
            nZNegLight = null;
          nZPosLight = N_ZPOS_LIGHT_POOL.get();
          if (!readNeighborLightFace(world, chunkX * 16, chunkY * 16,
              (chunkZ + 1) * 16, sectionZPos, 3, nZPosLight, sharedMpos))
            nZPosLight = null;
        }
        byte[] lightData = LIGHT_DATA_POOL.get();

        try {
          if (useApproximateLight) {
            fillApproximateLightData(world, chunkX, chunkY, chunkZ, blockStates, lightData, sharedMpos);
          } else {
            int baseX = chunkX * 16, baseY = chunkY * 16, baseZ = chunkZ * 16;
            for (int y = 0; y < 16; y++) {
              for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                  int lightIdx = y * 256 + z * 16 + x;
                  if (blockStates[lightIdx] == 0) {
                    lightData[lightIdx] = 0;
                    continue;
                  }
                  sharedMpos.set(baseX + x, baseY + y, baseZ + z);
                  int bl = world.getBrightness(LightLayer.BLOCK, sharedMpos);
                  int sl = world.getBrightness(LightLayer.SKY, sharedMpos);
                  lightData[lightIdx] = (byte) ((bl & 0xF) | ((sl & 0xF) << 4));
                }
              }
            }
          }
        } catch (Exception e) {
          recordLightSampleFallback("lod0_fill", e);
          java.util.Arrays.fill(lightData, (byte) 0x00);
        }
        doMeshBuild(chunkX, chunkY, chunkZ, blockStates, lightData, key,
            genAtSubmit, useApproximateLight, effectiveLodLevel,
            neighborXNeg, neighborXPos, neighborYNeg, neighborYPos,
            neighborZNeg, neighborZPos,
            nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight, nZPosLight);
        long pipeElapsed = System.nanoTime() - pipelineStart;
        pipelineTimeAcc += pipeElapsed;
        pipelineCount++;
      } catch (Exception e) {
        synchronized (pendingKeys) {
          pendingKeys.remove(key);
        }
        MetalLogger.error("Async mesh build error for [%d,%d,%d]: %s", chunkX,
            chunkY, chunkZ, e.getMessage());
      }
    };
    if (lightFix) {
      lightRebuildPool.submit(buildTask);
      return;
    }
    if (interactivePriority) {
      int interactiveQueueDepth = interactiveRebuildPool != null ? interactiveRebuildPool.getQueue().size() : 0;
      int interactiveActive = interactiveRebuildPool != null ? interactiveRebuildPool.getActiveCount() : 0;
      int interactiveMax = interactiveRebuildPool != null ? interactiveRebuildPool.getMaximumPoolSize() : 0;
      boolean interactiveHasHeadroom = interactiveActive < interactiveMax
          && interactiveQueueDepth < INTERACTIVE_PRIORITY_QUEUE_SPILLOVER_THRESHOLD;
      if (interactiveHasHeadroom) {
        interactiveRebuildPool.submit(buildTask);
        return;
      }
    }
    ExecutorService pool = wasDirty ? dirtyRebuildPool : builderPool;
    if (highPriority) {
      int instantQueueDepth = instantRebuildPool != null ? instantRebuildPool.getQueue().size() : 0;
      int instantActive = instantRebuildPool != null ? instantRebuildPool.getActiveCount() : 0;
      int instantMax = instantRebuildPool != null ? instantRebuildPool.getMaximumPoolSize() : 0;
      boolean instantHasHeadroom = instantActive < instantMax;
      if (instantQueueDepth < HIGH_PRIORITY_QUEUE_SPILLOVER_THRESHOLD && instantHasHeadroom) {
        instantRebuildPool.submit(buildTask);
        return;
      }
    }

    pool.submit(buildTask);
  }

  private int emitBakedQuad(ByteBuffer buf, BakedQuad quad, int blockX,
      int blockY, int blockZ, byte packedLight,
      byte tintR, byte tintG, byte tintB,
      boolean forceOpaque, boolean forceTint, byte vertAlpha) {
    Direction face = quad.direction();
    byte normalIndex = dirToNormalIndex(face);
    float shade = quad.materialInfo().shade() ? getFaceShade(normalIndex) : 1.0f;
    byte baseR, baseG, baseB;
    if (forceTint || quad.materialInfo().isTinted() || quad.materialInfo().tintIndex() >= 0) {
      baseR = tintR;
      baseG = tintG;
      baseB = tintB;
    } else {
      baseR = (byte) 255;
      baseG = (byte) 255;
      baseB = (byte) 255;
    }

    byte sr = baseR;
    byte sg = baseG;
    byte sb = baseB;
    byte light = packedLight;
    int emission = quad.materialInfo().lightEmission();
    if (emission > 0) {
      int bl = Math.max(light & 0xF, emission);
      int sl = (light >> 4) & 0xF;
      light = (byte) ((bl & 0xF) | ((sl & 0xF) << 4));
    }
    for (int v = 0; v < 4; v++) {
      Vector3fc pos = quad.position(v);
      long packedUV = quad.packedUV(v);
      float vx = pos.x() + blockX;
      float vy = pos.y() + blockY;
      float vz = pos.z() + blockZ;

      short spx = (short) (vx * 256.0f);
      short spy = (short) (vy * 256.0f);
      short spz = (short) (vz * 256.0f);
      float u = Float.intBitsToFloat((int) (packedUV >> 32));
      float vCoord = Float.intBitsToFloat((int) packedUV);
      short su = (short) (u * 65535.0f);
      short sv = (short) (vCoord * 65535.0f);
      long w0 = (spx & 0xFFFFL) | ((spy & 0xFFFFL) << 16) | ((spz & 0xFFFFL) << 32) | ((su & 0xFFFFL) << 48);
      long w1 = (sv & 0xFFFFL) | ((sr & 0xFFL) << 16) | ((sg & 0xFFL) << 24)
          | ((sb & 0xFFL) << 32) | ((forceOpaque ? 254L : (vertAlpha & 0xFFL)) << 40)
          | ((light & 0xFFL) << 48) | ((normalIndex & 0xFFL) << 56);
      buf.putLong(w0);
      buf.putLong(w1);
    }
    return 1;
  }

  private int emitBakedQuadAO(ByteBuffer buf, BakedQuad quad, int blockX,
      int blockY, int blockZ,
      byte tintR, byte tintG, byte tintB,
      boolean forceOpaque, boolean forceTint, byte vertAlpha,
      float[] ao, byte[] vLight) {
    Direction face = quad.direction();
    int faceIdx = face != null ? face.ordinal() : 1;
    byte normalIndex = dirToNormalIndex(face);
    int baseR, baseG, baseB;
    if (forceTint || quad.materialInfo().isTinted() || quad.materialInfo().tintIndex() >= 0) {
      baseR = tintR & 0xFF;
      baseG = tintG & 0xFF;
      baseB = tintB & 0xFF;
    } else {
      baseR = 255;
      baseG = 255;
      baseB = 255;
    }
    int emission = quad.materialInfo().lightEmission();
    byte alpha = forceOpaque ? (byte) 254 : vertAlpha;
    for (int v = 0; v < 4; v++) {
      Vector3fc pos = quad.position(v);
      long packedUV = quad.packedUV(v);
      float localX = pos.x();
      float localY = pos.y();
      float localZ = pos.z();
      float vx = localX + blockX;
      float vy = localY + blockY;
      float vz = localZ + blockZ;
      float aoVal = bilinearAO(ao, faceIdx, localX, localY, localZ);
      byte vertLight = bilinearLight(vLight, faceIdx, localX, localY, localZ);
      if (emission > 0) {
        int bl = Math.max(vertLight & 0xF, emission);
        int sl = (vertLight >> 4) & 0xF;
        vertLight = (byte) ((bl & 0xF) | ((sl & 0xF) << 4));
      }

      short spx = (short) (vx * 256.0f);
      short spy = (short) (vy * 256.0f);
      short spz = (short) (vz * 256.0f);
      float u = Float.intBitsToFloat((int) (packedUV >> 32));
      float vCoord = Float.intBitsToFloat((int) packedUV);
      short su = (short) (u * 65535.0f);
      short sv = (short) (vCoord * 65535.0f);
      byte ar = (byte) (int) (baseR * aoVal);
      byte ag = (byte) (int) (baseG * aoVal);
      byte ab = (byte) (int) (baseB * aoVal);
      long w0 = (spx & 0xFFFFL) | ((spy & 0xFFFFL) << 16) | ((spz & 0xFFFFL) << 32) | ((su & 0xFFFFL) << 48);
      long w1 = (sv & 0xFFFFL) | ((ar & 0xFFL) << 16) | ((ag & 0xFFL) << 24)
          | ((ab & 0xFFL) << 32) | ((alpha & 0xFFL) << 40)
          | ((vertLight & 0xFFL) << 48) | ((normalIndex & 0xFFL) << 56);
      buf.putLong(w0);
      buf.putLong(w1);
    }
    return 1;
  }

  private static byte dirToNormalIndex(Direction dir) {
    if (dir == null)
      return 1;
    return switch (dir) {
      case DOWN -> 0;
      case UP -> 1;
      case NORTH -> 2;
      case SOUTH -> 3;
      case WEST -> 4;
      case EAST -> 5;
    };
  }

  private static float getFaceShade(byte normalIndex) {
    if (!applyFaceShade)
      return 1.0f;
    return switch (normalIndex) {
      case 0 -> 0.5f;
      case 1 -> 1.0f;
      case 2, 3 -> 0.8f;
      case 4, 5 -> 0.6f;
      default -> 1.0f;
    };
  }

  private int emitFace(ByteBuffer buf, int x, int y, int z, int normalIndex,
      TextureAtlasSprite sprite, byte packedLight, byte r, byte g,
      byte b, byte alpha) {
    return emitFaceScaled(buf, x, y, z, normalIndex, sprite, packedLight, r, g, b, alpha, 1, 0);
  }

  private int emitFaceWater(ByteBuffer buf, int x, int y, int z, int normalIndex,
      TextureAtlasSprite sprite, byte packedLight, byte r, byte g,
      byte b, byte alpha, int topDrop) {
    return emitFaceScaled(buf, x, y, z, normalIndex, sprite, packedLight, r, g, b, alpha, 1, topDrop);
  }

  private static int emitFaceWaterSmooth(ByteBuffer buf, int x, int y, int z, int normalIndex,
      TextureAtlasSprite sprite, byte packedLight, byte r, byte g, byte b, byte alpha,
      int drop00, int drop01, int drop11, int drop10) {
    short uMin = 0, uMax = (short) 65535, vMin = 0, vMax = (short) 65535;
    if (sprite != null) {
      uMin = (short) (sprite.getU0() * 65535.0f);
      uMax = (short) (sprite.getU1() * 65535.0f);
      vMin = (short) (sprite.getV0() * 65535.0f);
      vMax = (short) (sprite.getV1() * 65535.0f);
    }
    emitWaterFace(buf, x, y, z, normalIndex, packedLight, r, g, b, alpha,
        true, uMin, uMax, vMin, vMax, drop00, drop01, drop11, drop10);
    return 1;
  }

  private static final float[] FACE_SHADE = { 0.5f, 1.0f, 0.8f, 0.8f, 0.6f, 0.6f };

  private static void emitFaceInline(ByteBuffer buf, int x, int y, int z,
      int normalIdx, byte light, byte r, byte g, byte b, byte a,
      boolean hasSpr, short uMin, short uMax, short vMin, short vMax) {
    emitFaceInlineWater(buf, x, y, z, normalIdx, light, r, g, b, a, hasSpr, uMin, uMax, vMin, vMax, 0);
  }

  private static void emitFaceInlineWater(ByteBuffer buf, int x, int y, int z,
      int normalIdx, byte light, byte r, byte g, byte b, byte a,
      boolean hasSpr, short uMin, short uMax, short vMin, short vMax,
      int topDrop) {

    byte sr = r;
    byte sg = g;
    byte sb = b;
    byte nIdx = (byte) normalIdx;
    if (!hasSpr) {
      uMin = 0;
      uMax = (short) 65535;
      vMin = 0;
      vMax = (short) 65535;
    }
    short sx = (short) (x * 256), sy = (short) (y * 256), sz = (short) (z * 256);
    short ex = (short) ((x + 1) * 256), ey = (short) ((y + 1) * 256 - topDrop), ez = (short) ((z + 1) * 256);
    switch (normalIdx) {
      case 1:
        emitVertex(buf, sx, ey, sz, uMin, vMin, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, sx, ey, ez, uMin, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, ey, ez, uMax, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, ey, sz, uMax, vMin, sr, sg, sb, a, light, nIdx);
        break;
      case 0:
        emitVertex(buf, sx, sy, ez, uMin, vMin, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, sx, sy, sz, uMin, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, sy, sz, uMax, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, sy, ez, uMax, vMin, sr, sg, sb, a, light, nIdx);
        break;
      case 3:
        emitVertex(buf, sx, ey, ez, uMin, vMin, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, sx, sy, ez, uMin, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, sy, ez, uMax, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, ey, ez, uMax, vMin, sr, sg, sb, a, light, nIdx);
        break;
      case 2:
        emitVertex(buf, ex, ey, sz, uMin, vMin, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, sy, sz, uMin, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, sx, sy, sz, uMax, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, sx, ey, sz, uMax, vMin, sr, sg, sb, a, light, nIdx);
        break;
      case 5:
        emitVertex(buf, ex, ey, ez, uMin, vMin, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, sy, ez, uMin, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, sy, sz, uMax, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, ey, sz, uMax, vMin, sr, sg, sb, a, light, nIdx);
        break;
      case 4:
        emitVertex(buf, sx, ey, sz, uMin, vMin, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, sx, sy, sz, uMin, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, sx, sy, ez, uMax, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, sx, ey, ez, uMax, vMin, sr, sg, sb, a, light, nIdx);
        break;
    }
  }

  private static void emitWaterFace(ByteBuffer buf, int x, int y, int z,
      int normalIdx, byte light, byte r, byte g, byte b, byte a,
      boolean hasSpr, short uMin, short uMax, short vMin, short vMax,
      int drop00, int drop01, int drop11, int drop10) {
    byte sr = r, sg = g, sb = b;
    byte nIdx = (byte) normalIdx;
    if (!hasSpr) {
      uMin = 0;
      uMax = (short) 65535;
      vMin = 0;
      vMax = (short) 65535;
    }
    short sx = (short) (x * 256), sy = (short) (y * 256), sz = (short) (z * 256);
    short ex = (short) ((x + 1) * 256), ez = (short) ((z + 1) * 256);
    int baseTop = (y + 1) * 256;

    int vRange = (vMax & 0xFFFF) - (vMin & 0xFFFF);
    short vTop00 = (vRange > 0) ? (short) ((vMin & 0xFFFF) + vRange * drop00 / 256) : vMin;
    short vTop01 = (vRange > 0) ? (short) ((vMin & 0xFFFF) + vRange * drop01 / 256) : vMin;
    short vTop11 = (vRange > 0) ? (short) ((vMin & 0xFFFF) + vRange * drop11 / 256) : vMin;
    short vTop10 = (vRange > 0) ? (short) ((vMin & 0xFFFF) + vRange * drop10 / 256) : vMin;
    switch (normalIdx) {
      case 1: {
        short ey00 = (short) (baseTop - drop00);
        short ey01 = (short) (baseTop - drop01);
        short ey11 = (short) (baseTop - drop11);
        short ey10 = (short) (baseTop - drop10);
        emitVertex(buf, sx, ey00, sz, uMin, vMin, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, sx, ey01, ez, uMin, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, ey11, ez, uMax, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, ey10, sz, uMax, vMin, sr, sg, sb, a, light, nIdx);
        break;
      }
      case 0:
        emitVertex(buf, sx, sy, ez, uMin, vMin, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, sx, sy, sz, uMin, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, sy, sz, uMax, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, sy, ez, uMax, vMin, sr, sg, sb, a, light, nIdx);
        break;
      case 3: {
        short eyL = (short) (baseTop - drop01);
        short eyR = (short) (baseTop - drop11);
        emitVertex(buf, sx, eyL, ez, uMin, vTop01, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, sx, sy, ez, uMin, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, sy, ez, uMax, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, eyR, ez, uMax, vTop11, sr, sg, sb, a, light, nIdx);
        break;
      }
      case 2: {
        short eyL = (short) (baseTop - drop10);
        short eyR = (short) (baseTop - drop00);
        emitVertex(buf, ex, eyL, sz, uMin, vTop10, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, sy, sz, uMin, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, sx, sy, sz, uMax, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, sx, eyR, sz, uMax, vTop00, sr, sg, sb, a, light, nIdx);
        break;
      }
      case 5: {
        short eyL = (short) (baseTop - drop11);
        short eyR = (short) (baseTop - drop10);
        emitVertex(buf, ex, eyL, ez, uMin, vTop11, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, sy, ez, uMin, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, sy, sz, uMax, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, ex, eyR, sz, uMax, vTop10, sr, sg, sb, a, light, nIdx);
        break;
      }
      case 4: {
        short eyL = (short) (baseTop - drop00);
        short eyR = (short) (baseTop - drop01);
        emitVertex(buf, sx, eyL, sz, uMin, vTop00, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, sx, sy, sz, uMin, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, sx, sy, ez, uMax, vMax, sr, sg, sb, a, light, nIdx);
        emitVertex(buf, sx, eyR, ez, uMax, vTop01, sr, sg, sb, a, light, nIdx);
        break;
      }
    }
  }

  private static void emitWaterFaceAO(ByteBuffer buf, int x, int y, int z,
      int normalIdx, byte r, byte g, byte b, byte a,
      boolean hasSpr, short uMin, short uMax, short vMin, short vMax,
      int drop00, int drop01, int drop11, int drop10, float[] ao, byte[] vLight) {
    byte nIdx = (byte) normalIdx;
    if (!hasSpr) {
      uMin = 0;
      uMax = (short) 65535;
      vMin = 0;
      vMax = (short) 65535;
    }
    short sx = (short) (x * 256), sy = (short) (y * 256), sz = (short) (z * 256);
    short ex = (short) ((x + 1) * 256), ez = (short) ((z + 1) * 256);
    int baseTop = (y + 1) * 256;
    int ri = r & 0xFF, gi = g & 0xFF, bi = b & 0xFF;
    byte r0 = (byte) (int) (ri * ao[0]), g0 = (byte) (int) (gi * ao[0]), b0 = (byte) (int) (bi * ao[0]);
    byte r1 = (byte) (int) (ri * ao[1]), g1 = (byte) (int) (gi * ao[1]), b1 = (byte) (int) (bi * ao[1]);
    byte r2 = (byte) (int) (ri * ao[2]), g2 = (byte) (int) (gi * ao[2]), b2 = (byte) (int) (bi * ao[2]);
    byte r3 = (byte) (int) (ri * ao[3]), g3 = (byte) (int) (gi * ao[3]), b3 = (byte) (int) (bi * ao[3]);

    int vRange = (vMax & 0xFFFF) - (vMin & 0xFFFF);
    short vTop00 = (vRange > 0) ? (short) ((vMin & 0xFFFF) + vRange * drop00 / 256) : vMin;
    short vTop01 = (vRange > 0) ? (short) ((vMin & 0xFFFF) + vRange * drop01 / 256) : vMin;
    short vTop11 = (vRange > 0) ? (short) ((vMin & 0xFFFF) + vRange * drop11 / 256) : vMin;
    short vTop10 = (vRange > 0) ? (short) ((vMin & 0xFFFF) + vRange * drop10 / 256) : vMin;
    switch (normalIdx) {
      case 1: {
        short ey00 = (short) (baseTop - drop00);
        short ey01 = (short) (baseTop - drop01);
        short ey11 = (short) (baseTop - drop11);
        short ey10 = (short) (baseTop - drop10);
        emitVertex(buf, sx, ey00, sz, uMin, vMin, r0, g0, b0, a, vLight[0], nIdx);
        emitVertex(buf, sx, ey01, ez, uMin, vMax, r1, g1, b1, a, vLight[1], nIdx);
        emitVertex(buf, ex, ey11, ez, uMax, vMax, r2, g2, b2, a, vLight[2], nIdx);
        emitVertex(buf, ex, ey10, sz, uMax, vMin, r3, g3, b3, a, vLight[3], nIdx);
        break;
      }
      case 0:
        emitVertex(buf, sx, sy, ez, uMin, vMin, r0, g0, b0, a, vLight[0], nIdx);
        emitVertex(buf, sx, sy, sz, uMin, vMax, r1, g1, b1, a, vLight[1], nIdx);
        emitVertex(buf, ex, sy, sz, uMax, vMax, r2, g2, b2, a, vLight[2], nIdx);
        emitVertex(buf, ex, sy, ez, uMax, vMin, r3, g3, b3, a, vLight[3], nIdx);
        break;
      case 3: {
        short eyL = (short) (baseTop - drop01);
        short eyR = (short) (baseTop - drop11);
        emitVertex(buf, sx, eyL, ez, uMin, vTop01, r0, g0, b0, a, vLight[0], nIdx);
        emitVertex(buf, sx, sy, ez, uMin, vMax, r1, g1, b1, a, vLight[1], nIdx);
        emitVertex(buf, ex, sy, ez, uMax, vMax, r2, g2, b2, a, vLight[2], nIdx);
        emitVertex(buf, ex, eyR, ez, uMax, vTop11, r3, g3, b3, a, vLight[3], nIdx);
        break;
      }
      case 2: {
        short eyL = (short) (baseTop - drop10);
        short eyR = (short) (baseTop - drop00);
        emitVertex(buf, ex, eyL, sz, uMin, vTop10, r0, g0, b0, a, vLight[0], nIdx);
        emitVertex(buf, ex, sy, sz, uMin, vMax, r1, g1, b1, a, vLight[1], nIdx);
        emitVertex(buf, sx, sy, sz, uMax, vMax, r2, g2, b2, a, vLight[2], nIdx);
        emitVertex(buf, sx, eyR, sz, uMax, vTop00, r3, g3, b3, a, vLight[3], nIdx);
        break;
      }
      case 5: {
        short eyL = (short) (baseTop - drop11);
        short eyR = (short) (baseTop - drop10);
        emitVertex(buf, ex, eyL, ez, uMin, vTop11, r0, g0, b0, a, vLight[0], nIdx);
        emitVertex(buf, ex, sy, ez, uMin, vMax, r1, g1, b1, a, vLight[1], nIdx);
        emitVertex(buf, ex, sy, sz, uMax, vMax, r2, g2, b2, a, vLight[2], nIdx);
        emitVertex(buf, ex, eyR, sz, uMax, vTop10, r3, g3, b3, a, vLight[3], nIdx);
        break;
      }
      case 4: {
        short eyL = (short) (baseTop - drop00);
        short eyR = (short) (baseTop - drop01);
        emitVertex(buf, sx, eyL, sz, uMin, vTop00, r0, g0, b0, a, vLight[0], nIdx);
        emitVertex(buf, sx, sy, sz, uMin, vMax, r1, g1, b1, a, vLight[1], nIdx);
        emitVertex(buf, sx, sy, ez, uMax, vMax, r2, g2, b2, a, vLight[2], nIdx);
        emitVertex(buf, sx, eyR, ez, uMax, vTop01, r3, g3, b3, a, vLight[3], nIdx);
        break;
      }
    }
  }

  private static void emitVertex(ByteBuffer buf, short px, short py, short pz,
      short u, short v, byte r, byte g, byte b, byte a, byte light, byte nIdx) {

    long w0 = (px & 0xFFFFL) | ((py & 0xFFFFL) << 16) | ((pz & 0xFFFFL) << 32) | ((u & 0xFFFFL) << 48);

    long w1 = (v & 0xFFFFL) | ((r & 0xFFL) << 16) | ((g & 0xFFL) << 24)
        | ((b & 0xFFL) << 32) | ((a & 0xFFL) << 40) | ((light & 0xFFL) << 48) | ((nIdx & 0xFFL) << 56);
    buf.putLong(w0);
    buf.putLong(w1);
  }

  private static void emitReversedQuad(ByteBuffer buf) {
    int pos = buf.position();

    long v0w0 = buf.getLong(pos - 64), v0w1 = buf.getLong(pos - 56);
    long v1w0 = buf.getLong(pos - 48), v1w1 = buf.getLong(pos - 40);
    long v2w0 = buf.getLong(pos - 32), v2w1 = buf.getLong(pos - 24);
    long v3w0 = buf.getLong(pos - 16), v3w1 = buf.getLong(pos - 8);

    buf.putLong(v3w0);
    buf.putLong(v3w1);
    buf.putLong(v2w0);
    buf.putLong(v2w1);
    buf.putLong(v1w0);
    buf.putLong(v1w1);
    buf.putLong(v0w0);
    buf.putLong(v0w1);
  }

  private static final float[] AO_CURVE = { 0.85f, 0.90f, 0.95f, 1.0f };

  private static float calcVertexAO(boolean side1, boolean side2, boolean corner) {
    if (side1 && side2)
      return AO_CURVE[0];
    int level = 3 - ((side1 ? 1 : 0) + (side2 ? 1 : 0) + (corner ? 1 : 0));
    return AO_CURVE[level];
  }

  private static boolean isOpaqueAtExt(int x, int y, int z,
      int[] blockStates, boolean[] sidOpaque,
      int[] nXNeg, int[] nXPos, int[] nYNeg, int[] nYPos, int[] nZNeg, int[] nZPos) {
    if (x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16) {
      int sid = blockStates != null ? blockStates[y * 256 + z * 16 + x] : 0;
      return sid > 0 && sid < sidOpaque.length && sidOpaque[sid];
    }
    int oob = (x < 0 || x >= 16 ? 1 : 0) + (y < 0 || y >= 16 ? 1 : 0) + (z < 0 || z >= 16 ? 1 : 0);
    if (oob > 1)
      return false;
    int sid = 0;
    if (x < 0 && nXNeg != null) {
      int ni = y * 16 + z;
      if (ni >= 0 && ni < nXNeg.length)
        sid = nXNeg[ni];
    } else if (x >= 16 && nXPos != null) {
      int ni = y * 16 + z;
      if (ni >= 0 && ni < nXPos.length)
        sid = nXPos[ni];
    } else if (y < 0 && nYNeg != null) {
      int ni = z * 16 + x;
      if (ni >= 0 && ni < nYNeg.length)
        sid = nYNeg[ni];
    } else if (y >= 16 && nYPos != null) {
      int ni = z * 16 + x;
      if (ni >= 0 && ni < nYPos.length)
        sid = nYPos[ni];
    } else if (z < 0 && nZNeg != null) {
      int ni = y * 16 + x;
      if (ni >= 0 && ni < nZNeg.length)
        sid = nZNeg[ni];
    } else if (z >= 16 && nZPos != null) {
      int ni = y * 16 + x;
      if (ni >= 0 && ni < nZPos.length)
        sid = nZPos[ni];
    }
    return sid > 0 && sid < sidOpaque.length && sidOpaque[sid];
  }

  private static int getLightAtExt(int x, int y, int z, byte[] lightData,
      byte[] nXNegLight, byte[] nXPosLight,
      byte[] nYNegLight, byte[] nYPosLight,
      byte[] nZNegLight, byte[] nZPosLight) {
    if (x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16)
      return lightData[y * 256 + z * 16 + x] & 0xFF;

    if (x == -1 && y >= 0 && y < 16 && z >= 0 && z < 16) {
      if (nXNegLight != null)
        return nXNegLight[y * 16 + z] & 0xFF;
      if (lightData != null)
        return lightData[y * 256 + z * 16 + 0] & 0xFF;
      return 0x00;
    }
    if (x == 16 && y >= 0 && y < 16 && z >= 0 && z < 16) {
      if (nXPosLight != null)
        return nXPosLight[y * 16 + z] & 0xFF;
      if (lightData != null)
        return lightData[y * 256 + z * 16 + 15] & 0xFF;
      return 0x00;
    }
    if (y == -1 && x >= 0 && x < 16 && z >= 0 && z < 16) {
      if (nYNegLight != null)
        return nYNegLight[z * 16 + x] & 0xFF;
      if (lightData != null)
        return lightData[0 * 256 + z * 16 + x] & 0xFF;
      return 0x00;
    }
    if (y == 16 && x >= 0 && x < 16 && z >= 0 && z < 16) {
      if (nYPosLight != null)
        return nYPosLight[z * 16 + x] & 0xFF;
      if (lightData != null)
        return lightData[15 * 256 + z * 16 + x] & 0xFF;
      return 0x00;
    }
    if (z == -1 && x >= 0 && x < 16 && y >= 0 && y < 16) {
      if (nZNegLight != null)
        return nZNegLight[y * 16 + x] & 0xFF;
      if (lightData != null)
        return lightData[y * 256 + 0 * 16 + x] & 0xFF;
      return 0x00;
    }
    if (z == 16 && x >= 0 && x < 16 && y >= 0 && y < 16) {
      if (nZPosLight != null)
        return nZPosLight[y * 16 + x] & 0xFF;
      if (lightData != null)
        return lightData[y * 256 + 15 * 16 + x] & 0xFF;
      return 0x00;
    }

    int lx = Math.max(0, Math.min(15, x));
    int ly = Math.max(0, Math.min(15, y));
    int lz = Math.max(0, Math.min(15, z));
    if (x < 0 || x > 15) {
      byte[] arr = (x < 0) ? nXNegLight : nXPosLight;
      if (arr != null)
        return arr[ly * 16 + lz] & 0xFF;
      if (lightData != null)
        return lightData[ly * 256 + lz * 16 + lx] & 0xFF;
    }
    if (y < 0 || y > 15) {
      byte[] arr = (y < 0) ? nYNegLight : nYPosLight;
      if (arr != null)
        return arr[lz * 16 + lx] & 0xFF;
      if (lightData != null)
        return lightData[ly * 256 + lz * 16 + lx] & 0xFF;
    }
    if (z < 0 || z > 15) {
      byte[] arr = (z < 0) ? nZNegLight : nZPosLight;
      if (arr != null)
        return arr[ly * 16 + lx] & 0xFF;
      if (lightData != null)
        return lightData[ly * 256 + lz * 16 + lx] & 0xFF;
    }

    if (lightData != null)
      return lightData[ly * 256 + lz * 16 + lx] & 0xFF;
    return 0x00;
  }

  private static byte avgLight4(int center, int s1, int s2, int corner,
      boolean s1Op, boolean s2Op) {

    int a = center, b = s1Op ? center : s1, c = s2Op ? center : s2;
    int d = (s1Op && s2Op) ? center : corner;
    int bl = ((a & 0xF) + (b & 0xF) + (c & 0xF) + (d & 0xF) + 2) >> 2;
    int sl = (((a >> 4) & 0xF) + ((b >> 4) & 0xF) + ((c >> 4) & 0xF) + ((d >> 4) & 0xF) + 2) >> 2;
    return (byte) ((bl & 0xF) | ((sl & 0xF) << 4));
  }

  private static byte mergePackedLight(int first, int second) {
    int block = Math.max(first & 0xF, second & 0xF);
    int sky = Math.max((first >> 4) & 0xF, (second >> 4) & 0xF);
    return (byte) ((block & 0xF) | ((sky & 0xF) << 4));
  }

  private static void computeSmoothLighting(int x, int y, int z, int face,
      int[] blockStates, boolean[] sidOpaque, byte[] lightData,
      int[] nXNeg, int[] nXPos, int[] nYNeg, int[] nYPos, int[] nZNeg, int[] nZPos,
      byte[] nXNegLight, byte[] nXPosLight, byte[] nYNegLight,
      byte[] nYPosLight, byte[] nZNegLight, byte[] nZPosLight,
      float[] aoOut, byte[] lightOut) {

    int nx = x, ny = y, nz = z;
    switch (face) {
      case 0:
        ny--;
        break;
      case 1:
        ny++;
        break;
      case 2:
        nz--;
        break;
      case 3:
        nz++;
        break;
      case 4:
        nx--;
        break;
      case 5:
        nx++;
        break;
    }
    int cL = getLightAtExt(nx, ny, nz, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
        nZPosLight);
    int srcL = getLightAtExt(x, y, z, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
        nZPosLight);

    if (!applyFaceShade) {
      aoOut[0] = aoOut[1] = aoOut[2] = aoOut[3] = 1.0f;
      byte flatLight = mergePackedLight(srcL, cL);
      lightOut[0] = lightOut[1] = lightOut[2] = lightOut[3] = flatLight;
      return;
    }

    switch (face) {
      case 1: {
        boolean eW = isOpaqueAtExt(nx - 1, ny, nz, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eE = isOpaqueAtExt(nx + 1, ny, nz, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eN = isOpaqueAtExt(nx, ny, nz - 1, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eS = isOpaqueAtExt(nx, ny, nz + 1, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cNW = eW && eN
            || isOpaqueAtExt(nx - 1, ny, nz - 1, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cSW = eW && eS
            || isOpaqueAtExt(nx - 1, ny, nz + 1, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cSE = eE && eS
            || isOpaqueAtExt(nx + 1, ny, nz + 1, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cNE = eE && eN
            || isOpaqueAtExt(nx + 1, ny, nz - 1, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        int lW = getLightAtExt(nx - 1, ny, nz, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
            nZPosLight),
            lE = getLightAtExt(nx + 1, ny, nz, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                nZPosLight);
        int lN = getLightAtExt(nx, ny, nz - 1, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
            nZPosLight),
            lS = getLightAtExt(nx, ny, nz + 1, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                nZPosLight);
        int lNW = getLightAtExt(nx - 1, ny, nz - 1, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight,
            nZNegLight, nZPosLight),
            lSW = getLightAtExt(nx - 1, ny, nz + 1, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight,
                nZNegLight, nZPosLight);
        int lSE = getLightAtExt(nx + 1, ny, nz + 1, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight,
            nZNegLight, nZPosLight),
            lNE = getLightAtExt(nx + 1, ny, nz - 1, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight,
                nZNegLight, nZPosLight);

        aoOut[0] = calcVertexAO(eW, eN, cNW);
        aoOut[1] = calcVertexAO(eW, eS, cSW);
        aoOut[2] = calcVertexAO(eE, eS, cSE);
        aoOut[3] = calcVertexAO(eE, eN, cNE);
        lightOut[0] = avgLight4(cL, lW, lN, lNW, eW, eN);
        lightOut[1] = avgLight4(cL, lW, lS, lSW, eW, eS);
        lightOut[2] = avgLight4(cL, lE, lS, lSE, eE, eS);
        lightOut[3] = avgLight4(cL, lE, lN, lNE, eE, eN);
        break;
      }
      case 0: {
        boolean eW = isOpaqueAtExt(nx - 1, ny, nz, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eE = isOpaqueAtExt(nx + 1, ny, nz, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eN = isOpaqueAtExt(nx, ny, nz - 1, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eS = isOpaqueAtExt(nx, ny, nz + 1, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cSW = eW && eS
            || isOpaqueAtExt(nx - 1, ny, nz + 1, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cNW = eW && eN
            || isOpaqueAtExt(nx - 1, ny, nz - 1, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cNE = eE && eN
            || isOpaqueAtExt(nx + 1, ny, nz - 1, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cSE = eE && eS
            || isOpaqueAtExt(nx + 1, ny, nz + 1, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        int lW = getLightAtExt(nx - 1, ny, nz, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
            nZPosLight),
            lE = getLightAtExt(nx + 1, ny, nz, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                nZPosLight);
        int lN = getLightAtExt(nx, ny, nz - 1, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
            nZPosLight),
            lS = getLightAtExt(nx, ny, nz + 1, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                nZPosLight);
        int lNW = getLightAtExt(nx - 1, ny, nz - 1, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight,
            nZNegLight, nZPosLight),
            lSW = getLightAtExt(nx - 1, ny, nz + 1, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight,
                nZNegLight, nZPosLight);
        int lSE = getLightAtExt(nx + 1, ny, nz + 1, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight,
            nZNegLight, nZPosLight),
            lNE = getLightAtExt(nx + 1, ny, nz - 1, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight,
                nZNegLight, nZPosLight);

        aoOut[0] = calcVertexAO(eW, eS, cSW);
        aoOut[1] = calcVertexAO(eW, eN, cNW);
        aoOut[2] = calcVertexAO(eE, eN, cNE);
        aoOut[3] = calcVertexAO(eE, eS, cSE);
        lightOut[0] = avgLight4(cL, lW, lS, lSW, eW, eS);
        lightOut[1] = avgLight4(cL, lW, lN, lNW, eW, eN);
        lightOut[2] = avgLight4(cL, lE, lN, lNE, eE, eN);
        lightOut[3] = avgLight4(cL, lE, lS, lSE, eE, eS);
        break;
      }
      case 2: {
        boolean eE = isOpaqueAtExt(nx + 1, ny, nz, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eW = isOpaqueAtExt(nx - 1, ny, nz, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eU = isOpaqueAtExt(nx, ny + 1, nz, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eD = isOpaqueAtExt(nx, ny - 1, nz, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cUE = eE && eU
            || isOpaqueAtExt(nx + 1, ny + 1, nz, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cDE = eE && eD
            || isOpaqueAtExt(nx + 1, ny - 1, nz, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cDW = eW && eD
            || isOpaqueAtExt(nx - 1, ny - 1, nz, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cUW = eW && eU
            || isOpaqueAtExt(nx - 1, ny + 1, nz, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        int lE = getLightAtExt(nx + 1, ny, nz, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
            nZPosLight),
            lW = getLightAtExt(nx - 1, ny, nz, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                nZPosLight);
        int lU = getLightAtExt(nx, ny + 1, nz, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
            nZPosLight),
            lD = getLightAtExt(nx, ny - 1, nz, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                nZPosLight);
        int lUE = getLightAtExt(nx + 1, ny + 1, nz, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight,
            nZNegLight, nZPosLight),
            lDE = getLightAtExt(nx + 1, ny - 1, nz, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight,
                nZNegLight, nZPosLight);
        int lDW = getLightAtExt(nx - 1, ny - 1, nz, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight,
            nZNegLight, nZPosLight),
            lUW = getLightAtExt(nx - 1, ny + 1, nz, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight,
                nZNegLight, nZPosLight);

        aoOut[0] = calcVertexAO(eE, eU, cUE);
        aoOut[1] = calcVertexAO(eE, eD, cDE);
        aoOut[2] = calcVertexAO(eW, eD, cDW);
        aoOut[3] = calcVertexAO(eW, eU, cUW);
        lightOut[0] = avgLight4(cL, lE, lU, lUE, eE, eU);
        lightOut[1] = avgLight4(cL, lE, lD, lDE, eE, eD);
        lightOut[2] = avgLight4(cL, lW, lD, lDW, eW, eD);
        lightOut[3] = avgLight4(cL, lW, lU, lUW, eW, eU);
        break;
      }
      case 3: {
        boolean eW = isOpaqueAtExt(nx - 1, ny, nz, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eE = isOpaqueAtExt(nx + 1, ny, nz, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eU = isOpaqueAtExt(nx, ny + 1, nz, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eD = isOpaqueAtExt(nx, ny - 1, nz, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cUW = eW && eU
            || isOpaqueAtExt(nx - 1, ny + 1, nz, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cDW = eW && eD
            || isOpaqueAtExt(nx - 1, ny - 1, nz, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cDE = eE && eD
            || isOpaqueAtExt(nx + 1, ny - 1, nz, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cUE = eE && eU
            || isOpaqueAtExt(nx + 1, ny + 1, nz, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        int lW = getLightAtExt(nx - 1, ny, nz, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
            nZPosLight),
            lE = getLightAtExt(nx + 1, ny, nz, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                nZPosLight);
        int lU = getLightAtExt(nx, ny + 1, nz, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
            nZPosLight),
            lD = getLightAtExt(nx, ny - 1, nz, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                nZPosLight);
        int lUW = getLightAtExt(nx - 1, ny + 1, nz, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight,
            nZNegLight, nZPosLight),
            lDW = getLightAtExt(nx - 1, ny - 1, nz, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight,
                nZNegLight, nZPosLight);
        int lDE = getLightAtExt(nx + 1, ny - 1, nz, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight,
            nZNegLight, nZPosLight),
            lUE = getLightAtExt(nx + 1, ny + 1, nz, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight,
                nZNegLight, nZPosLight);

        aoOut[0] = calcVertexAO(eW, eU, cUW);
        aoOut[1] = calcVertexAO(eW, eD, cDW);
        aoOut[2] = calcVertexAO(eE, eD, cDE);
        aoOut[3] = calcVertexAO(eE, eU, cUE);
        lightOut[0] = avgLight4(cL, lW, lU, lUW, eW, eU);
        lightOut[1] = avgLight4(cL, lW, lD, lDW, eW, eD);
        lightOut[2] = avgLight4(cL, lE, lD, lDE, eE, eD);
        lightOut[3] = avgLight4(cL, lE, lU, lUE, eE, eU);
        break;
      }
      case 5: {
        boolean eU = isOpaqueAtExt(nx, ny + 1, nz, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eD = isOpaqueAtExt(nx, ny - 1, nz, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eN = isOpaqueAtExt(nx, ny, nz - 1, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eS = isOpaqueAtExt(nx, ny, nz + 1, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cUS = eU && eS
            || isOpaqueAtExt(nx, ny + 1, nz + 1, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cDS = eD && eS
            || isOpaqueAtExt(nx, ny - 1, nz + 1, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cDN = eD && eN
            || isOpaqueAtExt(nx, ny - 1, nz - 1, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cUN = eU && eN
            || isOpaqueAtExt(nx, ny + 1, nz - 1, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        int lU = getLightAtExt(nx, ny + 1, nz, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
            nZPosLight),
            lD = getLightAtExt(nx, ny - 1, nz, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                nZPosLight);
        int lN = getLightAtExt(nx, ny, nz - 1, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
            nZPosLight),
            lS = getLightAtExt(nx, ny, nz + 1, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                nZPosLight);
        int lUS = getLightAtExt(nx, ny + 1, nz + 1, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight,
            nZNegLight, nZPosLight),
            lDS = getLightAtExt(nx, ny - 1, nz + 1, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight,
                nZNegLight, nZPosLight);
        int lDN = getLightAtExt(nx, ny - 1, nz - 1, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight,
            nZNegLight, nZPosLight),
            lUN = getLightAtExt(nx, ny + 1, nz - 1, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight,
                nZNegLight, nZPosLight);

        aoOut[0] = calcVertexAO(eU, eS, cUS);
        aoOut[1] = calcVertexAO(eD, eS, cDS);
        aoOut[2] = calcVertexAO(eD, eN, cDN);
        aoOut[3] = calcVertexAO(eU, eN, cUN);
        lightOut[0] = avgLight4(cL, lU, lS, lUS, eU, eS);
        lightOut[1] = avgLight4(cL, lD, lS, lDS, eD, eS);
        lightOut[2] = avgLight4(cL, lD, lN, lDN, eD, eN);
        lightOut[3] = avgLight4(cL, lU, lN, lUN, eU, eN);
        break;
      }
      case 4: {
        boolean eU = isOpaqueAtExt(nx, ny + 1, nz, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eD = isOpaqueAtExt(nx, ny - 1, nz, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eN = isOpaqueAtExt(nx, ny, nz - 1, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eS = isOpaqueAtExt(nx, ny, nz + 1, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cUN = eU && eN
            || isOpaqueAtExt(nx, ny + 1, nz - 1, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cDN = eD && eN
            || isOpaqueAtExt(nx, ny - 1, nz - 1, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cDS = eD && eS
            || isOpaqueAtExt(nx, ny - 1, nz + 1, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cUS = eU && eS
            || isOpaqueAtExt(nx, ny + 1, nz + 1, blockStates, sidOpaque, nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        int lU = getLightAtExt(nx, ny + 1, nz, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
            nZPosLight),
            lD = getLightAtExt(nx, ny - 1, nz, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                nZPosLight);
        int lN = getLightAtExt(nx, ny, nz - 1, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
            nZPosLight),
            lS = getLightAtExt(nx, ny, nz + 1, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                nZPosLight);
        int lUN = getLightAtExt(nx, ny + 1, nz - 1, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight,
            nZNegLight, nZPosLight),
            lDN = getLightAtExt(nx, ny - 1, nz - 1, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight,
                nZNegLight, nZPosLight);
        int lDS = getLightAtExt(nx, ny - 1, nz + 1, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight,
            nZNegLight, nZPosLight),
            lUS = getLightAtExt(nx, ny + 1, nz + 1, lightData, nXNegLight, nXPosLight, nYNegLight, nYPosLight,
                nZNegLight, nZPosLight);

        aoOut[0] = calcVertexAO(eU, eN, cUN);
        aoOut[1] = calcVertexAO(eD, eN, cDN);
        aoOut[2] = calcVertexAO(eD, eS, cDS);
        aoOut[3] = calcVertexAO(eU, eS, cUS);
        lightOut[0] = avgLight4(cL, lU, lN, lUN, eU, eN);
        lightOut[1] = avgLight4(cL, lD, lN, lDN, eD, eN);
        lightOut[2] = avgLight4(cL, lD, lS, lDS, eD, eS);
        lightOut[3] = avgLight4(cL, lU, lS, lUS, eU, eS);
        break;
      }
      default:
        aoOut[0] = aoOut[1] = aoOut[2] = aoOut[3] = 1.0f;
        byte fl = (byte) (cL & 0xFF);
        lightOut[0] = lightOut[1] = lightOut[2] = lightOut[3] = fl;
    }
  }

  private static void emitFaceAO(ByteBuffer buf, int x, int y, int z,
      int normalIdx, byte r, byte g, byte b, byte a,
      boolean hasSpr, short uMin, short uMax, short vMin, short vMax,
      int topDrop, float[] ao, byte[] vLight) {
    byte nIdx = (byte) normalIdx;
    if (!hasSpr) {
      uMin = 0;
      uMax = (short) 65535;
      vMin = 0;
      vMax = (short) 65535;
    }
    short sx = (short) (x * 256), sy = (short) (y * 256), sz = (short) (z * 256);
    short ex = (short) ((x + 1) * 256), ey = (short) ((y + 1) * 256 - topDrop), ez = (short) ((z + 1) * 256);
    int ri = r & 0xFF, gi = g & 0xFF, bi = b & 0xFF;
    byte r0 = (byte) (int) (ri * ao[0]), g0 = (byte) (int) (gi * ao[0]), b0 = (byte) (int) (bi * ao[0]);
    byte r1 = (byte) (int) (ri * ao[1]), g1 = (byte) (int) (gi * ao[1]), b1 = (byte) (int) (bi * ao[1]);
    byte r2 = (byte) (int) (ri * ao[2]), g2 = (byte) (int) (gi * ao[2]), b2 = (byte) (int) (bi * ao[2]);
    byte r3 = (byte) (int) (ri * ao[3]), g3 = (byte) (int) (gi * ao[3]), b3 = (byte) (int) (bi * ao[3]);
    switch (normalIdx) {
      case 1:
        emitVertex(buf, sx, ey, sz, uMin, vMin, r0, g0, b0, a, vLight[0], nIdx);
        emitVertex(buf, sx, ey, ez, uMin, vMax, r1, g1, b1, a, vLight[1], nIdx);
        emitVertex(buf, ex, ey, ez, uMax, vMax, r2, g2, b2, a, vLight[2], nIdx);
        emitVertex(buf, ex, ey, sz, uMax, vMin, r3, g3, b3, a, vLight[3], nIdx);
        break;
      case 0:
        emitVertex(buf, sx, sy, ez, uMin, vMin, r0, g0, b0, a, vLight[0], nIdx);
        emitVertex(buf, sx, sy, sz, uMin, vMax, r1, g1, b1, a, vLight[1], nIdx);
        emitVertex(buf, ex, sy, sz, uMax, vMax, r2, g2, b2, a, vLight[2], nIdx);
        emitVertex(buf, ex, sy, ez, uMax, vMin, r3, g3, b3, a, vLight[3], nIdx);
        break;
      case 3:
        emitVertex(buf, sx, ey, ez, uMin, vMin, r0, g0, b0, a, vLight[0], nIdx);
        emitVertex(buf, sx, sy, ez, uMin, vMax, r1, g1, b1, a, vLight[1], nIdx);
        emitVertex(buf, ex, sy, ez, uMax, vMax, r2, g2, b2, a, vLight[2], nIdx);
        emitVertex(buf, ex, ey, ez, uMax, vMin, r3, g3, b3, a, vLight[3], nIdx);
        break;
      case 2:
        emitVertex(buf, ex, ey, sz, uMin, vMin, r0, g0, b0, a, vLight[0], nIdx);
        emitVertex(buf, ex, sy, sz, uMin, vMax, r1, g1, b1, a, vLight[1], nIdx);
        emitVertex(buf, sx, sy, sz, uMax, vMax, r2, g2, b2, a, vLight[2], nIdx);
        emitVertex(buf, sx, ey, sz, uMax, vMin, r3, g3, b3, a, vLight[3], nIdx);
        break;
      case 5:
        emitVertex(buf, ex, ey, ez, uMin, vMin, r0, g0, b0, a, vLight[0], nIdx);
        emitVertex(buf, ex, sy, ez, uMin, vMax, r1, g1, b1, a, vLight[1], nIdx);
        emitVertex(buf, ex, sy, sz, uMax, vMax, r2, g2, b2, a, vLight[2], nIdx);
        emitVertex(buf, ex, ey, sz, uMax, vMin, r3, g3, b3, a, vLight[3], nIdx);
        break;
      case 4:
        emitVertex(buf, sx, ey, sz, uMin, vMin, r0, g0, b0, a, vLight[0], nIdx);
        emitVertex(buf, sx, sy, sz, uMin, vMax, r1, g1, b1, a, vLight[1], nIdx);
        emitVertex(buf, sx, sy, ez, uMax, vMax, r2, g2, b2, a, vLight[2], nIdx);
        emitVertex(buf, sx, ey, ez, uMax, vMin, r3, g3, b3, a, vLight[3], nIdx);
        break;
    }
  }

  private static final ThreadLocal<short[][]> FACE_POS_POOL = ThreadLocal.withInitial(() -> new short[][] {
      new short[3], new short[3], new short[3], new short[3]
  });

  private int emitFaceScaled(ByteBuffer buf, int x, int y, int z, int normalIndex,
      TextureAtlasSprite sprite, byte packedLight, byte r, byte g,
      byte b, byte alpha, int scale, int topDrop) {

    short sx = (short) (x * 256), sy = (short) (y * 256), sz = (short) (z * 256);
    short nex = (short) ((x + scale) * 256), ney = (short) ((y + scale) * 256 - topDrop),
        nez = (short) ((z + scale) * 256);
    short[][] p = FACE_POS_POOL.get();
    switch (normalIndex) {
      case 1:
        p[0][0] = sx;
        p[0][1] = ney;
        p[0][2] = sz;
        p[1][0] = sx;
        p[1][1] = ney;
        p[1][2] = nez;
        p[2][0] = nex;
        p[2][1] = ney;
        p[2][2] = nez;
        p[3][0] = nex;
        p[3][1] = ney;
        p[3][2] = sz;
        break;
      case 0:
        p[0][0] = sx;
        p[0][1] = sy;
        p[0][2] = nez;
        p[1][0] = sx;
        p[1][1] = sy;
        p[1][2] = sz;
        p[2][0] = nex;
        p[2][1] = sy;
        p[2][2] = sz;
        p[3][0] = nex;
        p[3][1] = sy;
        p[3][2] = nez;
        break;
      case 3:
        p[0][0] = sx;
        p[0][1] = ney;
        p[0][2] = nez;
        p[1][0] = sx;
        p[1][1] = sy;
        p[1][2] = nez;
        p[2][0] = nex;
        p[2][1] = sy;
        p[2][2] = nez;
        p[3][0] = nex;
        p[3][1] = ney;
        p[3][2] = nez;
        break;
      case 2:
        p[0][0] = nex;
        p[0][1] = ney;
        p[0][2] = sz;
        p[1][0] = nex;
        p[1][1] = sy;
        p[1][2] = sz;
        p[2][0] = sx;
        p[2][1] = sy;
        p[2][2] = sz;
        p[3][0] = sx;
        p[3][1] = ney;
        p[3][2] = sz;
        break;
      case 5:
        p[0][0] = nex;
        p[0][1] = ney;
        p[0][2] = nez;
        p[1][0] = nex;
        p[1][1] = sy;
        p[1][2] = nez;
        p[2][0] = nex;
        p[2][1] = sy;
        p[2][2] = sz;
        p[3][0] = nex;
        p[3][1] = ney;
        p[3][2] = sz;
        break;
      case 4:
        p[0][0] = sx;
        p[0][1] = ney;
        p[0][2] = sz;
        p[1][0] = sx;
        p[1][1] = sy;
        p[1][2] = sz;
        p[2][0] = sx;
        p[2][1] = sy;
        p[2][2] = nez;
        p[3][0] = sx;
        p[3][1] = ney;
        p[3][2] = nez;
        break;
      default:
        p[0][0] = sx;
        p[0][1] = sy;
        p[0][2] = sz;
        p[1][0] = sx;
        p[1][1] = sy;
        p[1][2] = sz;
        p[2][0] = sx;
        p[2][1] = sy;
        p[2][2] = sz;
        p[3][0] = sx;
        p[3][1] = sy;
        p[3][2] = sz;
        break;
    }
    short uMin = 0, uMax = (short) 65535, vMin = 0, vMax = (short) 65535;
    if (sprite != null) {
      uMin = (short) (sprite.getU0() * 65535.0f);
      uMax = (short) (sprite.getU1() * 65535.0f);
      vMin = (short) (sprite.getV0() * 65535.0f);
      vMax = (short) (sprite.getV1() * 65535.0f);
    }

    byte a = alpha;
    byte sr = r, sg = g, sb = b;
    byte nIdx = (byte) normalIndex;

    buf.putShort(p[0][0]);
    buf.putShort(p[0][1]);
    buf.putShort(p[0][2]);
    buf.putShort(uMin);
    buf.putShort(vMin);
    buf.put(sr);
    buf.put(sg);
    buf.put(sb);
    buf.put(a);
    buf.put(packedLight);
    buf.put(nIdx);

    buf.putShort(p[1][0]);
    buf.putShort(p[1][1]);
    buf.putShort(p[1][2]);
    buf.putShort(uMin);
    buf.putShort(vMax);
    buf.put(sr);
    buf.put(sg);
    buf.put(sb);
    buf.put(a);
    buf.put(packedLight);
    buf.put(nIdx);

    buf.putShort(p[2][0]);
    buf.putShort(p[2][1]);
    buf.putShort(p[2][2]);
    buf.putShort(uMax);
    buf.putShort(vMax);
    buf.put(sr);
    buf.put(sg);
    buf.put(sb);
    buf.put(a);
    buf.put(packedLight);
    buf.put(nIdx);

    buf.putShort(p[3][0]);
    buf.putShort(p[3][1]);
    buf.putShort(p[3][2]);
    buf.putShort(uMax);
    buf.putShort(vMin);
    buf.put(sr);
    buf.put(sg);
    buf.put(sb);
    buf.put(a);
    buf.put(packedLight);
    buf.put(nIdx);
    return 1;
  }

  private static boolean isLeafBlock(Block block) {
    return block == Blocks.OAK_LEAVES || block == Blocks.BIRCH_LEAVES ||
        block == Blocks.SPRUCE_LEAVES || block == Blocks.JUNGLE_LEAVES ||
        block == Blocks.ACACIA_LEAVES || block == Blocks.DARK_OAK_LEAVES ||
        block == Blocks.AZALEA_LEAVES ||
        block == Blocks.FLOWERING_AZALEA_LEAVES ||
        block == Blocks.MANGROVE_LEAVES || block == Blocks.CHERRY_LEAVES;
  }

  private static boolean isTopOnlyDecorative(Block block) {
    return block == Blocks.SNOW ||
        block == Blocks.WHITE_CARPET || block == Blocks.ORANGE_CARPET ||
        block == Blocks.MAGENTA_CARPET || block == Blocks.LIGHT_BLUE_CARPET ||
        block == Blocks.YELLOW_CARPET || block == Blocks.LIME_CARPET ||
        block == Blocks.PINK_CARPET || block == Blocks.GRAY_CARPET ||
        block == Blocks.LIGHT_GRAY_CARPET || block == Blocks.CYAN_CARPET ||
        block == Blocks.PURPLE_CARPET || block == Blocks.BLUE_CARPET ||
        block == Blocks.BROWN_CARPET || block == Blocks.GREEN_CARPET ||
        block == Blocks.RED_CARPET || block == Blocks.BLACK_CARPET ||
        block == Blocks.MOSS_CARPET;
  }

  private static int computeDecorativeTopDrop(BlockState bs, Block blk) {
    if (blk == Blocks.SNOW) {

      int layers = bs.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LAYERS);

      return 256 - layers * 32;
    } else {

      return 256 - 16;
    }
  }

  private static final Map<Block, Integer> BLOCK_COLORS = new IdentityHashMap<>();
  private static final int DEFAULT_BLOCK_COLOR = 0xB0B0B0;
  private static final byte TINT_NONE = 0;
  private static final byte TINT_GRASS = 1;
  private static final byte TINT_FOLIAGE = 2;
  private static final byte TINT_WATER = 3;
  private static final IdentityHashMap<Block, Byte> BIOME_TINT_TYPE = new IdentityHashMap<>();
  static {
    BLOCK_COLORS.put(Blocks.GRASS_BLOCK, 0x7CBE3F);
    BLOCK_COLORS.put(Blocks.DIRT, 0x866043);
    BLOCK_COLORS.put(Blocks.COARSE_DIRT, 0x866043);
    BLOCK_COLORS.put(Blocks.ROOTED_DIRT, 0x866043);
    BLOCK_COLORS.put(Blocks.STONE, 0x7D7D7D);
    BLOCK_COLORS.put(Blocks.SMOOTH_STONE, 0x7D7D7D);
    BLOCK_COLORS.put(Blocks.COBBLESTONE, 0x7A7A7A);
    BLOCK_COLORS.put(Blocks.MOSSY_COBBLESTONE, 0x7A7A7A);
    BLOCK_COLORS.put(Blocks.GRANITE, 0x9A6B53);
    BLOCK_COLORS.put(Blocks.DIORITE, 0xBFBFBF);
    BLOCK_COLORS.put(Blocks.ANDESITE, 0x888888);
    BLOCK_COLORS.put(Blocks.DEEPSLATE, 0x505050);
    BLOCK_COLORS.put(Blocks.COBBLED_DEEPSLATE, 0x505050);
    BLOCK_COLORS.put(Blocks.TUFF, 0x6B6B5E);
    BLOCK_COLORS.put(Blocks.BEDROCK, 0x3A3A3A);
    BLOCK_COLORS.put(Blocks.SAND, 0xDCCD82);
    BLOCK_COLORS.put(Blocks.SANDSTONE, 0xDCCD82);
    BLOCK_COLORS.put(Blocks.RED_SAND, 0xBE6621);
    BLOCK_COLORS.put(Blocks.RED_SANDSTONE, 0xBE6621);
    BLOCK_COLORS.put(Blocks.GRAVEL, 0x857E79);
    BLOCK_COLORS.put(Blocks.CLAY, 0x9EA4B0);
    BLOCK_COLORS.put(Blocks.COAL_ORE, 0x4A4A4A);
    BLOCK_COLORS.put(Blocks.DEEPSLATE_COAL_ORE, 0x4A4A4A);
    BLOCK_COLORS.put(Blocks.IRON_ORE, 0xB08D63);
    BLOCK_COLORS.put(Blocks.DEEPSLATE_IRON_ORE, 0xB08D63);
    BLOCK_COLORS.put(Blocks.GOLD_ORE, 0xDBCD34);
    BLOCK_COLORS.put(Blocks.DEEPSLATE_GOLD_ORE, 0xDBCD34);
    BLOCK_COLORS.put(Blocks.DIAMOND_ORE, 0x5DDCD3);
    BLOCK_COLORS.put(Blocks.DEEPSLATE_DIAMOND_ORE, 0x5DDCD3);
    BLOCK_COLORS.put(Blocks.COPPER_ORE, 0xA86340);
    BLOCK_COLORS.put(Blocks.DEEPSLATE_COPPER_ORE, 0xA86340);
    BLOCK_COLORS.put(Blocks.LAPIS_ORE, 0x3450AB);
    BLOCK_COLORS.put(Blocks.DEEPSLATE_LAPIS_ORE, 0x3450AB);
    BLOCK_COLORS.put(Blocks.REDSTONE_ORE, 0xAA0000);
    BLOCK_COLORS.put(Blocks.DEEPSLATE_REDSTONE_ORE, 0xAA0000);
    BLOCK_COLORS.put(Blocks.EMERALD_ORE, 0x17DD62);
    BLOCK_COLORS.put(Blocks.DEEPSLATE_EMERALD_ORE, 0x17DD62);
    BLOCK_COLORS.put(Blocks.OAK_LOG, 0x6B5534);
    BLOCK_COLORS.put(Blocks.OAK_WOOD, 0x6B5534);
    BLOCK_COLORS.put(Blocks.OAK_PLANKS, 0xAF8F55);
    BLOCK_COLORS.put(Blocks.BIRCH_LOG, 0xD5CB93);
    BLOCK_COLORS.put(Blocks.BIRCH_WOOD, 0xD5CB93);
    BLOCK_COLORS.put(Blocks.BIRCH_PLANKS, 0xC5B77B);
    BLOCK_COLORS.put(Blocks.SPRUCE_LOG, 0x3D2813);
    BLOCK_COLORS.put(Blocks.SPRUCE_WOOD, 0x3D2813);
    BLOCK_COLORS.put(Blocks.SPRUCE_PLANKS, 0x674B2B);
    BLOCK_COLORS.put(Blocks.DARK_OAK_LOG, 0x372814);
    BLOCK_COLORS.put(Blocks.DARK_OAK_WOOD, 0x372814);
    BLOCK_COLORS.put(Blocks.DARK_OAK_PLANKS, 0x422C14);
    BLOCK_COLORS.put(Blocks.JUNGLE_LOG, 0x554A2F);
    BLOCK_COLORS.put(Blocks.JUNGLE_WOOD, 0x554A2F);
    BLOCK_COLORS.put(Blocks.ACACIA_LOG, 0x676157);
    BLOCK_COLORS.put(Blocks.ACACIA_WOOD, 0x676157);
    BLOCK_COLORS.put(Blocks.OAK_LEAVES, 0x4BA836);
    BLOCK_COLORS.put(Blocks.JUNGLE_LEAVES, 0x4BA836);
    BLOCK_COLORS.put(Blocks.ACACIA_LEAVES, 0x4BA836);
    BLOCK_COLORS.put(Blocks.DARK_OAK_LEAVES, 0x4BA836);
    BLOCK_COLORS.put(Blocks.BIRCH_LEAVES, 0x6B9940);
    BLOCK_COLORS.put(Blocks.SPRUCE_LEAVES, 0x3B6126);
    BLOCK_COLORS.put(Blocks.MANGROVE_LEAVES, 0x6A9B2D);
    BLOCK_COLORS.put(Blocks.CHERRY_LEAVES, 0xE8A5C8);
    BLOCK_COLORS.put(Blocks.AZALEA_LEAVES, 0x4BA836);
    BLOCK_COLORS.put(Blocks.FLOWERING_AZALEA_LEAVES, 0x4BA836);
    BLOCK_COLORS.put(Blocks.WATER, 0x3F76E4);
    BLOCK_COLORS.put(Blocks.ICE, 0x8DB3E2);
    BLOCK_COLORS.put(Blocks.PACKED_ICE, 0x8DB3E2);
    BLOCK_COLORS.put(Blocks.BLUE_ICE, 0x8DB3E2);
    BLOCK_COLORS.put(Blocks.SNOW_BLOCK, 0xF0F0F0);
    BLOCK_COLORS.put(Blocks.SNOW, 0xF0F0F0);
    BLOCK_COLORS.put(Blocks.POWDER_SNOW, 0xF0F0F0);
    BLOCK_COLORS.put(Blocks.NETHERRACK, 0x6B3430);
    BLOCK_COLORS.put(Blocks.SOUL_SAND, 0x513F32);
    BLOCK_COLORS.put(Blocks.SOUL_SOIL, 0x513F32);
    BLOCK_COLORS.put(Blocks.BASALT, 0x4B4B4F);
    BLOCK_COLORS.put(Blocks.SMOOTH_BASALT, 0x4B4B4F);
    BLOCK_COLORS.put(Blocks.BLACKSTONE, 0x2C2630);
    BLOCK_COLORS.put(Blocks.GLOWSTONE, 0xAB8048);
    BLOCK_COLORS.put(Blocks.GLASS, 0xFFFFFF);
    BIOME_TINT_TYPE.put(Blocks.GRASS_BLOCK, TINT_GRASS);
    BIOME_TINT_TYPE.put(Blocks.SHORT_GRASS, TINT_GRASS);
    BIOME_TINT_TYPE.put(Blocks.TALL_GRASS, TINT_GRASS);
    BIOME_TINT_TYPE.put(Blocks.FERN, TINT_GRASS);
    BIOME_TINT_TYPE.put(Blocks.LARGE_FERN, TINT_GRASS);
    BIOME_TINT_TYPE.put(Blocks.SUGAR_CANE, TINT_GRASS);
    BIOME_TINT_TYPE.put(Blocks.POTTED_FERN, TINT_GRASS);
    BIOME_TINT_TYPE.put(Blocks.OAK_LEAVES, TINT_FOLIAGE);
    BIOME_TINT_TYPE.put(Blocks.JUNGLE_LEAVES, TINT_FOLIAGE);
    BIOME_TINT_TYPE.put(Blocks.ACACIA_LEAVES, TINT_FOLIAGE);
    BIOME_TINT_TYPE.put(Blocks.DARK_OAK_LEAVES, TINT_FOLIAGE);
    BIOME_TINT_TYPE.put(Blocks.MANGROVE_LEAVES, TINT_FOLIAGE);
    BIOME_TINT_TYPE.put(Blocks.VINE, TINT_FOLIAGE);
    BIOME_TINT_TYPE.put(Blocks.WATER, TINT_WATER);

    try {
      var leafLitterField = Blocks.class.getField("LEAF_LITTER");
      if (leafLitterField != null) {
        Block leafLitter = (Block) leafLitterField.get(null);
        BIOME_TINT_TYPE.put(leafLitter, TINT_FOLIAGE);
      }
    } catch (Exception ignored) {
    }

    try {
      net.minecraft.resources.Identifier leafLitterId = net.minecraft.resources.Identifier
          .fromNamespaceAndPath("minecraft", "leaf_litter");
      net.minecraft.core.Registry<Block> blockRegistry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
      if (blockRegistry.containsKey(leafLitterId)) {
        Block leafLitterBlock = blockRegistry.get(leafLitterId).map(net.minecraft.core.Holder.Reference::value)
            .orElse(null);
        if (leafLitterBlock != null && !BIOME_TINT_TYPE.containsKey(leafLitterBlock)) {
          BIOME_TINT_TYPE.put(leafLitterBlock, TINT_FOLIAGE);
        }
      }
    } catch (Exception ignored) {
    }
  }

  private static final ConcurrentHashMap<Block, Byte> DYNAMIC_TINT_CACHE = new ConcurrentHashMap<>();

  private static byte getBiomeTintType(Block block) {
    Byte type = BIOME_TINT_TYPE.get(block);
    if (type != null)
      return type;

    type = DYNAMIC_TINT_CACHE.get(block);
    if (type != null)
      return type;
    DYNAMIC_TINT_CACHE.put(block, TINT_NONE);
    return TINT_NONE;
  }

  private static int getBiomeColor(ClientLevel world, int worldX, int worldY, int worldZ, byte tintType) {
    if (world == null || tintType == TINT_NONE)
      return DEFAULT_BLOCK_COLOR;
    try {
      BlockPos.MutableBlockPos pos = BIOME_POS_POOL.get();
      pos.set(worldX, worldY, worldZ);
      return switch (tintType) {
        case TINT_GRASS -> world.getBlockTint(pos, net.minecraft.client.renderer.BiomeColors.GRASS_COLOR_RESOLVER);
        case TINT_FOLIAGE -> world.getBlockTint(pos, net.minecraft.client.renderer.BiomeColors.FOLIAGE_COLOR_RESOLVER);
        case TINT_WATER -> world.getBlockTint(pos, net.minecraft.client.renderer.BiomeColors.WATER_COLOR_RESOLVER);
        default -> DEFAULT_BLOCK_COLOR;
      };
    } catch (Exception e) {
      return DEFAULT_BLOCK_COLOR;
    }
  }

  private static final ThreadLocal<BlockPos.MutableBlockPos> BIOME_POS_POOL = ThreadLocal
      .withInitial(BlockPos.MutableBlockPos::new);

  private static final ThreadLocal<int[]> BIOME_COLORS_POOL = ThreadLocal.withInitial(() -> new int[4]);

  private static int avgColor(int[] packed, int count) {
    if (count == 0)
      return DEFAULT_BLOCK_COLOR;
    long r = 0, g = 0, b = 0;
    for (int i = 0; i < count; i++) {
      r += (packed[i] >> 16) & 0xFF;
      g += (packed[i] >> 8) & 0xFF;
      b += packed[i] & 0xFF;
    }
    return (int) (((r / count) << 16) | ((g / count) << 8) | (b / count));
  }

  private static int[] boxBlurColors(int[] src, int w, int h, int radius) {
    int[] dst = new int[w * h];
    for (int row = 0; row < h; row++) {
      for (int col = 0; col < w; col++) {
        long r = 0, g = 0, b = 0;
        int cnt = 0;
        for (int dr = -radius; dr <= radius; dr++) {
          for (int dc = -radius; dc <= radius; dc++) {
            int nr = row + dr, nc = col + dc;
            if (nr < 0)
              nr = 0;
            else if (nr >= h)
              nr = h - 1;
            if (nc < 0)
              nc = 0;
            else if (nc >= w)
              nc = w - 1;
            int c = src[nr * w + nc];
            r += (c >> 16) & 0xFF;
            g += (c >> 8) & 0xFF;
            b += c & 0xFF;
            cnt++;
          }
        }
        dst[row * w + col] = (int) (((r / cnt) << 16) | ((g / cnt) << 8) | (b / cnt));
      }
    }
    return dst;
  }

  private static int[][] buildBiomeSampleOffsets(int gridSize, int dy) {
    int[][] offsets = new int[gridSize * gridSize][3];
    int idx = 0;
    for (int row = 0; row < gridSize; row++) {
      for (int col = 0; col < gridSize; col++) {

        int ox = 1 + (int) Math.round(col * 14.0 / Math.max(1, gridSize - 1));
        int oz = 1 + (int) Math.round(row * 14.0 / Math.max(1, gridSize - 1));
        offsets[idx++] = new int[] { ox, dy, oz };
      }
    }
    return offsets;
  }

  private static int[] getSectionBiomeColors(ClientLevel world, int chunkX, int chunkY, int chunkZ, int detail) {
    int[] colors = BIOME_COLORS_POOL.get();
    colors[0] = DEFAULT_BLOCK_COLOR;
    colors[1] = DEFAULT_BLOCK_COLOR;
    colors[2] = DEFAULT_BLOCK_COLOR;
    colors[3] = DEFAULT_BLOCK_COLOR;
    if (world == null)
      return colors;

    int bx = chunkX * 16, by = chunkY * 16, bz = chunkZ * 16;

    int sampleY = Math.max(63, by + 8);
    int dy = sampleY - by;

    final int[][] offsets;
    if (detail <= 0) {
      offsets = new int[][] { { 8, dy, 8 } };
    } else if (detail == 1) {
      offsets = new int[][] {
          { 8, dy, 8 }, { 2, dy, 2 }, { 14, dy, 2 }, { 2, dy, 14 }, { 14, dy, 14 }
      };
    } else {
      int gridSize = detail == 2 ? 3 : detail == 3 ? 5 : detail == 4 ? 7 : 9;
      offsets = buildBiomeSampleOffsets(gridSize, dy);
    }

    int[] tmp = new int[offsets.length];
    BlockPos.MutableBlockPos pos = BIOME_POS_POOL.get();
    try {
      int valid = 0;
      for (int[] off : offsets) {
        pos.set(bx + off[0], by + off[1], bz + off[2]);
        tmp[valid++] = world.getBlockTint(pos, net.minecraft.client.renderer.BiomeColors.GRASS_COLOR_RESOLVER);
      }
      colors[TINT_GRASS] = avgColor(tmp, valid);

      valid = 0;
      for (int[] off : offsets) {
        pos.set(bx + off[0], by + off[1], bz + off[2]);
        tmp[valid++] = world.getBlockTint(pos, net.minecraft.client.renderer.BiomeColors.FOLIAGE_COLOR_RESOLVER);
      }
      colors[TINT_FOLIAGE] = avgColor(tmp, valid);

      valid = 0;
      for (int[] off : offsets) {
        pos.set(bx + off[0], by + off[1], bz + off[2]);
        tmp[valid++] = world.getBlockTint(pos, net.minecraft.client.renderer.BiomeColors.WATER_COLOR_RESOLVER);
      }
      colors[TINT_WATER] = avgColor(tmp, valid);
    } catch (Exception e) {
      colors[TINT_GRASS] = 0x7CBE3F;
      colors[TINT_FOLIAGE] = 0x4BA836;
      colors[TINT_WATER] = 0x3F76E4;
    }
    return colors;
  }

  private static int getBlockColor(BlockState state) {
    if (state == null)
      return 0xC8C8C8;
    Integer color = BLOCK_COLORS.get(state.getBlock());
    return color != null ? color : DEFAULT_BLOCK_COLOR;
  }

  public ChunkMeshData getMesh(int cx, int cy, int cz) {
    synchronized (meshCache) {
      return meshCache.get(packChunkKey(cx, cy, cz));
    }
  }

  public FarFieldDigest getFarFieldDigest(int cx, int cy, int cz) {
    synchronized (farFieldDigestCache) {
      return farFieldDigestCache.get(packChunkKey(cx, cy, cz));
    }
  }

  public java.util.List<FarFieldDigest> getAllFarFieldDigests() {
    synchronized (farFieldDigestCache) {
      return new java.util.ArrayList<>(farFieldDigestCache.values());
    }
  }

  public int getFarFieldDigestCount() {
    synchronized (farFieldDigestCache) {
      return farFieldDigestCache.size();
    }
  }

  public void removeMesh(int cx, int cy, int cz) {
    long key = packChunkKey(cx, cy, cz);
    ChunkMeshData mesh;
    synchronized (meshCache) {
      mesh = meshCache.remove(key);
    }
    removeFarFieldDigest(key);
    if (mesh != null) {
      NativeBridge.nUnregisterChunkMesh(cx, cy, cz);
      NativeBridge.nDestroyBuffer(mesh.bufferHandle);
      meshCountAtomic.decrementAndGet();
    }

    synchronized (emptyKeys) {
      emptyKeys.remove(key);
    }
  }

  public void clear() {
    synchronized (meshCache) {
      for (ChunkMeshData mesh : meshCache.values()) {
        NativeBridge.nUnregisterChunkMesh(mesh.chunkX, mesh.chunkY, mesh.chunkZ);
        NativeBridge.nDestroyBuffer(mesh.bufferHandle);
      }
      meshCache.clear();
    }
    synchronized (farFieldDigestCache) {
      farFieldDigestCache.clear();
    }
    synchronized (approxLightKeys) {
      approxLightKeys.clear();
    }
    synchronized (queuedLightFixKeys) {
      queuedLightFixKeys.clear();
    }
    synchronized (lightFixQueue) {
      lightFixQueue.clear();
    }
    clearUploadQueue();

    if (NativeBridge.isLibLoaded()) {
      NativeBridge.nClearAllChunkRegistrations();
    }
    if (globalIndexBufferHandle != 0) {
      NativeBridge.nDestroyBuffer(globalIndexBufferHandle);
      globalIndexBufferHandle = 0;
    }
    synchronized (pendingVisibleSectionNanos) {
      pendingVisibleSectionNanos.clear();
    }
    synchronized (pendingBlockUpdateNanos) {
      pendingBlockUpdateNanos.clear();
    }
    visibleSectionLatencyAccNs.set(0L);
    visibleSectionLatencySamples.set(0);
    blockUpdateLatencyAccNs.set(0L);
    blockUpdateLatencySamples.set(0);
  }

  public java.util.List<ChunkMeshData> getAllMeshes() {
    int currentGen = meshUpdateGeneration.get();
    if (currentGen == cachedSnapshotGen) {
      return cachedMeshSnapshot;
    }
    synchronized (meshCache) {

      cachedMeshSnapshot.clear();
      cachedMeshSnapshot.addAll(meshCache.values());

      cachedSnapshotGen = meshUpdateGeneration.get();
    }
    return cachedMeshSnapshot;
  }
}
