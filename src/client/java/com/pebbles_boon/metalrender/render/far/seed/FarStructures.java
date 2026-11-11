package com.pebbles_boon.metalrender.render.far.seed;

import com.pebbles_boon.metalrender.sodium.mixins.accessor.SinglePoolElementAccessor;
import com.pebbles_boon.metalrender.sodium.mixins.accessor.StructureTemplateAccessor;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.pools.ListPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

public final class FarStructures {
    public static final int MAX_LEVEL = 4;
    private static final int MARGIN = 160;
    private static final int CACHE_CAP = 4096;

    public static final class Placed {
        public final int[] x;
        public final int[] y;
        public final int[] z;
        public final BlockState[] state;
        public final boolean[] terrain;
        public final BoundingBox box;

        Placed(List<int[]> pos, List<BlockState> states, List<Boolean> terr, BoundingBox box) {
            int n = pos.size();
            x = new int[n];
            y = new int[n];
            z = new int[n];
            state = new BlockState[n];
            terrain = new boolean[n];
            for (int i = 0; i < n; i++) {
                x[i] = pos.get(i)[0];
                y[i] = pos.get(i)[1];
                z[i] = pos.get(i)[2];
                state[i] = states.get(i);
                terrain[i] = terr.get(i);
            }
            this.box = box;
        }
    }

    private static final Placed NONE = new Placed(List.of(), List.of(), List.of(), null);

    private final ServerLevel level;
    private final ChunkGenerator generator;
    private final ChunkGeneratorStructureState state;
    private final RandomState random;
    private final StructureTemplateManager templates;
    private final List<Holder<StructureSet>> sets;
    private final long seed;
    private final ConcurrentHashMap<Long, Placed> cache = new ConcurrentHashMap<>();

    private FarStructures(ServerLevel level) {
        this.level = level;
        this.generator = level.getChunkSource().getGenerator();
        this.state = level.getChunkSource().getGeneratorState();
        this.random = level.getChunkSource().randomState();
        this.templates = level.getServer().getStructureTemplateManager();
        this.sets = new ArrayList<>(state.possibleStructureSets());
        this.seed = level.getSeed();
    }

    public static FarStructures create(ServerLevel level) {
        try {
            return new FarStructures(level);
        } catch (Throwable t) {
            MetalLogger.warn("far structures unavailable: %s", t.toString());
            return null;
        }
    }

    public List<Placed> collect(int x0, int z0, int size) {
        List<Placed> out = new ArrayList<>();
        int cx0 = (x0 - MARGIN) >> 4;
        int cx1 = (x0 + size + MARGIN) >> 4;
        int cz0 = (z0 - MARGIN) >> 4;
        int cz1 = (z0 + size + MARGIN) >> 4;
        for (int si = 0; si < sets.size(); si++) {
            StructureSet set = sets.get(si).value();
            StructurePlacement placement = set.placement();
            if (!(placement instanceof RandomSpreadStructurePlacement spread)) {
                continue;
            }
            int sp = Math.max(1, spread.spacing());
            int rx0 = Math.floorDiv(cx0, sp);
            int rx1 = Math.floorDiv(cx1, sp);
            int rz0 = Math.floorDiv(cz0, sp);
            int rz1 = Math.floorDiv(cz1, sp);
            for (int rz = rz0; rz <= rz1; rz++) {
                for (int rx = rx0; rx <= rx1; rx++) {
                    ChunkPos cp = spread.getPotentialStructureChunk(seed, rx * sp, rz * sp);
                    if (cp.x() < cx0 || cp.x() > cx1 || cp.z() < cz0 || cp.z() > cz1) {
                        continue;
                    }
                    long key = ((long) si << 48) ^ (((long) cp.x() & 0xFFFFFFL) << 24) ^ ((long) cp.z() & 0xFFFFFFL);
                    Placed p = cache.get(key);
                    if (p == null) {
                        p = build(set, placement, cp);
                        if (cache.size() > CACHE_CAP) {
                            cache.clear();
                        }
                        cache.put(key, p);
                    }
                    if (p != NONE && p.box != null && p.box.maxX() >= x0 && p.box.minX() < x0 + size
                            && p.box.maxZ() >= z0 && p.box.minZ() < z0 + size) {
                        out.add(p);
                    }
                }
            }
        }
        return out;
    }

