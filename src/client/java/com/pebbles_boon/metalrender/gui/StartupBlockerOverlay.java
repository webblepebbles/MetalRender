package com.pebbles_boon.metalrender.gui;

import com.pebbles_boon.metalrender.util.MetalLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.resources.Identifier;

public class StartupBlockerOverlay extends Overlay {
    private static final RuntimeTexture IMAGE = new RuntimeTexture(
            Identifier.fromNamespaceAndPath("metalrender",
                    "textures/gui/dont_be_like_bloffo.png"),
            1954, 1556, "image");
    private static final RuntimeTexture TEXT = new RuntimeTexture(
            Identifier.fromNamespaceAndPath("metalrender",
                    "textures/gui/startup_blocker_text.png"),
            1620, 124, "text");
    private static final RuntimeTexture BOTTOM_TEXT = new RuntimeTexture(
            Identifier.fromNamespaceAndPath(
                    "metalrender", "textures/gui/startup_blocker_bottom_text.png"),
            670, 101, "bottom text");
    private static final RuntimeTexture FOOTNOTE = new RuntimeTexture(
            Identifier.fromNamespaceAndPath(
                    "metalrender", "textures/gui/startup_blocker_footnote.png"),
            260, 58, "footnote");
    private static final int PADDING = 16;
    private static final int GAP = 12;
    private static final int FOOTNOTE_GAP = 4;
    private static final float TEXT_HEIGHT_FRACTION = 0.05f;
    private static final float BOTTOM_TEXT_HEIGHT_FRACTION = 0.04f;
    private static final float FOOTNOTE_HEIGHT_FRACTION = 0.024f;
    private static final float IMAGE_WIDTH_FRACTION = 1f;
    private static final float IMAGE_HEIGHT_FRACTION = 0.76f;
    private static boolean loggedRender;

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mx, int my,
            float delta) {
        if (!loggedRender) {
            loggedRender = true;
            MetalLogger.info("startup_blocker: overlay wendew");
        }
        ctx.fill(0, 0, ctx.guiWidth(), ctx.guiHeight(), 0xFF000000);

        ensureRuntimeTextureLoaded(TEXT);
        ensureRuntimeTextureLoaded(BOTTOM_TEXT);
        ensureRuntimeTextureLoaded(FOOTNOTE);
        ensureRuntimeTextureLoaded(IMAGE);
        if (!TEXT.loaded || !BOTTOM_TEXT.loaded || !FOOTNOTE.loaded ||
                !IMAGE.loaded) {
            return;
        }

        int stackAvailableWidth = Math.max(
                1, Math.min(ctx.guiWidth() - PADDING * 2,
                        Math.round(ctx.guiWidth() * IMAGE_WIDTH_FRACTION)));
        int textAvailableWidth = stackAvailableWidth;
        int textAvailableHeight = Math.max(1, Math.round(ctx.guiHeight() * TEXT_HEIGHT_FRACTION));
        float textScale = Math.min((float) textAvailableWidth / TEXT.width,
                (float) textAvailableHeight / TEXT.height);
        int textWidth = Math.max(1, Math.round(TEXT.width * textScale));
        int textHeight = Math.max(1, Math.round(TEXT.height * textScale));

        int bottomTextAvailableHeight = Math.max(1, Math.round(ctx.guiHeight() * BOTTOM_TEXT_HEIGHT_FRACTION));
        float bottomTextScale = Math.min((float) stackAvailableWidth / BOTTOM_TEXT.width,
                (float) bottomTextAvailableHeight / BOTTOM_TEXT.height);
        int bottomTextWidth = Math.max(1, Math.round(BOTTOM_TEXT.width * bottomTextScale));
        int bottomTextHeight = Math.max(1, Math.round(BOTTOM_TEXT.height * bottomTextScale));

        int footnoteAvailableHeight = Math.max(1, Math.round(ctx.guiHeight() * FOOTNOTE_HEIGHT_FRACTION));
        float footnoteScale = Math.min((float) stackAvailableWidth / FOOTNOTE.width,
                (float) footnoteAvailableHeight / FOOTNOTE.height);
        int footnoteWidth = Math.max(1, Math.round(FOOTNOTE.width * footnoteScale));
        int footnoteHeight = Math.max(1, Math.round(FOOTNOTE.height * footnoteScale));

        int reservedHeight = textHeight + bottomTextHeight + footnoteHeight + GAP * 2 + FOOTNOTE_GAP;
        int imageBoxWidth = Math.max(
                1, Math.min(ctx.guiWidth() - PADDING * 2, stackAvailableWidth));
        int imageBoxHeight = Math.max(
                1, Math.min(ctx.guiHeight() - PADDING * 2 - reservedHeight,
                        Math.round(ctx.guiHeight() * IMAGE_HEIGHT_FRACTION)));
        float imageScale = Math.min((float) imageBoxWidth / IMAGE.width,
                (float) imageBoxHeight / IMAGE.height);
        int imageWidth = Math.max(1, Math.round(IMAGE.width * imageScale));
        int imageHeight = Math.max(1, Math.round(IMAGE.height * imageScale));
        int contentHeight = textHeight + GAP + imageHeight + GAP +
                bottomTextHeight + FOOTNOTE_GAP + footnoteHeight;
        int contentTop = Math.max(PADDING, (ctx.guiHeight() - contentHeight) / 2);
        int textX = (ctx.guiWidth() - textWidth) / 2;
        int textY = contentTop;
        int imageX = (ctx.guiWidth() - imageWidth) / 2;
        int imageY = textY + textHeight + GAP;
        int bottomTextX = (ctx.guiWidth() - bottomTextWidth) / 2;
        int bottomTextY = imageY + imageHeight + GAP;
        int footnoteX = (ctx.guiWidth() - footnoteWidth) / 2;
        int footnoteY = bottomTextY + bottomTextHeight + FOOTNOTE_GAP;

        ctx.blit(RenderPipelines.GUI_TEXTURED, TEXT.id, textX, textY, 0.0f, 0.0f,
                textWidth, textHeight, TEXT.width, TEXT.height, TEXT.width,
                TEXT.height);
        ctx.blit(RenderPipelines.GUI_TEXTURED, IMAGE.id, imageX, imageY, 0.0f, 0.0f,
                imageWidth, imageHeight, IMAGE.width, IMAGE.height, IMAGE.width,
                IMAGE.height);
        ctx.blit(RenderPipelines.GUI_TEXTURED, BOTTOM_TEXT.id, bottomTextX,
                bottomTextY, 0.0f, 0.0f, bottomTextWidth, bottomTextHeight,
                BOTTOM_TEXT.width, BOTTOM_TEXT.height, BOTTOM_TEXT.width,
                BOTTOM_TEXT.height);
        ctx.blit(RenderPipelines.GUI_TEXTURED, FOOTNOTE.id, footnoteX, footnoteY,
                0.0f, 0.0f, footnoteWidth, footnoteHeight, FOOTNOTE.width,
                FOOTNOTE.height, FOOTNOTE.width, FOOTNOTE.height);
    }

    private static void ensureRuntimeTextureLoaded(RuntimeTexture textureState) {
        if (textureState.loaded) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getTextureManager() == null) {
            return;
        }
        mc.getTextureManager().registerAndLoad(textureState.id,
                new SimpleTexture(textureState.id));
        textureState.loaded = true;
        MetalLogger.info("startup_blocker: %s weady %dx%d",
                textureState.logLabel, textureState.width,
                textureState.height);
    }

    private static final class RuntimeTexture {
        private final Identifier id;
        private final String logLabel;
        private boolean loaded;
        private int width;
        private int height;

        private RuntimeTexture(Identifier id, int width, int height,
                String logLabel) {
            this.id = id;
            this.width = width;
            this.height = height;
            this.logLabel = logLabel;
        }
    }
}