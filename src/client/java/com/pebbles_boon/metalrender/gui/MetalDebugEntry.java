package com.pebbles_boon.metalrender.gui;

import com.mojang.blaze3d.platform.GLX;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

public final class MetalDebugEntry implements DebugScreenEntry {
    private static final Identifier DBG_GRP = Identifier.fromNamespaceAndPath("metalrender", "debug_group");
    private static final Identifier DBG_ID = Identifier.fromNamespaceAndPath("metalrender", "debug");
    private static final String ACTIVE_LINE = "%sMetalRender %s rendering";
    private static final String[] DEATH_TPL = {
            "MetalRender was pricked to death",
            "MetalRender Was Impaled On A Stalagmite whilst trying to escape [PlayerName]'s world",
            "MetalRender fell from a high place whilst trying to escape [PlayerName]'s world",
            "MetalRender Walked into a Danger Zone due to [PlayerName]",
            "MetalRender is grinding coffee beans",
            "MetalRender was slain by Bloffo using Fancy Stick",
            "MetalRender drank a Concoction",
            "bloffo slain MetalRender using fancy stick"
    };

    private static boolean wasOn = true;
    private static int deathIx = -1;
    private static String deathPlyr = "Player";

    public static void register() {
        try {
            var reg = registry();
            reg.put(DBG_ID, new MetalDebugEntry());
            reg.put(DebugScreenEntries.SYSTEM_SPECS, new SysSpecEntry());
        } catch (ReflectiveOperationException err) {
            MetalLogger.error("fail to get metalrender easter egg", err);
        }
    }

    public static void show(@Nullable Minecraft mc) {
        if (mc == null) {
            return;
        }
        mc.debugEntries.setStatus(DBG_ID, DebugScreenEntryStatus.IN_OVERLAY);
    }

    static boolean rendOn() {
        var cfg = MetalRenderClient.getConfig();
        var wr = MetalRenderClient.getWorldRenderer();
        return cfg != null && cfg.enableMetalRendering && MetalRenderClient.isEnabled()
                && wr != null && wr.isReady();
    }

    @Override
    public void display(DebugScreenDisplayer dsp, @Nullable Level lvl, @Nullable LevelChunk chunk,
            @Nullable LevelChunk otherChunk) {
        dsp.addToGroup(DBG_GRP, line());
    }

    private static String line() {
        if (rendOn()) {
            wasOn = true;
            return ACTIVE_LINE.formatted(ChatFormatting.BLUE, dispVer());
        }

        var plyr = plyrName();
        if (wasOn || deathIx < 0 || !plyr.equals(deathPlyr)) {
            deathIx = ThreadLocalRandom.current().nextInt(DEATH_TPL.length);
            deathPlyr = plyr;
        }
        wasOn = false;
        return "%s%s".formatted(ChatFormatting.RED, DEATH_TPL[deathIx].replace("[PlayerName]", plyr));
    }

    private static Map<Identifier, DebugScreenEntry> registry() throws ReflectiveOperationException {
        Field f = DebugScreenEntries.class.getDeclaredField("ENTRIES_BY_ID");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Identifier, DebugScreenEntry> reg = (Map<Identifier, DebugScreenEntry>) f.get(null);
        return reg;
    }

    private static String dispVer() {
        var v = FabricLoader.getInstance()
                .getModContainer("metalrender")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        return !v.isEmpty() && (v.charAt(0) == 'v' || v.charAt(0) == 'V') ? v.substring(1) : v;
    }

    private static String plyrName() {
        var mc = Minecraft.getInstance();
        return mc != null && mc.player != null ? mc.player.getName().getString() : "Player";
    }

    private static final class SysSpecEntry implements DebugScreenEntry {
        private static final Identifier SYS_GRP = Identifier.withDefaultNamespace("system");

        @Override
        public void display(DebugScreenDisplayer dsp, @Nullable Level lvl, @Nullable LevelChunk chunk,
                @Nullable LevelChunk otherChunk) {
            GpuDevice dev = RenderSystem.getDevice();
            Minecraft mc = Minecraft.getInstance();
            List<String> rows = new ArrayList<>(5);
            rows.add(String.format(Locale.ROOT, "Java: %s", System.getProperty("java.version")));
            rows.add(String.format(Locale.ROOT, "CPU: %s", GLX._getCpuInfo()));
            rows.add(String.format(Locale.ROOT, "Display: %dx%d (%s)",
                    mc.getWindow().getWidth(), mc.getWindow().getHeight(), dev.getVendor()));
            rows.add(dev.getRenderer());
            if (!rendOn()) {
                rows.add(String.format(Locale.ROOT, "%s %s", dev.getBackendName(), dev.getVersion()));
            }
            dsp.addToGroup(SYS_GRP, rows);
        }
    }
}