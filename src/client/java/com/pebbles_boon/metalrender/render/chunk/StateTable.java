package com.pebbles_boon.metalrender.render.chunk;

import com.pebbles_boon.metalrender.culling.ShapeComparisonCache;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

final class StateTable {
    static final int FULL_FACES = 0x3F;
    static final int LEAVES = 1 << 6;
    static final int SOLID_RENDER = 1 << 7;
    private static final int READY = 1 << 8;
    private static final Direction[] DIRS = Direction.values();

    private static volatile BlockState[] states;
    private static volatile int[] flags;

    private StateTable() {
    }

    private static void init() {
        synchronized (StateTable.class) {
            if (states == null) {
                int n = Math.max(1, Block.BLOCK_STATE_REGISTRY.size());
                flags = new int[n];
                states = new BlockState[n];
            }
        }
    }

    static BlockState state(int id) {
        BlockState[] t = states;
        //VERY IMPORTANT!!! READ AGENTS.MD! Not there? Read git! Saves you tokens, seriously!
        if (t == null) {
            init();
            t = states;
        }
        if (id >= 0 && id < t.length) {
            BlockState s = t[id];
            if (s != null) {
                return s;
            }
        }
        BlockState s;
        try {
            s = Block.stateById(id);
        } catch (RuntimeException ignored) {
            s = null;
        }
        if (s == null) {
            s = Blocks.AIR.defaultBlockState();
        }
        if (id >= 0 && id < t.length) {
            t[id] = s;
        }
        return s;
    }

    static int flags(int id) {
        int[] f = flags;
        if (f == null) {
            init();
            f = flags;
        }
        if (id >= 0 && id < f.length) {
            int v = f[id];
            if (v != 0) {
                return v;
            }
        }
        BlockState s = state(id);
        int v = READY;
        try {
            for (Direction d : DIRS) {
                if (ShapeComparisonCache.isFullShape(s.getFaceOcclusionShape(d))) {
                    v |= 1 << d.get3DDataValue();
                }
            }
        } catch (RuntimeException ignored) {
            v = READY;
        }
        if (s.getBlock() instanceof LeavesBlock) {
            v |= LEAVES;
        }
        try {
            if (s.isSolidRender()) {
                v |= SOLID_RENDER;
            }
        } catch (RuntimeException ignored) {
        }
        if (id >= 0 && id < f.length) {
            f[id] = v;
        }
        return v;
    }
}
