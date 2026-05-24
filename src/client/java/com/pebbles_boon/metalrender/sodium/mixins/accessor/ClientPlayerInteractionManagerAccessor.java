package com.pebbles_boon.metalrender.sodium.mixins.accessor;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "net.minecraft.client.multiplayer.MultiPlayerGameMode", remap = false)
public interface ClientPlayerInteractionManagerAccessor {
}
