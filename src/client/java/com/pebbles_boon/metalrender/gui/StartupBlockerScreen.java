package com.pebbles_boon.metalrender.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.io.IOException;
import java.io.InputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class StartupBlockerScreen extends Screen {
  private static final String IMAGE_RESOURCE =
      "/assets/metalrender/textures/gui/dont_be_like_bloffo.png";
  private static final Identifier RUNTIME_IMAGE =
      Identifier.fromNamespaceAndPath("metalrender", "startup_blocker_runtime");
  private static final Component MESSAGE =
      Component.literal("You are on Windows. You do not need this.");
  private static final int IMAGE_WIDTH = 1954;
  private static final int IMAGE_HEIGHT = 1556;
  private static final int PADDING = 16;
  private static final int TEXT_Y = 16;
  private static final int IMAGE_TOP = 44;
  private static boolean runtimeImageLoaded;
  private static int runtimeImageWidth = IMAGE_WIDTH;
  private static int runtimeImageHeight = IMAGE_HEIGHT;
  private static boolean loggedRender;

  public StartupBlockerScreen() {
    super(Component.literal("MetalRender Startup Notice"));
  }

  @Override
  public boolean shouldCloseOnEsc() {
    return false;
  }

  @Override
  public void onClose() {}

  @Override
  public void extractRenderState(GuiGraphicsExtractor ctx, int mx, int my,
                                 float delta) {
    if (!loggedRender) {
      loggedRender = true;
      MetalLogger.info("STARTUP_BLOCKER: render width=%d height=%d", width,
                       height);
    }
    ctx.fill(0, 0, width, height, 0xFF000000);
    var font = getFont();
    ctx.text(font, MESSAGE, width / 2 - font.width(MESSAGE.getString()) / 2,
             TEXT_Y, 0xFFFFFFFF, false);

    ensureRuntimeImageLoaded();
    if (!runtimeImageLoaded) {
      return;
    }

    int availableWidth = Math.max(1, width - PADDING * 2);
    int availableHeight = Math.max(1, height - IMAGE_TOP - PADDING);
    float scale = Math.min((float)availableWidth / runtimeImageWidth,
                           (float)availableHeight / runtimeImageHeight);
    int drawWidth = Math.max(1, Math.round(runtimeImageWidth * scale));
    int drawHeight = Math.max(1, Math.round(runtimeImageHeight * scale));
    int drawX = (width - drawWidth) / 2;
    int drawY = IMAGE_TOP + Math.max(0, (availableHeight - drawHeight) / 2);

    ctx.blit(RenderPipelines.GUI_TEXTURED, RUNTIME_IMAGE, drawX, drawY, 0.0f,
             0.0f, drawWidth, drawHeight, runtimeImageWidth,
             runtimeImageHeight);
  }

  private static void ensureRuntimeImageLoaded() {
    if (runtimeImageLoaded) {
      return;
    }
    Minecraft mc = Minecraft.getInstance();
    if (mc == null || mc.getTextureManager() == null) {
      return;
    }
    try (InputStream stream =
             StartupBlockerScreen.class.getResourceAsStream(IMAGE_RESOURCE)) {
      if (stream == null) {
        MetalLogger.warn("STARTUP_BLOCKER: resource stream missing: %s",
                         IMAGE_RESOURCE);
        return;
      }
      NativeImage image = NativeImage.read(stream);
      runtimeImageWidth = image.getWidth();
      runtimeImageHeight = image.getHeight();
      DynamicTexture texture =
          new DynamicTexture(() -> "metalrender_startup_blocker", image);
      mc.getTextureManager().register(RUNTIME_IMAGE, texture);
      texture.upload();
      runtimeImageLoaded = true;
      MetalLogger.info("STARTUP_BLOCKER: image registered %dx%d",
                       runtimeImageWidth, runtimeImageHeight);
    } catch (IOException ignored) {
      MetalLogger.warn("STARTUP_BLOCKER: failed to load image: %s",
                       ignored.getMessage());
    }
  }
}