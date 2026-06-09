package com.pebbles_boon.metalrender.sodium.backend;

import com.pebbles_boon.metalrender.util.MetalLogger;

public class SodiumMetalInterface {
  private boolean active;

  public SodiumMetalInterface() {
  }

  public void activate() {
    this.active = true;
  }

  public boolean isActive() {
    return active;
  }

  public void shutdown() {
    active = false;
  }

  public static class MeshInfo {
    public long bufferId;
    public int vertexCount;

    public MeshInfo(long bufferId, int vertexCount) {
      this.bufferId = bufferId;
      this.vertexCount = vertexCount;
    }
  }

  public MeshInfo getSectionMesh(int chunkX, int chunkY, int chunkZ) {
    return null;
  }
}
