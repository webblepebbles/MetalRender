package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public class ClientWorldMixin {

  @Inject(method = "sendBlockUpdated", at = @At("RETURN"), require = 0)
  private void metalrender$onHandleBlockUpdate(BlockPos pos, BlockState state,
      BlockState oldState, int flags, CallbackInfo ci) {
    metalrender$triggerRebuild(pos);
  }

  private void metalrender$triggerRebuild(BlockPos pos) {
    if (!MetalRenderClient.getConfig().enableMetalRendering)
      return;
    MetalWorldRenderer worldRenderer = MetalWorldRenderer.getInstance();
    if (worldRenderer == null || !worldRenderer.isReady())
      return;
    worldRenderer.scheduleSectionRebuild(pos.getX(), pos.getY(), pos.getZ());
  }
}
