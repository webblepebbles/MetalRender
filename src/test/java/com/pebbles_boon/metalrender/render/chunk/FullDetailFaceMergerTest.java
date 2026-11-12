package com.pebbles_boon.metalrender.render.chunk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FullDetailFaceMergerTest {
    private static final int[][] CORNERS = {
            { 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0, 1 },
            { 0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0 },
            { 1, 1, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0 },
            { 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1 },
            { 0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1 },
            { 1, 1, 1, 1, 0, 1, 1, 0, 0, 1, 1, 0 }
    };
    private static final int[][] UV = { { 0, 0 }, { 0, 1 }, { 1, 1 }, { 1, 0 } };
    private QuadTiers.SpriteTable sprites;

    @BeforeEach
    void setUp() {
        sprites = new QuadTiers.SpriteTable(new int[] { 1024, 4096 }, new int[] { 2048, 2048 },
                new int[] { 2048, 5120 }, new int[] { 3072, 3072 });
        sprites.solid[0] = true;
        sprites.solid[1] = true;
        QuadTiers.setSpriteTable(sprites);
    }

    @AfterEach
    void tearDown() {
        QuadTiers.setSpriteTable(null);
    }

    @Test
    void preservesCoverageAndTextureOrientationForEveryFaceAndUvTransform() {
        for (int face = 0; face < 6; face++) {
            for (int code = 0; code < 8; code++) {
                ByteBuffer vertices = patch(face, code, 4, 4);
                Map<String, List<String>> before = samples(vertices, 16);
                QuadTiers.Section original = new QuadTiers.Section();
                int[] originalBuckets = new int[7];
                originalBuckets[face] = 16;
                QuadTiers.build(original, vertices, originalBuckets, 16, true, true);
                assertEquals(256, original.quadBytes);
                int count = QuadTiers.mergeFullDetail(vertices, 16);
                assertEquals(1, count, "face=" + face + " code=" + code);
                assertEquals(64, vertices.position());
                assertEquals(before, samples(vertices, count), "face=" + face + " code=" + code);
                QuadTiers.Section section = new QuadTiers.Section();
                int[] buckets = new int[7];
                buckets[face] = count;
                long badBefore = QuadTiers.VERIFY_BAD.sum();
                QuadTiers.build(section, vertices, buckets, count, true, true);
                assertEquals(1, section.tierB);
                assertEquals(32, section.quadBytes);
                assertEquals(1, section.qTiled[0]);
                assertEquals(badBefore, QuadTiers.VERIFY_BAD.sum());
            }
        }
    }

    @Test
    void preservesNonSquareTextureRepeatsAndSeparateFacePlanes() {
        for (int face = 0; face < 6; face++) {
            for (int code = 0; code < 8; code++) {
                for (int[] size : new int[][] { { 4, 1 }, { 1, 4 }, { 2, 3 }, { 3, 2 } }) {
                    ByteBuffer vertices = patch(face, code, size[0], size[1]);
                    int originalCount = size[0] * size[1];
                    Map<String, List<String>> before = samples(vertices, originalCount);
                    int count = QuadTiers.mergeFullDetail(vertices, originalCount);
                    assertEquals(1, count);
                    assertEquals(before, samples(vertices, count));
                    QuadTiers.Section section = new QuadTiers.Section();
                    int[] buckets = new int[7];
                    buckets[face] = count;
                    long badBefore = QuadTiers.VERIFY_BAD.sum();
                    QuadTiers.build(section, vertices, buckets, count, true, true);
                    assertEquals(1, section.tierB);
                    assertEquals(1, section.qTiled[0]);
                    assertEquals(badBefore, QuadTiers.VERIFY_BAD.sum());
                }
            }
        }
        ByteBuffer vertices = buffer();
        for (int face : new int[] { 0, 1 }) {
            for (int plane : new int[] { 0, 16 }) {
                for (int u = 0; u < 4; u++) {
                    face(vertices, face, u, 0, plane, 0, 0, 0xFFFFFF, 0xF0);
                }
            }
        }
        Map<String, List<String>> before = samples(vertices, 16);
        int count = QuadTiers.mergeFullDetail(vertices, 16);
        assertEquals(4, count);
        assertEquals(before, samples(vertices, count));
    }

    @Test
    void leavesTinyMergesOnTheCheaperSingleBlockShader() {
        for (int[] size : new int[][] { { 2, 1 }, { 1, 3 } }) {
            ByteBuffer vertices = patch(1, 0, size[0], size[1]);
            int count = size[0] * size[1];
            byte[] before = bytes(vertices, count);
            assertEquals(count, QuadTiers.mergeFullDetail(vertices, count));
            assertArrayEquals(before, bytes(vertices, count));
        }
    }

    @Test
    void keepsCullingBoundsSmallAcrossAnEntireSection() {
        ByteBuffer vertices = patch(1, 0, 16, 16);
        Map<String, List<String>> before = samples(vertices, 256);
        int count = QuadTiers.mergeFullDetail(vertices, 256);
        assertEquals(16, count);
        assertEquals(before, samples(vertices, count));
        for (int q = 0; q < count; q++) {
            for (int axis = 0; axis < 3; axis++) {
                assertTrue(max(vertices, q, axis) - min(vertices, q, axis) <= 1024);
            }
        }
    }

    @Test
    void preservesHolesAndMixedMaterialsInShuffledInput() {
        ByteBuffer vertices = buffer();
        List<int[]> cells = new ArrayList<>();
        for (int v = 0; v < 16; v++) {
            for (int u = 0; u < 16; u++) {
                if ((u + v) % 7 != 0) {
                    cells.add(new int[] { u, v });
                }
            }
        }
        Collections.shuffle(cells, new Random(7));
        for (int[] cell : cells) {
            face(vertices, 1, cell[0], cell[1], 8, cell[0] < 8 ? 0 : 3, cell[1] < 8 ? 0 : 1,
                    cell[0] % 5 == 0 ? 0x7FAD43 : 0xB9C7D3, cell[1] % 5 == 0 ? 0xA7 : 0xF0);
        }
        Map<String, List<String>> before = samples(vertices, cells.size());
        int count = QuadTiers.mergeFullDetail(vertices, cells.size());
        assertTrue(count < cells.size());
        assertEquals(before, samples(vertices, count));
    }

    @Test
    void leavesCornerLightingAndAoGradientsUntouched() {
        for (int offset : new int[] { 10, 11, 12, 14 }) {
            ByteBuffer vertices = patch(1, 0, 4, 1);
            for (int q = 0; q < 4; q++) {
                int at = q * 64 + 16 + offset;
                vertices.put(at, (byte) (vertices.get(at) - 1));
            }
            byte[] before = bytes(vertices, 4);
            assertEquals(4, QuadTiers.mergeFullDetail(vertices, 4));
            assertArrayEquals(before, bytes(vertices, 4));
        }
    }

    @Test
    void splitsAtUniformLightingTintAndTextureRotationChanges() {
        ByteBuffer vertices = buffer();
        for (int i = 0; i < 8; i++) {
            face(vertices, 1, i, 0, 8, i < 4 ? 0 : 1, 0, i % 4 < 2 ? 0xAABBCC : 0xAACCDD,
                    i % 2 == 0 ? 0xF0 : 0xE0);
        }
        byte[] before = bytes(vertices, 8);
        assertEquals(8, QuadTiers.mergeFullDetail(vertices, 8));
        assertArrayEquals(before, bytes(vertices, 8));
    }

    @Test
    void leavesTransparentSpritesLeavesAndTranslucentQuadsUntouched() {
        sprites.solid[0] = false;
        ByteBuffer cutout = patch(1, 0, 4, 4);
        byte[] cutoutBefore = bytes(cutout, 16);
        assertEquals(16, QuadTiers.mergeFullDetail(cutout, 16));
        assertArrayEquals(cutoutBefore, bytes(cutout, 16));
        sprites.solid[0] = true;
        for (int alpha : new int[] { 254, 168 }) {
            ByteBuffer vertices = patch(1, 0, 4, 4);
            for (int i = 0; i < 64; i++) {
                vertices.put(i * 16 + 13, (byte) alpha);
            }
            byte[] before = bytes(vertices, 16);
            assertEquals(16, QuadTiers.mergeFullDetail(vertices, 16));
            assertArrayEquals(before, bytes(vertices, 16));
        }
    }

    @Test
    void leavesPartialOffsetAndNonAxisAlignedGeometryUntouched() {
        for (int adjustment : new int[] { 1, 128 }) {
            ByteBuffer vertices = patch(1, 0, 4, 1);
            for (int q = 0; q < 4; q++) {
                vertices.putShort(q * 64 + 2, (short) (vertices.getShort(q * 64 + 2) + adjustment));
            }
            byte[] before = bytes(vertices, 4);
            assertEquals(4, QuadTiers.mergeFullDetail(vertices, 4));
            assertArrayEquals(before, bytes(vertices, 4));
        }
    }

    @Test
    void preservesDuplicateFacesAndUnmergeableQuadsDuringCompaction() {
        ByteBuffer vertices = buffer();
        face(vertices, 1, 0, 0, 8, 0, 0, 0xFFFFFF, 0xF0);
        face(vertices, 1, 0, 0, 8, 0, 0, 0xFFFFFF, 0xF0);
        for (int u = 1; u < 5; u++) {
            face(vertices, 1, u, 0, 8, 0, 0, 0xFFFFFF, 0xF0);
        }
        face(vertices, 1, 6, 0, 8, 0, 0, 0xFFFFFF, 0xF0);
        vertices.put(6 * 64 + 14, (byte) 0xA0);
        byte[] lastQuad = new byte[64];
        vertices.get(6 * 64, lastQuad);
        Map<String, List<String>> before = samples(vertices, 7);
        int count = QuadTiers.mergeFullDetail(vertices, 7);
        assertEquals(4, count);
        assertEquals(before, samples(vertices, count));
        byte[] actualLast = new byte[64];
        vertices.get((count - 1) * 64, actualLast);
        assertArrayEquals(lastQuad, actualLast);
    }

    @Test
    void fallsBackWhenUvCoordinatesCannotBePackedExactly() {
        ByteBuffer vertices = patch(1, 0, 4, 4);
        for (int i = 0; i < 64; i++) {
            vertices.putShort(i * 16 + 6, (short) (vertices.getShort(i * 16 + 6) + 1));
        }
        byte[] before = bytes(vertices, 16);
        assertEquals(16, QuadTiers.mergeFullDetail(vertices, 16));
        assertArrayEquals(before, bytes(vertices, 16));
    }

    @Test
    void supportsEmptySingleAndMissingAtlasInputs() {
        ByteBuffer vertices = buffer();
        assertEquals(0, QuadTiers.mergeFullDetail(vertices, 0));
        face(vertices, 0, 0, 0, 0, 0, 0, 0xFFFFFF, 0xF0);
        byte[] before = bytes(vertices, 1);
        assertEquals(1, QuadTiers.mergeFullDetail(vertices, 1));
        assertArrayEquals(before, bytes(vertices, 1));
        QuadTiers.setSpriteTable(null);
        vertices = patch(1, 0, 4, 4);
        before = bytes(vertices, 16);
        assertEquals(16, QuadTiers.mergeFullDetail(vertices, 16));
        assertArrayEquals(before, bytes(vertices, 16));
    }

    private static ByteBuffer buffer() {
        return ByteBuffer.allocate(32768).order(ByteOrder.nativeOrder());
    }

    private static ByteBuffer patch(int face, int code, int width, int height) {
        ByteBuffer vertices = buffer();
        for (int v = 0; v < height; v++) {
            for (int u = 0; u < width; u++) {
                face(vertices, face, u, v, 8, code, 0, 0x7FAD43, 0xF2);
            }
        }
        return vertices;
    }

    private static void face(ByteBuffer vertices, int face, int u, int v, int plane, int code,
            int sprite, int rgb, int light) {
        for (int i = 0; i < 4; i++) {
            int[] c = CORNERS[face];
            int x = face < 4 ? u + c[i * 3] : plane;
            int y = face < 2 ? plane : v + c[i * 3 + 1];
            int z = face < 2 ? v + c[i * 3 + 2] : face < 4 ? plane : u + c[i * 3 + 2];
            int p = (code & 4) != 0 ? ((code & 3) - i) & 3 : (i + code) & 3;
            int texU = (sprite == 0 ? 1024 : 4096) + UV[p][0] * 1024;
            int texV = 2048 + UV[p][1] * 1024;
            vertices.putShort((short) (x * 256)).putShort((short) (y * 256)).putShort((short) (z * 256));
            vertices.putShort((short) texU).putShort((short) texV);
            vertices.put((byte) (rgb >> 16)).put((byte) (rgb >> 8)).put((byte) rgb).put((byte) 255);
            vertices.put((byte) light).put((byte) face);
        }
    }

    private static byte[] bytes(ByteBuffer vertices, int quads) {
        byte[] out = new byte[quads * 64];
        vertices.get(0, out);
        return out;
    }

    private static int min(ByteBuffer vertices, int q, int axis) {
        int result = Integer.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            result = Math.min(result, vertices.getShort(q * 64 + i * 16 + axis * 2));
        }
        return result;
    }

    private static int max(ByteBuffer vertices, int q, int axis) {
        int result = Integer.MIN_VALUE;
        for (int i = 0; i < 4; i++) {
            result = Math.max(result, vertices.getShort(q * 64 + i * 16 + axis * 2));
        }
        return result;
    }

    private static Map<String, List<String>> samples(ByteBuffer vertices, int quads) {
        Map<String, List<String>> result = new HashMap<>();
        for (int q = 0; q < quads; q++) {
            int base = q * 64;
            int face = Byte.toUnsignedInt(vertices.get(base + 15));
            int axisU = face < 4 ? 0 : 2;
            int axisV = face < 2 ? 2 : 1;
            int axisN = face < 2 ? 1 : face < 4 ? 2 : 0;
            int u0 = min(vertices, q, axisU);
            int v0 = min(vertices, q, axisV);
            int width = (max(vertices, q, axisU) - u0) / 256;
            int height = (max(vertices, q, axisV) - v0) / 256;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    for (double[] sample : new double[][] { { 0.125, 0.375 }, { 0.625, 0.875 } }) {
                        double a = 0;
                        double b = 0;
                        for (int i = 0; i < 4; i++) {
                            int at = base + i * 16;
                            double cu = vertices.getShort(at + axisU * 2) > u0 ? 1 : 0;
                            double cv = vertices.getShort(at + axisV * 2) > v0 ? 1 : 0;
                            double du = (x + sample[0]) / width;
                            double dv = (y + sample[1]) / height;
                            double weight = (cu == 0 ? 1 - du : du) * (cv == 0 ? 1 - dv : dv);
                            a += Short.toUnsignedInt(vertices.getShort(at + 6)) * weight;
                            b += Short.toUnsignedInt(vertices.getShort(at + 8)) * weight;
                        }
                        int lowU = 65535;
                        int highU = 0;
                        int lowV = 65535;
                        int highV = 0;
                        for (int i = 0; i < 4; i++) {
                            int at = base + i * 16;
                            int tu = Short.toUnsignedInt(vertices.getShort(at + 6));
                            int tv = Short.toUnsignedInt(vertices.getShort(at + 8));
                            lowU = Math.min(lowU, tu);
                            highU = Math.max(highU, tu);
                            lowV = Math.min(lowV, tv);
                            highV = Math.max(highV, tv);
                        }
                        boolean uAlongU = vertices.getShort(base + 6) != vertices.getShort(base + 16 + 6)
                                ? vertices.getShort(base + axisU * 2) != vertices.getShort(base + 16 + axisU * 2)
                                : vertices.getShort(base + axisU * 2) != vertices.getShort(base + 48 + axisU * 2);
                        double tileU = (a - lowU) / (highU - lowU) * (uAlongU ? width : height);
                        double tileV = (b - lowV) / (highV - lowV) * (uAlongU ? height : width);
                        long texU = Math.round(lowU + (tileU - Math.floor(tileU)) * (highU - lowU));
                        long texV = Math.round(lowV + (tileV - Math.floor(tileV)) * (highV - lowV));
                        String key = face + ":" + min(vertices, q, axisN) + ":" + (u0 / 256 + x)
                                + ":" + (v0 / 256 + y) + ":" + sample[0];
                        String value = texU + ":" + texV + ":" + (vertices.getLong(base + 8) >>> 16);
                        result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
                    }
                }
            }
        }
        result.values().forEach(Collections::sort);
        return result;
    }
}
