#include <metal_stdlib>
using namespace metal;
struct VisBufferParams {
    float4x4 viewProjection;
    float4   cameraPos;
    uint     totalTriangles;
    uint     _pad0;
    uint     _pad1;
    uint     _pad2;
};
struct TerrainVertex {
    packed_float3 position;
    packed_short2 texCoord;
    packed_uchar4 color;
    packed_uchar4 normal;
    packed_short2 lightUV;
};
struct VisBufferVertexOut {
    float4 position [[position]];
    uint   triangleId;
    uint   instanceId;
};
vertex VisBufferVertexOut vertex_visbuffer(
    device const TerrainVertex*  vertices     [[buffer(0)]],
    constant float4x4&           projection   [[buffer(1)]],
    constant float4x4&           modelView    [[buffer(2)]],
    constant float4&             chunkOffset  [[buffer(3)]],
    uint vid [[vertex_id]],
    uint iid [[instance_id]]
) {
    TerrainVertex v = vertices[vid];
    VisBufferVertexOut out;
    float3 worldPos = float3(v.position) + chunkOffset.xyz;
    float4 viewPos = modelView * float4(worldPos, 1.0);
    out.position = projection * viewPos;
    out.triangleId = vid / 3;
    out.instanceId = iid;
    return out;
}
fragment uint fragment_visbuffer(
    VisBufferVertexOut in [[stage_in]]
) {
    return (in.instanceId << 20) | (in.triangleId & 0xFFFFF);
}
struct DeferredVertexOut {
    float4 position [[position]];
    float2 texCoord;
};
vertex DeferredVertexOut vertex_deferred_fullscreen(uint vid [[vertex_id]]) {
    float2 positions[3] = { float2(-1, -1), float2(3, -1), float2(-1, 3) };
    float2 texcoords[3] = { float2(0, 1), float2(2, 1), float2(0, -1) };
    DeferredVertexOut out;
    out.position = float4(positions[vid], 0.0, 1.0);
    out.texCoord = texcoords[vid];
    return out;
}
fragment float4 fragment_deferred_shade(
    DeferredVertexOut in [[stage_in]],
    texture2d<uint>    visBuffer   [[texture(0)]],
    texture2d<float>   blockAtlas  [[texture(1)]],
    texture2d<float>   lightmap    [[texture(2)]],
    device const TerrainVertex* vertices [[buffer(0)]],
    constant float4x4& invViewProj        [[buffer(1)]]
) {
    constexpr sampler nearestSampler(filter::linear);
    constexpr sampler atlasSampler(filter::nearest, address::clamp_to_edge);
    uint2 coord = uint2(in.position.xy);
    uint packedVis = visBuffer.read(coord).r;
    if (packedVis == 0) discard_fragment();
    uint triId = packedVis & 0xFFFFF;
    uint baseVid = triId * 3;
    TerrainVertex v0 = vertices[baseVid + 0];
    float2 uv = float2(v0.texCoord) / 65535.0;
    float4 color = float4(v0.color) / 255.0;
    float2 lightUV = float2(v0.lightUV) / 256.0;
    float4 texColor = blockAtlas.sample(atlasSampler, uv);
    if (texColor.a < 0.004) discard_fragment();
    float4 baseColor = texColor * color;
    float4 light = lightmap.sample(nearestSampler, lightUV);
    baseColor.rgb *= light.rgb;
    return float4(baseColor.rgb, 1.0);
}
