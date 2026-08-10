#include <metal_stdlib>
using namespace metal;

struct MotionVectorParams {
    float4x4 currentViewProjection;
    float4x4 previousViewProjection;
    uint2 size;
    uint2 _padding;
};

kernel void generate_motion_vectors(
    texture2d<float, access::read> depthTexture [[texture(0)]],
    texture2d<float, access::write> motionTexture [[texture(1)]],
    constant MotionVectorParams& params [[buffer(0)]],
    uint2 gid [[thread_position_in_grid]]) {
    if (gid.x >= params.size.x || gid.y >= params.size.y) {
        return;
    }

    float depth = depthTexture.read(gid).r;
    float2 currentUv = (float2(gid) + 0.5f) / float2(params.size);
    float2 clipMotion = float2(
        params.previousViewProjection[3][0] - params.currentViewProjection[3][0],
        params.previousViewProjection[3][1] - params.currentViewProjection[3][1]);
    float depthWeight = clamp(1.0f - depth, 0.0f, 1.0f);
    float2 motion = clipMotion * float2(params.size) * (0.5f + depthWeight * 0.5f);
    if (!all(isfinite(motion))) {
        motion = float2(0.0f);
    }
    motionTexture.write(float4(motion, 0.0f, 0.0f), gid);
}
