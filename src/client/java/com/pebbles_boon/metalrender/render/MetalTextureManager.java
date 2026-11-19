package com.pebbles_boon.metalrender.render;

import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.backend.metal.MetalTexture;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.performance.MetalRenderProfiler;
import com.pebbles_boon.metalrender.sodium.mixins.accessor.TextureAtlasAccessor;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

public class MetalTextureManager {
    private static final net.minecraft.resources.Identifier BLOCKS_ATLAS_ID = TextureAtlas.LOCATION_BLOCKS;
    private final long deviceHandle;
    private long blockAtlasTexture;
    private long lightmapTexture;
    private boolean blockAtlasBorrowed;
    private boolean lightmapBorrowed;
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
    private static final boolean ATLAS_RECTS = MetalRenderConfig.getBool("metalrender.atlasRects", true);
    private static final boolean ATLAS_BATCH_UPLOAD = MetalRenderConfig.getBool("metalrender.atlasBatchUpload", true);
    private static final boolean ATLAS_TIME_HEARTBEAT = MetalRenderConfig.getBool("metalrender.atlasTimeHeartbeat", true);
    private static final boolean ATLAS_RELEASE_BUFFERS = MetalRenderConfig.getBool("metalrender.atlasReleaseBuffers", true);
    private static final int RECT_BUFFER_BYTES = 4 * 1024 * 1024;
    private int[] animatedRects = new int[0];
    private int readFbo;
    private ByteBuffer rectBytes;
    private long lastFullReadMs;
    private GlTexture blockAtlasSource;
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
    private volatile double lastAtlasMs;
    private volatile double lastLightmapMs;

    public double getLastAtlasMs() {
        return lastAtlasMs;
    }

    public double getLastLightmapMs() {
        return lastLightmapMs;
    }

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
            if (MetalRenderClient.isBackendMode()) {
                if (atlasTexture instanceof TextureAtlas atlas) {
                    com.pebbles_boon.metalrender.render.chunk.CustomChunkMesher
                            .setAtlasSprites(((TextureAtlasAccessor) atlas).metalrender$getSprites());
                }
                borrowBlockAtlas(gpuTex);
                return;
            }
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
                blockAtlasSource = (GlTexture) gpuTex;
                lastFullReadMs = System.currentTimeMillis();
                animatedRects = new int[0];
                if (ATLAS_RECTS && atlasTexture instanceof TextureAtlas atlas) {
                    var rects = new java.util.ArrayList<Integer>();
                    for (var sprite : ((TextureAtlasAccessor) atlas).metalrender$getSprites()) {
                        if (sprite.contents().isAnimated()) {
                            rects.add(sprite.getX());
                            rects.add(sprite.getY());
                            rects.add(sprite.contents().width());
                            rects.add(sprite.contents().height());
                        }
                    }
                    animatedRects = rects.stream().mapToInt(Integer::intValue).toArray();
                    MetalLogger.info("atlas animated sprites: %d", animatedRects.length / 4);
                }
                releaseAtlasBuffers();
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

    private void borrowBlockAtlas(Object gpuTex) {
        blockAtlasLoaded = true;
        animatedRects = new int[0];
        if (!(gpuTex instanceof MetalTexture metalTex) || metalTex.handle() == 0) {
            usingFallbackBlockAtlas = true;
            return;
        }
        if (blockAtlasTexture != 0 && !blockAtlasBorrowed) {
            NativeBridge.nDestroyTexture2D(blockAtlasTexture);
        }
        blockAtlasTexture = metalTex.handle();
        blockAtlasBorrowed = true;
        blockAtlasWidth = metalTex.getWidth(0);
        blockAtlasHeight = metalTex.getHeight(0);
        blockAtlasMipLevels = metalTex.getMipLevels();
        usingFallbackBlockAtlas = false;
        lastFullReadMs = System.currentTimeMillis();
        atlasDirty = false;
    }

