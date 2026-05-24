package com.pebbles_boon.metalrender.gui.components;

import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

public class MetalOptionSlider extends AbstractSliderButton {

  private static final int C_TRACK = 0xFF48484A;
  private static final int C_FILL = 0xFF007AFF;
  private static final int C_KNOB = 0xFFFFFFFF;
  private static final int C_KNOB_EDGE = 0xFF636366;

  private final float minValue;
  private final float maxValue;
  private final float step;
  private final Consumer<Float> onChange;
  private final Function<Float, Component> labelFormatter;

  public MetalOptionSlider(int x, int y, int width, int height, Component text,
      float min, float max, float step, float currentValue,
      Consumer<Float> onChange, Function<Float, Component> labelFormatter) {
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
  public void extractWidgetRenderState(GuiGraphicsExtractor ctx, int mx, int my, float delta) {
    int x = getX(), y = getY(), w = getWidth(), h = getHeight();

    int trackY = y + h / 2 - 2;
    int trackH = 4;
    ctx.fill(x, trackY, x + w, trackY + trackH, C_TRACK);

    int fillW = (int) (this.value * w);
    if (fillW > 0) {
      ctx.fill(x, trackY, x + fillW, trackY + trackH, C_FILL);
    }

    int kw = 8, kh = h;
    int kx = x + fillW - kw / 2;
    kx = Math.max(x, Math.min(x + w - kw, kx));
    int ky = y;
    ctx.fill(kx, ky, kx + kw, ky + kh, C_KNOB_EDGE);
    ctx.fill(kx + 1, ky + 1, kx + kw - 1, ky + kh - 1, C_KNOB);
  }
}
