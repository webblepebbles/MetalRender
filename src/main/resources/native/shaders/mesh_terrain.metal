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
    uint  visibleFaceStart[7];
    uint  faceVertexCount[7];
    uint  _pad[3];
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
        uint srcVertex = vi;
        uint visBase0 = m.visibleFaceStart[0];
        uint visBase1 = m.visibleFaceStart[1];
        uint visBase2 = m.visibleFaceStart[2];
        uint visBase3 = m.visibleFaceStart[3];
        uint visBase4 = m.visibleFaceStart[4];
        uint visBase5 = m.visibleFaceStart[5];
        uint visBase6 = m.visibleFaceStart[6];
        uint len0 = m.faceVertexCount[0];
        uint len1 = m.faceVertexCount[1];
        uint len2 = m.faceVertexCount[2];
        uint len3 = m.faceVertexCount[3];
        uint len4 = m.faceVertexCount[4];
        uint len5 = m.faceVertexCount[5];
        uint len6 = m.faceVertexCount[6];
        if (vi < visBase0 + len0) { srcVertex = m.faceStart[0] + (vi - visBase0); }
        else if (vi < visBase1 + len1) { srcVertex = m.faceStart[1] + (vi - visBase1); }
        else if (vi < visBase2 + len2) { srcVertex = m.faceStart[2] + (vi - visBase2); }
        else if (vi < visBase3 + len3) { srcVertex = m.faceStart[3] + (vi - visBase3); }
        else if (vi < visBase4 + len4) { srcVertex = m.faceStart[4] + (vi - visBase4); }
        else if (vi < visBase5 + len5) { srcVertex = m.faceStart[5] + (vi - visBase5); }
        else if (vi < visBase6 + len6) { srcVertex = m.faceStart[6] + (vi - visBase6); }
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
        out.fogDist  = half(dot(viewPos.xyz, viewPos.xyz));
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
                        mip_filter::linear);
    constexpr sampler lightSampler(mag_filter::linear, min_filter::linear,
                                   mip_filter::nearest, address::clamp_to_edge);
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
        half fog = clamp(half(sqrt((float)in.fogDist)) / half(32.0h), half(0.0h), half(0.85h));
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
                        mip_filter::linear);
    constexpr sampler lightSampler(mag_filter::linear, min_filter::linear,
                                   mip_filter::nearest, address::clamp_to_edge);
    half4 tex = blockAtlas.sample(s, float2(in.texCoord));
    if (tex.a < half(0.5h)) discard_fragment();
    half4 col = tex * in.color;
    half3 light = lightmap.sample(lightSampler, float2(in.lightUV)).rgb;
    col.rgb *= max(light, half3(0.04h));
    if (camera.waterFog > 0.0f) {
        half fog = clamp(half(sqrt((float)in.fogDist)) / half(32.0h), half(0.0h), half(0.85h));
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
        half fog = clamp(half(sqrt((float)in.fogDist)) / half(32.0h), half(0.0h), half(0.85h));
        col.rgb  = mix(col.rgb, half3(0.05h, 0.12h, 0.3h), fog);
    }
    return col;
}
