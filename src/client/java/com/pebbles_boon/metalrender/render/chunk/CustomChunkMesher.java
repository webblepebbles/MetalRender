package com.pebbles_boon.metalrender.render.chunk;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.nativebridge.NativeMemory;
import com.pebbles_boon.metalrender.performance.BuildBudgetEstimator;
import com.pebbles_boon.metalrender.performance.MetalRenderProfiler;
import com.pebbles_boon.metalrender.performance.PerformanceController;
import com.pebbles_boon.metalrender.util.MetalLogger;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.joml.Vector3fc;

public class CustomChunkMesher {
  public enum PassType {
    SOLID, CUTOUT, TRANSLUCENT
  }

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
  private static final ThreadLocal<ByteBuffer> SORT_BUF_POOL = ThreadLocal
      .withInitial(() -> ByteBuffer.allocateDirect(VERTEX_BUF_SIZE)
          .order(ByteOrder.nativeOrder()));
  private final Long2ObjectOpenHashMap<ChunkMeshData> meshCache;

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
  private final java.util.concurrent.ThreadPoolExecutor builderPool;
  private final int boostedBuilderThreadCount;
  private final int steadyBuilderThreadCount;
  private final int maxBuilderThreadCount;
  private final int steadyInstantThreadCount;
  private final int maxInstantThreadCount;
  private static final int UPLOAD_PARALLELISM = Math.max(12, Runtime.getRuntime().availableProcessors());
  private static final java.util.concurrent.Semaphore UPLOAD_SEMAPHORE = new java.util.concurrent.Semaphore(UPLOAD_PARALLELISM);
  private static final int NORMAL_TOTAL_THREAD_BUDGET = 64;
  private static final int BURST_TOTAL_THREAD_BUDGET = 128;
  private static final int BURST_MAX_BUILDER_THREADS = 28;
  private static final int BURST_MAX_INSTANT_THREADS = 12;
  private static final int HIGH_PRIORITY_QUEUE_SPILLOVER_THRESHOLD = 24;
  private static final int INTERACTIVE_PRIORITY_QUEUE_SPILLOVER_THRESHOLD = 48;
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

  private static final java.util.concurrent.atomic.AtomicLong TASK_SEQUENCE = new java.util.concurrent.atomic.AtomicLong();

  private static final class PrioritizedMeshTask implements Runnable, Comparable<PrioritizedMeshTask> {
    final int priority;
    final long sequence;
    final Runnable task;

    PrioritizedMeshTask(int priority, Runnable task) {
      this.priority = priority;
      this.sequence = TASK_SEQUENCE.getAndIncrement();
      this.task = task;
    }

    @Override
    public void run() {
      task.run();
    }

    @Override
    public int compareTo(PrioritizedMeshTask other) {
      int byPriority = Integer.compare(priority, other.priority);
      return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
    }
  }

  private static final class MeshBuildContext {
    final BlockStateModelSet blockModels;
    final int[] biomeColors;
    final int buildPlayerCX;
    final int buildPlayerCY;
    final int buildPlayerCZ;

    MeshBuildContext(BlockStateModelSet blockModels, int[] biomeColors,
        int buildPlayerCX, int buildPlayerCY, int buildPlayerCZ) {
      this.blockModels = blockModels;
      this.biomeColors = biomeColors;
      this.buildPlayerCX = buildPlayerCX;
      this.buildPlayerCY = buildPlayerCY;
      this.buildPlayerCZ = buildPlayerCZ;
    }
  }

  private static final class SectionSnapshot {
    final boolean valid;
    final boolean empty;
    final int[] blockStates;
    final byte[] lightData;
    final int[] nXNeg;
    final int[] nXPos;
    final int[] nYNeg;
    final int[] nYPos;
    final int[] nZNeg;
    final int[] nZPos;
    final byte[] nXNegLight;
    final byte[] nXPosLight;
    final byte[] nYNegLight;
    final byte[] nYPosLight;
    final byte[] nZNegLight;
    final byte[] nZPosLight;
    final MeshBuildContext context;

    SectionSnapshot(boolean valid, boolean empty, int[] blockStates,
        byte[] lightData, int[] nXNeg, int[] nXPos, int[] nYNeg,
        int[] nYPos, int[] nZNeg, int[] nZPos, byte[] nXNegLight,
        byte[] nXPosLight, byte[] nYNegLight, byte[] nYPosLight,
        byte[] nZNegLight, byte[] nZPosLight, MeshBuildContext context) {
      this.valid = valid;
      this.empty = empty;
      this.blockStates = blockStates;
      this.lightData = lightData;
      this.nXNeg = nXNeg;
      this.nXPos = nXPos;
      this.nYNeg = nYNeg;
      this.nYPos = nYPos;
      this.nZNeg = nZNeg;
      this.nZPos = nZPos;
      this.nXNegLight = nXNegLight;
      this.nXPosLight = nXPosLight;
      this.nYNegLight = nYNegLight;
      this.nYPosLight = nYPosLight;
      this.nZNegLight = nZNegLight;
      this.nZPosLight = nZPosLight;
      this.context = context;
    }
  }

  private static final int[][] AO_BILINEAR = {
      { 1, 0, 2, 3 }, { 0, 1, 3, 2 }, { 2, 3, 1, 0 },
      { 1, 0, 2, 3 }, { 1, 0, 2, 3 }, { 2, 3, 1, 0 },
  };

  private static float bilinearAO(float[] ao, int face, float localX,
      float localY, float localZ) {
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
    return ao[m[0]] * (1 - u) * (1 - v) + ao[m[1]] * (1 - u) * v +
        ao[m[2]] * u * (1 - v) + ao[m[3]] * u * v;
  }

  private static byte bilinearLight(byte[] vLight, int face, float localX,
      float localY, float localZ) {
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
    int l0 = vLight[m[0]] & 0xFF, l1 = vLight[m[1]] & 0xFF,
        l2 = vLight[m[2]] & 0xFF, l3 = vLight[m[3]] & 0xFF;
    int bl = (int) ((l0 & 0xF) * (1 - u) * (1 - v) + (l1 & 0xF) * (1 - u) * v +
        (l2 & 0xF) * u * (1 - v) + (l3 & 0xF) * u * v + 0.5f);
    int sl = (int) (((l0 >> 4) & 0xF) * (1 - u) * (1 - v) +
        ((l1 >> 4) & 0xF) * (1 - u) * v +
        ((l2 >> 4) & 0xF) * u * (1 - v) + ((l3 >> 4) & 0xF) * u * v +
        0.5f);
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
          net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
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
    public final long visibilityMask;
    public final int[] facingQuadCounts;

    public final int buildPlayerCX, buildPlayerCY, buildPlayerCZ;

    public ChunkMeshData(long bufferHandle, int quadCount, int chunkX,
        int chunkY, int chunkZ,
        int buildPlayerCX, int buildPlayerCY,
        int buildPlayerCZ) {
      this(bufferHandle, quadCount, chunkX, chunkY, chunkZ,
          buildPlayerCX, buildPlayerCY, buildPlayerCZ, 0L, new int[7]);
    }

    public ChunkMeshData(long bufferHandle, int quadCount, int chunkX,
        int chunkY, int chunkZ,
        int buildPlayerCX, int buildPlayerCY,
        int buildPlayerCZ, long visibilityMask, int[] facingQuadCounts) {
      this.bufferHandle = bufferHandle;
      this.quadCount = quadCount;
      this.chunkX = chunkX;
      this.chunkY = chunkY;
      this.chunkZ = chunkZ;
      this.buildPlayerCX = buildPlayerCX;
      this.buildPlayerCY = buildPlayerCY;
      this.buildPlayerCZ = buildPlayerCZ;
      this.visibilityMask = visibilityMask;
      this.facingQuadCounts = facingQuadCounts != null
          ? java.util.Arrays.copyOf(facingQuadCounts, 14)
          : new int[14];
    }
  }

  public CustomChunkMesher() {
    this.meshCache = new Long2ObjectOpenHashMap<>();
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

    final int warmupThreadCount = warmupThreads;
    final int steadyThreadCount = steadyThreads;
    this.boostedBuilderThreadCount = warmupThreadCount;
    this.steadyBuilderThreadCount = steadyThreadCount;
    this.maxBuilderThreadCount = maxBuilderThreads;
    this.steadyInstantThreadCount = steadyInstantThreads;
    this.maxInstantThreadCount = Math.max(steadyInstantThreads, maxInstantThreads);
    java.util.concurrent.ThreadFactory meshFactory = r -> {
      Thread t = new Thread(r, "MetalRender-MeshBuilder");
      t.setDaemon(true);
      t.setPriority(Thread.NORM_PRIORITY);
      return t;
    };
    this.builderPool = new java.util.concurrent.ThreadPoolExecutor(
        warmupThreadCount, warmupThreadCount, 10L,
        java.util.concurrent.TimeUnit.SECONDS,
        new java.util.concurrent.PriorityBlockingQueue<>(), meshFactory);
    this.builderPool.allowCoreThreadTimeOut(true);

    java.util.concurrent.ScheduledExecutorService warmupTimer = java.util.concurrent.Executors
        .newSingleThreadScheduledExecutor(r -> {
          Thread t = new Thread(r, "MetalRender-WarmupTimer");
          t.setDaemon(true);
          return t;
        });
    warmupTimer.schedule(() -> {
      if (getPendingCount() == 0) {
        updateThreadPoolSize(builderPool, steadyThreadCount);
      }
      warmupTimer.shutdown();
    }, 30, java.util.concurrent.TimeUnit.SECONDS);

  }

  public long getGlobalIndexBuffer() {
    return globalIndexBufferHandle;
  }

  public void initialize(long device) {
    this.deviceHandle = device;
    MetalLogger.info("CustomChunkMesher initialize: device=%d loadingConfig=%s",
        device, MetalRenderClient.getConfig() != null
            ? MetalRenderClient.getConfig().enableMetalRendering
            : false);
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
    this.initialized = true;
    MetalLogger.info("Chunk mesh uploads using fallback path (parallelism=%d)",
        UPLOAD_PARALLELISM);
    MetalLogger.info("CustomChunkMesher initialized (maxQuads=%d, ibSize=%d)",
        MAX_QUADS, ibData.length);
  }

  private static long packChunkKey(int x, int y, int z) {
    return ((long) (x & 0x3FFFFF) << 42) | ((long) (y & 0xFFFFF) << 22) |
        (z & 0x3FFFFF);
  }

  private void submitMeshTask(int priority, Runnable task) {
    builderPool.execute(new PrioritizedMeshTask(priority, task));
  }

  private boolean isTaskCancelled(long key, long generation) {
    synchronized (dirtyGeneration) {
      return dirtyGeneration.get(key) != generation;
    }
  }

  private MeshBuildContext captureBuildContext(ClientLevel world, int chunkX,
      int chunkY, int chunkZ) {
    Minecraft mc = Minecraft.getInstance();
    BlockStateModelSet blockModels = null;
    int buildPCX = 0;
    int buildPCY = 0;
    int buildPCZ = 0;
    if (mc != null) {
      if (mc.getModelManager() != null) {
        blockModels = mc.getModelManager().getBlockStateModelSet();
      }
      if (mc.player != null) {
        buildPCX = mc.player.chunkPosition().x();
        buildPCZ = mc.player.chunkPosition().z();
        buildPCY = (int) Math.floor(mc.player.getY()) >> 4;
      }
    }
    int[] biomeColors = java.util.Arrays.copyOf(
        getSectionBiomeColors(world, chunkX, chunkY, chunkZ,
            MetalRenderClient.getConfig().biomeTransitionDetail),
        4);
    return new MeshBuildContext(blockModels, biomeColors, buildPCX, buildPCY,
        buildPCZ);
  }

  private void removeEmptyMesh(long key, int chunkX, int chunkY, int chunkZ) {
    synchronized (meshCache) {
      ChunkMeshData old = meshCache.remove(key);
      if (old != null) {
        NativeBridge.nUnregisterChunkMesh(chunkX, chunkY, chunkZ);
        NativeBridge.nDestroyBuffer(old.bufferHandle);
        meshCountAtomic.decrementAndGet();
        vertexCountAtomic.addAndGet(-old.quadCount * 4);
      }
    }
    synchronized (emptyKeys) {
      emptyKeys.add(key);
    }
    synchronized (dirtyKeys) {
      dirtyKeys.remove(key);
    }
    meshUpdateGeneration.incrementAndGet();
    recordVisibleLatency(key);
  }

