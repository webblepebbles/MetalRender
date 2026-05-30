package com.pebbles_boon.metalrender.particle;

import com.mojang.blaze3d.opengl.GlTexture;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.backend.MetalRenderer;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.render.CapturedMatrices;
import com.pebbles_boon.metalrender.render.MetalTextureManager;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.sodium.mixins.accessor.BillboardParticleAccessor;
import com.pebbles_boon.metalrender.sodium.mixins.accessor.ParticleAccessor;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Queue;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

public class MetalParticleRenderer {
  private static final int VERTEX_STRIDE = 32;
  private static final int MAX_PARTICLE_VERTICES = 65536 * 6;
  private static final int MAX_PARTICLES_PER_FRAME = 65536;
  private long device;
  private boolean active;
  private int frameCount;
  private ByteBuffer vertexStagingBuffer;
  private long[] vbufs = new long[3];
  private int vtxCount;

  private long cachedParticlePipeline = 0;
  private long cachedParticleFallbackPipeline = 0;
  private final ArrayList<ParticleDrawCommand> pendingDrawPool =
      new ArrayList<>();
  private int pendingDrawCount;

  private final ArrayList<CapturedParticle> capturedParticlePool =
      new ArrayList<>();
  private int count = 0;
  private static final int TEXTURE_CACHE_SIZE = 2048;
  private static final long TEXTURE_UNCACHED = Long.MIN_VALUE;
  private final long[] textureCache = new long[TEXTURE_CACHE_SIZE];
  private final int[] textureUploadFrame = new int[TEXTURE_CACHE_SIZE];
  {
    java.util.Arrays.fill(textureCache, TEXTURE_UNCACHED);
    java.util.Arrays.fill(textureUploadFrame, -1);
  }

  private static final int ATLAS_REFRESH_FRAMES = 1200;

  private ByteBuffer textureReadbackBuf = null;
  private byte[] texturePixelBuf = null;

  private static final float[][] CORNER_OFFSETS = {{-1.0f, -1.0f, 0.0f},
                                                   {1.0f, -1.0f, 0.0f},
                                                   {1.0f, 1.0f, 0.0f},
                                                   {-1.0f, 1.0f, 0.0f}};
  private final Quaternionf bbRot = new Quaternionf();
  private final Vector3f[] bbCorners = {new Vector3f(), new Vector3f(),
                                        new Vector3f(), new Vector3f()};
  private final Vector3f bbNormal = new Vector3f();

  private final BlockPos.MutableBlockPos scratchPos =
      new BlockPos.MutableBlockPos();

  public MetalParticleRenderer() {
    vertexStagingBuffer =
        ByteBuffer.allocateDirect(MAX_PARTICLE_VERTICES * VERTEX_STRIDE)
            .order(ByteOrder.nativeOrder());
  }

  public void setup(long device) {
    this.device = device;
    if (device != 0) {
      for (int i = 0; i < 3; i++) {
        vbufs[i] = NativeBridge.nCreateBuffer(
            device, MAX_PARTICLE_VERTICES * VERTEX_STRIDE, 0);
      }
      MetalLogger.info(
          "MetalParticleRenderer initialized: device=%d vb0=%d vb1=%d vb2=%d",
          device, vbufs[0], vbufs[1], vbufs[2]);
    }
  }

  public void setActive(boolean active) { this.active = active; }

  public boolean isActive() { return active; }

  public void capture(ParticleEngine engine, Camera camera, float delta) {
    if (!active)
      return;
    count = 0;
    try {
      collect(engine, camera, delta);
    } catch (Exception e) {
      if (frameCount < 5) {
        MetalLogger.error("Failed to capture particles: %s", e.getMessage());
        e.printStackTrace();
      }
    }
  }

