#include <metal_stdlib>
#include "terrain_decode.h"
#include "terrain_shade.h"
#include "terrain_cull.h"
using namespace metal;

constant uint kFarCols = 34u;
constant uint kFarOffsets = 34u * 34u + 1u;
constant uint kFarRanges = 21u;
constant uint kFarLists = 6u;
constant uint kFarLookWords = 26u;
constant uint kFarNoSprite = 0x1FFFu;
constant uint kFarMaxRuns = 4u;

struct FarTileRec {
    int  ox;
    int  oz;
    int  minY;
    uint level;
    uint blob;
    uint tint;
    uint quadBase;
    uint quadCap;
    int  yLo;
    int  yHi;
    uint flags;
    uint used;
    uint counts[21];
    uint starts[21];
    uint lookPal;
    uint plane;
};

struct FarCullParams {
    int4  nearBox;
    float farRadius;
    uint  listCap;
    uint  tileCount;
    uint  reverseZ;
};

struct FarDrawParams {
    int4   camBlock;
    float4 camFrac;
    float  depthPush;
    uint   reverseZ;
    uint   pad0;
    uint   pad1;
};

inline uint far_look_header(device const uint* looks, uint look) {
    return looks[look * kFarLookWords];
}

inline uint far_cls(uint header) { return header & 3u; }
inline uint far_shape(uint header) { return (header >> 2) & 15u; }
inline bool far_occluder(uint header) { return ((header >> 9) & 1u) != 0u; }

struct FarSpan {
    uint y0;
    uint len;
    uint sky;
    uint block;
    uint look;
    uint tint;
    uint header;
    bool planed;
};

__attribute__((noinline)) FarSpan far_span(device const uint* blob, device const uint* looks, device const uint* pal,
                                           bool compact, uint index) {
    uint lo = blob[kFarOffsets + index * 2u];
    uint hi = blob[kFarOffsets + index * 2u + 1u];
    FarSpan s;
    s.y0 = lo & 1023u;
    s.len = ((lo >> 10) & 1023u) + 1u;
    s.sky = (lo >> 20) & 15u;
    s.block = (lo >> 24) & 15u;
    s.look = hi & 0xFFFFu;
    s.tint = (hi >> 16) & 255u;
    s.planed = ((hi >> 24) & 1u) != 0u;
    s.header = far_look_header(looks, compact ? pal[s.look] : s.look);
    return s;
}

inline bool far_hides(FarSpan self, FarSpan other) {
    if (far_occluder(other.header)) {
        return far_shape(self.header) != 4u && far_shape(self.header) != 5u && far_shape(self.header) != 3u;
    }
    return far_cls(self.header) == 2u && far_cls(other.header) == 2u;
}

inline bool far_mergeable(uint header) {
    uint shape = far_shape(header);
    return shape == 0u || shape == 1u || shape == 2u || shape >= 8u;
}

struct FarFace {
    uint pos;
    uint y;
    uint h;
    uint look;
    uint tint;
    uint light;
    uint cls;
    uint extra;
    bool merge;
};

struct FarRun {
    uint key0;
    uint key1;
    uint start;
    uint width;
    uint y;
    uint h;
    uint lineOther;
    uint cls;
    uint extra;
    bool live;
    bool touched;
    bool direct;
};

struct FarEmitter {
    threadgroup atomic_uint* counters;
    device uint* quads;
    uint base;
    threadgroup uint* starts;
    bool emit;
    uint dir;
    uint cap;
    threadgroup uint4* rows;
    threadgroup uint* rowCount;
    bool compact;
};

constant uint kFarRowSlots = 24u;

__attribute__((noinline)) void far_emit(thread FarEmitter& e, uint x, uint z, uint y, uint wm1, uint hm1, uint look, uint tint,
                     uint light, uint cls, uint extra) {
    uint r = e.dir * 3u + cls;
    uint slot = atomic_fetch_add_explicit(&e.counters[r], 1u, memory_order_relaxed);
    if (!e.emit) {
        return;
    }
    uint at = e.starts[r] + slot;
    if (at >= e.cap) {
        return;
    }
    uint w0 = (x & 31u) | ((z & 31u) << 5) | ((y & 1023u) << 10) | ((e.dir & 7u) << 20) | ((wm1 & 31u) << 23)
              | (((light >> 4) & 15u) << 28);
    if (e.compact) {
        uint c1 = (hm1 & 1023u) | ((light & 15u) << 10) | ((tint & 255u) << 14) | ((extra & 3u) << 22)
                  | ((look & 255u) << 24);
        device uint* q = e.quads + (ulong(e.base) + ulong(at)) * 2ul;
        q[0] = w0;
        q[1] = c1;
        return;
    }
    uint w1 = (hm1 & 1023u) | ((light & 15u) << 10) | ((tint & 255u) << 14) | ((extra & 255u) << 22);
    uint w2 = look & 0xFFFFu;
    device uint* q = e.quads + (ulong(e.base) + ulong(at)) * 3ul;
    q[0] = w0;
    q[1] = w1;
    q[2] = w2;
}

