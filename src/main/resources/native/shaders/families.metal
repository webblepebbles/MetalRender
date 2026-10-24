#include <metal_stdlib>
#include "families_layout.h"
using namespace metal;

constant bool HAS_BLEND [[function_constant(MR_FC_HAS_BLEND)]];
constant float ALPHA_CUTOUT [[function_constant(MR_FC_ALPHA_CUTOUT)]];
constant bool NO_OVERLAY [[function_constant(MR_FC_NO_OVERLAY)]];
constant bool EMISSIVE [[function_constant(MR_FC_EMISSIVE)]];
constant bool PER_FACE_LIGHTING [[function_constant(MR_FC_PER_FACE_LIGHTING)]];
constant bool NO_CARDINAL_LIGHTING [[function_constant(MR_FC_NO_CARDINAL_LIGHTING)]];
constant bool APPLY_TEXTURE_MATRIX [[function_constant(MR_FC_APPLY_TEXTURE_MATRIX)]];
constant bool DISSOLVE [[function_constant(MR_FC_DISSOLVE)]];
constant int PORTAL_LAYERS [[function_constant(MR_FC_PORTAL_LAYERS)]];
constant int VS_VARIANT [[function_constant(MR_FC_VS_VARIANT)]];
constant int FS_VARIANT [[function_constant(MR_FC_FS_VARIANT)]];
constant bool HAS_POSITION [[function_constant(MR_FC_HAS_POSITION)]];
constant bool HAS_COLOR [[function_constant(MR_FC_HAS_COLOR)]];
constant bool HAS_UV0 [[function_constant(MR_FC_HAS_UV0)]];
constant bool HAS_UV1 [[function_constant(MR_FC_HAS_UV1)]];
constant bool HAS_UV2 [[function_constant(MR_FC_HAS_UV2)]];
constant bool HAS_NORMAL [[function_constant(MR_FC_HAS_NORMAL)]];
constant bool HAS_LINE_WIDTH [[function_constant(MR_FC_HAS_LINE_WIDTH)]];
constant bool HAS_PROJECTION [[function_constant(MR_FC_HAS_PROJECTION)]];
constant bool HAS_DYNAMIC_TRANSFORMS [[function_constant(MR_FC_HAS_DYNAMIC_TRANSFORMS)]];

constant bool GUI_TEX2D = FS_VARIANT == MR_FS_POSITION_TEX_COLOR || FS_VARIANT == MR_FS_GLASS;
constant bool GUI_CUBE = FS_VARIANT == MR_FS_PANORAMA;
constant bool GUI_SAMPLER0 = GUI_TEX2D || GUI_CUBE;

constant bool TEXT_VS_LIGHTMAP = VS_VARIANT == MR_VS_TEXT || VS_VARIANT == MR_VS_TEXT_BACKGROUND ||
                                 VS_VARIANT == MR_VS_TEXT_INTENSITY;
constant bool TEXT_FS_FOG = FS_VARIANT == MR_FS_TEXT || FS_VARIANT == MR_FS_TEXT_BACKGROUND ||
                            FS_VARIANT == MR_FS_TEXT_INTENSITY;
constant bool TEXT_FS_TEX0 = FS_VARIANT == MR_FS_TEXT || FS_VARIANT == MR_FS_TEXT_INTENSITY ||
                             FS_VARIANT == MR_FS_TEXT_SEE_THROUGH ||
                             FS_VARIANT == MR_FS_TEXT_INTENSITY_SEE_THROUGH;

constant bool SKY_FS_FOG = FS_VARIANT == MR_FS_SKY;
constant bool SKY_FS_TEX0 = FS_VARIANT == MR_FS_POSITION_TEX || FS_VARIANT == MR_FS_POSITION_TEX_COLOR;

constant bool LINES_VS_GLOBALS = VS_VARIANT == MR_VS_LINES;
constant bool LINES_VS_POINT = VS_VARIANT == MR_VS_DEBUG_POINT;
constant bool LINES_FS_FOG = FS_VARIANT == MR_FS_LINES;

constant bool POST_VS_ROTSCALE = VS_VARIANT == MR_VS_ROTSCALE;
constant bool POST_FS_IN = FS_VARIANT != MR_FS_LIGHTMAP && FS_VARIANT != MR_FS_TRANSPARENCY;
constant bool POST_FS_BLUR_SAMPLER = FS_VARIANT == MR_FS_SPIDERCLIP;
constant bool POST_FS_SAMPLER_INFO = FS_VARIANT == MR_FS_BOX_BLUR || FS_VARIANT == MR_FS_BITS ||
                                     FS_VARIANT == MR_FS_ENTITY_SOBEL ||
                                     FS_VARIANT == MR_FS_ENTITY_OUTLINE_BOX_BLUR ||
                                     FS_VARIANT == MR_FS_INVERT;
constant bool POST_FS_CONFIG = FS_VARIANT == MR_FS_POST_BLIT || FS_VARIANT == MR_FS_BOX_BLUR ||
                               FS_VARIANT == MR_FS_COLOR_CONVOLVE || FS_VARIANT == MR_FS_BITS ||
                               FS_VARIANT == MR_FS_INVERT || FS_VARIANT == MR_FS_SPIDERCLIP;
constant bool POST_FS_GLOBALS = FS_VARIANT == MR_FS_BOX_BLUR;
constant bool POST_FS_LIGHTMAP = FS_VARIANT == MR_FS_LIGHTMAP;
constant bool POST_FS_TRANSPARENCY = FS_VARIANT == MR_FS_TRANSPARENCY;

constant bool SPRITE_FS_INTERPOLATE = FS_VARIANT == MR_FS_ANIMATE_SPRITE_INTERPOLATE;

struct VIn {
  float3 position [[attribute(0), function_constant(HAS_POSITION)]];
  float4 color [[attribute(1), function_constant(HAS_COLOR)]];
  float2 uv0 [[attribute(2), function_constant(HAS_UV0)]];
  short2 uv1 [[attribute(3), function_constant(HAS_UV1)]];
  short2 uv2 [[attribute(4), function_constant(HAS_UV2)]];
  float3 normal [[attribute(5), function_constant(HAS_NORMAL)]];
  float lineWidth [[attribute(6), function_constant(HAS_LINE_WIDTH)]];
};

struct VOut {
  float4 position [[position]];
  float4 color;
  float2 uv0;
  float2 uv1;
  float3 dir;
  float sphericalDistance;
  float cylindricalDistance;
  float progress;
};

struct POut {
  float4 position [[position]];
  float4 color;
  float sphericalDistance;
  float cylindricalDistance;
  float pointSize [[point_size, function_constant(LINES_VS_POINT)]];
};

static inline float4x4 mr_mat4(const constant float *m) {
  return float4x4(float4(m[0], m[1], m[2], m[3]), float4(m[4], m[5], m[6], m[7]),
                  float4(m[8], m[9], m[10], m[11]), float4(m[12], m[13], m[14], m[15]));
}

static inline float4 mr_vec4(const constant float *v) { return float4(v[0], v[1], v[2], v[3]); }

static inline float4 mr_clip(float4 p) {
  p.y = -p.y;
  return p;
}

static inline float4 mr_transform(constant MRProjection &proj, constant MRDynamicTransforms &dt,
                                   float3 position) {
  return mr_clip(mr_mat4(proj.ProjMat) * mr_mat4(dt.ModelViewMat) * float4(position, 1.0));
}

static inline float mr_fog_spherical(float3 p) { return length(p); }

static inline float mr_fog_cylindrical(float3 p) { return max(length(p.xz), abs(p.y)); }

static inline float mr_linear_fog(float d, float start, float end) {
  if (d <= start) {
    return 0.0;
  }
  if (d >= end) {
    return 1.0;
  }
  return (d - start) / (end - start);
}

static inline float4 mr_apply_fog(float4 c, float sph, float cyl, float envStart, float envEnd,
                                  float rdStart, float rdEnd, float4 fogColor) {
  float f = max(mr_linear_fog(sph, envStart, envEnd), mr_linear_fog(cyl, rdStart, rdEnd));
  return float4(mix(c.rgb, fogColor.rgb, f * fogColor.a), c.a);
}

static inline float4 mr_fog(constant MRFog &fog, float4 c, float sph, float cyl) {
  return mr_apply_fog(c, sph, cyl, fog.FogEnvironmentalStart, fog.FogEnvironmentalEnd,
                      fog.FogRenderDistanceStart, fog.FogRenderDistanceEnd, mr_vec4(fog.FogColor));
}

