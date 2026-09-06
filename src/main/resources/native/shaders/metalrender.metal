#include <metal_stdlib>
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

constant half2 kLightmapLut[256] = {
    half2(0.031250h, 0.031250h), half2(0.093750h, 0.031250h), half2(0.156250h, 0.031250h), half2(0.218750h, 0.031250h),
    half2(0.281250h, 0.031250h), half2(0.343750h, 0.031250h), half2(0.406250h, 0.031250h), half2(0.468750h, 0.031250h),
    half2(0.531250h, 0.031250h), half2(0.593750h, 0.031250h), half2(0.656250h, 0.031250h), half2(0.718750h, 0.031250h),
    half2(0.781250h, 0.031250h), half2(0.843750h, 0.031250h), half2(0.906250h, 0.031250h), half2(0.968750h, 0.031250h),
    half2(0.031250h, 0.093750h), half2(0.093750h, 0.093750h), half2(0.156250h, 0.093750h), half2(0.218750h, 0.093750h),
    half2(0.281250h, 0.093750h), half2(0.343750h, 0.093750h), half2(0.406250h, 0.093750h), half2(0.468750h, 0.093750h),
    half2(0.531250h, 0.093750h), half2(0.593750h, 0.093750h), half2(0.656250h, 0.093750h), half2(0.718750h, 0.093750h),
    half2(0.781250h, 0.093750h), half2(0.843750h, 0.093750h), half2(0.906250h, 0.093750h), half2(0.968750h, 0.093750h),
    half2(0.031250h, 0.156250h), half2(0.093750h, 0.156250h), half2(0.156250h, 0.156250h), half2(0.218750h, 0.156250h),
    half2(0.281250h, 0.156250h), half2(0.343750h, 0.156250h), half2(0.406250h, 0.156250h), half2(0.468750h, 0.156250h),
    half2(0.531250h, 0.156250h), half2(0.593750h, 0.156250h), half2(0.656250h, 0.156250h), half2(0.718750h, 0.156250h),
    half2(0.781250h, 0.156250h), half2(0.843750h, 0.156250h), half2(0.906250h, 0.156250h), half2(0.968750h, 0.156250h),
    half2(0.031250h, 0.218750h), half2(0.093750h, 0.218750h), half2(0.156250h, 0.218750h), half2(0.218750h, 0.218750h),
    half2(0.281250h, 0.218750h), half2(0.343750h, 0.218750h), half2(0.406250h, 0.218750h), half2(0.468750h, 0.218750h),
    half2(0.531250h, 0.218750h), half2(0.593750h, 0.218750h), half2(0.656250h, 0.218750h), half2(0.718750h, 0.218750h),
    half2(0.781250h, 0.218750h), half2(0.843750h, 0.218750h), half2(0.906250h, 0.218750h), half2(0.968750h, 0.218750h),
    half2(0.031250h, 0.281250h), half2(0.093750h, 0.281250h), half2(0.156250h, 0.281250h), half2(0.218750h, 0.281250h),
    half2(0.281250h, 0.281250h), half2(0.343750h, 0.281250h), half2(0.406250h, 0.281250h), half2(0.468750h, 0.281250h),
    half2(0.531250h, 0.281250h), half2(0.593750h, 0.281250h), half2(0.656250h, 0.281250h), half2(0.718750h, 0.281250h),
    half2(0.781250h, 0.281250h), half2(0.843750h, 0.281250h), half2(0.906250h, 0.281250h), half2(0.968750h, 0.281250h),
    half2(0.031250h, 0.343750h), half2(0.093750h, 0.343750h), half2(0.156250h, 0.343750h), half2(0.218750h, 0.343750h),
    half2(0.281250h, 0.343750h), half2(0.343750h, 0.343750h), half2(0.406250h, 0.343750h), half2(0.468750h, 0.343750h),
    half2(0.531250h, 0.343750h), half2(0.593750h, 0.343750h), half2(0.656250h, 0.343750h), half2(0.718750h, 0.343750h),
    half2(0.781250h, 0.343750h), half2(0.843750h, 0.343750h), half2(0.906250h, 0.343750h), half2(0.968750h, 0.343750h),
    half2(0.031250h, 0.406250h), half2(0.093750h, 0.406250h), half2(0.156250h, 0.406250h), half2(0.218750h, 0.406250h),
    half2(0.281250h, 0.406250h), half2(0.343750h, 0.406250h), half2(0.406250h, 0.406250h), half2(0.468750h, 0.406250h),
    half2(0.531250h, 0.406250h), half2(0.593750h, 0.406250h), half2(0.656250h, 0.406250h), half2(0.718750h, 0.406250h),
    half2(0.781250h, 0.406250h), half2(0.843750h, 0.406250h), half2(0.906250h, 0.406250h), half2(0.968750h, 0.406250h),
    half2(0.031250h, 0.468750h), half2(0.093750h, 0.468750h), half2(0.156250h, 0.468750h), half2(0.218750h, 0.468750h),
    half2(0.281250h, 0.468750h), half2(0.343750h, 0.468750h), half2(0.406250h, 0.468750h), half2(0.468750h, 0.468750h),
    half2(0.531250h, 0.468750h), half2(0.593750h, 0.468750h), half2(0.656250h, 0.468750h), half2(0.718750h, 0.468750h),
    half2(0.781250h, 0.468750h), half2(0.843750h, 0.468750h), half2(0.906250h, 0.468750h), half2(0.968750h, 0.468750h),
    half2(0.031250h, 0.531250h), half2(0.093750h, 0.531250h), half2(0.156250h, 0.531250h), half2(0.218750h, 0.531250h),
    half2(0.281250h, 0.531250h), half2(0.343750h, 0.531250h), half2(0.406250h, 0.531250h), half2(0.468750h, 0.531250h),
    half2(0.531250h, 0.531250h), half2(0.593750h, 0.531250h), half2(0.656250h, 0.531250h), half2(0.718750h, 0.531250h),
    half2(0.781250h, 0.531250h), half2(0.843750h, 0.531250h), half2(0.906250h, 0.531250h), half2(0.968750h, 0.531250h),
    half2(0.031250h, 0.593750h), half2(0.093750h, 0.593750h), half2(0.156250h, 0.593750h), half2(0.218750h, 0.593750h),
    half2(0.281250h, 0.593750h), half2(0.343750h, 0.593750h), half2(0.406250h, 0.593750h), half2(0.468750h, 0.593750h),
    half2(0.531250h, 0.593750h), half2(0.593750h, 0.593750h), half2(0.656250h, 0.593750h), half2(0.718750h, 0.593750h),
    half2(0.781250h, 0.593750h), half2(0.843750h, 0.593750h), half2(0.906250h, 0.593750h), half2(0.968750h, 0.593750h),
    half2(0.031250h, 0.656250h), half2(0.093750h, 0.656250h), half2(0.156250h, 0.656250h), half2(0.218750h, 0.656250h),
    half2(0.281250h, 0.656250h), half2(0.343750h, 0.656250h), half2(0.406250h, 0.656250h), half2(0.468750h, 0.656250h),
    half2(0.531250h, 0.656250h), half2(0.593750h, 0.656250h), half2(0.656250h, 0.656250h), half2(0.718750h, 0.656250h),
    half2(0.781250h, 0.656250h), half2(0.843750h, 0.656250h), half2(0.906250h, 0.656250h), half2(0.968750h, 0.656250h),
    half2(0.031250h, 0.718750h), half2(0.093750h, 0.718750h), half2(0.156250h, 0.718750h), half2(0.218750h, 0.718750h),
    half2(0.281250h, 0.718750h), half2(0.343750h, 0.718750h), half2(0.406250h, 0.718750h), half2(0.468750h, 0.718750h),
    half2(0.531250h, 0.718750h), half2(0.593750h, 0.718750h), half2(0.656250h, 0.718750h), half2(0.718750h, 0.718750h),
    half2(0.781250h, 0.718750h), half2(0.843750h, 0.718750h), half2(0.906250h, 0.718750h), half2(0.968750h, 0.718750h),
    half2(0.031250h, 0.781250h), half2(0.093750h, 0.781250h), half2(0.156250h, 0.781250h), half2(0.218750h, 0.781250h),
    half2(0.281250h, 0.781250h), half2(0.343750h, 0.781250h), half2(0.406250h, 0.781250h), half2(0.468750h, 0.781250h),
    half2(0.531250h, 0.781250h), half2(0.593750h, 0.781250h), half2(0.656250h, 0.781250h), half2(0.718750h, 0.781250h),
    half2(0.781250h, 0.781250h), half2(0.843750h, 0.781250h), half2(0.906250h, 0.781250h), half2(0.968750h, 0.781250h),
    half2(0.031250h, 0.843750h), half2(0.093750h, 0.843750h), half2(0.156250h, 0.843750h), half2(0.218750h, 0.843750h),
    half2(0.281250h, 0.843750h), half2(0.343750h, 0.843750h), half2(0.406250h, 0.843750h), half2(0.468750h, 0.843750h),
    half2(0.531250h, 0.843750h), half2(0.593750h, 0.843750h), half2(0.656250h, 0.843750h), half2(0.718750h, 0.843750h),
    half2(0.781250h, 0.843750h), half2(0.843750h, 0.843750h), half2(0.906250h, 0.843750h), half2(0.968750h, 0.843750h),
    half2(0.031250h, 0.906250h), half2(0.093750h, 0.906250h), half2(0.156250h, 0.906250h), half2(0.218750h, 0.906250h),
    half2(0.281250h, 0.906250h), half2(0.343750h, 0.906250h), half2(0.406250h, 0.906250h), half2(0.468750h, 0.906250h),
    half2(0.531250h, 0.906250h), half2(0.593750h, 0.906250h), half2(0.656250h, 0.906250h), half2(0.718750h, 0.906250h),
    half2(0.781250h, 0.906250h), half2(0.843750h, 0.906250h), half2(0.906250h, 0.906250h), half2(0.968750h, 0.906250h),
    half2(0.031250h, 0.968750h), half2(0.093750h, 0.968750h), half2(0.156250h, 0.968750h), half2(0.218750h, 0.968750h),
    half2(0.281250h, 0.968750h), half2(0.343750h, 0.968750h), half2(0.406250h, 0.968750h), half2(0.468750h, 0.968750h),
    half2(0.531250h, 0.968750h), half2(0.593750h, 0.968750h), half2(0.656250h, 0.968750h), half2(0.718750h, 0.968750h),
    half2(0.781250h, 0.968750h), half2(0.843750h, 0.968750h), half2(0.906250h, 0.968750h), half2(0.968750h, 0.968750h),
};

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
    out.fogSphCyl = float2(length(viewPos.xyz),
        max(length(viewPos.xz), abs(viewPos.y)));
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
    out.fogSphCyl = float2(length(viewPos.xyz),
        max(length(viewPos.xz), abs(viewPos.y)));
    return out;
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