__attribute__((noinline)) void far_flush(thread FarEmitter& e, thread FarRun& r, bool alongZ) {
    if (!r.live) {
        return;
    }
    if (e.dir <= 1u && !r.direct) {
        uint row = e.dir * 32u + r.lineOther;
        uint n = e.rowCount[row];
        if (n < kFarRowSlots) {
            e.rows[row * kFarRowSlots + n] = uint4(r.key0, r.key1, r.start | (r.width << 6) | (r.cls << 12) | (r.extra << 16), 0u);
            e.rowCount[row] = n + 1u;
            r.live = false;
            return;
        }
    }
    uint x = alongZ ? r.lineOther : r.start;
    uint z = alongZ ? r.start : r.lineOther;
    far_emit(e, x, z, r.y, r.width - 1u, r.h - 1u, r.key0 & 0xFFFFu, (r.key0 >> 16) & 255u, (r.key0 >> 24) & 255u,
             r.cls, r.extra);
    r.live = false;
}

__attribute__((noinline)) void far_offer(thread FarEmitter& e, thread FarRun* runs, FarFace f, uint line, bool alongZ) {
    uint key0 = (f.look & 0xFFFFu) | ((f.tint & 255u) << 16) | ((f.light & 255u) << 24);
    uint key1 = f.y | ((f.h - 1u) << 10) | (f.extra << 20);
    if (f.merge) {
        for (uint i = 0u; i < kFarMaxRuns; i++) {
            if (runs[i].live && !runs[i].touched && runs[i].key0 == key0 && runs[i].key1 == key1
                && runs[i].start + runs[i].width == f.pos && runs[i].width < 32u) {
                runs[i].width++;
                runs[i].touched = true;
                return;
            }
        }
    }
    uint pick = kFarMaxRuns;
    for (uint i = 0u; i < kFarMaxRuns; i++) {
        if (!runs[i].live) {
            pick = i;
            break;
        }
    }
    if (pick == kFarMaxRuns) {
        uint oldest = 0u;
        for (uint i = 1u; i < kFarMaxRuns; i++) {
            if (runs[i].start < runs[oldest].start) {
                oldest = i;
            }
        }
        far_flush(e, runs[oldest], alongZ);
        pick = oldest;
    }
    FarRun r;
    r.key0 = key0;
    r.key1 = key1;
    r.start = f.pos;
    r.width = 1u;
    r.y = f.y;
    r.h = f.h;
    r.lineOther = line;
    r.cls = f.cls;
    r.extra = f.extra;
    r.live = true;
    r.touched = true;
    r.direct = !f.merge;
    if (!f.merge) {
        far_flush(e, r, alongZ);
        return;
    }
    runs[pick] = r;
}

inline bool far_row_has(threadgroup uint4* rows, threadgroup uint* rowCount, uint row, uint4 v) {
    uint n = rowCount[row];
    for (uint i = 0u; i < n; i++) {
        uint4 o = rows[row * kFarRowSlots + i];
        if (o.x == v.x && o.y == v.y && o.z == v.z) {
            return true;
        }
    }
    return false;
}

__attribute__((noinline)) void far_merge_rows(thread FarEmitter& e, uint dir, uint line) {
    uint row = dir * 32u + line;
    uint n = e.rowCount[row];
    for (uint i = 0u; i < n; i++) {
        uint4 v = e.rows[row * kFarRowSlots + i];
        if (line > 0u && far_row_has(e.rows, e.rowCount, row - 1u, v)) {
            continue;
        }
        uint d = 1u;
        while (line + d < 32u && far_row_has(e.rows, e.rowCount, row + d, v)) {
            d++;
        }
        uint start = v.z & 63u;
        uint width = (v.z >> 6) & 63u;
        uint cls = (v.z >> 12) & 3u;
        uint extra = (v.z >> 16) & 255u;
        far_emit(e, start, line, v.y & 1023u, width - 1u, d - 1u, v.x & 0xFFFFu, (v.x >> 16) & 255u, (v.x >> 24) & 255u,
                 cls, extra);
    }
}

inline uint far_col(int x, int z) {
    return uint(z + 1) * kFarCols + uint(x + 1);
}