  public void captureParticleList(Queue<? extends Particle> particles,
                                  Camera camera, float delta) {
    if (!active || particles == null)
      return;
    double camX = camera.position().x;
    double camY = camera.position().y;
    double camZ = camera.position().z;

    boolean inWater = false;
    ClientLevel world = null;
    int camLight = 0x00F000F0;
    Minecraft mc = Minecraft.getInstance();
    if (mc != null && mc.level != null) {
      world = mc.level;
      scratchPos.set((int)Math.floor(camera.position().x),
                     (int)Math.floor(camera.position().y),
                     (int)Math.floor(camera.position().z));
      inWater = !mc.level.getBlockState(scratchPos).getFluidState().isEmpty();
      var lights = world.getChunkSource().getLightEngine();
      int blockLev =
          lights.getLayerListener(LightLayer.BLOCK).getLightValue(scratchPos);
      int skyLev =
          lights.getLayerListener(LightLayer.SKY).getLightValue(scratchPos);
      camLight = ((skyLev * 16) << 16) | (blockLev * 16);
    }
    int captured = 0;
    for (Particle p : particles) {
      if (count >= MAX_PARTICLES_PER_FRAME)
        break;
      if (p == null || !p.isAlive())
        continue;
      if (!(p instanceof SingleQuadParticle bp))
        continue;

      if (inWater) {
        int hash = System.identityHashCode(p);
        if ((hash & 7) > 1) {
          continue;
        }
      }
      captured++;
      CapturedParticle cp;
      if (count < capturedParticlePool.size()) {
        cp = capturedParticlePool.get(count);
      } else {
        cp = new CapturedParticle();
        capturedParticlePool.add(cp);
      }
      count++;
      ParticleAccessor pa = (ParticleAccessor)p;
      BillboardParticleAccessor bpa = (BillboardParticleAccessor)bp;
      cp.x = (float)(Mth.lerp(delta, pa.metalrender$getLastX(),
                              pa.metalrender$getX()) -
                     camX);
      cp.y = (float)(Mth.lerp(delta, pa.metalrender$getLastY(),
                              pa.metalrender$getY()) -
                     camY);
      cp.z = (float)(Mth.lerp(delta, pa.metalrender$getLastZ(),
                              pa.metalrender$getZ()) -
                     camZ);
      cp.scale = bp.getQuadSize(delta);

      if (inWater) {
        cp.scale *= 0.5f;
      }
      cp.red = bpa.metalrender$getRed();
      cp.green = bpa.metalrender$getGreen();
      cp.blue = bpa.metalrender$getBlue();
      cp.alpha = bpa.metalrender$getAlpha();
      cp.zRotation = Mth.lerp(delta, bpa.metalrender$getLastZRotation(),
                              bpa.metalrender$getZRotation());
      var sprite = bpa.metalrender$getSprite();
      if (sprite != null) {

        cp.minU = bpa.metalrender$invokeGetMinU();
        cp.maxU = bpa.metalrender$invokeGetMaxU();
        cp.minV = bpa.metalrender$invokeGetMinV();
        cp.maxV = bpa.metalrender$invokeGetMaxV();
        if (sprite.atlasLocation() != null) {
          cp.atlasId = sprite.atlasLocation();
        }
      } else {
        cp.minU = 0;
        cp.maxU = 1;
        cp.minV = 0;
        cp.maxV = 1;
      }

      cp.light = camLight;
    }
    if (frameCount < 5 && captured > 0) {
      MetalLogger.info("Captured %d billboard particles from queue", captured);

      int logged = 0;
      for (int _di = 0; _di < count; _di++) {
        CapturedParticle dbg = capturedParticlePool.get(_di);
        if (logged >= 5)
          break;
        MetalLogger.info("  Particle scale=%.4f class=%s pos=(%.1f,%.1f,%.1f)",
                         dbg.scale, "captured", dbg.x, dbg.y, dbg.z);
        logged++;
      }
    }
  }

  private void collect(ParticleEngine manager, Camera camera, float delta) {}

