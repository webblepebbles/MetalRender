#include <metal_stdlib>
#include <metal_mesh>
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
    float    waterFog;
};


struct ChunkMeshlet {
    uint  baseVertexOffset;
    uint  vertexCount;
    float worldX;
    float worldY;
    float worldZ;
    uint  visibleFaceMask;
    uint  lodTier;
    uint  _pad2;
};


struct InhouseTerrainVertex {
    packed_short3  position;
    packed_ushort2 texCoord;
    packed_uchar4  color;
    uchar          packedLight;
    uchar          normalIndex;
};



constant float kGamma[16] = {
    0.0f,
    0.38157f,
    0.47038f,
    0.53589f,
    0.59018f,
    0.63728f,
    0.65132f,
    0.69210f,
    0.72984f,
    0.76496f,
    0.79780f,
    0.82860f,
    0.85752f,
    0.88474f,
    0.91038f,
    1.00000f,
};


constant half kFaceShade[6] = {
    half(0.5),
    half(1.0),
    half(0.8),
    half(0.8),
    half(0.6),
    half(0.6),
};


struct MeshVertexOut {
    float4 position    [[position]];
    float2 texCoord;
    half4  color;
    half   light;
    uint   normalIndex [[flat]];
    float3 worldPos;
};


struct MeshletPayload {
    uint chunkIndex;
};








[[object, max_total_threads_per_threadgroup(1)]]
void object_terrain(
    object_data MeshletPayload&   payload  [[payload]],
    mesh_grid_properties          grid,
    device const ChunkMeshlet*    meshlets [[buffer(0)]],
    constant CameraUniforms&      camera   [[buffer(1)]],
    uint tid [[thread_position_in_grid]]
) {
    if (tid >= camera.totalChunks) {
        grid.set_threadgroups_per_grid(uint3(0, 0, 0));
        return;
    }
    ChunkMeshlet m = meshlets[tid];
    if (m.vertexCount == 0u) {
        grid.set_threadgroups_per_grid(uint3(0, 0, 0));
        return;
    }

    payload.chunkIndex = tid;
    uint numGroups = (m.vertexCount + 255u) / 256u;
    grid.set_threadgroups_per_grid(uint3(numGroups, 1, 1));
}









constant uint kMaxMeshVerts = 256u;
constant uint kMaxMeshTris  = 128u;

[[mesh, max_total_threads_per_threadgroup(256)]]
void mesh_terrain(
    metal::mesh<MeshVertexOut, void, kMaxMeshVerts, kMaxMeshTris,
                metal::topology::triangle>      output,
    const object_data MeshletPayload&           payload  [[payload]],
    device const ChunkMeshlet*                  meshlets [[buffer(0)]],
    constant CameraUniforms&                    camera   [[buffer(1)]],
    device const InhouseTerrainVertex*          vertices [[buffer(2)]],
    uint tid   [[thread_index_in_threadgroup]],
    uint tgIdx [[threadgroup_position_in_grid]]
) {
    ChunkMeshlet m  = meshlets[payload.chunkIndex];
    uint vertStart  = tgIdx * kMaxMeshVerts;
    uint vertEnd    = min(vertStart + kMaxMeshVerts, m.vertexCount);
    uint localVerts = vertEnd - vertStart;
    uint localQuads = localVerts / 4u;

    threadgroup uint quadVisible[kMaxMeshTris / 2u];
    threadgroup uint quadCompact[kMaxMeshTris / 2u];

    if (tid < localQuads) {
        InhouseTerrainVertex first = vertices[m.baseVertexOffset + vertStart + tid * 4u];
        uint normalIndex = uint(first.normalIndex & 0x7u);
        quadVisible[tid] =
            (normalIndex >= 6u || (m.visibleFaceMask & (1u << normalIndex)) != 0u) ? 1u : 0u;
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);
    if (tid == 0u) {
        uint running = 0u;
        for (uint q = 0u; q < localQuads; q++) {
            quadCompact[q] = running;
            running += quadVisible[q];
        }
        output.set_primitive_count(running * 2u);
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);

    float3 chunkOrig = float3(m.worldX, m.worldY, m.worldZ);
    float  skyBr     = camera.cameraPosition.w;

    if (tid < localVerts) {
        uint sourceQuad = tid >> 2u;
        if (quadVisible[sourceQuad] != 0u) {
            uint vertexInQuad = tid & 3u;
            uint gv = m.baseVertexOffset + vertStart + tid;
            InhouseTerrainVertex v = vertices[gv];
            uint normalIndex = uint(v.normalIndex & 0x7u);

            float3 localPos = float3(short3(v.position)) / 256.0;
            float3 worldPos = localPos + chunkOrig;
            float4 viewPos  = camera.modelView * float4(worldPos, 1.0);

            MeshVertexOut out;
            out.position    = camera.projection * viewPos;
            out.texCoord    = float2(v.texCoord) / 65535.0f;
            out.color       = half4(float4(v.color) / 255.0f);

            uint pl      = uint(v.packedLight);
            half blockL  = half(kGamma[pl & 0xFu]);
            half skyL    = half(kGamma[(pl >> 4u) & 0xFu]);
            out.light    = half(max(max(float(blockL), float(skyL) * skyBr), 0.15f));
            out.normalIndex = normalIndex;
            out.worldPos    = worldPos;

            output.set_vertex(quadCompact[sourceQuad] * 4u + vertexInQuad, out);
        }
    }

    if (tid < localQuads && quadVisible[tid] != 0u) {
        uint compactQuad = quadCompact[tid];
        uint b = compactQuad * 4u;
        output.set_index(compactQuad * 6u + 0u, b + 0u);
        output.set_index(compactQuad * 6u + 1u, b + 1u);
        output.set_index(compactQuad * 6u + 2u, b + 2u);
        output.set_index(compactQuad * 6u + 3u, b + 0u);
        output.set_index(compactQuad * 6u + 4u, b + 2u);
        output.set_index(compactQuad * 6u + 5u, b + 3u);
    }
}





