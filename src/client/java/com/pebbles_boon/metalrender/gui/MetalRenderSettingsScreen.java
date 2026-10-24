package com.pebbles_boon.metalrender.gui;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.gui.components.MetalOptionSlider;
import com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.util.ARGB;

public class MetalRenderSettingsScreen extends Screen {
    private static final Identifier LOGO = Identifier.fromNamespaceAndPath("metalrender", "textures/metalrender_transparent.png");

    private static final class GlassBackdrop implements AutoCloseable {
        private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("metalrender", "settings_glass");
        private static final RenderPipeline SURFACE = pipeline(RenderPipelines.GUI_TEXTURED, "glass");
        private static final RenderPipeline BLUR = pipeline(RenderPipelines.ENTITY_OUTLINE_BLIT, "glass_blur");
        private GpuTexture scene, blurred;
        private GpuTextureView sceneView, blurredView;
        private final AbstractTexture textureAlias = new AbstractTexture() {
            @Override
            public GpuTexture getTexture() { return blurred; }

            @Override
            public GpuTextureView getTextureView() { return blurredView; }

            @Override
            public GpuSampler getSampler() {
                return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            }

            @Override
            public void close() {
            }
        };

        private static RenderPipeline pipeline(RenderPipeline source, String name) {
            var builder = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath("metalrender", "pipeline/" + name))
                    .withVertexShader(source.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX))
                    .withFragmentShader(source.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.FRAGMENT))
                    .withShaderDefine(name.equals("glass") ? "METALRENDER_GLASS" : "METALRENDER_GLASS_BLUR")
                    .withPrimitiveTopology(source.getPrimitiveTopology())
                    .withColorTargetState(source.getColorTargetStates().get(0))
                    .withDepthStencilState(java.util.Optional.ofNullable(source.getDepthStencilState())).withCull(false);
            if (!source.getVertexFormatBindings().isEmpty())
                builder.withVertexBinding(0, source.getVertexFormatBinding(0));
            var layout = com.mojang.renderpearl.api.pipeline.BindGroupLayout.builder();
            var existing = com.mojang.renderpearl.api.pipeline.BindGroupLayout.flattenUniforms(source.getBindGroupLayouts()).stream()
                    .filter(u -> u.type() == com.mojang.renderpearl.api.pipeline.UniformType.COMBINED_IMAGE_SAMPLER)
                    .map(com.mojang.renderpearl.api.pipeline.BindGroupLayout.UniformDescription::name).toList();
            String sampler = name.equals("glass") ? "Sampler0" : "InSampler";
            if (!existing.contains(sampler))
                layout.withUniform(sampler, com.mojang.renderpearl.api.pipeline.UniformType.COMBINED_IMAGE_SAMPLER);
            builder.withBindGroupLayout(layout.build());
            for (var other : source.getBindGroupLayouts())
                builder.withBindGroupLayout(other);
            RenderPipeline built = builder.build();
            com.pebbles_boon.metalrender.backend.metal.FamilyTable.register(built);
            return built;
        }

        void prepare() {
            var mc = Minecraft.getInstance();
            if (!MetalRenderClient.isBackendMode() || mc.level == null)
                return;
            var main = mc.gameRenderer.mainRenderTarget();
            int width = Math.max(1, (main.width + 3) / 4);
            int height = Math.max(1, (main.height + 3) / 4);
            if (scene != null && scene.getWidth(0) == width && scene.getHeight(0) == height)
                return;
            close();
            var device = RenderSystem.getDevice();
            int usage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
            scene = device.createTexture("Settings glass scene", usage, main.getColorTexture().getFormat(), width, height, 1, 1);
            blurred = device.createTexture("Settings glass frost", usage, main.getColorTexture().getFormat(), width, height, 1, 1);
            sceneView = device.createTextureView(scene);
            blurredView = device.createTextureView(blurred);
            mc.getTextureManager().register(TEXTURE, textureAlias);
        }

        void capture() {
            if (sceneView == null)
                return;
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            try (var pass = encoder.createRenderPass(() -> "Settings glass downsample", sceneView, java.util.Optional.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(BLUR));
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("InSampler", Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTextureView(), sampler);
                pass.draw(3, 1, 0, 0);
            }
            try (var pass = encoder.createRenderPass(() -> "Settings glass frost", blurredView, java.util.Optional.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(BLUR));
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("InSampler", sceneView, sampler);
                pass.draw(3, 1, 0, 0);
            }
        }

        boolean draw(GuiGraphicsExtractor ctx, int x, int y, int width, int height, int radius) {
            return drawLens(ctx, x, y, width, height, radius, true);
        }

        boolean drawLens(GuiGraphicsExtractor ctx, int x, int y, int width, int height, int radius, boolean pane) {
            if (blurredView == null)
                return false;
            int scale = Math.max(1, (Math.max(width, height) + 254) / 255);
            int metadata = ((scale + (pane ? 128 : 0)) << 24)
                    | (Math.min(255, Math.round((float) width / scale)) << 16)
                    | (Math.min(255, Math.round((float) height / scale)) << 8)
                    | Math.min(255, radius);
            ctx.blit(SURFACE, TEXTURE, x, y, 0.0f, 0.0f, width, height, width, height, metadata);
            return true;
        }

        @Override
        public void close() {
            if (blurredView != null)
                Minecraft.getInstance().getTextureManager().release(TEXTURE);
            if (sceneView != null) sceneView.close();
            if (blurredView != null) blurredView.close();
            if (scene != null) scene.close();
            if (blurred != null) blurred.close();
            sceneView = null;
            blurredView = null;
            scene = null;
            blurred = null;
        }
    }

    private static int C_BG_TOP = 0xFF0B0B0D;
    private static int C_BG_BOTTOM = 0xFF131318;
    private static int C_SCRIM = 0x80000000;
    private static int C_PANEL = 0x70222230;
    private static int C_PANEL_BORDER = 0x1AFFFFFF;
    private static int C_PANEL_INNER_STROKE = 0x40FFFFFF;
    private static int C_PANEL_HIGHLIGHT = 0x1AFFFFFF;
    private static int C_HEADER = 0x0016161B;
    private static int C_HEADER_GRADIENT = 0x001E1E26;
    private static int C_TAB_BAR = 0x00000000;
    private static int C_TAB_PILL_BG = 0x901E1E26;
    private static int C_TAB_PILL_ACTIVE = 0xFFFFFFFF;
    private static int C_TAB_PILL_HOVER = 0xFF2A2A32;
    private static int C_TAB_ACTIVE = 0xFF007AFF;
    private static int C_TAB_HOVER = 0xFF2A2A32;
    private static int C_TAB_TEXT = 0xFF9B9BA3;
    private static int C_TAB_TEXT_ACTIVE = 0xFF0B0B0D;
    private static int C_CARD = 0x662A2A32;
    private static int C_CARD_HOVER = 0x7730303A;
    private static int C_CARD_ACTIVE = 0xFF2E2E38;
    private static int C_CARD_STROKE = 0x20FFFFFF;
    private static int C_DIVIDER = 0x143A3A44;
    private static int C_TEXT_PRI = 0xFFFFFFFF;
    private static int C_TEXT_SEC = 0xFF9A9AA3;
    private static int C_TEXT_ACCENT = 0xFF3FA9FF;
    private static int C_VAL_ON = 0xFF34C759;
    private static int C_VAL_OFF = 0xFFFF453A;
    private static int C_PILL_ON = 0xFF007AFF;
    private static int C_PILL_ON_GRAD = 0xFF3FA9FF;
    private static int C_PILL_OFF = 0xFF3A3A40;
    private static int C_PILL_OFF_STROKE = 0xFF4A4A52;
    private static int C_ACCENT = 0xFF007AFF;
    private static int C_ACCENT_GRAD = 0xFF3FA9FF;
    private static int C_ACCENT_TEXT = 0xFF19232D;
    private static int C_SCROLLTHUMB = 0xFF5A5A66;
    private static int C_SCROLLTHUMB_HOVER = 0xFF007AFF;
    private static int C_GLASS_DOT_ON = 0xFF34C759;
    private static int C_GLASS_DOT_OFF = 0xFF48484F;

    private static final int PANEL_W = 700;
    private static final int PANEL_H = 460;
    private static final int HDR_H = 52;
    private static final int TAB_H = 44;
    private static final int FOOT_H = 16;
    private static final int CARD_H = 48;
    private static final int CARD_GAP = 8;
    private static final int SEC_H = 36;
    private static final int HPAD = 18;
    private static final int SIDEBAR_W = 144;
    private static final int SIDEBAR_GAP = 12;
    private static final int PILL_W = 44;
    private static final int PILL_H = 24;
    private static final int SLIDER_W = 130;
    private static final int SLIDER_H = 14;
    private static final int FPS_LIMIT_MAX = 240;
    private static final int FPS_LIMIT_UNLIMITED = 241;

    private static final String[] TABS = {
            "Video", "MetalRender", "Quality", "Performance", "Advanced", "Themes"
    };
    private static final String[] TAB_ICONS = {
            "\u25A3", "\u2605", "\u2736", "\u26A1", "\u2699", "\u2630"
    };
    private static UiTheme activeTheme = UiTheme.LIQUID_GLASS;
    private static String customHexBg = "141C2A";
    private static String customHexPanel = "1E242E";
    private static String customHexAccent = "5AC8FF";
    private static String customHexText = "E8F0FF";

    public static UiTheme getActiveTheme() {
        return activeTheme;
    }

    private EditBox bgHexBox, panelHexBox, accentHexBox, textHexBox;

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
    private float animSideY = -1;
    private long sidebarAnimationTime;
    private final GlassBackdrop glassBackdrop = new GlassBackdrop();
    private final Map<String, ToggleMotion> toggleMotions = new HashMap<>();

    private static final class ToggleMotion {
        float progress;
        long time;

        ToggleMotion(boolean on, long now) {
            progress = on ? 1.0f : 0.0f;
            time = now;
        }
    }
    private int totalContentHeight = 0;

    private int px, py, pw, ph;
    private int cx, cy, cw, ch;
    private int sx, sy, sw, sh;

    private static void applyTheme(UiTheme t) {
        if (t == null)
            t = UiTheme.LIQUID_GLASS;
        activeTheme = t;
        if (t == UiTheme.CUSTOM) {
            int bg = UiTheme.parseHex(customHexBg, 0xFF141C2A);
            int panel = UiTheme.parseHex(customHexPanel, 0xFF1E242E);
            int acc = UiTheme.parseHex(customHexAccent, 0xFF5AC8FF);
            int text = UiTheme.parseHex(customHexText, 0xFFE8F0FF);
            int accGrad = lighten(acc, 0.18f);
            int panelHi = addAlpha(panel, 0x22);
            C_BG_TOP = bg;
            C_BG_BOTTOM = darken(bg, 0.85f);
            C_SCRIM = 0x60000000;
            C_PANEL = addAlpha(panel, 0xCC);
            C_PANEL_BORDER = 0x40FFFFFF;
            C_PANEL_INNER_STROKE = 0x40FFFFFF;
            C_PANEL_HIGHLIGHT = panelHi;
            C_HEADER = 0x0016161B;
            C_HEADER_GRADIENT = 0x001E1E26;
            C_TAB_PILL_BG = darken(panel, 0.92f);
            C_TAB_PILL_ACTIVE = 0xFFFFFFFF;
            C_TAB_PILL_HOVER = lighten(panel, 0.12f);
            C_TAB_TEXT = 0xFF9AA8B8;
            C_TAB_TEXT_ACTIVE = 0xFF0B0B0D;
            C_CARD = addAlpha(lighten(panel, 0.08f), 0xFF);
            C_CARD_HOVER = lighten(panel, 0.14f);
            C_CARD_STROKE = 0x30FFFFFF;
            C_DIVIDER = 0x18FFFFFF;
            C_TEXT_PRI = text;
            C_TEXT_SEC = addAlpha(text, 0xB0);
            C_TEXT_ACCENT = acc;
            C_VAL_ON = 0xFF34C759;
            C_VAL_OFF = 0xFFFF6B6B;
            C_PILL_ON = acc;
            C_PILL_ON_GRAD = accGrad;
            C_PILL_OFF = 0xFF2A3442;
            C_PILL_OFF_STROKE = 0xFF4A5A6E;
            C_ACCENT = acc;
            C_ACCENT_TEXT = accentText(acc);
            C_ACCENT_GRAD = accGrad;
            C_SCROLLTHUMB = 0xFF6A7A8E;
            C_SCROLLTHUMB_HOVER = acc;
            C_GLASS_DOT_ON = acc;
            C_GLASS_DOT_OFF = 0xFF3A444E;
            return;
        }
        C_BG_TOP = t.bgTop;
        C_BG_BOTTOM = t.bgBottom;
        C_SCRIM = t.scrim;
        C_PANEL = t.panel;
        C_PANEL_BORDER = t.panelBorder;
        C_PANEL_INNER_STROKE = t.panelInnerStroke;
        C_PANEL_HIGHLIGHT = t.panelHighlight;
        C_HEADER = t.header;
        C_HEADER_GRADIENT = t.headerGrad;
        C_TAB_PILL_BG = t.tabPillBg;
        C_TAB_PILL_ACTIVE = t == UiTheme.LIQUID_GLASS ? 0x6855789E : t.tabPillActive;
        C_TAB_PILL_HOVER = t.tabPillHover;
        C_TAB_TEXT = t.textSec;
        C_TAB_TEXT_ACTIVE = t.textPri;
        C_CARD = t.card;
        C_CARD_HOVER = t.cardHover;
        C_CARD_STROKE = t.cardStroke;
        C_DIVIDER = t.divider;
        C_TEXT_PRI = t.textPri;
        C_TEXT_SEC = t.textSec;
        C_TEXT_ACCENT = t.textAccent;
        C_VAL_ON = t.valOn;
        C_VAL_OFF = t.valOff;
        C_PILL_ON = t.pillOn;
        C_PILL_ON_GRAD = t.pillOnGrad;
        C_PILL_OFF = t.pillOff;
        C_PILL_OFF_STROKE = t.pillOffStroke;
        C_ACCENT = t.accent;
        C_ACCENT_TEXT = accentText(t.accent);
        C_ACCENT_GRAD = t.accentGrad;
        C_SCROLLTHUMB = t.scrollThumb;
        C_SCROLLTHUMB_HOVER = t.scrollThumbHover;
        C_GLASS_DOT_ON = t.glassDotOn;
        C_GLASS_DOT_OFF = t.glassDotOff;
    }

    private static int accentText(int color) {
        float luminance = 0.2126f * ARGB.srgbToLinearChannel(ARGB.red(color))
                + 0.7152f * ARGB.srgbToLinearChannel(ARGB.green(color))
                + 0.0722f * ARGB.srgbToLinearChannel(ARGB.blue(color));
        return luminance > 0.179f ? 0xFF19232D : 0xFFF9FCFF;
    }

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
    private boolean initialGreedyMid;
    private boolean initialGreedyFar;
    private boolean initialSmoothLighting;

    private final List<Row> rows = new ArrayList<>();

    private enum RT {
        SECTION, TOGGLE, CYCLE, INFO, VANILLA, SLIDER, THEME
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

        UiTheme theme;

        int h() {
            if (type == RT.THEME)
                return 64;
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
        customHexBg = config.customHexBg;
        customHexPanel = config.customHexPanel;
        customHexAccent = config.customHexAccent;
        customHexText = config.customHexText;
        applyTheme(UiTheme.fromString(config.uiTheme));
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
        initialGreedyMid = config.enableGreedyMid;
        initialGreedyFar = config.enableGreedyFar;
        layout();
        rebuild();
    }

    private void layout() {
        pw = Math.min(PANEL_W, width - 24);
        ph = Math.min(PANEL_H, height - 24);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        sx = px + HPAD;
        sy = py + HDR_H + 8;
        sw = SIDEBAR_W;
        sh = ph - HDR_H - FOOT_H - 16;
        cx = sx + sw + SIDEBAR_GAP;
        cy = py + HDR_H + 8;
        cw = px + pw - HPAD - cx;
        ch = ph - HDR_H - FOOT_H - 16;
        if (cw < 220) {
            sw = 140;
            sx = px + HPAD;
            cx = sx + sw + 8;
            cw = px + pw - HPAD - cx;
        }
    }

    private static final int HEX_AREA_H = 52;
    private static final int HEX_APPLY_W = 54;

    private int contentH() {
        if (selectedTab == 5) {
            return Math.max(40, ch - HEX_AREA_H);
        }
        return ch;
    }

    private int hexTop() {
        return cy + contentH();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mx, int my, float delta) {
        var font = getFont();
        if (activeTheme == UiTheme.LIQUID_GLASS)
            glassBackdrop.prepare();

        drawBackground(ctx);
        drawPanel(ctx);
        drawHeader(ctx, font);
        renderSidebar(ctx, mx, my);

        int ech = contentH();
        ctx.enableScissor(cx, cy, cx + cw, cy + ech);
        posSliders();
        renderRows(ctx, mx, my);
        ctx.disableScissor();

        drawScrollFades(ctx);
        renderScrollbar(ctx, mx, my);
        drawFooter(ctx, font);
        if (selectedTab == 5)
            drawCustomHexLabels(ctx, font);

        super.extractRenderState(ctx, mx, my, delta);
    }

    @Override
    protected void extractBlurredBackground(GuiGraphicsExtractor ctx) {
    }

    @Override
    protected void extractMenuBackground(GuiGraphicsExtractor ctx) {
    }

    public void captureGlassBackdrop() {
        if (activeTheme == UiTheme.LIQUID_GLASS)
            glassBackdrop.capture();
    }

    @Override
    public void removed() {
        glassBackdrop.close();
        super.removed();
    }

    private void drawCustomHexLabels(GuiGraphicsExtractor ctx, net.minecraft.client.gui.Font font) {
        if (bgHexBox == null)
            return;
        int gap = 8;
        int top = hexTop();
        int labelY = top + 6;
        int lineY = top + 2;
        int lineCol = activeTheme.isLight ? 0x14000000 : 0x14FFFFFF;
        ctx.fill(cx, lineY, cx + cw, lineY + 1, lineCol);
        int labelCol = C_TEXT_SEC;
        int[] xs = hexBoxXs();
        int boxW = hexBoxW();
        String[] names = { "BG", "Panel", "Accent", "Text" };
        for (int i = 0; i < 4; i++) {
            String nm = truncateLabel(font, names[i], boxW - 4);
            ctx.text(font, Component.literal(nm), xs[i] + 2, labelY, labelCol, false);
        }
    }

    private int hexBoxW() {
        int gap = 8;
        int avail = cw - HEX_APPLY_W - gap;
        int boxW = (avail - gap * 3) / 4;
        return Math.max(40, boxW);
    }

    private int[] hexBoxXs() {
        int gap = 8;
        int boxW = hexBoxW();
        int x0 = cx;
        return new int[] { x0, x0 + boxW + gap, x0 + (boxW + gap) * 2, x0 + (boxW + gap) * 3 };
    }

    private void drawBackground(GuiGraphicsExtractor ctx) {
        if (activeTheme == UiTheme.LIQUID_GLASS) {
            ctx.fill(0, 0, width, height, 0x100A1018);
            return;
        }
        ctx.fillGradient(0, 0, width, height, C_BG_TOP, C_BG_BOTTOM);
        ctx.fill(0, 0, width, height, C_SCRIM);
        int corner = Math.max(width, height) / 3;
        if (activeTheme.isLight) {
            ctx.fillGradient(0, 0, corner, corner, 0x10C9A090, 0x00000000);
            ctx.fillGradient(width - corner, 0, width, corner, 0x10C9A090, 0x00000000);
            ctx.fillGradient(0, height - corner, corner, height, 0x10C9A090, 0x00000000);
            ctx.fillGradient(width - corner, height - corner, width, height, 0x10C9A090, 0x00000000);
        } else {
            ctx.fillGradient(0, 0, corner, corner, 0x4007070A, 0x00131318);
            ctx.fillGradient(width - corner, 0, width, corner, 0x4007070A, 0x00131318);
            ctx.fillGradient(0, height - corner, corner, height, 0x4007070A, 0x00131318);
            ctx.fillGradient(width - corner, height - corner, width, height, 0x4007070A, 0x00131318);
        }
        int cx2 = width / 2, cy2 = height / 2;
        if (activeTheme == UiTheme.PASTEL) {
            ctx.fillGradient(cx2 - pw, cy2 - 40, cx2 + pw, cy2 + 40, 0x06C9A090, 0x00000000);
        }
    }

    private void drawPanel(GuiGraphicsExtractor ctx) {
        boolean light = activeTheme.isLight;
        if (activeTheme == UiTheme.LIQUID_GLASS) {
            int pr = 22;
            fillRounded(ctx, px, py + 2, pw, ph, 0x28000000, pr);
            if (!glassBackdrop.draw(ctx, px, py, pw, ph, pr)) {
                fillRounded(ctx, px, py, pw, ph, C_PANEL, pr);
                drawRoundedOutline(ctx, px, py, pw, ph, 0x48FFFFFF, pr);
            }
            return;
        }
        fillRounded(ctx, px - 2, py + 3, pw + 4, ph + 2, light ? 0x1450473D : 0x30000000, 22);
        fillRounded(ctx, px, py, pw, ph, C_PANEL, 20);
        drawRoundedOutline(ctx, px, py, pw, ph, C_PANEL_BORDER, 20);
        ctx.fill(px + 20, py + 1, px + pw - 20, py + 2, C_PANEL_INNER_STROKE);
    }

    private void drawHeader(GuiGraphicsExtractor ctx, net.minecraft.client.gui.Font font) {
        int lh = HDR_H - 14;
        int lw = lh * 1374 / 1145;
        ctx.blit(RenderPipelines.GUI_TEXTURED, LOGO, px + 14, py + 7, 0.0f, 0.0f, lw, lh, lw, lh, 0xFFFFFFFF);
        int tx = px + 14 + lw + 8;
        int ty = py + 16;
        String gpu = MetalHardwareChecker.getDeviceName();
        boolean gpuOk = gpu != null && !gpu.isEmpty() && !gpu.equals("Unknown GPU");
        String gpuLabel = gpuOk ? gpu : "GPU";
        if (font.width(gpuLabel) > 90) {
            gpuLabel = gpuLabel.substring(0, Math.min(gpuLabel.length(), 14)) + "\u2026";
        }

        int dotsW = 10 + font.width("Sodium") + 14 + 10 + font.width("Metal") + 14
                + 10 + font.width(gpuLabel) + 8;
        int maxTitleW = pw - (tx - px) - 18 - dotsW;
        if (maxTitleW < 60) {
            maxTitleW = 60;

            gpuLabel = "GPU";
            dotsW = 10 + font.width("Sodium") + 14 + 10 + font.width("Metal") + 14
                    + 10 + font.width(gpuLabel) + 8;
            maxTitleW = Math.max(40, pw - (tx - px) - 18 - dotsW);
        }
        String title = "MetalRender";
        String version = "Settings  \u2022  v2.0.3"; 
        int titleW = font.width(title);
        int versionW = font.width(version);
        if (titleW + 6 + versionW > maxTitleW) {
            int versionMax = Math.max(20, maxTitleW - titleW - 6);
            version = truncateLabel(font, version, versionMax);
            versionW = font.width(version);
            if (titleW + 6 + versionW > maxTitleW) {
                title = truncateLabel(font, title, Math.max(20, maxTitleW - versionW - 6));
                titleW = font.width(title);
            }
        }
        ctx.text(font, Component.literal(title), tx, ty, C_TEXT_PRI, false);
        ctx.text(font, Component.literal(version), tx + titleW + 6, ty, C_TEXT_SEC, false);
        int dotY = py + HDR_H - 18;
        int dotX = px + pw - 18;
        boolean metalOk = MetalRenderClient.isMetalAvailable();
        boolean sodiumOk = MetalRenderClient.isSodiumLoaded();
        dotX = drawStatusDot(ctx, font, dotX, dotY, sodiumOk ? C_GLASS_DOT_ON : C_GLASS_DOT_OFF, "Sodium");
        dotX = drawStatusDot(ctx, font, dotX, dotY, metalOk ? C_GLASS_DOT_ON : C_GLASS_DOT_OFF, "Metal");
        dotX = drawStatusDot(ctx, font, dotX, dotY, gpuOk ? C_GLASS_DOT_ON : C_GLASS_DOT_OFF, gpuLabel);
        int div = activeTheme.isLight ? 0x14000000 : 0x14FFFFFF;
        ctx.fill(px + 12, py + HDR_H - 1, px + pw - 12, py + HDR_H, div);
    }

    private int drawStatusDot(GuiGraphicsExtractor ctx, net.minecraft.client.gui.Font font, int rightX, int y,
            int dotCol, String label) {
        int lw = font.width(label);
        String display = label;
        if (lw > 90) {
            display = label.substring(0, Math.min(label.length(), 14)) + "\u2026";
            lw = font.width(display);
        }
        int textX = rightX - lw;
        int dotX = textX - 10;
        ctx.text(font, Component.literal(display), textX, y - 3, C_TEXT_SEC, false);
        ctx.fill(dotX, y, dotX + 6, y + 6, dotCol);
        ctx.fill(dotX + 1, y - 1, dotX + 5, y, activeTheme.isLight ? 0x30FFFFFF : 0x55FFFFFF);
        return dotX - 14;
    }

    private void drawFooter(GuiGraphicsExtractor ctx, net.minecraft.client.gui.Font font) {
        int fy = py + ph - FOOT_H;
        int div = activeTheme.isLight ? 0x10000000 : 0x0FFFFFFF;
        ctx.fill(px + 12, fy, px + pw - 12, fy + 1, div);
    }

    private void renderSidebar(GuiGraphicsExtractor ctx, int mx, int my) {
        var font = getFont();
        int btnH = sidebarButtonHeight();
        int gap = sidebarButtonGap();
        int radius = activeTheme.isLight ? 12 : 10;
        fillRounded(ctx, sx, sy, sw, sh, C_TAB_PILL_BG, radius);
        drawRoundedOutline(ctx, sx, sy, sw, sh, activeTheme.isLight ? 0x14000000 : 0x14FFFFFF, radius);
        ctx.fill(sx + 8, sy + 1, sx + sw - 8, sy + 2, activeTheme.isLight ? 0x40FFFFFF : 0x1AFFFFFF);

        int targetY = sy + 8 + selectedTab * (btnH + gap);
        if (animSideY < 0)
            animSideY = targetY;
        long now = System.nanoTime();
        float elapsed = sidebarAnimationTime == 0 ? 0 : Math.min(0.05f, (now - sidebarAnimationTime) / 1_000_000_000f);
        sidebarAnimationTime = now;
        animSideY += (targetY - animSideY) * (1.0f - (float) Math.exp(-elapsed * 16.0f));

        fillRounded(ctx, sx + 4, (int) animSideY, sw - 8, btnH, C_TAB_PILL_ACTIVE, 8);
        ctx.fill(sx + 8, (int) animSideY + btnH - 1, sx + sw - 8, (int) animSideY + btnH,
                activeTheme.isLight ? 0x10000000 : 0x1A000000);

        for (int i = 0; i < TABS.length; i++) {
            int bx = sx + 4;
            int by = sy + 8 + i * (btnH + gap);
            int bw = sw - 8;
            boolean sel = i == selectedTab;
            boolean hov = !sel && mx >= bx && mx < bx + bw && my >= by && my < by + btnH;
            if (hov)
                fillRounded(ctx, bx, by, bw, btnH, C_TAB_PILL_HOVER, 8);
            String icon = i < TAB_ICONS.length ? TAB_ICONS[i] : "\u25CF";
            int iconCol = sel ? C_TAB_TEXT_ACTIVE : (hov ? C_TEXT_ACCENT : C_TEXT_SEC);
            ctx.text(font, Component.literal(icon), bx + 10, by + (btnH - 14) / 2, iconCol, false);
            int tc = sel ? C_TAB_TEXT_ACTIVE : (hov ? C_TEXT_ACCENT : C_TEXT_PRI);
            ctx.text(font, Component.literal(TABS[i]), bx + 26, by + (btnH - 9) / 2, tc, false);
            if (sel)
                ctx.text(font, Component.literal("\u203A"), bx + bw - 12, by + (btnH - 9) / 2, C_TAB_TEXT_ACTIVE,
                        false);
            if (i == TABS.length - 2) {
                int divY = by + btnH + gap / 2;
                int div = activeTheme.isLight ? 0x14000000 : 0x14FFFFFF;
                ctx.fill(sx + 12, divY, sx + sw - 12, divY + 1, div);
            }
        }
        int sep = activeTheme.isLight ? 0x14000000 : 0x14FFFFFF;
        ctx.fill(sx + sw + SIDEBAR_GAP / 2, sy, sx + sw + SIDEBAR_GAP / 2 + 1, sy + sh, sep);
    }

    private void renderTabs(GuiGraphicsExtractor ctx, int mx, int my) {
        renderSidebar(ctx, mx, my);
    }

    private int sidebarButtonGap() {
        return sh < 240 ? 4 : 6;
    }

    private int sidebarButtonHeight() {
        return Math.max(16, Math.min(36, (sh - 16 - (TABS.length - 1) * sidebarButtonGap()) / TABS.length));
    }

    private void fillRounded(GuiGraphicsExtractor ctx, int x, int y, int w, int h, int col, int r) {
        if (w <= 0 || h <= 0)
            return;
        fillGlassRounded(ctx, x, y, w, h, col, r);
    }

    private void drawRoundedOutline(GuiGraphicsExtractor ctx, int x, int y, int w, int h, int col, int r) {
        if (r > 1 && w > 0 && h > 0) {
            int radius = Math.min(r, Math.min(w, h) / 2);
            for (int row = 0; row < radius; row++) {
                float outer = glassInset(radius, row);
                float inner = row == 0 ? w / 2.0f : 1.0f + glassInset(radius - 1, row - 1);
                fillGlassSpan(ctx, x, y + row, outer, inner, col);
                fillGlassSpan(ctx, x, y + row, w - inner, w - outer, col);
                fillGlassSpan(ctx, x, y + h - row - 1, outer, inner, col);
                fillGlassSpan(ctx, x, y + h - row - 1, w - inner, w - outer, col);
            }
            ctx.fill(x, y + radius, x + 1, y + h - radius, col);
            ctx.fill(x + w - 1, y + radius, x + w, y + h - radius, col);
            return;
        }
        ctx.fill(x + r, y, x + w - r, y + 1, col);
        ctx.fill(x + r, y + h - 1, x + w - r, y + h, col);
        ctx.fill(x, y + r, x + 1, y + h - r, col);
        ctx.fill(x + w - 1, y + r, x + w, y + h - r, col);
    }

    private static float glassInset(int radius, int row) {
        float distance = Math.max(0, radius - row - 0.5f);
        return radius - (float) Math.sqrt(Math.max(0, radius * radius - distance * distance));
    }

    private static void fillGlassSpan(GuiGraphicsExtractor ctx, int x, int y, float from, float to, int col) {
        if (to <= from)
            return;
        int left = (int) Math.floor(from);
        int right = (int) Math.ceil(to) - 1;
        if (right > left + 1)
            ctx.fill(x + left + 1, y, x + right, y + 1, col);
        int alpha = col >>> 24;
        int leftAlpha = Math.round(alpha * Math.max(0, Math.min(left + 1.0f, to) - from));
        ctx.fill(x + left, y, x + left + 1, y + 1, (col & 0x00FFFFFF) | (leftAlpha << 24));
        if (right != left) {
            int rightAlpha = Math.round(alpha * (to - right));
            ctx.fill(x + right, y, x + right + 1, y + 1, (col & 0x00FFFFFF) | (rightAlpha << 24));
        }
    }

    public static void fillGlassRounded(GuiGraphicsExtractor ctx, int x, int y, int w, int h, int col, int r) {
        if (w <= 0 || h <= 0)
            return;
        int radius = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        for (int row = 0; row < radius; row++) {
            float inset = glassInset(radius, row);
            fillGlassSpan(ctx, x, y + row, inset, w - inset, col);
            fillGlassSpan(ctx, x, y + h - row - 1, inset, w - inset, col);
        }
        ctx.fill(x, y + radius, x + w, y + h - radius, col);
    }

    public static void drawGlassLens(GuiGraphicsExtractor ctx, int x, int y, int w, int h, int radius) {
        if (Minecraft.getInstance().gui.screen() instanceof MetalRenderSettingsScreen settings
                && settings.glassBackdrop.drawLens(ctx, x, y, w, h, radius, false))
            return;
        fillGlassRounded(ctx, x, y, w, h, 0x30FFFFFF, radius);
    }

    private int sliderWidthFor(Row r) {
        int maxByCard = r.layoutW - 14 - 60 - 16 - 70 - 16;
        int w = Math.min(SLIDER_W, maxByCard);
        return Math.max(70, Math.min(SLIDER_W, w));
    }

    private void renderRows(GuiGraphicsExtractor ctx, int mx, int my) {
        int ech = contentH();
        maxScroll = Math.max(0, totalH() - ech);
        scrollOffset = cl(scrollOffset, 0, maxScroll);
        hoverRowIndex = -1;
        int idx = 0;
        for (Row r : rows) {
            int screenX = cx + r.layoutX;
            int screenY = cy - scrollOffset + r.layoutY;
            r.renderY = screenY;
            boolean visible = screenY + r.h() >= cy && screenY < cy + ech;
            if (visible && my >= cy && my < cy + ech
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

        if (r.type == RT.THEME) {
            UiTheme th = r.theme;
            boolean sel = th == activeTheme;
            int thH = r.h();
            int thRadius = th.cardRadius;
            int baseCol = sel ? C_CARD_HOVER : (hovered ? C_CARD_HOVER : C_CARD);
            fillRounded(ctx, x, y, w, thH, baseCol, thRadius);
            drawRoundedOutline(ctx, x, y, w, thH, sel ? C_ACCENT : C_CARD_STROKE, thRadius);
            ctx.fill(x + thRadius, y + 1, x + w - thRadius, y + 2, th.isLight ? 0x30FFFFFF : 0x18FFFFFF);
            if (sel) {
                ctx.fill(x + 1, y + 10, x + 3, y + thH - 10, C_ACCENT);
                ctx.fillGradient(x + 3, y + 10, x + 9, y + thH - 10, 0x22007AFF, 0x00007AFF);
            }
            if (hovered)
                fillRounded(ctx, x + 1, y + 1, w - 2, thH - 2, 0x0DFFFFFF, thRadius - 1);

            int sw = 48, swX = x + 14, swY = y + (thH - sw) / 2;
            int swR = th == UiTheme.PASTEL ? 16 : (th == UiTheme.LIQUID_GLASS ? 18 : (th == UiTheme.CUSTOM ? 12 : 10));
            int effPanel = th.panel;
            int effBgTop = th.bgTop;
            int effBgBottom = th.bgBottom;
            int effAccent = th.accent;
            int effAccentGrad = th.accentGrad;
            boolean effLight = th.isLight;
            if (th == UiTheme.CUSTOM) {
                int cBg = UiTheme.parseHex(customHexBg, 0xFF141C2A);
                int cPanel = UiTheme.parseHex(customHexPanel, 0xFF1E242E);
                int cAcc = UiTheme.parseHex(customHexAccent, 0xFF5AC8FF);
                effPanel = 0xFF000000 | (cPanel & 0x00FFFFFF);
                effBgTop = 0xFF000000 | (cBg & 0x00FFFFFF);
                effBgBottom = darken(effBgTop, 0.88f);
                effAccent = 0xFF000000 | (cAcc & 0x00FFFFFF);
                effAccentGrad = lighten(effAccent, 0.18f);
                effLight = false;
                int rr = (cBg >> 16) & 0xFF, gg = (cBg >> 8) & 0xFF, bb = cBg & 0xFF;
                int luma = (rr * 299 + gg * 587 + bb * 114) / 1000;
                if (luma > 160)
                    effLight = true;
            }
            fillRounded(ctx, swX, swY, sw, sw, effPanel, swR);
            ctx.fillGradient(swX + 2, swY + 2, swX + sw - 2, swY + sw - 2, effBgTop, effBgBottom);
            ctx.fill(swX + 6, swY + 2, swX + sw - 6, swY + 4, effLight ? 0x25FFFFFF : 0x35FFFFFF);
            int pillW = 28, pillH = 12, pillX = swX + (sw - pillW) / 2, pillY = swY + sw - 16;
            fillRounded(ctx, pillX, pillY, pillW, pillH, effAccent, 6);
            ctx.fillGradient(pillX + 1, pillY + 1, pillX + pillW - 1, pillY + pillH - 1, effAccent, effAccentGrad);
            if (th == UiTheme.PASTEL) {
                fillRounded(ctx, swX + 8, swY + 8, 14, 14, 0x28FFFFFF, 7);
                fillRounded(ctx, swX + 26, swY + 14, 8, 8, 0x1AFFFFFF, 4);
                fillRounded(ctx, swX + 12, swY + 28, 6, 6, 0x20FFEEDD, 3);
            } else if (th == UiTheme.LIQUID_GLASS) {
                ctx.fill(swX + 8, swY + 3, swX + sw - 8, swY + 7, 0x33FFFFFF);
            }

            String state = sel ? "Active" : "Select";
            int stateW = font.width(state) + 16;
            int stateX = x + w - 14 - stateW;
            int tx = swX + sw + 14;
            int maxThemeTextW = stateX - tx - 10;
            if (maxThemeTextW < 40)
                maxThemeTextW = 40;
            String themeName = truncateLabel(font, th.displayName, maxThemeTextW);
            String variant = switch (th) {
                case LIQUID_GLASS -> "Frosted \u00b7 cool highlights";
                case AMBER -> "Warm graphite \u00b7 gold";
                case PASTEL -> "Soft ivory \u00b7 sage";
                case CRIMSON -> "Deep graphite \u00b7 rose";
                case LAVENDER -> "Muted slate \u00b7 lilac";
                case CUSTOM -> "Your own colours";
            };
            String themeSub = truncateLabel(font, variant, maxThemeTextW);
            ctx.text(font, Component.literal(themeName), tx, y + 12, C_TEXT_PRI, false);
            ctx.text(font, Component.literal(themeSub), tx, y + 26, C_TEXT_SEC, false);
            if (th.isLight) {
                String lightBadge = truncateLabel(font, "LIGHT", maxThemeTextW);
                ctx.text(font, Component.literal(lightBadge), tx, y + 40, 0xFF9A7E70, false);
            }
            int stateY = y + (thH - 18) / 2;
            if (sel) {
                fillRounded(ctx, stateX, stateY, stateW, 18, C_ACCENT, 9);
                drawRoundedOutline(ctx, stateX, stateY, stateW, 18, 0x1AFFFFFF, 9);
                ctx.centeredText(font, Component.literal(state), stateX + stateW / 2, stateY + 4, C_ACCENT_TEXT);
            } else {
                fillRounded(ctx, stateX, stateY, stateW, 18, th.isLight ? 0x14E8D8C8 : 0x14FFFFFF, 9);
                drawRoundedOutline(ctx, stateX, stateY, stateW, 18, th.isLight ? 0x14000000 : 0x14FFFFFF, 9);
                ctx.centeredText(font, Component.literal(state), stateX + stateW / 2, stateY + 4,
                        hovered ? C_TEXT_ACCENT : C_TEXT_SEC);
            }
            return;
        }

        int cardRadius = activeTheme.cardRadius;
        if (activeTheme == UiTheme.PASTEL)
            cardRadius = 16;
        else if (activeTheme == UiTheme.LIQUID_GLASS)
            cardRadius = 18;
        else if (activeTheme.isLight)
            cardRadius = activeTheme.cardRadius;
        int baseCol = hovered ? C_CARD_HOVER : C_CARD;
        if (activeTheme == UiTheme.LIQUID_GLASS && glassBackdrop.drawLens(ctx, x, y, w, CARD_H, cardRadius, false)) {
            if (hovered)
                fillRounded(ctx, x + 1, y + 1, w - 2, CARD_H - 2, 0x08FFFFFF, cardRadius - 1);
        } else {
            fillRounded(ctx, x, y, w, CARD_H, baseCol, cardRadius);
            drawRoundedOutline(ctx, x, y, w, CARD_H, C_CARD_STROKE, cardRadius);
            ctx.fill(x + cardRadius, y + 1, x + w - cardRadius, y + 2,
                    activeTheme.isLight ? 0x20FFFFFF : 0x18FFFFFF);
        }
        if (hovered)
            fillRounded(ctx, x + 1, y + 1, w - 2, CARD_H - 2, 0x0DFFFFFF, cardRadius - 1);

        int labelCol = C_TEXT_PRI;
        int reserved = 80;
        if (r.type == RT.TOGGLE) {
            reserved = PILL_W + 24;
        } else if (r.type == RT.CYCLE) {
            String cv = r.value == null ? "" : r.value + "  \u203A";
            reserved = font.width(cv) + 24 + 16;
        } else if (r.type == RT.INFO || r.type == RT.VANILLA) {
            String vv = r.value == null ? "" : r.value;
            int vwCap = Math.min(font.width(vv) + 16, w / 2);
            reserved = vwCap + 16;
            if (font.width(vv) + 16 > w / 2) {
                r.value = truncateLabel(font, vv, w / 2 - 16);
            }
        } else if (r.type == RT.SLIDER) {
            String sv = r.slider != null ? r.slider.getMessage().getString() : "";
            int swCap = Math.min(font.width(sv) + 12, 70);
            reserved = sliderWidthFor(r) + 12 + swCap + 16;
        }
        int maxLabelW = w - 14 - reserved;
        if (maxLabelW < 32)
            maxLabelW = 32;
        String displayLabel = truncateLabel(font, r.label, maxLabelW);
        int labelX = x + 14;
        int labelY = y + (CARD_H - 9) / 2;
        ctx.text(font, Component.literal(displayLabel), labelX, labelY, labelCol, activeTheme == UiTheme.LIQUID_GLASS);

        int rx = x + w - 12;
        switch (r.type) {
            case TOGGLE -> {
                boolean on = "Enabled".equals(r.value);
                drawPill(ctx, rx - PILL_W, y + (CARD_H - PILL_H) / 2, on, r.label);
            }
            case CYCLE -> {
                int vw = font.width(r.value) + 24;
                fillRounded(ctx, rx - vw, y + 10, vw, CARD_H - 20,
                        ARGB.color(activeTheme.isLight ? 24 : 38, C_ACCENT), 7);
                drawRoundedOutline(ctx, rx - vw, y + 10, vw, CARD_H - 20,
                        ARGB.color(activeTheme.isLight ? 48 : 64, C_ACCENT), 7);
                String chev = r.value + "  \u203A";
                ctx.centeredText(font, Component.literal(chev), rx - vw / 2, y + (CARD_H - 9) / 2, C_TEXT_ACCENT);
            }
            case INFO -> {
                String v = r.value == null ? "" : r.value;
                int col = C_TEXT_SEC;
                int pillBg = activeTheme.isLight ? 0xFFEADFD3 : 0xFF1E1E26;
                if ("Enabled".equals(v) || "Yes".equals(v) || "Supported".equals(v) || "Installed".equals(v)) {
                    col = C_VAL_ON;
                    pillBg = activeTheme.isLight ? 0xFFE0EAD8 : 0xFF1A2E22;
                } else if ("Disabled".equals(v) || "No".equals(v) || "Not Available".equals(v)
                        || "Not Installed".equals(v)) {
                    col = C_VAL_OFF;
                    pillBg = activeTheme.isLight ? 0xFFF0D8D0 : 0xFF2E1E1E;
                } else if (!v.isEmpty()) {
                    col = C_TEXT_ACCENT;
                    pillBg = activeTheme.isLight ? 0xFFE8D8C8 : 0xFF1E2430;
                }
                int vw = Math.min(font.width(v) + 16, w / 2);
                if (vw > 8 && !v.isEmpty()) {
                    fillRounded(ctx, rx - vw, y + 10, vw, CARD_H - 20, pillBg, 7);
                    drawRoundedOutline(ctx, rx - vw, y + 10, vw, CARD_H - 20,
                            activeTheme.isLight ? 0x14C9A090 : 0x14FFFFFF, 7);
                    String dispV = truncateLabel(font, v, vw - 12);
                    ctx.centeredText(font, Component.literal(dispV), rx - vw / 2, y + (CARD_H - 9) / 2, col);
                }
            }
            case VANILLA -> {
                String v = r.value == null ? "" : r.value;
                if ("ON".equals(v) || "OFF".equals(v)) {
                    drawPill(ctx, rx - PILL_W, y + (CARD_H - PILL_H) / 2, "ON".equals(v), r.label);
                    break;
                }
                int col = "ON".equals(v) ? C_VAL_ON : ("OFF".equals(v) ? C_VAL_OFF : C_TEXT_ACCENT);
                int pillBg = "ON".equals(v) ? (activeTheme.isLight ? 0xFFE0EAD8 : 0xFF1A2E22)
                        : ("OFF".equals(v) ? (activeTheme.isLight ? 0xFFF0D8D0 : 0xFF2E1E1E)
                                : (activeTheme.isLight ? 0xFFE8D8C8 : 0xFF1E2430));
                int vw = Math.min(font.width(v) + 16, w / 2);
                fillRounded(ctx, rx - vw, y + 10, vw, CARD_H - 20, pillBg, 7);
                drawRoundedOutline(ctx, rx - vw, y + 10, vw, CARD_H - 20, activeTheme.isLight ? 0x14C9A090 : 0x14FFFFFF,
                        7);
                String dispV = truncateLabel(font, v, vw - 12);
                ctx.centeredText(font, Component.literal(dispV), rx - vw / 2, y + (CARD_H - 9) / 2, col);
            }
            case SLIDER -> {
                if (r.slider != null) {
                    int sliderW = sliderWidthFor(r);
                    String sv = r.slider.getMessage().getString();
                    String dispSv = truncateLabel(font, sv, 64);
                    int sw = font.width(dispSv) + 12;
                    int bg = activeTheme == UiTheme.LIQUID_GLASS ? 0x28354150
                            : (activeTheme.isLight ? 0xFFEADFD3 : 0xFF1E2430);
                    fillRounded(ctx, rx - sliderW - 10 - sw, y + 12, sw, CARD_H - 24, bg, 6);
                    if (activeTheme.isLight)
                        drawRoundedOutline(ctx, rx - sliderW - 10 - sw, y + 12, sw, CARD_H - 24, 0x14C9A090, 6);
                    ctx.text(font, Component.literal(dispSv), rx - sliderW - 10 - sw + 6, y + (CARD_H - 9) / 2,
                            C_TEXT_ACCENT, false);
                }
            }
            default -> {
            }
        }
    }

    private void drawPill(GuiGraphicsExtractor ctx, int x, int y, boolean on, String key) {
        long now = System.nanoTime();
        ToggleMotion motion = toggleMotions.get(key);
        if (motion == null) {
            motion = new ToggleMotion(on, now);
            toggleMotions.put(key, motion);
        }
        float elapsed = Math.min(0.05f, (now - motion.time) / 1_000_000_000f);
        motion.time = now;
        float target = on ? 1.0f : 0.0f;
        motion.progress += (target - motion.progress) * (1.0f - (float) Math.exp(-elapsed * 22.0f));
        if (Math.abs(target - motion.progress) < 0.002f)
            motion.progress = target;
        int trackX = x + 2;
        int trackY = y + 1;
        int trackW = PILL_W - 4;
        int trackH = PILL_H - 2;
        boolean glass = activeTheme == UiTheme.LIQUID_GLASS;
        int track = ARGB.srgbLerp(motion.progress, glass ? 0x243B4C60 : C_PILL_OFF,
                glass ? 0xC23ABB88 : C_PILL_ON);
        fillRounded(ctx, trackX, trackY, trackW, trackH, track, trackH / 2);
        drawRoundedOutline(ctx, trackX, trackY, trackW, trackH,
                activeTheme.isLight ? 0x1862584E : 0x30FFFFFF, trackH / 2);
        ctx.fill(trackX + 11, trackY + 1, trackX + trackW - 11, trackY + 2, 0x20FFFFFF);
        int knob = glass ? trackH - 2 : trackH - 4;
        int knobW = glass ? 26 : knob;
        int padding = glass ? 1 : 2;
        float thumbX = trackX + padding + motion.progress * (trackW - knobW - padding * 2);
        int knobX = (int) Math.floor(thumbX);
        int knobY = trackY + padding;
        ctx.pose().pushMatrix();
        ctx.pose().translate(thumbX - knobX, 0.0f);
        fillRounded(ctx, knobX - 1, knobY + 2, knobW + 2, knob + 1, 0x18000000, knob / 2 + 1);
        if (glass) {
            drawGlassLens(ctx, knobX, knobY, knobW, knob, knob / 2);
        } else {
            fillRounded(ctx, knobX, knobY, knob, knob, activeTheme.isLight ? 0xFFFFFDF9 : 0xF0F4F9FF, knob / 2);
            drawRoundedOutline(ctx, knobX, knobY, knob, knob, 0x40FFFFFF, knob / 2);
            fillRounded(ctx, knobX + 4, knobY + 2, knob - 8, 3, 0x40FFFFFF, 1);
        }
        ctx.pose().popMatrix();
    }

    private void drawScrollFades(GuiGraphicsExtractor ctx) {
        if (maxScroll <= 0)
            return;
        int ech = contentH();
        if (activeTheme == UiTheme.LIQUID_GLASS) {
            return;
        }
        int transparent = C_PANEL & 0x00FFFFFF;
        ctx.fillGradient(cx, cy, cx + cw, cy + 8, C_PANEL, transparent);
        ctx.fillGradient(cx, cy + ech - 8, cx + cw, cy + ech, transparent, C_PANEL);
    }

    private void renderScrollbar(GuiGraphicsExtractor ctx, int mx, int my) {
        if (maxScroll <= 0)
            return;
        int ech = contentH();
        int sbX = px + pw - 9;
        int tot = totalH();
        int thumbH = Math.max(22, (int) ((float) ech / tot * ech));
        int thumbY = cy + (int) ((float) scrollOffset / maxScroll * (ech - thumbH));
        boolean hov = mx >= sbX - 2 && mx <= sbX + 5 && my >= cy && my <= cy + ech;
        int trackCol;
        int idleCol;
        if (activeTheme == UiTheme.LIQUID_GLASS) {
            trackCol = hov ? 0x805AC8FF : 0x305AC8FF;
            idleCol = 0xFF6A7A8E;
        } else if (activeTheme.isLight) {
            trackCol = hov ? 0xFFD8C6B8 : 0x14C9A090;
            idleCol = 0xFFC09888;
        } else {
            trackCol = hov ? 0xFF4A4A54 : 0x303A3A44;
            idleCol = 0xFF6A6A78;
        }
        ctx.fill(sbX + 1, cy + 4, sbX + 2, cy + ch - 4, trackCol);
        int col = (hov && mx >= sbX - 2 && my >= thumbY && my <= thumbY + thumbH) ? C_SCROLLTHUMB_HOVER : idleCol;
        ctx.fill(sbX, thumbY, sbX + 3, thumbY + thumbH, col);
        ctx.fill(sbX, thumbY + 1, sbX + 3, thumbY + thumbH - 1, col);
    }

    private void posSliders() {
        int ech = contentH();
        for (Row r : rows) {
            if (r.type != RT.SLIDER || r.slider == null)
                continue;
            int sliderW = sliderWidthFor(r);
            int screenX = cx + r.layoutX;
            int screenY = cy - scrollOffset + r.layoutY;
            boolean vis = screenY >= cy && screenY + CARD_H <= cy + ech;
            r.slider.setPosition(screenX + r.layoutW - 8 - sliderW, screenY + (CARD_H - SLIDER_H) / 2);
            r.slider.setWidth(sliderW);
            r.slider.visible = vis;
            r.slider.active = vis;
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean bl) {
        double mx = click.x(), my = click.y();

        int btnH = sidebarButtonHeight();
        int gap = sidebarButtonGap();
        if (mx >= sx && mx < sx + sw && my >= sy && my < sy + sh) {
            for (int i = 0; i < TABS.length; i++) {
                int by = sy + 8 + i * (btnH + gap);
                if (my >= by && my < by + btnH) {
                    if (selectedTab != i) {
                        selectedTab = i;
                        toggleMotions.clear();
                        scrollOffset = 0;
                        rebuild();
                    }
                    return true;
                }
            }
            return true;
        }

        int ech = contentH();
        int sbX = px + pw - 8;
        if (mx >= sbX - 2 && mx <= sbX + 5 && my >= cy && my <= cy + ech) {
            dragging = true;
            dragOriginY = (int) my;
            dragOriginOff = scrollOffset;
            return true;
        }

        if (mx >= cx && mx < cx + cw && my >= cy && my < cy + ech) {
            for (Row r : rows) {
                int screenX = cx + r.layoutX;
                int screenY = cy - scrollOffset + r.layoutY;
                if (mx >= screenX && mx < screenX + r.layoutW
                        && my >= screenY && my < screenY + r.h()) {
                    if ((r.type == RT.TOGGLE || r.type == RT.CYCLE || r.type == RT.THEME) && r.action != null) {
                        r.action.run();
                        if (r.type != RT.THEME)
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
            int ech = contentH();
            int tot = totalH();
            int thumbH = Math.max(16, (int) ((float) ech / tot * ech));
            float ratio = (float) (my - dragOriginY) / (ech - thumbH);
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
        if (mx >= px && mx < px + pw && my >= cy && my < cy + contentH()) {
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
                || (config.lodMidChunks != initialLodMidChunks)
                || (config.enableGreedyMid != initialGreedyMid)
                || (config.enableGreedyFar != initialGreedyFar);

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
            mc.gui.setScreen(parent);
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
        bgHexBox = null;
        panelHexBox = null;
        accentHexBox = null;
        textHexBox = null;

        switch (selectedTab) {
            case 0 -> buildVideo();
            case 1 -> buildMetal();
            case 2 -> buildQuality();
            case 3 -> buildPerformance();
            case 4 -> buildAdvanced();
            case 5 -> buildThemes();
        }
        computeLayout();
        for (Row r : rows)
            if (r.type == RT.SLIDER && r.slider != null)
                addRenderableWidget(r.slider);
        if (selectedTab == 5)
            setupCustomHexBoxes();
    }

    private void setupCustomHexBoxes() {
        var font = getFont();

        int gap = 8;
        int top = hexTop();
        int boxY = top + 20;
        int boxH = 18;
        int boxW = hexBoxW();
        int[] xs = hexBoxXs();
        bgHexBox = new EditBox(font, xs[0], boxY, boxW, boxH, Component.literal("BG Hex"));
        bgHexBox.setMaxLength(7);
        bgHexBox.setValue(customHexBg);
        bgHexBox.setHint(Component.literal("BG"));
        bgHexBox.setResponder(v -> {
            customHexBg = v.replace("#", "").trim();
        });
        addRenderableWidget(bgHexBox);
        panelHexBox = new EditBox(font, xs[1], boxY, boxW, boxH, Component.literal("Panel Hex"));
        panelHexBox.setMaxLength(7);
        panelHexBox.setValue(customHexPanel);
        panelHexBox.setHint(Component.literal("Panel"));
        panelHexBox.setResponder(v -> {
            customHexPanel = v.replace("#", "").trim();
        });
        addRenderableWidget(panelHexBox);
        accentHexBox = new EditBox(font, xs[2], boxY, boxW, boxH, Component.literal("Accent Hex"));
        accentHexBox.setMaxLength(7);
        accentHexBox.setValue(customHexAccent);
        accentHexBox.setHint(Component.literal("Accent"));
        accentHexBox.setResponder(v -> {
            customHexAccent = v.replace("#", "").trim();
        });
        addRenderableWidget(accentHexBox);
        textHexBox = new EditBox(font, xs[3], boxY, boxW, boxH, Component.literal("Text Hex"));
        textHexBox.setMaxLength(7);
        textHexBox.setValue(customHexText);
        textHexBox.setHint(Component.literal("Text"));
        textHexBox.setResponder(v -> {
            customHexText = v.replace("#", "").trim();
        });
        addRenderableWidget(textHexBox);
        int applyX = cx + cw - HEX_APPLY_W;
        addRenderableWidget(Button.builder(Component.literal("Apply"), b -> applyCustomHex())
                .bounds(applyX, boxY, HEX_APPLY_W, boxH).build());
    }

    private void applyCustomHex() {
        customHexBg = sanitizeHex(customHexBg, "141C2A");
        customHexPanel = sanitizeHex(customHexPanel, "1E242E");
        customHexAccent = sanitizeHex(customHexAccent, "5AC8FF");
        customHexText = sanitizeHex(customHexText, "E8F0FF");
        config.customHexBg = customHexBg;
        config.customHexPanel = customHexPanel;
        config.customHexAccent = customHexAccent;
        config.customHexText = customHexText;
        config.uiTheme = UiTheme.CUSTOM.name();
        config.save();
        applyTheme(UiTheme.CUSTOM);
        selectedTab = 5;
        rebuild();
    }

    private static String sanitizeHex(String s, String fallback) {
        if (s == null)
            return fallback;
        String h = s.trim().replace("#", "").replace("0x", "").toUpperCase();
        if (h.length() != 6)
            return fallback;
        for (char c : h.toCharArray())
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F')))
                return fallback;
        return h;
    }

    private boolean isFullWidth(Row r) {
        return r.type == RT.SECTION || r.type == RT.THEME || r.type == RT.SLIDER;
    }

    private void computeLayout() {
        int col = 0;
        int colWidth = (cw - CARD_GAP) / 2;
        int y = 0;
        Row lastRow = null;
        for (Row r : rows) {
            if (isFullWidth(r)) {
                if (col == 1 && lastRow != null) {
                    lastRow.layoutX = 0;
                    lastRow.layoutY = y;
                    lastRow.layoutW = cw;
                    y += lastRow.h() + CARD_GAP;
                    col = 0;
                }
                r.layoutX = 0;
                r.layoutY = y;
                r.layoutW = cw;
                y += r.h() + CARD_GAP;
                col = 0;
            } else {
                r.layoutX = col == 0 ? 0 : colWidth + CARD_GAP;
                r.layoutY = y;
                r.layoutW = colWidth;
                if (col == 0) {
                    col = 1;
                } else {
                    col = 0;
                    y += r.h() + CARD_GAP;
                }
            }
            lastRow = r;
        }
        if (col == 1 && lastRow != null) {
            lastRow.layoutX = 0;
            lastRow.layoutW = cw;
            y += lastRow.h() + CARD_GAP;
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
        tog("Greedy Meshing (Mid LOD)", config.enableGreedyMid, v -> config.enableGreedyMid = v);
        tog("Greedy Meshing (Far LOD)", config.enableGreedyFar, v -> config.enableGreedyFar = v);
        tog("Thermal-Adaptive LOD", config.lodThermalAdaptive, v -> config.lodThermalAdaptive = v);

        sec("Extras");
        tog("Smooth Lighting + AO", config.smoothLighting, v -> config.smoothLighting = v);
        tog("Hidden Fluid Culling", config.hiddenFluidCulling, v -> config.hiddenFluidCulling = v);
        tog("Improved Fluid Shaping", config.improvedFluidShaping, v -> config.improvedFluidShaping = v);
        tog("Closest Point Entity Sort", config.closestPointEntitySort, v -> config.closestPointEntitySort = v);
    }

    private void buildPerformance() {
        sec("Frame Pacing");
        sld("Target FPS", 30, 50000, 30, pendingTargetFps, v -> pendingTargetFps = (int) (float) v);
        tog("GPU-Driven Terrain (restart)", config.gpuDrivenTerrain, v -> config.gpuDrivenTerrain = v);
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
        cyc("Terrain Path", config.terrainPath, () -> {
            config.terrainPath = switch (config.terrainPath) {
                case "auto" -> "vertex";
                case "vertex" -> "mesh";
                case "mesh" -> "indirect";
                default -> "auto";
            };
            System.setProperty("metalrender.terrainPath", config.terrainPath);
            config.loadFeatureFlags();
            rebuild();
        });
        tog("Programmable Blending", config.enableProgrammableBlending, v -> config.enableProgrammableBlending = v);
        tog("Cluster Frustum Culling", config.enableClusterFrustumCulling,
                v -> config.enableClusterFrustumCulling = v);
        tog("GPU Translucency Sort", config.enableGpuTranslucencySort,
                v -> config.enableGpuTranslucencySort = v);
    }

    private void buildThemes() {
        sec("Colour Themes");
        infoRow("Active Theme", activeTheme.displayName);
        themeRow(UiTheme.LIQUID_GLASS);
        themeRow(UiTheme.PASTEL);
        themeRow(UiTheme.AMBER);
        themeRow(UiTheme.CRIMSON);
        themeRow(UiTheme.LAVENDER);
        themeRow(UiTheme.CUSTOM);
        sec("Custom Hex");
        infoRow("Custom", "Edit boxes below \u2192 Apply");
        sec("Preview");
        infoRow("Tip", "Click a theme to apply instantly");
        infoRow("Background", String.format("#%06X", activeTheme.bgBottom & 0xFFFFFF));
        infoRow("Accent", String.format("#%06X", activeTheme.accent & 0xFFFFFF));
        infoRow("Cards", String.format("#%06X", activeTheme.card & 0xFFFFFF)
                + (activeTheme.isLight ? " \u2022 Light soft matte" : " \u2022 Dark"));
    }

    private void themeRow(UiTheme th) {
        Row r = new Row(RT.THEME, th.displayName);
        r.theme = th;
        r.value = th == activeTheme ? "Active" : "Select";
        r.action = () -> {
            if (th != activeTheme) {
                applyTheme(th);
                config.uiTheme = th.name();
                config.save();
                rebuild();
            }
        };
        rows.add(r);
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

    private static int lighten(int argb, float amt) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        r = cl((int) (r + (255 - r) * amt), 0, 255);
        g = cl((int) (g + (255 - g) * amt), 0, 255);
        b = cl((int) (b + (255 - b) * amt), 0, 255);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int darken(int argb, float amt) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        r = cl((int) (r * amt), 0, 255);
        g = cl((int) (g * amt), 0, 255);
        b = cl((int) (b * amt), 0, 255);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int addAlpha(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }

    private String truncateLabel(net.minecraft.client.gui.Font font, String label, int maxW) {
        if (font.width(label) <= maxW)
            return label;
        String ell = "\u2026";
        int ellW = font.width(ell);
        for (int len = label.length() - 1; len > 0; len--) {
            String t = label.substring(0, len) + ell;
            if (font.width(t) <= maxW)
                return t;
        }
        return ell;
    }
}
