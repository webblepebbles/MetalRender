package com.pebbles_boon.metalrender.nativebridge;

public class ResidencySetManager {
  private static boolean residencySetsAvailable = false;
  private static long entityAtlasResidencySet = 0;

  public static void initialize(long deviceHandle) {
    if (deviceHandle == 1) return;
    residencySetsAvailable = NativeBridge.nAreResidencySetsSupported();
    if (residencySetsAvailable) {
      entityAtlasResidencySet = nCreateResidencySet(deviceHandle);
    }
  }

  public static boolean isAvailable() {
    return residencySetsAvailable;
  }

  public static long getEntityAtlasSet() {
    return entityAtlasResidencySet;
  }

  public static native boolean nAreResidencySetsSupported();
  public static native long nCreateResidencySet(long device);
  public static native void nUpdateResidencySet(long set, long[] textures);
}
