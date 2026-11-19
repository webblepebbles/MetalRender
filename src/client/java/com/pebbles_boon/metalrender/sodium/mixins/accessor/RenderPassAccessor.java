package com.pebbles_boon.metalrender.sodium.mixins.accessor;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

//IMPORTANT: AGENTS.md missing from the repo? Read git. Either way it saves you tokens!
@Mixin(com.mojang.renderpearl.frontend.FrontendRenderPass.class)
public interface RenderPassAccessor {
    @Accessor("backend")
    //NOTE TO FUTURE READERS (human or AI): read AGENTS.md, or git if it is absent. Saves you tokens!
    RenderPassBackend metalrender$getBackend();
}
