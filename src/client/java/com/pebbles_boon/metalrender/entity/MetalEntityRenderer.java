package com.pebbles_boon.metalrender.entity;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.vertex.PoseStack;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.backend.MetalRenderer;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.render.CapturedMatrices;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

public class MetalEntityRenderer {
    private static final int ENTITY_VERTEX_STRIDE = 32;
    private static final int MAX_BATCH_VERTICES = 262144;
    private static final int MAX_ENTITIES_PER_FRAME = 512;
    private static final int SUBMERGED_HOLD_FRAMES = 20;
    private static final int TEXTURE_CACHE_SIZE = 2048;
    private static final long TEXTURE_UNCACHED = Long.MIN_VALUE;
    private static final Identifier FIRE_SPRITE_0 = Identifier.fromNamespaceAndPath("minecraft", "block/fire_0");
    private static final Identifier FIRE_SPRITE_1 = Identifier.fromNamespaceAndPath("minecraft", "block/fire_1");

    private long device;
    private boolean active;
    private int frameCount;
    private long lastEntityLogTime;
    private int captureCallsPerSec;
    private int renderCallsPerSec;
    private int entitiesCapturedPerSec;
    private final ByteBuffer vertexStagingBuffer;
    private final long[] vbufs = new long[3];
    private int vtxCount;
    private long cachedEntityPipeline;
    private final MetalVertexConsumer metalVertexConsumer;
    private final ArrayList<EntityDrawCommand> pendingDrawPool = new ArrayList<>();
    private int pendingDrawCount;
    private final ArrayList<CapturedEntity> capturedEntityPool = new ArrayList<>();
    private int count;
    private final HashMap<Integer, Integer> submergedHoldFrames = new HashMap<>();
    private final long[] textureCache = new long[TEXTURE_CACHE_SIZE];
    private final PoseStack matrixStack = new PoseStack();
    private final CameraRenderState reusableCameraRenderState = new CameraRenderState();
    private final Vector3f[] overlayCorners = {
            new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()
    };
    private MetalRenderCommandQueue reusableCmdQueue;

    public MetalEntityRenderer() {
        vertexStagingBuffer = ByteBuffer.allocateDirect(MAX_BATCH_VERTICES * ENTITY_VERTEX_STRIDE)
                .order(ByteOrder.nativeOrder());
        metalVertexConsumer = new MetalVertexConsumer(vertexStagingBuffer, MAX_BATCH_VERTICES);
        java.util.Arrays.fill(textureCache, TEXTURE_UNCACHED);
        MetalLogger.info("[BUILD_V9] MetalEntityRenderer constructed for 26.1 official names");
    }

