package com.pebbles_boon.metalrender.render;

import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CloudRenderer;

























public class MetalCloudRenderer {
  private static final int VERTEX_STRIDE = 32;
  private static final int MAX_VERTS = 131072;
  private static final float CELL = 12.0f;
  private static final float THICK = 4.0f;


  private static final float SHADE_DOWN = 0.7f;
  private static final float SHADE_UP = 1.0f;
  private static final float SHADE_NS = 0.8f;
  private static final float SHADE_WE = 0.9f;

  private static final int POS_ABOVE = 0;
  private static final int POS_INSIDE = 1;
  private static final int POS_BELOW = 2;

  private static final net.minecraft.resources.Identifier CLOUDS_PNG =
      net.minecraft.resources.Identifier.withDefaultNamespace("textures/environment/clouds.png");

  private long device;
  private boolean active;
  private int frameCount;
  private final ByteBuffer staging;
  private final long[] vbufs = new long[3];
  private int vtxCount;
  private long whiteTexture;

  private java.lang.reflect.Field cloudRendererField;
  private java.lang.reflect.Field textureField;
  private boolean reflectionWarned;


  private long[] fallbackCells;
  private int fallbackW;
  private int fallbackH;
  private long fallbackParsedAtMs;

  public MetalCloudRenderer() {
    staging = ByteBuffer.allocateDirect(MAX_VERTS * VERTEX_STRIDE)
        .order(ByteOrder.nativeOrder());
  }

  public void setup(long device) {
    this.device = device;
    if (device != 0) {
      for (int i = 0; i < 3; i++) {
        if (vbufs[i] == 0) {
          vbufs[i] = NativeBridge.nCreateBuffer(device, MAX_VERTS * VERTEX_STRIDE, 0);
        }
      }
      if (whiteTexture == 0) {
        byte[] white = new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255};
        whiteTexture = NativeBridge.nCreateTexture2D(device, 1, 1, 1, white);
      }
    }
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public boolean isActive() {
    return active;
  }

