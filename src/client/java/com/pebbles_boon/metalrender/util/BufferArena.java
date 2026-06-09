package com.pebbles_boon.metalrender.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;

public class BufferArena {
  private final long capacityBytes;
  private final AtomicLong used = new AtomicLong();
  private final int blockSize;

  public BufferArena(long capacityBytes) {
    this(capacityBytes, 4096);
  }

  public BufferArena(long capacityBytes, int blockSize) {
    this.capacityBytes = capacityBytes;
    this.blockSize = Math.max(256, blockSize);
  }

  public boolean canAllocate(long bytes) {
    return used.get() + bytes <= capacityBytes;
  }

  public long allocate(long bytes) {
    long prev = used.get();
    if (prev + bytes > capacityBytes)
      return -1;
    used.addAndGet(bytes);
    return prev;
  }

  public void free(long handle, long bytes) {
    used.addAndGet(-bytes);
  }

  public ByteBuffer acquire() {
    return ByteBuffer.allocateDirect(blockSize).order(ByteOrder.LITTLE_ENDIAN);
  }

  public void release(ByteBuffer buf) {
  }

  public long getUsedBytes() {
    return used.get();
  }

  public long getCapacityBytes() {
    return capacityBytes;
  }

  public long upload(ByteBuffer data) {
    return 0L;
  }
}