  public void render(long ctx) {
    if (!active || ctx == 0 || device == 0)
      return;
    if (count == 0) {
      frameCount++;
      return;
    }
    buildParticleGeometry();
    if (vtxCount == 0 || pendingDrawCount == 0) {
      count = 0;
      frameCount++;
      return;
    }
    vertexStagingBuffer.flip();
    int uploadSize = vtxCount * VERTEX_STRIDE;
    long activeVB = vbufs[frameCount % 3];
    if (activeVB != 0 && uploadSize > 0) {
      NativeBridge.nUploadBufferDataDirect(activeVB, vertexStagingBuffer, 0,
                                           uploadSize);
    }
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null) {
      frameCount++;
      return;
    }
    long particlePipeline = cachedParticlePipeline;
    if (particlePipeline == 0) {
      particlePipeline =
          NativeBridge.nGetParticlePipelineHandle(renderer.getHandle());
      if (particlePipeline == 0) {

        long fb = cachedParticleFallbackPipeline;
        if (fb == 0) {
          fb = NativeBridge.nGetEntityTranslucentPipelineHandle(
              renderer.getHandle());
          if (fb != 0)
            cachedParticleFallbackPipeline = fb;
        }
        particlePipeline = fb;
      } else {
        cachedParticlePipeline = particlePipeline;
      }
    }
    if (particlePipeline == 0) {
      if (frameCount < 5)
        MetalLogger.warn("No particle pipeline available");
      frameCount++;
      return;
    }
    NativeBridge.nSetPipelineState(ctx, particlePipeline);
    NativeBridge.nSetChunkOffset(ctx, 0.0f, 0.0f, 0.0f);
    NativeBridge.nSetEntityOverlay(ctx, 0.0f, 0.0f, 1.0f);

    MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
    long fallbackBlockAtlas =
        (wr != null) ? wr.getTextureManager().getBlockAtlasTexture() : 0;
    long lastBoundTex = 0;
    if (fallbackBlockAtlas != 0) {
      NativeBridge.nBindEntityTexture(ctx, fallbackBlockAtlas);
      lastBoundTex = fallbackBlockAtlas;
    }
    int drawsDone = 0;
    for (int _di = 0; _di < pendingDrawCount; _di++) {
      ParticleDrawCommand cmd = pendingDrawPool.get(_di);
      if (cmd.vertexCount <= 0)
        continue;
      if (cmd.glTextureId != 0) {
        long metalTex = getOrCreateMetalTexture(cmd.glTextureId, cmd.atlasId);
        if (metalTex != 0 && metalTex != lastBoundTex) {
          NativeBridge.nBindEntityTexture(ctx, metalTex);
          lastBoundTex = metalTex;
        }
      } else if (fallbackBlockAtlas != 0 &&
                 lastBoundTex != fallbackBlockAtlas) {
        NativeBridge.nBindEntityTexture(ctx, fallbackBlockAtlas);
        lastBoundTex = fallbackBlockAtlas;
      }
      NativeBridge.nDrawEntityBuffer(ctx, activeVB, cmd.vertexCount,
                                     cmd.startVertex, 0x8);
      drawsDone++;
    }
    frameCount++;
    if (frameCount <= 5 || frameCount % 3000 == 0) {
      MetalLogger.info(
          "MetalParticleRenderer: frame %d, %d particles, %d verts, %d draws",
          frameCount, count, vtxCount, drawsDone);
    }
    pendingDrawCount = 0;

