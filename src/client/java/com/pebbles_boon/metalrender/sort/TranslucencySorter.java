package com.pebbles_boon.metalrender.sort;

import com.pebbles_boon.metalrender.nativebridge.MeshShaderNative;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.util.MetalLogger;
import org.joml.Vector3f;

public class TranslucencySorter {
  public static final int STABLE_REQUIRED_FRAMES = 16;
  private static final int DEFAULT_CAPACITY = 4096;

  private final Vector3f lastCamPos = new Vector3f();
  private float lastYaw = Float.NaN;
  private int stableFrames;
  private volatile long ssboHandle;
  private volatile boolean active;
  private volatile boolean sortPendingResult;
  private volatile long lastDispatchNs;
  private volatile int lastSortedCount;
  private static final float MOVE_THRESHOLD = 0.5f;
  private static final float YAW_THRESHOLD = 2.0f;

  public void ensureInitialized(int capacity) {
    if (!active || !NativeBridge.isLibLoaded()) {
      return;
    }
    if (ssboHandle != 0) {
      return;
    }
    int cap = Math.max(64, capacity > 0 ? capacity : DEFAULT_CAPACITY);
    try {
      ssboHandle = MeshShaderNative.createTranslucencySortSSBO(cap);
      if (ssboHandle == 0) {
        MetalLogger.warn("Translucency sort SSBO init returned 0; "
            + "falling back to CPU-driven order");
      }
    } catch (UnsatisfiedLinkError e) {
      MetalLogger.warn(
          "createTranslucencySortSSBO missing native impl: " + e.getMessage());
      ssboHandle = 0L;
      active = false;
    }
  }

  public boolean tickStable(Vector3f camPos, float yaw) {
    if (camPos == null) {
      return false;
    }
    float dx = camPos.x - lastCamPos.x;
    float dy = camPos.y - lastCamPos.y;
    float dz = camPos.z - lastCamPos.z;
    float moveDist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    float yawDelta = Float.isNaN(lastYaw) ? Float.MAX_VALUE : Math.abs(yaw - lastYaw);
    if (moveDist < MOVE_THRESHOLD && yawDelta < YAW_THRESHOLD) {
      stableFrames++;
    } else {
      stableFrames = 0;
      lastCamPos.set(camPos);
      lastYaw = yaw;
    }
    if (stableFrames >= STABLE_REQUIRED_FRAMES && active &&
        NativeBridge.isLibLoaded() && ssboHandle != 0) {
      try {
        int sorted = MeshShaderNative.dispatchTranslucencySort(ssboHandle,
            DEFAULT_CAPACITY);
        lastSortedCount = Math.max(0, sorted);
        sortPendingResult = true;
        lastDispatchNs = System.nanoTime();
        return true;
      } catch (UnsatisfiedLinkError e) {
        MetalLogger.warn(
            "dispatchTranslucencySort missing native impl: " + e.getMessage());
        active = false;
      }
    }
    return false;
  }

  public int readRecentOrder(int[] out) {
    if (out == null || ssboHandle == 0 || !sortPendingResult) {
      return -1;
    }
    try {
      int written = MeshShaderNative.readTranslucencyOrder(ssboHandle, out);
      sortPendingResult = false;
      return Math.max(0, written);
    } catch (UnsatisfiedLinkError e) {
      MetalLogger.warn(
          "readTranslucencyOrder missing native impl: " + e.getMessage());
      return -1;
    }
  }

  public boolean isStable() {
    return stableFrames >= STABLE_REQUIRED_FRAMES;
  }

  public long getSsboHandle() {
    return ssboHandle;
  }

  public long getLastDispatchNs() {
    return lastDispatchNs;
  }

  public int getLastSortedCount() {
    return lastSortedCount;
  }

  public void setActive(boolean v) {
    active = v;
    if (!v) {
      lastDispatchNs = 0L;
      sortPendingResult = false;
      lastSortedCount = 0;
    }
  }

  public boolean isActive() {
    return active;
  }

  public void shutdown() {
    if (ssboHandle != 0) {
      safeDestroy(ssboHandle);
      ssboHandle = 0L;
    }
    active = false;
    stableFrames = 0;
    sortPendingResult = false;
    lastDispatchNs = 0L;
    lastSortedCount = 0;
  }

  private void safeDestroy(long handle) {
    try {
      MeshShaderNative.destroyTranslucencySortSSBO(handle);
    } catch (UnsatisfiedLinkError ignored) {
    }
  }
}