static inline float4 mr_lightmap(texture2d<float> lightmap, sampler s, short2 uv) {
  float2 coord = clamp(float2(uv) / 256.0 + 0.5 / 16.0, float2(0.5 / 16.0), float2(15.5 / 16.0));
  return lightmap.sample(s, coord, level(0));
}

static inline VOut mr_vout() {
  VOut o;
  o.position = float4(0.0, 0.0, 0.0, 1.0);
  o.color = float4(1.0);
  o.uv0 = float2(0.0);
  o.uv1 = float2(0.0);
  o.dir = float3(0.0);
  o.sphericalDistance = 0.0;
  o.cylindricalDistance = 0.0;
  o.progress = 0.0;
  return o;
}

static inline float3 mr_position(VIn in) {
  float3 p = float3(0.0);
  if (HAS_POSITION) {
    p = in.position;
  }
  return p;
}

static inline float4 mr_color(VIn in) {
  float4 c = float4(1.0);
  if (HAS_COLOR) {
    c = in.color;
  }
  return c;
}

static inline float2 mr_uv0(VIn in) {
  float2 uv = float2(0.0);
  if (HAS_UV0) {
    uv = in.uv0;
  }
  return uv;
}

vertex VOut gui_vertex(VIn in [[stage_in]],
                       constant MRProjection &proj [[buffer(MR_SLOT_PROJECTION)]],
                       constant MRDynamicTransforms &dt [[buffer(MR_SLOT_DYNAMIC_TRANSFORMS)]]) {
  VOut o = mr_vout();
  float3 p = mr_position(in);
  o.position = mr_transform(proj, dt, p);
  if (VS_VARIANT == MR_VS_PANORAMA) {
    o.dir = p;
  } else {
    o.color = mr_color(in);
    o.uv0 = mr_uv0(in);
    if (FS_VARIANT == MR_FS_GLASS)
      o.uv1 = o.position.xy / o.position.w * float2(0.5, -0.5) + 0.5;
  }
  return o;
}

fragment float4 gui_fragment(VOut in [[stage_in]],
                             constant MRDynamicTransforms &dt [[buffer(MR_SLOT_DYNAMIC_TRANSFORMS)]],
                             texture2d<float> tex0 [[texture(0), function_constant(GUI_TEX2D)]],
                             texturecube<float> cube0 [[texture(MR_TEX_CUBE), function_constant(GUI_CUBE)]],
                             sampler s0 [[sampler(0), function_constant(GUI_SAMPLER0)]]) {
  if (GUI_CUBE) {
    return cube0.sample(s0, in.dir);
  }
  float4 color = in.color;
  if (FS_VARIANT == MR_FS_GLASS) {
    float packed = round(in.color.a * 255.0);
    bool pane = packed >= 128.0;
    float scale = max(1.0, packed - (pane ? 128.0 : 0.0));
    float2 size = max(float2(1.0), round(in.color.rg * 255.0) * scale);
    float radius = min(round(in.color.b * 255.0), min(size.x, size.y) * 0.5);
    float2 local = (in.uv0 - 0.5) * size;
    float2 q = abs(local) - size * 0.5 + radius;
    float distance = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
    float aa = max(fwidth(distance), 0.15);
    float coverage = 1.0 - smoothstep(-aa, aa, distance);
    if (coverage <= 0.0) discard_fragment();
    float2 normal = q.x > q.y ? float2(sign(local.x), 0.0) : float2(0.0, sign(local.y));
    if (length(max(q, 0.0)) > 0.001)
      normal = normalize(max(q, 0.0)) * sign(local);
    float rim = exp(-abs(distance + 0.65) * 2.5);
    float inner = exp(-abs(distance + 1.8) * 1.5);
    float light = dot(normal, normalize(float2(-0.55, -0.83)));
    float2 texel = 1.0 / float2(tex0.get_width(), tex0.get_height());
    float2 refractedUv = in.uv1 + normal * texel * rim * 0.8;
    float3 scene = tex0.sample(s0, refractedUv).rgb;
    if (pane) {
      float3 body = mix(scene, float3(0.10, 0.13, 0.17), 0.14);
      body += rim * (0.12 + 0.25 * max(light, 0.0));
      body -= inner * max(-light, 0.0) * 0.05;
      return float4(body, coverage);
    }
    float highlight = rim * (0.25 + 0.65 * max(light, 0.0));
    float shade = inner * max(-light, 0.0) * 0.17;
    float alpha = (0.035 + highlight + shade) * coverage;
    float3 lens = mix(scene, float3(0.94, 0.98, 1.0), 0.7);
    lens = mix(lens, float3(0.07, 0.10, 0.14), shade * 3.0);
    return float4(lens, alpha);
  }
  if (GUI_TEX2D) {
    color = tex0.sample(s0, in.uv0) * in.color;
  }
  if (color.a == 0.0) {
    discard_fragment();
  }
  return color * mr_vec4(dt.ColorModulator);
}

vertex VOut text_vertex(VIn in [[stage_in]],
                        constant MRProjection &proj [[buffer(MR_SLOT_PROJECTION)]],
                        constant MRDynamicTransforms &dt [[buffer(MR_SLOT_DYNAMIC_TRANSFORMS)]],
                        texture2d<float> lightmap [[texture(2), function_constant(TEXT_VS_LIGHTMAP)]],
                        sampler lightmapSampler [[sampler(2), function_constant(TEXT_VS_LIGHTMAP)]]) {
  VOut o = mr_vout();
  float3 p = mr_position(in);
  o.position = mr_transform(proj, dt, p);
  o.sphericalDistance = mr_fog_spherical(p);
  o.cylindricalDistance = mr_fog_cylindrical(p);
  o.color = mr_color(in);
  if (TEXT_VS_LIGHTMAP) {
    if (HAS_UV2) {
      o.color *= mr_lightmap(lightmap, lightmapSampler, in.uv2);
    }
  }
  o.uv0 = mr_uv0(in);
  return o;
}

fragment float4 text_fragment(VOut in [[stage_in]],
                              constant MRDynamicTransforms &dt [[buffer(MR_SLOT_DYNAMIC_TRANSFORMS)]],
                              constant MRFog &fog [[buffer(MR_SLOT_FOG), function_constant(TEXT_FS_FOG)]],
                              texture2d<float> tex0 [[texture(0), function_constant(TEXT_FS_TEX0)]],
                              sampler s0 [[sampler(0), function_constant(TEXT_FS_TEX0)]]) {
  float4 modulator = mr_vec4(dt.ColorModulator);
  float4 texel = float4(1.0);
  if (TEXT_FS_TEX0) {
    texel = tex0.sample(s0, in.uv0);
    if (FS_VARIANT == MR_FS_TEXT_INTENSITY || FS_VARIANT == MR_FS_TEXT_INTENSITY_SEE_THROUGH) {
      texel = texel.rrrr;
    }
  }
  float4 color = texel * in.color;
  if (TEXT_FS_FOG) {
    color *= modulator;
    if (color.a < 0.1) {
      discard_fragment();
    }
    return mr_fog(fog, color, in.sphericalDistance, in.cylindricalDistance);
  }
  if (color.a < 0.1) {
    discard_fragment();
  }
  return color * modulator;
}

vertex VOut sky_vertex(VIn in [[stage_in]],
                       constant MRProjection &proj [[buffer(MR_SLOT_PROJECTION)]],
                       constant MRDynamicTransforms &dt [[buffer(MR_SLOT_DYNAMIC_TRANSFORMS)]]) {
  VOut o = mr_vout();
  float3 p = mr_position(in);
  o.position = mr_transform(proj, dt, p);
  o.sphericalDistance = mr_fog_spherical(p);
  o.cylindricalDistance = mr_fog_cylindrical(p);
  if (VS_VARIANT == MR_VS_POSITION_TEX || VS_VARIANT == MR_VS_POSITION_TEX_COLOR) {
    o.uv0 = mr_uv0(in);
  }
  if (VS_VARIANT == MR_VS_POSITION_TEX_COLOR || VS_VARIANT == MR_VS_POSITION_COLOR) {
    o.color = mr_color(in);
  }
  return o;
}