    for (int i = 0; i < count; i++)
      capturedParticlePool.get(i).atlasId = null;
    count = 0;
  }

  private void buildParticleGeometry() {
    vertexStagingBuffer.clear();
    vtxCount = 0;
    pendingDrawCount = 0;
    Minecraft mc = Minecraft.getInstance();
    if (mc == null)
      return;
    Camera camera = mc.gameRenderer.getMainCamera();
    if (camera == null)
      return;
    Quaternionf camRot = camera.rotation();
    int currentGlTexId = -1;
    Identifier currentAtlasId = null;
    int batchStartVertex = 0;
    int batchVertexCount = 0;
    for (int _pi = 0; _pi < count; _pi++) {
      CapturedParticle cp = capturedParticlePool.get(_pi);
      if (vtxCount + 6 > MAX_PARTICLE_VERTICES)
        break;
      int glTexId = 0;
      if (cp.atlasId != null) {
        glTexId = getGlTextureIdForAtlas(cp.atlasId);
      }
      if (glTexId != currentGlTexId && batchVertexCount > 0) {
        ParticleDrawCommand cmd;
        if (pendingDrawCount < pendingDrawPool.size()) {
          cmd = pendingDrawPool.get(pendingDrawCount);
        } else {
          cmd = new ParticleDrawCommand();
          pendingDrawPool.add(cmd);
        }
        cmd.startVertex = batchStartVertex;
        cmd.vertexCount = batchVertexCount;
        cmd.glTextureId = currentGlTexId;
        cmd.atlasId = currentAtlasId;
        pendingDrawCount++;
        batchStartVertex = vtxCount;
        batchVertexCount = 0;
      }
      currentGlTexId = glTexId;
      currentAtlasId = cp.atlasId;
      buildBillboardQuad(cp, camRot);
      batchVertexCount += 6;
    }
    if (batchVertexCount > 0) {
      ParticleDrawCommand cmd;
      if (pendingDrawCount < pendingDrawPool.size()) {
        cmd = pendingDrawPool.get(pendingDrawCount);
      } else {
        cmd = new ParticleDrawCommand();
        pendingDrawPool.add(cmd);
      }
      cmd.startVertex = batchStartVertex;
      cmd.vertexCount = batchVertexCount;
      cmd.glTextureId = currentGlTexId;
      cmd.atlasId = currentAtlasId;
      pendingDrawCount++;
    }
    if (frameCount < 5 && vtxCount > 0) {
      MetalLogger.info("Built %d particle verts in %d draw batches", vtxCount,
                       pendingDrawCount);
    }
  }

  private void buildBillboardQuad(CapturedParticle cp, Quaternionf camRot) {

    float size = cp.scale * 0.5f;
    float x = cp.x, y = cp.y, z = cp.z;
    bbRot.set(camRot);
    if (cp.zRotation != 0.0f) {
      bbRot.rotateZ(cp.zRotation);
    }

    for (int i = 0; i < 4; i++) {
      Vector3f c = bbCorners[i];
      c.set(CORNER_OFFSETS[i][0], CORNER_OFFSETS[i][1], CORNER_OFFSETS[i][2]);
      bbRot.transform(c);
      c.mul(size);
      c.add(x, y, z);
    }
    int r = (int)(cp.red * 255.0f) & 0xFF;
    int g = (int)(cp.green * 255.0f) & 0xFF;
    int b = (int)(cp.blue * 255.0f) & 0xFF;
    int a = (int)(cp.alpha * 255.0f) & 0xFF;
    int color = (a << 24) | (r << 16) | (g << 8) | b;
    bbNormal.set(0, 0, 1);
    camRot.transform(bbNormal);
    float nx = bbNormal.x, ny = bbNormal.y, nz = bbNormal.z;
    int light = cp.light;

    float u0 = cp.maxU, u1 = cp.minU, u2 = cp.minU, u3 = cp.maxU;
    float v0 = cp.maxV, v1 = cp.maxV, v2 = cp.minV, v3 = cp.minV;

    writeParticleVertex(bbCorners[0].x, bbCorners[0].y, bbCorners[0].z, u0, v0,
                        color, nx, ny, nz, light);
    writeParticleVertex(bbCorners[1].x, bbCorners[1].y, bbCorners[1].z, u1, v1,
                        color, nx, ny, nz, light);
    writeParticleVertex(bbCorners[2].x, bbCorners[2].y, bbCorners[2].z, u2, v2,
                        color, nx, ny, nz, light);
    writeParticleVertex(bbCorners[0].x, bbCorners[0].y, bbCorners[0].z, u0, v0,
                        color, nx, ny, nz, light);
    writeParticleVertex(bbCorners[2].x, bbCorners[2].y, bbCorners[2].z, u2, v2,
                        color, nx, ny, nz, light);
    writeParticleVertex(bbCorners[3].x, bbCorners[3].y, bbCorners[3].z, u3, v3,
                        color, nx, ny, nz, light);
  }

  private void writeParticleVertex(float px, float py, float pz, float u,
                                   float v, int color, float nx, float ny,
                                   float nz, int light) {
    if (vtxCount >= MAX_PARTICLE_VERTICES ||
        vertexStagingBuffer.remaining() < VERTEX_STRIDE)
      return;
    vertexStagingBuffer.putFloat(px);
    vertexStagingBuffer.putFloat(py);
    vertexStagingBuffer.putFloat(pz);
    int iU = (int)(Math.min(Math.max(u, 0.0f), 1.0f) * 32767.0f);
    int iV = (int)(Math.min(Math.max(v, 0.0f), 1.0f) * 32767.0f);
    vertexStagingBuffer.putShort((short)(iU & 0x7FFF));
    vertexStagingBuffer.putShort((short)(iV & 0x7FFF));
    int cr = (color >> 16) & 0xFF;
    int cg = (color >> 8) & 0xFF;
    int cb = color & 0xFF;
    int ca = (color >> 24) & 0xFF;
    vertexStagingBuffer.put((byte)cr);
    vertexStagingBuffer.put((byte)cg);
    vertexStagingBuffer.put((byte)cb);
    vertexStagingBuffer.put((byte)ca);
    vertexStagingBuffer.put((byte)(int)((nx * 0.5f + 0.5f) * 255.0f));
    vertexStagingBuffer.put((byte)(int)((ny * 0.5f + 0.5f) * 255.0f));
    vertexStagingBuffer.put((byte)(int)((nz * 0.5f + 0.5f) * 255.0f));
    vertexStagingBuffer.put((byte)255);
    vertexStagingBuffer.putShort((short)0);
    vertexStagingBuffer.putShort((short)0);

    int blockL = light & 0xFFFF;
    int skyL = (light >> 16) & 0xFFFF;
    vertexStagingBuffer.putShort((short)blockL);
    vertexStagingBuffer.putShort((short)skyL);
    vtxCount++;
  }

  private long getOrCreateMetalTexture(int glTextureId, Identifier atlasId) {
    if (glTextureId == 0 || device == 0)
      return 0;
    boolean inBounds = glTextureId >= 0 && glTextureId < TEXTURE_CACHE_SIZE;
    int lastUpload = inBounds ? textureUploadFrame[glTextureId] : -1;
    long cached = inBounds ? textureCache[glTextureId] : TEXTURE_UNCACHED;
    boolean needsRefresh = shouldRefreshTexture(cached, lastUpload, atlasId);
    if (cached != TEXTURE_UNCACHED && !needsRefresh)
      return cached;
    try {
      int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTextureId);
      int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
                                               GL11.GL_TEXTURE_WIDTH);
      int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
                                                GL11.GL_TEXTURE_HEIGHT);
      if (width <= 0 || height <= 0 || width > 8192 || height > 8192) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        if (inBounds)
          textureCache[glTextureId] = 0L;
        return 0;
      }
      int reqSz = width * height * 4;
      if (textureReadbackBuf == null || textureReadbackBuf.capacity() < reqSz) {
        textureReadbackBuf =
            ByteBuffer.allocateDirect(reqSz).order(ByteOrder.nativeOrder());
        texturePixelBuf = new byte[reqSz];
      }
      ByteBuffer pixels = textureReadbackBuf;
      pixels.clear();
      GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
                         GL11.GL_UNSIGNED_BYTE, pixels);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
      pixels.rewind();
      if (texturePixelBuf.length < reqSz)
        texturePixelBuf = new byte[reqSz];
      byte[] pixelData = texturePixelBuf;
      pixels.get(pixelData, 0, reqSz);
      if (cached != TEXTURE_UNCACHED && cached != 0) {
        NativeBridge.nUpdateTexture2D(cached, width, height, pixelData);
        if (inBounds)
          textureUploadFrame[glTextureId] = frameCount;
        return cached;
      }
      long metalTex =
          NativeBridge.nCreateTexture2D(device, width, height, pixelData);
      if (inBounds) {
        textureCache[glTextureId] = metalTex;
        textureUploadFrame[glTextureId] = frameCount;
      }
      if (metalTex != 0 && frameCount < 3) {
        MetalLogger.info(
            "Created/refreshed Metal particle texture: glId=%d %dx%d handle=%d",
            glTextureId, width, height, metalTex);
      }
      return metalTex;
    } catch (Exception e) {
      MetalLogger.error("Failed to create Metal particle texture glId=%d: %s",
                        glTextureId, e.getMessage());
      if (inBounds)
        textureCache[glTextureId] = 0L;
      return 0;
    }
  }

  private boolean shouldRefreshTexture(long cached, int lastUpload,
                                       Identifier atlasId) {
    if (cached == TEXTURE_UNCACHED || lastUpload < 0) {
      return true;
    }
    if (TextureAtlas.LOCATION_BLOCKS.equals(atlasId)) {
      return MetalTextureManager.atlasDirty;
    }
    return frameCount - lastUpload >= ATLAS_REFRESH_FRAMES;
  }

  private int getGlTextureIdForAtlas(Identifier atlasId) {
    try {
      Minecraft mc = Minecraft.getInstance();
      if (mc == null)
        return 0;
      AbstractTexture tex = mc.getTextureManager().getTexture(atlasId);
      if (tex == null)
        return 0;
      var gpuTex = tex.getTexture();
      if (gpuTex instanceof GlTexture glTex) {
        return glTex.glId();
      }
    } catch (Exception e) {
    }
    return 0;
  }

  public void invalidateTextureCache() {
    for (int _ti = 0; _ti < TEXTURE_CACHE_SIZE; _ti++) {
      long h = textureCache[_ti];
      if (h > 0)
        NativeBridge.nDestroyTexture2D(h);
    }
    java.util.Arrays.fill(textureCache, TEXTURE_UNCACHED);
    java.util.Arrays.fill(textureUploadFrame, -1);
    MetalLogger.info("Particle texture cache invalidated");
  }

  public int getLastParticleCount() { return count; }

  public int getLastVertexCount() { return vtxCount; }

  public void shutdown() {
    active = false;
    cachedParticlePipeline = 0;
    cachedParticleFallbackPipeline = 0;
    textureReadbackBuf = null;
    texturePixelBuf = null;
    for (int i = 0; i < count; i++)
      capturedParticlePool.get(i).atlasId = null;
    count = 0;
    for (int _ti = 0; _ti < TEXTURE_CACHE_SIZE; _ti++) {
      long h = textureCache[_ti];
      if (h > 0)
        NativeBridge.nDestroyTexture2D(h);
    }
    java.util.Arrays.fill(textureCache, TEXTURE_UNCACHED);
    java.util.Arrays.fill(textureUploadFrame, -1);
    for (int i = 0; i < 3; i++) {
      if (vbufs[i] != 0) {
        NativeBridge.nDestroyBuffer(vbufs[i]);
        vbufs[i] = 0;
      }
    }
    device = 0;
    MetalLogger.info("MetalParticleRenderer shut down");
  }

  private static class CapturedParticle {
    float x, y, z;
    float scale;
    float red, green, blue, alpha;
    float zRotation;
    float minU, maxU, minV, maxV;
    int light;
    Identifier atlasId;
  }

  private static class ParticleDrawCommand {
    int startVertex;
    int vertexCount;
    int glTextureId;
    Identifier atlasId;
  }
}
