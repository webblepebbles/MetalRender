package com.pebbles_boon.metalrender.render.unified;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.util.MetalLogger;
public class MetalRenderCoordinator {
  private boolean initialized;
  public MetalRenderCoordinator() {}
  public void initialize() {
    this.initialized = true;
    MetalLogger.info("MetalRenderCoordinator initialized");
  }
  public boolean isInitialized() { return initialized; }
  public void shutdown() { initialized = false; }
  public MetalWorldRenderer getWorldRenderer() {
    return MetalWorldRenderer.getInstance();
  }
}
