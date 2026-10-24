package com.pebbles_boon.metalrender.render.chunk;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

public final class QuadTiers {
    public static final int UNIT_BYTES = 16;
    public static final int SECTION_BYTES = 32;
    public static final int MAX_UNIT_QUADS = 32;
    public static final int MAX_UNIT_SPAN = 4;
    public static final int TIER_A = 0;
    public static final int TIER_B = 1;
    public static final int TIER_C = 2;
    public static final int TIER_A_BYTES = 16;
    public static final int TIER_B_BYTES = 32;
    public static final int TIER_C_BYTES = 64;
    public static final int VERTEX_STRIDE = 16;
    public static final int BOX_OFFSET = 8;
    public static final int SPRITE_BITS = 13;

    private static final int[][] FACE_VERTS = {
            { 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0, 1 },
            { 0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0 },
            { 1, 1, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0 },
            { 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1 },
            { 0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1 },
            { 1, 1, 1, 1, 0, 1, 1, 0, 0, 1, 1, 0 },
    };
    private static final int[][] UV_PAT = { { 0, 0 }, { 0, 1 }, { 1, 1 }, { 1, 0 } };

    private static final float COLOR_TOLERANCE = 1.5f;
    private static final int B_ORIGIN = 0;
    private static final int B_EDGE1 = 36;
    private static final int B_EDGE2 = 75;
    private static final int B_SPRITE = 114;
    private static final int B_UVCODE = 127;
    private static final int B_LEAF = 130;
    private static final int B_UV = 131;
    private static final int B_TINT = 167;
    private static final int B_LIGHT = 191;
    private static final int B_SHADE = 223;
    private static final int B_POS_BIAS = 1024;

    public static final LongAdder COUNT_A = new LongAdder();
    public static final LongAdder COUNT_B = new LongAdder();
    public static final LongAdder COUNT_C = new LongAdder();
    public static final LongAdder VERIFY_BAD = new LongAdder();
    private static final java.util.concurrent.ConcurrentHashMap<String, LongAdder> REASONS = new java.util.concurrent.ConcurrentHashMap<>();

    private static volatile SpriteTable spriteTable;

    private QuadTiers() {
    }

    public static void setSpriteTable(SpriteTable table) {
        spriteTable = table;
    }

    public static SpriteTable spriteTable() {
        return spriteTable;
    }

    public static java.util.Map<String, Long> reasonSnapshot() {
        java.util.TreeMap<String, Long> out = new java.util.TreeMap<>();
        for (var e : REASONS.entrySet()) {
            out.put(e.getKey(), e.getValue().sum());
        }
        return out;
    }

    private static void reason(String why) {
        REASONS.computeIfAbsent(why, k -> new LongAdder()).increment();
    }

    public static final class SpriteTable {
        public final int count;
        final int[] u0;
        final int[] v0;
        final int[] u1;
        final int[] v1;
        public boolean[] solid;
        private final Long2IntOpenHashMap byMin = new Long2IntOpenHashMap();
        private static final int GRID = 64;
        private static final int[] NEIGHBORS = { 0, 1, -1, 2, -2 };
        private final int[][] cells = new int[GRID * GRID][];

        public SpriteTable(int[] u0, int[] v0, int[] u1, int[] v1) {
            this.count = u0.length;
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
            this.solid = new boolean[u0.length];
            byMin.defaultReturnValue(-1);
            int[] fill = new int[GRID * GRID];
            for (int i = 0; i < count; i++) {
                byMin.put(minKey(u0[i], v0[i]), i);
                forEachCell(i, c -> fill[c]++);
            }
            for (int c = 0; c < cells.length; c++) {
                cells[c] = new int[fill[c]];
                fill[c] = 0;
            }
            for (int i = 0; i < count; i++) {
                final int sprite = i;
                forEachCell(sprite, c -> cells[c][fill[c]++] = sprite);
            }
        }

        private void forEachCell(int sprite, java.util.function.IntConsumer sink) {
            int cx0 = Math.min(GRID - 1, u0[sprite] * GRID / 65536);
            int cx1 = Math.min(GRID - 1, u1[sprite] * GRID / 65536);
            int cy0 = Math.min(GRID - 1, v0[sprite] * GRID / 65536);
            int cy1 = Math.min(GRID - 1, v1[sprite] * GRID / 65536);
            for (int y = cy0; y <= cy1; y++) {
                for (int x = cx0; x <= cx1; x++) {
                    sink.accept(y * GRID + x);
                }
            }
        }

        private static long minKey(int u, int v) {
            return ((long) u << 20) | (long) v;
        }

        public static SpriteTable fromAtlas(List<net.minecraft.client.renderer.texture.TextureAtlasSprite> sprites) {
            int n = sprites.size();
            int[] a = new int[n];
            int[] b = new int[n];
            int[] c = new int[n];
            int[] d = new int[n];
            for (int i = 0; i < n; i++) {
                var s = sprites.get(i);
                a[i] = quant(s.getU0());
                b[i] = quant(s.getV0());
                c[i] = quant(s.getU1());
                d[i] = quant(s.getV1());
            }
            SpriteTable table = new SpriteTable(a, b, c, d);
            for (int i = 0; i < n; i++) {
                table.solid[i] = sprites.get(i).transparency().isOpaque();
            }
            return table;
        }

