package com.pebbles_boon.metalrender.render;

import com.mojang.blaze3d.opengl.GlTexture;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.backend.MetalRenderer;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

public class MetalWeatherRenderer {
    private static final Identifier RAIN_LOCATION = Identifier.fromNamespaceAndPath("minecraft",
            "textures/environment/rain.png");
    private static final Identifier SNOW_LOCATION = Identifier.fromNamespaceAndPath("minecraft",
            "textures/environment/snow.png");

    private static final int VERTEX_STRIDE = 32;
    private static final int MAX_VERTS = 65536;

    private long device;
    private boolean active;
    private int frameCount;
    private final ByteBuffer staging;
    private final long[] vbufs = new long[3];
    private int vtxCount;
    private long cachedWeatherPipeline;
    private long rainTexture;
    private int rainGlId = -1;
    private long snowTexture;
    private int snowGlId = -1;

    private java.lang.reflect.Field weatherRendererField;
    private boolean reflectionWarned;
    private final WeatherRenderState reusableState = new WeatherRenderState();

    public MetalWeatherRenderer() {
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
        }
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public void render(long ctx, Camera camera, float tickDelta) {
        if (!active || ctx == 0 || device == 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.levelRenderer == null) {
            return;
        }
        WeatherEffectRenderer vanilla = vanillaRenderer(mc);
        if (vanilla == null) {
            frameCount++;
            return;
        }

        reusableState.reset();
        int tick = (int) (mc.level.getGameTime() & 0x7FFFFFFFL);
        try {
            vanilla.extractRenderState(mc.level, tick, tickDelta, camera.position(), reusableState);
        } catch (Exception e) {
            frameCount++;
            return;
        }
        if (reusableState.intensity <= 0.0f
                && reusableState.rainColumns.isEmpty()
                && reusableState.snowColumns.isEmpty()) {
            frameCount++;
            return;
        }

        staging.clear();
        vtxCount = 0;
        double camX = camera.position().x;
        double camY = camera.position().y;
        double camZ = camera.position().z;

        emitColumns(reusableState.rainColumns, camX, camY, camZ,
                1.0f, reusableState.radius, reusableState.intensity);
        int rainCount = vtxCount;
        int snowStart = vtxCount;

        emitColumns(reusableState.snowColumns, camX, camY, camZ,
                0.8f, reusableState.radius, reusableState.intensity);
        int snowCount = vtxCount - snowStart;
        if (vtxCount == 0) {
            frameCount++;
            return;
        }

        if (frameCount < 3) {
            MetalLogger.info(
                    "metal weather: intensity=%.2f radius=%d rainCols=%d snowCols=%d verts=%d (vanilla-port)",
                    reusableState.intensity, reusableState.radius,
                    reusableState.rainColumns.size(), reusableState.snowColumns.size(), vtxCount);
        }

        staging.flip();
        long vb = vbufs[frameCount % 3];
        if (vb != 0) {
            NativeBridge.nUploadBufferDataDirect(vb, staging, 0, vtxCount * VERTEX_STRIDE);
        }
        MetalRenderer renderer = MetalRenderClient.getRenderer();
        if (renderer == null) {
            frameCount++;
            return;
        }

        long pipeline = cachedWeatherPipeline;
        if (pipeline == 0) {
            pipeline = weatherPipelineHandle(renderer.getHandle());
            if (pipeline != 0) {
                cachedWeatherPipeline = pipeline;
            }
        }
        if (pipeline == 0) {
            frameCount++;
            return;
        }
        NativeBridge.nSetPipelineState(ctx, pipeline);
        NativeBridge.nSetChunkOffset(ctx, 0.0f, 0.0f, 0.0f);
        NativeBridge.nSetEntityOverlay(ctx, 0.0f, 0.0f, 1.0f);
        NativeBridge.nSetWaterFog(ctx, 0.0f);

        if (rainCount > 0) {
            long tex = getOrCreatePrecipTexture(true);
            if (tex != 0) {
                NativeBridge.nBindEntityTexture(ctx, tex);
                NativeBridge.nDrawEntityBuffer(ctx, vb, rainCount, 0, 0x8);
            }
        }
        if (snowCount > 0) {
            long tex = getOrCreatePrecipTexture(false);
            if (tex != 0) {
                NativeBridge.nBindEntityTexture(ctx, tex);
                NativeBridge.nDrawEntityBuffer(ctx, vb, snowCount, snowStart, 0x8);
            }
        }
        frameCount++;
    }

