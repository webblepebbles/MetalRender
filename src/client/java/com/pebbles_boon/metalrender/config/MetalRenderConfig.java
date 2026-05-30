package com.pebbles_boon.metalrender.config;

public final class MetalRenderConfig {
  public boolean enableMetalRendering = true;
  public boolean enableSimpleLighting = true;
  public boolean enableDebugOverlay = false;
  public boolean debugPinkBlockTint = false;
  public int zone1Radius = 16;
  public int zone2Radius = 64;
  public float lodTransitionDistance = 0.8f;
  public int biomeTransitionDetail = 2;
  public boolean enableZone2Lod = true;
  public int leafCullingMode = 0;
  public int targetFrameRate = 60;
  public boolean prioritizeFpsOverTps = false;
  public int maxMemoryMB = 2048;
  public boolean enableTripleBuffering = true;
  public boolean enableMemoryPressureFallback = true;
  public boolean enableBurstThreadMode = false;
  public boolean enableMeshShaders = true;
  public boolean enableArgumentBuffers = false;

  public boolean enableProgrammableBlending = false;
  public boolean enableIndirectCommandBuffers = false;
  public boolean enableMemorylessTargets = false;
  private static final int DEFAULT_ZONE0_RADIUS_CHUNKS = 32;
  private static final int DEFAULT_FAR_FIELD_RADIUS_CHUNKS = 256;
  private static final float DEFAULT_ZONE0_EXACT_BLOCK_PIXELS = 12.0f;
  private static final float DEFAULT_ZONE0_GREEDY_BLOCK_PIXELS = 4.0f;
  private static final float DEFAULT_ZONE0_CLUSTER_BLOCK_PIXELS = 1.5f;
  private static volatile int zone0RadiusChunks = DEFAULT_ZONE0_RADIUS_CHUNKS;
  private static volatile int farFieldRadiusChunks =
      DEFAULT_FAR_FIELD_RADIUS_CHUNKS;
  private static volatile float zone0ExactBlockPixels =
      DEFAULT_ZONE0_EXACT_BLOCK_PIXELS;
  private static volatile float zone0GreedyBlockPixels =
      DEFAULT_ZONE0_GREEDY_BLOCK_PIXELS;
  private static volatile float zone0ClusterBlockPixels =
      DEFAULT_ZONE0_CLUSTER_BLOCK_PIXELS;
  private static volatile boolean mirrorUploads = false;
  private static volatile boolean swapOpaque = false;
  private static volatile boolean swapCutout = false;
  private static volatile boolean swapTranslucent = false;
  private static volatile boolean aggressiveFrustumCulling = false;
  private static volatile boolean occlusionCulling = false;
  private static volatile float resolutionScale = 1.0f;
  private static volatile boolean deepDebugActive = false;
  private static volatile boolean debugPinkBlockTintEnabled = false;

  private static java.nio.file.Path configFile() {
    return net.fabricmc.loader.api.FabricLoader.getInstance()
        .getConfigDir()
        .resolve("metalrender.json");
  }

  private static java.nio.file.Path deepDebugFlagFile() {
    return net.fabricmc.loader.api.FabricLoader.getInstance()
        .getConfigDir()
        .resolve("metalrender-debug-next-run.flag");
  }

  private static void activateOneRunDeepDebugIfRequested() {
    java.nio.file.Path flagPath = deepDebugFlagFile();
    try {
      deepDebugActive = java.nio.file.Files.exists(flagPath);
      if (deepDebugActive) {
        java.nio.file.Files.deleteIfExists(flagPath);
      }
    } catch (Exception e) {
      deepDebugActive = false;
    }
  }