__attribute__((noinline)) void far_line(thread FarEmitter& e, device const uint* blob, device const uint* looks,
                     device const uint* pal, uint dir, uint line, uint level) {
    FarRun runs[kFarMaxRuns];
    for (uint i = 0u; i < kFarMaxRuns; i++) {
        runs[i].live = false;
        runs[i].touched = false;
    }
    bool alongZ = dir == 4u || dir == 5u;
    int ndx = dir == 4u ? -1 : (dir == 5u ? 1 : 0);
    int ndz = dir == 2u ? -1 : (dir == 3u ? 1 : 0);
    for (uint p = 0u; p < 32u; p++) {
        int x = alongZ ? int(line) : int(p);
        int z = alongZ ? int(p) : int(line);
        uint c = far_col(x, z);
        uint s0 = blob[c];
        uint s1 = blob[c + 1u];
        if (dir == 6u) {
            if (level == 0u) {
                for (uint si = s0; si < s1; si++) {
                    FarSpan s = far_span(blob, looks, pal, e.compact, si);
                    if (far_shape(s.header) != 3u) {
                        continue;
                    }
                    uint light = s.block | (s.sky << 4);
                    for (uint k = 0u; k < 4u; k++) {
                        far_emit(e, uint(x), uint(z), s.y0, 0u, s.len - 1u, s.look, s.tint, light, 1u, k);
                    }
                }
            }
            continue;
        }
        for (uint si = s0; si < s1; si++) {
            FarSpan s = far_span(blob, looks, pal, e.compact, si);
            uint shape = far_shape(s.header);
            if (shape == 3u) {
                continue;
            }
            uint light = s.block | (s.sky << 4);
            uint cls = min(far_cls(s.header), 2u);
            bool merge = far_mergeable(s.header);
            uint top = s.y0 + s.len;
            if (dir == 1u) {
                bool covered = false;
                if (si + 1u < s1) {
                    FarSpan n = far_span(blob, looks, pal, e.compact, si + 1u);
                    covered = n.y0 == top && far_hides(s, n) && far_shape(s.header) == 0u;
                }
                if (!covered && !s.planed) {
                    FarFace f = {p, top - 1u, 1u, s.look, s.tint, light, cls, 0u, merge};
                    far_offer(e, runs, f, line, alongZ);
                }
            } else if (dir == 0u) {
                bool covered = s.y0 == 0u;
                if (!covered && si > s0) {
                    FarSpan n = far_span(blob, looks, pal, e.compact, si - 1u);
                    covered = n.y0 + n.len == s.y0 && far_hides(s, n) && far_shape(s.header) == 0u;
                }
                if (!covered) {
                    FarFace f = {p, s.y0, 1u, s.look, s.tint, light, cls, 0u, merge};
                    far_offer(e, runs, f, line, alongZ);
                }
            } else {
                uint nc = far_col(x + ndx, z + ndz);
                uint n0 = blob[nc];
                uint n1 = blob[nc + 1u];
                uint cur = s.y0;
                bool inset = shape == 4u || shape == 5u;
                if (cls == 2u && n0 == n1) {
                    continue;
                }
                if (!inset) {
                    for (uint ni = n0; ni < n1 && cur < top; ni++) {
                        FarSpan n = far_span(blob, looks, pal, e.compact, ni);
                        uint nend = n.y0 + n.len;
                        if (nend <= cur || !far_hides(s, n)) {
                            continue;
                        }
                        if (n.y0 >= top) {
                            break;
                        }
                        if (n.y0 > cur) {
                            FarFace f = {p, cur, n.y0 - cur, s.look, s.tint, light, cls, 0u, merge};
                            far_offer(e, runs, f, line, alongZ);
                        }
                        cur = max(cur, nend);
                    }
                }
                if (cur < top) {
                    FarFace f = {p, cur, top - cur, s.look, s.tint, light, cls, 0u, merge};
                    far_offer(e, runs, f, line, alongZ);
                }
            }
        }
        for (uint i = 0u; i < kFarMaxRuns; i++) {
            if (runs[i].live && !runs[i].touched) {
                far_flush(e, runs[i], alongZ);
            }
            runs[i].touched = false;
        }
    }
    for (uint i = 0u; i < kFarMaxRuns; i++) {
        far_flush(e, runs[i], alongZ);
    }
}

