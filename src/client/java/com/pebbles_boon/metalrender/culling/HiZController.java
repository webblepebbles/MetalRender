package com.pebbles_boon.metalrender.culling;

import com.pebbles_boon.metalrender.nativebridge.MeshShaderNative;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.ByteBuffer;

public class HiZController {
  private volatile long pyramidHandle;
  private volatile int width;
  private volatile int height;
  private volatile boolean active;
  private volatile long lastUpdateNs;

  public void ensureInitialized(int frameWidth, int frameHeight) {
    if (!active || !NativeBridge.isLibLoaded() || frameWidth <= 0 ||
        frameHeight <= 0) {
      return;
    }
    int w = frameWidth;
    int h = frameHeight;
    long existing = pyramidHandle;
    if (existing != 0 && width == w && height == h) {
      return;
    }
    if (existing != 0 && (width != w || height != h)) {
      safeDestroy(existing);
      pyramidHandle = 0L;
    }
    try {
      pyramidHandle = MeshShaderNative.createHiZPyramid(w, h);
      if (pyramidHandle != 0) {
        width = w;
        height = h;
        MetalLogger.info("HiZPyramid created: %dx%d handle=0x%X", w, h,
            pyramidHandle);
      } else {
        MetalLogger.warn("HiZPyramid init returned 0; cull will fall back to "
            + "CPU frustum");
      }
    } catch (UnsatisfiedLinkError e) {
      MetalLogger.warn("HiZPyramid init missing native impl: " + e.getMessage());
      pyramidHandle = 0L;
      active = false;
    }
  }

  public void pushDepthFrame(ByteBuffer depth, int frameWidth, int frameHeight) {
    if (!active || !NativeBridge.isLibLoaded() || pyramidHandle == 0L ||
        depth == null) {
      return;
    }
    ensureInitialized(frameWidth, frameHeight);
    if (pyramidHandle == 0L) {
      return;
    }
    int expected = frameWidth * frameHeight * 2;
    try {
      if (depth.capacity() < expected) {
        return;
      }
      MeshShaderNative.updateHiZPyramid(pyramidHandle, depth, frameWidth,
          frameHeight);
      lastUpdateNs = System.nanoTime();
    } catch (UnsatisfiedLinkError e) {
      MetalLogger.warn("updateHiZPyramid missing native impl: " + e.getMessage());
      safeDestroy(pyramidHandle);
      pyramidHandle = 0L;
    }
  }

  public long getHandle() {
    return pyramidHandle;
  }

  public int getWidth() {
    return width;
  }

  public int getHeight() {
    return height;
  }

  public long getLastUpdateNs() {
    return lastUpdateNs;
  }

  public void setActive(boolean v) {
    active = v;
    if (!v) {
      lastUpdateNs = 0L;
    }
  }

  public boolean isActive() {
    return active;
  }

  public void shutdown() {
    long existing = pyramidHandle;
    if (existing != 0L) {
      safeDestroy(existing);
      pyramidHandle = 0L;
    }
    active = false;
    width = 0;
    height = 0;
    lastUpdateNs = 0L;
  }

  private void safeDestroy(long handle) {
    try {
      MeshShaderNative.destroyHiZPyramid(handle);
    } catch (UnsatisfiedLinkError ignored) {
    }
  }
}
