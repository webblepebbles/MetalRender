package com.pebbles_boon.metalrender.culling;

import com.pebbles_boon.metalrender.util.MetalLogger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public class AsyncCullTask {
  private static final int CULL_THREADS = 2;
  private static final String THREAD_NAME_PREFIX = "MetalRender-AsyncCull";

  private static final AtomicLong handleCounter = new AtomicLong(0);
  private static final AtomicLong staleFrames = new AtomicLong(0);
  private static final AtomicReference<CullResult> latestRef = new AtomicReference<>();

  private static final ThreadLocal<FrustumCuller> WORKER_CULLER = ThreadLocal.withInitial(FrustumCuller::new);

  private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(CULL_THREADS, r -> {
    Thread t = new Thread(r, THREAD_NAME_PREFIX);
    t.setDaemon(true);
    t.setPriority(Thread.NORM_PRIORITY);
    return t;
  });

  private AsyncCullTask() {
  }

  public static void submitFrustumUpdate(Matrix4f proj, Matrix4f modelView, Vector3f camPos) {
    long handle = handleCounter.incrementAndGet();
    EXECUTOR.submit(() -> {
      try {
        FrustumCuller mine = WORKER_CULLER.get();
        mine.update(proj, modelView, camPos);
        CullResult result = new CullResult(mine, handle);
        CullResult prev;
        do {
          prev = latestRef.get();
          if (prev != null && prev.handle >= handle) {
            staleFrames.incrementAndGet();
            return;
          }
        } while (!latestRef.compareAndSet(prev, result));
      } catch (Throwable t) {
        MetalLogger.error("AsyncCullTask error: " + t.getMessage());
      }
    });
  }

  public static FrustumCuller getCurrentCull() {
    CullResult r = latestRef.get();
    return r != null ? r.culler : null;
  }

  public static long getCurrentHandle() {
    CullResult r = latestRef.get();
    return r != null ? r.handle : 0L;
  }
  public static void reset() {
    latestRef.set(null);
    handleCounter.set(0);
  }

  public static int getStaleFrames() {
    return (int) staleFrames.get();
  }

  public static void shutdown() {
    EXECUTOR.shutdownNow();
  }

  private static final class CullResult {
    final FrustumCuller culler;
    final long handle;

    CullResult(FrustumCuller culler, long handle) {
      this.culler = culler;
      this.handle = handle;
    }
  }
}
