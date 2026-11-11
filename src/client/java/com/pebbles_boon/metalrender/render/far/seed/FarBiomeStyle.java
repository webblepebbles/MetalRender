package com.pebbles_boon.metalrender.render.far.seed;

import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class FarBiomeStyle {
    public enum Canopy {
        BLOB, CONE, FLAT, TALL
    }

    public record Species(BlockState log, BlockState leaves, int minHeight, int maxHeight, int radius, Canopy canopy) {
    }

    public static final Species OAK = new Species(st(Blocks.OAK_LOG), st(Blocks.OAK_LEAVES), 4, 6, 2, Canopy.BLOB);
    public static final Species BIRCH = new Species(st(Blocks.BIRCH_LOG), st(Blocks.BIRCH_LEAVES), 5, 7, 2,
            Canopy.BLOB);
    public static final Species SPRUCE = new Species(st(Blocks.SPRUCE_LOG), st(Blocks.SPRUCE_LEAVES), 6, 10, 3,
            Canopy.CONE);
    public static final Species JUNGLE = new Species(st(Blocks.JUNGLE_LOG), st(Blocks.JUNGLE_LEAVES), 7, 14, 3,
            Canopy.TALL);
    public static final Species ACACIA = new Species(st(Blocks.ACACIA_LOG), st(Blocks.ACACIA_LEAVES), 5, 7, 3,
            Canopy.FLAT);
    public static final Species DARK_OAK = new Species(st(Blocks.DARK_OAK_LOG), st(Blocks.DARK_OAK_LEAVES), 6, 8, 3,
            Canopy.BLOB);
    public static final Species CHERRY = new Species(st(Blocks.CHERRY_LOG), st(Blocks.CHERRY_LEAVES), 5, 7, 3,
            Canopy.FLAT);
    public static final Species MANGROVE = new Species(st(Blocks.MANGROVE_LOG), st(Blocks.MANGROVE_LEAVES), 6, 9, 3,
            Canopy.BLOB);
    public static final Species PALE_OAK = new Species(st(Blocks.PALE_OAK_LOG), st(Blocks.PALE_OAK_LEAVES), 6, 8, 3,
            Canopy.BLOB);

    public final BlockState top;
    public final BlockState filler;
    public final int depth;
    public final BlockState floor;
    public final int treeGrid;
    public final float treeChance;
    public final Species[] species;
    public final boolean snowy;
    public final boolean frozenWater;
    public final float grassChance;
    public final boolean badlands;

    private FarBiomeStyle(BlockState top, BlockState filler, int depth, BlockState floor, int treeGrid,
            float treeChance, Species[] species, boolean snowy, boolean frozenWater, float grassChance,
            boolean badlands) {
        this.top = top;
        this.filler = filler;
        this.depth = depth;
        this.floor = floor;
        this.treeGrid = treeGrid;
        this.treeChance = treeChance;
        this.species = species;
        this.snowy = snowy;
        this.frozenWater = frozenWater;
        this.grassChance = grassChance;
        this.badlands = badlands;
    }

    private static BlockState st(Block b) {
        return b.defaultBlockState();
    }

    private static final ConcurrentHashMap<String, FarBiomeStyle> CACHE = new ConcurrentHashMap<>();

    public static FarBiomeStyle of(Holder<Biome> biome) {
        String path = biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("plains");
        return CACHE.computeIfAbsent(path, FarBiomeStyle::create);
    }

    private static FarBiomeStyle grassy(int grid, float chance, Species[] sp, float grass) {
        return new FarBiomeStyle(st(Blocks.GRASS_BLOCK), st(Blocks.DIRT), 3, st(Blocks.SAND), grid, chance, sp,
                false, false, grass, false);
    }

    private static FarBiomeStyle create(String p) {
        Species[] none = new Species[0];
        BlockState sand = st(Blocks.SAND);
        BlockState gravel = st(Blocks.GRAVEL);
        BlockState stone = st(Blocks.STONE);
        BlockState snowBlock = st(Blocks.SNOW_BLOCK);
        BlockState dirt = st(Blocks.DIRT);
        if (p.contains("frozen_ocean") || p.equals("frozen_river")) {
            return new FarBiomeStyle(sand, sand, 3, gravel, 0, 0, none, true, true, 0, false);
        }
        if (p.contains("deep") && p.contains("ocean")) {
            return new FarBiomeStyle(sand, sand, 3, gravel, 0, 0, none, false, false, 0, false);
        }
        if (p.contains("ocean")) {
            BlockState floor = p.contains("warm") || p.contains("lukewarm") ? sand
                    : (p.contains("cold") ? gravel : sand);
            return new FarBiomeStyle(sand, sand, 3, floor, 0, 0, none, false, false, 0, false);
        }
        if (p.equals("river")) {
            return new FarBiomeStyle(sand, dirt, 3, sand, 0, 0, none, false, false, 0, false);
        }
        if (p.equals("snowy_beach")) {
            return new FarBiomeStyle(sand, st(Blocks.SANDSTONE), 3, sand, 0, 0, none, true, false, 0, false);
        }
        if (p.equals("beach")) {
            return new FarBiomeStyle(sand, st(Blocks.SANDSTONE), 3, sand, 0, 0, none, false, false, 0, false);
        }
        if (p.equals("stony_shore") || p.equals("stony_peaks")) {
            return new FarBiomeStyle(stone, stone, 3, gravel, 0, 0, none, false, false, 0, false);
        }
        if (p.equals("desert")) {
            return new FarBiomeStyle(sand, st(Blocks.SANDSTONE), 4, sand, 0, 0, none, false, false, 0, false);
        }
        if (p.contains("badlands")) {
            boolean wooded = p.contains("wooded");
            return new FarBiomeStyle(st(Blocks.RED_SAND), st(Blocks.TERRACOTTA), 4, st(Blocks.RED_SAND),
                    wooded ? 12 : 0, wooded ? 0.4f : 0, wooded ? new Species[] { OAK } : none, false, false, 0, true);
        }
        if (p.equals("mushroom_fields")) {
            return new FarBiomeStyle(st(Blocks.MYCELIUM), dirt, 3, gravel, 0, 0, none, false, false, 0, false);
        }
        if (p.equals("jagged_peaks") || p.equals("frozen_peaks") || p.equals("snowy_slopes")) {
            return new FarBiomeStyle(snowBlock, snowBlock, 3, gravel, 0, 0, none, true, true, 0, false);
        }
        if (p.equals("ice_spikes")) {
            return new FarBiomeStyle(snowBlock, dirt, 3, gravel, 0, 0, none, true, true, 0, false);
        }
        if (p.equals("grove")) {
            return new FarBiomeStyle(snowBlock, dirt, 3, gravel, 7, 0.6f, new Species[] { SPRUCE }, true, true, 0,
                    false);
        }
        if (p.equals("snowy_plains")) {
            return new FarBiomeStyle(st(Blocks.GRASS_BLOCK), dirt, 3, gravel, 30, 0.3f, new Species[] { SPRUCE }, true,
                    true, 0, false);
        }
        if (p.equals("snowy_taiga")) {
            return new FarBiomeStyle(st(Blocks.GRASS_BLOCK), dirt, 3, gravel, 6, 0.7f, new Species[] { SPRUCE }, true,
                    true, 0.1f, false);
        }
        if (p.contains("old_growth") && p.contains("taiga")) {
            return new FarBiomeStyle(st(Blocks.PODZOL), dirt, 3, gravel, 5, 0.8f, new Species[] { SPRUCE }, false,
                    false, 0.2f, false);
        }
        if (p.contains("taiga")) {
            return grassy(5, 0.75f, new Species[] { SPRUCE }, 0.25f);
        }
        if (p.equals("windswept_gravelly_hills")) {
            return new FarBiomeStyle(gravel, gravel, 3, gravel, 14, 0.3f, new Species[] { SPRUCE, OAK }, false, false,
                    0, false);
        }
        if (p.startsWith("windswept")) {
            if (p.contains("savanna")) {
                return grassy(14, 0.4f, new Species[] { ACACIA }, 0.4f);
            }
            return grassy(10, 0.45f, new Species[] { SPRUCE, OAK }, 0.25f);
        }
        if (p.contains("savanna")) {
            return grassy(12, 0.45f, new Species[] { ACACIA }, 0.6f);
        }
        if (p.equals("sparse_jungle")) {
            return grassy(8, 0.6f, new Species[] { JUNGLE, OAK }, 0.6f);
        }
        if (p.contains("jungle")) {
            return grassy(4, 0.8f, new Species[] { JUNGLE, JUNGLE, OAK }, 0.6f);
        }
        if (p.equals("dark_forest")) {
            return grassy(4, 0.85f, new Species[] { DARK_OAK, DARK_OAK, OAK }, 0.2f);
        }
        if (p.equals("pale_garden")) {
            return grassy(4, 0.8f, new Species[] { PALE_OAK }, 0.2f);
        }
        if (p.contains("birch")) {
            return grassy(5, 0.75f, new Species[] { BIRCH }, 0.4f);
        }
        if (p.equals("flower_forest")) {
            return grassy(6, 0.6f, new Species[] { OAK, BIRCH }, 0.5f);
        }
        if (p.equals("forest")) {
            return grassy(5, 0.75f, new Species[] { OAK, OAK, BIRCH }, 0.35f);
        }
        if (p.equals("cherry_grove")) {
            return grassy(7, 0.6f, new Species[] { CHERRY }, 0.5f);
        }
        if (p.equals("mangrove_swamp")) {
            return new FarBiomeStyle(st(Blocks.MUD), st(Blocks.MUD), 3, st(Blocks.MUD), 5, 0.7f,
                    new Species[] { MANGROVE }, false, false, 0.1f, false);
        }
        if (p.equals("swamp")) {
            return new FarBiomeStyle(st(Blocks.GRASS_BLOCK), dirt, 3, st(Blocks.CLAY), 8, 0.5f, new Species[] { OAK },
                    false, false, 0.3f, false);
        }
        if (p.equals("meadow")) {
            return grassy(32, 0.15f, new Species[] { OAK, BIRCH }, 0.6f);
        }
        if (p.contains("plains")) {
            return grassy(24, 0.25f, new Species[] { OAK }, 0.5f);
        }
        return grassy(16, 0.3f, new Species[] { OAK }, 0.35f);
    }
}
