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
  private int depthTexture = 0;
  private int depthShaderProgram = 0;
  private int depthSrcFbo = 0;
  private int depthBlitFrameCount = 0;
  private int depthTextureWidth = 0;
  private int depthTextureHeight = 0;
  private ByteBuffer depthPixelBuffer = null;
  private byte[] depthRowA = null;
  private byte[] depthRowB = null;
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
  private static final String RECT_FRAGMENT_SHADER = """
      #version 150 core
      in vec2 vTexCoord;
      out vec4 fragColor;
      uniform sampler2DRect uTextureRect;
      uniform vec2 uTexSize;
      void main() {
          vec2 rc = vec2(vTexCoord.x * uTexSize.x, (1.0 - vTexCoord.y) * uTexSize.y);
          vec4 texColor = texture(uTextureRect, rc);
          if (texColor.a < 0.001) discard;
          fragColor = texColor;
      }
      """;
  private static final String DEPTH_FRAGMENT_SHADER = """
      #version 150 core
      in vec2 vTexCoord;
      out vec4 fragColor;
      uniform sampler2D uDepthTexture;
      void main() {
          float depth = texture(uDepthTexture, vec2(vTexCoord.x, 1.0 - vTexCoord.y)).r;
          gl_FragDepth = depth;
          fragColor = vec4(0.0, 0.0, 0.0, 0.0);
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
    deleteShaderProgram();
    if (rectShaderProgram != 0) {
      GL20.glDeleteProgram(rectShaderProgram);
      rectShaderProgram = 0;
      rectTexSizeLoc = -1;
    }
    deleteQuadGeometry();
    deleteTextures();
    if (ioSurfaceFbo != 0) {
      GL30.glDeleteFramebuffers(ioSurfaceFbo);
      ioSurfaceFbo = 0;
    }
    if (intermediateFbo != 0) {
      GL30.glDeleteFramebuffers(intermediateFbo);
      intermediateFbo = 0;
    }
    if (intermediateTexture != 0) {
      GL11.glDeleteTextures(intermediateTexture);
      intermediateTexture = 0;
    }
    initialized = false;
    boundWidth = 0;
    boundHeight = 0;
    pixelBuffer = null;
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
      if (blitFrameCount <= 10) {        MetalLogger.error("[iosurface] composite eww: %s",
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

    int prevReadFbo, prevDrawFbo;
    boolean scissor;
    if (!glStateQueried) {
      prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
      prevDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
      scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
      GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, prevClearColor);
      cachedPrevReadFbo = prevReadFbo;
      cachedPrevDrawFbo = prevDrawFbo;
      cachedScissor = scissor;
      glStateQueried = true;
    } else {
      prevReadFbo = cachedPrevReadFbo;
      prevDrawFbo = cachedPrevDrawFbo;
      scissor = cachedScissor;
    }
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

  private boolean drawDirectRect(int width, int height) {
    if (rectShaderProgram == 0) {
      rectShaderProgram = createRectShaderProgram();
      if (rectShaderProgram == 0) {
        return false;
      }
      GL20.glUseProgram(rectShaderProgram);
      int texLoc = GL20.glGetUniformLocation(rectShaderProgram, "uTextureRect");
      if (texLoc >= 0)
        GL20.glUniform1i(texLoc, 0);
      rectTexSizeLoc = GL20.glGetUniformLocation(rectShaderProgram, "uTexSize");
      GL20.glUseProgram(0);
    }
    int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
    int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
    boolean wasDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
    boolean wasBlend = GL11.glIsEnabled(GL11.GL_BLEND);
    boolean wasScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
    boolean wasDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
    int[] prevViewport = blitViewportBuf;
    GL11.glGetIntegerv(GL11.GL_VIEWPORT, prevViewport);
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
      GL20.glUseProgram(rectShaderProgram);
      if (rectTexSizeLoc >= 0) {
        GL20.glUniform2f(rectTexSizeLoc, (float) width, (float) height);
      }
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL11.glBindTexture(GL_TEXTURE_RECTANGLE, glTextureRect);
      GL30.glBindVertexArray(vao);
      GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
      GL30.glBindVertexArray(0);
      GL11.glBindTexture(GL_TEXTURE_RECTANGLE, 0);
      return true;
    } finally {
      GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2],
          prevViewport[3]);
      GL20.glUseProgram(prevProgram);
      GL30.glBindVertexArray(prevVao);
      GL11.glDepthMask(wasDepthMask);
      if (wasDepth)
        GL11.glEnable(GL11.GL_DEPTH_TEST);
      else
        GL11.glDisable(GL11.GL_DEPTH_TEST);
      if (wasBlend)
        GL11.glEnable(GL11.GL_BLEND);
      else
        GL11.glDisable(GL11.GL_BLEND);
      if (wasScissor)
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
      else
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }
  }

  private int createRectShaderProgram() {
    int vs = compileShader(GL20.GL_VERTEX_SHADER, VERTEX_SHADER);
    if (vs == 0)
      return 0;
    int fs = compileShader(GL20.GL_FRAGMENT_SHADER, RECT_FRAGMENT_SHADER);
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
      MetalLogger.error("[iosurface] rect shader fail: %s",
          GL20.glGetProgramInfoLog(prog));
      GL20.glDeleteProgram(prog);
      return 0;
    }
    return prog;
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
    if (!quadStateQueried) {
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
    } else {
      prevProgram = cachedQuadPrevProgram;
      prevVao = cachedQuadPrevVao;
      prevActiveTexture = cachedQuadPrevActiveTexture;
      prevTex = cachedQuadPrevTex;
      wasDepth = cachedQuadWasDepth;
      wasBlend = cachedQuadWasBlend;
      wasCull = cachedQuadWasCull;
      wasScissor = cachedQuadWasScissor;
      wasStencil = cachedQuadWasStencil;
      wasDepthMask = cachedQuadWasDepthMask;
      cmR = cachedQuadCmR;
      cmG = cachedQuadCmG;
      cmB = cachedQuadCmB;
      cmA = cachedQuadCmA;
      bSrcRGB = cachedQuadBSrcRGB;
      bDstRGB = cachedQuadBDstRGB;
      bSrcA = cachedQuadBSrcA;
      bDstA = cachedQuadBDstA;
    }
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

  public boolean blitDepth(long metalHandle, int width, int height) {
    depthBlitFrameCount++;
    if (metalHandle == 0 || width <= 0 || height <= 0) {
      return false;
    }
    int depthDataSize = width * height * 4;
    if (depthPixelBuffer == null ||
        depthPixelBuffer.capacity() < depthDataSize) {
      depthPixelBuffer = ByteBuffer.allocateDirect(depthDataSize)
          .order(ByteOrder.nativeOrder());
    }
    depthPixelBuffer.clear();
    boolean readOk = NativeBridge.nReadbackDepth(metalHandle, depthPixelBuffer);
    if (!readOk) {
      if (depthBlitFrameCount <= 5) {
        MetalLogger.warn("[iosurface] depth read fail");
      }
      return false;
    }
    if (depthBlitFrameCount <= 5 || depthBlitFrameCount % 600 == 0) {
      java.nio.FloatBuffer fb = depthPixelBuffer.asFloatBuffer();
      int totalPixels = width * height;
      float center = totalPixels > 0 ? fb.get(totalPixels / 2) : -1;
      float topLeft = fb.get(0);
      float bottomRight = totalPixels > 1 ? fb.get(totalPixels - 1) : -1;
      float mn = Float.MAX_VALUE, mx = Float.MIN_VALUE;
      int step = Math.max(1, totalPixels / 256);
      for (int i = 0; i < totalPixels; i += step) {
        float v = fb.get(i);
        if (v < mn)
          mn = v;
        if (v > mx)
          mx = v;
      }
      MetalLogger.info(
          "[iosurface] depth readback #%d c=%.6f tl=%.6f br=%.6f mn=%.6f mx=%.6f",
          depthBlitFrameCount, center, topLeft, bottomRight, mn, mx);
    }
    if (depthTexture == 0 || depthTextureWidth != width ||
        depthTextureHeight != height) {
      if (depthTexture != 0) {
        GL11.glDeleteTextures(depthTexture);
      }
      depthTexture = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
          GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
          GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S,
          GL12.GL_CLAMP_TO_EDGE);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T,
          GL12.GL_CLAMP_TO_EDGE);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32F, width, height, 0,
          GL11.GL_RED, GL11.GL_FLOAT, (ByteBuffer) null);
      depthTextureWidth = width;
      depthTextureHeight = height;
      MetalLogger.info("[iosurface] depth tex %d %dx%d",
          depthTexture, width, height);
    }
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
    GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height,
        GL11.GL_RED, GL11.GL_FLOAT, depthPixelBuffer);
    if (depthShaderProgram == 0) {
      depthShaderProgram = createDepthShaderProgram();
      if (depthShaderProgram == 0) {
        MetalLogger.error("[iosurface] depth shader create fail");
        return false;
      }
      GL20.glUseProgram(depthShaderProgram);
      int loc = GL20.glGetUniformLocation(depthShaderProgram, "uDepthTexture");
      if (loc >= 0)
        GL20.glUniform1i(loc, 0);
      GL20.glUseProgram(0);
      MetalLogger.info("[iosurface] depth shader %d", depthShaderProgram);
    }
    int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
    int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
    int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
    boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
    boolean depthMsk = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
    boolean stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
    boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
    boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
    int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
    int[] prevViewport = blitViewportBuf;
    GL11.glGetIntegerv(GL11.GL_VIEWPORT, prevViewport);
    reusableCmBuf.clear();
    GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, reusableCmBuf);
    ByteBuffer cmBuf = reusableCmBuf;
    boolean cmR = cmBuf.get(0) != 0, cmG = cmBuf.get(1) != 0,
        cmB = cmBuf.get(2) != 0, cmA = cmBuf.get(3) != 0;
    try {
      GL11.glViewport(0, 0, width, height);
      GL11.glEnable(GL11.GL_DEPTH_TEST);
      GL11.glDepthFunc(GL11.GL_ALWAYS);
      GL11.glDepthMask(true);
      GL11.glColorMask(false, false, false, false);
      GL11.glDisable(GL11.GL_BLEND);
      GL11.glDisable(GL11.GL_STENCIL_TEST);
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      GL11.glDisable(GL11.GL_CULL_FACE);
      GL20.glUseProgram(depthShaderProgram);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
      GL30.glBindVertexArray(vao);
      GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
      GL30.glBindVertexArray(0);
      if (depthBlitFrameCount <= 5 || depthBlitFrameCount % 600 == 0) {
        int currentFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int depthBits = GL11.glGetInteger(GL11.GL_DEPTH_BITS);
        java.nio.FloatBuffer verifyBuf = BufferUtils.createFloatBuffer(1);
        GL11.glReadPixels(width / 2, height / 2, 1, 1, GL11.GL_DEPTH_COMPONENT,
            GL11.GL_FLOAT, verifyBuf);
        float centerDepth = verifyBuf.get(0);
        GL11.glReadPixels(width / 4, height / 4, 1, 1, GL11.GL_DEPTH_COMPONENT,
            GL11.GL_FLOAT, verifyBuf);
        float quarterDepth = verifyBuf.get(0);
        int glErr = GL11.glGetError();
        MetalLogger.info(
            "[iosurface] depth verify #%d fbo=%d db=%d c=%.6f q=%.6f e=0x%X",
            depthBlitFrameCount, currentFbo, depthBits, centerDepth,
            quarterDepth, glErr);
      }
      if (depthBlitFrameCount <= 3 || depthBlitFrameCount % 600 == 0) {
        MetalLogger.info("[iosurface] depth blit ok #%d %dx%d",
            depthBlitFrameCount, width, height);
      }
      return true;
    } finally {
      GL20.glUseProgram(prevProgram);
      GL30.glBindVertexArray(prevVao);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
      GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2],
          prevViewport[3]);
      if (depth)
        GL11.glEnable(GL11.GL_DEPTH_TEST);
      else
        GL11.glDisable(GL11.GL_DEPTH_TEST);
      GL11.glDepthFunc(depthFunc);
      GL11.glDepthMask(depthMsk);
      GL11.glColorMask(cmR, cmG, cmB, cmA);
      if (blend)
        GL11.glEnable(GL11.GL_BLEND);
      else
        GL11.glDisable(GL11.GL_BLEND);
      if (stencil)
        GL11.glEnable(GL11.GL_STENCIL_TEST);
      else
        GL11.glDisable(GL11.GL_STENCIL_TEST);
      if (scissor)
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
      else
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
      if (cull)
        GL11.glEnable(GL11.GL_CULL_FACE);
      else
        GL11.glDisable(GL11.GL_CULL_FACE);
    }
  }

  public boolean uploadDepthDirect(long metalHandle, int mcDepthTexId,
      int width, int height) {
    depthBlitFrameCount++;
    if (metalHandle == 0 || mcDepthTexId == 0 || width <= 0 || height <= 0) {
      return false;
    }
    int depthDataSize = width * height * 4;
    if (depthPixelBuffer == null ||
        depthPixelBuffer.capacity() < depthDataSize) {
      depthPixelBuffer = ByteBuffer.allocateDirect(depthDataSize)
          .order(java.nio.ByteOrder.nativeOrder());
    }
    depthPixelBuffer.clear();
    boolean readOk = NativeBridge.nReadbackDepth(metalHandle, depthPixelBuffer);
    if (!readOk) {
      if (depthBlitFrameCount <= 5) {
        MetalLogger.warn("[iosurface] depth upload read fail");
      }
      return false;
    }
    if (depthBlitFrameCount <= 5 || depthBlitFrameCount % 600 == 0) {
      java.nio.FloatBuffer fb = depthPixelBuffer.asFloatBuffer();
      int totalPixels = width * height;
      float center = totalPixels > 0 ? fb.get(totalPixels / 2) : -1;
      float topLeft = fb.get(0);
      float bottomRight = totalPixels > 1 ? fb.get(totalPixels - 1) : -1;
      float mn = Float.MAX_VALUE, mx = Float.MIN_VALUE;
      int step = Math.max(1, totalPixels / 256);
      for (int i = 0; i < totalPixels; i += step) {
        float v = fb.get(i);
        if (v < mn)
          mn = v;
        if (v > mx)
          mx = v;
      }
      MetalLogger.info(
          "[iosurface] depth readback #%d c=%.6f tl=%.6f br=%.6f mn=%.6f mx=%.6f",
          depthBlitFrameCount, center, topLeft, bottomRight, mn, mx);
    }
    int rowBytes = width * 4;
    if (depthRowA == null || depthRowA.length < rowBytes) {
      depthRowA = new byte[rowBytes];
      depthRowB = new byte[rowBytes];
    }
    for (int y = 0; y < height / 2; y++) {
      int topOffset = y * rowBytes;
      int botOffset = (height - 1 - y) * rowBytes;
      depthPixelBuffer.get(topOffset, depthRowA);
      depthPixelBuffer.get(botOffset, depthRowB);
      depthPixelBuffer.put(topOffset, depthRowB);
      depthPixelBuffer.put(botOffset, depthRowA);
    }
    depthPixelBuffer.clear();
    int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, mcDepthTexId);
    GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height,
        GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT,
        depthPixelBuffer);
    int err = GL11.glGetError();
    if (err != GL11.GL_NO_ERROR && depthBlitFrameCount <= 5) {
      MetalLogger.error("[iosurface] depth upload gl err 0x%X", err);
    }
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
    if (depthBlitFrameCount <= 3 || depthBlitFrameCount % 600 == 0) {
      MetalLogger.info("[iosurface] depth upload ok #%d t=%d %dx%d",
          depthBlitFrameCount, mcDepthTexId, width, height);
    }
    return err == GL11.GL_NO_ERROR;
  }

  public boolean blitDepthViaFBO(long metalHandle, int mcDepthTexId,
      int mcFboId, int width, int height) {
    depthBlitFrameCount++;
    if (metalHandle == 0 || width <= 0 || height <= 0) {
      return false;
    }
    int depthDataSize = width * height * 4;
    if (depthPixelBuffer == null ||
        depthPixelBuffer.capacity() < depthDataSize) {
      depthPixelBuffer = ByteBuffer.allocateDirect(depthDataSize)
          .order(java.nio.ByteOrder.nativeOrder());
    }
    depthPixelBuffer.clear();
    boolean readOk = NativeBridge.nReadbackDepth(metalHandle, depthPixelBuffer);
    if (!readOk) {
      if (depthBlitFrameCount <= 5) {
        MetalLogger.warn("[iosurface] depth fbo read fail");
      }
      return false;
    }
    if (depthBlitFrameCount <= 5 || depthBlitFrameCount % 600 == 0) {
      java.nio.FloatBuffer fb = depthPixelBuffer.asFloatBuffer();
      int totalPixels = width * height;
      float center = totalPixels > 0 ? fb.get(totalPixels / 2) : -1;
      float topLeft = fb.get(0);
      float mn = Float.MAX_VALUE, mx = Float.MIN_VALUE;
      int step = Math.max(1, totalPixels / 256);
      for (int i = 0; i < totalPixels; i += step) {
        float v = fb.get(i);
        if (v < mn)
          mn = v;
        if (v > mx)
          mx = v;
      }
      MetalLogger.info("[iosurface] depth readback #%d c=%.6f tl=%.6f mn=%.6f mx=%.6f",
          depthBlitFrameCount, center, topLeft, mn, mx);
    }
    int rowBytes = width * 4;
    if (depthRowA == null || depthRowA.length < rowBytes) {
      depthRowA = new byte[rowBytes];
      depthRowB = new byte[rowBytes];
    }
    for (int y = 0; y < height / 2; y++) {
      int topOffset = y * rowBytes;
      int botOffset = (height - 1 - y) * rowBytes;
      depthPixelBuffer.get(topOffset, depthRowA);
      depthPixelBuffer.get(botOffset, depthRowB);
      depthPixelBuffer.put(topOffset, depthRowB);
      depthPixelBuffer.put(botOffset, depthRowA);
    }
    depthPixelBuffer.clear();
    if (depthTexture == 0 || depthTextureWidth != width ||
        depthTextureHeight != height) {
      if (depthTexture != 0)
        GL11.glDeleteTextures(depthTexture);
      depthTexture = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
          GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
          GL11.GL_NEAREST);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F,
          width, height, 0, GL11.GL_DEPTH_COMPONENT,
          GL11.GL_FLOAT, (ByteBuffer) null);
      depthTextureWidth = width;
      depthTextureHeight = height;
      MetalLogger.info("[iosurface] depth tex (r32f) %d %dx%d",
          depthTexture, width, height);
    } else {
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
    }
    GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height,
        GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT,
        depthPixelBuffer);
    int err = GL11.glGetError();
    if (err != GL11.GL_NO_ERROR && depthBlitFrameCount <= 5) {
      MetalLogger.error("[iosurface] depth fbo upload err 0x%X", err);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      return false;
    }
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    if (depthSrcFbo == 0) {
      depthSrcFbo = GL30.glGenFramebuffers();
    }
    int prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    int prevDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, depthSrcFbo);
    GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER,
        GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D,
        depthTexture, 0);
    int srcStatus = GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER);
    if (srcStatus != GL30.GL_FRAMEBUFFER_COMPLETE) {
      if (depthBlitFrameCount <= 3) {
        MetalLogger.error("[iosurface] depth fbo src bad 0x%X", srcStatus);
      }
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
      return false;
    }
    GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, mcFboId);
    GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
        GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
    err = GL11.glGetError();
    if (err != GL11.GL_NO_ERROR && depthBlitFrameCount <= 5) {
      MetalLogger.error("[iosurface] depth fbo blit err 0x%X", err);
    }
    GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
    GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
    if (depthBlitFrameCount <= 3 || depthBlitFrameCount % 600 == 0) {
      MetalLogger.info("[iosurface] depth fbo ok #%d s=%d d=%d %dx%d",
          depthBlitFrameCount, depthSrcFbo, mcFboId, width, height);
    }
    return err == GL11.GL_NO_ERROR;
  }

  private int createDepthShaderProgram() {
    int vertShader = compileShader(GL20.GL_VERTEX_SHADER, VERTEX_SHADER);
    int fragShader = compileShader(GL20.GL_FRAGMENT_SHADER, DEPTH_FRAGMENT_SHADER);
    if (vertShader == 0 || fragShader == 0) {
      if (vertShader != 0)
        GL20.glDeleteShader(vertShader);
      if (fragShader != 0)
        GL20.glDeleteShader(fragShader);
      return 0;
    }
    int prog = GL20.glCreateProgram();
    GL20.glAttachShader(prog, vertShader);
    GL20.glAttachShader(prog, fragShader);
    GL20.glBindAttribLocation(prog, 0, "aPos");
    GL20.glBindAttribLocation(prog, 1, "aTexCoord");
    GL20.glLinkProgram(prog);
    GL20.glDeleteShader(vertShader);
    GL20.glDeleteShader(fragShader);
    if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
      String log = GL20.glGetProgramInfoLog(prog);
      MetalLogger.error("[iosurface] depth shader fail: %s", log);
      GL20.glDeleteProgram(prog);
      return 0;
    }
    return prog;
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

  public void invalidate() {
    boundWidth = 0;
    boundHeight = 0;
  }
}