kernel void far_build(
    device FarTileRec* tiles [[buffer(0)]],
    device const uint* blobs [[buffer(1)]],
    device const uint* looks [[buffer(2)]],
    device uint* quads [[buffer(3)]],
    device const uint* buildList [[buffer(4)]],
    device const uint* tints [[buffer(5)]],
    uint group [[threadgroup_position_in_grid]],
    uint tid [[thread_index_in_threadgroup]]
) {
    threadgroup atomic_uint counters[21];
    threadgroup uint starts[21];
    threadgroup uint total;
    threadgroup uint4 rows[64 * kFarRowSlots];
    threadgroup uint rowCount[64];
    uint slot = buildList[group];
    device FarTileRec& rec = tiles[slot];
    if (tid < kFarRanges) {
        atomic_store_explicit(&counters[tid], 0u, memory_order_relaxed);
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);
    uint dir = tid / 32u;
    uint line = tid % 32u;
    device const uint* blob = blobs + rec.blob;
    FarEmitter e;
    e.counters = counters;
    e.quads = quads;
    e.base = rec.quadBase;
    e.starts = starts;
    e.dir = dir;
    e.cap = rec.quadCap;
    e.rows = rows;
    e.rowCount = rowCount;
    e.compact = (rec.flags & 2u) != 0u;
    device const uint* pal = tints + rec.tint + rec.lookPal;
    for (uint phase = 0u; phase < 2u; phase++) {
        e.emit = phase == 1u;
        if (tid < 64u) {
            rowCount[tid] = 0u;
        }
        threadgroup_barrier(mem_flags::mem_threadgroup);
        if (dir < 7u) {
            far_line(e, blob, looks, pal, dir, line, rec.level);
        }
        if (dir == 1u && line == 0u && (rec.flags & 4u) != 0u) {
            uint pw = rec.plane;
            far_emit(e, 0u, 0u, pw & 1023u, 31u, 31u, rec.flags >> 16, (pw >> 18) & 255u, (pw >> 10) & 255u, 2u, 0u);
        }
        threadgroup_barrier(mem_flags::mem_threadgroup);
        if (dir <= 1u) {
            far_merge_rows(e, dir, line);
        }
        threadgroup_barrier(mem_flags::mem_threadgroup);
        if (phase == 0u) {
            if (tid == 0u) {
                uint run = 0u;
                for (uint r = 0u; r < kFarRanges; r++) {
                    uint c = atomic_load_explicit(&counters[r], memory_order_relaxed);
                    starts[r] = run;
                    run += c;
                }
                total = run;
            }
            threadgroup_barrier(mem_flags::mem_threadgroup);
            if (tid < kFarRanges) {
                uint c = atomic_load_explicit(&counters[tid], memory_order_relaxed);
                uint st = starts[tid];
                rec.counts[tid] = st >= rec.quadCap ? 0u : min(c, rec.quadCap - st);
                rec.starts[tid] = st;
                atomic_store_explicit(&counters[tid], 0u, memory_order_relaxed);
            }
            if (tid == 0u) {
                rec.used = min(total, rec.quadCap);
            }
            threadgroup_barrier(mem_flags::mem_threadgroup);
        }
    }
}

kernel void far_cull_reset(
    device atomic_uint* args [[buffer(0)]],
    uint tid [[thread_position_in_grid]]
) {
    if (tid != 0u) {
        return;
    }
    for (uint i = 0u; i < kFarLists; i++) {
        atomic_store_explicit(&args[i * 5u + 0u], 192u, memory_order_relaxed);
        atomic_store_explicit(&args[i * 5u + 1u], 0u, memory_order_relaxed);
        atomic_store_explicit(&args[i * 5u + 2u], 0u, memory_order_relaxed);
        atomic_store_explicit(&args[i * 5u + 3u], 0u, memory_order_relaxed);
        atomic_store_explicit(&args[i * 5u + 4u], 0u, memory_order_relaxed);
    }
    atomic_store_explicit(&args[kFarLists * 5u + 0u], 0u, memory_order_relaxed);
    atomic_store_explicit(&args[kFarLists * 5u + 1u], 0u, memory_order_relaxed);
}

inline bool far_dir_facing(uint dir, float3 cam, float3 lo, float3 hi) {
    switch (dir) {
        case 0u: return cam.y < hi.y;
        case 1u: return cam.y > lo.y;
        case 2u: return cam.z < hi.z;
        case 3u: return cam.z > lo.z;
        case 4u: return cam.x < hi.x;
        case 5u: return cam.x > lo.x;
        default: return true;
    }
}

