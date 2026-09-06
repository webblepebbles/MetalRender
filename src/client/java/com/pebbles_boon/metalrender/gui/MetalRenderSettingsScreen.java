package com.pebbles_boon.metalrender.gui;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.gui.components.MetalOptionSlider;
import com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ParticleStatus;

public class MetalRenderSettingsScreen extends Screen {

  private static final int C_BG_TOP = 0xFF0B0B0D;
  private static final int C_BG_BOTTOM = 0xFF131318;
  private static final int C_PANEL = 0xCC1C1C22;
  private static final int C_PANEL_BORDER = 0xFF2E2E36;
  private static final int C_HEADER = 0xFF16161B;
  private static final int C_HEADER_GRADIENT = 0xFF1E1E26;
  private static final int C_TAB_BAR = 0xFF1F1F26;
  private static final int C_TAB_ACTIVE = 0xFF007AFF;
  private static final int C_TAB_HOVER = 0xFF2A2A32;
  private static final int C_TAB_TEXT = 0xFF9B9BA3;
  private static final int C_CARD = 0xFF25252B;
  private static final int C_CARD_HOVER = 0xFF2E2E36;
  private static final int C_CARD_ACTIVE = 0xFF2A2A34;
  private static final int C_DIVIDER = 0xFF3A3A44;
  private static final int C_TEXT_PRI = 0xFFFFFFFF;
  private static final int C_TEXT_SEC = 0xFF8E8E99;
  private static final int C_TEXT_ACCENT = 0xFF3FA9FF;
  private static final int C_VAL_ON = 0xFF34C759;
  private static final int C_VAL_OFF = 0xFFFF453A;
  private static final int C_PILL_ON = 0xFF34C759;
  private static final int C_PILL_OFF = 0xFF48484F;
  private static final int C_ACCENT = 0xFF007AFF;
  private static final int C_SCROLLTHUMB = 0xFF5A5A66;
  private static final int C_SCROLLTHUMB_HOVER = 0xFF007AFF;

  private static final int PANEL_W = 720;
  private static final int PANEL_H = 500;
  private static final int HDR_H = 44;
  private static final int TAB_H = 34;
  private static final int FOOT_H = 38;
  private static final int CARD_H = 40;
  private static final int CARD_GAP = 2;
  private static final int SEC_H = 30;
  private static final int HPAD = 14;
  private static final int PILL_W = 40;
  private static final int PILL_H = 20;
  private static final int SLIDER_W = 130;
  private static final int SLIDER_H = 14;
  private static final int FPS_LIMIT_MAX = 240;
  private static final int FPS_LIMIT_UNLIMITED = 241;

  private static final String[] TABS = {
      "Video", "MetalRender", "Quality", "Performance", "Advanced"
  };

  private final Screen parent;
  private MetalRenderConfig config;
  private int selectedTab = 0;
  private int scrollOffset = 0;
  private int maxScroll = 0;
  private boolean dragging = false;
  private int dragOriginY, dragOriginOff;
  private int hoverRowIndex = -1;
  private float animTabX = -1;
  private int animTabW = 0;
  private int totalContentHeight = 0;

  private int px, py, pw, ph;
  private int cx, cy, cw, ch;

  private int pendingRenderDist;
  private int pendingSimDist;
  private int pendingMaxFps;
  private int pendingGuiScale;
  private double pendingBrightness;
  private int pendingFov;
  private double pendingDistortion;
  private double pendingFovEffects;
  private int pendingTargetFps;
  private int pendingMaxMemMb;
  private boolean pendingDeepDebugNextRun;

  private int initialRenderDist;
  private int initialBiomeDetail;
  private int initialLeafCulling;
  private boolean initialMetalOn;
  private boolean initialCameraFacingCulling;
  private boolean initialClusterFrustumCulling;
  private boolean initialGpuTranslucencySort;
  private boolean initialDistanceLod;
  private int initialLodNearChunks;
  private int initialLodMidChunks;
  private boolean initialLodThroughputBudget;
  private boolean initialLodVisibilityGate;
  private boolean initialLodRecencyEviction;
  private boolean initialLodStickyTiers;
  private boolean initialLodViewImpact;
  private boolean initialLodSkeletonFirst;
  private boolean initialSmoothLighting;

  private final List<Row> rows = new ArrayList<>();

  private enum RT {
    SECTION, TOGGLE, CYCLE, INFO, VANILLA, SLIDER
  }

  private static class Row {
    final RT type;
    final String label;
    String value;
    Runnable action;
    OptionInstance<?> vanillaOpt;
    MetalOptionSlider slider;
    int renderY = 0;
    int layoutX, layoutY, layoutW;

    Row(RT t, String l) {
      type = t;
      label = l;
    }

    int h() {
      return type == RT.SECTION ? SEC_H : CARD_H;
    }