  public static MetalRenderConfig load() {
    activateOneRunDeepDebugIfRequested();
    MetalRenderConfig cfg = new MetalRenderConfig();

    try {
      java.nio.file.Path path = configFile();
      if (java.nio.file.Files.exists(path)) {
        String raw = java.nio.file.Files.readString(path);
        com.google.gson.JsonObject obj =
            com.google.gson.JsonParser.parseString(raw).getAsJsonObject();

        if (obj.has("enableMetalRendering"))
          cfg.enableMetalRendering =
              obj.get("enableMetalRendering").getAsBoolean();
        if (obj.has("enableSimpleLighting"))
          cfg.enableSimpleLighting =
              obj.get("enableSimpleLighting").getAsBoolean();
        if (obj.has("enableDebugOverlay"))
          cfg.enableDebugOverlay = obj.get("enableDebugOverlay").getAsBoolean();
        if (obj.has("debugPinkBlockTint"))
          cfg.debugPinkBlockTint = obj.get("debugPinkBlockTint").getAsBoolean();
        if (obj.has("zone1Radius"))
          cfg.zone1Radius = obj.get("zone1Radius").getAsInt();
        if (obj.has("zone2Radius"))
          cfg.zone2Radius = obj.get("zone2Radius").getAsInt();
        if (obj.has("lodTransitionDistance"))
          cfg.lodTransitionDistance =
              obj.get("lodTransitionDistance").getAsFloat();
        if (obj.has("biomeTransitionDetail"))
          cfg.biomeTransitionDetail =
              obj.get("biomeTransitionDetail").getAsInt();
        if (obj.has("enableZone2Lod"))
          cfg.enableZone2Lod = obj.get("enableZone2Lod").getAsBoolean();
        if (obj.has("leafCullingMode"))
          cfg.leafCullingMode = obj.get("leafCullingMode").getAsInt();
        if (obj.has("targetFrameRate"))
          cfg.targetFrameRate = obj.get("targetFrameRate").getAsInt();
        if (obj.has("prioritizeFpsOverTps"))
          cfg.prioritizeFpsOverTps =
              obj.get("prioritizeFpsOverTps").getAsBoolean();
        if (obj.has("maxMemoryMB"))
          cfg.maxMemoryMB = obj.get("maxMemoryMB").getAsInt();
        if (obj.has("enableTripleBuffering"))
          cfg.enableTripleBuffering =
              obj.get("enableTripleBuffering").getAsBoolean();
        if (obj.has("enableMemoryPressureFallback"))
          cfg.enableMemoryPressureFallback =
              obj.get("enableMemoryPressureFallback").getAsBoolean();
        if (obj.has("enableBurstThreadMode"))
          cfg.enableBurstThreadMode =
              obj.get("enableBurstThreadMode").getAsBoolean();
        if (obj.has("enableMeshShaders"))
          cfg.enableMeshShaders = obj.get("enableMeshShaders").getAsBoolean();
        if (obj.has("enableArgumentBuffers"))
          cfg.enableArgumentBuffers =
              obj.get("enableArgumentBuffers").getAsBoolean();

        if (obj.has("enableIndirectCommandBuffers"))
          cfg.enableIndirectCommandBuffers =
              obj.get("enableIndirectCommandBuffers").getAsBoolean();
        if (obj.has("enableMemorylessTargets"))
          cfg.enableMemorylessTargets =
              obj.get("enableMemorylessTargets").getAsBoolean();

        if (obj.has("lodZone0RadiusChunks")) {
          zone0RadiusChunks = obj.get("lodZone0RadiusChunks").getAsInt();
        } else if (obj.has("savedLod4Distance")) {
          zone0RadiusChunks = Math.max(DEFAULT_ZONE0_RADIUS_CHUNKS,
                                       obj.get("savedLod4Distance").getAsInt());
        }
        if (obj.has("lodFarFieldRadiusChunks"))
          farFieldRadiusChunks = obj.get("lodFarFieldRadiusChunks").getAsInt();
        if (obj.has("lodZone0ExactPixels"))
          zone0ExactBlockPixels = obj.get("lodZone0ExactPixels").getAsFloat();
        if (obj.has("lodZone0GreedyPixels"))
          zone0GreedyBlockPixels = obj.get("lodZone0GreedyPixels").getAsFloat();
        if (obj.has("lodZone0ClusterPixels"))
          zone0ClusterBlockPixels =
              obj.get("lodZone0ClusterPixels").getAsFloat();
        if (obj.has("savedResolutionScale"))
          resolutionScale =
              clamp(obj.get("savedResolutionScale").getAsFloat(), 0.20f, 1.5f);
        if (obj.has("savedAggressiveFrustumCulling"))
          aggressiveFrustumCulling =
              obj.get("savedAggressiveFrustumCulling").getAsBoolean();
        if (obj.has("savedOcclusionCulling"))
          occlusionCulling = obj.get("savedOcclusionCulling").getAsBoolean();
      }
    } catch (Exception e) {
    }

    normalizeLodGroundwork();

    applyStableQualityFallback(cfg);
    setDebugPinkBlockTint(cfg.debugPinkBlockTint);

    cfg.loadFeatureFlags();
    loadFromSystemProperties();
    return cfg;
  }