    public void setup(long device, long pipeline) {
        this.device = device;
        if (device != 0) {
            for (int i = 0; i < 3; i++) {
                vbufs[i] = NativeBridge.nCreateBuffer(
                        device, MAX_BATCH_VERTICES * ENTITY_VERTEX_STRIDE, 0);
            }
            MetalLogger.info("MetalEntityRenderer initialized: device=%d vb0=%d vb1=%d vb2=%d",
                    device, vbufs[0], vbufs[1], vbufs[2]);
        }
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isActive() {
        return active;
    }

    public boolean hasVisibleSubmergedEntities() {
        for (int index = 0; index < count; index++) {
            if (capturedEntityPool.get(index).isSubmerged) {
                return true;
            }
        }
        return false;
    }

    public void captureEntity(Entity entity, float delta, Matrix4f model) {
        if (!active || entity == null || count >= MAX_ENTITIES_PER_FRAME) {
            return;
        }
        entitiesCapturedPerSec++;
        captureCallsPerSec++;

        CapturedEntity captured;
        if (count < capturedEntityPool.size()) {
            captured = capturedEntityPool.get(count);
        } else {
            captured = new CapturedEntity();
            capturedEntityPool.add(captured);
        }

        captured.entity = entity;
        captured.tickDelta = delta;
        captured.modelMatrix.set(model);
        captured.glTextureId = 0;
        captured.light = 0x00F000F0;
        captured.overlayStartVertex = -1;
        captured.overlayVertexCount = 0;
        captured.overlayTextureId = 0;
        captured.isHurt = entity instanceof LivingEntity living && living.hurtTime > 0;
        captured.hurtFactor = captured.isHurt
                ? ((LivingEntity) entity).hurtTime / 10.0f
                : 0.0f;

        boolean submergedNow = entity.isUnderWater() || entity.isInWater();
        int entityId = entity.getId();
        if (submergedNow) {
            submergedHoldFrames.put(entityId, SUBMERGED_HOLD_FRAMES);
            captured.isSubmerged = true;
        } else {
            Integer holdFrames = submergedHoldFrames.get(entityId);
            if (holdFrames != null && holdFrames > 0) {
                captured.isSubmerged = true;
                if (holdFrames == 1) {
                    submergedHoldFrames.remove(entityId);
                } else {
                    submergedHoldFrames.put(entityId, holdFrames - 1);
                }
            } else {
                submergedHoldFrames.remove(entityId);
                captured.isSubmerged = false;
            }
        }

        count++;
    }

    public void buildMeshes(long ctx) {
        if (!active || ctx == 0 || device == 0) {
            return;
        }
        metalVertexConsumer.reset();
        vtxCount = 0;
        pendingDrawCount = 0;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            return;
        }

        EntityRenderDispatcher renderDispatcher = mc.getEntityRenderDispatcher();
        if (renderDispatcher == null) {
            return;
        }

        Camera camera = mc.gameRenderer.getMainCamera();
        double camX;
        double camY;
        double camZ;
        if (camera != null && camera.isInitialized()) {
            Vec3 cameraPos = camera.position();
            camX = cameraPos.x;
            camY = cameraPos.y;
            camZ = cameraPos.z;
        } else if (CapturedMatrices.isValid()) {
            camX = CapturedMatrices.getCamX();
            camY = CapturedMatrices.getCamY();
            camZ = CapturedMatrices.getCamZ();
        } else {
            camX = 0.0;
            camY = 0.0;
            camZ = 0.0;
        }

        float delta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        int drawn = 0;
        int modelCaptures = 0;
        int boxFallbacks = 0;

        for (int entityIndex = 0; entityIndex < count; entityIndex++) {
            CapturedEntity captured = capturedEntityPool.get(entityIndex);
            Entity entity = captured.entity;
            if (entity == null || entity.isRemoved()) {
                continue;
            }

            int startVertex = metalVertexConsumer.getVertexCount();
            boolean usedModel = false;
            try {
                usedModel = renderEntityModel(entity, captured, renderDispatcher, camX, camY, camZ,
                        delta);
            } catch (Exception e) {
                if (frameCount < 5) {
                    MetalLogger.warn("Failed to render entity model for %s: %s",
                            entity.getType().toString(), e.getMessage());
                }
            }

            if (!usedModel || metalVertexConsumer.getVertexCount() == startVertex) {
                if (entity instanceof ItemEntity itemEntity) {
                    boolean rendered = renderItemEntity(itemEntity, captured, camX, camY, camZ,
                            delta);
                    if (rendered) {
                        if (metalVertexConsumer.getVertexCount() > startVertex) {
                            enqueueEntityDraws(startVertex, captured,
                                    captured.isHurt ? captured.hurtFactor : 0.0f);
                            drawn++;
                            modelCaptures++;
                        }
                    }
                    continue;
                }

                if (isFallingBlockEntity(entity)
                        && renderFallingBlockFallback(entity, captured, camX, camY, camZ)) {
                    if (metalVertexConsumer.getVertexCount() > startVertex) {
                        enqueueEntityDraws(startVertex, captured, 0.0f);
                        drawn++;
                        modelCaptures++;
                    }
                    continue;
                }

                buildEntityQuads(entity, captured, camX, camY, camZ);
                int verticesAdded = metalVertexConsumer.getVertexCount() - startVertex;
                if (verticesAdded > 0) {
                    enqueueEntityDraws(startVertex, captured, captured.hurtFactor);
                    drawn++;
                    boxFallbacks++;
                }
            } else {
                int verticesAdded = metalVertexConsumer.getVertexCount() - startVertex;
                if (verticesAdded > 0) {
                    enqueueEntityDraws(startVertex, captured, captured.hurtFactor);
                    drawn++;
                    modelCaptures++;
                }
            }
        }

        vtxCount = metalVertexConsumer.getVertexCount();
        if (frameCount < 3 && drawn > 0) {
            MetalLogger.info(
                    "buildMeshes: %d entities (%d model, %d box) -> %d verts",
                    drawn, modelCaptures, boxFallbacks, vtxCount);
        }
    }

    private EntityDrawCommand getOrCreateDrawCommand() {
        if (pendingDrawCount < pendingDrawPool.size()) {
            return pendingDrawPool.get(pendingDrawCount);
        }
        EntityDrawCommand drawCommand = new EntityDrawCommand();
        pendingDrawPool.add(drawCommand);
        return drawCommand;
    }

