#include <metal_stdlib>
#include "terrain_decode.h"
using namespace metal;

struct ChunkVertex {
    uint posHi;
    uint posLo;
    uint color;
    uint texture;
    uint lightData;
};

float3 decodePackedPosition(uint posHi, uint posLo) {
    uint xHi = (posHi >>  0) & 0x3FF;
    uint yHi = (posHi >> 10) & 0x3FF;
    uint zHi = (posHi >> 20) & 0x3FF;
    uint xLo = (posLo >>  0) & 0x3FF;
    uint yLo = (posLo >> 10) & 0x3FF;
    uint zLo = (posLo >> 20) & 0x3FF;
    float x = float((xHi << 10) | xLo) / 1048576.0 * 32.0 - 8.0;
    float y = float((yHi << 10) | yLo) / 1048576.0 * 32.0 - 8.0;
    float z = float((zHi << 10) | zLo) / 1048576.0 * 32.0 - 8.0;
    return float3(x, y, z);
}

float4 decodePackedColor(uint c) {
    float a = float((c >> 24) & 0xFF) / 255.0;
    float r = float((c >> 16) & 0xFF) / 255.0;
    float g = float((c >>  8) & 0xFF) / 255.0;
    float b = float((c >>  0) & 0xFF) / 255.0;
    return float4(r, g, b, a);
}

float2 decodePackedTexCoord(uint tex) {
    float u = float(tex & 0xFFFFu) / 65535.0;
    float v = float((tex >> 16) & 0xFFFFu) / 65535.0;
    return float2(u, v);
}


float2 decodePackedLight(uint lightData) {
    return float2(kLightmapLut[lightData & 0xFFu]);
}

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
struct SimpleVertexOut {
    float4 position [[position]];
    float2 texCoord;
    half4 color;
    float2 lightUV;
    half  light;
    uint   normalIndex [[flat]];
    float2 fogSphCyl;
};

vertex SimpleVertexOut vertex_terrain(
    device const ChunkVertex* vertices        [[buffer(0)]],
    constant float4x4& projectionMatrix       [[buffer(1)]],
    constant float4x4& modelViewMatrix        [[buffer(2)]],
    constant float4& cameraPosition           [[buffer(3)]],
    constant float4& chunkOffset              [[buffer(4)]],
    uint vid [[vertex_id]]
) {
    ChunkVertex v = vertices[vid];
    SimpleVertexOut out;
    float3 localPos = decodePackedPosition(v.posHi, v.posLo);
    float3 worldPos = localPos + chunkOffset.xyz;
    float4 viewPos = modelViewMatrix * float4(worldPos, 1.0);
    out.position = projectionMatrix * viewPos;
    out.texCoord = decodePackedTexCoord(v.texture);
    out.color    = half4(decodePackedColor(v.color));
    out.lightUV  = decodePackedLight(v.lightData);
    out.light    = half(max(max(out.lightUV.x,
                                out.lightUV.y * cameraPosition.w), 0.15f));

    out.normalIndex = (v.lightData >> 16) & 0x7;
    out.fogSphCyl = float2(length(worldPos),
        max(length(worldPos.xz), abs(worldPos.y)));
    return out;
}

