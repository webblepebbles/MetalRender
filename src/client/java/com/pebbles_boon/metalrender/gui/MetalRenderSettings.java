package com.pebbles_boon.metalrender.gui;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;

final class MetalRenderSettings {
    interface Controls {
        void section(String label);

        void toggle(String label, BooleanSupplier value, Consumer<Boolean> setter);

        void choice(String label, List<String> choices, String value, Consumer<String> setter);

        void info(String label, Supplier<String> value);

        default void info(String label, String value) {
            info(label, () -> value);
        }

        void slider(String label, float min, float max, float step, float value,
                Consumer<Float> setter, Function<Float, Component> formatter);

        default void slider(String label, float min, float max, float step, float value, Consumer<Float> setter) {
            slider(label, min, max, step, value, setter, null);
        }
    }

    final MetalRenderConfig config;
    private int pendingTargetFps;
    private int pendingMaxMemMb;
    private boolean pendingDeepDebugNextRun;

    private final int initialBiomeDetail;
    private int initialLeafCulling;
    private final boolean initialMetalOn;
    private final boolean initialCameraFacingCulling;
    private final boolean initialClusterFrustumCulling;
    private final boolean initialGpuTranslucencySort;
    private final boolean initialDistanceLod;
    private final int initialLodNearChunks;
    private final int initialLodMidChunks;
    private final boolean initialGreedyMid;
    private final boolean initialGreedyFar;
    private final boolean initialSmoothLighting;
    private boolean initialSmoothGlass;

    MetalRenderSettings() {
        config = Objects.requireNonNullElseGet(MetalRenderClient.getConfig(), MetalRenderConfig::load);
        pendingTargetFps = config.targetFrameRate;
        pendingMaxMemMb = config.maxMemoryMB;
        pendingDeepDebugNextRun = MetalRenderConfig.isOneRunDeepDebugRequested();

        initialBiomeDetail = config.biomeTransitionDetail;
        initialLeafCulling = config.leafCullingMode;
        initialSmoothLighting = config.smoothLighting;
        initialSmoothGlass = config.smoothGlass;
        initialMetalOn = config.enableMetalRendering;
        initialCameraFacingCulling = config.enableCameraFacingCulling;
        initialClusterFrustumCulling = config.enableClusterFrustumCulling;
        initialGpuTranslucencySort = config.enableGpuTranslucencySort;
        initialDistanceLod = config.enableDistanceLod;
        initialLodNearChunks = config.lodNearChunks;
        initialLodMidChunks = config.lodMidChunks;
        initialGreedyMid = config.enableGreedyMid;
        initialGreedyFar = config.enableGreedyFar;
    }

    void save(boolean renderDistanceChanged) {
        config.targetFrameRate = pendingTargetFps;
        config.maxMemoryMB = pendingMaxMemMb;
        MetalRenderConfig.setOneRunDeepDebugRequested(pendingDeepDebugNextRun);
        config.save();

        boolean metalFlip = config.enableMetalRendering != initialMetalOn;
        boolean needsRebuild = renderDistanceChanged
                || (config.biomeTransitionDetail != initialBiomeDetail)
                || (config.leafCullingMode != initialLeafCulling)
                || (config.smoothLighting != initialSmoothLighting)
                || (config.smoothGlass != initialSmoothGlass)
                || (config.enableDistanceLod != initialDistanceLod)
                || (config.lodNearChunks != initialLodNearChunks)
                || (config.lodMidChunks != initialLodMidChunks)
                || (config.enableGreedyMid != initialGreedyMid)
                || (config.enableGreedyFar != initialGreedyFar);

        boolean biomeChanged = config.biomeTransitionDetail != initialBiomeDetail;
        boolean cameraFacingCullingChanged = config.enableCameraFacingCulling != initialCameraFacingCulling;
        boolean cullingFeaturesChanged = config.enableClusterFrustumCulling != initialClusterFrustumCulling
                || config.enableGpuTranslucencySort != initialGpuTranslucencySort;
        MetalLogger.info(
                "settings close: rebuild=%b rd=%b bm=%b lf=%b lt=%b",
                needsRebuild,
                renderDistanceChanged,
                biomeChanged,
                config.leafCullingMode != initialLeafCulling,
                config.smoothLighting != initialSmoothLighting);
        MetalRenderClient.requestDeferredApply(
                metalFlip || cameraFacingCullingChanged,
                metalFlip,
                !metalFlip && (needsRebuild || biomeChanged || cullingFeaturesChanged));
    }

    void setCustomColours(String background, String panel, String accent, String text) {
        config.customHexBg = sanitizeHex(background, "141C2A");
        config.customHexPanel = sanitizeHex(panel, "1E242E");
        config.customHexAccent = sanitizeHex(accent, "5AC8FF");
        config.customHexText = sanitizeHex(text, "E8F0FF");
        config.uiTheme = UiTheme.CUSTOM.name();
    }

