package com.pebbles_boon.metalrender.culling;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class BlockFaceCulling {
  private final ShapeComparisonCache occlusionCache = new ShapeComparisonCache();
  private int cullCompletionFlags;
  private int cullResultFlags;
  private BlockState cachedState;
  public void prepare(BlockState state) {
    if (this.cachedState != state) {
      this.cachedState = state;
      this.cullCompletionFlags = 0;
      this.cullResultFlags = 0;
    } else if (this.cachedState == null) {
      this.cullCompletionFlags = 0;
      this.cullResultFlags = 0;
    }
  }
  public void reset(BlockState state) {
    this.cachedState = state;
    this.cullCompletionFlags = 0;
    this.cullResultFlags = 0;
  }
  public boolean shouldDrawSide(BlockState selfState, BlockState neighborState, Direction facing) {
    VoxelShape neighborShape = neighborState.getFaceOcclusionShape(facing.getOpposite());
    if (ShapeComparisonCache.isFullShape(neighborShape)) {
      return false;
    }
    if (selfState.skipRendering(neighborState, facing)) {
      return false;
    }
    if (ShapeComparisonCache.isEmptyShape(neighborShape) || !neighborState.canOcclude()) {
      return true;
    }
    VoxelShape selfShape = selfState.getFaceOcclusionShape(facing);
    if (ShapeComparisonCache.isEmptyShape(selfShape)) {
      return true;
    }
    return this.occlusionCache.lookup(selfShape, neighborShape);
  }
  public boolean isFaceCulled(Direction face, BlockState selfState, BlockState neighborState) {
    if (face == null) {
      return false;
    }
    int mask = 1 << face.get3DDataValue();
    if ((this.cullCompletionFlags & mask) == 0) {
      this.cullCompletionFlags |= mask;
      if (this.shouldDrawSide(selfState, neighborState, face)) {
        this.cullResultFlags |= mask;
        return false;
      } else {
        return true;
      }
    } else {
      return (this.cullResultFlags & mask) == 0;
    }
  }
  public static boolean shouldCullFace(BlockState selfState, BlockState neighborState, Direction facing, ShapeComparisonCache cache) {
    if (neighborState == null || neighborState.isAir()) {
      return false;
    }
    VoxelShape neighborShape;
    try {
      neighborShape = neighborState.getFaceOcclusionShape(facing.getOpposite());
    } catch (RuntimeException ignored) {
      return neighborState.isSolidRender();
    }
    if (ShapeComparisonCache.isFullShape(neighborShape)) {
      return true;
    }
    try {
      if (selfState.skipRendering(neighborState, facing)) {
        return true;
      }
    } catch (RuntimeException ignored) {
    }
    boolean neighborCanOcclude;
    try {
      neighborCanOcclude = neighborState.canOcclude();
    } catch (RuntimeException ignored) {
      neighborCanOcclude = neighborState.isSolidRender();
    }
    if (ShapeComparisonCache.isEmptyShape(neighborShape) || !neighborCanOcclude) {
      return false;
    }
    VoxelShape selfShape;
    try {
      selfShape = selfState.getFaceOcclusionShape(facing);
    } catch (RuntimeException ignored) {
      return false;
    }
    if (ShapeComparisonCache.isEmptyShape(selfShape)) {
      return false;
    }
    try {
      return !cache.lookup(selfShape, neighborShape);
    } catch (RuntimeException ignored) {
      return false;
    }
  }
}
