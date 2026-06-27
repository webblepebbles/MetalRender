package com.pebbles_boon.metalrender.render;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.backend.MetalRenderer;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.culling.AsyncCullTask;
import com.pebbles_boon.metalrender.culling.CullingOrcreator;
import com.pebbles_boon.metalrender.culling.CullingOrcreator;
import com.pebbles_boon.metalrender.culling.FrustumCuller;
import com.pebbles_boon.metalrender.culling.HiZController;
import com.pebbles_boon.metalrender.draw.TerrainIndirectDraw;
import com.pebbles_boon.metalrender.entity.MetalEntityRenderer;
import com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.nativebridge.NativeMemory;
import com.pebbles_boon.metalrender.particle.MetalParticleRenderer;
import com.pebbles_boon.metalrender.render.chunk.CustomChunkMesher;
import com.pebbles_boon.metalrender.sodium.backend.MeshShaderBackend;
import com.pebbles_boon.metalrender.performance.BuildBudgetEstimator;
import com.pebbles_boon.metalrender.performance.PerformanceController;
import com.pebbles_boon.metalrender.performance.MetalRenderProfiler;
import com.pebbles_boon.metalrender.sort.TranslucencySorter;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.joml.Matrix4f;
import org.joml.Vector3f;

@SuppressWarnings("unused")
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
  private static final long CHUNK_TURN_BUILD_BURST_NS = 6_000_000L;
  private static final int MIN_CHUNK_TURN_BUILDS_PER_FRAME = 18;
  private static final long CHUNK_BUILD_WAIT_BUDGET_NS = 1_000_000L;
  private static final int MIN_CHUNK_BUILDS_DURING_WAIT = 2;
  private static final int BASE_HIGH_PRIORITY_SUBMISSIONS_PER_PASS = 8;
  private static final int BACKLOG_HIGH_PRIORITY_SUBMISSIONS_PER_PASS = 16;
  private static final int HEAVY_BACKLOG_HIGH_PRIORITY_SUBMISSIONS_PER_PASS = 24;
  private static final int TURN_HIGH_PRIORITY_SUBMISSIONS_PER_PASS = 24;
  private static final int WAIT_HIGH_PRIORITY_SUBMISSIONS_PER_PASS = 8;
  private static final int SATURATED_HIGH_PRIORITY_SUBMISSIONS_PER_PASS = 2;
  private static final int PRIORITIZED_BUILD_STREAK_LIMIT = 2;
  private static final int MAX_IN_FLIGHT_BUILD_TASKS = 192;
  private static final int RESERVED_PRIORITY_IN_FLIGHT_SLOTS = 64;
  private static final int FPS_PRIORITY_MAX_IN_FLIGHT_BUILD_TASKS = 192;
  private static final int FPS_PRIORITY_LOADING_BACKGROUND_SUBMISSIONS_PER_PASS = 128;
  private static final int FPS_PRIORITY_NORMAL_BACKGROUND_SUBMISSIONS_PER_PASS = 96;
  private static final long CHUNK_BUILD_WAIT_WINDOW_NS = 3_000_000L;
  private static final int HIGH_PRIORITY_LOADED_VERTICAL_RANGE = 3;
  private static final int MID_DISTANCE_SCAN_VERTICAL_RANGE = 8;
  private static final int FAR_DISTANCE_SCAN_VERTICAL_RANGE = 5;
  private static final int EXTREME_DISTANCE_SCAN_VERTICAL_RANGE = 3;
  private static final int SURFACE_SECTION_EXTRA_DEPTH = 2;
  private static final int TURN_PRIORITY_LOADED_CHUNK_RANGE = 24;
  private static final float BUILD_SORT_REORDER_DOT_THRESHOLD = 0.9848f;
  private static final int TURN_PRIORITY_SCAN_FRAMES = 12;
  private static final int TURN_PRIORITY_FORWARD_SCAN_DEPTH = 6;
  private static final float TURN_PRIORITY_SCAN_COS_THRESHOLD = 0.45f;
  private static final int IMMEDIATE_LOADED_CHUNK_BUILD_RANGE = 8;
  private static final int IMPORTANT_REBUILD_CHUNK_RANGE = 2;
  private static final int INTERACTIVE_PRIORITY_CHUNK_RANGE = 6;
  private static final int INTERACTIVE_PRIORITY_SUBMISSIONS_PER_PASS = 8;
  private static final int MAX_INTERACTIVE_PRIORITY_QUEUE_DEPTH = 16;
  private static final int LOADING_BACKGROUND_SUBMISSIONS_PER_PASS = 96;
  private static final int TURN_PRIORITY_BACKGROUND_SUBMISSIONS_PER_PASS = 16;
  private static final int NORMAL_BACKGROUND_SUBMISSIONS_PER_PASS = 72;
  private static final int ACTIVE_CLOSE_RANGE_RESCAN_INTERVAL = 3;
  private static final int IDLE_CLOSE_RANGE_RESCAN_INTERVAL = 8;
  private static final int HOT_LOAD_REBUILD_RANGE = 12;
  private static final int STARTUP_SOLID_FILL_MESH_THRESHOLD = 3072;
  private static final int LOADING_FRONTIER_RING_SCAN_SPAN = 12;
  private static final int NORMAL_FRONTIER_RING_SCAN_SPAN = 6;

  private static final int PRESSURED_CLOSE_SCAN_RANGE = 6;
  private static final int SATURATED_CLOSE_SCAN_RANGE = 4;
  private static final long FULL_RENDERDIST_RESCAN_INTERVAL_NS = 1_000_000_000L;
  private static final int TEXTURE_SYNC_PRESSURE_THRESHOLD = 64;
  private static final int PRESSURED_ATLAS_SYNC_FRAME_INTERVAL = 2;
  private static final int PRESSURED_LIGHTMAP_SYNC_FRAME_INTERVAL = 8;
  private static final double TEXTURE_BACKOFF_TRIP_MESH_MS = 6.0;
  private static final double TEXTURE_BACKOFF_HARD_TRIP_MESH_MS = 12.0;
  private static final double TEXTURE_BACKOFF_RECOVERY_MESH_MS = 4.0;
  private static final int TEXTURE_BACKOFF_TRIP_CONSEC = 2;
  private static final int TEXTURE_BACKOFF_RECOVER_CONSEC = 5;
  private static final int BACKED_OFF_ATLAS_SYNC_FRAME_INTERVAL = 8;
  private static final int BACKED_OFF_LIGHTMAP_SYNC_FRAME_INTERVAL = 32;
  private static final int JAVA_PROFILE_EMIT_INTERVAL = 240;
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
  private int maxDrawnChunksPerFrame = 65536;
  private final Set<Long> pendingChunkRebuilds = new HashSet<>();
  private final List<long[]> pendingSectionKeys = new ArrayList<>();
  private int lastDrawnChunkCount;
  private long lastDiagLogMs;
  private long outlineBufferHandle;
  private long jTextureAcc = 0, jPruneAcc = 0, jBuildAcc = 0, jLodAcc = 0;
  private int jProfCount = 0;
  private float[] batchDrawData;
  private float[] batchPackedData;
  private final float[] sortTmp = new float[7];
  private boolean gpuDrivenEnabled;
  private MeshShaderBackend meshShaderBackend;
  private ByteBuffer subChunkUploadBuffer;
  private ByteBuffer chunkUniformsBuffer;
  private int subChunkUploadCapacity = 4096;
  private long argumentBufferHandle;
  private it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap readinessCache;
  private final float[] viewProjMatrix = new float[16];
  private final float[] projMatrixFlat = new float[16];
  private final float[] modelViewFlat = new float[16];
  private final float[] cameraPosFloat = new float[4];
  private final float[] frustumPlanesFlat = new float[24];
  private final int[] gpuCullStats = new int[5];
  private int lastGPUVisibleCount;
  private final TranslucencyTrigger translucencyTrigger = new TranslucencyTrigger();
  private final CullingOrcreator cullingOrcreator = new CullingOrcreator();
  private final HiZController hiZController = new HiZController();
  private final TranslucencySorter translucencySorter = new TranslucencySorter();
  private final TerrainIndirectDraw terrainIndirectDraw = new TerrainIndirectDraw();
  private final float[] gpuFrustumPlanes = new float[24];
  private long lastThermalLogMs;
  private boolean loadingMode;
  private int loadingModePendingCount;
  private int loadingModeMeshCount;
  private int screenshotBlitCooldownFrames;
  private boolean loggedChunkLoadDropNotReady;
  private boolean loggedBlockUpdateDropNotReady;
  private boolean loggedWorldLoadWithoutRenderer;
  private long lastLoadingModeLogMs;
  private long lastQueuePressureLogMs;

  public MetalWorldRenderer() {
    this.frustumCuller = new FrustumCuller();
    this.entityRenderer = new MetalEntityRenderer();
    this.particleRenderer = new MetalParticleRenderer();
    this.chunkMesher = new CustomChunkMesher();
    this.readinessCache = new it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap();
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
    worldLoaded = true;
    MetalRenderConfig gpuConfig = MetalRenderClient.getConfig();
    boolean clusterEnabled = gpuConfig != null && gpuConfig.enableClusterFrustumCulling;
    boolean hiZEnabled = gpuConfig != null && gpuConfig.enableHiZCull;
    boolean sortEnabled = gpuConfig != null && gpuConfig.enableGpuTranslucencySort;
    boolean icbEnabled = gpuConfig != null && gpuConfig.enableIndirectCommandBuffers;
    cullingOrcreator.setActive(clusterEnabled);
    cullingOrcreator.setCpuFallbackEnabled(false);
    hiZController.setActive(hiZEnabled);
    translucencySorter.setActive(sortEnabled);
    terrainIndirectDraw.setActive(icbEnabled);
    if (hiZEnabled) {
      Minecraft mc0 = Minecraft.getInstance();
      if (mc0 != null && mc0.getWindow() != null) {
        int w = mc0.getWindow().getWidth();
        int h = mc0.getWindow().getHeight();
        if (w > 0 && h > 0) {
          hiZController.ensureInitialized(w, h);
        }
      }
    }
    if (sortEnabled) {
      translucencySorter.ensureInitialized(0);
    }
    MetalLogger.info(
        "Phase4A orchestrators: cluster=%s hiZ=%s sort=%s icb=%s",
        clusterEnabled, hiZEnabled, sortEnabled, icbEnabled);
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
            MetalLogger.info("Mesh argument buffer allocated: handle=%d size=%d",
                argumentBufferHandle, subChunkUploadCapacity * 16);
          }
        }
      }
      applyFeatureConfig(MetalRenderClient.getConfig());
      boolean meshShadersActive = NativeBridge.isLibLoaded() && NativeBridge.nAreMeshShadersActive();
      MetalLogger.info(
          "GPU-driven pipeline initialized (mesh shaders: %s, enabled: %s)",
          meshShadersActive
              ? "active"
              : (meshShadersSupported ? "available" : "unsupported"),
          gpuDrivenEnabled ? "yes" : "no");
      MetalLogger.info("Metal world rendering activated (" + w + "x" + h + ")");
    } else if (!loggedWorldLoadWithoutRenderer) {
      loggedWorldLoadWithoutRenderer = true;
      MetalLogger.warn(
          "World load observed before renderer was available; terrain capture is deferred until the backend comes up");
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
    pendingChunkRebuilds.clear();
    pendingSectionKeys.clear();
    loggedChunkLoadDropNotReady = false;
    loggedBlockUpdateDropNotReady = false;
    loggedWorldLoadWithoutRenderer = false;
    frameCount = 0;
    lastDrawnChunkCount = 0;
    if (meshShaderBackend != null) {
      meshShaderBackend.shutdown();
      meshShaderBackend = null;
    }
    gpuDrivenEnabled = false;
    subChunkUploadBuffer = null;
    chunkUniformsBuffer = null;
    if (argumentBufferHandle != 0) {
      NativeBridge.nDestroyBuffer(argumentBufferHandle);
      argumentBufferHandle = 0;
    }
    com.pebbles_boon.metalrender.nativebridge.ResidencySetManager.shutdown();
    cullingOrcreator.shutdown();
    hiZController.shutdown();
    translucencySorter.shutdown();
    terrainIndirectDraw.shutdown();
    updateLoadingModeState();
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
    renderer.resize(w, h);
    long tTexture0 = System.nanoTime();
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
      int atlasInterval;
      int lightmapInterval;
      if (textureBackoffActive) {
        atlasInterval = BACKED_OFF_ATLAS_SYNC_FRAME_INTERVAL;
        lightmapInterval = BACKED_OFF_LIGHTMAP_SYNC_FRAME_INTERVAL;
      } else if (textureSyncPressure) {
        atlasInterval = PRESSURED_ATLAS_SYNC_FRAME_INTERVAL;
        lightmapInterval = PRESSURED_LIGHTMAP_SYNC_FRAME_INTERVAL;
      } else {
        atlasInterval = 1;
        lightmapInterval = 1;
      }
      if (frameCount % atlasInterval == 0) {
        textureManager.updateBlockAtlas();
      }
      if (frameCount % lightmapInterval == 0) {
        textureManager.updateLightmap();
      }
    }
    long tTexture1 = System.nanoTime();
    MetalRenderProfiler.getInstance().recordTextureTime(tTexture1 - tTexture0);
    long now = System.currentTimeMillis();
    long diagInterval = chunkMesher.getMeshCount() < 2000 ? 1000 : 5000;
    if (MetalRenderConfig.isDeepDebugActive() &&
        now - lastDiagLogMs > diagInterval) {
      lastDiagLogMs = now;
      MetalLogger.info(
          "DiagWorld: texturesReady=" + texturesReady +
              ", atlasFallback=" + textureManager.isUsingFallbackBlockAtlas() +
              ", meshCount=" + chunkMesher.getMeshCount());
    }
    Camera camera = mc.gameRenderer.getMainCamera();
    Vector3f camPos = new Vector3f((float) camera.position().x, (float) camera.position().y,
        (float) camera.position().z);
    if (MetalRenderClient.getConfig().enableMetalRendering) {
      long t0 = System.nanoTime();
      int pruneInterval = chunkMesher.getMeshCount() > 3000 ? 120
          : (chunkMesher.getMeshCount() > 1500 ? 60 : 30);
      boolean nearMeshLimit = chunkMesher.getMeshCount() >= maxMeshes - 500;
      if (frameCount % pruneInterval == 0 ||
          (nearMeshLimit && !pendingBuildSet.isEmpty())) {
        pruneFarMeshes(mc, camPos);
      }
      long t1 = System.nanoTime();
      buildPendingChunkMeshes(mc);
      long t2 = System.nanoTime();
      long t3 = t2;
      jTextureAcc += (tTexture1 - tTexture0);
      jPruneAcc += (t1 - t0);
      jBuildAcc += (t2 - t1);
      jLodAcc += (t3 - t2);
      jProfCount++;
      if (jProfCount >= 120) {
        double textureMs = jTextureAcc / 1e6 / jProfCount;
        double pruneMs = jPruneAcc / 1e6 / jProfCount;
        double buildMs = jBuildAcc / 1e6 / jProfCount;
        MetalLogger.info(
            "JAVA_PROFILE: texture=%.2fms prune=%.2fms build=%.2fms "
                + "(avg/%d) pending=%d queued=%d meshes=%d | lanes "
                + "builder=%d/%d instant=%d/%d interactive=%d/%d | "
                + "visible=%.2fms/%d block=%.2fms/%d tracked=%d/%d",
            textureMs, pruneMs, buildMs, jProfCount,
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
        jTextureAcc = 0;
        jPruneAcc = 0;
        jBuildAcc = 0;
        jLodAcc = 0;
        jProfCount = 0;
      }
    }
    updateLoadingModeState();
  }

  public void beginFrame(Camera camera, float tickDelta, Matrix4f projection,
      Matrix4f modelView) {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return;
    if (chunkMesher != null) {
      chunkMesher.flushMeshRegistrations();
      chunkMesher.bumpCoalesceFrame();
    }
    projectionMatrix.set(projection);
    modelViewMatrix.set(modelView);
    Vector3f camPos = new Vector3f((float) camera.position().x, (float) camera.position().y,
        (float) camera.position().z);

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

    if (cullingOrcreator.isActive()) {
      Matrix4f vp = new Matrix4f(projectionMatrix).mul(modelViewMatrix);
      extractFrustumPlanes(vp, gpuFrustumPlanes);
      int chunkRadius = Minecraft.getInstance().options.renderDistance().get();
      cullingOrcreator.rebuildFromFrustumCpu(frustumCuller, chunkRadius);
      cullingOrcreator.uploadToGpu(gpuFrustumPlanes);
    }
    translucencySorter.tickStable(camPos, camera.getYRot());
    terrainIndirectDraw.beginFrame();

    lastDrawnChunkCount = 0;
    renderer.beginFrame(tickDelta);
    Matrix4f metalProj = new Matrix4f(projectionMatrix);
    metalProj.m02(0.5f * metalProj.m02() + 0.5f * metalProj.m03());
    metalProj.m12(0.5f * metalProj.m12() + 0.5f * metalProj.m13());
    metalProj.m22(0.5f * metalProj.m22() + 0.5f * metalProj.m23());
    metalProj.m32(0.5f * metalProj.m32() + 0.5f * metalProj.m33());
    renderer.setProjectionMatrix(metalProj);
    renderer.setModelViewMatrix(modelViewMatrix);
    renderer.setCameraPosition(camera.position().x, camera.position().y,
        camera.position().z);
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
    boolean skipTerrainDraw = false;
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
        if (!skipTerrainDraw) {
          boolean useMeshShaderOpaque = meshShaderBackend != null &&
              meshShaderBackend.areMeshShadersAvailable() &&
              meshShaderBackend.isActive();
          if (useMeshShaderOpaque && meshShaderBackend.isGPUDrivenEnabled()) {
            long ibHandle = chunkMesher.getGlobalIndexBuffer();
            if (ibHandle != 0) {
              meshShaderBackend.dispatchTerrainMultiPass(frameCtx, renderer.getHandle(), argumentBufferHandle);
              int visible = meshShaderBackend.getLastVisibleCount();
              if (visible > 0) {
                lastDrawnChunkCount = visible;
                MetalRenderProfiler.getInstance().incrementChunksDrawn(visible);
              } else {
                int drawn = NativeBridge.nDrawAllVisibleChunks(frameCtx, ibHandle);
                lastDrawnChunkCount = drawn;
                MetalRenderProfiler.getInstance().incrementChunksDrawn(drawn);
              }
            }
          } else {
            long ibHandle = chunkMesher.getGlobalIndexBuffer();
            if (ibHandle != 0) {
              int drawn = NativeBridge.nDrawAllVisibleChunks(frameCtx, ibHandle);
              lastDrawnChunkCount = drawn;
              MetalRenderProfiler.getInstance().incrementChunksDrawn(drawn);
              if (frameCount < 10 || frameCount % 1000 == 0) {
                MetalLogger.info("Frame %d: V18 native drew %d chunks",
                    frameCount, drawn);
              }
            } else {
              lastDrawnChunkCount = 0;
            }
          }
        }
      }
    }
  }

  private static void extractFrustumPlanes(Matrix4f vp, float[] out) {
    out[0] = vp.m03() + vp.m00();
    out[1] = vp.m13() + vp.m10();
    out[2] = vp.m23() + vp.m20();
    out[3] = vp.m33() + vp.m30();
    normalizePlane(out, 0);
    out[4] = vp.m03() - vp.m00();
    out[5] = vp.m13() - vp.m10();
    out[6] = vp.m23() - vp.m20();
    out[7] = vp.m33() - vp.m30();
    normalizePlane(out, 4);
    out[8] = vp.m03() + vp.m01();
    out[9] = vp.m13() + vp.m11();
    out[10] = vp.m23() + vp.m21();
    out[11] = vp.m33() + vp.m31();
    normalizePlane(out, 8);
    out[12] = vp.m03() - vp.m01();
    out[13] = vp.m13() - vp.m11();
    out[14] = vp.m23() - vp.m21();
    out[15] = vp.m33() - vp.m31();
    normalizePlane(out, 12);
    out[16] = vp.m02();
    out[17] = vp.m12();
    out[18] = vp.m22();
    out[19] = vp.m32();
    normalizePlane(out, 16);
    out[20] = vp.m03() - vp.m02();
    out[21] = vp.m13() - vp.m12();
    out[22] = vp.m23() - vp.m22();
    out[23] = vp.m33() - vp.m32();
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
      long entityStart = System.nanoTime();
      entityRenderer.renderCapturedEntities(frameCtx, inWater);
      MetalRenderProfiler.getInstance().recordEntityTime(System.nanoTime() - entityStart);
      NativeBridge.nDrawDeferredWaterPass(frameCtx);
      NativeBridge.nDrawOITPass(frameCtx);
      long particleStart = System.nanoTime();
      particleRenderer.render(frameCtx);
      MetalRenderProfiler.getInstance().recordParticleTime(System.nanoTime() - particleStart);
      renderBlockOutline(frameCtx);
    }
    renderer.endFrame();
    frameCount++;
  }

  private void renderBlockOutline(long frameCtx) {
    try {
      Minecraft mc = Minecraft.getInstance();
      if (mc == null || mc.hitResult == null)
        return;
      if (mc.hitResult.getType() != HitResult.Type.BLOCK)
        return;
      BlockHitResult hit = (BlockHitResult) mc.hitResult;
      BlockPos pos = hit.getBlockPos();
      Camera cam = mc.gameRenderer.getMainCamera();
      float bx = (float) (pos.getX() - cam.position().x);
      float by = (float) (pos.getY() - cam.position().y);
      float bz = (float) (pos.getZ() - cam.position().z);
      float e = 0.002f;
      float x0 = bx - e, y0 = by - e, z0 = bz - e;
      float x1 = bx + 1 + e, y1 = by + 1 + e, z1 = bz + 1 + e;
      float t = 0.015f;
      float[] verts = new float[72 * 3];
      int vi = 0;
      vi = addThickEdge(verts, vi, x0, y0, z0, x1, y0, z0, t, 1);
      vi = addThickEdge(verts, vi, x1, y0, z0, x1, y0, z1, t, 1);
      vi = addThickEdge(verts, vi, x1, y0, z1, x0, y0, z1, t, 1);
      vi = addThickEdge(verts, vi, x0, y0, z1, x0, y0, z0, t, 1);
      vi = addThickEdge(verts, vi, x0, y1, z0, x1, y1, z0, t, 1);
      vi = addThickEdge(verts, vi, x1, y1, z0, x1, y1, z1, t, 1);
      vi = addThickEdge(verts, vi, x1, y1, z1, x0, y1, z1, t, 1);
      vi = addThickEdge(verts, vi, x0, y1, z1, x0, y1, z0, t, 1);
      vi = addThickEdge(verts, vi, x0, y0, z0, x0, y1, z0, t, 0);
      vi = addThickEdge(verts, vi, x1, y0, z0, x1, y1, z0, t, 2);
      vi = addThickEdge(verts, vi, x1, y0, z1, x1, y1, z1, t, 0);
      vi = addThickEdge(verts, vi, x0, y0, z1, x0, y1, z1, t, 2);
      int vertexCount = vi / 3;
      ByteBuffer buf = ByteBuffer.allocateDirect(vi * 4).order(ByteOrder.nativeOrder());
      for (int i = 0; i < vi; i++)
        buf.putFloat(verts[i]);
      buf.flip();
      byte[] data = new byte[buf.remaining()];
      buf.get(data);
      MetalRenderer renderer = MetalRenderClient.getRenderer();
      if (renderer == null)
        return;
      long device = renderer.getBackend().getDeviceHandle();
      if (outlineBufferHandle == 0 || data.length > outlineBufferSize) {
        if (outlineBufferHandle != 0) {
          NativeBridge.nDestroyBuffer(outlineBufferHandle);
        }
        outlineBufferHandle = NativeBridge.nCreateBuffer(
            device, data.length, NativeMemory.STORAGE_MODE_SHARED);
        outlineBufferSize = data.length;
      }
      NativeBridge.nUploadBufferData(outlineBufferHandle, data, 0, data.length);
      NativeBridge.nSetDebugColor(frameCtx, 0.0f, 0.0f, 0.0f, 0.5f);
      NativeBridge.nDrawTriangleBuffer(frameCtx, outlineBufferHandle,
          vertexCount);
    } catch (Exception e) {
      MetalLogger.error("[BlockOutline] Exception: %s", e.getMessage());
    }
  }

  private int outlineBufferSize = 0;

  private static int addThickEdge(float[] v, int vi, float ax, float ay,
      float az, float bx, float by, float bz,
      float t, int expandAxis) {
    float dx = 0, dy = 0, dz = 0;
    if (expandAxis == 0)
      dx = t;
    else if (expandAxis == 1)
      dy = t;
    else
      dz = t;
    float p0x = ax - dx, p0y = ay - dy, p0z = az - dz;
    float p1x = ax + dx, p1y = ay + dy, p1z = az + dz;
    float p2x = bx + dx, p2y = by + dy, p2z = bz + dz;
    float p3x = bx - dx, p3y = by - dy, p3z = bz - dz;
    v[vi++] = p0x;
    v[vi++] = p0y;
    v[vi++] = p0z;
    v[vi++] = p1x;
    v[vi++] = p1y;
    v[vi++] = p1z;
    v[vi++] = p2x;
    v[vi++] = p2y;
    v[vi++] = p2z;
    v[vi++] = p0x;
    v[vi++] = p0y;
    v[vi++] = p0z;
    v[vi++] = p2x;
    v[vi++] = p2y;
    v[vi++] = p2z;
    v[vi++] = p3x;
    v[vi++] = p3y;
    v[vi++] = p3z;
    return vi;
  }

  private final it.unimi.dsi.fastutil.longs.LongOpenHashSet pendingBuildSet = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
  private final it.unimi.dsi.fastutil.longs.LongArrayList sortedBuildList = new it.unimi.dsi.fastutil.longs.LongArrayList();
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
    if (mc.player != null) {
      float yaw = mc.player.getYRot();
      float nextForwardX = (float) -Math.sin(Math.toRadians(yaw));
      float nextForwardZ = (float) Math.cos(Math.toRadians(yaw));
      float turnDot = cachedForwardX * nextForwardX + cachedForwardZ * nextForwardZ;
      cachedForwardX = nextForwardX;
      cachedForwardZ = nextForwardZ;
      if (turnDot < BUILD_SORT_REORDER_DOT_THRESHOLD) {
        if (!pendingBuildSet.isEmpty()) {
          sortedListDirty = true;
        }
        turnPriorityFrames = TURN_PRIORITY_SCAN_FRAMES;
        scanFrontierRing = HOT_LOAD_REBUILD_RANGE + 1;
        scanFrameCounter = 0;
      }
    }
    long scanStart = System.nanoTime();
    if (pendingBuildSet.size() < CHUNK_SCAN_SATURATED_THRESHOLD ||
        (frameCount & 1) == 0) {
      scanForPendingChunks(mc);
    }
    MetalRenderProfiler.getInstance().recordScanTime(System.nanoTime() - scanStart);
    if (mc.player != null && chunkMesher.getMeshCount() < maxMeshes) {
      int playerChunkX = mc.player.chunkPosition().x();
      int playerChunkZ = mc.player.chunkPosition().z();
      int playerSectionY = mc.player.getBlockY() >> 4;
      boolean shouldSortTranslucent = translucencyTrigger.shouldReSort(
          new Vector3f((float) mc.player.getX(), (float) mc.player.getY(), (float) mc.player.getZ()),
          mc.player.getYRot());
      if (shouldSortTranslucent && MetalRenderConfig.isDeepDebugActive()) {
        MetalLogger.debug("TranslucencyTrigger: re-sort triggered (stable=%s)",
            translucencyTrigger.isStable());
      }
      boolean turnBurstActive = turnPriorityFrames > 0;
      boolean startupSolidFill = loadingMode &&
          loadingModeMeshCount < STARTUP_SOLID_FILL_MESH_THRESHOLD;
      int mesherPending = chunkMesher.getPendingCount();
      int visibleBacklog = pendingBuildSet.size() + mesherPending;
      boolean fpsPriorityMode = MetalRenderClient.getConfig() != null &&
          MetalRenderClient.getConfig().prioritizeFpsOverTps;
      long buildBudget = turnBurstActive ? CHUNK_TURN_BUILD_BURST_NS : CHUNK_BUILD_BUDGET_NS;
      int minBuilds = turnBurstActive ? MIN_CHUNK_TURN_BUILDS_PER_FRAME
          : MIN_CHUNK_BUILDS_PER_FRAME;
      int highPrioritySubmissions = turnBurstActive ? TURN_HIGH_PRIORITY_SUBMISSIONS_PER_PASS
          : BASE_HIGH_PRIORITY_SUBMISSIONS_PER_PASS;
      if (startupSolidFill) {
        buildBudget = Math.max(buildBudget, CHUNK_HEAVY_BACKLOG_BUILD_BURST_NS);
        minBuilds = Math.max(minBuilds, MIN_CHUNK_HEAVY_BACKLOG_BUILDS_PER_FRAME);
        highPrioritySubmissions = Math.max(highPrioritySubmissions,
            HEAVY_BACKLOG_HIGH_PRIORITY_SUBMISSIONS_PER_PASS);
      }
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
        buildBudget = Math.max(buildBudget,
            loadingMode ? CHUNK_HEAVY_BACKLOG_BUILD_BURST_NS
                : CHUNK_BACKLOG_BUILD_BURST_NS);
        minBuilds = Math.max(
            minBuilds, loadingMode ? MIN_CHUNK_HEAVY_BACKLOG_BUILDS_PER_FRAME
                : MIN_CHUNK_BACKLOG_BUILDS_PER_FRAME);
        highPrioritySubmissions = Math.max(
            highPrioritySubmissions,
            loadingMode ? HEAVY_BACKLOG_HIGH_PRIORITY_SUBMISSIONS_PER_PASS
                : BACKLOG_HIGH_PRIORITY_SUBMISSIONS_PER_PASS);
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
    boolean coverageFillActive = loadingMode;
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
    if (scanPressured && !coverageFillActive) {
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
      int frontierSpan = coverageFillActive ? LOADING_FRONTIER_RING_SCAN_SPAN
          : NORMAL_FRONTIER_RING_SCAN_SPAN;
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
    if (turnPriorityFrames > 0 && !coverageFillActive && !scanPressured) {
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
          "QUEUE_TRIM: keepRange=%d player=[%d,%d] pending=%d chunkPending=%d meshes=%d",
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
        MetalLogger.debug("SCAN_SKIP: chunk [%d,%d] not available", chunkX,
            chunkZ);
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
      if (!chunkMesher.hasMesh(chunkX, worldY, chunkZ)) {
        chunkMesher.noteSectionAvailable(chunkX, worldY, chunkZ);
        if (pendingBuildSet.add(packChunkKey(chunkX, worldY, chunkZ))) {
          sortedListDirty = true;
          if (MetalRenderConfig.isDeepDebugActive()) {
            MetalLogger.debug(
                "QUEUE_ADD: chunk=[%d,%d,%d] dist=%d pending=%d",
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
        "CHUNK_AVAIL: server=%d/%d (max_ring=%d) meshes=%d pending=%d",
        available, total, maxRingAvail, chunkMesher.getMeshCount(),
        pendingBuildSet.size());
  }

  public int buildMeshesDuringWait(long metalHandle) {
    Minecraft mc = Minecraft.getInstance();
    if (mc == null || mc.player == null || mc.level == null)
      return 0;
    if (chunkMesher.getMeshCount() >= maxMeshes)
      return 0;
    int playerChunkX = mc.player.chunkPosition().x();
    int playerChunkZ = mc.player.chunkPosition().z();
    int playerSectionY = mc.player.getBlockY() >> 4;
    int totalBuilt = 0;
    long timeout = System.nanoTime() + CHUNK_BUILD_WAIT_WINDOW_NS;
    while (System.nanoTime() < timeout) {
      if (NativeBridge.nIsFrameReady(metalHandle)) {
        break;
      }
      int built = buildFromPendingSet(playerChunkX, playerSectionY,
          playerChunkZ, CHUNK_BUILD_WAIT_BUDGET_NS,
          MIN_CHUNK_BUILDS_DURING_WAIT,
          WAIT_HIGH_PRIORITY_SUBMISSIONS_PER_PASS);
      if (built == 0)
        break;
      totalBuilt += built;
    }
    if (totalBuilt > 0 || MetalRenderConfig.isDeepDebugActive()) {
      MetalLogger.info(
          "WAIT_BUILD: built=%d pending=%d chunkPending=%d meshes=%d ready=%s",
          totalBuilt, pendingBuildSet.size(), chunkMesher.getPendingCount(),
          chunkMesher.getMeshCount(), NativeBridge.nIsFrameReady(metalHandle));
    }
    return totalBuilt;
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
      boolean shouldSort = turnPriorityFrames > 0
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
        final boolean startupSolidFill = loadingMode &&
            loadingModeMeshCount < STARTUP_SOLID_FILL_MESH_THRESHOLD;
        sortedBuildList.sort((a, b) -> {
          int ax = (int) ((a >> 42) & 0x3FFFFF);
          if ((ax & 0x200000) != 0)
            ax |= ~0x3FFFFF;
          int ay = (int) ((a >> 22) & 0xFFFFF);
          if ((ay & 0x80000) != 0)
            ay |= ~0xFFFFF;
          int az = (int) (a & 0x3FFFFF);
          if ((az & 0x200000) != 0)
            az |= ~0x3FFFFF;
          int bx = (int) ((b >> 42) & 0x3FFFFF);
          if ((bx & 0x200000) != 0)
            bx |= ~0x3FFFFF;
          int by = (int) ((b >> 22) & 0xFFFFF);
          if ((by & 0x80000) != 0)
            by |= ~0xFFFFF;
          int bz = (int) (b & 0x3FFFFF);
          if ((bz & 0x200000) != 0)
            bz |= ~0x3FFFFF;
          float dotA = (ax - pcx) * fwdX + (az - pcz) * fwdZ;
          float dotB = (bx - pcx) * fwdX + (bz - pcz) * fwdZ;
          boolean frontA = dotA >= 0;
          boolean frontB = dotB >= 0;
          if (!startupSolidFill && frontA != frontB)
            return frontA ? -1 : 1;
          int distA = Math.abs(ax - pcx) + Math.abs(az - pcz);
          int distB = Math.abs(bx - pcx) + Math.abs(bz - pcz);
          if (distA != distB)
            return Integer.compare(distA, distB);
          int verticalDistA = Math.abs(ay - pcy);
          int verticalDistB = Math.abs(by - pcy);
          return Integer.compare(verticalDistA, verticalDistB);
        });
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
    BuildBudgetEstimator estimator = PerformanceController.getBudgetEstimator();
    boolean throttle = estimator != null && estimator.shouldThrottle();
    int meshCount = chunkMesher.getMeshCount();
    float meshUtil = maxMeshes > 0 ? (float) meshCount / maxMeshes : 0f;
    if (throttle || meshUtil > 0.75f) {
      budgetNanos = Math.min(budgetNanos, 2_000_000L);
      maxSubmit = Math.min(maxSubmit, 40);
      if (meshUtil > 0.90f) {
        maxSubmit = Math.min(maxSubmit, 15);
      }
    }
    int thermalState = 0;
    if ((frameCount & 31) == 0) {
      cachedThermalState = NativeBridge.isLibLoaded() ? NativeBridge.nGetThermalState() : 0;
    }
    thermalState = cachedThermalState;
    if (thermalState >= 2) {
      budgetNanos = Math.min(budgetNanos, 1_500_000L);
      maxSubmit = Math.min(maxSubmit, 25);
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
    int backgroundSubmissionBudget = loadingMode ? LOADING_BACKGROUND_SUBMISSIONS_PER_PASS
        : (turnPriorityFrames > 0
            ? TURN_PRIORITY_BACKGROUND_SUBMISSIONS_PER_PASS
            : NORMAL_BACKGROUND_SUBMISSIONS_PER_PASS);
    if (fpsPriorityMode) {
      backgroundInFlightLimit = maxInFlightBuildTasks;
      backgroundSubmissionBudget = Math.max(
          backgroundSubmissionBudget,
          loadingMode ? FPS_PRIORITY_LOADING_BACKGROUND_SUBMISSIONS_PER_PASS
              : FPS_PRIORITY_NORMAL_BACKGROUND_SUBMISSIONS_PER_PASS);
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
          boolean bypassReadiness = chunkDist <= IMPORTANT_REBUILD_CHUNK_RANGE ||
              (loadingMode && chunkDist <= HOT_LOAD_REBUILD_RANGE);
          if (!bypassReadiness && !isSectionBuildReady(world, cx, cy, cz)) {
            if (MetalRenderConfig.isDeepDebugActive()) {
              MetalLogger.debug(
                  "BUILD_DEFER: chunk=[%d,%d,%d] dist=%d readiness=false",
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
          (loadingMode || turnPriorityFrames > 0) &&
          chunkMesher.getInteractiveQueueDepth() < MAX_INTERACTIVE_PRIORITY_QUEUE_DEPTH;
      if (!highPriority && !interactivePriority) {
        if (backgroundSubmissions >= backgroundSubmissionBudget) {
          break;
        }
        if (chunkMesher.getPendingCount() >= backgroundInFlightLimit) {
          break;
        }
      }
      if (interactivePriority) {
        chunkMesher.buildMeshFromWorldInteractive(
            candidate.chunkX, candidate.chunkY, candidate.chunkZ);
      } else {
        chunkMesher.buildMeshFromWorld(candidate.chunkX, candidate.chunkY,
            candidate.chunkZ, highPriority);
      }
      if (built < 5 || MetalRenderConfig.isDeepDebugActive()) {
        MetalLogger.debug(
            "BUILD_QUEUE: chunk=[%d,%d,%d] high=%s interactive=%s pending=%d meshes=%d",
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
          "BUILD_PASS: built=%d important=%d background=%d pending=%d chunkPending=%d meshes=%d budgetNs=%d",
          built, importantSubmitted, backgroundSubmissions,
          pendingBuildSet.size(), chunkMesher.getPendingCount(),
          chunkMesher.getMeshCount(), budgetNanos);
    }
    return built;
  }

  public int getGLTextureForCompositing() {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null)
      return 0;
    return renderer.getGLTextureId();
  }

  public FrustumCuller getFrustumCuller() {
    return frustumCuller;
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

  public void setLastDrawnChunkCount(int c) {
    this.lastDrawnChunkCount = c;
  }

  public void addDrawnChunkCount(int c) {
    this.lastDrawnChunkCount += c;
  }

  public int getLastDrawnChunkCount() {
    return lastDrawnChunkCount;
  }

  public boolean areTexturesReady() {
    return texturesReady;
  }

  public MetalTextureManager getTextureManager() {
    return textureManager;
  }

  public boolean isWorldLoaded() {
    return worldLoaded;
  }

  public int getFrameCount() {
    return frameCount;
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
    boolean requestArgumentBuffers = config.enableArgumentBuffers || config.enableIndirectCommandBuffers;
    if (NativeBridge.isLibLoaded()) {
      NativeBridge.nSetFeatureFlags(
          config.enableIndirectCommandBuffers, config.enableMeshShaders,
          requestArgumentBuffers, config.enableProgrammableBlending);
      gpuDrivenEnabled = NativeBridge.nIsGPUDrivenActive();
      MetalLogger.info(
          "RUNTIME_FEATURES: mesh=%s gpuDriven=%s argBuf=%s",
          NativeBridge.nAreMeshShadersActive(), gpuDrivenEnabled,
          NativeBridge.nAreArgumentBuffersActive());
    }
    updateLoadingModeState();
  }

  public void onConfigScreenClosed() {
    if (!worldLoaded || !renderingActive) {
      return;
    }
    chunkMesher.clearAllMeshes();
    pendingChunkRebuilds.clear();
    pendingSectionKeys.clear();
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
    updateLoadingModeState();
  }

  private void updateLoadingModeState() {
    int prevPending = loadingModePendingCount;
    int prevMeshes = loadingModeMeshCount;
    boolean prevLoadingMode = loadingMode;
    loadingModeMeshCount = chunkMesher.getMeshCount();
    loadingModePendingCount = pendingBuildSet.size() + chunkMesher.getPendingCount();
    loadingMode = worldLoaded && renderingActive && loadingModePendingCount > 0;
    chunkMesher.setLoadingModeThreadBudget(loadingMode,
        loadingModePendingCount);
    if ((prevPending != loadingModePendingCount || prevMeshes != loadingModeMeshCount ||
        prevLoadingMode != loadingMode) &&
        System.currentTimeMillis() - lastLoadingModeLogMs >= 1000) {
      lastLoadingModeLogMs = System.currentTimeMillis();
      MetalLogger.info(
          "LOAD_STATE: loaded=%s active=%s loading=%s pending=%d chunkPending=%d meshes=%d",
          worldLoaded, renderingActive, loadingMode, loadingModePendingCount,
          chunkMesher.getPendingCount(), loadingModeMeshCount);
    }
  }

  public void renderFrame(Object viewport, Object matrices, double x, double y,
      double z) {
  }

  public void onChunkLoaded(int chunkX, int chunkZ, LevelChunk chunk) {
    if (!worldLoaded || !renderingActive) {
      if (!loggedChunkLoadDropNotReady) {
        loggedChunkLoadDropNotReady = true;
        MetalLogger.warn(
            "Chunk load received while renderer is not ready; dropping chunk [%d,%d] (worldLoaded=%s renderingActive=%s)",
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
        chunkMesher.buildMeshFromWorld(chunkX, worldY, chunkZ, false, true);
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
        "CHUNK_LOAD: chunk=[%d,%d] sections=%d nonAir=%d distance=%d immediate=%s priority=%s pending=%d chunkPending=%d",
        chunkX, chunkZ, sections.length, nonAirSections, loadedChunkDistance,
        immediateBuildChunk, highPriorityChunk, pendingBuildSet.size(),
        chunkMesher.getPendingCount());
    requeueNeighboursNowReady(mc, chunkX - 1, chunkZ);
    requeueNeighboursNowReady(mc, chunkX + 1, chunkZ);
    requeueNeighboursNowReady(mc, chunkX, chunkZ - 1);
    requeueNeighboursNowReady(mc, chunkX, chunkZ + 1);
    updateLoadingModeState();
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
      MetalLogger.debug("REQUEUE_READY: neighbor=[%d,%d] queued=%d pending=%d",
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
    boolean ready = readyNeighborCheck(source, chunkX, chunkZ);
    readinessCache.put(pKey, ready);
    return ready;
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
    if (loadingMode) {
      consecutiveHighMeshMsFrames = 0;
      consecutiveCoolMeshMsFrames = 0;
      textureBackoffActive = false;
      return;
    }
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
            "Block update rebuild dropped while renderer is not ready at [%d,%d,%d]",
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
    chunkMesher.buildMeshFromWorldInteractive(cx, cy, cz);
    if (!loadingMode) {
      markDirtyAndQueue(cx - 1, cy, cz);
      markDirtyAndQueue(cx + 1, cy, cz);
      markDirtyAndQueue(cx, cy - 1, cz);
      markDirtyAndQueue(cx, cy + 1, cz);
      markDirtyAndQueue(cx, cy, cz - 1);
      markDirtyAndQueue(cx, cy, cz + 1);
    }
    MetalLogger.info(
        "BLOCK_REBUILD: block=[%d,%d,%d] section=[%d,%d,%d] pending=%d chunkPending=%d meshes=%d",
        blockX, blockY, blockZ, cx, cy, cz, pendingBuildSet.size(),
        chunkMesher.getPendingCount(), chunkMesher.getMeshCount());
    updateLoadingModeState();
  }

  private void markDirtyAndQueue(int chunkX, int sectionY, int chunkZ) {
    chunkMesher.markDirty(chunkX, sectionY, chunkZ);
    if (pendingBuildSet.add(packChunkKey(chunkX, sectionY, chunkZ))) {
      sortedListDirty = true;
    }
  }

  public boolean isGPUDrivenEnabled() {
    return gpuDrivenEnabled;
  }

  public MeshShaderBackend getMeshShaderBackend() {
    return meshShaderBackend;
  }

  public int getLastGPUVisibleCount() {
    return lastGPUVisibleCount;
  }

  public int[] getGPUCullStats() {
    NativeBridge.nGetGPUCullStats(gpuCullStats);
    return gpuCullStats;
  }

  public int getThermalState() {
    return NativeBridge.nGetThermalState();
  }

  public boolean isLoadingMode() {
    return loadingMode;
  }

  public int getLoadingModePendingCount() {
    return loadingModePendingCount;
  }

  public int getLoadingModeMeshCount() {
    return loadingModeMeshCount;
  }
}