  private static int[] copyNeighborFace(LevelChunkSection section, int faceDir) {
    int[] out = new int[SECTION_SIZE * SECTION_SIZE];
    if (!readNeighborFaceStatic(section, faceDir, out)) {
      return null;
    }
    return out;
  }

  private static byte[] copyNeighborLightFace(ClientLevel world, int baseX,
      int baseY, int baseZ, LevelChunkSection section, int faceDir,
      BlockPos.MutableBlockPos mutablePos) {
    byte[] out = new byte[SECTION_SIZE * SECTION_SIZE];
    if (!readNeighborLightFaceStatic(world, baseX, baseY, baseZ, section,
        faceDir, out, mutablePos)) {
      return null;
    }
    return out;
  }

  private SectionSnapshot captureSectionSnapshot(ClientLevel world, int chunkX,
      int chunkY, int chunkZ, boolean useApproximateLight) {
    if (world == null) {
      return new SectionSnapshot(false, false, null, null, null, null, null,
          null, null, null, null, null, null, null, null, null,
          captureBuildContext(null, chunkX, chunkY, chunkZ));
    }
    LevelChunk chunk = world.getChunkSource().getChunkNow(chunkX, chunkZ);
    if (chunk == null) {
      return new SectionSnapshot(false, false, null, null, null, null, null,
          null, null, null, null, null, null, null, null, null,
          captureBuildContext(world, chunkX, chunkY, chunkZ));
    }
    int sectionIdx = chunk.getSectionIndexFromSectionY(chunkY);
    LevelChunkSection[] chunkSections = chunk.getSections();
    if (sectionIdx < 0 || sectionIdx >= chunkSections.length) {
      return new SectionSnapshot(false, false, null, null, null, null, null,
          null, null, null, null, null, null, null, null, null,
          captureBuildContext(world, chunkX, chunkY, chunkZ));
    }
    LevelChunkSection section = chunkSections[sectionIdx];
    MeshBuildContext context = captureBuildContext(world, chunkX, chunkY, chunkZ);
    if (section == null || section.hasOnlyAir()) {
      return new SectionSnapshot(true, true, null, null, null, null, null,
          null, null, null, null, null, null, null, null, null, context);
    }
    int[] blockStates = new int[SECTION_SIZE * SECTION_SIZE * SECTION_SIZE];
    boolean hasAnyBlock = false;
    for (int y = 0; y < SECTION_SIZE; y++) {
      for (int z = 0; z < SECTION_SIZE; z++) {
        for (int x = 0; x < SECTION_SIZE; x++) {
          BlockState bs = section.getBlockState(x, y, z);
          if (!bs.isAir()) {
            blockStates[y * 256 + z * 16 + x] = Block.getId(bs);
            hasAnyBlock = true;
          }
        }
      }
    }
    if (!hasAnyBlock) {
      return new SectionSnapshot(true, true, null, null, null, null, null,
          null, null, null, null, null, null, null, null, null, context);
    }
    LevelChunkSection sectionXNeg = resolveSection(world, chunkX - 1, chunkY, chunkZ);
    LevelChunkSection sectionXPos = resolveSection(world, chunkX + 1, chunkY, chunkZ);
    LevelChunkSection sectionYNeg = sectionIdx > 0 ? chunkSections[sectionIdx - 1] : null;
    LevelChunkSection sectionYPos = sectionIdx + 1 < chunkSections.length
        ? chunkSections[sectionIdx + 1]
        : null;
    LevelChunkSection sectionZNeg = resolveSection(world, chunkX, chunkY, chunkZ - 1);
    LevelChunkSection sectionZPos = resolveSection(world, chunkX, chunkY, chunkZ + 1);
    int[] nXNeg = copyNeighborFace(sectionXNeg, 4);
    int[] nXPos = copyNeighborFace(sectionXPos, 5);
    int[] nYNeg = copyNeighborFace(sectionYNeg, 0);
    int[] nYPos = copyNeighborFace(sectionYPos, 1);
    int[] nZNeg = copyNeighborFace(sectionZNeg, 2);
    int[] nZPos = copyNeighborFace(sectionZPos, 3);
    byte[] lightData = new byte[SECTION_SIZE * SECTION_SIZE * SECTION_SIZE];
    BlockPos.MutableBlockPos sharedMpos = MUTABLE_POS_POOL.get();
    byte[] nXNegLight = null;
    byte[] nXPosLight = null;
    byte[] nYNegLight = null;
    byte[] nYPosLight = null;
    byte[] nZNegLight = null;
    byte[] nZPosLight = null;
    if (useApproximateLight) {
      fillApproximateLightData(world, chunkX, chunkY, chunkZ, blockStates,
          lightData, sharedMpos);
    } else {
      nXNegLight = copyNeighborLightFace(world, (chunkX - 1) * 16,
          chunkY * 16, chunkZ * 16, sectionXNeg, 4, sharedMpos);
      nXPosLight = copyNeighborLightFace(world, (chunkX + 1) * 16,
          chunkY * 16, chunkZ * 16, sectionXPos, 5, sharedMpos);
      nYNegLight = copyNeighborLightFace(world, chunkX * 16,
          (chunkY - 1) * 16, chunkZ * 16, sectionYNeg, 0, sharedMpos);
      nYPosLight = copyNeighborLightFace(world, chunkX * 16,
          (chunkY + 1) * 16, chunkZ * 16, sectionYPos, 1, sharedMpos);
      nZNegLight = copyNeighborLightFace(world, chunkX * 16,
          chunkY * 16, (chunkZ - 1) * 16, sectionZNeg, 2, sharedMpos);
      nZPosLight = copyNeighborLightFace(world, chunkX * 16,
          chunkY * 16, (chunkZ + 1) * 16, sectionZPos, 3, sharedMpos);
      try {
        int baseX = chunkX * 16;
        int baseY = chunkY * 16;
        int baseZ = chunkZ * 16;
        for (int y = 0; y < SECTION_SIZE; y++) {
          for (int z = 0; z < SECTION_SIZE; z++) {
            for (int x = 0; x < SECTION_SIZE; x++) {
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
      } catch (Exception e) {
        recordLightSampleFallback("snapshot_fill", e);
        java.util.Arrays.fill(lightData, (byte) 0x00);
      }
    }
    return new SectionSnapshot(true, false, blockStates, lightData, nXNeg,
        nXPos, nYNeg, nYPos, nZNeg, nZPos, nXNegLight, nXPosLight,
        nYNegLight, nYPosLight, nZNegLight, nZPosLight, context);
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
      int leafMode, int[] nXNeg,
      int[] nXPos, int[] nYNeg,
      int[] nYPos, int[] nZNeg,
      int[] nZPos, byte[] oFlag) {
    return isTransparentFlatFor(states, x, y, z, leafMode, nXNeg, nXPos, nYNeg,
        nYPos, nZNeg, nZPos, oFlag, false);
  }

  private static boolean isTransparentFlatFor(int[] states, int x, int y, int z,
      int leafMode, int[] nXNeg,
      int[] nXPos, int[] nYNeg,
      int[] nYPos, int[] nZNeg,
      int[] nZPos, byte[] oFlag,
      boolean fromTransCube) {
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
      int[] nXNeg, int[] nXPos, int[] nYNeg,
      int[] nYPos, int[] nZNeg, int[] nZPos) {
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
      int[] nXNeg, int[] nXPos, int[] nYNeg,
      int[] nYPos, int[] nZNeg, int[] nZPos) {

    if (isWaterAt(blockStates, x, y + 1, z, nXNeg, nXPos, nYNeg, nYPos, nZNeg,
        nZPos))
      return 0;
    int sid = 0;
    if (x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16)
      sid = blockStates[y * 256 + z * 16 + x];
    if (sid == 0)
      return 32;
    return computeFluidDrop(sid);
  }

  private static int getStateIdAt(int[] blockStates, int x, int y, int z,
      int[] nXNeg, int[] nXPos, int[] nYNeg,
      int[] nYPos, int[] nZNeg, int[] nZPos) {
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

  private static int computeCornerFluidDrop(int[] blockStates, int cx, int y,
      int cz, boolean[] sidIsWater,
      int[] nXNeg, int[] nXPos,
      int[] nYNeg, int[] nYPos,
      int[] nZNeg, int[] nZPos) {
    int totalDrop = 0;
    int count = 0;
    for (int dz = -1; dz <= 0; dz++) {
      for (int dx = -1; dx <= 0; dx++) {
        int bx = cx + dx, bz = cz + dz;
        int sid = getStateIdAt(blockStates, bx, y, bz, nXNeg, nXPos, nYNeg,
            nYPos, nZNeg, nZPos);
        if (sid > 0 && sid < sidIsWater.length && sidIsWater[sid]) {
          int sidAbove = getStateIdAt(blockStates, bx, y + 1, bz, nXNeg, nXPos,
              nYNeg, nYPos, nZNeg, nZPos);
          if (sidAbove > 0 && sidAbove < sidIsWater.length &&
              sidIsWater[sidAbove])
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

  private static boolean shouldCullFace(boolean isWater, int[] blockStates,
      int idx, int coord, int boundary,
      boolean[] sidOpaque,
      boolean[] sidIsWater, int[] nArr,
      int nIdx) {
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

  public int getTotalVertexCount() {
    return vertexCountAtomic.get();
  }

  private final java.util.concurrent.atomic.AtomicInteger meshCountAtomic = new java.util.concurrent.atomic.AtomicInteger(
      0);

  private final java.util.concurrent.atomic.AtomicInteger vertexCountAtomic = new java.util.concurrent.atomic.AtomicInteger(
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

  public void noteSectionAvailable(int chunkX, int chunkY, int chunkZ) {
    long key = packChunkKey(chunkX, chunkY, chunkZ);
    long now = System.nanoTime();
    synchronized (pendingVisibleSectionNanos) {
      if (!pendingVisibleSectionNanos.containsKey(key)) {
        pendingVisibleSectionNanos.put(key, now);
      }
    }
  }

  public void noteBlockUpdate(int chunkX, int chunkY, int chunkZ) {
    long key = packChunkKey(chunkX, chunkY, chunkZ);
    synchronized (pendingBlockUpdateNanos) {
      pendingBlockUpdateNanos.put(key, System.nanoTime());
    }
  }

  private void recordVisibleLatency(long key) {
    long now = System.nanoTime();
    long visibleSectionStart = 0L;
    synchronized (pendingVisibleSectionNanos) {
      if (pendingVisibleSectionNanos.containsKey(key)) {
        visibleSectionStart = pendingVisibleSectionNanos.remove(key);
      }
    }
    if (visibleSectionStart != 0L) {
      visibleSectionLatencyAccNs.addAndGet(now - visibleSectionStart);
      visibleSectionLatencySamples.incrementAndGet();
    }

    long blockUpdateStart = 0L;
    synchronized (pendingBlockUpdateNanos) {
      if (pendingBlockUpdateNanos.containsKey(key)) {
        blockUpdateStart = pendingBlockUpdateNanos.remove(key);
      }
    }
    if (blockUpdateStart != 0L) {
      blockUpdateLatencyAccNs.addAndGet(now - blockUpdateStart);
      blockUpdateLatencySamples.incrementAndGet();
    }
  }

  public double getAverageVisibleSectionLatencyMs() {
    int samples = visibleSectionLatencySamples.get();
    return samples > 0 ? (visibleSectionLatencyAccNs.get() / 1e6) / samples
        : 0.0;
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
    return builderPool != null ? builderPool.getActiveCount() : 0;
  }

  public int getInstantQueueDepth() {
    return builderPool != null ? builderPool.getQueue().size()
        : 0;
  }

  public int getInteractiveActiveCount() {
    return builderPool != null
        ? builderPool.getActiveCount()
        : 0;
  }

  public int getInteractiveQueueDepth() {
    return builderPool != null
        ? builderPool.getQueue().size()
        : 0;
  }

  public int getBuilderThreadCount() {
    return builderPool.getCorePoolSize();
  }

  private static void updateThreadPoolSize(java.util.concurrent.ThreadPoolExecutor pool,
      int target) {
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
    return config != null &&
        (config.enableBurstThreadMode || config.prioritizeFpsOverTps);
  }

  private int getThreadBudgetCap() {
    return isBurstThreadModeEnabled() ? BURST_TOTAL_THREAD_BUDGET
        : NORMAL_TOTAL_THREAD_BUDGET;
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

  public void setLoadingModeThreadBudget(boolean loadingMode,
      int totalPending) {
    int pending = Math.max(getPendingCount(), totalPending);
    MetalRenderConfig config = MetalRenderClient.getConfig();
    boolean fpsPriorityMode = config != null && config.prioritizeFpsOverTps;
    int approximateLightingThreshold = fpsPriorityMode ? 2048 : 256;
    aggressiveApproximateLighting = loadingMode || pending >= approximateLightingThreshold;

    BuildBudgetEstimator estimator = PerformanceController.getBudgetEstimator();
    int ewmaThreads = estimator != null ? estimator.recommendedThreadCount(pending) : -1;
    int ewmaInFlight = estimator != null ? estimator.recommendedInFlight() : -1;
    boolean shouldThrottle = estimator != null && estimator.shouldThrottle();

    MetalLogger.info(
        "THREAD_BUDGET: loading=%s pending=%d fpsPriority=%s approxLighting=%s ewmaThreads=%d ewmaInFlight=%d throttle=%s",
        loadingMode, pending, fpsPriorityMode, aggressiveApproximateLighting,
        ewmaThreads, ewmaInFlight, shouldThrottle);
    if (pending <= 0) {
      updateThreadPoolSize(builderPool, 0);
      MetalLogger.info("THREAD_BUDGET: pools idled");
      return;
    }
    if (fpsPriorityMode && !shouldThrottle) {
      int target = Math.min(getBuilderThreadCap(), ewmaThreads > 0 ? Math.min(getBuilderThreadCap(), ewmaThreads) : getBuilderThreadCap());
      updateThreadPoolSize(builderPool, target);
      return;
    }
    if (fpsPriorityMode && shouldThrottle) {
      int target = Math.max(2, Math.min(getBuilderThreadCap(), ewmaThreads > 0 ? ewmaThreads / 2 : 2));
      updateThreadPoolSize(builderPool, target);
      return;
    }
    int baseTarget = loadingMode ? boostedBuilderThreadCount : steadyBuilderThreadCount;
    if (ewmaThreads > 0) {
      baseTarget = Math.max(2, Math.min(ewmaThreads, getBuilderThreadCap()));
    }
    if (isBurstThreadModeEnabled() && !shouldThrottle) {
      baseTarget += loadingMode ? 2 : 1;
    }
    int maxBacklogBoost = Math.max(2, Runtime.getRuntime().availableProcessors() / 4);
    int backlogBoost = 0;
    if (pending >= 8192) {
      backlogBoost = Math.min(10, maxBacklogBoost + 4);
    } else if (pending >= 4096) {
      backlogBoost = Math.min(8, maxBacklogBoost + 3);
    } else if (pending >= 2048) {
      backlogBoost = Math.min(6, maxBacklogBoost + 2);
    } else if (pending >= 1024) {
      backlogBoost = Math.min(4, maxBacklogBoost + 1);
    } else if (pending >= 512) {
      backlogBoost = Math.min(2, maxBacklogBoost);
    } else if (pending >= 256) {
      backlogBoost = 1;
    }
    int budgetCap = Math.min(getBuilderThreadCap(), getThreadBudgetCap());
    if (shouldThrottle) {
      budgetCap = Math.max(2, budgetCap / 2);
      backlogBoost = 0;
    }
    int target = Math.min(budgetCap, baseTarget + backlogBoost);
    updateThreadPoolSize(builderPool, target);
    MetalLogger.info(
        "THREAD_BUDGET: builder=%d cap=%d backlogBoost=%d ewma=%d",
        target, budgetCap, backlogBoost, ewmaThreads);
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
      vertexCountAtomic.set(0);
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



  public void markDirty(int cx, int cy, int cz) {
    long key = packChunkKey(cx, cy, cz);

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
    int[] blockSnapshot = blockStates != null
        ? java.util.Arrays.copyOf(blockStates, blockStates.length)
        : null;
    byte[] lightSnapshot = lightData != null
        ? java.util.Arrays.copyOf(lightData, lightData.length)
        : null;
    MeshBuildContext context = captureBuildContext(null, chunkX, chunkY, chunkZ);
    synchronized (pendingKeys) {
      pendingKeys.add(key);
    }
    MetalLogger.debug("ASYNC_QUEUE: chunk=[%d,%d,%d] pending=%d",
        chunkX, chunkY, chunkZ, getPendingCount());
    submitMeshTask(2, () -> {
      try {
        if (isTaskCancelled(key, genAtSubmit)) {
          return;
        }
        doMeshBuild(chunkX, chunkY, chunkZ, blockSnapshot, lightSnapshot, key,
            genAtSubmit, null, null, null, null, null, null, null, null,
            null, null, null, null, context);
      } catch (Exception e) {
        MetalLogger.error("Meshing error for chunk [%d,%d,%d]: %s", chunkX,
            chunkY, chunkZ, e.getMessage());
      } finally {
        synchronized (pendingKeys) {
          pendingKeys.remove(key);
        }
      }
    });
  }



  private static volatile boolean applyFaceShade = true;
  private static volatile int leafDebugFrames = 0;
  private static int meshBuildCount = 0;

  private static volatile int meshBuildDiagCount = 0;
  private static volatile long meshBuildTimeAcc = 0;
  private static volatile int meshBuildTimeSamples = 0;
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
    boolean[] computed, skip, opaque, isWater, isFluid, isTopOnly, isEmissive,
        isTranslucent;
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

  private static byte getFaceLight(byte[] lightData, byte[] nXNegLight,
      byte[] nXPosLight, byte[] nYNegLight,
      byte[] nYPosLight, byte[] nZNegLight,
      byte[] nZPosLight, int x, int y, int z,
      int face) {
    int centerLight = getLightAtExt(x, y, z, lightData, nXNegLight, nXPosLight, nYNegLight,
        nYPosLight, nZNegLight, nZPosLight);
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
      int[] blockStates, byte[] lightData, long key,
      long generation, int[] nXNeg, int[] nXPos, int[] nYNeg,
      int[] nYPos, int[] nZNeg, int[] nZPos,
      byte[] nXNegLight, byte[] nXPosLight,
      byte[] nYNegLight, byte[] nYPosLight,
      byte[] nZNegLight, byte[] nZPosLight, MeshBuildContext context) {
    long buildStart = System.nanoTime();
    try {
      if (isTaskCancelled(key, generation)) {
        return;
      }
      meshBuildCount++;
      ByteBuffer vertexBuffer = VERTEX_BUF_POOL.get();
      ByteBuffer waterBuffer = WATER_BUF_POOL.get();
      vertexBuffer.clear();
      waterBuffer.clear();
      int opaqueQuadCount = 0;
      int waterQuadCount = 0;
      int bakedQuadBlocks = 0;
      int fallbackBlocks = 0;
      BlockStateModelSet blockModels = context != null ? context.blockModels : null;
      int[] snapshotBiomeColors = context != null ? context.biomeColors : null;
      if (snapshotBiomeColors == null || snapshotBiomeColors.length < 4) {
        snapshotBiomeColors = new int[] { DEFAULT_BLOCK_COLOR, DEFAULT_BLOCK_COLOR,
            DEFAULT_BLOCK_COLOR, DEFAULT_BLOCK_COLOR };
      }
      MetalRenderConfig leafCfg = MetalRenderClient.getConfig();
      int leafMode = (leafCfg != null) ? leafCfg.leafCullingMode : 0;
      applyFaceShade = false;


      boolean useSmoothAO = false;

      boolean[] skipFace = null;
      int buildPCX = context != null ? context.buildPlayerCX : 0;
      int buildPCY = context != null ? context.buildPlayerCY : 0;
      int buildPCZ = context != null ? context.buildPlayerCZ : 0;
      {
        int[] fastBiomeColors = snapshotBiomeColors;
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
              if (blkSimpleName.contains("Stained") ||
                  blkSimpleName.contains("Tinted") || blk == Blocks.ICE ||
                  blk == Blocks.SLIME_BLOCK || blk == Blocks.HONEY_BLOCK) {
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
            CachedUVData cachedUV = (uvCacheKey >= 0 && uvCacheKey < UV_CACHE.length)
                ? UV_CACHE[uvCacheKey]
                : null;
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
                        boolean qTint = q.materialInfo().isTinted() ||
                            q.materialInfo().tintIndex() >= 0 ||
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
              if (anySpriteCached && uvCacheKey >= 0 &&
                  uvCacheKey < UV_CACHE.length)
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
        int[] colGrassTint = null, colWaterTint = null;
        for (int y = 0; y < SECTION_SIZE; y++) {
          if (isTaskCancelled(key, generation)) {
            return;
          }
          for (int z = 0; z < SECTION_SIZE; z++) {
            for (int x = 0; x < SECTION_SIZE; x++) {
              int idx = y * 256 + z * 16 + x;
              int sid = blockStates != null ? blockStates[idx] : 0;
              if (sid == 0 || sidSkip[sid])
                continue;
              byte r = sidR[sid], g = sidG[sid], b = sidB[sid],
                  a = sidAlpha[sid];

              if (colGrassTint != null && sid < sidTintTypeFast.length) {
                byte ft = sidTintTypeFast[sid];
                if (ft == TINT_GRASS) {
                  int c = colGrassTint[z * 16 + x];
                  r = (byte) ((c >> 16) & 0xFF);
                  g = (byte) ((c >> 8) & 0xFF);
                  b = (byte) (c & 0xFF);
                } else if (ft == TINT_WATER) {
                  int c = colWaterTint[z * 16 + x];
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

                drop00 = computeCornerFluidDrop(blockStates, x, y, z,
                    sidIsFluid, nXNeg, nXPos, nYNeg,
                    nYPos, nZNeg, nZPos);
                drop01 = computeCornerFluidDrop(blockStates, x, y, z + 1,
                    sidIsFluid, nXNeg, nXPos, nYNeg,
                    nYPos, nZNeg, nZPos);
                drop11 = computeCornerFluidDrop(blockStates, x + 1, y, z + 1,
                    sidIsFluid, nXNeg, nXPos, nYNeg,
                    nYPos, nZNeg, nZPos);
                drop10 = computeCornerFluidDrop(blockStates, x + 1, y, z,
                    sidIsFluid, nXNeg, nXPos, nYNeg,
                    nYPos, nZNeg, nZPos);
                topDrop = (drop00 + drop01 + drop11 + drop10) / 4;
              } else if (sidIsTopOnly[sid]) {
                topDrop = sidTopDrop[sid];
              } else {
                topDrop = 0;
              }
              boolean[] sf = isFluidBlock ? null : skipFace;
              ByteBuffer targetBuf = usesTranslucentBuf ? waterBuffer : vertexBuffer;
              if ((sf == null || !sf[1]) &&
                  !shouldCullFace(isWater, blockStates, idx + 256, y, 15,
                      sidOpaque, sidIsWater, nYPos, z * 16 + x)) {
                int uv = sidBase + 1;
                byte fr = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] &&
                    !forceDebugTint)
                        ? (byte) 0xFF
                        : r;
                byte fg = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] &&
                    !forceDebugTint)
                        ? (byte) 0xFF
                        : g;
                byte fb = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] &&
                    !forceDebugTint)
                        ? (byte) 0xFF
                        : b;
                if (usesTranslucentBuf)
                  waterQuadCount++;
                else
                  opaqueQuadCount++;
                if (useSmoothAO) {
                  computeSmoothLighting(x, y, z, 1, blockStates, sidOpaque,
                      lightData, nXNeg, nXPos, nYNeg, nYPos,
                      nZNeg, nZPos, nXNegLight, nXPosLight,
                      nYNegLight, nYPosLight, nZNegLight,
                      nZPosLight, faceAO, faceLight);
                  if (isFluidBlock)
                    emitWaterFaceAO(targetBuf, x, y, z, 1, fr, fg, fb, a,
                        sidFaceHasSprite[uv], sidFaceUMin[uv],
                        sidFaceUMax[uv], sidFaceVMin[uv],
                        sidFaceVMax[uv], drop00, drop01, drop11,
                        drop10, faceAO, faceLight);
                  else
                    emitFaceAO(targetBuf, x, y, z, 1, fr, fg, fb, a,
                        sidFaceHasSprite[uv], sidFaceUMin[uv],
                        sidFaceUMax[uv], sidFaceVMin[uv],
                        sidFaceVMax[uv], topDrop, faceAO, faceLight);
                } else {
                  if (isFluidBlock)
                    emitWaterFace(
                        targetBuf, x, y, z, 1,
                        getFaceLight(lightData, nXNegLight, nXPosLight,
                            nYNegLight, nYPosLight, nZNegLight,
                            nZPosLight, x, y, z, 1),
                        fr, fg, fb, a, sidFaceHasSprite[uv], sidFaceUMin[uv],
                        sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv],
                        drop00, drop01, drop11, drop10);
                  else
                    emitFaceInlineWater(
                        targetBuf, x, y, z, 1,
                        getFaceLight(lightData, nXNegLight, nXPosLight,
                            nYNegLight, nYPosLight, nZNegLight,
                            nZPosLight, x, y, z, 1),
                        fr, fg, fb, a, sidFaceHasSprite[uv], sidFaceUMin[uv],
                        sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv],
                        topDrop);
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
                  !shouldCullFace(isWater, blockStates, idx - 256, y, 0,
                      sidOpaque, sidIsWater, nYNeg, z * 16 + x)) {
                int uv = sidBase + 0;
                byte fr = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] &&
                    !forceDebugTint)
                        ? (byte) 0xFF
                        : r;
                byte fg = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] &&
                    !forceDebugTint)
                        ? (byte) 0xFF
                        : g;
                byte fb = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] &&
                    !forceDebugTint)
                        ? (byte) 0xFF
                        : b;
                if (usesTranslucentBuf)
                  waterQuadCount++;
                else
                  opaqueQuadCount++;
                if (useSmoothAO) {
                  computeSmoothLighting(x, y, z, 0, blockStates, sidOpaque,
                      lightData, nXNeg, nXPos, nYNeg, nYPos,
                      nZNeg, nZPos, nXNegLight, nXPosLight,
                      nYNegLight, nYPosLight, nZNegLight,
                      nZPosLight, faceAO, faceLight);
                  emitFaceAO(targetBuf, x, y, z, 0, fr, fg, fb, a,
                      sidFaceHasSprite[uv], sidFaceUMin[uv],
                      sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv],
                      0, faceAO, faceLight);
                } else {
                  emitFaceInlineWater(
                      targetBuf, x, y, z, 0,
                      getFaceLight(lightData, nXNegLight, nXPosLight,
                          nYNegLight, nYPosLight, nZNegLight,
                          nZPosLight, x, y, z, 0),
                      fr, fg, fb, a, sidFaceHasSprite[uv], sidFaceUMin[uv],
                      sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv], 0);
                }
                if (doubleSided) {
                  emitReversedQuad(targetBuf);
                  opaqueQuadCount++;
                }
              }
              if ((sf == null || !sf[3]) &&
                  !shouldCullFace(isWater, blockStates, idx + 16, z, 15,
                      sidOpaque, sidIsWater, nZPos, y * 16 + x)) {
                int uv = sidBase + 3;
                byte fr = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] &&
                    !forceDebugTint)
                        ? (byte) 0xFF
                        : r;
                byte fg = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] &&
                    !forceDebugTint)
                        ? (byte) 0xFF
                        : g;
                byte fb = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] &&
                    !forceDebugTint)
                        ? (byte) 0xFF
                        : b;
                if (usesTranslucentBuf)
                  waterQuadCount++;
                else
                  opaqueQuadCount++;
                if (useSmoothAO) {
                  computeSmoothLighting(x, y, z, 3, blockStates, sidOpaque,
                      lightData, nXNeg, nXPos, nYNeg, nYPos,
                      nZNeg, nZPos, nXNegLight, nXPosLight,
                      nYNegLight, nYPosLight, nZNegLight,
                      nZPosLight, faceAO, faceLight);
                  if (isFluidBlock)
                    emitWaterFaceAO(targetBuf, x, y, z, 3, fr, fg, fb, a,
                        sidFaceHasSprite[uv], sidFaceUMin[uv],
                        sidFaceUMax[uv], sidFaceVMin[uv],
                        sidFaceVMax[uv], drop00, drop01, drop11,
                        drop10, faceAO, faceLight);
                  else
                    emitFaceAO(targetBuf, x, y, z, 3, fr, fg, fb, a,
                        sidFaceHasSprite[uv], sidFaceUMin[uv],
                        sidFaceUMax[uv], sidFaceVMin[uv],
                        sidFaceVMax[uv], topDrop, faceAO, faceLight);
                } else {
                  if (isFluidBlock)
                    emitWaterFace(
                        targetBuf, x, y, z, 3,
                        getFaceLight(lightData, nXNegLight, nXPosLight,
                            nYNegLight, nYPosLight, nZNegLight,
                            nZPosLight, x, y, z, 3),
                        fr, fg, fb, a, sidFaceHasSprite[uv], sidFaceUMin[uv],
                        sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv],
                        drop00, drop01, drop11, drop10);
                  else
                    emitFaceInlineWater(
                        targetBuf, x, y, z, 3,
                        getFaceLight(lightData, nXNegLight, nXPosLight,
                            nYNegLight, nYPosLight, nZNegLight,
                            nZPosLight, x, y, z, 3),
                        fr, fg, fb, a, sidFaceHasSprite[uv], sidFaceUMin[uv],
                        sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv],
                        topDrop);
                }
                if (doubleSided) {
                  emitReversedQuad(targetBuf);
                  opaqueQuadCount++;
                }
              }
              if ((sf == null || !sf[2]) &&
                  !shouldCullFace(isWater, blockStates, idx - 16, z, 0,
                      sidOpaque, sidIsWater, nZNeg, y * 16 + x)) {
                int uv = sidBase + 2;
                byte fr = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] &&
                    !forceDebugTint)
                        ? (byte) 0xFF
                        : r;
                byte fg = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] &&
                    !forceDebugTint)
                        ? (byte) 0xFF
                        : g;
                byte fb = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] &&
                    !forceDebugTint)
                        ? (byte) 0xFF
                        : b;
                if (usesTranslucentBuf)
                  waterQuadCount++;
                else
                  opaqueQuadCount++;
                if (useSmoothAO) {
                  computeSmoothLighting(x, y, z, 2, blockStates, sidOpaque,
                      lightData, nXNeg, nXPos, nYNeg, nYPos,
                      nZNeg, nZPos, nXNegLight, nXPosLight,
                      nYNegLight, nYPosLight, nZNegLight,
                      nZPosLight, faceAO, faceLight);
                  if (isFluidBlock)
                    emitWaterFaceAO(targetBuf, x, y, z, 2, fr, fg, fb, a,
                        sidFaceHasSprite[uv], sidFaceUMin[uv],
                        sidFaceUMax[uv], sidFaceVMin[uv],
                        sidFaceVMax[uv], drop00, drop01, drop11,
                        drop10, faceAO, faceLight);
                  else
                    emitFaceAO(targetBuf, x, y, z, 2, fr, fg, fb, a,
                        sidFaceHasSprite[uv], sidFaceUMin[uv],
                        sidFaceUMax[uv], sidFaceVMin[uv],
                        sidFaceVMax[uv], topDrop, faceAO, faceLight);
                } else {
                  if (isFluidBlock)
                    emitWaterFace(
                        targetBuf, x, y, z, 2,
                        getFaceLight(lightData, nXNegLight, nXPosLight,
                            nYNegLight, nYPosLight, nZNegLight,
                            nZPosLight, x, y, z, 2),
                        fr, fg, fb, a, sidFaceHasSprite[uv], sidFaceUMin[uv],
                        sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv],
                        drop00, drop01, drop11, drop10);
                  else
                    emitFaceInlineWater(
                        targetBuf, x, y, z, 2,
                        getFaceLight(lightData, nXNegLight, nXPosLight,
                            nYNegLight, nYPosLight, nZNegLight,
                            nZPosLight, x, y, z, 2),
                        fr, fg, fb, a, sidFaceHasSprite[uv], sidFaceUMin[uv],
                        sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv],
                        topDrop);
                }
                if (doubleSided) {
                  emitReversedQuad(targetBuf);
                  opaqueQuadCount++;
                }
              }
              if ((sf == null || !sf[5]) &&
                  !shouldCullFace(isWater, blockStates, idx + 1, x, 15,
                      sidOpaque, sidIsWater, nXPos, y * 16 + z)) {
                int uv = sidBase + 5;
                byte fr = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] &&
                    !forceDebugTint)
                        ? (byte) 0xFF
                        : r;
                byte fg = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] &&
                    !forceDebugTint)
                        ? (byte) 0xFF
                        : g;
                byte fb = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] &&
                    !forceDebugTint)
                        ? (byte) 0xFF
                        : b;
                if (usesTranslucentBuf)
                  waterQuadCount++;
                else
                  opaqueQuadCount++;
                if (useSmoothAO) {
                  computeSmoothLighting(x, y, z, 5, blockStates, sidOpaque,
                      lightData, nXNeg, nXPos, nYNeg, nYPos,
                      nZNeg, nZPos, nXNegLight, nXPosLight,
                      nYNegLight, nYPosLight, nZNegLight,
                      nZPosLight, faceAO, faceLight);
                  if (isFluidBlock)
                    emitWaterFaceAO(targetBuf, x, y, z, 5, fr, fg, fb, a,
                        sidFaceHasSprite[uv], sidFaceUMin[uv],
                        sidFaceUMax[uv], sidFaceVMin[uv],
                        sidFaceVMax[uv], drop00, drop01, drop11,
                        drop10, faceAO, faceLight);
                  else
                    emitFaceAO(targetBuf, x, y, z, 5, fr, fg, fb, a,
                        sidFaceHasSprite[uv], sidFaceUMin[uv],
                        sidFaceUMax[uv], sidFaceVMin[uv],
                        sidFaceVMax[uv], topDrop, faceAO, faceLight);
                } else {
                  if (isFluidBlock)
                    emitWaterFace(
                        targetBuf, x, y, z, 5,
                        getFaceLight(lightData, nXNegLight, nXPosLight,
                            nYNegLight, nYPosLight, nZNegLight,
                            nZPosLight, x, y, z, 5),
                        fr, fg, fb, a, sidFaceHasSprite[uv], sidFaceUMin[uv],
                        sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv],
                        drop00, drop01, drop11, drop10);
                  else
                    emitFaceInlineWater(
                        targetBuf, x, y, z, 5,
                        getFaceLight(lightData, nXNegLight, nXPosLight,
                            nYNegLight, nYPosLight, nZNegLight,
                            nZPosLight, x, y, z, 5),
                        fr, fg, fb, a, sidFaceHasSprite[uv], sidFaceUMin[uv],
                        sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv],
                        topDrop);
                }
                if (doubleSided) {
                  emitReversedQuad(targetBuf);
                  opaqueQuadCount++;
                }
              }
              if ((sf == null || !sf[4]) &&
                  !shouldCullFace(isWater, blockStates, idx - 1, x, 0,
                      sidOpaque, sidIsWater, nXNeg, y * 16 + z)) {
                int uv = sidBase + 4;
                byte fr = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] &&
                    !forceDebugTint)
                        ? (byte) 0xFF
                        : r;
                byte fg = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] &&
                    !forceDebugTint)
                        ? (byte) 0xFF
                        : g;
                byte fb = (sidFaceHasSprite[uv] && !sidFaceHasTint[uv] &&
                    !forceDebugTint)
                        ? (byte) 0xFF
                        : b;
                if (usesTranslucentBuf)
                  waterQuadCount++;
                else
                  opaqueQuadCount++;
                if (useSmoothAO) {
                  computeSmoothLighting(x, y, z, 4, blockStates, sidOpaque,
                      lightData, nXNeg, nXPos, nYNeg, nYPos,
                      nZNeg, nZPos, nXNegLight, nXPosLight,
                      nYNegLight, nYPosLight, nZNegLight,
                      nZPosLight, faceAO, faceLight);
                  if (isFluidBlock)
                    emitWaterFaceAO(targetBuf, x, y, z, 4, fr, fg, fb, a,
                        sidFaceHasSprite[uv], sidFaceUMin[uv],
                        sidFaceUMax[uv], sidFaceVMin[uv],
                        sidFaceVMax[uv], drop00, drop01, drop11,
                        drop10, faceAO, faceLight);
                  else
                    emitFaceAO(targetBuf, x, y, z, 4, fr, fg, fb, a,
                        sidFaceHasSprite[uv], sidFaceUMin[uv],
                        sidFaceUMax[uv], sidFaceVMin[uv],
                        sidFaceVMax[uv], topDrop, faceAO, faceLight);
                } else {
                  if (isFluidBlock)
                    emitWaterFace(
                        targetBuf, x, y, z, 4,
                        getFaceLight(lightData, nXNegLight, nXPosLight,
                            nYNegLight, nYPosLight, nZNegLight,
                            nZPosLight, x, y, z, 4),
                        fr, fg, fb, a, sidFaceHasSprite[uv], sidFaceUMin[uv],
                        sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv],
                        drop00, drop01, drop11, drop10);
                  else
                    emitFaceInlineWater(
                        targetBuf, x, y, z, 4,
                        getFaceLight(lightData, nXNegLight, nXPosLight,
                            nYNegLight, nYPosLight, nZNegLight,
                            nZPosLight, x, y, z, 4),
                        fr, fg, fb, a, sidFaceHasSprite[uv], sidFaceUMin[uv],
                        sidFaceUMax[uv], sidFaceVMin[uv], sidFaceVMax[uv],
                        topDrop);
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
      }
      int quadCount = opaqueQuadCount + waterQuadCount;
      if (waterQuadCount > 0) {
        waterBuffer.flip();
        vertexBuffer.put(waterBuffer);
      }
      if (meshBuildCount <= 3) {
        MetalLogger.info("[BUILD_V9 DIAG] chunk[%d,%d,%d] quads=%d opaque=%d "
            + "water=%d baked=%d fallback=%d",
            chunkX, chunkY, chunkZ, quadCount, opaqueQuadCount,
            waterQuadCount, bakedQuadBlocks, fallbackBlocks);
      }
      if (quadCount == 0) {
        ChunkMeshData old;
        synchronized (meshCache) {
          old = meshCache.remove(key);
        }
        if (old != null) {
          NativeBridge.nUnregisterChunkMesh(chunkX, chunkY, chunkZ);
          NativeBridge.nDestroyBuffer(old.bufferHandle);
          meshCountAtomic.decrementAndGet();
          vertexCountAtomic.addAndGet(-old.quadCount * 4);
        }

        synchronized (emptyKeys) {
          emptyKeys.add(key);
        }
        synchronized (dirtyKeys) {
          dirtyKeys.remove(key);
        }

        meshUpdateGeneration.incrementAndGet();
        recordVisibleLatency(key);
        return;
      }
      vertexBuffer.flip();
      int[] facingQuadCounts = bucketQuadsByFacing(vertexBuffer, opaqueQuadCount,
          waterQuadCount);
      long visibilityMask = computeVisibilityMask(blockStates);
      int dataLen = quadCount * 4 * VERTEX_STRIDE;
      if (isTaskCancelled(key, generation)) {
        return;
      }

      long bufferHandle;
      UPLOAD_SEMAPHORE.acquireUninterruptibly();
      try {
        bufferHandle = NativeBridge.nCreateBuffer(
            deviceHandle, dataLen, NativeMemory.STORAGE_MODE_SHARED);
        long uploadStart = System.nanoTime();
        NativeBridge.nUploadBufferDataDirect(bufferHandle, vertexBuffer, 0,
            dataLen);
        MetalRenderProfiler.getInstance().recordUploadTime(System.nanoTime() - uploadStart);
        MetalRenderProfiler.getInstance().incrementUploadsDone(1);
      } finally {
        UPLOAD_SEMAPHORE.release();
      }
      ChunkMeshData mesh = new ChunkMeshData(bufferHandle, quadCount, chunkX, chunkY, chunkZ,
          buildPCX, buildPCY, buildPCZ, visibilityMask,
          facingQuadCounts);
      ChunkMeshData old;
      synchronized (meshCache) {
        old = meshCache.put(key, mesh);
      }
      if (old == null) {
        meshCountAtomic.incrementAndGet();
        vertexCountAtomic.addAndGet(mesh.quadCount * 4);
      } else {
        vertexCountAtomic.addAndGet(mesh.quadCount * 4 - old.quadCount * 4);
      }
      NativeBridge.nRegisterChunkMesh(chunkX, chunkY, chunkZ, bufferHandle,
          quadCount, opaqueQuadCount, visibilityMask,
          facingQuadCounts);
      if (old != null) {
        NativeBridge.nDestroyBuffer(old.bufferHandle);
      }

      meshUpdateGeneration.incrementAndGet();
      recordVisibleLatency(key);
      MetalRenderProfiler.getInstance().incrementMeshesBuilt(1);
      synchronized (dirtyKeys) {
        dirtyKeys.remove(key);
      }
    } catch (Exception e) {
      MetalLogger.error("Meshing error for chunk [%d,%d,%d]", chunkX, chunkY,
          chunkZ);
    } finally {
      long buildElapsed = System.nanoTime() - buildStart;
      MetalRenderProfiler.getInstance().recordMeshingTime(buildElapsed);
      meshBuildTimeAcc += buildElapsed;
      int samples = ++meshBuildTimeSamples;
      if (MetalRenderConfig.isDeepDebugActive() && samples % 500 == 0) {
        double avgMs = (meshBuildTimeAcc / 1e6) / samples;
        MetalLogger.info(
            "MESH_PERF: avg=%.2fms over %d builds | pipeline=%.2fms (%d)",
            avgMs, samples,
            pipelineCount > 0 ? (pipelineTimeAcc / 1e6) / pipelineCount : 0.0,
            pipelineCount);
        MetalLogger.info(
            "WATER_DBG: boundaryCullHit=%d boundaryWaterMiss=%d "
                + "boundaryNullArr=%d | waterBaked=%d waterFallback=%d "
                + "waterBakedFaceCull=%d nonFullSkip=%d",
            dbgBoundaryCullHit, dbgBoundaryWaterMiss, dbgBoundaryNullArr,
            dbgWaterBaked, dbgWaterFallback, dbgWaterBakedCull, dbgNonFullSkip);
      }
      synchronized (pendingKeys) {
        pendingKeys.remove(key);
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
    return readNeighborFaceStatic(resolveSection(world, nCx, nCy, nCz), faceDir, out);
  }

  private boolean readNeighborFace(LevelChunkSection section, int faceDir,
      int[] out) {
    return readNeighborFaceStatic(section, faceDir, out);
  }

  private static boolean readNeighborFaceStatic(LevelChunkSection section,
      int faceDir, int[] out) {
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

  private boolean readNeighborLightFace(ClientLevel world, int nCx, int nCy,
      int nCz, int faceDir, byte[] out,
      BlockPos.MutableBlockPos mutablePos) {
    return readNeighborLightFaceStatic(world, nCx * 16, nCy * 16, nCz * 16,
        resolveSection(world, nCx, nCy, nCz), faceDir,
        out, mutablePos);
  }

  private boolean readNeighborLightFace(ClientLevel world, int baseX, int baseY,
      int baseZ, LevelChunkSection section,
      int faceDir, byte[] out,
      BlockPos.MutableBlockPos mutablePos) {
    return readNeighborLightFaceStatic(world, baseX, baseY, baseZ, section,
        faceDir, out, mutablePos);
  }

  private static boolean readNeighborLightFaceStatic(ClientLevel world, int baseX,
      int baseY, int baseZ, LevelChunkSection section, int faceDir, byte[] out,
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
          e != null ? e.getClass().getSimpleName() + ": " +
              e.getMessage()
              : "unknown");
    }
  }

  private void fillApproximateLightData(ClientLevel world, int chunkX,
      int chunkY, int chunkZ,
      int[] blockStates, byte[] lightData,
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
                  maxBlockLight = Math.max(
                      maxBlockLight,
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
    buildMeshFromWorld(chunkX, chunkY, chunkZ, false);
  }

  public void buildMeshFromWorld(int chunkX, int chunkY, int chunkZ,
      boolean highPriority) {
    buildMeshFromWorld(chunkX, chunkY, chunkZ, highPriority, false);
  }

  public void buildMeshFromWorldInteractive(int chunkX, int chunkY,
      int chunkZ) {
    buildMeshFromWorld(chunkX, chunkY, chunkZ, true, true);
  }

  public void buildMeshFromWorld(int chunkX, int chunkY, int chunkZ,
      boolean highPriority,
      boolean interactivePriority) {
    if (!initialized)
      return;
    final boolean useApproximateLight = !interactivePriority && (highPriority || aggressiveApproximateLighting);
    long key = packChunkKey(chunkX, chunkY, chunkZ);
    boolean wasDirty;
    synchronized (dirtyKeys) {
      wasDirty = dirtyKeys.contains(key);
    }
    synchronized (pendingKeys) {
      if (!pendingKeys.add(key)) {
        if (MetalRenderConfig.isDeepDebugActive()) {
          MetalLogger.debug(
              "BUILD_SKIP_DUP: chunk=[%d,%d,%d] high=%s interactive=%s",
              chunkX, chunkY, chunkZ, highPriority,
              interactivePriority);
        }
        return;
      }
    }
    final long genAtSubmit;
    synchronized (dirtyGeneration) {
      genAtSubmit = dirtyGeneration.get(key);
    }
    Runnable buildTask = () -> {
      if (isTaskCancelled(key, genAtSubmit)) {
        synchronized (pendingKeys) {
          pendingKeys.remove(key);
        }
        return;
      }
      try {
        long pipelineStart = System.nanoTime();
        Minecraft mc = Minecraft.getInstance();
        SectionSnapshot snapshot = captureSectionSnapshot(
            mc != null ? mc.level : null, chunkX, chunkY, chunkZ,
            useApproximateLight);
        if (!snapshot.valid) {
          synchronized (pendingKeys) {
            pendingKeys.remove(key);
          }
          MetalLogger.debug(
              "BUILD_SKIP_INVALID: chunk=[%d,%d,%d] high=%s interactive=%s",
              chunkX, chunkY, chunkZ, highPriority,
              interactivePriority);
          return;
        }
        if (snapshot.empty) {
          removeEmptyMesh(key, chunkX, chunkY, chunkZ);
          synchronized (pendingKeys) {
            pendingKeys.remove(key);
          }
          MetalLogger.debug(
              "BUILD_SKIP_EMPTY: chunk=[%d,%d,%d] high=%s interactive=%s",
              chunkX, chunkY, chunkZ, highPriority,
              interactivePriority);
          return;
        }
        doMeshBuild(chunkX, chunkY, chunkZ, snapshot.blockStates,
            snapshot.lightData, key, genAtSubmit,
            snapshot.nXNeg, snapshot.nXPos, snapshot.nYNeg, snapshot.nYPos,
            snapshot.nZNeg, snapshot.nZPos, snapshot.nXNegLight,
            snapshot.nXPosLight, snapshot.nYNegLight, snapshot.nYPosLight,
            snapshot.nZNegLight, snapshot.nZPosLight, snapshot.context);
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
    int priority = interactivePriority ? 0 : (highPriority || wasDirty ? 1 : 2);
    MetalLogger.debug(
        "BUILD_SUBMIT: chunk=[%d,%d,%d] priority=%d high=%s interactive=%s dirty=%s",
        chunkX, chunkY, chunkZ, priority, highPriority,
        interactivePriority, wasDirty);
    submitMeshTask(priority, buildTask);
  }

  private int emitBakedQuad(ByteBuffer buf, BakedQuad quad, int blockX,
      int blockY, int blockZ, byte packedLight,
      byte tintR, byte tintG, byte tintB,
      boolean forceOpaque, boolean forceTint,
      byte vertAlpha) {
    Direction face = quad.direction();
    byte normalIndex = dirToNormalIndex(face);
    float shade = quad.materialInfo().shade() ? getFaceShade(normalIndex) : 1.0f;
    byte baseR, baseG, baseB;
    if (forceTint || quad.materialInfo().isTinted() ||
        quad.materialInfo().tintIndex() >= 0) {
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
      long w0 = (spx & 0xFFFFL) | ((spy & 0xFFFFL) << 16) |
          ((spz & 0xFFFFL) << 32) | ((su & 0xFFFFL) << 48);
      long w1 = (sv & 0xFFFFL) | ((sr & 0xFFL) << 16) | ((sg & 0xFFL) << 24) |
          ((sb & 0xFFL) << 32) |
          ((forceOpaque ? 254L : (vertAlpha & 0xFFL)) << 40) |
          ((light & 0xFFL) << 48) | ((normalIndex & 0xFFL) << 56);
      buf.putLong(w0);
      buf.putLong(w1);
    }
    return 1;
  }

  private int emitBakedQuadAO(ByteBuffer buf, BakedQuad quad, int blockX,
      int blockY, int blockZ, byte tintR, byte tintG,
      byte tintB, boolean forceOpaque,
      boolean forceTint, byte vertAlpha, float[] ao,
      byte[] vLight) {
    Direction face = quad.direction();
    int faceIdx = face != null ? face.ordinal() : 1;
    byte normalIndex = dirToNormalIndex(face);
    int baseR, baseG, baseB;
    if (forceTint || quad.materialInfo().isTinted() ||
        quad.materialInfo().tintIndex() >= 0) {
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
      long w0 = (spx & 0xFFFFL) | ((spy & 0xFFFFL) << 16) |
          ((spz & 0xFFFFL) << 32) | ((su & 0xFFFFL) << 48);
      long w1 = (sv & 0xFFFFL) | ((ar & 0xFFL) << 16) | ((ag & 0xFFL) << 24) |
          ((ab & 0xFFL) << 32) | ((alpha & 0xFFL) << 40) |
          ((vertLight & 0xFFL) << 48) | ((normalIndex & 0xFFL) << 56);
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
      TextureAtlasSprite sprite, byte packedLight, byte r,
      byte g, byte b, byte alpha) {
    return emitFaceScaled(buf, x, y, z, normalIndex, sprite, packedLight, r, g,
        b, alpha, 1, 0);
  }

  private int emitFaceWater(ByteBuffer buf, int x, int y, int z,
      int normalIndex, TextureAtlasSprite sprite,
      byte packedLight, byte r, byte g, byte b,
      byte alpha, int topDrop) {
    return emitFaceScaled(buf, x, y, z, normalIndex, sprite, packedLight, r, g,
        b, alpha, 1, topDrop);
  }

  private static int emitFaceWaterSmooth(ByteBuffer buf, int x, int y, int z,
      int normalIndex,
      TextureAtlasSprite sprite,
      byte packedLight, byte r, byte g,
      byte b, byte alpha, int drop00,
      int drop01, int drop11, int drop10) {
    short uMin = 0, uMax = (short) 65535, vMin = 0, vMax = (short) 65535;
    if (sprite != null) {
      uMin = (short) (sprite.getU0() * 65535.0f);
      uMax = (short) (sprite.getU1() * 65535.0f);
      vMin = (short) (sprite.getV0() * 65535.0f);
      vMax = (short) (sprite.getV1() * 65535.0f);
    }
    emitWaterFace(buf, x, y, z, normalIndex, packedLight, r, g, b, alpha, true,
        uMin, uMax, vMin, vMax, drop00, drop01, drop11, drop10);
    return 1;
  }

  private static final float[] FACE_SHADE = { 0.5f, 1.0f, 0.8f,
      0.8f, 0.6f, 0.6f };

  private static void emitFaceInline(ByteBuffer buf, int x, int y, int z,
      int normalIdx, byte light, byte r, byte g,
      byte b, byte a, boolean hasSpr, short uMin,
      short uMax, short vMin, short vMax) {
    emitFaceInlineWater(buf, x, y, z, normalIdx, light, r, g, b, a, hasSpr,
        uMin, uMax, vMin, vMax, 0);
  }

  private static void emitFaceInlineWater(ByteBuffer buf, int x, int y, int z,
      int normalIdx, byte light, byte r,
      byte g, byte b, byte a,
      boolean hasSpr, short uMin,
      short uMax, short vMin, short vMax,
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
    short ex = (short) ((x + 1) * 256), ey = (short) ((y + 1) * 256 - topDrop),
        ez = (short) ((z + 1) * 256);
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
      int normalIdx, byte light, byte r, byte g,
      byte b, byte a, boolean hasSpr, short uMin,
      short uMax, short vMin, short vMax,
      int drop00, int drop01, int drop11,
      int drop10) {
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
      int normalIdx, byte r, byte g, byte b,
      byte a, boolean hasSpr, short uMin,
      short uMax, short vMin, short vMax,
      int drop00, int drop01, int drop11,
      int drop10, float[] ao, byte[] vLight) {
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
    byte r0 = (byte) (int) (ri * ao[0]), g0 = (byte) (int) (gi * ao[0]),
        b0 = (byte) (int) (bi * ao[0]);
    byte r1 = (byte) (int) (ri * ao[1]), g1 = (byte) (int) (gi * ao[1]),
        b1 = (byte) (int) (bi * ao[1]);
    byte r2 = (byte) (int) (ri * ao[2]), g2 = (byte) (int) (gi * ao[2]),
        b2 = (byte) (int) (bi * ao[2]);
    byte r3 = (byte) (int) (ri * ao[3]), g3 = (byte) (int) (gi * ao[3]),
        b3 = (byte) (int) (bi * ao[3]);

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
        emitVertex(buf, sx, eyL, ez, uMin, vTop01, r0, g0, b0, a, vLight[0],
            nIdx);
        emitVertex(buf, sx, sy, ez, uMin, vMax, r1, g1, b1, a, vLight[1], nIdx);
        emitVertex(buf, ex, sy, ez, uMax, vMax, r2, g2, b2, a, vLight[2], nIdx);
        emitVertex(buf, ex, eyR, ez, uMax, vTop11, r3, g3, b3, a, vLight[3],
            nIdx);
        break;
      }
      case 2: {
        short eyL = (short) (baseTop - drop10);
        short eyR = (short) (baseTop - drop00);
        emitVertex(buf, ex, eyL, sz, uMin, vTop10, r0, g0, b0, a, vLight[0],
            nIdx);
        emitVertex(buf, ex, sy, sz, uMin, vMax, r1, g1, b1, a, vLight[1], nIdx);
        emitVertex(buf, sx, sy, sz, uMax, vMax, r2, g2, b2, a, vLight[2], nIdx);
        emitVertex(buf, sx, eyR, sz, uMax, vTop00, r3, g3, b3, a, vLight[3],
            nIdx);
        break;
      }
      case 5: {
        short eyL = (short) (baseTop - drop11);
        short eyR = (short) (baseTop - drop10);
        emitVertex(buf, ex, eyL, ez, uMin, vTop11, r0, g0, b0, a, vLight[0],
            nIdx);
        emitVertex(buf, ex, sy, ez, uMin, vMax, r1, g1, b1, a, vLight[1], nIdx);
        emitVertex(buf, ex, sy, sz, uMax, vMax, r2, g2, b2, a, vLight[2], nIdx);
        emitVertex(buf, ex, eyR, sz, uMax, vTop10, r3, g3, b3, a, vLight[3],
            nIdx);
        break;
      }
      case 4: {
        short eyL = (short) (baseTop - drop00);
        short eyR = (short) (baseTop - drop01);
        emitVertex(buf, sx, eyL, sz, uMin, vTop00, r0, g0, b0, a, vLight[0],
            nIdx);
        emitVertex(buf, sx, sy, sz, uMin, vMax, r1, g1, b1, a, vLight[1], nIdx);
        emitVertex(buf, sx, sy, ez, uMax, vMax, r2, g2, b2, a, vLight[2], nIdx);
        emitVertex(buf, sx, eyR, ez, uMax, vTop01, r3, g3, b3, a, vLight[3],
            nIdx);
        break;
      }
    }
  }

  private static void emitVertex(ByteBuffer buf, short px, short py, short pz,
      short u, short v, byte r, byte g, byte b,
      byte a, byte light, byte nIdx) {

    long w0 = (px & 0xFFFFL) | ((py & 0xFFFFL) << 16) | ((pz & 0xFFFFL) << 32) |
        ((u & 0xFFFFL) << 48);

    long w1 = (v & 0xFFFFL) | ((r & 0xFFL) << 16) | ((g & 0xFFL) << 24) |
        ((b & 0xFFL) << 32) | ((a & 0xFFL) << 40) |
        ((light & 0xFFL) << 48) | ((nIdx & 0xFFL) << 56);
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

  private static float calcVertexAO(boolean side1, boolean side2,
      boolean corner) {
    if (side1 && side2)
      return AO_CURVE[0];
    int level = 3 - ((side1 ? 1 : 0) + (side2 ? 1 : 0) + (corner ? 1 : 0));
    return AO_CURVE[level];
  }

  private static boolean isOpaqueAtExt(int x, int y, int z, int[] blockStates,
      boolean[] sidOpaque, int[] nXNeg,
      int[] nXPos, int[] nYNeg, int[] nYPos,
      int[] nZNeg, int[] nZPos) {
    if (x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16) {
      int sid = blockStates != null ? blockStates[y * 256 + z * 16 + x] : 0;
      return sid > 0 && sid < sidOpaque.length && sidOpaque[sid];
    }
    int oob = (x < 0 || x >= 16 ? 1 : 0) + (y < 0 || y >= 16 ? 1 : 0) +
        (z < 0 || z >= 16 ? 1 : 0);
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
    int sl = (((a >> 4) & 0xF) + ((b >> 4) & 0xF) + ((c >> 4) & 0xF) +
        ((d >> 4) & 0xF) + 2) >> 2;
    return (byte) ((bl & 0xF) | ((sl & 0xF) << 4));
  }

  private static byte mergePackedLight(int first, int second) {
    int block = Math.max(first & 0xF, second & 0xF);
    int sky = Math.max((first >> 4) & 0xF, (second >> 4) & 0xF);
    return (byte) ((block & 0xF) | ((sky & 0xF) << 4));
  }

  private static long computeVisibilityMask(int[] blockStates) {
    if (blockStates == null) {
      return 0x3F3F3F3FL;
    }
    boolean[] visited = new boolean[4096];
    int[] queue = new int[4096];
    long mask = 0L;
    for (int startFace = 0; startFace < 6; startFace++) {
      int head = 0;
      int tail = 0;
      for (int y = 0; y < SECTION_SIZE; y++) {
        for (int z = 0; z < SECTION_SIZE; z++) {
          for (int x = 0; x < SECTION_SIZE; x++) {
            if (!isOnFace(x, y, z, startFace)) {
              continue;
            }
            int idx = y * 256 + z * 16 + x;
            if (visited[idx] || isOccludingState(blockStates[idx])) {
              continue;
            }
            visited[idx] = true;
            queue[tail++] = idx;
          }
        }
      }
      while (head < tail) {
        int idx = queue[head++];
        int y = idx >> 8;
        int z = (idx >> 4) & 15;
        int x = idx & 15;
        for (int face = 0; face < 6; face++) {
          if (isOnFace(x, y, z, face)) {
            mask |= 1L << (startFace * 8 + face);
          }
        }
        tail = enqueueVisibleNeighbor(blockStates, visited, queue, tail, x + 1, y, z);
        tail = enqueueVisibleNeighbor(blockStates, visited, queue, tail, x - 1, y, z);
        tail = enqueueVisibleNeighbor(blockStates, visited, queue, tail, x, y + 1, z);
        tail = enqueueVisibleNeighbor(blockStates, visited, queue, tail, x, y - 1, z);
        tail = enqueueVisibleNeighbor(blockStates, visited, queue, tail, x, y, z + 1);
        tail = enqueueVisibleNeighbor(blockStates, visited, queue, tail, x, y, z - 1);
      }
      java.util.Arrays.fill(visited, false);
    }
    return mask;
  }

  private static int enqueueVisibleNeighbor(int[] blockStates, boolean[] visited,
      int[] queue, int tail, int x, int y, int z) {
    if (x < 0 || x >= SECTION_SIZE || y < 0 || y >= SECTION_SIZE || z < 0 ||
        z >= SECTION_SIZE) {
      return tail;
    }
    int idx = y * 256 + z * 16 + x;
    if (visited[idx] || isOccludingState(blockStates[idx])) {
      return tail;
    }
    visited[idx] = true;
    queue[tail++] = idx;
    return tail;
  }

  private static boolean isOnFace(int x, int y, int z, int face) {
    return switch (face) {
      case 0 -> y == 0;
      case 1 -> y == 15;
      case 2 -> z == 0;
      case 3 -> z == 15;
      case 4 -> x == 0;
      case 5 -> x == 15;
      default -> false;
    };
  }

  private static boolean isOccludingState(int stateId) {
    if (stateId == 0) {
      return false;
    }
    BlockState state = Block.stateById(stateId);
    return state != null && state.isSolidRender() && state.canOcclude();
  }

  private static int quadNormalIndex(ByteBuffer buffer, int quadIndex) {
    int base = quadIndex * 4 * VERTEX_STRIDE;
    long w1 = buffer.getLong(base + 8);
    int normal = (int) ((w1 >>> 56) & 0xFF);
    return normal >= 0 && normal < 6 ? normal : 6;
  }

  private static void putQuad(ByteBuffer dst, ByteBuffer src, int quadIndex) {
    int base = quadIndex * 4 * VERTEX_STRIDE;
    for (int i = 0; i < 4 * VERTEX_STRIDE; i++) {
      dst.put(src.get(base + i));
    }
  }

  private static int[] bucketQuadsByFacing(ByteBuffer vertexBuffer,
      int opaqueQuadCount, int waterQuadCount) {
    int totalQuads = opaqueQuadCount + waterQuadCount;
    int[] counts = new int[14];
    ByteBuffer sorted = SORT_BUF_POOL.get();
    sorted.clear();
    for (int bucket = 0; bucket < 7; bucket++) {
      for (int q = 0; q < opaqueQuadCount; q++) {
        if (quadNormalIndex(vertexBuffer, q) == bucket) {
          putQuad(sorted, vertexBuffer, q);
          counts[bucket]++;
        }
      }
    }
    for (int bucket = 0; bucket < 7; bucket++) {
      for (int q = opaqueQuadCount; q < totalQuads; q++) {
        if (quadNormalIndex(vertexBuffer, q) == bucket) {
          putQuad(sorted, vertexBuffer, q);
          counts[7 + bucket]++;
        }
      }
    }
    sorted.flip();
    vertexBuffer.clear();
    vertexBuffer.put(sorted);
    vertexBuffer.flip();
    return counts;
  }

  private static void computeSmoothLighting(int x, int y, int z, int face, int[] blockStates,
      boolean[] sidOpaque, byte[] lightData, int[] nXNeg,
      int[] nXPos, int[] nYNeg, int[] nYPos, int[] nZNeg,
      int[] nZPos, byte[] nXNegLight, byte[] nXPosLight,
      byte[] nYNegLight, byte[] nYPosLight, byte[] nZNegLight,
      byte[] nZPosLight, float[] aoOut, byte[] lightOut) {

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
    int cL = getLightAtExt(nx, ny, nz, lightData, nXNegLight, nXPosLight,
        nYNegLight, nYPosLight, nZNegLight, nZPosLight);
    int srcL = getLightAtExt(x, y, z, lightData, nXNegLight, nXPosLight,
        nYNegLight, nYPosLight, nZNegLight, nZPosLight);

    if (!applyFaceShade) {
      aoOut[0] = aoOut[1] = aoOut[2] = aoOut[3] = 1.0f;
      byte flatLight = mergePackedLight(srcL, cL);
      lightOut[0] = lightOut[1] = lightOut[2] = lightOut[3] = flatLight;
      return;
    }

    switch (face) {
      case 1: {
        boolean eW = isOpaqueAtExt(nx - 1, ny, nz, blockStates, sidOpaque, nXNeg,
            nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eE = isOpaqueAtExt(nx + 1, ny, nz, blockStates, sidOpaque, nXNeg,
            nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eN = isOpaqueAtExt(nx, ny, nz - 1, blockStates, sidOpaque, nXNeg,
            nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eS = isOpaqueAtExt(nx, ny, nz + 1, blockStates, sidOpaque, nXNeg,
            nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cNW = eW && eN || isOpaqueAtExt(nx - 1, ny, nz - 1, blockStates, sidOpaque,
            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cSW = eW && eS || isOpaqueAtExt(nx - 1, ny, nz + 1, blockStates, sidOpaque,
            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cSE = eE && eS || isOpaqueAtExt(nx + 1, ny, nz + 1, blockStates, sidOpaque,
            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cNE = eE && eN || isOpaqueAtExt(nx + 1, ny, nz - 1, blockStates, sidOpaque,
            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        int lW = getLightAtExt(nx - 1, ny, nz, lightData, nXNegLight, nXPosLight,
            nYNegLight, nYPosLight, nZNegLight, nZPosLight),
            lE = getLightAtExt(nx + 1, ny, nz, lightData, nXNegLight, nXPosLight,
                nYNegLight, nYPosLight, nZNegLight, nZPosLight);
        int lN = getLightAtExt(nx, ny, nz - 1, lightData, nXNegLight, nXPosLight,
            nYNegLight, nYPosLight, nZNegLight, nZPosLight),
            lS = getLightAtExt(nx, ny, nz + 1, lightData, nXNegLight, nXPosLight,
                nYNegLight, nYPosLight, nZNegLight, nZPosLight);
        int lNW = getLightAtExt(nx - 1, ny, nz - 1, lightData, nXNegLight,
            nXPosLight, nYNegLight, nYPosLight, nZNegLight,
            nZPosLight),
            lSW = getLightAtExt(nx - 1, ny, nz + 1, lightData, nXNegLight,
                nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                nZPosLight);
        int lSE = getLightAtExt(nx + 1, ny, nz + 1, lightData, nXNegLight,
            nXPosLight, nYNegLight, nYPosLight, nZNegLight,
            nZPosLight),
            lNE = getLightAtExt(nx + 1, ny, nz - 1, lightData, nXNegLight,
                nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                nZPosLight);

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
        boolean eW = isOpaqueAtExt(nx - 1, ny, nz, blockStates, sidOpaque, nXNeg,
            nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eE = isOpaqueAtExt(nx + 1, ny, nz, blockStates, sidOpaque, nXNeg,
            nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eN = isOpaqueAtExt(nx, ny, nz - 1, blockStates, sidOpaque, nXNeg,
            nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eS = isOpaqueAtExt(nx, ny, nz + 1, blockStates, sidOpaque, nXNeg,
            nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cSW = eW && eS || isOpaqueAtExt(nx - 1, ny, nz + 1, blockStates, sidOpaque,
            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cNW = eW && eN || isOpaqueAtExt(nx - 1, ny, nz - 1, blockStates, sidOpaque,
            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cNE = eE && eN || isOpaqueAtExt(nx + 1, ny, nz - 1, blockStates, sidOpaque,
            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cSE = eE && eS || isOpaqueAtExt(nx + 1, ny, nz + 1, blockStates, sidOpaque,
            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        int lW = getLightAtExt(nx - 1, ny, nz, lightData, nXNegLight, nXPosLight,
            nYNegLight, nYPosLight, nZNegLight, nZPosLight),
            lE = getLightAtExt(nx + 1, ny, nz, lightData, nXNegLight, nXPosLight,
                nYNegLight, nYPosLight, nZNegLight, nZPosLight);
        int lN = getLightAtExt(nx, ny, nz - 1, lightData, nXNegLight, nXPosLight,
            nYNegLight, nYPosLight, nZNegLight, nZPosLight),
            lS = getLightAtExt(nx, ny, nz + 1, lightData, nXNegLight, nXPosLight,
                nYNegLight, nYPosLight, nZNegLight, nZPosLight);
        int lNW = getLightAtExt(nx - 1, ny, nz - 1, lightData, nXNegLight,
            nXPosLight, nYNegLight, nYPosLight, nZNegLight,
            nZPosLight),
            lSW = getLightAtExt(nx - 1, ny, nz + 1, lightData, nXNegLight,
                nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                nZPosLight);
        int lSE = getLightAtExt(nx + 1, ny, nz + 1, lightData, nXNegLight,
            nXPosLight, nYNegLight, nYPosLight, nZNegLight,
            nZPosLight),
            lNE = getLightAtExt(nx + 1, ny, nz - 1, lightData, nXNegLight,
                nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                nZPosLight);

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
        boolean eE = isOpaqueAtExt(nx + 1, ny, nz, blockStates, sidOpaque, nXNeg,
            nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eW = isOpaqueAtExt(nx - 1, ny, nz, blockStates, sidOpaque, nXNeg,
            nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eU = isOpaqueAtExt(nx, ny + 1, nz, blockStates, sidOpaque, nXNeg,
            nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eD = isOpaqueAtExt(nx, ny - 1, nz, blockStates, sidOpaque, nXNeg,
            nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cUE = eE && eU || isOpaqueAtExt(nx + 1, ny + 1, nz, blockStates, sidOpaque,
            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cDE = eE && eD || isOpaqueAtExt(nx + 1, ny - 1, nz, blockStates, sidOpaque,
            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cDW = eW && eD || isOpaqueAtExt(nx - 1, ny - 1, nz, blockStates, sidOpaque,
            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cUW = eW && eU || isOpaqueAtExt(nx - 1, ny + 1, nz, blockStates, sidOpaque,
            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        int lE = getLightAtExt(nx + 1, ny, nz, lightData, nXNegLight, nXPosLight,
            nYNegLight, nYPosLight, nZNegLight, nZPosLight),
            lW = getLightAtExt(nx - 1, ny, nz, lightData, nXNegLight, nXPosLight,
                nYNegLight, nYPosLight, nZNegLight, nZPosLight);
        int lU = getLightAtExt(nx, ny + 1, nz, lightData, nXNegLight, nXPosLight,
            nYNegLight, nYPosLight, nZNegLight, nZPosLight),
            lD = getLightAtExt(nx, ny - 1, nz, lightData, nXNegLight, nXPosLight,
                nYNegLight, nYPosLight, nZNegLight, nZPosLight);
        int lUE = getLightAtExt(nx + 1, ny + 1, nz, lightData, nXNegLight,
            nXPosLight, nYNegLight, nYPosLight, nZNegLight,
            nZPosLight),
            lDE = getLightAtExt(nx + 1, ny - 1, nz, lightData, nXNegLight,
                nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                nZPosLight);
        int lDW = getLightAtExt(nx - 1, ny - 1, nz, lightData, nXNegLight,
            nXPosLight, nYNegLight, nYPosLight, nZNegLight,
            nZPosLight),
            lUW = getLightAtExt(nx - 1, ny + 1, nz, lightData, nXNegLight,
                nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                nZPosLight);

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
        boolean eW = isOpaqueAtExt(nx - 1, ny, nz, blockStates, sidOpaque, nXNeg,
            nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eE = isOpaqueAtExt(nx + 1, ny, nz, blockStates, sidOpaque, nXNeg,
            nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eU = isOpaqueAtExt(nx, ny + 1, nz, blockStates, sidOpaque, nXNeg,
            nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eD = isOpaqueAtExt(nx, ny - 1, nz, blockStates, sidOpaque, nXNeg,
            nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cUW = eW && eU || isOpaqueAtExt(nx - 1, ny + 1, nz, blockStates, sidOpaque,
            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cDW = eW && eD || isOpaqueAtExt(nx - 1, ny - 1, nz, blockStates, sidOpaque,
            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cDE = eE && eD || isOpaqueAtExt(nx + 1, ny - 1, nz, blockStates, sidOpaque,
            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cUE = eE && eU || isOpaqueAtExt(nx + 1, ny + 1, nz, blockStates, sidOpaque,
            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        int lW = getLightAtExt(nx - 1, ny, nz, lightData, nXNegLight, nXPosLight,
            nYNegLight, nYPosLight, nZNegLight, nZPosLight),
            lE = getLightAtExt(nx + 1, ny, nz, lightData, nXNegLight, nXPosLight,
                nYNegLight, nYPosLight, nZNegLight, nZPosLight);
        int lU = getLightAtExt(nx, ny + 1, nz, lightData, nXNegLight, nXPosLight,
            nYNegLight, nYPosLight, nZNegLight, nZPosLight),
            lD = getLightAtExt(nx, ny - 1, nz, lightData, nXNegLight, nXPosLight,
                nYNegLight, nYPosLight, nZNegLight, nZPosLight);
        int lUW = getLightAtExt(nx - 1, ny + 1, nz, lightData, nXNegLight,
            nXPosLight, nYNegLight, nYPosLight, nZNegLight,
            nZPosLight),
            lDW = getLightAtExt(nx - 1, ny - 1, nz, lightData, nXNegLight,
                nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                nZPosLight);
        int lDE = getLightAtExt(nx + 1, ny - 1, nz, lightData, nXNegLight,
            nXPosLight, nYNegLight, nYPosLight, nZNegLight,
            nZPosLight),
            lUE = getLightAtExt(nx + 1, ny + 1, nz, lightData, nXNegLight,
                nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                nZPosLight);

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
        boolean eU = isOpaqueAtExt(nx, ny + 1, nz, blockStates, sidOpaque, nXNeg,
            nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eD = isOpaqueAtExt(nx, ny - 1, nz, blockStates, sidOpaque, nXNeg,
            nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eN = isOpaqueAtExt(nx, ny, nz - 1, blockStates, sidOpaque, nXNeg,
            nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eS = isOpaqueAtExt(nx, ny, nz + 1, blockStates, sidOpaque, nXNeg,
            nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cUS = eU && eS || isOpaqueAtExt(nx, ny + 1, nz + 1, blockStates, sidOpaque,
            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cDS = eD && eS || isOpaqueAtExt(nx, ny - 1, nz + 1, blockStates, sidOpaque,
            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cDN = eD && eN || isOpaqueAtExt(nx, ny - 1, nz - 1, blockStates, sidOpaque,
            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cUN = eU && eN || isOpaqueAtExt(nx, ny + 1, nz - 1, blockStates, sidOpaque,
            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        int lU = getLightAtExt(nx, ny + 1, nz, lightData, nXNegLight, nXPosLight,
            nYNegLight, nYPosLight, nZNegLight, nZPosLight),
            lD = getLightAtExt(nx, ny - 1, nz, lightData, nXNegLight, nXPosLight,
                nYNegLight, nYPosLight, nZNegLight, nZPosLight);
        int lN = getLightAtExt(nx, ny, nz - 1, lightData, nXNegLight, nXPosLight,
            nYNegLight, nYPosLight, nZNegLight, nZPosLight),
            lS = getLightAtExt(nx, ny, nz + 1, lightData, nXNegLight, nXPosLight,
                nYNegLight, nYPosLight, nZNegLight, nZPosLight);
        int lUS = getLightAtExt(nx, ny + 1, nz + 1, lightData, nXNegLight,
            nXPosLight, nYNegLight, nYPosLight, nZNegLight,
            nZPosLight),
            lDS = getLightAtExt(nx, ny - 1, nz + 1, lightData, nXNegLight,
                nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                nZPosLight);
        int lDN = getLightAtExt(nx, ny - 1, nz - 1, lightData, nXNegLight,
            nXPosLight, nYNegLight, nYPosLight, nZNegLight,
            nZPosLight),
            lUN = getLightAtExt(nx, ny + 1, nz - 1, lightData, nXNegLight,
                nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                nZPosLight);

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
        boolean eU = isOpaqueAtExt(nx, ny + 1, nz, blockStates, sidOpaque, nXNeg,
            nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eD = isOpaqueAtExt(nx, ny - 1, nz, blockStates, sidOpaque, nXNeg,
            nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eN = isOpaqueAtExt(nx, ny, nz - 1, blockStates, sidOpaque, nXNeg,
            nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean eS = isOpaqueAtExt(nx, ny, nz + 1, blockStates, sidOpaque, nXNeg,
            nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cUN = eU && eN || isOpaqueAtExt(nx, ny + 1, nz - 1, blockStates, sidOpaque,
            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cDN = eD && eN || isOpaqueAtExt(nx, ny - 1, nz - 1, blockStates, sidOpaque,
            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cDS = eD && eS || isOpaqueAtExt(nx, ny - 1, nz + 1, blockStates, sidOpaque,
            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        boolean cUS = eU && eS || isOpaqueAtExt(nx, ny + 1, nz + 1, blockStates, sidOpaque,
            nXNeg, nXPos, nYNeg, nYPos, nZNeg, nZPos);
        int lU = getLightAtExt(nx, ny + 1, nz, lightData, nXNegLight, nXPosLight,
            nYNegLight, nYPosLight, nZNegLight, nZPosLight),
            lD = getLightAtExt(nx, ny - 1, nz, lightData, nXNegLight, nXPosLight,
                nYNegLight, nYPosLight, nZNegLight, nZPosLight);
        int lN = getLightAtExt(nx, ny, nz - 1, lightData, nXNegLight, nXPosLight,
            nYNegLight, nYPosLight, nZNegLight, nZPosLight),
            lS = getLightAtExt(nx, ny, nz + 1, lightData, nXNegLight, nXPosLight,
                nYNegLight, nYPosLight, nZNegLight, nZPosLight);
        int lUN = getLightAtExt(nx, ny + 1, nz - 1, lightData, nXNegLight,
            nXPosLight, nYNegLight, nYPosLight, nZNegLight,
            nZPosLight),
            lDN = getLightAtExt(nx, ny - 1, nz - 1, lightData, nXNegLight,
                nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                nZPosLight);
        int lDS = getLightAtExt(nx, ny - 1, nz + 1, lightData, nXNegLight,
            nXPosLight, nYNegLight, nYPosLight, nZNegLight,
            nZPosLight),
            lUS = getLightAtExt(nx, ny + 1, nz + 1, lightData, nXNegLight,
                nXPosLight, nYNegLight, nYPosLight, nZNegLight,
                nZPosLight);

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
      boolean hasSpr, short uMin, short uMax,
      short vMin, short vMax, int topDrop,
      float[] ao, byte[] vLight) {
    byte nIdx = (byte) normalIdx;
    if (!hasSpr) {
      uMin = 0;
      uMax = (short) 65535;
      vMin = 0;
      vMax = (short) 65535;
    }
    short sx = (short) (x * 256), sy = (short) (y * 256), sz = (short) (z * 256);
    short ex = (short) ((x + 1) * 256), ey = (short) ((y + 1) * 256 - topDrop),
        ez = (short) ((z + 1) * 256);
    int ri = r & 0xFF, gi = g & 0xFF, bi = b & 0xFF;
    byte r0 = (byte) (int) (ri * ao[0]), g0 = (byte) (int) (gi * ao[0]),
        b0 = (byte) (int) (bi * ao[0]);
    byte r1 = (byte) (int) (ri * ao[1]), g1 = (byte) (int) (gi * ao[1]),
        b1 = (byte) (int) (bi * ao[1]);
    byte r2 = (byte) (int) (ri * ao[2]), g2 = (byte) (int) (gi * ao[2]),
        b2 = (byte) (int) (bi * ao[2]);
    byte r3 = (byte) (int) (ri * ao[3]), g3 = (byte) (int) (gi * ao[3]),
        b3 = (byte) (int) (bi * ao[3]);
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

  private static final ThreadLocal<short[][]> FACE_POS_POOL = ThreadLocal
      .withInitial(() -> new short[][] { new short[3], new short[3],
          new short[3],
          new short[3] });

  private int emitFaceScaled(ByteBuffer buf, int x, int y, int z,
      int normalIndex, TextureAtlasSprite sprite,
      byte packedLight, byte r, byte g, byte b,
      byte alpha, int scale, int topDrop) {

    short sx = (short) (x * 256), sy = (short) (y * 256), sz = (short) (z * 256);
    short nex = (short) ((x + scale) * 256),
        ney = (short) ((y + scale) * 256 - topDrop),
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
    return block == Blocks.SNOW || block == Blocks.WHITE_CARPET ||
        block == Blocks.ORANGE_CARPET || block == Blocks.MAGENTA_CARPET ||
        block == Blocks.LIGHT_BLUE_CARPET || block == Blocks.YELLOW_CARPET ||
        block == Blocks.LIME_CARPET || block == Blocks.PINK_CARPET ||
        block == Blocks.GRAY_CARPET || block == Blocks.LIGHT_GRAY_CARPET ||
        block == Blocks.CYAN_CARPET || block == Blocks.PURPLE_CARPET ||
        block == Blocks.BLUE_CARPET || block == Blocks.BROWN_CARPET ||
        block == Blocks.GREEN_CARPET || block == Blocks.RED_CARPET ||
        block == Blocks.BLACK_CARPET || block == Blocks.MOSS_CARPET;
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
      net.minecraft.resources.Identifier leafLitterId = net.minecraft.resources.Identifier.fromNamespaceAndPath(
          "minecraft", "leaf_litter");
      net.minecraft.core.Registry<Block> blockRegistry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
      if (blockRegistry.containsKey(leafLitterId)) {
        Block leafLitterBlock = blockRegistry.get(leafLitterId)
            .map(net.minecraft.core.Holder.Reference::value)
            .orElse(null);
        if (leafLitterBlock != null &&
            !BIOME_TINT_TYPE.containsKey(leafLitterBlock)) {
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

  private static int getBiomeColor(ClientLevel world, int worldX, int worldY,
      int worldZ, byte tintType) {
    if (world == null || tintType == TINT_NONE)
      return DEFAULT_BLOCK_COLOR;
    try {
      BlockPos.MutableBlockPos pos = BIOME_POS_POOL.get();
      pos.set(worldX, worldY, worldZ);
      return switch (tintType) {
        case TINT_GRASS ->
          world.getBlockTint(
              pos,
              net.minecraft.client.renderer.BiomeColors.GRASS_COLOR_RESOLVER);
        case TINT_FOLIAGE ->
          world.getBlockTint(
              pos,
              net.minecraft.client.renderer.BiomeColors.FOLIAGE_COLOR_RESOLVER);
        case TINT_WATER ->
          world.getBlockTint(
              pos,
              net.minecraft.client.renderer.BiomeColors.WATER_COLOR_RESOLVER);
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

  private static int[] getSectionBiomeColors(ClientLevel world, int chunkX,
      int chunkY, int chunkZ,
      int detail) {
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
          { 8, dy, 8 }, { 2, dy, 2 }, { 14, dy, 2 }, { 2, dy, 14 }, { 14, dy, 14 } };
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
        tmp[valid++] = world.getBlockTint(
            pos,
            net.minecraft.client.renderer.BiomeColors.GRASS_COLOR_RESOLVER);
      }
      colors[TINT_GRASS] = avgColor(tmp, valid);

      valid = 0;
      for (int[] off : offsets) {
        pos.set(bx + off[0], by + off[1], bz + off[2]);
        tmp[valid++] = world.getBlockTint(
            pos,
            net.minecraft.client.renderer.BiomeColors.FOLIAGE_COLOR_RESOLVER);
      }
      colors[TINT_FOLIAGE] = avgColor(tmp, valid);

      valid = 0;
      for (int[] off : offsets) {
        pos.set(bx + off[0], by + off[1], bz + off[2]);
        tmp[valid++] = world.getBlockTint(
            pos,
            net.minecraft.client.renderer.BiomeColors.WATER_COLOR_RESOLVER);
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

  public void removeMesh(int cx, int cy, int cz) {
    long key = packChunkKey(cx, cy, cz);
    ChunkMeshData mesh;
    synchronized (meshCache) {
      mesh = meshCache.remove(key);
    }
    if (mesh != null) {
      NativeBridge.nUnregisterChunkMesh(cx, cy, cz);
      NativeBridge.nDestroyBuffer(mesh.bufferHandle);
      meshCountAtomic.decrementAndGet();
      vertexCountAtomic.addAndGet(-mesh.quadCount * 4);
    }
    synchronized (emptyKeys) {
      emptyKeys.remove(key);
    }
  }

  public void clear() {
    synchronized (meshCache) {
      for (ChunkMeshData mesh : meshCache.values()) {
        NativeBridge.nUnregisterChunkMesh(mesh.chunkX, mesh.chunkY,
            mesh.chunkZ);
        NativeBridge.nDestroyBuffer(mesh.bufferHandle);
      }
      meshCache.clear();
    }

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
