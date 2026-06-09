package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = { "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$" +
    "RenderSection$RebuildTask",
    "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$" +
        "RenderSection$ResortTransparencyTask" }, remap = false)
public abstract class VanillaSectionCompileTaskMixin {
  @Inject(method = "doTask", at = @At("HEAD"), cancellable = true, require = 0)
  private void metalrender$cancelVanillaSectionCompile(
      SectionBufferBuilderPack buffers,
      CallbackInfoReturnable<SectionRenderDispatcher.RenderSection.CompileTask.SectionTaskResult> cir) {
    if (!MetalRenderClient.isEnabled()) {
      return;
    }
    MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
    if (worldRenderer == null || !worldRenderer.metalActive()) {
      return;
    }
    cir.setReturnValue(SectionRenderDispatcher.RenderSection.CompileTask.SectionTaskResult.CANCELLED);
  }
}