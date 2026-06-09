package com.pebbles_boon.metalrender.culling;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RegionManager {
  private final Map<Long, Float> density = new ConcurrentHashMap<>();

  public void setRegionDensity(int rx, int ry, int rz, float d) {
    density.put(pack(rx, ry, rz), clamp01(d));
  }

  public float getRegionDensity(int rx, int ry, int rz) {
    return density.getOrDefault(pack(rx, ry, rz), 0.0f);
  }

  public void clear() {
    density.clear();
  }

  private static long pack(int x, int y, int z) {
    return ((long) (x) & 0x1FFFFFL) | (((long) (y) & 0x3FFFL) << 21) |
        (((long) (z) & 0x1FFFFFL) << 35);
  }

  private static float clamp01(float v) {
    return v < 0 ? 0 : (v > 1 ? 1 : v);
  }

  public int countBlockersAlongRay(double ox, double oy, double oz, double dx,
      double dy, double dz, double maxDist) {
    final double step = 8.0;
    final float threshold = 0.65f;
    int count = 0;
    double t = 0.0;
    while (t < maxDist && count < 8) {
      double x = ox + dx * t;
      double y = oy + dy * t;
      double z = oz + dz * t;
      int rx = (int) Math.floor(x / 16.0);
      int ry = (int) Math.floor(y / 16.0);
      int rz = (int) Math.floor(z / 16.0);
      if (getRegionDensity(rx, ry, rz) > threshold)
        count++;
      t += step;
    }
    return count;
  }
}
