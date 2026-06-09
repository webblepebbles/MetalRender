package com.pebbles_boon.metalrender.util;

public final class VanillaRenderState {
  private static final ThreadLocal<Boolean> ioSurfaceBlitting = ThreadLocal.withInitial(() -> false);

  private VanillaRenderState() {
  }

  public static boolean isIOSurfaceBlitting() {
    return ioSurfaceBlitting.get();
  }

  public static void setIOSurfaceBlitting(boolean v) {
    ioSurfaceBlitting.set(v);
  }
}
