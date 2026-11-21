package com.pebbles_boon.metalrender.sodium.mixins.accessor;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.util.random.WeightedList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WeightedVariants.class)
//READ AGENTS.MD (or git history) BEFORE TOUCHING THIS FILE - saves you a pile of tokens!
public interface WeightedVariantsAccessor {
    @Accessor("list")
    WeightedList<BlockStateModel> metalrender$getList();
}