fragment float4 sky_fragment(VOut in [[stage_in]],
                             constant MRDynamicTransforms &dt [[buffer(MR_SLOT_DYNAMIC_TRANSFORMS)]],
                             constant MRFog &fog [[buffer(MR_SLOT_FOG), function_constant(SKY_FS_FOG)]],
                             texture2d<float> tex0 [[texture(0), function_constant(SKY_FS_TEX0)]],
                             sampler s0 [[sampler(0), function_constant(SKY_FS_TEX0)]]) {
  float4 modulator = mr_vec4(dt.ColorModulator);
  if (SKY_FS_FOG) {
    return mr_apply_fog(modulator, in.sphericalDistance, in.cylindricalDistance, 0.0, fog.FogSkyEnd,
                        fog.FogSkyEnd, fog.FogSkyEnd, mr_vec4(fog.FogColor));
  }
  if (FS_VARIANT == MR_FS_STARS) {
    return modulator;
  }
  float4 color = in.color;
  if (SKY_FS_TEX0) {
    color = tex0.sample(s0, in.uv0);
    if (FS_VARIANT == MR_FS_POSITION_TEX_COLOR) {
      color *= in.color;
    }
  }
  if (color.a == 0.0) {
    discard_fragment();
  }
  return color * modulator;
}

vertex POut lines_vertex(VIn in [[stage_in]], uint vid [[vertex_id]],
                         constant MRProjection &proj [[buffer(MR_SLOT_PROJECTION)]],
                         constant MRDynamicTransforms &dt [[buffer(MR_SLOT_DYNAMIC_TRANSFORMS)]],
                         constant MRGlobals &globals [[buffer(MR_SLOT_GLOBALS), function_constant(LINES_VS_GLOBALS)]]) {
  POut o;
  float3 p = mr_position(in);
  o.color = mr_color(in);
  o.sphericalDistance = mr_fog_spherical(p);
  o.cylindricalDistance = mr_fog_cylindrical(p);
  if (LINES_VS_POINT) {
    o.pointSize = 1.0;
  }
  float4x4 projMat = mr_mat4(proj.ProjMat);
  float4x4 modelView = mr_mat4(dt.ModelViewMat);
  if (LINES_VS_GLOBALS) {
    const float shrink = 1.0 - (1.0 / 256.0);
    float4x4 viewScale = float4x4(float4(shrink, 0.0, 0.0, 0.0), float4(0.0, shrink, 0.0, 0.0),
                                  float4(0.0, 0.0, shrink, 0.0), float4(0.0, 0.0, 0.0, 1.0));
    float3 normal = float3(0.0);
    if (HAS_NORMAL) {
      normal = in.normal;
    }
    float width = 1.0;
    if (HAS_LINE_WIDTH) {
      width = in.lineWidth;
    }
    float2 screenSize = float2(globals.ScreenSize[0], globals.ScreenSize[1]);
    float4 start = projMat * viewScale * modelView * float4(p, 1.0);
    float4 end = projMat * viewScale * modelView * float4(p + normal, 1.0);
    float3 ndc1 = start.xyz / start.w;
    float3 ndc2 = end.xyz / end.w;
    float2 direction = normalize((ndc2.xy - ndc1.xy) * screenSize);
    float2 offset = float2(-direction.y, direction.x) * width / screenSize;
    if (offset.x < 0.0) {
      offset *= -1.0;
    }
    if (vid % 2 == 0) {
      o.position = float4((ndc1 + float3(offset, 0.0)) * start.w, start.w);
    } else {
      o.position = float4((ndc1 - float3(offset, 0.0)) * start.w, start.w);
    }
    o.position = mr_clip(o.position);
  } else {
    o.position = mr_clip(projMat * modelView * float4(p, 1.0));
    if (LINES_VS_POINT && HAS_LINE_WIDTH) {
      o.pointSize = in.lineWidth;
    }
  }
  return o;
}

fragment float4 lines_fragment(POut in [[stage_in]],
                               constant MRDynamicTransforms &dt [[buffer(MR_SLOT_DYNAMIC_TRANSFORMS)]],
                               constant MRFog &fog [[buffer(MR_SLOT_FOG), function_constant(LINES_FS_FOG)]]) {
  float4 modulator = mr_vec4(dt.ColorModulator);
  if (LINES_FS_FOG) {
    return mr_fog(fog, in.color * modulator, in.sphericalDistance, in.cylindricalDistance);
  }
  float4 color = in.color;
  if (color.a == 0.0) {
    discard_fragment();
  }
  return color * modulator;
}

vertex VOut post_vertex(uint vid [[vertex_id]],
                        constant MRRotScaleConfig &rot [[buffer(MR_SLOT_ROTSCALE_CONFIG), function_constant(POST_VS_ROTSCALE)]]) {
  VOut o = mr_vout();
  float2 uv = float2(float((vid << 1) & 2), float(vid & 2));
  o.position = mr_clip(float4(uv * 2.0 - 1.0, 0.0, 1.0));
  o.uv0 = uv;
  if (POST_VS_ROTSCALE) {
    float radians = rot.InRotation * 0.0174532925;
    float c = cos(radians);
    float s = sin(radians);
    float2 rotated = float2(uv.x * c - uv.y * s, uv.y * c + uv.x * s);
    o.uv1 = rotated * float2(rot.InScale[0], rot.InScale[1]) + float2(rot.InOffset[0], rot.InOffset[1]);
  }
  return o;
}

static inline float mr_lm_brightness(float level) { return level / (4.0 - 3.0 * level); }

static inline float3 mr_lm_not_gamma(float3 color) {
  float maxComponent = max(max(color.x, color.y), color.z);
  float maxInverted = 1.0 - maxComponent;
  float maxScaled = 1.0 - maxInverted * maxInverted * maxInverted * maxInverted;
  return color * (maxScaled / maxComponent);
}

static inline float3 mr_vec3(const constant float *v) { return float3(v[0], v[1], v[2]); }

static float4 mr_lightmap_color(constant MRLightmapInfo &info, float2 texCoord) {
  float blockLevel = floor(texCoord.x * 16.0) / 15.0;
  float skyLevel = floor(texCoord.y * 16.0) / 15.0;
  float blockBrightness = mr_lm_brightness(blockLevel) * info.BlockFactor;
  float skyBrightness = mr_lm_brightness(skyLevel) * info.SkyFactor;
  float3 nightVision = mr_vec3(info.NightVisionColor) * info.NightVisionFactor;
  float3 color = max(mr_vec3(info.AmbientColor), nightVision);
  color += mr_vec3(info.SkyLightColor) * skyBrightness;
  float parabolic = (2.0 * blockLevel - 1.0) * (2.0 * blockLevel - 1.0);
  float3 blockLight = mix(mr_vec3(info.BlockLightTint), float3(1.0), 0.9 * parabolic);
  color += blockLight * blockBrightness;
  color = mix(color, color * float3(0.7, 0.6, 0.6), info.BossOverlayWorldDarkeningFactor);
  color = color - float3(info.DarknessScale);
  color = clamp(color, 0.0, 1.0);
  color = mix(color, mr_lm_not_gamma(color), info.BrightnessFactor);
  return float4(color, 1.0);
}

static float4 mr_transparency(array<texture2d<float>, 12> layers, array<sampler, 12> samplers,
                              float2 uv) {
  float4 colors[6];
  float depths[6];
  colors[0] = float4(layers[0].sample(samplers[0], uv).rgb, 1.0);
  depths[0] = layers[1].sample(samplers[1], uv).r;
  int active = 1;
  for (int layer = 1; layer < 6; layer++) {
    float4 color = layers[layer * 2].sample(samplers[layer * 2], uv);
    float depth = layers[layer * 2 + 1].sample(samplers[layer * 2 + 1], uv).r;
    if (color.a == 0.0) {
      continue;
    }
    colors[active] = color;
    depths[active] = depth;
    int j = active++;
    int i = j - 1;
    while (j > 0 && depths[j] > depths[i]) {
      float dt = depths[i];
      depths[i] = depths[j];
      depths[j] = dt;
      float4 ct = colors[i];
      colors[i] = colors[j];
      colors[j] = ct;
      j = i--;
    }
  }
  float3 accum = colors[0].rgb;
  for (int i = 1; i < active; i++) {
    accum = accum * (1.0 - colors[i].a) + colors[i].rgb;
  }
  return float4(accum, 1.0);
}

