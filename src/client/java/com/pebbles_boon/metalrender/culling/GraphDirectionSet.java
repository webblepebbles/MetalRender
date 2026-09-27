package com.pebbles_boon.metalrender.culling;

public final class GraphDirectionSet {
    public static final int NONE = 0;
    public static final int ALL = (1 << GraphDirection.COUNT) - 1;

    private GraphDirectionSet() {
    }

    public static int of(int direction) {
        return 1 << direction;
    }

    public static boolean contains(int set, int direction) {
        return (set & GraphDirectionSet.of(direction)) != 0;
    }
}
