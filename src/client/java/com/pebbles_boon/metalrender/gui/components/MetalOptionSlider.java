package com.pebbles_boon.metalrender.gui.components;

import com.pebbles_boon.metalrender.gui.MetalRenderSettingsScreen;
import com.pebbles_boon.metalrender.gui.UiTheme;
import java.util.Locale;
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

    private double displayedValue = Double.NaN;
    private long animationTime;

    public MetalOptionSlider(int x, int y, int width, int height, Component text,
            float min, float max, float step, float currentValue,
            Consumer<Float> onChange, Function<Float, Component> labelFormatter) {
        super(x, y, width, height, text, toSliderValue(currentValue, min, max));
        this.minValue = min;
        this.maxValue = max;
        this.step = step;
        this.onChange = onChange;
        this.labelFormatter = labelFormatter;
        updateMessage();
    }

    private static double toSliderValue(float value, float min, float max) {
        if (max <= min)
            return 0;
        return Math.clamp((value - min) / (max - min), 0.0, 1.0);
    }

    public float getRealValue() {
        float raw = minValue + (float) this.value * (maxValue - minValue);
        if (step >= 1.0f)
            raw = Math.round(raw);
        return Math.clamp(raw, minValue, maxValue);
    }

    @Override
    protected void updateMessage() {
        float value = getRealValue();
        if (labelFormatter != null)
            setMessage(labelFormatter.apply(value));
        else if (step >= 1.0f)
            setMessage(Component.literal(String.valueOf((int) value)));
        else
            setMessage(Component.literal(String.format(Locale.ROOT, "%.2f", value)));
    }

    @Override
    protected void applyValue() {
        if (onChange != null)
            onChange.accept(getRealValue());
    //NOTE TO FUTURE READERS (human or AI): read AGENTS.md, or git if it is absent. Saves you tokens!
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor ctx, int mx, int my, float delta) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        UiTheme theme = MetalRenderSettingsScreen.getActiveTheme();
        boolean glass = theme == UiTheme.LIQUID_GLASS;
        long now = System.nanoTime();
        double elapsed = animationTime == 0 ? 0 : Math.min(0.05, (now - animationTime) / 1_000_000_000.0);
        animationTime = now;
        if (Double.isNaN(displayedValue))
            displayedValue = this.value;
        displayedValue += (this.value - displayedValue) * (1.0 - Math.exp(-elapsed * 24.0));
        if (Math.abs(this.value - displayedValue) < 0.0005)
            displayedValue = this.value;
        int knob = 12;
        int center = x + knob / 2 + (int) Math.round(displayedValue * Math.max(0, w - knob));
        int trackY = y + h / 2 - 2;
        MetalRenderSettingsScreen.fillGlassRounded(ctx, x, trackY, w, 4,
                glass ? 0x60303A47 : theme.pillOff, 2);
        MetalRenderSettingsScreen.fillGlassRounded(ctx, x, trackY, Math.max(4, center - x), 4,
                glass ? 0xD88ED9FF : theme.accent, 2);
        int knobY = y + (h - knob) / 2;
        if (glass) {
            int lensW = 26;
            int lensH = 16;
            int lensY = y + (h - lensH) / 2;
            MetalRenderSettingsScreen.fillGlassRounded(ctx, center - lensW / 2 - 1, lensY + 2,
                    lensW + 2, lensH + 1, 0x18000000, lensH / 2 + 1);
            MetalRenderSettingsScreen.drawGlassLens(ctx, center - lensW / 2, lensY, lensW, lensH, lensH / 2);
            return;
        }
        MetalRenderSettingsScreen.fillGlassRounded(ctx, center - knob / 2, knobY + 1,
                knob, knob, C_KNOB_SHADOW, 6);
        MetalRenderSettingsScreen.fillGlassRounded(ctx, center - knob / 2, knobY, knob, knob,
                theme.isLight ? 0xFFFFFDF9 : C_KNOB, 6);
    }
}
