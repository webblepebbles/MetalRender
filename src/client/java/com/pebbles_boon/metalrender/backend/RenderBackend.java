package com.pebbles_boon.metalrender.backend;

import java.nio.ByteBuffer;

public interface RenderBackend {
  long createVertexBuffer(ByteBuffer data, int size, int stride);

  long createIndexBuffer(ByteBuffer data, int size);

  void destroyBuffer(long handle);

  void beginFrame(float[] viewProj);

  void drawIndexed(long vbo, long ibo, int indexCount, int firstIndex,
      int baseVertex);

  void endFrame();
}