kernel void far_cull(
    constant TerrainCullFrame& f [[buffer(0)]],
    constant FarCullParams& p [[buffer(1)]],
    device const FarTileRec* tiles [[buffer(2)]],
    device const uint* live [[buffer(3)]],
    device atomic_uint* args [[buffer(4)]],
    device uint2* lists [[buffer(5)]],
    device const uchar* mask [[buffer(6)]],
    texture2d<float> hiz [[texture(0)]],
    uint group [[threadgroup_position_in_grid]],
    uint lane [[thread_index_in_simdgroup]]
) {
    if (group >= p.tileCount) {
        return;
    }
    uint slot = live[group];
    FarTileRec rec = tiles[slot];
    float size = float(32u << rec.level);
    float3 lo = float3(float(rec.ox - f.camBlock.x), float(rec.yLo - f.camBlock.y), float(rec.oz - f.camBlock.z))
                - f.camFrac.xyz;
    float3 hi = float3(lo.x + size, float(rec.yHi - f.camBlock.y) - f.camFrac.y, lo.z + size);
    float nx = clamp(0.0f, lo.x, hi.x);
    float nz = clamp(0.0f, lo.z, hi.z);
    if (nx * nx + nz * nz > (p.farRadius + 16.0f) * (p.farRadius + 16.0f)) {
        return;
    }
    if (!terr_box_visible(f, lo, hi)) {
        return;
    }
    if (terr_hiz_occluded(f, hiz, lo, hi)) {
        return;
    }
    int cx0 = rec.ox >> 4;
    int cz0 = rec.oz >> 4;
    int cn = int((32u << rec.level) >> 4);
    int ix0 = max(cx0, p.nearBox.x);
    int iz0 = max(cz0, p.nearBox.y);
    int ix1 = min(cx0 + cn - 1, p.nearBox.z);
    int iz1 = min(cz0 + cn - 1, p.nearBox.w);
    bool masked = false;
    if (ix0 <= ix1 && iz0 <= iz1) {
        uint w = uint(ix1 - ix0 + 1);
        uint n = w * uint(iz1 - iz0 + 1);
        uint ready = 0u;
        for (uint i = lane; i < n; i += 32u) {
            int cx = ix0 + int(i % w);
            int cz = iz0 + int(i / w);
            ready += mask[uint(cx & 127) + uint(cz & 127) * 128u] != 0u ? 1u : 0u;
        }
        ready = simd_sum(ready);
        bool whole = ix0 == cx0 && iz0 == cz0 && ix1 == cx0 + cn - 1 && iz1 == cz0 + cn - 1;
        if (whole && ready == n) {
            return;
        }
        masked = ready > 0u;
    }
    float3 cam = float3(0.0f);
    uint quads = 0u;
    if (lane < kFarRanges) {
        uint r = lane;
        uint count = rec.counts[r];
        uint dir = r / 3u;
        uint cls = r % 3u;
        if (count > 0u && far_dir_facing(dir, cam, lo, hi)) {
            uint entries = (count + 31u) / 32u;
            uint li = cls + (masked ? 3u : 0u);
            uint b = atomic_fetch_add_explicit(&args[li * 5u + 1u], entries, memory_order_relaxed);
            uint first = rec.quadBase + rec.starts[r];
            for (uint k = 0u; k < entries; k++) {
                uint at = b + k;
                if (at >= p.listCap) {
                    break;
                }
                uint n = min(32u, count - k * 32u);
                lists[li * p.listCap + at] = uint2(first + k * 32u, (slot << 5) | (n - 1u));
            }
            quads = count;
        }
    }
    uint total = simd_sum(quads);
    if (lane == 0u && total > 0u) {
        atomic_fetch_add_explicit(&args[kFarLists * 5u + 0u], 1u, memory_order_relaxed);
        atomic_fetch_add_explicit(&args[kFarLists * 5u + 1u], total, memory_order_relaxed);
    }
}

kernel void far_cull_finish(
    device atomic_uint* args [[buffer(0)]],
    constant FarCullParams& p [[buffer(1)]],
    uint tid [[thread_position_in_grid]]
) {
    if (tid >= kFarLists) {
        return;
    }
    uint count = min(atomic_load_explicit(&args[tid * 5u + 1u], memory_order_relaxed), p.listCap);
    atomic_store_explicit(&args[tid * 5u + 1u], count, memory_order_relaxed);
}

struct FarVOut {
    float4 position [[position]];
    float3 local;
    half4  tint;
    half2  lightUV;
    int3   origin [[flat]];
    uint   face [[flat]];
    uint   look [[flat]];
    uint   level [[flat]];
};

constant float kFarShade[7] = {0.5f, 1.0f, 0.8f, 0.8f, 0.6f, 0.6f, 1.0f};

