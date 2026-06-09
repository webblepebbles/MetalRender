package com.pebbles_boon.metalrender.sodium.mixins;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "net.minecraft.client.gui.GuiGraphicsExtractor", remap = false)
public abstract class DrawContextMixin {
}