    int gap() {
      return type == RT.SECTION ? 0 : CARD_GAP;
    }
  }

  public MetalRenderSettingsScreen(Screen parent) {
    super(Component.literal("MetalRender Settings"));
    this.parent = parent;
  }

  @Override
  protected void init() {
    config = MetalRenderClient.getConfig();
    if (config == null)
      config = MetalRenderConfig.load();
    Options o = Minecraft.getInstance().options;
    pendingRenderDist = o.renderDistance().get();
    pendingSimDist = o.simulationDistance().get();
    pendingMaxFps = fromVanillaFpsLimit(o.framerateLimit().get());
    pendingGuiScale = o.guiScale().get();
    pendingBrightness = o.gamma().get();
    pendingFov = o.fov().get();
    pendingDistortion = o.screenEffectScale().get();
    pendingFovEffects = o.fovEffectScale().get();
    pendingTargetFps = config.targetFrameRate;
    pendingMaxMemMb = config.maxMemoryMB;
    pendingDeepDebugNextRun = MetalRenderConfig.isOneRunDeepDebugRequested();

    initialRenderDist = pendingRenderDist;
    initialBiomeDetail = config.biomeTransitionDetail;
    initialLeafCulling = config.leafCullingMode;
    initialSmoothLighting = config.smoothLighting;
    initialMetalOn = config.enableMetalRendering;
    initialCameraFacingCulling = config.enableCameraFacingCulling;
    initialClusterFrustumCulling = config.enableClusterFrustumCulling;
    initialGpuTranslucencySort = config.enableGpuTranslucencySort;
    initialDistanceLod = config.enableDistanceLod;
    initialLodNearChunks = config.lodNearChunks;
    initialLodMidChunks = config.lodMidChunks;
    initialLodThroughputBudget = config.lodThroughputBudget;
    initialLodVisibilityGate = config.lodVisibilityGate;
    initialLodRecencyEviction = config.lodRecencyEviction;
    initialLodStickyTiers = config.lodStickyTiers;
    initialLodViewImpact = config.lodViewImpact;
    initialLodSkeletonFirst = config.lodSkeletonFirst;
    layout();
    rebuild();
  }

  private void layout() {
    pw = Math.min(PANEL_W, width - 16);
    ph = Math.min(PANEL_H, height - 16);
    px = (width - pw) / 2;
    py = (height - ph) / 2;
    cx = px + HPAD;
    cy = py + HDR_H + TAB_H;
    cw = pw - HPAD * 2;
    ch = ph - HDR_H - TAB_H - FOOT_H;
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor ctx, int mx, int my, float delta) {
    var font = getFont();

    drawBackground(ctx);
    drawPanel(ctx);
    drawHeader(ctx, font);
    renderTabs(ctx, mx, my);

    ctx.enableScissor(cx, cy, cx + cw, cy + ch);
    posSliders();
    renderRows(ctx, mx, my);
    ctx.disableScissor();

    drawScrollFades(ctx);
    renderScrollbar(ctx, mx, my);
    drawFooter(ctx, font);

    super.extractRenderState(ctx, mx, my, delta);
  }

  private void drawBackground(GuiGraphicsExtractor ctx) {
    ctx.fillGradient(0, 0, width, height, C_BG_TOP, C_BG_BOTTOM);
    int corner = Math.max(width, height) / 4;
    ctx.fillGradient(0, 0, corner, corner, 0xFF07070A, 0x00131318);
    ctx.fillGradient(width - corner, 0, width, corner, 0xFF07070A, 0x00131318);
    ctx.fillGradient(0, height - corner, corner, height, 0xFF07070A, 0x00131318);
    ctx.fillGradient(width - corner, height - corner, width, height, 0xFF07070A, 0x00131318);
  }

  private void drawPanel(GuiGraphicsExtractor ctx) {
    ctx.fill(px - 2, py - 2, px + pw + 2, py + ph + 2, 0x30000000);
    ctx.fill(px, py, px + pw, py + ph, C_PANEL);
    drawRectOutline(ctx, px, py, pw, ph, C_PANEL_BORDER);
    ctx.fill(px + 1, py + 1, px + pw - 1, py + 2, C_ACCENT);
    ctx.fillGradient(px, py, px + pw, py + 40, 0x1AFFFFFF, 0x00FFFFFF);
    ctx.fill(px, py + ph - 1, px + pw, py + ph, 0x22FFFFFF);
  }

  private void drawHeader(GuiGraphicsExtractor ctx, net.minecraft.client.gui.Font font) {
    ctx.fillGradient(px + 1, py + 1, px + pw - 1, py + HDR_H, C_HEADER, C_HEADER_GRADIENT);
    ctx.text(font,
        Component.literal("MetalRender Settings"),
        px + 16, py + (HDR_H - 9) / 2 + 1, C_TEXT_PRI, false);
    int vx = px + 16 + font.width("MetalRender Settings") + 8;
    ctx.text(font, Component.literal("v2.0.0"),
        vx, py + (HDR_H - 9) / 2 + 1, C_TEXT_SEC, false);
  }

