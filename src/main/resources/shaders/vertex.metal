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
    float u = float(tex & 0x7FFF) / 32768.0;
    float v = float((tex >> 16) & 0x7FFF) / 32768.0;
    return float2(u, v);
}
float2 decodePackedLight(uint lightData) {
    uint light = lightData & 0xFFFF;
    float blockLight = float(light & 0xFF) / 256.0;
    float skyLight   = float((light >> 8) & 0xFF) / 256.0;
    return float2(blockLight, skyLight);
}
struct SimpleVertexOut {
    float4 position  [[position]];
    float2 texCoord;
    float4 color;
    float2 lightUV;
    float  fogFactor;
};
vertex SimpleVertexOut vertex_terrain(
    device const ChunkVertex* vertices        [[buffer(0)]],
    constant float4x4& projectionMatrix       [[buffer(1)]],
    constant float4x4& modelViewMatrix        [[buffer(2)]],
    constant float4& cameraPosition           [[buffer(3)]],
    constant float4& chunkOffset              [[buffer(4)]],
    constant float4& fogParams                [[buffer(5)]],
    uint vid [[vertex_id]]
) {
    ChunkVertex v = vertices[vid];
    SimpleVertexOut out;
    float3 localPos = decodePackedPosition(v.posHi, v.posLo);
    float3 worldPos = localPos + chunkOffset.xyz;
    float4 viewPos  = modelViewMatrix * float4(worldPos, 1.0);
    out.position    = projectionMatrix * viewPos;
    out.texCoord    = decodePackedTexCoord(v.texture);
    out.color       = decodePackedColor(v.color);
    out.lightUV     = decodePackedLight(v.lightData);
    float dist     = length(viewPos.xyz);
    out.fogFactor  = saturate((fogParams.y - dist) / max(fogParams.y - fogParams.x, 0.001));
    return out;
}
struct InhouseTerrainVertex {
    packed_float3  position;
    packed_short2  texCoord;
    packed_uchar4  color;
    packed_uchar4  normal;
    packed_short2  lightUV;
};
vertex SimpleVertexOut vertex_terrain_inhouse(
    device const InhouseTerrainVertex* vertices   [[buffer(0)]],
    constant float4x4& projectionMatrix           [[buffer(1)]],
    constant float4x4& modelViewMatrix            [[buffer(2)]],
    constant float4& cameraPosition               [[buffer(3)]],
    constant float4& chunkOffset                  [[buffer(4)]],
    uint vid [[vertex_id]]
) {
    InhouseTerrainVertex v = vertices[vid];
    SimpleVertexOut out;
    float3 localPos = float3(v.position);
    float3 worldPos = localPos + chunkOffset.xyz;
    float4 viewPos  = modelViewMatrix * float4(worldPos, 1.0);
    out.position    = projectionMatrix * viewPos;
    out.texCoord    = float2(v.texCoord) / 65535.0;
    out.color       = float4(v.color) / 255.0;
    out.lightUV     = float2(v.lightUV) / 256.0;
    out.fogFactor   = 1.0;
    return out;
}
vertex SimpleVertexOut vertex_terrain_translucent(
    device const ChunkVertex* vertices        [[buffer(0)]],
    constant float4x4& projectionMatrix       [[buffer(1)]],
    constant float4x4& modelViewMatrix        [[buffer(2)]],
    constant float4& cameraPosition           [[buffer(3)]],
    constant float4& chunkOffset              [[buffer(4)]],
    constant float4& fogParams                [[buffer(5)]],
    uint vid [[vertex_id]]
) {
    ChunkVertex v = vertices[vid];
    SimpleVertexOut out;
    float3 localPos = decodePackedPosition(v.posHi, v.posLo);
    float3 worldPos = localPos + chunkOffset.xyz;
    float4 viewPos  = modelViewMatrix * float4(worldPos, 1.0);
    out.position    = projectionMatrix * viewPos;
    out.texCoord    = decodePackedTexCoord(v.texture);
    out.color       = decodePackedColor(v.color);
    out.lightUV     = decodePackedLight(v.lightData);
    float dist     = length(viewPos.xyz);
    out.fogFactor  = saturate((fogParams.y - dist) / max(fogParams.y - fogParams.x, 0.001));
    return out;
}
struct DebugVertexOut {
    float4 position [[position]];
    float4 color;
};
vertex DebugVertexOut vertex_debug(
    device const packed_float3* positions     [[buffer(0)]],
    constant float4x4& projectionMatrix       [[buffer(1)]],
    constant float4x4& modelViewMatrix        [[buffer(2)]],
    constant float4& debugColor               [[buffer(5)]],
    uint vid [[vertex_id]]
) {
    DebugVertexOut out;
    float4 viewPos = modelViewMatrix * float4(float3(positions[vid]), 1.0);
    out.position   = projectionMatrix * viewPos;
    out.color      = debugColor;
    return out;
}
struct BlitVertexOut {
    float4 position [[position]];
    float2 texCoord;
};
vertex BlitVertexOut vertex_blit(uint vid [[vertex_id]]) {
    float2 positions[3] = { float2(-1, -1), float2(3, -1), float2(-1, 3) };
    float2 texcoords[3] = { float2(0, 1), float2(2, 1), float2(0, -1) };
    BlitVertexOut out;
    out.position = float4(positions[vid], 0.0, 1.0);
    out.texCoord = texcoords[vid];
    return out;
}
