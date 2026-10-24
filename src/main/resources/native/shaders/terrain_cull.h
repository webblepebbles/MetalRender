#ifndef MR_TERRAIN_CULL_H
#define MR_TERRAIN_CULL_H

#include <metal_stdlib>
#include "terrain_decode.h"
using namespace metal;

struct TerrainCullFrame {
    int4   camBlock;
    float4 camFrac;
    float4 planes[4];
    float  viewDist;
    uint   sectionCount;
    uint   listCapacity;
    uint   pad;
    float4x4 prevViewProj;
    float4 prevOffset;
    uint2  hizSize;
    uint   hizMips;
    uint   hizFlags;
};

inline bool terr_hiz_occluded(constant TerrainCullFrame& f, texture2d<float> hiz, float3 lo, float3 hi) {
    if ((f.hizFlags & 1u) == 0u) {
        return false;
    }
    bool rev = (f.hizFlags & 2u) != 0u;
    lo -= 0.25f;
    hi += 0.25f;
    float2 nmin = float2(1e9f);
    float2 nmax = float2(-1e9f);
    float nearest = rev ? 0.0f : 1.0f;
    for (uint i = 0u; i < 8u; i++) {
        float3 c = float3((i & 1u) ? hi.x : lo.x, (i & 2u) ? hi.y : lo.y, (i & 4u) ? hi.z : lo.z);
        float4 clip = f.prevViewProj * float4(c + f.prevOffset.xyz, 1.0f);
        if (clip.w <= 0.05f) {
            return false;
        }
        float3 ndc = clip.xyz / clip.w;
        nmin = min(nmin, ndc.xy);
        nmax = max(nmax, ndc.xy);
        nearest = rev ? max(nearest, ndc.z) : min(nearest, ndc.z);
    }
    nmin = max(nmin, float2(-1.0f));
    nmax = min(nmax, float2(1.0f));
    if (any(nmin > nmax)) {
        return false;
    }
    float2 full = float2(f.hizSize);
    float2 pmin = float2(nmin.x * 0.5f + 0.5f, 0.5f - nmax.y * 0.5f) * full;
    float2 pmax = float2(nmax.x * 0.5f + 0.5f, 0.5f - nmin.y * 0.5f) * full;
    float extent = max(max(pmax.x - pmin.x, pmax.y - pmin.y), 1.0f);
    uint level = uint(max(ceil(log2(extent)) - 3.0f, 0.0f));
    level = min(level, f.hizMips - 1u);
    float scale = exp2(-(float(level) + 1.0f));
    int2 lim = int2(int(hiz.get_width(level)) - 1, int(hiz.get_height(level)) - 1);
    int2 a = clamp(int2(floor(pmin * scale)), int2(0), lim);
    int2 b = clamp(int2(floor(pmax * scale)), int2(0), lim);
    if (b.x - a.x > 5 || b.y - a.y > 5) {
        return false;
    }
    float far = rev ? 1.0f : 0.0f;
    for (int y = a.y; y <= b.y; y++) {
        for (int x = a.x; x <= b.x; x++) {
            float d = hiz.read(uint2(x, y), level).r;
            far = rev ? min(far, d) : max(far, d);
        }
    }
    return rev ? nearest < far : nearest > far;
}

inline bool terr_box_visible(constant TerrainCullFrame& f, float3 lo, float3 hi) {
    for (uint i = 0u; i < 4u; i++) {
        float4 pl = f.planes[i];
        float3 pv = float3(pl.x > 0.0f ? hi.x : lo.x, pl.y > 0.0f ? hi.y : lo.y, pl.z > 0.0f ? hi.z : lo.z);
        if (dot(pl.xyz, pv) + pl.w < -0.5f) {
            return false;
        }
    }
    return true;
}

inline bool terr_unit_facing(UnitRecord unit, float3 cam, constant TerrainCullFrame& f) {
    uint bucket = (unit.meta >> 5) & 7u;
    if ((f.pad & 1u) != 0u && bucket < 6u) {
        float plane = float(unit.plane) / 256.0f;
        uint axis = bucket >> 1;
        float c = axis == 0u ? cam.y : (axis == 1u ? cam.z : cam.x);
        if ((bucket & 1u) != 0u) {
            if (!(c > plane - 1.0f / 64.0f)) {
                return false;
            }
        } else {
            if (!(c < plane + 1.0f / 64.0f)) {
                return false;
            }
        }
    }
    return true;
}

inline bool terr_unit_box_keep(UnitRecord unit, float3 rel, constant TerrainCullFrame& f,
                               texture2d<float> hiz) {
    uint b = unit.box;
    float3 lo = float3(float(b & 31u), float((b >> 5) & 31u), float((b >> 10) & 31u)) - 8.0f;
    float3 hi = float3(float((b >> 15) & 31u), float((b >> 20) & 31u), float((b >> 25) & 31u)) - 8.0f;
    return terr_box_visible(f, rel + lo, rel + hi) && !terr_hiz_occluded(f, hiz, rel + lo, rel + hi);
}

inline bool terr_unit_keep(UnitRecord unit, float3 cam, float3 rel, constant TerrainCullFrame& f,
                           texture2d<float> hiz) {
    return terr_unit_facing(unit, cam, f) && terr_unit_box_keep(unit, rel, f, hiz);
}


#endif
