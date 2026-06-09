package com.pebbles_boon.metalrender.entity;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class MetalRenderCommandQueue implements SubmitNodeCollector {
  private VertexConsumer vertexConsumer;
  private int requestedGlTextureId;
  private int defaultLight;

  public MetalRenderCommandQueue(VertexConsumer vertexConsumer, int light) {
    this.vertexConsumer = vertexConsumer;
    this.defaultLight = light;
  }

  public void reset(VertexConsumer vertexConsumer, int light) {
    this.vertexConsumer = vertexConsumer;
    this.requestedGlTextureId = 0;
    this.defaultLight = light;
  }

  public int getRequestedGlTextureId() {
    return requestedGlTextureId;
  }

  @Override
  public OrderedSubmitNodeCollector order(int index) {
    return this;
  }

  @Override
  public <S> void submitModel(Model<? super S> model, S state,
      PoseStack matrices, RenderType layer, int light,
      int overlay, int color, TextureAtlasSprite sprite,
      int flags,
      ModelFeatureRenderer.CrumblingOverlay crumbling) {
    if (model == null) {
      return;
    }
    invokeSetupAnim(model, state);
    invokeRenderToBuffer(model, matrices, light, overlay, color);
  }

  @Override
  public void submitModelPart(ModelPart part, PoseStack matrices, RenderType layer,
      int light, int overlay, TextureAtlasSprite sprite,
      boolean visible, boolean noCull, int color,
      ModelFeatureRenderer.CrumblingOverlay crumbling, int extra) {
    if (part != null && (visible || noCull)) {
      part.render(matrices, vertexConsumer, light, overlay, color);
    }
  }

  @Override
  public void submitShadow(PoseStack matrices, float radius,
      List<EntityRenderState.ShadowPiece> pieces) {
  }

  @Override
  public void submitNameTag(PoseStack matrices, Vec3 pos, int bgColor,
      Component text, boolean seeThrough, int textColor,
      double distance, CameraRenderState camera) {
  }

  @Override
  public void submitText(PoseStack matrices, float x, float y,
      FormattedCharSequence text, boolean shadow,
      Font.DisplayMode layerType, int bgColor, int textColor,
      int light, int sortOrder) {
  }

  @Override
  public void submitFlame(PoseStack matrices, EntityRenderState state,
      Quaternionf rotation) {
  }

  @Override
  public void submitLeash(PoseStack matrices,
      EntityRenderState.LeashState leashData) {
  }

  @Override
  public void submitMovingBlock(PoseStack matrices,
      MovingBlockRenderState state) {
    if (state == null || state.blockState == null || vertexConsumer == null) {
      return;
    }

    TextureAtlasSprite sprite = getMovingBlockSprite(state);
    int blockAtlasTextureId = getBlockAtlasTextureId();
    if (sprite == null || blockAtlasTextureId == 0) {
      return;
    }

    int light = defaultLight != 0 ? defaultLight : 0x00F000F0;
    int color = 0xFFFFFFFF;
    float u0 = sprite.getU0();
    float u1 = sprite.getU1();
    float v0 = sprite.getV0();
    float v1 = sprite.getV1();

    emitTexturedQuad(matrices,
        0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f,
        1.0f, 1.0f, 1.0f, 0.0f, 1.0f, 1.0f,
        0.0f, 0.0f, 1.0f, u0, u1, v0, v1, color, light);
    emitTexturedQuad(matrices,
        1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f,
        0.0f, 0.0f, -1.0f, u0, u1, v0, v1, color, light);
    emitTexturedQuad(matrices,
        0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f,
        1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f,
        0.0f, 1.0f, 0.0f, u0, u1, v0, v1, color, light);
    emitTexturedQuad(matrices,
        0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f,
        1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f,
        0.0f, -1.0f, 0.0f, u0, u1, v0, v1, color, light);
    emitTexturedQuad(matrices,
        1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f,
        1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 1.0f,
        1.0f, 0.0f, 0.0f, u0, u1, v0, v1, color, light);
    emitTexturedQuad(matrices,
        0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f,
        0.0f, 1.0f, 1.0f, 0.0f, 1.0f, 0.0f,
        -1.0f, 0.0f, 0.0f, u0, u1, v0, v1, color, light);
    requestedGlTextureId = blockAtlasTextureId;
  }

  @Override
  public void submitBlockModel(PoseStack matrices, RenderType layer,
      List<BlockStateModelPart> parts, int[] colors,
      int x, int y, int z) {
  }

  @Override
  public void submitBreakingBlockModel(PoseStack matrices,
      BlockStateModel model, long seed,
      int color) {
  }

  @Override
  public void submitItem(PoseStack matrices, ItemDisplayContext context,
      int light, int overlay, int color, int[] tintColors,
      List<BakedQuad> quads,
      ItemStackRenderState.FoilType foilType) {
  }

  @Override
  public void submitCustomGeometry(PoseStack matrices, RenderType layer,
      SubmitNodeCollector.CustomGeometryRenderer custom) {
    if (custom != null) {
      custom.render(matrices.last(), vertexConsumer);
    }
  }

  @Override
  public void submitParticleGroup(SubmitNodeCollector.ParticleGroupRenderer particleGroup) {
  }

  private static void invokeSetupAnim(Model<?> model, Object state) {
    try {
      Method method = model.getClass().getMethod("setupAnim", Object.class);
      method.invoke(model, state);
    } catch (ReflectiveOperationException ignored) {
    }
  }

  private void invokeRenderToBuffer(Model<?> model, PoseStack matrices,
      int light, int overlay, int color) {
    try {
      Method method = model.getClass().getMethod(
          "renderToBuffer", PoseStack.class, VertexConsumer.class, int.class,
          int.class, int.class);
      method.invoke(model, matrices, vertexConsumer, light, overlay, color);
    } catch (ReflectiveOperationException ignored) {
      try {
        Method rootMethod = model.getClass().getMethod("root");
        Object root = rootMethod.invoke(model);
        if (root instanceof ModelPart modelPart) {
          modelPart.render(matrices, vertexConsumer, light, overlay, color);
        }
      } catch (ReflectiveOperationException ignoredAgain) {
      }
    }
  }

  private boolean invokeNamedMethod(Object target, String[] methodNames,
      Object... args) {
    if (target == null) {
      return false;
    }
    for (String methodName : methodNames) {
      Method method = findCompatibleMethod(target.getClass(), methodName, args);
      if (method == null) {
        continue;
      }
      try {
        method.setAccessible(true);
        method.invoke(target, args);
        return true;
      } catch (ReflectiveOperationException ignored) {
      }
    }
    return false;
  }

  private TextureAtlasSprite getMovingBlockSprite(MovingBlockRenderState state) {
    Minecraft mc = Minecraft.getInstance();
    if (mc == null || mc.getModelManager() == null) {
      return null;
    }

    BlockStateModelSet modelSet = mc.getModelManager().getBlockStateModelSet();
    if (modelSet == null) {
      return null;
    }

    try {
      return modelSet.getParticleMaterial(state.blockState).sprite();
    } catch (Exception ignored) {
      BlockStateModel model = modelSet.get(state.blockState);
      return model != null ? model.particleMaterial().sprite() : null;
    }
  }

  private void emitTexturedQuad(PoseStack matrices,
      float x0, float y0, float z0,
      float x1, float y1, float z1,
      float x2, float y2, float z2,
      float x3, float y3, float z3,
      float nx, float ny, float nz,
      float u0, float u1, float v0, float v1,
      int color, int light) {
    if (!(vertexConsumer instanceof MetalVertexConsumer metalVertexConsumer)) {
      return;
    }

    Vector3f normal = new Vector3f(nx, ny, nz);
    matrices.last().normal().transform(normal);
    if (normal.lengthSquared() > 1.0e-6f) {
      normal.normalize();
    }

    emitVertex(metalVertexConsumer, matrices, x0, y0, z0, u0, v1, color, light,
        normal);
    emitVertex(metalVertexConsumer, matrices, x1, y1, z1, u1, v1, color, light,
        normal);
    emitVertex(metalVertexConsumer, matrices, x2, y2, z2, u1, v0, color, light,
        normal);
    emitVertex(metalVertexConsumer, matrices, x3, y3, z3, u0, v0, color, light,
        normal);
  }

  private void emitVertex(MetalVertexConsumer metalVertexConsumer,
      PoseStack matrices, float x, float y, float z,
      float u, float v, int color, int light,
      Vector3f normal) {
    Vector3f position = new Vector3f(x, y, z);
    matrices.last().pose().transformPosition(position);
    metalVertexConsumer.vertex(position.x, position.y, position.z, color, u, v,
        0, light, normal.x, normal.y, normal.z);
  }

  private Object invokeNamedMethodValue(Object target, String[] methodNames,
      Object... args) {
    if (target == null) {
      return null;
    }
    for (String methodName : methodNames) {
      Method method = findCompatibleMethod(target.getClass(), methodName, args);
      if (method == null) {
        continue;
      }
      try {
        method.setAccessible(true);
        return method.invoke(target, args);
      } catch (ReflectiveOperationException ignored) {
      }
    }
    return null;
  }

  private Object getNamedFieldValue(Object target, String[] fieldNames) {
    if (target == null) {
      return null;
    }
    for (Class<?> current = target.getClass(); current != null; current = current.getSuperclass()) {
      for (String fieldName : fieldNames) {
        try {
          var field = current.getDeclaredField(fieldName);
          field.setAccessible(true);
          return field.get(target);
        } catch (ReflectiveOperationException ignored) {
        }
      }
    }
    return null;
  }

  private Method findCompatibleMethod(Class<?> targetClass, String methodName,
      Object[] args) {
    for (Class<?> current = targetClass; current != null; current = current.getSuperclass()) {
      for (Method method : current.getDeclaredMethods()) {
        if (!method.getName().equals(methodName) ||
            method.getParameterCount() != args.length) {
          continue;
        }
        Class<?>[] parameterTypes = method.getParameterTypes();
        boolean compatible = true;
        for (int index = 0; index < parameterTypes.length; index++) {
          if (!isCompatibleParameter(parameterTypes[index], args[index])) {
            compatible = false;
            break;
          }
        }
        if (compatible) {
          return method;
        }
      }
    }
    return null;
  }

  private boolean isCompatibleParameter(Class<?> parameterType, Object arg) {
    if (arg == null) {
      return !parameterType.isPrimitive();
    }
    if (parameterType.isInstance(arg)) {
      return true;
    }
    if (parameterType == boolean.class && arg instanceof Boolean) {
      return true;
    }
    return false;
  }

  private int getBlockAtlasTextureId() {
    Minecraft mc = Minecraft.getInstance();
    if (mc == null || mc.getTextureManager() == null) {
      return 0;
    }
    AbstractTexture atlasTexture = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
    if (atlasTexture != null && atlasTexture.getTexture() instanceof GlTexture glTexture) {
      return glTexture.glId();
    }
    return 0;
  }
}
