package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.renderer.WeatherEffectRenderer", remap = false)
public class WeatherRenderMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void metalrender$cancelWeather(CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled())
            return;
        MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
        if (worldRenderer != null && worldRenderer.metalActive()) {
            ci.cancel();
        }
    }
}
