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
  private int lightmapWidth;
  private int lightmapHeight;
  private byte[] atlasUploadData = null;
  private byte[] lightmapUploadData = null;
  private ByteBuffer atlasPixelBuffer = null;
  private ByteBuffer lightmapPixelBuffer = null;
  public static volatile boolean atlasDirty = true;

  private static final int ATLAS_MIN_UPLOAD_INTERVAL = 2;
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
        MetalLogger.info("Block atlas texture not available yet");
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
        MetalLogger.info("Block atlas GL texture ID is 0");
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
        MetalLogger.info("Block atlas dimensions invalid: %dx%d", width,
            height);
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
      long newTexture = NativeBridge.nCreateTexture2D(deviceHandle, width, height, data);
      if (newTexture != 0) {
        if (blockAtlasTexture != 0 && blockAtlasTexture != newTexture) {
          NativeBridge.nDestroyTexture2D(blockAtlasTexture);
        }
        blockAtlasTexture = newTexture;
        blockAtlasWidth = width;
        blockAtlasHeight = height;
        blockAtlasLoaded = true;
        usingFallbackBlockAtlas = false;
        MetalLogger.info("Block atlas loaded: %dx%d, Metal handle=%d", width,
            height, newTexture);
      } else {
        MetalLogger.error("Failed to create Metal texture for block atlas");
        blockAtlasLoaded = true;
        usingFallbackBlockAtlas = true;
      }
    } catch (Exception e) {
      MetalLogger.error("Error loading block atlas: %s", e.getMessage());
      blockAtlasLoaded = true;
      usingFallbackBlockAtlas = true;
    }
  }

  public void updateBlockAtlas() {
    if (blockAtlasTexture == 0 || usingFallbackBlockAtlas)
      return;
    atlasFramesSinceUpload++;

    if (!atlasDirty)
      return;

    if (atlasFramesSinceUpload < ATLAS_MIN_UPLOAD_INTERVAL)
      return;
    atlasFramesSinceUpload = 0;
    atlasDirty = false;

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
      GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
          GL11.GL_UNSIGNED_BYTE, atlasPixelBuffer);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
      if (atlasUploadData == null || atlasUploadData.length < dataSize) {
        atlasUploadData = new byte[dataSize];
      }
      atlasPixelBuffer.get(atlasUploadData, 0, dataSize);
      NativeBridge.nUpdateTexture2D(blockAtlasTexture, width, height, atlasUploadData);
    } catch (Exception e) {
    }
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
    if (lastUploadedLightmapGameTime != Long.MIN_VALUE
        && gameTime != Long.MIN_VALUE
        && gameTime - lastUploadedLightmapGameTime < LIGHTMAP_MIN_GAME_TIME_DELTA) {
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
      if (lightmapPixelBuffer == null || lightmapPixelBuffer.capacity() < dataSize) {
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
      if (lightmapTexture == 0 || width != lightmapWidth || height != lightmapHeight) {
        long newTexture = NativeBridge.nCreateTexture2D(deviceHandle, width, height, lightmapUploadData);
        if (newTexture == 0) {
          return;
        }
        if (lightmapTexture != 0 && lightmapTexture != newTexture) {
          NativeBridge.nDestroyTexture2D(lightmapTexture);
        }
        lightmapTexture = newTexture;
        lightmapWidth = width;
        lightmapHeight = height;
        MetalLogger.info("Lightmap loaded: %dx%d, Metal handle=%d", width,
            height, newTexture);
      } else {
        NativeBridge.nUpdateTexture2D(lightmapTexture, width, height, lightmapUploadData);
      }
      lightmapLoaded = true;
    } catch (Exception e) {
      MetalLogger.error("Error loading lightmap: %s", e.getMessage());
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
    lightmapWidth = 0;
    lightmapHeight = 0;
    lastLightmapObservedGameTime = Long.MIN_VALUE;
    lastUploadedLightmapGameTime = Long.MIN_VALUE;
    atlasUploadData = null;
    lightmapUploadData = null;
    atlasPixelBuffer = null;
    lightmapPixelBuffer = null;
    atlasFramesSinceUpload = 0;
    atlasDirty = true;
  }

}
