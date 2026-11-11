package com.pebbles_boon.metalrender.render.far;

public final class FarTileData {
    public static final int N = FarKeys.TILE;
    public static final int COLS = N * N;
    public static final byte MISSING = 0;
    public static final byte SEED = 1;
    public static final byte REAL = 2;
    public static final int[] EMPTY = new int[0];

    public final long key;
    public final int level;
    public final int[][] cols = new int[COLS][];
    public final byte[] state = new byte[COLS];
    public long fingerprint;
    public volatile boolean dirty;
    public volatile long version;

    public FarTileData(long key) {
        this.key = key;
        this.level = FarKeys.level(key);
        java.util.Arrays.fill(cols, EMPTY);
    }

    public static int lo(int y0, int len, int sky, int block) {
        return (y0 & 1023) | (((len - 1) & 1023) << 10) | ((sky & 15) << 20) | ((block & 15) << 24);
    }

    public static int y0(int lo) {
        return lo & 1023;
    }

    public static int len(int lo) {
        return ((lo >>> 10) & 1023) + 1;
    }

    public static int sky(int lo) {
        return (lo >>> 20) & 15;
    }

    public static int block(int lo) {
        return (lo >>> 24) & 15;
    }

    public synchronized void set(int index, int[] spans, byte kind) {
        cols[index] = spans == null ? EMPTY : spans;
        state[index] = kind;
        dirty = true;
        version++;
    }

    public synchronized int[] column(int index) {
        return cols[index];
    }

    public synchronized byte kind(int index) {
        return state[index];
    }

    public synchronized boolean hasMissing() {
        for (byte b : state) {
            if (b == MISSING) {
                return true;
            }
        }
        return false;
    }

    public synchronized boolean hasReal() {
        for (byte b : state) {
            if (b == REAL) {
                return true;
            }
        }
        return false;
    }

    public synchronized long bytes() {
        long total = 64 + COLS * 20L;
        for (int[] c : cols) {
            total += c.length * 4L;
        }
        return total;
    }
}
