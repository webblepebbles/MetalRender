#include <metal_stdlib>
using namespace metal;

struct SodiumVertex {
    uint posHi;
    uint posLo;
    uint color;
    uint texture;
    uint lightData;
};

float3 decodeSodiumPosition(uint posHi, uint posLo) {
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

half4 decodeSodiumColor(uint c) {
    constexpr half scale = 1.0h / 255.0h;
    half a = half((c >> 24) & 0xFF) * scale;
    half r = half((c >> 16) & 0xFF) * scale;
    half g = half((c >>  8) & 0xFF) * scale;
    half b = half((c >>  0) & 0xFF) * scale;
    return half4(r, g, b, a);
}

half2 decodeSodiumTexCoord(uint tex) {
    constexpr half scale = 1.0h / 32768.0h;
    half u = half(tex & 0x7FFF) * scale;
    half v = half((tex >> 16) & 0x7FFF) * scale;
    return half2(u, v);
}

half2 decodeSodiumLight(uint lightData) {
    uint light = lightData & 0xFFFF;
    constexpr half scale = 1.0h / 256.0h;
    half blockLight = half((light & 0xFF) + 8u) * scale;
    half skyLight   = half(((light >> 8) & 0xFF) + 8u) * scale;
    return half2(blockLight, skyLight);
}
constant half kFaceShade[6] = {
    half(0.65),
    half(1.0),
    half(0.8),
    half(0.8),
    half(0.65),
    half(0.65),
};
struct SimpleVertexOut {
    float4 position [[position]];
    half2 texCoord;
    half4 color;
    half2 lightUV;
    half  light;
    uint   normalIndex [[flat]];
};

vertex SimpleVertexOut vertex_terrain(
    device const SodiumVertex* vertices       [[buffer(0)]],
    constant float4x4& projectionMatrix       [[buffer(1)]],
    constant float4x4& modelViewMatrix        [[buffer(2)]],
    constant float4& cameraPosition           [[buffer(3)]],
    constant float4& chunkOffset              [[buffer(4)]],
    uint vid [[vertex_id]]
) {
    SodiumVertex v = vertices[vid];
    SimpleVertexOut out;
    float3 localPos = decodeSodiumPosition(v.posHi, v.posLo);
    float3 worldPos = localPos + chunkOffset.xyz;
    float4 viewPos = modelViewMatrix * float4(worldPos, 1.0);
    out.position = projectionMatrix * viewPos;
    out.texCoord = decodeSodiumTexCoord(v.texture);
    out.color    = decodeSodiumColor(v.color);
    out.lightUV  = decodeSodiumLight(v.lightData);
    out.light    = half(max(max(float(out.lightUV.x),
                                float(out.lightUV.y) * cameraPosition.w), 0.15f));

    out.normalIndex = (v.lightData >> 16) & 0x7;
    return out;
}

fragment half4 fragment_terrain(
    SimpleVertexOut in [[stage_in]],
    texture2d<half> blockAtlas  [[texture(0)]],
    texture2d<half> lightmap    [[texture(1)]]
) {
    constexpr sampler texSampler(mag_filter::nearest, min_filter::nearest, mip_filter::nearest);
    half4 texColor = blockAtlas.sample(texSampler, float2(in.texCoord));
    half vertAlpha = in.color.a;
    if (texColor.a < half(0.5)) {
        if (vertAlpha > half(0.994) && vertAlpha < half(0.998)) {

            texColor.a = half(1.0);
        } else {

            discard_fragment();
        }
    }
    half4 tinted = texColor * in.color;
    half faceShade = kFaceShade[min(in.normalIndex, 5u)];
    half3 light = lightmap.sample(texSampler, float2(in.lightUV)).rgb;
    tinted.rgb *= light * faceShade;
    return half4(tinted.rgb, vertAlpha < half(0.99) ? vertAlpha : half(1.0));
}

fragment half4 fragment_terrain_cutout(
    SimpleVertexOut in [[stage_in]],
    texture2d<half> blockAtlas  [[texture(0)]],
    texture2d<half> lightmap    [[texture(1)]]
) {
    constexpr sampler texSampler(mag_filter::nearest, min_filter::nearest, mip_filter::nearest);
    half4 texColor = blockAtlas.sample(texSampler, float2(in.texCoord));
    if (texColor.a < half(0.5)) discard_fragment();
    half4 tinted = texColor * in.color;
    half faceShade = kFaceShade[min(in.normalIndex, 5u)];
    half3 light = lightmap.sample(texSampler, float2(in.lightUV)).rgb;
    tinted.rgb *= light * faceShade;
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
            out.texCoord = half2(0.0h);
            out.color    = half4(0.0h);
            out.lightUV  = half2(0.0h);
            out.light    = 0.0h;
            out.normalIndex = 1;
            return out;
        }
    }
    float3 localPos = float3(short3(v.position)) / 256.0;
    float3 worldPos = localPos + chunkOffset.xyz;
    float4 viewPos = modelViewMatrix * float4(worldPos, 1.0);
    out.position = projectionMatrix * viewPos;
    out.texCoord = half2(float2(v.texCoord) / 65535.0f);
    out.color    = half4(float4(v.color) / 255.0);
    uint blockLight = uint(v.packedLight & 0xFu);
    uint skyLight   = uint((v.packedLight >> 4) & 0xFu);
    out.lightUV = half2((half(blockLight) + 0.5h) * (1.0h / 16.0h),
                        (half(skyLight) + 0.5h) * (1.0h / 16.0h));
    out.light = half(max(max(float(out.lightUV.x),
                             float(out.lightUV.y) * cameraPosition.w), 0.15f));
    out.normalIndex = uint(v.normalIndex & 0x7);
    return out;
}

