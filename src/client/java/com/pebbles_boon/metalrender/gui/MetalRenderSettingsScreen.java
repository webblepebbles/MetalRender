package com.pebbles_boon.metalrender.gui;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.gui.components.MetalOptionSlider;
import com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public class MetalRenderSettingsScreen extends Screen {

  private static final int C_PANEL = 0xFF1E1E20;
  private static final int C_HEADER = 0xFF161618;
  private static final int C_TAB_BAR = 0xFF252527;
  private static final int C_TAB_ACTIVE = 0xFF007AFF;
  private static final int C_TAB_HOVER = 0xFF38383A;
  private static final int C_CARD = 0xFF2C2C2E;
  private static final int C_CARD_HOVER = 0xFF38383A;
  private static final int C_DIVIDER = 0xFF3A3A3C;
  private static final int C_TEXT_PRI = 0xFFFFFFFF;
  private static final int C_TEXT_SEC = 0xFF8E8E93;
  private static final int C_TEXT_ACCENT = 0xFF007AFF;
  private static final int C_VAL_ON = 0xFF30D158;
  private static final int C_VAL_OFF = 0xFFFF453A;
  private static final int C_PILL_ON = 0xFF34C759;
  private static final int C_PILL_OFF = 0xFF48484A;
  private static final int C_SCROLLTHUMB = 0xFF636366;

  private static final int PANEL_W = 700;
  private static final int PANEL_H = 460;
  private static final int HDR_H = 38;
  private static final int TAB_H = 32;
  private static final int FOOT_H = 36;
  private static final int CARD_H = 38;
  private static final int CARD_GAP = 1;
  private static final int SEC_H = 28;
  private static final int HPAD = 10;
  private static final int PILL_W = 40;
  private static final int PILL_H = 20;
  private static final int SLIDER_W = 120;
  private static final int SLIDER_H = 12;
  private static final int FPS_LIMIT_MAX = 240;
  private static final int FPS_LIMIT_UNLIMITED = 241;

  private static final String[] TABS = {
      "Video", "MetalRender", "Quality", "Performance", "Advanced", "LOD"
  };

  private final Screen parent;
  private MetalRenderConfig config;
  private int selectedTab = 0;
  private int scrollOffset = 0;
  private int maxScroll = 0;
  private boolean dragging = false;
  private int dragOriginY, dragOriginOff;

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
  private int pendingLod1, pendingLod2, pendingLod3, pendingLod4;
  private boolean pendingLodEnabled;
  private boolean pendingDeepDebugNextRun;

  private int initialRenderDist;
  private int initialLod1, initialLod2, initialLod3, initialLod4;
  private boolean initialLodEnabled;
  private int initialBiomeDetail;
  private int initialLeafCulling;
  private boolean initialMetalOn;
  private boolean initialSmoothLighting;
  private boolean initialDebugPinkBlockTint;

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
    pendingLod1 = MetalRenderConfig.lod1Distance();
    pendingLod2 = MetalRenderConfig.lod2Distance();
    pendingLod3 = MetalRenderConfig.lod3Distance();
    pendingLod4 = MetalRenderConfig.lod4Distance();
    pendingLodEnabled = MetalRenderConfig.lodEnabled();
    pendingDeepDebugNextRun = MetalRenderConfig.isOneRunDeepDebugRequested();

    initialRenderDist = pendingRenderDist;
    initialLod1 = pendingLod1;
    initialLod2 = pendingLod2;
    initialLod3 = pendingLod3;
    initialLod4 = pendingLod4;
    initialLodEnabled = pendingLodEnabled;
    initialBiomeDetail = config.biomeTransitionDetail;
    initialLeafCulling = config.leafCullingMode;
    initialMetalOn = config.enableMetalRendering;
    initialSmoothLighting = config.enableSimpleLighting;
    initialDebugPinkBlockTint = config.debugPinkBlockTint;
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

    ctx.fill(0, 0, width, height, 0xAA000000);

    ctx.fill(px + 4, py + 4, px + pw + 4, py + ph + 4, 0x44000000);

    fr(ctx, px, py, pw, ph, C_PANEL);

    fr(ctx, px, py, pw, HDR_H, C_HEADER);
    ctx.text(font,
        Component.literal("MetalRender Settings"),
        px + 12, py + (HDR_H - 9) / 2, C_TEXT_PRI);
    int vx = px + 12 + font.width("MetalRender Settings") + 6;
    ctx.text(font, Component.literal("v0.1.7"),
        vx, py + (HDR_H - 9) / 2, C_TEXT_SEC, false);

    renderTabs(ctx, mx, my);

    ctx.enableScissor(cx, cy, cx + cw, cy + ch);
    posSliders();
    renderRows(ctx, mx, my);
    ctx.disableScissor();

    renderScrollbar(ctx);

    int fy = py + ph - FOOT_H;
    fr(ctx, px, fy, pw, 1, C_DIVIDER);
    String gpu = MetalHardwareChecker.getDeviceName();
    if (gpu == null || gpu.isEmpty())
      gpu = "Unknown GPU";
    if (font.width(gpu) > pw / 2 - 20)
      gpu = gpu.substring(0, Math.min(gpu.length(), 30)) + "\u2026";
    ctx.text(font, Component.literal(gpu),
        px + 12, fy + (FOOT_H - 9) / 2, C_TEXT_SEC, false);
    super.extractRenderState(ctx, mx, my, delta);
  }

  private void renderTabs(GuiGraphicsExtractor ctx, int mx, int my) {
    var font = getFont();
    int ty = py + HDR_H;
    fr(ctx, px, ty, pw, TAB_H, C_TAB_BAR);
    int tw = pw / TABS.length;
    for (int i = 0; i < TABS.length; i++) {
      int tx = px + i * tw;
      boolean sel = i == selectedTab;
      boolean hov = mx >= tx && mx < tx + tw && my >= ty && my < ty + TAB_H && !sel;
      int bg = sel ? C_TAB_ACTIVE : (hov ? C_TAB_HOVER : C_TAB_BAR);
      fr(ctx, tx + 2, ty + 2, tw - 4, TAB_H - 4, bg);
      int tc = (sel || hov) ? C_TEXT_PRI : C_TEXT_SEC;
      ctx.centeredText(font,
          Component.literal(TABS[i]), tx + tw / 2, ty + (TAB_H - 9) / 2, tc);
    }
    fr(ctx, px, ty + TAB_H - 1, pw, 1, C_DIVIDER);
  }

  private void renderRows(GuiGraphicsExtractor ctx, int mx, int my) {
    int totalH = totalH();
    maxScroll = Math.max(0, totalH - ch);
    scrollOffset = cl(scrollOffset, 0, maxScroll);
    int y = cy - scrollOffset;
    for (Row r : rows) {
      r.renderY = y;
      if (y + r.h() >= cy && y < cy + ch)
        drawRow(ctx, r, y, mx, my);
      y += r.h() + r.gap();
    }
  }

  private void drawRow(GuiGraphicsExtractor ctx, Row r, int y, int mx, int my) {
    var font = getFont();
    if (r.type == RT.SECTION) {
      ctx.text(font, Component.literal(r.label.toUpperCase()),
          cx + 4, y + (SEC_H - 9) / 2 + 4, C_TEXT_SEC, false);
      return;
    }
    boolean hov = mx >= cx && mx < cx + cw
        && my >= y && my < y + r.h()
        && my >= cy && my < cy + ch;
    fr(ctx, cx, y, cw, CARD_H, hov ? C_CARD_HOVER : C_CARD);

    if (r.type == RT.TOGGLE && "Enabled".equals(r.value))
      fr(ctx, cx, y, 3, CARD_H, C_TAB_ACTIVE);
    ctx.text(font, Component.literal(r.label),
        cx + 10, y + (CARD_H - 9) / 2, C_TEXT_PRI, false);
    int rx = cx + cw - 8;
    switch (r.type) {
      case TOGGLE -> {
        boolean on = "Enabled".equals(r.value);
        drawPill(ctx, rx - PILL_W, y + (CARD_H - PILL_H) / 2, on);
      }
      case CYCLE -> {
        int vw = font.width(r.value) + 12;
        fr(ctx, rx - vw, y + 8, vw, CARD_H - 16, C_TAB_ACTIVE);
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
              rx - SLIDER_W - 6 - font.width(sv),
              y + (CARD_H - 9) / 2, C_TEXT_ACCENT, false);
        }
      }
      default -> {
      }
    }
    fr(ctx, cx + 8, y + CARD_H - 1, cw - 16, 1, C_DIVIDER);
  }

  private void drawPill(GuiGraphicsExtractor ctx, int x, int y, boolean on) {
    int bg = on ? C_PILL_ON : C_PILL_OFF;
    ctx.fill(x + 2, y, x + PILL_W - 2, y + PILL_H, bg);
    ctx.fill(x, y + 2, x + PILL_W, y + PILL_H - 2, bg);
    int kx = on ? x + PILL_W - PILL_H + 1 : x + 1;
    ctx.fill(kx + 1, y + 2, kx + PILL_H - 2, y + PILL_H - 2, 0xFFFFFFFF);
  }

  private void renderScrollbar(GuiGraphicsExtractor ctx) {
    if (maxScroll <= 0)
      return;
    int sbX = px + pw - 4;
    int tot = totalH();
    int thumbH = Math.max(16, (int) ((float) ch / tot * ch));
    int thumbY = cy + (int) ((float) scrollOffset / maxScroll * (ch - thumbH));
    ctx.fill(sbX, cy, sbX + 3, cy + ch, 0xFF3A3A3C);
    ctx.fill(sbX, thumbY, sbX + 3, thumbY + thumbH, C_SCROLLTHUMB);
  }

  private void posSliders() {
    for (Row r : rows) {
      if (r.type != RT.SLIDER || r.slider == null)
        continue;
      int ry = r.renderY;
      boolean vis = ry >= cy && ry + CARD_H <= cy + ch;
      r.slider.setPosition(cx + cw - 8 - SLIDER_W, ry + (CARD_H - SLIDER_H) / 2);
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

    int sbX = px + pw - 6;
    if (mx >= sbX && mx <= sbX + 6 && my >= cy && my <= cy + ch) {
      dragging = true;
      dragOriginY = (int) my;
      dragOriginOff = scrollOffset;
      return true;
    }

    if (mx >= cx && mx < cx + cw && my >= cy && my < cy + ch) {
      int y = cy - scrollOffset;
      for (Row r : rows) {
        double lo = Math.max(y, cy), hi = Math.min(y + r.h(), cy + ch);
        if (my >= lo && my < hi) {
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
        y += r.h() + r.gap();
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
        || (pendingLod1 != initialLod1)
        || (pendingLod2 != initialLod2)
        || (pendingLod3 != initialLod3)
        || (pendingLod4 != initialLod4)
        || (pendingLodEnabled != initialLodEnabled)
        || (config.biomeTransitionDetail != initialBiomeDetail)
        || (config.leafCullingMode != initialLeafCulling)
        || (config.enableSimpleLighting != initialSmoothLighting)
        || (config.debugPinkBlockTint != initialDebugPinkBlockTint);

    boolean biomeChanged = config.biomeTransitionDetail != initialBiomeDetail;
    com.pebbles_boon.metalrender.util.MetalLogger.info(
        "Settings closed: needsRebuild=%b (renderDist=%b lod=%b biome=%b leaf=%b lighting=%b)",
        needsRebuild,
        pendingRenderDist != initialRenderDist,
        pendingLod1 != initialLod1 || pendingLod2 != initialLod2 || pendingLod3 != initialLod3
            || pendingLod4 != initialLod4 || pendingLodEnabled != initialLodEnabled,
        biomeChanged,
        config.leafCullingMode != initialLeafCulling,
        config.enableSimpleLighting != initialSmoothLighting);
    MetalRenderClient.requestDeferredApply(
        metalFlip,
        metalFlip,
        !metalFlip && (needsRebuild || biomeChanged));

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
    MetalRenderConfig.setLodEnabled(pendingLodEnabled);
    MetalRenderConfig.setLod1Distance(pendingLod1);
    MetalRenderConfig.setLod2Distance(pendingLod2);
    MetalRenderConfig.setLod3Distance(pendingLod3);
    MetalRenderConfig.setLod4Distance(pendingLod4);
    MetalRenderConfig.setDebugPinkBlockTint(config.debugPinkBlockTint);
  }

  private void rebuild() {
    clearWidgets();
    rows.clear();
    int bw = 64, bh = 20;
    addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
        .bounds(px + pw - bw - 10, py + (HDR_H - bh) / 2, bw, bh).build());
    switch (selectedTab) {
      case 0 -> buildVideo();
      case 1 -> buildMetal();
      case 2 -> buildQuality();
      case 3 -> buildPerformance();
      case 4 -> buildAdvanced();
      case 5 -> buildLod();
    }
    for (Row r : rows)
      if (r.type == RT.SLIDER && r.slider != null)
        addRenderableWidget(r.slider);
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
    tog("Smooth Lighting", config.enableSimpleLighting, v -> config.enableSimpleLighting = v);
    tog("Debug: Pink Block Tint", config.debugPinkBlockTint, v -> config.debugPinkBlockTint = v);
    if (MetalRenderConfig.isDeepDebugActive()) {
      nfo("Deep Debug Status", "Active this run");
    } else {
      tog("Deep Debug Next Run", pendingDeepDebugNextRun, v -> pendingDeepDebugNextRun = v);
      nfo("Deep Debug Status", pendingDeepDebugNextRun ? "Armed for next launch" : "Off");
    }
    sec("Hardware");
    nfo("GPU", MetalHardwareChecker.getDeviceName());
    nfo("Metal", MetalRenderClient.isMetalAvailable() ? "Supported" : "Not Available");
    nfo("Apple Silicon", MetalHardwareChecker.appleSilicon() ? "Yes" : "No");
    nfo("Sodium", MetalRenderClient.isSodiumLoaded() ? "Installed" : "Not Installed");
    nfo("Mesh Shaders", MetalHardwareChecker.supportsMeshShaders() ? "Supported" : "Not Available");
  }

  private void buildQuality() {
    sec("Rendering Style");
    cyc("Leaves Mode", config.leafCullingMode == 0 ? "Fast" : "Fancy",
        () -> config.leafCullingMode = config.leafCullingMode == 0 ? 1 : 0);
    nfo("LOD Status", "Disabled");
    sec("Biome Blending");

    sld("Biome Blend", 0, 10, 1, config.biomeTransitionDetail,
        v -> config.biomeTransitionDetail = (int) (float) v);
  }

  private void buildPerformance() {
    sec("Frame Pacing");
    sld("Target FPS", 30, 5000, 30, pendingTargetFps, v -> pendingTargetFps = (int) (float) v);
    tog("Triple Buffering", config.enableTripleBuffering, v -> config.enableTripleBuffering = v);
    tog("Burst Thread Mode", config.enableBurstThreadMode, v -> config.enableBurstThreadMode = v);
    tog("Sacrifice TPS for FPS", config.prioritizeFpsOverTps, v -> config.prioritizeFpsOverTps = v);
    nfo("FPS Priority Mode", config.prioritizeFpsOverTps ? "Simulation Distance <= 5" : "Off");
    sec("Memory");
    sld("Max GPU Memory (MB)", 512, 4096, 512, pendingMaxMemMb, v -> pendingMaxMemMb = (int) (float) v);
    tog("Memory Fallback", config.enableMemoryPressureFallback, v -> config.enableMemoryPressureFallback = v);
    sec("Runtime");
    Runtime rt = Runtime.getRuntime();
    long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    long max = rt.maxMemory() / (1024 * 1024);
    nfo("Heap Usage", used + " / " + max + " MB");
  }

  private void buildAdvanced() {
    sec("Metal Features");
    tog("Argument Buffers", config.enableArgumentBuffers, v -> config.enableArgumentBuffers = v);
    tog("Indirect CMD Buffers", config.enableIndirectCommandBuffers, v -> config.enableIndirectCommandBuffers = v);
    tog("Mesh Shaders", config.enableMeshShaders, v -> config.enableMeshShaders = v);
    tog("Programmable Blending", config.enableProgrammableBlending, v -> config.enableProgrammableBlending = v);
    tog("Memoryless Targets", config.enableMemorylessTargets, v -> config.enableMemorylessTargets = v);
  }

  private void buildLod() {
    sec("Level of Detail");
    nfo("Status", "Temporarily disabled");
    nfo("Reason", "LOD artifacts are removed by forcing full-detail chunk meshes");
    nfo("Chunk Loading", "Burst mode and backlog submission stay active without LOD");
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

  private void nfo(String label, String value) {
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
    int h = 0;
    for (Row r : rows)
      h += r.h() + r.gap();
    return h;
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
  }

  private static void fr(GuiGraphicsExtractor ctx, int x, int y, int w, int h, int col) {
    ctx.fill(x, y, x + w, y + h, col);
  }

  private static int cl(int v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, v));
  }
}