        public ByteBuffer toNative() {
            ByteBuffer out = ByteBuffer.allocateDirect(Math.max(1, count) * 8).order(ByteOrder.nativeOrder());
            for (int i = 0; i < count; i++) {
                out.putInt(u0[i] | (v0[i] << 16));
                out.putInt(u1[i] | (v1[i] << 16));
            }
            out.flip();
            return out;
        }

        int findWhole(int uMin, int vMin, int uMax, int vMax) {
            for (int du : NEIGHBORS) {
                for (int dv : NEIGHBORS) {
                    int idx = byMin.get(minKey(uMin + du, vMin + dv));
                    if (idx >= 0 && Math.abs(u1[idx] - uMax) <= 2 && Math.abs(v1[idx] - vMax) <= 2) {
                        return idx;
                    }
                }
            }
            return -1;
        }

        int findContaining(int uMin, int vMin, int uMax, int vMax) {
            int cu = (uMin + uMax) >> 1;
            int cv = (vMin + vMax) >> 1;
            int gx = Math.min(GRID - 1, cu * GRID / 65536);
            int gy = Math.min(GRID - 1, cv * GRID / 65536);
            int[] list = cells[gy * GRID + gx];
            for (int idx : list) {
                if (uMin >= u0[idx] - 2 && uMax <= u1[idx] + 2 && vMin >= v0[idx] - 2 && vMax <= v1[idx] + 2) {
                    return idx;
                }
            }
            return -1;
        }
    }

    public static final int WATER_ALPHA = 168;

    public static int quant(float f) {
        return ((int) (f * 65535f)) & 0xFFFF;
    }

    private static final class Quad {
        final int[] px = new int[4];
        final int[] py = new int[4];
        final int[] pz = new int[4];
        final int[] u = new int[4];
        final int[] v = new int[4];
        final int[] r = new int[4];
        final int[] g = new int[4];
        final int[] b = new int[4];
        final int[] a = new int[4];
        final int[] light = new int[4];
        final int[] n = new int[4];
        int minX, maxX, minY, maxY, minZ, maxZ;
        int uMin, uMax, vMin, vMax;
        boolean tiled;

        void load(ByteBuffer src, int base) {
            for (int i = 0; i < 4; i++) {
                long w0 = src.getLong(base + i * VERTEX_STRIDE);
                long w1 = src.getLong(base + i * VERTEX_STRIDE + 8);
                px[i] = (short) (w0 & 0xFFFF);
                py[i] = (short) ((w0 >> 16) & 0xFFFF);
                pz[i] = (short) ((w0 >> 32) & 0xFFFF);
                u[i] = (int) ((w0 >>> 48) & 0xFFFF);
                v[i] = (int) (w1 & 0xFFFF);
                r[i] = (int) ((w1 >>> 16) & 0xFF);
                g[i] = (int) ((w1 >>> 24) & 0xFF);
                b[i] = (int) ((w1 >>> 32) & 0xFF);
                a[i] = (int) ((w1 >>> 40) & 0xFF);
                light[i] = (int) ((w1 >>> 48) & 0xFF);
                n[i] = (int) ((w1 >>> 56) & 0xFF);
            }
            minX = Math.min(Math.min(px[0], px[1]), Math.min(px[2], px[3]));
            maxX = Math.max(Math.max(px[0], px[1]), Math.max(px[2], px[3]));
            minY = Math.min(Math.min(py[0], py[1]), Math.min(py[2], py[3]));
            maxY = Math.max(Math.max(py[0], py[1]), Math.max(py[2], py[3]));
            minZ = Math.min(Math.min(pz[0], pz[1]), Math.min(pz[2], pz[3]));
            maxZ = Math.max(Math.max(pz[0], pz[1]), Math.max(pz[2], pz[3]));
            uMin = Math.min(Math.min(u[0], u[1]), Math.min(u[2], u[3]));
            uMax = Math.max(Math.max(u[0], u[1]), Math.max(u[2], u[3]));
            vMin = Math.min(Math.min(v[0], v[1]), Math.min(v[2], v[3]));
            vMax = Math.max(Math.max(v[0], v[1]), Math.max(v[2], v[3]));
        }
    }

    private static final class Color {
        int tintR, tintG, tintB;
        final int[] shade = new int[4];
        boolean leaf;
    }

    private static boolean deriveColor(Quad q, Color out) {
        int a0 = q.a[0];
        if ((a0 != 0xFF && a0 != 0xFE) || q.a[1] != a0 || q.a[2] != a0 || q.a[3] != a0) {
            reason("alpha");
            return false;
        }
        out.leaf = a0 == 0xFE;
        boolean gray = true;
        for (int i = 0; i < 4; i++) {
            if (q.r[i] != q.g[i] || q.g[i] != q.b[i]) {
                gray = false;
            }
        }
        if (gray) {
            out.tintR = 255;
            out.tintG = 255;
            out.tintB = 255;
            for (int i = 0; i < 4; i++) {
                out.shade[i] = q.r[i];
            }
            return true;
        }
        int ref = 0;
        int refMax = -1;
        for (int i = 0; i < 4; i++) {
            int m = Math.max(q.r[i], Math.max(q.g[i], q.b[i]));
            if (m > refMax) {
                refMax = m;
                ref = i;
            }
        }
        out.tintR = q.r[ref];
        out.tintG = q.g[ref];
        out.tintB = q.b[ref];
        int tm = Math.max(out.tintR, Math.max(out.tintG, out.tintB));
        if (tm == 0) {
            reason("color");
            return false;
        }
        for (int i = 0; i < 4; i++) {
            int m = Math.max(q.r[i], Math.max(q.g[i], q.b[i]));
            int base = Math.min(255, Math.round(255f * m / tm));
            int best = base;
            float bestErr = Float.MAX_VALUE;
            for (int cand = Math.max(0, base - 1); cand <= Math.min(255, base + 1); cand++) {
                float err = Math.max(Math.abs(out.tintR * cand / 255f - q.r[i]),
                        Math.max(Math.abs(out.tintG * cand / 255f - q.g[i]),
                                Math.abs(out.tintB * cand / 255f - q.b[i])));
                if (err < bestErr) {
                    bestErr = err;
                    best = cand;
                }
            }
            if (bestErr > COLOR_TOLERANCE) {
                reason("color");
                return false;
            }
            out.shade[i] = best;
        }
        return true;
    }