fragment float4 post_fragment(VOut in [[stage_in]],
                              texture2d<float> inTex [[texture(0), function_constant(POST_FS_IN)]],
                              sampler inSampler [[sampler(0), function_constant(POST_FS_IN)]],
                              texture2d<float> blurTex [[texture(1), function_constant(POST_FS_BLUR_SAMPLER)]],
                              sampler blurSampler [[sampler(1), function_constant(POST_FS_BLUR_SAMPLER)]],
                              array<texture2d<float>, 12> layers [[texture(0), function_constant(POST_FS_TRANSPARENCY)]],
                              array<sampler, 12> layerSamplers [[sampler(0), function_constant(POST_FS_TRANSPARENCY)]],
                              constant MRSamplerInfo &samplerInfo [[buffer(MR_SLOT_SAMPLER_INFO), function_constant(POST_FS_SAMPLER_INFO)]],
                              constant float *config [[buffer(MR_SLOT_POST_CONFIG), function_constant(POST_FS_CONFIG)]],
                              constant MRGlobals &globals [[buffer(MR_SLOT_GLOBALS), function_constant(POST_FS_GLOBALS)]],
                              constant MRLightmapInfo &lightmapInfo [[buffer(MR_SLOT_LIGHTMAP_INFO), function_constant(POST_FS_LIGHTMAP)]]) {
  float2 uv = in.uv0;
  if (FS_VARIANT == MR_FS_GLASS_BLUR) {
    float2 step = 1.0 / float2(inTex.get_width(), inTex.get_height());
    float3 scene = inTex.sample(inSampler, uv).rgb * 0.25;
    scene += inTex.sample(inSampler, uv + float2(step.x, 0.0)).rgb * 0.125;
    scene += inTex.sample(inSampler, uv - float2(step.x, 0.0)).rgb * 0.125;
    scene += inTex.sample(inSampler, uv + float2(0.0, step.y)).rgb * 0.125;
    scene += inTex.sample(inSampler, uv - float2(0.0, step.y)).rgb * 0.125;
    scene += inTex.sample(inSampler, uv + step).rgb * 0.0625;
    scene += inTex.sample(inSampler, uv - step).rgb * 0.0625;
    scene += inTex.sample(inSampler, uv + float2(step.x, -step.y)).rgb * 0.0625;
    scene += inTex.sample(inSampler, uv + float2(-step.x, step.y)).rgb * 0.0625;
    return float4(scene, 1.0);
  }
  if (POST_FS_LIGHTMAP) {
    return mr_lightmap_color(lightmapInfo, uv);
  }
  if (POST_FS_TRANSPARENCY) {
    return mr_transparency(layers, layerSamplers, uv);
  }
  if (FS_VARIANT == MR_FS_BLIT_SCREEN) {
    return inTex.sample(inSampler, uv);
  }
  if (FS_VARIANT == MR_FS_POST_BLIT) {
    constant MRBlitConfig &blit = *(constant MRBlitConfig *)config;
    return inTex.sample(inSampler, uv) * mr_vec4(blit.ColorModulate);
  }
  if (FS_VARIANT == MR_FS_COLOR_CONVOLVE) {
    constant MRColorConfig &cc = *(constant MRColorConfig *)config;
    float4 texel = inTex.sample(inSampler, uv);
    float3 outColor = float3(dot(texel.rgb, mr_vec3(cc.RedMatrix)), dot(texel.rgb, mr_vec3(cc.GreenMatrix)),
                             dot(texel.rgb, mr_vec3(cc.BlueMatrix)));
    float luma = dot(outColor, float3(0.3, 0.59, 0.11));
    outColor = (outColor - luma) * 1.8 + luma;
    return float4(outColor, 1.0);
  }
  if (FS_VARIANT == MR_FS_SPIDERCLIP) {
    constant MRSpiderConfig &spider = *(constant MRSpiderConfig *)config;
    float4 scissor = mr_vec4(spider.Scissor);
    float4 vignette = mr_vec4(spider.Vignette);
    float2 scaled = in.uv1;
    float4 scaledTexel = inTex.sample(inSampler, scaled);
    float4 blurTexel = blurTex.sample(blurSampler, uv);
    float4 outTexel = scaledTexel;
    if (scaled.x < scissor.x) outTexel = blurTexel;
    if (scaled.y < scissor.y) outTexel = blurTexel;
    if (scaled.x > scissor.z) outTexel = blurTexel;
    if (scaled.y > scissor.w) outTexel = blurTexel;
    if (scaled.x < vignette.x) outTexel = mix(blurTexel, outTexel, (scissor.x - scaled.x) / (scissor.x - vignette.x));
    if (scaled.y < vignette.y) outTexel = mix(blurTexel, outTexel, (scissor.y - scaled.y) / (scissor.y - vignette.y));
    if (scaled.x > vignette.z) outTexel = mix(blurTexel, outTexel, (scissor.z - scaled.x) / (scissor.z - vignette.z));
    if (scaled.y > vignette.w) outTexel = mix(blurTexel, outTexel, (scissor.w - scaled.y) / (scissor.w - vignette.w));
    return float4(outTexel.rgb, 1.0);
  }
  if (POST_FS_SAMPLER_INFO) {
    float2 inSize = float2(samplerInfo.InSize[0], samplerInfo.InSize[1]);
    float2 oneTexel = 1.0 / inSize;
    if (FS_VARIANT == MR_FS_BOX_BLUR) {
      constant MRBlurConfig &blur = *(constant MRBlurConfig *)config;
      float2 step = oneTexel * float2(blur.BlurDir[0], blur.BlurDir[1]);
      float radius = blur.Radius >= 0.5 ? round(blur.Radius) : float(globals.MenuBlurRadius);
      float4 blurred = float4(0.0);
      for (float a = -radius + 0.5; a <= radius; a += 2.0) {
        blurred += inTex.sample(inSampler, uv + step * a);
      }
      blurred += inTex.sample(inSampler, uv + step * radius) / 2.0;
      return blurred / (radius + 0.5);
    }
    if (FS_VARIANT == MR_FS_ENTITY_OUTLINE_BOX_BLUR) {
      constant MRBlurConfig &blur = *(constant MRBlurConfig *)config;
      float2 step = oneTexel * float2(blur.BlurDir[0], blur.BlurDir[1]);
      const float radius = 2.0;
      float4 blurred = float4(0.0);
      for (float a = -radius + 0.5; a <= radius; a += 2.0) {
        blurred += inTex.sample(inSampler, uv + step * a);
      }
      blurred += inTex.sample(inSampler, uv + step * radius) / 2.0;
      return float4((blurred / (radius + 0.5)).rgb, blurred.a);
    }
    if (FS_VARIANT == MR_FS_BITS) {
      constant MRBitsConfig &bits = *(constant MRBitsConfig *)config;
      float2 mosaicInSize = inSize / bits.MosaicSize;
      float2 fractPix = fract(uv * mosaicInSize) / mosaicInSize;
      float4 baseTexel = inTex.sample(inSampler, uv - fractPix);
      float3 fractTexel = baseTexel.rgb - fract(baseTexel.rgb * bits.Resolution) / bits.Resolution;
      float luma = dot(fractTexel, float3(0.3, 0.59, 0.11));
      float3 chroma = (fractTexel - luma) * 1.5;
      return float4(luma + chroma, 1.0);
    }
    if (FS_VARIANT == MR_FS_ENTITY_SOBEL) {
      float4 center = inTex.sample(inSampler, uv);
      float4 left = inTex.sample(inSampler, uv - float2(oneTexel.x, 0.0));
      float4 right = inTex.sample(inSampler, uv + float2(oneTexel.x, 0.0));
      float4 up = inTex.sample(inSampler, uv - float2(0.0, oneTexel.y));
      float4 down = inTex.sample(inSampler, uv + float2(0.0, oneTexel.y));
      float total = clamp(abs(center.a - left.a) + abs(center.a - right.a) + abs(center.a - up.a) +
                              abs(center.a - down.a),
                          0.0, 1.0);
      float3 outColor = center.rgb * center.a + left.rgb * left.a + right.rgb * right.a +
                        up.rgb * up.a + down.rgb * down.a;
      return float4(outColor * 0.2, total);
    }
    if (FS_VARIANT == MR_FS_INVERT) {
      constant MRInvertConfig &invert = *(constant MRInvertConfig *)config;
      float4 diffuse = inTex.sample(inSampler, uv);
      float4 outColor = mix(diffuse, 1.0 - diffuse, invert.InverseAmount);
      return float4(outColor.rgb, 1.0);
    }
  }
  return float4(1.0, 0.0, 1.0, 1.0);
}