    private Placed build(StructureSet set, StructurePlacement placement, ChunkPos cp) {
        try {
            if (!placement.isStructureChunk(state, cp.x(), cp.z())) {
                return NONE;
            }
            Climate.Sampler climate = random.createClimateSampler(SamplerContext.EMPTY_UNCACHED);
            for (StructureSet.StructureSelectionEntry entry : set.structures()) {
                Holder<Structure> holder = entry.structure();
                Structure structure = holder.value();
                GenerationStep.Decoration step = structure.step();
                if (step != GenerationStep.Decoration.SURFACE_STRUCTURES) {
                    continue;
                }
                StructureStart start = structure.generate(holder, level.dimension(), level.registryAccess(),
                        generator, generator.getBiomeSource(), climate, random, templates, seed, cp, 0, level,
                        b -> structure.biomes().contains(b));
                if (start == null || !start.isValid()) {
                    continue;
                }
                return place(start);
            }
        } catch (Throwable t) {
            MetalLogger.debug("far structure skipped: %s", t.toString());
        }
        return NONE;
    }

    private Placed place(StructureStart start) {
        List<int[]> pos = new ArrayList<>();
        List<BlockState> states = new ArrayList<>();
        List<Boolean> terr = new ArrayList<>();
        for (StructurePiece piece : start.getPieces()) {
            if (piece instanceof PoolElementStructurePiece pep) {
                addElement(pep.getElement(), pep.getPosition(), pep.getRotation(), pep.getBoundingBox(), pos, states,
                        terr);
            } else if (piece instanceof TemplateStructurePiece tsp) {
                addTemplate(tsp.template(), tsp.placeSettings(), tsp.templatePosition(), false, pos, states, terr);
            }
        }
        if (pos.isEmpty()) {
            return NONE;
        }
        return new Placed(pos, states, terr, start.getBoundingBox());
    }

    private void addElement(StructurePoolElement element, BlockPos at, net.minecraft.world.level.block.Rotation rot,
            BoundingBox box, List<int[]> pos, List<BlockState> states, List<Boolean> terr) {
        if (element instanceof ListPoolElement list) {
            for (StructurePoolElement e : list.getElements()) {
                addElement(e, at, rot, box, pos, states, terr);
            }
            return;
        }
        if (!(element instanceof SinglePoolElement single)) {
            return;
        }
        SinglePoolElementAccessor acc = (SinglePoolElementAccessor) (Object) single;
        StructureTemplate template = acc.metalrender$template(templates);
        StructurePlaceSettings settings = acc.metalrender$settings(rot, box, LiquidSettings.IGNORE_WATERLOGGING,
                false);
        boolean terrain = element.getProjection() == StructureTemplatePool.Projection.TERRAIN_MATCHING;
        addTemplate(template, settings, at, terrain, pos, states, terr);
    }

    private void addTemplate(StructureTemplate template, StructurePlaceSettings settings, BlockPos at,
            boolean terrain, List<int[]> pos, List<BlockState> states, List<Boolean> terr) {
        if (template == null) {
            return;
        }
        List<StructureTemplate.Palette> palettes = ((StructureTemplateAccessor) template).metalrender$palettes();
        if (palettes == null || palettes.isEmpty()) {
            return;
        }
        for (StructureTemplate.StructureBlockInfo info : palettes.get(0).blocks()) {
            BlockState st = info.state();
            if (st.isAir() || st.is(Blocks.STRUCTURE_VOID) || st.is(Blocks.JIGSAW) || st.is(Blocks.STRUCTURE_BLOCK)) {
                continue;
            }
            BlockPos rel = StructureTemplate.calculateRelativePosition(settings, info.pos());
            BlockPos p = rel.offset(at);
            pos.add(new int[] { p.getX(), terrain ? rel.getY() : p.getY(), p.getZ() });
            states.add(st.mirror(settings.getMirror()).rotate(settings.getRotation()));
            terr.add(terrain);
        }
    }
}
