#include <metal_stdlib>
using namespace metal;
struct HiZParams {
    uint2  srcSize;
    uint2  dstSize;
    uint   mipLevel;
    uint   _pad0;
    uint   _pad1;
    uint   _pad2;
};
kernel void hiz_downsample(
    texture2d<float, access::read>  srcDepth [[texture(0)]],
    texture2d<float, access::write> dstDepth [[texture(1)]],
    constant HiZParams&             params   [[buffer(0)]],
    uint2 gid [[thread_position_in_grid]]
) {
    if (gid.x >= params.dstSize.x || gid.y >= params.dstSize.y) return;
    uint2 srcBase = gid * 2;
    uint srcLevel = params.mipLevel;
    uint dstLevel = params._pad0;
    float d00 = srcDepth.read(min(srcBase + uint2(0, 0), params.srcSize - 1), srcLevel).r;
    float d10 = srcDepth.read(min(srcBase + uint2(1, 0), params.srcSize - 1), srcLevel).r;
    float d01 = srcDepth.read(min(srcBase + uint2(0, 1), params.srcSize - 1), srcLevel).r;
    float d11 = srcDepth.read(min(srcBase + uint2(1, 1), params.srcSize - 1), srcLevel).r;
    float maxDepth = max(max(d00, d10), max(d01, d11));
    dstDepth.write(float4(maxDepth), gid, dstLevel);
}
kernel void hiz_downsample_multi(
    texture2d<float, access::read>  mip0    [[texture(0)]],
    texture2d<float, access::write> mip1    [[texture(1)]],
    texture2d<float, access::write> mip2    [[texture(2)]],
    texture2d<float, access::write> mip3    [[texture(3)]],
    texture2d<float, access::write> mip4    [[texture(4)]],
    constant HiZParams&             params  [[buffer(0)]],
    uint2 gid  [[thread_position_in_grid]],
    uint2 lid  [[thread_position_in_threadgroup]],
    uint2 tgid [[threadgroup_position_in_grid]]
) {
    threadgroup float sharedDepth[16][16];
    uint2 srcCoord = tgid * 16 + lid;
    float d = 0.0;
    if (all(srcCoord < params.srcSize)) {
        uint2 base = srcCoord * 2;
        float d00 = mip0.read(min(base + uint2(0,0), params.srcSize * 2 - 1)).r;
        float d10 = mip0.read(min(base + uint2(1,0), params.srcSize * 2 - 1)).r;
        float d01 = mip0.read(min(base + uint2(0,1), params.srcSize * 2 - 1)).r;
        float d11 = mip0.read(min(base + uint2(1,1), params.srcSize * 2 - 1)).r;
        d = max(max(d00, d10), max(d01, d11));
        mip1.write(float4(d), srcCoord);
    }
    sharedDepth[lid.y][lid.x] = d;
    threadgroup_barrier(mem_flags::mem_threadgroup);
    if (lid.x < 8 && lid.y < 8) {
        float a = sharedDepth[lid.y * 2    ][lid.x * 2    ];
        float b = sharedDepth[lid.y * 2    ][lid.x * 2 + 1];
        float c = sharedDepth[lid.y * 2 + 1][lid.x * 2    ];
        float e = sharedDepth[lid.y * 2 + 1][lid.x * 2 + 1];
        d = max(max(a, b), max(c, e));
        uint2 dst = tgid * 8 + lid;
        mip2.write(float4(d), dst);
        sharedDepth[lid.y][lid.x] = d;
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);
    if (lid.x < 4 && lid.y < 4) {
        float a = sharedDepth[lid.y * 2    ][lid.x * 2    ];
        float b = sharedDepth[lid.y * 2    ][lid.x * 2 + 1];
        float c = sharedDepth[lid.y * 2 + 1][lid.x * 2    ];
        float e = sharedDepth[lid.y * 2 + 1][lid.x * 2 + 1];
        d = max(max(a, b), max(c, e));
        uint2 dst = tgid * 4 + lid;
        mip3.write(float4(d), dst);
        sharedDepth[lid.y][lid.x] = d;
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);
    if (lid.x < 2 && lid.y < 2) {
        float a = sharedDepth[lid.y * 2    ][lid.x * 2    ];
        float b = sharedDepth[lid.y * 2    ][lid.x * 2 + 1];
        float c = sharedDepth[lid.y * 2 + 1][lid.x * 2    ];
        float e = sharedDepth[lid.y * 2 + 1][lid.x * 2 + 1];
        d = max(max(a, b), max(c, e));
        uint2 dst = tgid * 2 + lid;
        mip4.write(float4(d), dst);
    }
}
