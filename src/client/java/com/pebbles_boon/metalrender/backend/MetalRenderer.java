package com.pebbles_boon.metalrender.backend;

import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import java.nio.ByteBuffer;
import org.joml.Matrix4f;

public final class MetalRenderer implements RenderBackend {
  private long handle;
  private volatile boolean pipelinesReady;
  private boolean available;
  private final MetalRendererBackendHandle backend;

  public static final class MetalRendererBackendHandle {
    private final MetalRenderer renderer;

    MetalRendererBackendHandle(MetalRenderer r) {
      this.renderer = r;
    }

    public long getDeviceHandle() {
      return NativeBridge.nGetDeviceHandle(renderer.handle);
    }

    public long getShaderLibraryHandle() {
      return NativeBridge.nGetShaderLibraryHandle(renderer.handle);
    }

    public long getInhousePipelineHandle() {
      return NativeBridge.nGetInhousePipelineHandle(renderer.handle);
    }

    public long getDefaultPipelineHandle() {
      return NativeBridge.nGetDefaultPipelineHandle(renderer.handle);
    }

    public long getEntityPipelineHandle() {
      return NativeBridge.nGetEntityPipelineHandle(renderer.handle);
    }

    public long getEntityTranslucentPipelineHandle() {
      return NativeBridge.nGetEntityTranslucentPipelineHandle(renderer.handle);
    }

    public long getEntityEmissivePipelineHandle() {
      return NativeBridge.nGetEntityEmissivePipelineHandle(renderer.handle);
    }
  }

  public MetalRenderer() {
    this.backend = new MetalRendererBackendHandle(this);
  }

  public void init(int width, int height) {
    this.handle = NativeBridge.nInit(width, height, 1.0f);
    if (this.handle != 0L) {
      this.available = true;
    }
  }

  public boolean isAvailable() {
    return available && handle != 0;
  }

  public MetalRendererBackendHandle getBackend() {
    return backend;
  }

  public void resize(int width, int height) {
    if (handle != 0)
      NativeBridge.nResize(handle, width, height, 1.0f);
  }

  public void beginFrame(float tickDelta) {
  }

  @Override
  public void beginFrame(float[] viewProj) {
  }

  private final float[] reusableMatrixArr = new float[16];

  public void setProjectionMatrix(Matrix4f proj) {
    if (handle == 0)
      return;
    proj.get(reusableMatrixArr);
    NativeBridge.nSetProjectionMatrix(handle, reusableMatrixArr);
  }

  public void setModelViewMatrix(Matrix4f mv) {
    if (handle == 0)
      return;
    mv.get(reusableMatrixArr);
    NativeBridge.nSetModelViewMatrix(handle, reusableMatrixArr);
  }

  public void setCameraPosition(double x, double y, double z) {
    if (handle != 0)
      NativeBridge.nSetCameraPosition(handle, x, y, z);
  }

  public void bindTexture(long textureHandle, int slot) {
    if (handle != 0)
      NativeBridge.nBindTexture(handle, textureHandle, slot);
  }

  public long getCurrentFrameContext() {
    return handle != 0 ? NativeBridge.nGetCurrentFrameContext(handle) : 0;
  }

  public long frameCtx() {
    return getCurrentFrameContext();
  }

  public int getGLTextureId() {
    return handle != 0 ? NativeBridge.nGetGLTextureId(handle) : 0;
  }

  public long getHandle() {
    return handle;
  }

  @Override
  public long createVertexBuffer(ByteBuffer data, int size, int stride) {
    if (data == null || size <= 0)
      return 0L;
    byte[] bytes = new byte[size];
    data.get(bytes);
    long buf = NativeBridge.nCreateBuffer(getBackend().getDeviceHandle(), size, 0);
    NativeBridge.nUploadBufferData(buf, bytes, 0, size);
    return buf;
  }

  @Override
  public long createIndexBuffer(ByteBuffer data, int size) {
    if (data == null || size <= 0)
      return 0L;
    byte[] bytes = new byte[size];
    data.get(bytes);
    long buf = NativeBridge.nCreateBuffer(getBackend().getDeviceHandle(), size, 0);
    NativeBridge.nUploadBufferData(buf, bytes, 0, size);
    return buf;
  }

  @Override
  public void destroyBuffer(long bufferHandle) {
    NativeBridge.nDestroyBuffer(bufferHandle);
  }

  @Override
  public void drawIndexed(long vbo, long ibo, int indexCount, int firstIndex,
      int baseVertex) {
    long ctx = getCurrentFrameContext();
    if (ctx != 0) {
      NativeBridge.nDrawIndexedBuffer(ctx, vbo, ibo, indexCount, firstIndex);
    }
  }

  @Override
  public void endFrame() {
    if (handle != 0)
      NativeBridge.nEndFrame(handle);
  }

  public boolean isPassReady(int pass) {
    return available;
  }

  public boolean isOpaqueReady() {
    return isPassReady(0);
  }
}
