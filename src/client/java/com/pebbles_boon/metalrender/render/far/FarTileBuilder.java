package com.pebbles_boon.metalrender.render.far;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import net.minecraft.world.level.block.state.BlockState;

public final class FarTileBuilder {
    public static final int COLS = 34;
    public static final int OFFSETS = COLS * COLS + 1;

    public static final class Blob {
        public ByteBuffer blob;
        public int blobUints;
        public ByteBuffer tints;
        public int tintCount;
        public int quadBound;
        public int yLo;
        public int yHi;
        public int missingNeighbors;
        public int roughness;
        public int flags;
        public int lookPal;
        public int plane;
    }

    private FarTileBuilder() {
    }

    public static Blob build(FarTileData tile, FarTileData[] neighbors, FarLooks looks, int minY, boolean compact,
            boolean waterPlane) {
        int level = tile.level;
        int[][] grid = new int[COLS * COLS][];
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                grid[(z + 1) * COLS + (x + 1)] = tile.column(z * 32 + x);
            }
        }
        int missing = 0;
        for (int i = 0; i < 4; i++) {
            FarTileData n = neighbors[i];
            if (n == null) {
                missing |= 1 << i;
                continue;
            }
            for (int k = 0; k < 32; k++) {
                int src;
                int dst;
                switch (i) {
                    case 0 -> {
                        src = k * 32 + 31;
                        dst = (k + 1) * COLS;
                    }
                    case 1 -> {
                        src = k * 32;
                        dst = (k + 1) * COLS + 33;
                    }
                    case 2 -> {
                        src = 31 * 32 + k;
                        dst = k + 1;
                    }
                    default -> {
                        src = k;
                        dst = 33 * COLS + k + 1;
                    }
                }
                grid[dst] = n.column(src);
            }
        }
        int total = 0;
        for (int[] c : grid) {
            if (c != null) {
                total += c.length / 3;
            }
        }
        int uints = OFFSETS + total * 2;
        ByteBuffer blob = ByteBuffer.allocateDirect(uints * 4).order(ByteOrder.nativeOrder());
        IntBuffer ib = blob.asIntBuffer();
        int[] palette = new int[256];
        int paletteSize = 0;
        java.util.HashMap<Integer, Integer> paletteIndex = new java.util.HashMap<>();
        int cursor = 0;
        int bound = 0;
        int unit = 1 << level;
        int yLo = Integer.MAX_VALUE;
        int yHi = Integer.MIN_VALUE;
        int[] lookCache = new int[64];
        int[] lookCacheKey = new int[64];
        java.util.Arrays.fill(lookCacheKey, -1);
        int[] spanPairs = new int[total * 2];
        int[] offsets = new int[OFFSETS];
        int[] colTop = new int[1024];
        for (int c = 0; c < COLS * COLS; c++) {
            offsets[c] = cursor;
            int[] col = grid[c];
            if (col == null) {
                continue;
            }
            boolean inner = c / COLS >= 1 && c / COLS <= 32 && c % COLS >= 1 && c % COLS <= 32;
            for (int k = 0; k < col.length; k += 3) {
                int lo = col[k];
                int stateId = col[k + 1];
                int slot = (stateId * 0x9E3779B1 >>> 26) & 63;
                int look;
                if (lookCacheKey[slot] == stateId) {
                    look = lookCache[slot];
                } else {
                    BlockState st = FarStateIds.state(stateId);
                    look = st == null ? 0 : looks.lookOf(st);
                    lookCacheKey[slot] = stateId;
                    lookCache[slot] = look;
                }
                if (look == 0) {
                    continue;
                }
                int header = looks.header(look);
                if (level > 0 && FarLooks.shape(header) == FarLooks.SHAPE_CROSS) {
                    continue;
                }
                int rgb = col[k + 2] & 0xFFFFFF;
                Integer pi = paletteIndex.get(rgb);
                int tintIdx;
                if (pi != null) {
                    tintIdx = pi;
                } else if (paletteSize < 256) {
                    tintIdx = paletteSize;
                    palette[paletteSize++] = rgb;
                    paletteIndex.put(rgb, tintIdx);
                } else {
                    tintIdx = nearest(palette, paletteSize, rgb);
                }
                int sky = FarTileData.sky(lo);
                int blk = FarTileData.block(lo);
                int gpuLo = (FarTileData.y0(lo) & 1023) | (((FarTileData.len(lo) - 1) & 1023) << 10)
                        | ((sky & 15) << 20) | ((blk & 15) << 24);
                spanPairs[cursor * 2] = gpuLo;
                spanPairs[cursor * 2 + 1] = (look & 0xFFFF) | ((tintIdx & 255) << 16);
                cursor++;
                if (inner) {
                    int ci = (c / COLS - 1) * 32 + (c % COLS - 1);
                    colTop[ci] = Math.max(colTop[ci], FarTileData.y0(lo) + FarTileData.len(lo));
                    int y0 = minY + FarTileData.y0(lo) * unit;
                    int y1 = minY + (FarTileData.y0(lo) + FarTileData.len(lo)) * unit;
                    yLo = Math.min(yLo, y0);
                    yHi = Math.max(yHi, y1);
                    bound += FarLooks.shape(header) == FarLooks.SHAPE_CROSS ? 4 : 2;
                }
            }
        }
        offsets[COLS * COLS] = cursor;
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                int c = (z + 1) * COLS + (x + 1);
                int own = offsets[c + 1] - offsets[c];
                if (own == 0) {
                    continue;
                }
                int[] nb = { c - 1, c + 1, c - COLS, c + COLS };
                for (int n : nb) {
                    bound += own + (offsets[n + 1] - offsets[n]);
                }
            }
        }
        int topMin = Integer.MAX_VALUE;
        int topMax = 0;
        for (int t : colTop) {
            topMin = Math.min(topMin, t);
            topMax = Math.max(topMax, t);
        }
        int flags = 0;
        int planeWord = 0;
        int planeLook = 0;
        int waterLook = looks.waterLook();
        if (waterPlane && waterLook != 0) {
            java.util.HashMap<Integer, Integer> votes = new java.util.HashMap<>();
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    int c = (z + 1) * COLS + (x + 1);
                    for (int j = offsets[c]; j < offsets[c + 1]; j++) {
                        if ((spanPairs[j * 2 + 1] & 0xFFFF) == waterLook) {
                            votes.merge(waterKey(spanPairs[j * 2], spanPairs[j * 2 + 1]), 1, Integer::sum);
                        }
                    }
                }
            }
            int best = 0;
            int bestN = 0;
            for (var e : votes.entrySet()) {
                if (e.getValue() > bestN) {
                    bestN = e.getValue();
                    best = e.getKey();
                }
            }
            int planeTop = best & 1023;
            boolean ok = bestN >= 1024;
            for (int z = 0; z < 32 && ok; z++) {
                for (int x = 0; x < 32 && ok; x++) {
                    int c = (z + 1) * COLS + (x + 1);
                    boolean wet = false;
                    for (int j = offsets[c]; j < offsets[c + 1]; j++) {
                        int lo = spanPairs[j * 2];
                        int hi = spanPairs[j * 2 + 1];
                        if ((hi & 0xFFFF) == waterLook && waterKey(lo, hi) == best) {
                            wet = true;
                        }
                    }
                    if (!wet || colTop[z * 32 + x] != planeTop) {
                        ok = false;
                    }
                }
            }
            if (ok) {
                for (int z = 0; z < 32; z++) {
                    for (int x = 0; x < 32; x++) {
                        int c = (z + 1) * COLS + (x + 1);
                        for (int j = offsets[c]; j < offsets[c + 1]; j++) {
                            if ((spanPairs[j * 2 + 1] & 0xFFFF) == waterLook
                                    && waterKey(spanPairs[j * 2], spanPairs[j * 2 + 1]) == best) {
                                spanPairs[j * 2 + 1] |= 1 << 24;
                            }
                        }
                    }
                }
                flags |= 4;
                planeWord = ((planeTop - 1) & 1023) | (((best >>> 18) & 255) << 10) | (((best >>> 10) & 255) << 18);
                planeLook = waterLook;
            }
        }
        int[] lookPalette = null;
        int lookCount = 0;
        if (compact) {
            java.util.HashMap<Integer, Integer> local = new java.util.HashMap<>();
            int[] pal = new int[256];
            boolean fits = true;
            int[] remap = new int[cursor];
            for (int j = 0; j < cursor && fits; j++) {
                int lk = spanPairs[j * 2 + 1] & 0xFFFF;
                Integer li = local.get(lk);
                if (li == null) {
                    if (local.size() >= 256) {
                        fits = false;
                        break;
                    }
                    li = local.size();
                    local.put(lk, li);
                    pal[li] = lk;
                }
                remap[j] = li;
            }
            if (fits && planeLook != 0 && !local.containsKey(planeLook)) {
                if (local.size() >= 256) {
                    fits = false;
                } else {
                    pal[local.size()] = planeLook;
                    local.put(planeLook, local.size());
                }
            }
            if (fits) {
                for (int j = 0; j < cursor; j++) {
                    spanPairs[j * 2 + 1] = (spanPairs[j * 2 + 1] & 0xFFFF0000) | remap[j];
                }
                if (planeLook != 0) {
                    planeLook = local.get(planeLook);
                }
                lookPalette = pal;
                lookCount = Math.max(1, local.size());
                flags |= 2;
            }
        }
        flags |= (planeLook & 0xFFFF) << 16;
        ib.put(offsets);
        ib.put(spanPairs, 0, cursor * 2);
        Blob out = new Blob();
        out.roughness = topMin == Integer.MAX_VALUE ? 0 : (topMax - topMin) * unit;
        out.flags = flags;
        out.plane = planeWord;
        out.blob = blob;
        out.blobUints = OFFSETS + cursor * 2;
        int tintSlots = Math.max(1, paletteSize);
        ByteBuffer tints = ByteBuffer.allocateDirect((tintSlots + lookCount) * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < paletteSize; i++) {
            int rgb = palette[i];
            int r = (rgb >> 16) & 255;
            int g = (rgb >> 8) & 255;
            int b = rgb & 255;
            tints.putInt(r | (g << 8) | (b << 16) | (255 << 24));
        }
        if (paletteSize == 0) {
            tints.putInt(0xFFFFFFFF);
        }
        for (int i = 0; i < lookCount; i++) {
            tints.putInt(lookPalette[i]);
        }
        tints.flip();
        out.tints = tints;
        out.tintCount = tintSlots + lookCount;
        out.lookPal = tintSlots;
        out.quadBound = Math.max(32, bound);
        out.yLo = yLo == Integer.MAX_VALUE ? minY : yLo;
        out.yHi = yHi == Integer.MIN_VALUE ? minY + 1 : yHi;
        out.missingNeighbors = missing;
        return out;
    }

    private static int waterKey(int lo, int hi) {
        int top = (lo & 1023) + ((lo >>> 10) & 1023) + 1;
        int light = ((lo >>> 24) & 15) | (((lo >>> 20) & 15) << 4);
        return (top & 1023) | (((hi >>> 16) & 255) << 10) | (light << 18);
    }

    private static int nearest(int[] palette, int n, int rgb) {
        int best = 0;
        int bestD = Integer.MAX_VALUE;
        int r = (rgb >> 16) & 255;
        int g = (rgb >> 8) & 255;
        int b = rgb & 255;
        for (int i = 0; i < n; i++) {
            int p = palette[i];
            int dr = ((p >> 16) & 255) - r;
            int dg = ((p >> 8) & 255) - g;
            int db = (p & 255) - b;
            int d = dr * dr + dg * dg + db * db;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }
}
