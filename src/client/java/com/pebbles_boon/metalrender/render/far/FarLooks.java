package com.pebbles_boon.metalrender.render.far;

import com.pebbles_boon.metalrender.render.chunk.QuadTiers;
import com.pebbles_boon.metalrender.sodium.mixins.accessor.WeightedVariantsAccessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.Weighted;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class FarLooks {
    public static final int WORDS = 26;
    public static final int NO_SPRITE = 0x1FFF;
    public static final int CLS_OPAQUE = 0;
    public static final int CLS_CUTOUT = 1;
    public static final int CLS_TRANSLUCENT = 2;
    public static final int SHAPE_FULL = 0;
    public static final int SHAPE_SLAB_BOTTOM = 1;
    public static final int SHAPE_SLAB_TOP = 2;
    public static final int SHAPE_CROSS = 3;
    public static final int SHAPE_POST_THIN = 4;
    public static final int SHAPE_POST_WIDE = 5;
    public static final int SHAPE_LAYER_BASE = 7;
    public static final int KIND_SKIP = 0;
    public static final int KIND_SOLID = 1;
    public static final int KIND_WATER = 2;

    private static final Direction[] DIRS = { Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST };
    private static volatile FarLooks instance;

    private final Object lock = new Object();
    private final ConcurrentHashMap<BlockState, Integer> ids = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<BlockState, Integer> kinds = new ConcurrentHashMap<>();
    private int[] records = new int[WORDS * 1024];
    private int[] tintIndex = new int[1024];
    private BlockState[] states = new BlockState[1024];
    private int count = 1;
    private volatile boolean dirty = true;
    private final QuadTiers.SpriteTable sprites;
    private TextureAtlasSprite waterSprite;
    private TextureAtlasSprite lavaSprite;

    private FarLooks(QuadTiers.SpriteTable sprites) {
        this.sprites = sprites;
        for (int i = 0; i < WORDS; i++) {
            records[i] = 0;
        }
        for (int f = 0; f < 24; f++) {
            records[2 + f] = NO_SPRITE | (NO_SPRITE << 16);
        }
        TextureAtlas atlas = atlas();
        if (atlas != null) {
            waterSprite = atlas.getSprite(Identifier.fromNamespaceAndPath("minecraft", "block/water_still"));
            lavaSprite = atlas.getSprite(Identifier.fromNamespaceAndPath("minecraft", "block/lava_still"));
        }
    }

    public static FarLooks get() {
        QuadTiers.SpriteTable table = QuadTiers.spriteTable();
        if (table == null) {
            return null;
        }
        FarLooks cur = instance;
        if (cur == null || cur.sprites != table) {
            synchronized (FarLooks.class) {
                cur = instance;
                if (cur == null || cur.sprites != table) {
                    cur = new FarLooks(table);
                    instance = cur;
                }
            }
        }
        return cur;
    }

    public static int cls(int header) {
        return header & 3;
    }

    public static int shape(int header) {
        return (header >>> 2) & 15;
    }

    public static boolean occluder(int header) {
        return ((header >>> 9) & 1) != 0;
    }

    public int header(int look) {
        synchronized (lock) {
            return look >= 0 && look < count ? records[look * WORDS] : 0;
        }
    }

    public int tintIndexOf(int look) {
        synchronized (lock) {
            return look > 0 && look < count ? tintIndex[look] : -1;
        }
    }

    public BlockState stateOf(int look) {
        synchronized (lock) {
            return look > 0 && look < count ? states[look] : null;
        }
    }

    public int kindOf(BlockState state) {
        Integer k = kinds.get(state);
        if (k != null) {
            return k;
        }
        int v = classifyKind(state);
        kinds.put(state, v);
        return v;
    }

    private static int classifyKind(BlockState state) {
        if (state == null || state.isAir()) {
            return KIND_SKIP;
        }
        Block b = state.getBlock();
        if (b == Blocks.WATER || b == Blocks.BUBBLE_COLUMN || b == Blocks.KELP || b == Blocks.KELP_PLANT
                || b == Blocks.SEAGRASS || b == Blocks.TALL_SEAGRASS) {
            return KIND_WATER;
        }
        if (b instanceof LiquidBlock) {
            return KIND_SOLID;
        }
        if (state.getRenderShape() == RenderShape.INVISIBLE) {
            return state.getFluidState().is(Fluids.WATER) ? KIND_WATER : KIND_SKIP;
        }
        return KIND_SOLID;
    }

    public int lookOf(BlockState state) {
        Integer id = ids.get(state);
        if (id != null) {
            return id;
        }
        synchronized (lock) {
            id = ids.get(state);
            if (id != null) {
                return id;
            }
            int look = count;
            if (look >= 65535) {
                return 0;
            }
            int[] rec = new int[WORDS];
            int ti = build(state, rec);
            ensure(look + 1);
            System.arraycopy(rec, 0, records, look * WORDS, WORDS);
            tintIndex[look] = ti;
            states[look] = state;
            count = look + 1;
            ids.put(state, look);
            dirty = true;
            return look;
        }
    }

    public int waterLook() {
        return lookOf(Blocks.WATER.defaultBlockState());
    }

    private void ensure(int n) {
        if (n * WORDS <= records.length) {
            return;
        }
        int cap = records.length / WORDS;
        while (cap < n) {
            cap *= 2;
        }
        records = java.util.Arrays.copyOf(records, cap * WORDS);
        tintIndex = java.util.Arrays.copyOf(tintIndex, cap);
        states = java.util.Arrays.copyOf(states, cap);
    }

    public boolean takeDirty() {
        if (!dirty) {
            return false;
        }
        dirty = false;
        return true;
    }

    public ByteBuffer snapshot(int[] countOut) {
        synchronized (lock) {
            ByteBuffer out = ByteBuffer.allocateDirect(count * WORDS * 4).order(ByteOrder.nativeOrder());
            out.asIntBuffer().put(records, 0, count * WORDS);
            countOut[0] = count;
            return out;
        }
    }

    private static TextureAtlas atlas() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getTextureManager() == null) {
            return null;
        }
        var tex = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        return tex instanceof TextureAtlas a ? a : null;
    }

    private int spriteIndex(TextureAtlasSprite s) {
        int i = sprites.indexOf(s);
        return i < 0 || i >= NO_SPRITE ? NO_SPRITE : i;
    }

    private int build(BlockState state, int[] rec) {
        for (int f = 0; f < 24; f++) {
            rec[2 + f] = NO_SPRITE | (NO_SPRITE << 16);
        }
        int kind = kindOf(state);
        Block block = state.getBlock();
        if (kind == KIND_WATER || block == Blocks.LAVA) {
            boolean lava = block == Blocks.LAVA;
            int sp = spriteIndex(lava ? lavaSprite : waterSprite);
            for (int f = 0; f < 6; f++) {
                rec[2 + f * 4] = sp | (NO_SPRITE << 16);
            }
            int tintMask = lava ? 0 : 0x3F;
            rec[0] = (lava ? CLS_OPAQUE : CLS_TRANSLUCENT) | (SHAPE_FULL << 2) | (1 << 6) | ((lava ? 1 : 0) << 9)
                    | (tintMask << 10);
            rec[1] = 1;
            return lava ? -1 : 0;
        }
        BlockStateModelSet set = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        BlockStateModel model = set != null ? set.get(state) : null;
        List<BlockStateModel> variants = new ArrayList<>(4);
        int[] weights = new int[4];
        if (model instanceof WeightedVariants wv) {
            List<Weighted<BlockStateModel>> list = new ArrayList<>(
                    ((WeightedVariantsAccessor) (Object) wv).metalrender$getList().unwrap());
            if (list.size() > 4) {
                list.sort((a, b) -> Integer.compare(b.weight(), a.weight()));
                list = list.subList(0, 4);
            }
            for (Weighted<BlockStateModel> w : list) {
                variants.add(w.value());
                weights[variants.size() - 1] = Math.max(1, w.weight());
            }
        } else if (model != null) {
            variants.add(model);
            weights[0] = 1;
        }
        int cls = CLS_OPAQUE;
        int tintMask = 0;
        int ovTintMask = 0;
        int tintIdx = -1;
        boolean anyDirQuad = false;
        boolean anyNullQuad = false;
        RandomSource random = RandomSource.create(0);
        int cum = 0;
        int cumWord = 0;
        for (int v = 0; v < variants.size(); v++) {
            cum = Math.min(255, cum + weights[v]);
            cumWord |= cum << (8 * v);
            List<BlockStateModelPart> parts = new ArrayList<>();
            try {
                variants.get(v).collectParts(random, parts);
            } catch (RuntimeException e) {
                continue;
            }
            for (int f = 0; f < 6; f++) {
                BakedQuad base = null;
                BakedQuad overlay = null;
                for (BlockStateModelPart part : parts) {
                    List<BakedQuad> qs;
                    try {
                        qs = part.getQuads(DIRS[f]);
                    } catch (RuntimeException e) {
                        continue;
                    }
                    for (BakedQuad q : qs) {
                        if (base == null) {
                            base = q;
                        } else if (overlay == null) {
                            overlay = q;
                        }
                    }
                }
                if (base == null) {
                    for (BlockStateModelPart part : parts) {
                        List<BakedQuad> qs;
                        try {
                            qs = part.getQuads(null);
                        } catch (RuntimeException e) {
                            continue;
                        }
                        for (BakedQuad q : qs) {
                            if (q.direction() == DIRS[f] && base == null && onFacePlane(q, f)) {
                                base = q;
                            }
                        }
                    }
                }
                if (base == null) {
                    continue;
                }
                anyDirQuad = true;
                int entry = encodeFace(base, f) | (overlay != null ? (encodeFace(overlay, f) << 16)
                        : (NO_SPRITE << 16));
                rec[2 + f * 4 + v] = entry;
                if (v == 0) {
                    cls = Math.max(cls, layerCls(base));
                    if (base.materialInfo().isTinted()) {
                        tintMask |= 1 << f;
                        if (tintIdx < 0) {
                            tintIdx = base.materialInfo().tintIndex();
                        }
                    }
                    if (overlay != null && overlay.materialInfo().isTinted()) {
                        ovTintMask |= 1 << f;
                        if (tintIdx < 0) {
                            tintIdx = overlay.materialInfo().tintIndex();
                        }
                    }
                }
            }
            if (!anyDirQuad && v == 0) {
                for (BlockStateModelPart part : parts) {
                    List<BakedQuad> qs;
                    try {
                        qs = part.getQuads(null);
                    } catch (RuntimeException e) {
                        continue;
                    }
                    if (!qs.isEmpty()) {
                        BakedQuad q = qs.get(0);
                        anyNullQuad = true;
                        int entry = encodeCross(q) | (NO_SPRITE << 16);
                        for (int f = 0; f < 6; f++) {
                            rec[2 + f * 4] = entry;
                        }
                        cls = Math.max(cls, Math.max(CLS_CUTOUT, layerCls(q)));
                        if (q.materialInfo().isTinted()) {
                            tintMask = 0x3F;
                            tintIdx = q.materialInfo().tintIndex();
                        }
                        break;
                    }
                }
            }
        }
        int nvar = Math.max(1, variants.size());
        for (int v = 0; v < variants.size(); v++) {
            int fallback = NO_SPRITE;
            for (int f = 0; f < 6 && fallback == NO_SPRITE; f++) {
                int e = rec[2 + f * 4 + v] & 0xFFFF;
                if ((e & 0x1FFF) != NO_SPRITE) {
                    fallback = e;
                }
            }
            if (fallback == NO_SPRITE) {
                try {
                    fallback = spriteIndex(variants.get(v).particleMaterial().sprite());
                } catch (RuntimeException e) {
                    fallback = NO_SPRITE;
                }
            }
            if (fallback == NO_SPRITE) {
                continue;
            }
            for (int f = 0; f < 6; f++) {
                if ((rec[2 + f * 4 + v] & 0x1FFF) == NO_SPRITE) {
                    rec[2 + f * 4 + v] = fallback | (NO_SPRITE << 16);
                }
            }
        }
        int shape = classifyShape(state, anyDirQuad, anyNullQuad);
        boolean leaf = block instanceof LeavesBlock;
        if (leaf) {
            cls = CLS_CUTOUT;
        }
        boolean occluder = shape == SHAPE_FULL && (leaf || (cls == CLS_OPAQUE && state.isSolidRender()));
        if (shape == SHAPE_CROSS) {
            cls = Math.max(cls, CLS_CUTOUT);
        }
        rec[0] = (cls & 3) | ((shape & 15) << 2) | ((nvar & 7) << 6) | ((occluder ? 1 : 0) << 9)
                | ((tintMask & 0x3F) << 10) | ((ovTintMask & 0x3F) << 16) | ((leaf ? 1 : 0) << 22);
        rec[1] = cumWord == 0 ? 1 : cumWord;
        return tintIdx;
    }

    private static boolean onFacePlane(BakedQuad q, int face) {
        float plane = (face & 1) == 1 ? 1.0f : 0.0f;
        float first = Float.NaN;
        float aMin = 1.0f;
        float aMax = 0.0f;
        float bMin = 1.0f;
        float bMax = 0.0f;
        for (int i = 0; i < 4; i++) {
            org.joml.Vector3fc p = q.position(i);
            float v = face < 2 ? p.y() : (face < 4 ? p.z() : p.x());
            float a = face < 2 ? p.x() : p.y();
            float b = face < 4 && face >= 2 ? p.x() : p.z();
            if (Float.isNaN(first)) {
                first = v;
            } else if (Math.abs(v - first) > 0.002f) {
                return false;
            }
            aMin = Math.min(aMin, a);
            aMax = Math.max(aMax, a);
            bMin = Math.min(bMin, b);
            bMax = Math.max(bMax, b);
        }
        if (Math.abs(first - plane) <= 0.002f) {
            return true;
        }
        return Math.abs(first - plane) <= 0.25f && aMin <= 0.002f && aMax >= 0.998f && bMin <= 0.002f
                && bMax >= 0.998f;
    }

    private static int layerCls(BakedQuad q) {
        try {
            ChunkSectionLayer layer = q.materialInfo().layer();
            if (layer == ChunkSectionLayer.TRANSLUCENT) {
                return CLS_TRANSLUCENT;
            }
            if (layer == ChunkSectionLayer.CUTOUT) {
                return CLS_CUTOUT;
            }
        } catch (RuntimeException ignored) {
        }
        return CLS_OPAQUE;
    }

    private int encodeFace(BakedQuad q, int face) {
        TextureAtlasSprite sp = q.materialInfo().sprite();
        int index = spriteIndex(sp);
        if (index == NO_SPRITE) {
            return NO_SPRITE;
        }
        float[] s = new float[4];
        float[] t = new float[4];
        for (int i = 0; i < 4; i++) {
            org.joml.Vector3fc p = q.position(i);
            switch (face) {
                case 0, 1 -> {
                    s[i] = p.x();
                    t[i] = p.z();
                }
                case 2, 3 -> {
                    s[i] = p.x();
                    t[i] = p.y();
                }
                default -> {
                    s[i] = p.z();
                    t[i] = p.y();
                }
            }
        }
        return index | (fitCode(q, sp, s, t) << 13);
    }

    private int encodeCross(BakedQuad q) {
        TextureAtlasSprite sp = q.materialInfo().sprite();
        int index = spriteIndex(sp);
        if (index == NO_SPRITE) {
            return NO_SPRITE;
        }
        float[] s = new float[4];
        float[] t = new float[4];
        for (int i = 0; i < 4; i++) {
            org.joml.Vector3fc p = q.position(i);
            s[i] = p.x();
            t[i] = p.y();
        }
        return index | (fitCode(q, sp, s, t) << 13);
    }

    private static int fitCode(BakedQuad q, TextureAtlasSprite sp, float[] s, float[] t) {
        float du = sp.getU1() - sp.getU0();
        float dv = sp.getV1() - sp.getV0();
        float[] uf = new float[4];
        float[] vf = new float[4];
        for (int i = 0; i < 4; i++) {
            long packed = q.packedUV(i);
            float u = Float.intBitsToFloat((int) (packed >> 32));
            float v = Float.intBitsToFloat((int) packed);
            uf[i] = du != 0 ? (u - sp.getU0()) / du : 0;
            vf[i] = dv != 0 ? (v - sp.getV0()) / dv : 0;
        }
        int best = 0;
        float bestErr = Float.MAX_VALUE;
        for (int code = 0; code < 8; code++) {
            float err = 0;
            for (int i = 0; i < 4; i++) {
                float a = (code & 1) != 0 ? t[i] : s[i];
                float b = (code & 1) != 0 ? s[i] : t[i];
                if ((code & 2) != 0) {
                    a = 1 - a;
                }
                if ((code & 4) != 0) {
                    b = 1 - b;
                }
                err += Math.abs(a - uf[i]) + Math.abs(b - vf[i]);
            }
            if (err < bestErr - 1e-5f) {
                bestErr = err;
                best = code;
            }
        }
        return best;
    }

    private static int classifyShape(BlockState state, boolean anyDirQuad, boolean anyNullQuad) {
        Block b = state.getBlock();
        if (b instanceof SlabBlock) {
            SlabType type = state.getValue(SlabBlock.TYPE);
            return type == SlabType.BOTTOM ? SHAPE_SLAB_BOTTOM : type == SlabType.TOP ? SHAPE_SLAB_TOP : SHAPE_FULL;
        }
        if (b instanceof SnowLayerBlock) {
            int layers = state.getValue(SnowLayerBlock.LAYERS);
            return layers >= 8 ? SHAPE_FULL : SHAPE_LAYER_BASE + layers;
        }
        if (b instanceof CarpetBlock) {
            return SHAPE_LAYER_BASE + 1;
        }
        if (b instanceof FenceBlock || b instanceof IronBarsBlock) {
            return SHAPE_POST_THIN;
        }
        if (b instanceof WallBlock) {
            return SHAPE_POST_WIDE;
        }
        if (b instanceof LeavesBlock || state.isSolidRender()) {
            return SHAPE_FULL;
        }
        if (!anyDirQuad && anyNullQuad) {
            return SHAPE_CROSS;
        }
        try {
            VoxelShape vs = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (!vs.isEmpty()) {
                AABB box = vs.bounds();
                double h = box.maxY - box.minY;
                boolean fullXZ = box.minX <= 0.01 && box.minZ <= 0.01 && box.maxX >= 0.99 && box.maxZ >= 0.99;
                if (fullXZ && box.minY <= 0.01 && h <= 0.2) {
                    return SHAPE_LAYER_BASE + Math.max(1, (int) Math.round(h * 8));
                }
                if (fullXZ && box.minY <= 0.01 && Math.abs(h - 0.5) < 0.07) {
                    return SHAPE_SLAB_BOTTOM;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return SHAPE_FULL;
    }

    public boolean isSmallDecoration(BlockState state) {
        if (state.isSolidRender() || state.getBlock() instanceof LeavesBlock) {
            return false;
        }
        try {
            VoxelShape vs = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (vs.isEmpty()) {
                return false;
            }
            AABB box = vs.bounds();
            double vol = (box.maxX - box.minX) * (box.maxY - box.minY) * (box.maxZ - box.minZ);
            boolean fullXZ = box.minX <= 0.01 && box.minZ <= 0.01 && box.maxX >= 0.99 && box.maxZ >= 0.99;
            return !fullXZ && vol < 0.3 && !(state.getBlock() instanceof FenceBlock)
                    && !(state.getBlock() instanceof WallBlock) && !(state.getBlock() instanceof IronBarsBlock);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
