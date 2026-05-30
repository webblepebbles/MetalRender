package com.pebbles_boon.metalrender.nativebridge;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public final class MetalHardwareChecker {
  private static final Logger LOGGER = LogUtils.getLogger();
  private static volatile Boolean compatible = null;
  private static volatile boolean checkScheduled = false;

  public static boolean isCompatible() {
    scheduleCheck();
    return compatible == null || compatible;
  }

  public static boolean isMetalSupported() {
    try {
      return NativeBridge.isLibLoaded() && NativeBridge.nIsAvailable();
    } catch (Throwable t) {
      LOGGER.warn("[MetalRender] Metal support check failed", t);
      return false;
    }
  }

  public static String getDeviceName() {
    try {
      return NativeBridge.isLibLoaded() ? NativeBridge.nGetDeviceName()
                                        : "Unknown";
    } catch (Throwable t) {
      return "Unknown";
    }
  }

  public static boolean supportsMeshShaders() {
    try {
      return NativeBridge.isLibLoaded() && NativeBridge.nSupportsMeshShaders();
    } catch (Throwable t) {
      return false;
    }
  }

  public static boolean isAppleSilicon() {
    String arch = System.getProperty("os.arch", "").toLowerCase();
    return arch.contains("aarch64") || arch.contains("arm64");
  }

  public static boolean appleSilicon() { return isAppleSilicon(); }

  public static void showIncompatibleScreen() {}

  private static void scheduleCheck() {
    if (compatible != null || checkScheduled)
      return;
    checkScheduled = true;
    try {
      compatible = true;
    } catch (UnsatisfiedLinkError | IllegalArgumentException e) {
      compatible = true;
      LOGGER.warn("[MetalRender] Could not schedule GL capability check, "
                      + "allowing fallback",
                  e);
    }
  }
}