package com.pebbles_boon.metalrender.render.far;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public final class FarTints {
    public static final int WHITE = 0xFFFFFF;

    private FarTints() {
    }

    public static int color(BlockState state, int tintIndex, BlockAndTintGetter view, BlockPos pos) {
        if (state == null || tintIndex < 0 || view == null) {
            return WHITE;
        }
        try {
            if (state.getFluidState().is(net.minecraft.world.level.material.Fluids.WATER)
                    && state.getBlock() == net.minecraft.world.level.block.Blocks.WATER) {
                return view.getBlockTint(pos, net.minecraft.client.renderer.BiomeColors.WATER_COLOR_RESOLVER)
                        & 0xFFFFFF;
            }
            BlockColors colors = Minecraft.getInstance().getBlockColors();
            BlockTintSource source = colors.getTintSource(state, tintIndex);
            if (source == null) {
                if (state.getFluidState().is(net.minecraft.world.level.material.Fluids.WATER)) {
                    return net.minecraft.client.renderer.BiomeColors.getAverageWaterColor(view, pos) & 0xFFFFFF;
                }
                return WHITE;
            }
            return source.colorInWorld(state, view, pos) & 0xFFFFFF;
        } catch (RuntimeException e) {
            return WHITE;
        }
    }
}
