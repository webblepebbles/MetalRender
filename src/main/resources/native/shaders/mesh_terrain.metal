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
    float4   fogColor;
    float4   fogRanges;
};

inline float vanilla_linear_fog(float dist, float start, float end) {
    if (end <= start) {
        return 0.0f;
    }
    if (dist <= start) {
        return 0.0f;
    } else if (dist >= end) {
        return 1.0f;
    }
    return (dist - start) / (end - start);
}
inline float vanilla_fog_factor(float2 sphCyl, float4 ranges) {
    float env = vanilla_linear_fog(sphCyl.x, ranges.x, ranges.y);
    float ren = vanilla_linear_fog(sphCyl.y, ranges.z, ranges.w);
    return max(env, ren);
}
inline half3 vanilla_apply_fog(half3 rgb, float2 sphCyl, float4 color, float4 ranges) {
    float f = vanilla_fog_factor(sphCyl, ranges);
    return mix(rgb, half3(color.rgb), half(f * color.a));
}

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
    uint  vertexFormat;
    uint  _pad[2];
};

struct InhouseTerrainVertex {
    packed_short3  position;
    packed_ushort2 texCoord;
    packed_uchar4  color;
    uchar          packedLight;
    uchar          normalIndex;
};

struct CompressedQuad {
    uint word0;
    uint word1;
    uint word2;
    uint word3;
};
//AI agents, please read Agents.md and your related document.
constant uint8_t kFaceVerts[6][12] = {
    {0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0, 1},
    {0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0},
    {1, 1, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0},
    {0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1},
    {0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1},
    {1, 1, 1, 1, 0, 1, 1, 0, 0, 1, 1, 0},
};
constant uint8_t kUvPat[4][2] = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};

