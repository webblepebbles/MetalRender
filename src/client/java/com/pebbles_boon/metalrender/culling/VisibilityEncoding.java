package com.pebbles_boon.metalrender.culling;

import net.minecraft.client.renderer.chunk.VisibilitySet;

public final class VisibilityEncoding {
    private VisibilityEncoding() {
    }

    public static long encode(VisibilitySet occlusionData) {
        long visibilityData = 0L;
        for (int from = 0; from < GraphDirection.COUNT; from++) {
            for (int to = 0; to < GraphDirection.COUNT; to++) {
                if (occlusionData.visibilityBetween(GraphDirection.toEnum(from), GraphDirection.toEnum(to))) {
                    visibilityData |= 1L << bit(from, to);
                }
            }
        }
        return visibilityData;
    }

    public static int bit(int from, int to) {
        return (from * 8) + to;
    }

    public static int getConnections(long visibilityData, int incoming) {
        return foldOutgoingDirections(visibilityData & createMask(incoming));
    }

    public static int getConnections(long visibilityData) {
        return foldOutgoingDirections(visibilityData);
    }

    private static long createMask(int incoming) {
        long expanded = (0b0000001_0000001_0000001_0000001_0000001_0000001L * Integer.toUnsignedLong(incoming));
        return (expanded & 0b00000001_00000001_00000001_00000001_00000001_00000001L) * 0xFFL;
    }

    private static int foldOutgoingDirections(long data) {
        long folded = data;
        folded |= folded >> 32;
        folded |= folded >> 16;
        folded |= folded >> 8;
        return (int) (folded & GraphDirectionSet.ALL);
    }
}
