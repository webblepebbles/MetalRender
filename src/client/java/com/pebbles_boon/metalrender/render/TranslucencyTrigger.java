package com.pebbles_boon.metalrender.render;

import org.joml.Vector3f;

public class TranslucencyTrigger {
  private Vector3f lastCamPos = new Vector3f();
  private float lastYaw = 1;
  private int stableFrames = 0;
  private static final float MOVE_THRESHOLD = 0.5f;
  private static final float YAW_THRESHOLD = 2.0f;
  private static final int STABLE_REQUIRED = 8;

  public boolean shouldReSort(Vector3f camPos, float yaw) {
    float dx = camPos.x - lastCamPos.x;
    float dy = camPos.y - lastCamPos.y;
    float dz = camPos.z - lastCamPos.z;
    float moveDist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    float yawDelta = Math.abs(yaw - lastYaw);
    if (moveDist < MOVE_THRESHOLD && yawDelta < YAW_THRESHOLD) {
      stableFrames++;
    } else {
      stableFrames = 0;
      lastCamPos.set(camPos);
      lastYaw = yaw;
    }
    boolean stable = stableFrames >= STABLE_REQUIRED;
    notifyGpuSort(stable);
    return stable;
  }

  public static interface GpuSortHook {
    void onTrigger(boolean stableNow);
  }

  private static volatile GpuSortHook gpuSortHook;

  public static void registerGpuSortHook(GpuSortHook hook) {
    gpuSortHook = hook;
  }

  public static void clearGpuSortHook(GpuSortHook hook) {
    if (gpuSortHook == hook) {
      gpuSortHook = null;
    }
  }

  void notifyGpuSort(boolean stableNow) {
    GpuSortHook hook = gpuSortHook;
    if (hook != null) {
      hook.onTrigger(stableNow);
    }
  }

  public boolean isStable() {
    return stableFrames >= STABLE_REQUIRED;
  }
}