    private static int uvCode(Quad q, int uLo, int uHi, int vLo, int vHi, int tol) {
        for (int code = 0; code < 8; code++) {
            int rot = code & 3;
            boolean flip = (code & 4) != 0;
            boolean ok = true;
            for (int j = 0; j < 4 && ok; j++) {
                int p = flip ? ((rot - j) & 3) : ((j + rot) & 3);
                int eu = UV_PAT[p][0] == 0 ? uLo : uHi;
                int ev = UV_PAT[p][1] == 0 ? vLo : vHi;
                if (Math.abs(q.u[j] - eu) > tol || Math.abs(q.v[j] - ev) > tol) {
                    ok = false;
                }
            }
            if (ok) {
                return code;
            }
        }
        return -1;
    }

    private static void setBits(int[] w, int off, int n, int value) {
        int lo = off >> 5;
        int sh = off & 31;
        long mask = n >= 32 ? 0xFFFFFFFFL : ((1L << n) - 1);
        long v = value & mask;
        w[lo] |= (int) (v << sh);
        if (sh + n > 32) {
            w[lo + 1] |= (int) (v >>> (32 - sh));
        }
    }

    private static int getBits(int[] w, int off, int n) {
        int lo = off >> 5;
        int sh = off & 31;
        long v = (w[lo] & 0xFFFFFFFFL) >>> sh;
        if (sh + n > 32) {
            v |= (w[lo + 1] & 0xFFFFFFFFL) << (32 - sh);
        }
        long mask = n >= 32 ? 0xFFFFFFFFL : ((1L << n) - 1);
        return (int) (v & mask);
    }

    private static int signExtend(int v, int bits) {
        int shift = 32 - bits;
        return (v << shift) >> shift;
    }

    private static boolean tryA(Quad q, SpriteTable sprites, Color color, int[] out) {
        int normal = q.n[0];
        if (normal < 0 || normal > 5 || q.n[1] != normal || q.n[2] != normal || q.n[3] != normal) {
            reason("a_normal");
            return false;
        }
        int bx;
        int by;
        int bz;
        int overlay = 0;
        if (normal < 2) {
            if (q.minY != q.maxY || q.maxX - q.minX != 256 || q.maxZ - q.minZ != 256) {
                reason("a_size");
                return false;
            }
            if ((q.minX & 255) != 0 || (q.minZ & 255) != 0 || (q.minY & 255) != 0) {
                reason("a_grid");
                return false;
            }
            bx = q.minX >> 8;
            bz = q.minZ >> 8;
            by = normal == 1 ? (q.minY >> 8) - 1 : (q.minY >> 8);
        } else if (normal >= 4) {
            if (q.minX != q.maxX || q.maxY - q.minY != 256 || q.maxZ - q.minZ != 256) {
                reason("a_size");
                return false;
            }
            if ((q.minY & 255) != 0 || (q.minZ & 255) != 0) {
                reason("a_grid");
                return false;
            }
            int mod = q.minX & 255;
            int base = q.minX;
            if (mod == 0) {
                overlay = 0;
            } else if (mod == 1 && normal == 5) {
                overlay = 1;
                base = q.minX - 1;
            } else if (mod == 255 && normal == 4) {
                overlay = 1;
                base = q.minX + 1;
            } else {
                reason("a_grid");
                return false;
            }
            bx = normal == 5 ? (base >> 8) - 1 : (base >> 8);
            by = q.minY >> 8;
            bz = q.minZ >> 8;
        } else {
            if (q.minZ != q.maxZ || q.maxX - q.minX != 256 || q.maxY - q.minY != 256) {
                reason("a_size");
                return false;
            }
            if ((q.minX & 255) != 0 || (q.minY & 255) != 0) {
                reason("a_grid");
                return false;
            }
            int mod = q.minZ & 255;
            int base = q.minZ;
            if (mod == 0) {
                overlay = 0;
            } else if (mod == 1 && normal == 3) {
                overlay = 1;
                base = q.minZ - 1;
            } else if (mod == 255 && normal == 2) {
                overlay = 1;
                base = q.minZ + 1;
            } else {
                reason("a_grid");
                return false;
            }
            bz = normal == 3 ? (base >> 8) - 1 : (base >> 8);
            bx = q.minX >> 8;
            by = q.minY >> 8;
        }
        if (bx < 0 || bx > 15 || by < 0 || by > 15 || bz < 0 || bz > 15) {
            reason("a_bounds");
            return false;
        }
        int[] pat = FACE_VERTS[normal];
        for (int j = 0; j < 4; j++) {
            int ex = pat[j * 3] == 0 ? q.minX : q.maxX;
            int ey = pat[j * 3 + 1] == 0 ? q.minY : q.maxY;
            int ez = pat[j * 3 + 2] == 0 ? q.minZ : q.maxZ;
            if (q.px[j] != ex || q.py[j] != ey || q.pz[j] != ez) {
                reason("a_order");
                return false;
            }
        }
        if (sprites == null) {
            reason("a_nosprites");
            return false;
        }
        int sprite = sprites.findWhole(q.uMin, q.vMin, q.uMax, q.vMax);
        if (sprite >= (1 << SPRITE_BITS)) {
            reason("a_sprite_index");
            return false;
        }
        if (sprite < 0) {
            reason("a_uv_not_whole");
            return false;
        }
        int code = uvCode(q, sprites.u0[sprite], sprites.u1[sprite], sprites.v0[sprite], sprites.v1[sprite], 3);
        if (code < 0) {
            reason("a_uv_order");
            return false;
        }
        if (!deriveColor(q, color)) {
            return false;
        }
        int w0 = bx | (by << 4) | (bz << 8) | (normal << 12) | ((color.leaf ? 1 : 0) << 15) | (overlay << 16)
                | (code << 17) | ((sprite & 0xFFF) << 20);
        int w1 = color.tintR | (color.tintG << 8) | (color.tintB << 16) | (((sprite >> 12) & 1) << 24);
        int w2 = q.light[0] | (q.light[1] << 8) | (q.light[2] << 16) | (q.light[3] << 24);
        int w3 = color.shade[0] | (color.shade[1] << 8) | (color.shade[2] << 16) | (color.shade[3] << 24);
        out[0] = w0;
        out[1] = w1;
        out[2] = w2;
        out[3] = w3;
        return true;
    }

