package com.pebbles_boon.metalrender.region;

import java.util.concurrent.ConcurrentHashMap;

public class MetalRenderRegion {
  public final int regionX;
  public final int regionY;
  public final int regionZ;
  public long slabOffset;
  public int slabSize;
  public boolean active;
  public final ConcurrentHashMap<Long, Integer> sectionOffsets = new ConcurrentHashMap<>();

  public MetalRenderRegion(int rx, int ry, int rz) {
    this.regionX = rx;
    this.regionY = ry;
    this.regionZ = rz;
  }

  public long key() {
    return ((long) regionX << 42) | ((long) regionY << 21) | (regionZ & 0x1fffffL);
  }

  public static long key(int rx, int ry, int rz) {
    return ((long) rx << 42) | ((long) ry << 21) | (rz & 0x1fffffL);
  }

  public void setSlab(long offset, int size) {
    this.slabOffset = offset;
    this.slabSize = size;
  }

  public void registerSection(int cx, int cy, int cz, int byteOffset) {
    long sk = ((long) cx << 42) | ((long) cy << 21) | (cz & 0x1fffffL);
    sectionOffsets.put(sk, byteOffset);
  }

  public Integer getSectionOffset(int cx, int cy, int cz) {
    long sk = ((long) cx << 42) | ((long) cy << 21) | (cz & 0x1fffffL);
    return sectionOffsets.get(sk);
  }
}
