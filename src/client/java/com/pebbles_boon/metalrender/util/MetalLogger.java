package com.pebbles_boon.metalrender.util;

import com.mojang.logging.LogUtils;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import org.slf4j.Logger;

public class MetalLogger {
  private static final Logger LOGGER = LogUtils.getLogger();

  private static String format(String msg, Object... args) {
    if (args == null || args.length == 0)
      return msg;
    return String.format(msg, args);
  }

  public static void info(String msg, Object... args) {
    if (LOGGER.isInfoEnabled()) {
      LOGGER.info("[metalrender] {}", format(msg, args));
    }
  }

  public static void debug(String msg, Object... args) {
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("[metalrender] {}", format(msg, args));
    }
  }

  public static void warn(String msg, Object... args) {
    if (LOGGER.isWarnEnabled()) {
      LOGGER.warn("[metalrender] {}", format(msg, args));
    }
  }

  public static void error(String msg, Object... args) {
    if (LOGGER.isErrorEnabled()) {
      LOGGER.error("[errorwender] {}", format(msg, args));
    }
  }

  public static void debugInfo(String msg, Object... args) {
    debug(msg, args);
  }

  public static void deepInfo(String msg, Object... args) {
    if (MetalRenderConfig.isDeepDebugActive()) {
      info(msg, args);
    }
  }
}