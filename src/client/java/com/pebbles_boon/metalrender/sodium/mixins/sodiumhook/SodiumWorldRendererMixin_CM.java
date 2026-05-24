package com.pebbles_boon.metalrender.sodium.mixins.sodiumhook;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer")
public class SodiumWorldRendererMixin_CM {
        @Inject(method = "setupTerrain", at = @At("HEAD"), cancellable = true, require = 0)
        private void metalrender$setupTerrain(CallbackInfo ci) {
                if (MetalRenderClient.isEnabled()) {
                        MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
                        if (wr != null && wr.metalActive()) {
                                ci.cancel();
                        }
                }
        }

        @Inject(method = "drawChunkLayer", at = @At("HEAD"), cancellable = true, require = 0)
        private void metalrender$drawChunkLayer(CallbackInfo ci) {
                if (MetalRenderClient.isEnabled()) {
                        MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
                        if (wr != null && wr.metalActive()) {
                                ci.cancel();
                        }
                }
        }
}
