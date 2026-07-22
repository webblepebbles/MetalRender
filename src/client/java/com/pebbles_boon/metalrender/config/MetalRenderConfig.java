package com.pebbles_boon.metalrender.config;

public final class MetalRenderConfig {
  public boolean enableMetalRendering = true;
  public boolean enableSimpleLighting = true;
  public boolean enableDebugOverlay = false;
  public boolean debugPinkBlockTint = false;
  public int leafCullingMode = 1;
  public int biomeTransitionDetail = 2;
  public int targetFrameRate = 60;
  public boolean prioritizeFpsOverTps = false;
  public int maxMemoryMB = 2048;
  public boolean enableTripleBuffering = true;
  public boolean enableMemoryPressureFallback = true;
  public boolean enableBurstThreadMode = false;
  public boolean enableMeshShaders = true;
  public boolean enableArgumentBuffers = false;
  public boolean enableClusterFrustumCulling = false;
  public boolean enableHiZCull = false;
  public boolean enableGpuTranslucencySort = false;

  public boolean enableProgrammableBlending = false;
  public boolean enableIndirectCommandBuffers = false;
  private static volatile boolean mirrorUploads = false;
  private static volatile boolean swapOpaque = false;
  private static volatile boolean swapCutout = false;
  private static volatile boolean swapTranslucent = false;
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

        if (obj.has("enableIndirectCommandBuffers"))
          cfg.enableIndirectCommandBuffers = obj.get("enableIndirectCommandBuffers").getAsBoolean();

        if (obj.has("savedResolutionScale"))
          resolutionScale = clamp(obj.get("savedResolutionScale").getAsFloat(), 0.20f, 1.5f);
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
      obj.addProperty("enableSimpleLighting", enableSimpleLighting);
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
      obj.addProperty("enableProgrammableBlending", enableProgrammableBlending);
      obj.addProperty("enableIndirectCommandBuffers", enableIndirectCommandBuffers);

      obj.addProperty("savedResolutionScale", resolutionScale);
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
