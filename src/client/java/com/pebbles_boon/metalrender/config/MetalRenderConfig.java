package com.pebbles_boon.metalrender.config;

public final class MetalRenderConfig {
  public boolean enableMetalRendering = true;
  public boolean enableDebugOverlay = false;
  public boolean debugPinkBlockTint = false;
  public int leafCullingMode = 1;
  public int biomeTransitionDetail = 2;
  public int targetFrameRate = 300;
  public boolean prioritizeFpsOverTps = false;
  public int maxMemoryMB = 2048;
  public boolean enableTripleBuffering = true;
  public boolean enableMemoryPressureFallback = true;
  public boolean enableBurstThreadMode = true;
  public boolean enableMeshShaders = true;
  public boolean enableArgumentBuffers = true;
  public boolean enableClusterFrustumCulling = false;
  public boolean enableGpuTranslucencySort = false;

  public boolean hiddenFluidCulling = true;
  public boolean improvedFluidShaping = false;
  public boolean closestPointEntitySort = false;
  public boolean smoothLighting = true;

  public boolean enableDistanceLod = true;
  public int lodNearChunks = 6;
  public int lodMidChunks = 16;
  public boolean lodThermalAdaptive = true;
  public boolean lodThroughputBudget = true;
  public boolean lodVisibilityGate = true;
  public boolean lodRecencyEviction = true;
  public boolean lodStickyTiers = true;
  public boolean lodViewImpact = true;
  public boolean lodSkeletonFirst = true;

  public boolean enableProgrammableBlending = false;
  public boolean enableIndirectCommandBuffers = true;
  public boolean enableCameraFacingCulling = true;
  public boolean enableOcclusionCulling = true;
  private static volatile boolean mirrorUploads = true;
  private static volatile boolean swapOpaque = false;
  private static volatile boolean swapCutout = false;
  private static volatile boolean swapTranslucent = false;
  private static volatile float resolutionScale = 1.0f;
  private static volatile boolean adaptiveResolutionEnabled = true;
  private static volatile boolean metalFXTemporalEnabled = true;
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
        if (obj.has("enableDebugOverlay"))
          cfg.enableDebugOverlay = obj.get("enableDebugOverlay").getAsBoolean();
        if (obj.has("debugPinkBlockTint"))
          cfg.debugPinkBlockTint = obj.get("debugPinkBlockTint").getAsBoolean();
        if (obj.has("leafCullingMode"))
          cfg.leafCullingMode = obj.get("leafCullingMode").getAsInt();
        if (obj.has("biomeTransitionDetail"))
          cfg.biomeTransitionDetail = obj.get("biomeTransitionDetail").getAsInt();
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
        if (obj.has("enableClusterFrustumCulling"))
          cfg.enableClusterFrustumCulling = obj.get("enableClusterFrustumCulling").getAsBoolean();
        if (obj.has("enableGpuTranslucencySort"))
          cfg.enableGpuTranslucencySort = obj.get("enableGpuTranslucencySort").getAsBoolean();

        if (obj.has("enableIndirectCommandBuffers"))
          cfg.enableIndirectCommandBuffers = obj.get("enableIndirectCommandBuffers").getAsBoolean();
        if (obj.has("enableCameraFacingCulling"))
          cfg.enableCameraFacingCulling = obj.get("enableCameraFacingCulling").getAsBoolean();
        if (obj.has("enableOcclusionCulling"))
          cfg.enableOcclusionCulling = obj.get("enableOcclusionCulling").getAsBoolean();

        if (obj.has("hiddenFluidCulling"))
          cfg.hiddenFluidCulling = obj.get("hiddenFluidCulling").getAsBoolean();
        if (obj.has("improvedFluidShaping"))
          cfg.improvedFluidShaping = obj.get("improvedFluidShaping").getAsBoolean();
        if (obj.has("closestPointEntitySort"))
          cfg.closestPointEntitySort = obj.get("closestPointEntitySort").getAsBoolean();
        if (obj.has("smoothLighting"))
          cfg.smoothLighting = obj.get("smoothLighting").getAsBoolean();

        if (obj.has("enableDistanceLod"))
          cfg.enableDistanceLod = obj.get("enableDistanceLod").getAsBoolean();
        if (obj.has("lodNearChunks"))
          cfg.lodNearChunks = clamp(obj.get("lodNearChunks").getAsInt(), 2, 32);
        if (obj.has("lodMidChunks"))
          cfg.lodMidChunks = clamp(obj.get("lodMidChunks").getAsInt(), 4, 48);
        if (obj.has("lodThermalAdaptive"))
          cfg.lodThermalAdaptive = obj.get("lodThermalAdaptive").getAsBoolean();
        if (obj.has("lodThroughputBudget"))
          cfg.lodThroughputBudget = obj.get("lodThroughputBudget").getAsBoolean();
        if (obj.has("lodVisibilityGate"))
          cfg.lodVisibilityGate = obj.get("lodVisibilityGate").getAsBoolean();
        if (obj.has("lodRecencyEviction"))
          cfg.lodRecencyEviction = obj.get("lodRecencyEviction").getAsBoolean();
        if (obj.has("lodStickyTiers"))
          cfg.lodStickyTiers = obj.get("lodStickyTiers").getAsBoolean();
        if (obj.has("lodViewImpact"))
          cfg.lodViewImpact = obj.get("lodViewImpact").getAsBoolean();
        if (obj.has("lodSkeletonFirst"))
          cfg.lodSkeletonFirst = obj.get("lodSkeletonFirst").getAsBoolean();