  private void drawFooter(GuiGraphicsExtractor ctx, net.minecraft.client.gui.Font font) {
    int fy = py + ph - FOOT_H;
    ctx.fill(px + 1, fy, px + pw - 1, fy + 1, C_DIVIDER);
    String gpu = MetalHardwareChecker.getDeviceName();
    if (gpu == null || gpu.isEmpty())
      gpu = "Unknown GPU";
    if (font.width(gpu) > pw / 2 - 20)
      gpu = gpu.substring(0, Math.min(gpu.length(), 30)) + "\u2026";
    ctx.text(font, Component.literal(gpu),
        px + 16, fy + (FOOT_H - 9) / 2, C_TEXT_SEC, false);
  }

  private void renderTabs(GuiGraphicsExtractor ctx, int mx, int my) {
    var font = getFont();
    int ty = py + HDR_H;
    ctx.fill(px + 1, ty, px + pw - 1, ty + TAB_H, C_TAB_BAR);
    int tw = pw / TABS.length;
    for (int i = 0; i < TABS.length; i++) {
      int tx = px + i * tw;
      boolean sel = i == selectedTab;
      boolean hov = mx >= tx && mx < tx + tw && my >= ty && my < ty + TAB_H && !sel;

      if (sel) {
        ctx.fill(tx + 2, ty + 2, tx + tw - 2, ty + TAB_H - 2, C_CARD_ACTIVE);
      } else if (hov) {
        ctx.fill(tx + 2, ty + 2, tx + tw - 2, ty + TAB_H - 2, C_TAB_HOVER);
      }

      int tc = sel ? C_TEXT_PRI : (hov ? C_TEXT_ACCENT : C_TAB_TEXT);
      ctx.centeredText(font,
          Component.literal(TABS[i]), tx + tw / 2, ty + (TAB_H - 9) / 2 + 1, tc);
    }

    int targetX = px + selectedTab * tw + 2;
    int targetW = tw - 4;
    if (animTabX < 0) {
      animTabX = targetX;
      animTabW = targetW;
    }
    animTabX += (targetX - animTabX) * 0.25f;
    animTabW += (targetW - animTabW) * 0.25f;
    ctx.fill((int) animTabX, ty + TAB_H - 2, (int) (animTabX + animTabW), ty + TAB_H, C_ACCENT);

    ctx.fill(px + 1, ty + TAB_H - 1, px + pw - 1, ty + TAB_H, C_DIVIDER);
  }

  private void renderRows(GuiGraphicsExtractor ctx, int mx, int my) {
    maxScroll = Math.max(0, totalH() - ch);
    scrollOffset = cl(scrollOffset, 0, maxScroll);
    hoverRowIndex = -1;
    int idx = 0;
    for (Row r : rows) {
      int screenX = cx + r.layoutX;
      int screenY = cy - scrollOffset + r.layoutY;
      r.renderY = screenY;
      boolean visible = screenY + r.h() >= cy && screenY < cy + ch;
      if (visible && my >= cy && my < cy + ch
          && mx >= screenX && mx < screenX + r.layoutW
          && my >= screenY && my < screenY + r.h()) {
        hoverRowIndex = idx;
      }
      if (visible)
        drawRow(ctx, r, screenX, screenY, r.layoutW, mx, my, idx == hoverRowIndex);
      idx++;
    }
  }

