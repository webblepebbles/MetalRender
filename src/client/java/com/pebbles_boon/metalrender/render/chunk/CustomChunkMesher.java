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
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.util.TriState;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

public class CustomChunkMesher {
  private static final int VERTEX_STRIDE = 16;
  private static final int SECTION_SIZE = 16;
  private static final int BIOME_TINT_SLOTS = 4;
  private static final int PADDED_RADIUS = 2;
  private static final int PADDED_SIZE = SECTION_SIZE + PADDED_RADIUS * 2;
  private static final int PADDED_VOLUME = PADDED_SIZE * PADDED_SIZE * PADDED_SIZE;
  private static final int MAX_QUADS = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE * 6;
  private static final int VERTEX_BUF_SIZE = MAX_QUADS * 4 * VERTEX_STRIDE;
  private static final byte WATER_ALPHA = (byte) 168;
  private static final float FLUID_EPSILON = 0.001f;
  private static final float GRASS_OVERLAY_OFFSET = 1.0f / 256.0f;
  private static final int UPLOAD_PARALLELISM = Math.min(6,
      Math.max(3, Runtime.getRuntime().availableProcessors() / 2));
  private static final java.util.concurrent.Semaphore UPLOAD_SEMAPHORE = new java.util.concurrent.Semaphore(
      UPLOAD_PARALLELISM);
  private static final java.util.concurrent.atomic.AtomicLong BUILD_WALL_ACC = new java.util.concurrent.atomic.AtomicLong(
      0L);
  private static final java.util.concurrent.atomic.AtomicInteger BUILD_WALL_CNT = new java.util.concurrent.atomic.AtomicInteger(
      0);
  private static final java.util.concurrent.atomic.AtomicInteger DIAG_INVALID = new java.util.concurrent.atomic.AtomicInteger(
      0);
  private static final java.util.concurrent.atomic.AtomicInteger DIAG_EMPTY = new java.util.concurrent.atomic.AtomicInteger(
      0);
  private static final java.util.concurrent.atomic.AtomicInteger DIAG_BUILT = new java.util.concurrent.atomic.AtomicInteger(
      0);

  private static final ThreadLocal<ByteBuffer> VERTEX_BUF_POOL = ThreadLocal
      .withInitial(() -> ByteBuffer.allocateDirect(VERTEX_BUF_SIZE)
          .order(ByteOrder.nativeOrder()));
  private static final ThreadLocal<ByteBuffer> WATER_BUF_POOL = ThreadLocal
      .withInitial(() -> ByteBuffer.allocateDirect(VERTEX_BUF_SIZE)
          .order(ByteOrder.nativeOrder()));
  private static final ThreadLocal<ByteBuffer> FACE_BUCKET_BUF_POOL = ThreadLocal
      .withInitial(() -> ByteBuffer.allocateDirect(VERTEX_BUF_SIZE)
          .order(ByteOrder.nativeOrder()));

  private static final Direction[] ALL_DIRECTIONS = Direction.values();

  private static final int BATCH_REG_CAPACITY = 2048;
  private static final int BATCH_REG_STRIDE = 9;
  public static final int NEIGHBOR_MISSING_MINUS_X = 1;
  public static final int NEIGHBOR_MISSING_PLUS_X = 2;
  public static final int NEIGHBOR_MISSING_MINUS_Z = 4;
  public static final int NEIGHBOR_MISSING_PLUS_Z = 8;

  public static class ChunkMeshData {
    public final long bufferHandle;
    public final int quadCount;
    public final int chunkX;
    public final int chunkY;
    public final int chunkZ;
    public final long visibilityMask;
    public final int[] facingQuadCounts;
    public final int buildPlayerCX, buildPlayerCY, buildPlayerCZ;
    public final int lodTier;
    public final byte missingNeighborMask;

    public final byte faceOcclusionMask;

    public ChunkMeshData(long bufferHandle, int quadCount, int chunkX, int chunkY, int chunkZ,
        int buildPlayerCX, int buildPlayerCY, int buildPlayerCZ) {
      this(bufferHandle, quadCount, chunkX, chunkY, chunkZ, buildPlayerCX, buildPlayerCY, buildPlayerCZ, 0L,
          new int[7], 0, (byte) 0, (byte) 0);
    }

    public ChunkMeshData(long bufferHandle, int quadCount, int chunkX, int chunkY, int chunkZ,
        int buildPlayerCX, int buildPlayerCY, int buildPlayerCZ, long visibilityMask, int[] facingQuadCounts,
        int lodTier, byte missingNeighborMask) {
      this(bufferHandle, quadCount, chunkX, chunkY, chunkZ, buildPlayerCX, buildPlayerCY, buildPlayerCZ,
          visibilityMask, facingQuadCounts, lodTier, missingNeighborMask, (byte) 0);
    }

    public ChunkMeshData(long bufferHandle, int quadCount, int chunkX, int chunkY, int chunkZ,
        int buildPlayerCX, int buildPlayerCY, int buildPlayerCZ, long visibilityMask, int[] facingQuadCounts,
        int lodTier, byte missingNeighborMask, byte faceOcclusionMask) {
      this.bufferHandle = bufferHandle;
      this.quadCount = quadCount;
      this.chunkX = chunkX;
      this.chunkY = chunkY;
      this.chunkZ = chunkZ;
      this.buildPlayerCX = buildPlayerCX;
      this.buildPlayerCY = buildPlayerCY;
      this.buildPlayerCZ = buildPlayerCZ;
      this.visibilityMask = visibilityMask;
      this.facingQuadCounts = facingQuadCounts != null ? Arrays.copyOf(facingQuadCounts, 14) : new int[14];
      this.lodTier = lodTier;
      this.missingNeighborMask = missingNeighborMask;
      this.faceOcclusionMask = faceOcclusionMask;
    }
  }

  private static volatile int lodThermalBias = 0;
  private static volatile int lodForceCoarse = 0;

  private static final java.util.concurrent.ConcurrentHashMap<Long, Integer> lodTierOverrides =
      new java.util.concurrent.ConcurrentHashMap<>(256);

  public static void setLodThermalBias(int bias) {
    lodThermalBias = Math.max(0, Math.min(2, bias));
  }

  public static void setLodTierOverride(long key, int tier) {
    if (tier <= 0) {
      lodTierOverrides.remove(key);
    } else {
      lodTierOverrides.put(key, Math.max(1, Math.min(2, tier)));
    }
  }

  public static void clearLodTierOverrides() {
    lodTierOverrides.clear();
  }

  public static int applyLodTierOverride(long key, int ringTier) {
    Integer override = lodTierOverrides.get(key);
    return override != null && override > ringTier ? override : ringTier;
  }

  public static void setLodForceCoarse(int cap) {
    lodForceCoarse = Math.max(0, Math.min(2, cap));
  }

  public static int lodTierForDistance(int chunkDist) {
    MetalRenderConfig cfg = MetalRenderClient.getConfig();
    if (cfg == null || !cfg.enableDistanceLod) {
      return 0;
    }
    int bias = lodThermalBias;
    int near = Math.max(2, cfg.lodNearChunks - bias * 2);
    int mid = Math.max(near + 2, cfg.lodMidChunks - bias * 4);
    int tier;
    if (chunkDist <= near) {
      tier = 0;
    } else if (chunkDist <= mid) {
      tier = 1;
    } else {
      tier = 2;
    }
    int cap = lodForceCoarse;
    if (cap > 0 && tier < cap) {
      tier = cap;
    }
    return tier;
  }

  private final Long2ObjectOpenHashMap<ChunkMeshData> meshCache;
  private final java.util.ArrayList<ChunkMeshData> cachedMeshSnapshot = new java.util.ArrayList<>(8192);
  private volatile int cachedSnapshotGen = Integer.MIN_VALUE;
  private final LongOpenHashSet pendingKeys = new LongOpenHashSet();
  private final LongOpenHashSet dirtyKeys = new LongOpenHashSet();
  private final LongOpenHashSet emptyKeys = new LongOpenHashSet();
  private final long[] batchRegData = new long[BATCH_REG_CAPACITY * BATCH_REG_STRIDE];
  private int batchRegCount = 0;

  private final java.util.concurrent.ThreadPoolExecutor immediatePool;
  private final java.util.concurrent.ThreadPoolExecutor backgroundPool;

  private final Long2LongOpenHashMap dirtyGeneration = new Long2LongOpenHashMap();
  private final java.util.concurrent.atomic.AtomicLong globalBuildGeneration = new java.util.concurrent.atomic.AtomicLong();
  private final Long2LongOpenHashMap pendingVisibleSectionNanos = new Long2LongOpenHashMap();
  private final Long2LongOpenHashMap pendingBlockUpdateNanos = new Long2LongOpenHashMap();

  private long deviceHandle;
  private boolean initialized;
  private long globalIndexBufferHandle;

  private final java.util.concurrent.atomic.AtomicInteger meshCountAtomic = new java.util.concurrent.atomic.AtomicInteger(
      0);
  private final java.util.concurrent.atomic.AtomicInteger vertexCountAtomic = new java.util.concurrent.atomic.AtomicInteger(
      0);
  private final java.util.concurrent.atomic.AtomicInteger meshUpdateGeneration = new java.util.concurrent.atomic.AtomicInteger(
      0);

  private final int immediateThreadCount;
  private final int backgroundThreadCount;

  private final java.util.concurrent.atomic.AtomicLong visibleSectionLatencyAccNs = new java.util.concurrent.atomic.AtomicLong(
      0L);
  private final java.util.concurrent.atomic.AtomicInteger visibleSectionLatencySamples = new java.util.concurrent.atomic.AtomicInteger(
      0);
  private final java.util.concurrent.atomic.AtomicLong blockUpdateLatencyAccNs = new java.util.concurrent.atomic.AtomicLong(
      0L);
  private final java.util.concurrent.atomic.AtomicInteger blockUpdateLatencySamples = new java.util.concurrent.atomic.AtomicInteger(
      0);

  public CustomChunkMesher() {
    this.meshCache = new Long2ObjectOpenHashMap<>();
    this.dirtyGeneration.defaultReturnValue(0L);
    this.pendingVisibleSectionNanos.defaultReturnValue(0L);
    this.pendingBlockUpdateNanos.defaultReturnValue(0L);

    int processors = Runtime.getRuntime().availableProcessors();
    this.immediateThreadCount = Math.max(3, Math.min(6, processors / 3 + 1));
    this.backgroundThreadCount = Math.max(4, Math.min(8, processors - 2));

    final java.util.concurrent.ThreadFactory immediateFactory = r -> {
      Thread t = new Thread(r, "MetalRender-MeshBuilder-Immediate");
      t.setDaemon(true);
      t.setPriority(Thread.NORM_PRIORITY - 1);
      return t;
    };
    final java.util.concurrent.ThreadFactory backgroundFactory = r -> {
      Thread t = new Thread(r, "MetalRender-MeshBuilder-Background");
      t.setDaemon(true);
      t.setPriority(Thread.NORM_PRIORITY - 1);
      return t;
    };

    this.immediatePool = new java.util.concurrent.ThreadPoolExecutor(
        immediateThreadCount, immediateThreadCount, 10L,
        java.util.concurrent.TimeUnit.SECONDS,
        new java.util.concurrent.PriorityBlockingQueue<>(), immediateFactory);
    this.immediatePool.allowCoreThreadTimeOut(true);
    this.backgroundPool = new java.util.concurrent.ThreadPoolExecutor(
        backgroundThreadCount, backgroundThreadCount, 10L,
        java.util.concurrent.TimeUnit.SECONDS,
        new java.util.concurrent.PriorityBlockingQueue<>(), backgroundFactory);
    this.backgroundPool.allowCoreThreadTimeOut(true);
  }

  public long getGlobalIndexBuffer() {
    return globalIndexBufferHandle;
  }