    private void enqueueEntityDraws(int startVertex, CapturedEntity captured, float hurtFactor) {
        int endVertex = metalVertexConsumer.getVertexCount();
        int totalVertexCount = endVertex - startVertex;
        if (totalVertexCount <= 0) {
            return;
        }

        int mainVertexCount = totalVertexCount;
        if (captured.overlayVertexCount > 0 && captured.overlayStartVertex >= startVertex) {
            mainVertexCount = captured.overlayStartVertex - startVertex;
        }

        if (mainVertexCount > 0) {
            enqueueDraw(startVertex, mainVertexCount, hurtFactor, captured.glTextureId);
        }
        if (captured.overlayVertexCount > 0 && captured.overlayTextureId != 0) {
            enqueueDraw(captured.overlayStartVertex, captured.overlayVertexCount, 0.0f,
                    captured.overlayTextureId);
        }
    }

    private void enqueueDraw(int startVertex, int vertexCount, float hurtFactor, int glTextureId) {
        EntityDrawCommand drawCommand = getOrCreateDrawCommand();
        drawCommand.startVertex = startVertex;
        drawCommand.vertexCount = vertexCount;
        drawCommand.hurtFactor = hurtFactor;
        drawCommand.whiteFlash = 0.0f;
        drawCommand.renderFlags = 0;
        drawCommand.glTextureId = glTextureId;
        pendingDrawCount++;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private boolean renderEntityModel(Entity entity, CapturedEntity captured,
            EntityRenderDispatcher renderDispatcher,
            double camX, double camY, double camZ,
            float tickDelta) {
        EntityRenderer renderer = renderDispatcher.getRenderer(entity);
        EntityRenderState state;
        try {
            state = (EntityRenderState) renderer.createRenderState(entity, tickDelta);
        } catch (Exception e) {
            return false;
        }
        if (state == null) {
            return false;
        }

        matrixStack.setIdentity();
        Vec3 lerpedPos = entity.getPosition(tickDelta);
        double ex = lerpedPos.x - camX;
        double ey = lerpedPos.y - camY;
        double ez = lerpedPos.z - camZ;
        Vec3 offset = renderer.getRenderOffset(state);
        ex += offset.x;
        ey += offset.y;
        ez += offset.z;
        matrixStack.translate(ex, ey, ez);

        if (frameCount % 3000 == 1 && state instanceof LivingEntityRenderState livingState) {
            MetalLogger.info("[ENTITY_DIAG] entity=%s bodyRot=%.1f scale=%.2f livingState=true",
                    entity.getType().toString(), livingState.bodyRot, livingState.scale);
        }

        int light = state.lightCoords != 0 ? state.lightCoords : 0x00F000F0;
        captured.light = light;
        if (reusableCmdQueue == null) {
            reusableCmdQueue = new MetalRenderCommandQueue(metalVertexConsumer, light);
        } else {
            reusableCmdQueue.reset(metalVertexConsumer, light);
        }

        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc != null ? mc.gameRenderer.getMainCamera() : null;
        if (camera != null && camera.isInitialized()) {
            camera.extractRenderState(reusableCameraRenderState, tickDelta);
        } else {
            reusableCameraRenderState.pos = new Vec3(0.0, 0.0, 0.0);
            reusableCameraRenderState.blockPos = BlockPos.ZERO;
            reusableCameraRenderState.xRot = 0.0f;
            reusableCameraRenderState.yRot = 0.0f;
            reusableCameraRenderState.initialized = false;
        }

        try {
            renderer.submit(state, matrixStack, reusableCmdQueue, reusableCameraRenderState);
            if (hasFireRenderState(state) && reusableCameraRenderState.orientation != null) {
                appendFireOverlay(captured, state, ex, ey, ez, reusableCameraRenderState.orientation);
            }
        } catch (Exception e) {
            return false;
        }

        try {
            if (renderer instanceof LivingEntityRenderer livingRenderer
                    && state instanceof LivingEntityRenderState livingState) {
                LivingEntityRenderer<?, LivingEntityRenderState, ?> typedRenderer = (LivingEntityRenderer<?, LivingEntityRenderState, ?>) livingRenderer;
                Identifier textureId = typedRenderer.getTextureLocation(livingState);
                if (textureId != null) {
                    AbstractTexture mcTexture = Minecraft.getInstance().getTextureManager().getTexture(textureId);
                    if (mcTexture != null && mcTexture.getTexture() instanceof GlTexture glTexture) {
                        captured.glTextureId = glTexture.glId();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    private void appendFireOverlay(CapturedEntity captured, EntityRenderState state,
            double ex, double ey, double ez, Quaternionf cameraOrientation) {
        TextureAtlasSprite fire0 = getBlockAtlasSprite(FIRE_SPRITE_0);
        TextureAtlasSprite fire1 = getBlockAtlasSprite(FIRE_SPRITE_1);
        int blockAtlasTextureId = getBlockAtlasTextureId();
        if (blockAtlasTextureId == 0 || (fire0 == null && fire1 == null)) {
            return;
        }

        int startVertex = metalVertexConsumer.getVertexCount();
        float scale = Math.max(getFloatField(state, "width", 0.6f) * 1.4f, 0.6f);
        float sliceHalfWidth = 0.5f;
        float normalizedHeight = getFloatField(state, "height", 1.8f) / scale;
        float verticalOffset = 0.0f;
        float depthOffset = 0.0f;
        float baseDepth = 0.3f - (float) ((int) normalizedHeight) * 0.02f;
        int sliceIndex = 0;

        while (normalizedHeight > 0.0f) {
            TextureAtlasSprite sprite = (sliceIndex & 1) == 0 ? fire0 : fire1;
            if (sprite == null) {
                sprite = fire0 != null ? fire0 : fire1;
            }
            float minU = sprite.getU0();
            float minV = sprite.getV0();
            float maxU = sprite.getU1();
            float maxV = sprite.getV1();
            if (((sliceIndex / 2) & 1) == 0) {
                float swap = maxU;
                maxU = minU;
                minU = swap;
            }

            emitFireSlice(ex, ey, ez, cameraOrientation, scale,
                    sliceHalfWidth, verticalOffset, baseDepth + depthOffset,
                    minU, minV, maxU, maxV);

            normalizedHeight -= 0.45f;
            verticalOffset -= 0.45f;
            sliceHalfWidth *= 0.9f;
            depthOffset -= 0.03f;
            sliceIndex++;
        }

        int overlayVertexCount = metalVertexConsumer.getVertexCount() - startVertex;
        if (overlayVertexCount > 0) {
            captured.overlayStartVertex = startVertex;
            captured.overlayVertexCount = overlayVertexCount;
            captured.overlayTextureId = blockAtlasTextureId;
        }
    }

    private void emitFireSlice(double ex, double ey, double ez,
            Quaternionf rotation, float scale, float halfWidth,
            float verticalOffset, float depthOffset,
            float minU, float minV, float maxU, float maxV) {
        setOverlayCorner(rotation, ex, ey, ez, -halfWidth * scale, (-verticalOffset) * scale, depthOffset * scale, 0);
        setOverlayCorner(rotation, ex, ey, ez, halfWidth * scale, (-verticalOffset) * scale, depthOffset * scale, 1);
        setOverlayCorner(rotation, ex, ey, ez, halfWidth * scale, (1.4f - verticalOffset) * scale, depthOffset * scale,
                2);
        setOverlayCorner(rotation, ex, ey, ez, -halfWidth * scale, (1.4f - verticalOffset) * scale,
                depthOffset * scale, 3);

        int fullBright = 0x00F000F0;
        int color = 0xFFFFFFFF;
        metalVertexConsumer.vertex(overlayCorners[0].x, overlayCorners[0].y, overlayCorners[0].z,
                color, maxU, maxV, 0, fullBright, 0.0f, 1.0f, 0.0f);
        metalVertexConsumer.vertex(overlayCorners[1].x, overlayCorners[1].y, overlayCorners[1].z,
                color, minU, maxV, 0, fullBright, 0.0f, 1.0f, 0.0f);
        metalVertexConsumer.vertex(overlayCorners[2].x, overlayCorners[2].y, overlayCorners[2].z,
                color, minU, minV, 0, fullBright, 0.0f, 1.0f, 0.0f);
        metalVertexConsumer.vertex(overlayCorners[3].x, overlayCorners[3].y, overlayCorners[3].z,
                color, maxU, minV, 0, fullBright, 0.0f, 1.0f, 0.0f);
    }

    private void setOverlayCorner(Quaternionf rotation, double ex, double ey, double ez,
            float localX, float localY, float localZ, int index) {
        Vector3f corner = overlayCorners[index];
        corner.set(localX, localY, localZ);
        rotation.transform(corner);
        corner.add((float) ex, (float) ey, (float) ez);
    }

    private TextureAtlasSprite getBlockAtlasSprite(Identifier spriteId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getTextureManager() == null) {
            return null;
        }
        AbstractTexture atlasTexture = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        if (atlasTexture instanceof TextureAtlas atlas) {
            return atlas.getSprite(spriteId);
        }
        return null;
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

    private boolean renderFallingBlockFallback(Entity entity, CapturedEntity captured,
            double camX, double camY, double camZ) {
        TextureAtlasSprite sprite = resolveFallingBlockSprite(entity);
        int blockAtlasTextureId = getBlockAtlasTextureId();
        if (sprite == null || blockAtlasTextureId == 0) {
            return false;
        }

        Vec3 position = entity.getPosition(captured.tickDelta);
        float ex = (float) (position.x - camX);
        float ey = (float) (position.y - camY);
        float ez = (float) (position.z - camZ);
        float halfWidth = Math.max(0.45f, entity.getBbWidth() * 0.5f);
        float height = Math.max(0.9f, entity.getBbHeight());
        float x0 = ex - halfWidth;
        float y0 = ey;
        float z0 = ez - halfWidth;
        float x1 = ex + halfWidth;
        float y1 = ey + height;
        float z1 = ez + halfWidth;
        int light = captured.light != 0 ? captured.light : 0x00F000F0;
        int color = 0xFFFFFFFF;
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();

        emitTexturedQuad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1,
                0.0f, 0.0f, 1.0f, u0, u1, v0, v1, color, light);
        emitTexturedQuad(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0,
                0.0f, 0.0f, -1.0f, u0, u1, v0, v1, color, light);
        emitTexturedQuad(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0,
                0.0f, 1.0f, 0.0f, u0, u1, v0, v1, color, light);
        emitTexturedQuad(x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1,
                0.0f, -1.0f, 0.0f, u0, u1, v0, v1, color, light);
        emitTexturedQuad(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1,
                1.0f, 0.0f, 0.0f, u0, u1, v0, v1, color, light);
        emitTexturedQuad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0,
                -1.0f, 0.0f, 0.0f, u0, u1, v0, v1, color, light);
        captured.glTextureId = blockAtlasTextureId;
        return true;
    }

    private TextureAtlasSprite resolveFallingBlockSprite(Entity entity) {
        Object blockState = invokeNamedMethod(entity, new String[] { "getBlockState" });
        if (blockState == null) {
            return null;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return null;
        }

        Object blockRenderer = invokeNamedMethod(mc,
                new String[] { "getBlockRenderer", "getBlockRenderDispatcher", "getBlockRenderManager" });
        if (blockRenderer != null) {
            Object model = invokeNamedMethod(blockRenderer,
                    new String[] { "getBlockModel", "getModel" }, blockState);
            if (model != null) {
                Object particleMaterial = invokeNamedMethod(model,
                        new String[] { "particleMaterial", "getParticleIcon", "getParticleTexture" });
                if (particleMaterial instanceof TextureAtlasSprite sprite) {
                    return sprite;
                }
                Object sprite = invokeNamedMethod(particleMaterial, new String[] { "sprite", "getSprite" });
                if (sprite instanceof TextureAtlasSprite textureAtlasSprite) {
                    return textureAtlasSprite;
                }
            }
        }

        Object block = invokeNamedMethod(blockState, new String[] { "getBlock" });
        Object blockId = invokeNamedMethod(BuiltInRegistries.BLOCK, new String[] { "getKey" }, block);
        if (blockId instanceof Identifier identifier) {
            return getBlockAtlasSprite(Identifier.fromNamespaceAndPath(
                    identifier.getNamespace(), "block/" + identifier.getPath()));
        }
        return null;
    }

    private Object invokeNamedMethod(Object target, String[] methodNames, Object... args) {
        if (target == null) {
            return null;
        }
        Class<?> targetClass = target.getClass();
        for (String methodName : methodNames) {
            Method method = findCompatibleMethod(targetClass, methodName, args);
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

    private Method findCompatibleMethod(Class<?> targetClass, String methodName, Object[] args) {
        for (Class<?> current = targetClass; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(methodName) || method.getParameterCount() != args.length) {
                    continue;
                }
                Class<?>[] parameterTypes = method.getParameterTypes();
                boolean compatible = true;
                for (int index = 0; index < parameterTypes.length; index++) {
                    Object arg = args[index];
                    if (arg == null) {
                        if (parameterTypes[index].isPrimitive()) {
                            compatible = false;
                            break;
                        }
                        continue;
                    }
                    if (!parameterTypes[index].isInstance(arg)) {
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

    private boolean isFallingBlockEntity(Entity entity) {
        return entity != null && entity.getClass().getSimpleName().equals("FallingBlockEntity");
    }

    private static boolean hasFireRenderState(EntityRenderState state) {
        return getBooleanField(state, "onFire") || getBooleanField(state, "displayFireAnimation");
    }

    private static float getFloatField(EntityRenderState state, String fieldName, float fallback) {
        try {
            Field field = state.getClass().getField(fieldName);
            return field.getFloat(state);
        } catch (ReflectiveOperationException ignored) {
            return fallback;
        }
    }

    private static boolean getBooleanField(EntityRenderState state, String fieldName) {
        try {
            Field field = state.getClass().getField(fieldName);
            return field.getBoolean(state);
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private boolean renderItemEntity(ItemEntity entity, CapturedEntity captured,
            double camX, double camY, double camZ, float tickDelta) {
        try {
            ItemStack stack = entity.getItem();
            if (stack == null || stack.isEmpty()) {
                return false;
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                return false;
            }

            Identifier itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            TextureAtlasSprite sprite = null;
            AbstractTexture itemsTexture = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_ITEMS);
            if (itemsTexture instanceof TextureAtlas itemsAtlas) {
                sprite = itemsAtlas.getSprite(Identifier.fromNamespaceAndPath(
                        itemId.getNamespace(), "item/" + itemId.getPath()));
            }

            if (itemsTexture != null && itemsTexture.getTexture() instanceof GlTexture glTexture) {
                captured.glTextureId = glTexture.glId();
            }

            float ex = (float) (Mth.lerp(tickDelta, entity.xOld, entity.getX()) - camX);
            float ey = (float) (Mth.lerp(tickDelta, entity.yOld, entity.getY()) - camY);
            float ez = (float) (Mth.lerp(tickDelta, entity.zOld, entity.getZ()) - camZ);

            float age = entity.getAge() + tickDelta;
            float spinAngle = age / 20.0f * 57.2957795f;
            float bobY = (float) (Math.sin(age / 10.0f) * 0.1f + 0.1f);
            ey += bobY;
            float sinSpin = (float) Math.sin(Math.toRadians(spinAngle));
            float cosSpin = (float) Math.cos(Math.toRadians(spinAngle));
            float halfWidth = 0.125f;
            int light = 0x00F000F0;
            int color = 0xFFFFFFFF;

            float u0 = sprite != null ? sprite.getU0() : 0.0f;
            float u1 = sprite != null ? sprite.getU1() : 0.0625f;
            float v0 = sprite != null ? sprite.getV0() : 0.0f;
            float v1 = sprite != null ? sprite.getV1() : 0.0625f;
            float nx = sinSpin;
            float nz = cosSpin;

            float x0 = ex + cosSpin * (-halfWidth);
            float z0 = ez + sinSpin * (-halfWidth);
            float x1 = ex + cosSpin * halfWidth;
            float z1 = ez + sinSpin * halfWidth;
            float y0 = ey;
            float y1 = ey + halfWidth * 2.0f;

            metalVertexConsumer.vertex(x0, y0, z0, color, u0, v1, 0, light, nx, 0.0f, nz);
            metalVertexConsumer.vertex(x1, y0, z1, color, u1, v1, 0, light, nx, 0.0f, nz);
            metalVertexConsumer.vertex(x1, y1, z1, color, u1, v0, 0, light, nx, 0.0f, nz);
            metalVertexConsumer.vertex(x0, y1, z0, color, u0, v0, 0, light, nx, 0.0f, nz);

            metalVertexConsumer.vertex(x1, y0, z1, color, u1, v1, 0, light, -nx, 0.0f, -nz);
            metalVertexConsumer.vertex(x0, y0, z0, color, u0, v1, 0, light, -nx, 0.0f, -nz);
            metalVertexConsumer.vertex(x0, y1, z0, color, u0, v0, 0, light, -nx, 0.0f, -nz);
            metalVertexConsumer.vertex(x1, y1, z1, color, u1, v0, 0, light, -nx, 0.0f, -nz);
            return true;
        } catch (Exception e) {
            if (frameCount < 5) {
                MetalLogger.warn("renderItemEntity failed: %s", e.getMessage());
            }
            return false;
        }
    }

    private void buildEntityQuads(Entity entity, CapturedEntity captured,
            double camX, double camY, double camZ) {
        float tickDelta = captured.tickDelta;
        Vec3 position = entity.getPosition(tickDelta);
        float ex = (float) (position.x - camX);
        float ey = (float) (position.y - camY);
        float ez = (float) (position.z - camZ);
        float halfWidth = entity.getBbWidth() * 0.5f;
        float height = entity.getBbHeight();
        float x0 = ex - halfWidth;
        float y0 = ey;
        float z0 = ez - halfWidth;
        float x1 = ex + halfWidth;
        float y1 = ey + height;
        float z1 = ez + halfWidth;
        int color = 0xFFFFFFFF;
        if (captured.isHurt) {
            int gb = (int) (255 * (1.0f - captured.hurtFactor * 0.6f));
            color = (255 << 24) | (255 << 16) | (gb << 8) | gb;
        }
        int light = 0x00F000F0;

        emitQuad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1, color, light);
        emitQuad(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0, 0, -1, color, light);
        emitQuad(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0, 1, 0, color, light);
        emitQuad(x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1, 0, -1, 0, color, light);
        emitQuad(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1, 0, 0, color, light);
        emitQuad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1, 0, 0, color, light);
    }

    private void emitQuad(float x0, float y0, float z0, float x1, float y1,
            float z1, float x2, float y2, float z2, float x3,
            float y3, float z3, float nx, float ny, float nz,
            int color, int light) {
        metalVertexConsumer.vertex(x0, y0, z0, color, 0, 0, 0, light, nx, ny, nz);
        metalVertexConsumer.vertex(x1, y1, z1, color, 1, 0, 0, light, nx, ny, nz);
        metalVertexConsumer.vertex(x2, y2, z2, color, 1, 1, 0, light, nx, ny, nz);
        metalVertexConsumer.vertex(x3, y3, z3, color, 0, 1, 0, light, nx, ny, nz);
    }

    private void emitTexturedQuad(float x0, float y0, float z0, float x1, float y1,
            float z1, float x2, float y2, float z2, float x3, float y3, float z3,
            float nx, float ny, float nz,
            float u0, float u1, float v0, float v1,
            int color, int light) {
        metalVertexConsumer.vertex(x0, y0, z0, color, u0, v1, 0, light, nx, ny, nz);
        metalVertexConsumer.vertex(x1, y1, z1, color, u1, v1, 0, light, nx, ny, nz);
        metalVertexConsumer.vertex(x2, y2, z2, color, u1, v0, 0, light, nx, ny, nz);
        metalVertexConsumer.vertex(x3, y3, z3, color, u0, v0, 0, light, nx, ny, nz);
    }

    public void renderCapturedEntities(long ctx, boolean inWater) {
        if (!active || ctx == 0 || device == 0) {
            return;
        }
        renderCallsPerSec++;
        long now = System.currentTimeMillis();
        if (MetalRenderConfig.isDeepDebugActive() && now - lastEntityLogTime >= 10000) {
            MetalLogger.info(
                    "Entity stats: captures=%d/10s renders=%d/10s entities=%d/10s",
                    captureCallsPerSec, renderCallsPerSec, entitiesCapturedPerSec);
            captureCallsPerSec = 0;
            renderCallsPerSec = 0;
            entitiesCapturedPerSec = 0;
            lastEntityLogTime = now;
        } else if (now - lastEntityLogTime >= 10000) {
            captureCallsPerSec = 0;
            renderCallsPerSec = 0;
            entitiesCapturedPerSec = 0;
            lastEntityLogTime = now;
        }

        buildMeshes(ctx);
        if (vtxCount == 0 || pendingDrawCount == 0) {
            return;
        }

        vertexStagingBuffer.flip();
        int uploadSize = vtxCount * ENTITY_VERTEX_STRIDE;
        long activeVertexBuffer = vbufs[frameCount % 3];
        if (activeVertexBuffer != 0 && uploadSize > 0) {
            NativeBridge.nUploadBufferDataDirect(activeVertexBuffer, vertexStagingBuffer, 0, uploadSize);
        }

        MetalRenderer renderer = MetalRenderClient.getRenderer();
        if (renderer == null) {
            if (frameCount < 5) {
                MetalLogger.warn("MetalEntityRenderer: renderer null");
            }
            return;
        }

        long entityPipeline = cachedEntityPipeline;
        if (entityPipeline == 0) {
            entityPipeline = NativeBridge.nGetEntityPipelineHandle(renderer.getHandle());
            if (entityPipeline != 0) {
                cachedEntityPipeline = entityPipeline;
            }
        }

        if (entityPipeline != 0) {
            NativeBridge.nSetPipelineState(ctx, entityPipeline);
        } else {
            long inhousePipeline = renderer.getBackend().getInhousePipelineHandle();
            if (inhousePipeline == 0) {
                if (frameCount < 5) {
                    MetalLogger.warn("MetalEntityRenderer: no pipeline available");
                }
            } else {
                NativeBridge.nSetPipelineState(ctx, inhousePipeline);
            }
        }

        NativeBridge.nSetChunkOffset(ctx, 0.0f, 0.0f, 0.0f);
        int drawsDone = 0;
        float lastHurt = -1.0f;
        float lastFlash = -1.0f;
        float lastWaterFog = -1.0f;
        long lastBoundTexture = -1;
        for (int drawIndex = 0; drawIndex < pendingDrawCount; drawIndex++) {
            EntityDrawCommand drawCommand = pendingDrawPool.get(drawIndex);
            if (drawCommand.vertexCount <= 0) {
                continue;
            }
            int renderFlags = drawCommand.renderFlags;
            if (drawCommand.hurtFactor != lastHurt || drawCommand.whiteFlash != lastFlash) {
                NativeBridge.nSetEntityOverlay(ctx, drawCommand.hurtFactor,
                        drawCommand.whiteFlash, 1.0f);
                lastHurt = drawCommand.hurtFactor;
                lastFlash = drawCommand.whiteFlash;
            }
            if (inWater) {
                renderFlags |= 0x1;
            }
            float waterFog = inWater ? 1.0f : 0.0f;
            if (waterFog != lastWaterFog) {
                NativeBridge.nSetWaterFog(ctx, waterFog);
                lastWaterFog = waterFog;
            }
            if (drawCommand.glTextureId != 0) {
                long metalTexture = getOrCreateMetalTexture(drawCommand.glTextureId);
                if (metalTexture != 0 && metalTexture != lastBoundTexture) {
                    NativeBridge.nBindEntityTexture(ctx, metalTexture);
                    lastBoundTexture = metalTexture;
                }
            }
            NativeBridge.nDrawEntityBuffer(ctx, activeVertexBuffer,
                    drawCommand.vertexCount, drawCommand.startVertex, renderFlags);
            drawsDone++;
        }

        frameCount++;
        if (MetalRenderConfig.isDeepDebugActive() &&
                (frameCount <= 5 || frameCount % 3000 == 0)) {
            MetalLogger.info(
                    "MetalEntityRenderer: frame %d, %d entities, %d verts, %d draws",
                    frameCount, pendingDrawCount, vtxCount, drawsDone);
        }

        pendingDrawCount = 0;
        for (int entityIndex = 0; entityIndex < count; entityIndex++) {
            capturedEntityPool.get(entityIndex).entity = null;
        }
        count = 0;
    }

    private long getOrCreateMetalTexture(int glTextureId) {
        if (glTextureId == 0 || device == 0) {
            return 0;
        }
        if (glTextureId >= 0 && glTextureId < TEXTURE_CACHE_SIZE) {
            long cached = textureCache[glTextureId];
            if (cached != TEXTURE_UNCACHED) {
                return cached;
            }
        }
        try {
            int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTextureId);
            int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
                if (glTextureId >= 0 && glTextureId < TEXTURE_CACHE_SIZE) {
                    textureCache[glTextureId] = 0L;
                }
                return 0;
            }

            ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE, pixels);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            byte[] pixelData = new byte[width * height * 4];
            pixels.get(pixelData);

            long metalTexture = NativeBridge.nCreateTexture2D(device, width, height, pixelData);
            if (glTextureId >= 0 && glTextureId < TEXTURE_CACHE_SIZE) {
                textureCache[glTextureId] = metalTexture;
            }
            return metalTexture;
        } catch (Exception e) {
            MetalLogger.error("Failed to create Metal entity texture for glId=%d: %s",
                    glTextureId, e.getMessage());
            if (glTextureId >= 0 && glTextureId < TEXTURE_CACHE_SIZE) {
                textureCache[glTextureId] = 0L;
            }
            return 0;
        }
    }

    public void invalidateTextureCache() {
        for (int textureIndex = 0; textureIndex < TEXTURE_CACHE_SIZE; textureIndex++) {
            long handle = textureCache[textureIndex];
            if (handle > 0) {
                NativeBridge.nDestroyTexture2D(handle);
            }
        }
        java.util.Arrays.fill(textureCache, TEXTURE_UNCACHED);
        MetalLogger.info("Entity texture cache invalidated");
    }

    public int getLastEntityCount() {
        return pendingDrawCount;
    }

    public int getLastVertexCount() {
        return vtxCount;
    }

    public void clearCapturedEntities() {
        for (int entityIndex = 0; entityIndex < count; entityIndex++) {
            capturedEntityPool.get(entityIndex).entity = null;
        }
        count = 0;
    }

    public void shutdown() {
        active = false;
        cachedEntityPipeline = 0;
        count = 0;
        pendingDrawCount = 0;
        for (int textureIndex = 0; textureIndex < TEXTURE_CACHE_SIZE; textureIndex++) {
            long handle = textureCache[textureIndex];
            if (handle > 0) {
                NativeBridge.nDestroyTexture2D(handle);
            }
        }
        java.util.Arrays.fill(textureCache, TEXTURE_UNCACHED);
        for (int i = 0; i < 3; i++) {
            if (vbufs[i] != 0) {
                NativeBridge.nDestroyBuffer(vbufs[i]);
                vbufs[i] = 0;
            }
        }
        device = 0;
        MetalLogger.info("MetalEntityRenderer shut down");
    }

    private static class CapturedEntity {
        Entity entity;
        float tickDelta;
        final Matrix4f modelMatrix = new Matrix4f();
        boolean isHurt;
        float hurtFactor;
        int glTextureId;
        int light;
        int overlayStartVertex;
        int overlayVertexCount;
        int overlayTextureId;
        boolean isSubmerged;
    }

    private static class EntityDrawCommand {
        int startVertex;
        int vertexCount;
        float hurtFactor;
        float whiteFlash;
        int renderFlags;
        int glTextureId;
    }
}