  public void render(long ctx, Camera camera, float tickDelta) {
    if (!active || ctx == 0 || device == 0) {
      return;
    }
    Minecraft mc = Minecraft.getInstance();
    if (mc == null || mc.level == null || mc.options == null || mc.levelRenderer == null) {
      return;
    }
    net.minecraft.client.CloudStatus status;
    try {
      status = mc.options.cloudStatus().get();
    } catch (Exception e) {
      return;
    }
    if (status == net.minecraft.client.CloudStatus.OFF) {
      frameCount++;
      return;
    }
    boolean fancy = status == net.minecraft.client.CloudStatus.FANCY;


    float cloudHeight = 192.0f;
    int cloudArgb = 0xFFFFFFFF;
    Object probe = null;
    try {
      probe = camera.attributeProbe();
      if (probe != null) {
        Class<?> attrs = Class.forName("net.minecraft.world.attribute.EnvironmentAttributes");
        cloudArgb = getIntAttr(probe, attrs, "CLOUD_COLOR", tickDelta, cloudArgb);
        cloudHeight = getFloatAttr(probe, attrs, "CLOUD_HEIGHT", tickDelta, cloudHeight);
      }
    } catch (Exception ignored) {
    }
    int cloudAlpha = (cloudArgb >>> 24) & 0xFF;
    if (cloudAlpha <= 0) {
      frameCount++;
      return;
    }

    int cloudRange = 128;
    try {
      cloudRange = mc.options.cloudRange().get();
    } catch (Exception ignored) {
    }
    cloudRange = Math.max(2, Math.min(128, cloudRange));


    long[] cells = null;
    int texW = 0;
    int texH = 0;
    CloudRenderer.TextureData texData = vanillaTextureData(mc);
    if (texData != null && texData.cells() != null
        && texData.width() > 0 && texData.height() > 0) {
      cells = texData.cells();
      texW = texData.width();
      texH = texData.height();
    } else {
      ensureFallbackCopy(mc);
      if (fallbackCells != null && fallbackW > 0 && fallbackH > 0) {
        cells = fallbackCells;
        texW = fallbackW;
        texH = fallbackH;
      }
    }
    if (cells == null || texW <= 0 || texH <= 0) {
      frameCount++;
      return;
    }


    double camX = camera.position().x;
    double camY = camera.position().y;
    double camZ = camera.position().z;
    long gameTime = mc.level.getGameTime();
    float scroll = (gameTime % (texW * 400L)) + tickDelta;
    double sx = camX + scroll * 0.03;
    double sz = camZ + 3.96;
    double wrapX = texW * 12.0;
    double wrapZ = texH * 12.0;
    double rx = sx - Math.floor(sx / wrapX) * wrapX;
    double rz = sz - Math.floor(sz / wrapZ) * wrapZ;
    int cellX = (int) Math.floor(rx / 12.0);
    int cellZ = (int) Math.floor(rz / 12.0);
    float fracX = (float) (rx - cellX * 12.0);
    float fracZ = (float) (rz - cellZ * 12.0);

    float relY = cloudHeight - (float) camY;
    int relPos;
    if (relY + 4.0f < 0.0f) {
      relPos = POS_ABOVE;
    } else if (relY > 0.0f) {
      relPos = POS_BELOW;
    } else {
      relPos = POS_INSIDE;
    }


    float rangeBlocks = cloudRange * 16.0f;
    float cloudEndFog = rangeBlocks;
    try {
      if (probe != null) {
        Class<?> attrs = Class.forName("net.minecraft.world.attribute.EnvironmentAttributes");
        cloudEndFog = Math.min(rangeBlocks, getFloatAttr(probe, attrs,
            "CLOUD_FOG_END_DISTANCE", tickDelta, rangeBlocks));
      }
    } catch (Exception ignored) {
    }
    if (cloudEndFog <= 0.0f) {
      frameCount++;
      return;
    }



    int radius = Math.max(2, (int) Math.ceil(rangeBlocks / 12.0));
    float cullDist = cloudEndFog + 24.0f;

    float cr = ((cloudArgb >> 16) & 0xFF) / 255.0f;
    float cg = ((cloudArgb >> 8) & 0xFF) / 255.0f;
    float cb = (cloudArgb & 0xFF) / 255.0f;
    float ca = cloudAlpha / 255.0f;

    staging.clear();
    vtxCount = 0;



    for (int d = 0; d <= 2 * radius; d++) {
      for (int e = -d; e <= d; e++) {
        int m = d - Math.abs(e);
        if (m < 0 || m > radius) {
          continue;
        }
        if (e * e + m * m > radius * radius) {
          continue;
        }
        emitCell(cells, texW, texH, cellX, cellZ, e, -m, fancy, relPos,
            fracX, fracZ, relY, cr, cg, cb, ca, cloudEndFog, cullDist);
        if (m != 0) {
          emitCell(cells, texW, texH, cellX, cellZ, e, m, fancy, relPos,
              fracX, fracZ, relY, cr, cg, cb, ca, cloudEndFog, cullDist);
        }
        if (vtxCount + 60 > MAX_VERTS) {
          break;
        }
      }
      if (vtxCount + 60 > MAX_VERTS) {
        break;
      }
    }

    if (vtxCount == 0) {
      frameCount++;
      return;
    }

    staging.flip();
    long vb = vbufs[frameCount % 3];
    if (vb != 0) {
      NativeBridge.nUploadBufferDataDirect(vb, staging, 0, vtxCount * VERTEX_STRIDE);
    }
    NativeBridge.nSetChunkOffset(ctx, 0.0f, 0.0f, 0.0f);
    NativeBridge.nSetEntityOverlay(ctx, 0.0f, 0.0f, 1.0f);
    NativeBridge.nSetWaterFog(ctx, 0.0f);
    if (whiteTexture != 0) {
      NativeBridge.nBindEntityTexture(ctx, whiteTexture);
    }


    NativeBridge.nDrawEntityBuffer(ctx, vb, vtxCount, 0, 0x2);
    frameCount++;
  }

