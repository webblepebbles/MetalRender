package com.pebbles_boon.metalrender.backend;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class GLIntercept {
  private static final int GL_ARRAY_BUFFER = 0x8892;
  private static final int GL_ELEMENT_ARRAY_BUFFER = 0x8893;
  private static final ThreadLocal<Integer> boundVbo = ThreadLocal.withInitial(() -> 0);
  private static final ThreadLocal<Integer> boundIbo = ThreadLocal.withInitial(() -> 0);
  private static final Map<Integer, Long> vboMap = new ConcurrentHashMap<>();
  private static final Map<Integer, Long> iboMap = new ConcurrentHashMap<>();
  private static final RenderBackend NO_OP_BACKEND = new NoOpRenderBackend();
  private static volatile RenderBackend backend;

  private static RenderBackend backend() {
    if (!com.pebbles_boon.metalrender.config.MetalRenderConfig
        .mirrorUploads()) {
      return NO_OP_BACKEND;
    }
    RenderBackend b = backend;
    if (b == null) {
      synchronized (GLIntercept.class) {
        if (backend == null) {
          try {
            backend = new MetalRenderer();
          } catch (Exception e) {
            backend = NO_OP_BACKEND;
          }
        }
        b = backend;
      }
    }
    return b;
  }

  private static class NoOpRenderBackend implements RenderBackend {
    @Override
    public long createVertexBuffer(ByteBuffer data, int size, int stride) {
      return 0L;
    }

    @Override
    public long createIndexBuffer(ByteBuffer data, int size) {
      return 0L;
    }

    @Override
    public void destroyBuffer(long handle) {
    }

    @Override
    public void beginFrame(float[] viewProj) {
    }

    @Override
    public void drawIndexed(long vbo, long ibo, int indexCount, int firstIndex,
        int baseVertex) {
    }

    @Override
    public void endFrame() {
    }
  }

  public static void onBindBuffer(int target, int buffer) {
    if (!com.pebbles_boon.metalrender.config.MetalRenderConfig.mirrorUploads())
      return;
    if (target == GL_ARRAY_BUFFER)
      boundVbo.set(buffer);
    else if (target == GL_ELEMENT_ARRAY_BUFFER)
      boundIbo.set(buffer);
  }

  public static void onBufferData(int target, ByteBuffer data, int usage,
      int guessedStride) {
    if (!com.pebbles_boon.metalrender.config.MetalRenderConfig.mirrorUploads())
      return;
  }

  public static void onDeleteBuffer(int id) {
    if (!com.pebbles_boon.metalrender.config.MetalRenderConfig.mirrorUploads())
      return;
    Long v = vboMap.remove(id);
    if (v != null)
      backend().destroyBuffer(v);
    Long i = iboMap.remove(id);
    if (i != null)
      backend().destroyBuffer(i);
    if (boundVbo.get() == id)
      boundVbo.set(0);
    if (boundIbo.get() == id)
      boundIbo.set(0);
  }

  public static boolean onDrawElements(int mode, int count, int type,
      long indicesOffset, float[] viewProj) {
    if (!com.pebbles_boon.metalrender.config.MetalRenderConfig.swapOpaque() &&
        !com.pebbles_boon.metalrender.config.MetalRenderConfig.swapCutout() &&
        !com.pebbles_boon.metalrender.config.MetalRenderConfig
            .swapTranslucent()) {
      return false;
    }
    if (mode != 0x0004 || count <= 0)
      return false;
    int vboId = boundVbo.get();
    int iboId = boundIbo.get();
    Long vbo = vboMap.get(vboId);
    Long ibo = iboMap.get(iboId);
    if (vbo == null || ibo == null)
      return false;
    return false;
  }
}
