package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.StartupBlocker;
import com.pebbles_boon.metalrender.gui.StartupBlockerOverlay;
import com.pebbles_boon.metalrender.performance.PerformanceController;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.util.MetalLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftClientMixin {
  @Unique
  private boolean metalrender$levelWasLoaded = false;
  @Unique
  private int metalrender$debugCounter = 0;
  @Unique
  private boolean metalrender$forcingStartupBlocker;

  @Inject(method = "<init>", at = @At("TAIL"))
  private void metalrender$showStartupBlocker(GameConfig gameConfig,
      CallbackInfo ci) {
    if (StartupBlocker.shouldBlockStartup()) {
      metalrender$forceStartupBlocker();
    }
  }

  @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
  private void metalrender$keepStartupBlocker(Screen screen, CallbackInfo ci) {
    if (!StartupBlocker.shouldBlockStartup() ||
        metalrender$forcingStartupBlocker) {
      return;
    }
    metalrender$forceStartupBlocker();
    ci.cancel();
  }

  @Inject(method = "setScreenAndRender", at = @At("HEAD"), cancellable = true)
  private void metalrender$keepStartupBlockerVisible(Screen screen,
      CallbackInfo ci) {
    if (!StartupBlocker.shouldBlockStartup() ||
        metalrender$forcingStartupBlocker) {
      return;
    }
    metalrender$forceStartupBlocker();
    ci.cancel();
  }

  @Inject(method = "setOverlay", at = @At("HEAD"), cancellable = true)
  private void metalrender$blockStartupOverlays(Overlay overlay,
      CallbackInfo ci) {
    if (StartupBlocker.shouldBlockStartup() &&
        !metalrender$forcingStartupBlocker &&
        !(overlay instanceof StartupBlockerOverlay)) {
      ci.cancel();
    }
  }

  @Unique
  private void metalrender$forceStartupBlocker() {
    metalrender$forcingStartupBlocker = true;
    try {
      ((Minecraft) (Object) this).setOverlay(new StartupBlockerOverlay());
    } finally {
      metalrender$forcingStartupBlocker = false;
    }
  }

  @Inject(method = "runTick", at = @At("HEAD"))
  private void metalrender$startFrame(boolean tick, CallbackInfo ci) {
    if (StartupBlocker.shouldBlockStartup()) {
      return;
    }
    if (MetalRenderClient.isEnabled()) {
      PerformanceController.startFrame();
      ClientLevel level = ((Minecraft) (Object) this).level;
      metalrender$debugCounter++;
      if (metalrender$debugCounter % 600 == 1) {
        MetalLogger.deepInfo(
            "[mcmix] lvl=" +
                (level != null ? "ok" : "null") +
                " was=" + metalrender$levelWasLoaded + " wr=" +
                (MetalRenderClient.getWorldRenderer() != null ? "ok"
                    : "null"));
      }
      if (level != null && !metalrender$levelWasLoaded) {
        MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
        MetalLogger.info("[mcmix] level found wr=" + (wr != null));
        if (wr != null) {
          try {
            wr.onWorldLoad();
            MetalLogger.info("[mcmix] onworldload ok");
          } catch (Exception e) {
            MetalLogger.error("[mcmix] onworldload fail", e);
          }
        }
        metalrender$levelWasLoaded = true;
      } else if (level == null && metalrender$levelWasLoaded) {
        MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
        if (wr != null) {
          wr.onWorldUnload();
        }
        metalrender$levelWasLoaded = false;
      }
      if (level != null) {
        MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
        if (wr != null && wr.metalActive()) {
          wr.prepareMeshes();
        }
      }
    }
  }

  @Inject(method = "runTick", at = @At("TAIL"))
  private void metalrender$endFrame(boolean tick, CallbackInfo ci) {
    if (StartupBlocker.shouldBlockStartup()) {
      return;
    }
    if (MetalRenderClient.isEnabled()) {
      PerformanceController.endFrame();
    }
  }
}
