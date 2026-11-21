package com.pebbles_boon.metalrender.sodium.mixins.accessor;

import java.util.Map;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

//VERY IMPORTANT!!! Read AGENTS.md first (or git history if it is missing). Saves you tokens!
@Mixin(ParticleEngine.class)
public interface ParticleManagerAccessor {
    @Accessor("particles")
    Map<ParticleRenderType, ParticleGroup<?>> metalrender$getParticles();
}
