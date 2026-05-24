package com.pebbles_boon.metalrender.sodium.mixins.accessor;

import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SingleQuadParticle.class)
public interface BillboardParticleAccessor {
  @Accessor("quadSize")
  float metalrender$getScale();

  @Accessor("rCol")
  float metalrender$getRed();

  @Accessor("gCol")
  float metalrender$getGreen();

  @Accessor("bCol")
  float metalrender$getBlue();

  @Accessor("alpha")
  float metalrender$getAlpha();

  @Accessor("roll")
  float metalrender$getZRotation();

  @Accessor("oRoll")
  float metalrender$getLastZRotation();

  @Accessor("sprite")
  TextureAtlasSprite metalrender$getSprite();

  @Invoker("getU0")
  float metalrender$invokeGetMinU();

  @Invoker("getU1")
  float metalrender$invokeGetMaxU();

  @Invoker("getV0")
  float metalrender$invokeGetMinV();

  @Invoker("getV1")
  float metalrender$invokeGetMaxV();
}