  private void emitCell(long[] cells, int texW, int texH,
      int baseX, int baseZ, int ox, int oz, boolean fancy, int relPos,
      float fracX, float fracZ, float relY,
      float cr, float cg, float cb, float ca,
      float cloudEndFog, float cullDist) {
    int tx = Math.floorMod(baseX + ox, texW);
    int tz = Math.floorMod(baseZ + oz, texH);
    long cell = cells[tx + tz * texW];
    if (cell == 0L) {
      return;
    }
    float x0 = ox * CELL - fracX;
    float z0 = oz * CELL - fracZ;

    float cx = x0 + CELL * 0.5f;
    float cz = z0 + CELL * 0.5f;
    float cy = relY + THICK * 0.5f;
    float centerDist = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
    if (centerDist > cullDist) {
      return;
    }
    float x1 = x0 + CELL;
    float z1 = z0 + CELL;
    float top = relY + THICK;
    float bot = relY;

    if (!fancy) {


      quad(x0, bot, z0, x1, bot, z0, x1, bot, z1, x0, bot, z1,
          0.0f, -1.0f, 0.0f, cr * SHADE_UP, cg * SHADE_UP, cb * SHADE_UP, ca,
          cloudEndFog);
      return;
    }








    if (relPos != POS_BELOW) {

      quad(x0, top, z0, x0, top, z1, x1, top, z1, x1, top, z0,
          0.0f, 1.0f, 0.0f, cr * SHADE_UP, cg * SHADE_UP, cb * SHADE_UP, ca,
          cloudEndFog);
    }
    if (relPos != POS_ABOVE) {

      quad(x0, bot, z0, x1, bot, z0, x1, bot, z1, x0, bot, z1,
          0.0f, -1.0f, 0.0f, cr * SHADE_DOWN, cg * SHADE_DOWN, cb * SHADE_DOWN, ca,
          cloudEndFog);
    }
    if (isNorthEmpty(cell) && oz > 0) {
      quad(x0, bot, z0, x1, bot, z0, x1, top, z0, x0, top, z0,
          0.0f, 0.0f, -1.0f, cr * SHADE_NS, cg * SHADE_NS, cb * SHADE_NS, ca,
          cloudEndFog);
    }
    if (isSouthEmpty(cell) && oz < 0) {
      quad(x1, bot, z1, x0, bot, z1, x0, top, z1, x1, top, z1,
          0.0f, 0.0f, 1.0f, cr * SHADE_NS, cg * SHADE_NS, cb * SHADE_NS, ca,
          cloudEndFog);
    }
    if (isWestEmpty(cell) && ox > 0) {
      quad(x0, bot, z1, x0, bot, z0, x0, top, z0, x0, top, z1,
          -1.0f, 0.0f, 0.0f, cr * SHADE_WE, cg * SHADE_WE, cb * SHADE_WE, ca,
          cloudEndFog);
    }
    if (isEastEmpty(cell) && ox < 0) {
      quad(x1, bot, z0, x1, bot, z1, x1, top, z1, x1, top, z0,
          1.0f, 0.0f, 0.0f, cr * SHADE_WE, cg * SHADE_WE, cb * SHADE_WE, ca,
          cloudEndFog);
    }
  }

  private void quad(float x0, float y0, float z0, float x1, float y1, float z1,
      float x2, float y2, float z2, float x3, float y3, float z3,
      float nx, float ny, float nz, float r, float g, float b, float a,
      float cloudEndFog) {
    vert(x0, y0, z0, r, g, b, a, nx, ny, nz, cloudEndFog);
    vert(x1, y1, z1, r, g, b, a, nx, ny, nz, cloudEndFog);
    vert(x2, y2, z2, r, g, b, a, nx, ny, nz, cloudEndFog);
    vert(x0, y0, z0, r, g, b, a, nx, ny, nz, cloudEndFog);
    vert(x2, y2, z2, r, g, b, a, nx, ny, nz, cloudEndFog);
    vert(x3, y3, z3, r, g, b, a, nx, ny, nz, cloudEndFog);
  }

  private void vert(float x, float y, float z, float r, float g, float b, float a,
      float nx, float ny, float nz, float cloudEndFog) {
    if (vtxCount >= MAX_VERTS || staging.remaining() < VERTEX_STRIDE) {
      return;
    }

    float dist = (float) Math.sqrt(x * x + y * y + z * z);
    float fogF = dist / cloudEndFog;
    if (fogF < 0.0f) {
      fogF = 0.0f;
    } else if (fogF > 1.0f) {
      fogF = 1.0f;
    }
    float alpha = a * (1.0f - fogF);
    staging.putFloat(x);
    staging.putFloat(y);
    staging.putFloat(z);
    staging.putShort((short) 0);
    staging.putShort((short) 0);
    staging.put((byte) (clamp01(r) * 255.0f));
    staging.put((byte) (clamp01(g) * 255.0f));
    staging.put((byte) (clamp01(b) * 255.0f));
    staging.put((byte) (clamp01(alpha) * 255.0f));
    staging.put((byte) (int) (nx * 127.0f));
    staging.put((byte) (int) (ny * 127.0f));
    staging.put((byte) (int) (nz * 127.0f));
    staging.put((byte) 0);
    staging.putShort((short) 0);
    staging.putShort((short) 0);
    staging.putShort((short) 0xF0);
    staging.putShort((short) 0xF0);
    vtxCount++;
  }

  private static float clamp01(float v) {
    return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
  }

  private static boolean isNorthEmpty(long cell) {
    return ((cell >> 3) & 1L) != 0L;
  }

  private static boolean isEastEmpty(long cell) {
    return ((cell >> 2) & 1L) != 0L;
  }

  private static boolean isSouthEmpty(long cell) {
    return ((cell >> 1) & 1L) != 0L;
  }

  private static boolean isWestEmpty(long cell) {
    return (cell & 1L) != 0L;
  }