fragment half4 fragment_terrain_mesh_opaque(
    MeshVertexOut in [[stage_in]],
    texture2d<half> blockAtlas [[texture(0)]],
    constant CameraUniforms& camera [[buffer(1)]]
) {
    constexpr sampler s(mag_filter::nearest, min_filter::nearest,
                        mip_filter::nearest);
    half4 tex = blockAtlas.sample(s, float2(in.texCoord));
    half  va  = in.color.a;



    if (tex.a < half(0.5h)) {
        if (va > half(0.994h) && va < half(0.998h)) {
            tex.a = half(1.0h);
        } else {
            discard_fragment();
        }
    }
    half4 col = tex * in.color;

    col.rgb *= max(in.light, half(0.04h)) * kFaceShade[min(in.normalIndex, 5u)];

    if (camera.waterFog > 0.0f) {
        half dist = half(fast::length(in.worldPos));
        half fog  = clamp(dist / half(32.0h), half(0.0h), half(0.85h));
        col.rgb   = mix(col.rgb, half3(0.05h, 0.12h, 0.3h), fog);
    }
    return half4(col.rgb, half(1.0h));
}

fragment half4 fragment_terrain_mesh_cutout(
    MeshVertexOut in [[stage_in]],
    texture2d<half> blockAtlas [[texture(0)]],
    constant CameraUniforms& camera [[buffer(1)]]
) {
    constexpr sampler s(mag_filter::nearest, min_filter::nearest,
                        mip_filter::nearest);
    half4 tex = blockAtlas.sample(s, float2(in.texCoord));
    if (tex.a < half(0.5h)) discard_fragment();
    half4 col = tex * in.color;
    col.rgb *= max(in.light, half(0.04h)) * kFaceShade[min(in.normalIndex, 5u)];
    if (camera.waterFog > 0.0f) {
        half dist = half(fast::length(in.worldPos));
        half fog  = clamp(dist / half(32.0h), half(0.0h), half(0.85h));
        col.rgb   = mix(col.rgb, half3(0.05h, 0.12h, 0.3h), fog);
    }
    return half4(col.rgb, half(1.0h));
}

fragment half4 fragment_terrain_mesh_emissive(
    MeshVertexOut in [[stage_in]],
    texture2d<half> blockAtlas [[texture(0)]],
    constant CameraUniforms& camera [[buffer(1)]]
) {
    constexpr sampler s(mag_filter::nearest, min_filter::nearest,
                        mip_filter::nearest);
    half4 tex = blockAtlas.sample(s, float2(in.texCoord));
    if (tex.a < half(0.1h)) discard_fragment();
    half4 col = tex * in.color;

    if (camera.waterFog > 0.0f) {
        half dist = half(fast::length(in.worldPos));
        half fog  = clamp(dist / half(32.0h), half(0.0h), half(0.85h));
        col.rgb   = mix(col.rgb, half3(0.05h, 0.12h, 0.3h), fog);
    }
    return col;
}
