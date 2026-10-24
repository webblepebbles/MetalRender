#ifndef MR_TERRAIN_DECODE_H
#define MR_TERRAIN_DECODE_H

#include <metal_stdlib>
using namespace metal;

struct SectionRecord {
    int  x;
    int  y;
    int  z;
    uint firstUnit;
    uint unitCount;
    uint lodTier;
    uint flags;
    uint reserved;
};

struct UnitRecord {
    uint firstQuad;
    uint meta;
    int  plane;
    uint box;
};

static_assert(sizeof(SectionRecord) == 32, "SectionRecord must be 32 bytes");
static_assert(sizeof(UnitRecord) == 16, "UnitRecord must be 16 bytes");

struct TerrainRawVertex {
    packed_short3  position;
    packed_ushort2 texCoord;
    packed_uchar4  color;
    uchar          packedLight;
    uchar          normalIndex;
};

struct TerrainCorner {
    float3 pos;
    float2 uv;
    float3 rgb;
    float  alpha;
    uint   light;
    float2 tile;
    float4 tileRect;
};

constant uint8_t kTerrFaceVerts[6][12] = {
    {0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0, 1},
    {0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0},
    {1, 1, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0},
    {0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1},
    {0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1},
    {1, 1, 1, 1, 0, 1, 1, 0, 0, 1, 1, 0},
};
constant uint8_t kTerrUvPat[4][2] = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
constant uint kTerrCornerOfIndex[6] = {0, 1, 2, 2, 3, 0};

inline uint terr_bits(device const uint* w, uint off, uint n) {
    uint lo = off >> 5;
    uint sh = off & 31u;
    uint v = w[lo] >> sh;
    if (sh + n > 32u) {
        v |= w[lo + 1u] << (32u - sh);
    }
    return v & ((1u << n) - 1u);
}

inline int terr_sext(uint v, uint n) {
    return int(v << (32u - n)) >> (32u - n);
}

inline float2 terr_sprite_uv(device const uint2* sprites, uint sprite, uint p) {
    uint2 sr = sprites[sprite];
    uint u0 = sr.x & 0xFFFFu;
    uint v0 = sr.x >> 16;
    uint u1 = sr.y & 0xFFFFu;
    uint v1 = sr.y >> 16;
    uint u = kTerrUvPat[p][0] != 0 ? u1 : u0;
    uint v = kTerrUvPat[p][1] != 0 ? v1 : v0;
    return float2(float(u), float(v)) / 65535.0f;
}

inline TerrainCorner terr_decode_a(device const uint* w, uint corner, device const uint2* sprites) {
    uint w0 = w[0];
    uint w1 = w[1];
    uint w2 = w[2];
    uint w3 = w[3];
    int bx = int(w0 & 15u);
    int by = int((w0 >> 4) & 15u);
    int bz = int((w0 >> 8) & 15u);
    uint face = (w0 >> 12) & 7u;
    bool leaf = ((w0 >> 15) & 1u) != 0u;
    int overlay = int((w0 >> 16) & 1u);
    uint code = (w0 >> 17) & 7u;
    uint sprite = ((w0 >> 20) & 0xFFFu) | (((w1 >> 24) & 1u) << 12);
    int sx = bx * 256;
    int sy = by * 256;
    int sz = bz * 256;
    int ex = sx + 256;
    int ey = sy + 256;
    int ez = sz + 256;
    int xs[2] = {sx, ex};
    int ys[2] = {sy, ey};
    int zs[2] = {sz, ez};
    if (face < 2u) {
        int plane = face == 1u ? ey : sy;
        ys[0] = plane;
        ys[1] = plane;
    } else if (face >= 4u) {
        int plane = (face == 5u ? ex : sx) + (overlay != 0 ? (face == 5u ? 1 : -1) : 0);
        xs[0] = plane;
        xs[1] = plane;
    } else {
        int plane = (face == 3u ? ez : sz) + (overlay != 0 ? (face == 3u ? 1 : -1) : 0);
        zs[0] = plane;
        zs[1] = plane;
    }
    uint f = face < 6u ? face : 0u;
    int px = xs[kTerrFaceVerts[f][corner * 3u]];
    int py = ys[kTerrFaceVerts[f][corner * 3u + 1u]];
    int pz = zs[kTerrFaceVerts[f][corner * 3u + 2u]];
    uint rot = code & 3u;
    bool flip = (code & 4u) != 0u;
    uint p = flip ? ((rot + 4u - corner) & 3u) : ((corner + rot) & 3u);
    TerrainCorner c;
    c.pos = float3(float(px), float(py), float(pz)) / 256.0f;
    c.uv = terr_sprite_uv(sprites, sprite, p);
    float3 tint = float3(float(w1 & 255u), float((w1 >> 8) & 255u), float((w1 >> 16) & 255u));
    float shade = float((w3 >> (8u * corner)) & 255u);
    c.rgb = tint * shade / (255.0f * 255.0f);
    c.alpha = leaf ? (254.0f / 255.0f) : 1.0f;
    c.light = (w2 >> (8u * corner)) & 255u;
    c.tile = float2(0.0f);
    c.tileRect = float4(0.0f);
    return c;
}