  private void drawRow(GuiGraphicsExtractor ctx, Row r, int x, int y, int w, int mx, int my, boolean hovered) {
    var font = getFont();
    if (r.type == RT.SECTION) {
      ctx.text(font, Component.literal(r.label.toUpperCase()),
          x + 4, y + (SEC_H - 9) / 2 + 5, C_TEXT_SEC, false);
      ctx.fill(x + 4, y + SEC_H - 2, x + w - 4, y + SEC_H - 1, C_DIVIDER);
      return;
    }

    fr(ctx, x, y, w, CARD_H, hovered ? C_CARD_HOVER : C_CARD);

    if (r.type == RT.TOGGLE && "Enabled".equals(r.value))
      fr(ctx, x, y, 3, CARD_H, C_VAL_ON);

    if (hovered) {
      ctx.fill(x + 3, y, x + w, y + CARD_H, 0x0DFFFFFF);
    }

    ctx.text(font, Component.literal(r.label),
        x + 14, y + (CARD_H - 9) / 2, C_TEXT_PRI, false);

    int rx = x + w - 10;
    switch (r.type) {
      case TOGGLE -> {
        boolean on = "Enabled".equals(r.value);
        drawPill(ctx, rx - PILL_W, y + (CARD_H - PILL_H) / 2, on);
      }
      case CYCLE -> {
        int vw = font.width(r.value) + 16;
        fr(ctx, rx - vw, y + 7, vw, CARD_H - 14, C_ACCENT);
        ctx.centeredText(font,
            Component.literal(r.value), rx - vw / 2, y + (CARD_H - 9) / 2, C_TEXT_PRI);
      }
      case INFO -> {
        String v = r.value == null ? "" : r.value;
        int col = C_TEXT_SEC;
        if ("Enabled".equals(v) || "Yes".equals(v) || "Supported".equals(v) || "Installed".equals(v))
          col = C_VAL_ON;
        else if ("Disabled".equals(v) || "No".equals(v) || "Not Available".equals(v) || "Not Installed".equals(v))
          col = C_VAL_OFF;
        else if (!v.isEmpty())
          col = C_TEXT_ACCENT;
        ctx.text(font, Component.literal(v),
            rx - font.width(v), y + (CARD_H - 9) / 2, col, false);
      }
      case VANILLA -> {
        String v = r.value == null ? "" : r.value;
        int col = "ON".equals(v) ? C_VAL_ON : ("OFF".equals(v) ? C_VAL_OFF : C_TEXT_ACCENT);
        ctx.text(font, Component.literal(v),
            rx - font.width(v), y + (CARD_H - 9) / 2, col, false);
      }
      case SLIDER -> {
        if (r.slider != null) {
          String sv = r.slider.getMessage().getString();
          ctx.text(font, Component.literal(sv),
              rx - SLIDER_W - 8 - font.width(sv),
              y + (CARD_H - 9) / 2, C_TEXT_ACCENT, false);
        }
      }
      default -> {
      }
    }
    ctx.fill(x + 12, y + CARD_H - 1, x + w - 12, y + CARD_H, C_DIVIDER);
  }

  private void drawPill(GuiGraphicsExtractor ctx, int x, int y, boolean on) {
    int bg = on ? C_PILL_ON : C_PILL_OFF;
    ctx.fill(x + 2, y, x + PILL_W - 2, y + PILL_H, bg);
    ctx.fill(x, y + 2, x + PILL_W, y + PILL_H - 2, bg);
    int kx = on ? x + PILL_W - PILL_H + 1 : x + 1;
    ctx.fill(kx + 1, y + 2, kx + PILL_H - 2, y + PILL_H - 2, 0xFFFFFFFF);
  }

  private void drawScrollFades(GuiGraphicsExtractor ctx) {
    if (maxScroll <= 0)
      return;
    ctx.fillGradient(cx, cy, cx + cw, cy + 10, C_PANEL, 0x001C1C22);
    ctx.fillGradient(cx, cy + ch - 10, cx + cw, cy + ch, 0x001C1C22, C_PANEL);
  }

  private void renderScrollbar(GuiGraphicsExtractor ctx, int mx, int my) {
    if (maxScroll <= 0)
      return;
    int sbX = px + pw - 8;
    int tot = totalH();
    int thumbH = Math.max(18, (int) ((float) ch / tot * ch));
    int thumbY = cy + (int) ((float) scrollOffset / maxScroll * (ch - thumbH));
    boolean hov = mx >= sbX - 2 && mx <= sbX + 5 && my >= cy && my <= cy + ch;
    int trackCol = hov ? 0xFF4A4A54 : 0xFF3A3A44;
    ctx.fill(sbX, cy, sbX + 3, cy + ch, trackCol);
    int col = (hov && mx >= sbX - 2 && my >= thumbY && my <= thumbY + thumbH) ? C_SCROLLTHUMB_HOVER : C_SCROLLTHUMB;
    ctx.fill(sbX, thumbY, sbX + 3, thumbY + thumbH, col);
  }

  private void posSliders() {
    for (Row r : rows) {
      if (r.type != RT.SLIDER || r.slider == null)
        continue;
      int screenX = cx + r.layoutX;
      int screenY = cy - scrollOffset + r.layoutY;
      boolean vis = screenY >= cy && screenY + CARD_H <= cy + ch;
      r.slider.setPosition(screenX + r.layoutW - 8 - SLIDER_W, screenY + (CARD_H - SLIDER_H) / 2);
      r.slider.setWidth(SLIDER_W);
      r.slider.visible = vis;
      r.slider.active = vis;
    }
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent click, boolean bl) {
    double mx = click.x(), my = click.y();

    int ty = py + HDR_H;
    int tw = pw / TABS.length;
    if (my >= ty && my < ty + TAB_H) {
      for (int i = 0; i < TABS.length; i++) {
        int tx = px + i * tw;
        if (mx >= tx && mx < tx + tw) {
          if (selectedTab != i) {
            selectedTab = i;
            scrollOffset = 0;
            rebuild();
          }
          return true;
        }
      }
    }

    int sbX = px + pw - 8;
    if (mx >= sbX - 2 && mx <= sbX + 5 && my >= cy && my <= cy + ch) {
      dragging = true;
      dragOriginY = (int) my;
      dragOriginOff = scrollOffset;
      return true;
    }

    if (mx >= cx && mx < cx + cw && my >= cy && my < cy + ch) {
      for (Row r : rows) {
        int screenX = cx + r.layoutX;
        int screenY = cy - scrollOffset + r.layoutY;
        if (mx >= screenX && mx < screenX + r.layoutW
            && my >= screenY && my < screenY + r.h()) {
          if ((r.type == RT.TOGGLE || r.type == RT.CYCLE) && r.action != null) {
            r.action.run();
            rebuild();
            return true;
          }
          if (r.type == RT.VANILLA && r.vanillaOpt != null) {
            cycleVanilla(r.vanillaOpt);
            rebuild();
            return true;
          }
        }
      }
    }
    return super.mouseClicked(click, bl);
  }