  public void initialize(long device) {
    this.deviceHandle = device;
    MetalLogger.info("mesher init: dev=%d cfg=%s",
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
    MetalLogger.info("mesher ready (maxq=%d ib=%d)",
        MAX_QUADS, ibData.length);
  }

  public boolean buildMeshFromWorld(int chunkX, int chunkY, int chunkZ, boolean highPriority) {
    return buildMeshFromWorld(chunkX, chunkY, chunkZ, highPriority, false);
  }

  public boolean buildMeshFromWorld(int chunkX, int chunkY, int chunkZ,
      boolean highPriority, boolean interactive) {
    if (!initialized)
      return false;
    Minecraft mc = Minecraft.getInstance();
    ClientLevel world = mc != null ? mc.level : null;
    if (world == null)
      return false;

    long key = packChunkKey(chunkX, chunkY, chunkZ);
    final long genAtSubmit;
    final long globalGenAtSubmit = globalBuildGeneration.get();
    synchronized (dirtyGeneration) {
      genAtSubmit = dirtyGeneration.get(key);
    }

    synchronized (pendingKeys) {
      pendingKeys.add(key);
    }

    int priority = interactive ? 0 : (highPriority ? 1 : 2);
    boolean submitted = submitMeshTask(priority, () -> {
      long taskStart = System.nanoTime();
      try {
        if (isTaskCancelled(key, genAtSubmit, globalGenAtSubmit)) {
          return;
        }
        refreshThreadLocalCachesIfNeeded();
        MeshBuildContext context = captureBuildContext(world, chunkX, chunkY, chunkZ);
        int lodTier = lodTierForDistance(Math.max(
            Math.abs(chunkX - context.buildPlayerCX),
            Math.abs(chunkZ - context.buildPlayerCZ)));
        lodTier = applyLodTierOverride(key, lodTier);

        SectionSnapshot snapshot = captureSectionSnapshot(world, chunkX, chunkY, chunkZ, lodTier);
        if (!snapshot.valid) {
          int v = DIAG_INVALID.incrementAndGet();
          if (v % 2000 == 1) {
            MetalLogger.info("builddiag: invalid_snapshot=%d empty=%d built=%d",
                DIAG_INVALID.get(), DIAG_EMPTY.get(), DIAG_BUILT.get());
          }
          return;
        }
        if (snapshot.empty) {
          DIAG_EMPTY.incrementAndGet();
          removeEmptyMesh(key, chunkX, chunkY, chunkZ, genAtSubmit, globalGenAtSubmit);
          return;
        }
        DIAG_BUILT.incrementAndGet();
        doMeshBuild(chunkX, chunkY, chunkZ, snapshot, key, genAtSubmit, globalGenAtSubmit, context, lodTier);
      } catch (Exception e) {
        MetalLogger.error("mesher fail [%d,%d,%d]: %s", chunkX,
            chunkY, chunkZ, e.getMessage());
      } finally {
        long taskWall = System.nanoTime() - taskStart;
        long acc = BUILD_WALL_ACC.getAndAdd(taskWall);
        int cnt = BUILD_WALL_CNT.incrementAndGet();
        if (cnt % 1000 == 0) {
          MetalLogger.info("builddiag: avg_wall=%.2fms over %d tasks",
              (double) acc / cnt / 1e6, cnt);
          BUILD_WALL_ACC.set(0);
          BUILD_WALL_CNT.set(0);
        }
        synchronized (dirtyGeneration) {
          if (dirtyGeneration.get(key) == genAtSubmit) {
            synchronized (pendingKeys) {
              pendingKeys.remove(key);
            }
          }
        }
      }
    }, chunkX, chunkZ);
    if (!submitted) {
      synchronized (pendingKeys) {
        pendingKeys.remove(key);
      }
    }
    return submitted;
  }

  public boolean buildMeshFromWorldInteractive(int chunkX, int chunkY, int chunkZ) {
    return buildMeshFromWorld(chunkX, chunkY, chunkZ, true, true);
  }

  public void clear() {
    clearAllMeshes();
  }

  public int getTotalVertexCount() {
    return vertexCountAtomic.get();
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

  public boolean hasMeshIgnoreDirty(int cx, int cy, int cz) {
    long key = packChunkKey(cx, cy, cz);
    synchronized (meshCache) {
      return meshCache.containsKey(key);
    }
  }

  public boolean isBuildPending(int cx, int cy, int cz) {
    long key = packChunkKey(cx, cy, cz);
    synchronized (pendingKeys) {
      return pendingKeys.contains(key);
    }
  }

  public boolean wasHorizontalNeighborMissingAtBuild(int cx, int cy, int cz,
      int missingBit) {
    long key = packChunkKey(cx, cy, cz);
    synchronized (meshCache) {
      ChunkMeshData mesh = meshCache.get(key);
      return mesh != null && (mesh.missingNeighborMask & missingBit) != 0;
    }
  }

  private static byte computeMissingNeighborMask(ClientLevel world, int chunkX,
      int chunkZ) {
    if (world == null) {
      return 0;
    }
    var source = world.getChunkSource();
    byte mask = 0;
    if (source.getChunkNow(chunkX - 1, chunkZ) == null) {
      mask |= NEIGHBOR_MISSING_MINUS_X;
    }
    if (source.getChunkNow(chunkX + 1, chunkZ) == null) {
      mask |= NEIGHBOR_MISSING_PLUS_X;
    }
    if (source.getChunkNow(chunkX, chunkZ - 1) == null) {
      mask |= NEIGHBOR_MISSING_MINUS_Z;
    }
    if (source.getChunkNow(chunkX, chunkZ + 1) == null) {
      mask |= NEIGHBOR_MISSING_PLUS_Z;
    }
    return mask;
  }

  public void markDirty(int cx, int cy, int cz) {
    long key = packChunkKey(cx, cy, cz);
    boolean newlyDirty;
    synchronized (dirtyKeys) {
      newlyDirty = dirtyKeys.add(key);
    }
    if (!newlyDirty) {
      return;
    }
    synchronized (emptyKeys) {
      emptyKeys.remove(key);
    }
    synchronized (pendingKeys) {
      pendingKeys.remove(key);
    }
    synchronized (dirtyGeneration) {
      dirtyGeneration.put(key, dirtyGeneration.get(key) + 1L);
    }
  }

  public void markAllDirty() {
    invalidateThreadLocalCaches();
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
    globalBuildGeneration.incrementAndGet();
    synchronized (dirtyGeneration) {
      for (long k : keys)
        dirtyGeneration.put(k, dirtyGeneration.get(k) + 1L);
      for (long k : emptyArr)
        dirtyGeneration.put(k, dirtyGeneration.get(k) + 1L);
    }
  }

  public void removeMesh(int cx, int cy, int cz) {
    long key = packChunkKey(cx, cy, cz);
    ChunkMeshData old;
    synchronized (dirtyGeneration) {
      dirtyGeneration.put(key, dirtyGeneration.get(key) + 1L);
      synchronized (meshCache) {
        old = meshCache.remove(key);
      }
    }
    if (old != null) {
      NativeBridge.nUnregisterChunkMesh(cx, cy, cz);
      NativeBridge.nDestroyBuffer(old.bufferHandle);
      meshCountAtomic.decrementAndGet();
      vertexCountAtomic.addAndGet(-old.quadCount * 4);
    }
    synchronized (emptyKeys) {
      emptyKeys.remove(key);
    }
    synchronized (dirtyKeys) {
      dirtyKeys.remove(key);
    }
    meshUpdateGeneration.incrementAndGet();
  }

  public void clearAllMeshes() {
    invalidateThreadLocalCaches();
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
    lodTierOverrides.clear();
    globalBuildGeneration.incrementAndGet();
    synchronized (batchRegData) {
      batchRegCount = 0;
    }
    MetalLogger.info("mesher data cleared (%d).", count);
  }

  public int getMeshCount() {
    return meshCountAtomic.get();
  }

  public int getPendingCount() {
    synchronized (pendingKeys) {
      return pendingKeys.size();
    }
  }

  public int getBuilderActiveCount() {
    return (immediatePool != null ? immediatePool.getActiveCount() : 0) +
        (backgroundPool != null ? backgroundPool.getActiveCount() : 0);
  }

  public int getBuilderQueueDepth() {
    return (immediatePool != null ? immediatePool.getQueue().size() : 0) +
        (backgroundPool != null ? backgroundPool.getQueue().size() : 0);
  }

  public int getInstantActiveCount() {
    return getBuilderActiveCount();
  }

  public int getInstantQueueDepth() {
    return getBuilderQueueDepth();
  }

  public int getInteractiveActiveCount() {
    return immediatePool != null ? immediatePool.getActiveCount() : 0;
  }

  public int getInteractiveQueueDepth() {
    return immediatePool != null ? immediatePool.getQueue().size() : 0;
  }

  public int getBuilderThreadCount() {
    return (immediatePool != null ? immediatePool.getCorePoolSize() : 0) +
        (backgroundPool != null ? backgroundPool.getCorePoolSize() : 0);
  }

  public void noteSectionAvailable(int cx, int cy, int cz) {
    long key = packChunkKey(cx, cy, cz);
    long now = System.nanoTime();
    synchronized (pendingVisibleSectionNanos) {
      if (!pendingVisibleSectionNanos.containsKey(key)) {
        pendingVisibleSectionNanos.put(key, now);
      }
    }
  }

  public long getSectionAvailableNanos(int cx, int cy, int cz) {
    long key = packChunkKey(cx, cy, cz);
    synchronized (pendingVisibleSectionNanos) {
      return pendingVisibleSectionNanos.getOrDefault(key, 0L);
    }
  }

  public void noteBlockUpdate(int cx, int cy, int cz) {
    long key = packChunkKey(cx, cy, cz);
    synchronized (pendingBlockUpdateNanos) {
      pendingBlockUpdateNanos.put(key, System.nanoTime());
    }
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

  public void flushMeshRegistrations() {
    int toFlush;
    synchronized (batchRegData) {
      toFlush = batchRegCount;
      if (toFlush <= 0)
        return;
      NativeBridge.nRegisterChunkMeshBatch(toFlush, batchRegData);
      batchRegCount = 0;
    }
  }

  public int getMeshUpdateGeneration() {
    return meshUpdateGeneration.get();
  }

  public Iterable<ChunkMeshData> getAllMeshes() {
    refreshCachedMeshSnapshot();
    return cachedMeshSnapshot;
  }

  public int getMeshSnapshotSize() {
    refreshCachedMeshSnapshot();
    return cachedMeshSnapshot.size();
  }

  public ChunkMeshData getMeshSnapshotAt(int index) {
    refreshCachedMeshSnapshot();
    return index >= 0 && index < cachedMeshSnapshot.size()
        ? cachedMeshSnapshot.get(index)
        : null;
  }

  private void refreshCachedMeshSnapshot() {
    int currentGen = meshUpdateGeneration.get();
    if (cachedSnapshotGen != currentGen) {
      synchronized (meshCache) {
        cachedMeshSnapshot.clear();
        cachedMeshSnapshot.addAll(meshCache.values());
      }
      cachedSnapshotGen = currentGen;
    }
  }

  private static long packChunkKey(int x, int y, int z) {
    return ((long) (x & 0x3FFFFF) << 42) | ((long) (y & 0xFFFFF) << 22) |
        (z & 0x3FFFFF);
  }

  private static int roundToSizeClass(int size) {
    if (size <= 8192)
      return 8192;
    if (size <= 16384)
      return 16384;
    if (size <= 32768)
      return 32768;
    if (size <= 65536)
      return 65536;
    if (size <= 131072)
      return 131072;
    if (size <= 262144)
      return 262144;
    return (size + 255) & ~255;
  }

  private static final class PrioritizedMeshTask implements Runnable, Comparable<PrioritizedMeshTask> {
    final int priority;
    final long sequence;
    final Runnable task;
    private static final java.util.concurrent.atomic.AtomicLong TASK_SEQUENCE = new java.util.concurrent.atomic.AtomicLong();

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

  private static final int IMMEDIATE_QUEUE_CHUNK_RANGE = 8;

  private boolean submitMeshTask(int priority, Runnable task, int chunkX, int chunkZ) {
    boolean isImmediate;
    if (priority == 0) {
      isImmediate = true;
    } else {
      Minecraft mc = Minecraft.getInstance();
      if (priority == 1 && mc != null && mc.player != null) {
        int pcx = mc.player.chunkPosition().x();
        int pcz = mc.player.chunkPosition().z();
        int dx = Math.abs(chunkX - pcx);
        int dz = Math.abs(chunkZ - pcz);
        int chunkDist = Math.max(dx, dz);
        isImmediate = chunkDist <= IMMEDIATE_QUEUE_CHUNK_RANGE;
      } else {
        isImmediate = false;
      }
    }
    BuildBudgetEstimator estimator = PerformanceController.getBudgetEstimator();
    int immediateCap = estimator != null ? estimator.recommendedInFlightFor(0) : 64;
    if (isImmediate && getImmediateInFlight() >= immediateCap) {
      isImmediate = false;
    }
    int backgroundCap = estimator != null ? estimator.recommendedInFlightFor(1) : 256;
    if (!isImmediate && getBackgroundInFlight() >= backgroundCap) {
      return false;
    }
    PrioritizedMeshTask ptask = new PrioritizedMeshTask(priority, task);
    if (isImmediate) {
      immediatePool.execute(ptask);
    } else {
      backgroundPool.execute(ptask);
    }
    return true;
  }

  private int getImmediateInFlight() {
    return immediatePool.getActiveCount() + immediatePool.getQueue().size();
  }

  private int getBackgroundInFlight() {
    return backgroundPool.getActiveCount() + backgroundPool.getQueue().size();
  }

  private boolean isTaskCancelled(long key, long generation, long globalGeneration) {
    if (globalBuildGeneration.get() != globalGeneration) {
      return true;
    }
    synchronized (dirtyGeneration) {
      return dirtyGeneration.get(key) != generation;
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

  private void removeEmptyMesh(long key, int chunkX, int chunkY, int chunkZ,
      long generation, long globalGeneration) {
    ChunkMeshData old;
    synchronized (dirtyGeneration) {
      if (globalBuildGeneration.get() != globalGeneration ||
          dirtyGeneration.get(key) != generation) {
        return;
      }
      synchronized (meshCache) {
        old = meshCache.remove(key);
      }
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
  }

  private static final class SectionSnapshot {
    final boolean valid;
    final boolean empty;
    final int[] paddedBlockStates;
    final byte[] paddedLight;
    final byte[] paddedOcclusion;
    final byte[] paddedShade;
    final byte[] paddedEmission;
    final int[] biomeTints;

    SectionSnapshot(boolean valid, boolean empty,
        int[] paddedBlockStates, byte[] paddedLight, byte[] paddedOcclusion,
        byte[] paddedShade, byte[] paddedEmission, int[] biomeTints) {
      this.valid = valid;
      this.empty = empty;
      this.paddedBlockStates = paddedBlockStates;
      this.paddedLight = paddedLight;
      this.paddedOcclusion = paddedOcclusion;
      this.paddedShade = paddedShade;
      this.paddedEmission = paddedEmission;
      this.biomeTints = biomeTints;
    }
  }

  private static final class SnapshotData {
    final int[] paddedBlockStates = new int[PADDED_VOLUME];
    final byte[] paddedLight = new byte[PADDED_VOLUME];
    final byte[] paddedOcclusion = new byte[PADDED_VOLUME];
    final byte[] paddedShade = new byte[PADDED_VOLUME];
    final byte[] paddedEmission = new byte[PADDED_VOLUME];
    final int[] biomeTints = new int[SECTION_SIZE * SECTION_SIZE * SECTION_SIZE * BIOME_TINT_SLOTS];
  }

  private static final ThreadLocal<SnapshotData> SNAPSHOT_POOL = ThreadLocal.withInitial(SnapshotData::new);

  private static final class MeshBuildContext {
    final BlockStateModelSet blockModels;
    final int buildPlayerCX, buildPlayerCY, buildPlayerCZ;
    final TextureAtlasSprite waterStillSprite;
    final TextureAtlasSprite waterFlowingSprite;
    final TextureAtlasSprite lavaStillSprite;
    final TextureAtlasSprite lavaFlowingSprite;
    final TextureAtlasSprite grassSideOverlaySprite;
    final ClientLevel world;
    final float[] faceShade;

    MeshBuildContext(BlockStateModelSet blockModels, int buildPlayerCX, int buildPlayerCY, int buildPlayerCZ,
        TextureAtlasSprite waterStillSprite, TextureAtlasSprite waterFlowingSprite,
        TextureAtlasSprite lavaStillSprite, TextureAtlasSprite lavaFlowingSprite,
        TextureAtlasSprite grassSideOverlaySprite, ClientLevel world,
        float[] faceShade) {
      this.blockModels = blockModels;
      this.buildPlayerCX = buildPlayerCX;
      this.buildPlayerCY = buildPlayerCY;
      this.buildPlayerCZ = buildPlayerCZ;
      this.waterStillSprite = waterStillSprite;
      this.waterFlowingSprite = waterFlowingSprite;
      this.lavaStillSprite = lavaStillSprite;
      this.lavaFlowingSprite = lavaFlowingSprite;
      this.grassSideOverlaySprite = grassSideOverlaySprite;
      this.world = world;
      this.faceShade = faceShade;
    }
  }

  private MeshBuildContext captureBuildContext(ClientLevel world, int chunkX, int chunkY, int chunkZ) {
    Minecraft mc = Minecraft.getInstance();
    BlockStateModelSet blockModels = null;
    int buildPCX = 0, buildPCY = 0, buildPCZ = 0;
    TextureAtlasSprite waterStill = null;
    TextureAtlasSprite waterFlow = null;
    TextureAtlasSprite lavaStill = null;
    TextureAtlasSprite lavaFlow = null;
    TextureAtlasSprite grassSideOverlay = null;
    if (mc != null) {
      if (mc.getModelManager() != null) {
        blockModels = mc.getModelManager().getBlockStateModelSet();
      }
      if (mc.player != null) {
        buildPCX = mc.player.chunkPosition().x();
        buildPCZ = mc.player.chunkPosition().z();
        buildPCY = (int) Math.floor(mc.player.getY()) >> 4;
      }
      waterStill = getFluidSprite(mc, net.minecraft.world.level.material.Fluids.WATER, false);
      waterFlow = getFluidSprite(mc, net.minecraft.world.level.material.Fluids.WATER, true);
      lavaStill = getFluidSprite(mc, net.minecraft.world.level.material.Fluids.LAVA, false);
      lavaFlow = getFluidSprite(mc, net.minecraft.world.level.material.Fluids.LAVA, true);
      grassSideOverlay = getAtlasSprite(mc,
          Identifier.fromNamespaceAndPath("minecraft", "block/grass_block_side_overlay"));
    }
    float[] faceShade = new float[6];
    for (Direction direction : ALL_DIRECTIONS) {
      int index = direction.get3DDataValue();
      if (index < faceShade.length) {
        faceShade[index] = world.cardinalLighting().byFace(direction);
      }
    }
    return new MeshBuildContext(blockModels, buildPCX, buildPCY, buildPCZ,
        waterStill, waterFlow, lavaStill, lavaFlow, grassSideOverlay, world,
        faceShade);
  }

  private static int getGrassTint(ClientLevel world, BlockPos pos) {
    if (world == null) {
      return net.minecraft.world.level.GrassColor.getDefaultColor();
    }
    try {
      return net.minecraft.client.renderer.BiomeColors.getAverageGrassColor(world, pos);
    } catch (RuntimeException ignored) {
      return net.minecraft.world.level.GrassColor.getDefaultColor();
    }
  }

  private static TextureAtlasSprite getAtlasSprite(Minecraft mc, Identifier id) {
    if (mc == null || mc.getTextureManager() == null) {
      return null;
    }
    AbstractTexture atlasTexture = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
    return atlasTexture instanceof TextureAtlas atlas ? atlas.getSprite(id) : null;
  }

  private static TextureAtlasSprite getFluidSprite(Minecraft mc, net.minecraft.world.level.material.Fluid fluid,
      boolean flowing) {
    if (mc == null || mc.getTextureManager() == null) {
      return null;
    }
    AbstractTexture atlasTexture = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
    if (!(atlasTexture instanceof TextureAtlas atlas)) {
      return null;
    }
    boolean lava = fluid == net.minecraft.world.level.material.Fluids.LAVA ||
        fluid == net.minecraft.world.level.material.Fluids.FLOWING_LAVA;
    Identifier id;
    if (lava) {
      id = flowing
          ? Identifier.fromNamespaceAndPath("minecraft", "block/lava_flow")
          : Identifier.fromNamespaceAndPath("minecraft", "block/lava_still");
    } else {
      id = flowing
          ? Identifier.fromNamespaceAndPath("minecraft", "block/water_flow")
          : Identifier.fromNamespaceAndPath("minecraft", "block/water_still");
    }
    return atlas.getSprite(id);
  }

  private static LevelChunkSection resolveSection(ClientLevel world, int chunkX, int chunkY, int chunkZ) {
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

  private static BlockState getBorderBlockState(LevelChunk[] neighborChunks, LevelChunk centerChunk,
      int chunkX, int chunkZ, int chunkY, int wx, int wy, int wz,
      ClientLevel world, BlockPos.MutableBlockPos mutablePos) {
    int ncx = wx >> 4;
    int ncz = wz >> 4;
    int dx = ncx - chunkX;
    int dz = ncz - chunkZ;
    if (dx >= -1 && dx <= 1 && dz >= -1 && dz <= 1) {
      LevelChunk nc = neighborChunks[(dz + 1) * 3 + (dx + 1)];
      if (nc != null) {
        try {
          int sectionIdx = nc.getSectionIndexFromSectionY(wy >> 4);
          LevelChunkSection[] sections = nc.getSections();
          if (sectionIdx >= 0 && sectionIdx < sections.length) {
            LevelChunkSection sec = sections[sectionIdx];
            if (sec != null && !sec.hasOnlyAir()) {
              return sec.getStates().get(wx & 15, wy & 15, wz & 15);
            }
            if (sec != null) {
              return Blocks.AIR.defaultBlockState();
            }
          }
        } catch (Exception ignored) {
        }
      } else {
        return Blocks.AIR.defaultBlockState();
      }
    }
    mutablePos.set(wx, wy, wz);
    try {
      return world.getBlockState(mutablePos);
    } catch (Exception e) {
      return Blocks.AIR.defaultBlockState();
    }
  }

  private SectionSnapshot captureSectionSnapshot(ClientLevel world, int chunkX, int chunkY, int chunkZ,
      int lodTier) {
    if (world == null) {
      return new SectionSnapshot(false, false, null, null, null, null, null, null);
    }
    LevelChunk chunk = world.getChunkSource().getChunkNow(chunkX, chunkZ);
    if (chunk == null) {
      return new SectionSnapshot(false, false, null, null, null, null, null, null);
    }
    int sectionIdx = chunk.getSectionIndexFromSectionY(chunkY);
    LevelChunkSection[] chunkSections = chunk.getSections();
    if (sectionIdx < 0 || sectionIdx >= chunkSections.length) {
      return new SectionSnapshot(false, false, null, null, null, null, null, null);
    }
    LevelChunkSection section = chunkSections[sectionIdx];
    if (section == null || section.hasOnlyAir()) {
      return new SectionSnapshot(true, true, null, null, null, null, null, null);
    }

    SnapshotData data = SNAPSHOT_POOL.get();
    int[] paddedBlockStates = data.paddedBlockStates;
    byte[] paddedLight = data.paddedLight;
    byte[] paddedOcclusion = data.paddedOcclusion;
    byte[] paddedShade = data.paddedShade;
    byte[] paddedEmission = data.paddedEmission;
    int[] biomeTints = data.biomeTints;
    boolean hasAnyBlock = false;

    Object2IntOpenHashMap<BlockState> bsIdCache = BS_ID_CACHE.get();
    BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
    net.minecraft.client.color.block.BlockColors blockColors = Minecraft.getInstance().getBlockColors();

    int baseX = chunkX * 16;
    int baseY = chunkY * 16;
    int baseZ = chunkZ * 16;

    net.minecraft.world.level.chunk.PalettedContainer<BlockState> localStates = section.getStates();

    LevelChunk[] neighborChunks = new LevelChunk[9];
    var chunkSource = world.getChunkSource();
    for (int dz = -1; dz <= 1; dz++) {
      for (int dx = -1; dx <= 1; dx++) {
        if (dx == 0 && dz == 0) {
          neighborChunks[(dz + 1) * 3 + (dx + 1)] = chunk;
        } else {
          neighborChunks[(dz + 1) * 3 + (dx + 1)] =
              chunkSource.getChunkNow(chunkX + dx, chunkZ + dz);
        }
      }
    }

    net.minecraft.world.level.lighting.LayerLightEventListener blockLightListener = null;
    net.minecraft.world.level.lighting.LayerLightEventListener skyLightListener = null;
    net.minecraft.world.level.chunk.DataLayer[] blockLightLayers = null;
    net.minecraft.world.level.chunk.DataLayer[] skyLightLayers = null;
    boolean[] lightLayerResolved = null;

    blockLightListener = world.getChunkSource().getLightEngine()
        .getLayerListener(LightLayer.BLOCK);
    skyLightListener = world.getChunkSource().getLightEngine()
        .getLayerListener(LightLayer.SKY);
    blockLightLayers = new net.minecraft.world.level.chunk.DataLayer[27];
    skyLightLayers = new net.minecraft.world.level.chunk.DataLayer[27];
    lightLayerResolved = new boolean[27];

    boolean checkStaleLight = false;
    int snapshotSkyDarken = 0;
    try {
      if (world.dimensionType().hasSkyLight()) {
        snapshotSkyDarken = world.getSkyDarken();
        checkStaleLight = snapshotSkyDarken <= 0;
      }
    } catch (Exception ignored) {
      checkStaleLight = false;
    }

    boolean coarseTint = lodTier >= 2;
    int[] coarseTintState = coarseTint ? new int[64] : null;
    int[] coarseTint0 = coarseTint ? new int[64] : null;
    int[] coarseTint1 = coarseTint ? new int[64] : null;
    int[] coarseTint2 = coarseTint ? new int[64] : null;
    int[] coarseTint3 = coarseTint ? new int[64] : null;
    boolean[] coarseTintInit = coarseTint ? new boolean[64] : null;

    for (int py = 0; py < PADDED_SIZE; py++) {
      for (int pz = 0; pz < PADDED_SIZE; pz++) {
        for (int px = 0; px < PADDED_SIZE; px++) {
          int wx = baseX + px - PADDED_RADIUS;
          int wy = baseY + py - PADDED_RADIUS;
          int wz = baseZ + pz - PADDED_RADIUS;
          int pIdx = (py * PADDED_SIZE + pz) * PADDED_SIZE + px;

          mutablePos.set(wx, wy, wz);
          BlockState state;
          boolean inner = px >= PADDED_RADIUS && px < PADDED_RADIUS + SECTION_SIZE
              && py >= PADDED_RADIUS && py < PADDED_RADIUS + SECTION_SIZE
              && pz >= PADDED_RADIUS && pz < PADDED_RADIUS + SECTION_SIZE;
          if (inner) {
            try {
              state = localStates.get(px - PADDED_RADIUS, py - PADDED_RADIUS,
                  pz - PADDED_RADIUS);
            } catch (Exception e) {
              state = world.getBlockState(mutablePos);
            }
          } else {
            state = getBorderBlockState(neighborChunks, chunk, chunkX, chunkZ,
                chunkY, wx, wy, wz, world, mutablePos);
          }

          int stateId = 0;
          if (!state.isAir()) {
            hasAnyBlock = true;
            int cachedId = bsIdCache.getInt(state);
            if (cachedId != -1) {
              stateId = cachedId;
            } else {
              stateId = Block.getId(state);
              if (bsIdCache.size() < BS_ID_CACHE_CAP) {
                bsIdCache.put(state, stateId);
              }
            }
          }
          paddedBlockStates[pIdx] = stateId;
          if (stateId == 0) {
            paddedOcclusion[pIdx] = 0;
            paddedShade[pIdx] = (byte) 255;
            paddedEmission[pIdx] = 0;
          } else {
            paddedOcclusion[pIdx] = (byte) (state.isViewBlocking(world, mutablePos)
                && state.getLightDampening() != 0 ? 1 : 0);
            float shade = Math.max(0.0f, Math.min(1.0f,
                state.getShadeBrightness(world, mutablePos)));
            paddedShade[pIdx] = (byte) Math.round(shade * 255.0f);
            paddedEmission[pIdx] = (byte) (state.emissiveRendering(world, mutablePos) ? 1 : 0);
          }

          int sectionX = wx >> 4;
          int sectionY = wy >> 4;
          int sectionZ = wz >> 4;
          int layerIdx = ((sectionY - chunkY + 1) * 3 + (sectionZ - chunkZ + 1)) * 3 +
              (sectionX - chunkX + 1);
          int bl;
          int sl;
          if (layerIdx < 0 || layerIdx >= 27) {
            bl = blockLightListener.getLightValue(mutablePos);
            sl = skyLightListener.getLightValue(mutablePos);
          } else {
            if (!lightLayerResolved[layerIdx]) {
              net.minecraft.core.SectionPos sectionPos = net.minecraft.core.SectionPos.of(sectionX, sectionY,
                  sectionZ);
              blockLightLayers[layerIdx] = blockLightListener.getDataLayerData(sectionPos);
              skyLightLayers[layerIdx] = skyLightListener.getDataLayerData(sectionPos);
              lightLayerResolved[layerIdx] = true;
            }
            net.minecraft.world.level.chunk.DataLayer blockLayer = blockLightLayers[layerIdx];
            net.minecraft.world.level.chunk.DataLayer skyLayer = skyLightLayers[layerIdx];
            bl = blockLayer != null ? blockLayer.get(wx & 15, wy & 15, wz & 15)
                : blockLightListener.getLightValue(mutablePos);
            sl = skyLayer != null ? skyLayer.get(wx & 15, wy & 15, wz & 15)
                : skyLightListener.getLightValue(mutablePos);
          }
          if (checkStaleLight && bl == 0 && sl == 0 && stateId == 0
              && px > 0 && px < PADDED_SIZE - 1
              && py > 0 && py < PADDED_SIZE - 1
              && pz > 0 && pz < PADDED_SIZE - 1
              && paddedBlockStates[pIdx - 1] == 0
              && paddedBlockStates[pIdx + 1] == 0
              && paddedBlockStates[pIdx - PADDED_SIZE] == 0
              && paddedBlockStates[pIdx + PADDED_SIZE] == 0
              && paddedBlockStates[pIdx - PADDED_SIZE * PADDED_SIZE] == 0
              && paddedBlockStates[pIdx + PADDED_SIZE * PADDED_SIZE] == 0) {
            int liveBl = blockLightListener.getLightValue(mutablePos);
            int liveSl = skyLightListener.getLightValue(mutablePos);
            if (liveBl != 0 || liveSl != 0) {
              bl = liveBl;
              sl = liveSl;
            }
          }
          paddedLight[pIdx] = (byte) ((bl & 0xF) | ((sl & 0xF) << 4));

          if (inner) {
            int x = px - PADDED_RADIUS;
            int y = py - PADDED_RADIUS;
            int z = pz - PADDED_RADIUS;
            int tintBase = (y * 256 + z * 16 + x) * BIOME_TINT_SLOTS;
            int coarseIdx = -1;
            if (coarseTint && stateId != 0) {
              coarseIdx = ((y >> 2) * 4 + (z >> 2)) * 4 + (x >> 2);
              if (coarseTintInit[coarseIdx] && coarseTintState[coarseIdx] == stateId) {
                biomeTints[tintBase] = coarseTint0[coarseIdx];
                biomeTints[tintBase + 1] = coarseTint1[coarseIdx];
                biomeTints[tintBase + 2] = coarseTint2[coarseIdx];
                biomeTints[tintBase + 3] = coarseTint3[coarseIdx];
                continue;
              }
            }
            biomeTints[tintBase] = 0xFFFFFF;
            biomeTints[tintBase + 1] = 0xFFFFFF;
            biomeTints[tintBase + 2] = 0xFFFFFF;
            biomeTints[tintBase + 3] = 0xFFFFFF;
            if (stateId != 0) {
              mutablePos.set(baseX + x, baseY + y, baseZ + z);
              FluidState fluid = state.getFluidState();
              boolean water = !fluid.isEmpty() &&
                  (fluid.getType() == net.minecraft.world.level.material.Fluids.WATER ||
                      fluid.getType() == net.minecraft.world.level.material.Fluids.FLOWING_WATER);
              boolean lava = !fluid.isEmpty() &&
                  (fluid.getType() == net.minecraft.world.level.material.Fluids.LAVA ||
                      fluid.getType() == net.minecraft.world.level.material.Fluids.FLOWING_LAVA);
              if (water) {
                int tint = 0xFFFFFF;
                try {
                  tint = net.minecraft.client.renderer.BiomeColors.getAverageWaterColor(world, mutablePos);
                } catch (Exception ignored) {
                }
                biomeTints[tintBase] = tint == -1 ? 0xFFFFFF : tint;
              } else if (state.getBlock() == Blocks.GRASS_BLOCK) {
                int tint = 0xFFFFFF;
                try {
                  net.minecraft.client.color.block.BlockTintSource source = getCachedTintSource(
                      blockColors, state, 0);
                  if (source != null) {
                    tint = source.colorInWorld(state, world, mutablePos);
                  } else {
                    tint = getGrassTint(world, mutablePos);
                  }
                } catch (Exception ignored) {
                }
                biomeTints[tintBase] = tint == -1 ? 0xFFFFFF : tint;
              } else if (!lava) {
                byte mask = getTintSlotMask(state, blockColors);
                int remaining = mask;
                while (remaining != 0) {
                  int tintIndex = Integer.numberOfTrailingZeros(remaining);
                  remaining &= remaining - 1;
                  int tint = 0xFFFFFF;
                  try {
                    net.minecraft.client.color.block.BlockTintSource source = getCachedTintSource(
                        blockColors, state, tintIndex);
                    if (source != null) {
                      tint = source.colorInWorld(state, world, mutablePos);
                    }
                  } catch (Exception ignored) {
                  }
                  biomeTints[tintBase + tintIndex] = tint == -1 ? 0xFFFFFF : tint;
                }
              }
              if (coarseIdx >= 0) {
                coarseTintInit[coarseIdx] = true;
                coarseTintState[coarseIdx] = stateId;
                coarseTint0[coarseIdx] = biomeTints[tintBase];
                coarseTint1[coarseIdx] = biomeTints[tintBase + 1];
                coarseTint2[coarseIdx] = biomeTints[tintBase + 2];
                coarseTint3[coarseIdx] = biomeTints[tintBase + 3];
              }
            }
          }
        }
      }
    }

    if (!hasAnyBlock) {
      return new SectionSnapshot(true, true, null, null, null, null, null, null);
    }

    return new SectionSnapshot(true, false, paddedBlockStates, paddedLight,
        paddedOcclusion, paddedShade, paddedEmission, biomeTints);
  }

  private static final ThreadLocal<Object2IntOpenHashMap<BlockState>> BS_ID_CACHE = ThreadLocal.withInitial(() -> {
    Object2IntOpenHashMap<BlockState> m = new Object2IntOpenHashMap<>(8192);
    m.defaultReturnValue(-1);
    return m;
  });
  private static final int BS_ID_CACHE_CAP = 32768;

  private static final ThreadLocal<RandomSource> REUSABLE_RANDOM = ThreadLocal
      .withInitial(() -> RandomSource.create(0));

  private static final int STATE_BY_ID_CACHE_CAP = 65536;
  private static final ThreadLocal<it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<BlockState>> STATE_BY_ID_CACHE = ThreadLocal
      .withInitial(() -> new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(4096));
  private static final ThreadLocal<IdentityHashMap<BlockState, BlockStateModel>> MODEL_CACHE = ThreadLocal
      .withInitial(IdentityHashMap::new);
  private static final int MODEL_CACHE_CAP = 32768;
  private static final ThreadLocal<IdentityHashMap<BlockState, net.minecraft.client.color.block.BlockTintSource[]>> TINT_SOURCE_CACHE = ThreadLocal
      .withInitial(IdentityHashMap::new);
  private static final ThreadLocal<IdentityHashMap<BlockState, Byte>> TINT_SLOT_MASK_CACHE = ThreadLocal
      .withInitial(IdentityHashMap::new);
  private static final ThreadLocal<java.util.ArrayList<BlockStateModelPart>> PARTS_POOL = ThreadLocal
      .withInitial(java.util.ArrayList::new);

  private static final java.util.concurrent.atomic.AtomicInteger THREAD_LOCAL_GENERATION = new java.util.concurrent.atomic.AtomicInteger();
  private static final ThreadLocal<Integer> THREAD_LOCAL_GEN = ThreadLocal.withInitial(() -> 0);

  public static void invalidateThreadLocalCaches() {
    THREAD_LOCAL_GENERATION.incrementAndGet();
  }

  private static void refreshThreadLocalCachesIfNeeded() {
    int gen = THREAD_LOCAL_GENERATION.get();
    if (THREAD_LOCAL_GEN.get() != gen) {
      STATE_BY_ID_CACHE.remove();
      MODEL_CACHE.remove();
      TINT_SOURCE_CACHE.remove();
      TINT_SLOT_MASK_CACHE.remove();
      PARTS_POOL.remove();
      BS_ID_CACHE.remove();
      THREAD_LOCAL_GEN.set(gen);
    }
  }

  private static net.minecraft.client.color.block.BlockTintSource getCachedTintSource(
      net.minecraft.client.color.block.BlockColors blockColors, BlockState state, int tintIndex) {
    if (tintIndex < 0 || tintIndex >= BIOME_TINT_SLOTS) {
      return null;
    }
    IdentityHashMap<BlockState, net.minecraft.client.color.block.BlockTintSource[]> cache = TINT_SOURCE_CACHE
        .get();
    net.minecraft.client.color.block.BlockTintSource[] sources = cache.get(state);
    if (sources == null) {
      sources = new net.minecraft.client.color.block.BlockTintSource[BIOME_TINT_SLOTS];
      if (cache.size() < 65536) {
        cache.put(state, sources);
      }
    }
    net.minecraft.client.color.block.BlockTintSource source = sources[tintIndex];
    if (source == null) {
      source = blockColors.getTintSource(state, tintIndex);
      sources[tintIndex] = source;
    }
    return source;
  }

  private static byte getTintSlotMask(BlockState state,
      net.minecraft.client.color.block.BlockColors blockColors) {
    IdentityHashMap<BlockState, Byte> cache = TINT_SLOT_MASK_CACHE.get();
    Byte cached = cache.get(state);
    if (cached != null) {
      return cached;
    }
    byte mask = 0;
    for (int i = 0; i < BIOME_TINT_SLOTS; i++) {
      try {
        if (blockColors.getTintSource(state, i) != null) {
          mask |= (byte) (1 << i);
        }
      } catch (Exception ignored) {
      }
    }
    if (cache.size() < 65536) {
      cache.put(state, mask);
    }
    return mask;
  }

  private void doMeshBuild(int chunkX, int chunkY, int chunkZ,
      SectionSnapshot snapshot, long key, long generation, long globalGeneration,
      MeshBuildContext context, int lodTier) {
    long buildStart = System.nanoTime();
    try {
      if (isTaskCancelled(key, generation, globalGeneration)) {
        return;
      }

      if (snapshot != null && snapshot.paddedBlockStates != null &&
          isSectionFullyOccluded(snapshot.paddedBlockStates)) {
        removeEmptyMesh(key, chunkX, chunkY, chunkZ, generation, globalGeneration);
        return;
      }

      ByteBuffer vertexBuffer = VERTEX_BUF_POOL.get();
      ByteBuffer waterBuffer = WATER_BUF_POOL.get();
      vertexBuffer.clear();
      waterBuffer.clear();

      MeshBuilder builder = new MeshBuilder(vertexBuffer, waterBuffer, context.blockModels,
          snapshot, context, chunkX, chunkY, chunkZ, lodTier);

      builder.build();

      int opaqueQuadCount = builder.opaqueQuadCount;
      int waterQuadCount = builder.waterQuadCount;
      int quadCount = opaqueQuadCount + waterQuadCount;

      if (quadCount == 0) {
        removeEmptyMesh(key, chunkX, chunkY, chunkZ, generation, globalGeneration);
        return;
      }

      vertexBuffer.flip();
      int[] facingQuadCounts = bucketQuadsByFacing(vertexBuffer, opaqueQuadCount, waterQuadCount);

      if (waterQuadCount > 0) {
        waterBuffer.flip();
        vertexBuffer.limit(vertexBuffer.capacity());
        vertexBuffer.position(opaqueQuadCount * 4 * VERTEX_STRIDE);
        vertexBuffer.put(waterBuffer);
      }
      vertexBuffer.flip();
      long visibilityMask = computeVisibilityMask(snapshot.paddedBlockStates);
      byte faceOcclusionMask = computeFaceOcclusionMask(snapshot.paddedBlockStates);
      int dataLen = quadCount * 4 * VERTEX_STRIDE;

      if (isTaskCancelled(key, generation, globalGeneration)) {
        return;
      }

      int roundedSize = roundToSizeClass(dataLen);
      long oldHintHandle = 0;
      synchronized (meshCache) {
        ChunkMeshData existing = meshCache.get(key);
        if (existing != null) {
          oldHintHandle = existing.bufferHandle;
        }
      }

      long bufferHandle;
      UPLOAD_SEMAPHORE.acquireUninterruptibly();
      try {
        bufferHandle = NativeBridge.nCreateBufferWithHint(
            deviceHandle, roundedSize, NativeMemory.STORAGE_MODE_SHARED, oldHintHandle);
        long uploadStart = System.nanoTime();
        NativeBridge.nUploadBufferDataDirect(bufferHandle, vertexBuffer, 0, dataLen);
        MetalRenderProfiler.getInstance().recordUploadTime(System.nanoTime() - uploadStart);
        MetalRenderProfiler.getInstance().incrementUploadsDone(1);
      } finally {
        UPLOAD_SEMAPHORE.release();
      }

      ChunkMeshData mesh = new ChunkMeshData(bufferHandle, quadCount, chunkX, chunkY, chunkZ,
          context.buildPlayerCX, context.buildPlayerCY, context.buildPlayerCZ,
          visibilityMask, facingQuadCounts, lodTier,
          computeMissingNeighborMask(context.world, chunkX, chunkZ), faceOcclusionMask);
      ChunkMeshData old;
      synchronized (dirtyGeneration) {
        if (isTaskCancelled(key, generation, globalGeneration)) {
          NativeBridge.nDestroyBuffer(bufferHandle);
          return;
        }
        synchronized (meshCache) {
          old = meshCache.put(key, mesh);
        }
      }
      if (old == null) {
        meshCountAtomic.incrementAndGet();
        vertexCountAtomic.addAndGet(mesh.quadCount * 4);
      } else {
        vertexCountAtomic.addAndGet(mesh.quadCount * 4 - old.quadCount * 4);
      }

      int flushCount = -1;
      synchronized (dirtyGeneration) {
        synchronized (batchRegData) {
          int idx = batchRegCount * BATCH_REG_STRIDE;
          batchRegData[idx] = (chunkX & 0xFFFFFFFFL) | ((long) chunkY << 32);
          batchRegData[idx + 1] = (chunkZ & 0xFFFFFFFFL) | ((long) quadCount << 32);
          batchRegData[idx + 2] = bufferHandle;
          batchRegData[idx + 3] = visibilityMask;
          batchRegData[idx + 4] = (opaqueQuadCount & 0xFFFFFFFFL)
              | ((long) (facingQuadCounts.length > 0 ? facingQuadCounts[0] : 0) << 32);
          batchRegData[idx + 5] = ((long) (facingQuadCounts.length > 1 ? facingQuadCounts[1] : 0) & 0xFFFFFFFFL)
              | ((long) (facingQuadCounts.length > 2 ? facingQuadCounts[2] : 0) << 32);
          batchRegData[idx + 6] = ((long) (facingQuadCounts.length > 3 ? facingQuadCounts[3] : 0) & 0xFFFFFFFFL)
              | ((long) (facingQuadCounts.length > 4 ? facingQuadCounts[4] : 0) << 32);
          batchRegData[idx + 7] = ((long) (facingQuadCounts.length > 5 ? facingQuadCounts[5] : 0) & 0xFFFFFFFFL)
              | ((long) (facingQuadCounts.length > 6 ? facingQuadCounts[6] : 0) << 32);
          batchRegData[idx + 8] = lodTier;
          batchRegCount++;
          if (batchRegCount >= BATCH_REG_CAPACITY) {
            flushCount = batchRegCount;
            batchRegCount = 0;
          }
        }
      }
      if (flushCount > 0) {
        NativeBridge.nRegisterChunkMeshBatch(flushCount, batchRegData);
      }
      if (old != null && old.bufferHandle != bufferHandle) {
        NativeBridge.nDestroyBuffer(old.bufferHandle);
      }

      meshUpdateGeneration.incrementAndGet();
      recordVisibleLatency(key);
      MetalRenderProfiler.getInstance().incrementMeshesBuilt(1);
      synchronized (dirtyKeys) {
        dirtyKeys.remove(key);
      }
    } catch (Exception e) {
      java.io.StringWriter sw = new java.io.StringWriter();
      e.printStackTrace(new java.io.PrintWriter(sw));
      MetalLogger.error("mesh fail [%d,%d,%d]: %s\n%s", chunkX, chunkY, chunkZ, e.toString(), sw.toString());
    } finally {
      long buildElapsed = System.nanoTime() - buildStart;
      MetalRenderProfiler.getInstance().recordMeshingTime(buildElapsed);
      synchronized (dirtyGeneration) {
        if (dirtyGeneration.get(key) == generation) {
          synchronized (pendingKeys) {
            pendingKeys.remove(key);
          }
        }
      }
    }
  }

  private static final class MeshBuilder {
    private final ByteBuffer solidBuffer;
    private final ByteBuffer waterBuffer;
    private final BlockStateModelSet blockModels;
    private final SectionSnapshot snapshot;
    private final MeshBuildContext context;
    private final int chunkX, chunkY, chunkZ;
    private final int lodTier;
    private final boolean smoothLightingEnabled;

    int opaqueQuadCount = 0;
    int waterQuadCount = 0;
    private final float[] cornerHeights = new float[4];

    MeshBuilder(ByteBuffer solidBuffer, ByteBuffer waterBuffer, BlockStateModelSet blockModels,
        SectionSnapshot snapshot, MeshBuildContext context, int chunkX, int chunkY, int chunkZ,
        int lodTier) {
      this.solidBuffer = solidBuffer;
      this.waterBuffer = waterBuffer;
      this.blockModels = blockModels;
      this.snapshot = snapshot;
      this.context = context;
      this.chunkX = chunkX;
      this.chunkY = chunkY;
      this.chunkZ = chunkZ;
      this.lodTier = lodTier;
      MetalRenderConfig cfg = MetalRenderClient.getConfig();
      this.smoothLightingEnabled = (cfg == null || cfg.smoothLighting)
          && vanillaSmoothLightingEnabled();
    }

    private static boolean vanillaSmoothLightingEnabled() {
      try {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.options != null && mc.options.ambientOcclusion() != null) {
          Object v = mc.options.ambientOcclusion().get();
          if (v instanceof Boolean b) {
            return b;
          }
        }
      } catch (Exception ignored) {
      }
      return true;
    }

    void build() {
      if (snapshot == null || snapshot.paddedBlockStates == null)
        return;
      RandomSource random = REUSABLE_RANDOM.get();
      BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
      for (int y = 0; y < SECTION_SIZE; y++) {
        for (int z = 0; z < SECTION_SIZE; z++) {
          for (int x = 0; x < SECTION_SIZE; x++) {
            int stateId = getPaddedBlockStateId(x, y, z);
            if (stateId == 0)
              continue;
            BlockState state = getStateById(stateId);
            if (state.isAir())
              continue;

            pos.set(chunkX * 16 + x, chunkY * 16 + y, chunkZ * 16 + z);

            if (state.getRenderShape() == net.minecraft.world.level.block.RenderShape.MODEL) {
              if (blockModels != null) {
                BlockStateModel model = getCachedModel(state);
                if (model != null) {
                  renderBlockModel(model, state, pos, x, y, z, random);
                }
              }
            }

            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
              renderFluid(fluid, pos, x, y, z);
            }
          }
        }
      }
    }

    private int getPaddedBlockStateId(int x, int y, int z) {
      int px = x + PADDED_RADIUS;
      int py = y + PADDED_RADIUS;
      int pz = z + PADDED_RADIUS;
      return snapshot.paddedBlockStates[(py * PADDED_SIZE + pz) * PADDED_SIZE + px];
    }

    private BlockState getPaddedBlockState(int x, int y, int z) {
      int stateId = getPaddedBlockStateId(x, y, z);
      if (stateId == 0)
        return null;
      return getStateById(stateId);
    }

    private BlockState getStateById(int stateId) {
      it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<BlockState> cache = STATE_BY_ID_CACHE
          .get();
      BlockState state = cache.get(stateId);
      if (state == null) {
        try {
          state = Block.stateById(stateId);
        } catch (RuntimeException ignored) {

          state = Blocks.AIR.defaultBlockState();
        }
        if (state == null) {
          state = Blocks.AIR.defaultBlockState();
        }
        if (cache.size() < STATE_BY_ID_CACHE_CAP) {
          cache.put(stateId, state);
        }
      }
      return state;
    }

    private BlockStateModel getCachedModel(BlockState state) {
      IdentityHashMap<BlockState, BlockStateModel> cache = MODEL_CACHE.get();
      BlockStateModel model = cache.get(state);
      if (model == null && !cache.containsKey(state)) {
        try {

          model = blockModels.get(state);
        } catch (RuntimeException ignored) {
          model = null;
        }
        if (cache.size() < MODEL_CACHE_CAP) {
          cache.put(state, model);
        }
      }
      return model;
    }

    private byte getPaddedLight(int x, int y, int z) {
      int px = x + PADDED_RADIUS;
      int py = y + PADDED_RADIUS;
      int pz = z + PADDED_RADIUS;
      return snapshot.paddedLight[(py * PADDED_SIZE + pz) * PADDED_SIZE + px];
    }

    private int getBiomeTint(int x, int y, int z) {
      return getBiomeTint(x, y, z, 0);
    }

    private int getBiomeTint(int x, int y, int z, int tintIndex) {
      int slot = tintIndex >= 0 && tintIndex < BIOME_TINT_SLOTS ? tintIndex : 0;
      return snapshot.biomeTints[(y * 256 + z * 16 + x) * BIOME_TINT_SLOTS + slot];
    }

    private boolean isGrassSideOverlay(TextureAtlasSprite sprite) {
      TextureAtlasSprite overlay = context.grassSideOverlaySprite;
      if (sprite == null) {
        return false;
      }
      try {
        Identifier spriteName = sprite.contents().name();
        if (Identifier.fromNamespaceAndPath("minecraft", "block/grass_block_side_overlay")
            .equals(spriteName)) {
          return true;
        }
        if (overlay != null && spriteName.equals(overlay.contents().name())) {
          return true;
        }
      } catch (RuntimeException ignored) {
      }
      if (overlay == null) {
        return false;
      }
      if (sprite == overlay || sprite.contents() == overlay.contents()) {
        return true;
      }
      return sprite.atlasLocation().equals(overlay.atlasLocation()) &&
          Float.compare(sprite.getU0(), overlay.getU0()) == 0 &&
          Float.compare(sprite.getU1(), overlay.getU1()) == 0 &&
          Float.compare(sprite.getV0(), overlay.getV0()) == 0 &&
          Float.compare(sprite.getV1(), overlay.getV1()) == 0;
    }

    private boolean isCrossLike(BlockState state, BlockStateModel model, RandomSource random,
        BlockPos pos, int lx, int ly, int lz) {
      try {
        if (state.isAir() || state.isSolidRender()) {
          return false;
        }
        if (state.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) {
          return false;
        }
        java.util.ArrayList<BlockStateModelPart> parts = PARTS_POOL.get();
        int savedSize = parts.size();
        try {
          random.setSeed(state.getSeed(pos));
        } catch (RuntimeException ignored) {
        }
        model.collectParts(random, parts);
        try {
          for (int i = savedSize; i < parts.size(); i++) {
            BlockStateModelPart part = parts.get(i);
            if (part == null) {
              continue;
            }
            for (Direction direction : ALL_DIRECTIONS) {
              List<BakedQuad> quads;
              try {
                quads = part.getQuads(direction);
              } catch (RuntimeException ignored) {
                continue;
              }
              if (quads == null || quads.isEmpty()) {
                continue;
              }
              boolean anyKept = false;
              for (BakedQuad quad : quads) {
                if (quad == null) {
                  continue;
                }
                anyKept = true;
                break;
              }
              if (anyKept && !shouldCullFace(lx, ly, lz, direction, state)) {
                return false;
              }
            }
          }
        } finally {
          while (parts.size() > savedSize) {
            parts.remove(parts.size() - 1);
          }
        }
        return true;
      } catch (Exception ignored) {
        return false;
      }
    }

    private void renderBlockModel(BlockStateModel model, BlockState state, BlockPos pos,
        int lx, int ly, int lz, RandomSource random) {

      boolean skipDetailQuads = lodTier >= 2;
      boolean blockEmitsLight;
      try {
        blockEmitsLight = state.getLightEmission() != 0;
      } catch (RuntimeException ignored) {
        blockEmitsLight = false;
      }
      boolean crossLike = isCrossLike(state, model, random, pos, lx, ly, lz);
      try {
        random.setSeed(state.getSeed(pos));
        java.util.ArrayList<BlockStateModelPart> parts = PARTS_POOL.get();
        parts.clear();
        model.collectParts(random, parts);
        for (BlockStateModelPart part : parts) {
          if (part == null) {
            continue;
          }
          boolean partAO;
          try {
            partAO = part.useAmbientOcclusion();
          } catch (RuntimeException ignored) {
            partAO = true;
          }
          boolean forceFlat = crossLike || !partAO || blockEmitsLight;
          for (Direction direction : ALL_DIRECTIONS) {
            List<BakedQuad> quads;
            try {
              quads = part.getQuads(direction);
            } catch (RuntimeException ignored) {
              continue;
            }
            if (quads == null || quads.isEmpty() || shouldCullFace(lx, ly, lz, direction, state))
              continue;
            for (BakedQuad quad : quads) {
              if (quad != null) {
                emitBakedQuad(quad, lx, ly, lz, state, false, forceFlat, crossLike);
              }
            }
          }
          try {
            List<BakedQuad> noCull = skipDetailQuads ? null : part.getQuads(null);
            if (noCull != null) {
              for (BakedQuad quad : noCull) {
                if (quad != null) {
                  emitBakedQuad(quad, lx, ly, lz, state, false, true, crossLike);
                }
              }
            }
          } catch (RuntimeException ignored) {

          }
        }
      } catch (RuntimeException ignored) {

      }
    }

    private boolean shouldCullFace(int x, int y, int z, Direction direction, BlockState state) {
      int nx = x + direction.getStepX();
      int ny = y + direction.getStepY();
      int nz = z + direction.getStepZ();
      BlockState neighbor = getPaddedBlockState(nx, ny, nz);
      if (neighbor == null)
        return false;
      if (neighbor.isAir())
        return false;

      boolean currentIsLeaves = state.getBlock() instanceof LeavesBlock;
      boolean neighborIsLeaves = neighbor.getBlock() instanceof LeavesBlock;
      if (currentIsLeaves || neighborIsLeaves) {
        return isOpaqueForCulling(neighbor);
      }

      if (state.skipRendering(neighbor, direction))
        return true;
      return isOpaqueForCulling(neighbor);
    }

    private boolean isOpaqueForCulling(BlockState state) {
      if (state.isSolidRender()) {
        return true;
      }
      if (state.getBlock() instanceof LeavesBlock) {

        if (lodTier >= 1) {
          return true;
        }
        MetalRenderConfig cfg = MetalRenderClient.getConfig();
        return cfg == null || cfg.leafCullingMode == 0;
      }
      return false;
    }

    private void renderFluid(FluidState fluid, BlockPos pos, int lx, int ly, int lz) {
      boolean isWater = fluid.getType() == net.minecraft.world.level.material.Fluids.WATER ||
          fluid.getType() == net.minecraft.world.level.material.Fluids.FLOWING_WATER;
      boolean isLava = fluid.getType() == net.minecraft.world.level.material.Fluids.LAVA ||
          fluid.getType() == net.minecraft.world.level.material.Fluids.FLOWING_LAVA;
      if (!isWater && !isLava) {
        return;
      }

      BlockState above = getPaddedBlockState(lx, ly + 1, lz);
      boolean upVisible = above == null || above.getFluidState().isEmpty();
      BlockState below = getPaddedBlockState(lx, ly - 1, lz);
      boolean downVisible = below == null || below.getFluidState().isEmpty();

      float[] cornerHeights = this.cornerHeights;
      float fluidHeight = sampleFluidHeight(lx, ly, lz, isLava);

      if (lodTier >= 1 || fluidHeight >= 1.0f) {
        if (fluidHeight <= 0.0f) {
          fluidHeight = fluid.getOwnHeight();
        }
        cornerHeights[0] = fluidHeight;
        cornerHeights[1] = fluidHeight;
        cornerHeights[2] = fluidHeight;
        cornerHeights[3] = fluidHeight;
      } else {
        cornerHeights[0] = sampleFluidCornerHeight(lx, ly, lz, 0, 0, isLava);
        cornerHeights[1] = sampleFluidCornerHeight(lx, ly, lz, 1, 0, isLava);
        cornerHeights[2] = sampleFluidCornerHeight(lx, ly, lz, 1, 1, isLava);
        cornerHeights[3] = sampleFluidCornerHeight(lx, ly, lz, 0, 1, isLava);
      }

      byte light = computeFluidLight(lx, ly, lz);
      int fluidColor = isLava ? 0xFFFFFF : getBiomeTint(lx, ly, lz);
      byte r = (byte) ((fluidColor >> 16) & 0xFF);
      byte g = (byte) ((fluidColor >> 8) & 0xFF);
      byte b = (byte) (fluidColor & 0xFF);
      byte a = isLava ? (byte) 0xFF : WATER_ALPHA;

      if (upVisible) {
        float flowAngle = computeFluidFlowAngle(fluid, pos);
        renderFluidTop(lx, ly, lz, cornerHeights, r, g, b, a, light, flowAngle, isLava);
      }

      for (Direction dir : ALL_DIRECTIONS) {
        if (dir.getAxis() == Direction.Axis.Y)
          continue;
        BlockState neighbor = getPaddedBlockState(lx + dir.getStepX(), ly, lz + dir.getStepZ());
        if (neighbor != null && !neighbor.getFluidState().isEmpty())
          continue;
        if (neighbor != null && neighbor.isSolidRender())
          continue;
        renderFluidSide(lx, ly, lz, dir, cornerHeights, r, g, b, a, light, isLava);
      }

      if (downVisible) {
        BlockState downState = getPaddedBlockState(lx, ly - 1, lz);
        if (downState == null || !downState.isSolidRender()) {
          renderFluidBottom(lx, ly, lz, r, g, b, a, light, isLava);
        }
      }
    }

    private byte computeFluidLight(int lx, int ly, int lz) {
      byte self = getPaddedLight(lx, ly, lz);
      byte above = getPaddedLight(lx, ly + 1, lz);
      int block = Math.max(self & 0xF, above & 0xF);
      int sky = Math.max((self >> 4) & 0xF, (above >> 4) & 0xF);
      return (byte) (block | (sky << 4));
    }

    private boolean isWaterFluid(FluidState fs) {
      return fs.getType() == net.minecraft.world.level.material.Fluids.WATER ||
          fs.getType() == net.minecraft.world.level.material.Fluids.FLOWING_WATER;
    }

    private boolean isLavaFluid(FluidState fs) {
      return fs.getType() == net.minecraft.world.level.material.Fluids.LAVA ||
          fs.getType() == net.minecraft.world.level.material.Fluids.FLOWING_LAVA;
    }

    private short mapFluidU(TextureAtlasSprite sprite, float u) {
      if (sprite == null) {
        return (short) (u * 65535f);
      }
      float t = sprite.getU0() + u * (sprite.getU1() - sprite.getU0());
      return (short) (t * 65535f);
    }

    private short mapFluidV(TextureAtlasSprite sprite, float v) {
      if (sprite == null) {
        return (short) (v * 65535f);
      }
      float t = sprite.getV0() + v * (sprite.getV1() - sprite.getV0());
      return (short) (t * 65535f);
    }

    private float sampleFluidHeight(int x, int y, int z, boolean lava) {
      BlockState state = getPaddedBlockState(x, y, z);
      if (state == null)
        return 0.0f;
      FluidState fs = state.getFluidState();
      boolean matchingFluid = lava ? isLavaFluid(fs) : isWaterFluid(fs);
      if (matchingFluid) {
        BlockState above = getPaddedBlockState(x, y + 1, z);
        if (above != null) {
          FluidState aboveFs = above.getFluidState();
          if (lava ? isLavaFluid(aboveFs) : isWaterFluid(aboveFs)) {
            return 1.0f;
          }
        }
        return fs.getOwnHeight();
      }
      return state.isSolid() ? -1.0f : 0.0f;
    }

    private float sampleFluidCornerHeight(int lx, int ly, int lz, int dx, int dz,
        boolean lava) {
      float weightedHeight = 0.0f;
      int weight = 0;
      for (int sx = 0; sx <= 1; sx++) {
        for (int sz = 0; sz <= 1; sz++) {
          int nx = lx + dx + sx - 1;
          int nz = lz + dz + sz - 1;
          float height = sampleFluidHeight(nx, ly, nz, lava);
          if (height >= 1.0f) {
            return 1.0f;
          }
          if (height >= 0.8f) {
            weightedHeight += height * 10.0f;
            weight += 10;
          } else if (height >= 0.0f) {
            weightedHeight += height;
            weight++;
          }
        }
      }
      return weight > 0 ? weightedHeight / weight : 0.0f;
    }

    private float computeFluidFlowAngle(FluidState fluid, BlockPos pos) {
      Vec3 flow = context.world != null
          ? fluid.getFlow(context.world, pos)
          : Vec3.ZERO;
      if (flow.x == 0.0 && flow.z == 0.0) {
        return Float.NaN;
      }
      return (float) Math.atan2(flow.z, flow.x);
    }

    private void renderFluidTop(int lx, int ly, int lz, float[] heights, byte r, byte g, byte b, byte a, byte light,
        float flowAngle, boolean lava) {
      float h0 = heights[0];
      float h1 = heights[1];
      float h2 = heights[2];
      float h3 = heights[3];
      float minH = Math.min(Math.min(h0, h1), Math.min(h2, h3));
      if (minH <= 0.0f)
        return;

      float y0 = ly + h0;
      float y1 = ly + h1;
      float y2 = ly + h2;
      float y3 = ly + h3;

      short px0 = (short) (lx * 256.0f);
      short pz0 = (short) (lz * 256.0f);
      short px1 = (short) ((lx + 1) * 256.0f);
      short pz1 = (short) ((lz + 1) * 256.0f);

      short py0 = (short) (y0 * 256.0f);
      short py1 = (short) (y1 * 256.0f);
      short py2 = (short) (y2 * 256.0f);
      short py3 = (short) (y3 * 256.0f);

      float fu0, fv0, fu1, fv1, fu2, fv2, fu3, fv3;
      if (Float.isNaN(flowAngle)) {
        fu0 = 0.0f;
        fv0 = 0.0f;
        fu1 = 0.0f;
        fv1 = 1.0f;
        fu2 = 1.0f;
        fv2 = 1.0f;
        fu3 = 1.0f;
        fv3 = 0.0f;
      } else {
        float dir = flowAngle - ((float) Math.PI / 2.0f);
        float sin = (float) Math.sin(dir) * 0.25f;
        float cos = (float) Math.cos(dir) * 0.25f;
        fu0 = 0.5f + (-cos - sin);
        fv0 = 0.5f + (-cos + sin);
        fu1 = 0.5f + (-cos + sin);
        fv1 = 0.5f + (cos + sin);
        fu2 = 0.5f + (cos + sin);
        fv2 = 0.5f + (cos - sin);
        fu3 = 0.5f + (cos - sin);
        fv3 = 0.5f + (-cos - sin);
      }

      boolean flowing = !Float.isNaN(flowAngle);
      TextureAtlasSprite topSprite = lava
          ? (flowing ? context.lavaFlowingSprite : context.lavaStillSprite)
          : (flowing ? context.waterFlowingSprite : context.waterStillSprite);
      short u0 = mapFluidU(topSprite, fu0);
      short v0 = mapFluidV(topSprite, fv0);
      short u1 = mapFluidU(topSprite, fu1);
      short v1 = mapFluidV(topSprite, fv1);
      short u2 = mapFluidU(topSprite, fu2);
      short v2 = mapFluidV(topSprite, fv2);
      short u3 = mapFluidU(topSprite, fu3);
      short v3 = mapFluidV(topSprite, fv3);

      ByteBuffer target = waterBuffer;
      emitVertex(target, px0, py0, pz0, u0, v0, r, g, b, a, light, (byte) 1);
      emitVertex(target, px0, py3, pz1, u1, v1, r, g, b, a, light, (byte) 1);
      emitVertex(target, px1, py2, pz1, u2, v2, r, g, b, a, light, (byte) 1);
      emitVertex(target, px1, py1, pz0, u3, v3, r, g, b, a, light, (byte) 1);
      waterQuadCount++;
    }

    private void renderFluidSide(int lx, int ly, int lz, Direction dir, float[] heights, byte r, byte g, byte b, byte a,
        byte light, boolean lava) {
      float h0, h1;
      short x0, z0, x1, z1;
      TextureAtlasSprite sideSprite = lava ? context.lavaFlowingSprite : context.waterFlowingSprite;

      switch (dir) {
        case NORTH:
          h0 = heights[0];
          h1 = heights[1];
          x0 = (short) (lx * 256.0f);
          z0 = (short) ((lz + FLUID_EPSILON) * 256.0f);
          x1 = (short) ((lx + 1) * 256.0f);
          z1 = (short) ((lz + FLUID_EPSILON) * 256.0f);
          break;
        case SOUTH:
          h0 = heights[3];
          h1 = heights[2];
          x0 = (short) (lx * 256.0f);
          z0 = (short) ((lz + 1 - FLUID_EPSILON) * 256.0f);
          x1 = (short) ((lx + 1) * 256.0f);
          z1 = (short) ((lz + 1 - FLUID_EPSILON) * 256.0f);
          break;
        case WEST:
          h0 = heights[0];
          h1 = heights[3];
          x0 = (short) ((lx + FLUID_EPSILON) * 256.0f);
          z0 = (short) (lz * 256.0f);
          x1 = (short) ((lx + FLUID_EPSILON) * 256.0f);
          z1 = (short) ((lz + 1 - FLUID_EPSILON) * 256.0f);
          break;
        case EAST:
          h0 = heights[1];
          h1 = heights[2];
          x0 = (short) ((lx + 1 - FLUID_EPSILON) * 256.0f);
          z0 = (short) (lz * 256.0f);
          x1 = (short) ((lx + 1 - FLUID_EPSILON) * 256.0f);
          z1 = (short) ((lz + 1 - FLUID_EPSILON) * 256.0f);
          break;
        default:
          return;
      }

      short py0 = (short) ((ly + h0) * 256.0f);
      short py1 = (short) ((ly + h1) * 256.0f);
      short pyBase = (short) (ly * 256.0f);
      short uLeft = mapFluidU(sideSprite, 0.5f);
      short uRight = mapFluidU(sideSprite, 0.0f);
      short vTop0 = mapFluidV(sideSprite, (1.0f - h0) * 0.5f);
      short vTop1 = mapFluidV(sideSprite, (1.0f - h1) * 0.5f);
      short vBottom = mapFluidV(sideSprite, 0.5f);

      byte normal = (byte) dir.get3DDataValue();
      ByteBuffer target = waterBuffer;
      emitVertex(target, x0, py0, z0, uLeft, vTop0, r, g, b, a, light, normal);
      emitVertex(target, x0, pyBase, z0, uLeft, vBottom, r, g, b, a, light, normal);
      emitVertex(target, x1, pyBase, z1, uRight, vBottom, r, g, b, a, light, normal);
      emitVertex(target, x1, py1, z1, uRight, vTop1, r, g, b, a, light, normal);
      waterQuadCount++;
    }

    private void renderFluidBottom(int lx, int ly, int lz, byte r, byte g, byte b, byte a, byte light, boolean lava) {
      short px0 = (short) (lx * 256.0f);
      short pz0 = (short) (lz * 256.0f);
      short px1 = (short) ((lx + 1) * 256.0f);
      short pz1 = (short) ((lz + 1) * 256.0f);
      short py = (short) (ly * 256.0f);

      TextureAtlasSprite bottomSprite = lava ? context.lavaStillSprite : context.waterStillSprite;
      short u0 = mapFluidU(bottomSprite, 0.0f);
      short u1 = mapFluidU(bottomSprite, 1.0f);
      short v0 = mapFluidV(bottomSprite, 0.0f);
      short v1 = mapFluidV(bottomSprite, 1.0f);

      ByteBuffer target = waterBuffer;
      emitVertex(target, px0, py, pz1, u0, v1, r, g, b, a, light, (byte) 0);
      emitVertex(target, px0, py, pz0, u0, v0, r, g, b, a, light, (byte) 0);
      emitVertex(target, px1, py, pz0, u1, v0, r, g, b, a, light, (byte) 0);
      emitVertex(target, px1, py, pz1, u1, v1, r, g, b, a, light, (byte) 0);
      waterQuadCount++;
    }

    private void emitBakedQuad(BakedQuad quad, int lx, int ly, int lz,
        BlockState state, boolean water, boolean forceFlat, boolean crossLike) {
      ByteBuffer target = water ? waterBuffer : solidBuffer;
      Direction face = quad.direction();
      byte normalIndex = (byte) (face != null ? face.get3DDataValue() : 6);
      float shade = quad.materialInfo().shade() && normalIndex >= 0
          && normalIndex < context.faceShade.length
              ? context.faceShade[normalIndex]
              : 1.0f;

      boolean isLeaves = state.getBlock() instanceof LeavesBlock;
      MetalRenderConfig cfg = MetalRenderClient.getConfig();
      boolean fastLeaves = isLeaves && (lodTier >= 1 || (cfg != null && cfg.leafCullingMode == 0));

      TextureAtlasSprite quadSprite = quad.materialInfo().sprite();
      boolean grassOverlay = state.getBlock() == Blocks.GRASS_BLOCK &&
          isGrassSideOverlay(quadSprite);
      boolean grassSideTint = grassOverlay ||
          (state.getBlock() == Blocks.GRASS_BLOCK && face != null
              && face.getAxis() != Direction.Axis.Y
              && quad.materialInfo().tintIndex() == 0);
      boolean tinted = grassSideTint || quad.materialInfo().isTinted()
          || quad.materialInfo().tintIndex() >= 0;
      int tintIndex = grassSideTint ? 0 : quad.materialInfo().tintIndex();
      int blockColor = tinted ? getBiomeTint(lx, ly, lz, tintIndex) : 0xFFFFFF;
      byte tintR = (byte) ((blockColor >> 16) & 0xFF);
      byte tintG = (byte) ((blockColor >> 8) & 0xFF);
      byte tintB = (byte) (blockColor & 0xFF);

      boolean smoothAO = !forceFlat && lodTier == 0 && face != null && smoothLightingEnabled;
      boolean faceCubic = false;
      if (smoothAO) {
        float minX = 32f, minY = 32f, minZ = 32f;
        float maxX = -32f, maxY = -32f, maxZ = -32f;
        for (int i = 0; i < 4; i++) {
          org.joml.Vector3fc p = quad.position(i);
          minX = Math.min(minX, p.x());
          maxX = Math.max(maxX, p.x());
          minY = Math.min(minY, p.y());
          maxY = Math.max(maxY, p.y());
          minZ = Math.min(minZ, p.z());
          maxZ = Math.max(maxZ, p.z());
        }
        faceCubic = switch (face.getAxis()) {
          case Y -> minY == maxY
              && (face == Direction.UP ? maxY > 0.9999f : minY < 1.0e-4f);
          case Z -> minZ == maxZ
              && (face == Direction.SOUTH ? maxZ > 0.9999f : minZ < 1.0e-4f);
          default -> minX == maxX
              && (face == Direction.EAST ? maxX > 0.9999f : minX < 1.0e-4f);
        };
      }

      for (int i = 0; i < 4; i++) {
        org.joml.Vector3fc pos = quad.position(i);
        long packedUV = quad.packedUV(i);
        float x = pos.x() + lx;
        float y = pos.y() + ly;
        float z = pos.z() + lz;
        if (grassSideTint && face != null && face.getAxis() != Direction.Axis.Y) {
          x += face.getStepX() * GRASS_OVERLAY_OFFSET;
          y += face.getStepY() * GRASS_OVERLAY_OFFSET;
          z += face.getStepZ() * GRASS_OVERLAY_OFFSET;
        }
        float u = Float.intBitsToFloat((int) (packedUV >> 32));
        float v = Float.intBitsToFloat((int) packedUV);

        short px = (short) (x * 256.0f);
        short py = (short) (y * 256.0f);
        short pz = (short) (z * 256.0f);
        short su = (short) (u * 65535f);
        short sv = (short) (v * 65535f);

        byte light;
        float ao;
        if (lodTier >= 2) {
          light = computeFaceLightFast(lx, ly, lz, face, quad.materialInfo().lightEmission());
          ao = 1.0f;
        } else {
          computeVertexLighting(lx, ly, lz, face, faceCubic, pos.x(), pos.y(),
              pos.z(), quad.materialInfo().lightEmission(), forceFlat);
          light = (byte) ((computedBlock & 0xF) | ((computedSky & 0xF) << 4));
          ao = computedAO;
          if (crossLike) {
            byte above = getPaddedLight(lx, ly + 1, lz);
            int bl = Math.max(light & 0xF, above & 0xF);
            int sl = Math.max((light >> 4) & 0xF, (above >> 4) & 0xF);
            int emission = quad.materialInfo().lightEmission();
            if (emission > 0) {
              bl = Math.max(bl, Math.min(15, emission));
            }
            light = (byte) ((bl & 0xF) | ((sl & 0xF) << 4));
          }
        }

        float fr, fg, fb;
        if (tinted) {
          fr = (tintR & 0xFF) * shade * ao;
          fg = (tintG & 0xFF) * shade * ao;
          fb = (tintB & 0xFF) * shade * ao;
        } else {
          fr = 255f * shade * ao;
          fg = 255f * shade * ao;
          fb = 255f * shade * ao;
        }
        byte r = (byte) Math.min(255, (int) fr);
        byte g = (byte) Math.min(255, (int) fg);
        byte b = (byte) Math.min(255, (int) fb);
        byte a = fastLeaves ? (byte) 0xFE : (byte) 0xFF;

        emitVertex(target, px, py, pz, su, sv, r, g, b, a, light, normalIndex);
      }
      if (water) {
        waterQuadCount++;
      } else {
        opaqueQuadCount++;
      }
    }

    private byte computeFaceLightFast(int lx, int ly, int lz, Direction face, int emission) {
      int sx = lx;
      int sy = ly;
      int sz = lz;
      if (face != null) {
        sx = clampLocal(lx + face.getStepX());
        sy = clampLocal(ly + face.getStepY());
        sz = clampLocal(lz + face.getStepZ());
      }
      byte light = getPaddedLight(sx, sy, sz);
      int bl = light & 0xF;
      int sl = (light >> 4) & 0xF;
      if (emission > 0) {
        bl = Math.max(bl, Math.min(15, emission));
      }
      return (byte) ((bl & 0xF) | ((sl & 0xF) << 4));
    }

    private int computedBlock;
    private int computedSky;
    private float computedAO;

    private void computeVertexLighting(int lx, int ly, int lz, Direction face,
        boolean faceCubic, float vx, float vy, float vz, int emission, boolean flatLight) {
      if (!flatLight && lodTier == 0 && face != null && smoothLightingEnabled) {
        computeSmoothLightAndAO(lx, ly, lz, face, faceCubic, vx, vy, vz, emission);
        return;
      }
      if (face == null) {
        byte light = getPaddedLight(lx, ly, lz);
        computedBlock = light & 0xF;
        computedSky = (light >> 4) & 0xF;
        computedAO = 1.0f;
        return;
      }
      byte light = computeFaceLightFast(lx, ly, lz, face, emission);
      computedBlock = light & 0xF;
      computedSky = (light >> 4) & 0xF;
      computedAO = 1.0f;
    }

    private void computeSmoothLightAndAO(int lx, int ly, int lz, Direction face,
        boolean faceCubic, float vx, float vy, float vz, int emission) {
      int stepX = face.getStepX();
      int stepY = face.getStepY();
      int stepZ = face.getStepZ();

      int bx;
      int by;
      int bz;
      if (faceCubic) {
        bx = lx + stepX;
        by = ly + stepY;
        bz = lz + stepZ;
      } else {
        bx = lx;
        by = ly;
        bz = lz;
      }

      int a1;
      int a2;
      switch (face.getAxis()) {
        case X -> { a1 = 1; a2 = 2; }
        case Y -> { a1 = 0; a2 = 2; }
        default -> { a1 = 0; a2 = 1; }
      }
      float p1 = a1 == 0 ? vx : (a1 == 1 ? vy : vz);
      float p2 = a2 == 0 ? vx : (a2 == 1 ? vy : vz);
      int d1 = p1 > 0.5f ? 1 : -1;
      int d2 = p2 > 0.5f ? 1 : -1;

      int s1x = bx;
      int s1y = by;
      int s1z = bz;
      int s2x = bx;
      int s2y = by;
      int s2z = bz;
      int cxc = bx;
      int cyc = by;
      int czc = bz;
      if (a1 == 0) {
        s1x += d1;
        cxc += d1;
      } else if (a1 == 1) {
        s1y += d1;
        cyc += d1;
      } else {
        s1z += d1;
        czc += d1;
      }
      if (a2 == 0) {
        s2x += d2;
        cxc += d2;
      } else if (a2 == 1) {
        s2y += d2;
        cyc += d2;
      } else {
        s2z += d2;
        czc += d2;
      }

      byte light1 = getPaddedLight(s1x, s1y, s1z);
      byte light2 = getPaddedLight(s2x, s2y, s2z);
      float shade1 = getPaddedShade(s1x, s1y, s1z);
      float shade2 = getPaddedShade(s2x, s2y, s2z);

      byte cornerLight;
      float cornerShade;
      if (!isPaddedOccluding(s1x, s1y, s1z) || !isPaddedOccluding(s2x, s2y, s2z)) {
        cornerLight = getPaddedLight(cxc, cyc, czc);
        cornerShade = getPaddedShade(cxc, cyc, czc);
      } else {
        cornerLight = light1;
        cornerShade = shade1;
      }

      BlockState front = getPaddedBlockState(lx + stepX, ly + stepY, lz + stepZ);
      byte centerLight;
      if (faceCubic || front == null || !front.isSolidRender()) {
        centerLight = getPaddedLight(lx + stepX, ly + stepY, lz + stepZ);
      } else {
        centerLight = getPaddedLight(lx, ly, lz);
      }
      float centerShade = getPaddedShade(bx, by, bz);

      int centerBl = centerLight & 0xF;
      int centerSl = (centerLight >> 4) & 0xF;

      int bl1 = light1 & 0xF;
      int sl1 = (light1 >> 4) & 0xF;
      int bl2 = light2 & 0xF;
      int sl2 = (light2 >> 4) & 0xF;
      int blC = cornerLight & 0xF;
      int slC = (cornerLight >> 4) & 0xF;

      if (centerBl > 2 || centerSl > 2) {
        if (bl1 == 0) bl1 = centerBl;
        if (sl1 == 0) sl1 = centerSl;
        if (bl2 == 0) bl2 = centerBl;
        if (sl2 == 0) sl2 = centerSl;
        if (blC == 0) blC = centerBl;
        if (slC == 0) slC = centerSl;
      }

      int avgBl = (bl1 + bl2 + blC + centerBl + 2) >> 2;
      int avgSl = (sl1 + sl2 + slC + centerSl + 2) >> 2;
      if (emission > 0) {
        avgBl = Math.max(avgBl, Math.min(15, emission));
      }

      computedBlock = avgBl & 0xF;
      computedSky = avgSl & 0xF;
      computedAO = Math.max(0.2f, Math.min(1.0f,
          (shade1 + shade2 + cornerShade + centerShade) * 0.25f));
    }

    private boolean isPaddedOccluding(int x, int y, int z) {
      int px = x + PADDED_RADIUS;
      int py = y + PADDED_RADIUS;
      int pz = z + PADDED_RADIUS;
      return snapshot.paddedOcclusion[(py * PADDED_SIZE + pz) * PADDED_SIZE + px] != 0;
    }

    private float getPaddedShade(int x, int y, int z) {
      int px = x + PADDED_RADIUS;
      int py = y + PADDED_RADIUS;
      int pz = z + PADDED_RADIUS;
      if (px < 0 || px >= PADDED_SIZE || py < 0 || py >= PADDED_SIZE
          || pz < 0 || pz >= PADDED_SIZE) {
        return 1.0f;
      }
      return (snapshot.paddedShade[(py * PADDED_SIZE + pz) * PADDED_SIZE + px] & 0xFF) / 255.0f;
    }

    private int clampLocal(int value) {
      return Math.max(-PADDED_RADIUS,
          Math.min(SECTION_SIZE + PADDED_RADIUS - 1, value));
    }
  }

  private static void emitVertex(ByteBuffer buf, short px, short py, short pz,
      short u, short v, byte r, byte g, byte b, byte a, byte light, byte nIdx) {
    long w0 = (px & 0xFFFFL) | ((py & 0xFFFFL) << 16) | ((pz & 0xFFFFL) << 32) |
        ((u & 0xFFFFL) << 48);
    long w1 = (v & 0xFFFFL) | ((r & 0xFFL) << 16) | ((g & 0xFFL) << 24) |
        ((b & 0xFFL) << 32) | ((a & 0xFFL) << 40) |
        ((light & 0xFFL) << 48) | ((nIdx & 0xFFL) << 56);
    buf.putLong(w0);
    buf.putLong(w1);
  }

  private static int[] bucketQuadsByFacing(ByteBuffer vertexBuffer, int opaqueQuadCount,
      int waterQuadCount) {
    int[] facingQuadCounts = new int[14];
    int totalQuads = Math.min(vertexBuffer.limit() / (4 * VERTEX_STRIDE),
        opaqueQuadCount + waterQuadCount);
    int boundedOpaqueQuadCount = Math.min(Math.max(opaqueQuadCount, 0), totalQuads);
    for (int i = 0; i < boundedOpaqueQuadCount; i++) {
      int nIdx = readNormalIndex(vertexBuffer, i);
      if (nIdx < 0 || nIdx >= 7) {
        nIdx = 6;
      }
      facingQuadCounts[nIdx]++;
    }

    ByteBuffer bucketBuffer = FACE_BUCKET_BUF_POOL.get();
    bucketBuffer.clear();
    int[] bucketStarts = new int[7];
    int running = 0;
    for (int i = 0; i < 7; i++) {
      bucketStarts[i] = running;
      running += facingQuadCounts[i];
    }
    int[] bucketOffsets = Arrays.copyOf(bucketStarts, bucketStarts.length);
    final int quadBytes = 4 * VERTEX_STRIDE;
    for (int i = 0; i < boundedOpaqueQuadCount; i++) {
      int nIdx = readNormalIndex(vertexBuffer, i);
      if (nIdx < 0 || nIdx >= 7) {
        nIdx = 6;
      }
      bucketBuffer.put(bucketOffsets[nIdx] * quadBytes, vertexBuffer,
          i * quadBytes, quadBytes);
      bucketOffsets[nIdx]++;
    }
    int opaqueBytes = boundedOpaqueQuadCount * quadBytes;
    vertexBuffer.put(0, bucketBuffer, 0, opaqueBytes);
    return facingQuadCounts;
  }

  private static byte readNormalIndex(ByteBuffer buf, int quadIndex) {
    int base = quadIndex * 4 * VERTEX_STRIDE;
    if (base + VERTEX_STRIDE > buf.limit())
      return 0;
    long w1 = buf.getLong(base + 8);
    return (byte) (w1 >>> 56);
  }

  public ChunkMeshData getMesh(int cx, int cy, int cz) {
    long key = packChunkKey(cx, cy, cz);
    synchronized (meshCache) {
      return meshCache.get(key);
    }
  }

  public void queueDrawRegistration(ChunkMeshData mesh) {
    if (mesh == null || mesh.bufferHandle == 0 || mesh.quadCount <= 0) {
      return;
    }
    int opaqueQuadCount = 0;
    int[] facing = mesh.facingQuadCounts;
    if (facing != null) {
      for (int i = 0; i < 7 && i < facing.length; i++) {
        opaqueQuadCount += Math.max(0, facing[i]);
      }
    }
    int flushCount = -1;
    synchronized (batchRegData) {

      if (batchRegCount >= BATCH_REG_CAPACITY) {
        try {
          NativeBridge.nRegisterChunkMeshBatch(batchRegCount, batchRegData);
        } catch (Exception ignored) {
        }
        batchRegCount = 0;
      }
      int idx = batchRegCount * BATCH_REG_STRIDE;
      batchRegData[idx] = (mesh.chunkX & 0xFFFFFFFFL) | ((long) mesh.chunkY << 32);
      batchRegData[idx + 1] = (mesh.chunkZ & 0xFFFFFFFFL) | ((long) mesh.quadCount << 32);
      batchRegData[idx + 2] = mesh.bufferHandle;
      batchRegData[idx + 3] = mesh.visibilityMask;
      batchRegData[idx + 4] = (opaqueQuadCount & 0xFFFFFFFFL)
          | ((long) (facing.length > 0 ? Math.max(0, facing[0]) : 0) << 32);
      batchRegData[idx + 5] = ((long) (facing.length > 1 ? Math.max(0, facing[1]) : 0) & 0xFFFFFFFFL)
          | ((long) (facing.length > 2 ? Math.max(0, facing[2]) : 0) << 32);
      batchRegData[idx + 6] = ((long) (facing.length > 3 ? Math.max(0, facing[3]) : 0) & 0xFFFFFFFFL)
          | ((long) (facing.length > 4 ? Math.max(0, facing[4]) : 0) << 32);
      batchRegData[idx + 7] = ((long) (facing.length > 5 ? Math.max(0, facing[5]) : 0) & 0xFFFFFFFFL)
          | ((long) (facing.length > 6 ? Math.max(0, facing[6]) : 0) << 32);
      batchRegData[idx + 8] = mesh.lodTier;
      batchRegCount++;
      if (batchRegCount >= BATCH_REG_CAPACITY) {
        flushCount = batchRegCount;
        batchRegCount = 0;
      }
    }
    if (flushCount > 0) {
      try {
        NativeBridge.nRegisterChunkMeshBatch(flushCount, batchRegData);
      } catch (Exception ignored) {
      }
    }
  }

  private static byte computeFaceOcclusionMask(int[] paddedBlockStates) {
    if (paddedBlockStates == null) {
      return 0;
    }
    byte mask = 0;
    for (Direction dir : ALL_DIRECTIONS) {
      try {
        if (isSectionFaceOccluded(paddedBlockStates, dir)) {
          mask |= (byte) (1 << dir.get3DDataValue());
        }
      } catch (Exception ignored) {
      }
    }
    return mask;
  }

  private static long computeVisibilityMask(int[] paddedBlockStates) {    long mask = 0L;
    if (paddedBlockStates == null)
      return mask;
    for (int y = 0; y < SECTION_SIZE; y++) {
      for (int z = 0; z < SECTION_SIZE; z++) {
        for (int x = 0; x < SECTION_SIZE; x++) {
          int pIdx = ((y + PADDED_RADIUS) * PADDED_SIZE + (z + PADDED_RADIUS))
              * PADDED_SIZE + (x + PADDED_RADIUS);
          if (paddedBlockStates[pIdx] != 0) {
            int bit = (y / 4) * 16 + (z / 4) * 4 + (x / 4);
            if (bit >= 0 && bit < 64) {
              mask |= (1L << bit);
            }
          }
        }
      }
    }
    return mask;
  }

  private static boolean isSectionFullyOccluded(int[] paddedBlockStates) {
    if (paddedBlockStates == null) {
      return true;
    }
    for (Direction dir : ALL_DIRECTIONS) {
      if (!isSectionFaceOccluded(paddedBlockStates, dir)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isSectionFaceOccluded(int[] paddedBlockStates, Direction dir) {
    int stepX = dir.getStepX();
    int stepY = dir.getStepY();
    int stepZ = dir.getStepZ();
    int startX = stepX < 0 ? 0 : (stepX > 0 ? SECTION_SIZE - 1 : 0);
    int startY = stepY < 0 ? 0 : (stepY > 0 ? SECTION_SIZE - 1 : 0);
    int startZ = stepZ < 0 ? 0 : (stepZ > 0 ? SECTION_SIZE - 1 : 0);
    int endX = stepX != 0 ? startX + 1 : SECTION_SIZE;
    int endY = stepY != 0 ? startY + 1 : SECTION_SIZE;
    int endZ = stepZ != 0 ? startZ + 1 : SECTION_SIZE;

    for (int y = startY; y < endY; y++) {
      for (int z = startZ; z < endZ; z++) {
        for (int x = startX; x < endX; x++) {
          int pIdx = ((y + PADDED_RADIUS) * PADDED_SIZE + (z + PADDED_RADIUS))
              * PADDED_SIZE + (x + PADDED_RADIUS);
          int stateId = paddedBlockStates[pIdx];
          if (stateId == 0 || !isOpaqueState(stateId)) {
            return false;
          }
          int nx = x + stepX;
          int ny = y + stepY;
          int nz = z + stepZ;
          int nIdx = ((ny + PADDED_RADIUS) * PADDED_SIZE + (nz + PADDED_RADIUS))
              * PADDED_SIZE + (nx + PADDED_RADIUS);
          if (nIdx < 0 || nIdx >= paddedBlockStates.length) {
            return false;
          }
          int neighborId = paddedBlockStates[nIdx];
          if (neighborId == 0 || !isOpaqueState(neighborId)) {
            return false;
          }
        }
      }
    }
    return true;
  }

  private static boolean isOpaqueState(int stateId) {
    it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<BlockState> cache = STATE_BY_ID_CACHE
        .get();
    BlockState state = cache.get(stateId);
    if (state == null) {
      try {
        state = Block.stateById(stateId);
      } catch (RuntimeException ignored) {
        state = Blocks.AIR.defaultBlockState();
      }
      if (state == null) {
        state = Blocks.AIR.defaultBlockState();
      }
      if (cache.size() < STATE_BY_ID_CACHE_CAP) {
        cache.put(stateId, state);
      }
    }
    try {
      return state.isSolidRender();
    } catch (RuntimeException ignored) {
      return false;
    }
  }
}
