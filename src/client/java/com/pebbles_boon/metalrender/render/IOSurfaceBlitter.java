package com.pebbles_boon.metalrender.render;

import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;

public final class IOSurfaceBlitter {
    private static final int GL_TEXTURE_RECTANGLE = 0x84F5;
    private static final int GL_BGRA = 0x80E1;
    private final int[] blitViewportBuf = new int[4];
    private int glTextureRect = 0;
    private int ioSurfaceFbo = 0;
    private int intermediateFbo = 0;
    private int intermediateTexture = 0;
    private int vao = 0;
    private int vbo = 0;
    private int shaderProgram = 0;
    private int rectShaderProgram = 0;
    private int rectTexSizeLoc = -1;
    private int glTexture = 0;
    private ByteBuffer pixelBuffer = null;
    private int boundWidth = 0;
    private int boundHeight = 0;
    private boolean initialized = false;

    private volatile boolean destroyed = false;
    private int blitFrameCount = 0;
    private boolean ioSurfaceFailed = false;
    private int consecutiveFastPathFailures = 0;
    private static final int MAX_FAST_PATH_FAILURES = 3;
    private int lastIOSurfaceWidth = 0;
    private int lastIOSurfaceHeight = 0;
    private boolean readFboVerified = false;
    private boolean drawFboVerified = false;
    private final float[] prevClearColor = new float[4];

    private int cachedPrevReadFbo = -1;
    private int cachedPrevDrawFbo = -1;
    private boolean cachedScissor = false;
    private boolean glStateQueried = false;

    private int cachedQuadPrevProgram = -1;
    private int cachedQuadPrevVao = -1;
    private int cachedQuadPrevActiveTexture = -1;
    private int cachedQuadPrevTex = -1;
    private boolean cachedQuadWasDepth = false;
    private boolean cachedQuadWasBlend = false;
    private boolean cachedQuadWasCull = false;
    private boolean cachedQuadWasScissor = false;
    private boolean cachedQuadWasStencil = false;
    private boolean cachedQuadWasDepthMask = false;
    private boolean cachedQuadCmR = true, cachedQuadCmG = true,
            cachedQuadCmB = true, cachedQuadCmA = true;
    private int cachedQuadBSrcRGB = -1, cachedQuadBDstRGB = -1,
            cachedQuadBSrcA = -1, cachedQuadBDstA = -1;
    private final int[] cachedQuadViewport = new int[4];
    private boolean quadStateQueried = false;

    private final ByteBuffer reusableCmBuf = BufferUtils.createByteBuffer(4);
    private long blitWaitAccNs = 0;
    private long blitBindAccNs = 0;
    private long blitInterAccNs = 0;
    private long blitQuadAccNs = 0;
    private int blitStageCount = 0;
    private static final String VERTEX_SHADER = """
            #version 150 core
            in vec2 aPos;
            in vec2 aTexCoord;
            out vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPos.x, aPos.y, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
            """;
    private static final String FRAGMENT_SHADER = """
            #version 150 core
            in vec2 vTexCoord;
            out vec4 fragColor;
            uniform sampler2D uTexture;
            void main() {
                vec4 texColor = texture(uTexture, vTexCoord);
                if (texColor.a < 0.001) discard;
                fragColor = texColor;
            }
            """;
    private static final float[] QUAD_VERTICES = {
            -1.0f, -1.0f, 0.0f, 0.0f, 1.0f, -1.0f, 1.0f, 0.0f,
            1.0f, 1.0f, 1.0f, 1.0f, -1.0f, -1.0f, 0.0f, 0.0f,
            1.0f, 1.0f, 1.0f, 1.0f, -1.0f, 1.0f, 0.0f, 1.0f,
    };

    public IOSurfaceBlitter() {
    }

    public boolean blit(long metalHandle) {
        return blit(metalHandle, false);
    }

