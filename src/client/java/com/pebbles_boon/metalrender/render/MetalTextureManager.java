package com.pebbles_boon.metalrender.render;

import com.mojang.blaze3d.opengl.GlTexture;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

public class MetalTextureManager {
  private static final net.minecraft.resources.Identifier BLOCKS_ATLAS_ID = TextureAtlas.LOCATION_BLOCKS;
  private final long deviceHandle;
  private long blockAtlasTexture;
  private long lightmapTexture;
  private boolean blockAtlasLoaded;
  private boolean lightmapLoaded;
  private boolean usingFallbackBlockAtlas;
  private int blockAtlasWidth;
  private int blockAtlasHeight;
  private int blockAtlasMipLevels = 1;
  private int lightmapWidth;
  private int lightmapHeight;
  private byte[] atlasUploadData = null;
  private byte[] atlasReadbackScratch = null;
  private byte[] lightmapUploadData = null;
  private ByteBuffer atlasPixelBuffer = null;
  private ByteBuffer lightmapPixelBuffer = null;
  public static volatile boolean atlasDirty = true;

  private static final int ATLAS_MIN_UPLOAD_INTERVAL = 1;
  private static final int ATLAS_HEARTBEAT_FRAMES = 1800;

  private static final long ATLAS_MIN_UPLOAD_INTERVAL_MS = 100L;
  private static final long ATLAS_BACKOFF_INTERVAL_MS = 400L;
  private static final double ATLAS_ADAPTIVE_COST_THRESHOLD_MS = 1.5;
  private static final double ATLAS_ADAPTIVE_DELAY_SCALE = 60.0;
  private static final long ATLAS_ADAPTIVE_MAX_DELAY_MS = 700L;
  private long lastAtlasUploadMs = 0L;
  private boolean atlasBackoffActive;
  private long atlasExtraDelayMs;
  private static final int LIGHTMAP_MIN_UPLOAD_INTERVAL = 2;
  private static final long LIGHTMAP_MIN_GAME_TIME_DELTA = 4L;
  private int atlasFramesSinceUpload = 0;
  private int lightmapFramesSinceUpload = 0;
  private long lastLightmapObservedGameTime = Long.MIN_VALUE;
  private long lastUploadedLightmapGameTime = Long.MIN_VALUE;

  public static void markAtlasDirty() {
    atlasDirty = true;
  }

  public MetalTextureManager(long deviceHandle) {
    this.deviceHandle = deviceHandle;
  }

