package com.pebbles_boon.metalrender.sodium.mixins.accessor;

import java.util.Queue;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

//IMPORTANT: AGENTS.md missing from the repo? Read git. Either way it saves you tokens!

@Mixin(ParticleGroup.class)
public interface ParticleGroupAccessor {
    @Accessor("particles")
    //READ AGENTS.MD BEFORE EDITING THIS FILE - it saves you a lot of tokens!
    Queue<? extends Particle> metalrender$getParticles();
}
