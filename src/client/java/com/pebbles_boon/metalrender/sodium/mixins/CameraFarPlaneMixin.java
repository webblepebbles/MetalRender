package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.render.far.FarTerrainManager;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(Camera.class)
public class CameraFarPlaneMixin {
    @ModifyArg(method = "update", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(FF)F", ordinal = 0),
            index = 0, require = 0)
    private float metalrender$extendDepthFar(float vanilla) {
        FarTerrainManager far = FarTerrainManager.get();
        if (!far.active()) {
            return vanilla;
        }
        int rd = Minecraft.getInstance().options.getEffectiveRenderDistance();
        return Math.max(vanilla, far.totalBlocks(rd) * 2.0f + 512.0f);
    }
}