vertex FarVOut far_vs(
    uint vid [[vertex_id]],
    uint iid [[instance_id]],
    device const uint2* list [[buffer(0)]],
    constant float4x4& projectionMatrix [[buffer(1)]],
    constant float4x4& modelViewMatrix [[buffer(2)]],
    constant FarDrawParams& dp [[buffer(4)]],
    device const uint* quads [[buffer(5)]],
    device const FarTileRec* tiles [[buffer(6)]],
    device const uint* looks [[buffer(7)]],
    device const uint* tints [[buffer(8)]]
) {
    FarVOut out;
    out.position = float4(0.0f, 0.0f, 2.0f, 1.0f);
    out.local = float3(0.0f);
    out.tint = half4(1.0h);
    out.lightUV = half2(0.0h);
    out.origin = int3(0);
    out.face = 0u;
    out.look = 0u;
    out.level = 0u;
    uint q = vid >> 2;
    uint corner = vid & 3u;
    uint2 entry = list[iid];
    if (q > (entry.y & 31u)) {
        return out;
    }
    uint slot = entry.y >> 5;
    FarTileRec rec = tiles[slot];
    bool compact = (rec.flags & 2u) != 0u;
    device const uint* w = quads + ulong(entry.x + q) * (compact ? 2ul : 3ul);
    uint w0 = w[0];
    uint w1 = w[1];
    uint w2 = compact ? tints[rec.tint + rec.lookPal + (w1 >> 24)] : w[2];
    uint qx = w0 & 31u;
    uint qz = (w0 >> 5) & 31u;
    uint qy = (w0 >> 10) & 1023u;
    uint dir = (w0 >> 20) & 7u;
    uint width = ((w0 >> 23) & 31u) + 1u;
    uint sky = (w0 >> 28) & 15u;
    uint height = (w1 & 1023u) + 1u;
    uint block = (w1 >> 10) & 15u;
    uint tintIdx = (w1 >> 14) & 255u;
    uint extra = compact ? ((w1 >> 22) & 3u) : ((w1 >> 22) & 255u);
    uint look = w2 & 0xFFFFu;
    uint header = far_look_header(looks, look);
    uint shape = far_shape(header);
    float s = float(1u << rec.level);
    float3 lo = float3(float(qx), float(qy), float(qz)) * s;
    float3 hi;
    bool alongZ = dir == 4u || dir == 5u;
    float spanW = float(width) * s;
    float spanH = (dir <= 1u ? 1.0f : float(height)) * s;
    float depth = dir <= 1u ? float(height) * s : s;
    hi = lo + float3(alongZ ? s : spanW, spanH, alongZ ? spanW : depth);
    if (rec.level == 0u) {
        if (shape == 1u) {
            hi.y -= 0.5f;
        } else if (shape == 2u) {
            lo.y += 0.5f;
        } else if (shape >= 8u) {
            hi.y = hi.y - 1.0f + float(shape - 7u) * 0.125f;
        } else if (shape == 4u || shape == 5u) {
            float inset = shape == 4u ? 0.375f : 0.25f;
            lo.x += inset;
            lo.z += inset;
            hi.x -= inset;
            hi.z -= inset;
        }
    }
    float3 p;
    if (dir == 6u) {
        bool diagB = (extra & 1u) != 0u;
        bool back = (extra & 2u) != 0u;
        uint c = back ? (3u - corner) : corner;
        float2 a = diagB ? float2(lo.x + 1.0f, lo.z) : float2(lo.x, lo.z);
        float2 b = diagB ? float2(lo.x, lo.z + 1.0f) : float2(lo.x + 1.0f, lo.z + 1.0f);
        float2 xz = (c == 0u || c == 1u) ? a : b;
        float y = (c == 1u || c == 2u) ? lo.y + float(height) : lo.y;
        p = float3(xz.x, y, xz.y);
    } else {
        uint fv = dir;
        float xs[2] = {lo.x, hi.x};
        float ys[2] = {lo.y, hi.y};
        float zs[2] = {lo.z, hi.z};
        if (dir == 0u) { ys[1] = lo.y; }
        if (dir == 1u) { ys[0] = hi.y; }
        if (dir == 2u) { zs[1] = lo.z; }
        if (dir == 3u) { zs[0] = hi.z; }
        if (dir == 4u) { xs[1] = lo.x; }
        if (dir == 5u) { xs[0] = hi.x; }
        p = float3(xs[kTerrFaceVerts[fv][corner * 3u]], ys[kTerrFaceVerts[fv][corner * 3u + 1u]],
                   zs[kTerrFaceVerts[fv][corner * 3u + 2u]]);
    }
    int3 origin = int3(rec.ox, rec.minY, rec.oz);
    float3 rel = float3(origin - dp.camBlock.xyz) - dp.camFrac.xyz + p;
    float4 clip = projectionMatrix * (modelViewMatrix * float4(rel, 1.0f));
    if (dp.reverseZ != 0u) {
        clip.z *= (1.0f - dp.depthPush);
    } else {
        clip.z += dp.depthPush * clip.w;
    }
    out.position = clip;
    out.local = p;
    uint tc = tints[rec.tint + tintIdx];
    out.tint = half4(half(tc & 255u) / 255.0h, half((tc >> 8) & 255u) / 255.0h, half((tc >> 16) & 255u) / 255.0h,
                     1.0h);
    out.lightUV = kLightmapLut[(block & 15u) | ((sky & 15u) << 4)];
    out.origin = origin;
    out.face = dir;
    out.look = look;
    out.level = rec.level;
    return out;
}

inline long far_block_seed(int x, int y, int z) {
    long l = long(int(uint(x) * 3129871u)) ^ (long(z) * 116129781l) ^ long(y);
    l = l * l * 42317861l + l * 11l;
    return l >> 16;
}

inline uint far_pick_variant(int3 cell, uint weights, uint count) {
    if (count <= 1u) {
        return 0u;
    }
    uint total = (weights >> (8u * (count - 1u))) & 255u;
    if (total == 0u) {
        return 0u;
    }
    ulong mask48 = (1ul << 48) - 1ul;
    ulong seed = (ulong(far_block_seed(cell.x, cell.y, cell.z)) ^ 0x5DEECE66Dul) & mask48;
    int pick;
    if ((total & (total - 1u)) == 0u) {
        seed = (seed * 0x5DEECE66Dul + 0xBul) & mask48;
        int bits = int(uint(seed >> 17));
        pick = int((long(total) * long(bits)) >> 31);
    } else {
        int bits;
        int val;
        int guard = 0;
        do {
            seed = (seed * 0x5DEECE66Dul + 0xBul) & mask48;
            bits = int(uint(seed >> 17));
            val = bits % int(total);
            guard++;
        } while (bits - val + int(total - 1u) < 0 && guard < 8);
        pick = val;
    }
    for (uint i = 0u; i < count; i++) {
        if (uint(pick) < ((weights >> (8u * i)) & 255u)) {
            return i;
        }
    }
    return count - 1u;
}

