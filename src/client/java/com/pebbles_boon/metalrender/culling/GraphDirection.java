package com.pebbles_boon.metalrender.culling;

import net.minecraft.core.Direction;

public final class GraphDirection {
    public static final int DOWN = 0;
    public static final int UP = 1;
    public static final int NORTH = 2;
    public static final int SOUTH = 3;
    public static final int WEST = 4;
    public static final int EAST = 5;
    public static final int COUNT = 6;
    private static final Direction[] ENUMS = new Direction[COUNT];
    private static final int[] X = new int[COUNT];
    private static final int[] Y = new int[COUNT];
    private static final int[] Z = new int[COUNT];
    static {
        X[WEST] = -1;
        X[EAST] = 1;
        Y[DOWN] = -1;
        Y[UP] = 1;
        Z[NORTH] = -1;
        Z[SOUTH] = 1;
        ENUMS[DOWN] = Direction.DOWN;
        ENUMS[UP] = Direction.UP;
        ENUMS[NORTH] = Direction.NORTH;
        ENUMS[SOUTH] = Direction.SOUTH;
        ENUMS[WEST] = Direction.WEST;
        ENUMS[EAST] = Direction.EAST;
    }

    private GraphDirection() {
    }

    public static int opposite(int direction) {
        return direction ^ 1;
    }

    public static int x(int direction) {
        return X[direction];
    }

    public static int y(int direction) {
        return Y[direction];
    }

    public static int z(int direction) {
        return Z[direction];
    }

    public static Direction toEnum(int direction) {
        return ENUMS[direction];
    }

    public static int fromEnum(Direction direction) {
        return switch (direction) {
            case DOWN -> DOWN;
            case UP -> UP;
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case EAST -> EAST;
        };
    }
}