vertex VOut sprite_vertex(uint vid [[vertex_id]],
                          constant MRSpriteAnimationInfo &info [[buffer(MR_SLOT_SPRITE_ANIMATION_INFO)]]) {
  const float2 positions[6] = {float2(0.0, 0.0), float2(1.0, 0.0), float2(0.0, 1.0),
                               float2(0.0, 1.0), float2(1.0, 0.0), float2(1.0, 1.0)};
  VOut o = mr_vout();
  uint index = vid & 7u;
  float2 corner = positions[min(index, 5u)];
  float2 padding = float2(info.UPadding, info.VPadding);
  o.position = mr_clip(mr_mat4(info.ProjectionMatrix) * mr_mat4(info.SpriteMatrix) * float4(corner, 0.0, 1.0));
  o.uv0 = corner + padding * (corner * 2.0 - 1.0);
  o.progress = float(vid >> 3) / 1000.0;
  return o;
}

fragment float4 sprite_fragment(VOut in [[stage_in]],
                                constant MRSpriteAnimationInfo &info [[buffer(MR_SLOT_SPRITE_ANIMATION_INFO)]],
                                texture2d<float> current [[texture(0)]],
                                sampler currentSampler [[sampler(0)]],
                                texture2d<float> next [[texture(1), function_constant(SPRITE_FS_INTERPOLATE)]],
                                sampler nextSampler [[sampler(1), function_constant(SPRITE_FS_INTERPOLATE)]]) {
  float lod = float(info.MipMapLevel);
  float4 color = current.sample(currentSampler, in.uv0, level(lod));
  if (SPRITE_FS_INTERPOLATE) {
    color = mix(color, next.sample(nextSampler, in.uv0, level(lod)), in.progress);
  }
  return color;
}

constant bool HAS_ALPHA_CUTOUT = is_function_constant_defined(ALPHA_CUTOUT);
constant int PORTAL_LAYER_COUNT = is_function_constant_defined(PORTAL_LAYERS) ? PORTAL_LAYERS : 15;

constant bool TERRAIN_SECTION = VS_VARIANT == MR_VS_TERRAIN;
constant bool TERRAIN_DT = VS_VARIANT != MR_VS_TERRAIN;
constant bool TERRAIN_VS_LIGHTMAP = VS_VARIANT == MR_VS_TERRAIN || VS_VARIANT == MR_VS_BLOCK;

constant bool ENTITY_VS_LIGHTING = (VS_VARIANT == MR_VS_ENTITY && !NO_CARDINAL_LIGHTING) || VS_VARIANT == MR_VS_ITEM;
constant bool ENTITY_VS_LIGHTMAP = (VS_VARIANT == MR_VS_ENTITY && !EMISSIVE) || VS_VARIANT == MR_VS_ITEM ||
                                   VS_VARIANT == MR_VS_LEASH;
constant bool ENTITY_VS_OVERLAY = VS_VARIANT == MR_VS_ENTITY && !NO_OVERLAY;
constant bool ENTITY_FS_FOG = FS_VARIANT == MR_FS_ENTITY || FS_VARIANT == MR_FS_ITEM || FS_VARIANT == MR_FS_GLINT ||
                              FS_VARIANT == MR_FS_POSITION || FS_VARIANT == MR_FS_BEACON_BEAM ||
                              FS_VARIANT == MR_FS_END_PORTAL || FS_VARIANT == MR_FS_ENTITY_SHADOW ||
                              FS_VARIANT == MR_FS_LEASH || FS_VARIANT == MR_FS_LIGHTNING;
constant bool ENTITY_FS_DT = FS_VARIANT != MR_FS_LEASH && FS_VARIANT != MR_FS_END_PORTAL;
constant bool ENTITY_FS_TEX0 = FS_VARIANT == MR_FS_ENTITY || FS_VARIANT == MR_FS_ITEM || FS_VARIANT == MR_FS_GLINT ||
                               FS_VARIANT == MR_FS_BEACON_BEAM || FS_VARIANT == MR_FS_END_PORTAL ||
                               FS_VARIANT == MR_FS_ENTITY_SHADOW || FS_VARIANT == MR_FS_OUTLINE;
constant bool ENTITY_FS_PORTAL = FS_VARIANT == MR_FS_END_PORTAL;
constant bool ENTITY_FS_DISSOLVE = FS_VARIANT == MR_FS_ENTITY && DISSOLVE;
constant bool ENTITY_FS_GLOBALS = FS_VARIANT == MR_FS_GLINT || FS_VARIANT == MR_FS_END_PORTAL;
constant bool ENTITY_FS_PROJECTION = FS_VARIANT == MR_FS_BEACON_BEAM;

constant bool WORLD_VS_CLOUDS = VS_VARIANT == MR_VS_CLOUDS;
constant bool WORLD_VS_LIGHTMAP = VS_VARIANT == MR_VS_PARTICLE;
constant bool WORLD_FS_TEX0 = FS_VARIANT != MR_FS_CLOUDS;
constant bool WORLD_FS_FOG = FS_VARIANT != MR_FS_WORLD_BORDER;

struct WOut {
  float4 position [[position]];
  float4 color;
  float4 backColor;
  float4 lightColor;
  float4 overlay;
  float4 texProj;
  float4 flatColor [[flat]];
  float2 uv0;
  float sphericalDistance;
  float cylindricalDistance;
};

static inline WOut mr_wout() {
  WOut o;
  o.position = float4(0.0, 0.0, 0.0, 1.0);
  o.color = float4(1.0);
  o.backColor = float4(1.0);
  o.lightColor = float4(1.0);
  o.overlay = float4(0.0);
  o.texProj = float4(0.0);
  o.flatColor = float4(1.0);
  o.uv0 = float2(0.0);
  o.sphericalDistance = 0.0;
  o.cylindricalDistance = 0.0;
  return o;
}

static inline float2 mr_compute_light(constant MRLighting &l, float3 normal) {
  return float2(dot(mr_vec3(l.Light0_Direction), normal), dot(mr_vec3(l.Light1_Direction), normal));
}

static inline float4 mr_mix_light_separate(float2 light, float4 color) {
  float2 value = max(float2(0.0), light);
  float accum = min(1.0, (value.x + value.y) * 0.6 + 0.4);
  return float4(color.rgb * accum, color.a);
}

static inline float mr_total_fog(constant MRFog &fog, float sph, float cyl) {
  return max(mr_linear_fog(sph, fog.FogEnvironmentalStart, fog.FogEnvironmentalEnd),
             mr_linear_fog(cyl, fog.FogRenderDistanceStart, fog.FogRenderDistanceEnd));
}

static inline float3 mr_normal(VIn in) {
  float3 n = float3(0.0, 1.0, 0.0);
  if (HAS_NORMAL) {
    n = in.normal;
  }
  return n;
}

static inline float4 mr_sample_nearest(texture2d<float> tex, sampler s, float2 uv, float2 pixelSize, float2 du,
                                       float2 dv, float2 texelScreenSize) {
  float2 uvTexel = uv / pixelSize;
  float2 center = round(uvTexel) - 0.5;
  float2 offset = uvTexel - center;
  offset = (offset - 0.5) * pixelSize / texelScreenSize + 0.5;
  offset = clamp(offset, 0.0, 1.0);
  return tex.sample(s, (center + offset) * pixelSize, gradient2d(du, dv));
}

static inline float4 mr_sample_terrain(texture2d<float> tex, sampler s, float2 uv, float2 pixelSize, bool rgss) {
  float2 du = dfdx(uv);
  float2 dv = dfdy(uv);
  float2 texelScreenSize = sqrt(du * du + dv * dv);
  float4 nearest = mr_sample_nearest(tex, s, uv, pixelSize, du, dv, texelScreenSize);
  if (!rgss) {
    return nearest;
  }
  float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);
  float minPixelSize = min(pixelSize.x, pixelSize.y);
  float blend = smoothstep(minPixelSize, minPixelSize * 2.0, maxTexelSize);
  float duLength = length(du);
  float dvLength = length(dv);
  float effective = sqrt(min(duLength, dvLength) * max(duLength, dvLength));
  float mipExact = max(0.0, log2(effective / minPixelSize));
  float mipLow = floor(mipExact);
  float mipBlend = fract(mipExact);
  const float2 offsets[4] = {float2(0.125, 0.375), float2(-0.125, -0.375), float2(0.375, -0.125),
                             float2(-0.375, 0.125)};
  float4 low = float4(0.0);
  float4 high = float4(0.0);
  for (int i = 0; i < 4; i++) {
    float2 p = uv + offsets[i] * pixelSize;
    low += tex.sample(s, p, level(mipLow));
    high += tex.sample(s, p, level(mipLow + 1.0));
  }
  float4 rgssColor = mix(low * 0.25, high * 0.25, mipBlend);
  return mix(nearest, rgssColor, blend);
}