    private void emitColumns(java.util.List<WeatherEffectRenderer.ColumnInstance> columns,
            double camX, double camY, double camZ,
            float alphaScale, int radius, float intensity) {
        if (columns.isEmpty() || radius <= 0) {
            return;
        }
        float radiusSq = (float) radius * radius;
        int floorCamX = (int) Math.floor(camX);
        int floorCamZ = (int) Math.floor(camZ);
        for (WeatherEffectRenderer.ColumnInstance col : columns) {
            float dx = (float) (col.x() + 0.5 - camX);
            float dz = (float) (col.z() + 0.5 - camZ);
            float distSq = dx * dx + dz * dz;
            float closeness = Math.min(distSq / radiusSq, 1.0f);

            float alpha = (alphaScale + closeness * (0.5f - alphaScale)) * intensity;
            if (alpha < 0.1f) {

                continue;
            }
            int color = ((int) (clamp01(alpha) * 255.0f) << 24) | 0x00FFFFFF;

            int tabIdx = (col.z() - floorCamZ + 16) * 32 + (col.x() - floorCamX) + 16;
            if (tabIdx < 0 || tabIdx >= 1024) {
                continue;
            }

            int bdx = col.x() - floorCamX;
            int bdz = col.z() - floorCamZ;
            float len = (float) Math.sqrt((double) bdx * bdx + (double) bdz * bdz);
            float dirX;
            float dirZ;
            if (len < 1.0e-6f) {
                dirX = 0.0f;
                dirZ = 0.0f;
            } else {
                dirX = -bdz / len / 2.0f;
                dirZ = bdx / len / 2.0f;
            }
            float x0 = dx - dirX;
            float x1 = dx + dirX;
            float z0 = dz - dirZ;
            float z1 = dz + dirZ;
            float yTop = col.topY() - (float) camY;
            float yBot = col.bottomY() - (float) camY;
            if (yTop <= yBot) {
                continue;
            }

            float uA = col.uOffset();
            float uB = col.uOffset() + 1.0f;
            float vTopRow = col.bottomY() * 0.25f + col.vOffset();
            float vBotRow = col.topY() * 0.25f + col.vOffset();

            emitTiledQuad(x0, yTop, z0, x1, z1, yBot,
                    uA, uB, vTopRow, vBotRow, color, col.lightCoords());
            if (vtxCount + 72 > MAX_VERTS) {
                break;
            }
        }
    }

    private void emitTiledQuad(float x0, float yTop, float z0, float x1,
            float z1, float yBot,
            float uA, float uB, float vTopRow, float vBotRow,
            int color, int light) {

        float uf = uA - (float) Math.floor(uA);
        float[][] uPieces;
        if (uf < 1.0e-6f || uf > 1.0f - 1.0e-6f) {
            uPieces = new float[][] { { 0.0f, 1.0f, 0.0f, 1.0f } };
        } else {
            uPieces = new float[][] { { uf, 1.0f, 0.0f, 1.0f - uf }, { 0.0f, uf, 1.0f - uf, 1.0f } };
        }

        float vSpan = vBotRow - vTopRow;
        java.util.ArrayList<Float> cuts = new java.util.ArrayList<>();
        cuts.add(0.0f);
        if (vSpan > 1.0e-9f) {
            for (int k = (int) Math.floor(vTopRow) + 1; (float) k < vBotRow; k++) {
                cuts.add(((float) k - vTopRow) / vSpan);
            }
        } else if (vSpan < -1.0e-9f) {
            for (int k = (int) Math.ceil(vTopRow) - 1; (float) k > vBotRow; k--) {
                cuts.add(((float) k - vTopRow) / vSpan);
            }
        }
        cuts.add(1.0f);
        for (int i = 0; i + 1 < cuts.size(); i++) {
            float t0 = cuts.get(i);
            float t1 = cuts.get(i + 1);
            float ya = yTop + (yBot - yTop) * t0;
            float yb = yTop + (yBot - yTop) * t1;
            float va = vTopRow + vSpan * t0;
            float vb = vTopRow + vSpan * t1;
            float fa = va - (float) Math.floor(va);
            float fb = vb - (float) Math.floor(vb);
            if (fb == 0.0f && vb > va) {

                fb = 1.0f;
            }
            for (float[] up : uPieces) {
                float px0 = x0 + (x1 - x0) * up[2];
                float pz0 = z0 + (z1 - z0) * up[2];
                float px1 = x0 + (x1 - x0) * up[3];
                float pz1 = z0 + (z1 - z0) * up[3];
                quad(px0, ya, pz0, up[0], fa,
                        px1, ya, pz1, up[1], fa,
                        px1, yb, pz1, up[1], fb,
                        px0, yb, pz0, up[0], fb,
                        color, light);
                if (vtxCount + 12 > MAX_VERTS) {
                    return;
                }
            }
        }
    }

    private void quad(float x0, float y0, float z0, float u0, float v0,
            float x1, float y1, float z1, float u1, float v1,
            float x2, float y2, float z2, float u2, float v2,
            float x3, float y3, float z3, float u3, float v3,
            int color, int light) {

        vert(x0, y0, z0, u0, v0, color, light);
        vert(x1, y1, z1, u1, v1, color, light);
        vert(x2, y2, z2, u2, v2, color, light);
        vert(x0, y0, z0, u0, v0, color, light);
        vert(x2, y2, z2, u2, v2, color, light);
        vert(x3, y3, z3, u3, v3, color, light);
    }

