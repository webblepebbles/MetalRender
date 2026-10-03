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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class MetalRenderCommandQueue implements SubmitNodeCollector {

    public static final class DrawSegment {
        public int startVertex;
        public int vertexCount;
        public int glTextureId;
        public int renderFlags;
    }

    private VertexConsumer vertexConsumer;
    private int requestedGlTextureId;
    private int defaultLight;
    private final java.util.ArrayList<DrawSegment> segments = new java.util.ArrayList<>();

    public MetalRenderCommandQueue(VertexConsumer vertexConsumer, int light) {
        this.vertexConsumer = vertexConsumer;
        this.defaultLight = light;
    }

    public void reset(VertexConsumer vertexConsumer, int light) {
        this.vertexConsumer = vertexConsumer;
        this.requestedGlTextureId = 0;
        this.defaultLight = light;
        this.segments.clear();
    }

    public int getRequestedGlTextureId() {
        return requestedGlTextureId;
    }

    public java.util.List<DrawSegment> getSegments() {
        return segments;
    }

    @Override
    public OrderedSubmitNodeCollector order(int index) {
        return this;
    }

    private int currentVertexCount() {
        if (vertexConsumer instanceof MetalVertexConsumer mvc) {
            return mvc.getVertexCount();
        }
        return -1;
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
        int start = currentVertexCount();
        invokeSetupAnim(model, state);
        invokeRenderToBuffer(model, matrices, light, overlay, color);
        int end = currentVertexCount();
        if (start >= 0 && end > start) {
            int glId = resolveRenderTypeTexture(layer);
            if (glId != 0) {
                requestedGlTextureId = glId;
            }
            DrawSegment seg = new DrawSegment();
            seg.startVertex = start;
            seg.vertexCount = end - start;
            seg.glTextureId = glId;
            seg.renderFlags = renderFlagsFor(layer);
            segments.add(seg);
        }
    }

    @Override
    public void submitModelPart(ModelPart part, PoseStack matrices, RenderType layer,
            int light, int overlay, TextureAtlasSprite sprite,
            boolean visible, boolean noCull, int color,
            ModelFeatureRenderer.CrumblingOverlay crumbling, int extra) {
        if (part != null && (visible || noCull)) {
            int start = currentVertexCount();
            part.render(matrices, vertexConsumer, light, overlay, color);
            int end = currentVertexCount();
            if (start >= 0 && end > start) {
                int glId = resolveRenderTypeTexture(layer);
                if (glId != 0) {
                    requestedGlTextureId = glId;
                }
                DrawSegment seg = new DrawSegment();
                seg.startVertex = start;
                seg.vertexCount = end - start;
                seg.glTextureId = glId;
                seg.renderFlags = renderFlagsFor(layer);
                segments.add(seg);
            }
        }
    }

    private static int renderFlagsFor(RenderType layer) {
        if (layer == null) {
            return 0;
        }
        try {
            String name = layer.toString();
            String lower = name != null ? name.toLowerCase(java.util.Locale.ROOT) : "";
            if (lower.contains("eyes") || lower.contains("emissive") || lower.contains("energy")
                    || lower.contains("glint") || lower.contains("swirl") || lower.contains("beam")) {
                return 0x2;
            }
        } catch (Exception ignored) {
        }
        try {
            if (layer.hasBlending()) {
                return 0x1;
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static int resolveRenderTypeTexture(RenderType layer) {
        if (layer == null) {
            return 0;
        }
        try {

            java.lang.reflect.Field stateField = null;
            for (Class<?> c = layer.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    stateField = c.getDeclaredField("state");
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }
            if (stateField == null) {
                return 0;
            }
            stateField.setAccessible(true);
            Object setup = stateField.get(layer);
            if (setup == null) {
                return 0;
            }
            java.lang.reflect.Field texturesField = null;
            for (Class<?> c = setup.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    texturesField = c.getDeclaredField("textures");
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }
            if (texturesField == null) {
                return 0;
            }
            texturesField.setAccessible(true);
            Object mapObj = texturesField.get(setup);
            if (!(mapObj instanceof java.util.Map<?, ?> texMap) || texMap.isEmpty()) {
                return 0;
            }
            for (Object binding : texMap.values()) {
                if (binding == null) {
                    continue;
                }
                try {

                    java.lang.reflect.Method locMethod = null;
                    try {
                        locMethod = binding.getClass().getDeclaredMethod("location");
                    } catch (NoSuchMethodException e) {
                        locMethod = binding.getClass().getMethod("location");
                    }
                    locMethod.setAccessible(true);
                    Object idObj = locMethod.invoke(binding);
                    if (idObj instanceof net.minecraft.resources.Identifier identifier) {
                        int glId = getTextureGlId(identifier);
                        if (glId != 0) {
                            return glId;
                        }

                    }
                } catch (Exception ignored) {
                }
            }

            try {
                java.lang.reflect.Method getTextures = setup.getClass().getMethod("getTextures");
                getTextures.setAccessible(true);
                Object map2 = getTextures.invoke(setup);
                if (map2 instanceof java.util.Map<?, ?> m2) {
                    for (Object sampler : m2.values()) {
                        int glId = glIdFromTextureAndSampler(sampler);
                        if (glId != 0) {
                            return glId;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static int glIdFromTextureAndSampler(Object textureAndSampler) {
        if (textureAndSampler == null) {
            return 0;
        }
        try {
            java.lang.reflect.Method viewMethod = null;
            try {
                viewMethod = textureAndSampler.getClass().getDeclaredMethod("textureView");
            } catch (NoSuchMethodException e) {
                viewMethod = textureAndSampler.getClass().getMethod("textureView");
            }
            viewMethod.setAccessible(true);
            Object view = viewMethod.invoke(textureAndSampler);
            if (view == null) {
                return 0;
            }

            for (Class<?> c = view.getClass(); c != null; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(view);
                        if (v instanceof GlTexture gl) {
                            return gl.glId();
                        }
                        if (v != null) {

                            for (Class<?> c2 = v.getClass(); c2 != null
                                    && c2 != Object.class; c2 = c2.getSuperclass()) {
                                for (java.lang.reflect.Field f2 : c2.getDeclaredFields()) {
                                    try {
                                        f2.setAccessible(true);
                                        Object v2 = f2.get(v);
                                        if (v2 instanceof GlTexture gl2) {
                                            return gl2.glId();
                                        }
                                    } catch (Exception ignored) {
                                    }
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static int getTextureGlId(net.minecraft.resources.Identifier textureId) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getTextureManager() == null) {
                return 0;
            }
            AbstractTexture texture = mc.getTextureManager().getTexture(textureId);
            if (texture != null && texture.getTexture() instanceof GlTexture glTexture) {
                return glTexture.glId();
            }
        } catch (Exception ignored) {
        }
        return 0;
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
        if (!(vertexConsumer instanceof MetalVertexConsumer metalVertexConsumer)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getModelManager() == null) {
            return;
        }
        BlockStateModelSet modelSet = mc.getModelManager().getBlockStateModelSet();
        if (modelSet == null) {
            return;
        }
        BlockStateModel model;
        try {
            model = modelSet.get(state.blockState);
        } catch (Exception ignored) {
            return;
        }
        if (model == null) {
            return;
        }

        int blockAtlasTextureId = getBlockAtlasTextureId();
        if (blockAtlasTextureId == 0) {
            return;
        }

        BlockPos seedPos = state.randomSeedPos != null ? state.randomSeedPos
                : (state.blockPos != null ? state.blockPos : BlockPos.ZERO);
        RandomSource random;
        try {
            random = RandomSource.create();
            random.setSeed(state.blockState.getSeed(seedPos));
        } catch (Exception ignored) {
            random = RandomSource.create();
        }

        java.util.ArrayList<BlockStateModelPart> parts = new java.util.ArrayList<>(4);
        try {
            model.collectParts(random, parts);
        } catch (Exception ignored) {
            return;
        }
        if (parts.isEmpty()) {
            return;
        }

        int light = defaultLight != 0 ? defaultLight : 0x00F000F0;
        int start = currentVertexCount();
        for (BlockStateModelPart part : parts) {
            if (part == null) {
                continue;
            }
            for (Direction dir : Direction.values()) {
                java.util.List<BakedQuad> quads;
                try {
                    quads = part.getQuads(dir);
                } catch (Exception ignored) {
                    continue;
                }
                if (quads == null || quads.isEmpty()) {
                    continue;
                }
                for (BakedQuad quad : quads) {
                    if (quad != null) {
                        emitMovingBlockQuad(metalVertexConsumer, matrices, quad, state, light);
                    }
                }
            }
            java.util.List<BakedQuad> unculled;
            try {
                unculled = part.getQuads(null);
            } catch (Exception ignored) {
                continue;
            }
            if (unculled == null || unculled.isEmpty()) {
                continue;
            }
            for (BakedQuad quad : unculled) {
                if (quad != null) {
                    emitMovingBlockQuad(metalVertexConsumer, matrices, quad, state, light);
                }
            }
        }
        int end = currentVertexCount();
        if (end > start) {
            requestedGlTextureId = blockAtlasTextureId;
        }
    }

    @Override
    public void submitBlockModel(PoseStack matrices, RenderType layer,
            List<BlockStateModelPart> parts, int[] colors,
            int x, int y, int z) {
        if (matrices == null || parts == null || parts.isEmpty() || vertexConsumer == null) {
            return;
        }
        if (!(vertexConsumer instanceof MetalVertexConsumer metalVertexConsumer)) {
            return;
        }
        int blockAtlasTextureId = getBlockAtlasTextureId();
        if (blockAtlasTextureId == 0) {
            return;
        }
        int start = currentVertexCount();
        for (BlockStateModelPart part : parts) {
            if (part == null) {
                continue;
            }
            for (Direction dir : Direction.values()) {
                java.util.List<BakedQuad> quads;
                try {
                    quads = part.getQuads(dir);
                } catch (Exception ignored) {
                    continue;
                }
                if (quads == null || quads.isEmpty()) {
                    continue;
                }
                for (BakedQuad quad : quads) {
                    if (quad != null) {
                        emitBlockModelQuad(metalVertexConsumer, matrices, quad, colors,
                                defaultLight != 0 ? defaultLight : 0x00F000F0);
                    }
                }
            }
            java.util.List<BakedQuad> unculled;
            try {
                unculled = part.getQuads(null);
            } catch (Exception ignored) {
                continue;
            }
            if (unculled == null || unculled.isEmpty()) {
                continue;
            }
            for (BakedQuad quad : unculled) {
                if (quad != null) {
                    emitBlockModelQuad(metalVertexConsumer, matrices, quad, colors,
                            defaultLight != 0 ? defaultLight : 0x00F000F0);
                }
            }
        }
        int end = currentVertexCount();
        if (end > start) {
            if (requestedGlTextureId == 0) {
                requestedGlTextureId = blockAtlasTextureId;
            }
            DrawSegment seg = new DrawSegment();
            seg.startVertex = start;
            seg.vertexCount = end - start;
            seg.glTextureId = blockAtlasTextureId;
            seg.renderFlags = renderFlagsFor(layer);
            segments.add(seg);
        }
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
            int start = currentVertexCount();
            custom.render(matrices.last(), vertexConsumer);
            int end = currentVertexCount();
            if (start >= 0 && end > start) {
                int glId = resolveRenderTypeTexture(layer);
                if (glId != 0) {
                    requestedGlTextureId = glId;
                }
                DrawSegment seg = new DrawSegment();
                seg.startVertex = start;
                seg.vertexCount = end - start;
                seg.glTextureId = glId;
                seg.renderFlags = renderFlagsFor(layer);
                segments.add(seg);
            }
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

    private void emitMovingBlockQuad(MetalVertexConsumer mvc, PoseStack matrices,
            BakedQuad quad, MovingBlockRenderState state, int light) {
        int tintIndex;
        boolean shade;
        int emission;
        try {
            tintIndex = quad.materialInfo().tintIndex();
            shade = quad.materialInfo().shade();
            emission = quad.materialInfo().lightEmission();
        } catch (Exception ignored) {
            tintIndex = -1;
            shade = true;
            emission = 0;
        }
        int baseColor = 0xFFFFFFFF;
        if (tintIndex >= 0) {
            baseColor = resolveBlockTint(state.blockState, state,
                    state.blockPos != null ? state.blockPos : BlockPos.ZERO, tintIndex);
        }
        float shadeFactor = 1.0f;
        if (shade) {
            shadeFactor = diffuseShade(state, quad.direction());
        }
        if (shadeFactor != 1.0f) {
            int r = Math.min(255, (int) (((baseColor >> 16) & 0xFF) * shadeFactor));
            int g = Math.min(255, (int) (((baseColor >> 8) & 0xFF) * shadeFactor));
            int b = Math.min(255, (int) ((baseColor & 0xFF) * shadeFactor));
            baseColor = (baseColor & 0xFF000000) | (r << 16) | (g << 8) | b;
        }
        if (emission > 0) {
            light = 0x00F000F0;
        }
        emitBakedQuadVertices(mvc, matrices, quad, baseColor, light);
    }

    private void emitBlockModelQuad(MetalVertexConsumer mvc, PoseStack matrices,
            BakedQuad quad, int[] colors, int light) {
        int tintIndex;
        boolean shade;
        int emission;
        try {
            tintIndex = quad.materialInfo().tintIndex();
            shade = quad.materialInfo().shade();
            emission = quad.materialInfo().lightEmission();
        } catch (Exception ignored) {
            tintIndex = -1;
            shade = true;
            emission = 0;
        }
        int baseColor = 0xFFFFFFFF;
        if (tintIndex >= 0 && colors != null && tintIndex < colors.length) {
            int c = colors[tintIndex];
            if ((c & 0xFF000000) == 0) {
                c |= 0xFF000000;
            }
            baseColor = c;
        }
        float shadeFactor = 1.0f;
        if (shade) {
            shadeFactor = diffuseShade(null, quad.direction());
        }
        if (shadeFactor != 1.0f) {
            int r = Math.min(255, (int) (((baseColor >> 16) & 0xFF) * shadeFactor));
            int g = Math.min(255, (int) (((baseColor >> 8) & 0xFF) * shadeFactor));
            int b = Math.min(255, (int) ((baseColor & 0xFF) * shadeFactor));
            baseColor = (baseColor & 0xFF000000) | (r << 16) | (g << 8) | b;
        }
        if (emission > 0) {
            light = 0x00F000F0;
        }
        emitBakedQuadVertices(mvc, matrices, quad, baseColor, light);
    }

    private static float diffuseShade(MovingBlockRenderState state, Direction dir) {
        if (dir == null) {
            return 1.0f;
        }
        if (state != null) {
            try {
                if (state.cardinalLighting() != null) {
                    return state.cardinalLighting().byFace(dir);
                }
            } catch (Exception ignored) {
            }
        }
        return switch (dir) {
            case DOWN -> 0.5f;
            case UP -> 1.0f;
            case NORTH, SOUTH -> 0.8f;
            case WEST, EAST -> 0.6f;
        };
    }

    private static final float ENTITY_SHADER_LX = 0.2f;
    private static final float ENTITY_SHADER_LY = 1.0f;
    private static final float ENTITY_SHADER_LZ = 0.5f;

    private void emitBakedQuadVertices(MetalVertexConsumer mvc, PoseStack matrices,
            BakedQuad quad, int color, int light) {
        float invLen = 1.0f / (float) Math.sqrt(
                ENTITY_SHADER_LX * ENTITY_SHADER_LX + ENTITY_SHADER_LY * ENTITY_SHADER_LY
                        + ENTITY_SHADER_LZ * ENTITY_SHADER_LZ);
        float nnx = ENTITY_SHADER_LX * invLen;
        float nny = ENTITY_SHADER_LY * invLen;
        float nnz = ENTITY_SHADER_LZ * invLen;
        for (int i = 0; i < 4; i++) {
            org.joml.Vector3fc pos;
            long packed;
            try {
                pos = quad.position(i);
                packed = quad.packedUV(i);
            } catch (Exception ignored) {
                return;
            }
            float u = Float.intBitsToFloat((int) (packed >> 32));
            float v = Float.intBitsToFloat((int) packed);
            Vector3f p = new Vector3f(pos.x(), pos.y(), pos.z());
            try {
                matrices.last().pose().transformPosition(p);
            } catch (Exception ignored) {
            }
            mvc.vertex(p.x, p.y, p.z, color, u, v, 0, light, nnx, nny, nnz);
        }
    }

    private static int resolveBlockTint(BlockState blockState,
            net.minecraft.client.renderer.block.BlockAndTintGetter level,
            BlockPos pos, int tintIndex) {
        if (tintIndex < 0 || blockState == null) {
            return 0xFFFFFFFF;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getBlockColors() == null) {
                return 0xFFFFFFFF;
            }
            net.minecraft.client.color.block.BlockTintSource source =
                    mc.getBlockColors().getTintSource(blockState, tintIndex);
            if (source == null) {
                return 0xFFFFFFFF;
            }
            int rgb;
            if (level != null && pos != null) {
                rgb = source.colorInWorld(blockState, level, pos);
            } else {
                rgb = source.color(blockState);
            }
            if (rgb == -1) {
                return 0xFFFFFFFF;
            }
            return 0xFF000000 | (rgb & 0x00FFFFFF);
        } catch (Exception ignored) {
            return 0xFFFFFFFF;
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
