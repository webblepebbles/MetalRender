package com.pebbles_boon.metalrender.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker;
import com.pebbles_boon.metalrender.performance.MetalRenderProfiler;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.util.MetalLogger;
import net.minecraft.client.Minecraft;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

public final class MetalRenderCommands {
    private static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                literal("metalrender")
                    .then(literal("help").executes(ctx -> {
                        sendHelp(ctx.getSource());
                        return 1;
                    }))
                    .then(literal("status").executes(ctx -> {
                        sendStatus(ctx.getSource());
                        return 1;
                    }))
                    .then(literal("mark").then(RequiredArgumentBuilder
                            .<FabricClientCommandSource, String>argument("name", StringArgumentType.word())
                            .executes(ctx -> {
                                String name = StringArgumentType.getString(ctx, "name");
                                MetalRenderProfiler.getInstance().setScene(name);
                                msg(ctx.getSource(), "scene = " + name);
                                return 1;
                            })))
                    .then(literal("cache")
                            .then(literal("clear").executes(ctx -> {
                                cacheClear(ctx.getSource());
                                return 1;
                            })))
                    .then(literal("reload").executes(ctx -> {
                        reloadWorld(ctx.getSource());
                        return 1;
                    }))
                    .then(literal("restart").executes(ctx -> {
                        restart(ctx.getSource());
                        return 1;
                    }))
                    .then(literal("adaptive")
                            .then(literal("on").executes(ctx -> {
                                MetalRenderConfig.setAdaptiveResolutionEnabled(true);
                                msg(ctx.getSource(),
                                        "§aAdaptive resolution enabled (auto-scales render resolution to keep GPU time in budget)");
                                return 1;
                            }))
                            .then(literal("off").executes(ctx -> {
                                MetalRenderConfig.setAdaptiveResolutionEnabled(false);
                                MetalRenderConfig.setResolutionScale(1.0f);
                                msg(ctx.getSource(), "§cAdaptive resolution disabled, scale reset to 1.0x");
                                return 1;
                            })))
                    .then(literal("config")
                            .then(literal("open").executes(ctx -> {
                                openConfigScreen(ctx.getSource());
                                return 1;
                            }))
                            .then(literal("save").executes(ctx -> {
                                MetalRenderConfig cfg = MetalRenderClient.getConfig();
                                if (cfg != null)
                                    cfg.save();
                                msg(ctx.getSource(), "§aConfig saved to disk");
                                return 1;
                            }))
                            .then(literal("reload").executes(ctx -> {
                                msg(ctx.getSource(),
                                        "§eRestart Minecraft to reload configuration from disk");
                                return 1;
                            }))
                            .then(literal("reset").executes(ctx -> {
                                resetConfig(ctx.getSource());
                                return 1;
                            })))
                    .then(literal("performance")
                            .then(literal("reset").executes(ctx -> {
                                MetalRenderConfig.setResolutionScale(1.0f);
                                msg(ctx.getSource(),
                                        "§eResolution scale reset to 1.0x");
                                return 1;
                            })))
                    .then(literal("profile").executes(ctx -> {
                        MetalRenderProfiler.getInstance()
                                .toggleVisible();
                        boolean nowVisible = MetalRenderProfiler
                                .getInstance().isVisible();
                        msg(ctx.getSource(), nowVisible ? "§aMetalRender profiler enabled"
                                : "§eMetalRender profiler disabled");
                        return 1;
                    }))
                    .executes(ctx -> {
                        sendHelp(ctx.getSource());
                        return 1;
                    }));
        });
        MetalLogger.info("MetalRender commands registered");
    }

    private static void msg(FabricClientCommandSource src, String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(text));
        }
    }

    private static void sendHelp(FabricClientCommandSource src) {
        msg(src, "§6§l--- MetalRender Commands ---");
        msg(src, "§e/metalrender status §7- Show renderer status");
        msg(src, "§e/metalrender help §7- help menu");
        msg(src, "§e/metalrender cache clear §7- Clear mesh cache and rebuild terrain");
        msg(src, "§e/metalrender reload §7- Reload world renderer");
        msg(src, "§e/metalrender restart §7- Restart renderer");
        msg(src, "§e/metalrender config open §7- Open MetalRender settings screen");
        msg(src, "§e/metalrender config save|reload|reset §7- Config management");
        msg(src, "§e/metalrender performance reset §7- reset perf settings");
        msg(src, "§e/metalrender mark <name> §7- Mark a profiling scene");
        msg(src, "§e/metalrender profile §7- Toggle profiler overlay");
    }

    private static void openConfigScreen(FabricClientCommandSource src) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                msg(src, "§cMinecraft client is unavailable");
                return;
            }
            MetalRenderClient.openSettingsScreen(mc);
            msg(src, "§aOpened MetalRender settings");
        } catch (Exception e) {
            msg(src, "§cFailed to open settings screen: " + e.getMessage());
        }
    }

    private static void sendStatus(FabricClientCommandSource src) {
        boolean available = MetalRenderClient.isMetalAvailable();
        MetalRenderConfig cfg = MetalRenderClient.getConfig();
        boolean enabled = cfg != null && cfg.enableMetalRendering;

        msg(src, "§6§l--- MetalRender Status ---");
        msg(src, "§7Enabled: " + (enabled ? "§aYes" : "§cNo"));
        msg(src, "§7Hardware: "
                + (available ? "§a" + MetalHardwareChecker.getDeviceName() : "§cUnavailable"));
        msg(src, "§7Resolution scale: §f" + String.format("%.2fx", MetalRenderConfig.resolutionScale())
                + (MetalRenderConfig.isAdaptiveResolutionEnabled() ? " §7(§aadaptive§7)" : " §7(§cmanual§7)"));

        MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
        if (wr != null) {
            msg(src, "§7Mesh count: §f" + wr.getChunkMesher().getMeshCount());
            msg(src, "§7Pending: §f" + wr.getChunkMesher().getPendingCount());
        }
    }

    private static void cacheClear(FabricClientCommandSource src) {
        MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
        if (wr != null) {
            wr.getChunkMesher().clearAllMeshes();
            msg(src, "§aMesh cache cleared; terrain will rebuild");
        } else {
            msg(src, "§cWorld renderer is unavailable");
        }
    }

    private static void reloadWorld(FabricClientCommandSource src) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.levelRenderer != null) {
                MetalRenderClient.reloadLevelRenderer(mc);
            }
            MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
            if (wr != null) {
                wr.getChunkMesher().clearAllMeshes();
            }
            msg(src, "§aWorld renderer reloaded");
        } catch (Exception e) {
            msg(src, "§creload failed: " + e.getMessage());
        }
    }

    private static void restart(FabricClientCommandSource src) {
        try {
            MetalRenderConfig cfg = MetalRenderClient.getConfig();
            if (cfg != null) {
                cfg.enableMetalRendering = false;
                cfg.enableMetalRendering = true;
            }
            MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
            if (wr != null) {
                wr.getChunkMesher().clearAllMeshes();
            }
            msg(src, "§aMetalRender restarted");
        } catch (Exception e) {
            msg(src, "§cRestart failed: " + e.getMessage());
        }
    }

    private static void resetConfig(FabricClientCommandSource src) {
        MetalRenderConfig.setResolutionScale(1.0f);
        invalidateAllMeshes();
        msg(src, "§eResolution scale reset to 1.0x; terrain will rebuild");
    }

    private static void invalidateAllMeshes() {
        MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
        if (wr != null) {
            wr.getChunkMesher().clearAllMeshes();
        }
    }

    private MetalRenderCommands() {
    }
}
