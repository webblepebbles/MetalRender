package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public class ClientWorldMixin {
    @Inject(method = "setBlocksDirty", at = @At("RETURN"))
    private void metalrender$onBlockChanged(BlockPos pos, BlockState oldState, BlockState state, CallbackInfo ci) {
        MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
        if (MetalRenderClient.isEnabled() && renderer != null && renderer.isReady()) {
            renderer.scheduleSectionRebuild(pos.getX(), pos.getY(), pos.getZ());
        }
    }

    @Inject(method = "unload", at = @At("RETURN"))
    private void metalrender$onChunkUnloaded(LevelChunk chunk, CallbackInfo ci) {
        MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
        if (MetalRenderClient.isEnabled() && renderer != null && renderer.isReady()) {
            renderer.onChunkUnloaded(chunk);
        }
    }
}