    private static String sanitizeHex(String s, String fallback) {
        if (s == null)
            return fallback;
        String h = s.trim().replace("#", "").replace("0x", "").toUpperCase(Locale.ROOT);
        if (h.length() != 6)
            return fallback;
        for (char c : h.toCharArray())
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F')))
                return fallback;
        return h;
    }

    void addRenderer(Controls controls) {
        controls.section("Renderer");
        controls.toggle("Metal Rendering", () -> config.enableMetalRendering, v -> config.enableMetalRendering = v);
        if (MetalRenderConfig.isDeepDebugActive()) {
            controls.info("Deep Debug Status", "this one this run");
        } else {
            controls.toggle("Deep Debug Next Run", () -> pendingDeepDebugNextRun, v -> pendingDeepDebugNextRun = v);
            controls.info("Deep Debug Status", () -> pendingDeepDebugNextRun ? "ok for next launch" : "Off");
        }
        controls.section("Hardware");
        controls.info("GPU", MetalHardwareChecker.getDeviceName());
        controls.info("Metal", MetalRenderClient.isMetalAvailable() ? "Supported" : "Not Available");
        controls.info("Apple Silicon", MetalHardwareChecker.appleSilicon() ? "yuh" : "nah");
        controls.info("Sodium", MetalRenderClient.isSodiumLoaded() ? "exists" : "Not exists");
        controls.info("Mesh Shaders", MetalHardwareChecker.supportsMeshShaders() ? "Supported" : "Not Available");
    }

    void addLeaves(Controls controls) {
        controls.choice("Leaves Quality", List.of("Fast", "𝒻𝒶𝓃𝒸𝓎"), config.leafCullingMode == 0 ? "Fast" : "𝒻𝒶𝓃𝒸𝓎",
                value -> {
                    config.leafCullingMode = value.equals("Fast") ? 0 : 1;
                    initialLeafCulling = config.leafCullingMode;
                    MetalRenderClient.requestDeferredApply(false, false, true);
                });
    }

    void addQuality(Controls controls) {
        controls.section("Level of Detail");
        controls.toggle("Distance LOD", () -> config.enableDistanceLod, v -> config.enableDistanceLod = v);
        controls.toggle("Throughput Budget", () -> config.lodThroughputBudget, v -> config.lodThroughputBudget = v);
        controls.toggle("Visibility Gate", () -> config.lodVisibilityGate, v -> config.lodVisibilityGate = v);
        controls.toggle("Recency Eviction", () -> config.lodRecencyEviction, v -> config.lodRecencyEviction = v);
        controls.toggle("Sticky Tiers", () -> config.lodStickyTiers, v -> config.lodStickyTiers = v);
        controls.toggle("View Impact", () -> config.lodViewImpact, v -> config.lodViewImpact = v);
        controls.toggle("Skeleton First", () -> config.lodSkeletonFirst, v -> config.lodSkeletonFirst = v);
        controls.slider("Full Detail Radius (chunks)", 2, 32, 1, config.lodNearChunks,
                v -> config.lodNearChunks = v.intValue());
        controls.slider("Medium Detail Radius (chunks)", 4, 48, 1, config.lodMidChunks,
                v -> config.lodMidChunks = v.intValue());
        controls.toggle("Greedy Meshing (Mid LOD)", () -> config.enableGreedyMid, v -> config.enableGreedyMid = v);
        controls.toggle("Greedy Meshing (Far LOD)", () -> config.enableGreedyFar, v -> config.enableGreedyFar = v);
        controls.toggle("Thermal-Adaptive LOD", () -> config.lodThermalAdaptive, v -> config.lodThermalAdaptive = v);

        controls.section("Extras");
        controls.toggle("Smooth Lighting + AO", () -> config.smoothLighting, v -> config.smoothLighting = v);
        controls.toggle("Hidden Fluid Culling", () -> config.hiddenFluidCulling, v -> config.hiddenFluidCulling = v);
        controls.toggle("Improved Fluid Shaping", () -> config.improvedFluidShaping, v -> config.improvedFluidShaping = v);
        controls.toggle("Closest Point Entity Sort", () -> config.closestPointEntitySort, v -> config.closestPointEntitySort = v);
    }