  private CloudRenderer.TextureData vanillaTextureData(Minecraft mc) {
    try {
      if (cloudRendererField == null && !reflectionWarned) {
        try {
          cloudRendererField =
              net.minecraft.client.renderer.LevelRenderer.class.getDeclaredField("cloudRenderer");
          cloudRendererField.setAccessible(true);
        } catch (Exception e) {
          reflectionWarned = true;
          MetalLogger.warn("clouds: vanilla accessor missing: %s", e.getMessage());
          return null;
        }
      }
      if (cloudRendererField == null) {
        return null;
      }
      Object vanilla = cloudRendererField.get(mc.levelRenderer);
      if (vanilla == null) {
        return null;
      }
      if (textureField == null) {
        textureField = vanilla.getClass().getDeclaredField("texture");
        textureField.setAccessible(true);
      }
      Object data = textureField.get(vanilla);
      if (data instanceof CloudRenderer.TextureData td) {
        return td;
      }
    } catch (Exception e) {
      if (!reflectionWarned) {
        reflectionWarned = true;
        MetalLogger.warn("clouds: vanilla data unreadable: %s", e.getMessage());
      }
    }
    return null;
  }







  private void ensureFallbackCopy(Minecraft mc) {
    long now = System.currentTimeMillis();
    if (fallbackCells != null && now - fallbackParsedAtMs < 10000L) {
      return;
    }
    fallbackParsedAtMs = now;
    try {
      var rm = mc.getResourceManager();
      try (var in = rm.open(CLOUDS_PNG);
          var img = com.mojang.blaze3d.platform.NativeImage.read(in)) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (w <= 0 || h <= 0 || w > 512 || h > 512) {
          return;
        }
        long[] arr = new long[w * h];
        for (int y = 0; y < h; y++) {
          for (int x = 0; x < w; x++) {
            int pixel = img.getPixel(x, y);
            if (net.minecraft.util.ARGB.alpha(pixel) < 10) {
              arr[x + y * w] = 0L;
            } else {
              boolean n = net.minecraft.util.ARGB.alpha(
                  img.getPixel(x, Math.floorMod(y - 1, h))) < 10;
              boolean e = net.minecraft.util.ARGB.alpha(
                  img.getPixel(Math.floorMod(x + 1, w), y)) < 10;
              boolean s = net.minecraft.util.ARGB.alpha(
                  img.getPixel(x, Math.floorMod(y + 1, h))) < 10;
              boolean ww = net.minecraft.util.ARGB.alpha(
                  img.getPixel(Math.floorMod(x - 1, w), y)) < 10;
              arr[x + y * w] = packCell(n, e, s, ww);
            }
          }
        }
        fallbackCells = arr;
        fallbackW = w;
        fallbackH = h;
      }
    } catch (Exception e) {
      if (!reflectionWarned) {
        reflectionWarned = true;
        MetalLogger.warn("clouds: png copy failed: %s", e.getMessage());
      }
    }
  }

  private static long packCell(boolean n, boolean e, boolean s, boolean w) {


    long v = 0x100L;
    if (n) {
      v |= 8L;
    }
    if (e) {
      v |= 4L;
    }
    if (s) {
      v |= 2L;
    }
    if (w) {
      v |= 1L;
    }
    return v;
  }

  private static int getIntAttr(Object probe, Class<?> attrs, String name,
      float tickDelta, int fallback) {
    try {
      java.lang.reflect.Field f = attrs.getField(name);
      java.lang.reflect.Method m = probe.getClass().getMethod("getValue",
          f.getType(), float.class);
      Object v = m.invoke(probe, f.get(null), tickDelta);
      if (v instanceof Number n) {
        return n.intValue();
      }
    } catch (Exception ignored) {
    }
    return fallback;
  }

  private static float getFloatAttr(Object probe, Class<?> attrs, String name,
      float tickDelta, float fallback) {
    try {
      java.lang.reflect.Field f = attrs.getField(name);
      java.lang.reflect.Method m = probe.getClass().getMethod("getValue",
          f.getType(), float.class);
      Object v = m.invoke(probe, f.get(null), tickDelta);
      if (v instanceof Number n) {
        return n.floatValue();
      }
    } catch (Exception ignored) {
    }
    return fallback;
  }

  public void shutdown() {
    active = false;
    fallbackCells = null;
    if (whiteTexture != 0) {
      NativeBridge.nDestroyTexture2D(whiteTexture);
      whiteTexture = 0;
    }
    for (int i = 0; i < 3; i++) {
      if (vbufs[i] != 0) {
        NativeBridge.nDestroyBuffer(vbufs[i]);
        vbufs[i] = 0;
      }
    }
    device = 0;
    MetalLogger.info("cloud wendewer shut down");
  }
}
