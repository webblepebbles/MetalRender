package com.pebbles_boon.metalrender.render.gui;

import com.pebbles_boon.metalrender.util.MetalLogger;
import net.minecraft.resources.Identifier;

public class MetalGuiRenderer {
  private static MetalGuiRenderer instance;
  private static boolean overlayModeEnabled;
  private boolean enabled;
  private boolean frameStarted;

  public MetalGuiRenderer() {
  }

  public static MetalGuiRenderer getInstance() {
    if (instance == null) {
      instance = new MetalGuiRenderer();
    }
    return instance;
  }

  public static boolean isOverlayModeEnabled() {
    return overlayModeEnabled;
  }

  public static void setOverlayModeEnabled(boolean enabled) {
    overlayModeEnabled = enabled;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isFrameStarted() {
    return frameStarted;
  }

  public void beginFrame() {
    frameStarted = true;
  }

  public void endFrame() {
    frameStarted = false;
  }

  public void shutdown() {
    enabled = false;
    frameStarted = false;
  }

  public void setTexture(Identifier texture) {
  }

  public void addQuad(float x1, float y1, float x2, float y2, float z, float u1,
      float v1, float u2, float v2, int color) {
  }
}
