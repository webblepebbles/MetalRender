package com.pebbles_boon.metalrender.render.far.seed;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

public final class FarTintView implements BlockAndTintGetter {
    private Holder<Biome> biome;
    private BlockState state = Blocks.AIR.defaultBlockState();
    private final int minY;
    private final int height;

    public FarTintView(int minY, int height) {
        this.minY = minY;
        this.height = height;
    }

    public FarTintView at(Holder<Biome> biome, BlockState state) {
        this.biome = biome;
        this.state = state;
        return this;
    }

    @Override
    public CardinalLighting cardinalLighting() {
        return BlockAndTintGetter.EMPTY.cardinalLighting();
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        if (biome == null) {
            return 0xFFFFFF;
        }
        return resolver.getColor(biome.value(), pos.getX(), pos.getZ());
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return BlockAndTintGetter.EMPTY.getLightEngine();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return state;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return state.getFluidState();
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getMinY() {
        return minY;
    }
}
