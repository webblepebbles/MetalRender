package com.pebbles_boon.metalrender.render.far.seed;

import com.pebbles_boon.metalrender.util.MetalLogger;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.op.RangeChoiceFunction;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

public final class FarSeedSource {
    public static final int ALGORITHM = 9;

    public final RandomState random;
    public final BiomeSource biomes;
    public final int seaLevel;
    public final int minY;
    public final int height;
    public final long fingerprint;
    public volatile FarStructures structures;
    private final DensitySampler density;
    private final DensitySampler surface;
    private final ThreadLocal<BiomeResolver> resolvers;

    private FarSeedSource(RandomState random, NoiseGeneratorSettings settings, BiomeSource biomes, int minY,
            int height, long seed, String settingsName) {
        this.random = random;
        this.biomes = biomes;
        this.seaLevel = settings.seaLevel();
        this.minY = minY;
        this.height = height;
        DensityFunction full = settings.noiseRouter().finalDensity();
        DensitySampler d;
        try {
            d = random.getSampler(uncut(full, 0));
            d.sampleValue(SamplerContext.EMPTY_UNCACHED, 0, 64, 0);
        } catch (RuntimeException e) {
            d = random.getSampler(full);
        }
        this.density = d;
        this.surface = random.getSampler(settings.noiseRouter().chunkSurfaceLevel());
        this.resolvers = ThreadLocal.withInitial(() -> biomes.createUncachedResolver(random));
        long h = seed * 31L + settingsName.hashCode();
        h = h * 31L + ALGORITHM;
        h = h * 31L + biomes.getClass().getName().hashCode();
        this.fingerprint = h;
    }

    public float density(int x, int y, int z) {
        return density.sampleValue(SamplerContext.EMPTY_UNCACHED, x, y, z);
    }

    public int surfaceEstimate(int x, int z) {
        float v = surface.sampleValue(SamplerContext.EMPTY_UNCACHED, x, 0, z);
        if (!Float.isFinite(v)) {
            return seaLevel;
        }
        return (int) Math.floor(v);
    }

    public Holder<Biome> biome(int x, int y, int z) {
        return resolvers.get().getNoiseBiome(x >> 2, y >> 2, z >> 2);
    }

    private static DensityFunction uncut(DensityFunction f, int depth) {
        if (depth > 48) {
            return f;
        }
        if (f instanceof RangeChoiceFunction rc) {
            DensityFunction in = rc.input();
            if (references(rc.whenInRange(), in, 0) || references(rc.whenOutOfRange(), in, 0)) {
                return uncut(in, depth + 1);
            }
        }
        return f.rewriteChildren(child -> uncut(child, depth + 1));
    }

    private static boolean references(DensityFunction f, DensityFunction target, int depth) {
        if (f == target || f.equals(target)) {
            return true;
        }
        if (depth > 6) {
            return false;
        }
        boolean[] found = { false };
        Consumer<DensityFunction> visit = child -> {
            if (!found[0] && references(child, target, depth + 1)) {
                found[0] = true;
            }
        };
        f.rewriteChildren(child -> {
            visit.accept(child);
            return child;
        });
        return found[0];
    }

    public static FarSeedSource forLevel(Level clientLevel, String manualSeed) {
        if (clientLevel == null) {
            return null;
        }
        ResourceKey<Level> dim = clientLevel.dimension();
        try {
            IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
            if (server != null) {
                ServerLevel level = server.getLevel(dim);
                if (level != null) {
                    ChunkGenerator gen = level.getChunkSource().getGenerator();
                    if (gen instanceof NoiseBasedChunkGenerator noise) {
                        NoiseGeneratorSettings settings = noise.generatorSettings().value();
                        String name = noise.generatorSettings().unwrapKey().map(k -> k.identifier().toString())
                                .orElse("custom");
                        FarSeedSource src = new FarSeedSource(level.getChunkSource().randomState(), settings,
                                gen.getBiomeSource(), level.getMinY(), level.getHeight(), level.getSeed(), name);
                        src.structures = FarStructures.create(level);
                        return src;
                    }
                }
                return null;
            }
            if (manualSeed == null || manualSeed.isBlank() || dim != Level.OVERWORLD) {
                return null;
            }
            long seed = parseSeed(manualSeed.trim());
            HolderLookup.Provider lookup = VanillaRegistries.createWorldLookup();
            Holder<NoiseGeneratorSettings> settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
                    .getOrThrow(NoiseGeneratorSettings.OVERWORLD);
            HolderGetter<NormalNoise> noises = lookup.lookupOrThrow(Registries.NOISE);
            RandomState random = RandomState.create(noises, seed, settings.value());
            Holder<MultiNoiseBiomeSourceParameterList> preset = lookup
                    .lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                    .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
            BiomeSource biomes = MultiNoiseBiomeSource.createFromPreset(preset);
            return new FarSeedSource(random, settings.value(), biomes, clientLevel.getMinY(),
                    clientLevel.getHeight(), seed, "minecraft:overworld");
        } catch (Throwable t) {
            MetalLogger.warn("far seed source unavailable: %s", t.toString());
            return null;
        }
    }

    private static long parseSeed(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return s.hashCode();
        }
    }
}
