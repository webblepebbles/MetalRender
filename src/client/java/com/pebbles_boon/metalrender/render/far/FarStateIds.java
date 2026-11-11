package com.pebbles_boon.metalrender.render.far;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class FarStateIds {
    public static final int WATER = Block.BLOCK_STATE_REGISTRY.getId(Blocks.WATER.defaultBlockState());

    private FarStateIds() {
    }

    public static int id(BlockState state) {
        return Block.BLOCK_STATE_REGISTRY.getId(state);
    }

    private static volatile byte[] leaves;

    public static boolean isLeaf(int id) {
        byte[] l = leaves;
        if (l == null) {
            int n = Block.BLOCK_STATE_REGISTRY.size();
            l = new byte[n];
            for (int i = 0; i < n; i++) {
                BlockState st = Block.BLOCK_STATE_REGISTRY.byId(i);
                l[i] = (byte) (st != null && st.is(net.minecraft.tags.BlockTags.LEAVES) ? 1 : 0);
            }
            leaves = l;
        }
        return id >= 0 && id < l.length && l[id] != 0;
    }

    public static BlockState state(int id) {
        return Block.BLOCK_STATE_REGISTRY.byId(id);
    }

    public static long registryFingerprint() {
        long h = Block.BLOCK_STATE_REGISTRY.size();
        int n = Block.BLOCK_STATE_REGISTRY.size();
        int step = Math.max(1, n / 64);
        for (int i = 0; i < n; i += step) {
            BlockState st = Block.BLOCK_STATE_REGISTRY.byId(i);
            h = h * 1000003L + (st == null ? 0 : st.toString().hashCode());
        }
        return h;
    }
}
