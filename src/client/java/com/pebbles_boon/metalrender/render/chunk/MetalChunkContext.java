package com.pebbles_boon.metalrender.render.chunk;

public class MetalChunkContext {
  private final int chunkX;
  private final int chunkY;
  private final int chunkZ;
  private long frameContext;

  public MetalChunkContext(int chunkX, int chunkY, int chunkZ) {
    this.chunkX = chunkX;
    this.chunkY = chunkY;
    this.chunkZ = chunkZ;
  }

  public int getChunkX() {
    return chunkX;
  }

  public int getChunkY() {
    return chunkY;
  }

  public int getChunkZ() {
    return chunkZ;
  }

  public void setFrameContext(long ctx) {
    this.frameContext = ctx;
  }

  public long getFrameContext() {
    return frameContext;
  }
}
