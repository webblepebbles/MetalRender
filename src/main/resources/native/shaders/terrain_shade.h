#ifndef MR_TERRAIN_SHADE_H
#define MR_TERRAIN_SHADE_H

#include <metal_stdlib>
#include "terrain_decode.h"
using namespace metal;

struct FogUniforms {
    float4 color;
    float4 ranges;
};
inline float vanilla_linear_fog(float dist, float start, float end) {
    if (end <= start) {
        return 0.0f;
    }
    if (dist <= start) {
        return 0.0f;
    } else if (dist >= end) {
        return 1.0f;
    }
    return (dist - start) / (end - start);
}
inline float vanilla_fog_factor(float2 sphCyl, constant FogUniforms& fog) {
    float env = vanilla_linear_fog(sphCyl.x, fog.ranges.x, fog.ranges.y);
    float ren = vanilla_linear_fog(sphCyl.y, fog.ranges.z, fog.ranges.w);
    return max(env, ren);
}
inline half3 vanilla_apply_fog(half3 rgb, float2 sphCyl, constant FogUniforms& fog) {
    float f = vanilla_fog_factor(sphCyl, fog);
    return mix(rgb, half3(fog.color.rgb), half(f * fog.color.a));
}

struct TerrainFrame {
    int4   camBlock;
    float4 camFrac;
};

struct TerrainFogRecon {
    float4 row0;
    float4 row1;
    float4 row2;
    float4 proj;
    float4 proj2;
    float4 viewport;
};

inline float2 terrain_fog_dist(float4 pos, constant TerrainFogRecon& r) {
    float w = 1.0f / pos.w;
    float ndcx = 2.0f * pos.x / r.viewport.x - 1.0f;
    float ndcy = 1.0f - 2.0f * pos.y / r.viewport.y;
    float vz = w / r.proj2.x;
    float vx = (ndcx * w - r.proj.z * vz) / r.proj.x;
    float vy = (ndcy * w - r.proj.w * vz) / r.proj.y;
    float3 v = float3(vx, vy, vz);
    float3 world = float3(dot(r.row0.xyz, v) + r.row0.w, dot(r.row1.xyz, v) + r.row1.w,
                          dot(r.row2.xyz, v) + r.row2.w);
    return float2(length(world), max(length(world.xz), abs(world.y)));
}


constant sampler kTerrLightSampler(mag_filter::linear, min_filter::linear, mip_filter::nearest,
                                   address::clamp_to_edge);
constant sampler kTerrAtlasSampler(mag_filter::nearest, min_filter::linear, mip_filter::linear);
constant sampler kTerrAtlasSamplerAniso(mag_filter::nearest, min_filter::linear, mip_filter::linear,
                                        max_anisotropy(8));

inline half4 terrain_shade_solid(half4 tex, half4 color, half2 lightUV, float2 fogDist,
                                 texture2d<half> lightmap, constant FogUniforms& fog) {
    half3 rgb = tex.rgb * color.rgb;
    half3 light = lightmap.sample(kTerrLightSampler, float2(lightUV)).rgb;
    rgb *= max(light, half3(0.04h));
    rgb = vanilla_apply_fog(rgb, fogDist, fog);
    return half4(rgb, half(1.0h));
}

inline half4 terrain_shade_cutout(half4 texColor, half4 color, half2 lightUV, float2 fogDist,
                                  texture2d<half> lightmap, constant FogUniforms& fog) {
    half vertAlpha = color.a;
    if (texColor.a < half(0.5h)) {
        if (vertAlpha > half(0.994h) && vertAlpha < half(0.998h)) {
            texColor.a = half(1.0h);
        } else {
            discard_fragment();
        }
    }
    half3 rgb = texColor.rgb * color.rgb;
    half3 light = lightmap.sample(kTerrLightSampler, float2(lightUV)).rgb;
    rgb *= max(light, half3(0.04h));
    rgb = vanilla_apply_fog(rgb, fogDist, fog);
    return half4(rgb, half(1.0h));
}

inline half4 terrain_shade_water(half4 texColor, half4 color, half2 lightUV, float2 fogDist,
                                 texture2d<half> lightmap, constant FogUniforms& fog) {
    half vertAlpha = color.a;
    bool flagged = vertAlpha > half(0.994h) && vertAlpha < half(0.998h);
    bool water = flagged || vertAlpha < half(0.99h);
    half alpha;
    if (water) {
        if (texColor.a < half(0.5h)) {
            discard_fragment();
        }
        alpha = flagged ? half(168.0h / 255.0h) : vertAlpha;
    } else {
        if (texColor.a < half(0.02h)) {
            discard_fragment();
        }
        alpha = texColor.a;
    }
    half3 rgb = texColor.rgb * color.rgb;
    half3 light = lightmap.sample(kTerrLightSampler, float2(lightUV)).rgb;
    rgb *= max(light, half3(0.04h));
    rgb = vanilla_apply_fog(rgb, fogDist, fog);
    return half4(rgb, alpha);
}

#endif