    private static boolean tryB(Quad q, SpriteTable sprites, Color color, int[] out) {
        for (int i = 0; i < 4; i++) {
            if (((q.px[i] | q.py[i] | q.pz[i]) & 1) != 0) {
                reason("b_precision");
                return false;
            }
        }
        if (q.px[0] + q.px[2] != q.px[1] + q.px[3] || q.py[0] + q.py[2] != q.py[1] + q.py[3]
                || q.pz[0] + q.pz[2] != q.pz[1] + q.pz[3]) {
            reason("b_parallelogram");
            return false;
        }
        int ox = (q.px[0] >> 1) + B_POS_BIAS;
        int oy = (q.py[0] >> 1) + B_POS_BIAS;
        int oz = (q.pz[0] >> 1) + B_POS_BIAS;
        if (((ox | oy | oz) & ~4095) != 0) {
            reason("b_range");
            return false;
        }
        int e1x = (q.px[1] - q.px[0]) >> 1;
        int e1y = (q.py[1] - q.py[0]) >> 1;
        int e1z = (q.pz[1] - q.pz[0]) >> 1;
        int e2x = (q.px[3] - q.px[0]) >> 1;
        int e2y = (q.py[3] - q.py[0]) >> 1;
        int e2z = (q.pz[3] - q.pz[0]) >> 1;
        q.tiled = Math.abs(e1x) + Math.abs(e1y) + Math.abs(e1z) > 192
                || Math.abs(e2x) + Math.abs(e2y) + Math.abs(e2z) > 192;
        int[] edges = { e1x, e1y, e1z, e2x, e2y, e2z };
        for (int e : edges) {
            if (e < -4096 || e > 4095) {
                reason("b_edge");
                return false;
            }
        }
        if (sprites == null) {
            reason("b_nosprites");
            return false;
        }
        if (q.uMin == q.uMax || q.vMin == q.vMax) {
            reason("b_uv_degenerate");
            return false;
        }
        int sprite = sprites.findContaining(q.uMin, q.vMin, q.uMax, q.vMax);
        if (sprite < 0 || sprite >= (1 << SPRITE_BITS)) {
            reason("b_sprite");
            return false;
        }
        int su0 = sprites.u0[sprite];
        int sv0 = sprites.v0[sprite];
        int su1 = sprites.u1[sprite];
        int sv1 = sprites.v1[sprite];
        if (su1 <= su0 || sv1 <= sv0) {
            reason("b_sprite");
            return false;
        }
        int fu0 = fraction(q.uMin, su0, su1);
        int fu1 = fraction(q.uMax, su0, su1);
        int fv0 = fraction(q.vMin, sv0, sv1);
        int fv1 = fraction(q.vMax, sv0, sv1);
        int ru0 = restore(fu0, su0, su1);
        int ru1 = restore(fu1, su0, su1);
        int rv0 = restore(fv0, sv0, sv1);
        int rv1 = restore(fv1, sv0, sv1);
        if (Math.abs(ru0 - q.uMin) > 3 || Math.abs(ru1 - q.uMax) > 3 || Math.abs(rv0 - q.vMin) > 3
                || Math.abs(rv1 - q.vMax) > 3) {
            reason("b_uv_grid");
            return false;
        }
        int code = uvCode(q, ru0, ru1, rv0, rv1, 3);
        if (code < 0) {
            reason("b_uv_order");
            return false;
        }
        if (!deriveColor(q, color)) {
            return false;
        }
        Arrays.fill(out, 0, 8, 0);
        setBits(out, B_ORIGIN, 12, ox);
        setBits(out, B_ORIGIN + 12, 12, oy);
        setBits(out, B_ORIGIN + 24, 12, oz);
        for (int i = 0; i < 3; i++) {
            setBits(out, B_EDGE1 + i * 13, 13, edges[i] & 0x1FFF);
            setBits(out, B_EDGE2 + i * 13, 13, edges[3 + i] & 0x1FFF);
        }
        setBits(out, B_SPRITE, 13, sprite);
        setBits(out, B_UVCODE, 3, code);
        setBits(out, B_LEAF, 1, color.leaf ? 1 : 0);
        setBits(out, B_UV, 9, fu0);
        setBits(out, B_UV + 9, 9, fv0);
        setBits(out, B_UV + 18, 9, fu1);
        setBits(out, B_UV + 27, 9, fv1);
        setBits(out, B_TINT, 8, color.tintR);
        setBits(out, B_TINT + 8, 8, color.tintG);
        setBits(out, B_TINT + 16, 8, color.tintB);
        for (int i = 0; i < 4; i++) {
            setBits(out, B_LIGHT + i * 8, 8, q.light[i]);
            setBits(out, B_SHADE + i * 8, 8, color.shade[i]);
        }
        return true;
    }


