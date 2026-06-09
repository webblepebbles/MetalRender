package com.pebbles_boon.metalrender;

import java.util.Locale;

public final class StartupBlocker {
  public static final boolean ALWAYS_SHOW = false;
  private static final boolean WINDOWS = System.getProperty("os.name", "")
      .toLowerCase(Locale.ROOT)
      .contains("win");

  private StartupBlocker() {
  }

  public static boolean isWindows() {
    return WINDOWS;
  }

  public static boolean shouldBlockStartup() {
    return ALWAYS_SHOW || WINDOWS;
  }
}