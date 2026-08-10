package com.pebbles_boon.metalrender.gui;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.performance.AdaptiveResolutionController;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

@SuppressWarnings("deprecation")
public final class MetalHudOverlay implements HudElement {
  private static final int COLOR = 0xFFFF00FF;
  private static final String LABEL = "MetalRender ACTIVE";
  private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath("metalrender", "hud_overlay");

  @Override
  public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor context,
      DeltaTracker tickCounter) {
    if (!MetalRenderClient.isEnabled() ||
        MetalRenderClient.getWorldRenderer() == null ||
        !MetalRenderClient.getWorldRenderer().isReady()) {
      return;
    }
    Minecraft mc = Minecraft.getInstance();
    if (mc == null)
      return;
    var font = mc.font;
    if (font == null)
      return;
    context.text(font, LABEL, 10, 10, COLOR, true);

    double gpuMs = AdaptiveResolutionController.getInstance().getEmaGpuMs();
    String scaleLine = String.format(java.util.Locale.ROOT, "Res %.0f%% | GPU %.1fms%s",
        MetalRenderConfig.resolutionScale() * 100.0f, gpuMs > 0 ? gpuMs : 0.0,
        MetalRenderConfig.isAdaptiveResolutionEnabled() ? " (adaptive)" : "");
    context.text(font, scaleLine, 10, 22, 0xFF00FFFF, true);
  }

  public static void register() {
    HudElementRegistry.addLast(HUD_ID, new MetalHudOverlay());
  }
}
