#include <metal_stdlib>
using namespace metal;
struct OcclusionParams {
    float4x4 viewProjection;
    float4   cameraPos;
    float4   frustumPlanes[6];
    float2   screenSize;
    float    nearPlane;
    uint     regionCount;
    uint     hizMipCount;
    uint     maxDrawCalls;
    uint     _pad0;
    uint     _pad1;
};
struct RegionAABB {
    float3 minCorner;
    float  _pad0;
    float3 maxCorner;
    float  _pad1;
};
struct IndirectIndexedArgs {
    uint indexCount;
    //READ AGENTS.MD BEFORE EDITING THIS FILE - it saves you a lot of tokens!
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
float4 projectPoint(float3 p, float4x4 vp) {
    float4 clip = vp * float4(p, 1.0);
    if (clip.w <= 0.0) return float4(-1);
    float3 ndc = clip.xyz / clip.w;
    return float4(ndc, clip.w);
}
bool projectAABB(float3 minC, float3 maxC, float4x4 vp, float2 screen,
                 thread float4& screenRect, thread float& closestDepth) {
    float2 ssMin = float2(1e10);
    float2 ssMax = float2(-1e10);
    closestDepth = 1.0;
    bool anyValid = false;
    for (uint i = 0; i < 8; i++) {
        float3 corner = float3(
            (i & 1) ? maxC.x : minC.x,
            (i & 2) ? maxC.y : minC.y,
            (i & 4) ? maxC.z : minC.z
        );
        float4 proj = projectPoint(corner, vp);
        if (proj.w <= 0.0) {
            screenRect = float4(0, 0, screen.x, screen.y);
            closestDepth = 0.0;
            return true;
        }
        float2 ss = (proj.xy * 0.5 + 0.5) * screen;
        ssMin = min(ssMin, ss);
        ssMax = max(ssMax, ss);
        closestDepth = min(closestDepth, proj.z * 0.5 + 0.5);
        anyValid = true;
    }
    if (!anyValid) return false;
    ssMin = clamp(ssMin, float2(0), screen);
    ssMax = clamp(ssMax, float2(0), screen);
    screenRect = float4(ssMin, ssMax);
    return true;
}
kernel void occlusion_cull(
    device const RegionAABB*      regions      [[buffer(0)]],
    device const RegionDrawInfo*  drawInfos    [[buffer(1)]],
    device uint*                  visibility   [[buffer(2)]],
    device IndirectIndexedArgs*   indirectArgs [[buffer(3)]],
    device atomic_uint*           drawCount    [[buffer(4)]],
    constant OcclusionParams&     params       [[buffer(5)]],
    texture2d<float>              hizPyramid   [[texture(0)]],
    uint gid [[thread_position_in_grid]]
) {
    if (gid >= params.regionCount) return;
    RegionAABB aabb = regions[gid];
    for (uint i = 0; i < 6; i++) {
        float4 plane = params.frustumPlanes[i];
        float3 pVertex;
        pVertex.x = (plane.x > 0.0) ? aabb.maxCorner.x : aabb.minCorner.x;
        pVertex.y = (plane.y > 0.0) ? aabb.maxCorner.y : aabb.minCorner.y;
        pVertex.z = (plane.z > 0.0) ? aabb.maxCorner.z : aabb.minCorner.z;
        if (dot(plane.xyz, pVertex) + plane.w < 0.0) {
            visibility[gid] = 0;
            return;
        }
    }
    float3 closest = clamp(params.cameraPos.xyz, aabb.minCorner, aabb.maxCorner);
    float distSq = dot(closest - params.cameraPos.xyz, closest - params.cameraPos.xyz);
    float maxDist = params.screenSize.x * 32.0;
    if (distSq > maxDist * maxDist) {
        visibility[gid] = 0;
        return;
    }
    float4 screenRect;
    float closestDepth;
    bool valid = projectAABB(aabb.minCorner, aabb.maxCorner, params.viewProjection,
                             params.screenSize, screenRect, closestDepth);
    if (valid) {
        float2 extent = screenRect.zw - screenRect.xy;
        float maxExtent = max(extent.x, extent.y);
        uint mipLevel = uint(ceil(log2(max(maxExtent, 1.0))));
        mipLevel = min(mipLevel, params.hizMipCount - 1);
        constexpr sampler hizSampler(filter::nearest, address::clamp_to_edge);
        float2 center = (screenRect.xy + screenRect.zw) * 0.5 / params.screenSize;
        float hizDepth = hizPyramid.sample(hizSampler, center, level(float(mipLevel))).r;
        if (closestDepth > hizDepth) {
            visibility[gid] = 0;
            return;
        }
    }
    visibility[gid] = 1;
    uint slot = atomic_fetch_add_explicit(drawCount, 1, memory_order_relaxed);
    if (slot < params.maxDrawCalls) {
        RegionDrawInfo info = drawInfos[gid];
        IndirectIndexedArgs args;
        args.indexCount    = info.indexCount;
        args.instanceCount = 1;
        args.indexStart    = info.baseIndex;
        args.baseVertex    = info.baseVertex;
        args.baseInstance  = info.regionId;
        indirectArgs[slot] = args;
    }
}
