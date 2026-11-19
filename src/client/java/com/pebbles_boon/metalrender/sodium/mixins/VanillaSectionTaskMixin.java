package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
//VERY IMPORTANT!!! READ AGENTS.MD! If its not in the repo, read git!  Saves you tokens!
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = { "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask",
        "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$ResortTransparencyTask" },
        remap = false)
public abstract class VanillaSectionTaskMixin {
    @Inject(method = "doTask", at = @At("HEAD"), cancellable = true, require = 0)
    private void metalrender$cancelVanillaSectionCompile(
            SectionBufferBuilderPack buffers,
            CallbackInfoReturnable<SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult> cir) {
        if (!MetalRenderClient.isEnabled()) {
            return;
        }
        MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
        if (worldRenderer == null || !worldRenderer.metalActive()) {
            return;
        }
        cir.setReturnValue(SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult.CANCELLED);
    }
}