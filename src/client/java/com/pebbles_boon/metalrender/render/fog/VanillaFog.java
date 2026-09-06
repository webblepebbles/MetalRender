package com.pebbles_boon.metalrender.render.fog;

import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.level.CameraRenderState;

public final class VanillaFog {
  public static final float NO_FOG_DIST = 60000.0f;

  public record FogState(
      float r, float g, float b,
      float envStart, float envEnd,
      float renderStart, float renderEnd) {
    public static FogState none() {
      return new FogState(0.0f, 0.0f, 0.0f,
          NO_FOG_DIST, NO_FOG_DIST, NO_FOG_DIST, NO_FOG_DIST);
    }

    public boolean isFinite() {
      return Float.isFinite(r) && Float.isFinite(g) && Float.isFinite(b)
          && Float.isFinite(envStart) && Float.isFinite(envEnd)
          && Float.isFinite(renderStart) && Float.isFinite(renderEnd);
    }
  }

  private VanillaFog() {
  }

  public static FogState fromRenderState(CameraRenderState state) {
    try {
      if (state == null || state.fogData == null) {
        return null;
      }
      FogData data = state.fogData;
      if (data.color == null) {
        return null;
      }
      FogState out = new FogState(
          data.color.x, data.color.y, data.color.z,
          data.environmentalStart, data.environmentalEnd,
          data.renderDistanceStart, data.renderDistanceEnd);
      return out.isFinite() ? out : null;
    } catch (Throwable ignored) {
      return null;
    }
  }
}
