package com.pebbles_boon.metalrender.culling;

public final class CameraFaceCuller {
  public static final int MODEL_POS_X = 0;
  public static final int MODEL_POS_Y = 1;
  public static final int MODEL_POS_Z = 2;
  public static final int MODEL_NEG_X = 3;
  public static final int MODEL_NEG_Y = 4;
  public static final int MODEL_NEG_Z = 5;
  public static final int MODEL_UNASSIGNED = 6;
  public static final int MODEL_COUNT = 7;
  public static final int MODEL_ALL = (1 << MODEL_COUNT) - 1;
  public static final int DIR_DOWN = 0;
  public static final int DIR_UP = 1;
  public static final int DIR_NORTH = 2;
  public static final int DIR_SOUTH = 3;
  public static final int DIR_WEST = 4;
  public static final int DIR_EAST = 5;
  public static final int DIR_UNASSIGNED = 6;
  private CameraFaceCuller() {
  }
  public static int getVisibleFaces(int originX, int originY, int originZ, int chunkX, int chunkY, int chunkZ) {
    int boundsMinX = (chunkX << 4);
    int boundsMaxX = boundsMinX + 16;
    int boundsMinY = (chunkY << 4);
    int boundsMaxY = boundsMinY + 16;
    int boundsMinZ = (chunkZ << 4);
    int boundsMaxZ = boundsMinZ + 16;
    int planes = (1 << MODEL_UNASSIGNED);
    planes |= BitwiseMath.greaterThan(originX, (boundsMinX - 3)) << MODEL_POS_X;
    planes |= BitwiseMath.greaterThan(originY, (boundsMinY - 3)) << MODEL_POS_Y;
    planes |= BitwiseMath.greaterThan(originZ, (boundsMinZ - 3)) << MODEL_POS_Z;
    planes |= BitwiseMath.lessThan(originX, (boundsMaxX + 3)) << MODEL_NEG_X;
    planes |= BitwiseMath.lessThan(originY, (boundsMaxY + 3)) << MODEL_NEG_Y;
    planes |= BitwiseMath.lessThan(originZ, (boundsMaxZ + 3)) << MODEL_NEG_Z;
    return planes;
  }
  public static int toDirectionMask(int modelMask) {
    int out = 0;
    if ((modelMask & (1 << MODEL_POS_X)) != 0) {
      out |= 1 << DIR_EAST;
    }
    if ((modelMask & (1 << MODEL_POS_Y)) != 0) {
      out |= 1 << DIR_UP;
    }
    if ((modelMask & (1 << MODEL_POS_Z)) != 0) {
      out |= 1 << DIR_SOUTH;
    }
    if ((modelMask & (1 << MODEL_NEG_X)) != 0) {
      out |= 1 << DIR_WEST;
    }
    if ((modelMask & (1 << MODEL_NEG_Y)) != 0) {
      out |= 1 << DIR_DOWN;
    }
    if ((modelMask & (1 << MODEL_NEG_Z)) != 0) {
      out |= 1 << DIR_NORTH;
    }
    if ((modelMask & (1 << MODEL_UNASSIGNED)) != 0) {
      out |= 1 << DIR_UNASSIGNED;
    }
    return out;
  }
  public static int getVisibleFacesDirectionMask(int originX, int originY, int originZ, int chunkX, int chunkY, int chunkZ) {
    return toDirectionMask(getVisibleFaces(originX, originY, originZ, chunkX, chunkY, chunkZ));
  }
  public static int getVisibleFacesForBounds(int originX, int originY, int originZ, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    int planes = (1 << DIR_UNASSIGNED);
    planes |= BitwiseMath.greaterThan(originX, (minX - 3)) << DIR_EAST;
    planes |= BitwiseMath.greaterThan(originY, (minY - 3)) << DIR_UP;
    planes |= BitwiseMath.greaterThan(originZ, (minZ - 3)) << DIR_SOUTH;
    planes |= BitwiseMath.lessThan(originX, (maxX + 3)) << DIR_WEST;
    planes |= BitwiseMath.lessThan(originY, (maxY + 3)) << DIR_DOWN;
    planes |= BitwiseMath.lessThan(originZ, (maxZ + 3)) << DIR_NORTH;
    return planes;
  }
}
