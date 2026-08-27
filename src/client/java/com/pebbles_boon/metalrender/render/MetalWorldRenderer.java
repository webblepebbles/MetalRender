package com.pebbles_boon.metalrender.render;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.backend.MetalRenderer;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.culling.AsyncCullTask;
import com.pebbles_boon.metalrender.culling.CullingOrcreator;
import com.pebbles_boon.metalrender.culling.FrustumCuller;
import com.pebbles_boon.metalrender.entity.MetalEntityRenderer;
import com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.nativebridge.NativeMemory;
import com.pebbles_boon.metalrender.particle.MetalParticleRenderer;
import com.pebbles_boon.metalrender.performance.AdaptiveResolutionController;
import com.pebbles_boon.metalrender.render.chunk.CustomChunkMesher;
import com.pebbles_boon.metalrender.render.lod.LodPolicy;
import com.pebbles_boon.metalrender.sodium.backend.MeshShaderBackend;
import com.pebbles_boon.metalrender.performance.BuildBudgetEstimator;
import com.pebbles_boon.metalrender.performance.PerformanceController;
import com.pebbles_boon.metalrender.performance.MetalRenderProfiler;
import com.pebbles_boon.metalrender.sort.TranslucencySorter;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public class MetalWorldRenderer {
  private static final int DEFAULT_MAX_MESHES = 65536;
  private static final int PINNED_RENDER_DISTANCE = 32;
  private static final int PINNED_MAX_MESHES = 131072;
  private static final long CHUNK_BUILD_BUDGET_NS = 4_500_000L;
  private static final int MIN_CHUNK_BUILDS_PER_FRAME = 10;
  private static final int CHUNK_BACKLOG_PRESSURE_THRESHOLD = 384;
  private static final int CHUNK_BACKLOG_HEAVY_THRESHOLD = 1024;
  private static final int CHUNK_SCAN_PRESSURE_THRESHOLD = 4096;
  private static final int CHUNK_SCAN_SATURATED_THRESHOLD = 12288;
  private static final long CHUNK_BACKLOG_BUILD_BURST_NS = 8_000_000L;
  private static final int MIN_CHUNK_BACKLOG_BUILDS_PER_FRAME = 24;
  private static final long CHUNK_HEAVY_BACKLOG_BUILD_BURST_NS = 12_000_000L;
  private static final int MIN_CHUNK_HEAVY_BACKLOG_BUILDS_PER_FRAME = 40;
  private static final long CHUNK_SATURATED_BUILD_BUDGET_NS = 1_500_000L;
  private static final int MIN_CHUNK_SATURATED_BUILDS_PER_FRAME = 4;
  private static final long CHUNK_TURN_BUILD_BURST_NS = 3_500_000L;
  private static final int MIN_CHUNK_TURN_BUILDS_PER_FRAME = 12;
  private static final int BASE_HIGH_PRIORITY_SUBMISSIONS_PER_PASS = 8;
  private static final int BACKLOG_HIGH_PRIORITY_SUBMISSIONS_PER_PASS = 16;
  private static final int HEAVY_BACKLOG_HIGH_PRIORITY_SUBMISSIONS_PER_PASS = 24;
  private static final int TURN_HIGH_PRIORITY_SUBMISSIONS_PER_PASS = 16;
  private static final int SATURATED_HIGH_PRIORITY_SUBMISSIONS_PER_PASS = 2;
  private static final int PRIORITIZED_BUILD_STREAK_LIMIT = 2;
  private static final int MAX_IN_FLIGHT_BUILD_TASKS = 256;
  private static final int RESERVED_PRIORITY_IN_FLIGHT_SLOTS = 32;
  private static final int FPS_PRIORITY_MAX_IN_FLIGHT_BUILD_TASKS = 256;
  private static final int FPS_PRIORITY_NORMAL_BACKGROUND_SUBMISSIONS_PER_PASS = 192;
  private static final int HIGH_PRIORITY_LOADED_VERTICAL_RANGE = 3;
  private static final int MID_DISTANCE_SCAN_VERTICAL_RANGE = 8;
  private static final int FAR_DISTANCE_SCAN_VERTICAL_RANGE = 5;
  private static final int EXTREME_DISTANCE_SCAN_VERTICAL_RANGE = 3;
  private static final int SURFACE_SECTION_EXTRA_DEPTH = 2;
  private static final int TURN_PRIORITY_LOADED_CHUNK_RANGE = 24;
  private static final float BUILD_SORT_REORDER_DOT_THRESHOLD = 0.9848f;
  private static final int TURN_PRIORITY_SCAN_FRAMES = 6;
  private static final int TURN_PRIORITY_FORWARD_SCAN_DEPTH = 6;
  private static final float TURN_PRIORITY_SCAN_COS_THRESHOLD = 0.45f;
  private static final int IMMEDIATE_LOADED_CHUNK_BUILD_RANGE = 8;
  private static final int IMPORTANT_REBUILD_CHUNK_RANGE = 2;
  private static final int LOD_REFRESH_FRAME_INTERVAL = 2;
  private static final int MAX_LOD_REFRESH_SUBMITS_PER_PASS = 4;
  private static final int MAX_LOD_SCAN_PER_PASS = 512;
  private static final int LOD_REFRESH_PENDING_LIMIT = 64;
  private static final int LOD_REFRESH_IN_FLIGHT_LIMIT = 48;
  private static final int MAX_LOD_DEMOTIONS_PER_PASS = 12;
  private static final int LOD_RECENCY_PULL_INTERVAL = 30;
  private static final int MAX_LOD_RECENCY_DEMOTIONS_PER_PASS = 8;
  private static final int LOD_RECENCY_SCRATCH_SIZE = 32768;
  private static final int INTERACTIVE_PRIORITY_CHUNK_RANGE = 6;
  private static final int INTERACTIVE_PRIORITY_SUBMISSIONS_PER_PASS = 8;
  private static final int MAX_INTERACTIVE_PRIORITY_QUEUE_DEPTH = 16;
  private static final int LOADING_BACKGROUND_SUBMISSIONS_PER_PASS = 96;
  private static final int TURN_PRIORITY_BACKGROUND_SUBMISSIONS_PER_PASS = 24;
  private static final int NORMAL_BACKGROUND_SUBMISSIONS_PER_PASS = 96;
  private static final int ACTIVE_CLOSE_RANGE_RESCAN_INTERVAL = 3;
  private static final int IDLE_CLOSE_RANGE_RESCAN_INTERVAL = 8;
  private static final int HOT_LOAD_REBUILD_RANGE = 12;
  private static final int NORMAL_FRONTIER_RING_SCAN_SPAN = 6;

  private static final int PRESSURED_CLOSE_SCAN_RANGE = 6;
  private static final int SATURATED_CLOSE_SCAN_RANGE = 4;
  private static final long FULL_RENDERDIST_RESCAN_INTERVAL_NS = 1_000_000_000L;
  private static final int TEXTURE_SYNC_PRESSURE_THRESHOLD = 64;
  private static final int PRESSURED_LIGHTMAP_SYNC_FRAME_INTERVAL = 8;
  private static final double TEXTURE_BACKOFF_TRIP_MESH_MS = 6.0;
  private static final double TEXTURE_BACKOFF_HARD_TRIP_MESH_MS = 12.0;
  private static final double TEXTURE_BACKOFF_RECOVERY_MESH_MS = 4.0;
  private static final int TEXTURE_BACKOFF_TRIP_CONSEC = 2;
  private static final int TEXTURE_BACKOFF_RECOVER_CONSEC = 5;
  private static final int BACKED_OFF_LIGHTMAP_SYNC_FRAME_INTERVAL = 32;
  private static volatile java.lang.reflect.Field skyLightFactorField;
  private static volatile java.lang.reflect.Method skyLightProbeGetValueMethod;
  private static volatile boolean skyLightLookupFailed;
  private static MetalWorldRenderer instance;
  private final FrustumCuller frustumCuller;
  private final MetalEntityRenderer entityRenderer;
  private final MetalParticleRenderer particleRenderer;
  private final CustomChunkMesher chunkMesher;
  private final MetalTextureManager textureManager;
  private final IOSurfaceBlitter ioSurfaceBlitter;
  private final Matrix4f projectionMatrix;
  private final Matrix4f modelViewMatrix;
  private boolean worldLoaded;
  private boolean renderingActive;
  private boolean texturesReady;
  private int frameCount;
  private int maxMeshes = DEFAULT_MAX_MESHES;
  private int lastDrawnChunkCount;
  private long lastDiagLogMs;
  private long outlineBufferHandle;
  private long jBuildAcc;
  private int jProfCount = 0;
  private boolean gpuDrivenEnabled;
  private MeshShaderBackend meshShaderBackend;
  private ByteBuffer subChunkUploadBuffer;
  private ByteBuffer chunkUniformsBuffer;
  private int subChunkUploadCapacity = 4096;
  private long argumentBufferHandle;
  private it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap readinessCache;
  private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap delayedBlockRebuildFrames =
      new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
  private final int[] cameraFacingCullStats = new int[3];
  private int lastCullMeshGen = -1;
  private float lastCullCamX;
  private float lastCullCamY;
  private float lastCullCamZ;
  private int lastCullCount = 0;
  private final float[] lastCullFrustum = new float[24];
  private final CullingOrcreator cullingOrcreator = new CullingOrcreator();
  private final TranslucencySorter translucencySorter = new TranslucencySorter();
  private final float[] gpuFrustumPlanes = new float[24];
  private double frameCameraX;
  private double frameCameraY;
  private double frameCameraZ;
  private float[] outlineVerts = new float[72 * 3];
  private byte[] outlineDataBuf = new byte[72 * 3 * 4];
  private final java.util.ArrayList<float[]> outlineEdges = new java.util.ArrayList<>(32);
  private int screenshotBlitCooldownFrames;
  private boolean loggedChunkLoadDropNotReady;
  private boolean loggedBlockUpdateDropNotReady;
  private boolean loggedWorldLoadWithoutRenderer;
  private long lastQueuePressureLogMs;

  public MetalWorldRenderer() {
    this.frustumCuller = new FrustumCuller();
    this.entityRenderer = new MetalEntityRenderer();
    this.particleRenderer = new MetalParticleRenderer();
    this.chunkMesher = new CustomChunkMesher();
    this.readinessCache = new it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap();
    this.delayedBlockRebuildFrames.defaultReturnValue(Integer.MIN_VALUE);
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    long device = renderer != null ? renderer.getBackend().getDeviceHandle() : 0;
    this.textureManager = new MetalTextureManager(device);
    this.ioSurfaceBlitter = new IOSurfaceBlitter();
    this.projectionMatrix = new Matrix4f();
    this.modelViewMatrix = new Matrix4f();
    instance = this;
  }

  public static MetalWorldRenderer getInstance() {
    return instance;
  }

  public void onWorldLoad() {
    AsyncCullTask.reset();
    lodPolicy.clear();
    worldLoaded = true;
    MetalRenderConfig gpuConfig = MetalRenderClient.getConfig();
    boolean clusterEnabled = gpuConfig != null && gpuConfig.enableClusterFrustumCulling;
    boolean sortEnabled = gpuConfig != null && gpuConfig.enableGpuTranslucencySort;
    cullingOrcreator.setActive(clusterEnabled);
    cullingOrcreator.setCpuFallbackEnabled(true);
    translucencySorter.setActive(sortEnabled);
    MetalLogger.info("orchestrators: cluster=%s sort=%s",
        clusterEnabled, sortEnabled);
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer != null && renderer.isAvailable()) {
      Minecraft mc = Minecraft.getInstance();
      int w = mc.getWindow().getWidth();
      int h = mc.getWindow().getHeight();
      if (w > 0 && h > 0) {
        renderer.resize(w, h);
      }
      chunkMesher.initialize(renderer.getBackend().getDeviceHandle());
      entityRenderer.setup(renderer.getBackend().getDeviceHandle(), 0);
      particleRenderer.setup(renderer.getBackend().getDeviceHandle());
      renderingActive = true;
      entityRenderer.setActive(true);
      particleRenderer.setActive(true);
      texturesReady = false;
      long handle = renderer.getBackend().getDeviceHandle();
      meshShaderBackend = new MeshShaderBackend();
      meshShaderBackend.initialize();
      boolean meshShadersSupported = MetalHardwareChecker.supportsMeshShaders();
      if (handle != 0) {
        subChunkUploadBuffer = ByteBuffer.allocateDirect(subChunkUploadCapacity * 48)
            .order(ByteOrder.nativeOrder());
        chunkUniformsBuffer = ByteBuffer.allocateDirect(subChunkUploadCapacity * 16)
            .order(ByteOrder.nativeOrder());
        if (argumentBufferHandle == 0 && meshShaderBackend != null &&
            meshShaderBackend.areMeshShadersAvailable()) {
          argumentBufferHandle = NativeBridge.nCreateBuffer(handle,
              subChunkUploadCapacity * 16,
              NativeMemory.STORAGE_MODE_SHARED);
          if (argumentBufferHandle != 0) {
            MetalLogger.info("mesh arg buf: h=%d sz=%d",
                argumentBufferHandle, subChunkUploadCapacity * 16);
          }
        }
      }
      applyFeatureConfig(MetalRenderClient.getConfig());
      boolean meshShadersActive = NativeBridge.isLibLoaded() && NativeBridge.nAreMeshShadersActive();
      MetalLogger.info("gpu pipeline weady (mesh=%s on=%s)",
          meshShadersActive ? "on" : (meshShadersSupported ? "avail" : "no"),
          gpuDrivenEnabled ? "yes" : "no");
      MetalLogger.info("world wendew on (" + w + "x" + h + ")");
    } else if (!loggedWorldLoadWithoutRenderer) {
      loggedWorldLoadWithoutRenderer = true;
      MetalLogger.warn(
          "world load before wendewer weady; capture deferred");
    }
  }

  public void onWorldUnload() {
    worldLoaded = false;
    renderingActive = false;
    texturesReady = false;
    entityRenderer.shutdown();
    particleRenderer.shutdown();
    textureManager.destroy();
    ioSurfaceBlitter.destroy();
    chunkMesher.clear();
    loggedChunkLoadDropNotReady = false;
    loggedBlockUpdateDropNotReady = false;
    loggedWorldLoadWithoutRenderer = false;
    delayedBlockRebuildFrames.clear();
    frameCount = 0;
    lastDrawnChunkCount = 0;
    if (meshShaderBackend != null) {
      meshShaderBackend.shutdown();
      meshShaderBackend = null;
    }
    gpuDrivenEnabled = false;
    instance = null;
    subChunkUploadBuffer = null;
    chunkUniformsBuffer = null;
    if (argumentBufferHandle != 0) {
      NativeBridge.nDestroyBuffer(argumentBufferHandle);
      argumentBufferHandle = 0;
    }
    com.pebbles_boon.metalrender.nativebridge.ResidencySetManager.shutdown();
    cullingOrcreator.shutdown();
    translucencySorter.shutdown();
  }

  public boolean metalActive() {
    return worldLoaded && renderingActive &&
        MetalRenderClient.isMetalAvailable() &&
        MetalRenderClient.getConfig().enableMetalRendering;
  }

  public void prepareMeshes() {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return;
    Minecraft mc = Minecraft.getInstance();
    maxMeshes = shouldPinLoadedMeshes(mc) ? PINNED_MAX_MESHES : DEFAULT_MAX_MESHES;
    int w = mc.getWindow().getWidth();
    int h = mc.getWindow().getHeight();
    int refreshRate = mc.getWindow().getRefreshRate();
    double budgetMs = refreshRate > 0 ? 1000.0 / refreshRate : 16.666;
    if (!mc.options.enableVsync().get()) {
      int fpsLimit = mc.options.framerateLimit().get();
      if (fpsLimit > 0 && fpsLimit < 250) {
        budgetMs = Math.min(budgetMs, 1000.0 / fpsLimit);
      }
    }
    AdaptiveResolutionController.getInstance().setFrameBudgetMs(budgetMs);
    renderer.resize(w, h);
    if (!texturesReady && frameCount > 2) {
      textureManager.loadBlockAtlas();
      textureManager.loadLightmap();
      texturesReady = textureManager.isBlockAtlasLoaded() &&
          textureManager.isLightmapLoaded();
    } else if (texturesReady && textureManager.isUsingFallbackBlockAtlas() &&
        frameCount % 120 == 0) {
      textureManager.loadBlockAtlas();
    } else if (texturesReady && !textureManager.isUsingFallbackBlockAtlas()) {
      boolean textureSyncPressure = pendingBuildSet.size() >= TEXTURE_SYNC_PRESSURE_THRESHOLD ||
          chunkMesher.getPendingCount() >= TEXTURE_SYNC_PRESSURE_THRESHOLD;
      updateTextureBackoffState();
      textureManager.updateBlockAtlas();
      int lightmapInterval;
      if (textureBackoffActive) {
        lightmapInterval = BACKED_OFF_LIGHTMAP_SYNC_FRAME_INTERVAL;
      } else if (textureSyncPressure) {
        lightmapInterval = PRESSURED_LIGHTMAP_SYNC_FRAME_INTERVAL;
      } else {
        lightmapInterval = 1;
      }
      if (frameCount % lightmapInterval == 0) {
        textureManager.updateLightmap();
      }
    }
    long now = System.currentTimeMillis();
    long diagInterval = chunkMesher.getMeshCount() < 2000 ? 1000 : 5000;
    if (MetalRenderConfig.isDeepDebugActive() &&
        now - lastDiagLogMs > diagInterval) {
      lastDiagLogMs = now;
      MetalLogger.info(
          "diag: texReady=" + texturesReady +
              " fb=" + textureManager.isUsingFallbackBlockAtlas() +
              " m=" + chunkMesher.getMeshCount());
    }
    if (MetalRenderClient.getConfig().enableMetalRendering) {
      long buildStart = System.nanoTime();
      if (frameCount % LOD_REFRESH_FRAME_INTERVAL == 0) {
        refreshLodTiers(mc);
        updateLodRecencyEviction(mc);
      }
      releaseDelayedBlockRebuilds();
      buildPendingChunkMeshes(mc);
      jBuildAcc += System.nanoTime() - buildStart;
      jProfCount++;
      if (jProfCount >= 120) {
        double buildMs = jBuildAcc / 1e6 / jProfCount;
        MetalLogger.info(
            "java_profile: build=%.2f (avg/%d) p=%d q=%d m=%d "
                + "build=%d/%d inst=%d/%d int=%d/%d vis=%.2f/%d blk=%.2f/%d t=%d/%d",
            buildMs, jProfCount,
            pendingBuildSet.size(), chunkMesher.getPendingCount(),
            chunkMesher.getMeshCount(), chunkMesher.getBuilderActiveCount(),
            chunkMesher.getBuilderQueueDepth(),
            chunkMesher.getInstantActiveCount(),
            chunkMesher.getInstantQueueDepth(),
            chunkMesher.getInteractiveActiveCount(),
            chunkMesher.getInteractiveQueueDepth(),
            chunkMesher.getAverageVisibleSectionLatencyMs(),
            chunkMesher.getVisibleSectionLatencySamples(),
            chunkMesher.getAverageBlockUpdateLatencyMs(),
            chunkMesher.getBlockUpdateLatencySamples(),
            chunkMesher.getTrackedVisibleSectionCount(),
            chunkMesher.getTrackedBlockUpdateCount());
        jBuildAcc = 0;
        jProfCount = 0;
      }
    }
  }

  public void beginFrame(Camera camera, float tickDelta, Matrix4f projection,
      Matrix4f modelView, double cameraX, double cameraY, double cameraZ) {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return;
    frameCameraX = cameraX;
    frameCameraY = cameraY;
    frameCameraZ = cameraZ;
    if (chunkMesher != null) {
      chunkMesher.flushMeshRegistrations();
    }
    projectionMatrix.set(projection);
    modelViewMatrix.set(modelView);
    Vector3f camPos = new Vector3f((float) cameraX, (float) cameraY,
        (float) cameraZ);

    long cullStart = System.nanoTime();
    FrustumCuller latest = AsyncCullTask.getCurrentCull();
    if (latest != null) {
      frustumCuller.copyFrom(latest);
    } else {
      frustumCuller.update(projectionMatrix, modelViewMatrix, camPos);
    }
    final Matrix4f asyncProj = new Matrix4f(projectionMatrix);
    final Matrix4f asyncMV = new Matrix4f(modelViewMatrix);
    final Vector3f asyncCam = new Vector3f(camPos);
    AsyncCullTask.submitFrustumUpdate(asyncProj, asyncMV, asyncCam);
    MetalRenderProfiler.getInstance().recordCullTime(System.nanoTime() - cullStart);

    boolean frustumStable = !cullingOrcreator.isActive();
    if (cullingOrcreator.isActive()) {
      Matrix4f vp = new Matrix4f(projectionMatrix).mul(modelViewMatrix);
      extractFrustumPlanes(vp, gpuFrustumPlanes);
      int chunkRadius = Minecraft.getInstance().options.renderDistance().get();
      cullingOrcreator.rebuildFromFrustumCpu(frustumCuller, chunkRadius,
          camPos.x, camPos.y, camPos.z);
      cullingOrcreator.uploadToGpu(gpuFrustumPlanes);
      frustumStable = true;
      for (int i = 0; i < 24; i++) {
        if (Float.floatToRawIntBits(gpuFrustumPlanes[i]) !=
            Float.floatToRawIntBits(lastCullFrustum[i])) {
          frustumStable = false;
          break;
        }
      }
    }
    lastDrawnChunkCount = 0;
    Matrix4f metalProj = new Matrix4f(projectionMatrix);
    metalProj.m02(0.5f * metalProj.m02() + 0.5f * metalProj.m03());
    metalProj.m12(0.5f * metalProj.m12() + 0.5f * metalProj.m13());
    metalProj.m22(0.5f * metalProj.m22() + 0.5f * metalProj.m23());
    metalProj.m32(0.5f * metalProj.m32() + 0.5f * metalProj.m33());
    renderer.setProjectionMatrix(metalProj);
    renderer.setModelViewMatrix(modelViewMatrix);
    renderer.setCameraPosition(cameraX, cameraY, cameraZ);
    Vector3f cameraDirection = camera.rotation().transform(new Vector3f(0.0f, 0.0f, 1.0f)).normalize();
    if (NativeBridge.isLibLoaded()) {
      NativeBridge.nSetCameraDirection(renderer.getHandle(), cameraDirection.x,
          cameraDirection.y, cameraDirection.z);
      MetalRenderConfig config = MetalRenderClient.getConfig();
      NativeBridge.nSetCameraFacingCulling(
          config != null && config.enableCameraFacingCulling);
    }
    if (NativeBridge.isLibLoaded()) {
      NativeBridge.nSetRenderDistance(
          Minecraft.getInstance().options.getEffectiveRenderDistance() * 16);
    }
    if (texturesReady) {
      long blockAtlas = textureManager.getBlockAtlasTexture();
      if (blockAtlas != 0) {
        renderer.bindTexture(blockAtlas, 0);
      }
      long lightmap = textureManager.getLightmapTexture();
      if (lightmap != 0) {
        renderer.bindTexture(lightmap, 1);
      }
    }
    NativeBridge.nSetReuseTerrainFrame(false);
    long frameCtx = renderer.frameCtx();
    if (frameCtx != 0) {
      if (MetalRenderClient.getConfig().enableMetalRendering) {
        long inhousePipeline = renderer.getBackend().getInhousePipelineHandle();
        if (inhousePipeline != 0) {
          NativeBridge.nSetPipelineState(frameCtx, inhousePipeline);
        }
        float skyFactor = resolveSkyLightFactor(camera, tickDelta);
        NativeBridge.nSetSkyBrightness(frameCtx, skyFactor);
        if (argumentBufferHandle == 0 && meshShaderBackend != null &&
            meshShaderBackend.areMeshShadersAvailable() &&
            NativeBridge.isLibLoaded()) {
          long handle0 = renderer.getBackend().getDeviceHandle();
          if (handle0 != 0) {
            argumentBufferHandle = NativeBridge.nCreateBuffer(handle0,
                subChunkUploadCapacity * 16,
                NativeMemory.STORAGE_MODE_SHARED);
          }
        }
        long ibHandle = chunkMesher.getGlobalIndexBuffer();
        if (ibHandle != 0) {
          int drawn = NativeBridge.nDrawAllVisibleChunks(frameCtx, ibHandle);
          lastDrawnChunkCount = drawn;
          MetalRenderProfiler.getInstance().incrementChunksDrawn(drawn);
          if (frameCount < 10 || frameCount % 1000 == 0) {
            MetalLogger.info("frame %d: drew %d chunks",
                frameCount, lastDrawnChunkCount);
          }
        } else {
          lastDrawnChunkCount = 0;
        }
      }
    }
  }

  private static void extractFrustumPlanes(Matrix4f vp, float[] out) {
    out[0] = vp.m30() + vp.m00();
    out[1] = vp.m31() + vp.m01();
    out[2] = vp.m32() + vp.m02();
    out[3] = vp.m33() + vp.m03();
    normalizePlane(out, 0);
    out[4] = vp.m30() - vp.m00();
    out[5] = vp.m31() - vp.m01();
    out[6] = vp.m32() - vp.m02();
    out[7] = vp.m33() - vp.m03();
    normalizePlane(out, 4);
    out[8] = vp.m30() + vp.m10();
    out[9] = vp.m31() + vp.m11();
    out[10] = vp.m32() + vp.m12();
    out[11] = vp.m33() + vp.m13();
    normalizePlane(out, 8);
    out[12] = vp.m30() - vp.m10();
    out[13] = vp.m31() - vp.m11();
    out[14] = vp.m32() - vp.m12();
    out[15] = vp.m33() - vp.m13();
    normalizePlane(out, 12);
    out[16] = vp.m30() + vp.m20();
    out[17] = vp.m31() + vp.m21();
    out[18] = vp.m32() + vp.m22();
    out[19] = vp.m33() + vp.m23();
    normalizePlane(out, 16);
    out[20] = vp.m30() - vp.m20();
    out[21] = vp.m31() - vp.m21();
    out[22] = vp.m32() - vp.m22();
    out[23] = vp.m33() - vp.m23();
    normalizePlane(out, 20);
  }

  private static float resolveSkyLightFactor(Camera camera, float tickDelta) {
    if (camera == null || skyLightLookupFailed) {
      return 1.0f;
    }
    Object attributeProbe = camera.attributeProbe();
    if (attributeProbe == null) {
      return 1.0f;
    }
    try {
      java.lang.reflect.Field factorField = skyLightFactorField;
      java.lang.reflect.Method getValueMethod = skyLightProbeGetValueMethod;
      if (factorField == null || getValueMethod == null) {
        Class<?> attributesClass = Class.forName(
            "net.minecraft.world.attribute.EnvironmentAttributes");
        factorField = attributesClass.getField("SKY_LIGHT_FACTOR");
        getValueMethod = attributeProbe.getClass().getMethod(
            "getValue", factorField.getType(), float.class);
        skyLightFactorField = factorField;
        skyLightProbeGetValueMethod = getValueMethod;
      }
      Object value = getValueMethod.invoke(attributeProbe,
          factorField.get(null), tickDelta);
      if (value instanceof Number number) {
        return number.floatValue();
      }
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      skyLightLookupFailed = true;
    }
    return 1.0f;
  }

  private static void normalizePlane(float[] planes, int offset) {
    float a = planes[offset], b = planes[offset + 1], c = planes[offset + 2];
    float len = (float) Math.sqrt(a * a + b * b + c * c);
    if (len > 0.0f) {
      float invLen = 1.0f / len;
      planes[offset] *= invLen;
      planes[offset + 1] *= invLen;
      planes[offset + 2] *= invLen;
      planes[offset + 3] *= invLen;
    }
  }

  public void endFrame() {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return;
    long frameCtx = renderer.frameCtx();
    if (frameCtx != 0) {
      boolean inWater = false;
      Minecraft mc = Minecraft.getInstance();
      if (mc != null && mc.getCameraEntity() != null) {
        inWater = mc.getCameraEntity().isUnderWater();
      }
      entityRenderer.renderCapturedEntities(frameCtx, inWater);
      NativeBridge.nDrawDeferredWaterPass(frameCtx);
      NativeBridge.nDrawOITPass(frameCtx);
      particleRenderer.render(frameCtx);
      renderBlockOutline(frameCtx);
    }
    renderer.endFrame();
    frameCount++;
  }

  private void renderBlockOutline(long frameCtx) {
    try {
      Minecraft mc = Minecraft.getInstance();
      if (mc == null || mc.level == null || mc.hitResult == null ||
          mc.hitResult.getType() != HitResult.Type.BLOCK) {
        return;
      }
      BlockHitResult hit = (BlockHitResult) mc.hitResult;
      BlockPos pos = hit.getBlockPos();
      BlockState state = mc.level.getBlockState(pos);
      if (state.isAir() || !mc.level.getWorldBorder().isWithinBounds(pos)) {
        return;
      }

      CollisionContext context = mc.getCameraEntity() != null
          ? CollisionContext.of(mc.getCameraEntity())
          : CollisionContext.empty();
      VoxelShape shape = state.getShape(mc.level, pos, context);
      if (shape.isEmpty()) {
        return;
      }

      float bx = (float) (pos.getX() - frameCameraX);
      float by = (float) (pos.getY() - frameCameraY);
      float bz = (float) (pos.getZ() - frameCameraZ);
      outlineEdges.clear();
      shape.forAllEdges((x0, y0, z0, x1, y1, z1) -> outlineEdges.add(new float[] {
          bx + (float) x0, by + (float) y0, bz + (float) z0,
          bx + (float) x1, by + (float) y1, bz + (float) z1
      }));
      if (outlineEdges.isEmpty()) {
        return;
      }

      int lineVertexCount = outlineEdges.size() * 2;
      int drawVertexCount = outlineEdges.size() * 6;
      int scalarCount = lineVertexCount * 3;
      if (outlineVerts.length < scalarCount) {
        outlineVerts = new float[Math.max(scalarCount, outlineVerts.length * 2)];
      }
      int vertexIndex = 0;
      for (float[] edge : outlineEdges) {
        for (int point = 0; point < 2; point++) {
          int edgeOffset = point * 3;
          outlineVerts[vertexIndex++] = edge[edgeOffset];
          outlineVerts[vertexIndex++] = edge[edgeOffset + 1];
          outlineVerts[vertexIndex++] = edge[edgeOffset + 2];
        }
      }

      int dataLen = scalarCount * Float.BYTES;
      if (outlineDataBuf.length < dataLen) {
        outlineDataBuf = new byte[dataLen];
      }
      byte[] data = outlineDataBuf;
      int dataIndex = 0;
      for (int index = 0; index < scalarCount; index++) {
        int bits = Float.floatToRawIntBits(outlineVerts[index]);
        data[dataIndex++] = (byte) bits;
        data[dataIndex++] = (byte) (bits >>> 8);
        data[dataIndex++] = (byte) (bits >>> 16);
        data[dataIndex++] = (byte) (bits >>> 24);
      }
      MetalRenderer renderer = MetalRenderClient.getRenderer();
      if (renderer == null) {
        return;
      }
      long device = renderer.getBackend().getDeviceHandle();
      if (outlineBufferHandle == 0 || dataLen > outlineBufferSize) {
        if (outlineBufferHandle != 0) {
          NativeBridge.nDestroyBuffer(outlineBufferHandle);
        }
        outlineBufferHandle = NativeBridge.nCreateBuffer(
            device, dataLen, NativeMemory.STORAGE_MODE_SHARED);
        outlineBufferSize = dataLen;
      }
      NativeBridge.nUploadBufferData(outlineBufferHandle, data, 0, dataLen);
      NativeBridge.nSetDebugColor(frameCtx, 0.0f, 0.0f, 0.0f, 0.4f);
      NativeBridge.nDrawTriangleBuffer(frameCtx, outlineBufferHandle, drawVertexCount);
    } catch (Exception e) {
      MetalLogger.error("[blockoutline] eww: %s", e.getMessage());
    }
  }

  private int outlineBufferSize = 0;

  private final it.unimi.dsi.fastutil.longs.LongOpenHashSet pendingBuildSet = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
  private final it.unimi.dsi.fastutil.longs.LongArrayList sortedBuildList = new it.unimi.dsi.fastutil.longs.LongArrayList();
  private long[] sortKeyScratch = new long[1024];
  private final it.unimi.dsi.fastutil.longs.LongArrayList sortReorderScratch = new it.unimi.dsi.fastutil.longs.LongArrayList();
  private boolean sortedListDirty = true;
  private int lastSortedSize = 0;
  private int consecutiveHighMeshMsFrames = 0;
  private int consecutiveCoolMeshMsFrames = 0;
  private boolean textureBackoffActive = false;
  private int framesSinceLastSort = 0;
  private float cachedForwardX = 0, cachedForwardZ = 1;
  private int lastScanPlayerCX = Integer.MIN_VALUE, lastScanPlayerCZ = Integer.MIN_VALUE;
  private int lastSortedPlayerCX = Integer.MIN_VALUE, lastSortedPlayerCZ = Integer.MIN_VALUE;
  private int lastScanRenderDist = -1;
  private int turnPriorityFrames = 0;
  private int remainingPrioritizedBuilds = PRIORITIZED_BUILD_STREAK_LIMIT;
  private int cachedThermalState = 0;
  private int lodRefreshCursor = 0;
  private int lodRefreshPlayerCX = Integer.MIN_VALUE;
  private int lodRefreshPlayerCZ = Integer.MIN_VALUE;
  private int lodRefreshThermalBias = Integer.MIN_VALUE;
  private final LodPolicy lodPolicy = new LodPolicy();
  private final long[] lodRecencyKeys = new long[LOD_RECENCY_SCRATCH_SIZE];
  private final int[] lodRecencyFrames = new int[LOD_RECENCY_SCRATCH_SIZE];
  private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap lodRecencyAgeMap =
      new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(LOD_RECENCY_SCRATCH_SIZE);
  private boolean lodRecencyNativeEnabled;
  private int lodRecencyPullCounter;
  private int lodDiagScanCounter;

  private static final class LodCandidate {
    final long key;
    final float impact;
    final int chunkX, chunkY, chunkZ;

    LodCandidate(long key, float impact, int chunkX, int chunkY, int chunkZ) {
      this.key = key;
      this.impact = impact;
      this.chunkX = chunkX;
      this.chunkY = chunkY;
      this.chunkZ = chunkZ;
    }
  }

  private static final class PendingBuildCandidate {
    final long key;
    final int index;
    final int chunkX;
    final int chunkY;
    final int chunkZ;
    final int chunkDist;

    PendingBuildCandidate(long key, int index, int chunkX, int chunkY,
        int chunkZ, int chunkDist) {
      this.key = key;
      this.index = index;
      this.chunkX = chunkX;
      this.chunkY = chunkY;
      this.chunkZ = chunkZ;
      this.chunkDist = chunkDist;
    }
  }

  private static long packChunkKey(int cx, int cy, int cz) {
    return ((long) (cx & 0x3FFFFF) << 42) | ((long) (cy & 0xFFFFF) << 22) |
        (cz & 0x3FFFFF);
  }

  private static int unpackChunkX(long key) {
    int chunkX = (int) ((key >> 42) & 0x3FFFFF);
    if ((chunkX & 0x200000) != 0) {
      chunkX |= ~0x3FFFFF;
    }
    return chunkX;
  }

  private static int unpackChunkY(long key) {
    int chunkY = (int) ((key >> 22) & 0xFFFFF);
    if ((chunkY & 0x80000) != 0) {
      chunkY |= ~0xFFFFF;
    }
    return chunkY;
  }

  private static int unpackChunkZ(long key) {
    int chunkZ = (int) (key & 0x3FFFFF);
    if ((chunkZ & 0x200000) != 0) {
      chunkZ |= ~0x3FFFFF;
    }
    return chunkZ;
  }

  private void buildPendingChunkMeshes(Minecraft mc) {
    if (mc.player == null || mc.level == null) {
      return;
    }
    if (mc.getOverlay() != null) {
      return;
    }
    if (mc.player != null) {
      float yaw = mc.player.getYRot();
      float nextForwardX = (float) -Math.sin(Math.toRadians(yaw));
      float nextForwardZ = (float) Math.cos(Math.toRadians(yaw));
      float turnDot = cachedForwardX * nextForwardX + cachedForwardZ * nextForwardZ;
      cachedForwardX = nextForwardX;
      cachedForwardZ = nextForwardZ;
      if (turnDot < BUILD_SORT_REORDER_DOT_THRESHOLD) {
        if (turnPriorityFrames == 0) {
          if (!pendingBuildSet.isEmpty()) {
            sortedListDirty = true;
          }
          turnPriorityFrames = TURN_PRIORITY_SCAN_FRAMES;
          scanFrontierRing = HOT_LOAD_REBUILD_RANGE + 1;
          scanFrameCounter = 0;
        }
      }
    }
    if (pendingBuildSet.size() < CHUNK_SCAN_SATURATED_THRESHOLD ||
        (frameCount & 1) == 0) {
      scanForPendingChunks(mc);
    }
    if (mc.player != null && chunkMesher.getMeshCount() < maxMeshes) {
      int playerChunkX = mc.player.chunkPosition().x();
      int playerChunkZ = mc.player.chunkPosition().z();
      int playerSectionY = mc.player.getBlockY() >> 4;
      boolean turnBurstActive = turnPriorityFrames > 0;
      int mesherPending = chunkMesher.getPendingCount();
      int visibleBacklog = pendingBuildSet.size() + mesherPending;
      boolean fpsPriorityMode = MetalRenderClient.getConfig() != null &&
          MetalRenderClient.getConfig().prioritizeFpsOverTps;
      long buildBudget = turnBurstActive ? CHUNK_TURN_BUILD_BURST_NS : CHUNK_BUILD_BUDGET_NS;
      int minBuilds = turnBurstActive ? MIN_CHUNK_TURN_BUILDS_PER_FRAME
          : MIN_CHUNK_BUILDS_PER_FRAME;
      int highPrioritySubmissions = turnBurstActive ? TURN_HIGH_PRIORITY_SUBMISSIONS_PER_PASS
          : BASE_HIGH_PRIORITY_SUBMISSIONS_PER_PASS;
      if (mesherPending >= CHUNK_SCAN_SATURATED_THRESHOLD) {
        buildBudget = Math.min(buildBudget, CHUNK_SATURATED_BUILD_BUDGET_NS);
        minBuilds = Math.min(minBuilds, MIN_CHUNK_SATURATED_BUILDS_PER_FRAME);
        highPrioritySubmissions = Math.min(highPrioritySubmissions,
            SATURATED_HIGH_PRIORITY_SUBMISSIONS_PER_PASS);
      } else if (visibleBacklog >= CHUNK_BACKLOG_HEAVY_THRESHOLD) {
        buildBudget = Math.max(buildBudget, CHUNK_HEAVY_BACKLOG_BUILD_BURST_NS);
        minBuilds = Math.max(minBuilds, MIN_CHUNK_HEAVY_BACKLOG_BUILDS_PER_FRAME);
        highPrioritySubmissions = Math.max(highPrioritySubmissions,
            HEAVY_BACKLOG_HIGH_PRIORITY_SUBMISSIONS_PER_PASS);
      } else if (visibleBacklog >= CHUNK_BACKLOG_PRESSURE_THRESHOLD) {
        buildBudget = Math.max(buildBudget, CHUNK_BACKLOG_BUILD_BURST_NS);
        minBuilds = Math.max(minBuilds, MIN_CHUNK_BACKLOG_BUILDS_PER_FRAME);
        highPrioritySubmissions = Math.max(highPrioritySubmissions,
            BACKLOG_HIGH_PRIORITY_SUBMISSIONS_PER_PASS);
      }
      if (fpsPriorityMode) {
        buildBudget = Math.max(buildBudget, CHUNK_BACKLOG_BUILD_BURST_NS);
        minBuilds = Math.max(
            minBuilds, MIN_CHUNK_BACKLOG_BUILDS_PER_FRAME);
        highPrioritySubmissions = Math.max(
            highPrioritySubmissions,
            BACKLOG_HIGH_PRIORITY_SUBMISSIONS_PER_PASS);
      }
      buildFromPendingSet(playerChunkX, playerSectionY, playerChunkZ,
          buildBudget, minBuilds, highPrioritySubmissions);

      if (turnPriorityFrames > 0) {
        turnPriorityFrames--;
      }
    }
  }

  private int scanFrameCounter = 0;
  private int scanFrontierRing = 0;
  private long lastFullRescanNs = 0L;

  private void scanForPendingChunks(Minecraft mc) {
    ClientLevel world = mc.level;
    if (world == null)
      return;
    if (mc.player == null)
      return;
    int renderDist = mc.options.renderDistance().get();
    int mesherPending = chunkMesher.getPendingCount();
    int visibleBacklog = pendingBuildSet.size() + mesherPending;
    boolean scanPressured = visibleBacklog >= CHUNK_SCAN_PRESSURE_THRESHOLD;
    boolean scanSaturated = visibleBacklog >= CHUNK_SCAN_SATURATED_THRESHOLD;
    int closeRange = Math.min(HOT_LOAD_REBUILD_RANGE, renderDist);
    if (scanSaturated) {
      closeRange = Math.min(closeRange, SATURATED_CLOSE_SCAN_RANGE);
    } else if (scanPressured) {
      closeRange = Math.min(closeRange, PRESSURED_CLOSE_SCAN_RANGE);
    }
    int playerChunkX = mc.player.chunkPosition().x();
    int playerChunkZ = mc.player.chunkPosition().z();
    int playerSectionY = mc.player.getBlockY() >> 4;
    if (scanPressured) {
      if (visibleBacklog < CHUNK_SCAN_SATURATED_THRESHOLD ||
          (frameCount % 3) == 0) {
        trimPendingBuildSet(playerChunkX, playerChunkZ, closeRange);
        visibleBacklog = pendingBuildSet.size() + mesherPending;
        scanPressured = visibleBacklog >= CHUNK_SCAN_PRESSURE_THRESHOLD;
        scanSaturated = visibleBacklog >= CHUNK_SCAN_SATURATED_THRESHOLD;
      }
    }
    boolean playerMovedChunk = (playerChunkX != lastScanPlayerCX || playerChunkZ != lastScanPlayerCZ);
    boolean renderDistChanged = (renderDist != lastScanRenderDist);
    if (playerMovedChunk || renderDistChanged) {
      lastScanPlayerCX = playerChunkX;
      lastScanPlayerCZ = playerChunkZ;
      lastScanRenderDist = renderDist;
      sortedListDirty = true;
      if (renderDistChanged) {
        pendingBuildSet.clear();
        scanRingsInRange(world, playerChunkX, playerChunkZ, playerSectionY, 0,
            closeRange);
        scanFrontierRing = closeRange + 1;
        scanFrameCounter = 0;
      } else {
        scanRingsInRange(world, playerChunkX, playerChunkZ, playerSectionY, 0,
            closeRange);
        scanFrontierRing = closeRange + 1;
      }
    }
    long nowNs = System.nanoTime();
    boolean fullRescanDue = lastFullRescanNs == 0L ||
        nowNs - lastFullRescanNs >= FULL_RENDERDIST_RESCAN_INTERVAL_NS;
    scanFrameCounter++;
    if (fullRescanDue) {
      scanRingsInRange(world, playerChunkX, playerChunkZ, playerSectionY, 0,
          renderDist);
      lastFullRescanNs = nowNs;
      scanFrameCounter = 0;
      scanFrontierRing = closeRange + 1;
    } else {
      boolean queuePressure = !pendingBuildSet.isEmpty() || chunkMesher.getPendingCount() > 0;
      int closeRangeRescanInterval = queuePressure
          ? ACTIVE_CLOSE_RANGE_RESCAN_INTERVAL
          : IDLE_CLOSE_RANGE_RESCAN_INTERVAL;
      if (!playerMovedChunk &&
          scanFrameCounter % closeRangeRescanInterval == 0) {
        scanRingsInRange(world, playerChunkX, playerChunkZ, playerSectionY, 0,
            closeRange);
      }
      int frontierStart = Math.max(closeRange + 1, scanFrontierRing);
      int frontierSpan = NORMAL_FRONTIER_RING_SCAN_SPAN;
      if (scanSaturated) {
        frontierSpan = 1;
      } else if (scanPressured) {
        frontierSpan = Math.min(frontierSpan, 2);
      }
      int frontierEnd = Math.min(frontierStart + frontierSpan - 1, renderDist);
      if (frontierStart <= renderDist) {
        scanRingsInRange(world, playerChunkX, playerChunkZ, playerSectionY,
            frontierStart, frontierEnd);
        scanFrontierRing = frontierEnd + 1;
        if (scanFrontierRing > renderDist) {
          scanFrontierRing = closeRange + 1;
        }
      }
    }
    if (turnPriorityFrames > 0 && !scanPressured) {
      scanForwardSector(world, playerChunkX, playerChunkZ, playerSectionY,
          renderDist);
    }
    logServerChunkAvailability(world, playerChunkX, playerChunkZ, renderDist);
  }

  private void scanRingsInRange(ClientLevel world, int playerChunkX,
      int playerChunkZ, int playerSectionY,
      int startRing, int endRing) {
    for (int ring = startRing; ring <= endRing; ring++) {
      for (int dx = -ring; dx <= ring; dx++) {
        for (int dz = -ring; dz <= ring; dz++) {
          if (ring > 0 && Math.abs(dx) < ring && Math.abs(dz) < ring)
            continue;
          int cx = playerChunkX + dx;
          int cz = playerChunkZ + dz;
          queueChunkSectionsIfMissing(world, cx, cz, playerSectionY,
              Math.max(Math.abs(dx), Math.abs(dz)));
        }
      }
    }
  }

  private void scanForwardSector(ClientLevel world, int playerChunkX,
      int playerChunkZ, int playerSectionY,
      int renderDist) {
    int startRing = Math.min(HOT_LOAD_REBUILD_RANGE, renderDist) + 1;
    scanForwardSector(world, playerChunkX, playerChunkZ, playerSectionY,
        startRing, renderDist);
  }

  private void scanForwardSector(ClientLevel world, int playerChunkX,
      int playerChunkZ, int playerSectionY,
      int startRing, int endRing) {
    float minForwardDotSq = TURN_PRIORITY_SCAN_COS_THRESHOLD * TURN_PRIORITY_SCAN_COS_THRESHOLD;
    for (int ring = startRing; ring <= endRing; ring++) {
      for (int dx = -ring; dx <= ring; dx++) {
        for (int dz = -ring; dz <= ring; dz++) {
          if (ring > 0 && Math.abs(dx) < ring && Math.abs(dz) < ring) {
            continue;
          }
          if (dx == 0 && dz == 0) {
            continue;
          }
          float forwardDot = dx * cachedForwardX + dz * cachedForwardZ;
          if (forwardDot <= 0.0f) {
            continue;
          }
          float distSq = (dx * dx) + (dz * dz);
          if (forwardDot * forwardDot < distSq * minForwardDotSq) {
            continue;
          }
          queueChunkSectionsIfMissing(world, playerChunkX + dx,
              playerChunkZ + dz, playerSectionY, ring);
        }
      }
    }
  }

  private void trimPendingBuildSet(int playerChunkX, int playerChunkZ,
      int keepRange) {
    if (pendingBuildSet.isEmpty()) {
      return;
    }
    boolean removed = false;
    it.unimi.dsi.fastutil.longs.LongIterator iterator = pendingBuildSet.iterator();
    while (iterator.hasNext()) {
      long key = iterator.nextLong();
      int chunkX = unpackChunkX(key);
      int chunkZ = unpackChunkZ(key);
      int dx = chunkX - playerChunkX;
      int dz = chunkZ - playerChunkZ;
      int chunkDistance = Math.max(Math.abs(dx), Math.abs(dz));
      if (chunkDistance <= keepRange || isInForwardPriorityCone(dx, dz)) {
        continue;
      }
      iterator.remove();
      removed = true;
    }
    if (removed) {
      sortedListDirty = true;
      MetalLogger.info(
          "queue_trim: keep=%d player=[%d,%d] p=%d cp=%d m=%d",
          keepRange, playerChunkX, playerChunkZ, pendingBuildSet.size(),
          chunkMesher.getPendingCount(), chunkMesher.getMeshCount());
    }
  }

  private int getScanVerticalRange(int chunkDistance) {
    if (chunkDistance <= HOT_LOAD_REBUILD_RANGE) {
      return Integer.MAX_VALUE;
    }
    if (chunkDistance < 16) {
      return MID_DISTANCE_SCAN_VERTICAL_RANGE;
    }
    if (chunkDistance < 24) {
      return FAR_DISTANCE_SCAN_VERTICAL_RANGE;
    }
    return EXTREME_DISTANCE_SCAN_VERTICAL_RANGE;
  }

  private void queueChunkSectionsIfMissing(ClientLevel world, int chunkX,
      int chunkZ, int playerSectionY,
      int chunkDistance) {
    LevelChunk chunk = world.getChunkSource().getChunkNow(chunkX, chunkZ);
    if (chunk == null) {
      if (MetalRenderConfig.isDeepDebugActive()) {
        MetalLogger.debug("scan_skip: chunk [%d,%d]", chunkX, chunkZ);
      }
      return;
    }
    LevelChunkSection[] sections = chunk.getSections();
    int maxVerticalRange = getScanVerticalRange(chunkDistance);
    int highestNonAirSection = Integer.MIN_VALUE;
    if (maxVerticalRange != Integer.MAX_VALUE) {
      for (int sy = sections.length - 1; sy >= 0; sy--) {
        LevelChunkSection section = sections[sy];
        if (section != null && !section.hasOnlyAir()) {
          highestNonAirSection = chunk.getSectionYFromSectionIndex(sy);
          break;
        }
      }
    }
    for (int sy = 0; sy < sections.length; sy++) {
      LevelChunkSection section = sections[sy];
      if (section == null || section.hasOnlyAir())
        continue;
      MetalRenderProfiler.getInstance().incrementChunksScanned(1);
      int worldY = chunk.getSectionYFromSectionIndex(sy);
      if (maxVerticalRange != Integer.MAX_VALUE) {
        boolean withinVerticalWindow = Math.abs(worldY - playerSectionY) <= maxVerticalRange;
        boolean withinSurfaceBand = highestNonAirSection != Integer.MIN_VALUE &&
            worldY >= highestNonAirSection - SURFACE_SECTION_EXTRA_DEPTH;
        if (!withinVerticalWindow && !withinSurfaceBand) {
          continue;
        }
      }
      long sectionKey = packChunkKey(chunkX, worldY, chunkZ);
      if (pendingBuildSet.contains(sectionKey))
        continue;
      if (!chunkMesher.hasMesh(chunkX, worldY, chunkZ)) {
        chunkMesher.noteSectionAvailable(chunkX, worldY, chunkZ);
        if (pendingBuildSet.add(sectionKey)) {
          sortedListDirty = true;
          if (MetalRenderConfig.isDeepDebugActive()) {
            MetalLogger.debug(
                "queue_add: chunk=[%d,%d,%d] dist=%d p=%d",
                chunkX, worldY, chunkZ, chunkDistance, pendingBuildSet.size());
          }
        }
      }
    }
  }

  private long lastChunkDiagMs = 0;

  private void logServerChunkAvailability(ClientLevel world, int playerChunkX,
      int playerChunkZ, int renderDist) {
    if (!MetalRenderConfig.isDeepDebugActive())
      return;
    long now = System.currentTimeMillis();
    if (now - lastChunkDiagMs < 5000)
      return;
    lastChunkDiagMs = now;
    int available = 0, total = 0;
    int maxRingAvail = 0;
    for (int ring = 0; ring <= renderDist; ring++) {
      int ringAvail = 0;
      for (int dx = -ring; dx <= ring; dx++) {
        for (int dz = -ring; dz <= ring; dz++) {
          if (ring > 0 && Math.abs(dx) < ring && Math.abs(dz) < ring)
            continue;
          total++;
          if (world.getChunkSource().getChunkNow(playerChunkX + dx,
              playerChunkZ + dz) != null) {
            available++;
            ringAvail++;
          }
        }
      }
      if (ringAvail > 0)
        maxRingAvail = ring;
    }
    MetalLogger.info(
        "chunk_avail: server=%d/%d (max_ring=%d) m=%d p=%d",
        available, total, maxRingAvail, chunkMesher.getMeshCount(),
        pendingBuildSet.size());
  }

  private int buildFromPendingSet(int playerChunkX, int playerSectionY,
      int playerChunkZ, long budgetNanos,
      int minBuilds, int highPrioritySubmissions) {
    if (pendingBuildSet.isEmpty())
      return 0;
    readinessCache.clear();
    if (sortedListDirty) {
      int currentSize = pendingBuildSet.size();
      int sortInterval = currentSize > 25000 ? 30
          : (currentSize > 15000 ? 20
              : (currentSize > 5000 ? 10
                  : (currentSize > 1000 ? 5 : 3)));
      int playerMovedSinceSort = Math.max(
          Math.abs(playerChunkX - lastSortedPlayerCX),
          Math.abs(playerChunkZ - lastSortedPlayerCZ));
      boolean shouldSort = turnPriorityFrames == TURN_PRIORITY_SCAN_FRAMES
          || currentSize > lastSortedSize + 64
          || currentSize < lastSortedSize * 3 / 4
          || framesSinceLastSort >= sortInterval
          || playerMovedSinceSort > 4
          || sortedBuildList.isEmpty();
      if (shouldSort) {
        sortedBuildList.clear();
        sortedBuildList.addAll(pendingBuildSet);
        final int pcx = playerChunkX;
        final int pcy = playerSectionY;
        final int pcz = playerChunkZ;
        final float fwdX = cachedForwardX;
        final float fwdZ = cachedForwardZ;
        int n = sortedBuildList.size();
        if (sortKeyScratch.length < n) {
          sortKeyScratch = new long[Math.max(n * 2, 1024)];
        }
        for (int i = 0; i < n; i++) {
          long key = sortedBuildList.getLong(i);
          int cx = unpackChunkX(key);
          int cy = unpackChunkY(key);
          int cz = unpackChunkZ(key);
          float dot = (cx - pcx) * fwdX + (cz - pcz) * fwdZ;
          int front = dot >= 0 ? 1 : 0;
          int dist = Math.abs(cx - pcx) + Math.abs(cz - pcz);
          int vd = Math.abs(cy - pcy);
          sortKeyScratch[i] = ((long) front << 63) | ((long) dist << 40)
              | ((long) vd << 32) | (i & 0xFFFFFFFFL);
        }
        java.util.Arrays.sort(sortKeyScratch, 0, n);
        sortReorderScratch.clear();
        for (int i = 0; i < n; i++) {
          sortReorderScratch.add(sortedBuildList.getLong(
              (int) (sortKeyScratch[i] & 0xFFFFFFFFL)));
        }
        sortedBuildList.clear();
        sortedBuildList.addAll(sortReorderScratch);
        lastSortedSize = sortedBuildList.size();
        lastSortedPlayerCX = playerChunkX;
        lastSortedPlayerCZ = playerChunkZ;
        framesSinceLastSort = 0;
      } else {
        framesSinceLastSort++;
      }
      sortedListDirty = false;
    }
    Minecraft mc = Minecraft.getInstance();
    ClientLevel world = mc != null ? mc.level : null;
    if (world == null) {
      return 0;
    }
    long deadline = budgetNanos > 0 ? System.nanoTime() + budgetNanos : Long.MAX_VALUE;
    int maxSubmit = pendingBuildSet.size() > 20000 ? 200 : 500;
    int thermalState = 0;
    if ((frameCount & 31) == 0) {
      cachedThermalState = NativeBridge.isLibLoaded() ? NativeBridge.nGetThermalState() : 0;
    }
    thermalState = cachedThermalState;
    if (thermalState >= 2) {
      budgetNanos = Math.min(budgetNanos, 3_000_000L);
      maxSubmit = Math.min(maxSubmit, 100);
    }
    int built = 0;
    int importantSubmitted = 0;
    int backgroundSubmissions = 0;
    boolean fpsPriorityMode = MetalRenderClient.getConfig() != null &&
        MetalRenderClient.getConfig().prioritizeFpsOverTps;
    int maxInFlightBuildTasks = fpsPriorityMode
        ? FPS_PRIORITY_MAX_IN_FLIGHT_BUILD_TASKS
        : MAX_IN_FLIGHT_BUILD_TASKS;
    int backgroundInFlightLimit = Math.max(1, maxInFlightBuildTasks - RESERVED_PRIORITY_IN_FLIGHT_SLOTS);
    int backgroundSubmissionBudget = turnPriorityFrames > 0
        ? TURN_PRIORITY_BACKGROUND_SUBMISSIONS_PER_PASS
        : NORMAL_BACKGROUND_SUBMISSIONS_PER_PASS;
    if (fpsPriorityMode) {
      backgroundInFlightLimit = maxInFlightBuildTasks;
      backgroundSubmissionBudget = Math.max(
          backgroundSubmissionBudget,
          FPS_PRIORITY_NORMAL_BACKGROUND_SUBMISSIONS_PER_PASS);
    }
    int currentMeshCount = chunkMesher.getMeshCount();
    while (!sortedBuildList.isEmpty() && built < maxSubmit &&
        currentMeshCount < maxMeshes) {
      if (budgetNanos > 0 && built >= minBuilds &&
          System.nanoTime() >= deadline)
        break;
      int currentPending = chunkMesher.getPendingCount();
      if (currentPending >= maxInFlightBuildTasks) {
        break;
      }
      PendingBuildCandidate importantCandidate = null;
      PendingBuildCandidate normalCandidate = null;
      int index = 0;
      final int baseScanLimit = (pendingBuildSet.size() > 10000 && budgetNanos > 3_000_000L) ? 256 : 128;
      int scanLimit = Math.min(baseScanLimit, sortedBuildList.size());
      while (true) {
        while (index < scanLimit) {
          long key = sortedBuildList.get(index);
          int cx = unpackChunkX(key);
          int cy = unpackChunkY(key);
          int cz = unpackChunkZ(key);
          if (chunkMesher.hasMesh(cx, cy, cz)) {
            pendingBuildSet.remove(key);
            sortedBuildList.remove(index);
            scanLimit = Math.min(baseScanLimit, sortedBuildList.size());
            continue;
          }
          int dx = cx - playerChunkX;
          int dz = cz - playerChunkZ;
          int chunkDist = Math.max(Math.abs(dx), Math.abs(dz));
          boolean bypassReadiness = chunkDist <= IMPORTANT_REBUILD_CHUNK_RANGE;
          if (!bypassReadiness && !isSectionBuildReady(world, cx, cy, cz)) {
            if (MetalRenderConfig.isDeepDebugActive()) {
              MetalLogger.debug(
                  "build_defer: chunk=[%d,%d,%d] dist=%d",
                  cx, cy, cz, chunkDist);
            }
            index++;
            continue;
          }
          PendingBuildCandidate candidate = new PendingBuildCandidate(
              key, index, cx, cy, cz, chunkDist);
          boolean importantBuild = importantSubmitted < highPrioritySubmissions &&
              isImportantPendingBuild(dx, dz, chunkDist);
          if (importantBuild) {
            importantCandidate = candidate;
          } else if (normalCandidate == null) {
            normalCandidate = candidate;
          }
          if (importantCandidate != null && normalCandidate != null) {
            break;
          }
          index++;
        }
        if (importantCandidate != null || normalCandidate != null) {
          break;
        }
        if (scanLimit >= sortedBuildList.size()) {
          break;
        }
        scanLimit = Math.min(scanLimit + 128, sortedBuildList.size());
      }

      if (importantCandidate == null && normalCandidate == null) {
        break;
      }

      final PendingBuildCandidate candidate;
      final boolean highPriority;
      if (importantCandidate == null) {
        candidate = normalCandidate;
        highPriority = false;
        remainingPrioritizedBuilds = PRIORITIZED_BUILD_STREAK_LIMIT;
      } else if (normalCandidate == null) {
        candidate = importantCandidate;
        highPriority = true;
        remainingPrioritizedBuilds = Math.max(0, remainingPrioritizedBuilds - 1);
      } else if (remainingPrioritizedBuilds <= 0) {
        candidate = normalCandidate;
        highPriority = false;
        remainingPrioritizedBuilds = PRIORITIZED_BUILD_STREAK_LIMIT;
      } else if (importantCandidate.index <= normalCandidate.index) {
        candidate = importantCandidate;
        highPriority = true;
        remainingPrioritizedBuilds = Math.max(0, remainingPrioritizedBuilds - 1);
      } else {
        candidate = normalCandidate;
        highPriority = false;
        remainingPrioritizedBuilds = PRIORITIZED_BUILD_STREAK_LIMIT;
      }

      pendingBuildSet.remove(candidate.key);
      sortedBuildList.remove(candidate.index);

      boolean interactivePriority = highPriority &&
          candidate.chunkDist <= INTERACTIVE_PRIORITY_CHUNK_RANGE &&
          turnPriorityFrames > 0 &&
          chunkMesher.getInteractiveQueueDepth() < MAX_INTERACTIVE_PRIORITY_QUEUE_DEPTH;
      if (!highPriority && !interactivePriority) {
        if (backgroundSubmissions >= backgroundSubmissionBudget) {
          break;
        }
        if (chunkMesher.getPendingCount() >= backgroundInFlightLimit) {
          break;
        }
      }
      boolean submitted;
      if (interactivePriority) {
        submitted = chunkMesher.buildMeshFromWorldInteractive(
            candidate.chunkX, candidate.chunkY, candidate.chunkZ);
      } else {
        submitted = chunkMesher.buildMeshFromWorld(candidate.chunkX, candidate.chunkY,
            candidate.chunkZ, highPriority);
      }
      if (!submitted) {
        pendingBuildSet.add(candidate.key);
        sortedListDirty = true;
        break;
      }
      if (built < 5 || MetalRenderConfig.isDeepDebugActive()) {
        MetalLogger.debug(
            "build_queue: chunk=[%d,%d,%d] high=%s int=%s p=%d m=%d",
            candidate.chunkX, candidate.chunkY, candidate.chunkZ,
            highPriority, interactivePriority,
            pendingBuildSet.size(), chunkMesher.getMeshCount());
      }
      if (highPriority) {
        importantSubmitted++;
      } else {
        backgroundSubmissions++;
      }
      built++;
    }
    if (built > 0 && System.currentTimeMillis() - lastQueuePressureLogMs >= 1000) {
      lastQueuePressureLogMs = System.currentTimeMillis();
      MetalLogger.info(
          "build_pass: built=%d imp=%d bg=%d p=%d cp=%d m=%d bud=%d",
          built, importantSubmitted, backgroundSubmissions,
          pendingBuildSet.size(), chunkMesher.getPendingCount(),
          chunkMesher.getMeshCount(), budgetNanos);
    }
    return built;
  }

  public MetalEntityRenderer getEntityRenderer() {
    return entityRenderer;
  }

  public MetalParticleRenderer getParticleRenderer() {
    return particleRenderer;
  }

  public CustomChunkMesher getChunkMesher() {
    return chunkMesher;
  }

  public int getLastDrawnChunkCount() {
    return lastDrawnChunkCount;
  }

  public MetalTextureManager getTextureManager() {
    return textureManager;
  }

  private void refreshLodTiers(Minecraft mc) {
    MetalRenderConfig config = MetalRenderClient.getConfig();
    if (config == null || mc.player == null) {
      return;
    }
    int thermalBias = config.lodThermalAdaptive
        ? (cachedThermalState >= 3 ? 2 : (cachedThermalState >= 2 ? 1 : 0))
        : 0;
    CustomChunkMesher.setLodThermalBias(thermalBias);
    int playerChunkX = mc.player.chunkPosition().x();
    int playerChunkZ = mc.player.chunkPosition().z();
    if (playerChunkX != lodRefreshPlayerCX || playerChunkZ != lodRefreshPlayerCZ ||
        thermalBias != lodRefreshThermalBias) {
      lodRefreshCursor = 0;
      lodRefreshPlayerCX = playerChunkX;
      lodRefreshPlayerCZ = playerChunkZ;
      lodRefreshThermalBias = thermalBias;
    }
    int refreshBudget = cachedThermalState >= 3 ? 1
        : (cachedThermalState >= 2 ? 1 : MAX_LOD_REFRESH_SUBMITS_PER_PASS);
    if (pendingBuildSet.size() >= LOD_REFRESH_PENDING_LIMIT ||
        chunkMesher.getPendingCount() >= LOD_REFRESH_IN_FLIGHT_LIMIT) {
      refreshBudget = 1;
    }
    int meshCount = chunkMesher.getMeshSnapshotSize();
    if (meshCount == 0) {
      lodRefreshCursor = 0;
      return;
    }
    int queued = 0;
    int inspected = 0;
    while (inspected < meshCount && inspected < MAX_LOD_SCAN_PER_PASS &&
        queued < refreshBudget) {
      if (lodRefreshCursor >= meshCount) {
        lodRefreshCursor = 0;
      }
      CustomChunkMesher.ChunkMeshData mesh = chunkMesher.getMeshSnapshotAt(lodRefreshCursor++);
      inspected++;
      if (mesh == null) {
        continue;
      }
      int dx = Math.abs(mesh.chunkX - playerChunkX);
      int dz = Math.abs(mesh.chunkZ - playerChunkZ);
      int chunkDist = Math.max(dx, dz);
      int targetLod = CustomChunkMesher.lodTierForDistance(chunkDist);
      if (mesh.lodTier == targetLod) {
        continue;
      }
      long key = packChunkKey(mesh.chunkX, mesh.chunkY, mesh.chunkZ);
      if (pendingBuildSet.contains(key) || chunkMesher.isBuildPending(mesh.chunkX, mesh.chunkY, mesh.chunkZ)) {
        continue;
      }
      chunkMesher.markDirty(mesh.chunkX, mesh.chunkY, mesh.chunkZ);
      if (pendingBuildSet.add(key)) {
        sortedListDirty = true;
        queued++;
      }
    }
  }

  private void updateLodRecencyEviction(Minecraft mc) {
    if (mc == null || lodRecencyNativeEnabled ||
        ++lodRecencyPullCounter < LOD_RECENCY_PULL_INTERVAL) {
      return;
    }
    lodRecencyPullCounter = 0;
    if (chunkMesher.getMeshSnapshotSize() == 0 ||
        chunkMesher.getPendingCount() > LOD_REFRESH_IN_FLIGHT_LIMIT) {
      return;
    }

    int count = Math.min(chunkMesher.getMeshSnapshotSize(), lodRecencyKeys.length);
    int staleCount = 0;
    int currentFrame = frameCount;
    for (int i = 0; i < count; i++) {
      CustomChunkMesher.ChunkMeshData mesh = chunkMesher.getMeshSnapshotAt(i);
      if (mesh == null) {
        continue;
      }
      long key = packChunkKey(mesh.chunkX, mesh.chunkY, mesh.chunkZ);
      lodRecencyKeys[staleCount] = key;
      int lastDrawn = lodRecencyAgeMap.getOrDefault(key, currentFrame);
      lodRecencyFrames[staleCount] = lastDrawn;
      staleCount++;
    }
    if (staleCount == 0) {
      return;
    }

    int demoted = 0;
    for (int i = 0; i < staleCount && demoted < MAX_LOD_RECENCY_DEMOTIONS_PER_PASS; i++) {
      int oldest = i;
      for (int j = i + 1; j < staleCount; j++) {
        if (lodRecencyFrames[j] < lodRecencyFrames[oldest]) {
          oldest = j;
        }
      }
      if (oldest != i) {
        long key = lodRecencyKeys[i];
        lodRecencyKeys[i] = lodRecencyKeys[oldest];
        lodRecencyKeys[oldest] = key;
        int frame = lodRecencyFrames[i];
        lodRecencyFrames[i] = lodRecencyFrames[oldest];
        lodRecencyFrames[oldest] = frame;
      }
      if (currentFrame - lodRecencyFrames[i] < LodPolicy.RECENCY_STALE_TICKS) {
        break;
      }
      int x = unpackChunkX(lodRecencyKeys[i]);
      int y = unpackChunkY(lodRecencyKeys[i]);
      int z = unpackChunkZ(lodRecencyKeys[i]);
      if (chunkMesher.isBuildPending(x, y, z)) {
        continue;
      }
      chunkMesher.markDirty(x, y, z);
      if (pendingBuildSet.add(lodRecencyKeys[i])) {
        sortedListDirty = true;
        demoted++;
      }
    }
  }

  private void pruneFarMeshes(Minecraft mc, org.joml.Vector3f camPos) {
    if (mc.player == null)
      return;
    int renderDist = mc.options.renderDistance().get();
    int extraMarginChunks = shouldPinLoadedMeshes(mc) ? 8 : 2;
    float maxDist = (renderDist + extraMarginChunks) * 16.0f;
    float maxDistSq = maxDist * maxDist;
    var iter = chunkMesher.getAllMeshes().iterator();
    while (iter.hasNext()) {
      CustomChunkMesher.ChunkMeshData mesh = iter.next();
      float dx = mesh.chunkX * 16.0f + 8.0f - camPos.x;
      float dz = mesh.chunkZ * 16.0f + 8.0f - camPos.z;
      if (dx * dx + dz * dz > maxDistSq) {
        chunkMesher.removeMesh(mesh.chunkX, mesh.chunkY, mesh.chunkZ);
      }
    }
  }

  public static boolean shouldBlitAt(String timingPoint) {
    return "flip_head".equals(timingPoint) || "before_hand".equals(timingPoint);
  }

  private boolean shouldPinLoadedMeshes(Minecraft mc) {
    return mc != null &&
        mc.options.renderDistance().get() >= PINNED_RENDER_DISTANCE;
  }

  public static String getBlitTimingMode() {
    return "flip_head";
  }

  public void forceBlitNow() {
    if (shouldSuspendBlitForScreenshot()) {
      return;
    }
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return;
    long handle = renderer.getHandle();
    if (handle == 0)
      return;
    Minecraft mc = Minecraft.getInstance();
    if (mc != null && mc.getMainRenderTarget() != null) {
      CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
      try (RenderPass pass = encoder.createRenderPass(
          () -> "metalrender_terrain_blit",
          mc.getMainRenderTarget().getColorTextureView(),
          java.util.OptionalInt.empty())) {
        ioSurfaceBlitter.blit(handle);
      }
    } else {
      ioSurfaceBlitter.blit(handle);
    }
  }

  private boolean shouldSuspendBlitForScreenshot() {
    Minecraft mc = Minecraft.getInstance();
    if (mc == null || mc.options == null || mc.options.keyScreenshot == null) {
      return false;
    }
    if (mc.options.keyScreenshot.isDown()) {
      screenshotBlitCooldownFrames = 4;
      return true;
    }
    if (screenshotBlitCooldownFrames > 0) {
      screenshotBlitCooldownFrames--;
      return true;
    }
    return false;
  }

  public void forceBlitDepthNow(int width, int height) {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return;
    long handle = renderer.getHandle();
    if (handle == 0)
      return;
    ioSurfaceBlitter.blitDepth(handle, width, height);
  }

  public boolean uploadDepthDirect(int mcDepthTexId, int width, int height) {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return false;
    long handle = renderer.getHandle();
    if (handle == 0)
      return false;
    return ioSurfaceBlitter.uploadDepthDirect(handle, mcDepthTexId, width,
        height);
  }

  public boolean blitDepthViaFBO(int mcDepthTexId, int mcFboId, int width,
      int height) {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return false;
    long handle = renderer.getHandle();
    if (handle == 0)
      return false;
    return ioSurfaceBlitter.blitDepthViaFBO(handle, mcDepthTexId, mcFboId,
        width, height);
  }

  public boolean isReady() {
    return worldLoaded && renderingActive;
  }

  public void applyFeatureConfig(MetalRenderConfig config) {
    if (config == null) {
      return;
    }
    gpuDrivenEnabled = false;
    cullingOrcreator.setActive(config.enableClusterFrustumCulling);
    cullingOrcreator.setCpuFallbackEnabled(config.enableClusterFrustumCulling);
    translucencySorter.setActive(config.enableGpuTranslucencySort);
    boolean requestArgumentBuffers = config.enableArgumentBuffers || config.enableIndirectCommandBuffers;
    if (NativeBridge.isLibLoaded()) {
      NativeBridge.nSetFeatureFlags(
          config.enableIndirectCommandBuffers, config.enableMeshShaders,
          requestArgumentBuffers, config.enableProgrammableBlending);
      NativeBridge.nSetCameraFacingCulling(config.enableCameraFacingCulling);
      gpuDrivenEnabled = NativeBridge.nIsGPUDrivenActive();
      MetalLogger.info(
          "runtime_features: mesh=%s gpu=%s arg=%s",
          NativeBridge.nAreMeshShadersActive(), gpuDrivenEnabled,
          NativeBridge.nAreArgumentBuffersActive());
    }
  }

  public void onConfigScreenClosed() {
    if (!worldLoaded || !renderingActive) {
      return;
    }
    chunkMesher.clearAllMeshes();
    pendingBuildSet.clear();
    sortedBuildList.clear();
    sortedListDirty = true;
    scanFrameCounter = 0;
    scanFrontierRing = 0;
    lastScanPlayerCX = Integer.MIN_VALUE;
    lastScanPlayerCZ = Integer.MIN_VALUE;
    lastScanRenderDist = -1;
    Minecraft mc = Minecraft.getInstance();
    if (mc != null && mc.player != null && mc.level != null) {
      int playerChunkX = mc.player.chunkPosition().x();
      int playerChunkZ = mc.player.chunkPosition().z();
      int playerSectionY = mc.player.getBlockY() >> 4;
      int renderDist = mc.options.renderDistance().get();
      scanRingsInRange(mc.level, playerChunkX, playerChunkZ, playerSectionY, 0,
          renderDist);
    }
  }

  public void onChunkLoaded(int chunkX, int chunkZ, LevelChunk chunk) {
    if (!worldLoaded || !renderingActive) {
      if (!loggedChunkLoadDropNotReady) {
        loggedChunkLoadDropNotReady = true;
        MetalLogger.warn(
            "chunk load drop [%d,%d] (loaded=%s active=%s)",
            chunkX, chunkZ, worldLoaded, renderingActive);
      }
      return;
    }
    loggedChunkLoadDropNotReady = false;
    boolean highPriorityChunk = shouldPrioritizeLoadedChunk(chunkX, chunkZ);
    Minecraft mc = Minecraft.getInstance();
    int playerChunkX = mc != null && mc.player != null
        ? mc.player.chunkPosition().x()
        : Integer.MIN_VALUE;
    int playerChunkZ = mc != null && mc.player != null
        ? mc.player.chunkPosition().z()
        : Integer.MIN_VALUE;
    int loadedChunkDistance = mc != null && mc.player != null
        ? Math.max(Math.abs(chunkX - playerChunkX),
            Math.abs(chunkZ - playerChunkZ))
        : Integer.MAX_VALUE;
    boolean immediateBuildChunk = loadedChunkDistance <= IMMEDIATE_LOADED_CHUNK_BUILD_RANGE;
    int playerSectionY = mc != null && mc.player != null
        ? mc.player.getBlockY() >> 4
        : Integer.MIN_VALUE;
    LevelChunkSection[] sections = chunk.getSections();
    int nonAirSections = 0;
    for (int sy = 0; sy < sections.length; sy++) {
      LevelChunkSection section = sections[sy];
      if (section == null || section.hasOnlyAir())
        continue;
      nonAirSections++;
      int worldY = chunk.getSectionYFromSectionIndex(sy);
      chunkMesher.noteSectionAvailable(chunkX, worldY, chunkZ);
      boolean highPrioritySection = highPriorityChunk
          && Math.abs(worldY - playerSectionY) <= HIGH_PRIORITY_LOADED_VERTICAL_RANGE;
      if (highPrioritySection && immediateBuildChunk && mc != null &&
          mc.level != null &&
          isSectionBuildReady(mc.level, chunkX, worldY, chunkZ) &&
          !chunkMesher.hasMesh(chunkX, worldY, chunkZ)) {
        if (!chunkMesher.buildMeshFromWorld(chunkX, worldY, chunkZ, false, true)) {
          enqueueSectionBuild(chunkX, worldY, chunkZ);
        }
      } else {
        enqueueSectionBuild(chunkX, worldY, chunkZ);
      }
      refreshLoadedNeighborSection(chunkX - 1, worldY, chunkZ);
      refreshLoadedNeighborSection(chunkX + 1, worldY, chunkZ);
      refreshLoadedNeighborSection(chunkX, worldY - 1, chunkZ);
      refreshLoadedNeighborSection(chunkX, worldY + 1, chunkZ);
      refreshLoadedNeighborSection(chunkX, worldY, chunkZ - 1);
      refreshLoadedNeighborSection(chunkX, worldY, chunkZ + 1);
    }
    MetalLogger.info(
        "chunk_load: c=[%d,%d] sec=%d air=%d d=%d imm=%s pri=%s p=%d cp=%d",
        chunkX, chunkZ, sections.length, nonAirSections, loadedChunkDistance,
        immediateBuildChunk, highPriorityChunk, pendingBuildSet.size(),
        chunkMesher.getPendingCount());
    requeueNeighboursNowReady(mc, chunkX - 1, chunkZ);
    requeueNeighboursNowReady(mc, chunkX + 1, chunkZ);
    requeueNeighboursNowReady(mc, chunkX, chunkZ - 1);
    requeueNeighboursNowReady(mc, chunkX, chunkZ + 1);
  }

  private void requeueNeighboursNowReady(Minecraft mc, int chunkX, int chunkZ) {
    if (mc == null || mc.level == null || mc.player == null) {
      return;
    }
    LevelChunk chunk = mc.level.getChunkSource().getChunkNow(chunkX, chunkZ);
    if (chunk == null) {
      return;
    }
    LevelChunkSection[] sections = chunk.getSections();
    int queued = 0;
    for (int sy = 0; sy < sections.length; sy++) {
      LevelChunkSection section = sections[sy];
      if (section == null || section.hasOnlyAir()) {
        continue;
      }
      MetalRenderProfiler.getInstance().incrementChunksScanned(1);
      int worldY = chunk.getSectionYFromSectionIndex(sy);
      if (!chunkMesher.hasMesh(chunkX, worldY, chunkZ) &&
          isSectionBuildReady(mc.level, chunkX, worldY, chunkZ)) {
        chunkMesher.noteSectionAvailable(chunkX, worldY, chunkZ);
        if (pendingBuildSet.add(packChunkKey(chunkX, worldY, chunkZ))) {
          sortedListDirty = true;
          queued++;
        }
      }
    }
    if (queued > 0 || MetalRenderConfig.isDeepDebugActive()) {
      MetalLogger.debug("requeue_ready: nb=[%d,%d] q=%d p=%d",
          chunkX, chunkZ, queued, pendingBuildSet.size());
    }
  }

  private boolean shouldPrioritizeLoadedChunk(int chunkX, int chunkZ) {
    Minecraft mc = Minecraft.getInstance();
    if (mc == null || mc.player == null)
      return false;
    int playerChunkX = mc.player.chunkPosition().x();
    int playerChunkZ = mc.player.chunkPosition().z();
    int dx = chunkX - playerChunkX;
    int dz = chunkZ - playerChunkZ;
    int chunkDistance = Math.max(Math.abs(dx), Math.abs(dz));
    if (chunkDistance <= HOT_LOAD_REBUILD_RANGE) {
      return true;
    }
    if (turnPriorityFrames <= 0 ||
        chunkDistance > TURN_PRIORITY_LOADED_CHUNK_RANGE) {
      return false;
    }
    return isInForwardPriorityCone(dx, dz);
  }

  private boolean isInForwardPriorityCone(int dx, int dz) {
    float forwardDot = dx * cachedForwardX + dz * cachedForwardZ;
    if (forwardDot <= 0.0f) {
      return false;
    }
    float distSq = (dx * dx) + (dz * dz);
    float minForwardDotSq = TURN_PRIORITY_SCAN_COS_THRESHOLD * TURN_PRIORITY_SCAN_COS_THRESHOLD;
    return forwardDot * forwardDot >= distSq * minForwardDotSq;
  }

  private boolean isImportantPendingBuild(int dx, int dz, int chunkDist) {
    if (chunkDist <= IMPORTANT_REBUILD_CHUNK_RANGE) {
      return true;
    }
    return turnPriorityFrames > 0 &&
        chunkDist <= TURN_PRIORITY_LOADED_CHUNK_RANGE &&
        isInForwardPriorityCone(dx, dz);
  }

  private boolean isSectionBuildReady(ClientLevel world, int chunkX, int chunkY,
      int chunkZ) {
    long pKey = readinessColumnKey(chunkX, chunkZ);
    if (readinessCache.containsKey(pKey)) {
      return readinessCache.get(pKey);
    }
    var source = world.getChunkSource();
    if (source.getChunkNow(chunkX, chunkZ) == null) {
      readinessCache.put(pKey, false);
      return false;
    }
    readinessCache.put(pKey, true);
    return true;
  }

  private static long readinessColumnKey(int chunkX, int chunkZ) {
    return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
  }

  private static boolean readyNeighborCheck(Object source, int chunkX, int chunkZ) {
    if (!(source instanceof net.minecraft.world.level.chunk.ChunkSource src)) {
      return true;
    }
    return src.getChunkNow(chunkX - 1, chunkZ) != null &&
        src.getChunkNow(chunkX + 1, chunkZ) != null &&
        src.getChunkNow(chunkX, chunkZ - 1) != null &&
        src.getChunkNow(chunkX, chunkZ + 1) != null;
  }

  private void updateTextureBackoffState() {
    BuildBudgetEstimator estimator = PerformanceController.getBudgetEstimator();
    double meshMs = estimator != null ? estimator.getEwmaMeshMs() : 0.0;
    if (meshMs > TEXTURE_BACKOFF_HARD_TRIP_MESH_MS) {
      textureBackoffActive = true;
      consecutiveHighMeshMsFrames = TEXTURE_BACKOFF_TRIP_CONSEC;
      consecutiveCoolMeshMsFrames = 0;
      return;
    }
    if (meshMs > TEXTURE_BACKOFF_TRIP_MESH_MS) {
      consecutiveHighMeshMsFrames++;
      consecutiveCoolMeshMsFrames = 0;
      if (consecutiveHighMeshMsFrames >= TEXTURE_BACKOFF_TRIP_CONSEC) {
        textureBackoffActive = true;
      }
    } else if (meshMs < TEXTURE_BACKOFF_RECOVERY_MESH_MS) {
      consecutiveCoolMeshMsFrames++;
      consecutiveHighMeshMsFrames = 0;
      if (consecutiveCoolMeshMsFrames >= TEXTURE_BACKOFF_RECOVER_CONSEC) {
        textureBackoffActive = false;
      }
    } else {
      consecutiveHighMeshMsFrames = 0;
      consecutiveCoolMeshMsFrames = 0;
    }
  }

  private void enqueueSectionBuild(int chunkX, int worldY, int chunkZ) {
    if (!chunkMesher.hasMesh(chunkX, worldY, chunkZ)) {
      if (pendingBuildSet.add(packChunkKey(chunkX, worldY, chunkZ))) {
        sortedListDirty = true;
      }
    }
  }

  private void refreshLoadedNeighborSection(int chunkX, int worldY,
      int chunkZ) {
    if (!chunkMesher.hasMeshIgnoreDirty(chunkX, worldY, chunkZ)) {
      return;
    }
    chunkMesher.markDirty(chunkX, worldY, chunkZ);
    enqueueSectionBuild(chunkX, worldY, chunkZ);
  }

  public void scheduleSectionRebuild(int blockX, int blockY, int blockZ) {
    if (!worldLoaded || !renderingActive) {
      if (!loggedBlockUpdateDropNotReady) {
        loggedBlockUpdateDropNotReady = true;
        MetalLogger.warn(
            "block rebuild drop [%d,%d,%d]",
            blockX, blockY, blockZ);
      }
      return;
    }
    loggedBlockUpdateDropNotReady = false;
    int cx = blockX >> 4;
    int cy = blockY >> 4;
    int cz = blockZ >> 4;
    chunkMesher.noteBlockUpdate(cx, cy, cz);
    chunkMesher.markDirty(cx, cy, cz);
    delayBlockSectionRebuild(cx, cy, cz);
    int lx = blockX & 15;
    int ly = blockY & 15;
    int lz = blockZ & 15;
    if (lx == 0) {
      markDirtyAndQueue(cx - 1, cy, cz);
    } else if (lx == 15) {
      markDirtyAndQueue(cx + 1, cy, cz);
    }
    if (ly == 0) {
      markDirtyAndQueue(cx, cy - 1, cz);
    } else if (ly == 15) {
      markDirtyAndQueue(cx, cy + 1, cz);
    }
    if (lz == 0) {
      markDirtyAndQueue(cx, cy, cz - 1);
    } else if (lz == 15) {
      markDirtyAndQueue(cx, cy, cz + 1);
    }
    MetalLogger.info(
        "block_rebuild: b=[%d,%d,%d] s=[%d,%d,%d] p=%d cp=%d m=%d",
        blockX, blockY, blockZ, cx, cy, cz, pendingBuildSet.size(),
        chunkMesher.getPendingCount(), chunkMesher.getMeshCount());
  }

  private void markDirtyAndQueue(int chunkX, int sectionY, int chunkZ) {
    chunkMesher.markDirty(chunkX, sectionY, chunkZ);
    delayBlockSectionRebuild(chunkX, sectionY, chunkZ);
  }

  private void delayBlockSectionRebuild(int chunkX, int sectionY, int chunkZ) {
    long key = packChunkKey(chunkX, sectionY, chunkZ);
    int dueFrame = frameCount + 2;
    int existingDue = delayedBlockRebuildFrames.get(key);
    delayedBlockRebuildFrames.put(key, Math.max(existingDue, dueFrame));
  }

  private void releaseDelayedBlockRebuilds() {
    if (delayedBlockRebuildFrames.isEmpty()) {
      return;
    }
    long[] keys = delayedBlockRebuildFrames.keySet().toLongArray();
    for (long key : keys) {
      if (delayedBlockRebuildFrames.get(key) > frameCount) {
        continue;
      }
      delayedBlockRebuildFrames.remove(key);
      if (pendingBuildSet.add(key)) {
        sortedListDirty = true;
      }
    }
  }

  public int[] getCameraFacingCullStats() {
    if (NativeBridge.isLibLoaded()) {
      NativeBridge.nGetCameraFacingCullStats(cameraFacingCullStats);
    }
    return cameraFacingCullStats;
  }

}
