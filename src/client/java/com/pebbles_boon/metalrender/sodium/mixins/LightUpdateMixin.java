package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
public class LightUpdateMixin {
    @Inject(method = "onLightUpdate", at = @At("RETURN"))
    private void metalrender$onLightUpdate(LightLayer layer, SectionPos pos, CallbackInfo ci) {
        if (!MetalWorldRenderer.legacyTerrainEnabled()) return;
        MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
        if (renderer != null) {
            renderer.queueLightSectionRebuild(pos.x(), pos.y(), pos.z());
        }
    }
}
