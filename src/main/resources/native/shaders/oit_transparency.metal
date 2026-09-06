#include <metal_stdlib>
using namespace metal;
struct OitParams {
    float4x4 projection;
    float4x4 modelView;
    float4   cameraPos;
    float4   chunkOffset;
    float4   fogColor;
    float    fogStart;
    float    fogEnd;
    float    translucentCullDist;
    float    _pad1;
};
struct TranslucentVertex {
    packed_float3 position;
    packed_short2 texCoord;
    packed_uchar4 color;
    packed_uchar4 normal;
    packed_short2 lightUV;
};
struct TranslucentVertexOut {
    float4 position [[position]];
    half2  texCoord;
    half4  color;
    half2  lightUV;
    float  viewDepth;
};
vertex TranslucentVertexOut vertex_oit(
    device const TranslucentVertex* vertices    [[buffer(0)]],
    constant OitParams&             params       [[buffer(1)]],
    uint vid [[vertex_id]]
) {
    TranslucentVertex v = vertices[vid];
    TranslucentVertexOut out;
    float3 worldPos = float3(v.position) + params.chunkOffset.xyz;
    float4 viewPos  = params.modelView * float4(worldPos, 1.0);
    out.position    = params.projection * viewPos;
    out.texCoord    = half2(float2(v.texCoord) / 65535.0);
    out.color       = half4(float4(v.color) / 255.0);
    out.lightUV     = half2(float2(v.lightUV) / 256.0);
    out.viewDepth   = -viewPos.z;
    return out;
}
struct OitAccumOutput {
    half4  accumColor [[color(0)]];
    half   revealage  [[color(1)]];
};
fragment OitAccumOutput fragment_oit_accum(
    TranslucentVertexOut in [[stage_in]],
    texture2d<half> blockAtlas [[texture(0)]],
    texture2d<half> lightmap   [[texture(1)]],
    constant OitParams& params  [[buffer(1)]]
) {
    constexpr sampler texSampler(filter::nearest, address::clamp_to_edge);
    constexpr sampler lightSampler(filter::linear, address::clamp_to_edge);
    half4 texColor = blockAtlas.sample(texSampler, float2(in.texCoord));
    if (texColor.a < half(0.004)) discard_fragment();
    half4 baseColor = texColor * in.color;
    half4 light = lightmap.sample(lightSampler, float2(in.lightUV));
    baseColor.rgb *= light.rgb;
    float fogFactor = saturate((params.fogEnd - in.viewDepth) /
                               max(params.fogEnd - params.fogStart, 0.001f));
    baseColor.rgb = mix(half3(params.fogColor.rgb), baseColor.rgb, half(fogFactor));
    half alpha = baseColor.a;
    float linearDepth = in.viewDepth;
    float w = float(alpha) * max(1e-2f,
        min(3e3f, 10.0f / (1e-5f + pow(linearDepth / 200.0f, 4.0f))));
    half weight = half(w);
    OitAccumOutput out;
    out.accumColor = half4(baseColor.rgb * alpha * weight, alpha * weight);
    out.revealage  = alpha;
    return out;
}
struct CompositeVertexOut {
    float4 position [[position]];
    float2 texCoord;
};
vertex CompositeVertexOut vertex_oit_composite(uint vid [[vertex_id]]) {
    float2 positions[3] = { float2(-1, -1), float2(3, -1), float2(-1, 3) };
    float2 texcoords[3] = { float2(0, 1), float2(2, 1), float2(0, -1) };
    CompositeVertexOut out;
    out.position = float4(positions[vid], 0.0, 1.0);
    out.texCoord = texcoords[vid];
    return out;
}
fragment half4 fragment_oit_composite(
    CompositeVertexOut in [[stage_in]],
    texture2d<half>  accumTexture     [[texture(0)]],
    texture2d<half>  revealageTexture [[texture(1)]],
    texture2d<half>  opaqueColor      [[texture(2)]]
) {
    uint2 coord = uint2(in.position.xy);
    half4 accum = accumTexture.read(coord);
    half revealage = revealageTexture.read(coord).r;
    half4 opaque = opaqueColor.read(coord);
    if (accum.a < half(1e-4)) return opaque;
    half3 avgColor = accum.rgb / max(accum.a, half(1e-4));
    half3 result = avgColor * (half(1.0) - revealage) + opaque.rgb * revealage;
    return half4(result, half(1.0));
}

struct TerrainOitVertOut {
    float4 position    [[position]];
    float2 texCoord;
    half4  color;
    half   light;
    uint   normalIndex [[flat]];
};

fragment OitAccumOutput fragment_oit_terrain_accum(
    TerrainOitVertOut      in        [[stage_in]],
    texture2d<half>        blockAtlas [[texture(0)]]
) {
    constexpr sampler texSampler(mag_filter::nearest, min_filter::nearest,
                                 address::clamp_to_edge);
    half4 texColor = blockAtlas.sample(texSampler, in.texCoord);
    if (texColor.a < half(0.004h)) discard_fragment();

    half4 baseColor = texColor * in.color;
    baseColor.rgb  *= max(in.light, half(0.1h));

    half  alpha = baseColor.a;

    float linearDepth = in.position.w;
    float w = max(1e-2f,
                  min(3e3f, float(alpha) * 10.0f /
                            (1e-5f + pow(linearDepth / 200.0f, 4.0f))));
    half weight = half(w);

    OitAccumOutput out;
    out.accumColor = half4(baseColor.rgb * alpha * weight, alpha * weight);
    out.revealage  = alpha;
    return out;
}

fragment half4 fragment_oit_composite_tbdr(
    CompositeVertexOut      in            [[stage_in]],
    texture2d<half>         accumTex      [[texture(0)]],
    texture2d<half>         revealageTex  [[texture(1)]],
    half4                   opaqueColor   [[color(0)]]
) {
    uint2  coord     = uint2(in.position.xy);
    half4  accum     = accumTex.read(coord);
    half   revealage = revealageTex.read(coord).r;

    if (accum.a < half(1e-4h)) return opaqueColor;

    half3 avgColor = accum.rgb / max(accum.a, half(1e-4h));
    half3 result   = avgColor * (half(1.0h) - revealage)
                   + opaqueColor.rgb * revealage;
    return half4(result, opaqueColor.a);
}
