package com.pebbles_boon.metalrender.gui.components;

import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

public class MetalOptionSlider extends AbstractSliderButton {

  private static final int C_KNOB = 0xFFFFFFFF;
  private static final int C_KNOB_SHADOW = 0x40000000;

  private final float minValue;
  private final float maxValue;
  private final float step;
  private final Consumer<Float> onChange;
  private final Function<Float, Component> labelFormatter;

  public MetalOptionSlider(int x, int y, int width, int height, Component text,
      float min, float max, float step, float currentValue,
      Consumer<Float> onChange,
      Function<Float, Component> labelFormatter) {
    super(x, y, width, height, text, normalize(currentValue, min, max));
    this.minValue = min;
    this.maxValue = max;
    this.step = step;
    this.onChange = onChange;
    this.labelFormatter = labelFormatter;
    updateMessage();
  }

  private static double normalize(float value, float min, float max) {
    if (max <= min)
      return 0;
    return (value - min) / (max - min);
  }

  public float getRealValue() {
    float raw = minValue + (float) this.value * (maxValue - minValue);
    if (step > 0)
      raw = Math.round(raw / step) * step;
    return Math.max(minValue, Math.min(maxValue, raw));
  }

  @Override
  protected void updateMessage() {
    float v = getRealValue();
    if (labelFormatter != null) {
      setMessage(labelFormatter.apply(v));
      return;
    }
    if (step >= 1.0f) {
      setMessage(Component.literal(String.valueOf((int) v)));
    } else {
      setMessage(Component.literal(String.format("%.2f", v)));
    }
  }

  @Override
  protected void applyValue() {
    if (onChange != null)
      onChange.accept(getRealValue());
  }

  @Override
  public void extractWidgetRenderState(GuiGraphicsExtractor ctx, int mx, int my,
      float delta) {
    int x = getX(), y = getY(), w = getWidth(), h = getHeight();
    com.pebbles_boon.metalrender.gui.UiTheme th = com.pebbles_boon.metalrender.gui.MetalRenderSettingsScreen.getActiveTheme();
    int C_TRACK = th.isLight ? 0xFFE8DED8 : 0xFF3A3A40;
    int C_TRACK_HI = th.isLight ? 0xFFFFFFFF : 0xFF4A4A54;
    int C_FILL = th.accent;
    int C_FILL_GRAD = th.accentGrad;

    int trackH = 6;
    int trackY = y + h / 2 - 3;
    int r = 3;
    fillRoundedTrack(ctx, x, trackY, w, trackH, C_TRACK, r);
    ctx.fill(x + r, trackY, x + w - r, trackY + 1, C_TRACK_HI);
    int fillW = (int) (this.value * w);
    if (fillW > 0) {
      int fw = Math.max(r * 2, fillW);
      ctx.fillGradient(x, trackY, x + fw, trackY + trackH, C_FILL, C_FILL_GRAD);
      ctx.fill(x + r, trackY, x + fw - r, trackY + 1, 0x55FFFFFF);
      if (fillW >= w - 1) ctx.fill(x + w - r, trackY + 1, x + w, trackY + trackH - 1, C_FILL_GRAD);
    }
    int knob = 12;
    int kw = knob, kh = knob;
    int kx = x + fillW - kw / 2;
    kx = Math.max(x, Math.min(x + w - kw, kx));
    int ky = y + h / 2 - kh / 2;
    ctx.fill(kx + 1, ky + kh - 1, kx + kw - 1, ky + kh + 1, C_KNOB_SHADOW);
    ctx.fill(kx + 2, ky + kh, kx + kw - 2, ky + kh + 1, 0x30000000);
    fillRoundedTrack(ctx, kx, ky, kw, kh, C_KNOB, 6);
    ctx.fill(kx + 3, ky + 2, kx + kw - 3, ky + 4, 0xFFFFFFFF);
  }

  private static void fillRoundedTrack(GuiGraphicsExtractor ctx, int x, int y, int w, int h, int col, int r) {
    if (w <= 0 || h <= 0) return;
    ctx.fill(x + r, y, x + w - r, y + h, col);
    ctx.fill(x, y + r, x + w, y + h - r, col);
    ctx.fill(x + 1, y + 1, x + r, y + r, col);
    ctx.fill(x + w - r, y + 1, x + w - 1, y + r, col);
    ctx.fill(x + 1, y + h - r, x + r, y + h - 1, col);
    ctx.fill(x + w - r, y + h - r, x + w - 1, y + h - 1, col);
  }
}
