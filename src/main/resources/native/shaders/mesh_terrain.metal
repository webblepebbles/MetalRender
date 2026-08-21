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
    float    cameraSpeed;
};


struct ChunkMeshlet {
    uint  baseVertexOffset;
    uint  vertexCount;
    float worldX;
    float worldY;
    float worldZ;
    uint  visibleFaceMask;
    uint  lodTier;
    uint  visibleVertexCount;
    uint  faceStart[7];
    uint  _pad;
};


struct InhouseTerrainVertex {
    packed_short3  position;
    packed_ushort2 texCoord;
    packed_uchar4  color;
    uchar          packedLight;
    uchar          normalIndex;
};




struct MeshVertexOut {
    float4 position    [[position]];
    float2 texCoord;
    half2  lightUV;
    half4  color;
    half   fogDist;
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
    if (m.visibleVertexCount == 0u) {
        grid.set_threadgroups_per_grid(uint3(0, 0, 0));
        return;
    }

    float3 minC = float3(m.worldX, m.worldY, m.worldZ);
    float3 maxC = minC + float3(16.0, 16.0, 16.0);
    for (uint i = 0u; i < 4u; i++) {
        float4 plane = camera.frustumPlanes[i];
        float3 pv;
        pv.x = (plane.x > 0.0) ? maxC.x : minC.x;
        pv.y = (plane.y > 0.0) ? maxC.y : minC.y;
        pv.z = (plane.z > 0.0) ? maxC.z : minC.z;
        if (dot(plane.xyz, pv) + plane.w < 0.0) {
            grid.set_threadgroups_per_grid(uint3(0, 0, 0));
            return;
        }
    }
    payload.chunkIndex = tid;
    uint numGroups = (m.visibleVertexCount + 255u) / 256u;
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
    uint vStart     = tgIdx * kMaxMeshVerts;
    uint vEnd       = min(vStart + kMaxMeshVerts, m.visibleVertexCount);
    uint localVerts = vEnd - vStart;
    uint localQuads = localVerts / 4u;

    if (tid == 0u) {
        output.set_primitive_count(localQuads * 2u);
    }

    float3 chunkOrig = float3(m.worldX, m.worldY, m.worldZ);

    if (tid < localVerts) {
        uint vi = vStart + tid;
        uint srcVertex = 0u;
        uint running = 0u;
        for (uint f = 0u; f < 7u; f++) {
            uint fs = m.faceStart[f];
            uint fe = (f + 1u < 7u) ? m.faceStart[f + 1u] : m.vertexCount;
            uint visLen = ((m.visibleFaceMask & (1u << f)) != 0u)
                              ? (fe - fs)
                              : 0u;
            if (vi < running + visLen) {
                srcVertex = fs + (vi - running);
                break;
            }
            running += visLen;
        }
        uint gv = m.baseVertexOffset + srcVertex;
        InhouseTerrainVertex v = vertices[gv];
        float3 localPos = float3(short3(v.position)) / 256.0;
        float3 worldPos = localPos + chunkOrig;
        float4 viewPos  = camera.modelView * float4(worldPos, 1.0);
        MeshVertexOut out;
        out.position = camera.projection * viewPos;
        out.texCoord = float2(v.texCoord) / 65535.0f;
        out.color    = half4(float4(v.color) / 255.0f);
        uint pl      = uint(v.packedLight);
        out.lightUV  = half2((float((pl & 0xFu) * 16) + 8.0) / 256.0,
                             (float(((pl >> 4u) & 0xFu) * 16) + 8.0) / 256.0);
        out.fogDist  = half(length(viewPos.xyz));
        output.set_vertex(tid, out);
    }

    if (tid < localQuads) {
        uint b = tid * 4u;
        output.set_index(tid * 6u + 0u, b + 0u);
        output.set_index(tid * 6u + 1u, b + 1u);
        output.set_index(tid * 6u + 2u, b + 2u);
        output.set_index(tid * 6u + 3u, b + 0u);
        output.set_index(tid * 6u + 4u, b + 2u);
        output.set_index(tid * 6u + 5u, b + 3u);
    }
}





fragment half4 fragment_terrain_mesh_opaque(
    MeshVertexOut in [[stage_in]],
    texture2d<half> blockAtlas [[texture(0)]],
    texture2d<half> lightmap   [[texture(1)]],
    constant CameraUniforms& camera [[buffer(1)]]
) {
    constexpr sampler s(mag_filter::nearest, min_filter::linear,
                        mip_filter::linear, max_anisotropy(2));
    constexpr sampler lightSampler(mag_filter::nearest, min_filter::nearest,
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

    half3 light = lightmap.sample(lightSampler, float2(in.lightUV)).rgb;
    col.rgb *= max(light, half3(0.04h));

    if (camera.waterFog > 0.0f) {
        half fog = clamp(in.fogDist / half(32.0h), half(0.0h), half(0.85h));
        col.rgb  = mix(col.rgb, half3(0.05h, 0.12h, 0.3h), fog);
    }
    return half4(col.rgb, half(1.0h));
}

fragment half4 fragment_terrain_mesh_cutout(
    MeshVertexOut in [[stage_in]],
    texture2d<half> blockAtlas [[texture(0)]],
    texture2d<half> lightmap   [[texture(1)]],
    constant CameraUniforms& camera [[buffer(1)]]
) {
    constexpr sampler s(mag_filter::nearest, min_filter::linear,
                        mip_filter::linear, max_anisotropy(2));
    constexpr sampler lightSampler(mag_filter::nearest, min_filter::nearest,
                                   mip_filter::nearest);
    half4 tex = blockAtlas.sample(s, float2(in.texCoord));
    if (tex.a < half(0.5h)) discard_fragment();
    half4 col = tex * in.color;
    half3 light = lightmap.sample(lightSampler, float2(in.lightUV)).rgb;
    col.rgb *= max(light, half3(0.04h));
    if (camera.waterFog > 0.0f) {
        half fog = clamp(in.fogDist / half(32.0h), half(0.0h), half(0.85h));
        col.rgb  = mix(col.rgb, half3(0.05h, 0.12h, 0.3h), fog);
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
        half fog = clamp(in.fogDist / half(32.0h), half(0.0h), half(0.85h));
        col.rgb  = mix(col.rgb, half3(0.05h, 0.12h, 0.3h), fog);
    }
    return col;
}
