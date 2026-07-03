package com.pebbles_boon.metalrender.draw;

import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.util.MetalLogger;

public class TerrainIndirectDraw {
  private static final int DEFAULT_MAX_COMMANDS = 16384;

  private volatile long icbHandle;
  private volatile int maxCommands = DEFAULT_MAX_COMMANDS;
  private volatile int currentCommandCount;
  private volatile long lastExecuteNs;
  private volatile boolean active;

  public void ensureInitialized(long deviceHandle) {
    if (!active || !NativeBridge.isLibLoaded() || deviceHandle == 0) {
      return;
    }
    if (icbHandle != 0) {
      return;
    }
    try {
      icbHandle = NativeBridge.nCreateIndirectCommandBuffer(deviceHandle,
          maxCommands);
      if (icbHandle == 0) {
        MetalLogger.warn("icb alloc 0; multipass fallback");
      }
    } catch (UnsatisfiedLinkError e) {
      MetalLogger.warn("icb make missing: " + e.getMessage());
      icbHandle = 0L;
      active = false;
    }
  }

  public boolean encodeChunkDraw(int cmdIndex, int sectionIndex,
      int instanceCount, long meshBufferHandle, long indexBufferHandle,
      int indexCount) {
    if (!active || icbHandle == 0 || !NativeBridge.isLibLoaded()) {
      return false;
    }
    if (cmdIndex < 0 || cmdIndex >= maxCommands) {
      return false;
    }
    try {
      NativeBridge.nEncodeChunkDrawICBCmd(icbHandle, cmdIndex, sectionIndex,
          instanceCount, meshBufferHandle, indexBufferHandle, indexCount);
      if (cmdIndex + 1 > currentCommandCount) {
        currentCommandCount = cmdIndex + 1;
      }
      return true;
    } catch (UnsatisfiedLinkError e) {
      MetalLogger.warn("icb encode missing: " + e.getMessage());
      return false;
    }
  }

  public void beginFrame() {
    currentCommandCount = 0;
  }

  public boolean execute(long frameContext) {
    if (!active || icbHandle == 0 || !NativeBridge.isLibLoaded() ||
        frameContext == 0) {
      return false;
    }
    try {
      NativeBridge.nExecuteIndirectCommandBuffer(frameContext, icbHandle);
      lastExecuteNs = System.nanoTime();
      return true;
    } catch (UnsatisfiedLinkError e) {
      MetalLogger.warn("icb exec missing: " + e.getMessage());
      return false;
    }
  }

  public long getHandle() {
    return icbHandle;
  }

  public int getMaxCommands() {
    return maxCommands;
  }

  public void setMaxCommands(int v) {
    if (v < 64)
      v = 64;
    if (v > 1 << 20)
      v = 1 << 20;
    if (v != maxCommands) {
      maxCommands = v;
      if (icbHandle != 0) {
        safeDestroy(icbHandle);
        icbHandle = 0L;
      }
    }
  }

  public int getCurrentCommandCount() {
    return currentCommandCount;
  }

  public long getLastExecuteNs() {
    return lastExecuteNs;
  }

  public void setActive(boolean v) {
    active = v;
    if (!v) {
      lastExecuteNs = 0L;
      currentCommandCount = 0;
    }
  }

  public boolean isActive() {
    return active;
  }

  public void shutdown() {
    long existing = icbHandle;
    if (existing != 0) {
      safeDestroy(existing);
      icbHandle = 0L;
    }
    active = false;
    currentCommandCount = 0;
    lastExecuteNs = 0L;
  }

  private void safeDestroy(long handle) {
    try {
      NativeBridge.nDestroyIndirectCommandBuffer(handle);
    } catch (UnsatisfiedLinkError ignored) {
    }
  }
}
