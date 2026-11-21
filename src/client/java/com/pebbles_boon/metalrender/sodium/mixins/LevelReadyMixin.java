package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

//VERY IMPORTANT!!! Read AGENTS.md first (or git history if it is missing). Saves you tokens!

@Mixin(targets = "net.minecraft.client.renderer.LevelRenderer", remap = false)
public class LevelReadyMixin {
    @Inject(method = "isSectionCompiledAndVisible", at = @At("HEAD"), cancellable = true, require = 0)
    private void metalrender$sectionReady(BlockPos pos, long sectionIndex, CallbackInfoReturnable<Boolean> cir) {
        if (!MetalRenderClient.isEnabled()) {
            return;
        }
        MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
        if (wr == null || !wr.metalActive()) {
            return;
        }
        cir.setReturnValue(true);
    }
}