  @Override
  public boolean mouseDragged(MouseButtonEvent click, double dx, double dy) {
    if (dragging && maxScroll > 0) {
      double my = click.y();
      int tot = totalH();
      int thumbH = Math.max(16, (int) ((float) ch / tot * ch));
      float ratio = (float) (my - dragOriginY) / (ch - thumbH);
      scrollOffset = cl(dragOriginOff + (int) (ratio * maxScroll), 0, maxScroll);
      return true;
    }
    return super.mouseDragged(click, dx, dy);
  }

  @Override
  public boolean mouseReleased(MouseButtonEvent click) {
    dragging = false;
    return super.mouseReleased(click);
  }

  @Override
  public boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
    if (mx >= px && mx < px + pw && my >= cy && my < cy + ch) {
      scrollOffset = cl(scrollOffset - (int) (vAmt * CARD_H * 2), 0, maxScroll);
      return true;
    }
    return super.mouseScrolled(mx, my, hAmt, vAmt);
  }

  @Override
  public void onClose() {
    applyPending();
    config.save();

    boolean metalFlip = config.enableMetalRendering != initialMetalOn;
    boolean needsRebuild = (pendingRenderDist != initialRenderDist)
        || (config.biomeTransitionDetail != initialBiomeDetail)
        || (config.leafCullingMode != initialLeafCulling)
        || (config.smoothLighting != initialSmoothLighting)
        || (config.enableDistanceLod != initialDistanceLod)
        || (config.lodNearChunks != initialLodNearChunks)
        || (config.lodMidChunks != initialLodMidChunks);

    boolean biomeChanged = config.biomeTransitionDetail != initialBiomeDetail;
    boolean cameraFacingCullingChanged = config.enableCameraFacingCulling != initialCameraFacingCulling;
    boolean cullingFeaturesChanged = config.enableClusterFrustumCulling != initialClusterFrustumCulling
        || config.enableGpuTranslucencySort != initialGpuTranslucencySort;
    com.pebbles_boon.metalrender.util.MetalLogger.info(
        "settings close: rebuild=%b rd=%b bm=%b lf=%b lt=%b",
        needsRebuild,
        pendingRenderDist != initialRenderDist,
        biomeChanged,
        config.leafCullingMode != initialLeafCulling,
        config.smoothLighting != initialSmoothLighting,
        false);
    MetalRenderClient.requestDeferredApply(
        metalFlip || cameraFacingCullingChanged,
        metalFlip,
        !metalFlip && (needsRebuild || biomeChanged || cullingFeaturesChanged));

    Minecraft mc = Minecraft.getInstance();
    if (mc != null)
      mc.setScreen(parent);
  }

  private void applyPending() {
    Options o = Minecraft.getInstance().options;
    o.renderDistance().set(pendingRenderDist);
    if (config.prioritizeFpsOverTps) {
      pendingSimDist = Math.min(pendingSimDist, 5);
    }
    o.simulationDistance().set(pendingSimDist);
    o.framerateLimit().set(toVanillaFpsLimit(pendingMaxFps));
    o.guiScale().set(pendingGuiScale);
    o.gamma().set(pendingBrightness);
    o.fov().set(pendingFov);
    o.screenEffectScale().set(pendingDistortion);
    o.fovEffectScale().set(pendingFovEffects);
    o.save();
    config.targetFrameRate = pendingTargetFps;
    config.maxMemoryMB = pendingMaxMemMb;
    MetalRenderConfig.setOneRunDeepDebugRequested(pendingDeepDebugNextRun);
  }

  private void rebuild() {
    clearWidgets();
    rows.clear();
    int bw = 70, bh = 20;
    addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
        .bounds(px + pw - bw - 12, py + (HDR_H - bh) / 2 + 1, bw, bh).build());
    switch (selectedTab) {
      case 0 -> buildVideo();
      case 1 -> buildMetal();
      case 2 -> buildQuality();
      case 3 -> buildPerformance();
      case 4 -> buildAdvanced();
    }
    computeLayout();
    for (Row r : rows)
      if (r.type == RT.SLIDER && r.slider != null)
        addRenderableWidget(r.slider);
  }

  private void computeLayout() {
    int col = 0;
    int colWidth = (cw - CARD_GAP) / 2;
    int y = 0;
    Row lastRow = null;
    for (Row r : rows) {
      if (r.type == RT.SECTION) {
        if (col == 1 && lastRow != null) {
          lastRow.layoutX = 0;
          lastRow.layoutY = y;
          lastRow.layoutW = cw;
          y += CARD_H + CARD_GAP;
          col = 0;
        }
        r.layoutX = 0;
        r.layoutY = y;
        r.layoutW = cw;
        y += SEC_H;
        col = 0;
      } else {
        r.layoutX = col == 0 ? 0 : colWidth + CARD_GAP;
        r.layoutY = y;
        r.layoutW = colWidth;
        if (col == 0) {
          col = 1;
        } else {
          col = 0;
          y += CARD_H + CARD_GAP;
        }
      }
      lastRow = r;
    }
    if (col == 1 && lastRow != null) {
      lastRow.layoutX = 0;
      lastRow.layoutW = cw;
      y += CARD_H + CARD_GAP;
    }
    totalContentHeight = y;
  }

  private void buildVideo() {
    Options o = Minecraft.getInstance().options;
    sec("Display");
    vanilla("Fullscreen", o.fullscreen());
    vanilla("VSync", o.enableVsync());
    sld("Max FPS", 1, FPS_LIMIT_UNLIMITED, 1, pendingMaxFps,
        v -> pendingMaxFps = (int) (float) v,
        MetalRenderSettingsScreen::formatFpsLimit);
    sld("GUI Scale", 0, 6, 1, pendingGuiScale, v -> pendingGuiScale = (int) (float) v);
    sec("World");
    sld("Render Distance", 2, 32, 1, pendingRenderDist, v -> pendingRenderDist = (int) (float) v);
    sld("Simulation Distance", 5, 32, 1, pendingSimDist, v -> pendingSimDist = (int) (float) v);
    sec("Environment");
    sld("Brightness", 0f, 1f, 0.05f, (float) pendingBrightness, v -> pendingBrightness = v);
    sec("Camera");
    sld("Field of View", 30f, 110f, 1f, pendingFov, v -> pendingFov = (int) (float) v);
    sld("Distortion Effects", 0f, 1f, 0.05f, (float) pendingDistortion, v -> pendingDistortion = v);
    sld("FOV Effects", 0f, 1f, 0.05f, (float) pendingFovEffects, v -> pendingFovEffects = v);
    vanilla("View Bobbing", o.bobView());
    vanilla("Entity Shadows", o.entityShadows());
    vanilla("Graphics", o.graphicsPreset());
  }

  private void buildMetal() {
    sec("Renderer");
    tog("Metal Rendering", config.enableMetalRendering, v -> config.enableMetalRendering = v);
    if (MetalRenderConfig.isDeepDebugActive()) {
      infoRow("Deep Debug Status", "Active this run");
    } else {
      tog("Deep Debug Next Run", pendingDeepDebugNextRun, v -> pendingDeepDebugNextRun = v);
      infoRow("Deep Debug Status", pendingDeepDebugNextRun ? "Armed for next launch" : "Off");
    }
    sec("Hardware");
    infoRow("GPU", MetalHardwareChecker.getDeviceName());
    infoRow("Metal", MetalRenderClient.isMetalAvailable() ? "Supported" : "Not Available");
    infoRow("Apple Silicon", MetalHardwareChecker.appleSilicon() ? "Yes" : "No");
    infoRow("Sodium", MetalRenderClient.isSodiumLoaded() ? "Installed" : "Not Installed");
    infoRow("Mesh Shaders", MetalHardwareChecker.supportsMeshShaders() ? "Supported" : "Not Available");
  }

  private void buildQuality() {
    Options o = Minecraft.getInstance().options;

    sec("World");
    vanilla("Ambient Occlusion", o.ambientOcclusion());
    sld("Biome Blend", 0, 7, 1, o.biomeBlendRadius().get(),
        v -> o.biomeBlendRadius().set((int) (float) v));
    cyc("Leaves Quality",
        config.leafCullingMode == 0 ? "Fast" : "Fancy",
        () -> config.leafCullingMode = (config.leafCullingMode == 0) ? 1 : 0);

    sec("Atmosphere");
    vanilla("Clouds", o.cloudStatus());
    sld("Cloud Distance", 2, 128, 2, o.cloudRange().get(),
        v -> o.cloudRange().set((int) (float) v));
    sld("Weather Radius", 3, 10, 1, o.weatherRadius().get(),
        v -> o.weatherRadius().set((int) (float) v));

    sec("Entities & Effects");
    vanilla("Particles", o.particles());
    vanilla("Entity Shadows", o.entityShadows());
    sld("Entity Distance", 50, 500, 25,
        Math.round(o.entityDistanceScaling().get().floatValue() * 100f),
        v -> o.entityDistanceScaling().set(v / 100.0));
    vanilla("Vignette", o.vignette());
    sld("Chunk Fade", 0, 2000, 50,
        (int) (o.chunkSectionFadeInTime().get() * 1000.0),
        v -> o.chunkSectionFadeInTime().set(v / 1000.0));

    sec("Textures");
    sld("Mipmap Levels", 0, 4, 1, o.mipmapLevels().get(),
        v -> o.mipmapLevels().set((int) (float) v));
    vanilla("Improved Transparency", o.improvedTransparency());
    vanilla("Texture Filtering", o.textureFiltering());
    sld("Anisotropy", 0, 3, 1, o.maxAnisotropyBit().get(),
        v -> o.maxAnisotropyBit().set((int) (float) v));

    sec("Level of Detail");
    tog("Distance LOD", config.enableDistanceLod, v -> config.enableDistanceLod = v);
    tog("Throughput Budget", config.lodThroughputBudget, v -> config.lodThroughputBudget = v);
    tog("Visibility Gate", config.lodVisibilityGate, v -> config.lodVisibilityGate = v);
    tog("Recency Eviction", config.lodRecencyEviction, v -> config.lodRecencyEviction = v);
    tog("Sticky Tiers", config.lodStickyTiers, v -> config.lodStickyTiers = v);
    tog("View Impact", config.lodViewImpact, v -> config.lodViewImpact = v);
    tog("Skeleton First", config.lodSkeletonFirst, v -> config.lodSkeletonFirst = v);
    sld("Full Detail Radius (chunks)", 2, 32, 1, config.lodNearChunks,
        v -> config.lodNearChunks = (int) (float) v);
    sld("Medium Detail Radius (chunks)", 4, 48, 1, config.lodMidChunks,
        v -> config.lodMidChunks = (int) (float) v);
    tog("Thermal-Adaptive LOD", config.lodThermalAdaptive, v -> config.lodThermalAdaptive = v);

    sec("Extras");
    tog("Smooth Lighting + AO", config.smoothLighting, v -> config.smoothLighting = v);
    tog("Hidden Fluid Culling", config.hiddenFluidCulling, v -> config.hiddenFluidCulling = v);
    tog("Improved Fluid Shaping", config.improvedFluidShaping, v -> config.improvedFluidShaping = v);
    tog("Closest Point Entity Sort", config.closestPointEntitySort, v -> config.closestPointEntitySort = v);
  }

  private void buildPerformance() {
    sec("Frame Pacing");
    sld("Target FPS", 30, 5000, 30, pendingTargetFps, v -> pendingTargetFps = (int) (float) v);
    tog("Triple Buffering", config.enableTripleBuffering, v -> config.enableTripleBuffering = v);
    tog("Burst Thread Mode", config.enableBurstThreadMode, v -> config.enableBurstThreadMode = v);
    tog("Sacrifice TPS for FPS", config.prioritizeFpsOverTps, v -> config.prioritizeFpsOverTps = v);
    infoRow("FPS Priority Mode", config.prioritizeFpsOverTps ? "Simulation Distance <= 5" : "Off");
    tog("Adaptive Resolution", MetalRenderConfig.isAdaptiveResolutionEnabled(),
        MetalRenderConfig::setAdaptiveResolutionEnabled);
    tog("Temporal MetalFX", MetalRenderConfig.isMetalFXTemporalEnabled(),
        MetalRenderConfig::setMetalFXTemporalEnabled);
    infoRow("MetalFX Mode", MetalRenderConfig.isMetalFXTemporalEnabled() ? "Temporal" : "Spatial");
    sld(MetalRenderConfig.isAdaptiveResolutionEnabled()
        ? "Resolution Scale (auto)"
        : "Resolution Scale",
        0.50f, 1.50f, 0.05f, MetalRenderConfig.resolutionScale(),
        MetalRenderConfig::setResolutionScale,
        v -> Component.literal(String.format(java.util.Locale.ROOT, "%.0f%%", v * 100.0f)));
    sec("Memory");
    sld("Max GPU Memory (MB)", 512, 4096, 512, pendingMaxMemMb, v -> pendingMaxMemMb = (int) (float) v);
    tog("Memory Fallback", config.enableMemoryPressureFallback, v -> config.enableMemoryPressureFallback = v);
    sec("Runtime");
    Runtime rt = Runtime.getRuntime();
    long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    long max = rt.maxMemory() / (1024 * 1024);
    infoRow("Heap Usage", used + " / " + max + " MB");
  }

  private void buildAdvanced() {
    sec("Metal Features");
    tog("Camera-Facing Face Culling", config.enableCameraFacingCulling,
        v -> config.enableCameraFacingCulling = v);
    tog("Argument Buffers", config.enableArgumentBuffers, v -> config.enableArgumentBuffers = v);
    tog("Indirect CMD Buffers", config.enableIndirectCommandBuffers, v -> config.enableIndirectCommandBuffers = v);
    tog("Mesh Shaders", config.enableMeshShaders, v -> config.enableMeshShaders = v);
    tog("Programmable Blending", config.enableProgrammableBlending, v -> config.enableProgrammableBlending = v);
    tog("Cluster Frustum Culling", config.enableClusterFrustumCulling,
        v -> config.enableClusterFrustumCulling = v);
    tog("GPU Translucency Sort", config.enableGpuTranslucencySort,
        v -> config.enableGpuTranslucencySort = v);
  }

  private void sec(String label) {
    rows.add(new Row(RT.SECTION, label));
  }

  private void tog(String label, boolean on, java.util.function.Consumer<Boolean> setter) {
    boolean[] s = { on };
    Row r = new Row(RT.TOGGLE, label);
    r.value = s[0] ? "Enabled" : "Disabled";
    r.action = () -> {
      s[0] = !s[0];
      r.value = s[0] ? "Enabled" : "Disabled";
      setter.accept(s[0]);
    };
    rows.add(r);
  }

  private void cyc(String label, String initial, Runnable action) {
    Row r = new Row(RT.CYCLE, label);
    r.value = initial;
    r.action = action;
    rows.add(r);
  }

  private void infoRow(String label, String value) {
    Row r = new Row(RT.INFO, label);
    r.value = value;
    rows.add(r);
  }

  private void vanilla(String label, OptionInstance<?> opt) {
    Row r = new Row(RT.VANILLA, label);
    r.value = fmtV(opt);
    r.vanillaOpt = opt;
    rows.add(r);
  }

  private void sld(String label, float min, float max, float step,
      float cur, java.util.function.Consumer<Float> cb) {
    sld(label, min, max, step, cur, cb, null);
  }

  private void sld(String label, float min, float max, float step,
      float cur, java.util.function.Consumer<Float> cb,
      java.util.function.Function<Float, Component> formatter) {
    Row r = new Row(RT.SLIDER, label);
    r.slider = new MetalOptionSlider(0, 0, SLIDER_W, SLIDER_H,
        Component.literal(""), min, max, step, cur, cb, formatter);
    rows.add(r);
  }

  private static int fromVanillaFpsLimit(int fpsLimit) {
    if (fpsLimit >= 260) {
      return FPS_LIMIT_UNLIMITED;
    }
    return cl(fpsLimit, 1, FPS_LIMIT_MAX);
  }

  private static int toVanillaFpsLimit(int sliderValue) {
    if (sliderValue > FPS_LIMIT_MAX) {
      return 260;
    }
    return cl(sliderValue, 1, FPS_LIMIT_MAX);
  }

  private static Component formatFpsLimit(float value) {
    int fpsLimit = Math.round(value);
    if (fpsLimit > FPS_LIMIT_MAX) {
      return Component.translatable("options.framerateLimit.max");
    }
    return Component.translatable("options.framerate", fpsLimit);
  }

  private int totalH() {
    return totalContentHeight;
  }

  private String fmtV(OptionInstance<?> opt) {
    Object v = opt.get();
    if (v instanceof Boolean b)
      return b ? "ON" : "OFF";
    if (v instanceof Integer i)
      return String.valueOf(i);
    if (v instanceof Double d) {
      if (d == (int) (double) d)
        return String.valueOf((int) (double) d);
      return String.format("%.1f", d);
    }
    return v.toString();
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private void cycleVanilla(OptionInstance opt) {
    Object v = opt.get();
    if (v instanceof Boolean b)
      opt.set(!b);
    else if (v instanceof Enum<?> e) {
      @SuppressWarnings("unchecked")
      Enum<?>[] vals = e.getDeclaringClass().getEnumConstants();
      if (vals != null) {
        int next = (e.ordinal() + 1) % vals.length;
        opt.set(vals[next]);
      }
    }
  }

  private static void fr(GuiGraphicsExtractor ctx, int x, int y, int w, int h, int col) {
    ctx.fill(x, y, x + w, y + h, col);
  }

  private static void drawRectOutline(GuiGraphicsExtractor ctx, int x, int y, int w, int h, int col) {
    ctx.fill(x, y, x + w, y + 1, col);
    ctx.fill(x, y + h - 1, x + w, y + h, col);
    ctx.fill(x, y, x + 1, y + h, col);
    ctx.fill(x + w - 1, y, x + w, y + h, col);
  }

  private static int cl(int v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, v));
  }
}
