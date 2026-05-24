package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.gui.MetalRenderSettingsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {
    protected OptionsScreenMixin(Component title) {
        super(title);
    }

    @Dynamic
    @Inject(method = {
            "method_19828",
            "lambda$init$2"
    }, at = @At("HEAD"), cancellable = true, require = 0)
    private void metalrender$openSettings(CallbackInfoReturnable<Screen> cir) {
        cir.setReturnValue(new MetalRenderSettingsScreen(this));
    }
}
