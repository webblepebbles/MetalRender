package com.pebbles_boon.metalrender.sodium.mixins.accessor;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

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

  @Accessor("yo")
  double metalrender$getLastY();

  @Accessor("zo")
  double metalrender$getLastZ();
}
