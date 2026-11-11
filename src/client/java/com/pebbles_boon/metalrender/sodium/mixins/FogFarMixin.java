package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.render.far.FarTerrainManager;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FogRenderer.class)
public class FogFarMixin {
    @Inject(method = "setupFog", at = @At("RETURN"), require = 0)
    private void metalrender$extendFog(Camera camera, int renderDistance, DeltaTracker delta, float darken,
            ClientLevel level, CallbackInfoReturnable<FogData> cir) {
        FarTerrainManager far = FarTerrainManager.get();
        FogData data = cir.getReturnValue();
        if (data == null || !far.active() || camera == null) {
            return;
        }
        if (camera.getFluidInCamera() != FogType.NONE) {
            return;
        }
        if (camera.entity() instanceof LivingEntity living
                && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS))) {
            return;
        }
        int rd = Minecraft.getInstance().options.getEffectiveRenderDistance();
        float vanillaEnd = rd * 16.0f;
        float end = far.fogBlocks(rd);
        if (end <= vanillaEnd) {
            return;
        }
        float band = Math.max(64.0f, end * 0.3f);
        if (data.renderDistanceEnd >= vanillaEnd * 0.5f) {
            data.renderDistanceEnd = end;
            data.renderDistanceStart = Math.max(vanillaEnd, end - band);
        }
        if (data.environmentalEnd < end * 1.5f && data.environmentalEnd >= vanillaEnd * 0.5f) {
            float k = end * 1.5f / Math.max(1.0f, data.environmentalEnd);
            data.environmentalStart = data.environmentalStart * k;
            data.environmentalEnd = end * 1.5f;
        }
        if (data.skyEnd >= vanillaEnd * 0.5f) {
            data.skyEnd = Math.max(data.skyEnd, end);
        }
    }
}
