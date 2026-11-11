package com.pebbles_boon.metalrender.render.far.seed;

import com.pebbles_boon.metalrender.render.far.FarIngest;
import com.pebbles_boon.metalrender.render.far.FarKeys;
import com.pebbles_boon.metalrender.render.far.FarLooks;
import com.pebbles_boon.metalrender.render.far.FarStateIds;
import com.pebbles_boon.metalrender.render.far.FarTileData;
import com.pebbles_boon.metalrender.render.far.FarTints;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.SnowyBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class SeedColumns {
    private static final int GROUND_GRASS = 1;

    private static final class Spans {
        int[] y0 = new int[32];
        int[] len = new int[32];
        BlockState[] state = new BlockState[32];
        int[] sky = new int[32];
        int n;

        void clear() {
            n = 0;
        }

        void add(int a, int l, BlockState s, int skyLight) {
            if (l <= 0 || s == null) {
                return;
            }
            if (n == y0.length) {
                y0 = Arrays.copyOf(y0, n * 2);
                len = Arrays.copyOf(len, n * 2);
                state = Arrays.copyOf(state, n * 2);
                sky = Arrays.copyOf(sky, n * 2);
            }
            y0[n] = a;
            len[n] = l;
            state[n] = s;
            sky[n] = skyLight;
            n++;
        }
    }

    private static final ThreadLocal<Spans> SPANS = ThreadLocal.withInitial(Spans::new);
    private static final ThreadLocal<int[][]> CELLS = ThreadLocal.withInitial(() -> new int[4][1024]);
    private static final ThreadLocal<BlockState[]> CELL_STATES = ThreadLocal.withInitial(() -> new BlockState[1024]);

    private SeedColumns() {
    }

    public static void fill(FarTileData tile, FarSeedSource src, FarLooks looks) {
        int level = tile.level;
        int w = 1 << level;
        int size = FarKeys.size(level);
        int ox = FarKeys.originX(tile.key);
        int oz = FarKeys.originZ(tile.key);
        int[] surface = new int[FarTileData.COLS];
        @SuppressWarnings("unchecked")
        Holder<Biome>[] biomes = new Holder[FarTileData.COLS];
        FarBiomeStyle[] styles = new FarBiomeStyle[FarTileData.COLS];
        boolean any = false;
        for (int i = 0; i < FarTileData.COLS; i++) {
            if (tile.kind(i) == FarTileData.MISSING) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }
        if (level <= 2) {
            int g = 4 << level;
            int n = size / g + 1;
            int[] gs = new int[n * n];
            @SuppressWarnings("unchecked")
            Holder<Biome>[] gb = new Holder[n * n];
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    int bx = ox + i * g;
                    int bz = oz + j * g;
                    int sv = solve(src, bx, bz, 0);
                    gs[j * n + i] = sv;
                    gb[j * n + i] = src.biome(bx, Math.max(sv, src.seaLevel), bz);
                }
            }
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    int i = z * 32 + x;
                    float fx = (x * w + (w >> 1)) / (float) g;
                    float fz = (z * w + (w >> 1)) / (float) g;
                    int ix = Math.min(n - 2, (int) fx);
                    int iz = Math.min(n - 2, (int) fz);
                    float tx = fx - ix;
                    float tz = fz - iz;
                    float h = gs[iz * n + ix] * (1 - tx) * (1 - tz) + gs[iz * n + ix + 1] * tx * (1 - tz)
                            + gs[(iz + 1) * n + ix] * (1 - tx) * tz + gs[(iz + 1) * n + ix + 1] * tx * tz;
                    surface[i] = Math.round(h);
                    Holder<Biome> b = gb[Math.round(fz) * n + Math.round(fx)];
                    biomes[i] = b;
                    styles[i] = FarBiomeStyle.of(b);
                }
            }
        } else {
            int[] half = new int[17 * 17];
            for (int j = 0; j < 17; j++) {
                for (int k = 0; k < 17; k++) {
                    half[j * 17 + k] = solve(src, ox + k * 2 * w + (w >> 1), oz + j * 2 * w + (w >> 1), level);
                }
            }
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    int i = z * 32 + x;
                    int bx = ox + x * w + (w >> 1);
                    int bz = oz + z * w + (w >> 1);
                    int hx = x >> 1;
                    int hz = z >> 1;
                    int sv;
                    if ((x & 1) == 0 && (z & 1) == 0) {
                        sv = half[hz * 17 + hx];
                    } else if ((z & 1) == 0) {
                        sv = (half[hz * 17 + hx] + half[hz * 17 + hx + 1] + 1) >> 1;
                    } else if ((x & 1) == 0) {
                        sv = (half[hz * 17 + hx] + half[(hz + 1) * 17 + hx] + 1) >> 1;
                    } else {
                        sv = (half[hz * 17 + hx] + half[hz * 17 + hx + 1] + half[(hz + 1) * 17 + hx]
                                + half[(hz + 1) * 17 + hx + 1] + 2) >> 2;
                    }
                    surface[i] = sv;
                    Holder<Biome> b = src.biome(bx, Math.max(sv, src.seaLevel), bz);
                    biomes[i] = b;
                    styles[i] = FarBiomeStyle.of(b);
                }
            }
        }
        int[] canopyLo = new int[FarTileData.COLS];
        int[] canopyHi = new int[FarTileData.COLS];
        BlockState[] canopyState = new BlockState[FarTileData.COLS];
        int[] trunkLo = new int[FarTileData.COLS];
        int[] trunkHi = new int[FarTileData.COLS];
        BlockState[] trunkState = new BlockState[FarTileData.COLS];
        Arrays.fill(canopyLo, Integer.MAX_VALUE);
        Arrays.fill(canopyHi, Integer.MIN_VALUE);
        Arrays.fill(trunkLo, Integer.MAX_VALUE);
        Arrays.fill(trunkHi, Integer.MIN_VALUE);
        if (level <= 2) {
            placeTrees(src, ox, oz, size, w, surface, styles, canopyLo, canopyHi, canopyState, trunkLo, trunkHi,
                    trunkState, level);
        } else {
            forestMass(src, ox, oz, w, surface, styles, canopyLo, canopyHi, canopyState);
        }
        int[][] structY = new int[FarTileData.COLS][];
        BlockState[][] structS = new BlockState[FarTileData.COLS][];
        FarStructures fs = src.structures;
        if (fs != null && level <= FarStructures.MAX_LEVEL) {
            int[] counts = new int[FarTileData.COLS];
            List<FarStructures.Placed> placed = fs.collect(ox, oz, size);
            for (FarStructures.Placed p : placed) {
                for (int k = 0; k < p.x.length; k++) {
                    int lx = Math.floorDiv(p.x[k] - ox, w);
                    int lz = Math.floorDiv(p.z[k] - oz, w);
                    if (lx >= 0 && lx < 32 && lz >= 0 && lz < 32) {
                        counts[lz * 32 + lx]++;
                    }
                }
            }
            for (int i = 0; i < FarTileData.COLS; i++) {
                if (counts[i] > 0) {
                    structY[i] = new int[counts[i]];
                    structS[i] = new BlockState[counts[i]];
                    counts[i] = 0;
                }
            }
            for (FarStructures.Placed p : placed) {
                for (int k = 0; k < p.x.length; k++) {
                    int lx = Math.floorDiv(p.x[k] - ox, w);
                    int lz = Math.floorDiv(p.z[k] - oz, w);
                    if (lx < 0 || lx >= 32 || lz < 0 || lz >= 32) {
                        continue;
                    }
                    int i = lz * 32 + lx;
                    if (surface[i] < src.seaLevel) {
                        continue;
                    }
                    BlockState st = p.state[k];
                    int kind = looks.kindOf(st);
                    if (kind == FarLooks.KIND_SKIP || (kind == FarLooks.KIND_SOLID && looks.isSmallDecoration(st))) {
                        continue;
                    }
                    if (kind == FarLooks.KIND_WATER) {
                        st = Blocks.WATER.defaultBlockState();
                    }
                    int y = p.terrain[k] ? surface[i] + p.y[k] : p.y[k];
                    if (FarLooks.shape(looks.header(looks.lookOf(st))) == FarLooks.SHAPE_CROSS
                            && !FarIngest.keepCross(p.x[k], y, p.z[k])) {
                        continue;
                    }
                    structY[i][counts[i]] = y;
                    structS[i][counts[i]] = st;
                    counts[i]++;
                }
            }
            for (int i = 0; i < FarTileData.COLS; i++) {
                if (structY[i] != null) {
                    structY[i] = Arrays.copyOf(structY[i], counts[i]);
                    structS[i] = Arrays.copyOf(structS[i], counts[i]);
                    if (counts[i] > 0) {
                        canopyState[i] = null;
                        trunkState[i] = null;
                    }
                }
            }
        }
        FarTintView view = new FarTintView(src.minY, src.height);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                int i = z * 32 + x;
                if (tile.kind(i) != FarTileData.MISSING) {
                    continue;
                }
                int bx = ox + x * w + (w >> 1);
                int bz = oz + z * w + (w >> 1);
                int[] spans = column(src, looks, view, pos, bx, bz, surface[i], biomes[i], styles[i], level,
                        canopyLo[i], canopyHi[i], canopyState[i], trunkLo[i], trunkHi[i], trunkState[i], structY[i],
                        structS[i]);
                tile.set(i, spans, FarTileData.SEED);
            }
        }
    }

    private static int solve(FarSeedSource src, int x, int z, int level) {
        int lo = src.minY + 1;
        int hi = src.minY + src.height - 2;
        int est = Math.max(lo, Math.min(hi, src.surfaceEstimate(x, z)));
        if (level >= 3) {
            return est;
        }
        int step = Math.max(4, Math.min(16, 1 << level));
        int prec = Math.max(1, (1 << level) >> 1);
        int y = Math.min(hi, est + 8);
        int solid;
        int air;
        if (src.density(x, y, z) > 0) {
            solid = y;
            air = y + step;
            int guard = 0;
            while (air <= hi && src.density(x, air, z) > 0 && guard++ < 12) {
                solid = air;
                air += step;
            }
            if (air > hi) {
                return hi;
            }
        } else {
            air = y;
            solid = y - step;
            int guard = 0;
            while (solid > lo && src.density(x, solid, z) <= 0 && guard++ < 24) {
                air = solid;
                solid -= step;
            }
            if (solid <= lo) {
                return Math.max(lo, est);
            }
        }
        while (air - solid > prec) {
            int mid = (air + solid) >>> 1;
            if (src.density(x, mid, z) > 0) {
                solid = mid;
            } else {
                air = mid;
            }
        }
        return solid;
    }

    private static long hash(long a, long b, long salt) {
        long h = a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return h;
    }

    private static float unit(long h) {
        return (h >>> 40) / (float) (1L << 24);
    }

    private static boolean treeGround(FarBiomeStyle style) {
        BlockState t = style.top;
        return t.is(Blocks.GRASS_BLOCK) || t.is(Blocks.PODZOL) || t.is(Blocks.MUD) || t.is(Blocks.DIRT)
                || t.is(Blocks.SNOW_BLOCK) || t.is(Blocks.COARSE_DIRT) || t.is(Blocks.RED_SAND);
    }

    private static int radiusAt(FarBiomeStyle.Species sp, int rel, int h) {
        int r = sp.radius();
        switch (sp.canopy()) {
            case BLOB -> {
                if (rel < h - 2 || rel > h + 1) {
                    return -1;
                }
                return rel <= h - 1 ? r : (rel == h ? Math.max(1, r - 1) : 1);
            }
            case CONE -> {
                if (rel < 2 || rel > h + 1) {
                    return -1;
                }
                float t = (rel - 2) / (float) Math.max(1, h - 1);
                return Math.max(0, Math.round(r * (1.0f - t)));
            }
            case FLAT -> {
                if (rel < h - 1 || rel > h) {
                    return -1;
                }
                return rel == h - 1 ? r : Math.max(1, r - 1);
            }
            default -> {
                if (rel < h - 3 || rel > h + 1) {
                    return -1;
                }
                return rel == h + 1 ? Math.max(1, r - 1) : r;
            }
        }
    }

    private static void placeTrees(FarSeedSource src, int ox, int oz, int size, int w, int[] surface,
            FarBiomeStyle[] styles, int[] canopyLo, int[] canopyHi, BlockState[] canopyState, int[] trunkLo,
            int[] trunkHi, BlockState[] trunkState, int level) {
        int reach = 4;
        int minGrid = Integer.MAX_VALUE;
        for (FarBiomeStyle s : styles) {
            if (s.treeGrid > 0) {
                minGrid = Math.min(minGrid, s.treeGrid);
            }
        }
        if (minGrid == Integer.MAX_VALUE) {
            return;
        }
        for (int gridSize = minGrid; gridSize <= 32; gridSize++) {
            boolean used = false;
            for (FarBiomeStyle s : styles) {
                if (s.treeGrid == gridSize) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                continue;
            }
            int g = gridSize;
            int gx0 = Math.floorDiv(ox - reach, g);
            int gx1 = Math.floorDiv(ox + size + reach, g);
            int gz0 = Math.floorDiv(oz - reach, g);
            int gz1 = Math.floorDiv(oz + size + reach, g);
            for (int gz = gz0; gz <= gz1; gz++) {
                for (int gx = gx0; gx <= gx1; gx++) {
                    long h = hash(gx, gz, g);
                    int tx = gx * g + (int) ((h >>> 8) & 0xFFFF) % g;
                    int tz = gz * g + (int) ((h >>> 24) & 0xFFFF) % g;
                    int cx = Math.max(0, Math.min(31, Math.floorDiv(tx - ox, w)));
                    int cz = Math.max(0, Math.min(31, Math.floorDiv(tz - oz, w)));
                    int ci = cz * 32 + cx;
                    FarBiomeStyle style = styles[ci];
                    if (style.treeGrid != g || style.species.length == 0 || unit(h) > style.treeChance) {
                        continue;
                    }
                    if (!treeGround(style) || surface[ci] < src.seaLevel) {
                        continue;
                    }
                    FarBiomeStyle.Species sp = style.species[(int) ((h >>> 48) & 0xFF) % style.species.length];
                    int th = sp.minHeight() + (int) ((h >>> 4) & 0xF) % (sp.maxHeight() - sp.minHeight() + 1);
                    int base = surface[ci];
                    int r = sp.radius();
                    for (int dz = -r - 1; dz <= r + 1; dz++) {
                        for (int dx = -r - 1; dx <= r + 1; dx++) {
                            int bx = tx + dx;
                            int bz = tz + dz;
                            int lx = Math.floorDiv(bx - ox, w);
                            int lz = Math.floorDiv(bz - oz, w);
                            if (lx < 0 || lx > 31 || lz < 0 || lz > 31) {
                                continue;
                            }
                            if (w > 1) {
                                int centerX = ox + lx * w + (w >> 1);
                                int centerZ = oz + lz * w + (w >> 1);
                                if (bx != centerX || bz != centerZ) {
                                    continue;
                                }
                            }
                            int i = lz * 32 + lx;
                            if (dx == 0 && dz == 0 && level == 0) {
                                trunkLo[i] = Math.min(trunkLo[i], base + 1);
                                trunkHi[i] = Math.max(trunkHi[i], base + th);
                                trunkState[i] = sp.log();
                            }
                            int d2 = dx * dx + dz * dz;
                            for (int rel = 0; rel <= th + 1; rel++) {
                                int rad = radiusAt(sp, rel, th);
                                if (rad < 0 || d2 > rad * rad + (rad > 1 ? 1 : 0)) {
                                    continue;
                                }
                                int y = base + rel;
                                if (dx == 0 && dz == 0 && level == 0 && rel <= th) {
                                    continue;
                                }
                                canopyLo[i] = Math.min(canopyLo[i], y);
                                canopyHi[i] = Math.max(canopyHi[i], y);
                                canopyState[i] = sp.leaves();
                            }
                        }
                    }
                }
            }
        }
    }

    private static void forestMass(FarSeedSource src, int ox, int oz, int w, int[] surface, FarBiomeStyle[] styles,
            int[] canopyLo, int[] canopyHi, BlockState[] canopyState) {
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                int i = z * 32 + x;
                FarBiomeStyle s = styles[i];
                if (s.treeGrid <= 0 || s.species.length == 0 || !treeGround(s) || surface[i] < src.seaLevel) {
                    continue;
                }
                FarBiomeStyle.Species sp = s.species[0];
                float area = (float) Math.PI * sp.radius() * sp.radius();
                float coverage = Math.min(1.0f, s.treeChance * area / (s.treeGrid * (float) s.treeGrid));
                int bx = ox + x * w;
                int bz = oz + z * w;
                long h = hash(bx, bz, 77);
                boolean keep = coverage >= 0.45f || unit(h) < coverage * 1.4f;
                if (!keep) {
                    continue;
                }
                int avg = (sp.minHeight() + sp.maxHeight()) / 2;
                canopyLo[i] = surface[i] + 1;
                canopyHi[i] = surface[i] + avg + 1;
                FarBiomeStyle.Species pick = s.species[(int) ((h >>> 50) & 0xFF) % s.species.length];
                canopyState[i] = pick.leaves();
            }
        }
    }

    private static int[] column(FarSeedSource src, FarLooks looks, FarTintView view, BlockPos.MutableBlockPos pos,
            int bx, int bz, int surface, Holder<Biome> biome, FarBiomeStyle style, int level, int cLo, int cHi,
            BlockState cState, int tLo, int tHi, BlockState tState, int[] sY, BlockState[] sS) {
        boolean built = sY != null && sY.length > 0;
        Spans sp = SPANS.get();
        sp.clear();
        int minY = src.minY;
        int sea = src.seaLevel;
        int s = surface - minY;
        boolean under = surface < sea - 1;
        BlockState top = style.top;
        BlockState filler = style.filler;
        if (under) {
            top = style.floor;
            filler = style.floor;
        } else if (style.badlands && surface > sea + 10) {
            int band = Math.floorMod(surface / 3, 5);
            top = switch (band) {
                case 0 -> Blocks.TERRACOTTA.defaultBlockState();
                case 1 -> Blocks.DYED_TERRACOTTA.pick(DyeColor.ORANGE).defaultBlockState();
                case 2 -> Blocks.DYED_TERRACOTTA.pick(DyeColor.YELLOW).defaultBlockState();
                case 3 -> Blocks.DYED_TERRACOTTA.pick(DyeColor.BROWN).defaultBlockState();
                default -> Blocks.DYED_TERRACOTTA.pick(DyeColor.RED).defaultBlockState();
            };
            filler = Blocks.TERRACOTTA.defaultBlockState();
        }
        boolean snowy = false;
        if (!under) {
            try {
                snowy = style.snowy || biome.value().coldEnoughToSnow(new BlockPos(bx, surface + 1, bz), sea);
            } catch (RuntimeException ignored) {
                snowy = style.snowy;
            }
        }
        boolean grassTop = top.is(Blocks.GRASS_BLOCK) || top.is(Blocks.PODZOL) || top.is(Blocks.MYCELIUM);
        if (snowy && grassTop) {
            if (level == 0) {
                top = top.hasProperty(SnowyBlock.SNOWY) ? top.setValue(SnowyBlock.SNOWY, true) : top;
            } else {
                top = Blocks.SNOW_BLOCK.defaultBlockState();
            }
        }
        int depth = style.depth;
        int stoneTop = s - depth;
        int floorSky = under ? Math.max(0, 15 - (sea - 1 - surface)) : 15;
        if (stoneTop > 0) {
            sp.add(0, stoneTop, Blocks.STONE.defaultBlockState(), floorSky);
        }
        int fillStart = Math.max(0, stoneTop);
        sp.add(fillStart, s - fillStart, filler, floorSky);
        sp.add(s, 1, top, floorSky);
        if (under) {
            int waterTop = sea - 1 - minY;
            boolean frozen = style.frozenWater;
            if (!frozen) {
                try {
                    frozen = biome.value().coldEnoughToSnow(new BlockPos(bx, sea, bz), sea);
                } catch (RuntimeException ignored) {
                }
            }
            if (frozen) {
                sp.add(s + 1, waterTop - s - 1, Blocks.WATER.defaultBlockState(), 15);
                sp.add(waterTop, 1, Blocks.ICE.defaultBlockState(), 15);
            } else {
                sp.add(s + 1, waterTop - s, Blocks.WATER.defaultBlockState(), 15);
            }
        } else {
            int above = s + 1;
            if (tState != null && tHi >= tLo) {
                sp.add(tLo - minY, tHi - tLo + 1, tState, 15);
                above = Math.max(above, tHi - minY + 1);
            }
            if (cState != null && cHi >= cLo) {
                int lo = cLo - minY;
                int hi = cHi - minY;
                if (tState != null && tHi >= tLo) {
                    lo = Math.max(lo, tHi - minY + 1);
                }
                if (hi >= lo) {
                    sp.add(lo, hi - lo + 1, cState, 15);
                }
            } else if (level == 0 && tState == null && !built) {
                if (snowy && grassTop) {
                    sp.add(above, 1, Blocks.SNOW.defaultBlockState(), 15);
                } else if (top.is(Blocks.GRASS_BLOCK) && unit(hash(bx, bz, GROUND_GRASS)) < style.grassChance
                        && FarIngest.keepCross(bx, surface + 1, bz)) {
                    sp.add(above, 1, Blocks.SHORT_GRASS.defaultBlockState(), 15);
                }
            }
        }
        if (built) {
            for (int k = 0; k < sY.length; k++) {
                int yy = sY[k] - minY;
                if (yy >= 0 && yy < src.height) {
                    sp.add(yy, 1, sS[k], 15);
                }
            }
        }
        return quantize(sp, level, looks, view, pos, bx, bz, minY, biome);
    }

    private static int[] quantize(Spans sp, int level, FarLooks looks, FarTintView view, BlockPos.MutableBlockPos pos,
            int bx, int bz, int minY, Holder<Biome> biome) {
        int u = FarKeys.vunit(level);
        int maxCell = 0;
        for (int i = 0; i < sp.n; i++) {
            maxCell = Math.max(maxCell, (sp.y0[i] + sp.len[i] + u - 1) / u);
        }
        maxCell = Math.min(maxCell, 1023);
        int[][] cells = CELLS.get();
        int[] top = cells[0];
        int[] sky = cells[1];
        BlockState[] st = CELL_STATES.get();
        int[] cov = cells[2];
        int[] leafy = cells[3];
        Arrays.fill(top, 0, maxCell, -1);
        Arrays.fill(st, 0, maxCell, null);
        Arrays.fill(cov, 0, maxCell, 0);
        Arrays.fill(leafy, 0, maxCell, 0);
        for (int i = 0; i < sp.n; i++) {
            int a = sp.y0[i];
            int b = a + sp.len[i];
            if (level > 0 && looks.header(looks.lookOf(sp.state[i])) != 0
                    && FarLooks.shape(looks.header(looks.lookOf(sp.state[i]))) == FarLooks.SHAPE_CROSS) {
                continue;
            }
            boolean leaf = sp.state[i].is(net.minecraft.tags.BlockTags.LEAVES);
            for (int c = a / u; c < Math.min(maxCell, (b + u - 1) / u); c++) {
                int overlap = Math.min(b, (c + 1) * u) - Math.max(a, c * u);
                cov[c] += overlap;
                if (leaf) {
                    leafy[c] += overlap;
                }
                if (b >= top[c]) {
                    top[c] = b;
                    st[c] = sp.state[i];
                    sky[c] = sp.sky[i];
                }
            }
        }
        if (u > 1) {
            for (int k = 0; k < maxCell; k++) {
                if (st[k] != null) {
                    boolean leafTop = st[k].is(net.minecraft.tags.BlockTags.LEAVES);
                    if (leafTop ? leafy[k] * 4 < u : cov[k] * 2 < u) {
                        st[k] = null;
                    }
                }
            }
        }
        int[] out = new int[maxCell * 3];
        int n = 0;
        int c = 0;
        while (c < maxCell) {
            if (st[c] == null) {
                c++;
                continue;
            }
            BlockState s = st[c];
            int start = c;
            int last = c;
            while (c < maxCell && st[c] == s) {
                last = c;
                c++;
            }
            int len = c - start;
            int look = looks.lookOf(s);
            int ti = looks.tintIndexOf(look);
            int tint = FarTints.WHITE;
            if (ti >= 0) {
                pos.set(bx, minY + last * u, bz);
                tint = FarTints.color(s, ti, view.at(biome, s), pos);
            }
            int stateId = s.is(Blocks.WATER) ? FarStateIds.WATER : FarStateIds.id(s);
            out[n * 3] = FarTileData.lo(start, len, sky[last], 0);
            out[n * 3 + 1] = stateId;
            out[n * 3 + 2] = tint;
            n++;
        }
        return Arrays.copyOf(out, n * 3);
    }
}