    private static boolean closeColor(Quad q, int j, int tr, int tg, int tb, int shade) {
        return Math.abs(tr * shade / 255f - q.r[j]) <= COLOR_TOLERANCE + 0.01f
                && Math.abs(tg * shade / 255f - q.g[j]) <= COLOR_TOLERANCE + 0.01f
                && Math.abs(tb * shade / 255f - q.b[j]) <= COLOR_TOLERANCE + 0.01f;
    }

    private static boolean verifyA(Quad q, int[] w, SpriteTable sp) {
        int bx = w[0] & 15;
        int by = (w[0] >> 4) & 15;
        int bz = (w[0] >> 8) & 15;
        int face = (w[0] >> 12) & 7;
        boolean leaf = ((w[0] >> 15) & 1) != 0;
        int overlay = (w[0] >> 16) & 1;
        int code = (w[0] >> 17) & 7;
        int sprite = ((w[0] >>> 20) & 0xFFF) | (((w[1] >> 24) & 1) << 12);
        if (face > 5 || sprite >= sp.count) {
            return false;
        }
        int sx = bx * 256;
        int sy = by * 256;
        int sz = bz * 256;
        int ex = sx + 256;
        int ey = sy + 256;
        int ez = sz + 256;
        int plane;
        int[] xs = { sx, ex };
        int[] ys = { sy, ey };
        int[] zs = { sz, ez };
        if (face < 2) {
            plane = face == 1 ? ey : sy;
            ys[0] = plane;
            ys[1] = plane;
        } else if (face >= 4) {
            plane = (face == 5 ? ex : sx) + (overlay != 0 ? (face == 5 ? 1 : -1) : 0);
            xs[0] = plane;
            xs[1] = plane;
        } else {
            plane = (face == 3 ? ez : sz) + (overlay != 0 ? (face == 3 ? 1 : -1) : 0);
            zs[0] = plane;
            zs[1] = plane;
        }
        int[] pat = FACE_VERTS[face];
        int rot = code & 3;
        boolean flip = (code & 4) != 0;
        int tr = w[1] & 255;
        int tg = (w[1] >> 8) & 255;
        int tb = (w[1] >> 16) & 255;
        for (int j = 0; j < 4; j++) {
            if (xs[pat[j * 3]] != q.px[j] || ys[pat[j * 3 + 1]] != q.py[j] || zs[pat[j * 3 + 2]] != q.pz[j]) {
                return false;
            }
            int p = flip ? ((rot - j) & 3) : ((j + rot) & 3);
            int u = UV_PAT[p][0] == 0 ? sp.u0[sprite] : sp.u1[sprite];
            int v = UV_PAT[p][1] == 0 ? sp.v0[sprite] : sp.v1[sprite];
            if (Math.abs(u - q.u[j]) > 3 || Math.abs(v - q.v[j]) > 3) {
                return false;
            }
            int light = (w[2] >>> (8 * j)) & 255;
            int shade = (w[3] >>> (8 * j)) & 255;
            if (light != q.light[j] || !closeColor(q, j, tr, tg, tb, shade)) {
                return false;
            }
            if (q.a[j] != (leaf ? 254 : 255) || q.n[j] != face) {
                return false;
            }
        }
        return true;
    }

