package com.pebbles_boon.metalrender.render;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.backend.MetalRenderer;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.culling.FrustumCuller;
import com.pebbles_boon.metalrender.entity.MetalEntityRenderer;
import com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.nativebridge.NativeMemory;
import com.pebbles_boon.metalrender.particle.MetalParticleRenderer;
import com.pebbles_boon.metalrender.render.chunk.CustomChunkMesher;
import com.pebbles_boon.metalrender.sodium.backend.MeshShaderBackend;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
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
  private static final int DEFAULT_MAX_MESHES = 131072;
  private static final int PINNED_RENDER_DISTANCE = 32;
  private static final int PINNED_MAX_MESHES = 262144;
  private static final int FAR_KEEP_MARGIN_CHUNKS = 8;
  private static final int MAX_FAR_PROXY_SUBMITS = 24;
  private static final long CHUNK_BUILD_BUDGET_NS = 4_500_000L;
  private static final int MIN_CHUNK_BUILDS_PER_FRAME = 10;
  private static final long CHUNK_UPLOAD_BUDGET_NS = 2_000_000L;
  private static final int MIN_CHUNK_UPLOADS_PER_FRAME = 4;
  private static final int MAX_CHUNK_UPLOADS_PER_FRAME = 12;
  private static final long CHUNK_UPLOAD_STARTUP_BUDGET_NS = 5_000_000L;
  private static final int MIN_CHUNK_UPLOADS_STARTUP = 16;
  private static final int MAX_CHUNK_UPLOADS_STARTUP = 32;
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
  private static final long CHUNK_UPLOAD_WAIT_BUDGET_NS = 1_000_000L;
  private static final int MIN_CHUNK_UPLOADS_DURING_WAIT = 2;
  private static final int MAX_CHUNK_UPLOADS_DURING_WAIT = 8;
  private static final int MAX_LIGHT_FIXES_PER_FRAME = 4;
  private static final int MAX_LIGHT_FIXES_DURING_WAIT = 1;
  private static final int MAX_LIGHT_FIX_IN_FLIGHT = 2;
  private static final int MAX_LIGHT_FIX_SCAN_MULTIPLIER = 4;
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
  private static final int PRESSURED_BACKGROUND_IN_FLIGHT_LIMIT = 96;
  private static final int HEAVY_BACKGROUND_IN_FLIGHT_LIMIT = 48;
  private static final int PRESSURED_BACKGROUND_SUBMISSIONS_PER_PASS = 24;
  private static final int HEAVY_BACKGROUND_SUBMISSIONS_PER_PASS = 8;
  private static final long CHUNK_BUILD_WAIT_WINDOW_NS = 3_000_000L;
  private static final int HIGH_PRIORITY_LOADED_VERTICAL_RANGE = 3;
  private static final int MID_DISTANCE_SCAN_VERTICAL_RANGE = 4;
  private static final int FAR_DISTANCE_SCAN_VERTICAL_RANGE = 2;
  private static final int EXTREME_DISTANCE_SCAN_VERTICAL_RANGE = 1;
  private static final int SURFACE_SECTION_EXTRA_DEPTH = 1;
  private static final int SURFACE_ONLY_SECTION_DISTANCE = 8;
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
  private static final int HOT_LOAD_REBUILD_RANGE = 10;
  private static final int STARTUP_SOLID_FILL_MESH_THRESHOLD = 3072;
  private static final int LOADING_FRONTIER_RING_SCAN_SPAN = 10;
  private static final int NORMAL_FRONTIER_RING_SCAN_SPAN = 6;
  private static final int PRESSURED_FRONTIER_RING_SCAN_SPAN = 2;
  private static final int PRESSURED_PENDING_TRIM_THRESHOLD = 3072;
  private static final int TRIM_PRESERVE_EXTRA_RANGE = 8;
  private static final int DETAIL_TIER_REBUILD_FRAME_INTERVAL = 2;
  private static final long DETAIL_TIER_REBUILD_BUDGET_NS = 1_500_000L;
  private static final int DETAIL_TIER_REBUILD_SCAN_LIMIT = 1024;
  private static final int DETAIL_TIER_REBUILD_MAX_PER_PASS = 12;
  private static final int PRESSURED_CLOSE_SCAN_RANGE = 10;
  private static final int SATURATED_CLOSE_SCAN_RANGE = 4;
  private static final int MAX_PENDING_BUILD_SET_SIZE = 5120;
  private static final int MAX_PENDING_BUILD_SET_SIZE_LOADING = 8192;
  private static final int HARD_PENDING_BUILD_SET_SIZE = 10240;
  private static final long FULL_RENDERDIST_RESCAN_INTERVAL_NS = 5_000_000_000L;
  private static final int TEXTURE_SYNC_PRESSURE_THRESHOLD = 64;
  private static final int PRESSURED_ATLAS_SYNC_FRAME_INTERVAL = 2;
  private static final int PRESSURED_LIGHTMAP_SYNC_FRAME_INTERVAL = 8;
  private static final int JAVA_PROFILE_EMIT_INTERVAL = 120;
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
  private long jTextureAcc = 0, jUploadAcc = 0, jPruneAcc = 0, jBuildAcc = 0, jLodAcc = 0;
  private long jUploadBytesAcc = 0;
  private int jUploadJobsAcc = 0;
  private int jLightFixJobsAcc = 0;
  private int jProfCount = 0;
  private float[] batchDrawData;
  private float[] batchPackedData;
  private final float[] sortTmp = new float[7];
  private boolean gpuDrivenEnabled;
  private MeshShaderBackend meshShaderBackend;
  private ByteBuffer subChunkUploadBuffer;
  private ByteBuffer chunkUniformsBuffer;
  private int subChunkUploadCapacity = 4096;
  private final float[] viewProjMatrix = new float[16];
  private final float[] projMatrixFlat = new float[16];
  private final float[] modelViewFlat = new float[16];
  private final float[] cameraPosFloat = new float[4];
  private final float[] frustumPlanesFlat = new float[24];
  private final int[] gpuCullStats = new int[5];
  private int lastGPUVisibleCount;
  private long lastThermalLogMs;
  private boolean loadingMode;
  private int loadingModePendingCount;
  private int loadingModeMeshCount;
  private int screenshotBlitCooldownFrames;

  public MetalWorldRenderer() {
    this.frustumCuller = new FrustumCuller();
    this.entityRenderer = new MetalEntityRenderer();
    this.particleRenderer = new MetalParticleRenderer();
    this.chunkMesher = new CustomChunkMesher();
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    long device = renderer != null ? renderer.getBackend().getDeviceHandle() : 0;
    this.textureManager = new MetalTextureManager(device);
    this.ioSurfaceBlitter = new IOSurfaceBlitter();
    this.projectionMatrix = new Matrix4f();
    this.modelViewMatrix = new Matrix4f();
    for (BuildLane lane : BuildLane.values()) {
      pendingBuildLanes.put(lane, new java.util.LinkedHashSet<>());
      sortedBuildLanes.put(lane, new java.util.ArrayList<>());
      activeBuildLanes.put(lane, new java.util.LinkedHashSet<>());
    }
    instance = this;
  }

  public static MetalWorldRenderer getInstance() {
    return instance;
  }

  public void onWorldLoad() {
    worldLoaded = true;
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer != null && renderer.isAvailable()) {
      Minecraft mc = Minecraft.getInstance();
      int w = mc.getWindow().getWidth();
      int h = mc.getWindow().getHeight();
      if (w > 0 && h > 0) {
        renderer.resize(w, h);
      }
      chunkMesher.initialize(renderer.getBackend().getDeviceHandle());
      entityRenderer.setup(
          renderer.getBackend().getDeviceHandle(), 0);
      particleRenderer.setup(
          renderer.getBackend().getDeviceHandle());
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
      }
      applyFeatureConfig(MetalRenderClient.getConfig());
      boolean meshShadersActive = NativeBridge.isLibLoaded()
          && NativeBridge.nAreMeshShadersActive();
      MetalLogger.info("GPU-driven pipeline initialized (mesh shaders: %s, enabled: %s)",
          meshShadersActive ? "active"
              : (meshShadersSupported ? "available" : "unsupported"),
          gpuDrivenEnabled ? "yes" : "no");
      MetalLogger.info("Metal world rendering activated (" + w + "x" + h + ")");
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
    frameCount = 0;
    lastDrawnChunkCount = 0;
    if (meshShaderBackend != null) {
      meshShaderBackend.shutdown();
      meshShaderBackend = null;
    }
    gpuDrivenEnabled = false;
    subChunkUploadBuffer = null;
    chunkUniformsBuffer = null;
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
      boolean textureSyncPressure = pendingBuildSet.size() >= TEXTURE_SYNC_PRESSURE_THRESHOLD
          || chunkMesher.getPendingCount() >= TEXTURE_SYNC_PRESSURE_THRESHOLD;
      if (!textureSyncPressure || frameCount % PRESSURED_ATLAS_SYNC_FRAME_INTERVAL == 0) {
        textureManager.updateBlockAtlas();
      }
      if (!textureSyncPressure || frameCount % PRESSURED_LIGHTMAP_SYNC_FRAME_INTERVAL == 0) {
        textureManager.updateLightmap();
      }
    }
    long tTexture1 = System.nanoTime();
    long now = System.currentTimeMillis();
    long diagInterval = chunkMesher.getMeshCount() < 2000 ? 1000 : 5000;
    if (MetalRenderConfig.isDeepDebugActive() && now - lastDiagLogMs > diagInterval) {
      lastDiagLogMs = now;
      MetalLogger.info(
          "DiagWorld: texturesReady=" + texturesReady +
              ", atlasFallback=" + textureManager.isUsingFallbackBlockAtlas() +
              ", meshCount=" + chunkMesher.getMeshCount());
    }
    Camera camera = mc.gameRenderer.getMainCamera();
    Vector3f camPos = new Vector3f((float) camera.position().x,
        (float) camera.position().y,
        (float) camera.position().z);
    if (MetalRenderClient.getConfig().enableMetalRendering) {
      long t0 = System.nanoTime();
      boolean nearMeshLimit = chunkMesher.getMeshCount() >= maxMeshes - 500;
      if (frameCount % 30 == 0 || (nearMeshLimit && !pendingBuildSet.isEmpty())) {
        pruneFarMeshes(mc, camPos);
      }
      long t1 = System.nanoTime();
      CustomChunkMesher.UploadStat uploadStat = drainChunkUploads(false);
      long t2 = System.nanoTime();
      buildPendingChunkMeshes(mc);
      int lightFixed = drainLightFixes(mc, false);
      long t3 = System.nanoTime();
      long t4 = t3;
      jTextureAcc += (tTexture1 - tTexture0);
      jPruneAcc += (t1 - t0);
      jUploadAcc += (t2 - t1);
      jBuildAcc += (t3 - t2);
      jLodAcc += (t4 - t3);
      jUploadJobsAcc += uploadStat.jobs;
      jUploadBytesAcc += uploadStat.bytes;
      jLightFixJobsAcc += lightFixed;
      jProfCount++;
      if (jProfCount >= JAVA_PROFILE_EMIT_INTERVAL) {
        double textureMs = jTextureAcc / 1e6 / jProfCount;
        double uploadMs = jUploadAcc / 1e6 / jProfCount;
        double pruneMs = jPruneAcc / 1e6 / jProfCount;
        double buildMs = jBuildAcc / 1e6 / jProfCount;
        double lodMs = jLodAcc / 1e6 / jProfCount;
        double uploadJobs = (double) jUploadJobsAcc / jProfCount;
        double uploadMb = (jUploadBytesAcc / (1024.0 * 1024.0)) / jProfCount;
        long uploadQueueKb = chunkMesher.getUploadQueueBytes() / 1024L;
        MetalLogger.info(
            "JAVA_PROFILE: texture=%.2fms prune=%.2fms upload=%.2fms build=%.2fms lod=%.2fms (avg/%d) up=%.1f/%.2fMB upQ=%d/%dKB pending=%d queued=%d meshes=%d light=%.1f/%d/%d | lanes builder=%d/%d instant=%d/%d interactive=%d/%d | visible=%.2fms/%d block=%.2fms/%d tracked=%d/%d",
            textureMs, pruneMs, uploadMs, buildMs, lodMs, jProfCount,
            uploadJobs, uploadMb, chunkMesher.getUploadQueueCount(), uploadQueueKb,
            pendingBuildSet.size(), chunkMesher.getPendingCount(),
            chunkMesher.getMeshCount(),
            (double) jLightFixJobsAcc / jProfCount,
            chunkMesher.getLightActiveCount(), chunkMesher.getLightQueueDepth(),
            chunkMesher.getBuilderActiveCount(), chunkMesher.getBuilderQueueDepth(),
            chunkMesher.getInstantActiveCount(), chunkMesher.getInstantQueueDepth(),
            chunkMesher.getInteractiveActiveCount(), chunkMesher.getInteractiveQueueDepth(),
            chunkMesher.getAverageVisibleSectionLatencyMs(), chunkMesher.getVisibleSectionLatencySamples(),
            chunkMesher.getAverageBlockUpdateLatencyMs(), chunkMesher.getBlockUpdateLatencySamples(),
            chunkMesher.getTrackedVisibleSectionCount(), chunkMesher.getTrackedBlockUpdateCount());
        jTextureAcc = 0;
        jUploadAcc = 0;
        jPruneAcc = 0;
        jBuildAcc = 0;
        jLodAcc = 0;
        jUploadBytesAcc = 0;
        jUploadJobsAcc = 0;
        jLightFixJobsAcc = 0;
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
    projectionMatrix.set(projection);
    modelViewMatrix.set(modelView);
    Vector3f camPos = new Vector3f((float) camera.position().x,
        (float) camera.position().y,
        (float) camera.position().z);
    frustumCuller.update(projectionMatrix, modelViewMatrix, camPos);
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
      NativeBridge.nSetRenderDistance(getFarRadiusChunks() * 16);
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
        if (!skipTerrainDraw) {
          long ibHandle = chunkMesher.getGlobalIndexBuffer();
          if (ibHandle != 0) {
            int drawn = NativeBridge.nDrawAllVisibleChunks(frameCtx, ibHandle);
            lastDrawnChunkCount = drawn;
            if (frameCount < 10 || frameCount % 1000 == 0) {
              MetalLogger.info("Frame %d: V18 native drew %d chunks", frameCount, drawn);
            }
          } else {
            lastDrawnChunkCount = 0;
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
        Class<?> attributesClass = Class.forName("net.minecraft.world.attribute.EnvironmentAttributes");
        factorField = attributesClass.getField("SKY_LIGHT_FACTOR");
        getValueMethod = attributeProbe.getClass().getMethod("getValue", factorField.getType(), float.class);
        skyLightFactorField = factorField;
        skyLightProbeGetValueMethod = getValueMethod;
      }
      Object value = getValueMethod.invoke(attributeProbe, factorField.get(null), tickDelta);
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
      ByteBuffer buf = ByteBuffer.allocateDirect(vi * 4)
          .order(ByteOrder.nativeOrder());
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
      NativeBridge.nDrawTriangleBuffer(frameCtx, outlineBufferHandle, vertexCount);
    } catch (Exception e) {
      MetalLogger.error("[BlockOutline] Exception: %s", e.getMessage());
    }
  }

  private int outlineBufferSize = 0;

  private static int addThickEdge(float[] v, int vi,
      float ax, float ay, float az, float bx, float by, float bz,
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

  private enum BuildLane {
    VIEW,
    FIX,
    FRONT,
    FAR,
  }

  private final java.util.LinkedHashSet<Long> pendingBuildSet = new java.util.LinkedHashSet<>();
  private final java.util.EnumMap<BuildLane, java.util.LinkedHashSet<Long>> pendingBuildLanes = new java.util.EnumMap<>(
      BuildLane.class);
  private final java.util.EnumMap<BuildLane, java.util.ArrayList<Long>> sortedBuildLanes = new java.util.EnumMap<>(
      BuildLane.class);
  private final java.util.EnumMap<BuildLane, java.util.LinkedHashSet<Long>> activeBuildLanes = new java.util.EnumMap<>(
      BuildLane.class);
  private boolean sortedListDirty = true;
  private float cachedForwardX = 0, cachedForwardZ = 1;
  private int lastScanPlayerCX = Integer.MIN_VALUE, lastScanPlayerCZ = Integer.MIN_VALUE;
  private int lastScanRenderDist = -1;
  private int turnPriorityFrames = 0;

  private static final class PendingBuildCandidate {
    final BuildLane lane;
    final long key;
    final int index;
    final int chunkX;
    final int chunkY;
    final int chunkZ;
    final int chunkDist;
    final int lodLevel;

    PendingBuildCandidate(BuildLane lane, long key, int index, int chunkX, int chunkY,
        int chunkZ, int chunkDist, int lodLevel) {
      this.lane = lane;
      this.key = key;
      this.index = index;
      this.chunkX = chunkX;
      this.chunkY = chunkY;
      this.chunkZ = chunkZ;
      this.chunkDist = chunkDist;
      this.lodLevel = lodLevel;
    }
  }

  private static long packChunkKey(int cx, int cy, int cz) {
    return ((long) (cx & 0x3FFFFF) << 42) | ((long) (cy & 0xFFFFF) << 22) | (cz & 0x3FFFFF);
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

  private void clearPendingBuilds() {
    pendingBuildSet.clear();
    for (BuildLane lane : BuildLane.values()) {
      pendingBuildLanes.get(lane).clear();
      sortedBuildLanes.get(lane).clear();
      activeBuildLanes.get(lane).clear();
    }
    sortedListDirty = true;
  }

  private void clearActiveBuild(long key) {
    for (BuildLane lane : BuildLane.values()) {
      activeBuildLanes.get(lane).remove(key);
    }
  }

  private BuildLane getPendingLane(long key) {
    for (BuildLane lane : BuildLane.values()) {
      if (pendingBuildLanes.get(lane).contains(key)) {
        return lane;
      }
    }
    return null;
  }

  private boolean isVisibleLane(int chunkX, int chunkZ) {
    Minecraft mc = Minecraft.getInstance();
    if (mc == null || mc.player == null) {
      return false;
    }
    int playerChunkX = mc.player.chunkPosition().x();
    int playerChunkZ = mc.player.chunkPosition().z();
    int dx = chunkX - playerChunkX;
    int dz = chunkZ - playerChunkZ;
    int chunkDist = Math.max(Math.abs(dx), Math.abs(dz));
    return chunkDist <= HOT_LOAD_REBUILD_RANGE
        || (turnPriorityFrames > 0
            && chunkDist <= TURN_PRIORITY_LOADED_CHUNK_RANGE
            && isInForwardPriorityCone(dx, dz));
  }

  private BuildLane classifyBuildLane(int chunkX, int chunkZ,
      boolean rebuild) {
    if (rebuild) {
      return BuildLane.FIX;
    }
    if (isVisibleLane(chunkX, chunkZ)) {
      return BuildLane.VIEW;
    }
    int chunkDist = getChunkDistanceFromPlayer(chunkX, chunkZ);
    int frontRange = Math.max(HOT_LOAD_REBUILD_RANGE + NORMAL_FRONTIER_RING_SCAN_SPAN,
        MetalRenderConfig.zone0RadiusChunks());
    if (chunkDist <= frontRange) {
      return BuildLane.FRONT;
    }
    return BuildLane.FAR;
  }

  private boolean addPendingBuild(long key, BuildLane lane) {
    BuildLane currentLane = getPendingLane(key);
    if (currentLane == lane) {
      return false;
    }
    pendingBuildSet.add(key);
    if (currentLane != null) {
      if (lane.ordinal() >= currentLane.ordinal()) {
        return false;
      }
      pendingBuildLanes.get(currentLane).remove(key);
    }
    pendingBuildLanes.get(lane).add(key);
    sortedListDirty = true;
    return true;
  }

  private void removePendingBuild(long key) {
    pendingBuildSet.remove(key);
    for (BuildLane lane : BuildLane.values()) {
      pendingBuildLanes.get(lane).remove(key);
    }
    clearActiveBuild(key);
    sortedListDirty = true;
  }

  private void removePendingBuild(PendingBuildCandidate candidate) {
    pendingBuildSet.remove(candidate.key);
    pendingBuildLanes.get(candidate.lane).remove(candidate.key);
    sortedBuildLanes.get(candidate.lane).remove(candidate.index);
  }

  private void refreshActiveBuildLanes() {
    for (BuildLane lane : BuildLane.values()) {
      java.util.Iterator<Long> iterator = activeBuildLanes.get(lane).iterator();
      while (iterator.hasNext()) {
        long key = iterator.next();
        if (!chunkMesher.isPendingBuild(unpackChunkX(key), unpackChunkY(key),
            unpackChunkZ(key))) {
          iterator.remove();
        }
      }
    }
  }

  private int getLaneInFlightLimit(BuildLane lane,
      int maxInFlightBuildTasks, boolean startupSolidFill,
      int visibleBacklog) {
    return switch (lane) {
      case VIEW -> startupSolidFill
          ? Math.max(32, (maxInFlightBuildTasks * 2) / 3)
          : Math.max(24, maxInFlightBuildTasks / 2);
      case FIX -> startupSolidFill
          ? Math.max(12, maxInFlightBuildTasks / 4)
          : Math.max(10, maxInFlightBuildTasks / 5);
      case FRONT -> startupSolidFill
          ? Math.max(12, maxInFlightBuildTasks / 3)
          : Math.max(8, maxInFlightBuildTasks / 4);
      case FAR -> {
        int limit = startupSolidFill
            ? Math.max(2, maxInFlightBuildTasks / 16)
            : Math.max(4, maxInFlightBuildTasks / 8);
        if (visibleBacklog >= CHUNK_BACKLOG_HEAVY_THRESHOLD) {
          limit = Math.min(limit, 2);
        } else if (visibleBacklog >= CHUNK_BACKLOG_PRESSURE_THRESHOLD) {
          limit = Math.min(limit, 4);
        }
        yield limit;
      }
    };
  }

  private int getLaneSubmissionBudget(BuildLane lane,
      int highPrioritySubmissions, int backgroundSubmissionBudget,
      boolean startupSolidFill, int visibleBacklog) {
    return switch (lane) {
      case VIEW -> highPrioritySubmissions;
      case FIX -> Math.max(4, highPrioritySubmissions / 2);
      case FRONT -> backgroundSubmissionBudget;
      case FAR -> {
        int limit = startupSolidFill ? 2 : Math.max(2, backgroundSubmissionBudget / 6);
        if (visibleBacklog >= CHUNK_BACKLOG_HEAVY_THRESHOLD) {
          limit = 1;
        } else if (visibleBacklog >= CHUNK_BACKLOG_PRESSURE_THRESHOLD) {
          limit = Math.min(limit, 2);
        }
        yield limit;
      }
    };
  }

  private static int comparePendingKeys(long a, long b, int playerChunkX,
      int playerSectionY, int playerChunkZ, float forwardX, float forwardZ,
      boolean startupSolidFill) {
    int ax = unpackChunkX(a);
    int ay = unpackChunkY(a);
    int az = unpackChunkZ(a);
    int bx = unpackChunkX(b);
    int by = unpackChunkY(b);
    int bz = unpackChunkZ(b);
    float dotA = (ax - playerChunkX) * forwardX + (az - playerChunkZ) * forwardZ;
    float dotB = (bx - playerChunkX) * forwardX + (bz - playerChunkZ) * forwardZ;
    boolean frontA = dotA >= 0;
    boolean frontB = dotB >= 0;
    if (!startupSolidFill && frontA != frontB) {
      return frontA ? -1 : 1;
    }
    int distA = Math.abs(ax - playerChunkX) + Math.abs(az - playerChunkZ);
    int distB = Math.abs(bx - playerChunkX) + Math.abs(bz - playerChunkZ);
    if (distA != distB) {
      return Integer.compare(distA, distB);
    }
    int verticalDistA = Math.abs(ay - playerSectionY);
    int verticalDistB = Math.abs(by - playerSectionY);
    return Integer.compare(verticalDistA, verticalDistB);
  }

  private void rebuildSortedBuildLanes(int playerChunkX, int playerSectionY,
      int playerChunkZ, boolean startupSolidFill) {
    float forwardX = cachedForwardX;
    float forwardZ = cachedForwardZ;
    for (BuildLane lane : BuildLane.values()) {
      java.util.ArrayList<Long> sorted = sortedBuildLanes.get(lane);
      sorted.clear();
      sorted.addAll(pendingBuildLanes.get(lane));
      sorted.sort((a, b) -> comparePendingKeys(a, b, playerChunkX,
          playerSectionY, playerChunkZ, forwardX, forwardZ,
          startupSolidFill));
    }
    sortedListDirty = false;
  }

  private PendingBuildCandidate findPendingCandidate(BuildLane lane,
      ClientLevel world, int playerChunkX, int playerSectionY,
      int playerChunkZ) {
    java.util.ArrayList<Long> sorted = sortedBuildLanes.get(lane);
    java.util.LinkedHashSet<Long> pending = pendingBuildLanes.get(lane);
    boolean startupSolidFill = loadingMode
        && loadingModeMeshCount < STARTUP_SOLID_FILL_MESH_THRESHOLD;
    int index = 0;
    while (index < sorted.size()) {
      long key = sorted.get(index);
      int chunkX = unpackChunkX(key);
      int chunkY = unpackChunkY(key);
      int chunkZ = unpackChunkZ(key);
      if (chunkMesher.hasMesh(chunkX, chunkY, chunkZ)) {
        pendingBuildSet.remove(key);
        pending.remove(key);
        sorted.remove(index);
        continue;
      }
      int dx = chunkX - playerChunkX;
      int dz = chunkZ - playerChunkZ;
      int chunkDist = Math.max(Math.abs(dx), Math.abs(dz));
      int lodLevel = getDesiredLod(dx, dz);
      boolean bypassReadiness = lane == BuildLane.FIX
          || chunkDist <= IMPORTANT_REBUILD_CHUNK_RANGE
          || (loadingMode
              && (chunkDist <= HOT_LOAD_REBUILD_RANGE || startupSolidFill));
      if (!bypassReadiness && !isSectionBuildReady(world, chunkX, chunkY,
          chunkZ)) {
        index++;
        continue;
      }
      return new PendingBuildCandidate(lane, key, index, chunkX, chunkY,
          chunkZ, chunkDist, lodLevel);
    }
    return null;
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
    scanForPendingChunks(mc);
    if (mc.player != null && chunkMesher.getMeshCount() < maxMeshes) {
      int playerChunkX = mc.player.chunkPosition().x();
      int playerChunkZ = mc.player.chunkPosition().z();
      int playerSectionY = mc.player.getBlockY() >> 4;
      boolean turnBurstActive = turnPriorityFrames > 0;
      boolean startupSolidFill = loadingMode && loadingModeMeshCount < STARTUP_SOLID_FILL_MESH_THRESHOLD;
      int mesherPending = chunkMesher.getPendingCount();
      int visibleBacklog = pendingBuildSet.size() + mesherPending;
      boolean fpsPriorityMode = MetalRenderClient.getConfig() != null
          && MetalRenderClient.getConfig().prioritizeFpsOverTps;
      long buildBudget = turnBurstActive ? CHUNK_TURN_BUILD_BURST_NS : CHUNK_BUILD_BUDGET_NS;
      int minBuilds = turnBurstActive ? MIN_CHUNK_TURN_BUILDS_PER_FRAME : MIN_CHUNK_BUILDS_PER_FRAME;
      int highPrioritySubmissions = turnBurstActive
          ? TURN_HIGH_PRIORITY_SUBMISSIONS_PER_PASS
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
            loadingMode ? CHUNK_HEAVY_BACKLOG_BUILD_BURST_NS : CHUNK_BACKLOG_BUILD_BURST_NS);
        minBuilds = Math.max(minBuilds,
            loadingMode ? MIN_CHUNK_HEAVY_BACKLOG_BUILDS_PER_FRAME : MIN_CHUNK_BACKLOG_BUILDS_PER_FRAME);
        highPrioritySubmissions = Math.max(highPrioritySubmissions,
            loadingMode ? HEAVY_BACKLOG_HIGH_PRIORITY_SUBMISSIONS_PER_PASS
                : BACKLOG_HIGH_PRIORITY_SUBMISSIONS_PER_PASS);
      }
      buildFromPendingSet(
          playerChunkX,
          playerSectionY,
          playerChunkZ,
          buildBudget,
          minBuilds,
          highPrioritySubmissions);
      if (!loadingMode
          && pendingBuildSet.size() <= DETAIL_TIER_REBUILD_SCAN_LIMIT / 2
          && chunkMesher.getPendingCount() <= DETAIL_TIER_REBUILD_MAX_PER_PASS
          && frameCount % DETAIL_TIER_REBUILD_FRAME_INTERVAL == 0) {
        rebuildLodMeshes(mc);
      }
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
    boolean startupCoverageActive = chunkMesher.getMeshCount() < STARTUP_SOLID_FILL_MESH_THRESHOLD;
    boolean coverageFillActive = loadingMode || startupCoverageActive;
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
    if (!coverageFillActive && pendingBuildSet.size() >= PRESSURED_PENDING_TRIM_THRESHOLD) {
      trimPendingBuildSet(playerChunkX, playerChunkZ,
          closeRange + TRIM_PRESERVE_EXTRA_RANGE);
      visibleBacklog = pendingBuildSet.size() + mesherPending;
      scanPressured = visibleBacklog >= CHUNK_SCAN_PRESSURE_THRESHOLD;
      scanSaturated = visibleBacklog >= CHUNK_SCAN_SATURATED_THRESHOLD;
    }
    if (scanSaturated && !coverageFillActive
        && pendingBuildSet.size() > MAX_PENDING_BUILD_SET_SIZE_LOADING) {
      trimPendingBuildSet(playerChunkX, playerChunkZ,
          closeRange + TRIM_PRESERVE_EXTRA_RANGE);
      visibleBacklog = pendingBuildSet.size() + mesherPending;
      scanPressured = visibleBacklog >= CHUNK_SCAN_PRESSURE_THRESHOLD;
      scanSaturated = visibleBacklog >= CHUNK_SCAN_SATURATED_THRESHOLD;
    }
    boolean playerMovedChunk = (playerChunkX != lastScanPlayerCX ||
        playerChunkZ != lastScanPlayerCZ);
    boolean renderDistChanged = (renderDist != lastScanRenderDist);
    int frontierShift = playerMovedChunk
        ? Math.max(Math.abs(playerChunkX - lastScanPlayerCX), Math.abs(playerChunkZ - lastScanPlayerCZ))
        : 0;
    if (playerMovedChunk || renderDistChanged) {
      lastScanPlayerCX = playerChunkX;
      lastScanPlayerCZ = playerChunkZ;
      lastScanRenderDist = renderDist;
      sortedListDirty = true;
      lodScanOffset = 0;
      if (renderDistChanged) {
        clearPendingBuilds();
        scanRingsInRange(world, playerChunkX, playerChunkZ, playerSectionY, 0,
            closeRange);
        scanFrontierRing = closeRange + 1;
        scanFrameCounter = 0;
      } else {
        scanRingsInRange(world, playerChunkX, playerChunkZ, playerSectionY, 0,
            closeRange);
        scanFrontierRing = Math.max(closeRange + 1, scanFrontierRing - frontierShift);
      }
    }
    long nowNs = System.nanoTime();
    boolean fullRescanDue = lastFullRescanNs == 0L
        || nowNs - lastFullRescanNs >= FULL_RENDERDIST_RESCAN_INTERVAL_NS;
    scanFrameCounter++;
    if (fullRescanDue) {
      scanRingsInRange(world, playerChunkX, playerChunkZ, playerSectionY, 0,
          closeRange);
      lastFullRescanNs = nowNs;
      scanFrameCounter = 0;
      scanFrontierRing = closeRange + 1;
    } else {
      boolean queuePressure = !pendingBuildSet.isEmpty() || chunkMesher.getPendingCount() > 0;
      int closeRangeRescanInterval = queuePressure
          ? ACTIVE_CLOSE_RANGE_RESCAN_INTERVAL
          : IDLE_CLOSE_RANGE_RESCAN_INTERVAL;
      if (!playerMovedChunk && scanFrameCounter % closeRangeRescanInterval == 0) {
        scanRingsInRange(world, playerChunkX, playerChunkZ, playerSectionY, 0,
            closeRange);
      }
      int frontierStart = Math.max(closeRange + 1, scanFrontierRing);
      int frontierSpan = coverageFillActive
          ? LOADING_FRONTIER_RING_SCAN_SPAN
          : (scanPressured ? PRESSURED_FRONTIER_RING_SCAN_SPAN : NORMAL_FRONTIER_RING_SCAN_SPAN);
      if (!coverageFillActive && pendingBuildSet.size() >= PRESSURED_PENDING_TRIM_THRESHOLD) {
        frontierSpan = 0;
      } else if (scanSaturated) {
        frontierSpan = 0;
      } else if (scanPressured) {
        frontierSpan = Math.min(frontierSpan, 1);
      }
      int frontierEnd = Math.min(frontierStart + frontierSpan - 1, renderDist);
      if (frontierSpan > 0 && frontierStart <= renderDist) {
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
      int playerChunkZ, int playerSectionY, int startRing, int endRing) {
    for (int ring = startRing; ring <= endRing; ring++) {
      if (ring == 0) {
        queueChunkSectionsIfMissing(world, playerChunkX, playerChunkZ,
            playerSectionY, 0);
        continue;
      }
      for (int dx = -ring; dx <= ring; dx++) {
        int dz = ring - Math.abs(dx);
        int cx = playerChunkX + dx;
        int cz = playerChunkZ + dz;
        queueChunkSectionsIfMissing(world, cx, cz, playerSectionY, ring);
        if (dz != 0) {
          queueChunkSectionsIfMissing(world, cx, playerChunkZ - dz,
              playerSectionY, ring);
        }
      }
    }
  }

  private void scanForwardSector(ClientLevel world, int playerChunkX, int playerChunkZ,
      int playerSectionY, int renderDist) {
    int startRing = Math.min(HOT_LOAD_REBUILD_RANGE, renderDist) + 1;
    scanForwardSector(world, playerChunkX, playerChunkZ, playerSectionY,
        startRing, renderDist);
  }

  private void scanForwardSector(ClientLevel world, int playerChunkX, int playerChunkZ,
      int playerSectionY, int startRing, int endRing) {
    float minForwardDotSq = TURN_PRIORITY_SCAN_COS_THRESHOLD * TURN_PRIORITY_SCAN_COS_THRESHOLD;
    for (int ring = startRing; ring <= endRing; ring++) {
      for (int dx = -ring; dx <= ring; dx++) {
        int dz = ring - Math.abs(dx);
        for (int sign = -1; sign <= 1; sign += 2) {
          if (dz == 0 && sign > 0) {
            continue;
          }
          int offZ = dz * sign;
          if (dx == 0 && offZ == 0) {
            continue;
          }
          float forwardDot = dx * cachedForwardX + offZ * cachedForwardZ;
          if (forwardDot <= 0.0f) {
            continue;
          }
          float distSq = (dx * dx) + (offZ * offZ);
          if (forwardDot * forwardDot < distSq * minForwardDotSq) {
            continue;
          }
          queueChunkSectionsIfMissing(world, playerChunkX + dx,
              playerChunkZ + offZ, playerSectionY, ring);
        }
      }
    }
  }

  private void trimPendingBuildSet(int playerChunkX, int playerChunkZ, int keepRange) {
    if (pendingBuildSet.isEmpty()) {
      return;
    }
    java.util.ArrayList<Long> removedKeys = new java.util.ArrayList<>();
    java.util.Iterator<Long> iterator = pendingBuildSet.iterator();
    while (iterator.hasNext()) {
      long key = iterator.next();
      BuildLane lane = getPendingLane(key);
      if (lane == BuildLane.VIEW || lane == BuildLane.FIX) {
        continue;
      }
      int chunkX = unpackChunkX(key);
      int chunkZ = unpackChunkZ(key);
      int dx = chunkX - playerChunkX;
      int dz = chunkZ - playerChunkZ;
      int chunkDistance = Math.max(Math.abs(dx), Math.abs(dz));
      if (chunkDistance <= keepRange || isInForwardPriorityCone(dx, dz)) {
        continue;
      }
      iterator.remove();
      removedKeys.add(key);
    }
    if (!removedKeys.isEmpty()) {
      for (long key : removedKeys) {
        for (BuildLane lane : BuildLane.values()) {
          pendingBuildLanes.get(lane).remove(key);
          sortedBuildLanes.get(lane).remove(key);
        }
      }
      sortedListDirty = true;
    }
  }

  private int getScanVerticalRange(int chunkDistance) {
    if (chunkDistance <= HOT_LOAD_REBUILD_RANGE) {
      return HIGH_PRIORITY_LOADED_VERTICAL_RANGE;
    }
    int desiredLod = getDesiredLod(chunkDistance);
    if (desiredLod <= 1) {
      return MID_DISTANCE_SCAN_VERTICAL_RANGE;
    }
    if (desiredLod == 2) {
      return FAR_DISTANCE_SCAN_VERTICAL_RANGE;
    }
    return EXTREME_DISTANCE_SCAN_VERTICAL_RANGE;
  }

  private void queueChunkSectionsIfMissing(ClientLevel world, int chunkX,
      int chunkZ, int playerSectionY, int chunkDistance) {
    if (chunkDistance > IMPORTANT_REBUILD_CHUNK_RANGE
        && pendingBuildSet.size() >= HARD_PENDING_BUILD_SET_SIZE) {
      return;
    }
    int pendingCap = loadingMode
        ? MAX_PENDING_BUILD_SET_SIZE_LOADING
        : MAX_PENDING_BUILD_SET_SIZE;
    if (chunkDistance > HOT_LOAD_REBUILD_RANGE && pendingBuildSet.size() >= pendingCap) {
      return;
    }
    LevelChunk chunk = world.getChunkSource().getChunkNow(chunkX, chunkZ);
    if (chunk == null)
      return;
    LevelChunkSection[] sections = chunk.getSections();
    int maxVerticalRange = getScanVerticalRange(chunkDistance);
    if (maxVerticalRange != Integer.MAX_VALUE) {
      if (pendingBuildSet.size() >= CHUNK_SCAN_SATURATED_THRESHOLD) {
        maxVerticalRange = Math.min(maxVerticalRange, 1);
      } else if (pendingBuildSet.size() >= CHUNK_SCAN_PRESSURE_THRESHOLD) {
        maxVerticalRange = Math.min(maxVerticalRange, 2);
      }
    }
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
    boolean surfaceOnlyDistance = chunkDistance >= SURFACE_ONLY_SECTION_DISTANCE;
    for (int sy = 0; sy < sections.length; sy++) {
      LevelChunkSection section = sections[sy];
      if (section == null || section.hasOnlyAir())
        continue;
      int worldY = chunk.getSectionYFromSectionIndex(sy);
      if (maxVerticalRange != Integer.MAX_VALUE) {
        boolean withinVerticalWindow = !surfaceOnlyDistance
            && Math.abs(worldY - playerSectionY) <= maxVerticalRange;
        boolean withinSurfaceBand = highestNonAirSection != Integer.MIN_VALUE
            && worldY >= highestNonAirSection - SURFACE_SECTION_EXTRA_DEPTH;
        if (!withinVerticalWindow && !withinSurfaceBand) {
          continue;
        }
      }
      if (!chunkMesher.hasMesh(chunkX, worldY, chunkZ)) {
        if (chunkDistance > IMPORTANT_REBUILD_CHUNK_RANGE
            && pendingBuildSet.size() >= HARD_PENDING_BUILD_SET_SIZE) {
          return;
        }
        if (chunkDistance > HOT_LOAD_REBUILD_RANGE && pendingBuildSet.size() >= pendingCap) {
          return;
        }
        chunkMesher.noteSectionAvailable(chunkX, worldY, chunkZ);
        addPendingBuild(packChunkKey(chunkX, worldY, chunkZ),
            classifyBuildLane(chunkX, chunkZ, false));
      }
    }
  }

  private long lastChunkDiagMs = 0;

  private void logServerChunkAvailability(ClientLevel world, int playerChunkX, int playerChunkZ, int renderDist) {
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
      if (ring == 0) {
        total++;
        if (world.getChunkSource().getChunkNow(playerChunkX, playerChunkZ) != null) {
          available++;
          ringAvail++;
        }
        if (ringAvail > 0) {
          maxRingAvail = ring;
        }
        continue;
      }
      for (int dx = -ring; dx <= ring; dx++) {
        int dz = ring - Math.abs(dx);
        total++;
        if (world.getChunkSource().getChunkNow(playerChunkX + dx,
            playerChunkZ + dz) != null) {
          available++;
          ringAvail++;
        }
        if (dz != 0) {
          total++;
          if (world.getChunkSource().getChunkNow(playerChunkX + dx,
              playerChunkZ - dz) != null) {
            available++;
            ringAvail++;
          }
        }
      }
      if (ringAvail > 0)
        maxRingAvail = ring;
    }
    MetalLogger.info("CHUNK_AVAIL: server=%d/%d (max_ring=%d) meshes=%d pending=%d",
        available, total, maxRingAvail, chunkMesher.getMeshCount(), pendingBuildSet.size());
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
      CustomChunkMesher.UploadStat uploadStat = drainChunkUploads(true);
      int built = buildFromPendingSet(playerChunkX, playerSectionY, playerChunkZ,
          CHUNK_BUILD_WAIT_BUDGET_NS,
          MIN_CHUNK_BUILDS_DURING_WAIT,
          WAIT_HIGH_PRIORITY_SUBMISSIONS_PER_PASS);
      int lightFixed = drainLightFixes(mc, true);
      if (built == 0 && uploadStat.jobs == 0 && lightFixed == 0)
        break;
      totalBuilt += built;
    }
    return totalBuilt;
  }

  private CustomChunkMesher.UploadStat drainChunkUploads(boolean waitMode) {
    int backlog = pendingBuildSet.size() + chunkMesher.getPendingCount();
    boolean startupSolidFill = backlog > 0
        && chunkMesher.getMeshCount() < STARTUP_SOLID_FILL_MESH_THRESHOLD;
    long budget = waitMode
        ? CHUNK_UPLOAD_WAIT_BUDGET_NS
        : (startupSolidFill ? CHUNK_UPLOAD_STARTUP_BUDGET_NS : CHUNK_UPLOAD_BUDGET_NS);
    int minUploads = waitMode
        ? MIN_CHUNK_UPLOADS_DURING_WAIT
        : (startupSolidFill ? MIN_CHUNK_UPLOADS_STARTUP : MIN_CHUNK_UPLOADS_PER_FRAME);
    int maxUploads = waitMode
        ? MAX_CHUNK_UPLOADS_DURING_WAIT
        : (startupSolidFill ? MAX_CHUNK_UPLOADS_STARTUP : MAX_CHUNK_UPLOADS_PER_FRAME);
    return chunkMesher.drainUploads(budget, minUploads, maxUploads);
  }

  private int drainLightFixes(Minecraft mc, boolean waitMode) {
    if (mc == null || mc.player == null || mc.level == null) {
      return 0;
    }
    int visibleBacklog = pendingBuildSet.size() + chunkMesher.getPendingCount();
    int maxFixes = waitMode ? MAX_LIGHT_FIXES_DURING_WAIT : MAX_LIGHT_FIXES_PER_FRAME;
    if (!waitMode) {
      if (loadingMode || visibleBacklog >= CHUNK_BACKLOG_HEAVY_THRESHOLD) {
        maxFixes = 0;
      } else if (visibleBacklog >= CHUNK_BACKLOG_PRESSURE_THRESHOLD) {
        maxFixes = 1;
      }
    }
    if (maxFixes <= 0 || chunkMesher.getLightActiveCount() >= MAX_LIGHT_FIX_IN_FLIGHT) {
      return 0;
    }
    int submitted = 0;
    int scanned = 0;
    int scanLimit = Math.max(1, maxFixes * MAX_LIGHT_FIX_SCAN_MULTIPLIER);
    int playerChunkX = mc.player.chunkPosition().x();
    int playerChunkZ = mc.player.chunkPosition().z();
    while (submitted < maxFixes
        && scanned < scanLimit
        && chunkMesher.getLightActiveCount() < MAX_LIGHT_FIX_IN_FLIGHT) {
      Long keyObj = chunkMesher.pollLightFixKey();
      if (keyObj == null) {
        break;
      }
      scanned++;
      long key = keyObj.longValue();
      int chunkX = unpackChunkX(key);
      int chunkY = unpackChunkY(key);
      int chunkZ = unpackChunkZ(key);
      if (chunkMesher.isPendingBuild(chunkX, chunkY, chunkZ)) {
        chunkMesher.requeueLightFix(key);
        continue;
      }
      if (!chunkMesher.hasMeshIgnoreDirty(chunkX, chunkY, chunkZ)
          || !chunkMesher.needsLightFix(chunkX, chunkY, chunkZ)) {
        continue;
      }
      if (classifyBuildLane(chunkX, chunkZ, false) == BuildLane.FAR) {
        chunkMesher.requeueLightFix(key);
        continue;
      }
      chunkMesher.buildMeshFromWorldLightFix(chunkX, chunkY, chunkZ,
          getDesiredLod(chunkX - playerChunkX, chunkZ - playerChunkZ));
      submitted++;
    }
    return submitted;
  }

  private int buildFromPendingSet(int playerChunkX, int playerSectionY, int playerChunkZ,
      long budgetNanos, int minBuilds, int highPrioritySubmissions) {
    if (pendingBuildSet.isEmpty())
      return 0;
    boolean startupSolidFill = loadingMode && loadingModeMeshCount < STARTUP_SOLID_FILL_MESH_THRESHOLD;
    if (sortedListDirty) {
      rebuildSortedBuildLanes(playerChunkX, playerSectionY, playerChunkZ,
          startupSolidFill);
    }
    Minecraft mc = Minecraft.getInstance();
    ClientLevel world = mc != null ? mc.level : null;
    if (world == null) {
      return 0;
    }
    long deadline = budgetNanos > 0 ? System.nanoTime() + budgetNanos : Long.MAX_VALUE;
    int maxSubmit = 160;
    int built = 0;
    int importantSubmitted = 0;
    int backgroundSubmissions = 0;
    boolean fpsPriorityMode = MetalRenderClient.getConfig() != null
        && MetalRenderClient.getConfig().prioritizeFpsOverTps;
    int maxInFlightBuildTasks = fpsPriorityMode
        ? FPS_PRIORITY_MAX_IN_FLIGHT_BUILD_TASKS
        : MAX_IN_FLIGHT_BUILD_TASKS;
    int backgroundInFlightLimit = Math.max(1,
        maxInFlightBuildTasks - RESERVED_PRIORITY_IN_FLIGHT_SLOTS);
    int backgroundSubmissionBudget = loadingMode
        ? LOADING_BACKGROUND_SUBMISSIONS_PER_PASS
        : (turnPriorityFrames > 0 ? TURN_PRIORITY_BACKGROUND_SUBMISSIONS_PER_PASS
            : NORMAL_BACKGROUND_SUBMISSIONS_PER_PASS);
    if (fpsPriorityMode) {
      backgroundInFlightLimit = maxInFlightBuildTasks;
      backgroundSubmissionBudget = Math.max(backgroundSubmissionBudget,
          loadingMode ? FPS_PRIORITY_LOADING_BACKGROUND_SUBMISSIONS_PER_PASS
              : FPS_PRIORITY_NORMAL_BACKGROUND_SUBMISSIONS_PER_PASS);
    }
    int visibleBacklog = pendingBuildSet.size() + chunkMesher.getPendingCount();
    if (!startupSolidFill) {
      if (visibleBacklog >= CHUNK_BACKLOG_HEAVY_THRESHOLD) {
        backgroundInFlightLimit = Math.min(backgroundInFlightLimit,
            HEAVY_BACKGROUND_IN_FLIGHT_LIMIT);
        backgroundSubmissionBudget = Math.min(backgroundSubmissionBudget,
            HEAVY_BACKGROUND_SUBMISSIONS_PER_PASS);
      } else if (visibleBacklog >= CHUNK_BACKLOG_PRESSURE_THRESHOLD) {
        backgroundInFlightLimit = Math.min(backgroundInFlightLimit,
            PRESSURED_BACKGROUND_IN_FLIGHT_LIMIT);
        backgroundSubmissionBudget = Math.min(backgroundSubmissionBudget,
            PRESSURED_BACKGROUND_SUBMISSIONS_PER_PASS);
      }
    }
    int viewBudget = getLaneSubmissionBudget(BuildLane.VIEW,
        highPrioritySubmissions, backgroundSubmissionBudget,
        startupSolidFill, visibleBacklog);
    int fixBudget = getLaneSubmissionBudget(BuildLane.FIX,
        highPrioritySubmissions, backgroundSubmissionBudget,
        startupSolidFill, visibleBacklog);
    int frontBudget = getLaneSubmissionBudget(BuildLane.FRONT,
        highPrioritySubmissions, backgroundSubmissionBudget,
        startupSolidFill, visibleBacklog);
    int farBudget = getLaneSubmissionBudget(BuildLane.FAR,
        highPrioritySubmissions, backgroundSubmissionBudget,
        startupSolidFill, visibleBacklog);
    int viewSubmitted = 0;
    int fixSubmitted = 0;
    int frontSubmitted = 0;
    int farSubmitted = 0;
    while (built < maxSubmit && chunkMesher.getMeshCount() < maxMeshes) {
      if (budgetNanos > 0 && built >= minBuilds && System.nanoTime() >= deadline)
        break;
      if (chunkMesher.getPendingCount() >= maxInFlightBuildTasks) {
        break;
      }
      refreshActiveBuildLanes();
      PendingBuildCandidate candidate = null;
      boolean highPriority = false;
      if (viewSubmitted < viewBudget
          && activeBuildLanes.get(BuildLane.VIEW).size() < getLaneInFlightLimit(
              BuildLane.VIEW, maxInFlightBuildTasks, startupSolidFill,
              visibleBacklog)) {
        candidate = findPendingCandidate(BuildLane.VIEW, world, playerChunkX,
            playerSectionY, playerChunkZ);
        highPriority = candidate != null;
      }
      if (candidate == null
          && fixSubmitted < fixBudget
          && activeBuildLanes.get(BuildLane.FIX).size() < getLaneInFlightLimit(
              BuildLane.FIX, maxInFlightBuildTasks, startupSolidFill,
              visibleBacklog)) {
        candidate = findPendingCandidate(BuildLane.FIX, world, playerChunkX,
            playerSectionY, playerChunkZ);
        highPriority = candidate != null
            && isImportantPendingBuild(candidate.chunkX - playerChunkX,
                candidate.chunkZ - playerChunkZ, candidate.chunkDist);
      }
      if (candidate == null
          && frontSubmitted < frontBudget
          && activeBuildLanes.get(BuildLane.FRONT).size() < getLaneInFlightLimit(
              BuildLane.FRONT, maxInFlightBuildTasks, startupSolidFill,
              visibleBacklog)) {
        candidate = findPendingCandidate(BuildLane.FRONT, world, playerChunkX,
            playerSectionY, playerChunkZ);
      }
      if (candidate == null
          && farSubmitted < farBudget
          && activeBuildLanes.get(BuildLane.FAR).size() < getLaneInFlightLimit(
              BuildLane.FAR, maxInFlightBuildTasks, startupSolidFill,
              visibleBacklog)) {
        candidate = findPendingCandidate(BuildLane.FAR, world, playerChunkX,
            playerSectionY, playerChunkZ);
      }
      if (candidate == null) {
        break;
      }
      if (!highPriority) {
        highPriority = candidate.lane == BuildLane.VIEW
            || (candidate.lane == BuildLane.FIX
                && isImportantPendingBuild(candidate.chunkX - playerChunkX,
                    candidate.chunkZ - playerChunkZ, candidate.chunkDist));
      }
      boolean interactivePriority = candidate.lodLevel == 0
          && (candidate.lane == BuildLane.VIEW || candidate.lane == BuildLane.FIX)
          && highPriority
          && candidate.chunkDist <= INTERACTIVE_PRIORITY_CHUNK_RANGE
          && (loadingMode || turnPriorityFrames > 0
              || candidate.lane == BuildLane.FIX)
          && chunkMesher.getInteractiveQueueDepth() < MAX_INTERACTIVE_PRIORITY_QUEUE_DEPTH;
      if (candidate.lane == BuildLane.FRONT || candidate.lane == BuildLane.FAR) {
        if (backgroundSubmissions >= backgroundSubmissionBudget) {
          break;
        }
        if (chunkMesher.getPendingCount() >= backgroundInFlightLimit) {
          break;
        }
      }
      if (interactivePriority) {
        chunkMesher.buildMeshFromWorldInteractive(candidate.chunkX,
            candidate.chunkY, candidate.chunkZ);
      } else {
        chunkMesher.buildMeshFromWorld(candidate.chunkX, candidate.chunkY,
            candidate.chunkZ, candidate.lodLevel, highPriority);
      }
      clearActiveBuild(candidate.key);
      activeBuildLanes.get(candidate.lane).add(candidate.key);
      switch (candidate.lane) {
        case VIEW -> viewSubmitted++;
        case FIX -> fixSubmitted++;
        case FRONT -> {
          frontSubmitted++;
          backgroundSubmissions++;
        }
        case FAR -> {
          farSubmitted++;
          backgroundSubmissions++;
        }
      }
      removePendingBuild(candidate);
      built++;
    }
    return built;
  }

  private int lodScanOffset = 0;

  private void rebuildLodMeshes(Minecraft mc) {
    if (mc.player == null || mc.level == null)
      return;
    if (loadingMode || pendingBuildSet.size() > DETAIL_TIER_REBUILD_SCAN_LIMIT / 2) {
      return;
    }
    boolean fpsPriorityMode = MetalRenderClient.getConfig() != null
        && MetalRenderClient.getConfig().prioritizeFpsOverTps;
    int maxInFlightBuildTasks = fpsPriorityMode
        ? FPS_PRIORITY_MAX_IN_FLIGHT_BUILD_TASKS
        : MAX_IN_FLIGHT_BUILD_TASKS;
    if (chunkMesher.getPendingCount() >= maxInFlightBuildTasks) {
      return;
    }
    int playerChunkX = mc.player.chunkPosition().x();
    int playerChunkZ = mc.player.chunkPosition().z();
    int rebuilt = 0;
    long deadline = System.nanoTime() + DETAIL_TIER_REBUILD_BUDGET_NS;
    int maxScansPerPass = DETAIL_TIER_REBUILD_SCAN_LIMIT;
    int scanned = 0;
    var allMeshes = chunkMesher.getAllMeshes();
    if (allMeshes.isEmpty())
      return;
    var iter = allMeshes.iterator();
    int skip = lodScanOffset;
    while (skip > 0 && iter.hasNext()) {
      iter.next();
      skip--;
    }
    while (iter.hasNext() && scanned < maxScansPerPass) {
      if (rebuilt >= DETAIL_TIER_REBUILD_MAX_PER_PASS)
        break;
      if (rebuilt > 0 && System.nanoTime() >= deadline)
        break;
      CustomChunkMesher.ChunkMeshData mesh = iter.next();
      scanned++;
      int dx = mesh.chunkX - playerChunkX;
      int dz = mesh.chunkZ - playerChunkZ;
      int chunkDist = Math.max(Math.abs(dx), Math.abs(dz));
      int desiredLod = getDesiredLod(dx, dz);
      if (chunkMesher.needsLodRebuild(mesh.chunkX, mesh.chunkY, mesh.chunkZ,
          desiredLod)) {
        chunkMesher.buildMeshFromWorld(mesh.chunkX, mesh.chunkY, mesh.chunkZ,
            desiredLod);
        rebuilt++;
      }
    }
    lodScanOffset += scanned;
    if (!iter.hasNext() || lodScanOffset >= allMeshes.size()) {
      lodScanOffset = 0;
    }
    if (rebuilt > 0 && frameCount % 60 == 0) {
      MetalLogger.info("[LOD_REBUILD] Rebuilt %d meshes (scanned %d, offset %d)",
          rebuilt, scanned, lodScanOffset);
    }
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

  private void pruneFarMeshes(Minecraft mc,
      org.joml.Vector3f camPos) {
    if (mc.player == null)
      return;
    int zone0 = Math.max(1, MetalRenderConfig.zone0RadiusChunks());
    int far = getFarRadiusChunks();
    float proxyDist = zone0 * 16.0f;
    float keepDist = (far + FAR_KEEP_MARGIN_CHUNKS) * 16.0f;
    float proxyDistSq = proxyDist * proxyDist;
    float keepDistSq = keepDist * keepDist;
    int submitted = 0;
    var iter = chunkMesher.getAllMeshes().iterator();
    while (iter.hasNext()) {
      CustomChunkMesher.ChunkMeshData mesh = iter.next();
      float dx = mesh.chunkX * 16.0f + 8.0f - camPos.x;
      float dz = mesh.chunkZ * 16.0f + 8.0f - camPos.z;
      float distSq = dx * dx + dz * dz;
      if (distSq > keepDistSq) {
        chunkMesher.removeMesh(mesh.chunkX, mesh.chunkY, mesh.chunkZ, false);
        continue;
      }
      if (mesh.lodLevel < 4 && distSq > proxyDistSq
          && submitted < MAX_FAR_PROXY_SUBMITS
          && chunkMesher.getFarFieldDigest(mesh.chunkX, mesh.chunkY,
              mesh.chunkZ) != null) {
        chunkMesher.buildMeshFromDigest(mesh.chunkX, mesh.chunkY, mesh.chunkZ);
        submitted++;
      }
    }
  }

  public static boolean shouldBlitAt(String timingPoint) {
    return "flip_head".equals(timingPoint) || "before_hand".equals(timingPoint);
  }

  private boolean shouldPinLoadedMeshes(Minecraft mc) {
    return getFarRadiusChunks() > MetalRenderConfig.zone0RadiusChunks()
        || (mc != null && mc.options.renderDistance().get() >= PINNED_RENDER_DISTANCE);
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
          config.enableIndirectCommandBuffers,
          config.enableMeshShaders,
          requestArgumentBuffers,
          config.enableProgrammableBlending,
          config.enableMemorylessTargets);
      gpuDrivenEnabled = NativeBridge.nIsGPUDrivenActive();
      MetalLogger.info(
          "RUNTIME_FEATURES: mesh=%s gpuDriven=%s argBuf=%s memoryless=%s requested(mesh=%s icb=%s argBuf=%s memoryless=%s)",
          NativeBridge.nAreMeshShadersActive(),
          gpuDrivenEnabled,
          NativeBridge.nAreArgumentBuffersActive(),
          NativeBridge.nAreMemorylessTargetsActive(),
          config.enableMeshShaders,
          config.enableIndirectCommandBuffers,
          requestArgumentBuffers,
          config.enableMemorylessTargets);
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
    clearPendingBuilds();
    scanFrameCounter = 0;
    scanFrontierRing = 0;
    lodScanOffset = 0;
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
    loadingModeMeshCount = chunkMesher.getMeshCount();
    loadingModePendingCount = pendingBuildSet.size() + chunkMesher.getPendingCount();
    loadingMode = worldLoaded && renderingActive && loadingModePendingCount > 0;
    chunkMesher.setLoadingModeThreadBudget(loadingMode, loadingModePendingCount);
  }

  public void renderFrame(Object viewport, Object matrices, double x, double y,
      double z) {
  }

  public void onChunkLoaded(int chunkX, int chunkZ, LevelChunk chunk) {
    if (!worldLoaded || !renderingActive)
      return;
    boolean highPriorityChunk = shouldPrioritizeLoadedChunk(chunkX, chunkZ);
    Minecraft mc = Minecraft.getInstance();
    int playerChunkX = mc != null && mc.player != null
        ? mc.player.chunkPosition().x()
        : Integer.MIN_VALUE;
    int playerChunkZ = mc != null && mc.player != null
        ? mc.player.chunkPosition().z()
        : Integer.MIN_VALUE;
    int loadedChunkDistance = mc != null && mc.player != null
        ? Math.max(Math.abs(chunkX - playerChunkX), Math.abs(chunkZ - playerChunkZ))
        : Integer.MAX_VALUE;
    boolean immediateBuildChunk = loadedChunkDistance <= IMMEDIATE_LOADED_CHUNK_BUILD_RANGE;
    int playerSectionY = mc != null && mc.player != null
        ? mc.player.getBlockY() >> 4
        : Integer.MIN_VALUE;
    LevelChunkSection[] sections = chunk.getSections();
    int maxVerticalRange = getScanVerticalRange(loadedChunkDistance);
    if (maxVerticalRange != Integer.MAX_VALUE) {
      if (pendingBuildSet.size() >= CHUNK_SCAN_SATURATED_THRESHOLD) {
        maxVerticalRange = Math.min(maxVerticalRange, 1);
      } else if (pendingBuildSet.size() >= CHUNK_SCAN_PRESSURE_THRESHOLD) {
        maxVerticalRange = Math.min(maxVerticalRange, 2);
      }
    }
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
    boolean surfaceOnlyDistance = loadedChunkDistance >= SURFACE_ONLY_SECTION_DISTANCE;
    for (int sy = 0; sy < sections.length; sy++) {
      LevelChunkSection section = sections[sy];
      if (section == null || section.hasOnlyAir())
        continue;
      int worldY = chunk.getSectionYFromSectionIndex(sy);
      if (maxVerticalRange != Integer.MAX_VALUE) {
        boolean withinVerticalWindow = !surfaceOnlyDistance
            && Math.abs(worldY - playerSectionY) <= maxVerticalRange;
        boolean withinSurfaceBand = highestNonAirSection != Integer.MIN_VALUE
            && worldY >= highestNonAirSection - SURFACE_SECTION_EXTRA_DEPTH;
        if (!withinVerticalWindow && !withinSurfaceBand) {
          continue;
        }
      }
      chunkMesher.noteSectionAvailable(chunkX, worldY, chunkZ);
      boolean highPrioritySection = highPriorityChunk
          && Math.abs(worldY - playerSectionY) <= HIGH_PRIORITY_LOADED_VERTICAL_RANGE;
      if (highPrioritySection && immediateBuildChunk
          && mc != null && mc.level != null
          && isSectionBuildReady(mc.level, chunkX, worldY, chunkZ)
          && !chunkMesher.hasMesh(chunkX, worldY, chunkZ)) {
        chunkMesher.buildMeshFromWorld(chunkX, worldY, chunkZ, 0, true);
      } else {
        enqueueSectionBuild(chunkX, worldY, chunkZ, false);
      }
      refreshLoadedNeighborSection(chunkX - 1, worldY, chunkZ);
      refreshLoadedNeighborSection(chunkX + 1, worldY, chunkZ);
      refreshLoadedNeighborSection(chunkX, worldY - 1, chunkZ);
      refreshLoadedNeighborSection(chunkX, worldY + 1, chunkZ);
      refreshLoadedNeighborSection(chunkX, worldY, chunkZ - 1);
      refreshLoadedNeighborSection(chunkX, worldY, chunkZ + 1);
    }
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
    int chunkDistance = Math.max(Math.abs(chunkX - mc.player.chunkPosition().x()),
        Math.abs(chunkZ - mc.player.chunkPosition().z()));
    LevelChunkSection[] sections = chunk.getSections();
    int playerSectionY = mc.player.getBlockY() >> 4;
    int maxVerticalRange = getScanVerticalRange(chunkDistance);
    if (maxVerticalRange != Integer.MAX_VALUE) {
      if (pendingBuildSet.size() >= CHUNK_SCAN_SATURATED_THRESHOLD) {
        maxVerticalRange = Math.min(maxVerticalRange, 1);
      } else if (pendingBuildSet.size() >= CHUNK_SCAN_PRESSURE_THRESHOLD) {
        maxVerticalRange = Math.min(maxVerticalRange, 2);
      }
    }
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
    boolean surfaceOnlyDistance = chunkDistance >= SURFACE_ONLY_SECTION_DISTANCE;
    for (int sy = 0; sy < sections.length; sy++) {
      LevelChunkSection section = sections[sy];
      if (section == null || section.hasOnlyAir()) {
        continue;
      }
      int worldY = chunk.getSectionYFromSectionIndex(sy);
      if (maxVerticalRange != Integer.MAX_VALUE) {
        boolean withinVerticalWindow = !surfaceOnlyDistance
            && Math.abs(worldY - playerSectionY) <= maxVerticalRange;
        boolean withinSurfaceBand = highestNonAirSection != Integer.MIN_VALUE
            && worldY >= highestNonAirSection - SURFACE_SECTION_EXTRA_DEPTH;
        if (!withinVerticalWindow && !withinSurfaceBand) {
          continue;
        }
      }
      if (!chunkMesher.hasMesh(chunkX, worldY, chunkZ)
          && isSectionBuildReady(mc.level, chunkX, worldY, chunkZ)) {
        chunkMesher.noteSectionAvailable(chunkX, worldY, chunkZ);
        enqueueSectionBuild(chunkX, worldY, chunkZ, false);
      }
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
    if (turnPriorityFrames <= 0 || chunkDistance > TURN_PRIORITY_LOADED_CHUNK_RANGE) {
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
    return turnPriorityFrames > 0
        && chunkDist <= TURN_PRIORITY_LOADED_CHUNK_RANGE
        && isInForwardPriorityCone(dx, dz);
  }

  private int getDesiredLod(int chunkDist) {
    return MetalRenderConfig.getLodLevel(chunkDist);
  }

  private int getDesiredLod(int dx, int dz) {
    double chunkDist = Math.hypot(dx, dz);
    if (chunkDist <= 0.0) {
      return 0;
    }
    return MetalRenderConfig.getLodLevel(chunkDist);
  }

  private boolean isSectionBuildReady(ClientLevel world, int chunkX, int chunkY,
      int chunkZ) {
    LevelChunk centerChunk = world.getChunkSource().getChunkNow(chunkX, chunkZ);
    if (centerChunk == null) {
      return false;
    }
    if (world.getChunkSource().getChunkNow(chunkX - 1, chunkZ) == null) {
      return false;
    }
    if (world.getChunkSource().getChunkNow(chunkX + 1, chunkZ) == null) {
      return false;
    }
    if (world.getChunkSource().getChunkNow(chunkX, chunkZ - 1) == null) {
      return false;
    }
    return world.getChunkSource().getChunkNow(chunkX, chunkZ + 1) != null;
  }

  private void enqueueSectionBuild(int chunkX, int worldY, int chunkZ) {
    enqueueSectionBuild(chunkX, worldY, chunkZ, true);
  }

  private void enqueueSectionBuild(int chunkX, int worldY, int chunkZ, boolean limitShape) {
    if (chunkMesher.hasMesh(chunkX, worldY, chunkZ)) {
      return;
    }
    Minecraft mc = Minecraft.getInstance();
    if (limitShape && mc != null && mc.player != null) {
      int dx = chunkX - mc.player.chunkPosition().x();
      int dz = chunkZ - mc.player.chunkPosition().z();
      if (Math.abs(dx) + Math.abs(dz) > Math.max(1, mc.options.renderDistance().get())) {
        return;
      }
    }
    int chunkDistance = getChunkDistanceFromPlayer(chunkX, chunkZ);
    if (shouldRejectQueueForDistance(chunkDistance)) {
      return;
    }
    addPendingBuild(packChunkKey(chunkX, worldY, chunkZ),
        classifyBuildLane(chunkX, chunkZ, false));
  }

  private int getFarRadiusChunks() {
    return Math.max(MetalRenderConfig.zone0RadiusChunks(),
        MetalRenderConfig.farFieldRadiusChunks());
  }

  private int getChunkDistanceFromPlayer(int chunkX, int chunkZ) {
    Minecraft mc = Minecraft.getInstance();
    if (mc == null || mc.player == null) {
      return Integer.MAX_VALUE;
    }
    int playerChunkX = mc.player.chunkPosition().x();
    int playerChunkZ = mc.player.chunkPosition().z();
    return Math.max(Math.abs(chunkX - playerChunkX), Math.abs(chunkZ - playerChunkZ));
  }

  private boolean shouldRejectQueueForDistance(int chunkDistance) {
    boolean startupSolidFill = loadingModeMeshCount < STARTUP_SOLID_FILL_MESH_THRESHOLD;
    if (chunkDistance > IMPORTANT_REBUILD_CHUNK_RANGE
        && pendingBuildSet.size() >= HARD_PENDING_BUILD_SET_SIZE) {
      return true;
    }
    if (!startupSolidFill
        && chunkDistance > HOT_LOAD_REBUILD_RANGE
        && pendingBuildSet.size() >= PRESSURED_PENDING_TRIM_THRESHOLD) {
      return true;
    }
    int pendingCap = loadingMode
        ? MAX_PENDING_BUILD_SET_SIZE_LOADING
        : MAX_PENDING_BUILD_SET_SIZE;
    return chunkDistance > HOT_LOAD_REBUILD_RANGE && pendingBuildSet.size() >= pendingCap;
  }

  private void refreshLoadedNeighborSection(int chunkX, int worldY, int chunkZ) {
    if (getChunkDistanceFromPlayer(chunkX, chunkZ) > HOT_LOAD_REBUILD_RANGE) {
      return;
    }
    if (!chunkMesher.hasMeshIgnoreDirty(chunkX, worldY, chunkZ)) {
      return;
    }
    chunkMesher.markDirty(chunkX, worldY, chunkZ);
    enqueueSectionBuild(chunkX, worldY, chunkZ);
  }

  public void scheduleSectionRebuild(int blockX, int blockY, int blockZ) {
    if (!worldLoaded || !renderingActive) {
      return;
    }
    int cx = blockX >> 4;
    int cy = blockY >> 4;
    int cz = blockZ >> 4;
    chunkMesher.noteBlockUpdate(cx, cy, cz);
    chunkMesher.markDirty(cx, cy, cz);
    chunkMesher.buildMeshFromWorldInteractive(cx, cy, cz);
    markDirtyAndQueue(cx - 1, cy, cz);
    markDirtyAndQueue(cx + 1, cy, cz);
    markDirtyAndQueue(cx, cy - 1, cz);
    markDirtyAndQueue(cx, cy + 1, cz);
    markDirtyAndQueue(cx, cy, cz - 1);
    markDirtyAndQueue(cx, cy, cz + 1);
    updateLoadingModeState();
  }

  private void markDirtyAndQueue(int chunkX, int sectionY, int chunkZ) {
    chunkMesher.markDirty(chunkX, sectionY, chunkZ);
    int chunkDistance = getChunkDistanceFromPlayer(chunkX, chunkZ);
    if (shouldRejectQueueForDistance(chunkDistance)) {
      return;
    }
    addPendingBuild(packChunkKey(chunkX, sectionY, chunkZ),
        classifyBuildLane(chunkX, chunkZ, true));
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