  private MetalRenderConfig() {}

  private static void applyStableQualityFallback(MetalRenderConfig cfg) {
    aggressiveFrustumCulling = false;
    occlusionCulling = false;
    resolutionScale = 1.0f;
  }

  public void save() {

    System.setProperty("metalrender.enabled",
                       String.valueOf(enableMetalRendering));
    System.setProperty("metalrender.feature.icb",
                       String.valueOf(enableIndirectCommandBuffers));
    System.setProperty("metalrender.feature.mesh",
                       String.valueOf(enableMeshShaders));
    System.setProperty("metalrender.feature.argbuf",
                       String.valueOf(enableArgumentBuffers));
    System.setProperty("metalrender.feature.oit",
                       String.valueOf(enableProgrammableBlending));
    System.setProperty("metalrender.feature.memoryless",
                       String.valueOf(enableMemorylessTargets));

    try {
      com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
      obj.addProperty("enableMetalRendering", enableMetalRendering);
      obj.addProperty("enableSimpleLighting", enableSimpleLighting);
      obj.addProperty("enableDebugOverlay", enableDebugOverlay);
      obj.addProperty("debugPinkBlockTint", debugPinkBlockTint);
      obj.addProperty("zone1Radius", zone1Radius);
      obj.addProperty("zone2Radius", zone2Radius);
      obj.addProperty("lodTransitionDistance", lodTransitionDistance);
      obj.addProperty("biomeTransitionDetail", biomeTransitionDetail);
      obj.addProperty("enableZone2Lod", enableZone2Lod);
      obj.addProperty("leafCullingMode", leafCullingMode);
      obj.addProperty("targetFrameRate", targetFrameRate);
      obj.addProperty("prioritizeFpsOverTps", prioritizeFpsOverTps);
      obj.addProperty("maxMemoryMB", maxMemoryMB);
      obj.addProperty("enableTripleBuffering", enableTripleBuffering);
      obj.addProperty("enableMemoryPressureFallback",
                      enableMemoryPressureFallback);
      obj.addProperty("enableBurstThreadMode", enableBurstThreadMode);
      obj.addProperty("enableMeshShaders", enableMeshShaders);
      obj.addProperty("enableArgumentBuffers", enableArgumentBuffers);
      obj.addProperty("enableProgrammableBlending", enableProgrammableBlending);
      obj.addProperty("enableIndirectCommandBuffers",
                      enableIndirectCommandBuffers);
      obj.addProperty("enableMemorylessTargets", enableMemorylessTargets);

      obj.addProperty("lodZone0RadiusChunks", zone0RadiusChunks);
      obj.addProperty("lodFarFieldRadiusChunks", farFieldRadiusChunks);
      obj.addProperty("lodZone0ExactPixels", zone0ExactBlockPixels);
      obj.addProperty("lodZone0GreedyPixels", zone0GreedyBlockPixels);
      obj.addProperty("lodZone0ClusterPixels", zone0ClusterBlockPixels);
      obj.addProperty("savedResolutionScale", resolutionScale);
      obj.addProperty("savedAggressiveFrustumCulling",
                      aggressiveFrustumCulling);
      obj.addProperty("savedOcclusionCulling", occlusionCulling);
      java.nio.file.Path path = configFile();
      java.nio.file.Files.createDirectories(path.getParent());
      java.nio.file.Files.writeString(
          path,
          new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(
              obj));
    } catch (Exception e) {
    }
  }