vertex VOut terrain_vertex(VIn in [[stage_in]],
                           constant MRProjection &proj [[buffer(MR_SLOT_PROJECTION)]],
                           constant MRChunkSection &section [[buffer(MR_SLOT_CHUNK_SECTION), function_constant(TERRAIN_SECTION)]],
                           constant MRTerrainUniform &terrain [[buffer(MR_SLOT_TERRAIN_UNIFORM), function_constant(TERRAIN_SECTION)]],
                           constant MRGlobals &globals [[buffer(MR_SLOT_GLOBALS), function_constant(TERRAIN_SECTION)]],
                           constant MRDynamicTransforms &dt [[buffer(MR_SLOT_DYNAMIC_TRANSFORMS), function_constant(TERRAIN_DT)]],
                           texture2d<float> lightmap [[texture(2), function_constant(TERRAIN_VS_LIGHTMAP)]],
                           sampler lightmapSampler [[sampler(2), function_constant(TERRAIN_VS_LIGHTMAP)]]) {
  VOut o = mr_vout();
  float3 p = mr_position(in);
  float4x4 modelView;
  if (TERRAIN_SECTION) {
    int3 chunk = int3(section.ChunkPosition[0], section.ChunkPosition[1], section.ChunkPosition[2]);
    int3 camera = int3(globals.CameraBlockPos[0], globals.CameraBlockPos[1], globals.CameraBlockPos[2]);
    p = p + float3(chunk - camera) + mr_vec3(globals.CameraOffset);
    modelView = mr_mat4(terrain.ModelViewMat);
  } else {
    if (VS_VARIANT == MR_VS_BLOCK) {
      p = p + mr_vec3(dt.ModelOffset);
    }
    modelView = mr_mat4(dt.ModelViewMat);
  }
  o.position = mr_clip(mr_mat4(proj.ProjMat) * modelView * float4(p, 1.0));
  o.sphericalDistance = mr_fog_spherical(p);
  o.cylindricalDistance = mr_fog_cylindrical(p);
  o.color = mr_color(in);
  if (TERRAIN_VS_LIGHTMAP) {
    if (HAS_UV2) {
      o.color *= mr_lightmap(lightmap, lightmapSampler, in.uv2);
    }
  }
  o.uv0 = mr_uv0(in);
  return o;
}

fragment float4 terrain_fragment(VOut in [[stage_in]],
                                 constant MRFog &fog [[buffer(MR_SLOT_FOG)]],
                                 constant MRChunkSection &section [[buffer(MR_SLOT_CHUNK_SECTION), function_constant(TERRAIN_SECTION)]],
                                 constant MRTerrainUniform &terrain [[buffer(MR_SLOT_TERRAIN_UNIFORM), function_constant(TERRAIN_SECTION)]],
                                 constant MRGlobals &globals [[buffer(MR_SLOT_GLOBALS), function_constant(TERRAIN_SECTION)]],
                                 constant MRDynamicTransforms &dt [[buffer(MR_SLOT_DYNAMIC_TRANSFORMS), function_constant(TERRAIN_DT)]],
                                 texture2d<float> tex0 [[texture(0)]], sampler s0 [[sampler(0)]]) {
  float4 color;
  if (TERRAIN_SECTION) {
    float2 pixelSize = 1.0 / float2(terrain.TextureSize[0], terrain.TextureSize[1]);
    color = mr_sample_terrain(tex0, s0, in.uv0, pixelSize, globals.UseRgss == 1) * in.color;
    color = mix(mr_vec4(fog.FogColor) * float4(1.0, 1.0, 1.0, color.a), color, section.ChunkVisibility);
  } else if (FS_VARIANT == MR_FS_CRUMBLING) {
    color = tex0.sample(s0, in.uv0) * in.color;
    if (color.a < 0.1) {
      discard_fragment();
    }
    color *= mr_vec4(dt.ColorModulator);
  } else {
    color = tex0.sample(s0, in.uv0) * in.color * mr_vec4(dt.ColorModulator);
  }
  if (HAS_ALPHA_CUTOUT) {
    if (color.a < ALPHA_CUTOUT) {
      discard_fragment();
    }
  }
  return mr_fog(fog, color, in.sphericalDistance, in.cylindricalDistance);
}

vertex WOut entity_vertex(VIn in [[stage_in]],
                          constant MRProjection &proj [[buffer(MR_SLOT_PROJECTION)]],
                          constant MRDynamicTransforms &dt [[buffer(MR_SLOT_DYNAMIC_TRANSFORMS)]],
                          constant MRLighting &lighting [[buffer(MR_SLOT_LIGHTING), function_constant(ENTITY_VS_LIGHTING)]],
                          texture2d<float> overlayTex [[texture(1), function_constant(ENTITY_VS_OVERLAY)]],
                          texture2d<float> lightmap [[texture(2), function_constant(ENTITY_VS_LIGHTMAP)]],
                          sampler lightmapSampler [[sampler(2), function_constant(ENTITY_VS_LIGHTMAP)]]) {
  WOut o = mr_wout();
  float3 p = mr_position(in);
  float4 viewPosition = mr_mat4(dt.ModelViewMat) * float4(p, 1.0);
  float4 glPosition = mr_mat4(proj.ProjMat) * viewPosition;
  o.position = mr_clip(glPosition);
  o.sphericalDistance = mr_fog_spherical(p);
  o.cylindricalDistance = mr_fog_cylindrical(p);
  float4 color = mr_color(in);
  o.uv0 = mr_uv0(in);
  if (VS_VARIANT == MR_VS_ENTITY) {
    if (ENTITY_VS_LIGHTING) {
      float2 light = mr_compute_light(lighting, mr_normal(in));
      if (PER_FACE_LIGHTING) {
        o.backColor = mr_mix_light_separate(-light, color);
        o.color = mr_mix_light_separate(light, color);
      } else {
        o.color = mr_mix_light_separate(light, color);
      }
    } else {
      o.color = color;
      o.backColor = color;
    }
    if (ENTITY_VS_LIGHTMAP) {
      if (HAS_UV2) {
        o.lightColor = mr_lightmap(lightmap, lightmapSampler, in.uv2);
      }
    }
    if (ENTITY_VS_OVERLAY) {
      if (HAS_UV1) {
        uint2 size = uint2(overlayTex.get_width(), overlayTex.get_height());
        uint2 texel = uint2(clamp(int2(in.uv1), int2(0), int2(size) - 1));
        o.overlay = overlayTex.read(texel, 0);
      }
    }
    if (APPLY_TEXTURE_MATRIX) {
      o.uv0 = (mr_mat4(dt.TextureMat) * float4(o.uv0, 0.0, 1.0)).xy;
    }
  } else if (VS_VARIANT == MR_VS_ITEM) {
    o.color = mr_mix_light_separate(mr_compute_light(lighting, mr_normal(in)), color);
    if (ENTITY_VS_LIGHTMAP) {
      if (HAS_UV2) {
        o.color *= mr_lightmap(lightmap, lightmapSampler, in.uv2);
      }
    }
  } else if (VS_VARIANT == MR_VS_GLINT || VS_VARIANT == MR_VS_WORLD_BORDER) {
    o.uv0 = (mr_mat4(dt.TextureMat) * float4(o.uv0, 0.0, 1.0)).xy;
  } else if (VS_VARIANT == MR_VS_END_PORTAL) {
    float4 projection = glPosition * 0.5;
    projection.xy = float2(projection.x + projection.w, projection.y + projection.w);
    projection.zw = glPosition.zw;
    o.texProj = projection;
  } else if (VS_VARIANT == MR_VS_LEASH) {
    float4 lm = float4(1.0);
    if (ENTITY_VS_LIGHTMAP) {
      if (HAS_UV2) {
        lm = mr_lightmap(lightmap, lightmapSampler, in.uv2);
      }
    }
    o.flatColor = color * mr_vec4(dt.ColorModulator) * lm;
  } else {
    o.color = color;
  }
  return o;
}

