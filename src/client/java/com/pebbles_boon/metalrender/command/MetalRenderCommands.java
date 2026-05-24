package com.pebbles_boon.metalrender.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker;
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

                            .then(literal("lod").executes(ctx -> {
                                msg(ctx.getSource(),
                                        "§eLOD is currently disabled. Full-detail chunk meshes stay active.");
                                return 1;
                            }))

                            .then(literal("config")
                                    .then(literal("open").executes(ctx -> {
                                        openConfigScreen(ctx.getSource());
                                        return 1;
                                    }))
                                    .then(literal("save").executes(ctx -> {
                                        MetalRenderConfig cfg = MetalRenderClient.getConfig();
                                        if (cfg != null)
                                            cfg.save();
                                        msg(ctx.getSource(), "§aConfig saved to disk.");
                                        return 1;
                                    }))
                                    .then(literal("reload").executes(ctx -> {

                                        msg(ctx.getSource(),
                                                "§eConfig reloaded. Some changes may require /metalrender restart.");
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
                                                "§ePerformance settings reset to defaults.");
                                        return 1;
                                    })))

                            .executes(ctx -> {
                                sendHelp(ctx.getSource());
                                return 1;
                            }));
        });
        MetalLogger.info("MetalRender commands registered.");
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
        msg(src, "§e/metalrender help §7- This help menu");
        msg(src, "§e/metalrender cache clear §7- Clear cache & restart renderer");
        msg(src, "§e/metalrender reload §7- Reload world renderer");
        msg(src, "§e/metalrender restart §7- Full renderer restart");
        msg(src, "§e/metalrender lod §7- Show LOD disabled status");
        msg(src, "§e/metalrender config open §7- Open MetalRender settings screen");
        msg(src, "§e/metalrender config save|reload|reset §7- Config management");
        msg(src, "§e/metalrender performance reset §7- Reset perf settings");
    }

    private static void openConfigScreen(FabricClientCommandSource src) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                msg(src, "§cMinecraft client unavailable.");
                return;
            }
            MetalRenderClient.openSettingsScreen(mc);
            msg(src, "§aOpening MetalRender settings...");
        } catch (Exception e) {
            msg(src, "§cFailed to open config screen: " + e.getMessage());
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
        msg(src, "§7LOD: §cDisabled §7(full-detail chunk meshes only)");
        msg(src, "§7Resolution scale: §f" + String.format("%.2fx", MetalRenderConfig.resolutionScale()));
        msg(src, "§7Frustum culling: "
                + (MetalRenderConfig.aggressiveFrustumCulling() ? "§aAggressive" : "§eNormal"));

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
            msg(src, "§aCache cleared. Renderer restarting...");
        } else {
            msg(src, "§cWorld renderer not available.");
        }
    }

    private static void reloadWorld(FabricClientCommandSource src) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.levelRenderer != null) {
                mc.levelRenderer.allChanged();
            }
            MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
            if (wr != null) {
                wr.getChunkMesher().clearAllMeshes();
            }
            msg(src, "§aWorld renderer reloaded.");
        } catch (Exception e) {
            msg(src, "§cReload failed: " + e.getMessage());
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
            msg(src, "§aMetalRender restarted.");
        } catch (Exception e) {
            msg(src, "§cRestart failed: " + e.getMessage());
        }
    }

    private static void resetConfig(FabricClientCommandSource src) {
        MetalRenderConfig.setLodEnabled(false);
        MetalRenderConfig.setLod1Distance(4);
        MetalRenderConfig.setLod2Distance(8);
        MetalRenderConfig.setLod3Distance(12);
        MetalRenderConfig.setLod4Distance(16);
        MetalRenderConfig.setResolutionScale(1.0f);
        MetalRenderConfig.setAggressiveFrustumCulling(false);
        MetalRenderConfig.setOcclusionCulling(false);
        MetalRenderConfig.setMirrorUploads(false);
        invalidateAllMeshes();
        msg(src, "§eAll settings reset to defaults. Rebuilding...");
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
