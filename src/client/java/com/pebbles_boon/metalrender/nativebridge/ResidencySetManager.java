package com.pebbles_boon.metalrender.nativebridge;

public class ResidencySetManager {
  private static boolean residencySetsAvailable = false;
  private static long entityAtlasResidencySet = 0;

  public static void initialize(long deviceHandle) {
    if (deviceHandle == 0 || deviceHandle == 1) return;
    residencySetsAvailable = NativeBridge.nAreResidencySetsSupported();
    if (residencySetsAvailable) {
      entityAtlasResidencySet = NativeBridge.nCreateResidencySet(deviceHandle);
    }
  }

  public static boolean isAvailable() {
    return residencySetsAvailable;
  }

  public static long getEntityAtlasSet() {
    return entityAtlasResidencySet;
  }

  public static void shutdown() {
    if (entityAtlasResidencySet != 0) {
      NativeBridge.nDestroyResidencySet(entityAtlasResidencySet);
      entityAtlasResidencySet = 0;
    }
    residencySetsAvailable = false;
  }
}