    private void vert(float x, float y, float z, float u, float v, int color, int light) {
        if (vtxCount >= MAX_VERTS || staging.remaining() < VERTEX_STRIDE) {
            return;
        }
        staging.putFloat(x);
        staging.putFloat(y);
        staging.putFloat(z);

        staging.putShort((short) ((int) (clamp01(u) * 32767.0f) & 0x7FFF));
        staging.putShort((short) ((int) (clamp01(v) * 32767.0f) & 0x7FFF));
        staging.put((byte) ((color >> 16) & 0xFF));
        staging.put((byte) ((color >> 8) & 0xFF));
        staging.put((byte) (color & 0xFF));
        staging.put((byte) ((color >> 24) & 0xFF));

        float nl = (float) Math.sqrt(x * x + z * z);
        float nx = nl < 1.0e-6f ? 0.0f : x / nl;
        float nz = nl < 1.0e-6f ? 1.0f : z / nl;
        staging.put((byte) (int) (nx * 127.0f));
        staging.put((byte) 0);
        staging.put((byte) (int) (nz * 127.0f));
        staging.put((byte) 0);
        staging.putShort((short) 0);
        staging.putShort((short) 0);
        staging.putShort((short) (light & 0xFFFF));
        staging.putShort((short) ((light >> 16) & 0xFFFF));
        vtxCount++;
    }

    private static float clamp01(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }

    private static long weatherPipelineHandle(long rendererHandle) {
        try {
            long h = NativeBridge.nGetWeatherPipelineHandle(rendererHandle);
            if (h != 0) {
                return h;
            }
        } catch (Throwable ignored) {

        }
        try {
            return NativeBridge.nGetParticlePipelineHandle(rendererHandle);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private WeatherEffectRenderer vanillaRenderer(Minecraft mc) {
        try {
            if (weatherRendererField == null && !reflectionWarned) {
                try {
                    weatherRendererField = net.minecraft.client.renderer.LevelRenderer.class
                            .getDeclaredField("weatherEffectRenderer");
                    weatherRendererField.setAccessible(true);
                } catch (Exception e) {
                    reflectionWarned = true;
                    MetalLogger.warn("weather: vanilla accessor missing: %s", e.getMessage());
                    return null;
                }
            }
            if (weatherRendererField == null) {
                return null;
            }
            Object o = weatherRendererField.get(mc.levelRenderer);
            if (o instanceof WeatherEffectRenderer w) {
                return w;
            }
        } catch (Exception e) {
            if (!reflectionWarned) {
                reflectionWarned = true;
                MetalLogger.warn("weather: vanilla renderer unreadable: %s", e.getMessage());
            }
        }
        return null;
    }

    private long getOrCreatePrecipTexture(boolean rain) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getTextureManager() == null) {
                return 0;
            }
            Identifier id = rain ? RAIN_LOCATION : SNOW_LOCATION;
            AbstractTexture tex = mc.getTextureManager().getTexture(id);
            int glId = 0;
            if (tex != null && tex.getTexture() instanceof GlTexture gl) {
                glId = gl.glId();
            }
            if (glId == 0) {
                return 0;
            }
            if (rain && glId == rainGlId && rainTexture != 0) {
                return rainTexture;
            }
            if (!rain && glId == snowGlId && snowTexture != 0) {
                return snowTexture;
            }
            int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId);
            int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (w <= 0 || h <= 0 || w > 1024 || h > 1024) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
                return 0;
            }
            ByteBuffer pixels = BufferUtils.createByteBuffer(w * h * 4);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
            byte[] data = new byte[w * h * 4];
            pixels.get(data);

            long metalTex = NativeBridge.nCreateTexture2D(device, w, h, 1, data);
            if (rain) {
                if (rainTexture != 0) {
                    NativeBridge.nDestroyTexture2D(rainTexture);
                }
                rainTexture = metalTex;
                rainGlId = glId;
            } else {
                if (snowTexture != 0) {
                    NativeBridge.nDestroyTexture2D(snowTexture);
                }
                snowTexture = metalTex;
                snowGlId = glId;
            }
            return metalTex;
        } catch (Exception e) {
            MetalLogger.warn("weather tex fail: %s", e.getMessage());
            return 0;
        }
    }

    public void shutdown() {
        active = false;
        cachedWeatherPipeline = 0;
        if (rainTexture != 0) {
            NativeBridge.nDestroyTexture2D(rainTexture);
            rainTexture = 0;
        }
        if (snowTexture != 0) {
            NativeBridge.nDestroyTexture2D(snowTexture);
            snowTexture = 0;
        }
        rainGlId = -1;
        snowGlId = -1;
        for (int i = 0; i < 3; i++) {
            if (vbufs[i] != 0) {
                NativeBridge.nDestroyBuffer(vbufs[i]);
                vbufs[i] = 0;
            }
        }
        device = 0;
        MetalLogger.info("weather wendewer shut down");
    }
}