    private static boolean verifyB(Quad q, int[] w, SpriteTable sp) {
        int ox = getBits(w, B_ORIGIN, 12) - B_POS_BIAS;
        int oy = getBits(w, B_ORIGIN + 12, 12) - B_POS_BIAS;
        int oz = getBits(w, B_ORIGIN + 24, 12) - B_POS_BIAS;
        int[] e = new int[6];
        for (int i = 0; i < 3; i++) {
            e[i] = signExtend(getBits(w, B_EDGE1 + i * 13, 13), 13);
            e[3 + i] = signExtend(getBits(w, B_EDGE2 + i * 13, 13), 13);
        }
        int sprite = getBits(w, B_SPRITE, 13);
        int code = getBits(w, B_UVCODE, 3);
        boolean leaf = getBits(w, B_LEAF, 1) != 0;
        if (sprite >= sp.count) {
            return false;
        }
        int fu0 = getBits(w, B_UV, 9);
        int fv0 = getBits(w, B_UV + 9, 9);
        int fu1 = getBits(w, B_UV + 18, 9);
        int fv1 = getBits(w, B_UV + 27, 9);
        int u0 = restore(fu0, sp.u0[sprite], sp.u1[sprite]);
        int u1 = restore(fu1, sp.u0[sprite], sp.u1[sprite]);
        int v0 = restore(fv0, sp.v0[sprite], sp.v1[sprite]);
        int v1 = restore(fv1, sp.v0[sprite], sp.v1[sprite]);
        int tr = getBits(w, B_TINT, 8);
        int tg = getBits(w, B_TINT + 8, 8);
        int tb = getBits(w, B_TINT + 16, 8);
        int rot = code & 3;
        boolean flip = (code & 4) != 0;
        int[] cx = { ox, ox + e[0], ox + e[0] + e[3], ox + e[3] };
        int[] cy = { oy, oy + e[1], oy + e[1] + e[4], oy + e[4] };
        int[] cz = { oz, oz + e[2], oz + e[2] + e[5], oz + e[5] };
        for (int j = 0; j < 4; j++) {
            if (cx[j] * 2 != q.px[j] || cy[j] * 2 != q.py[j] || cz[j] * 2 != q.pz[j]) {
                return false;
            }
            int p = flip ? ((rot - j) & 3) : ((j + rot) & 3);
            int u = UV_PAT[p][0] == 0 ? u0 : u1;
            int v = UV_PAT[p][1] == 0 ? v0 : v1;
            if (Math.abs(u - q.u[j]) > 3 || Math.abs(v - q.v[j]) > 3) {
                return false;
            }
            int light = getBits(w, B_LIGHT + j * 8, 8);
            int shade = getBits(w, B_SHADE + j * 8, 8);
            if (light != q.light[j] || !closeColor(q, j, tr, tg, tb, shade)) {
                return false;
            }
            if (q.a[j] != (leaf ? 254 : 255)) {
                return false;
            }
        }
        return true;
    }

    private static int fraction(int value, int lo, int hi) {
        int f = Math.round((value - lo) * 256f / (hi - lo));
        return Math.max(0, Math.min(256, f));
    }

    private static int restore(int f, int lo, int hi) {
        return lo + Math.round((hi - lo) * (f / 256f));
    }

    public static final class Section {
        public ByteBuffer quads = ByteBuffer.allocateDirect(1 << 16).order(ByteOrder.nativeOrder());
        public ByteBuffer units = ByteBuffer.allocateDirect(1 << 12).order(ByteOrder.nativeOrder());
        public int quadBytes;
        public int unitCount;
        public int tierA;
        public int tierB;
        public int tierC;
        ByteBuffer[] tmp = {
                ByteBuffer.allocateDirect(1 << 14).order(ByteOrder.nativeOrder()),
                ByteBuffer.allocateDirect(1 << 14).order(ByteOrder.nativeOrder()),
                ByteBuffer.allocateDirect(1 << 15).order(ByteOrder.nativeOrder()) };
        int[] qTier = new int[1024];
        int[] qTiled = new int[1024];
        int[] qSeq = new int[1024];
        int[] qBucket = new int[1024];
        int[] qLayer = new int[1024];
        int[] qPlane = new int[1024];
        int[] qMin = new int[1024];
        int[] qMax = new int[1024];
        long[] keys = new long[1024];
        final Quad quad = new Quad();
        final Color color = new Color();
        final int[] words = new int[8];
        int[] boxes = new int[1024];
        final ByteBuffer[] out = {
                ByteBuffer.allocateDirect(1 << 14).order(ByteOrder.nativeOrder()),
                ByteBuffer.allocateDirect(1 << 14).order(ByteOrder.nativeOrder()),
                ByteBuffer.allocateDirect(1 << 15).order(ByteOrder.nativeOrder()) };

        public int sortedSource(int sortedIndex) {
            return (int) (keys[sortedIndex] & 0xFFFFFF);
        }

        void reset(int capacityQuads) {
            if (qTier.length < capacityQuads) {
                int cap = Math.max(capacityQuads, qTier.length * 2);
                qTier = new int[cap];
                qTiled = new int[cap];
                qSeq = new int[cap];
                qBucket = new int[cap];
                qLayer = new int[cap];
                qPlane = new int[cap];
                qMin = new int[cap];
                qMax = new int[cap];
                keys = new long[cap];
                boxes = new int[cap];
            }
            for (ByteBuffer b : tmp) {
                b.clear();
            }
            for (ByteBuffer b : out) {
                b.clear();
            }
            quads.clear();
            units.clear();
            quadBytes = 0;
            unitCount = 0;
            tierA = 0;
            tierB = 0;
            tierC = 0;
        }
    }

    private static ByteBuffer ensure(ByteBuffer b, int extra) {
        if (b.remaining() >= extra) {
            return b;
        }
        ByteBuffer next = ByteBuffer.allocateDirect(Math.max(b.capacity() * 2, b.position() + extra))
                .order(ByteOrder.nativeOrder());
        b.flip();
        next.put(b);
        return next;
    }