fragment half4 fragment_terrain(
    SimpleVertexOut in [[stage_in]],
    texture2d<half> blockAtlas  [[texture(0)]],
    texture2d<half> lightmap    [[texture(1)]],
    constant FogUniforms& fog [[buffer(6)]]
) {
    constexpr sampler lightSampler(mag_filter::linear, min_filter::linear,
                                   mip_filter::nearest, address::clamp_to_edge);
    constexpr sampler atlasSampler(mag_filter::nearest, min_filter::linear,
                                   mip_filter::linear, max_anisotropy(2));
    half4 texColor = blockAtlas.sample(atlasSampler, in.texCoord);
    half vertAlpha = in.color.a;
    if (texColor.a < half(0.5)) {
        if (vertAlpha > half(0.994) && vertAlpha < half(0.998)) {

            texColor.a = half(1.0);
        } else {

            discard_fragment();
        }
    }
    half4 tinted = texColor * in.color;
    half3 light = lightmap.sample(lightSampler, in.lightUV).rgb;
    tinted.rgb *= max(light, half3(0.04h));
    tinted.rgb = vanilla_apply_fog(tinted.rgb, in.fogSphCyl, fog);
    return half4(tinted.rgb, vertAlpha < half(0.99) ? vertAlpha : half(1.0));
}fragment half4 fragment_water_surface(
    SimpleVertexOut in [[stage_in]],
    texture2d<half> blockAtlas  [[texture(0)]],
    texture2d<half> lightmap    [[texture(1)]],
    constant FogUniforms& fog [[buffer(6)]]
) {
    constexpr sampler atlasSampler(mag_filter::nearest, min_filter::linear,
                                   mip_filter::linear, address::clamp_to_edge,
                                   max_anisotropy(8));
    constexpr sampler lightSampler(mag_filter::linear, min_filter::linear, mip_filter::nearest, address::clamp_to_edge);
    half4 texColor = blockAtlas.sample(atlasSampler, in.texCoord);
    half  vertAlpha = in.color.a;
    if (texColor.a < half(0.5)) {
        if (vertAlpha > half(0.994) && vertAlpha < half(0.998)) {
            texColor.a = half(1.0);
        } else {
            discard_fragment();
        }
    }
    half4 tinted = texColor * in.color;
    half3 light = lightmap.sample(lightSampler, in.lightUV).rgb;
    tinted.rgb *= max(light, half3(0.04h));
    tinted.rgb = vanilla_apply_fog(tinted.rgb, in.fogSphCyl, fog);
    return half4(tinted.rgb, vertAlpha < half(0.99) ? vertAlpha : half(1.0));
}

fragment half4 fragment_terrain_cutout(
    SimpleVertexOut in [[stage_in]],
    texture2d<half> blockAtlas  [[texture(0)]],
    texture2d<half> lightmap    [[texture(1)]],
    constant FogUniforms& fog [[buffer(6)]]
) {
    constexpr sampler lightSampler(mag_filter::linear, min_filter::linear,
                                   mip_filter::nearest, address::clamp_to_edge);
    constexpr sampler atlasSampler(mag_filter::nearest, min_filter::linear,
                                   mip_filter::linear, max_anisotropy(2));
    half4 texColor = blockAtlas.sample(atlasSampler, in.texCoord);
    if (texColor.a < half(0.5)) discard_fragment();
    half4 tinted = texColor * in.color;
    half3 light = lightmap.sample(lightSampler, in.lightUV).rgb;
    tinted.rgb *= max(light, half3(0.04h));
    tinted.rgb = vanilla_apply_fog(tinted.rgb, in.fogSphCyl, fog);
    return half4(tinted.rgb, half(1.0));
}

struct InhouseTerrainVertex {
    packed_short3 position;
    packed_ushort2 texCoord;
    packed_uchar4 color;
    uchar packedLight;
    uchar normalIndex;
};