    void addPerformance(Controls controls) {
        controls.section("Frame Pacing");
        controls.slider("Target FPS", 30, 50000, 30, pendingTargetFps, v -> pendingTargetFps = v.intValue());
        controls.toggle("GPU-Driven Terrain (restart)", () -> config.gpuDrivenTerrain, v -> config.gpuDrivenTerrain = v);
        controls.toggle("Triple Buffering", () -> config.enableTripleBuffering, v -> config.enableTripleBuffering = v);
        controls.toggle("Burst Thread Mode", () -> config.enableBurstThreadMode, v -> config.enableBurstThreadMode = v);
        controls.toggle("Sacrifice TPS for FPS", () -> config.prioritizeFpsOverTps, v -> config.prioritizeFpsOverTps = v);
        controls.info("FPS Priority Mode", () -> config.prioritizeFpsOverTps ? "Simulation Distance <= 5" : "Off");
        controls.toggle("Adaptive Resolution", MetalRenderConfig::isAdaptiveResolutionEnabled,
                MetalRenderConfig::setAdaptiveResolutionEnabled);
        controls.toggle("Temporal MetalFX", MetalRenderConfig::isMetalFXTemporalEnabled,
                MetalRenderConfig::setMetalFXTemporalEnabled);
        controls.info("MetalFX Mode", () -> MetalRenderConfig.isMetalFXTemporalEnabled() ? "Temporal" : "Spatial");
        controls.slider("Resolution Scale", 0.10f, 1.50f, 0.05f, MetalRenderConfig.resolutionScale(),
                MetalRenderConfig::setResolutionScale,
                v -> Component.literal(String.format(Locale.ROOT, "%.0f%%%s", v * 100.0f,
                        MetalRenderConfig.isAdaptiveResolutionEnabled() ? " (auto)" : "")));
        controls.section("Memory");
        controls.slider("Max GPU Memory (MB)", 512, 4096, 512, pendingMaxMemMb, v -> pendingMaxMemMb = v.intValue());
        controls.toggle("Memory Fallback", () -> config.enableMemoryPressureFallback, v -> config.enableMemoryPressureFallback = v);
        controls.section("Runtime");
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long max = rt.maxMemory() / (1024 * 1024);
        controls.info("Heap Usage", used + " / " + max + " MB");
    }

    void addAdvanced(Controls controls) {
        controls.section("Metal Features");
        controls.toggle("Camera-Facing Face Culling", () -> config.enableCameraFacingCulling,
                v -> config.enableCameraFacingCulling = v);
        controls.toggle("Argument Buffers", () -> config.enableArgumentBuffers, v -> config.enableArgumentBuffers = v);
        controls.toggle("Indirect CMD Buffers", () -> config.enableIndirectCommandBuffers, v -> config.enableIndirectCommandBuffers = v);
        controls.toggle("Mesh Shaders", () -> config.enableMeshShaders, v -> config.enableMeshShaders = v);
        controls.choice("Terrain Path", List.of("auto", "vertex", "mesh", "indirect"), config.terrainPath, value -> {
            config.terrainPath = value;
            System.setProperty("metalrender.terrainPath", config.terrainPath);
            config.loadFeatureFlags();
        });
        controls.toggle("Programmable Blending", () -> config.enableProgrammableBlending, v -> config.enableProgrammableBlending = v);
        controls.toggle("Cluster Frustum Culling", () -> config.enableClusterFrustumCulling,
                v -> config.enableClusterFrustumCulling = v);
        controls.toggle("GPU Translucency Sort", () -> config.enableGpuTranslucencySort,
                v -> config.enableGpuTranslucencySort = v);
    }

    void addFarTerrain(Controls controls) {
        controls.section("Far Terrain");
        controls.slider("Far Distance (extra chunks)", 0, 512, 8, config.farDistanceChunks,
                v -> config.farDistanceChunks = v.intValue());
        controls.slider("Far Full-Detail Range (chunks)", 8, 512, 8, config.farQuality / 16,
                v -> config.farQuality = v.intValue() * 16);
        controls.toggle("Generate From Seed", () -> config.farSeedGen, v -> config.farSeedGen = v);
        controls.slider("Far GPU Budget (MB)", 64, 2048, 64, config.farGpuBudgetMb,
                v -> config.farGpuBudgetMb = v.intValue());
        controls.slider("Far RAM Cache (MB)", 64, 1024, 32, config.farMemoryMb,
                v -> config.farMemoryMb = v.intValue());
        controls.slider("Far Disk Cache (MB)", 256, 16384, 256, config.farDiskMb,
                v -> config.farDiskMb = v.intValue());
        controls.slider("Far Worker Threads (0 = auto)", 0, 8, 1, config.farThreads,
                v -> config.farThreads = v.intValue());
        controls.toggle("Far Flat Distant Colour", () -> config.farFlatColor, v -> config.farFlatColor = v);
        controls.toggle("Far Terrain-Aware Detail", () -> config.farRoughLod, v -> config.farRoughLod = v);
        controls.toggle("Far Ocean Water Plane", () -> config.farWaterPlane, v -> config.farWaterPlane = v);
        controls.toggle("Far Compact Geometry", () -> config.farCompactQuads, v -> config.farCompactQuads = v);
        controls.toggle("Far Hidden Tile Culling", () -> config.farHiZ, v -> config.farHiZ = v);
        controls.toggle("Far Zoom Detail Saver", () -> config.farZoomSaver, v -> config.farZoomSaver = v);
        controls.toggle("Far Detail Fade", () -> config.farLodFade, v -> config.farLodFade = v);
        controls.toggle("Far Smooth Biome Colours", () -> config.farBiomeBlend, v -> config.farBiomeBlend = v);
    }
}
