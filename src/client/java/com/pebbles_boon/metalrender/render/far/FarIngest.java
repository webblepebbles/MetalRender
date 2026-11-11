package com.pebbles_boon.metalrender.render.far;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightEventListener;

public final class FarIngest {
    private static final class Scratch {
        int[] ids = new int[0];
        byte[] kind = new byte[0];
        boolean[] occl = new boolean[0];
        int[] lookCache = new int[0];
        byte[] kindCache = new byte[0];
        FarLooks looks;
        int[] runs = new int[1024 * 3];
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private FarIngest() {
    }

    public static boolean keepCross(int x, int y, int z) {
        int h = (x * 73856093) ^ (y * 19349663) ^ (z * 83492791);
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return Math.floorMod(h, 3) == 0;
    }

    public static int[][] ingest(LevelChunk chunk, ClientLevel level, FarLooks looks) {
        int height = level.getHeight();
        int minY = level.getMinY();
        int cells = 256 * height;
        Scratch s = SCRATCH.get();
        if (s.ids.length < cells) {
            s.ids = new int[cells];
            s.kind = new byte[cells];
            s.occl = new boolean[cells];
        }
        int registry = Block.BLOCK_STATE_REGISTRY.size();
        if (s.looks != looks || s.lookCache.length < registry) {
            s.lookCache = new int[registry];
            s.kindCache = new byte[registry];
            s.looks = looks;
        }
        int[] ids = s.ids;
        byte[] kind = s.kind;
        boolean[] occl = s.occl;
        java.util.Arrays.fill(kind, 0, cells, (byte) 0);
        java.util.Arrays.fill(occl, 0, cells, false);
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        LevelChunkSection[] sections = chunk.getSections();
        int[] topY = new int[256];
        java.util.Arrays.fill(topY, -1);
        for (int si = 0; si < sections.length; si++) {
            LevelChunkSection sec = sections[si];
            if (sec == null || sec.hasOnlyAir()) {
                continue;
            }
            int y0 = si * 16;
            for (int ly = 0; ly < 16; ly++) {
                int y = y0 + ly;
                if (y >= height) {
                    break;
                }
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        BlockState st = sec.getBlockState(lx, ly, lz);
                        if (st.isAir()) {
                            continue;
                        }
                        int id = Block.BLOCK_STATE_REGISTRY.getId(st);
                        if (id < 0 || id >= s.lookCache.length) {
                            continue;
                        }
                        int k = s.kindCache[id];
                        if (k == 0) {
                            int kv = looks.kindOf(st);
                            if (kv == FarLooks.KIND_SOLID && looks.isSmallDecoration(st)) {
                                kv = FarLooks.KIND_SKIP;
                            }
                            k = kv + 1;
                            s.kindCache[id] = (byte) k;
                            s.lookCache[id] = kv == FarLooks.KIND_SKIP ? 0
                                    : (kv == FarLooks.KIND_WATER ? looks.waterLook() : looks.lookOf(st));
                        }
                        int kv = k - 1;
                        if (kv == FarLooks.KIND_SKIP) {
                            continue;
                        }
                        int look = s.lookCache[id];
                        int header = looks.header(look);
                        if (FarLooks.shape(header) == FarLooks.SHAPE_CROSS
                                && !keepCross(baseX + lx, minY + y, baseZ + lz)) {
                            continue;
                        }
                        int c = (y * 16 + lz) * 16 + lx;
                        ids[c] = kv == FarLooks.KIND_WATER ? -1 : id;
                        kind[c] = (byte) kv;
                        occl[c] = kv == FarLooks.KIND_SOLID && FarLooks.occluder(header);
                        int col = lz * 16 + lx;
                        if (y > topY[col]) {
                            topY[col] = y;
                        }
                    }
                }
            }
        }
        LayerLightEventListener skyL = level.getLightEngine().getLayerListener(LightLayer.SKY);
        LayerLightEventListener blockL = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        int secBase = chunk.getMinSectionY();
        DataLayer[] skyLayers = new DataLayer[sections.length];
        DataLayer[] blockLayers = new DataLayer[sections.length];
        int cx = chunk.getPos().x();
        int cz = chunk.getPos().z();
        for (int si = 0; si < sections.length; si++) {
            try {
                skyLayers[si] = skyL.getDataLayerData(SectionPos.of(cx, secBase + si, cz));
                blockLayers[si] = blockL.getDataLayerData(SectionPos.of(cx, secBase + si, cz));
            } catch (RuntimeException ignored) {
            }
        }
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int col = lz * 16 + lx;
                int top = topY[col];
                int lastSolid = -2;
                for (int y = 0; y < top; y++) {
                    int c = (y * 16 + lz) * 16 + lx;
                    if (kind[c] == FarLooks.KIND_SOLID) {
                        lastSolid = ids[c];
                        continue;
                    }
                    if (kind[c] != FarLooks.KIND_SKIP || lastSolid == -2) {
                        continue;
                    }
                    if (light(skyLayers, y, lx, lz, 15) == 0 && light(blockLayers, y, lx, lz, 0) == 0) {
                        ids[c] = lastSolid;
                        kind[c] = FarLooks.KIND_SOLID;
                        occl[c] = true;
                    }
                }
            }
        }
        int[][] out = new int[256][];
        int[] runs = s.runs;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int col = lz * 16 + lx;
                int top = topY[col];
                if (top < 0) {
                    out[col] = FarTileData.EMPTY;
                    continue;
                }
                int prevId = Integer.MIN_VALUE;
                boolean prevOccl = false;
                for (int y = top; y >= 0; y--) {
                    int c = (y * 16 + lz) * 16 + lx;
                    if (!occl[c]) {
                        prevOccl = false;
                        continue;
                    }
                    boolean exposed = y == top || y == 0 || lx == 0 || lx == 15 || lz == 0 || lz == 15
                            || !occl[c + 256] || !occl[c - 256] || !occl[c - 1] || !occl[c + 1]
                            || !occl[c - 16] || !occl[c + 16];
                    if (!exposed && prevOccl) {
                        ids[c] = prevId;
                    }
                    prevId = ids[c];
                    prevOccl = true;
                }
                int n = 0;
                int y = 0;
                while (y <= top) {
                    int c = (y * 16 + lz) * 16 + lx;
                    if (kind[c] == FarLooks.KIND_SKIP) {
                        y++;
                        continue;
                    }
                    int id = ids[c];
                    byte k = kind[c];
                    int start = y;
                    while (y <= top) {
                        int cc = (y * 16 + lz) * 16 + lx;
                        if (kind[cc] != k || ids[cc] != id) {
                            break;
                        }
                        y++;
                    }
                    int len = y - start;
                    int above = y;
                    int sky = above >= height ? 15 : light(skyLayers, above, lx, lz, 15);
                    int blk = above >= height ? 0 : light(blockLayers, above, lx, lz, 0);
                    int topCell = y - 1;
                    int midCell = (start + topCell) >> 1;
                    for (int probe = 0; probe < 2 && sky < 15; probe++) {
                        int py = probe == 0 ? topCell : midCell;
                        if (lx > 0) {
                            sky = Math.max(sky, sideLight(kind, skyLayers, py, lx - 1, lz));
                        }
                        if (lx < 15) {
                            sky = Math.max(sky, sideLight(kind, skyLayers, py, lx + 1, lz));
                        }
                        if (lz > 0) {
                            sky = Math.max(sky, sideLight(kind, skyLayers, py, lx, lz - 1));
                        }
                        if (lz < 15) {
                            sky = Math.max(sky, sideLight(kind, skyLayers, py, lx, lz + 1));
                        }
                    }
                    if (n * 3 + 3 > runs.length) {
                        runs = java.util.Arrays.copyOf(runs, runs.length * 2);
                        s.runs = runs;
                    }
                    int lookState = id;
                    int look = k == FarLooks.KIND_WATER ? looks.waterLook()
                            : (id >= 0 && id < s.lookCache.length ? s.lookCache[id] : 0);
                    BlockState st = looks.stateOf(look);
                    int tint = FarTints.WHITE;
                    int ti = looks.tintIndexOf(look);
                    if (ti >= 0 && st != null) {
                        tint = FarTints.color(st, ti, level,
                                new BlockPos(baseX + lx, minY + start + len - 1, baseZ + lz));
                    }
                    runs[n * 3] = FarTileData.lo(start, len, sky, blk);
                    runs[n * 3 + 1] = k == FarLooks.KIND_WATER ? FarStateIds.WATER : lookState;
                    runs[n * 3 + 2] = tint;
                    n++;
                }
                out[col] = java.util.Arrays.copyOf(runs, n * 3);
            }
        }
        return out;
    }

    private static int sideLight(byte[] kind, DataLayer[] layers, int y, int lx, int lz) {
        int c = (y * 16 + lz) * 16 + lx;
        if (kind[c] == FarLooks.KIND_SOLID) {
            return 0;
        }
        return light(layers, y, lx, lz, 15);
    }

    private static int light(DataLayer[] layers, int y, int lx, int lz, int fallback) {
        int si = y >> 4;
        if (si < 0 || si >= layers.length) {
            return fallback;
        }
        DataLayer d = layers[si];
        if (d == null) {
            return fallback;
        }
        return d.get(lx, y & 15, lz);
    }
}
