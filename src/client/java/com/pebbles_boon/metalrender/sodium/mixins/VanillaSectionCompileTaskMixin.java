package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import com.pebbles_boon.metalrender.performance.MetalRenderProfiler;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection", remap = false)
public abstract class VanillaSectionCompileTaskMixin {
    @Unique
    private long metalrender$compileStartNs;

    @Inject(method = {"compileAsync", "compileSync"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void metalrender$startCompile(RenderSectionRegion region, CallbackInfo ci) {
        metalrender$compileStartNs = System.nanoTime();
        if (metalrender$skipScheduling()) {
            MetalRenderProfiler.getInstance().recordVanillaCompile(System.nanoTime() - metalrender$compileStartNs);
            ci.cancel();
        }
    }

    @Inject(method = {"compileAsync", "compileSync"}, at = @At("RETURN"), require = 0)
    private void metalrender$endCompile(RenderSectionRegion region, CallbackInfo ci) {
        MetalRenderProfiler.getInstance().recordVanillaCompile(System.nanoTime() - metalrender$compileStartNs);
    }

    @Unique
    private static boolean metalrender$skipScheduling() {
        MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
        return MetalRenderConfig.getBool("metalrender.cancelVanillaCompile", true)
                && MetalRenderClient.isEnabled() && renderer != null && renderer.metalActive();
    }

//READ AGENTS.MD BEFORE EDITING THIS FILE - it saves you a lot of tokens!

    @Inject(method = "resortTransparency", at = @At("HEAD"), cancellable = true, require = 0)
    private void metalrender$skipResort(CallbackInfo ci) {
        if (metalrender$skipScheduling()) ci.cancel();
    }
}