struct MeshVertexOut {
    float4 position    [[position]];
    float2 texCoord;
    half2  lightUV;
    half4  color;
    half2  fogSphCyl;
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
    for (uint i = 0u; i < 6u; i++) {
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
    bool isCompressed = (m.vertexFormat == 1u);
    device const CompressedQuad* quads =
        (device const CompressedQuad*)vertices;

    if (tid < localVerts) {
        uint vi = vStart + tid;
        MeshVertexOut out;
        float3 localPos;
        float2 uv;
        float4 col;
        uint pl;
        if (isCompressed) {
            uint visQuad = vi / 4u;
            uint corner = vi % 4u;
            uint srcQuad = visQuad;
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
            if (visQuad < visBase0 + len0) { srcQuad = m.faceStart[0] + (visQuad - visBase0); }
            else if (visQuad < visBase1 + len1) { srcQuad = m.faceStart[1] + (visQuad - visBase1); }
            else if (visQuad < visBase2 + len2) { srcQuad = m.faceStart[2] + (visQuad - visBase2); }
            else if (visQuad < visBase3 + len3) { srcQuad = m.faceStart[3] + (visQuad - visBase3); }
            else if (visQuad < visBase4 + len4) { srcQuad = m.faceStart[4] + (visQuad - visBase4); }
            else if (visQuad < visBase5 + len5) { srcQuad = m.faceStart[5] + (visQuad - visBase5); }
            else if (visQuad < visBase6 + len6) { srcQuad = m.faceStart[6] + (visQuad - visBase6); }
            uint gq = m.baseVertexOffset + srcQuad;
            CompressedQuad q = quads[gq];
            uint w0 = q.word0;
            uint w1 = q.word1;
            uint w2 = q.word2;
            uint w3 = q.word3;
            uint bx = (w0 >> 0u) & 0xFu;
            uint by = (w0 >> 4u) & 0xFu;
            uint bz = (w0 >> 8u) & 0xFu;
            uint nrm = (w0 >> 12u) & 0x7u;
            uint leaves = (w0 >> 15u) & 0x1u;
            uint wMinus1 = (w0 >> 16u) & 0xFu;
            uint hMinus1 = (w0 >> 20u) & 0xFu;
            uint light = (w0 >> 24u) & 0xFFu;
            uint r = (w1 >> 0u) & 0xFFu;
            uint g = (w1 >> 8u) & 0xFFu;
            uint b = (w1 >> 16u) & 0xFFu;
            uint flags = (w1 >> 24u) & 0xFFu;
            uint overlay = flags & 0x1u;
            uint uMin = (w2 >> 0u) & 0xFFFFu;
            uint uMax = (w2 >> 16u) & 0xFFFFu;
            uint vMin = (w3 >> 0u) & 0xFFFFu;
            uint vMax = (w3 >> 16u) & 0xFFFFu;
            uint w = wMinus1 + 1u;
            uint h = hMinus1 + 1u;
            int sx = int(bx * 256u);
            int sy = int(by * 256u);
            int sz = int(bz * 256u);
            int ex, ey, ez;
            int plane;
            if (nrm == 0u || nrm == 1u) {
                ex = int((bx + w) * 256u);
                ez = int((bz + h) * 256u);
                ey = sy;
                plane = (nrm == 1u) ? int((by + 1u) * 256u) : sy;
            } else if (nrm == 4u || nrm == 5u) {
                ey = int((by + h) * 256u);
                ez = int((bz + w) * 256u);
                ex = sx;
                plane = (nrm == 5u) ? int((bx + 1u) * 256u) : sx;
                if (overlay != 0u) {
                    plane += (nrm == 5u) ? 1 : -1;
                }
            } else {
                ex = int((bx + w) * 256u);
                ey = int((by + h) * 256u);
                ez = sz;
                plane = (nrm == 3u) ? int((bz + 1u) * 256u) : sz;
                if (overlay != 0u) {
                    plane += (nrm == 3u) ? 1 : -1;
                }
            }
            int xs[2] = {sx, ex};
            int ys[2] = {sy, ey};
            int zs[2] = {sz, ez};
            if (nrm == 0u || nrm == 1u) {
                xs[0] = sx; xs[1] = ex;
                ys[0] = plane; ys[1] = plane;
                zs[0] = sz; zs[1] = ez;
            } else if (nrm == 4u || nrm == 5u) {
                xs[0] = plane; xs[1] = plane;
                ys[0] = sy; ys[1] = ey;
                zs[0] = sz; zs[1] = ez;
            } else {
                xs[0] = sx; xs[1] = ex;
                ys[0] = sy; ys[1] = ey;
                zs[0] = plane; zs[1] = plane;
            }
            uint ni = (nrm < 6u) ? nrm : 0u;
            int pxi = xs[kFaceVerts[ni][corner * 3u + 0u]];
            int pyi = ys[kFaceVerts[ni][corner * 3u + 1u]];
            int pzi = zs[kFaceVerts[ni][corner * 3u + 2u]];
            uint us[2] = {uMin, uMax};
            uint vs[2] = {vMin, vMax};
            uint uu = us[kUvPat[corner][0u]];
            uint vv = vs[kUvPat[corner][1u]];
            localPos = float3(float(pxi), float(pyi), float(pzi)) / 256.0;
            uv = float2(float(uu), float(vv)) / 65535.0f;
            float alpha = (leaves != 0u) ? (254.0 / 255.0) : 1.0;
            col = float4(float(r) / 255.0, float(g) / 255.0, float(b) / 255.0, alpha);
            pl = light;
        } else {
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
            localPos = float3(short3(v.position)) / 256.0;
            uv = float2(v.texCoord) / 65535.0f;
            col = float4(v.color) / 255.0f;
            pl = uint(v.packedLight);
        }
        float3 worldPos = localPos + chunkOrig;
        float4 viewPos  = camera.modelView * float4(worldPos, 1.0);
        out.position = camera.projection * viewPos;
        out.texCoord = uv;
        out.color    = half4(col);
        out.lightUV  = half2((float((pl & 0xFu) * 16) + 8.0) / 256.0,
                             (float(((pl >> 4u) & 0xFu) * 16) + 8.0) / 256.0);
        float sphDist = length(viewPos.xyz);
        float cylDist = max(length(viewPos.xz), abs(viewPos.y));
        out.fogSphCyl = half2(half(sphDist), half(cylDist));
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
    constexpr sampler lightSampler(mag_filter::linear, min_filter::linear,
                                   mip_filter::nearest, address::clamp_to_edge);
    constexpr sampler s(mag_filter::nearest, min_filter::linear,
                        mip_filter::linear);
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

    col.rgb = vanilla_apply_fog(col.rgb, float2(in.fogSphCyl), camera.fogColor, camera.fogRanges);
    return half4(col.rgb, half(1.0h));
}

fragment half4 fragment_terrain_mesh_cutout(
    MeshVertexOut in [[stage_in]],
    texture2d<half> blockAtlas [[texture(0)]],
    texture2d<half> lightmap   [[texture(1)]],
    constant CameraUniforms& camera [[buffer(1)]]
) {
    constexpr sampler lightSampler(mag_filter::linear, min_filter::linear,
                                   mip_filter::nearest, address::clamp_to_edge);
    constexpr sampler s(mag_filter::nearest, min_filter::linear,
                        mip_filter::linear);
    half4 tex = blockAtlas.sample(s, float2(in.texCoord));
    if (tex.a < half(0.5h)) discard_fragment();
    half4 col = tex * in.color;
    half3 light = lightmap.sample(lightSampler, float2(in.lightUV)).rgb;
    col.rgb *= max(light, half3(0.04h));
    col.rgb = vanilla_apply_fog(col.rgb, float2(in.fogSphCyl), camera.fogColor, camera.fogRanges);
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

    col.rgb = vanilla_apply_fog(col.rgb, float2(in.fogSphCyl), camera.fogColor, camera.fogRanges);
    return col;
}