  public static boolean mirrorUploads() { return mirrorUploads; }

  public static boolean swapOpaque() { return swapOpaque; }

  public static boolean swapCutout() { return swapCutout; }

  public static boolean swapTranslucent() { return swapTranslucent; }

  public static boolean aggressiveFrustumCulling() {
    return aggressiveFrustumCulling;
  }

  public static boolean occlusionCulling() { return occlusionCulling; }

  public static float resolutionScale() { return resolutionScale; }

  public static boolean isDeepDebugActive() { return deepDebugActive; }

  public static boolean debugPinkBlockTint() {
    return debugPinkBlockTintEnabled;
  }

  public static boolean isOneRunDeepDebugRequested() {
    try {
      return java.nio.file.Files.exists(deepDebugFlagFile());
    } catch (Exception e) {
      return false;
    }
  }

  public static void setOneRunDeepDebugRequested(boolean enabled) {
    java.nio.file.Path flagPath = deepDebugFlagFile();
    try {
      if (enabled) {
        java.nio.file.Files.createDirectories(flagPath.getParent());
        java.nio.file.Files.writeString(flagPath, "enabled\n");
      } else {
        java.nio.file.Files.deleteIfExists(flagPath);
      }
    } catch (Exception e) {
    }
  }

  public static void setMirrorUploads(boolean v) { mirrorUploads = v; }

  public static void setSwapOpaque(boolean v) { swapOpaque = v; }

  public static void setSwapCutout(boolean v) { swapCutout = v; }

  public static void setSwapTranslucent(boolean v) { swapTranslucent = v; }

  public static void setAggressiveFrustumCulling(boolean v) {
    aggressiveFrustumCulling = v;
  }

  public static void setOcclusionCulling(boolean v) { occlusionCulling = v; }

  public static void setResolutionScale(float v) {
    resolutionScale = clamp(v, 0.20f, 1.5f);
  }

  public static void setDebugPinkBlockTint(boolean v) {
    debugPinkBlockTintEnabled = v;
  }

  public static int zone0RadiusChunks() { return zone0RadiusChunks; }

  public static void setZone0RadiusChunks(int v) {
    zone0RadiusChunks = DEFAULT_ZONE0_RADIUS_CHUNKS;
    normalizeLodGroundwork();
  }

  public static int farFieldRadiusChunks() { return farFieldRadiusChunks; }

  public static void setFarFieldRadiusChunks(int v) {
    farFieldRadiusChunks = DEFAULT_FAR_FIELD_RADIUS_CHUNKS;
    normalizeLodGroundwork();
  }

  public static float zone0ExactBlockPixels() { return zone0ExactBlockPixels; }

  public static void setZone0ExactBlockPixels(float v) {
    zone0ExactBlockPixels = v;
    normalizeLodGroundwork();
  }

  public static float zone0GreedyBlockPixels() {
    return zone0GreedyBlockPixels;
  }

  public static void setZone0GreedyBlockPixels(float v) {
    zone0GreedyBlockPixels = v;
    normalizeLodGroundwork();
  }

  public static float zone0ClusterBlockPixels() {
    return zone0ClusterBlockPixels;
  }

  public static void setZone0ClusterBlockPixels(float v) {
    zone0ClusterBlockPixels = v;
    normalizeLodGroundwork();
  }

  public static boolean isZone0LodRuntimeActive() { return true; }

  public static boolean isFarFieldDescriptorRuntimeActive() { return true; }

  public static int getLodLevel(int chunkDistance) {
    return getLodLevel((double)chunkDistance);
  }

  public static int getLodLevel(double chunkDistance) {
    if (!isZone0LodRuntimeActive() || chunkDistance <= 0) {
      return 0;
    }
    float projectedBlockPixels = estimateBlockFacePixels(chunkDistance);
    if (projectedBlockPixels > zone0ExactBlockPixels) {
      return 0;
    }
    if (projectedBlockPixels > zone0GreedyBlockPixels) {
      return 1;
    }
    if (projectedBlockPixels > zone0ClusterBlockPixels) {
      return 2;
    }
    if (chunkDistance <= zone0RadiusChunks) {
      return 3;
    }
    return 4;
  }

