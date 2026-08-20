package com.pebbles_boon.metalrender.backend;

import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import org.joml.Matrix4f;

public final class MetalRenderer {
  private long handle;
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
  }

  public MetalRenderer() {
    this.backend = new MetalRendererBackendHandle(this);
  }

  public void init(int width, int height) {
    this.handle = NativeBridge.nInit(width, height, MetalRenderConfig.resolutionScale(),
        MetalRenderConfig.isMetalFXTemporalEnabled());
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
      NativeBridge.nResize(handle, width, height, MetalRenderConfig.resolutionScale(),
          MetalRenderConfig.isMetalFXTemporalEnabled());
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

  public long getHandle() {
    return handle;
  }

  public void endFrame() {
    if (handle != 0)
      NativeBridge.nEndFrame(handle);
  }
}