    public boolean blit(long metalHandle, boolean skipWait) {
        if (destroyed)
            return false;
        blitFrameCount++;
        if (metalHandle == 0) {
            return false;
        }
        if (!initialized && !initialize()) {
            return false;
        }
        int width = NativeBridge.nGetIOSurfaceWidth(metalHandle);
        int height = NativeBridge.nGetIOSurfaceHeight(metalHandle);
        if (width <= 0 || height <= 0) {
            return false;
        }
        if (lastIOSurfaceWidth > 0 && lastIOSurfaceHeight > 0 &&
                (width != lastIOSurfaceWidth || height != lastIOSurfaceHeight)) {
            if (blitFrameCount == 1 || blitFrameCount % 6000 == 0) {
                MetalLogger.debugInfo(
                        "[iosurface] resize %dx%d->%dx%d",
                        lastIOSurfaceWidth, lastIOSurfaceHeight, width, height);
            }
            invalidateTextures();
        }
        lastIOSurfaceWidth = width;
        lastIOSurfaceHeight = height;
        return blitGPUComposite(metalHandle, width, height, skipWait);
    }

    public void destroy() {
        destroyed = true;
        try {
            deleteShaderProgram();
        } catch (Throwable ignored) {
        }
        try {
            if (rectShaderProgram != 0) {
                GL20.glDeleteProgram(rectShaderProgram);
                rectShaderProgram = 0;
                rectTexSizeLoc = -1;
            }
        } catch (Throwable ignored) {
        }
        try {
            deleteQuadGeometry();
        } catch (Throwable ignored) {
        }
        try {
            deleteTextures();
        } catch (Throwable ignored) {
        }
        try {
            if (ioSurfaceFbo != 0) {
                GL30.glDeleteFramebuffers(ioSurfaceFbo);
                ioSurfaceFbo = 0;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (intermediateFbo != 0) {
                GL30.glDeleteFramebuffers(intermediateFbo);
                intermediateFbo = 0;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (intermediateTexture != 0) {
                GL11.glDeleteTextures(intermediateTexture);
                intermediateTexture = 0;
            }
        } catch (Throwable ignored) {
        }
        initialized = false;
        boundWidth = 0;
        boundHeight = 0;
        pixelBuffer = null;
        glStateQueried = false;
        quadStateQueried = false;
        readFboVerified = false;
        drawFboVerified = false;
        resetFastPathState();
        MetalLogger.info("[iosurface] destroyed");
    }

    private boolean initialize() {
        if (initialized)
            return true;
        try {
            vao = GL30.glGenVertexArrays();
            vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            FloatBuffer buf = BufferUtils.createFloatBuffer(QUAD_VERTICES.length);
            buf.put(QUAD_VERTICES).flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);
            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES,
                    0);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES,
                    2L * Float.BYTES);
            GL20.glEnableVertexAttribArray(1);
            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            initialized = true;
            MetalLogger.info("[iosurface] weady");
            return true;
        } catch (Exception e) {
            MetalLogger.error("[iosurface] init fail: %s", e.getMessage());
            destroy();
            return false;
        }
    }

    private boolean blitGPUComposite(long metalHandle, int width, int height,
            boolean skipWait) {
        try {
            long t0 = System.nanoTime();
            if (!skipWait && !NativeBridge.nIsFrameReady(metalHandle)) {
                NativeBridge.nWaitForRender(metalHandle);
            }
            long t1 = System.nanoTime();
            if (!ioSurfaceFailed &&
                    consecutiveFastPathFailures < MAX_FAST_PATH_FAILURES) {
                if (glTextureRect == 0) {
                    glTextureRect = GL11.glGenTextures();
                }
                boolean bound = NativeBridge.nBindIOSurfaceToTexture(metalHandle, glTextureRect);
                long t2 = System.nanoTime();
                if (bound && blitToIntermediateTexture(width, height)) {
                    long t3 = System.nanoTime();
                    consecutiveFastPathFailures = 0;
                    boolean ok = drawFullscreenQuad(width, height);
                    long t4 = System.nanoTime();
                    blitWaitAccNs += (t1 - t0);
                    blitBindAccNs += (t2 - t1);
                    blitInterAccNs += (t3 - t2);
                    blitQuadAccNs += (t4 - t3);
                    blitStageCount++;
                    if (blitStageCount >= 120) {
                        double wMs = blitWaitAccNs / (blitStageCount * 1_000_000.0);
                        double bMs = blitBindAccNs / (blitStageCount * 1_000_000.0);
                        double iMs = blitInterAccNs / (blitStageCount * 1_000_000.0);
                        double qMs = blitQuadAccNs / (blitStageCount * 1_000_000.0);
                        MetalLogger.info(
                                "[iosurface] timing w=%.2f b=%.2f i=%.2f q=%.2f (avg/%d)",
                                wMs, bMs, iMs, qMs, blitStageCount);
                        blitWaitAccNs = 0;
                        blitBindAccNs = 0;
                        blitInterAccNs = 0;
                        blitQuadAccNs = 0;
                        blitStageCount = 0;
                    }
                    return ok;
                } else {
                    consecutiveFastPathFailures++;
                    if (blitFrameCount <= 10) {
                        MetalLogger.warn("[iosurface] fastpath fail #%d, slow fallback",
                                consecutiveFastPathFailures);
                    }
                    if (consecutiveFastPathFailures >= MAX_FAST_PATH_FAILURES) {
                        MetalLogger.warn("[iosurface] fastpath off (%d fails)",
                                MAX_FAST_PATH_FAILURES);
                        ioSurfaceFailed = true;
                    }
                }
            }
            return blitSlowPath(metalHandle, width, height);
        } catch (Exception e) {
            if (blitFrameCount <= 10) {
                MetalLogger.error("[iosurface] composite eww: %s",
                        e.getMessage());
            }
            int err;
            int drained = 0;
            while ((err = GL11.glGetError()) != GL11.GL_NO_ERROR && drained++ < 8) {
                if (blitFrameCount <= 10) {
                    MetalLogger.warn("[iosurface] deferred gl err 0x%X", err);
                }
            }
            return false;
        }
    }

    private boolean blitToIntermediateTexture(int width, int height) {
        com.pebbles_boon.metalrender.util.VanillaRenderState.setIOSurfaceBlitting(
                true);
        try {
            return blitToIntermediateImpl(width, height);
        } finally {
            com.pebbles_boon.metalrender.util.VanillaRenderState.setIOSurfaceBlitting(
                    false);
        }
    }

    private boolean blitToIntermediateImpl(int width, int height) {

        int prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, prevClearColor);
        cachedPrevReadFbo = prevReadFbo;
        cachedPrevDrawFbo = prevDrawFbo;
        cachedScissor = scissor;
        glStateQueried = true;
        try {
            if (ioSurfaceFbo == 0) {
                ioSurfaceFbo = GL30.glGenFramebuffers();
            }
            ensureIntermediateTexture(width, height);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, ioSurfaceFbo);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER,
                    GL30.GL_COLOR_ATTACHMENT0,
                    GL_TEXTURE_RECTANGLE, glTextureRect, 0);
            if (!readFboVerified) {
                int status = GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER);
                if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                    MetalLogger.error("[iosurface] read fbo bad 0x%X", status);
                    return false;
                }
                readFboVerified = true;
            }
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, intermediateFbo);
            if (!drawFboVerified) {
                int status = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
                if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                    MetalLogger.error("[iosurface] inter fbo bad 0x%X", status);
                    return false;
                }
                drawFboVerified = true;
            }
            GL11.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL30.glBlitFramebuffer(0, height, width, 0, 0, 0, width, height,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            if (blitFrameCount <= 10) {
                int err = GL11.glGetError();
                if (err != GL11.GL_NO_ERROR) {
                    MetalLogger.error("[iosurface] glblit err 0x%X", err);
                }
            }
            return true;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, ioSurfaceFbo);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER,
                    GL30.GL_COLOR_ATTACHMENT0,
                    GL_TEXTURE_RECTANGLE, 0, 0);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
            if (scissor)
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glClearColor(prevClearColor[0], prevClearColor[1], prevClearColor[2],
                    prevClearColor[3]);
        }
    }

    private void ensureIntermediateTexture(int width, int height) {
        if (intermediateTexture != 0 && boundWidth == width &&
                boundHeight == height) {
            return;
        }
        readFboVerified = false;
        drawFboVerified = false;
        if (intermediateTexture != 0) {
            GL11.glDeleteTextures(intermediateTexture);
        }
        if (intermediateFbo == 0) {
            intermediateFbo = GL30.glGenFramebuffers();
        }
        intermediateTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, intermediateTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
                GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S,
                GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T,
                GL12.GL_CLAMP_TO_EDGE);
        GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, width, height);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, intermediateFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, intermediateTexture, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            MetalLogger.error("[iosurface] inter fbo setup bad 0x%X", status);
        }
        GL11.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        boundWidth = width;
        boundHeight = height;
        MetalLogger.info("[iosurface] inter tex %dx%d", width, height);
    }

    private boolean blitSlowPath(long metalHandle, int width, int height) {
        int requiredSize = width * height * 4;
        if (pixelBuffer == null || pixelBuffer.capacity() < requiredSize) {
            pixelBuffer = BufferUtils.createByteBuffer(requiredSize);
        }
        pixelBuffer.clear();
        if (!NativeBridge.nReadbackPixels(metalHandle, pixelBuffer)) {
            return false;
        }
        pixelBuffer.rewind();
        uploadToTexture(width, height);
        return drawFullscreenQuad(width, height);
    }

    private void uploadToTexture(int width, int height) {
        if (width != boundWidth || height != boundHeight || glTexture == 0) {
            if (glTexture != 0)
                GL11.glDeleteTextures(glTexture);
            glTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                    GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
                    GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S,
                    GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T,
                    GL12.GL_CLAMP_TO_EDGE);
            GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, width, height);
            boundWidth = width;
            boundHeight = height;
        } else {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTexture);
        }
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA,
                GL11.GL_UNSIGNED_BYTE, pixelBuffer);
    }

    private boolean drawFullscreenQuad(int width, int height) {
        if (shaderProgram == 0) {
            shaderProgram = createShaderProgram();
            if (shaderProgram == 0) {
                MetalLogger.error("[iosurface] shader create fail");
                return false;
            }
            GL20.glUseProgram(shaderProgram);
            int loc = GL20.glGetUniformLocation(shaderProgram, "uTexture");
            if (loc >= 0)
                GL20.glUniform1i(loc, 0);
            GL20.glUseProgram(0);
        }

        int prevProgram, prevVao, prevActiveTexture, prevTex;
        boolean wasDepth, wasBlend, wasCull, wasScissor, wasStencil, wasDepthMask;
        boolean cmR, cmG, cmB, cmA;
        int bSrcRGB, bDstRGB, bSrcA, bDstA;
        prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        prevActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        wasDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        wasBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        wasCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        wasScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        wasStencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        wasDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        reusableCmBuf.clear();
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, reusableCmBuf);
        ByteBuffer cmBuf = reusableCmBuf;
        cmR = cmBuf.get(0) != 0;
        cmG = cmBuf.get(1) != 0;
        cmB = cmBuf.get(2) != 0;
        cmA = cmBuf.get(3) != 0;
        bSrcRGB = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        bDstRGB = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        bSrcA = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        bDstA = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        cachedQuadPrevProgram = prevProgram;
        cachedQuadPrevVao = prevVao;
        cachedQuadPrevActiveTexture = prevActiveTexture;
        cachedQuadPrevTex = prevTex;
        cachedQuadWasDepth = wasDepth;
        cachedQuadWasBlend = wasBlend;
        cachedQuadWasCull = wasCull;
        cachedQuadWasScissor = wasScissor;
        cachedQuadWasStencil = wasStencil;
        cachedQuadWasDepthMask = wasDepthMask;
        cachedQuadCmR = cmR;
        cachedQuadCmG = cmG;
        cachedQuadCmB = cmB;
        cachedQuadCmA = cmA;
        cachedQuadBSrcRGB = bSrcRGB;
        cachedQuadBDstRGB = bDstRGB;
        cachedQuadBSrcA = bSrcA;
        cachedQuadBDstA = bDstA;
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, cachedQuadViewport);
        quadStateQueried = true;
        int[] prevViewport = cachedQuadViewport;
        try {
            GL11.glViewport(0, 0, width, height);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glColorMask(true, true, true, true);
            GL20.glUseProgram(shaderProgram);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            int texToUse = intermediateTexture != 0 ? intermediateTexture : glTexture;
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texToUse);
            GL30.glBindVertexArray(vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
            GL30.glBindVertexArray(0);
            return true;
        } finally {
            GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2],
                    prevViewport[3]);
            GL20.glUseProgram(prevProgram);
            GL30.glBindVertexArray(prevVao);
            GL13.glActiveTexture(prevActiveTexture);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            GL11.glDepthMask(wasDepthMask);
            GL11.glColorMask(cmR, cmG, cmB, cmA);
            if (wasDepth)
                GL11.glEnable(GL11.GL_DEPTH_TEST);
            else
                GL11.glDisable(GL11.GL_DEPTH_TEST);
            if (wasBlend)
                GL11.glEnable(GL11.GL_BLEND);
            else
                GL11.glDisable(GL11.GL_BLEND);
            if (wasCull)
                GL11.glEnable(GL11.GL_CULL_FACE);
            else
                GL11.glDisable(GL11.GL_CULL_FACE);
            if (wasScissor)
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
            else
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            if (wasStencil)
                GL11.glEnable(GL11.GL_STENCIL_TEST);
            else
                GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL14.glBlendFuncSeparate(bSrcRGB, bDstRGB, bSrcA, bDstA);
        }
    }

    private int createShaderProgram() {
        int vs = compileShader(GL20.GL_VERTEX_SHADER, VERTEX_SHADER);
        if (vs == 0)
            return 0;
        int fs = compileShader(GL20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        if (fs == 0) {
            GL20.glDeleteShader(vs);
            return 0;
        }
        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, vs);
        GL20.glAttachShader(prog, fs);
        GL20.glBindAttribLocation(prog, 0, "aPos");
        GL20.glBindAttribLocation(prog, 1, "aTexCoord");
        GL20.glLinkProgram(prog);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            MetalLogger.error("[iosurface] shader fail: %s",
                    GL20.glGetProgramInfoLog(prog));
            GL20.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    private int compileShader(int type, String source) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, source);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            MetalLogger.error("[iosurface] %s shader compile fail: %s",
                    type == GL20.GL_VERTEX_SHADER ? "vert" : "frag",
                    GL20.glGetShaderInfoLog(s));
            GL20.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    private void invalidateTextures() {
        if (glTextureRect != 0) {
            GL11.glDeleteTextures(glTextureRect);
            glTextureRect = 0;
        }
        if (glTexture != 0) {
            GL11.glDeleteTextures(glTexture);
            glTexture = 0;
        }
        if (intermediateTexture != 0) {
            GL11.glDeleteTextures(intermediateTexture);
            intermediateTexture = 0;
        }
        boundWidth = 0;
        boundHeight = 0;

        glStateQueried = false;
        quadStateQueried = false;
        resetFastPathState();
    }

    private void resetFastPathState() {
        ioSurfaceFailed = false;
        consecutiveFastPathFailures = 0;
        lastIOSurfaceWidth = 0;
        lastIOSurfaceHeight = 0;
    }

    private void deleteShaderProgram() {
        if (shaderProgram != 0) {
            GL20.glDeleteProgram(shaderProgram);
            shaderProgram = 0;
        }
    }

    private void deleteQuadGeometry() {
        if (vao != 0) {
            GL30.glDeleteVertexArrays(vao);
            vao = 0;
        }
        if (vbo != 0) {
            GL15.glDeleteBuffers(vbo);
            vbo = 0;
        }
    }

    private void deleteTextures() {
        if (glTexture != 0) {
            GL11.glDeleteTextures(glTexture);
            glTexture = 0;
        }
        if (glTextureRect != 0) {
            GL11.glDeleteTextures(glTextureRect);
            glTextureRect = 0;
        }
        if (intermediateTexture != 0) {
            GL11.glDeleteTextures(intermediateTexture);
            intermediateTexture = 0;
        }
    }
}