    public void updateBlockAtlas() {
        if (MetalRenderClient.isBackendMode()) {
            Minecraft mc = Minecraft.getInstance();
            AbstractTexture atlasTexture = mc != null && mc.getTextureManager() != null
                    ? mc.getTextureManager().getTexture(BLOCKS_ATLAS_ID)
                    : null;
            if (atlasTexture != null && atlasTexture.getTexture() instanceof MetalTexture metalTex
                    && metalTex.handle() != blockAtlasTexture) {
                borrowBlockAtlas(metalTex);
            }
            return;
        }
        if (blockAtlasTexture == 0 || usingFallbackBlockAtlas) return;
        long t0 = System.nanoTime();
        try {
            atlasFramesSinceUpload++;
            boolean dirty = atlasDirty;
            long now = System.currentTimeMillis();
            if (dirty) {
                if (atlasFramesSinceUpload < ATLAS_MIN_UPLOAD_INTERVAL) return;
            } else if (ATLAS_TIME_HEARTBEAT) {
                if (now - lastFullReadMs < 60000L) return;
            } else {
                if (atlasFramesSinceUpload < ATLAS_HEARTBEAT_FRAMES) return;
                long minIntervalMs = atlasBackoffActive ? ATLAS_BACKOFF_INTERVAL_MS : ATLAS_MIN_UPLOAD_INTERVAL_MS;
                if (now - lastAtlasUploadMs < minIntervalMs + atlasExtraDelayMs) return;
            }
            int mipLevels = currentMipmapLevels();
            if (mipLevels != blockAtlasMipLevels) {
                loadBlockAtlas();
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getTextureManager() == null) return;
            AbstractTexture atlasTexture = mc.getTextureManager().getTexture(BLOCKS_ATLAS_ID);
            if (atlasTexture == null || !(atlasTexture.getTexture() instanceof GlTexture glTex) || glTex.glId() == 0) return;
            if (ATLAS_RECTS && glTex != blockAtlasSource) {
                loadBlockAtlas();
                return;
            }
            int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTex.glId());
            try {
                int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
                if (width != blockAtlasWidth || height != blockAtlasHeight) {
                    loadBlockAtlas();
                    return;
                }
                if (ATLAS_RECTS && dirty && animatedRects.length > 0 && updateAnimatedRects(glTex.glId())) {
                    atlasDirty = false;
                    atlasFramesSinceUpload = 0;
                    lastAtlasUploadMs = now;
                    return;
                }
                int dataSize = width * height * 4;
                if (atlasPixelBuffer == null || atlasPixelBuffer.capacity() < dataSize) {
                    atlasPixelBuffer = BufferUtils.createByteBuffer(dataSize);
                }
                if (atlasReadbackScratch == null || atlasReadbackScratch.length < dataSize) {
                    atlasReadbackScratch = new byte[dataSize];
                }
                atlasPixelBuffer.clear();
                long syncStartNs = System.nanoTime();
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, atlasPixelBuffer);
                atlasPixelBuffer.get(atlasReadbackScratch, 0, dataSize);
                MetalRenderProfiler.getInstance().recordAtlasSplit(System.nanoTime() - syncStartNs, 0, 0);
                uploadAtlasDiff(width, height, dataSize);
                lastFullReadMs = now;
                releaseAtlasBuffers();
                trackAtlasSyncCost(syncStartNs);
                atlasDirty = false;
                atlasFramesSinceUpload = 0;
                lastAtlasUploadMs = now;
            } finally {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            }
        } catch (Exception e) {
            atlasDirty = true;
            MetalLogger.deepInfo("atlas update failed: %s", e.getMessage());
        } finally {
            long atlasNs = System.nanoTime() - t0;
            lastAtlasMs = atlasNs / 1_000_000.0;
            if (atlasNs > 50_000) MetalRenderProfiler.getInstance().recordAtlasTime(atlasNs);
        }
    }

    private void releaseAtlasBuffers() {
        if (ATLAS_RECTS && ATLAS_RELEASE_BUFFERS) {
            atlasPixelBuffer = null;
            atlasReadbackScratch = null;
            atlasUploadData = null;
        }
    }

    private boolean updateAnimatedRects(int glTexId) {
        long total = 0;
        for (int i = 0; i < animatedRects.length; i += 4) {
            int x = animatedRects[i], y = animatedRects[i + 1], w = animatedRects[i + 2], h = animatedRects[i + 3];
            if (x < 0 || y < 0 || w <= 0 || h <= 0 || (long) x + w > blockAtlasWidth || (long) y + h > blockAtlasHeight) return false;
            total += (long) w * h * 4;
            if (total > RECT_BUFFER_BYTES) return false;
        }
        if (readFbo == 0) readFbo = GL30.glGenFramebuffers();
        if (rectBytes == null) rectBytes = BufferUtils.createByteBuffer(RECT_BUFFER_BYTES);
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int prevAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int prevRowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int prevSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int prevSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        long readStartNs = System.nanoTime();
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, glTexId, 0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) return false;
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            int offset = 0;
            for (int i = 0; i < animatedRects.length; i += 4) {
                int w = animatedRects[i + 2], h = animatedRects[i + 3];
                rectBytes.limit(offset + w * h * 4).position(offset);
                GL11.glReadPixels(animatedRects[i], animatedRects[i + 1], w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rectBytes);
                offset += w * h * 4;
            }
        } finally {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, prevAlignment);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, prevRowLength);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, prevSkipRows);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, prevSkipPixels);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prevPackBuffer);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            MetalRenderProfiler.getInstance().recordAtlasSplit(System.nanoTime() - readStartNs, 0, 0);
        }
        rectBytes.position(0).limit((int) total);
        long uploadStartNs = System.nanoTime();
        if (ATLAS_BATCH_UPLOAD) {
            NativeBridge.nUpdateTextureRects(blockAtlasTexture, animatedRects.length / 4, animatedRects, rectBytes);
        } else {
            int offset = 0;
            for (int i = 0; i < animatedRects.length; i += 4) {
                int length = animatedRects[i + 2] * animatedRects[i + 3] * 4;
                NativeBridge.nUpdateTextureRects(blockAtlasTexture, 1,
                        java.util.Arrays.copyOfRange(animatedRects, i, i + 4), rectBytes.slice(offset, length));
                offset += length;
            }
        }
        MetalRenderProfiler.getInstance().recordAtlasSplit(0, 0, System.nanoTime() - uploadStartNs);
        return true;
    }

    private void uploadAtlasDiff(int width, int height, int dataSize) {
        byte[] fresh = atlasReadbackScratch;
        byte[] mirror = atlasUploadData;
        if (mirror == null || mirror.length != dataSize) {
            long uploadStartNs = System.nanoTime();
            NativeBridge.nUpdateTexture2D(blockAtlasTexture, width, height, fresh);
            com.pebbles_boon.metalrender.performance.MetalRenderProfiler.getInstance()
                    .recordAtlasSplit(0, 0, System.nanoTime() - uploadStartNs);
            if (atlasUploadData == null || atlasUploadData.length < dataSize) {
                atlasUploadData = new byte[dataSize];
            }
            System.arraycopy(fresh, 0, atlasUploadData, 0, dataSize);
            return;
        }
        long diffStartNs = System.nanoTime();
        int rowBytes = width * 4;
        int dirtyRows = 0;
        java.util.ArrayList<int[]> dirtySpans = new java.util.ArrayList<>(8);
        int spanStart = -1;
        int slabRows = 16;
        int slabBytes = rowBytes * slabRows;
        for (int row = 0; row < height;) {
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
        com.pebbles_boon.metalrender.performance.MetalRenderProfiler.getInstance()
                .recordAtlasSplit(0, System.nanoTime() - diffStartNs, 0);
        if (dirtySpans.isEmpty()) {
            return;
        }
        for (int[] span : dirtySpans) {
            //READ AGENTS.MD (or git history) BEFORE TOUCHING THIS FILE - saves you a pile of tokens!
            int y0 = span[0];
            int y1 = span[1];
            long uploadStartNs = System.nanoTime();
            NativeBridge.nUpdateTexture2DRegion(blockAtlasTexture, width, 0, y0,
                    width, y1 - y0, fresh);
            com.pebbles_boon.metalrender.performance.MetalRenderProfiler.getInstance()
                    .recordAtlasSplit(0, 0, System.nanoTime() - uploadStartNs);
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
        long t0 = System.nanoTime();
        try {
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
        } finally {
            long lightNs = System.nanoTime() - t0;
            lastLightmapMs = lightNs / 1_000_000.0;
            if (lightNs > 50_000) {
                com.pebbles_boon.metalrender.performance.MetalRenderProfiler.getInstance()
                        .recordLightmapTime(lightNs);
            }
        }
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
            if (MetalRenderClient.isBackendMode()) {
                if (gpuTexture instanceof MetalTexture metalTex && metalTex.handle() != 0) {
                    if (lightmapTexture != 0 && !lightmapBorrowed) {
                        NativeBridge.nDestroyTexture2D(lightmapTexture);
                    }
                    lightmapTexture = metalTex.handle();
                    lightmapBorrowed = true;
                    lightmapWidth = metalTex.getWidth(0);
                    lightmapHeight = metalTex.getHeight(0);
                    lightmapLoaded = true;
                }
                return;
            }
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
                lightmapHeight = height;
                MetalLogger.info("lightmap weady: %dx%d h=%d", width, height, newTexture);
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
        if (readFbo != 0 && !MetalRenderClient.isBackendMode()) {
            GL30.glDeleteFramebuffers(readFbo);
        }
        readFbo = 0;
        rectBytes = null;
        animatedRects = new int[0];
        blockAtlasSource = null;
        lastFullReadMs = 0;
        if (blockAtlasTexture != 0 && !blockAtlasBorrowed) {
            NativeBridge.nDestroyTexture2D(blockAtlasTexture);
        }
        blockAtlasTexture = 0;
        blockAtlasBorrowed = false;
        if (lightmapTexture != 0 && !lightmapBorrowed) {
            NativeBridge.nDestroyTexture2D(lightmapTexture);
        }
        lightmapTexture = 0;
        lightmapBorrowed = false;
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
