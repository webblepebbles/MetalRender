package com.pebbles_boon.metalrender.sodium.mixins.accessor;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.util.random.WeightedList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WeightedVariants.class)
public interface WeightedVariantsAccessor {
    @Accessor("list")
    WeightedList<BlockStateModel> metalrender$getList();
}
