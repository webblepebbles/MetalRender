package com.pebbles_boon.metalrender.render.far;

public final class FarKeys {
    public static final int TILE = 32;
    public static final int MAX_LEVEL = 6;

    private FarKeys() {
    }

    public static long key(int level, int tx, int tz) {
        return ((long) level << 56) | (((long) tx & 0xFFFFFFFL) << 28) | ((long) tz & 0xFFFFFFFL);
    }

    public static int level(long key) {
        return (int) (key >>> 56);
    }

    public static int tx(long key) {
        return (int) (((key >>> 28) & 0xFFFFFFFL) << 4) >> 4;
    }

    public static int tz(long key) {
        return (int) ((key & 0xFFFFFFFL) << 4) >> 4;
    }

    public static int size(int level) {
        return TILE << level;
    }

    public static int originX(long key) {
        return tx(key) * size(level(key));
    }

    public static int originZ(long key) {
        return tz(key) * size(level(key));
    }

    public static long parent(long key) {
        int l = level(key);
        return key(l + 1, tx(key) >> 1, tz(key) >> 1);
    }

    public static long child(long key, int i) {
        int l = level(key) - 1;
        return key(l, (tx(key) << 1) + (i & 1), (tz(key) << 1) + (i >> 1));
    }

    public static long containing(int level, int blockX, int blockZ) {
        int s = size(level);
        return key(level, Math.floorDiv(blockX, s), Math.floorDiv(blockZ, s));
    }

    public static long neighbor(long key, int dx, int dz) {
        return key(level(key), tx(key) + dx, tz(key) + dz);
    }

    public static boolean isAncestor(long ancestor, long key) {
        int la = level(ancestor);
        int lk = level(key);
        if (la <= lk) {
            return false;
        }
        int shift = la - lk;
        return (tx(key) >> shift) == tx(ancestor) && (tz(key) >> shift) == tz(ancestor);
    }

    public static long ancestorAt(long key, int level) {
        int shift = level - level(key);
        return key(level, tx(key) >> shift, tz(key) >> shift);
    }
}