  public static void resetLodGroundworkDefaults() {
    zone0RadiusChunks = DEFAULT_ZONE0_RADIUS_CHUNKS;
    farFieldRadiusChunks = DEFAULT_FAR_FIELD_RADIUS_CHUNKS;
    zone0ExactBlockPixels = DEFAULT_ZONE0_EXACT_BLOCK_PIXELS;
    zone0GreedyBlockPixels = DEFAULT_ZONE0_GREEDY_BLOCK_PIXELS;
    zone0ClusterBlockPixels = DEFAULT_ZONE0_CLUSTER_BLOCK_PIXELS;
    normalizeLodGroundwork();
  }

  public static void loadFromSystemProperties() {
    mirrorUploads = getBool("metalrender.mirror", mirrorUploads);
    swapOpaque = getBool("metalrender.swap.opaque", swapOpaque);
    swapCutout = getBool("metalrender.swap.cutout", swapCutout);
    swapTranslucent = getBool("metalrender.swap.translucent", swapTranslucent);
    aggressiveFrustumCulling =
        getBool("metalrender.culling.frustum", aggressiveFrustumCulling);
    occlusionCulling =
        getBool("metalrender.culling.occlusion", occlusionCulling);
    resolutionScale =
        getFloat("metalrender.render.resolutionScale", resolutionScale);
  }

  public void loadFeatureFlags() {
    enableIndirectCommandBuffers =
        getBool("metalrender.feature.icb", enableIndirectCommandBuffers);
    enableMeshShaders = getBool("metalrender.feature.mesh", enableMeshShaders);
    enableArgumentBuffers =
        getBool("metalrender.feature.argbuf", enableArgumentBuffers);
    enableProgrammableBlending =
        getBool("metalrender.feature.oit", enableProgrammableBlending);
    enableMemorylessTargets =
        getBool("metalrender.feature.memoryless", enableMemorylessTargets);
  }

  private static boolean getBool(String key, boolean def) {
    String v = System.getProperty(key);
    if (v == null)
      return def;
    return "1".equals(v) || Boolean.parseBoolean(v);
  }

  private static float getFloat(String key, float def) {
    String v = System.getProperty(key);
    if (v == null)
      return def;
    try {
      return Float.parseFloat(v);
    } catch (NumberFormatException ex) {
      return def;
    }
  }

  private static void normalizeLodGroundwork() {
    zone0RadiusChunks = DEFAULT_ZONE0_RADIUS_CHUNKS;
    farFieldRadiusChunks = DEFAULT_FAR_FIELD_RADIUS_CHUNKS;
    zone0ExactBlockPixels = clamp(zone0ExactBlockPixels, 6.0f, 24.0f);
    zone0GreedyBlockPixels =
        clamp(zone0GreedyBlockPixels, 2.0f, zone0ExactBlockPixels - 0.5f);
    zone0ClusterBlockPixels =
        clamp(zone0ClusterBlockPixels, 0.5f, zone0GreedyBlockPixels - 0.25f);
  }

  private static float estimateBlockFacePixels(double chunkDistance) {
    net.minecraft.client.Minecraft mc =
        net.minecraft.client.Minecraft.getInstance();
    double screenHeight = 1080.0;
    double fovDegrees = 70.0;
    if (mc != null) {
      if (mc.getWindow() != null) {
        screenHeight = Math.max(1.0, mc.getWindow().getHeight());
      }
      if (mc.options != null) {
        fovDegrees = mc.options.fov().get();
      }
    }
    double clampedFovRadians =
        Math.toRadians(clamp((float)fovDegrees, 30.0f, 110.0f));
    double distanceBlocks = Math.max(1.0, chunkDistance * 16.0);
    double denominator =
        2.0 * Math.tan(clampedFovRadians * 0.5) * distanceBlocks;
    if (denominator <= 0.0) {
      return DEFAULT_ZONE0_EXACT_BLOCK_PIXELS;
    }
    return (float)(screenHeight / denominator);
  }

  private static float clamp(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
  }
}