  public void loadBlockAtlas() {
    try {
      Minecraft mc = Minecraft.getInstance();
      if (mc == null || mc.getTextureManager() == null)
        return;
      AbstractTexture atlasTexture = mc.getTextureManager().getTexture(BLOCKS_ATLAS_ID);
      if (atlasTexture == null) {
        MetalLogger.info("atlas not weady");
        blockAtlasLoaded = true;
        usingFallbackBlockAtlas = true;
        return;
      }
      int glTexId = 0;
      var gpuTex = atlasTexture.getTexture();
      if (gpuTex instanceof GlTexture glTex) {
        glTexId = glTex.glId();
      }
      if (glTexId == 0) {
        MetalLogger.info("atlas gl id 0");
        blockAtlasLoaded = true;
        usingFallbackBlockAtlas = true;
        return;
      }
      int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTexId);
      int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
          GL11.GL_TEXTURE_WIDTH);
      int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
          GL11.GL_TEXTURE_HEIGHT);
      if (width <= 0 || height <= 0) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        MetalLogger.info("atlas bad dim %dx%d", width, height);
        blockAtlasLoaded = true;
        usingFallbackBlockAtlas = true;
        return;
      }
      ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
      GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
          GL11.GL_UNSIGNED_BYTE, pixels);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
      byte[] data = new byte[width * height * 4];
      pixels.get(data);
      int mipLevels = currentMipmapLevels();
      long newTexture = NativeBridge.nCreateTexture2D(deviceHandle, width,
          height, mipLevels, data);
      if (newTexture != 0) {
        if (blockAtlasTexture != 0 && blockAtlasTexture != newTexture) {
          NativeBridge.nDestroyTexture2D(blockAtlasTexture);
        }
        blockAtlasTexture = newTexture;
        blockAtlasWidth = width;
        blockAtlasHeight = height;
        blockAtlasMipLevels = mipLevels;
        blockAtlasLoaded = true;
        usingFallbackBlockAtlas = false;
        MetalLogger.info("atlas weady: %dx%d h=%d mips=%d", width, height,
            newTexture, mipLevels);
      } else {
        MetalLogger.error("atlas tex create fail");
        blockAtlasLoaded = true;
        usingFallbackBlockAtlas = true;
      }
    } catch (Exception e) {
      MetalLogger.error("atlas load fail: %s", e.getMessage());
      blockAtlasLoaded = true;
      usingFallbackBlockAtlas = true;
    }
  }

  public void updateBlockAtlas() {
    if (blockAtlasTexture == 0 || usingFallbackBlockAtlas)
      return;
    atlasFramesSinceUpload++;

    if (atlasFramesSinceUpload < ATLAS_MIN_UPLOAD_INTERVAL)
      return;
    if (!atlasDirty && atlasFramesSinceUpload < ATLAS_HEARTBEAT_FRAMES)
      return;
    long nowMs = System.currentTimeMillis();
    long minIntervalMs = atlasBackoffActive
        ? ATLAS_BACKOFF_INTERVAL_MS
        : ATLAS_MIN_UPLOAD_INTERVAL_MS;
    if (atlasDirty &&
        nowMs - lastAtlasUploadMs < minIntervalMs + atlasExtraDelayMs) {
      return;
    }
    lastAtlasUploadMs = nowMs;
    atlasFramesSinceUpload = 0;
    atlasDirty = false;
    int mipLevels = currentMipmapLevels();
    if (mipLevels != blockAtlasMipLevels) {
      loadBlockAtlas();
      return;
    }

    try {
      Minecraft mc = Minecraft.getInstance();
      if (mc == null || mc.getTextureManager() == null)
        return;
      AbstractTexture atlasTexture = mc.getTextureManager().getTexture(BLOCKS_ATLAS_ID);
      if (atlasTexture == null)
        return;
      int glTexId = 0;
      var gpuTex = atlasTexture.getTexture();
      if (gpuTex instanceof GlTexture glTex) {
        glTexId = glTex.glId();
      }
      if (glTexId == 0)
        return;
      int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTexId);
      int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
          GL11.GL_TEXTURE_WIDTH);
      int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
          GL11.GL_TEXTURE_HEIGHT);
      if (width != blockAtlasWidth || height != blockAtlasHeight) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        loadBlockAtlas();
        return;
      }
      int dataSize = width * height * 4;
      if (atlasPixelBuffer == null || atlasPixelBuffer.capacity() < dataSize) {
        atlasPixelBuffer = BufferUtils.createByteBuffer(dataSize);
      }
      atlasPixelBuffer.clear();
      long syncStartNs = System.nanoTime();
      GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
          GL11.GL_UNSIGNED_BYTE, atlasPixelBuffer);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
      if (atlasReadbackScratch == null || atlasReadbackScratch.length < dataSize) {
        atlasReadbackScratch = new byte[dataSize];
      }
      atlasPixelBuffer.get(atlasReadbackScratch, 0, dataSize);
      uploadAtlasDiff(width, height, dataSize);
      trackAtlasSyncCost(syncStartNs);
    } catch (Exception e) {
    }
  }

  private void uploadAtlasDiff(int width, int height, int dataSize) {
    byte[] fresh = atlasReadbackScratch;
    byte[] mirror = atlasUploadData;
    if (mirror == null || mirror.length != dataSize) {
      NativeBridge.nUpdateTexture2D(blockAtlasTexture, width, height, fresh);
      if (atlasUploadData == null || atlasUploadData.length < dataSize) {
        atlasUploadData = new byte[dataSize];
      }
      System.arraycopy(fresh, 0, atlasUploadData, 0, dataSize);
      return;
    }
    int rowBytes = width * 4;
    int dirtyRows = 0;
    java.util.ArrayList<int[]> dirtySpans = new java.util.ArrayList<>(8);
    int spanStart = -1;
    int slabRows = 16;
    int slabBytes = rowBytes * slabRows;
    for (int row = 0; row < height; ) {
      int rowsLeft = height - row;
      boolean slabDirty;
      if (rowsLeft >= slabRows) {
        int off = row * rowBytes;
        slabDirty = !java.util.Arrays.equals(mirror, off, off + slabBytes,
            fresh, off, off + slabBytes);
      } else {
        slabDirty = false;
        for (int r = row; r < height; r++) {
          int rOff = r * rowBytes;
          if (!java.util.Arrays.equals(mirror, rOff, rOff + rowBytes, fresh,
              rOff, rOff + rowBytes)) {
            slabDirty = true;
            break;
          }
        }
      }
      if (!slabDirty) {
        if (spanStart >= 0) {
          dirtySpans.add(new int[] { spanStart, row });
          spanStart = -1;
        }
        row += rowsLeft >= slabRows ? slabRows : rowsLeft;
        continue;
      }
      int slabEnd = Math.min(height, row + slabRows);
      for (int r = row; r < slabEnd; r++) {
        int rOff = r * rowBytes;
        boolean rowDirty = !java.util.Arrays.equals(mirror, rOff,
            rOff + rowBytes, fresh, rOff, rOff + rowBytes);
        if (rowDirty) {
          dirtyRows++;
          if (spanStart < 0) {
            spanStart = r;
          }
        } else if (spanStart >= 0) {
          dirtySpans.add(new int[] { spanStart, r });
          spanStart = -1;
        }
      }
      row = slabEnd;
    }
    if (spanStart >= 0) {
      dirtySpans.add(new int[] { spanStart, height });
    }
    if (dirtySpans.isEmpty()) {
      return;
    }
    for (int[] span : dirtySpans) {
      int y0 = span[0];
      int y1 = span[1];
      NativeBridge.nUpdateTexture2DRegion(blockAtlasTexture, width, 0, y0,
          width, y1 - y0, fresh);
    }
    if (dirtyRows > 0) {
      System.arraycopy(fresh, 0, mirror, 0, dataSize);
    }
  }

  private void trackAtlasSyncCost(long syncStartNs) {
    double costMs = (System.nanoTime() - syncStartNs) / 1_000_000.0;
    if (costMs > ATLAS_ADAPTIVE_COST_THRESHOLD_MS) {
      long extra = (long) ((costMs - ATLAS_ADAPTIVE_COST_THRESHOLD_MS)
          * ATLAS_ADAPTIVE_DELAY_SCALE);
      atlasExtraDelayMs = Math.min(ATLAS_ADAPTIVE_MAX_DELAY_MS, extra);
      MetalLogger.deepInfo("atlas_sync cost=%.1fms next_in=%.0fms", costMs,
          ATLAS_MIN_UPLOAD_INTERVAL_MS + atlasExtraDelayMs);
    } else {
      atlasExtraDelayMs = 0;
    }
  }

  public void setAtlasBackoffActive(boolean backoff) {
    atlasBackoffActive = backoff;
  }

  public void updateLightmap() {
    if (lightmapTexture == 0)
      return;
    Minecraft mc = Minecraft.getInstance();
    long gameTime = mc != null && mc.level != null ? mc.level.getGameTime()
        : Long.MIN_VALUE;
    if (gameTime == lastLightmapObservedGameTime)
      return;
    lastLightmapObservedGameTime = gameTime;
    if (lastUploadedLightmapGameTime != Long.MIN_VALUE &&
        gameTime != Long.MIN_VALUE &&
        gameTime - lastUploadedLightmapGameTime < LIGHTMAP_MIN_GAME_TIME_DELTA) {
      return;
    }
    lightmapFramesSinceUpload++;
    if (lightmapFramesSinceUpload < LIGHTMAP_MIN_UPLOAD_INTERVAL)
      return;
    lightmapFramesSinceUpload = 0;
    uploadLightmap();
    lastUploadedLightmapGameTime = gameTime;
  }

  public void loadLightmap() {
    lightmapFramesSinceUpload = 0;
    lastLightmapObservedGameTime = Long.MIN_VALUE;
    lastUploadedLightmapGameTime = Long.MIN_VALUE;
    uploadLightmap();
  }

  public boolean isBlockAtlasLoaded() {
    return blockAtlasLoaded;
  }

  public boolean isLightmapLoaded() {
    return lightmapLoaded;
  }

  public boolean isUsingFallbackBlockAtlas() {
    return usingFallbackBlockAtlas;
  }

  public long getBlockAtlasTexture() {
    return blockAtlasTexture;
  }

  public long getLightmapTexture() {
    return lightmapTexture;
  }

  private int currentMipmapLevels() {
    try {
      Minecraft mc = Minecraft.getInstance();
      if (mc != null && mc.options != null && mc.options.mipmapLevels() != null) {
        return Math.max(1, mc.options.mipmapLevels().get() + 1);
      }
    } catch (Exception ignored) {
    }
    return 1;
  }

  private void uploadLightmap() {
    try {
      Minecraft mc = Minecraft.getInstance();
      if (mc == null || mc.gameRenderer == null) {
        return;
      }
      var lightmapView = mc.gameRenderer.levelLightmap();
      if (lightmapView == null) {
        return;
      }
      int glTexId = 0;
      var gpuTexture = lightmapView.texture();
      if (gpuTexture instanceof GlTexture glTexture) {
        glTexId = glTexture.glId();
      }
      if (glTexId == 0) {
        return;
      }
      int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTexId);
      int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
          GL11.GL_TEXTURE_WIDTH);
      int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
          GL11.GL_TEXTURE_HEIGHT);
      if (width <= 0 || height <= 0) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        return;
      }
      int dataSize = width * height * 4;
      if (lightmapPixelBuffer == null ||
          lightmapPixelBuffer.capacity() < dataSize) {
        lightmapPixelBuffer = BufferUtils.createByteBuffer(dataSize);
      }
      lightmapPixelBuffer.clear();
      GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
          GL11.GL_UNSIGNED_BYTE, lightmapPixelBuffer);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
      if (lightmapUploadData == null || lightmapUploadData.length < dataSize) {
        lightmapUploadData = new byte[dataSize];
      }
      lightmapPixelBuffer.get(lightmapUploadData, 0, dataSize);
      if (lightmapTexture == 0 || width != lightmapWidth ||
          height != lightmapHeight) {
        long newTexture = NativeBridge.nCreateTexture2D(
            deviceHandle, width, height, 1, lightmapUploadData);
        if (newTexture == 0) {
          return;
        }
        if (lightmapTexture != 0 && lightmapTexture != newTexture) {
          NativeBridge.nDestroyTexture2D(lightmapTexture);
        }
        lightmapTexture = newTexture;
        lightmapWidth = width;
        lightmapHeight = height;          MetalLogger.info("lightmap weady: %dx%d h=%d", width, height, newTexture);
      } else {
        NativeBridge.nUpdateTexture2D(lightmapTexture, width, height,
            lightmapUploadData);
      }
      lightmapLoaded = true;
    } catch (Exception e) {
      MetalLogger.error("lightmap load fail: %s", e.getMessage());
    }
  }

  public void destroy() {
    if (blockAtlasTexture != 0) {
      NativeBridge.nDestroyTexture2D(blockAtlasTexture);
      blockAtlasTexture = 0;
    }
    if (lightmapTexture != 0) {
      NativeBridge.nDestroyTexture2D(lightmapTexture);
      lightmapTexture = 0;
    }
    blockAtlasLoaded = false;
    lightmapLoaded = false;
    usingFallbackBlockAtlas = false;
    blockAtlasWidth = 0;
    blockAtlasHeight = 0;
    blockAtlasMipLevels = 1;
    lightmapWidth = 0;
    lightmapHeight = 0;
    lastLightmapObservedGameTime = Long.MIN_VALUE;
    lastUploadedLightmapGameTime = Long.MIN_VALUE;
    atlasUploadData = null;
    atlasReadbackScratch = null;
    lightmapUploadData = null;
    atlasPixelBuffer = null;
    lightmapPixelBuffer = null;
    atlasFramesSinceUpload = 0;
    lastAtlasUploadMs = 0L;
    atlasBackoffActive = false;
    atlasExtraDelayMs = 0L;
    atlasDirty = true;
  }
}