    private static int packBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int x0 = Math.floorDiv(minX, 256) + BOX_OFFSET;
        int y0 = Math.floorDiv(minY, 256) + BOX_OFFSET;
        int z0 = Math.floorDiv(minZ, 256) + BOX_OFFSET;
        int x1 = Math.floorDiv(maxX + 255, 256) + BOX_OFFSET;
        int y1 = Math.floorDiv(maxY + 255, 256) + BOX_OFFSET;
        int z1 = Math.floorDiv(maxZ + 255, 256) + BOX_OFFSET;
        if (x0 < 0 || y0 < 0 || z0 < 0 || x1 > 31 || y1 > 31 || z1 > 31) {
            return (31 << 15) | (31 << 20) | (31 << 25);
        }
        return x0 | (y0 << 5) | (z0 << 10) | (x1 << 15) | (y1 << 20) | (z1 << 25);
    }

    private static int morton(int cx, int cy, int cz) {
        int code = 0;
        for (int i = 0; i < 3; i++) {
            code |= ((cx >> i) & 1) << (i * 3);
            code |= ((cy >> i) & 1) << (i * 3 + 1);
            code |= ((cz >> i) & 1) << (i * 3 + 2);
        }
        return code;
    }

    public static void build(Section s, ByteBuffer src, int[] bucketCounts, int quadTotal, boolean tiers,
            boolean verify) {
        build(s, src, bucketCounts, quadTotal, null, 0, tiers, verify);
    }

    public static void build(Section s, ByteBuffer src, int[] bucketCounts, int opaqueTotal, ByteBuffer water,
            int waterTotal, boolean tiers, boolean verify) {
        int quadTotal = opaqueTotal + waterTotal;
        s.reset(quadTotal);
        SpriteTable sprites = spriteTable;
        Quad q = s.quad;
        int bucket = 0;
        int inBucket = 0;
        long a = 0;
        long b = 0;
        long c = 0;
        for (int i = 0; i < quadTotal; i++) {
            boolean translucent = i >= opaqueTotal;
            ByteBuffer qsrc = translucent ? water : src;
            int qbase = (translucent ? i - opaqueTotal : i) * 4 * VERTEX_STRIDE;
            if (translucent) {
                bucket = 6;
            } else {
                while (bucket < 6 && inBucket >= bucketCounts[bucket]) {
                    bucket++;
                    inBucket = 0;
                }
                inBucket++;
            }
            q.load(qsrc, qbase);
            q.tiled = false;
            if (translucent && q.a[0] == WATER_ALPHA && q.a[1] == WATER_ALPHA && q.a[2] == WATER_ALPHA
                    && q.a[3] == WATER_ALPHA) {
                Arrays.fill(q.a, 0xFE);
            }
            int tier = TIER_C;
            if (tiers) {
                if (tryA(q, sprites, s.color, s.words)) {
                    tier = TIER_A;
                } else if (tryB(q, sprites, s.color, s.words)) {
                    tier = TIER_B;
                }
            }
            ByteBuffer tmp = s.tmp[tier];
            int stride = tier == TIER_A ? TIER_A_BYTES : tier == TIER_B ? TIER_B_BYTES : TIER_C_BYTES;
            tmp = ensure(tmp, stride);
            s.tmp[tier] = tmp;
            s.qSeq[i] = tmp.position() / stride;
            if (verify && tier == TIER_A && !verifyA(q, s.words, sprites)) {
                VERIFY_BAD.increment();
            } else if (verify && tier == TIER_B && !verifyB(q, s.words, sprites)) {
                VERIFY_BAD.increment();
            }
            if (tier == TIER_A) {
                for (int k = 0; k < 4; k++) {
                    tmp.putInt(s.words[k]);
                }
                a++;
            } else if (tier == TIER_B) {
                for (int k = 0; k < 8; k++) {
                    tmp.putInt(s.words[k]);
                }
                b++;
            } else {
                for (int k = 0; k < 4; k++) {
                    long w0 = qsrc.getLong(qbase + k * VERTEX_STRIDE);
                    long w1 = qsrc.getLong(qbase + k * VERTEX_STRIDE + 8);
                    tmp.putLong(w0);
                    tmp.putLong(w1);
                }
                c++;
            }
            s.qTier[i] = tier;
            int tiledFlag = tier == TIER_B && q.tiled ? 1 : 0;
            s.qTiled[i] = tiledFlag;
            s.qBucket[i] = bucket;
            int layer = 1;
            if (translucent) {
                layer = 2;
            } else if (q.a[0] == 0xFE) {
                layer = 0;
            } else if (sprites != null) {
                int sp = sprites.findContaining(q.uMin, q.vMin, q.uMax, q.vMax);
                if (sp >= 0 && sprites.solid[sp]) {
                    layer = 0;
                }
            }
            s.qLayer[i] = layer;
            int plane = 0;
            if (bucket < 6) {
                int axis = bucket >> 1;
                boolean positive = (bucket & 1) == 1;
                int lo;
                int hi;
                if (axis == 0) {
                    lo = q.minY;
                    hi = q.maxY;
                } else if (axis == 1) {
                    lo = q.minZ;
                    hi = q.maxZ;
                } else {
                    lo = q.minX;
                    hi = q.maxX;
                }
                plane = positive ? lo : hi;
            }
            s.qPlane[i] = plane;
            s.qMin[i] = (q.minX & 0xFFFF) | ((q.minY & 0xFFFF) << 16);
            s.qMax[i] = (q.minZ & 0xFFFF) | ((q.maxX & 0xFFFF) << 16);
            int cx = Math.max(0, Math.min(15, Math.floorDiv(q.minX, 256)));
            int cy = Math.max(0, Math.min(15, Math.floorDiv(q.minY, 256)));
            int cz = Math.max(0, Math.min(15, Math.floorDiv(q.minZ, 256)));
            s.keys[i] = ((long) bucket << 40) | ((long) layer << 38) | ((long) tier << 36) | ((long) tiledFlag << 35) | ((long) morton(cx >> 1, cy >> 1, cz >> 1) << 24)
                    | i;
            s.boxes[i] = packQuadBox(q);
        }
        COUNT_A.add(a);
        COUNT_B.add(b);
        COUNT_C.add(c);
        s.tierA = (int) a;
        s.tierB = (int) b;
        s.tierC = (int) c;
        Arrays.sort(s.keys, 0, quadTotal);
        ByteBuffer[] out = s.out;
        out[0] = ensure(out[0], (int) a * TIER_A_BYTES + 16);
        out[1] = ensure(out[1], (int) b * TIER_B_BYTES + 16);
        out[2] = ensure(out[2], (int) c * TIER_C_BYTES + 16);
        int unitsGuess = quadTotal / 8 + 16;
        if (s.units.capacity() < unitsGuess * UNIT_BYTES) {
            s.units = ByteBuffer.allocateDirect(unitsGuess * 2 * UNIT_BYTES).order(ByteOrder.nativeOrder());
        }
        s.units.clear();
        int[] localFirst = new int[3];
        int unitCount = 0;
        int i = 0;
        while (i < quadTotal) {
            long k0 = s.keys[i];
            int idx0 = (int) (k0 & 0xFFFFFF);
            int unitBucket = s.qBucket[idx0];
            int unitTier = s.qTier[idx0];
            int unitLayer = s.qLayer[idx0];
            int unitTiled = s.qTiled[idx0];
            int count = 0;
            int j = i;
            int stride = unitTier == TIER_A ? TIER_A_BYTES : unitTier == TIER_B ? TIER_B_BYTES : TIER_C_BYTES;
            int planeBound = 0;
            int bx0 = 31;
            int by0 = 31;
            int bz0 = 31;
            int bx1 = 0;
            int by1 = 0;
            int bz1 = 0;
            boolean first = true;
            localFirst[unitTier] = out[unitTier].position() / 16;
            while (j < quadTotal && count < MAX_UNIT_QUADS) {
                int idx = (int) (s.keys[j] & 0xFFFFFF);
                if (s.qBucket[idx] != unitBucket || s.qTier[idx] != unitTier || s.qLayer[idx] != unitLayer
                        || s.qTiled[idx] != unitTiled) {
                    break;
                }
                int nb = s.boxes[idx];
                if (count > 0) {
                    int nx0 = Math.min(bx0, nb & 31);
                    int ny0 = Math.min(by0, (nb >> 5) & 31);
                    int nz0 = Math.min(bz0, (nb >> 10) & 31);
                    int nx1 = Math.max(bx1, (nb >> 15) & 31);
                    int ny1 = Math.max(by1, (nb >> 20) & 31);
                    int nz1 = Math.max(bz1, (nb >> 25) & 31);
                    if (nx1 - nx0 > MAX_UNIT_SPAN || ny1 - ny0 > MAX_UNIT_SPAN || nz1 - nz0 > MAX_UNIT_SPAN) {
                        break;
                    }
                }
                ByteBuffer tmp = s.tmp[unitTier];
                int srcOff = s.qSeq[idx] * stride;
                for (int w = 0; w < stride; w += 4) {
                    out[unitTier].putInt(tmp.getInt(srcOff + w));
                }
                int plane = s.qPlane[idx];
                if (unitBucket < 6) {
                    if (first) {
                        planeBound = plane;
                    } else if ((unitBucket & 1) == 1) {
                        planeBound = Math.min(planeBound, plane);
                    } else {
                        planeBound = Math.max(planeBound, plane);
                    }
                }
                int qb = s.boxes[idx];
                bx0 = Math.min(bx0, qb & 31);
                by0 = Math.min(by0, (qb >> 5) & 31);
                bz0 = Math.min(bz0, (qb >> 10) & 31);
                bx1 = Math.max(bx1, (qb >> 15) & 31);
                by1 = Math.max(by1, (qb >> 20) & 31);
                bz1 = Math.max(bz1, (qb >> 25) & 31);
                first = false;
                count++;
                j++;
            }
            int meta = (count - 1) | (unitBucket << 5) | (unitTier << 8) | (unitLayer << 10) | (unitTiled << 12);
            int box = bx0 | (by0 << 5) | (bz0 << 10) | (bx1 << 15) | (by1 << 20) | (bz1 << 25);
            s.units = ensure(s.units, UNIT_BYTES);
            s.units.putInt(localFirst[unitTier] | (unitTier << 30));
            s.units.putInt(meta);
            s.units.putInt(planeBound);
            s.units.putInt(box);
            unitCount++;
            i = j;
        }
        int aBytes = out[0].position();
        int bBytes = out[1].position();
        int cBytes = out[2].position();
        int total = aBytes + bBytes + cBytes;
        if (s.quads.capacity() < total) {
            s.quads = ByteBuffer.allocateDirect(Math.max(total, s.quads.capacity() * 2)).order(ByteOrder.nativeOrder());
        }
        s.quads.clear();
        out[0].flip();
        out[1].flip();
        out[2].flip();
        s.quads.put(out[0]);
        s.quads.put(out[1]);
        s.quads.put(out[2]);
        s.quads.flip();
        s.quadBytes = total;
        int[] base = { 0, aBytes / 16, (aBytes + bBytes) / 16 };
        for (int u = 0; u < unitCount; u++) {
            int at = u * UNIT_BYTES;
            int first0 = s.units.getInt(at);
            int tier = first0 >>> 30;
            int local = first0 & 0x3FFFFFFF;
            s.units.putInt(at, base[tier] + local);
        }
        s.units.position(0);
        s.units.limit(unitCount * UNIT_BYTES);
        s.unitCount = unitCount;
    }

    private static int packQuadBox(Quad q) {
        return packBox(q.minX, q.minY, q.minZ, q.maxX, q.maxY, q.maxZ);
    }
}