inline TerrainCorner terr_decode_b(device const uint* w, uint corner, device const uint2* sprites) {
    int ox = int(terr_bits(w, 0, 12)) - 1024;
    int oy = int(terr_bits(w, 12, 12)) - 1024;
    int oz = int(terr_bits(w, 24, 12)) - 1024;
    int e1x = terr_sext(terr_bits(w, 36, 13), 13);
    int e1y = terr_sext(terr_bits(w, 49, 13), 13);
    int e1z = terr_sext(terr_bits(w, 62, 13), 13);
    int e2x = terr_sext(terr_bits(w, 75, 13), 13);
    int e2y = terr_sext(terr_bits(w, 88, 13), 13);
    int e2z = terr_sext(terr_bits(w, 101, 13), 13);
    uint sprite = terr_bits(w, 114, 13);
    uint code = terr_bits(w, 127, 3);
    bool leaf = terr_bits(w, 130, 1) != 0u;
    uint fu0 = terr_bits(w, 131, 9);
    uint fv0 = terr_bits(w, 140, 9);
    uint fu1 = terr_bits(w, 149, 9);
    uint fv1 = terr_bits(w, 158, 9);
    float3 tint = float3(float(terr_bits(w, 167, 8)), float(terr_bits(w, 175, 8)), float(terr_bits(w, 183, 8)));
    int cx = ox;
    int cy = oy;
    int cz = oz;
    if (corner == 1u) {
        cx += e1x; cy += e1y; cz += e1z;
    } else if (corner == 2u) {
        cx += e1x + e2x; cy += e1y + e2y; cz += e1z + e2z;
    } else if (corner == 3u) {
        cx += e2x; cy += e2y; cz += e2z;
    }
    uint2 sr = sprites[sprite];
    float su0 = float(sr.x & 0xFFFFu);
    float sv0 = float(sr.x >> 16);
    float su1 = float(sr.y & 0xFFFFu);
    float sv1 = float(sr.y >> 16);
    float u0 = su0 + (su1 - su0) * (float(fu0) / 256.0f);
    float u1 = su0 + (su1 - su0) * (float(fu1) / 256.0f);
    float v0 = sv0 + (sv1 - sv0) * (float(fv0) / 256.0f);
    float v1 = sv0 + (sv1 - sv0) * (float(fv1) / 256.0f);
    uint rot = code & 3u;
    bool flip = (code & 4u) != 0u;
    uint p = flip ? ((rot + 4u - corner) & 3u) : ((corner + rot) & 3u);
    TerrainCorner c;
    c.pos = float3(float(cx * 2), float(cy * 2), float(cz * 2)) / 256.0f;
    c.uv = float2(kTerrUvPat[p][0] != 0 ? u1 : u0, kTerrUvPat[p][1] != 0 ? v1 : v0) / 65535.0f;
    float shade = float(terr_bits(w, 223u + 8u * corner, 8));
    c.rgb = tint * shade / (255.0f * 255.0f);
    c.alpha = leaf ? (254.0f / 255.0f) : 1.0f;
    c.light = terr_bits(w, 191u + 8u * corner, 8);
    c.tile = float2(0.0f);
    c.tileRect = float4(0.0f);
    float n1 = float(abs(e1x) + abs(e1y) + abs(e1z)) / 128.0f;
    float n2 = float(abs(e2x) + abs(e2y) + abs(e2z)) / 128.0f;
    if (n1 > 1.5f || n2 > 1.5f) {
        uint p0 = flip ? ((rot + 4u) & 3u) : (rot & 3u);
        uint p1 = flip ? ((rot + 3u) & 3u) : ((1u + rot) & 3u);
        bool uAlongE1 = kTerrUvPat[p1][0] != kTerrUvPat[p0][0];
        float nu = uAlongE1 ? n1 : n2;
        float nv = uAlongE1 ? n2 : n1;
        c.tile = float2(kTerrUvPat[p][0] != 0 ? nu : 0.0f, kTerrUvPat[p][1] != 0 ? nv : 0.0f);
        c.tileRect = float4(su0 + (su1 - su0) * (float(fu0) / 256.0f),
                            sv0 + (sv1 - sv0) * (float(fv0) / 256.0f),
                            (su1 - su0) * (float(fu1 - fu0) / 256.0f),
                            (sv1 - sv0) * (float(fv1 - fv0) / 256.0f)) / float4(65535.0f);
    }
    return c;
}

inline TerrainCorner terr_decode_c(device const uint* w, uint corner) {
    device const TerrainRawVertex* v = (device const TerrainRawVertex*)(w) + corner;
    TerrainRawVertex raw = *v;
    TerrainCorner c;
    c.pos = float3(short3(raw.position)) / 256.0f;
    c.uv = float2(raw.texCoord) / 65535.0f;
    float4 col = float4(raw.color) / 255.0f;
    c.rgb = col.rgb;
    c.alpha = col.a;
    c.light = uint(raw.packedLight);
    c.tile = float2(0.0f);
    c.tileRect = float4(0.0f);
    return c;
}

inline TerrainCorner terr_decode(device const uint* arena, device const uint2* sprites, uint tier,
                                 uint firstQuad, uint quad, uint corner) {
    if (tier == 0u) {
        return terr_decode_a(arena + (ulong(firstQuad) + ulong(quad)) * 4ul, corner, sprites);
    }
    if (tier == 1u) {
        return terr_decode_b(arena + (ulong(firstQuad) + ulong(quad) * 2ul) * 4ul, corner, sprites);
    }
    return terr_decode_c(arena + (ulong(firstQuad) + ulong(quad) * 4ul) * 4ul, corner);
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

struct TerrainVOut {
    float4 position [[position]];
    half4  color;
    half2  lightUV;
    float2 tile;
    uint   quadAddr [[flat]];
};

struct TerrainVOutSlim {
    float4 position [[position]];
    float2 texCoord;
    half4  color;
    half2  lightUV;
};

#endif
