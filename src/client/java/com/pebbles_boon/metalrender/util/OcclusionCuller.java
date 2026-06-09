package com.pebbles_boon.metalrender.util;

import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;

public class OcclusionCuller {
  private static final int AZIMUTH_BINS = 72;
  private static final int ELEVATION_BINS = 36;
  private static final float MARGIN = 2.0f;
  private final float[] depthGrid = new float[AZIMUTH_BINS * ELEVATION_BINS];
  private double camX, camY, camZ;

  public void beginFrame(Camera camera) {
    for (int i = 0; i < depthGrid.length; i++)
      depthGrid[i] = Float.POSITIVE_INFINITY;
    net.minecraft.world.phys.Vec3 pos = camera.position();
    camX = pos.x;
    camY = pos.y;
    camZ = pos.z;
  }

  public boolean isChunkOccluded(BlockPos chunkPos, Camera camera) {
    double cx = (chunkPos.getX() << 4) + 8.0;
    double cy = chunkPos.getY();
    double cz = (chunkPos.getZ() << 4) + 8.0;
    double dx = cx - camX;
    double dy = cy - camY;
    double dz = cz - camZ;
    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
    if (dist < 1e-3)
      dist = 1e-3;
    double az = Math.atan2(dz, dx);
    double el = Math.asin(dy / dist);
    int ai = (int) Math.floor((az + Math.PI) / (2.0 * Math.PI) * AZIMUTH_BINS);
    int ei = (int) Math.floor((el + Math.PI / 2.0) / Math.PI * ELEVATION_BINS);
    if (ai < 0)
      ai = 0;
    else if (ai >= AZIMUTH_BINS)
      ai = AZIMUTH_BINS - 1;
    if (ei < 0)
      ei = 0;
    else if (ei >= ELEVATION_BINS)
      ei = ELEVATION_BINS - 1;
    int idx = ei * AZIMUTH_BINS + ai;
    float nearest = depthGrid[idx];
    boolean occluded = nearest + MARGIN < (float) dist;
    if (!occluded) {
      if ((float) dist < nearest)
        depthGrid[idx] = (float) dist;
    }
    return occluded;
  }
}