static float4x4 mr_portal_layer(float layer, float gameTime) {
  float4x4 scaleTranslate = float4x4(float4(0.5, 0.0, 0.0, 0.25), float4(0.0, 0.5, 0.0, 0.25),
                                     float4(0.0, 0.0, 1.0, 0.0), float4(0.0, 0.0, 0.0, 1.0));
  float4x4 translate = float4x4(float4(1.0, 0.0, 0.0, 17.0 / layer),
                                float4(0.0, 1.0, 0.0, (2.0 + layer / 1.5) * (gameTime * 1.5)),
                                float4(0.0, 0.0, 1.0, 0.0), float4(0.0, 0.0, 0.0, 1.0));
  float r = (layer * layer * 4321.0 + layer * 9.0) * 2.0 * (M_PI_F / 180.0);
  float2x2 rotate = float2x2(float2(cos(r), -sin(r)), float2(sin(r), cos(r)));
  float2x2 scale = float2x2((4.5 - layer / 4.0) * 2.0);
  float2x2 sr = scale * rotate;
  float4x4 sr4 = float4x4(float4(sr[0], 0.0, 0.0), float4(sr[1], 0.0, 0.0), float4(0.0, 0.0, 1.0, 0.0),
                          float4(0.0, 0.0, 0.0, 1.0));
  return sr4 * translate * scaleTranslate;
}

fragment float4 entity_fragment(WOut in [[stage_in]], bool frontFacing [[front_facing]],
                                constant MRDynamicTransforms &dt [[buffer(MR_SLOT_DYNAMIC_TRANSFORMS), function_constant(ENTITY_FS_DT)]],
                                constant MRFog &fog [[buffer(MR_SLOT_FOG), function_constant(ENTITY_FS_FOG)]],
                                constant MRGlobals &globals [[buffer(MR_SLOT_GLOBALS), function_constant(ENTITY_FS_GLOBALS)]],
                                constant MRProjection &proj [[buffer(MR_SLOT_PROJECTION), function_constant(ENTITY_FS_PROJECTION)]],
                                texture2d<float> tex0 [[texture(0), function_constant(ENTITY_FS_TEX0)]],
                                sampler s0 [[sampler(0), function_constant(ENTITY_FS_TEX0)]],
                                texture2d<float> tex1 [[texture(1), function_constant(ENTITY_FS_PORTAL)]],
                                sampler s1 [[sampler(1), function_constant(ENTITY_FS_PORTAL)]],
                                texture2d<float> dissolve [[texture(3), function_constant(ENTITY_FS_DISSOLVE)]],
                                sampler dissolveSampler [[sampler(3), function_constant(ENTITY_FS_DISSOLVE)]]) {
  float sph = in.sphericalDistance;
  float cyl = in.cylindricalDistance;
  if (FS_VARIANT == MR_FS_ENTITY) {
    float4 color = tex0.sample(s0, in.uv0);
    if (HAS_ALPHA_CUTOUT) {
      if (color.a < ALPHA_CUTOUT) {
        discard_fragment();
      }
    }
    float4 face = in.color;
    if (PER_FACE_LIGHTING) {
      face = frontFacing ? in.color : in.backColor;
    }
    if (ENTITY_FS_DISSOLVE) {
      if (face.a < dissolve.sample(dissolveSampler, in.uv0).a) {
        discard_fragment();
      }
      face.a = 1.0;
    }
    color *= face * mr_vec4(dt.ColorModulator);
    if (!NO_OVERLAY) {
      color.rgb = mix(in.overlay.rgb, color.rgb, in.overlay.a);
    }
    if (!EMISSIVE) {
      color *= in.lightColor;
    }
    return mr_fog(fog, color, sph, cyl);
  }
  if (FS_VARIANT == MR_FS_ITEM) {
    float4 color = tex0.sample(s0, in.uv0);
    if (HAS_ALPHA_CUTOUT) {
      if (color.a < ALPHA_CUTOUT) {
        discard_fragment();
      }
    }
    color *= in.color * mr_vec4(dt.ColorModulator);
    return mr_fog(fog, color, sph, cyl);
  }
  if (FS_VARIANT == MR_FS_GLINT) {
    float4 color = tex0.sample(s0, in.uv0) * mr_vec4(dt.ColorModulator);
    if (color.a < 0.1) {
      discard_fragment();
    }
    float fade = (1.0 - mr_total_fog(fog, sph, cyl)) * globals.GlintAlpha;
    return float4(color.rgb * fade, color.a);
  }
  if (FS_VARIANT == MR_FS_POSITION) {
    return mr_fog(fog, mr_vec4(dt.ColorModulator), sph, cyl);
  }
  if (FS_VARIANT == MR_FS_BEACON_BEAM) {
    float4 color = tex0.sample(s0, in.uv0) * in.color * mr_vec4(dt.ColorModulator);
    float4x4 projMat = mr_mat4(proj.ProjMat);
    float distance = -projMat[3].z / ((in.position.z) * -2.0 + 1.0 - projMat[2].z);
    return mr_fog(fog, color, distance, distance);
  }
  if (FS_VARIANT == MR_FS_END_PORTAL) {
    const float3 colors[16] = {
        float3(0.022087, 0.098399, 0.110818), float3(0.011892, 0.095924, 0.089485),
        float3(0.027636, 0.101689, 0.100326), float3(0.046564, 0.109883, 0.114838),
        float3(0.064901, 0.117696, 0.097189), float3(0.063761, 0.086895, 0.123646),
        float3(0.084817, 0.111994, 0.166380), float3(0.097489, 0.154120, 0.091064),
        float3(0.106152, 0.131144, 0.195191), float3(0.097721, 0.110188, 0.187229),
        float3(0.133516, 0.138278, 0.148582), float3(0.070006, 0.243332, 0.235792),
        float3(0.196766, 0.142899, 0.214696), float3(0.047281, 0.315338, 0.321970),
        float3(0.204675, 0.390010, 0.302066), float3(0.080955, 0.314821, 0.661491)};
    float4 tp = in.texProj;
    float3 color = tex0.sample(s0, tp.xy / tp.w).rgb * colors[0];
    for (int i = 0; i < PORTAL_LAYER_COUNT && i < 16; i++) {
      float4 q = tp * mr_portal_layer(float(i + 1), globals.GameTime);
      color += tex1.sample(s1, q.xy / q.w).rgb * colors[i];
    }
    return mr_fog(fog, float4(color, 1.0), sph, cyl);
  }
  if (FS_VARIANT == MR_FS_ENTITY_SHADOW) {
    float4 color = tex0.sample(s0, clamp(in.uv0, 0.0, 1.0)) * in.color * mr_vec4(dt.ColorModulator);
    return mr_fog(fog, color, sph, cyl);
  }
  if (FS_VARIANT == MR_FS_LEASH) {
    return mr_fog(fog, in.flatColor, sph, cyl);
  }
  if (FS_VARIANT == MR_FS_LIGHTNING) {
    return in.color * mr_vec4(dt.ColorModulator) * (1.0 - mr_total_fog(fog, sph, cyl));
  }
  if (FS_VARIANT == MR_FS_OUTLINE) {
    float4 texel = tex0.sample(s0, in.uv0);
    if (texel.a == 0.0) {
      discard_fragment();
    }
    float4 modulator = mr_vec4(dt.ColorModulator);
    return float4(modulator.rgb * in.color.rgb, modulator.a);
  }
  return mr_vec4(dt.ColorModulator);
}

vertex VOut particle_vertex(VIn in [[stage_in]],
                            constant MRProjection &proj [[buffer(MR_SLOT_PROJECTION)]],
                            constant MRDynamicTransforms &dt [[buffer(MR_SLOT_DYNAMIC_TRANSFORMS)]],
                            texture2d<float> lightmap [[texture(2)]], sampler lightmapSampler [[sampler(2)]]) {
  VOut o = mr_vout();
  float3 p = mr_position(in);
  o.position = mr_transform(proj, dt, p);
  o.sphericalDistance = mr_fog_spherical(p);
  o.cylindricalDistance = mr_fog_cylindrical(p);
  o.uv0 = mr_uv0(in);
  o.color = mr_color(in);
  if (HAS_UV2) {
    o.color *= mr_lightmap(lightmap, lightmapSampler, in.uv2);
  }
  return o;
}

