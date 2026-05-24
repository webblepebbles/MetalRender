package com.pebbles_boon.metalrender;

import com.pebbles_boon.metalrender.backend.MetalRenderer;
import com.pebbles_boon.metalrender.command.MetalRenderCommands;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.gui.MetalDebugEntry;
import com.pebbles_boon.metalrender.gui.MetalRenderSettingsScreen;
import com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.render.unified.MetalRenderCoordinator;
import com.pebbles_boon.metalrender.sodium.backend.MeshShaderBackend;
import com.pebbles_boon.metalrender.sodium.backend.SodiumMetalInterface;
import com.pebbles_boon.metalrender.util.MetalLogger;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

public class MetalRenderClient implements ClientModInitializer {
  private static final int FPS_PRIORITY_SIMULATION_DISTANCE = 5;
  private static MetalRenderClient instance;
  private static MetalRenderer renderer;
  private static MetalRenderConfig config;
  private static MetalRenderCoordinator coordinator;
  private static MeshShaderBackend meshShaderBackend;
  private static SodiumMetalInterface sodiumInterface;
  private static MetalWorldRenderer worldRenderer;
  private static boolean metalUp;
  private static boolean cfgWasOn;
  private static boolean cfgSyncPending;
  private static boolean runtimeApplyPending;
  private static boolean levelRendererRefreshPending;
  private static boolean worldRendererRefreshPending;
  private static boolean debugEntryStatusSet;

  @Override
  public void onInitializeClient() {
    if (StartupBlocker.shouldBlockStartup()) {
      return;
    }
    instance = this;
    MetalLogger.info("MetalRender can render");
    config = MetalRenderConfig.load();
    cfgWasOn = config != null && config.enableMetalRendering;
    MetalDebugEntry.register();
    if (MetalRenderConfig.isDeepDebugActive()) {
      MetalLogger.info("debug logging is logging");
    }
    MetalRenderCommands.register();
    if (!config.enableMetalRendering) {
      MetalLogger.info("metalrender was sniped");
    }

    ClientTickEvents.START_CLIENT_TICK.register(client -> {
      var mc = Minecraft.getInstance();
      if (!debugEntryStatusSet && mc != null) {
        MetalDebugEntry.show(mc);
        debugEntryStatusSet = true;
      }
      if (cfgSyncPending) {
        cfgSyncPending = false;
        syncCfg(mc);
      }
      applyDeferredRuntimeChanges(mc);
      applyFpsPriorityMode(mc);
      syncCfg(mc);
      if (config != null && config.enableMetalRendering && renderer == null && mc != null) {
        initMetal(mc);
      }
    });
  }

  public static void requestDeferredApply(boolean requestCfgSync,
      boolean refreshLevelRenderer,
      boolean refreshWorldRenderer) {
    runtimeApplyPending = true;
    cfgSyncPending |= requestCfgSync;
    levelRendererRefreshPending |= refreshLevelRenderer;
    worldRendererRefreshPending |= refreshWorldRenderer;
  }

  private static void applyDeferredRuntimeChanges(Minecraft mc) {
    if (!runtimeApplyPending || config == null) {
      return;
    }

    runtimeApplyPending = false;
    if (NativeBridge.isLibLoaded()) {
      boolean useArgBufs = config.enableArgumentBuffers || config.enableIndirectCommandBuffers;
      NativeBridge.nSetFeatureFlags(
          config.enableIndirectCommandBuffers,
          config.enableMeshShaders,
          useArgBufs,
          config.enableProgrammableBlending,
          config.enableMemorylessTargets);
    }

    MetalWorldRenderer wr = worldRenderer;
    if (wr != null) {
      wr.applyFeatureConfig(config);
    }

    if (levelRendererRefreshPending && mc != null && mc.levelRenderer != null) {
      mc.levelRenderer.allChanged();
    }
    levelRendererRefreshPending = false;

    if (worldRendererRefreshPending) {
      if (NativeBridge.isLibLoaded()) {
        NativeBridge.nFlushFrames();
      }
      if (wr != null) {
        wr.onConfigScreenClosed();
      }
    }
    worldRendererRefreshPending = false;
  }

  public static void syncCfg(Minecraft mc) {
    boolean cfgOn = config != null && config.enableMetalRendering;
    if (cfgOn == cfgWasOn || mc == null) {
      return;
    }
    cfgWasOn = cfgOn;

    if (!cfgOn) {
      if (worldRenderer != null) {
        drainRenderer();
        worldRenderer.onWorldUnload();
        worldRenderer = null;
      }
      return;
    }

    if (renderer == null || !metalUp) {
      initMetal(mc);
    } else if (worldRenderer == null) {
      worldRenderer = new MetalWorldRenderer();
    }

    if (worldRenderer != null && mc.level != null) {
      worldRenderer.onWorldLoad();
      worldRenderer.onConfigScreenClosed();
    }
  }

  private static void applyFpsPriorityMode(Minecraft mc) {
    if (mc == null || mc.options == null || config == null || !config.prioritizeFpsOverTps) {
      return;
    }
    try {
      if (mc.options.simulationDistance().get() > FPS_PRIORITY_SIMULATION_DISTANCE) {
        mc.options.simulationDistance().set(FPS_PRIORITY_SIMULATION_DISTANCE);
        mc.options.save();
      }
    } catch (Exception ignored) {
    }
  }