vertex SimpleVertexOut vertex_terrain_inhouse(
    device const InhouseTerrainVertex* vertices [[buffer(0)]],
    constant float4x4& projectionMatrix [[buffer(1)]],
    constant float4x4& modelViewMatrix [[buffer(2)]],
    constant float4& cameraPosition [[buffer(3)]],
    constant float4* chunkOffsets [[buffer(4)]],
    uint vid [[vertex_id]],
    uint iid [[instance_id]]
) {
    InhouseTerrainVertex v = vertices[vid];
    SimpleVertexOut out;
    float4 chunkOffset = chunkOffsets[iid];
    uint faceMask = as_type<uint>(chunkOffset.w);
    if (faceMask != 0 && v.color[3] == 255) {
        uint nIdx = v.normalIndex & 0x7;
        if (nIdx < 6 && ((faceMask >> nIdx) & 1) == 0) {
            out.position = float4(0.0, 0.0, -2.0, 1.0);
            out.texCoord = float2(0.0);
            out.color    = half4(0.0h);
            out.lightUV  = float2(0.0f);
            out.light    = 0.0h;
            out.normalIndex = 1;
            out.fogSphCyl = float2(0.0f, 0.0f);
            return out;
        }
    }
    float3 localPos = float3(short3(v.position)) / 256.0;
    float3 worldPos = localPos + chunkOffset.xyz;
    float4 viewPos = modelViewMatrix * float4(worldPos, 1.0);
    out.position = projectionMatrix * viewPos;
    out.texCoord = float2(v.texCoord) / 65535.0;
    out.color    = half4(float4(v.color) / 255.0);
    out.lightUV = decodePackedLight(uint(v.packedLight));
    out.light = half(max(max(out.lightUV.x,
                             out.lightUV.y * cameraPosition.w), 0.15f));
    out.normalIndex = uint(v.normalIndex & 0x7);
    out.fogSphCyl = float2(length(worldPos),
        max(length(worldPos.xz), abs(worldPos.y)));
    return out;
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


inline float4 terrain_tile_rect(device const uint* arena, device const uint2* sprites, uint addr) {
    device const uint* w = arena + addr;
    uint sprite = terr_bits(w, 114, 13);
    uint fu0 = terr_bits(w, 131, 9);
    uint fv0 = terr_bits(w, 140, 9);
    uint fu1 = terr_bits(w, 149, 9);
    uint fv1 = terr_bits(w, 158, 9);
    uint2 sr = sprites[sprite];
    float su0 = float(sr.x & 0xFFFFu);
    float sv0 = float(sr.x >> 16);
    float su1 = float(sr.y & 0xFFFFu);
    float sv1 = float(sr.y >> 16);
    return float4(su0 + (su1 - su0) * (float(fu0) / 256.0f),
                  sv0 + (sv1 - sv0) * (float(fv0) / 256.0f),
                  (su1 - su0) * (float(fu1 - fu0) / 256.0f),
                  (sv1 - sv0) * (float(fv1 - fv0) / 256.0f)) / float4(65535.0f);
}

inline half4 sample_terrain_tiled(texture2d<half> atlas, sampler smp, TerrainVOut in,
                                  device const uint* arena, device const uint2* sprites) {
    float4 rect = terrain_tile_rect(arena, sprites, in.quadAddr);
    float2 uv = rect.xy + fract(in.tile * 0.99999f) * rect.zw;
    float2 gx = dfdx(in.tile) * rect.zw;
    float2 gy = dfdy(in.tile) * rect.zw;
    return atlas.sample(smp, uv, gradient2d(gx, gy));
}

vertex TerrainVOut terrain_vs(
    uint vid [[vertex_id]],
    uint iid [[instance_id]],
    device const uint2* list [[buffer(0)]],
    constant float4x4& projectionMatrix [[buffer(1)]],
    constant float4x4& modelViewMatrix [[buffer(2)]],
    constant TerrainFrame& frame [[buffer(4)]],
    device const uint* arena [[buffer(5)]],
    device const UnitRecord* units [[buffer(6)]],
    device const SectionRecord* sections [[buffer(7)]],
    device const uint2* sprites [[buffer(8)]]
) {
    TerrainVOut out;
    out.position = float4(0.0, 0.0, 2.0, 1.0);
    out.color    = half4(0.0h);
    out.lightUV  = half2(0.0h);
    out.tile = float2(0.0f);
    out.quadAddr = 0u;
    uint quad = vid >> 2;
    uint corner = vid & 3u;
    uint2 entry = list[iid];
    if (quad > (entry.y & 31u)) {
        return out;
    }
    uint tier = (entry.y >> 5) & 3u;
    TerrainCorner c = terr_decode(arena, sprites, tier, entry.x, quad, corner);
    int3 secOff = int3(int(entry.y << 17) >> 24, int(entry.y << 9) >> 24, int(entry.y << 1) >> 24);
    int3 secPos = ((frame.camBlock.xyz >> 4) + secOff) << 4;
    float3 rel = float3(secPos - frame.camBlock.xyz) - frame.camFrac.xyz;
    float3 worldPos = c.pos + rel;
    out.position = projectionMatrix * (modelViewMatrix * float4(worldPos, 1.0));
    out.tile     = c.tile;
    out.quadAddr = (entry.x + quad * 2u) * 4u;
    out.color    = half4(half3(c.rgb), half(c.alpha));
    out.lightUV  = half2(kLightmapLut[c.light & 0xFFu]);
    return out;
}

vertex TerrainVOutSlim terrain_vs_slim(
    uint vid [[vertex_id]],
    uint iid [[instance_id]],
    device const uint2* list [[buffer(0)]],
    constant float4x4& projectionMatrix [[buffer(1)]],
    constant float4x4& modelViewMatrix [[buffer(2)]],
    constant TerrainFrame& frame [[buffer(4)]],
    device const uint* arena [[buffer(5)]],
    device const UnitRecord* units [[buffer(6)]],
    device const SectionRecord* sections [[buffer(7)]],
    device const uint2* sprites [[buffer(8)]]
) {
    TerrainVOutSlim out;
    out.position = float4(0.0, 0.0, 2.0, 1.0);
    out.texCoord = float2(0.0);
    out.color    = half4(0.0h);
    out.lightUV  = half2(0.0h);
    uint quad = vid >> 2;
    uint corner = vid & 3u;
    uint2 entry = list[iid];
    if (quad > (entry.y & 31u)) {
        return out;
    }
    uint tier = (entry.y >> 5) & 3u;
    TerrainCorner c = terr_decode(arena, sprites, tier, entry.x, quad, corner);
    int3 secOff = int3(int(entry.y << 17) >> 24, int(entry.y << 9) >> 24, int(entry.y << 1) >> 24);
    int3 secPos = ((frame.camBlock.xyz >> 4) + secOff) << 4;
    float3 rel = float3(secPos - frame.camBlock.xyz) - frame.camFrac.xyz;
    float3 worldPos = c.pos + rel;
    out.position = projectionMatrix * (modelViewMatrix * float4(worldPos, 1.0));
    out.texCoord = c.uv;
    out.color    = half4(half3(c.rgb), half(c.alpha));
    out.lightUV  = half2(kLightmapLut[c.light & 0xFFu]);
    return out;
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

fragment half4 fragment_arena_solid(
    TerrainVOut in [[stage_in]],
    texture2d<half> blockAtlas  [[texture(0)]],
    texture2d<half> lightmap    [[texture(1)]],
    constant FogUniforms& fog [[buffer(6)]],
    constant TerrainFogRecon& recon [[buffer(7)]],
    device const uint* arena [[buffer(5)]],
    device const uint2* sprites [[buffer(8)]]
) {
    half4 tex = sample_terrain_tiled(blockAtlas, kTerrAtlasSampler, in, arena, sprites);
    return terrain_shade_solid(tex, in.color, in.lightUV, terrain_fog_dist(in.position, recon), lightmap, fog);
}

fragment half4 fragment_arena_cutout(
    TerrainVOut in [[stage_in]],
    texture2d<half> blockAtlas  [[texture(0)]],
    texture2d<half> lightmap    [[texture(1)]],
    constant FogUniforms& fog [[buffer(6)]],
    constant TerrainFogRecon& recon [[buffer(7)]],
    device const uint* arena [[buffer(5)]],
    device const uint2* sprites [[buffer(8)]]
) {
    half4 tex = sample_terrain_tiled(blockAtlas, kTerrAtlasSampler, in, arena, sprites);
    return terrain_shade_cutout(tex, in.color, in.lightUV, terrain_fog_dist(in.position, recon), lightmap, fog);
}

fragment half4 fragment_arena_water(
    TerrainVOut in [[stage_in]],
    texture2d<half> blockAtlas  [[texture(0)]],
    texture2d<half> lightmap    [[texture(1)]],
    constant FogUniforms& fog [[buffer(6)]],
    constant TerrainFogRecon& recon [[buffer(7)]],
    device const uint* arena [[buffer(5)]],
    device const uint2* sprites [[buffer(8)]]
) {
    half4 tex = sample_terrain_tiled(blockAtlas, kTerrAtlasSamplerAniso, in, arena, sprites);
    return terrain_shade_water(tex, in.color, in.lightUV, terrain_fog_dist(in.position, recon), lightmap, fog);
}

fragment half4 fragment_arena_solid_slim(
    TerrainVOutSlim in [[stage_in]],
    texture2d<half> blockAtlas  [[texture(0)]],
    texture2d<half> lightmap    [[texture(1)]],
    constant FogUniforms& fog [[buffer(6)]],
    constant TerrainFogRecon& recon [[buffer(7)]]
) {
    half4 tex = blockAtlas.sample(kTerrAtlasSampler, in.texCoord);
    return terrain_shade_solid(tex, in.color, in.lightUV, terrain_fog_dist(in.position, recon), lightmap, fog);
}

fragment half4 fragment_arena_cutout_slim(
    TerrainVOutSlim in [[stage_in]],
    texture2d<half> blockAtlas  [[texture(0)]],
    texture2d<half> lightmap    [[texture(1)]],
    constant FogUniforms& fog [[buffer(6)]],
    constant TerrainFogRecon& recon [[buffer(7)]]
) {
    half4 tex = blockAtlas.sample(kTerrAtlasSampler, in.texCoord);
    return terrain_shade_cutout(tex, in.color, in.lightUV, terrain_fog_dist(in.position, recon), lightmap, fog);
}

fragment half4 fragment_arena_water_slim(
    TerrainVOutSlim in [[stage_in]],
    texture2d<half> blockAtlas  [[texture(0)]],
    texture2d<half> lightmap    [[texture(1)]],
    constant FogUniforms& fog [[buffer(6)]],
    constant TerrainFogRecon& recon [[buffer(7)]]
) {
    half4 tex = blockAtlas.sample(kTerrAtlasSamplerAniso, in.texCoord);
    return terrain_shade_water(tex, in.color, in.lightUV, terrain_fog_dist(in.position, recon), lightmap, fog);
}

struct TerrainFragArgs {
    texture2d<half> blockAtlas [[id(0)]];
    texture2d<half> lightmap   [[id(1)]];
};

fragment half4 fragment_terrain_opaque(
    SimpleVertexOut in [[stage_in]],
    texture2d<half> blockAtlas  [[texture(0)]],
    texture2d<half> lightmap    [[texture(1)]],
    constant FogUniforms& fog [[buffer(6)]]
) {
    constexpr sampler lightSampler(mag_filter::linear, min_filter::linear,
                                   mip_filter::nearest, address::clamp_to_edge);
    constexpr sampler atlasSampler(mag_filter::nearest, min_filter::linear,
                                   mip_filter::linear, max_anisotropy(2));
    half4 texColor = blockAtlas.sample(atlasSampler, in.texCoord);
    half vertAlpha = in.color.a;
    if (texColor.a < half(0.5)) {
        if (vertAlpha > half(0.994h) && vertAlpha < half(0.998h)) {
            texColor.a = half(1.0h);
        } else {
            discard_fragment();
        }
    }
    half4 tinted = texColor * in.color;
    half3 light = lightmap.sample(lightSampler, in.lightUV).rgb;
    tinted.rgb *= max(light, half3(0.04h));
    tinted.rgb = vanilla_apply_fog(tinted.rgb, in.fogSphCyl, fog);
    return half4(tinted.rgb, half(1.0h));
}

fragment half4 fragment_terrain_solid(
    SimpleVertexOut in [[stage_in]],
    texture2d<half> blockAtlas  [[texture(0)]],
    texture2d<half> lightmap    [[texture(1)]],
    constant FogUniforms& fog [[buffer(6)]]
) {
    constexpr sampler lightSampler(mag_filter::linear, min_filter::linear,
                                   mip_filter::nearest, address::clamp_to_edge);
    constexpr sampler atlasSampler(mag_filter::nearest, min_filter::linear,
                                   mip_filter::linear, max_anisotropy(1));
    half3 rgb = blockAtlas.sample(atlasSampler, in.texCoord).rgb * in.color.rgb;
    half3 light = lightmap.sample(lightSampler, in.lightUV).rgb;
    rgb *= max(light, half3(0.04h));
    rgb = vanilla_apply_fog(rgb, in.fogSphCyl, fog);
    return half4(rgb, half(1.0h));
}

fragment half4 fragment_terrain_icb_opaque(
    SimpleVertexOut in [[stage_in]],
    constant TerrainFragArgs& resources [[buffer(0)]],
    constant FogUniforms& fog [[buffer(6)]]
) {
    constexpr sampler lightSampler(mag_filter::linear, min_filter::linear,
                                   mip_filter::nearest, address::clamp_to_edge);
    constexpr sampler atlasSampler(mag_filter::nearest, min_filter::linear,
                                   mip_filter::linear, max_anisotropy(2));
    half4 texColor = resources.blockAtlas.sample(atlasSampler, in.texCoord);
    half vertAlpha = in.color.a;
    if (texColor.a < half(0.5)) {
        if (vertAlpha > half(0.994h) && vertAlpha < half(0.998h)) {
            texColor.a = half(1.0h);
        } else {
            discard_fragment();
        }
    }
    half4 tinted = texColor * in.color;
    half3 light = resources.lightmap.sample(lightSampler, in.lightUV).rgb;
    tinted.rgb *= max(light, half3(0.04h));
    tinted.rgb = vanilla_apply_fog(tinted.rgb, in.fogSphCyl, fog);
    return half4(tinted.rgb, half(1.0h));
}

fragment half4 fragment_terrain_icb(
    SimpleVertexOut in [[stage_in]],
    constant TerrainFragArgs& resources [[buffer(0)]],
    constant float4& overlayParams [[buffer(5)]],
    constant FogUniforms& fog [[buffer(6)]]
) {
    constexpr sampler lightSampler(mag_filter::linear, min_filter::linear,
                                   mip_filter::nearest, address::clamp_to_edge);
    constexpr sampler atlasSampler(mag_filter::nearest, min_filter::linear,
                                   mip_filter::linear, max_anisotropy(2));
    half4 texColor = resources.blockAtlas.sample(atlasSampler, in.texCoord);
    half vertAlpha = in.color.a;
    if (texColor.a < half(0.5)) {
        if (vertAlpha > half(0.994) && vertAlpha < half(0.998)) {
            texColor.a = half(1.0);
        } else {
            discard_fragment();
        }
    }
    half4 tinted = texColor * in.color;
    half3 light = resources.lightmap.sample(lightSampler, in.lightUV).rgb;
    tinted.rgb *= max(light, half3(0.04h));
    tinted.rgb = vanilla_apply_fog(tinted.rgb, in.fogSphCyl, fog);
    return half4(tinted.rgb, vertAlpha < half(0.99) ? vertAlpha : half(1.0));
}

fragment half4 fragment_terrain_cutout_inhouse(
    SimpleVertexOut in [[stage_in]],
    texture2d<half> blockAtlas  [[texture(0)]],
    texture2d<half> lightmap    [[texture(1)]],
    constant FogUniforms& fog [[buffer(6)]]
) {
    constexpr sampler lightSampler(mag_filter::linear, min_filter::linear,
                                   mip_filter::nearest, address::clamp_to_edge);
    constexpr sampler atlasSampler(mag_filter::nearest, min_filter::linear,
                                   mip_filter::linear, max_anisotropy(2));
    half4 texColor = blockAtlas.sample(atlasSampler, in.texCoord);
    if (texColor.a < half(0.5)) discard_fragment();
    half4 tinted = texColor * in.color;
    half3 light = lightmap.sample(lightSampler, in.lightUV).rgb;
    tinted.rgb *= max(light, half3(0.04h));
    tinted.rgb = vanilla_apply_fog(tinted.rgb, in.fogSphCyl, fog);
    return half4(tinted.rgb, half(1.0));
}

fragment half4 fragment_terrain_icb_cutout(
    SimpleVertexOut in [[stage_in]],
    constant TerrainFragArgs& resources [[buffer(0)]],
    constant FogUniforms& fog [[buffer(6)]]
) {
    constexpr sampler lightSampler(mag_filter::linear, min_filter::linear,
                                   mip_filter::nearest, address::clamp_to_edge);
    constexpr sampler atlasSampler(mag_filter::nearest, min_filter::linear,
                                   mip_filter::linear, max_anisotropy(2));
    half4 texColor = resources.blockAtlas.sample(atlasSampler, in.texCoord);
    if (texColor.a < half(0.5)) discard_fragment();
    half4 tinted = texColor * in.color;
    half3 light = resources.lightmap.sample(lightSampler, in.lightUV).rgb;
    tinted.rgb *= max(light, half3(0.04h));
    tinted.rgb = vanilla_apply_fog(tinted.rgb, in.fogSphCyl, fog);
    return half4(tinted.rgb, half(1.0));
}

struct DebugVertexOut {
    float4 position [[position]];
    float4 color;
};

vertex DebugVertexOut vertex_debug(
    device const packed_float3* positions [[buffer(0)]],
    constant float4x4& projectionMatrix [[buffer(1)]],
    constant float4x4& modelViewMatrix [[buffer(2)]],
    constant float4& debugColor [[buffer(5)]],
    uint vid [[vertex_id]]
) {
    DebugVertexOut out;
    float4 viewPos = modelViewMatrix * float4(float3(positions[vid]), 1.0);
    out.position = projectionMatrix * viewPos;
    out.color = debugColor;
    return out;
}

vertex DebugVertexOut vertex_debug_thick_line(
    device const packed_float3* positions [[buffer(0)]],
    constant float4x4& projectionMatrix [[buffer(1)]],
    constant float4x4& modelViewMatrix [[buffer(2)]],
    constant float4& debugColor [[buffer(5)]],
    constant float4& lineParams [[buffer(6)]],
    uint vid [[vertex_id]]
) {
    uint edge = vid / 6u;
    uint corner = vid % 6u;
    float4 p0 = projectionMatrix * (modelViewMatrix * float4(float3(positions[edge * 2u]), 1.0));
    float4 p1 = projectionMatrix * (modelViewMatrix * float4(float3(positions[edge * 2u + 1u]), 1.0));
    float2 n0 = p0.xy / max(abs(p0.w), 1.0e-6);
    float2 n1 = p1.xy / max(abs(p1.w), 1.0e-6);
    float2 direction = n1 - n0;
    float directionLength = length(direction);
    float2 normal = directionLength > 1.0e-6
        ? float2(-direction.y, direction.x) / directionLength
        : float2(0.0, 1.0);
    float2 halfWidthNdc = normal * (lineParams.x / lineParams.zw);
    float2 offset = (corner == 0u || corner == 3u || corner == 5u)
        ? -halfWidthNdc : halfWidthNdc;
    bool first = corner < 2u || corner == 3u;
    float4 position = first ? p0 : p1;
    position.xy += offset * position.w;

    DebugVertexOut out;
    out.position = position;
    out.color = debugColor;
    return out;
}

fragment float4 fragment_debug(DebugVertexOut in [[stage_in]]) {
    return in.color;
}

kernel void mfx_preserve_alpha(
    texture2d<half, access::read>   upscaled [[texture(0)]],
    texture2d<half, access::sample> scene    [[texture(1)]],
    texture2d<half, access::write>  output   [[texture(2)]],
    uint2 gid [[thread_position_in_grid]]) {
    if (gid.x >= output.get_width() || gid.y >= output.get_height()) {
        return;
    }
    half4 up = upscaled.read(gid);
    constexpr sampler nearestSampler(mag_filter::nearest, min_filter::nearest,
                                     address::clamp_to_edge);
    float2 uv = (float2(gid) + 0.5f) /
                float2(output.get_width(), output.get_height());
    half4 sc = scene.sample(nearestSampler, uv);
    output.write(half4(up.rgb, sc.a), gid);
}