struct TerrainFragArgs {
    texture2d<half> blockAtlas [[id(0)]];
    texture2d<half> lightmap   [[id(1)]];
};








fragment half4 fragment_terrain_opaque(
    SimpleVertexOut in [[stage_in]],
    texture2d<half> blockAtlas  [[texture(0)]],
    texture2d<half> lightmap    [[texture(1)]]
) {
    constexpr sampler texSampler(mag_filter::nearest, min_filter::nearest, mip_filter::nearest);
    half4 texColor = blockAtlas.sample(texSampler, float2(in.texCoord));
    half vertAlpha = in.color.a;
    if (texColor.a < half(0.5)) {



        if (vertAlpha > half(0.994) && vertAlpha < half(0.998)) {
            texColor.a = half(1.0);
        } else {
            discard_fragment();
        }
    }
    half4 tinted = texColor * in.color;
    half faceShade = kFaceShade[min(in.normalIndex, 5u)];
    half3 light = lightmap.sample(texSampler, float2(in.lightUV)).rgb;
    tinted.rgb *= light * faceShade;
    return half4(tinted.rgb, half(1.0));
}

fragment half4 fragment_terrain_icb_opaque(
    SimpleVertexOut in [[stage_in]],
    constant TerrainFragArgs& resources [[buffer(0)]]
) {
    constexpr sampler texSampler(mag_filter::nearest, min_filter::nearest, mip_filter::nearest);
    half4 texColor = resources.blockAtlas.sample(texSampler, float2(in.texCoord));
    half vertAlpha = in.color.a;
    if (texColor.a < half(0.5)) {

        if (vertAlpha > half(0.994) && vertAlpha < half(0.998)) {
            texColor.a = half(1.0);
        } else {
            discard_fragment();
        }
    }
    half4 tinted = texColor * in.color;
    half faceShade = kFaceShade[min(in.normalIndex, 5u)];
    half3 light = resources.lightmap.sample(texSampler, float2(in.lightUV)).rgb;
    tinted.rgb *= light * faceShade;
    return half4(tinted.rgb, half(1.0));
}

fragment half4 fragment_terrain_icb(
    SimpleVertexOut in [[stage_in]],
    constant TerrainFragArgs& resources [[buffer(0)]],
    constant float4& overlayParams [[buffer(5)]]
) {
    constexpr sampler texSampler(mag_filter::nearest, min_filter::nearest, mip_filter::nearest);
    half4 texColor = resources.blockAtlas.sample(texSampler, float2(in.texCoord));
    half vertAlpha = in.color.a;
    if (texColor.a < half(0.5)) {
        if (vertAlpha > half(0.994) && vertAlpha < half(0.998)) {
            texColor.a = half(1.0);
        } else {
            discard_fragment();
        }
    }
    half4 tinted = texColor * in.color;
    half faceShade = kFaceShade[min(in.normalIndex, 5u)];
    half3 light = resources.lightmap.sample(texSampler, float2(in.lightUV)).rgb;
    tinted.rgb *= light * faceShade;
    return half4(tinted.rgb, vertAlpha < half(0.99) ? vertAlpha : half(1.0));
}




fragment half4 fragment_terrain_cutout_inhouse(
    SimpleVertexOut in [[stage_in]],
    texture2d<half> blockAtlas  [[texture(0)]],
    texture2d<half> lightmap    [[texture(1)]]
) {
    constexpr sampler texSampler(mag_filter::nearest, min_filter::nearest, mip_filter::nearest);
    half4 texColor = blockAtlas.sample(texSampler, float2(in.texCoord));
    if (texColor.a < half(0.5)) discard_fragment();
    half4 tinted = texColor * in.color;
    half faceShade = kFaceShade[min(in.normalIndex, 5u)];
    half3 light = lightmap.sample(texSampler, float2(in.lightUV)).rgb;
    tinted.rgb *= light * faceShade;
    return half4(tinted.rgb, half(1.0));
}

fragment half4 fragment_terrain_icb_cutout(
    SimpleVertexOut in [[stage_in]],
    constant TerrainFragArgs& resources [[buffer(0)]]
) {
    constexpr sampler texSampler(mag_filter::nearest, min_filter::nearest, mip_filter::nearest);
    half4 texColor = resources.blockAtlas.sample(texSampler, float2(in.texCoord));
    if (texColor.a < half(0.5)) discard_fragment();
    half4 tinted = texColor * in.color;
    half faceShade = kFaceShade[min(in.normalIndex, 5u)];
    half3 light = resources.lightmap.sample(texSampler, float2(in.lightUV)).rgb;
    tinted.rgb *= light * faceShade;
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

fragment float4 fragment_debug(DebugVertexOut in [[stage_in]]) {
    return in.color;
}
