package com.pebbles_boon.metalrender.culling;

import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.util.MetalLogger;

public class HiZController {
  private volatile int width;
  private volatile int height;
  private volatile boolean active;

  public void ensureInitialized(int frameWidth, int frameHeight) {
    if (!active || !NativeBridge.isLibLoaded() || frameWidth <= 0 ||
        frameHeight <= 0) {
      return;
    }
    width = frameWidth;
    height = frameHeight;
    MetalLogger.info("hiz enabled: %dx%d", frameWidth, frameHeight);
  }

  public boolean isReady() {
    if (!active || !NativeBridge.isLibLoaded() || width <= 0 || height <= 0) {
      return false;
    }
    try {
      return NativeBridge.nIsHiZReady();
    } catch (UnsatisfiedLinkError e) {
      active = false;
      return false;
    }
  }

  public void setActive(boolean v) {
    active = v;
    if (NativeBridge.isLibLoaded()) {
      NativeBridge.nSetHiZCullEnabled(v);
    }
  }

  public void shutdown() {
    active = false;
    width = 0;
    height = 0;
    if (NativeBridge.isLibLoaded()) {
      NativeBridge.nSetHiZCullEnabled(false);
    }
  }
}
