package com.pebbles_boon.metalrender.culling;

import net.minecraft.client.renderer.chunk.VisibilitySet;

public final class DirectionalVisGraph {
    private static final int SIZE = 16 * 16 * 16;
    private static final int STACK_SIZE = 512;
    private static final int DX = 1;
    private static final int DY = 16 * 16;
    private static final int DZ = 16;
    private static final int X_MASK = 0b1111;
    private static final int Y_MASK = 0b1111 << 8;
    private static final int Z_MASK = 0b1111 << 4;
    private static final int[] DIRECTION_SETS = new int[] {
            0b010101,
            0b010110,
            0b011001,
            0b100101
    };
    private final long[] words = new long[SIZE >> 6];
    private int filled = 0;

    private static int getIndex(int x, int y, int z) {
        return x | (z << 4) | (y << 8);
    }

    private static boolean getAndSet(long[] words, int index) {
        int wi = index >> 6;
        long bit = 1L << (index & 63);
        long w = words[wi];
        words[wi] = w | bit;
        return (w & bit) != 0L;
    }

    private static void setBit(long[] words, int index) {
        words[index >> 6] |= 1L << (index & 63);
    }

    public void setOpaque(int x, int y, int z) {
        setBit(this.words, getIndex(x, y, z));
        this.filled++;
    }

    public void reset() {
        java.util.Arrays.fill(this.words, 0L);
        this.filled = 0;
    }

    public VisibilitySet[] resolve() {
        if (this.filled == SIZE) {
            VisibilitySet visibilitySet = new VisibilitySet();
            visibilitySet.setAll(false);
            return new VisibilitySet[] { visibilitySet };
        }
        if (this.filled < 256) {
            VisibilitySet visibilitySet = new VisibilitySet();
            visibilitySet.setAll(true);
            return new VisibilitySet[] { visibilitySet };
        }
        VisibilitySet[] results = new VisibilitySet[DIRECTION_SETS.length];
        for (int i = 0; i < DIRECTION_SETS.length; i++) {
            results[i] = this.resolveWithDirections(DIRECTION_SETS[i]);
        }
        return results;
    }

    public long[] resolveEncoded() {
        VisibilitySet[] sets = this.resolve();
        long[] out = new long[sets.length];
        for (int i = 0; i < sets.length; i++) {
            out[i] = VisibilityEncoding.encode(sets[i]);
        }
        return out;
    }

    private VisibilitySet resolveWithDirections(int directionSet) {
        VisibilitySet visibilitySet = new VisibilitySet();
        int originDirections = (~directionSet) & GraphDirectionSet.ALL;
        short[] stackPos = new short[STACK_SIZE];
        byte[] stackDirs = new byte[STACK_SIZE];
        for (int i = 0; i < 3; i++) {
            int originDirection = Integer.numberOfTrailingZeros(originDirections);
            originDirections &= ~(1 << originDirection);
            int minX = 0;
            int minY = 0;
            int minZ = 0;
            int maxX = 15;
            int maxY = 15;
            int maxZ = 15;
            switch (originDirection) {
                case GraphDirection.DOWN -> maxY = 0;
                case GraphDirection.UP -> minY = 15;
                case GraphDirection.NORTH -> maxZ = 0;
                case GraphDirection.SOUTH -> minZ = 15;
                case GraphDirection.WEST -> maxX = 0;
                case GraphDirection.EAST -> minX = 15;
                default -> {
                }
            }
            long[] visited = this.words.clone();
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        int originIndex = getIndex(x, y, z);
                        if (!getAndSet(visited, originIndex)) {
                            this.search(visited, visibilitySet, stackPos, stackDirs, originDirection, directionSet,
                                    originIndex);
                        }
                    }
                }
            }
        }
        return visibilitySet;
    }

    private void search(long[] visited, VisibilitySet visibilitySet, short[] stackPos, byte[] stackDirs, int originFace,
            int directionSet, int originIndex) {
        int stackSize = 0;
        stackPos[stackSize++] = (short) originIndex;
        stackDirs[0] = (byte) directionSet;
        int connectedFaces = GraphDirectionSet.of(~directionSet);
        while (stackSize > 0) {
            int stackIndex = stackSize - 1;
            int remainingDirs = stackDirs[stackIndex];
            if (remainingDirs == 0) {
                stackSize--;
                continue;
            }
            int nextDir = Integer.numberOfTrailingZeros(remainingDirs);
            stackDirs[stackIndex] &= (byte) ~(1 << nextDir);
            int currentIndex = stackPos[stackIndex];
            int neighborIndex;
            boolean reachedFace;
            switch (nextDir) {
                case GraphDirection.DOWN -> {
                    neighborIndex = currentIndex - DY;
                    reachedFace = (neighborIndex & Y_MASK) == Y_MASK;
                }
                case GraphDirection.UP -> {
                    neighborIndex = currentIndex + DY;
                    reachedFace = (neighborIndex & Y_MASK) == 0;
                }
                case GraphDirection.NORTH -> {
                    neighborIndex = currentIndex - DZ;
                    reachedFace = (neighborIndex & Z_MASK) == Z_MASK;
                }
                case GraphDirection.SOUTH -> {
                    neighborIndex = currentIndex + DZ;
                    reachedFace = (neighborIndex & Z_MASK) == 0;
                }
                case GraphDirection.WEST -> {
                    neighborIndex = currentIndex - DX;
                    reachedFace = (neighborIndex & X_MASK) == X_MASK;
                }
                case GraphDirection.EAST -> {
                    neighborIndex = currentIndex + DX;
                    reachedFace = (neighborIndex & X_MASK) == 0;
                }
                default -> throw new IllegalStateException("Unexpected graph direction: " + nextDir);
            }
            if (reachedFace) {
                connectedFaces |= GraphDirectionSet.of(nextDir);
                if (connectedFaces == directionSet) {
                    break;
                }
                continue;
            }
            if (getAndSet(visited, neighborIndex)) {
                continue;
            }
            if (stackSize >= stackPos.length) {
                connectedFaces = directionSet;
                break;
            }
            stackPos[stackSize] = (short) neighborIndex;
            stackDirs[stackSize] = (byte) directionSet;
            stackSize++;
        }
        var originFaceEnum = GraphDirection.toEnum(originFace);
        for (int direction = 0; direction < GraphDirection.COUNT; direction++) {
            if (GraphDirectionSet.contains(connectedFaces, direction)) {
                visibilitySet.set(originFaceEnum, GraphDirection.toEnum(direction), true);
            }
        }
        visibilitySet.set(originFaceEnum, originFaceEnum, true);
    }
}
