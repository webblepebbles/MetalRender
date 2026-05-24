package com.pebbles_boon.metalrender.gui;

import com.pebbles_boon.metalrender.MetalRenderClient;
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
  private static final int LOADING_COLOR = 0xFFFFAA00;
  private static final int LOADING_BG_COLOR = 0x80000000;
  private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath("metalrender", "hud_overlay");

  private String cachedLoadingText;
  private int cachedLoadingTextWidth;
  private int lastPending = -1, lastMeshes = -1;

  @Override
  public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor context, DeltaTracker tickCounter) {
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

    try {
      MetalWorldRenderer wr = MetalWorldRenderer.getInstance();
      if (wr != null && wr.isLoadingMode()) {
        int pending = wr.getLoadingModePendingCount();
        int meshes = wr.getLoadingModeMeshCount();
        if (pending != lastPending || meshes != lastMeshes) {
          cachedLoadingText = "Loading: " + pending + " pending / " + meshes + " built";
          cachedLoadingTextWidth = font.width(cachedLoadingText);
          lastPending = pending;
          lastMeshes = meshes;
        }
        int textWidth = cachedLoadingTextWidth;
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int lx = screenWidth - textWidth - 6;
        int ly = 4;
        context.fill(lx - 3, ly - 1, lx + textWidth + 3, ly + 10, LOADING_BG_COLOR);
        context.text(font, cachedLoadingText, lx, ly, LOADING_COLOR, true);
      }
    } catch (Exception e) {

    }
  }

  public static void register() {
    HudElementRegistry.addLast(HUD_ID, new MetalHudOverlay());
  }
}
