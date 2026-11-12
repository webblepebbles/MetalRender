package com.pebbles_boon.metalrender.render.chunk;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.function.LongPredicate;

final class FullDetailFaceMerger {
    private static final int QUAD_BYTES = 64;
    private static final int MAX_SPAN = 4;
    private static final int MIN_AREA = 4;
    private static final int[][] CORNERS = {
            { 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0, 1 },
            { 0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0 },
            { 1, 1, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0 },
            { 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1 },
            { 0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1 },
            { 1, 1, 1, 1, 0, 1, 1, 0, 0, 1, 1, 0 }
    };
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private static final class Scratch {
        final int[] grid = new int[6 * 17 * 256];
        final boolean[] planes = new boolean[6 * 17];
        final int[] min = new int[3];
        final int[] max = new int[3];
        final HashMap<Long, Boolean> textures = new HashMap<>();
        int[] extents = new int[1024];
    }

    private FullDetailFaceMerger() {
    }

    static int merge(ByteBuffer vertices, int quadCount, LongPredicate supportedTexture) {
        if (quadCount < 2) {
            return quadCount;
        }
        Scratch s = SCRATCH.get();
        Arrays.fill(s.grid, 0);
        Arrays.fill(s.planes, false);
        s.textures.clear();
        if (s.extents.length < quadCount) {
            s.extents = new int[Math.max(quadCount, s.extents.length * 2)];
        }
        Arrays.fill(s.extents, 0, quadCount, 0);
        int candidates = 0;
        for (int q = 0; q < quadCount; q++) {
            int base = q * QUAD_BYTES;
            int cell = locate(vertices, base, s);
            if (cell < 0) {
                continue;
            }
            if (s.grid[cell] != 0) {
                s.grid[cell] = -1;
                continue;
            }
            if (!uniform(vertices, base) || !supported(vertices, base, supportedTexture, s)) {
                s.grid[cell] = -1;
                continue;
            }
            s.grid[cell] = q + 1;
            s.planes[cell >>> 8] = true;
            candidates++;
        }
        if (candidates < 2) {
            return quadCount;
        }
        int removed = 0;
        for (int plane = 0; plane < s.planes.length; plane++) {
            if (!s.planes[plane]) {
                continue;
            }
            for (int v = 0; v < 16; v++) {
                for (int u = 0; u < 16; u++) {
                    int cell = plane * 256 + v * 16 + u;
                    int q = s.grid[cell] - 1;
                    if (q < 0) {
                        continue;
                    }
                    int width = 1;
                    while (width < MAX_SPAN && u + width < 16
                            && matches(vertices, q, s.grid[cell + width] - 1)) {
                        width++;
                    }
                    int height = 1;
                    rows: while (height < MAX_SPAN && v + height < 16) {
                        for (int x = 0; x < width; x++) {
                            if (!matches(vertices, q, s.grid[cell + height * 16 + x] - 1)) {
                                break rows;
                            }
                        }
                        height++;
                    }
                    if (width * height < MIN_AREA) {
                        s.grid[cell] = 0;
                        continue;
                    }
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            int at = cell + y * 16 + x;
                            s.extents[s.grid[at] - 1] = -1;
                            s.grid[at] = 0;
                        }
                    }
                    s.extents[q] = width | (height << 3);
                    removed += width * height - 1;
                }
            }
        }
        if (removed == 0) {
            return quadCount;
        }
        int written = 0;
        for (int q = 0; q < quadCount; q++) {
            int extent = s.extents[q];
            if (extent < 0) {
                continue;
            }
            int base = q * QUAD_BYTES;
            int target = written++ * QUAD_BYTES;
            int face = (int) (vertices.getLong(base + 8) >>> 56);
            int width = extent & 7;
            int height = extent >>> 3;
            for (int i = 0; i < 4; i++) {
                long position = vertices.getLong(base + i * 16);
                long attributes = vertices.getLong(base + i * 16 + 8);
                if (extent != 0) {
                    int[] corner = CORNERS[face];
                    int uAxis = face < 4 ? 0 : 2;
                    int vAxis = face < 2 ? 2 : 1;
                    position += (long) ((width - 1) * 256 * corner[i * 3 + uAxis]) << (uAxis * 16);
                    position += (long) ((height - 1) * 256 * corner[i * 3 + vAxis]) << (vAxis * 16);
                }
                vertices.putLong(target + i * 16, position);
                vertices.putLong(target + i * 16 + 8, attributes);
            }
        }
        vertices.position(written * QUAD_BYTES);
        return written;
    }

    private static int locate(ByteBuffer vertices, int base, Scratch s) {
        int face = (int) (vertices.getLong(base + 8) >>> 56);
        if (face > 5) {
            return -1;
        }
        Arrays.fill(s.min, Integer.MAX_VALUE);
        Arrays.fill(s.max, Integer.MIN_VALUE);
        for (int i = 0; i < 4; i++) {
            long position = vertices.getLong(base + i * 16);
            if ((vertices.getLong(base + i * 16 + 8) >>> 56) != face) {
                return -1;
            }
            for (int axis = 0; axis < 3; axis++) {
                int p = (short) (position >>> (axis * 16));
                if (p < 0 || p > 4096 || (p & 255) != 0) {
                    return -1;
                }
                s.min[axis] = Math.min(s.min[axis], p);
                s.max[axis] = Math.max(s.max[axis], p);
            }
        }
        int normalAxis = face < 2 ? 1 : face < 4 ? 2 : 0;
        for (int axis = 0; axis < 3; axis++) {
            if (s.max[axis] - s.min[axis] != (axis == normalAxis ? 0 : 256)) {
                return -1;
            }
        }
        for (int i = 0; i < 4; i++) {
            long position = vertices.getLong(base + i * 16);
            for (int axis = 0; axis < 3; axis++) {
                int expected = s.min[axis] + CORNERS[face][i * 3 + axis] * (s.max[axis] - s.min[axis]);
                if ((short) (position >>> (axis * 16)) != expected) {
                    return -1;
                }
            }
        }
        int u = s.min[face < 4 ? 0 : 2] >> 8;
        int v = s.min[face < 2 ? 2 : 1] >> 8;
        return (face * 17 + (s.min[normalAxis] >> 8)) * 256 + v * 16 + u;
    }

    private static boolean uniform(ByteBuffer vertices, int base) {
        long attributes = vertices.getLong(base + 8) & ~0xFFFFL;
        if (((attributes >>> 40) & 255) != 255) {
            return false;
        }
        for (int i = 1; i < 4; i++) {
            if ((vertices.getLong(base + i * 16 + 8) & ~0xFFFFL) != attributes) {
                return false;
            }
        }
        return true;
    }

    private static boolean supported(ByteBuffer vertices, int base, LongPredicate supportedTexture, Scratch s) {
        int u0 = 65535;
        int v0 = 65535;
        int u1 = 0;
        int v1 = 0;
        for (int i = 0; i < 4; i++) {
            int u = (int) (vertices.getLong(base + i * 16) >>> 48);
            int v = (int) (vertices.getLong(base + i * 16 + 8) & 65535);
            u0 = Math.min(u0, u);
            v0 = Math.min(v0, v);
            u1 = Math.max(u1, u);
            v1 = Math.max(v1, v);
        }
        if (u0 == u1 || v0 == v1) {
            return false;
        }
        int seen = 0;
        int previous = -1;
        int first = -1;
        for (int i = 0; i < 4; i++) {
            int u = (int) (vertices.getLong(base + i * 16) >>> 48);
            int v = (int) (vertices.getLong(base + i * 16 + 8) & 65535);
            if ((u != u0 && u != u1) || (v != v0 && v != v1)) {
                return false;
            }
            int corner = (u == u1 ? 1 : 0) | (v == v1 ? 2 : 0);
            if (previous >= 0 && Integer.bitCount(corner ^ previous) != 1) {
                return false;
            }
            if (first < 0) {
                first = corner;
            }
            previous = corner;
            seen |= 1 << corner;
        }
        if (seen != 15 || Integer.bitCount(first ^ previous) != 1) {
            return false;
        }
        long bounds = u0 | ((long) v0 << 16) | ((long) u1 << 32) | ((long) v1 << 48);
        return s.textures.computeIfAbsent(bounds, supportedTexture::test);
    }

    private static boolean matches(ByteBuffer vertices, int first, int other) {
        if (other < 0) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            int a = first * QUAD_BYTES + i * 16;
            int b = other * QUAD_BYTES + i * 16;
            if ((vertices.getLong(a) >>> 48) != (vertices.getLong(b) >>> 48)
                    || vertices.getLong(a + 8) != vertices.getLong(b + 8)) {
                return false;
            }
        }
        return true;
    }
}
