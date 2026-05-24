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
  private static volatile int lod1Distance = 8;
  private static volatile int lod2Distance = 16;
  private static volatile int lod3Distance = 24;
  private static volatile int lod4Distance = 32;
  private static volatile boolean lodEnabled = true;
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
        .getConfigDir().resolve("metalrender.json");
  }

  private static java.nio.file.Path deepDebugFlagFile() {
    return net.fabricmc.loader.api.FabricLoader.getInstance()
        .getConfigDir().resolve("metalrender-debug-next-run.flag");
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
        com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();

        if (obj.has("enableMetalRendering"))
          cfg.enableMetalRendering = obj.get("enableMetalRendering").getAsBoolean();
        if (obj.has("enableSimpleLighting"))
          cfg.enableSimpleLighting = obj.get("enableSimpleLighting").getAsBoolean();
        if (obj.has("enableDebugOverlay"))
          cfg.enableDebugOverlay = obj.get("enableDebugOverlay").getAsBoolean();
        if (obj.has("debugPinkBlockTint"))
          cfg.debugPinkBlockTint = obj.get("debugPinkBlockTint").getAsBoolean();
        if (obj.has("zone1Radius"))
          cfg.zone1Radius = obj.get("zone1Radius").getAsInt();
        if (obj.has("zone2Radius"))
          cfg.zone2Radius = obj.get("zone2Radius").getAsInt();
        if (obj.has("lodTransitionDistance"))
          cfg.lodTransitionDistance = obj.get("lodTransitionDistance").getAsFloat();
        if (obj.has("biomeTransitionDetail"))
          cfg.biomeTransitionDetail = obj.get("biomeTransitionDetail").getAsInt();
        if (obj.has("enableZone2Lod"))
          cfg.enableZone2Lod = obj.get("enableZone2Lod").getAsBoolean();
        if (obj.has("leafCullingMode"))
          cfg.leafCullingMode = obj.get("leafCullingMode").getAsInt();
        if (obj.has("targetFrameRate"))
          cfg.targetFrameRate = obj.get("targetFrameRate").getAsInt();
        if (obj.has("prioritizeFpsOverTps"))
          cfg.prioritizeFpsOverTps = obj.get("prioritizeFpsOverTps").getAsBoolean();
        if (obj.has("maxMemoryMB"))
          cfg.maxMemoryMB = obj.get("maxMemoryMB").getAsInt();
        if (obj.has("enableTripleBuffering"))
          cfg.enableTripleBuffering = obj.get("enableTripleBuffering").getAsBoolean();
        if (obj.has("enableMemoryPressureFallback"))
          cfg.enableMemoryPressureFallback = obj.get("enableMemoryPressureFallback").getAsBoolean();
        if (obj.has("enableBurstThreadMode"))
          cfg.enableBurstThreadMode = obj.get("enableBurstThreadMode").getAsBoolean();
        if (obj.has("enableMeshShaders"))
          cfg.enableMeshShaders = obj.get("enableMeshShaders").getAsBoolean();
        if (obj.has("enableArgumentBuffers"))
          cfg.enableArgumentBuffers = obj.get("enableArgumentBuffers").getAsBoolean();

        if (obj.has("enableIndirectCommandBuffers"))
          cfg.enableIndirectCommandBuffers = obj.get("enableIndirectCommandBuffers").getAsBoolean();
        if (obj.has("enableMemorylessTargets"))
          cfg.enableMemorylessTargets = obj.get("enableMemorylessTargets").getAsBoolean();

        if (obj.has("savedLod1Distance"))
          lod1Distance = obj.get("savedLod1Distance").getAsInt();
        if (obj.has("savedLod2Distance"))
          lod2Distance = obj.get("savedLod2Distance").getAsInt();
        if (obj.has("savedLod3Distance"))
          lod3Distance = obj.get("savedLod3Distance").getAsInt();
        if (obj.has("savedLod4Distance"))
          lod4Distance = obj.get("savedLod4Distance").getAsInt();
        if (obj.has("savedLodEnabled"))
          lodEnabled = obj.get("savedLodEnabled").getAsBoolean();
        if (obj.has("savedResolutionScale"))
          resolutionScale = clamp(obj.get("savedResolutionScale").getAsFloat(), 0.20f, 1.5f);
        if (obj.has("savedAggressiveFrustumCulling"))
          aggressiveFrustumCulling = obj.get("savedAggressiveFrustumCulling").getAsBoolean();
        if (obj.has("savedOcclusionCulling"))
          occlusionCulling = obj.get("savedOcclusionCulling").getAsBoolean();
      }
    } catch (Exception e) {

    }

    applyStableQualityFallback(cfg);
    setDebugPinkBlockTint(cfg.debugPinkBlockTint);

    cfg.loadFeatureFlags();
    loadFromSystemProperties();
    return cfg;
  }

  private MetalRenderConfig() {
  }

  private static void applyStableQualityFallback(MetalRenderConfig cfg) {
    aggressiveFrustumCulling = false;
    occlusionCulling = false;
    resolutionScale = 1.0f;
  }

  public void save() {

    System.setProperty("metalrender.enabled", String.valueOf(enableMetalRendering));
    System.setProperty("metalrender.feature.icb",
        String.valueOf(enableIndirectCommandBuffers));
    System.setProperty("metalrender.feature.mesh", String.valueOf(enableMeshShaders));
    System.setProperty("metalrender.feature.argbuf", String.valueOf(enableArgumentBuffers));
    System.setProperty("metalrender.feature.oit", String.valueOf(enableProgrammableBlending));
    System.setProperty("metalrender.feature.memoryless", String.valueOf(enableMemorylessTargets));

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
      obj.addProperty("enableMemoryPressureFallback", enableMemoryPressureFallback);
      obj.addProperty("enableBurstThreadMode", enableBurstThreadMode);
      obj.addProperty("enableMeshShaders", enableMeshShaders);
      obj.addProperty("enableArgumentBuffers", enableArgumentBuffers);
      obj.addProperty("enableProgrammableBlending", enableProgrammableBlending);
      obj.addProperty("enableIndirectCommandBuffers", enableIndirectCommandBuffers);
      obj.addProperty("enableMemorylessTargets", enableMemorylessTargets);

      obj.addProperty("savedLod1Distance", lod1Distance);
      obj.addProperty("savedLod2Distance", lod2Distance);
      obj.addProperty("savedLod3Distance", lod3Distance);
      obj.addProperty("savedLod4Distance", lod4Distance);
      obj.addProperty("savedLodEnabled", lodEnabled);
      obj.addProperty("savedResolutionScale", resolutionScale);
      obj.addProperty("savedAggressiveFrustumCulling", aggressiveFrustumCulling);
      obj.addProperty("savedOcclusionCulling", occlusionCulling);
      java.nio.file.Path path = configFile();
      java.nio.file.Files.createDirectories(path.getParent());
      java.nio.file.Files.writeString(path,
          new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(obj));
    } catch (Exception e) {

    }
  }

  public static boolean mirrorUploads() {
    return mirrorUploads;
  }

  public static boolean swapOpaque() {
    return swapOpaque;
  }

  public static boolean swapCutout() {
    return swapCutout;
  }

  public static boolean swapTranslucent() {
    return swapTranslucent;
  }

  public static boolean aggressiveFrustumCulling() {
    return aggressiveFrustumCulling;
  }

  public static boolean occlusionCulling() {
    return occlusionCulling;
  }

  public static float resolutionScale() {
    return resolutionScale;
  }

  public static boolean isDeepDebugActive() {
    return deepDebugActive;
  }

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

  public static void setMirrorUploads(boolean v) {
    mirrorUploads = v;
  }

  public static void setSwapOpaque(boolean v) {
    swapOpaque = v;
  }

  public static void setSwapCutout(boolean v) {
    swapCutout = v;
  }

  public static void setSwapTranslucent(boolean v) {
    swapTranslucent = v;
  }

  public static void setAggressiveFrustumCulling(boolean v) {
    aggressiveFrustumCulling = v;
  }

  public static void setOcclusionCulling(boolean v) {
    occlusionCulling = v;
  }

  public static void setResolutionScale(float v) {
    resolutionScale = clamp(v, 0.20f, 1.5f);
  }

  public static void setDebugPinkBlockTint(boolean v) {
    debugPinkBlockTintEnabled = v;
  }

  public static boolean lodEnabled() {
    return lodEnabled;
  }

  public static void setLodEnabled(boolean v) {
    lodEnabled = v;
  }

  public static int lod1Distance() {
    return lod1Distance;
  }

  public static void setLod1Distance(int v) {
    lod1Distance = Math.max(1, v);
  }

  public static int lod2Distance() {
    return lod2Distance;
  }

  public static void setLod2Distance(int v) {
    lod2Distance = Math.max(lod1Distance + 1, v);
  }

  public static int lod3Distance() {
    return lod3Distance;
  }

  public static void setLod3Distance(int v) {
    lod3Distance = Math.max(lod2Distance + 1, v);
  }

  public static int lod4Distance() {
    return lod4Distance;
  }

  public static void setLod4Distance(int v) {
    lod4Distance = Math.max(lod3Distance + 1, v);
  }

  public static int getLodLevel(int chunkDistance) {
    if (!lodEnabled)
      return 0;
    if (chunkDistance >= lod4Distance)
      return 4;
    if (chunkDistance >= lod3Distance)
      return 3;
    if (chunkDistance >= lod2Distance)
      return 2;
    if (chunkDistance >= lod1Distance)
      return 1;
    return 0;
  }

  public static void loadFromSystemProperties() {
    mirrorUploads = getBool("metalrender.mirror", mirrorUploads);
    swapOpaque = getBool("metalrender.swap.opaque", swapOpaque);
    swapCutout = getBool("metalrender.swap.cutout", swapCutout);
    swapTranslucent = getBool("metalrender.swap.translucent", swapTranslucent);
    aggressiveFrustumCulling = getBool("metalrender.culling.frustum", aggressiveFrustumCulling);
    occlusionCulling = getBool("metalrender.culling.occlusion", occlusionCulling);
    resolutionScale = getFloat("metalrender.render.resolutionScale", resolutionScale);
  }

  public void loadFeatureFlags() {
    enableIndirectCommandBuffers = getBool("metalrender.feature.icb", enableIndirectCommandBuffers);
    enableMeshShaders = getBool("metalrender.feature.mesh", enableMeshShaders);
    enableArgumentBuffers = getBool("metalrender.feature.argbuf", enableArgumentBuffers);
    enableProgrammableBlending = getBool("metalrender.feature.oit", enableProgrammableBlending);
    enableMemorylessTargets = getBool("metalrender.feature.memoryless", enableMemorylessTargets);
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

  private static float clamp(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
  }
}
