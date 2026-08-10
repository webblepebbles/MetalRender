#include <metal_stdlib>
using namespace metal;
struct CullParams {
    float4x4 viewProjection;
    float4   cameraPos;
    float4   frustumPlanes[6];
    float    maxDrawDistSq;
    uint     regionCount;
    uint     maxDrawCalls;
    uint     _pad;
};
struct RegionAABB {
    float3 minCorner;
    float  _pad0;
    float3 maxCorner;
    float  _pad1;
};
struct IndirectIndexedArgs {
    uint indexCount;
    uint instanceCount;
    uint indexStart;
    int  baseVertex;
    uint baseInstance;
};
struct RegionDrawInfo {
    uint  vertexBufferIndex;
    uint  indexBufferIndex;
    uint  indexCount;
    uint  baseIndex;
    int   baseVertex;
    uint  regionId;
};
bool aabbBehindPlane(float3 minC, float3 maxC, float4 plane) {
    float3 pVertex;
    pVertex.x = (plane.x > 0.0) ? maxC.x : minC.x;
    pVertex.y = (plane.y > 0.0) ? maxC.y : minC.y;
    pVertex.z = (plane.z > 0.0) ? maxC.z : minC.z;
    return (dot(plane.xyz, pVertex) + plane.w) < 0.0;
}
bool frustumTest(float3 minC, float3 maxC, constant float4* planes) {
    for (uint i = 0; i < 4; i++) {
        if (aabbBehindPlane(minC, maxC, planes[i])) return false;
    }
    return true;
}
float distanceSqToAABB(float3 pt, float3 minC, float3 maxC) {
    float3 clamped = clamp(pt, minC, maxC);
    float3 d = clamped - pt;
    return dot(d, d);
}
kernel void cull_regions(
    device const RegionAABB*      regions      [[buffer(0)]],
    device const RegionDrawInfo*  drawInfos    [[buffer(1)]],
    device uint*                  visibility   [[buffer(2)]],
    device IndirectIndexedArgs*   indirectArgs [[buffer(3)]],
    device atomic_uint*           drawCount    [[buffer(4)]],
    constant CullParams&          params       [[buffer(5)]],
    uint gid [[thread_position_in_grid]]
) {
    if (gid >= params.regionCount) return;
    RegionAABB aabb = regions[gid];
    bool vis = frustumTest(aabb.minCorner, aabb.maxCorner, params.frustumPlanes);
    if (vis) {
        float dSq = distanceSqToAABB(params.cameraPos.xyz, aabb.minCorner, aabb.maxCorner);
        if (dSq > params.maxDrawDistSq) vis = false;
    }
    visibility[gid] = vis ? 1u : 0u;
    if (vis) {
        uint slot = atomic_fetch_add_explicit(drawCount, 1, memory_order_relaxed);
        if (slot < params.maxDrawCalls) {
            RegionDrawInfo info = drawInfos[gid];
            IndirectIndexedArgs a;
            a.indexCount    = info.indexCount;
            a.instanceCount = 1;
            a.indexStart    = info.baseIndex;
            a.baseVertex    = info.baseVertex;
            a.baseInstance  = info.regionId;
            indirectArgs[slot] = a;
        }
    }
}