  private static void drainRenderer() {
    if (renderer == null || !renderer.isAvailable() || !NativeBridge.isLibLoaded()) {
      return;
    }
    try {
      NativeBridge.nFlushFrames();
      NativeBridge.nWaitForRender(renderer.getHandle());
    } catch (Throwable t) {
      MetalLogger.warn("failed to drain Metal renderer before lifecycle change: %s", t.getMessage());
    }
  }

  public static void openSettingsScreen(Minecraft mc) {
    if (mc == null) {
      return;
    }
    try {
      mc.execute(() -> mc.setScreen(new MetalRenderSettingsScreen(mc.screen)));
      MetalLogger.info("Opened MetalRender settings screen.");
    } catch (Exception e) {
      MetalLogger.warn("failed to open MetalRender settings screen: %s", e.getMessage());
    }
  }

  private static void initMetal(Minecraft mc) {
    try {
      NativeBridge.loadLibrary();
    } catch (UnsatisfiedLinkError e) {
      MetalLogger.error("got lost finding non-existent native library", e);
      return;
    }

    try {
      if (!MetalHardwareChecker.isMetalSupported()) {
        MetalLogger.warn("computer lazy cant even get metal");
        return;
      }

      renderer = new MetalRenderer();
      var win = mc.getWindow();
      int w = win != null ? win.getWidth() : 0;
      int h = win != null ? win.getHeight() : 0;
      renderer.init(w, h);
      metalUp = renderer.isAvailable();
      if (!metalUp) {
        return;
      }

      coordinator = new MetalRenderCoordinator();
      coordinator.initialize();
      worldRenderer = new MetalWorldRenderer();
      meshShaderBackend = new MeshShaderBackend();
      meshShaderBackend.initialize();
      logStartDiag(mc);
      MetalLogger.info("Metal ready: " + MetalHardwareChecker.getDeviceName());
    } catch (Exception e) {
      MetalLogger.error("Failure", e);
      metalUp = false;
    }
  }

  public static MetalRenderClient getInstance() {
    return instance;
  }

  public static MetalRenderer getRenderer() {
    return renderer;
  }

  public static MetalRenderConfig getConfig() {
    return config;
  }

  public static MetalRenderCoordinator getCoordinator() {
    return coordinator;
  }

  public static MeshShaderBackend getMeshShaderBackend() {
    return meshShaderBackend;
  }

  public static boolean isMetalAvailable() {
    return metalUp;
  }

  public static boolean isEnabled() {
    return config != null && config.enableMetalRendering && metalUp
        && renderer != null && renderer.isAvailable();
  }

  public static MetalWorldRenderer getWorldRenderer() {
    return worldRenderer;
  }

  public static SodiumMetalInterface getSodiumInterface() {
    if (sodiumInterface == null) {
      sodiumInterface = new SodiumMetalInterface();
    }
    return sodiumInterface;
  }

  public static boolean isSodiumLoaded() {
    return FabricLoader.getInstance().isModLoaded("sodium");
  }

  private static void logStartDiag(Minecraft mc) {
    try {
      var o = mc.options;
      var cfg = config;
      int fpsCap = o != null && o.framerateLimit() != null ? o.framerateLimit().get() : -1;
      boolean vsync = o != null && o.enableVsync() != null && Boolean.TRUE.equals(o.enableVsync().get());
      int rd = o != null && o.renderDistance() != null ? o.renderDistance().get() : -1;
      int sd = o != null && o.simulationDistance() != null ? o.simulationDistance().get() : -1;

      boolean meshOk = NativeBridge.nSupportsMeshShaders();
      boolean indOk = NativeBridge.nSupportsIndirect();
      boolean meshOn = NativeBridge.nAreMeshShadersActive();
      boolean gpuOn = NativeBridge.nIsGPUDrivenActive();

      MetalLogger.info(
          "STARTUP_DIAG: supportsMesh=%s supportsIndirect=%s meshActive=%s gpuDriven=%s cfg(mesh=%s icb=%s argBuf=%s) fpsLimit=%d vsync=%s rd=%d sd=%d",
          meshOk, indOk, meshOn, gpuOn,
          cfg != null && cfg.enableMeshShaders,
          cfg != null && cfg.enableIndirectCommandBuffers,
          cfg != null && cfg.enableArgumentBuffers,
          fpsCap, vsync, rd, sd);

      if (cfg != null && cfg.enableMeshShaders && !meshOn) {
        MetalLogger.warn(
            "STARTUP_DIAG: Mesh shaders requested but inactive. Check capability gates/fallback path selection.");
      }
      if (cfg != null && cfg.enableIndirectCommandBuffers && !indOk) {
        MetalLogger.warn(
            "STARTUP_DIAG: Indirect command buffers requested but not supported on this runtime/device.");
      }
      if (vsync || (fpsCap > 0 && fpsCap <= 60)) {
        MetalLogger.warn(
            "STARTUP_DIAG: FPS may be capped by settings (vsync=%s, framerateLimit=%d).",
            vsync, fpsCap);
      }
    } catch (Throwable t) {
      MetalLogger.warn("STARTUP_DIAG failed: %s", t.getMessage());
    }
  }
}