inline float2 far_apply_code(float2 st, uint code) {
    float2 uv = (code & 1u) != 0u ? st.yx : st;
    if ((code & 2u) != 0u) {
        uv.x = 1.0f - uv.x;
    }
    if ((code & 4u) != 0u) {
        uv.y = 1.0f - uv.y;
    }
    return uv;
}

inline float2 far_apply_code_grad(float2 g, uint code) {
    float2 uv = (code & 1u) != 0u ? g.yx : g;
    if ((code & 2u) != 0u) {
        uv.x = -uv.x;
    }
    if ((code & 4u) != 0u) {
        uv.y = -uv.y;
    }
    return uv;
}

inline float4 far_sprite_rect(device const uint2* sprites, uint sprite) {
    uint2 sr = sprites[sprite];
    float u0 = float(sr.x & 0xFFFFu);
    float v0 = float(sr.x >> 16);
    float u1 = float(sr.y & 0xFFFFu);
    float v1 = float(sr.y >> 16);
    return float4(u0, v0, u1 - u0, v1 - v0) / 65535.0f;
}

kernel void far_sprite_avg(
    device const uint2* sprites [[buffer(0)]],
    device half4* outAvg [[buffer(1)]],
    constant uint& count [[buffer(2)]],
    texture2d<half> atlas [[texture(0)]],
    uint g [[threadgroup_position_in_grid]],
    uint t [[thread_index_in_threadgroup]]
) {
    threadgroup float4 acc[64];
    if (g >= count) {
        return;
    }
    float4 rect = far_sprite_rect(sprites, g);
    float aw = float(atlas.get_width());
    float ah = float(atlas.get_height());
    int x0 = int(rect.x * aw + 0.5f);
    int y0 = int(rect.y * ah + 0.5f);
    int w = clamp(int(rect.z * aw + 0.5f), 1, 256);
    int h = clamp(int(rect.w * ah + 0.5f), 1, 256);
    float4 sum = float4(0.0f);
    int n = w * h;
    for (int i = int(t); i < n; i += 64) {
        uint2 at = uint2(uint(clamp(x0 + i % w, 0, int(aw) - 1)), uint(clamp(y0 + i / w, 0, int(ah) - 1)));
        half4 c = atlas.read(at, 0);
        sum += float4(float3(c.rgb) * float(c.a), float(c.a));
    }
    acc[t] = sum;
    threadgroup_barrier(mem_flags::mem_threadgroup);
    for (uint stride = 32u; stride > 0u; stride >>= 1) {
        if (t < stride) {
            acc[t] += acc[t + stride];
        }
        threadgroup_barrier(mem_flags::mem_threadgroup);
    }
    if (t == 0u) {
        float4 a = acc[0];
        outAvg[g] = a.w > 0.0f ? half4(half3(a.rgb / a.w), half(a.w / float(n))) : half4(1.0h, 1.0h, 1.0h, 0.0h);
    }
}

inline half4 far_sample_sprite(texture2d<half> atlas, sampler smp, device const uint2* sprites, uint sprite,
                               uint code, float2 st, float2 gx, float2 gy) {
    float4 rect = far_sprite_rect(sprites, sprite);
    float2 uvf = far_apply_code(st, code);
    float2 ux = far_apply_code_grad(gx, code) * rect.zw;
    float2 uy = far_apply_code_grad(gy, code) * rect.zw;
    return atlas.sample(smp, rect.xy + uvf * rect.zw, gradient2d(ux, uy));
}

struct FarSurface {
    half4 tex;
    half4 color;
};

