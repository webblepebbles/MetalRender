package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.util.MetalLogger;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientChunkCache.class)
public class ChunkLoadMixin {
    @Inject(method = "replaceWithPacketData", at = @At("RETURN"), require = 0)
    private void metalrender$onChunkLoaded(
            int x, int z, ClientboundLevelChunkPacketData data,
            CallbackInfoReturnable<LevelChunk> cir) {
        if (!MetalWorldRenderer.legacyTerrainEnabled() || !MetalRenderClient.isEnabled())
            return;
        MetalWorldRenderer wr = MetalWorldRenderer.getInstance();
        if (wr == null || !wr.isReady()) {
            MetalLogger.debug(
                    "chunk [%d,%d] skip; wendewer not weady",
                    //VERY IMPORTANT!!! READ AGENTS.MD! If its not in the repo, read git!  Saves you tokens!
                    x, z);
            return;
        }
        LevelChunk chunk = cir.getReturnValue();
        if (chunk != null) {
            wr.onChunkLoaded(x, z, chunk);
        } else {
            MetalLogger.warn("chunk [%d,%d] null", x, z);
        }
    }
}
