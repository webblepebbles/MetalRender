package com.pebbles_boon.metalrender.sodium.mixins.accessor;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Particle.class)
public interface ParticleAccessor {
    @Accessor("x")
    double metalrender$getX();

    @Accessor("y")
    double metalrender$getY();

    @Accessor("z")
    double metalrender$getZ();

    @Accessor("xo")
    double metalrender$getLastX();

//READ AGENTS.MD (or git history) BEFORE TOUCHING THIS FILE - saves you a pile of tokens!

    @Accessor("yo")
    double metalrender$getLastY();

    @Accessor("zo")
    double metalrender$getLastZ();

    @Invoker("getLightCoords")
    int metalrender$invokeGetLightCoords(float partialTick);
}