inline FarSurface far_surface(FarVOut in, texture2d<half> atlas, sampler smp, device const uint2* sprites,
                              device const uint* looks, device const uchar* mask, bool masked, uint flat,
                              device const half4* avg) {
    float3 p = in.local;
    uint face = in.face;
    float3 cellF;
    float2 stRaw;
    switch (face) {
        case 0u: cellF = float3(floor(p.x), floor(p.y + 0.5f), floor(p.z)); stRaw = p.xz; break;
        case 1u: cellF = float3(floor(p.x), floor(p.y - 0.5f), floor(p.z)); stRaw = p.xz; break;
        case 2u: cellF = float3(floor(p.x), floor(p.y), floor(p.z + 0.5f)); stRaw = float2(p.x, p.y); break;
        case 3u: cellF = float3(floor(p.x), floor(p.y), floor(p.z - 0.5f)); stRaw = float2(p.x, p.y); break;
        case 4u: cellF = float3(floor(p.x + 0.5f), floor(p.y), floor(p.z)); stRaw = float2(p.z, p.y); break;
        case 5u: cellF = float3(floor(p.x - 0.5f), floor(p.y), floor(p.z)); stRaw = float2(p.z, p.y); break;
        default: cellF = floor(p - float3(0.0f, 0.0f, 0.0f)); stRaw = float2(p.x, p.y); break;
    }
    int3 cell = int3(cellF) + in.origin;
    if (masked) {
        int cx = cell.x >> 4;
        int cz = cell.z >> 4;
        if (mask[uint(cx & 127) + uint(cz & 127) * 128u] != 0u) {
            discard_fragment();
        }
    }
    float2 st = clamp(fract(stRaw), float2(0.0005f), float2(0.9995f));
    float2 gx = dfdx(stRaw);
    float2 gy = dfdy(stRaw);
    device const uint* lk = looks + in.look * kFarLookWords;
    uint header = lk[0];
    uint weights = lk[1];
    uint nvar = (header >> 6) & 7u;
    uint fslot = face <= 5u ? face : 1u;
    float foot = max(max(abs(gx.x), abs(gx.y)), max(abs(gy.x), abs(gy.y)));
    uint v = (nvar <= 1u || foot > 1.0f) ? 0u : far_pick_variant(cell, weights, nvar);
    uint entry = lk[2u + fslot * 4u + v];
    uint sprite = entry & 0x1FFFu;
    uint code = (entry >> 13) & 7u;
    uint ov = (entry >> 16) & 0x1FFFu;
    uint ovCode = (entry >> 29) & 7u;
    bool baseTint = ((header >> (10u + fslot)) & 1u) != 0u;
    bool ovTint = ((header >> (16u + fslot)) & 1u) != 0u;
    FarSurface out;
    half4 base = half4(1.0h);
    bool useAvg = flat != 0u && foot > 1.0f;
    if (sprite != kFarNoSprite) {
        base = useAvg ? avg[sprite] : far_sample_sprite(atlas, smp, sprites, sprite, code, st, gx, gy);
    }
    half3 rgb = base.rgb * (baseTint ? in.tint.rgb : half3(1.0h));
    if (ov != kFarNoSprite) {
        half4 o = useAvg ? avg[ov] : far_sample_sprite(atlas, smp, sprites, ov, ovCode, st, gx, gy);
        half3 orgb = o.rgb * (ovTint ? in.tint.rgb : half3(1.0h));
        rgb = mix(rgb, orgb, o.a);
    }
    out.tex = half4(rgb, base.a);
    half shade = half(kFarShade[min(face, 6u)]);
    bool leaf = ((header >> 22) & 1u) != 0u;
    half alpha = leaf ? half(254.0h / 255.0h) : half(1.0h);
    if (far_cls(header) == 2u) {
        alpha = half(0.996h);
    }
    out.color = half4(shade, shade, shade, alpha);
    return out;
}

constant bool kFarMasked [[function_constant(0)]];

fragment half4 far_fs_solid(
    FarVOut in [[stage_in]],
    texture2d<half> blockAtlas [[texture(0)]],
    texture2d<half> lightmap [[texture(1)]],
    constant FogUniforms& fog [[buffer(6)]],
    constant TerrainFogRecon& recon [[buffer(7)]],
    device const uint2* sprites [[buffer(8)]],
    device const uint* looks [[buffer(9)]],
    device const uchar* mask [[buffer(10)]],
    constant uint& flat [[buffer(11)]],
    device const half4* avg [[buffer(12)]]
) {
    FarSurface s = far_surface(in, blockAtlas, kTerrAtlasSampler, sprites, looks, mask, kFarMasked, flat, avg);
    return terrain_shade_solid(s.tex, s.color, in.lightUV, terrain_fog_dist(in.position, recon), lightmap, fog);
}

fragment half4 far_fs_cutout(
    FarVOut in [[stage_in]],
    texture2d<half> blockAtlas [[texture(0)]],
    texture2d<half> lightmap [[texture(1)]],
    constant FogUniforms& fog [[buffer(6)]],
    constant TerrainFogRecon& recon [[buffer(7)]],
    device const uint2* sprites [[buffer(8)]],
    device const uint* looks [[buffer(9)]],
    device const uchar* mask [[buffer(10)]],
    constant uint& flat [[buffer(11)]],
    device const half4* avg [[buffer(12)]]
) {
    FarSurface s = far_surface(in, blockAtlas, kTerrAtlasSampler, sprites, looks, mask, kFarMasked, flat, avg);
    return terrain_shade_cutout(s.tex, s.color, in.lightUV, terrain_fog_dist(in.position, recon), lightmap, fog);
}

fragment half4 far_fs_water(
    FarVOut in [[stage_in]],
    texture2d<half> blockAtlas [[texture(0)]],
    texture2d<half> lightmap [[texture(1)]],
    constant FogUniforms& fog [[buffer(6)]],
    constant TerrainFogRecon& recon [[buffer(7)]],
    device const uint2* sprites [[buffer(8)]],
    device const uint* looks [[buffer(9)]],
    device const uchar* mask [[buffer(10)]],
    constant uint& flat [[buffer(11)]],
    device const half4* avg [[buffer(12)]]
) {
    FarSurface s = far_surface(in, blockAtlas, kTerrAtlasSamplerAniso, sprites, looks, mask, kFarMasked, flat, avg);
    return terrain_shade_water(s.tex, s.color, in.lightUV, terrain_fog_dist(in.position, recon), lightmap, fog);
}
