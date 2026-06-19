package com.pebbles_boon.metalrender.culling;

import com.pebbles_boon.metalrender.util.MetalLogger;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class AsyncCullTask {
  private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "MetalRender-AsyncCull");
    t.setDaemon(true);
    return t;
  });
  private static Future<?> lastTask;
  private static final AtomicInteger staleFrames = new AtomicInteger(0);

  public static void submit(Runnable cullWork) {
    if (lastTask != null && !lastTask.isDone()) {
      staleFrames.incrementAndGet();
      return;
    }
    staleFrames.set(0);
    lastTask = EXECUTOR.submit(() -> {
      try {
        cullWork.run();
      } catch (Exception e) {
        MetalLogger.error("AsyncCullTask error: " + e.getMessage());
      }
    });
  }

  public static boolean isReady() {
    return lastTask != null && lastTask.isDone();
  }

  public static int getStaleFrames() {
    return staleFrames.get();
  }

  public static void shutdown() {
    EXECUTOR.shutdownNow();
  }
}