fragment float4 particle_fragment(VOut in [[stage_in]],
                                  constant MRDynamicTransforms &dt [[buffer(MR_SLOT_DYNAMIC_TRANSFORMS)]],
                                  constant MRFog &fog [[buffer(MR_SLOT_FOG)]],
                                  texture2d<float> tex0 [[texture(0)]], sampler s0 [[sampler(0)]]) {
  float4 color = tex0.sample(s0, in.uv0) * in.color * mr_vec4(dt.ColorModulator);
  if (color.a < 0.1) {
    discard_fragment();
  }
  return mr_fog(fog, color, in.sphericalDistance, in.cylindricalDistance);
}

vertex VOut world_vertex(VIn in [[stage_in]], uint vid [[vertex_id]],
                         constant MRProjection &proj [[buffer(MR_SLOT_PROJECTION)]],
                         constant MRDynamicTransforms &dt [[buffer(MR_SLOT_DYNAMIC_TRANSFORMS)]],
                         constant MRCloudInfo &cloud [[buffer(MR_SLOT_CLOUD_INFO), function_constant(WORLD_VS_CLOUDS)]],
                         constant char *faces [[buffer(MR_SLOT_CLOUD_FACES), function_constant(WORLD_VS_CLOUDS)]],
                         texture2d<float> lightmap [[texture(2), function_constant(WORLD_VS_LIGHTMAP)]],
                         sampler lightmapSampler [[sampler(2), function_constant(WORLD_VS_LIGHTMAP)]]) {
  VOut o = mr_vout();
  if (WORLD_VS_CLOUDS) {
    const float3 vertices[24] = {
        float3(1, 0, 0), float3(1, 0, 1), float3(0, 0, 1), float3(0, 0, 0), float3(0, 1, 0), float3(0, 1, 1),
        float3(1, 1, 1), float3(1, 1, 0), float3(0, 0, 0), float3(0, 1, 0), float3(1, 1, 0), float3(1, 0, 0),
        float3(1, 0, 1), float3(1, 1, 1), float3(0, 1, 1), float3(0, 0, 1), float3(0, 0, 1), float3(0, 1, 1),
        float3(0, 1, 0), float3(0, 0, 0), float3(1, 0, 0), float3(1, 1, 0), float3(1, 1, 1), float3(1, 0, 1)};
    const float4 faceColors[6] = {float4(0.7, 0.7, 0.7, 1.0), float4(1.0, 1.0, 1.0, 1.0),
                                  float4(0.8, 0.8, 0.8, 1.0), float4(0.8, 0.8, 0.8, 1.0),
                                  float4(0.9, 0.9, 0.9, 1.0), float4(0.9, 0.9, 0.9, 1.0)};
    int id = int(vid);
    int quadVertex = id % 4;
    int index = (id / 4) * 3;
    int cellX = int(faces[index]);
    int cellZ = int(faces[index + 1]);
    int flags = int(faces[index + 2]);
    int direction = flags & 7;
    bool inside = (flags & (1 << 4)) != 0;
    bool topColor = (flags & (1 << 5)) != 0;
    cellX = (cellX << 1) | ((flags & (1 << 7)) >> 7);
    cellZ = (cellZ << 1) | ((flags & (1 << 6)) >> 6);
    float3 faceVertex = vertices[min((direction * 4) + (inside ? 3 - quadVertex : quadVertex), 23)];
    float3 cellSize = mr_vec3(cloud.CellSize);
    float3 pos = faceVertex * cellSize + float3(float(cellX), 0.0, float(cellZ)) * cellSize + mr_vec3(cloud.CloudOffset);
    o.position = mr_transform(proj, dt, pos);
    o.sphericalDistance = mr_fog_spherical(pos);
    o.color = (topColor ? faceColors[1] : faceColors[min(direction, 5)]) * mr_vec4(cloud.CloudColor);
    return o;
  }
  float3 p = mr_position(in);
  if (VS_VARIANT == MR_VS_WORLD_BORDER) {
    p += mr_vec3(dt.ModelOffset);
    o.position = mr_transform(proj, dt, p);
    o.uv0 = (mr_mat4(dt.TextureMat) * float4(mr_uv0(in), 0.0, 1.0)).xy;
    return o;
  }
  o.position = mr_transform(proj, dt, p);
  o.sphericalDistance = mr_fog_spherical(p);
  o.cylindricalDistance = mr_fog_cylindrical(p);
  o.uv0 = mr_uv0(in);
  o.color = mr_color(in);
  if (WORLD_VS_LIGHTMAP) {
    if (HAS_UV2) {
      o.color *= mr_lightmap(lightmap, lightmapSampler, in.uv2);
    }
  }
  return o;
}

fragment float4 world_fragment(VOut in [[stage_in]],
                               constant MRDynamicTransforms &dt [[buffer(MR_SLOT_DYNAMIC_TRANSFORMS), function_constant(WORLD_FS_TEX0)]],
                               constant MRFog &fog [[buffer(MR_SLOT_FOG), function_constant(WORLD_FS_FOG)]],
                               texture2d<float> tex0 [[texture(0), function_constant(WORLD_FS_TEX0)]],
                               sampler s0 [[sampler(0), function_constant(WORLD_FS_TEX0)]]) {
  if (FS_VARIANT == MR_FS_CLOUDS) {
    float4 color = in.color;
    color.a *= 1.0 - mr_linear_fog(in.sphericalDistance, 0.0, fog.FogCloudsEnd);
    return color;
  }
  if (FS_VARIANT == MR_FS_WORLD_BORDER) {
    float4 color = tex0.sample(s0, in.uv0);
    if (color.a == 0.0) {
      discard_fragment();
    }
    return color * mr_vec4(dt.ColorModulator);
  }
  float4 color = tex0.sample(s0, in.uv0) * in.color * mr_vec4(dt.ColorModulator);
  if (color.a < 0.1) {
    discard_fragment();
  }
  return mr_fog(fog, color, in.sphericalDistance, in.cylindricalDistance);
}

vertex VOut fallback_vertex(VIn in [[stage_in]],
                            constant MRProjection &proj [[buffer(MR_SLOT_PROJECTION), function_constant(HAS_PROJECTION)]],
                            constant MRDynamicTransforms &dt [[buffer(MR_SLOT_DYNAMIC_TRANSFORMS), function_constant(HAS_DYNAMIC_TRANSFORMS)]]) {
  VOut o = mr_vout();
  float4 p = float4(mr_position(in), 1.0);
  if (HAS_DYNAMIC_TRANSFORMS) {
    p = mr_mat4(dt.ModelViewMat) * p;
  }
  if (HAS_PROJECTION) {
    p = mr_mat4(proj.ProjMat) * p;
  }
  o.position = mr_clip(p);
  return o;
}

vertex VOut fallback_vertex_empty(uint vid [[vertex_id]]) {
  VOut o = mr_vout();
  float2 uv = float2(float((vid << 1) & 2), float(vid & 2));
  o.position = mr_clip(float4(uv * 2.0 - 1.0, 0.0, 1.0));
  return o;
}

fragment float4 fallback_fragment(VOut in [[stage_in]]) { return float4(1.0, 0.0, 1.0, 1.0); }

struct PresentOut {
  float4 position [[position]];
  float2 uv;
};

vertex PresentOut mr_present_vertex(uint vid [[vertex_id]]) {
  PresentOut o;
  float2 uv = float2(float((vid << 1) & 2), float(vid & 2));
  o.position = float4(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0, 0.0, 1.0);
  o.uv = uv;
  return o;
}

fragment float4 mr_present_fragment(PresentOut in [[stage_in]], texture2d<float> source [[texture(0)]]) {
  constexpr sampler nearestSampler(coord::normalized, filter::nearest, address::clamp_to_edge);
  float4 c = source.sample(nearestSampler, float2(in.uv.x, 1.0 - in.uv.y));
  return float4(c.rgb, 1.0);
}

struct ClearParams {
  float color[4];
  float depth;
};

struct ClearOut {
  float4 position [[position]];
};

vertex ClearOut mr_clear_vertex(uint vid [[vertex_id]], constant ClearParams &params [[buffer(0)]]) {
  ClearOut o;
  float2 uv = float2(float((vid << 1) & 2), float(vid & 2));
  o.position = float4(uv * 2.0 - 1.0, params.depth, 1.0);
  return o;
}

fragment float4 mr_clear_fragment(ClearOut in [[stage_in]], constant ClearParams &params [[buffer(0)]]) {
  return float4(params.color[0], params.color[1], params.color[2], params.color[3]);
}
