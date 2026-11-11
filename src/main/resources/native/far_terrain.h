#ifndef MR_FAR_TERRAIN_H
#define MR_FAR_TERRAIN_H

#import <Metal/Metal.h>
#include <cstdint>

id<MTLDevice> mr_host_device();
id<MTLCommandQueue> mr_host_queue();
id<MTLLibrary> mr_host_library();
id<MTLRenderPipelineState> mr_host_make_pipeline(MTLRenderPipelineDescriptor *desc, NSError **err);
id<MTLRenderPipelineState> mr_host_ext_pso(id<MTLRenderPipelineState> pso);
MTLWinding mr_host_ext_winding();
id<MTLDepthStencilState> mr_host_depth_state(bool write);
id<MTLBuffer> mr_host_sprites();
id<MTLTexture> mr_host_atlas();
id<MTLBuffer> mr_host_quad_index();
id<MTLTexture> mr_host_hiz(bool valid);
void mr_host_fill_cull_frame(void *out, size_t size);
void mr_host_fill_fog_recon(void *out, size_t size);
const void *mr_host_fog_uniforms(size_t *size);
const float *mr_host_proj();
const float *mr_host_mv();
void mr_host_cam(double *out);
bool mr_host_reverse_z();
uint64_t mr_host_frame_in_progress();
uint64_t mr_host_frame_completed();
void mr_host_count_draw();

bool mr_far_active();
void mr_far_prepare();
void mr_far_encode(id<MTLRenderCommandEncoder> enc);
void mr_far_encode_water(id<MTLRenderCommandEncoder> enc);

#endif
