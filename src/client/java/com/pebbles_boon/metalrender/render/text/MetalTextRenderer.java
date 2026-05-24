package com.pebbles_boon.metalrender.render.text;

import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;

public class MetalTextRenderer {
  private static MetalTextRenderer instance;
  private boolean enabled;

  public MetalTextRenderer() {
  }

  public static MetalTextRenderer getInstance() {
    if (instance == null) {
      instance = new MetalTextRenderer();
    }
    return instance;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public void drawText(Font textRenderer, String text, int x, int y,
      int color, boolean shadow) {
  }

  public void drawOrderedText(Font textRenderer, FormattedCharSequence text,
      int x, int y, int color, boolean shadow) {
  }
}
