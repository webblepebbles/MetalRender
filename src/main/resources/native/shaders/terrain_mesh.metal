#include <metal_stdlib>
#include <metal_mesh>
#include "terrain_decode.h"
using namespace metal;

struct TerrainMeshFrame {
    int4   camBlock;
    float4 camFrac;
};

inline uint terr_mesh_outcode(float4 c) {
    return (c.x < -c.w ? 1u : 0u) | (c.x > c.w ? 2u : 0u) | (c.y < -c.w ? 4u : 0u) | (c.y > c.w ? 8u : 0u)
           | (c.z < 0.0f ? 16u : 0u) | (c.z > c.w ? 32u : 0u);
}

inline void terr_mesh_attrs(thread TerrainVOut& v, TerrainCorner c, uint quadAddr) {
    v.tile = c.tile;
    v.quadAddr = quadAddr;
}

inline void terr_mesh_attrs(thread TerrainVOutSlim& v, TerrainCorner c, uint quadAddr) {
    v.texCoord = c.uv;
}

template <typename V>
inline void terr_mesh_emit(
    thread metal::mesh<V, void, 128, 64, metal::topology::triangle>& out,
    device const uint2* list,
    constant uint2& mp,
    device const uint* args,
    constant float4x4& projectionMatrix,
    constant float4x4& modelViewMatrix,
    constant TerrainMeshFrame& frame,
    device const uint* arena,
    device const uint2* sprites,
    threadgroup uint* simdTotals,
    uint tid,
    uint tg
) {
    uint q = tid >> 2;
    uint corner = tid & 3u;
    uint per = 32u / mp.x;
    uint local = q / mp.x;
    uint quad = q - local * mp.x;
    uint entryIdx = tg * per + local;
    bool live = local < per && entryIdx < args[mp.y * 5u + 1u];
    TerrainCorner c;
    float4 clip = float4(0.0f);
    uint firstQuad = 0u;
    uint code = 63u;
    if (live) {
        uint2 entry = list[entryIdx];
        live = quad <= (entry.y & 31u);
        if (live) {
            firstQuad = entry.x;
            uint tier = (entry.y >> 5) & 3u;
            int3 secOff = int3(int(entry.y << 17) >> 24, int(entry.y << 9) >> 24, int(entry.y << 1) >> 24);
            int3 secPos = ((frame.camBlock.xyz >> 4) + secOff) << 4;
            float3 rel = float3(secPos - frame.camBlock.xyz) - frame.camFrac.xyz;
            c = terr_decode(arena, sprites, tier, firstQuad, quad, corner);
            clip = projectionMatrix * (modelViewMatrix * float4(c.pos + rel, 1.0));
            code = terr_mesh_outcode(clip);
        }
    }
    bool keep = live && quad_and(code) == 0u;
    uint lead = (keep && corner == 0u) ? 1u : 0u;
    uint rank = quad_broadcast(simd_prefix_exclusive_sum(lead), 0u);
    uint simdTotal = simd_sum(lead);
    uint sg = tid >> 5;
    if ((tid & 31u) == 0u) {
        simdTotals[sg] = simdTotal;
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);
    uint offset = 0u;
    for (uint k = 0u; k < sg; k++) {
        offset += simdTotals[k];
    }
    uint total = simdTotals[0] + simdTotals[1] + simdTotals[2] + simdTotals[3];
    if (tid == 0u) {
        out.set_primitive_count(total * 2u);
    }
    if (!keep) {
        return;
    }
    uint idx = offset + rank;
    V v;
    v.position = clip;
    v.color = half4(half3(c.rgb), half(c.alpha));
    v.lightUV = half2(kLightmapLut[c.light & 0xFFu]);
    terr_mesh_attrs(v, c, (firstQuad + quad * 2u) * 4u);
    out.set_vertex(idx * 4u + corner, v);
    if (corner == 0u) {
        uint b = idx * 4u;
        out.set_index(idx * 6u + 0u, b + 0u);
        out.set_index(idx * 6u + 1u, b + 1u);
        out.set_index(idx * 6u + 2u, b + 2u);
        out.set_index(idx * 6u + 3u, b + 2u);
        out.set_index(idx * 6u + 4u, b + 3u);
        out.set_index(idx * 6u + 5u, b + 0u);
    }
}

[[mesh]]
void terrain_mesh_full(
    metal::mesh<TerrainVOut, void, 128, 64, metal::topology::triangle> out,
    device const uint2* list [[buffer(0)]],
    constant float4x4& projectionMatrix [[buffer(1)]],
    constant float4x4& modelViewMatrix [[buffer(2)]],
    constant TerrainMeshFrame& frame [[buffer(4)]],
    device const uint* arena [[buffer(5)]],
    device const uint2* sprites [[buffer(8)]],
    constant uint2& mp [[buffer(9)]],
    device const uint* args [[buffer(10)]],
    uint tid [[thread_index_in_threadgroup]],
    uint tg [[threadgroup_position_in_grid]]
) {
    threadgroup uint simdTotals[4];
    terr_mesh_emit<TerrainVOut>(out, list, mp, args, projectionMatrix, modelViewMatrix, frame, arena, sprites, simdTotals, tid, tg);
}

[[mesh]]
void terrain_mesh_slim(
    metal::mesh<TerrainVOutSlim, void, 128, 64, metal::topology::triangle> out,
    device const uint2* list [[buffer(0)]],
    constant float4x4& projectionMatrix [[buffer(1)]],
    constant float4x4& modelViewMatrix [[buffer(2)]],
    constant TerrainMeshFrame& frame [[buffer(4)]],
    device const uint* arena [[buffer(5)]],
    device const uint2* sprites [[buffer(8)]],
    constant uint2& mp [[buffer(9)]],
    device const uint* args [[buffer(10)]],
    uint tid [[thread_index_in_threadgroup]],
    uint tg [[threadgroup_position_in_grid]]
) {
    threadgroup uint simdTotals[4];
    terr_mesh_emit<TerrainVOutSlim>(out, list, mp, args, projectionMatrix, modelViewMatrix, frame, arena, sprites, simdTotals, tid, tg);
}
