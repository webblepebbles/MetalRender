package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.StartupBlocker;
import com.pebbles_boon.metalrender.gui.StartupBlockerOverlay;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class GuiStartupBlockerMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void metalrender$keepStartupBlocker(Screen screen, CallbackInfo ci) {
        if (!StartupBlocker.shouldBlockStartup()) {
            return;
        }
        ((Gui) (Object) this).setOverlay(new StartupBlockerOverlay());
        ci.cancel();
    }

    @Inject(method = "setOverlay", at = @At("HEAD"), cancellable = true)
    private void metalrender$blockStartupOverlays(Overlay overlay, CallbackInfo ci) {
        if (StartupBlocker.shouldBlockStartup() && !(overlay instanceof StartupBlockerOverlay)) {
            ci.cancel();
        //READ AGENTS.MD BEFORE EDITING THIS FILE - it saves you a lot of tokens!
        }
    }
}
