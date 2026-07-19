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
struct SimpleVertexOut {
    float4 position  [[position]];
    float2 texCoord;
    float4 color;
    float2 lightUV;
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
    out.color    = decodePackedColor(v.color);
    out.lightUV  = decodePackedLight(v.lightData);
    return out;
}
struct InhouseTerrainVertex {
    packed_short3 position;
    packed_ushort2 texCoord;
    packed_uchar4 color;
    uchar packedLight;
    uchar normalIndex;
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
    float3 localPos = float3(short3(v.position)) / 256.0;
    float3 worldPos = localPos + chunkOffset.xyz;
    float4 viewPos = modelViewMatrix * float4(worldPos, 1.0);
    out.position = projectionMatrix * viewPos;
    out.texCoord = float2(v.texCoord) / 65535.0;
    out.color    = float4(v.color) / 255.0;
    float lightVal = float(v.packedLight) / 255.0;
    out.lightUV  = float2(lightVal, lightVal);
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
    out.position = projectionMatrix * viewPos;
    out.color = debugColor;
    return out;
}