        if (obj.has("savedResolutionScale"))
          resolutionScale = clamp(obj.get("savedResolutionScale").getAsFloat(), 0.20f, 1.5f);

        if (obj.has("enableAdaptiveResolution"))
          adaptiveResolutionEnabled =
              obj.get("enableAdaptiveResolution").getAsBoolean();

        if (obj.has("enableMetalFXTemporal"))
          metalFXTemporalEnabled = obj.get("enableMetalFXTemporal").getAsBoolean();
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
    resolutionScale = 1.0f;
  }

  public void save() {

    System.setProperty("metalrender.enabled", String.valueOf(enableMetalRendering));
    System.setProperty("metalrender.feature.icb",
        String.valueOf(enableIndirectCommandBuffers));
    System.setProperty("metalrender.feature.mesh", String.valueOf(enableMeshShaders));
    System.setProperty("metalrender.feature.argbuf", String.valueOf(enableArgumentBuffers));
    System.setProperty("metalrender.feature.oit", String.valueOf(enableProgrammableBlending));

    try {
      com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
      obj.addProperty("enableMetalRendering", enableMetalRendering);
      obj.addProperty("enableDebugOverlay", enableDebugOverlay);
      obj.addProperty("debugPinkBlockTint", debugPinkBlockTint);
      obj.addProperty("leafCullingMode", leafCullingMode);
      obj.addProperty("biomeTransitionDetail", biomeTransitionDetail);
      obj.addProperty("targetFrameRate", targetFrameRate);
      obj.addProperty("prioritizeFpsOverTps", prioritizeFpsOverTps);
      obj.addProperty("maxMemoryMB", maxMemoryMB);
      obj.addProperty("enableTripleBuffering", enableTripleBuffering);
      obj.addProperty("enableMemoryPressureFallback", enableMemoryPressureFallback);
      obj.addProperty("enableBurstThreadMode", enableBurstThreadMode);
      obj.addProperty("enableMeshShaders", enableMeshShaders);
      obj.addProperty("enableArgumentBuffers", enableArgumentBuffers);
      obj.addProperty("enableClusterFrustumCulling", enableClusterFrustumCulling);
      obj.addProperty("enableGpuTranslucencySort", enableGpuTranslucencySort);
      obj.addProperty("enableProgrammableBlending", enableProgrammableBlending);
      obj.addProperty("enableIndirectCommandBuffers", enableIndirectCommandBuffers);
      obj.addProperty("enableCameraFacingCulling", enableCameraFacingCulling);
      obj.addProperty("enableOcclusionCulling", enableOcclusionCulling);
      obj.addProperty("hiddenFluidCulling", hiddenFluidCulling);
      obj.addProperty("improvedFluidShaping", improvedFluidShaping);
      obj.addProperty("closestPointEntitySort", closestPointEntitySort);
      obj.addProperty("smoothLighting", smoothLighting);
      obj.addProperty("enableDistanceLod", enableDistanceLod);
      obj.addProperty("lodNearChunks", lodNearChunks);
      obj.addProperty("lodMidChunks", lodMidChunks);
      obj.addProperty("lodThermalAdaptive", lodThermalAdaptive);
      obj.addProperty("lodThroughputBudget", lodThroughputBudget);
      obj.addProperty("lodVisibilityGate", lodVisibilityGate);
      obj.addProperty("lodRecencyEviction", lodRecencyEviction);
      obj.addProperty("lodStickyTiers", lodStickyTiers);
      obj.addProperty("lodViewImpact", lodViewImpact);
      obj.addProperty("lodSkeletonFirst", lodSkeletonFirst);

      obj.addProperty("savedResolutionScale", resolutionScale);
      obj.addProperty("enableAdaptiveResolution", adaptiveResolutionEnabled);
      obj.addProperty("enableMetalFXTemporal", metalFXTemporalEnabled);
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

  public static void setResolutionScale(float v) {
    resolutionScale = clamp(v, 0.20f, 1.5f);
  }

  public static boolean isAdaptiveResolutionEnabled() {
    return adaptiveResolutionEnabled;
  }

  public static void setAdaptiveResolutionEnabled(boolean v) {
    adaptiveResolutionEnabled = v;
  }

  public static boolean isMetalFXTemporalEnabled() {
    return metalFXTemporalEnabled;
  }

  public static void setMetalFXTemporalEnabled(boolean v) {
    metalFXTemporalEnabled = v;
  }

  public static void setDebugPinkBlockTint(boolean v) {
    debugPinkBlockTintEnabled = v;
  }

  public static void loadFromSystemProperties() {
    mirrorUploads = getBool("metalrender.mirror", mirrorUploads);
    swapOpaque = getBool("metalrender.swap.opaque", swapOpaque);
    swapCutout = getBool("metalrender.swap.cutout", swapCutout);
    swapTranslucent = getBool("metalrender.swap.translucent", swapTranslucent);
    resolutionScale = getFloat("metalrender.render.resolutionScale", resolutionScale);
  }

  public void loadFeatureFlags() {
    enableIndirectCommandBuffers = getBool("metalrender.feature.icb", enableIndirectCommandBuffers);
    enableMeshShaders = getBool("metalrender.feature.mesh", enableMeshShaders);
    enableArgumentBuffers = getBool("metalrender.feature.argbuf", enableArgumentBuffers);
    enableProgrammableBlending = getBool("metalrender.feature.oit", enableProgrammableBlending);

    if (enableMeshShaders && com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker.supportsMeshShaders()) {
      enableIndirectCommandBuffers = true;
    }
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

  private static int clamp(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
  }

  private static float clamp(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
  }
}
