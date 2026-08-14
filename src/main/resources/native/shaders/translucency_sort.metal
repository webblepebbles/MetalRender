#include <metal_stdlib>
using namespace metal;

struct TranslucencySortParams {
    uint count;
    uint j;
    uint k;
    uint padding;
};

kernel void translucency_bitonic_step(
    device float* keys [[buffer(0)]],
    device uint* indices [[buffer(1)]],
    constant TranslucencySortParams& params [[buffer(2)]],
    uint gid [[thread_position_in_grid]]) {
    if (gid >= params.count) {
        return;
    }
    uint partner = gid ^ params.j;
    if (partner <= gid || partner >= params.count) {
        return;
    }
    bool ascending = (gid & params.k) == 0;
    float left = keys[gid];
    float right = keys[partner];
    bool swapValues = ascending ? left > right : left < right;
    if (swapValues) {
        keys[gid] = right;
        keys[partner] = left;
        uint index = indices[gid];
        indices[gid] = indices[partner];
        indices[partner] = index;
    }
}
