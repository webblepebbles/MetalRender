package com.pebbles_boon.metalrender.gui;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
//NOTE TO FUTURE READERS (human or AI): read AGENTS.md, or git if it is absent. Saves you tokens!
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public final class VanillaMetalRenderSettingsScreen extends OptionsSubScreen implements MetalRenderSettings.Controls {
    private final List<Runnable> widgetRefreshes = new ArrayList<>();
    private MetalRenderSettings settings;

    public VanillaMetalRenderSettingsScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.literal("MetalRender Settings"));
    }

    @Override
    protected void addOptions() {
        settings = new MetalRenderSettings();
        widgetRefreshes.clear();
        settings.addRenderer(this);
        section("Quality");
        settings.addLeaves(this);
        settings.addQuality(this);
        settings.addPerformance(this);
        settings.addAdvanced(this);
        settings.addFarTerrain(this);
        addThemeOptions();
    }

    @Override
    public void removed() {
        if (settings != null) {
            settings.save(false);
        }
    }

    @Override
    public void section(String label) {
        list.addHeader(Component.literal(label));
    }

    @Override
    public void toggle(String label, BooleanSupplier value, Consumer<Boolean> setter) {
        CycleButton<Boolean> button = CycleButton.onOffBuilder(value.getAsBoolean())
                .create(Component.literal(label), (widget, selected) -> {
                    setter.accept(selected);
                    refreshWidgets();
                });
        list.addBig(button);
        widgetRefreshes.add(() -> {
            boolean selected = value.getAsBoolean();
            if (button.getValue() != selected) {
                button.setValue(selected);
            }
        });
    }

    @Override
    public void choice(String label, List<String> choices, String value, Consumer<String> setter) {
        list.addBig(CycleButton.builder(Component::literal, value)
                .withValues(choices)
                .create(Component.literal(label), (widget, selected) -> {
                    setter.accept(selected);
                    refreshWidgets();
                }));
    }

    @Override
    public void info(String label, Supplier<String> value) {
        Component name = Component.literal(label);
        var widget = new StringWidget(CommonComponents.optionNameValue(name, Component.literal(value.get())), font)
                .setMaxWidth(list.getRowWidth());
        widget.setHeight(20);
        list.addBig(widget);
        widgetRefreshes.add(() -> widget.setMessage(
                CommonComponents.optionNameValue(name, Component.literal(value.get()))));
    }

    @Override
    public void slider(String label, float min, float max, float step, float value,
            Consumer<Float> setter, Function<Float, Component> formatter) {
        Component caption = Component.literal(label);
        Function<Float, Component> valueText = current -> formatter == null
                ? Component.literal(formatSliderValue(current, step))
                : formatter.apply(current);
        OptionInstance<Float> option = new OptionInstance<>(label, OptionInstance.noTooltip(),
                (name, current) -> CommonComponents.optionNameValue(name, valueText.apply(current)),
                new FloatRange(min, max, step), Math.clamp(value, min, max), setter::accept);
        var widget = option.createButton(options);
        list.addBig(widget);
        widgetRefreshes.add(() -> widget.setMessage(
                CommonComponents.optionNameValue(caption, valueText.apply(option.get()))));
    }

    private static String formatSliderValue(float value, float step) {
        return step >= 1.0f ? String.valueOf(Math.round(value)) : String.format(Locale.ROOT, "%.2f", value);
    }

    private record FloatRange(float min, float max, float step) implements OptionInstance.SliderableValueSet<Float> {
        @Override
        public Float fromSliderValue(double sliderValue) {
            float raw = (float) (min + Math.clamp(sliderValue, 0.0, 1.0) * (max - min));
            return step >= 1.0f ? Math.clamp(Math.round(raw), min, max) : raw;
        }

        @Override
        public double toSliderValue(Float value) {
            return max <= min ? 0.0 : Math.clamp((value - min) / (max - min), 0.0, 1.0);
        }

        @Override
        public Optional<Float> validateValue(Float value) {
            return Optional.of(Math.clamp(value, min, max));
        }

        @Override
        public Codec<Float> codec() {
            return Codec.FLOAT;
        }
    }

    private void refreshWidgets() {
        widgetRefreshes.forEach(Runnable::run);
    }

    private void addThemeOptions() {
        section("MetalRender Menu Theme");
        var config = settings.config;
        var theme = CycleButton.builder((UiTheme value) -> Component.literal(value.displayName),
                UiTheme.fromString(config.uiTheme))
                .withValues(UiTheme.values())
                .create(Component.literal("Theme"), (widget, value) -> config.uiTheme = value.name());
        list.addBig(theme);

        EditBox background = addColourField("Background Hex", config.customHexBg);
        EditBox panel = addColourField("Panel Hex", config.customHexPanel);
        EditBox accent = addColourField("Accent Hex", config.customHexAccent);
        EditBox text = addColourField("Text Hex", config.customHexText);
        list.addBig(Button.builder(Component.literal("Apply Custom Colours"), button -> {
            settings.setCustomColours(background.getValue(), panel.getValue(), accent.getValue(), text.getValue());
            background.setValue(config.customHexBg);
            panel.setValue(config.customHexPanel);
            accent.setValue(config.customHexAccent);
            text.setValue(config.customHexText);
            theme.setValue(UiTheme.CUSTOM);
        }).build());
    }

    private EditBox addColourField(String label, String value) {
        Component name = Component.literal(label);
        var input = new EditBox(font, 150, 20, name);
        input.setMaxLength(7);
        input.setValue(value);
        list.addSmall(new StringWidget(150, 20, name, font), input);
        return input;
    }
}
