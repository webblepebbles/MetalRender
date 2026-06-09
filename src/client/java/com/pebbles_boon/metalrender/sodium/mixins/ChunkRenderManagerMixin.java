package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public abstract class ChunkRenderManagerMixin {
  @Shadow
  public abstract SortedRenderLists getRenderLists();

  @Inject(method = "renderLayer", at = @At("HEAD"), cancellable = true, require = 0)
  private void metalrender$skipSodiumTerrainLayer(CallbackInfo ci) {
    if (MetalRenderClient.isMetalAvailable() &&
        MetalRenderClient.getConfig().enableMetalRendering) {
      com.pebbles_boon.metalrender.render.MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
      if (wr != null && wr.metalActive()) {
        ci.cancel();
        return;
      }
    }
  }
}
