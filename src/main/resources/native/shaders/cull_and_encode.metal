#include <metal_stdlib>
using namespace metal;
struct CameraUniforms {
    float4x4 viewProjection;
    float4x4 projection;
    float4x4 modelView;
    float4   cameraPosition;
    float4   frustumPlanes[6];
    float2   screenSize;
    float    nearPlane;
    float    farPlane;
    uint     frameIndex;
    uint     hizMipCount;
    uint     totalChunks;
    uint     _pad;
};
struct SubChunkEntry {
    float4   aabbMin;
    float4   aabbMax;
    uint     bufHandleHi;
    uint     bufHandleLo;
    uint     indexCount;
    uint     flags;
};
struct CullStats {
    atomic_uint visibleCount;
    atomic_uint frustumCulled;
    atomic_uint occlusionCulled;
    atomic_uint distanceCulled;
    atomic_uint totalProcessed;
};
bool isOccludedByHiZ(float3 aabbMin, float3 aabbMax,
                     texture2d<float> hiz,
                     constant CameraUniforms& camera) {
    float2 ssMin = float2(1e10);
    float2 ssMax = float2(-1e10);
    float minDepth = 1.0;
    for (uint i = 0; i < 8; i++) {
        float3 corner = float3(
            (i & 1) ? aabbMax.x : aabbMin.x,
            (i & 2) ? aabbMax.y : aabbMin.y,
            (i & 4) ? aabbMax.z : aabbMin.z
        );
        float4 clip = camera.viewProjection * float4(corner, 1.0);
        if (clip.w <= 0.0) return false;
        float3 ndc = clip.xyz / clip.w;
        float2 ss = (ndc.xy * 0.5 + 0.5) * camera.screenSize;
        ssMin = min(ssMin, ss);
        ssMax = max(ssMax, ss);
        minDepth = min(minDepth, ndc.z);
    }
    ssMin = clamp(ssMin, float2(0), camera.screenSize);
    ssMax = clamp(ssMax, float2(0), camera.screenSize);
    float2 extent = ssMax - ssMin;
    float maxExtent = max(extent.x, extent.y);
    if (maxExtent < 1.0) return false;
    uint mipLevel = uint(ceil(log2(max(maxExtent, 1.0))));
    mipLevel = min(mipLevel, camera.hizMipCount - 1);
    constexpr sampler hizSampler(filter::nearest, address::clamp_to_edge);
    float2 center = (ssMin + ssMax) * 0.5 / camera.screenSize;
    float hizDepth = hiz.sample(hizSampler, center, level(float(mipLevel))).r;
    return (minDepth > hizDepth);
}
bool isInFrustum(float3 minC, float3 maxC, constant float4* planes) {
    for (uint i = 0; i < 4; i++) {
        float4 plane = planes[i];
        float3 pVertex;
        pVertex.x = (plane.x > 0.0) ? maxC.x : minC.x;
        pVertex.y = (plane.y > 0.0) ? maxC.y : minC.y;
        pVertex.z = (plane.z > 0.0) ? maxC.z : minC.z;
        if (dot(plane.xyz, pVertex) + plane.w < 0.0) return false;
    }
    return true;
}
kernel void cull_and_encode(
    device const SubChunkEntry*   chunks       [[buffer(0)]],
    device uint*                  visibleIdx   [[buffer(1)]],
    device atomic_uint*           drawCount    [[buffer(2)]],
    device CullStats*             stats        [[buffer(3)]],
    constant CameraUniforms&      camera       [[buffer(4)]],
    texture2d<float>              hiz          [[texture(0)]],
    uint gid [[thread_position_in_grid]]
) {
    if (gid >= camera.totalChunks) return;
    atomic_fetch_add_explicit(&stats->totalProcessed, 1, memory_order_relaxed);
    SubChunkEntry chunk = chunks[gid];
    if (!isInFrustum(chunk.aabbMin.xyz, chunk.aabbMax.xyz, camera.frustumPlanes)) {
        atomic_fetch_add_explicit(&stats->frustumCulled, 1, memory_order_relaxed);
        return;
    }
    float3 center = (chunk.aabbMin.xyz + chunk.aabbMax.xyz) * 0.5;
    float distSq = dot(center, center);
    if (distSq > camera.farPlane * camera.farPlane) {
        atomic_fetch_add_explicit(&stats->distanceCulled, 1, memory_order_relaxed);
        return;
    }
    if (camera.hizMipCount > 0 && camera.frameIndex > 2) {
        if (isOccludedByHiZ(chunk.aabbMin.xyz, chunk.aabbMax.xyz, hiz, camera)) {
            atomic_fetch_add_explicit(&stats->occlusionCulled, 1, memory_order_relaxed);
            return;
        }
    }
    uint slot = atomic_fetch_add_explicit(drawCount, 1, memory_order_relaxed);
    visibleIdx[slot] = gid;
    atomic_fetch_add_explicit(&stats->visibleCount, 1, memory_order_relaxed);
}
kernel void reset_cull_stats(
    device atomic_uint*  drawCount [[buffer(0)]],
    device CullStats*    stats     [[buffer(1)]],
    uint gid [[thread_position_in_grid]]
) {
    if (gid != 0) return;
    atomic_store_explicit(drawCount, 0, memory_order_relaxed);
    atomic_store_explicit(&stats->visibleCount, 0, memory_order_relaxed);
    atomic_store_explicit(&stats->frustumCulled, 0, memory_order_relaxed);
    atomic_store_explicit(&stats->occlusionCulled, 0, memory_order_relaxed);
    atomic_store_explicit(&stats->distanceCulled, 0, memory_order_relaxed);
    atomic_store_explicit(&stats->totalProcessed, 0, memory_order_relaxed);
}
