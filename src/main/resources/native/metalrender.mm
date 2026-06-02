#import <Foundation/NSProcessInfo.h>
#import <IOSurface/IOSurface.h>
#import <Metal/Metal.h>
#import <MetalKit/MetalKit.h>
#ifdef METALRENDER_HAS_METALFX
#import <MetalFX/MetalFX.h>
#endif
#import <OpenGL/CGLIOSurface.h>
#import <OpenGL/OpenGL.h>
#import <OpenGL/gl.h>
#include <algorithm>
#include <atomic>
#include <dispatch/dispatch.h>
#include <dlfcn.h>
#include <jni.h>
#include <mach/mach.h>
#include <mach/mach_host.h>
#include <mach/mach_time.h>
#include <mutex>
#include <shared_mutex>
#include <thread>
#include <unordered_map>
#include <utility>
#include <vector>
#ifdef __aarch64__
#include <arm_neon.h>
#endif
static FILE *g_debugFile = nullptr;
static void dbg(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
static void dbg(const char *fmt, ...) {
  if (!g_debugFile) {
    g_debugFile = fopen("/tmp/metalrender_debug.log", "w");
    if (!g_debugFile)
      return;
  }
  va_list args;
  va_start(args, fmt);
  vfprintf(g_debugFile, fmt, args);
  va_end(args);
  static int s_dbgFlushCounter = 0;
  if (++s_dbgFlushCounter >= 120) {
    fflush(g_debugFile);
    s_dbgFlushCounter = 0;
  }
}
static mach_timebase_info_data_t g_cachedTimebase = {0, 0};
static void ensureTimebase() {
  if (g_cachedTimebase.denom == 0)
    mach_timebase_info(&g_cachedTimebase);
}
static id<MTLBuffer> get_buffer(uint64_t h);
struct ResolvedBuf {
  id<MTLBuffer> buf;
  size_t offset;
};
static ResolvedBuf resolve_buffer(uint64_t h);
#ifndef dispatch_get_active_cpu_count
static inline int dispatch_get_active_cpu_count() {
  unsigned hc = std::thread::hardware_concurrency();
  return (int)(hc == 0 ? 1 : hc);
}
#endif
static bool g_available = true;
id<MTLDevice> g_device = nil;
static id<MTLCommandQueue> g_queue = nil;
static std::unordered_map<uint64_t, id<MTLBuffer>> g_buffers;
static uint64_t g_nextHandle = 1;
static id<MTLBuffer> g_megaVB = nil;

static const size_t MEGA_VB_CAPACITY = 3072ULL * 1024 * 1024;
static size_t g_megaVBHead = 0;
struct MegaSubAlloc {
  size_t offset;
  size_t size;
};
static std::unordered_map<uint64_t, MegaSubAlloc> g_megaAllocs;

static std::vector<MegaSubAlloc> g_megaFreeList;
static uint64_t g_nextMegaHandle = 0x8000000000000000ULL;
static std::shared_mutex g_megaMutex;

static void megaCoalesceFreeList() {
  if (g_megaFreeList.size() < 2)
    return;

  std::sort(g_megaFreeList.begin(), g_megaFreeList.end(),
            [](const MegaSubAlloc &a, const MegaSubAlloc &b) {
              return a.offset < b.offset;
            });

  size_t write = 0;
  for (size_t read = 1; read < g_megaFreeList.size(); read++) {
    if (g_megaFreeList[write].offset + g_megaFreeList[write].size ==
        g_megaFreeList[read].offset) {
      g_megaFreeList[write].size += g_megaFreeList[read].size;
    } else {
      write++;
      if (write != read)
        g_megaFreeList[write] = g_megaFreeList[read];
    }
  }
  g_megaFreeList.resize(write + 1);
}
struct DeferredDeletion {
  uint64_t handle;
  int frameQueued;
  bool isMega;
};
static std::vector<DeferredDeletion> g_deferredDeletions;
static std::mutex g_deferredMutex;
static const int DEFERRED_FRAME_DELAY = 10;
static std::atomic<bool> g_gpuNeedsRecovery{false};
static inline bool isMegaHandle(uint64_t h) {
  return (h & 0x8000000000000000ULL) != 0;
}
static uint64_t megaAlloc(size_t size) {
  std::unique_lock<std::shared_mutex> lock(g_megaMutex);
  size_t aligned = (size + 255) & ~255;

  int bestIdx = -1;
  size_t bestSize = SIZE_MAX;
  for (int i = 0; i < (int)g_megaFreeList.size(); i++) {
    if (g_megaFreeList[i].size >= aligned &&
        g_megaFreeList[i].size < bestSize) {
      bestIdx = i;
      bestSize = g_megaFreeList[i].size;
      if (bestSize == aligned)
        break;
    }
  }
  if (bestIdx >= 0) {
    size_t offset = g_megaFreeList[bestIdx].offset;
    size_t blockSize = g_megaFreeList[bestIdx].size;

    g_megaFreeList[bestIdx] = g_megaFreeList.back();
    g_megaFreeList.pop_back();
    uint64_t handle = g_nextMegaHandle++;
    g_megaAllocs[handle] = {offset, aligned};
    if (blockSize > aligned) {
      g_megaFreeList.push_back({offset + aligned, blockSize - aligned});
    }
    return handle;
  }
  if (g_megaVBHead + aligned > MEGA_VB_CAPACITY) {

    megaCoalesceFreeList();

    bestIdx = -1;
    bestSize = SIZE_MAX;
    for (int i = 0; i < (int)g_megaFreeList.size(); i++) {
      if (g_megaFreeList[i].size >= aligned &&
          g_megaFreeList[i].size < bestSize) {
        bestIdx = i;
        bestSize = g_megaFreeList[i].size;
        if (bestSize == aligned)
          break;
      }
    }
    if (bestIdx >= 0) {
      size_t offset2 = g_megaFreeList[bestIdx].offset;
      size_t blockSize2 = g_megaFreeList[bestIdx].size;
      g_megaFreeList[bestIdx] = g_megaFreeList.back();
      g_megaFreeList.pop_back();
      uint64_t handle2 = g_nextMegaHandle++;
      g_megaAllocs[handle2] = {offset2, aligned};
      if (blockSize2 > aligned) {
        g_megaFreeList.push_back({offset2 + aligned, blockSize2 - aligned});
      }
      return handle2;
    }
    static int megaFailCount = 0;
    if (megaFailCount++ < 10 || megaFailCount % 500 == 0)
      dbg("megaAlloc FAIL: need %zu, head=%zu, cap=%zu, freeBlocks=%zu (fail "
          "#%d)\n",
          aligned, g_megaVBHead, MEGA_VB_CAPACITY, g_megaFreeList.size(),
          megaFailCount);
    return 0;
  }
  size_t offset = g_megaVBHead;
  g_megaVBHead += aligned;
  uint64_t handle = g_nextMegaHandle++;
  g_megaAllocs[handle] = {offset, aligned};
  return handle;
}
static void megaFree(uint64_t handle) {
  std::unique_lock<std::shared_mutex> lock(g_megaMutex);
  auto it = g_megaAllocs.find(handle);
  if (it == g_megaAllocs.end())
    return;
  g_megaFreeList.push_back(it->second);
  g_megaAllocs.erase(it);

  if (g_megaFreeList.size() > 64) {
    megaCoalesceFreeList();
  }
}
static size_t megaGetOffset(uint64_t handle) {
  std::shared_lock<std::shared_mutex> lock(g_megaMutex);
  auto it = g_megaAllocs.find(handle);
  return (it != g_megaAllocs.end()) ? it->second.offset : 0;
}
static void *megaGetPointer(uint64_t handle) {
  std::shared_lock<std::shared_mutex> lock(g_megaMutex);
  auto it = g_megaAllocs.find(handle);
  if (it == g_megaAllocs.end() || !g_megaVB)
    return nullptr;
  return (char *)[g_megaVB contents] + it->second.offset;
}
static id<MTLRenderPipelineState> g_pipelineOpaque = nil;
static id<MTLRenderPipelineState> g_pipelineInhouse = nil;
static id<MTLRenderPipelineState> g_pipelineWater = nil;
static id<MTLRenderPipelineState> g_pipelineCutout = nil;
static id<MTLRenderPipelineState> g_pipelineTranslucent = nil;
static id<MTLRenderPipelineState> g_pipelineEntity = nil;
static id<MTLRenderPipelineState> g_pipelineEntityInstanced = nil;
static id<MTLRenderPipelineState> g_pipelineEntityTranslucent = nil;
static id<MTLRenderPipelineState> g_pipelineEntityEmissive = nil;
static id<MTLRenderPipelineState> g_pipelineEntityOutline = nil;
static id<MTLRenderPipelineState> g_pipelineEntityShadow = nil;
static id<MTLRenderPipelineState> g_pipelineParticle = nil;
static id<MTLRenderPipelineState> g_pipelineDebugLines = nil;
id<MTLDepthStencilState> g_depthState = nil;
static id<MTLDepthStencilState> g_depthStateNoWrite = nil;
static id<MTLDepthStencilState> g_depthStateLessEqual = nil;

static id<MTLDepthStencilState> g_depthStateEqualNoWrite = nil;

static id<MTLTexture> g_tbColor[3] = {};
static id<MTLTexture> g_tbDepth[3] = {};
#ifdef METALRENDER_HAS_METALFX

static id<MTLTexture> g_lrColor[3] = {};
static id<MTLTexture> g_lrDepth[3] = {};
static id<MTLFXSpatialScaler> g_mfxScaler = nil;
#endif
static IOSurfaceRef g_tbIOSurface[3] = {};
static std::atomic<bool> g_tbSlotReady[3] = {{true}, {true}, {true}};
static std::atomic<int> g_tbLastCompleted{-1};
static int g_renderSlot = 0;
static bool g_wasReuseFrame = false;
static id<MTLCommandBuffer> g_tbCmdBuf[3] = {};

static id<MTLTexture> g_color = nil;
static id<MTLTexture> g_depth = nil;
static IOSurfaceRef g_ioSurface = NULL;
static id<MTLBuffer> g_depthReadBuffer = nil;
static id<MTLCommandBuffer> g_depthCmdBuffer = nil;
static id<MTLTexture> g_blockAtlas = nil;
static id<MTLTexture> g_lightmap = nil;
static id<MTLLibrary> g_shaderLibrary = nil;
static int g_rtWidth = 16;
static int g_rtHeight = 16;
static float g_scale = 1.0f;
static int g_frameCount = 0;
static std::atomic<float> g_lastGpuMs{0.0f};
uint32_t g_drawCallCount = 0;
static int g_drawSkipCount = 0;
static int g_totalDraws = 0;
static uint64_t g_prof_drawAll_acc = 0;
static uint64_t g_prof_endFrame_acc = 0;
static uint64_t g_prof_waitRender_acc = 0;
static uint64_t g_prof_cglBind_acc = 0;
static int g_prof_count = 0;
static const int kProfileEmitInterval = 240;
static id<MTLTexture> g_entityTexture = nil;
static float g_entityOverlayParams[4] = {0, 0, 0, 1};
static float g_entityTintColor[4] = {1, 1, 1, 1};
static id<MTLIndirectCommandBuffer> g_icb[3] = {};

static NSUInteger g_icbMaxCommands[3] = {};
static const NSUInteger ICB_INITIAL_SIZE = 8192;
static id<MTLRenderPipelineState> g_pipelineInhouseICB = nil;
static id<MTLRenderPipelineState> g_pipelineInhouseICBOpaque = nil;
static id<MTLRenderPipelineState> g_pipelineInhouseOpaque = nil;

id<MTLRenderPipelineState> g_pipelineMeshOpaque = nil;
id<MTLRenderPipelineState> g_pipelineMeshCutout = nil;
id<MTLRenderPipelineState> g_pipelineMeshEmissive = nil;
static id<MTLBuffer> g_fragArgBuf = nil;
static id<MTLBuffer> g_fragArgBufOpaque = nil;
static id<MTLArgumentEncoder> g_fragArgEncoder = nil;
static id<MTLArgumentEncoder> g_fragArgEncoderOpaque = nil;
static id<MTLComputePipelineState> g_hizDownsamplePipeline = nil;
static id<MTLComputePipelineState> g_hizMultiPipeline = nil;
static id<MTLComputePipelineState> g_cullEncodePipeline = nil;
static id<MTLComputePipelineState> g_resetCullPipeline = nil;
static id<MTLComputePipelineState> g_lodSelectPipeline = nil;
static id<MTLTexture> g_hizPyramid = nil;
static id<MTLTexture> g_hizFallbackTexture = nil;
static uint32_t g_hizMipCount = 0;
static int g_hizWidth = 0;
static int g_hizHeight = 0;
static id<MTLBuffer> g_hizReadbackBuf = nil;
static int g_hizReadbackW = 0;
static int g_hizReadbackH = 0;
static const uint32_t HIZ_READBACK_MIP = 3;
static float g_vpMatrix[16] = {0};
static id<MTLTexture> g_hizSrcViews[16] = {};
static id<MTLTexture> g_hizDstViews[16] = {};
static int g_hizViewsValid = 0;
id<MTLBuffer> g_cullDrawArgsBuffer = nil;
id<MTLBuffer> g_cullDrawCountBuffer = nil;
static id<MTLBuffer> g_cullStatsBuffer = nil;
id<MTLBuffer> g_subChunkBuffer = nil;
static id<MTLBuffer> g_chunkUniformsBuffer = nil;
static id<MTLBuffer> g_cameraUniformsBuffer = nil;
static id<MTLBuffer> g_visibleIndicesBuffer = nil;
static uint32_t g_maxGPUDrawCalls = 65536;
uint32_t g_gpuSubChunkCount = 0;
static bool g_gpuDrivenEnabled = false;
static id<MTLSharedEvent> g_frameEvent = nil;
static MTLSharedEventListener *g_eventListener = nil;
static uint64_t g_eventCounter = 0;
static constexpr int kTripleBufferCount = 3;
id<MTLBuffer> g_tripleBuffers[kTripleBufferCount] = {};

static id<MTLBuffer> g_meshletBuffers[kTripleBufferCount] = {};
int g_currentBufferIndex = 0;
static dispatch_semaphore_t g_frameSemaphore = nil;
static volatile bool g_shuttingDown = false;
static volatile bool g_currentFrameReady = true;
static id<MTLBuffer> g_argumentBuffer = nil;
static bool g_argumentBufferDirty = true;
static id<MTLRenderPipelineState> g_meshTerrainOpaquePSO = nil;
static id<MTLRenderPipelineState> g_meshTerrainCutoutPSO = nil;
static id<MTLRenderPipelineState> g_meshTerrainEmissivePSO = nil;
bool g_meshShadersActive = false;
uint32_t g_meshPipelineCount = 0;
static int g_thermalState = 0;
static int g_lodRadiusReduction = 0;
static double g_lastThermalCheckTime = 0;
static float g_skyBrightness = 1.0f;

struct StaleDrawCmd {
  uint64_t bufferHandle;
  int idxCount;
  int opaqueIdxCount;
  float ox, oy, oz;
  bool isMega;
};
static StaleDrawCmd *g_staleDrawCmds = nullptr;
static int g_staleDrawCount = 0;
static int g_staleMegaCount = 0;
static int g_staleCapacity = 0;
static float *g_staleOffsetData = nullptr;
static int g_staleOffsetCapacity = 0;
static bool g_hasStaleDrawList = false;
static float g_staleCamX = 0, g_staleCamY = 0, g_staleCamZ = 0;

static float g_dynamicLODScale = 1.0f;
static float g_targetFrameTimeMs = 16.67f;
static float g_avgFrameTimeMs = 0.0f;
static int g_configuredRenderDistBlocks = 512;
static bool g_useMemorylessTargets = false;
static bool g_useProgrammableBlending = false;
static bool g_useArgumentBuffers = false;
static id<MTLTexture> g_oitAccumTex = nil;
static id<MTLTexture> g_oitRevealTex = nil;
static id<MTLRenderPipelineState> g_pipelineOITAccum = nil;
static id<MTLRenderPipelineState> g_pipelineOITComposite = nil;

struct OITCachedCmd {
  __unsafe_unretained id<MTLBuffer> resolvedBuf;
  size_t megaOffset;
  bool isMega;
  int translucentIdxCount;
  int instanceIdx;
  int opaqueVertCount;
};
static OITCachedCmd *g_oitCmds = nullptr;
static int g_oitCmdsCapacity = 0;
static int g_oitCmdsCount = 0;
static __unsafe_unretained id<MTLBuffer> g_oitIB = nil;
static NSUInteger g_oitIBOffset = 0;
static __unsafe_unretained id<MTLBuffer> g_oitOffsetBuf = nil;

struct DeferredWaterCmd {
  __unsafe_unretained id<MTLBuffer> resolvedBuf;
  size_t megaOffset;
  int idxCount;
  int opaqueIdxCount;
  float distSq;
  int instanceIdx;
  bool isMega;
};
static DeferredWaterCmd *g_deferredWaterCmds = nullptr;
static int g_deferredWaterCapacity = 0;
static int g_deferredWaterCmdCount = 0;
static __unsafe_unretained id<MTLBuffer> g_deferredWaterIB = nil;
static NSUInteger g_deferredWaterIBOffset = 0;
static __unsafe_unretained id<MTLBuffer> g_deferredWaterOffsetBuf = nil;

static int g_thermalQualityLevel = 0;

static bool g_supportsASTC = false;
static id<MTLDepthStencilState> g_depthStateReversedZ = nil;
static id<MTLDepthStencilState> g_depthStateReversedZNoWrite = nil;
static id<MTLDepthStencilState> g_depthStateReversedZReadOnly = nil;
struct NativeMesh {
  int32_t chunkX, chunkY, chunkZ;
  uint64_t bufferHandle;
  int32_t quadCount;
  int32_t opaqueQuadCount;
  int32_t lodLevel;
  bool active;
};

struct CameraUniformsCPU {
  float viewProjection[16];
  float projection[16];
  float modelView[16];
  float cameraPosition[4];
  float frustumPlanes[24];
  float screenSize[2];
  float nearPlane;
  float farPlane;
  uint32_t frameIndex;
  uint32_t hizMipCount;
  uint32_t totalChunks;
  float waterFog;
};

struct ChunkMeshletNative {
  uint32_t baseVertexOffset;
  uint32_t vertexCount;
  float worldX;
  float worldY;
  float worldZ;
  uint32_t lodLevel;
  uint32_t _pad0;
  uint32_t _pad1;
};
static_assert(sizeof(ChunkMeshletNative) == 32,
              "ChunkMeshletNative must be 32 bytes");
static std::vector<NativeMesh> g_nativeMeshes;
static std::unordered_map<int64_t, size_t> g_meshKeyToIdx;
static std::vector<size_t> g_meshFreeSlots;

static std::vector<int> g_activeMeshIndices;
static std::shared_mutex g_meshRegMutex;
static int g_activeMeshCount = 0;
static int64_t packMeshKey(int cx, int cy, int cz) {
  return ((int64_t)(cx & 0x3FFFFF) << 42) | ((int64_t)(cy & 0xFFFFF) << 22) |
         (int64_t)(cz & 0x3FFFFF);
}
static inline bool frustumTestAABB(const float p[24], float x0, float y0,
                                   float z0, float x1, float y1, float z1) {
#ifdef __aarch64__

  float32x4_t vx0 = vdupq_n_f32(x0), vx1 = vdupq_n_f32(x1);
  float32x4_t vy0 = vdupq_n_f32(y0), vy1 = vdupq_n_f32(y1);
  float32x4_t vz0 = vdupq_n_f32(z0), vz1 = vdupq_n_f32(z1);
  float32x4_t zero = vdupq_n_f32(0.0f);
  for (int i = 0; i < 6; i += 2) {
    if (i != 4) {
      float32x4_t plane0 = vld1q_f32(&p[i * 4]);

      float32x4_t a0 = vdupq_laneq_f32(plane0, 0);
      float32x4_t b0 = vdupq_laneq_f32(plane0, 1);
      float32x4_t c0 = vdupq_laneq_f32(plane0, 2);
      float32x4_t d0 = vdupq_laneq_f32(plane0, 3);

      uint32x4_t maskA0 = vcgeq_f32(a0, zero);
      uint32x4_t maskB0 = vcgeq_f32(b0, zero);
      uint32x4_t maskC0 = vcgeq_f32(c0, zero);
      float32x4_t px0 = vbslq_f32(maskA0, vx1, vx0);
      float32x4_t py0 = vbslq_f32(maskB0, vy1, vy0);
      float32x4_t pz0 = vbslq_f32(maskC0, vz1, vz0);

      float32x4_t dot0 = vfmaq_f32(d0, a0, px0);
      dot0 = vfmaq_f32(dot0, b0, py0);
      dot0 = vfmaq_f32(dot0, c0, pz0);
      if (vgetq_lane_f32(dot0, 0) < 0)
        return false;
    }
    if (i + 1 < 6 && i + 1 != 4) {
      float32x4_t plane1 = vld1q_f32(&p[(i + 1) * 4]);
      float32x4_t a1 = vdupq_laneq_f32(plane1, 0);
      float32x4_t b1 = vdupq_laneq_f32(plane1, 1);
      float32x4_t c1 = vdupq_laneq_f32(plane1, 2);
      float32x4_t d1 = vdupq_laneq_f32(plane1, 3);
      uint32x4_t maskA1 = vcgeq_f32(a1, zero);
      uint32x4_t maskB1 = vcgeq_f32(b1, zero);
      uint32x4_t maskC1 = vcgeq_f32(c1, zero);
      float32x4_t px1 = vbslq_f32(maskA1, vx1, vx0);
      float32x4_t py1 = vbslq_f32(maskB1, vy1, vy0);
      float32x4_t pz1 = vbslq_f32(maskC1, vz1, vz0);
      float32x4_t dot1 = vfmaq_f32(d1, a1, px1);
      dot1 = vfmaq_f32(dot1, b1, py1);
      dot1 = vfmaq_f32(dot1, c1, pz1);
      if (vgetq_lane_f32(dot1, 0) < 0)
        return false;
    }
  }
  return true;
#else
  for (int i = 0; i < 6; i++) {
    if (i == 4)
      continue;
    float a = p[i * 4], b = p[i * 4 + 1], c = p[i * 4 + 2], d = p[i * 4 + 3];
    float px = (a >= 0) ? x1 : x0;
    float py = (b >= 0) ? y1 : y0;
    float pz = (c >= 0) ? z1 : z0;
    if (a * px + b * py + c * pz + d < 0)
      return false;
  }
  return true;
#endif
}

#ifdef __aarch64__

static inline uint32_t frustumTestAABB_x4(const float p[24], const float ox[4],
                                          const float oy[4],
                                          const float oz[4]) {

  float32x4_t x0 = vld1q_f32(ox);
  float32x4_t y0 = vld1q_f32(oy);
  float32x4_t z0 = vld1q_f32(oz);

  float32x4_t sixteen = vdupq_n_f32(16.0f);
  float32x4_t x1 = vaddq_f32(x0, sixteen);
  float32x4_t y1 = vaddq_f32(y0, sixteen);
  float32x4_t z1 = vaddq_f32(z0, sixteen);
  float32x4_t zero = vdupq_n_f32(0.0f);

  uint32x4_t visible = vdupq_n_u32(0xFFFFFFFF);
  for (int i = 0; i < 6; i++) {
    if (i == 4)
      continue;
    float a = p[i * 4], b = p[i * 4 + 1], c = p[i * 4 + 2], d = p[i * 4 + 3];

    float32x4_t va = vdupq_n_f32(a);
    float32x4_t vb = vdupq_n_f32(b);
    float32x4_t vc = vdupq_n_f32(c);
    float32x4_t vd = vdupq_n_f32(d);

    uint32x4_t maskA = vcgeq_f32(va, zero);
    uint32x4_t maskB = vcgeq_f32(vb, zero);
    uint32x4_t maskC = vcgeq_f32(vc, zero);
    float32x4_t px = vbslq_f32(maskA, x1, x0);
    float32x4_t py = vbslq_f32(maskB, y1, y0);
    float32x4_t pz = vbslq_f32(maskC, z1, z0);

    float32x4_t dot = vfmaq_f32(vd, va, px);
    dot = vfmaq_f32(dot, vb, py);
    dot = vfmaq_f32(dot, vc, pz);

    uint32x4_t notBehind = vcgeq_f32(dot, zero);
    visible = vandq_u32(visible, notBehind);

    if (vmaxvq_u32(visible) == 0)
      return 0;
  }

  uint32_t mask = 0;
  uint32_t v[4];
  vst1q_u32(v, visible);
  if (v[0])
    mask |= 1;
  if (v[1])
    mask |= 2;
  if (v[2])
    mask |= 4;
  if (v[3])
    mask |= 8;
  return mask;
}
#endif

static void extractFrustumPlanes(const float m[16], float out[24]) {

  out[0] = m[3] + m[0];
  out[1] = m[7] + m[4];
  out[2] = m[11] + m[8];
  out[3] = m[15] + m[12];

  out[4] = m[3] - m[0];
  out[5] = m[7] - m[4];
  out[6] = m[11] - m[8];
  out[7] = m[15] - m[12];

  out[8] = m[3] + m[1];
  out[9] = m[7] + m[5];
  out[10] = m[11] + m[9];
  out[11] = m[15] + m[13];

  out[12] = m[3] - m[1];
  out[13] = m[7] - m[5];
  out[14] = m[11] - m[9];
  out[15] = m[15] - m[13];

  out[16] = m[2];
  out[17] = m[6];
  out[18] = m[10];
  out[19] = m[14];

  out[20] = m[3] - m[2];
  out[21] = m[7] - m[6];
  out[22] = m[11] - m[10];
  out[23] = m[15] - m[14];
  for (int i = 0; i < 6; i++) {
    float a = out[i * 4], b = out[i * 4 + 1], c = out[i * 4 + 2];
    float len = sqrtf(a * a + b * b + c * c);
    if (len > 0) {
      float inv = 1.0f / len;
      out[i * 4] *= inv;
      out[i * 4 + 1] *= inv;
      out[i * 4 + 2] *= inv;
      out[i * 4 + 3] *= inv;
    }
  }
}
static void ensure_device() {
  if (!g_device) {
    g_device = MTLCreateSystemDefaultDevice();
    if (g_device) {
      g_queue = [g_device newCommandQueue];

      g_supportsASTC = [g_device supportsFamily:MTLGPUFamilyApple1];
      if (g_supportsASTC) {
        dbg("ASTC texture compression supported (Apple GPU)\n");
      }
      if (!g_megaVB) {
        g_megaVB = [g_device newBufferWithLength:MEGA_VB_CAPACITY
                                         options:MTLResourceStorageModeShared];
        g_megaVBHead = 0;
        if (g_megaVB) {
          dbg("Mega vertex buffer created: %zuMB\n",
              MEGA_VB_CAPACITY / (1024 * 1024));
        } else {
          dbg("WARN: Failed to create mega vertex buffer, falling back to "
              "individual buffers\n");
        }
      }
    }
  }
}

struct MetalFeatureCaps {
  bool argumentBuffers;
  bool indirectCommandBuffers;
  bool memorylessTargets;
  bool meshShaders;
};

static bool metal_supports_family(id<MTLDevice> device, MTLGPUFamily family) {
  if (!device)
    return false;
  if (@available(macOS 11.0, *)) {
    return [device supportsFamily:family];
  }
  return false;
}

static MetalFeatureCaps current_feature_caps() {
  ensure_device();
  MetalFeatureCaps caps = {};
  if (!g_device)
    return caps;

  const bool apple2 = metal_supports_family(g_device, MTLGPUFamilyApple2);
  const bool apple3 = metal_supports_family(g_device, MTLGPUFamilyApple3);
  const bool apple7 = metal_supports_family(g_device, MTLGPUFamilyApple7);
  const bool mac2 = metal_supports_family(g_device, MTLGPUFamilyMac2);

  caps.argumentBuffers = apple2 || mac2;
  caps.indirectCommandBuffers = apple3 || mac2;
  caps.memorylessTargets = apple2;
  caps.meshShaders = apple7 || mac2;

  if (!@available(macOS 13.0, *)) {
    caps.meshShaders = false;
  }

  return caps;
}

static id<MTLBinaryArchive> g_pipelineArchive = nil;
static NSString *g_archivePath = nil;

static id<MTLRenderPipelineState>
makePipeline(MTLRenderPipelineDescriptor *desc, NSError **outErr) {
  if (@available(macOS 11.0, *)) {
    if (g_pipelineArchive)
      desc.binaryArchives = @[ g_pipelineArchive ];
  }
  return [g_device newRenderPipelineStateWithDescriptor:desc error:outErr];
}

static id<MTLComputePipelineState> makeComputePipeline(id<MTLFunction> func,
                                                       NSError **outErr) {
  if (@available(macOS 11.0, *)) {
    if (g_pipelineArchive) {
      MTLComputePipelineDescriptor *cd =
          [[MTLComputePipelineDescriptor alloc] init];
      cd.computeFunction = func;
      cd.binaryArchives = @[ g_pipelineArchive ];
      return
          [g_device newComputePipelineStateWithDescriptor:cd
                                                  options:MTLPipelineOptionNone
                                               reflection:nil
                                                    error:outErr];
    }
  }
  return [g_device newComputePipelineStateWithFunction:func error:outErr];
}
static void load_shaders() {
  dbg("load_shaders() called: device=%p shaderLibrary=%p\n", g_device,
      g_shaderLibrary);
  if (!g_device || g_shaderLibrary)
    return;
  NSError *error = nil;

  if (@available(macOS 11.0, *)) {
    NSArray *caches = NSSearchPathForDirectoriesInDomains(
        NSCachesDirectory, NSUserDomainMask, YES);
    NSString *cacheDir =
        (caches.count > 0) ? caches[0] : NSTemporaryDirectory();
    g_archivePath = [cacheDir
        stringByAppendingPathComponent:@"metalrender_pipeline_cache.metallib"];
    MTLBinaryArchiveDescriptor *archDesc =
        [[MTLBinaryArchiveDescriptor alloc] init];
    if ([[NSFileManager defaultManager] fileExistsAtPath:g_archivePath]) {
      archDesc.url = [NSURL fileURLWithPath:g_archivePath];
      dbg("Loading pipeline cache from: %s\n", [g_archivePath UTF8String]);
    }
    NSError *archErr = nil;
    g_pipelineArchive = [g_device newBinaryArchiveWithDescriptor:archDesc
                                                           error:&archErr];
    if (!g_pipelineArchive && archErr) {
      dbg("WARN: Could not create MTLBinaryArchive: %s\n",
          [[archErr localizedDescription] UTF8String]);
    }
  }

  NSArray<NSString *> *searchPaths = @[
    @"src/main/resources/shaders.metallib",
    [NSString
        stringWithFormat:@"%@/shaders.metallib",
                         [[NSFileManager defaultManager] currentDirectoryPath]],
    @"shaders.metallib",
  ];
  Dl_info dlInfo;
  if (dladdr((void *)load_shaders, &dlInfo) && dlInfo.dli_fname) {
    NSString *dylibPath = [[NSString stringWithUTF8String:dlInfo.dli_fname]
        stringByDeletingLastPathComponent];
    NSString *metallibPath =
        [dylibPath stringByAppendingPathComponent:@"shaders.metallib"];
    searchPaths = [searchPaths arrayByAddingObject:metallibPath];
  }
  for (NSString *path in searchPaths) {
    if ([[NSFileManager defaultManager] fileExistsAtPath:path]) {
      NSURL *url = [NSURL fileURLWithPath:path];
      g_shaderLibrary = [g_device newLibraryWithURL:url error:&error];
      if (g_shaderLibrary) {
        dbg("Loaded metallib from: %s\n", [path UTF8String]);
        break;
      } else {
        dbg("Failed to load metallib from %s: %s\n", [path UTF8String],
            error ? [[error localizedDescription] UTF8String] : "unknown");
      }
    }
  }
  if (!g_shaderLibrary) {
    dbg("No metallib found, falling back to inline shader compilation\n");
    NSString *shaderSource = @R"(
#include <metal_stdlib>
using namespace metal;
struct InhouseTerrainVertex {
	packed_short3 position;
	packed_ushort2 texCoord;
	packed_uchar4 color;
	uchar packedLight;
	uchar normalIndex;
};
struct SimpleVertexOut {
	float4 position  [[position]];
	float2 texCoord;
	half4 color;
	half light;
	float3 worldPos;
	uint normalIdx [[flat]];
	half fogDist;
};
vertex SimpleVertexOut vertex_terrain_inhouse(
	device const InhouseTerrainVertex* vertices   [[buffer(0)]],
	constant float4x4& projectionMatrix            [[buffer(1)]],
	constant float4x4& modelViewMatrix            [[buffer(2)]],
	constant float4& cameraPosition               [[buffer(3)]],
	constant float4& chunkOffset                  [[buffer(4)]],
	uint vid [[vertex_id]]
) {
	InhouseTerrainVertex v = vertices[vid];
	SimpleVertexOut out;
	uint faceMask = as_type<uint>(chunkOffset.w);
	if (faceMask != 0 && v.color[3] == 255) {
		uint nIdx = v.normalIndex & 0x7;
		if (nIdx < 6 && ((faceMask >> nIdx) & 1) == 0) {
			out.position = float4(0.0, 0.0, -2.0, 1.0);
			out.texCoord = float2(0.0);
			out.color    = half4(0.0h);
			out.light    = 0.0h;
			return out;
		}
	}
	float3 localPos = float3(short3(v.position)) / 256.0;
	float3 worldPos = localPos + chunkOffset.xyz;
	float4 viewPos = modelViewMatrix * float4(worldPos, 1.0);
	out.position = projectionMatrix * viewPos;
	out.texCoord = float2(v.texCoord) / 65535.0;
	out.color    = half4(float4(v.color) / 255.0);
	float blockLight = float(v.packedLight & 0xF) / 15.0;
	float skyLight   = float((v.packedLight >> 4) & 0xF) / 15.0;
	out.light    = half(max(blockLight, skyLight * cameraPosition.w));
	out.worldPos = worldPos;
	out.normalIdx = v.normalIndex & 0x7;
	out.fogDist = half(length(viewPos.xyz));
	return out;
}
fragment float4 fragment_terrain(
	SimpleVertexOut in [[stage_in]],
	texture2d<float> blockAtlas [[texture(0)]],
	constant float4& overlayParams [[buffer(5)]]
) {
	constexpr sampler s(filter::nearest, address::clamp_to_edge);
	float4 texColor = blockAtlas.sample(s, in.texCoord);
	float ca = float(in.color.a);
	if (texColor.a < 0.5) {

		if (ca > 0.993 && ca < 0.999) {
			texColor.a = 1.0;
		} else {
			discard_fragment();
		}
	}
	constant float faceShade[6] = { 0.5, 1.0, 0.8, 0.8, 0.6, 0.6 };
	float shade = (in.normalIdx < 6) ? faceShade[in.normalIdx] : 1.0;
	float4 baseColor = texColor * float4(in.color);
	baseColor.rgb *= shade;
	baseColor.rgb *= max(float(in.light), 0.04f);
	float waterFog = overlayParams.z;
	if (waterFog > 0.0) {
		float fogFactor = clamp(float(in.fogDist) / 48.0, 0.0, 0.85);
		baseColor.rgb = mix(baseColor.rgb, float3(0.05, 0.12, 0.3), fogFactor * waterFog);
	}
	float outAlpha = ca > 0.98 ? 1.0 : ca;
	return float4(baseColor.rgb, outAlpha);
}
struct FragmentArgs {
	texture2d<float> blockAtlas [[id(0)]];
};
fragment float4 fragment_terrain_icb(
	SimpleVertexOut in [[stage_in]],
	constant FragmentArgs& args [[buffer(0)]],
	constant float4& overlayParams [[buffer(5)]]
) {
	constexpr sampler s(filter::nearest, address::clamp_to_edge);
	float4 texColor = args.blockAtlas.sample(s, in.texCoord);
	float ca = float(in.color.a);
	if (texColor.a < 0.5) {
		if (ca > 0.993 && ca < 0.999) {
			texColor.a = 1.0;
		} else {
			discard_fragment();
		}
	}
	constant float faceShade[6] = { 0.5, 1.0, 0.8, 0.8, 0.6, 0.6 };
	float shade = (in.normalIdx < 6) ? faceShade[in.normalIdx] : 1.0;
	float4 baseColor = texColor * float4(in.color);
	baseColor.rgb *= shade;
	baseColor.rgb *= max(float(in.light), 0.04f);
	float waterFog = overlayParams.z;
	if (waterFog > 0.0) {
		float fogFactor = clamp(float(in.fogDist) / 48.0, 0.0, 0.85);
		baseColor.rgb = mix(baseColor.rgb, float3(0.05, 0.12, 0.3), fogFactor * waterFog);
	}
	float outAlpha = ca > 0.98 ? 1.0 : ca;
	return float4(baseColor.rgb, outAlpha);
}



struct DepthOnlyOut {
	float4 position [[position]];
};
vertex DepthOnlyOut vertex_depth_only(
	device const InhouseTerrainVertex* vertices   [[buffer(0)]],
	constant float4x4& projectionMatrix            [[buffer(1)]],
	constant float4x4& modelViewMatrix            [[buffer(2)]],
	constant float4& cameraPosition               [[buffer(3)]],
	constant float4& chunkOffset                  [[buffer(4)]],
	uint vid [[vertex_id]]
) {
	InhouseTerrainVertex v = vertices[vid];
	DepthOnlyOut out;
	uint faceMask = as_type<uint>(chunkOffset.w);
	if (faceMask != 0 && v.color[3] == 255) {
		uint nIdx = v.normalIndex & 0x7;
		if (nIdx < 6 && ((faceMask >> nIdx) & 1) == 0) {
			out.position = float4(0.0, 0.0, -2.0, 1.0);
			return out;
		}
	}
	float3 localPos = float3(short3(v.position)) / 256.0;
	float3 worldPos = localPos + chunkOffset.xyz;
	float4 viewPos = modelViewMatrix * float4(worldPos, 1.0);
	out.position = projectionMatrix * viewPos;
	return out;
}

struct EntityVertex {
	packed_float3 position;
	packed_short2 texCoord;
	packed_uchar4 color;
	packed_uchar4 normal;
	packed_short2 overlay;
	packed_short2 lightUV;
};
struct EntityVertexOut {
	float4 position  [[position]];
	float2 texCoord;
	float4 color;
	float3 normal;
	float2 lightUV;
	float2 overlay;
	float3 worldPos;
	float fogDist;
};
vertex EntityVertexOut vertex_entity(
	device const EntityVertex*     vertices    [[buffer(0)]],
	constant float4x4&             projection  [[buffer(1)]],
	constant float4x4&             modelView   [[buffer(2)]],
	uint vid [[vertex_id]]
) {
	EntityVertex v = vertices[vid];
	EntityVertexOut out;
	float3 pos      = float3(v.position);
	float4 viewPos  = modelView * float4(pos, 1.0);
	out.position = projection * viewPos;
	out.texCoord = float2(v.texCoord) / 32768.0;
	out.color    = float4(v.color) / 255.0;



	float3 rawN = float3(v.normal.xyz);
	out.normal = normalize(float3(
		rawN.x > 127.0f ? rawN.x - 256.0f : rawN.x,
		rawN.y > 127.0f ? rawN.y - 256.0f : rawN.y,
		rawN.z > 127.0f ? rawN.z - 256.0f : rawN.z
	) / 127.0f);
	out.lightUV  = float2(v.lightUV) / 256.0;
	out.overlay  = float2(v.overlay.x, v.overlay.y);
	out.worldPos = pos;
	out.fogDist  = length(viewPos.xyz);
	return out;
}
fragment float4 fragment_entity(
	EntityVertexOut in [[stage_in]],
	texture2d<float> entityTex  [[texture(0)]],
	constant float4& overlayParams [[buffer(5)]]
) {
	constexpr sampler texSampler(filter::nearest, address::clamp_to_edge);
	float4 texColor = entityTex.sample(texSampler, in.texCoord);

	if (texColor.a < 0.1f) discard_fragment();
	float4 baseColor = texColor * in.color;
	float3 lightDir = normalize(float3(0.2, 1.0, 0.5));
	float nDotL     = max(dot(in.normal, lightDir), 0.0);
	baseColor.rgb *= (0.4 + 0.6 * nDotL);
	float blockLight = clamp(in.lightUV.x, 0.0, 1.0);

	float skyLight   = clamp(in.lightUV.y * overlayParams.w, 0.0, 1.0);
	baseColor.rgb *= max(max(blockLight, skyLight), 0.5);
	float hurtTime = overlayParams.x;
	if (hurtTime > 0.0) {
		baseColor.rgb = mix(baseColor.rgb, float3(1.0, 0.0, 0.0),
			clamp(hurtTime, 0.0, 0.6));
	}
	float whiteFlash = overlayParams.y;
	if (whiteFlash > 0.0) {
		baseColor.rgb = mix(baseColor.rgb, float3(1.0),
			clamp(whiteFlash, 0.0, 1.0));
	}

	float waterFog = overlayParams.z;
	if (waterFog > 0.0) {
		float dist = in.fogDist;
		float fogFactor = clamp(dist / 32.0, 0.0, 0.85);
		baseColor.rgb = mix(baseColor.rgb, float3(0.05, 0.12, 0.3), fogFactor);
		baseColor.a = mix(1.0, 0.3, fogFactor);
	}
	return float4(baseColor.rgb, baseColor.a);
}
fragment float4 fragment_entity_translucent(
	EntityVertexOut in [[stage_in]],
	texture2d<float> entityTex  [[texture(0)]],
	constant float4& overlayParams [[buffer(5)]]
) {
	constexpr sampler texSampler(filter::linear, address::clamp_to_edge);
	float4 texColor = entityTex.sample(texSampler, in.texCoord);
	if (texColor.a < 0.004f) discard_fragment();
	float4 baseColor = texColor * in.color;
	float3 lightDir = normalize(float3(0.2, 1.0, 0.5));
	float nDotL     = max(dot(in.normal, lightDir), 0.0);
	baseColor.rgb *= (0.4 + 0.6 * nDotL);
	float blockLight = clamp(in.lightUV.x, 0.0, 1.0);
	float skyLight   = clamp(in.lightUV.y * overlayParams.w, 0.0, 1.0);
	baseColor.rgb *= max(max(blockLight, skyLight), 0.5);
	float hurtTime = overlayParams.x;
	if (hurtTime > 0.0) {
		baseColor.rgb = mix(baseColor.rgb, float3(1.0, 0.0, 0.0),
			clamp(hurtTime, 0.0, 0.6));
	}

	float waterFog = overlayParams.z;
	if (waterFog > 0.0) {
		float dist = in.fogDist;
		float fogFactor = clamp(dist / 32.0, 0.0, 0.85);
		baseColor.rgb = mix(baseColor.rgb, float3(0.05, 0.12, 0.3), fogFactor);
		baseColor.a *= (1.0 - fogFactor * 0.4);
	}
	return baseColor;
}
fragment float4 fragment_entity_emissive(
	EntityVertexOut in [[stage_in]],
	texture2d<float> entityTex  [[texture(0)]]
) {
	constexpr sampler texSampler(filter::nearest, address::clamp_to_edge);
	float4 texColor = entityTex.sample(texSampler, in.texCoord);
	float4 baseColor = (texColor.a > 0.01) ? texColor * in.color : in.color;
	return baseColor;
}
fragment float4 fragment_particle(
	EntityVertexOut in [[stage_in]],
	texture2d<float> entityTex  [[texture(0)]],
	constant float4& overlayParams [[buffer(5)]]
) {

	constexpr sampler texSampler(filter::nearest, address::clamp_to_edge);
	float4 texColor = entityTex.sample(texSampler, in.texCoord);


	if (texColor.a < 0.01) discard_fragment();
	float4 baseColor = texColor * in.color;


	float blockLight = clamp(in.lightUV.x, 0.0, 1.0);
	float skyLight   = clamp(in.lightUV.y * overlayParams.w, 0.0, 1.0);
	baseColor.rgb *= max(max(blockLight, skyLight), 0.3);

	float waterFog = overlayParams.z;
	if (waterFog > 0.0) {
		float dist = in.fogDist;
		float fogFactor = clamp(dist / 24.0, 0.0, 0.85);
		baseColor.rgb = mix(baseColor.rgb, float3(0.05, 0.12, 0.3), fogFactor);
		baseColor.a *= (1.0 - fogFactor * 0.5);
	}
	return baseColor;
}
	)";
    MTLCompileOptions *opts = [[MTLCompileOptions alloc] init];
    g_shaderLibrary = [g_device newLibraryWithSource:shaderSource
                                             options:opts
                                               error:&error];
    if (!g_shaderLibrary) {
      dbg("FATAL: Shader compilation failed: %s\n",
          error ? [[error localizedDescription] UTF8String] : "unknown");
      return;
    }
    dbg("Inline shader compilation OK\n");
  }
  id<MTLFunction> vertexFn =
      [g_shaderLibrary newFunctionWithName:@"vertex_terrain_inhouse"];
  id<MTLFunction> fragmentFn =
      [g_shaderLibrary newFunctionWithName:@"fragment_terrain"];
  if (vertexFn && fragmentFn) {
    MTLRenderPipelineDescriptor *desc =
        [[MTLRenderPipelineDescriptor alloc] init];
    desc.vertexFunction = vertexFn;
    desc.fragmentFunction = fragmentFn;
    desc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
    desc.colorAttachments[0].blendingEnabled = YES;
    desc.colorAttachments[0].rgbBlendOperation = MTLBlendOperationAdd;
    desc.colorAttachments[0].alphaBlendOperation = MTLBlendOperationAdd;
    desc.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
    desc.colorAttachments[0].destinationRGBBlendFactor =
        MTLBlendFactorOneMinusSourceAlpha;
    desc.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
    desc.colorAttachments[0].destinationAlphaBlendFactor =
        MTLBlendFactorOneMinusSourceAlpha;
    desc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
    desc.label = @"InhouseTerrain";
    g_pipelineInhouse = makePipeline(desc, &error);
    if (!g_pipelineInhouse) {
      dbg("FATAL: Terrain pipeline creation failed: %s\n",
          [[error localizedDescription] UTF8String]);
    }
    id<MTLFunction> waterFragFn =
        [g_shaderLibrary newFunctionWithName:@"fragment_water_surface"];
    if (vertexFn && waterFragFn) {
      MTLRenderPipelineDescriptor *waterDesc =
          [[MTLRenderPipelineDescriptor alloc] init];
      waterDesc.vertexFunction = vertexFn;
      waterDesc.fragmentFunction = waterFragFn;
      waterDesc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
      waterDesc.colorAttachments[0].blendingEnabled = YES;
      waterDesc.colorAttachments[0].rgbBlendOperation = MTLBlendOperationAdd;
      waterDesc.colorAttachments[0].alphaBlendOperation = MTLBlendOperationAdd;
      waterDesc.colorAttachments[0].sourceRGBBlendFactor =
          MTLBlendFactorSourceAlpha;
      waterDesc.colorAttachments[0].destinationRGBBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
      waterDesc.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
      waterDesc.colorAttachments[0].destinationAlphaBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
      waterDesc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
      waterDesc.label = @"WaterSurface";
      g_pipelineWater = makePipeline(waterDesc, &error);
      if (!g_pipelineWater) {
        dbg("WARN: Water surface pipeline creation failed: %s\n",
            error ? [[error localizedDescription] UTF8String] : "unknown");
      }
    }
    g_pipelineOpaque = g_pipelineInhouse;
    id<MTLFunction> fragIcbFn =
        [g_shaderLibrary newFunctionWithName:@"fragment_terrain_icb"];
    if (vertexFn && fragIcbFn) {
      MTLRenderPipelineDescriptor *icbDesc =
          [[MTLRenderPipelineDescriptor alloc] init];
      icbDesc.vertexFunction = vertexFn;
      icbDesc.fragmentFunction = fragIcbFn;
      icbDesc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
      icbDesc.colorAttachments[0].blendingEnabled = YES;
      icbDesc.colorAttachments[0].rgbBlendOperation = MTLBlendOperationAdd;
      icbDesc.colorAttachments[0].alphaBlendOperation = MTLBlendOperationAdd;
      icbDesc.colorAttachments[0].sourceRGBBlendFactor =
          MTLBlendFactorSourceAlpha;
      icbDesc.colorAttachments[0].destinationRGBBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
      icbDesc.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
      icbDesc.colorAttachments[0].destinationAlphaBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
      icbDesc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
      icbDesc.supportIndirectCommandBuffers = YES;
      icbDesc.label = @"InhouseTerrainICB";
      g_pipelineInhouseICB = makePipeline(icbDesc, &error);
      if (!g_pipelineInhouseICB) {
        dbg("WARN: ICB terrain pipeline creation failed: %s\n",
            [[error localizedDescription] UTF8String]);
      } else {
        dbg("ICB terrain pipeline created OK\n");
        g_fragArgEncoder = [fragIcbFn newArgumentEncoderWithBufferIndex:0];
        if (g_fragArgEncoder) {
          NSUInteger argBufLen = [g_fragArgEncoder encodedLength];
          g_fragArgBuf =
              [g_device newBufferWithLength:argBufLen
                                    options:MTLResourceStorageModeShared];
          dbg("Fragment arg buffer: %zu bytes\n", (size_t)argBufLen);
        }
      }
    } else {
      dbg("WARN: ICB fragment shader not found\n");
    }
    id<MTLFunction> fragIcbOpaqueFn =
        [g_shaderLibrary newFunctionWithName:@"fragment_terrain_icb_opaque"];
    if (vertexFn && fragIcbOpaqueFn) {
      MTLRenderPipelineDescriptor *opaqueIcbDesc =
          [[MTLRenderPipelineDescriptor alloc] init];
      opaqueIcbDesc.vertexFunction = vertexFn;
      opaqueIcbDesc.fragmentFunction = fragIcbOpaqueFn;
      opaqueIcbDesc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
      opaqueIcbDesc.colorAttachments[0].blendingEnabled = NO;
      opaqueIcbDesc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
      opaqueIcbDesc.supportIndirectCommandBuffers = YES;
      opaqueIcbDesc.label = @"InhouseTerrainICBOpaque";
      g_pipelineInhouseICBOpaque = makePipeline(opaqueIcbDesc, &error);
      if (g_pipelineInhouseICBOpaque) {
        dbg("ICB opaque terrain pipeline created OK\n");
        g_fragArgEncoderOpaque =
            [fragIcbOpaqueFn newArgumentEncoderWithBufferIndex:0];
        if (g_fragArgEncoderOpaque) {
          NSUInteger argBufLen = [g_fragArgEncoderOpaque encodedLength];
          g_fragArgBufOpaque =
              [g_device newBufferWithLength:argBufLen
                                    options:MTLResourceStorageModeShared];
        }
      } else {
        dbg("WARN: ICB opaque pipeline failed: %s\n",
            error ? [[error localizedDescription] UTF8String] : "unknown");
      }
    }
    id<MTLFunction> fragOpaqueFn =
        [g_shaderLibrary newFunctionWithName:@"fragment_terrain_opaque"];
    if (vertexFn && fragOpaqueFn) {
      MTLRenderPipelineDescriptor *opaqueDesc =
          [[MTLRenderPipelineDescriptor alloc] init];
      opaqueDesc.vertexFunction = vertexFn;
      opaqueDesc.fragmentFunction = fragOpaqueFn;
      opaqueDesc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
      opaqueDesc.colorAttachments[0].blendingEnabled = NO;
      opaqueDesc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
      opaqueDesc.label = @"InhouseTerrainOpaque";
      g_pipelineInhouseOpaque = makePipeline(opaqueDesc, &error);
      if (g_pipelineInhouseOpaque) {
        dbg("Opaque terrain pipeline created OK\n");
      } else {
        dbg("WARN: Opaque pipeline failed: %s\n",
            error ? [[error localizedDescription] UTF8String] : "unknown");
      }
    }
  }

  auto createEntityPipeline = [&](NSString *vertName, NSString *fragName,
                                  NSString *label,
                                  bool blending) -> id<MTLRenderPipelineState> {
    id<MTLFunction> vf = [g_shaderLibrary newFunctionWithName:vertName];
    id<MTLFunction> ff = [g_shaderLibrary newFunctionWithName:fragName];
    if (!vf || !ff) {
      dbg("Entity pipeline '%s': missing function (vert=%p frag=%p)\n",
          [label UTF8String], vf, ff);
      return nil;
    }
    MTLRenderPipelineDescriptor *pd =
        [[MTLRenderPipelineDescriptor alloc] init];
    pd.vertexFunction = vf;
    pd.fragmentFunction = ff;
    pd.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
    pd.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
    pd.label = label;
    if (blending) {
      pd.colorAttachments[0].blendingEnabled = YES;
      pd.colorAttachments[0].rgbBlendOperation = MTLBlendOperationAdd;
      pd.colorAttachments[0].alphaBlendOperation = MTLBlendOperationAdd;
      pd.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
      pd.colorAttachments[0].destinationRGBBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
      pd.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
      pd.colorAttachments[0].destinationAlphaBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
    } else {
      pd.colorAttachments[0].blendingEnabled = NO;
    }
    id<MTLRenderPipelineState> ps = makePipeline(pd, &error);
    if (!ps) {
      dbg("Entity pipeline '%s' creation failed: %s\n", [label UTF8String],
          error ? [[error localizedDescription] UTF8String] : "unknown");
    } else {
      dbg("Entity pipeline '%s' created OK\n", [label UTF8String]);
    }
    return ps;
  };
  g_pipelineEntity = createEntityPipeline(@"vertex_entity", @"fragment_entity",
                                          @"EntityOpaque", false);
  g_pipelineEntityTranslucent =
      createEntityPipeline(@"vertex_entity", @"fragment_entity_translucent",
                           @"EntityTranslucent", true);
  g_pipelineEntityEmissive = createEntityPipeline(
      @"vertex_entity", @"fragment_entity_emissive", @"EntityEmissive", true);
  g_pipelineEntityInstanced =
      createEntityPipeline(@"vertex_entity_instanced", @"fragment_entity",
                           @"EntityInstanced", false);
  id<MTLFunction> outlineFragFn =
      [g_shaderLibrary newFunctionWithName:@"fragment_entity_outline"];
  if (outlineFragFn) {
    g_pipelineEntityOutline = createEntityPipeline(
        @"vertex_entity", @"fragment_entity_outline", @"EntityOutline", true);
  }
  g_pipelineEntityShadow = createEntityPipeline(
      @"vertex_entity", @"fragment_entity_shadow", @"EntityShadow", true);
  g_pipelineParticle = createEntityPipeline(
      @"vertex_entity", @"fragment_particle", @"Particle", true);
  {
    id<MTLFunction> dbgVert =
        [g_shaderLibrary newFunctionWithName:@"vertex_debug"];
    id<MTLFunction> dbgFrag =
        [g_shaderLibrary newFunctionWithName:@"fragment_debug"];
    if (dbgVert && dbgFrag) {
      MTLRenderPipelineDescriptor *dbgDesc =
          [[MTLRenderPipelineDescriptor alloc] init];
      dbgDesc.vertexFunction = dbgVert;
      dbgDesc.fragmentFunction = dbgFrag;
      dbgDesc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
      dbgDesc.colorAttachments[0].blendingEnabled = YES;
      dbgDesc.colorAttachments[0].rgbBlendOperation = MTLBlendOperationAdd;
      dbgDesc.colorAttachments[0].alphaBlendOperation = MTLBlendOperationAdd;
      dbgDesc.colorAttachments[0].sourceRGBBlendFactor =
          MTLBlendFactorSourceAlpha;
      dbgDesc.colorAttachments[0].destinationRGBBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
      dbgDesc.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
      dbgDesc.colorAttachments[0].destinationAlphaBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
      dbgDesc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
      dbgDesc.label = @"DebugLines";
      NSError *dbgErr = nil;
      g_pipelineDebugLines = makePipeline(dbgDesc, &dbgErr);
      if (!g_pipelineDebugLines) {
        dbg("Debug line pipeline creation failed: %s\n",
            dbgErr ? [[dbgErr localizedDescription] UTF8String] : "unknown");
      } else {
        dbg("Debug line pipeline created OK\n");
      }
    }
  }
  MTLDepthStencilDescriptor *dsDesc = [[MTLDepthStencilDescriptor alloc] init];
  dsDesc.depthCompareFunction = MTLCompareFunctionLess;
  dsDesc.depthWriteEnabled = YES;
  g_depthState = [g_device newDepthStencilStateWithDescriptor:dsDesc];
  g_depthStateReversedZ = g_depthState;
  MTLDepthStencilDescriptor *dsNoWrite =
      [[MTLDepthStencilDescriptor alloc] init];
  dsNoWrite.depthCompareFunction = MTLCompareFunctionLessEqual;
  dsNoWrite.depthWriteEnabled = NO;
  g_depthStateNoWrite = [g_device newDepthStencilStateWithDescriptor:dsNoWrite];
  g_depthStateReversedZNoWrite = g_depthStateNoWrite;
  MTLDepthStencilDescriptor *dsLessEq =
      [[MTLDepthStencilDescriptor alloc] init];
  dsLessEq.depthCompareFunction = MTLCompareFunctionLessEqual;
  dsLessEq.depthWriteEnabled = NO;
  g_depthStateLessEqual =
      [g_device newDepthStencilStateWithDescriptor:dsLessEq];
  g_depthStateReversedZReadOnly = g_depthStateLessEqual;

  MTLDepthStencilDescriptor *dsEqNoWrite =
      [[MTLDepthStencilDescriptor alloc] init];
  dsEqNoWrite.depthCompareFunction = MTLCompareFunctionEqual;
  dsEqNoWrite.depthWriteEnabled = NO;
  g_depthStateEqualNoWrite =
      [g_device newDepthStencilStateWithDescriptor:dsEqNoWrite];
  auto createComputePipeline =
      [&](NSString *funcName) -> id<MTLComputePipelineState> {
    id<MTLFunction> func = [g_shaderLibrary newFunctionWithName:funcName];
    if (!func) {
      dbg("Compute function '%s' not found in library\n",
          [funcName UTF8String]);
      return nil;
    }
    NSError *cErr = nil;
    id<MTLComputePipelineState> cps = makeComputePipeline(func, &cErr);
    if (!cps) {
      dbg("Compute pipeline '%s' creation failed: %s\n", [funcName UTF8String],
          cErr ? [[cErr localizedDescription] UTF8String] : "unknown");
    } else {
      dbg("Compute pipeline '%s' created OK\n", [funcName UTF8String]);
    }
    return cps;
  };
  g_hizDownsamplePipeline = createComputePipeline(@"hiz_downsample");
  g_hizMultiPipeline = createComputePipeline(@"hiz_downsample_multi");
  g_cullEncodePipeline = createComputePipeline(@"cull_and_encode");
  g_resetCullPipeline = createComputePipeline(@"reset_cull_stats");
  g_lodSelectPipeline = createComputePipeline(@"lod_select");

  {
    NSError *oitErr = nil;
    id<MTLFunction> vtxTerrain =
        [g_shaderLibrary newFunctionWithName:@"vertex_terrain_inhouse"];
    id<MTLFunction> fragAccum =
        [g_shaderLibrary newFunctionWithName:@"fragment_oit_terrain_accum"];
    if (vtxTerrain && fragAccum) {
      MTLRenderPipelineDescriptor *ad =
          [[MTLRenderPipelineDescriptor alloc] init];
      ad.vertexFunction = vtxTerrain;
      ad.fragmentFunction = fragAccum;
      ad.label = @"OITAccum";

      ad.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA16Float;
      ad.colorAttachments[0].blendingEnabled = YES;
      ad.colorAttachments[0].rgbBlendOperation = MTLBlendOperationAdd;
      ad.colorAttachments[0].alphaBlendOperation = MTLBlendOperationAdd;
      ad.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorOne;
      ad.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorOne;
      ad.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
      ad.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorOne;

      ad.colorAttachments[1].pixelFormat = MTLPixelFormatR8Unorm;
      ad.colorAttachments[1].blendingEnabled = YES;
      ad.colorAttachments[1].rgbBlendOperation = MTLBlendOperationAdd;
      ad.colorAttachments[1].sourceRGBBlendFactor = MTLBlendFactorZero;
      ad.colorAttachments[1].destinationRGBBlendFactor =
          MTLBlendFactorOneMinusSourceColor;
      ad.colorAttachments[1].sourceAlphaBlendFactor = MTLBlendFactorZero;
      ad.colorAttachments[1].destinationAlphaBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
      ad.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
      g_pipelineOITAccum = makePipeline(ad, &oitErr);
      if (g_pipelineOITAccum)
        dbg("OIT accum pipeline created OK\n");
      else
        dbg("WARN: OIT accum pipeline failed: %s\n",
            oitErr ? [[oitErr localizedDescription] UTF8String] : "unknown");
    } else {
      dbg("WARN: OIT accum shaders not found (vertex=%s fragment=%s)\n",
          vtxTerrain ? "ok" : "missing", fragAccum ? "ok" : "missing");
    }
    id<MTLFunction> vtxComp =
        [g_shaderLibrary newFunctionWithName:@"vertex_oit_composite"];
    id<MTLFunction> fragComp =
        [g_shaderLibrary newFunctionWithName:@"fragment_oit_composite_tbdr"];
    if (vtxComp && fragComp) {
      MTLRenderPipelineDescriptor *cd =
          [[MTLRenderPipelineDescriptor alloc] init];
      cd.vertexFunction = vtxComp;
      cd.fragmentFunction = fragComp;
      cd.label = @"OITComposite";
      cd.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
      cd.colorAttachments[0].blendingEnabled = NO;
      oitErr = nil;
      g_pipelineOITComposite = makePipeline(cd, &oitErr);
      if (g_pipelineOITComposite)
        dbg("OIT composite pipeline created OK\n");
      else
        dbg("WARN: OIT composite pipeline failed: %s\n",
            oitErr ? [[oitErr localizedDescription] UTF8String] : "unknown");
    } else {
      dbg("WARN: OIT composite shaders not found (vertex=%s fragment=%s)\n",
          vtxComp ? "ok" : "missing", fragComp ? "ok" : "missing");
    }
  }
  if (!g_frameSemaphore) {

    g_frameSemaphore = dispatch_semaphore_create(kTripleBufferCount - 1);
    dbg("Triple buffering: dispatch_semaphore created (count=%d)\n",
        kTripleBufferCount - 1);
  }
  if (!g_frameEvent) {
    g_frameEvent = [g_device newSharedEvent];
    g_eventListener = [[MTLSharedEventListener alloc]
        initWithDispatchQueue:dispatch_get_global_queue(
                                  QOS_CLASS_USER_INTERACTIVE, 0)];
    g_eventCounter = 0;
    dbg("Triple buffering: MTLSharedEvent created\n");
  }
  for (int i = 0; i < kTripleBufferCount; i++) {
    if (!g_tripleBuffers[i]) {
      g_tripleBuffers[i] =
          [g_device newBufferWithLength:512
                                options:MTLResourceStorageModeShared];
    }
  }
  if (!g_cullDrawCountBuffer) {
    g_cullDrawCountBuffer =
        [g_device newBufferWithLength:sizeof(uint32_t)
                              options:MTLResourceStorageModeShared];
  }
  if (!g_cullStatsBuffer) {
    g_cullStatsBuffer =
        [g_device newBufferWithLength:sizeof(uint32_t) * 8
                              options:MTLResourceStorageModeShared];
  }
  if (!g_cullDrawArgsBuffer) {
    size_t argsSize = g_maxGPUDrawCalls * sizeof(uint32_t);
    g_cullDrawArgsBuffer =
        [g_device newBufferWithLength:argsSize
                              options:MTLResourceStorageModeShared];
  }
  if (!g_visibleIndicesBuffer) {
    g_visibleIndicesBuffer =
        [g_device newBufferWithLength:g_maxGPUDrawCalls * sizeof(uint32_t)
                              options:MTLResourceStorageModeShared];
  }
  if (!g_blockAtlas) {
    MTLTextureDescriptor *fallbackDesc = [MTLTextureDescriptor
        texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm
                                     width:1
                                    height:1
                                 mipmapped:NO];
    fallbackDesc.usage = MTLTextureUsageShaderRead;
    fallbackDesc.storageMode = MTLStorageModeShared;
    g_blockAtlas = [g_device newTextureWithDescriptor:fallbackDesc];
    uint8_t white[4] = {255, 255, 255, 255};
    [g_blockAtlas replaceRegion:MTLRegionMake2D(0, 0, 1, 1)
                    mipmapLevel:0
                      withBytes:white
                    bytesPerRow:4];
    dbg("Created 1x1 white fallback atlas texture\n");
  }

  if (@available(macOS 11.0, *)) {
    if (g_pipelineArchive && g_archivePath) {
      NSError *serErr = nil;
      BOOL ok = [g_pipelineArchive
          serializeToURL:[NSURL fileURLWithPath:g_archivePath]
                   error:&serErr];
      if (!ok)
        dbg("WARN: Could not serialize pipeline archive: %s\n",
            serErr ? [[serErr localizedDescription] UTF8String] : "unknown");
      else
        dbg("Pipeline archive saved to: %s\n", [g_archivePath UTF8String]);
    }
  }
  dbg("Shaders loaded: terrain inhouse=%p opaque=%p entity=%p "
      "entityTranslucent=%p entityEmissive=%p depth=%p\n",
      g_pipelineInhouse, g_pipelineOpaque, g_pipelineEntity,
      g_pipelineEntityTranslucent, g_pipelineEntityEmissive, g_depthState);
}
static void ensure_offscreen() {
  if (!g_device)
    return;
  int w = std::max(1, (int)(g_rtWidth * g_scale));
  int h = std::max(1, (int)(g_rtHeight * g_scale));

  bool recreate = (!g_tbColor[0]) || ((int)g_tbColor[0].width != w) ||
                  ((int)g_tbColor[0].height != h);
  if (!recreate)
    return;

  for (int s = 0; s < 3; s++) {
    if (g_tbColor[s]) {
      [g_tbColor[s] release];
      g_tbColor[s] = nil;
    }
    if (g_tbDepth[s]) {
      [g_tbDepth[s] release];
      g_tbDepth[s] = nil;
    }
    if (g_tbIOSurface[s]) {
      CFRelease(g_tbIOSurface[s]);
      g_tbIOSurface[s] = NULL;
    }
    g_tbSlotReady[s].store(true, std::memory_order_release);
  }
  g_tbLastCompleted.store(-1, std::memory_order_release);
  g_color = nil;
  g_depth = nil;
  g_ioSurface = NULL;
  if (g_hizPyramid) {
    [g_hizPyramid release];
    g_hizPyramid = nil;
  }
  for (int m = 0; m < 16; m++) {
    if (g_hizSrcViews[m]) {
      [g_hizSrcViews[m] release];
      g_hizSrcViews[m] = nil;
    }
    if (g_hizDstViews[m]) {
      [g_hizDstViews[m] release];
      g_hizDstViews[m] = nil;
    }
  }
  g_hizViewsValid = 0;
  NSUInteger bytesPerRow = ((w * 4) + 15) & ~15;

  for (int s = 0; s < 3; s++) {
    NSDictionary *surfaceProperties = @{
      (id)kIOSurfaceWidth : @(w),
      (id)kIOSurfaceHeight : @(h),
      (id)kIOSurfaceBytesPerElement : @4,
      (id)kIOSurfaceBytesPerRow : @(bytesPerRow),
      (id)kIOSurfaceAllocSize : @(bytesPerRow * h),
      (id)kIOSurfacePixelFormat : @((uint32_t)'BGRA'),
    };
    g_tbIOSurface[s] =
        IOSurfaceCreate((__bridge CFDictionaryRef)surfaceProperties);
    MTLTextureDescriptor *cd = [MTLTextureDescriptor
        texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                     width:w
                                    height:h
                                 mipmapped:NO];
    cd.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
    cd.storageMode = MTLStorageModeShared;
    if (g_tbIOSurface[s]) {
      g_tbColor[s] = [g_device newTextureWithDescriptor:cd
                                              iosurface:g_tbIOSurface[s]
                                                  plane:0];
    }
    if (!g_tbColor[s]) {
      cd.storageMode = MTLStorageModeShared;
      g_tbColor[s] = [g_device newTextureWithDescriptor:cd];
    }
    MTLTextureDescriptor *dd = [MTLTextureDescriptor
        texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float
                                     width:w

                                    height:h
                                 mipmapped:NO];

    dd.storageMode = g_useMemorylessTargets ? MTLStorageModeMemoryless
                                            : MTLStorageModePrivate;
    dd.usage = g_useMemorylessTargets
                   ? MTLTextureUsageRenderTarget
                   : (MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead);
    g_tbDepth[s] = [g_device newTextureWithDescriptor:dd];
  }
#ifdef METALRENDER_HAS_METALFX

  if (@available(macOS 13.0, *)) {
    [g_mfxScaler release];
    g_mfxScaler = nil;
    for (int s = 0; s < 3; s++) {
      if (g_lrColor[s]) {
        [g_lrColor[s] release];
        g_lrColor[s] = nil;
      }
      if (g_lrDepth[s]) {
        [g_lrDepth[s] release];
        g_lrDepth[s] = nil;
      }
    }
    if (g_scale < 0.99f) {

      int nativeW = std::max(1, g_rtWidth);
      int nativeH = std::max(1, g_rtHeight);
      int lrw = w;
      int lrh = h;
      for (int s = 0; s < 3; s++) {
        MTLTextureDescriptor *lrcd = [MTLTextureDescriptor
            texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                         width:lrw
                                        height:lrh
                                     mipmapped:NO];
        lrcd.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
        lrcd.storageMode = MTLStorageModePrivate;
        g_lrColor[s] = [g_device newTextureWithDescriptor:lrcd];
        MTLTextureDescriptor *lrdd = [MTLTextureDescriptor
            texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float
                                         width:lrw
                                        height:lrh
                                     mipmapped:NO];
        lrdd.usage = MTLTextureUsageRenderTarget;
        lrdd.storageMode = MTLStorageModeMemoryless;
        g_lrDepth[s] = [g_device newTextureWithDescriptor:lrdd];
      }
      MTLFXSpatialScalerDescriptor *scalerDesc =
          [[MTLFXSpatialScalerDescriptor alloc] init];
      scalerDesc.inputWidth = (NSUInteger)lrw;
      scalerDesc.inputHeight = (NSUInteger)lrh;
      scalerDesc.outputWidth = (NSUInteger)nativeW;
      scalerDesc.outputHeight = (NSUInteger)nativeH;
      scalerDesc.colorTextureFormat = MTLPixelFormatBGRA8Unorm;
      scalerDesc.outputTextureFormat = MTLPixelFormatBGRA8Unorm;
      g_mfxScaler = [scalerDesc newSpatialScalerWithDevice:g_device];
      if (!g_mfxScaler)
        dbg("WARN: MTLFXSpatialScaler creation failed\n");
      else
        dbg("MetalFX SpatialScaler created: %dx%d -> %dx%d (scale=%.2f)\n", lrw,
            lrh, nativeW, nativeH, g_scale);
    } else {
      dbg("MetalFX upscaler DISABLED (scale=%.2f >= 1.0, full-res path)\n",
          g_scale);
    }
  }
#endif

  g_color = g_tbColor[0];
  g_depth = g_tbDepth[0];
  g_ioSurface = g_tbIOSurface[0];
  g_renderSlot = 0;
  dbg("Triple-buffered render targets created: %dx%d (3 sets)\n", w, h);

  g_hizWidth = w;
  g_hizHeight = h;
  int hizW = std::max(1, w / 2);
  int hizH = std::max(1, h / 2);
  g_hizMipCount = (uint32_t)floor(log2(std::max(hizW, hizH))) + 1;
  g_hizMipCount = std::min(g_hizMipCount, (uint32_t)12);
  MTLTextureDescriptor *hizDesc = [MTLTextureDescriptor
      texture2DDescriptorWithPixelFormat:MTLPixelFormatR32Float
                                   width:hizW
                                  height:hizH
                               mipmapped:YES];
  hizDesc.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
  hizDesc.storageMode = MTLStorageModePrivate;
  hizDesc.mipmapLevelCount = g_hizMipCount;
  g_hizPyramid = [g_device newTextureWithDescriptor:hizDesc];
  dbg("Created Hi-Z pyramid: %dx%d, %d mips\n", hizW, hizH, g_hizMipCount);

  if (g_oitAccumTex) {
    [g_oitAccumTex release];
    g_oitAccumTex = nil;
  }
  if (g_oitRevealTex) {
    [g_oitRevealTex release];
    g_oitRevealTex = nil;
  }
  MTLTextureDescriptor *oitAccumDesc = [MTLTextureDescriptor
      texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float
                                   width:w
                                  height:h
                               mipmapped:NO];
  oitAccumDesc.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
#if defined(__aarch64__)
  oitAccumDesc.storageMode = MTLStorageModeMemoryless;
#else
  oitAccumDesc.storageMode = MTLStorageModePrivate;
#endif
  g_oitAccumTex = [g_device newTextureWithDescriptor:oitAccumDesc];
  MTLTextureDescriptor *oitRevDesc = [MTLTextureDescriptor
      texture2DDescriptorWithPixelFormat:MTLPixelFormatR8Unorm
                                   width:w
                                  height:h
                               mipmapped:NO];
  oitRevDesc.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
#if defined(__aarch64__)
  oitRevDesc.storageMode = MTLStorageModeMemoryless;
#else
  oitRevDesc.storageMode = MTLStorageModePrivate;
#endif
  g_oitRevealTex = [g_device newTextureWithDescriptor:oitRevDesc];
  dbg("OIT render targets created: %dx%d (accum RGBA16F + revealage R8)\n", w,
      h);
  NSUInteger depthBufSize = (NSUInteger)(w * h * 4);
  if (!g_depthReadBuffer || g_depthReadBuffer.length < depthBufSize) {
    if (g_depthReadBuffer)
      [g_depthReadBuffer release];
    g_depthReadBuffer =
        [g_device newBufferWithLength:depthBufSize
                              options:MTLResourceStorageModeShared];
  }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nIsAvailable(
    JNIEnv *, jclass) {
  ensure_device();
  return (g_available && g_device) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nInit(
    JNIEnv *, jclass, jint width, jint height, jfloat scale) {
  ensure_device();
  g_rtWidth = (int)width;
  g_rtHeight = (int)height;
  g_scale = scale;
  g_shuttingDown = false;
  ensure_offscreen();
  load_shaders();
  return (g_device != nil) ? (jlong)0x1 : (jlong)0;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nResize(
    JNIEnv *, jclass, jlong handle, jint width, jint height, jfloat scale) {
  (void)handle;
  g_rtWidth = (int)width;
  g_rtHeight = (int)height;
  g_scale = scale;
  ensure_offscreen();
}
static bool g_reuseTerrainFrame = false;
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetReuseTerrainFrame(
    JNIEnv *, jclass, jboolean reuse) {
  g_reuseTerrainFrame = (bool)reuse;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nBeginFrame(
    JNIEnv *, jclass, jlong handle, jfloatArray proj, jfloatArray view,
    jfloat fogStart, jfloat fogEnd) {
  (void)handle;
  (void)proj;
  (void)view;
  (void)fogStart;
  (void)fogEnd;
  ensure_device();
  ensure_offscreen();
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawTerrain(
    JNIEnv *, jclass, jlong handle, jint layerId) {}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawOverlay(
    JNIEnv *, jclass, jlong handle, jint layerId) {}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nOnWorldLoaded(
    JNIEnv *, jclass, jlong handle) {}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nOnWorldUnloaded(
    JNIEnv *, jclass, jlong handle) {
  g_shuttingDown = true;
  if (g_frameSemaphore) {
    dispatch_semaphore_signal(g_frameSemaphore);
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDestroy(
    JNIEnv *, jclass, jlong handle) {
  g_shuttingDown = true;
  if (g_frameSemaphore) {
    dispatch_semaphore_signal(g_frameSemaphore);
  }
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetDeviceName(
    JNIEnv *env, jclass) {
  ensure_device();
  if (!g_device)
    return env->NewStringUTF("unknown");
  NSString *name = [g_device name];
  return env->NewStringUTF([name UTF8String]);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSupportsIndirect(
    JNIEnv *, jclass) {
  MetalFeatureCaps caps = current_feature_caps();
  return caps.indirectCommandBuffers ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSupportsMeshShaders(
    JNIEnv *, jclass) {
  MetalFeatureCaps caps = current_feature_caps();
  return caps.meshShaders ? JNI_TRUE : JNI_FALSE;
}
static std::shared_mutex g_bufferMutex;
static uint64_t store_buffer(id<MTLBuffer> buf) {
  if (!buf)
    return 0;
  std::unique_lock<std::shared_mutex> lock(g_bufferMutex);
  uint64_t h = g_nextHandle++;
  g_buffers[h] = buf;
  return h;
}
static id<MTLBuffer> get_buffer(uint64_t h) {
  std::shared_lock<std::shared_mutex> lock(g_bufferMutex);
  auto it = g_buffers.find(h);
  if (it == g_buffers.end())
    return nil;
  return it->second;
}
static ResolvedBuf resolve_buffer(uint64_t h) {
  if (isMegaHandle(h)) {
    std::shared_lock<std::shared_mutex> lock(g_megaMutex);
    auto it = g_megaAllocs.find(h);
    if (it != g_megaAllocs.end() && g_megaVB)
      return {g_megaVB, it->second.offset};
    return {nil, 0};
  }
  std::shared_lock<std::shared_mutex> lock(g_bufferMutex);
  auto it = g_buffers.find(h);
  if (it != g_buffers.end())
    return {it->second, 0};
  return {nil, 0};
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MetalBackend_initNative(
    JNIEnv *, jclass, jlong windowHandle, jboolean someFlag) {
  (void)windowHandle;
  (void)someFlag;
  ensure_device();
  return (g_device != nil) ? (jlong)0xBEEF : (jlong)0;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MetalBackend_uploadStaticMesh(
    JNIEnv *env, jclass, jlong handle, jobject vertexData, jint vertexCount,
    jint stride) {
  (void)handle;
  (void)vertexCount;
  (void)stride;
  if (!vertexData)
    return;
  void *ptr = env->GetDirectBufferAddress(vertexData);
  jlong cap = env->GetDirectBufferCapacity(vertexData);
  (void)ptr;
  (void)cap;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MetalBackend_resize(
    JNIEnv *, jclass, jlong handle, jint width, jint height) {
  (void)handle;
  (void)width;
  (void)height;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MetalBackend_setCamera(
    JNIEnv *env, jclass, jlong handle, jfloatArray viewProj4x4) {
  (void)handle;
  if (!viewProj4x4)
    return;
  jfloat tmp[16];
  if (env->GetArrayLength(viewProj4x4) >= 16) {
    env->GetFloatArrayRegion(viewProj4x4, 0, 16, tmp);
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MetalBackend_render(
    JNIEnv *, jclass, jlong handle, jfloat timeSeconds) {
  (void)handle;
  (void)timeSeconds;
  ensure_device();
  ensure_offscreen();
  if (!g_device || !g_queue || !g_color || !g_depth)
    return;
  @autoreleasepool {
    id<MTLCommandBuffer> cb = [g_queue commandBuffer];
    MTLRenderPassDescriptor *rp =
        [MTLRenderPassDescriptor renderPassDescriptor];
    rp.colorAttachments[0].texture = g_color;
    rp.colorAttachments[0].loadAction = MTLLoadActionClear;
    rp.colorAttachments[0].storeAction = MTLStoreActionStore;
    rp.colorAttachments[0].clearColor = MTLClearColorMake(0.0, 0.0, 0.0, 0.0);
    rp.depthAttachment.texture = g_depth;
    rp.depthAttachment.loadAction = MTLLoadActionClear;
    rp.depthAttachment.storeAction = MTLStoreActionStore;
    rp.depthAttachment.clearDepth = 1.0;
    id<MTLRenderCommandEncoder> enc =
        [cb renderCommandEncoderWithDescriptor:rp];
    [enc endEncoding];
    [cb commit];
  }
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MetalBackend_createVertexBuffer(
    JNIEnv *env, jclass, jlong handle, jobject data, jint size) {
  (void)handle;
  ensure_device();
  if (!g_device || !data || size <= 0)
    return 0;
  void *ptr = env->GetDirectBufferAddress(data);
  jlong cap = env->GetDirectBufferCapacity(data);
  if (!ptr || cap < size)
    return 0;
  id<MTLBuffer> buf =
      [g_device newBufferWithLength:(size_t)size
                            options:MTLResourceStorageModeShared];
  memcpy([buf contents], ptr, (size_t)size);
  uint64_t h = store_buffer(buf);
  return (jlong)h;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MetalBackend_createIndexBuffer(
    JNIEnv *env, jclass, jlong handle, jobject data, jint size) {
  (void)handle;
  ensure_device();
  if (!g_device || !data || size <= 0)
    return 0;
  void *ptr = env->GetDirectBufferAddress(data);
  jlong cap = env->GetDirectBufferCapacity(data);
  if (!ptr || cap < size)
    return 0;
  id<MTLBuffer> buf =
      [g_device newBufferWithLength:(size_t)size
                            options:MTLResourceStorageModeShared];
  memcpy([buf contents], ptr, (size_t)size);
  uint64_t h = store_buffer(buf);
  return (jlong)h;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MetalBackend_destroyBuffer(
    JNIEnv *, jclass, jlong handle, jlong bufferHandle) {
  (void)handle;
  uint64_t h = (uint64_t)bufferHandle;
  std::lock_guard<std::mutex> lock(g_deferredMutex);
  g_deferredDeletions.push_back({h, g_frameCount, isMegaHandle(h)});
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nCreateBuffer(
    JNIEnv *, jclass, jlong deviceHandle, jint sizeBytes, jint storageMode) {
  (void)deviceHandle;
  (void)storageMode;
  ensure_device();
  if (!g_device || sizeBytes <= 0)
    return 0;
  if (g_megaVB && sizeBytes <= 16 * 1024 * 1024) {
    uint64_t megaH = megaAlloc((size_t)sizeBytes);
    if (megaH != 0) {
      return (jlong)megaH;
    }
  }
  id<MTLBuffer> buf =
      [g_device newBufferWithLength:(size_t)sizeBytes
                            options:MTLResourceStorageModeShared];
  return (jlong)store_buffer(buf);
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nUploadBufferData(
    JNIEnv *env, jclass, jlong bufferHandle, jbyteArray data, jint offset,
    jint length) {
  uint64_t h = (uint64_t)bufferHandle;
  if (isMegaHandle(h)) {
    void *dst = megaGetPointer(h);
    if (!dst || !data || length <= 0)
      return;
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes) {
      memcpy((uint8_t *)dst + offset, bytes, (size_t)length);
      env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    }
    return;
  }
  id<MTLBuffer> buf = get_buffer(h);
  if (!buf || !data)
    return;
  jbyte *bytes = env->GetByteArrayElements(data, nullptr);
  if (bytes && length > 0 && (size_t)(offset + length) <= [buf length]) {
    memcpy((uint8_t *)[buf contents] + offset, bytes, (size_t)length);
  }
  env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nUploadBufferDataDirect(
    JNIEnv *env, jclass, jlong bufferHandle, jobject directBuffer, jint offset,
    jint length) {
  uint64_t h = (uint64_t)bufferHandle;
  if (isMegaHandle(h)) {
    void *dst = megaGetPointer(h);
    if (!dst || !directBuffer || length <= 0)
      return;
    void *ptr = env->GetDirectBufferAddress(directBuffer);
    if (ptr) {
      memcpy((uint8_t *)dst, (uint8_t *)ptr + offset, (size_t)length);
    }
    return;
  }
  id<MTLBuffer> buf = get_buffer(h);
  if (!buf || !directBuffer)
    return;
  void *ptr = env->GetDirectBufferAddress(directBuffer);
  if (ptr && length > 0 && (size_t)(offset + length) <= [buf length]) {
    memcpy((uint8_t *)[buf contents], (uint8_t *)ptr + offset, (size_t)length);
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDestroyBuffer(
    JNIEnv *, jclass, jlong bufferHandle) {
  uint64_t h = (uint64_t)bufferHandle;
  std::lock_guard<std::mutex> lock(g_deferredMutex);
  g_deferredDeletions.push_back({h, g_frameCount, isMegaHandle(h)});
}
static id<MTLRenderPipelineState> g_currentPipeline = nil;
static float g_chunkOffsetX = 0, g_chunkOffsetY = 0, g_chunkOffsetZ = 0;
static float g_projMatrix[16] = {};
static float g_mvMatrix[16] = {};
static float g_mvpMatrix[16] = {};
static double g_camX = 0, g_camY = 0, g_camZ = 0;
id<MTLRenderCommandEncoder> g_currentEncoder = nil;
static id<MTLCommandBuffer> g_currentCmdBuffer = nil;
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetPipelineState(
    JNIEnv *, jclass, jlong frameContext, jlong pipelineHandle) {
  (void)frameContext;
  if (g_currentEncoder && pipelineHandle != 0) {
    id<MTLRenderPipelineState> pipeline =
        (__bridge id<MTLRenderPipelineState>)(void *)(uintptr_t)pipelineHandle;
    [g_currentEncoder setRenderPipelineState:pipeline];
    g_currentPipeline = pipeline;
    bool isTranslucentPipeline = (pipeline == g_pipelineEntityTranslucent ||
                                  pipeline == g_pipelineParticle);
    id<MTLDepthStencilState> ds =
        isTranslucentPipeline ? g_depthStateLessEqual : g_depthState;
    if (ds)
      [g_currentEncoder setDepthStencilState:ds];
  } else if (g_currentEncoder && g_pipelineInhouse) {
    [g_currentEncoder setRenderPipelineState:g_pipelineInhouse];
    g_currentPipeline = g_pipelineInhouse;
    if (g_depthState)
      [g_currentEncoder setDepthStencilState:g_depthState];
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetChunkOffset(
    JNIEnv *, jclass, jlong frameContext, jfloat x, jfloat y, jfloat z) {
  (void)frameContext;
  g_chunkOffsetX = x;
  g_chunkOffsetY = y;
  g_chunkOffsetZ = z;
  if (g_currentEncoder) {
    float offset[4] = {x, y, z, 0.0f};
    [g_currentEncoder setVertexBytes:offset length:sizeof(offset) atIndex:4];
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawIndexedBuffer(
    JNIEnv *, jclass, jlong frameContext, jlong vertexBuffer, jlong indexBuffer,
    jint indexCount, jint baseIndex) {
  (void)frameContext;
  if (!g_currentEncoder || indexCount <= 0) {
    g_drawSkipCount++;
    return;
  }
  ResolvedBuf vbRes = resolve_buffer((uint64_t)vertexBuffer);
  ResolvedBuf ibRes = resolve_buffer((uint64_t)indexBuffer);
  if (!vbRes.buf || !ibRes.buf)
    return;
  id<MTLBuffer> vb = vbRes.buf;
  id<MTLBuffer> ib = ibRes.buf;
  g_totalDraws++;
  if (g_totalDraws <= 3) {
    const short *vtx = (const short *)((uint8_t *)[vb contents] + vbRes.offset);
    const uint32_t *idx =
        (const uint32_t *)((uint8_t *)[ib contents] + ibRes.offset);
    dbg("Draw #%d: vb=%p(%luB+%zu) ib=%p(%luB+%zu) idxCount=%d baseIdx=%d\n",
        g_totalDraws, vb, (unsigned long)[vb length], vbRes.offset, ib,
        (unsigned long)[ib length], ibRes.offset, indexCount, baseIndex);
    dbg("  First vertex (shorts): %d %d %d %d %d | bytes: %d %d %d %d | %d "
        "%d\n",
        vtx[0], vtx[1], vtx[2], vtx[3], vtx[4], ((const uint8_t *)vtx)[10],
        ((const uint8_t *)vtx)[11], ((const uint8_t *)vtx)[12],
        ((const uint8_t *)vtx)[13], ((const uint8_t *)vtx)[14],
        ((const uint8_t *)vtx)[15]);
    dbg("  First indices: %u %u %u %u %u %u\n", idx[0], idx[1], idx[2], idx[3],
        idx[4], idx[5]);
    dbg("  ChunkOffset: %.2f %.2f %.2f\n", g_chunkOffsetX, g_chunkOffsetY,
        g_chunkOffsetZ);
  }
  if (!g_currentPipeline && g_pipelineInhouse) {
    [g_currentEncoder setRenderPipelineState:g_pipelineInhouse];
    g_currentPipeline = g_pipelineInhouse;
    if (g_depthState)
      [g_currentEncoder setDepthStencilState:g_depthState];
  }
  if (!g_currentPipeline)
    return;
  [g_currentEncoder setVertexBuffer:vb
                             offset:(NSUInteger)vbRes.offset
                            atIndex:0];
  [g_currentEncoder
      drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                 indexCount:(NSUInteger)indexCount
                  indexType:MTLIndexTypeUInt32
                indexBuffer:ib
          indexBufferOffset:(NSUInteger)(ibRes.offset +
                                         (size_t)baseIndex * sizeof(uint32_t))];
  g_drawCallCount++;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawIndexedBatch(
    JNIEnv *env, jclass, jlong frameContext, jlong indexBuffer,
    jfloatArray drawData, jint drawCount) {
  @autoreleasepool {
    (void)frameContext;
    if (!g_currentEncoder || drawCount <= 0 || !drawData)
      return;
    uint64_t t0 = mach_absolute_time();
    ResolvedBuf ibRes = resolve_buffer((uint64_t)indexBuffer);
    if (!ibRes.buf)
      return;
    id<MTLBuffer> ib = ibRes.buf;
    NSUInteger ibOffset = (NSUInteger)ibRes.offset;
    if (!g_currentPipeline && g_pipelineInhouse) {
      id<MTLRenderPipelineState> waterPipeline =
          g_pipelineWater ? g_pipelineWater : g_pipelineInhouse;
      [g_currentEncoder setRenderPipelineState:waterPipeline];
      g_currentPipeline = waterPipeline;
      if (g_depthState)
        [g_currentEncoder setDepthStencilState:g_depthState];
    }
    if (!g_currentPipeline)
      return;
    int len = env->GetArrayLength(drawData);
    int stride = 6;
    if (len < drawCount * stride)
      return;
    jfloat *data = env->GetFloatArrayElements(drawData, nullptr);
    if (!data)
      return;
    uint64_t t1 = mach_absolute_time();
    static id<MTLBuffer> g_batchOffsetBuf = nil;
    static size_t g_batchOffsetCap = 0;
    size_t offsetBufSize = (size_t)drawCount * 16;
    if (!g_batchOffsetBuf || g_batchOffsetCap < offsetBufSize) {
      g_batchOffsetCap = offsetBufSize * 2;
      if (g_batchOffsetBuf)
        [g_batchOffsetBuf release];
      g_batchOffsetBuf =
          [g_device newBufferWithLength:g_batchOffsetCap
                                options:MTLResourceStorageModeShared];
    }
    float *offBuf = (float *)[g_batchOffsetBuf contents];
    struct DrawCmd {
      uint64_t bufHandle;
      size_t megaOffset;
      id<MTLBuffer> resolvedBuf;
      int idxCount;
      int opaqueIdxCount;
      float distSq;
      float ox, oy, oz;
      bool isMega;
    };
    DrawCmd stackCmds[256];
    DrawCmd *cmds = (drawCount <= 256) ? stackCmds : new DrawCmd[drawCount];
    int validCount = 0;
    int megaCount = 0;
    static const int VERTEX_STRIDE = 16;
    {
      std::shared_lock<std::shared_mutex> megaLock(g_megaMutex);
      std::shared_lock<std::shared_mutex> bufLock(g_bufferMutex);
      for (int i = 0; i < drawCount; i++) {
        int off = i * stride;
        uint32_t hi = *(uint32_t *)&data[off + 0];
        uint32_t lo = *(uint32_t *)&data[off + 1];
        uint64_t bufHandle = ((uint64_t)hi << 32) | (uint64_t)lo;
        int idxCount = *(int *)&data[off + 5];
        if (idxCount <= 0)
          continue;
        float ox = data[off + 2];
        float oy = data[off + 3];
        float oz = data[off + 4];
        float cx = ox + 8.0f;
        float cy = oy + 8.0f;
        float cz = oz + 8.0f;
        float distSq = cx * cx + cy * cy + cz * cz;
        bool mega = isMegaHandle(bufHandle);
        if (mega) {
          auto it = g_megaAllocs.find(bufHandle);
          if (it == g_megaAllocs.end())
            continue;
          cmds[validCount] = {bufHandle, it->second.offset,
                              nil,       idxCount,
                              idxCount,  distSq,
                              ox,        oy,
                              oz,        true};
          megaCount++;
        } else {
          id<MTLBuffer> rb = nil;
          auto bit = g_buffers.find(bufHandle);
          if (bit != g_buffers.end())
            rb = bit->second;
          cmds[validCount] = {bufHandle, 0,  rb, idxCount, idxCount,
                              distSq,    ox, oy, oz,       false};
        }
        validCount++;
      }
    }
    env->ReleaseFloatArrayElements(drawData, data, JNI_ABORT);

    if (validCount > 1) {
      std::sort(cmds, cmds + validCount,
                [](const DrawCmd &a, const DrawCmd &b) {
                  return a.distSq < b.distSq;
                });
    }
    for (int i = 0; i < validCount; i++) {
      int oidx = i * 4;
      offBuf[oidx + 0] = cmds[i].ox;
      offBuf[oidx + 1] = cmds[i].oy;
      offBuf[oidx + 2] = cmds[i].oz;
      float cx2 = cmds[i].ox + 8.0f;
      float cy2 = cmds[i].oy + 8.0f;
      float cz2 = cmds[i].oz + 8.0f;
      uint32_t faceMask = 0x3F;
      const float margin = 9.0f;
      if (cy2 > margin)
        faceMask &= ~(1u << 1);
      if (cy2 < -margin)
        faceMask &= ~(1u << 0);
      if (cz2 < -margin)
        faceMask &= ~(1u << 2);
      if (cz2 > margin)
        faceMask &= ~(1u << 3);
      if (cx2 < -margin)
        faceMask &= ~(1u << 4);
      if (cx2 > margin)
        faceMask &= ~(1u << 5);
      float maskAsFloat;
      memcpy(&maskAsFloat, &faceMask, sizeof(float));
      offBuf[oidx + 3] = maskAsFloat;
    }
    uint64_t t2 = mach_absolute_time();
    static uint64_t t_acc_resolve = 0, t_acc_jni = 0, t_acc_classify = 0;
    static uint64_t t_acc_icb_encode = 0, t_acc_icb_exec = 0, t_acc_total = 0;
    static int t_acc_frames = 0;
    static int t_acc_draws = 0;
    static uint64_t t_last_log_frame = 0;
    if (g_frameCount > 0 && g_frameCount != t_last_log_frame &&
        (g_frameCount % 120 == 0)) {
      ensureTimebase();
      auto toUs = [&](uint64_t t) -> double {
        return (double)(t * g_cachedTimebase.numer / g_cachedTimebase.denom) /
               1000.0;
      };
      dbg("TIMING [%d frames, %d draws]: total=%.0fus resolve=%.0fus "
          "jni=%.0fus "
          "classify=%.0fus icb_encode=%.0fus icb_exec=%.0fus\n",
          t_acc_frames, t_acc_draws, toUs(t_acc_total), toUs(t_acc_resolve),
          toUs(t_acc_jni), toUs(t_acc_classify), toUs(t_acc_icb_encode),
          toUs(t_acc_icb_exec));
      dbg("TIMING per-frame avg: total=%.1fus resolve=%.1fus jni=%.1fus "
          "classify=%.1fus icb_encode=%.1fus icb_exec=%.1fus\n",
          toUs(t_acc_total) / MAX(t_acc_frames, 1),
          toUs(t_acc_resolve) / MAX(t_acc_frames, 1),
          toUs(t_acc_jni) / MAX(t_acc_frames, 1),
          toUs(t_acc_classify) / MAX(t_acc_frames, 1),
          toUs(t_acc_icb_encode) / MAX(t_acc_frames, 1),
          toUs(t_acc_icb_exec) / MAX(t_acc_frames, 1));
      t_acc_resolve = t_acc_jni = t_acc_classify = 0;
      t_acc_icb_encode = t_acc_icb_exec = t_acc_total = 0;
      t_acc_frames = 0;
      t_acc_draws = 0;
      t_last_log_frame = g_frameCount;
    }
    if (validCount == 0) {
      if (cmds != stackCmds)
        delete[] cmds;
      return;
    }

    static int g_drawBudget = 65536;
    static const int MIN_BUDGET = 16384;
    static const int MAX_BUDGET = 65536;
    float gpuMs = g_lastGpuMs.load(std::memory_order_relaxed);
    if (gpuMs > 14.0f && g_drawBudget > MIN_BUDGET) {
      g_drawBudget = MAX(MIN_BUDGET, (int)(g_drawBudget * 0.92f));
    } else if (gpuMs < 12.0f && g_drawBudget < MAX_BUDGET) {
      g_drawBudget = MIN(MAX_BUDGET, (int)(g_drawBudget * 1.12f) + 2);
    }
    int preCapCount = validCount;
    if (validCount > g_drawBudget) {
      megaCount = 0;
      for (int i = 0; i < g_drawBudget; i++) {
        if (cmds[i].isMega)
          megaCount++;
      }
      validCount = g_drawBudget;
    }
    if (g_frameCount % 600 == 0) {
      dbg("DRAW_BUDGET: budget=%d, preCap=%d, drawn=%d, gpuMs=%.1f\n",
          g_drawBudget, preCapCount, validCount, gpuMs);
    }
    if (g_megaVB) {
      [g_currentEncoder setVertexBuffer:g_megaVB offset:0 atIndex:0];
    }
    [g_currentEncoder setVertexBuffer:g_batchOffsetBuf offset:0 atIndex:4];

    [g_currentEncoder setFragmentBytes:g_entityOverlayParams
                                length:sizeof(g_entityOverlayParams)
                               atIndex:5];
    int icbCandidateCount = 0;
    for (int i = 0; i < validCount; i++) {
      const DrawCmd &cmd = cmds[i];
      if (cmd.opaqueIdxCount <= 0)
        continue;
      if (cmd.isMega) {
        if (!g_megaVB)
          continue;
      } else if (!cmd.resolvedBuf) {
        continue;
      }
      icbCandidateCount++;
    }
    bool canICB =
        (g_gpuDrivenEnabled && g_useArgumentBuffers && icbCandidateCount > 0 &&
         g_pipelineInhouseICB && g_fragArgBuf && g_fragArgEncoder &&
         g_blockAtlas && g_lightmap);
    if (canICB) {
      [g_currentEncoder setRenderPipelineState:g_pipelineInhouseICB];
      g_currentPipeline = g_pipelineInhouseICB;
      [g_fragArgEncoder setArgumentBuffer:g_fragArgBuf offset:0];
      [g_fragArgEncoder setTexture:g_blockAtlas atIndex:0];
      [g_fragArgEncoder setTexture:g_lightmap atIndex:1];
      [g_currentEncoder setFragmentBuffer:g_fragArgBuf offset:0 atIndex:0];
      [g_currentEncoder useResource:g_blockAtlas
                              usage:MTLResourceUsageRead
                             stages:MTLRenderStageFragment];
      [g_currentEncoder useResource:g_lightmap
                              usage:MTLResourceUsageRead
                             stages:MTLRenderStageFragment];
      if (!g_icb[g_renderSlot] ||
          g_icbMaxCommands[g_renderSlot] < (NSUInteger)icbCandidateCount) {
        NSUInteger newSize =
            MAX(ICB_INITIAL_SIZE, (NSUInteger)icbCandidateCount * 2);
        MTLIndirectCommandBufferDescriptor *desc =
            [MTLIndirectCommandBufferDescriptor new];
        desc.commandTypes = MTLIndirectCommandTypeDrawIndexed;
        desc.inheritPipelineState = YES;
        desc.inheritBuffers = YES;
        desc.maxVertexBufferBindCount = 1;

        if (g_icb[g_renderSlot])
          [g_icb[g_renderSlot] release];
        g_icb[g_renderSlot] = [g_device
            newIndirectCommandBufferWithDescriptor:desc
                                   maxCommandCount:newSize
                                           options:
                                               MTLResourceStorageModeShared];
        [desc release];
        g_icbMaxCommands[g_renderSlot] = newSize;
        dbg("ICB created: slot=%d maxCommands=%lu\n", g_renderSlot,
            (unsigned long)newSize);
      }
      [g_icb[g_renderSlot]
          resetWithRange:NSMakeRange(0, (NSUInteger)icbCandidateCount)];
      int icbIdx = 0;
      for (int i = 0; i < validCount; i++) {
        const DrawCmd &cmd = cmds[i];
        if (cmd.opaqueIdxCount <= 0) {
          continue;
        }
        if (cmd.isMega) {
          if (!g_megaVB)
            continue;
        } else {
          if (!cmd.resolvedBuf)
            continue;
        }
        id<MTLIndirectRenderCommand> icmd = [g_icb[g_renderSlot]
            indirectRenderCommandAtIndex:(NSUInteger)icbIdx];
        if (!cmd.isMega) {
          [icmd setVertexBuffer:cmd.resolvedBuf offset:0 atIndex:0];
        }
        [icmd drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                         indexCount:(NSUInteger)cmd.opaqueIdxCount
                          indexType:MTLIndexTypeUInt32
                        indexBuffer:ib
                  indexBufferOffset:ibOffset
                      instanceCount:1
                         baseVertex:(NSInteger)(cmd.isMega ? (cmd.megaOffset /
                                                              VERTEX_STRIDE)
                                                           : 0)
                       baseInstance:(NSUInteger)i];
        icbIdx++;
      }
      uint64_t t3 = mach_absolute_time();
      [g_currentEncoder
          executeCommandsInBuffer:g_icb[g_renderSlot]
                        withRange:NSMakeRange(0, (NSUInteger)icbIdx)];
      g_drawCallCount++;
      uint64_t t4 = mach_absolute_time();
      t_acc_resolve += (t1 - t0);
      t_acc_jni += (t1 - t0);
      t_acc_classify += (t2 - t1);
      t_acc_icb_encode += (t3 - t2);
      t_acc_icb_exec += (t4 - t3);
      t_acc_total += (t4 - t0);
      t_acc_frames++;
      t_acc_draws += validCount;
      [g_currentEncoder setRenderPipelineState:g_pipelineInhouse];
      g_currentPipeline = g_pipelineInhouse;
      if (g_blockAtlas) {
        [g_currentEncoder setFragmentTexture:g_blockAtlas atIndex:0];
      }
      if (g_megaVB) {
        [g_currentEncoder setVertexBuffer:g_megaVB offset:0 atIndex:0];
      }
    } else {
      for (int i = 0; i < validCount; i++) {
        const DrawCmd &cmd = cmds[i];
        if (cmd.opaqueIdxCount <= 0) {
          g_drawCallCount++;
          continue;
        }
        if (cmd.isMega) {
          [g_currentEncoder
              drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                         indexCount:(NSUInteger)cmd.opaqueIdxCount
                          indexType:MTLIndexTypeUInt32
                        indexBuffer:ib
                  indexBufferOffset:ibOffset
                      instanceCount:1
                         baseVertex:(NSInteger)(cmd.megaOffset / VERTEX_STRIDE)
                       baseInstance:(NSUInteger)i];
        } else {
          if (cmd.resolvedBuf) {
            [g_currentEncoder setVertexBuffer:cmd.resolvedBuf
                                       offset:0
                                      atIndex:0];
            [g_currentEncoder
                drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                           indexCount:(NSUInteger)cmd.opaqueIdxCount
                            indexType:MTLIndexTypeUInt32
                          indexBuffer:ib
                    indexBufferOffset:ibOffset
                        instanceCount:1
                           baseVertex:0
                         baseInstance:(NSUInteger)i];
            if (g_megaVB) {
              [g_currentEncoder setVertexBuffer:g_megaVB offset:0 atIndex:0];
            }
          }
        }
        g_drawCallCount++;
      }
    }
    if (cmds != stackCmds)
      delete[] cmds;
    if (g_frameCount < 5 || (g_frameCount % 600 == 0)) {
      dbg("DrawBatch: total=%d valid=%d mega=%d nonMega=%d icb=%s "
          "megaVBUsed=%zuMB\n",
          drawCount, validCount, megaCount, validCount - megaCount,
          canICB ? "YES" : "NO", g_megaVBHead / (1024 * 1024));
    }
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nRegisterChunkMesh(
    JNIEnv *, jclass, jint cx, jint cy, jint cz, jlong bufferHandle,
    jint quadCount, jint opaqueQuadCount, jint lodLevel) {
  int64_t key = packMeshKey(cx, cy, cz);
  std::unique_lock<std::shared_mutex> lock(g_meshRegMutex);
  auto it = g_meshKeyToIdx.find(key);
  if (it != g_meshKeyToIdx.end()) {
    NativeMesh &m = g_nativeMeshes[it->second];
    m.bufferHandle = (uint64_t)bufferHandle;
    m.quadCount = quadCount;
    m.opaqueQuadCount = opaqueQuadCount;
    m.lodLevel = lodLevel;
    m.active = true;

    return;
  }
  size_t idx;
  if (!g_meshFreeSlots.empty()) {
    idx = g_meshFreeSlots.back();
    g_meshFreeSlots.pop_back();
  } else {
    idx = g_nativeMeshes.size();
    g_nativeMeshes.push_back({});
  }
  g_nativeMeshes[idx] = {(int32_t)cx,        (int32_t)cy,
                         (int32_t)cz,        (uint64_t)bufferHandle,
                         (int32_t)quadCount, (int32_t)opaqueQuadCount,
                         (int32_t)lodLevel,  true};
  g_meshKeyToIdx[key] = idx;

  g_activeMeshIndices.push_back((int)idx);
  g_activeMeshCount++;
  if (g_activeMeshCount <= 5 || g_activeMeshCount % 2000 == 0) {
    dbg("MeshReg: registered (%d,%d,%d) handle=%llu total=%d\n", cx, cy, cz,
        (unsigned long long)bufferHandle, g_activeMeshCount);
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nUnregisterChunkMesh(
    JNIEnv *, jclass, jint cx, jint cy, jint cz) {
  int64_t key = packMeshKey(cx, cy, cz);
  std::unique_lock<std::shared_mutex> lock(g_meshRegMutex);
  auto it = g_meshKeyToIdx.find(key);
  if (it == g_meshKeyToIdx.end())
    return;
  size_t idx = it->second;
  g_nativeMeshes[idx].active = false;
  g_meshFreeSlots.push_back(idx);
  g_meshKeyToIdx.erase(it);

  int intIdx = (int)idx;
  for (int k = 0; k < (int)g_activeMeshIndices.size(); k++) {
    if (g_activeMeshIndices[k] == intIdx) {
      g_activeMeshIndices[k] = g_activeMeshIndices.back();
      g_activeMeshIndices.pop_back();
      break;
    }
  }
  g_activeMeshCount--;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nBatchPackFaces(
    JNIEnv *env, jclass, jlong outAddr, jint outOffset, jobject faceDataBuf,
    jint faceCount) {
  if (faceCount <= 0 || !outAddr)
    return 0;
  uint8_t *out = (uint8_t *)(uintptr_t)outAddr + outOffset;
  const uint8_t *faceData =
      (const uint8_t *)env->GetDirectBufferAddress(faceDataBuf);
  if (!faceData)
    return 0;

  static const uint8_t FACE_VERTS[6][12] = {
      {0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0, 1},
      {0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0},
      {1, 1, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0},
      {0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1},
      {0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1},
      {1, 1, 1, 1, 0, 1, 1, 0, 0, 1, 1, 0},
  };

  static const uint8_t UV_PAT[4][2] = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};

  for (int f = 0; f < faceCount; f++) {
    const uint8_t *fd = faceData + f * 20;
    int16_t bx = *(const int16_t *)(fd + 0);
    int16_t by = *(const int16_t *)(fd + 2);
    int16_t bz = *(const int16_t *)(fd + 4);
    uint8_t nIdx = fd[6];
    int16_t uMin = *(const int16_t *)(fd + 7);
    int16_t uMax = *(const int16_t *)(fd + 9);
    int16_t vMin = *(const int16_t *)(fd + 11);
    int16_t vMax = *(const int16_t *)(fd + 13);
    uint8_t r = fd[15], g = fd[16], b = fd[17], a = fd[18];
    uint8_t light = fd[19];

    int16_t sx = bx * 256, sy = by * 256, sz = bz * 256;
    int16_t ex = (bx + 1) * 256;
    int16_t ey = (by + 1) * 256;
    int16_t ez = (bz + 1) * 256;

    const uint8_t *pat = FACE_VERTS[nIdx < 6 ? nIdx : 0];
    int16_t xs[2] = {sx, ex}, ys[2] = {sy, ey}, zs[2] = {sz, ez};
    int16_t us[2] = {uMin, uMax}, vs[2] = {vMin, vMax};

    for (int v = 0; v < 4; v++) {
      uint8_t *dst = out + (f * 4 + v) * 16;
      int16_t px = xs[pat[v * 3 + 0]];
      int16_t py = ys[pat[v * 3 + 1]];
      int16_t pz = zs[pat[v * 3 + 2]];
      int16_t uu = us[UV_PAT[v][0]];
      int16_t vv = vs[UV_PAT[v][1]];
#ifdef __aarch64__

      uint64_t w0 = (uint16_t)px | ((uint64_t)(uint16_t)py << 16) |
                    ((uint64_t)(uint16_t)pz << 32) |
                    ((uint64_t)(uint16_t)uu << 48);
      uint64_t w1 = (uint16_t)vv | ((uint64_t)r << 16) | ((uint64_t)g << 24) |
                    ((uint64_t)b << 32) | ((uint64_t)a << 40) |
                    ((uint64_t)light << 48) | ((uint64_t)nIdx << 56);
      *(uint64_t *)(dst + 0) = w0;
      *(uint64_t *)(dst + 8) = w1;
#else
      *(int16_t *)(dst + 0) = px;
      *(int16_t *)(dst + 2) = py;
      *(int16_t *)(dst + 4) = pz;
      *(int16_t *)(dst + 6) = uu;
      *(int16_t *)(dst + 8) = vv;
      dst[10] = r;
      dst[11] = g;
      dst[12] = b;
      dst[13] = a;
      dst[14] = light;
      dst[15] = nIdx;
#endif
    }
  }
  return faceCount * 4;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawAllVisibleChunks(
    JNIEnv *, jclass, jlong frameContext, jlong indexBuffer) {
  @autoreleasepool {
    (void)frameContext;
    uint64_t _prof_t0 = mach_absolute_time();
    if (!g_currentEncoder)
      return 0;
    ResolvedBuf ibRes = resolve_buffer((uint64_t)indexBuffer);
    if (!ibRes.buf)
      return 0;
    id<MTLBuffer> ib = ibRes.buf;
    NSUInteger ibOffset = (NSUInteger)ibRes.offset;
    g_deferredWaterCmdCount = 0;
    g_deferredWaterIB = nil;
    g_deferredWaterIBOffset = 0;
    g_deferredWaterOffsetBuf = nil;
    g_oitCmdsCount = 0;
    if (!g_currentPipeline && g_pipelineInhouse) {
      [g_currentEncoder setRenderPipelineState:g_pipelineInhouse];
      g_currentPipeline = g_pipelineInhouse;
      if (g_depthState)
        [g_currentEncoder setDepthStencilState:g_depthState];
    }
    if (!g_currentPipeline)
      return 0;
    float vp[16];
    for (int c = 0; c < 4; c++) {
      for (int r = 0; r < 4; r++) {
        float sum = 0;
        for (int k = 0; k < 4; k++) {
          sum += g_projMatrix[k * 4 + r] * g_mvMatrix[c * 4 + k];
        }
        vp[c * 4 + r] = sum;
      }
    }
    float frustumPlanes[24];
    extractFrustumPlanes(vp, frustumPlanes);
    float camX = g_camX, camY = g_camY, camZ = g_camZ;
    static const int VERTEX_STRIDE = 16;
    struct DrawCmd {
      uint64_t bufHandle;
      size_t megaOffset;
      id<MTLBuffer> resolvedBuf;

      int idxCount;
      int opaqueIdxCount;
      float distSq;
      float ox, oy, oz;
      bool isMega;
    };
    static DrawCmd *s_cmds = nullptr;
    static int s_cmdsCapacity = 0;

    int meshCount = g_activeMeshCount;
    if (meshCount == 0)
      return 0;
    if (s_cmdsCapacity < meshCount) {
      delete[] s_cmds;
      s_cmdsCapacity = meshCount * 2;
      s_cmds = new DrawCmd[s_cmdsCapacity];
    }
    int validCount = 0;
    int megaCount = 0;

    float baseDist = fmaxf(384.0f / g_dynamicLODScale,
                           fmaxf(256.0f, (float)g_configuredRenderDistBlocks));
    const float maxDrawDistSq = baseDist * baseDist;

    int totalActive = 0;

    struct MeshSnapshot {
      int meshIdx;
      float ox, oy, oz;
      uint64_t bufferHandle;
      int quadCount;
      int opaqueQuadCount;
    };
    static MeshSnapshot *s_snapshots = nullptr;
    static int s_snapshotsCap = 0;
    {
      std::shared_lock<std::shared_mutex> regLock(g_meshRegMutex);
      int activeTotal = (int)g_activeMeshIndices.size();
      if (s_snapshotsCap < activeTotal) {
        delete[] s_snapshots;
        s_snapshotsCap = activeTotal * 2;
        s_snapshots = new MeshSnapshot[s_snapshotsCap];
      }
      for (int k = 0; k < activeTotal; k++) {
        int i = g_activeMeshIndices[k];
        const NativeMesh &nm = g_nativeMeshes[i];

        if (__builtin_expect(nm.quadCount <= 0 || nm.bufferHandle == 0, 0))
          continue;
        float ox = nm.chunkX * 16.0f - camX;
        float oy = nm.chunkY * 16.0f - camY;
        float oz = nm.chunkZ * 16.0f - camZ;
        s_snapshots[totalActive++] = {
            i, ox, oy, oz, nm.bufferHandle, nm.quadCount, nm.opaqueQuadCount};
      }
    }

    if (totalActive == 0)
      return 0;

    if (s_cmdsCapacity < totalActive) {
      delete[] s_cmds;
      s_cmdsCapacity = totalActive * 2;
      s_cmds = new DrawCmd[s_cmdsCapacity];
    }

    int distCulled = 0;
    {
      std::shared_lock<std::shared_mutex> megaLock(g_megaMutex);
      std::shared_lock<std::shared_mutex> bufLock(g_bufferMutex);

      auto processVisible = [&](int si) {
        const MeshSnapshot &ms = s_snapshots[si];
        float cx = ms.ox + 8.0f, cy = ms.oy + 8.0f, cz = ms.oz + 8.0f;

        float distSq = cx * cx + cz * cz;
        if (__builtin_expect(distSq > maxDrawDistSq, 0)) {
          distCulled++;
          return;
        }
        int idxCount = ms.quadCount * 6;
        int opaqueIdxCount = ms.opaqueQuadCount * 6;
        bool mega = isMegaHandle(ms.bufferHandle);
        if (mega) {
          auto it = g_megaAllocs.find(ms.bufferHandle);
          if (__builtin_expect(it == g_megaAllocs.end(), 0))
            return;
          s_cmds[validCount] = {ms.bufferHandle,
                                it->second.offset,
                                nil,
                                idxCount,
                                opaqueIdxCount,
                                distSq,
                                ms.ox,
                                ms.oy,
                                ms.oz,
                                true};
          megaCount++;
        } else {
          id<MTLBuffer> rb = nil;
          auto bit = g_buffers.find(ms.bufferHandle);
          if (bit != g_buffers.end())
            rb = bit->second;
          s_cmds[validCount] = {ms.bufferHandle, 0,      rb,    idxCount,
                                opaqueIdxCount,  distSq, ms.ox, ms.oy,
                                ms.oz,           false};
        }
        validCount++;
      };

      for (int si = 0; si < totalActive; si++) {
        const MeshSnapshot &ms = s_snapshots[si];
        if (!frustumTestAABB(frustumPlanes, ms.ox, ms.oy, ms.oz, ms.ox + 16.0f,
                             ms.oy + 16.0f, ms.oz + 16.0f))
          continue;
        processVisible(si);
      }

      bool severeUnderfill =
          (validCount > 0 && totalActive >= 96 &&
           (validCount <= 8 || validCount * 24 < totalActive));
      if (severeUnderfill) {
        validCount = 0;
        megaCount = 0;
        for (int si = 0; si < totalActive; si++) {
          processVisible(si);
        }
        if (g_frameCount < 5 || (g_frameCount % 600 == 0)) {
          dbg("CULL_FAILSAFE_LOW: visible=%d input=%d fallback distance-only\n",
              validCount, totalActive);
        }
      }

      if (validCount == 0 && totalActive > 0) {
        for (int si = 0; si < totalActive; si++) {
          processVisible(si);
        }
        if (g_frameCount < 5 || (g_frameCount % 600 == 0)) {
          dbg("CULL_FAILSAFE: frustum rejected all %d chunks, fallback "
              "distance-only\n",
              totalActive);
        }
      }
    }

    if (g_frameCount < 5 || (g_frameCount % 600 == 0)) {
      dbg("CULL: input=%d visible=%d frustumCulled=%d distCulled=%d\n",
          totalActive, validCount, totalActive - validCount - distCulled,
          distCulled);
    }

    if (validCount > 0) {

      if (g_staleCapacity < validCount) {
        free(g_staleDrawCmds);
        g_staleCapacity = validCount * 2;
        g_staleDrawCmds =
            (StaleDrawCmd *)malloc(sizeof(StaleDrawCmd) * g_staleCapacity);
      }
      for (int i = 0; i < validCount; i++) {
        g_staleDrawCmds[i] = {
            s_cmds[i].bufHandle, s_cmds[i].idxCount, s_cmds[i].opaqueIdxCount,
            s_cmds[i].ox,        s_cmds[i].oy,       s_cmds[i].oz,
            s_cmds[i].isMega};
      }
      g_staleDrawCount = validCount;
      g_staleMegaCount = megaCount;
      g_staleCamX = camX;
      g_staleCamY = camY;
      g_staleCamZ = camZ;
      g_hasStaleDrawList = true;
    } else if (g_hasStaleDrawList && g_staleDrawCount > 0) {

      dbg("use stale draw list (%d cmds)\n", g_staleDrawCount);
      float dcx = camX - g_staleCamX;
      float dcy = camY - g_staleCamY;
      float dcz = camZ - g_staleCamZ;
      validCount = g_staleDrawCount;
      megaCount = g_staleMegaCount;
      if (s_cmdsCapacity < validCount) {
        delete[] s_cmds;
        s_cmdsCapacity = validCount * 2;
        s_cmds = new DrawCmd[s_cmdsCapacity];
      }
      int reusedCount = 0;
      int reusedMegaCount = 0;
      for (int i = 0; i < validCount; i++) {
        const StaleDrawCmd &sc = g_staleDrawCmds[i];
        ResolvedBuf staleRes = resolve_buffer(sc.bufferHandle);
        if (!staleRes.buf)
          continue;
        s_cmds[reusedCount++] = {sc.bufferHandle,   staleRes.offset,
                                 staleRes.buf,      sc.idxCount,
                                 sc.opaqueIdxCount, 0.0f,
                                 sc.ox - dcx,       sc.oy - dcy,
                                 sc.oz - dcz,       sc.isMega};
        if (sc.isMega)
          reusedMegaCount++;
      }
      validCount = reusedCount;
      megaCount = reusedMegaCount;
    }

    if (validCount == 0)
      return 0;

    if (validCount > 1) {
      static DrawCmd *s_radixScratch = nullptr;
      static int s_radixScratchCap = 0;
      if (s_radixScratchCap < validCount) {
        delete[] s_radixScratch;
        s_radixScratchCap = std::max(validCount * 2, 1024);
        s_radixScratch = new DrawCmd[s_radixScratchCap];
      }
      if (validCount <= 64) {

        std::sort(s_cmds, s_cmds + validCount,
                  [](const DrawCmd &a, const DrawCmd &b) {
                    return a.distSq < b.distSq;
                  });
      } else {
        DrawCmd *src = s_cmds, *dst = s_radixScratch;
        int counts[256];
        for (int pass = 0; pass < 4; pass++) {
          int shift = pass * 8;
          memset(counts, 0, sizeof(counts));
          for (int i = 0; i < validCount; i++) {
            uint32_t key;
            memcpy(&key, &src[i].distSq, sizeof(uint32_t));
            counts[(key >> shift) & 0xFF]++;
          }

          bool skip = false;
          for (int b = 0; b < 256; b++) {
            if (counts[b] == validCount) {
              skip = true;
              break;
            }
          }
          if (skip)
            continue;

          int total = 0;
          for (int b = 0; b < 256; b++) {
            int c = counts[b];
            counts[b] = total;
            total += c;
          }

          for (int i = 0; i < validCount; i++) {
            uint32_t key;
            memcpy(&key, &src[i].distSq, sizeof(uint32_t));
            dst[counts[(key >> shift) & 0xFF]++] = src[i];
          }
          std::swap(src, dst);
        }

        if (src != s_cmds) {
          memcpy(s_cmds, src, (size_t)validCount * sizeof(DrawCmd));
        }
      }
    }

    if (g_meshShadersActive && g_pipelineMeshOpaque && megaCount > 0 &&
        g_megaVB && g_blockAtlas && g_tripleBuffers[g_renderSlot] &&
        g_tripleBuffers[g_renderSlot].length >= sizeof(CameraUniformsCPU)) {

      CameraUniformsCPU *cu =
          (CameraUniformsCPU *)[g_tripleBuffers[g_renderSlot] contents];
      memcpy(cu->viewProjection, vp, sizeof(vp));
      memcpy(cu->projection, g_projMatrix, sizeof(g_projMatrix));
      memcpy(cu->modelView, g_mvMatrix, sizeof(g_mvMatrix));

      cu->cameraPosition[0] = (float)g_camX;
      cu->cameraPosition[1] = (float)g_camY;
      cu->cameraPosition[2] = (float)g_camZ;
      cu->cameraPosition[3] = g_skyBrightness;
      memcpy(cu->frustumPlanes, frustumPlanes, sizeof(frustumPlanes));
      cu->screenSize[0] = 0.0f;
      cu->screenSize[1] = 0.0f;
      cu->nearPlane = 0.05f;
      cu->farPlane = 1024.0f;
      cu->frameIndex = (uint32_t)g_frameCount;
      cu->hizMipCount = 0;
      cu->totalChunks = 0;

      cu->waterFog = g_entityOverlayParams[2];

      int meshletSlot = g_renderSlot % kTripleBufferCount;
      size_t meshletBufNeeded = (size_t)validCount * sizeof(ChunkMeshletNative);
      if (!g_meshletBuffers[meshletSlot] ||
          g_meshletBuffers[meshletSlot].length < meshletBufNeeded) {
        if (g_meshletBuffers[meshletSlot])
          [g_meshletBuffers[meshletSlot] release];
        g_meshletBuffers[meshletSlot] =
            [g_device newBufferWithLength:meshletBufNeeded * 2
                                  options:MTLResourceStorageModeShared];
      }
      id<MTLBuffer> meshletBuf = g_meshletBuffers[meshletSlot];

      int meshletCount = 0;
      static const int VERTEX_STRIDE_MESH = 16;
      if (meshletBuf) {
        ChunkMeshletNative *meshlets =
            (ChunkMeshletNative *)[meshletBuf contents];
        for (int i = 0; i < validCount; i++) {
          if (!s_cmds[i].isMega)
            continue;
          int opaqueV = (s_cmds[i].opaqueIdxCount / 6) * 4;
          if (opaqueV <= 0)
            continue;
          meshlets[meshletCount].baseVertexOffset =
              (uint32_t)(s_cmds[i].megaOffset / VERTEX_STRIDE_MESH);
          meshlets[meshletCount].vertexCount = (uint32_t)opaqueV;
          meshlets[meshletCount].worldX = s_cmds[i].ox;
          meshlets[meshletCount].worldY = s_cmds[i].oy;
          meshlets[meshletCount].worldZ = s_cmds[i].oz;
          meshlets[meshletCount].lodLevel = 0;
          meshlets[meshletCount]._pad0 = 0;
          meshlets[meshletCount]._pad1 = 0;
          meshletCount++;
        }
        cu->totalChunks = (uint32_t)meshletCount;
      }

      if (meshletCount > 0) {
        if (@available(macOS 13.0, *)) {
          [g_currentEncoder setRenderPipelineState:g_pipelineMeshOpaque];
          g_currentPipeline = g_pipelineMeshOpaque;
          if (g_depthState)
            [g_currentEncoder setDepthStencilState:g_depthState];

          id<MTLBuffer> camBuf = g_tripleBuffers[g_renderSlot];

          [g_currentEncoder setObjectBuffer:meshletBuf offset:0 atIndex:0];
          [g_currentEncoder setObjectBuffer:camBuf offset:0 atIndex:1];

          [g_currentEncoder setMeshBuffer:meshletBuf offset:0 atIndex:0];
          [g_currentEncoder setMeshBuffer:camBuf offset:0 atIndex:1];
          [g_currentEncoder setMeshBuffer:g_megaVB offset:0 atIndex:2];

          [g_currentEncoder setFragmentTexture:g_blockAtlas atIndex:0];
          [g_currentEncoder setFragmentBuffer:camBuf offset:0 atIndex:1];

          MTLSize objTGS = MTLSizeMake((NSUInteger)meshletCount, 1, 1);
          MTLSize objTPG = MTLSizeMake(1, 1, 1);
          MTLSize meshTPG = MTLSizeMake(256, 1, 1);

          [g_currentEncoder drawMeshThreadgroups:objTGS
                     threadsPerObjectThreadgroup:objTPG
                       threadsPerMeshThreadgroup:meshTPG];
          g_drawCallCount++;

          g_currentPipeline = nil;
        }
      }

      if (g_useProgrammableBlending ||
          (g_pipelineInhouse && g_depthStateNoWrite &&
           g_thermalQualityLevel < 2)) {

        static id<MTLBuffer> s_waterOffsetBuf[kTripleBufferCount] = {};
        static size_t s_waterOffsetCap = 0;
        size_t waterOfBufNeeded = (size_t)validCount * 16;
        if (s_waterOffsetCap < waterOfBufNeeded) {
          for (int wb = 0; wb < kTripleBufferCount; wb++) {
            if (s_waterOffsetBuf[wb])
              [s_waterOffsetBuf[wb] release];
            s_waterOffsetBuf[wb] = [g_device
                newBufferWithLength:waterOfBufNeeded * 2
                            options:MTLResourceStorageModeShared |
                                    MTLResourceCPUCacheModeWriteCombined];
          }
          s_waterOffsetCap = waterOfBufNeeded * 2;
        }
        id<MTLBuffer> waterBuf = s_waterOffsetBuf[g_renderSlot];
        if (waterBuf) {
          float *offBuf = (float *)[waterBuf contents];
          for (int i = 0; i < validCount; i++) {
            offBuf[i * 4 + 0] = s_cmds[i].ox;
            offBuf[i * 4 + 1] = s_cmds[i].oy;
            offBuf[i * 4 + 2] = s_cmds[i].oz;
            uint32_t fm = 0u;
            memcpy(&offBuf[i * 4 + 3], &fm, 4);
          }

          {
            id<MTLRenderPipelineState> opaqueP = g_pipelineInhouseOpaque
                                                     ? g_pipelineInhouseOpaque
                                                     : g_pipelineInhouse;
            if (opaqueP) {
              [g_currentEncoder setRenderPipelineState:opaqueP];
              g_currentPipeline = opaqueP;
              if (g_blockAtlas)
                [g_currentEncoder setFragmentTexture:g_blockAtlas atIndex:0];
              if (g_depthState)
                [g_currentEncoder setDepthStencilState:g_depthState];
              [g_currentEncoder
                  setFrontFacingWinding:MTLWindingCounterClockwise];
              [g_currentEncoder setCullMode:MTLCullModeBack];
              [g_currentEncoder setVertexBytes:g_projMatrix
                                        length:64
                                       atIndex:1];
              [g_currentEncoder setVertexBytes:g_mvMatrix length:64 atIndex:2];
              float camPosNM[4] = {0.0f, 0.0f, 0.0f, g_skyBrightness};
              [g_currentEncoder setVertexBytes:camPosNM length:16 atIndex:3];
              [g_currentEncoder setVertexBuffer:waterBuf offset:0 atIndex:4];
              [g_currentEncoder setFragmentBytes:g_entityOverlayParams
                                          length:sizeof(g_entityOverlayParams)
                                         atIndex:5];
              for (int i = 0; i < validCount; i++) {
                if (s_cmds[i].isMega)
                  continue;
                if (s_cmds[i].opaqueIdxCount <= 0)
                  continue;
                if (!s_cmds[i].resolvedBuf)
                  continue;
                [g_currentEncoder setVertexBuffer:s_cmds[i].resolvedBuf
                                           offset:0
                                          atIndex:0];
                [g_currentEncoder
                    drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                               indexCount:(NSUInteger)s_cmds[i].opaqueIdxCount
                                indexType:MTLIndexTypeUInt32
                              indexBuffer:ib
                        indexBufferOffset:ibOffset
                            instanceCount:1
                               baseVertex:0
                             baseInstance:(NSUInteger)i];
                g_drawCallCount++;
              }
              if (g_megaVB)
                [g_currentEncoder setVertexBuffer:g_megaVB offset:0 atIndex:0];
            }
          }

          if (g_useProgrammableBlending) {
            if (g_oitCmdsCapacity < validCount) {
              free(g_oitCmds);
              g_oitCmdsCapacity = validCount * 2;
              g_oitCmds = (OITCachedCmd *)malloc(sizeof(OITCachedCmd) *
                                                 g_oitCmdsCapacity);
            }
            int oitCount = 0;
            for (int i = 0; i < validCount; i++) {
              int waterIdxCount = s_cmds[i].idxCount - s_cmds[i].opaqueIdxCount;
              if (waterIdxCount <= 0)
                continue;
              g_oitCmds[oitCount++] = {
                  s_cmds[i].resolvedBuf,
                  s_cmds[i].megaOffset,
                  s_cmds[i].isMega,
                  waterIdxCount,
                  i,
                  s_cmds[i].opaqueIdxCount / 6 * 4,
              };
            }
            g_oitCmdsCount = oitCount;
            g_oitIB = ib;
            g_oitIBOffset = ibOffset;
            g_oitOffsetBuf = waterBuf;
          } else {
            if (g_deferredWaterCapacity < validCount) {
              free(g_deferredWaterCmds);
              g_deferredWaterCapacity = validCount * 2;
              g_deferredWaterCmds = (DeferredWaterCmd *)malloc(
                  sizeof(DeferredWaterCmd) * g_deferredWaterCapacity);
            }
            int deferredCount = 0;
            for (int i = 0; i < validCount; i++) {
              int waterIdxCount = s_cmds[i].idxCount - s_cmds[i].opaqueIdxCount;
              if (waterIdxCount <= 0)
                continue;
              g_deferredWaterCmds[deferredCount++] = {
                  s_cmds[i].resolvedBuf, s_cmds[i].megaOffset,
                  s_cmds[i].idxCount,    s_cmds[i].opaqueIdxCount,
                  s_cmds[i].distSq,      i,
                  s_cmds[i].isMega,
              };
            }
            g_deferredWaterCmdCount = deferredCount;
            g_deferredWaterIB = ib;
            g_deferredWaterIBOffset = ibOffset;
            g_deferredWaterOffsetBuf = waterBuf;
          }
          if (g_depthState)
            [g_currentEncoder setDepthStencilState:g_depthState];

          [g_currentEncoder setDepthBias:0.0f slopeScale:0.0f clamp:0.0f];
          [g_currentEncoder setCullMode:MTLCullModeBack];
          g_currentPipeline = nil;
        }
      }

      return (jint)meshletCount;
    }

    static id<MTLBuffer> g_v18OffsetRing[kTripleBufferCount] = {};
    static size_t g_v18OffsetRingCap = 0;
    static const size_t OFFSET_RING_MIN_CAP = 16384 * 16;
    size_t offsetBufSize = (size_t)validCount * 16;
    if (g_v18OffsetRingCap < offsetBufSize) {
      size_t newCap = std::max(OFFSET_RING_MIN_CAP, offsetBufSize * 2);
      for (int rb = 0; rb < kTripleBufferCount; rb++) {
        if (g_v18OffsetRing[rb])
          [g_v18OffsetRing[rb] release];
        g_v18OffsetRing[rb] =
            [g_device newBufferWithLength:newCap
                                  options:MTLResourceStorageModeShared |
                                          MTLResourceCPUCacheModeWriteCombined];
      }
      g_v18OffsetRingCap = newCap;
      dbg("GAP1: Allocated %d offset ring buffers, %zuKB each "
          "(WriteCombined)\n",
          kTripleBufferCount, newCap / 1024);
    }
    id<MTLBuffer> offsetBuf = g_v18OffsetRing[g_renderSlot];
    float *offBuf = (float *)[offsetBuf contents];
    int totalOpaqueIdx = 0;
    for (int i = 0; i < validCount; i++) {
      if (i + 8 < validCount)
        __builtin_prefetch(&s_cmds[i + 8], 0, 1);
      totalOpaqueIdx += s_cmds[i].opaqueIdxCount;
      offBuf[i * 4 + 0] = s_cmds[i].ox;
      offBuf[i * 4 + 1] = s_cmds[i].oy;
      offBuf[i * 4 + 2] = s_cmds[i].oz;
      uint32_t faceMask = 0u;
      float maskAsFloat;
      memcpy(&maskAsFloat, &faceMask, sizeof(float));
      offBuf[i * 4 + 3] = maskAsFloat;
    }
    if (g_megaVB) {
      [g_currentEncoder setVertexBuffer:g_megaVB offset:0 atIndex:0];
    }
    [g_currentEncoder setVertexBuffer:offsetBuf offset:0 atIndex:4];

    if (g_depthState) {
      [g_currentEncoder setDepthStencilState:g_depthState];
    }
    [g_currentEncoder setFrontFacingWinding:MTLWindingCounterClockwise];
    [g_currentEncoder setCullMode:MTLCullModeBack];

    [g_currentEncoder setFragmentBytes:g_entityOverlayParams
                                length:sizeof(g_entityOverlayParams)
                               atIndex:5];

    if (g_useProgrammableBlending) {
      if (g_oitCmdsCapacity < validCount) {
        free(g_oitCmds);
        g_oitCmdsCapacity = validCount * 2;
        g_oitCmds =
            (OITCachedCmd *)malloc(sizeof(OITCachedCmd) * g_oitCmdsCapacity);
      }
      int oitCount = 0;
      for (int i = 0; i < validCount; i++) {
        int wIdx = s_cmds[i].idxCount - s_cmds[i].opaqueIdxCount;
        if (wIdx <= 0)
          continue;
        g_oitCmds[oitCount++] = {
            s_cmds[i].resolvedBuf,
            s_cmds[i].megaOffset,
            s_cmds[i].isMega,
            wIdx,
            i,
            s_cmds[i].opaqueIdxCount / 6 * 4,
        };
      }
      g_oitCmdsCount = oitCount;
      g_oitIB = ib;
      g_oitIBOffset = ibOffset;
      g_oitOffsetBuf = offsetBuf;
    } else if (g_depthStateNoWrite && g_thermalQualityLevel < 2) {
      if (g_deferredWaterCapacity < validCount) {
        free(g_deferredWaterCmds);
        g_deferredWaterCapacity = validCount * 2;
        g_deferredWaterCmds = (DeferredWaterCmd *)malloc(
            sizeof(DeferredWaterCmd) * g_deferredWaterCapacity);
      }
      int deferredCount = 0;
      for (int i = 0; i < validCount; i++) {
        int waterIdxCount = s_cmds[i].idxCount - s_cmds[i].opaqueIdxCount;
        if (waterIdxCount <= 0)
          continue;
        g_deferredWaterCmds[deferredCount++] = {
            s_cmds[i].resolvedBuf,    s_cmds[i].megaOffset, s_cmds[i].idxCount,
            s_cmds[i].opaqueIdxCount, s_cmds[i].distSq,     i,
            s_cmds[i].isMega,
        };
      }
      g_deferredWaterCmdCount = deferredCount;
      g_deferredWaterIB = ib;
      g_deferredWaterIBOffset = ibOffset;
      g_deferredWaterOffsetBuf = offsetBuf;
    }

    int icbCandidateCount = 0;
    for (int i = 0; i < validCount; i++) {
      if (s_cmds[i].opaqueIdxCount <= 0)
        continue;
      if (s_cmds[i].isMega) {
        if (!g_megaVB)
          continue;
      } else if (!s_cmds[i].resolvedBuf) {
        continue;
      }
      icbCandidateCount++;
    }
    bool canICB =
        (g_gpuDrivenEnabled && g_useArgumentBuffers && icbCandidateCount > 0 &&
         g_pipelineInhouseICB && g_fragArgBuf && g_fragArgEncoder &&
         g_blockAtlas && g_lightmap);
    bool useOpaqueICB = canICB && g_pipelineInhouseICBOpaque &&
                        g_fragArgBufOpaque && g_fragArgEncoderOpaque;
    bool useOpaque = g_pipelineInhouseOpaque != nil;
    if (canICB) {
      if (useOpaqueICB) {
        [g_currentEncoder setRenderPipelineState:g_pipelineInhouseICBOpaque];
        g_currentPipeline = g_pipelineInhouseICBOpaque;
        [g_fragArgEncoderOpaque setArgumentBuffer:g_fragArgBufOpaque offset:0];
        [g_fragArgEncoderOpaque setTexture:g_blockAtlas atIndex:0];
        [g_fragArgEncoderOpaque setTexture:g_lightmap atIndex:1];
        [g_currentEncoder setFragmentBuffer:g_fragArgBufOpaque
                                     offset:0
                                    atIndex:0];
      } else {
        [g_currentEncoder setRenderPipelineState:g_pipelineInhouseICB];
        g_currentPipeline = g_pipelineInhouseICB;
        [g_fragArgEncoder setArgumentBuffer:g_fragArgBuf offset:0];
        [g_fragArgEncoder setTexture:g_blockAtlas atIndex:0];
        [g_fragArgEncoder setTexture:g_lightmap atIndex:1];
        [g_currentEncoder setFragmentBuffer:g_fragArgBuf offset:0 atIndex:0];
      }
      [g_currentEncoder useResource:g_blockAtlas
                              usage:MTLResourceUsageRead
                             stages:MTLRenderStageFragment];
      [g_currentEncoder useResource:g_lightmap
                              usage:MTLResourceUsageRead
                             stages:MTLRenderStageFragment];
      if (!g_icb[g_renderSlot] ||
          g_icbMaxCommands[g_renderSlot] < (NSUInteger)icbCandidateCount) {
        NSUInteger newSize =
            MAX(ICB_INITIAL_SIZE, (NSUInteger)icbCandidateCount * 2);
        MTLIndirectCommandBufferDescriptor *desc =
            [MTLIndirectCommandBufferDescriptor new];
        desc.commandTypes = MTLIndirectCommandTypeDrawIndexed;
        desc.inheritPipelineState = YES;
        desc.inheritBuffers = YES;
        desc.maxVertexBufferBindCount = 1;

        if (g_icb[g_renderSlot])
          [g_icb[g_renderSlot] release];
        g_icb[g_renderSlot] = [g_device
            newIndirectCommandBufferWithDescriptor:desc
                                   maxCommandCount:newSize
                                           options:
                                               MTLResourceStorageModeShared];
        [desc release];
        g_icbMaxCommands[g_renderSlot] = newSize;
      }
      [g_icb[g_renderSlot]
          resetWithRange:NSMakeRange(0, (NSUInteger)icbCandidateCount)];
      int icbIdx = 0;
      for (int i = 0; i < validCount; i++) {
        int opaqueIdx = s_cmds[i].opaqueIdxCount;
        if (__builtin_expect(opaqueIdx <= 0, 0))
          continue;
        if (s_cmds[i].isMega) {
          if (__builtin_expect(!g_megaVB, 0))
            continue;
        } else {
          if (__builtin_expect(s_cmds[i].resolvedBuf == nil, 0))
            continue;
        }
        id<MTLIndirectRenderCommand> icmd = [g_icb[g_renderSlot]
            indirectRenderCommandAtIndex:(NSUInteger)icbIdx];
        if (!s_cmds[i].isMega) {
          [icmd setVertexBuffer:s_cmds[i].resolvedBuf offset:0 atIndex:0];
        }
        [icmd drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                         indexCount:(NSUInteger)opaqueIdx
                          indexType:MTLIndexTypeUInt32
                        indexBuffer:ib
                  indexBufferOffset:ibOffset
                      instanceCount:1
                         baseVertex:(NSInteger)(s_cmds[i].isMega
                                                    ? (s_cmds[i].megaOffset /
                                                       VERTEX_STRIDE)
                                                    : 0)
                       baseInstance:(NSUInteger)i];
        icbIdx++;
      }
      [g_currentEncoder
          executeCommandsInBuffer:g_icb[g_renderSlot]
                        withRange:NSMakeRange(0, (NSUInteger)icbIdx)];
      g_drawCallCount++;
      if (useOpaque) {
        [g_currentEncoder setRenderPipelineState:g_pipelineInhouseOpaque];
        g_currentPipeline = g_pipelineInhouseOpaque;
      } else {
        [g_currentEncoder setRenderPipelineState:g_pipelineInhouse];
        g_currentPipeline = g_pipelineInhouse;
      }
      if (g_blockAtlas) {
        [g_currentEncoder setFragmentTexture:g_blockAtlas atIndex:0];
      }
      if (g_megaVB) {
        [g_currentEncoder setVertexBuffer:g_megaVB offset:0 atIndex:0];
      }
    } else {
      if (useOpaque) {
        [g_currentEncoder setRenderPipelineState:g_pipelineInhouseOpaque];
        g_currentPipeline = g_pipelineInhouseOpaque;
        if (g_blockAtlas) {
          [g_currentEncoder setFragmentTexture:g_blockAtlas atIndex:0];
        }
      }
      for (int i = 0; i < validCount; i++) {
        int opaqueIdx = s_cmds[i].opaqueIdxCount;
        if (__builtin_expect(opaqueIdx <= 0, 0))
          continue;
        if (__builtin_expect(s_cmds[i].isMega, 1)) {
          [g_currentEncoder
              drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                         indexCount:(NSUInteger)opaqueIdx
                          indexType:MTLIndexTypeUInt32
                        indexBuffer:ib
                  indexBufferOffset:ibOffset
                      instanceCount:1
                         baseVertex:(NSInteger)(s_cmds[i].megaOffset /
                                                VERTEX_STRIDE)
                       baseInstance:(NSUInteger)i];
        } else {

          if (__builtin_expect(s_cmds[i].resolvedBuf != nil, 1)) {
            [g_currentEncoder setVertexBuffer:s_cmds[i].resolvedBuf
                                       offset:0
                                      atIndex:0];
            [g_currentEncoder drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                                         indexCount:(NSUInteger)opaqueIdx
                                          indexType:MTLIndexTypeUInt32
                                        indexBuffer:ib
                                  indexBufferOffset:ibOffset
                                      instanceCount:1
                                         baseVertex:0
                                       baseInstance:(NSUInteger)i];
            if (g_megaVB) {
              [g_currentEncoder setVertexBuffer:g_megaVB offset:0 atIndex:0];
            }
          }
        }
        g_drawCallCount++;
      }
    }
    int waterDraws =
        g_useProgrammableBlending ? g_oitCmdsCount : g_deferredWaterCmdCount;
    if (g_frameCount < 5 || g_frameCount % 600 == 0) {
      float gpuMs2 = g_lastGpuMs.load(std::memory_order_relaxed);
      dbg("V18_Draw: meshes=%d visible=%d mega=%d icb=%s gpu=%.1fms "
          "tris=%dK water=%d\n",
          g_activeMeshCount, validCount, megaCount, canICB ? "Y" : "N", gpuMs2,
          totalOpaqueIdx / 3000, waterDraws);
    }
    g_prof_drawAll_acc += (mach_absolute_time() - _prof_t0);
    return (jint)validCount;
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawBuffer(
    JNIEnv *, jclass, jlong frameContext, jlong vertexBuffer, jint vertexCount,
    jint baseVertex) {
  (void)frameContext;
  if (!g_currentEncoder || vertexCount <= 0)
    return;
  ResolvedBuf vbRes = resolve_buffer((uint64_t)vertexBuffer);
  if (!vbRes.buf)
    return;
  if (!g_currentPipeline && g_pipelineInhouse) {
    [g_currentEncoder setRenderPipelineState:g_pipelineInhouse];
    g_currentPipeline = g_pipelineInhouse;
    if (g_depthState)
      [g_currentEncoder setDepthStencilState:g_depthState];
  }
  if (!g_currentPipeline)
    return;
  [g_currentEncoder setVertexBuffer:vbRes.buf
                             offset:(NSUInteger)vbRes.offset
                            atIndex:0];
  [g_currentEncoder drawPrimitives:MTLPrimitiveTypeTriangle
                       vertexStart:(NSUInteger)baseVertex
                       vertexCount:(NSUInteger)vertexCount];
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetDebugColor(
    JNIEnv *, jclass, jlong frameContext, jfloat r, jfloat g, jfloat b,
    jfloat a) {
  (void)frameContext;
  if (!g_currentEncoder)
    return;
  float color[4] = {r, g, b, a};
  [g_currentEncoder setVertexBytes:color length:sizeof(color) atIndex:5];
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawLineBuffer(
    JNIEnv *, jclass, jlong frameContext, jlong vertexBuffer,
    jint vertexCount) {
  (void)frameContext;
  if (__builtin_expect(g_frameCount < 5, 0))
    dbg("nDrawLineBuffer: encoder=%p, vtxCount=%d, pipeline=%p, buffer=%lld\n",
        g_currentEncoder, vertexCount, g_pipelineDebugLines,
        (long long)vertexBuffer);
  if (!g_currentEncoder || vertexCount <= 0 || !g_pipelineDebugLines)
    return;
  ResolvedBuf vbRes = resolve_buffer((uint64_t)vertexBuffer);
  if (!vbRes.buf) {
    if (__builtin_expect(g_frameCount < 5, 0))
      dbg("nDrawLineBuffer: buffer lookup failed\n");
    return;
  }
  [g_currentEncoder setRenderPipelineState:g_pipelineDebugLines];
  if (g_depthStateLessEqual)
    [g_currentEncoder setDepthStencilState:g_depthStateLessEqual];
  [g_currentEncoder setVertexBytes:g_projMatrix length:64 atIndex:1];
  [g_currentEncoder setVertexBytes:g_mvMatrix length:64 atIndex:2];
  [g_currentEncoder setVertexBuffer:vbRes.buf
                             offset:(NSUInteger)vbRes.offset
                            atIndex:0];
  [g_currentEncoder drawPrimitives:MTLPrimitiveTypeLine
                       vertexStart:0
                       vertexCount:(NSUInteger)vertexCount];
  if (__builtin_expect(g_frameCount < 5, 0))
    dbg("nDrawLineBuffer: drew %d vertices\n", vertexCount);
  if (g_currentPipeline) {
    [g_currentEncoder setRenderPipelineState:g_currentPipeline];
    if (g_depthState)
      [g_currentEncoder setDepthStencilState:g_depthState];
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawTriangleBuffer(
    JNIEnv *, jclass, jlong frameContext, jlong vertexBuffer,
    jint vertexCount) {
  (void)frameContext;
  if (!g_currentEncoder || vertexCount <= 0 || !g_pipelineDebugLines)
    return;
  ResolvedBuf vbRes = resolve_buffer((uint64_t)vertexBuffer);
  if (!vbRes.buf)
    return;
  [g_currentEncoder setRenderPipelineState:g_pipelineDebugLines];
  if (g_depthStateLessEqual)
    [g_currentEncoder setDepthStencilState:g_depthStateLessEqual];
  [g_currentEncoder setVertexBytes:g_projMatrix length:64 atIndex:1];
  [g_currentEncoder setVertexBytes:g_mvMatrix length:64 atIndex:2];
  [g_currentEncoder setVertexBuffer:vbRes.buf
                             offset:(NSUInteger)vbRes.offset
                            atIndex:0];
  [g_currentEncoder drawPrimitives:MTLPrimitiveTypeTriangle
                       vertexStart:0
                       vertexCount:(NSUInteger)vertexCount];
  if (g_currentPipeline) {
    [g_currentEncoder setRenderPipelineState:g_currentPipeline];
    if (g_depthState)
      [g_currentEncoder setDepthStencilState:g_depthState];
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawOverlayQuad(
    JNIEnv *, jclass, jlong frameContext, jfloat r, jfloat g, jfloat b,
    jfloat a) {
  (void)frameContext;
  if (!g_currentEncoder || !g_pipelineDebugLines)
    return;

  float identity[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
  float color[4] = {r, g, b, a};

  float verts[9] = {
      -1.0f, -1.0f, 0.5f, 3.0f, -1.0f, 0.5f, -1.0f, 3.0f, 0.5f,
  };

  [g_currentEncoder setRenderPipelineState:g_pipelineDebugLines];

  if (g_depthStateNoWrite)
    [g_currentEncoder setDepthStencilState:g_depthStateNoWrite];

  [g_currentEncoder setVertexBytes:identity length:64 atIndex:1];
  [g_currentEncoder setVertexBytes:identity length:64 atIndex:2];
  [g_currentEncoder setVertexBytes:color length:16 atIndex:5];
  [g_currentEncoder setVertexBytes:verts length:sizeof(verts) atIndex:0];
  [g_currentEncoder drawPrimitives:MTLPrimitiveTypeTriangle
                       vertexStart:0
                       vertexCount:3];

  if (g_currentPipeline) {
    [g_currentEncoder setRenderPipelineState:g_currentPipeline];
    if (g_depthState)
      [g_currentEncoder setDepthStencilState:g_depthState];
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetCurrentFrameContext(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  if (!g_device || !g_queue || !g_tbColor[0] || !g_tbDepth[0])
    return 0;
  if (g_gpuNeedsRecovery.load(std::memory_order_acquire)) {
    g_gpuNeedsRecovery.store(false, std::memory_order_release);
    dbg("GPU_RECOVERY: Recreating command queue after GPU error\n");
    for (int i = 0; i < kTripleBufferCount; i++) {
      if (g_tbCmdBuf[i]) {
        [g_tbCmdBuf[i] release];
        g_tbCmdBuf[i] = nil;
      }
      g_tbSlotReady[i].store(true, std::memory_order_release);
    }
    g_tbLastCompleted.store(0, std::memory_order_release);
    g_currentFrameReady = true;
    if (g_currentEncoder) {
      [g_currentEncoder endEncoding];
      [g_currentEncoder release];
      g_currentEncoder = nil;
    }
    if (g_currentCmdBuffer) {
      [g_currentCmdBuffer release];
      g_currentCmdBuffer = nil;
    }
    [g_queue release];
    g_queue = [g_device newCommandQueue];
    if (g_frameSemaphore) {

      for (int _si = 0; _si < kTripleBufferCount; _si++)
        dispatch_semaphore_signal(g_frameSemaphore);
      dispatch_release(g_frameSemaphore);
      g_frameSemaphore = dispatch_semaphore_create(kTripleBufferCount - 1);
    }
  }
  if (g_currentEncoder)
    return (jlong)0x1;
  @autoreleasepool {
    bool reuseFrame = g_reuseTerrainFrame;
    g_reuseTerrainFrame = false;
    g_wasReuseFrame = reuseFrame;
    if (reuseFrame) {

      int prevSlot = (g_currentBufferIndex + 2) % 3;

      if (!g_tbSlotReady[prevSlot].load(std::memory_order_acquire)) {

        int slot1 = (g_currentBufferIndex + 1) % 3;
        int slot0 = g_currentBufferIndex;
        if (g_tbSlotReady[slot1].load(std::memory_order_acquire)) {
          g_renderSlot = slot1;
          g_color = g_tbColor[g_renderSlot];
          g_depth = g_tbDepth[g_renderSlot];
          g_ioSurface = g_tbIOSurface[g_renderSlot];
        } else if (g_tbSlotReady[slot0].load(std::memory_order_acquire)) {
          g_renderSlot = slot0;
          g_color = g_tbColor[g_renderSlot];
          g_depth = g_tbDepth[g_renderSlot];
          g_ioSurface = g_tbIOSurface[g_renderSlot];
        } else {

          reuseFrame = false;
          g_wasReuseFrame = false;
        }
      } else {
        g_renderSlot = prevSlot;
        g_color = g_tbColor[g_renderSlot];
        g_depth = g_tbDepth[g_renderSlot];
        g_ioSurface = g_tbIOSurface[g_renderSlot];
      }
    }
    if (!reuseFrame) {

      if (g_frameSemaphore && !g_shuttingDown) {
        dispatch_time_t timeout =
            dispatch_time(DISPATCH_TIME_NOW, 100 * NSEC_PER_MSEC);
        long result = dispatch_semaphore_wait(g_frameSemaphore, timeout);
        if (result != 0) {

          static int timeoutCount = 0;
          timeoutCount++;
          if (timeoutCount <= 10 || timeoutCount % 100 == 0) {
            fprintf(stderr,
                    "[GPU STALL] Semaphore timeout #%d — forcing recovery\n",
                    timeoutCount);
          }

          for (int i = 0; i < kTripleBufferCount; i++) {
            g_tbSlotReady[i].store(true, std::memory_order_release);
          }
        }
      }
      g_renderSlot = g_currentBufferIndex;
      g_color = g_tbColor[g_renderSlot];
      g_depth = g_tbDepth[g_renderSlot];
      g_ioSurface = g_tbIOSurface[g_renderSlot];
      g_tbSlotReady[g_renderSlot].store(false, std::memory_order_release);
    }
    g_currentFrameReady = false;
    g_currentCmdBuffer = [[g_queue commandBuffer] retain];
    static MTLRenderPassDescriptor *s_cachedRP = nil;
    if (!s_cachedRP) {
      s_cachedRP = [[MTLRenderPassDescriptor renderPassDescriptor] retain];
      s_cachedRP.colorAttachments[0].storeAction = MTLStoreActionStore;
      s_cachedRP.colorAttachments[0].clearColor =
          MTLClearColorMake(0.0, 0.0, 0.0, 0.0);

      s_cachedRP.depthAttachment.storeAction =
          g_useMemorylessTargets ? MTLStoreActionDontCare : MTLStoreActionStore;
      s_cachedRP.depthAttachment.clearDepth = 1.0;
    }
#ifdef METALRENDER_HAS_METALFX

    id<MTLTexture> renderTarget = g_color;
    id<MTLTexture> depthTarget = g_depth;
    bool usingLR = false;
    if (@available(macOS 13.0, *)) {
      if (g_mfxScaler && g_lrColor[g_renderSlot] && !reuseFrame) {
        renderTarget = g_lrColor[g_renderSlot];
        depthTarget = g_lrDepth[g_renderSlot];
        usingLR = true;
      }
    }
    s_cachedRP.colorAttachments[0].texture = renderTarget;
    s_cachedRP.colorAttachments[0].loadAction =
        (reuseFrame && !usingLR) ? MTLLoadActionLoad : MTLLoadActionClear;
    s_cachedRP.depthAttachment.texture = depthTarget;
    s_cachedRP.depthAttachment.loadAction =
        (reuseFrame && !usingLR) ? MTLLoadActionLoad : MTLLoadActionClear;
#else
    s_cachedRP.colorAttachments[0].texture = g_color;
    s_cachedRP.colorAttachments[0].loadAction =
        reuseFrame ? MTLLoadActionLoad : MTLLoadActionClear;
    s_cachedRP.depthAttachment.texture = g_depth;
    s_cachedRP.depthAttachment.loadAction =
        reuseFrame ? MTLLoadActionLoad : MTLLoadActionClear;
#endif
    g_currentEncoder = [[g_currentCmdBuffer
        renderCommandEncoderWithDescriptor:s_cachedRP] retain];
    g_currentPipeline = nil;
    MTLViewport vp;
    vp.originX = 0;
    vp.originY = 0;
#ifdef METALRENDER_HAS_METALFX
    vp.width = (double)s_cachedRP.colorAttachments[0].texture.width;
    vp.height = (double)s_cachedRP.colorAttachments[0].texture.height;
#else
    vp.width = (double)g_color.width;
    vp.height = (double)g_color.height;
#endif
    vp.znear = 0.0;
    vp.zfar = 1.0;
    [g_currentEncoder setViewport:vp];
    [g_currentEncoder setVertexBytes:g_projMatrix length:64 atIndex:1];
    [g_currentEncoder setVertexBytes:g_mvMatrix length:64 atIndex:2];
    float camPos[4] = {(float)g_camX, (float)g_camY, (float)g_camZ,
                       g_skyBrightness};
    [g_currentEncoder setVertexBytes:camPos length:16 atIndex:3];
    float chunkOff[4] = {0, 0, 0, 0};
    [g_currentEncoder setVertexBytes:chunkOff length:16 atIndex:4];
    [g_currentEncoder setFrontFacingWinding:MTLWindingCounterClockwise];
    [g_currentEncoder setCullMode:MTLCullModeBack];
    if (g_blockAtlas) {
      [g_currentEncoder setFragmentTexture:g_blockAtlas atIndex:0];
    }
    if (g_lightmap) {
      [g_currentEncoder setFragmentTexture:g_lightmap atIndex:1];
    }
    if (g_frameCount < 3) {
      dbg("Triple-buffer: renderSlot=%d reuse=%d lastCompleted=%d\n",
          g_renderSlot, reuseFrame ? 1 : 0,
          g_tbLastCompleted.load(std::memory_order_relaxed));
    }
  }
  return (jlong)0x1;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nEndFrame(
    JNIEnv *, jclass, jlong handle) {
  @autoreleasepool {
    (void)handle;
    uint64_t _prof_ef_t0 = mach_absolute_time();
    g_frameCount++;
    static uint64_t ft_last = 0;
    static uint64_t ft_acc = 0;
    static int ft_count = 0;
    uint64_t ft_now = mach_absolute_time();
    if (ft_last > 0) {
      ft_acc += (ft_now - ft_last);
      ft_count++;
    }
    ft_last = ft_now;

    if (ft_count > 0 && (g_frameCount % 600 == 0)) {
      ensureTimebase();
      double avg_ms = (double)(ft_acc / ft_count) * g_cachedTimebase.numer /
                      g_cachedTimebase.denom / 1000000.0;
      double avg_fps = (avg_ms > 0) ? (1000.0 / avg_ms) : 0;
      dbg("FRAME_TIMING: avg=%.2fms (%.1f FPS) over %d frames, draws=%d\n",
          avg_ms, avg_fps, ft_count, g_drawCallCount);
      ft_acc = 0;
      ft_count = 0;
    }

    {
      ensureTimebase();
      if (ft_last > 0 && ft_now > ft_last) {
        float frameMs = (float)((ft_now - ft_last) * g_cachedTimebase.numer /
                                g_cachedTimebase.denom) /
                        1000000.0f;
        g_avgFrameTimeMs = g_avgFrameTimeMs * 0.95f + frameMs * 0.05f;
      }
    }
    if (g_frameCount <= 5 || g_frameCount % 500 == 0) {
      dbg("EndFrame #%d: draws=%d skips=%d encoder=%p pipeline=%p "
          "pipelineInhouse=%p texture=%dx%d proj[0]=%.3f mv[0]=%.3f "
          "cam=%.1f,%.1f,%.1f gpuDriven=%d meshShaders=%d\n",
          g_frameCount, g_drawCallCount, g_drawSkipCount, g_currentEncoder,
          g_currentPipeline, g_pipelineInhouse,
          g_color ? (int)g_color.width : 0, g_color ? (int)g_color.height : 0,
          g_projMatrix[0], g_mvMatrix[0], g_camX, g_camY, g_camZ,
          g_gpuDrivenEnabled ? 1 : 0, g_meshShadersActive ? 1 : 0);
    }

    if (g_frameCount % 300 == 0) {
      NSLog(@"[MetalRender] Frame %d — ActivePath: %@  ICB=%@  MeshShaders=%@  "
            @"OIT=%@  ArgBuf=%@  draws=%d",
            g_frameCount,
            g_meshShadersActive
                ? @"MESH_SHADER"
                : (g_gpuDrivenEnabled ? @"ICB_GPU_DRIVEN" : @"INHOUSE_VERTEX"),
            g_gpuDrivenEnabled ? @"ON" : @"OFF",
            g_meshShadersActive ? @"ON" : @"OFF",
            g_useProgrammableBlending ? @"ON" : @"OFF",
            g_useArgumentBuffers ? @"ON" : @"OFF", g_drawCallCount);
    }
    g_drawCallCount = 0;
    g_drawSkipCount = 0;
    if (g_currentEncoder) {
      [g_currentEncoder endEncoding];
      [g_currentEncoder release];
      g_currentEncoder = nil;
    }
#ifdef METALRENDER_HAS_METALFX

    if (@available(macOS 13.0, *)) {
      if (g_mfxScaler && !g_reuseTerrainFrame && g_lrColor[g_renderSlot] &&
          g_currentCmdBuffer) {
        g_mfxScaler.colorTexture = g_lrColor[g_renderSlot];
        g_mfxScaler.outputTexture = g_color;
        [g_mfxScaler encodeToCommandBuffer:g_currentCmdBuffer];
      }
    }
#endif
    if (g_currentCmdBuffer) {
      if (g_frameEvent) {
        g_eventCounter++;
        [g_currentCmdBuffer encodeSignalEvent:g_frameEvent
                                        value:g_eventCounter];
      }
      if (g_depthCmdBuffer) {
        [g_depthCmdBuffer release];
        g_depthCmdBuffer = nil;
      }
      g_depthCmdBuffer = [g_currentCmdBuffer retain];

      int completedSlot = g_renderSlot;

      bool wasReuseForThisFrame = g_wasReuseFrame;
      if (g_tbCmdBuf[completedSlot]) {
        [g_tbCmdBuf[completedSlot] release];
      }
      g_tbCmdBuf[completedSlot] = [g_currentCmdBuffer retain];
      {
        static uint64_t gpu_acc = 0;
        static int gpu_frame_count = 0;

        dispatch_semaphore_t capSema = g_frameSemaphore;
        if (capSema)
          dispatch_retain(capSema);
        [g_currentCmdBuffer addCompletedHandler:^(id<MTLCommandBuffer> cb) {
          if (cb.status == MTLCommandBufferStatusError) {
            NSError *err = cb.error;
            dbg("GPU_ERROR: slot=%d err=%s code=%ld\n", completedSlot,
                err ? [[err localizedDescription] UTF8String] : "unknown",
                (long)(err ? err.code : -1));
            g_gpuNeedsRecovery.store(true, std::memory_order_release);
          }
          g_tbSlotReady[completedSlot].store(true, std::memory_order_release);
          g_tbLastCompleted.store(completedSlot, std::memory_order_release);
          g_currentFrameReady = true;
          if (capSema && !wasReuseForThisFrame) {
            dispatch_semaphore_signal(capSema);
          }
          if (capSema)
            dispatch_release(capSema);
          if (@available(macOS 10.15, *)) {
            CFTimeInterval gpuStart = cb.GPUStartTime;
            CFTimeInterval gpuEnd = cb.GPUEndTime;
            if (gpuStart > 0 && gpuEnd > gpuStart) {
              double gpuMs = (gpuEnd - gpuStart) * 1000.0;
              g_lastGpuMs.store((float)gpuMs, std::memory_order_relaxed);
              uint64_t gpuUs = (uint64_t)(gpuMs * 1000.0);
              gpu_acc += gpuUs;
              gpu_frame_count++;
              if (gpu_frame_count % 120 == 0) {
                double avg = (double)gpu_acc / gpu_frame_count;
                dbg("GPU_TIMING: avg=%.2fms (%.1f max-FPS) over %d frames\n",
                    avg / 1000.0, 1000000.0 / avg, gpu_frame_count);
                gpu_acc = 0;
                gpu_frame_count = 0;
              }
            }
          }
        }];
      }
      [g_currentCmdBuffer commit];

      if (!wasReuseForThisFrame) {
        g_currentBufferIndex = (g_currentBufferIndex + 1) % kTripleBufferCount;
      }
      [g_currentCmdBuffer release];
      g_currentCmdBuffer = nil;
    }
    g_currentPipeline = nil;
    double now = CFAbsoluteTimeGetCurrent();
    if (now - g_lastThermalCheckTime > 1.0) {
      g_lastThermalCheckTime = now;
      NSProcessInfoThermalState state =
          [[NSProcessInfo processInfo] thermalState];
      g_thermalState = (int)state;

      if (state >= NSProcessInfoThermalStateCritical) {
        g_lodRadiusReduction = 50;
        g_thermalQualityLevel = 2;
        g_dynamicLODScale = fmaxf(g_dynamicLODScale, 1.8f);
        if (g_frameCount % 60 == 0)
          dbg("THERMAL: Critical! quality=2, LOD scale=%.2f\n",
              g_dynamicLODScale);
      } else if (state >= NSProcessInfoThermalStateSerious) {
        g_lodRadiusReduction = 25;
        g_thermalQualityLevel = 1;
        g_dynamicLODScale = fmaxf(g_dynamicLODScale, 1.4f);
        if (g_frameCount % 300 == 0)
          dbg("THERMAL: Serious. quality=1, LOD scale=%.2f\n",
              g_dynamicLODScale);
      } else {
        g_lodRadiusReduction = 0;
        g_thermalQualityLevel = 0;
      }

      if (g_thermalQualityLevel == 0 && g_avgFrameTimeMs > 0.0f) {
        if (g_avgFrameTimeMs > 18.0f) {

          g_dynamicLODScale = fminf(g_dynamicLODScale * 1.02f, 1.6f);
          if (g_frameCount % 300 == 0)
            dbg("ADAPTIVE_LOD: frame_time=%.1fms > 18ms, scale UP to %.2f\n",
                g_avgFrameTimeMs, g_dynamicLODScale);
        } else if (g_avgFrameTimeMs < 14.0f && g_dynamicLODScale > 1.0f) {

          g_dynamicLODScale = fmaxf(g_dynamicLODScale * 0.98f, 1.0f);
        }
      }
    }
    {
      std::lock_guard<std::mutex> lock(g_deferredMutex);

      int freed = 0;
      size_t i = 0;
      while (i < g_deferredDeletions.size()) {
        auto &dd = g_deferredDeletions[i];
        if (g_frameCount - dd.frameQueued >= DEFERRED_FRAME_DELAY) {
          if (dd.isMega) {
            megaFree(dd.handle);
          } else {
            std::unique_lock<std::shared_mutex> bufLock(g_bufferMutex);
            auto bufIt = g_buffers.find(dd.handle);
            if (bufIt != g_buffers.end()) {
              [bufIt->second release];
              g_buffers.erase(bufIt);
            }
          }

          dd = g_deferredDeletions.back();
          g_deferredDeletions.pop_back();
          freed++;
        } else {
          i++;
        }
      }
      if (freed > 0 && (g_frameCount % 300 == 0)) {
        dbg("DeferredDelete: freed %d buffers (%zu pending)\n", freed,
            g_deferredDeletions.size());
      }
    }
    g_prof_endFrame_acc += (mach_absolute_time() - _prof_ef_t0);
    g_prof_count++;

    if (g_prof_count > 0 && (g_frameCount % kProfileEmitInterval == 0)) {
      ensureTimebase();
      double ns_per_tick =
          (double)g_cachedTimebase.numer / g_cachedTimebase.denom;
      double drawAll_ms =
          (double)g_prof_drawAll_acc / g_prof_count * ns_per_tick / 1e6;
      double endFrame_ms =
          (double)g_prof_endFrame_acc / g_prof_count * ns_per_tick / 1e6;
      double waitRender_ms =
          (double)g_prof_waitRender_acc / g_prof_count * ns_per_tick / 1e6;
      double cglBind_ms =
          (double)g_prof_cglBind_acc / g_prof_count * ns_per_tick / 1e6;
      dbg("PROFILE: drawAll=%.2fms endFrame=%.2fms waitRender=%.2fms "
          "cglBind=%.2fms (avg over %d frames)\n",
          drawAll_ms, endFrame_ms, waitRender_ms, cglBind_ms, g_prof_count);
      NSLog(@"[MetalRender] PROFILE: drawAll=%.2fms endFrame=%.2fms "
            @"waitRender=%.2fms cglBind=%.2fms (avg/%d)",
            drawAll_ms, endFrame_ms, waitRender_ms, cglBind_ms, g_prof_count);
      g_prof_drawAll_acc = 0;
      g_prof_endFrame_acc = 0;
      g_prof_waitRender_acc = 0;
      g_prof_cglBind_acc = 0;
      g_prof_count = 0;
    }
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetProjectionMatrix(
    JNIEnv *env, jclass, jlong handle, jfloatArray matrix) {
  (void)handle;
  if (matrix && env->GetArrayLength(matrix) >= 16) {
    env->GetFloatArrayRegion(matrix, 0, 16, g_projMatrix);
    if (g_currentEncoder) {
      [g_currentEncoder setVertexBytes:g_projMatrix length:64 atIndex:1];
    }
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetModelViewMatrix(
    JNIEnv *env, jclass, jlong handle, jfloatArray matrix) {
  (void)handle;
  if (matrix && env->GetArrayLength(matrix) >= 16) {
    env->GetFloatArrayRegion(matrix, 0, 16, g_mvMatrix);
    if (g_currentEncoder) {
      [g_currentEncoder setVertexBytes:g_mvMatrix length:64 atIndex:2];
    }
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetCameraPosition(
    JNIEnv *, jclass, jlong handle, jdouble x, jdouble y, jdouble z) {
  (void)handle;
  g_camX = x;
  g_camY = y;
  g_camZ = z;
  if (g_currentEncoder) {

    float camPos[4] = {(float)x, (float)y, (float)z, g_skyBrightness};
    [g_currentEncoder setVertexBytes:camPos length:16 atIndex:3];
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetFrameMatrices(
    JNIEnv *env, jclass, jlong handle, jfloatArray projMatrix,
    jfloatArray mvMatrix, jdouble camX, jdouble camY, jdouble camZ) {
  (void)handle;
  if (projMatrix && env->GetArrayLength(projMatrix) >= 16) {
    env->GetFloatArrayRegion(projMatrix, 0, 16, g_projMatrix);
  }
  if (mvMatrix && env->GetArrayLength(mvMatrix) >= 16) {
    env->GetFloatArrayRegion(mvMatrix, 0, 16, g_mvMatrix);
  }
  g_camX = camX;
  g_camY = camY;
  g_camZ = camZ;
  if (g_currentEncoder) {
    [g_currentEncoder setVertexBytes:g_projMatrix length:64 atIndex:1];
    [g_currentEncoder setVertexBytes:g_mvMatrix length:64 atIndex:2];
    float camPos[4] = {(float)camX, (float)camY, (float)camZ, g_skyBrightness};
    [g_currentEncoder setVertexBytes:camPos length:16 atIndex:3];
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nBindTexture(
    JNIEnv *, jclass, jlong handle, jlong textureHandle, jint slot) {
  (void)handle;
  if (textureHandle == 0)
    return;
  id<MTLTexture> tex =
      (__bridge id<MTLTexture>)(void *)(uintptr_t)textureHandle;
  if (slot == 0) {
    g_blockAtlas = tex;
  } else if (slot == 1) {
    g_lightmap = tex;
  }
  if (g_currentEncoder && tex) {
    [g_currentEncoder setFragmentTexture:tex atIndex:(NSUInteger)slot];
  }
}

static void astc_encode_block_4x4(const uint8_t *pixels, uint8_t out[16]) {
  memset(out, 0, 16);

  auto setBits = [&](int pos, int count, uint32_t val) {
    for (int i = 0; i < count; i++) {
      if (val & (1u << i)) {
        out[(pos + i) >> 3] |= (1 << ((pos + i) & 7));
      }
    }
  };

  setBits(0, 11, 0x14A);

  setBits(11, 2, 0);

  setBits(13, 4, 12);

  uint8_t mn[4] = {255, 255, 255, 255};
  uint8_t mx[4] = {0, 0, 0, 0};
  for (int i = 0; i < 16; i++) {
    for (int c = 0; c < 4; c++) {
      uint8_t v = pixels[i * 4 + c];
      if (v < mn[c])
        mn[c] = v;
      if (v > mx[c])
        mx[c] = v;
    }
  }

  uint8_t ep[8] = {mn[0], mx[0], mn[1], mx[1], mn[2], mx[2], mn[3], mx[3]};
  for (int i = 0; i < 8; i++) {
    setBits(17 + i * 8, 8, ep[i]);
  }

  int dr = mx[0] - mn[0], dg = mx[1] - mn[1];
  int db = mx[2] - mn[2], da = mx[3] - mn[3];
  int dot_max = dr * dr + dg * dg + db * db + da * da;
  for (int i = 0; i < 16; i++) {
    int w = 0;
    if (dot_max > 0) {
      int cr = pixels[i * 4] - mn[0];
      int cg = pixels[i * 4 + 1] - mn[1];
      int cb = pixels[i * 4 + 2] - mn[2];
      int ca = pixels[i * 4 + 3] - mn[3];
      int dot = cr * dr + cg * dg + cb * db + ca * da;
      w = (dot * 3 + dot_max / 2) / dot_max;
      if (w < 0)
        w = 0;
      if (w > 3)
        w = 3;
    }

    setBits(126 - i * 2, 2, (uint32_t)w);
  }
}

static uint8_t *compress_rgba_to_astc4x4(const uint8_t *rgba, int w, int h,
                                         size_t *outSize) {
  int bw = (w + 3) / 4;
  int bh = (h + 3) / 4;
  size_t compSize = (size_t)bw * bh * 16;
  uint8_t *comp = new uint8_t[compSize];
  for (int by = 0; by < bh; by++) {
    for (int bx = 0; bx < bw; bx++) {

      uint8_t block[64];
      for (int py = 0; py < 4; py++) {
        for (int px = 0; px < 4; px++) {
          int sx = bx * 4 + px;
          int sy = by * 4 + py;
          if (sx >= w)
            sx = w - 1;
          if (sy >= h)
            sy = h - 1;
          const uint8_t *src = rgba + ((size_t)sy * w + sx) * 4;
          uint8_t *dst = block + (py * 4 + px) * 4;
          dst[0] = src[0];
          dst[1] = src[1];
          dst[2] = src[2];
          dst[3] = src[3];
        }
      }
      astc_encode_block_4x4(block, comp + ((size_t)by * bw + bx) * 16);
    }
  }
  *outSize = compSize;
  return comp;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nCreateTexture2D(
    JNIEnv *env, jclass, jlong deviceHandle, jint width, jint height,
    jbyteArray pixelData) {
  (void)deviceHandle;
  if (!g_device || width <= 0 || height <= 0)
    return 0;
  jbyte *data = env->GetByteArrayElements(pixelData, NULL);
  if (!data)
    return 0;

  id<MTLTexture> tex = nil;

  bool useASTC = false;
  if (useASTC) {
    MTLTextureDescriptor *desc = [MTLTextureDescriptor
        texture2DDescriptorWithPixelFormat:MTLPixelFormatASTC_4x4_LDR
                                     width:(NSUInteger)width
                                    height:(NSUInteger)height
                                 mipmapped:NO];
    desc.usage = MTLTextureUsageShaderRead;
    desc.storageMode = MTLStorageModeShared;
    tex = [g_device newTextureWithDescriptor:desc];
    if (tex) {
      size_t compSize = 0;
      uint8_t *comp = compress_rgba_to_astc4x4((const uint8_t *)data, width,
                                               height, &compSize);
      MTLRegion region =
          MTLRegionMake2D(0, 0, (NSUInteger)width, (NSUInteger)height);

      [tex replaceRegion:region
             mipmapLevel:0
               withBytes:comp
             bytesPerRow:(NSUInteger)((width / 4) * 16)];
      delete[] comp;
      dbg("nCreateTexture2D: created %dx%d ASTC 4x4 texture %p (%.1fKB vs "
          "%.1fKB RGBA)\n",
          width, height, tex, compSize / 1024.0, (width * height * 4) / 1024.0);
    }
  }

  if (!tex) {
    MTLTextureDescriptor *desc = [MTLTextureDescriptor
        texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm
                                     width:(NSUInteger)width
                                    height:(NSUInteger)height
                                 mipmapped:NO];
    desc.usage = MTLTextureUsageShaderRead;
    desc.storageMode = MTLStorageModeShared;
    tex = [g_device newTextureWithDescriptor:desc];
    if (tex) {
      MTLRegion region =
          MTLRegionMake2D(0, 0, (NSUInteger)width, (NSUInteger)height);
      [tex replaceRegion:region
             mipmapLevel:0
               withBytes:data
             bytesPerRow:(NSUInteger)(width * 4)];
      dbg("nCreateTexture2D: created %dx%d RGBA texture %p\n", width, height,
          tex);
    } else {
      dbg("nCreateTexture2D: failed to create %dx%d texture\n", width, height);
    }
  }
  env->ReleaseByteArrayElements(pixelData, data, JNI_ABORT);
  return tex ? (jlong)(uintptr_t)(__bridge_retained void *)tex : 0;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDestroyTexture2D(
    JNIEnv *, jclass, jlong textureHandle) {
  if (!textureHandle)
    return;
  id<MTLTexture> tex =
      (__bridge id<MTLTexture>)(void *)(uintptr_t)textureHandle;
  if (tex == g_blockAtlas)
    g_blockAtlas = nil;
  if (tex == g_lightmap)
    g_lightmap = nil;
  if (tex == g_entityTexture)
    g_entityTexture = nil;
  if (tex)
    [tex release];
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nUpdateTexture2D(
    JNIEnv *env, jclass, jlong textureHandle, jint width, jint height,
    jbyteArray pixelData) {
  if (!textureHandle || width <= 0 || height <= 0)
    return;
  id<MTLTexture> tex =
      (__bridge id<MTLTexture>)(void *)(uintptr_t)textureHandle;
  if (!tex)
    return;
  jbyte *data = env->GetByteArrayElements(pixelData, NULL);
  if (data) {
    MTLRegion region =
        MTLRegionMake2D(0, 0, (NSUInteger)width, (NSUInteger)height);

    if (false && tex.pixelFormat == MTLPixelFormatASTC_4x4_LDR) {
      size_t compSize = 0;
      uint8_t *comp = compress_rgba_to_astc4x4((const uint8_t *)data, width,
                                               height, &compSize);
      [tex replaceRegion:region
             mipmapLevel:0
               withBytes:comp
             bytesPerRow:(NSUInteger)((width / 4) * 16)];
      delete[] comp;
    } else {
      [tex replaceRegion:region
             mipmapLevel:0
               withBytes:data
             bytesPerRow:(NSUInteger)(width * 4)];
    }
    env->ReleaseByteArrayElements(pixelData, data, JNI_ABORT);
  }
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetDeviceHandle(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return (jlong)(uintptr_t)(__bridge void *)g_device;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetShaderLibraryHandle(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;

  if (!g_shaderLibrary)
    return 0;
  return (jlong)(uintptr_t)(__bridge void *)g_shaderLibrary;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetInhousePipelineHandle(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return (jlong)(uintptr_t)(__bridge void *)g_pipelineOpaque;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetDefaultPipelineHandle(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return (jlong)(uintptr_t)(__bridge void *)g_pipelineOpaque;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetGLTextureId(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return 0;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIOSurfaceWidth(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return g_rtWidth;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIOSurfaceHeight(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return g_rtHeight;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nIsFrameReady(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;

  int last = g_tbLastCompleted.load(std::memory_order_acquire);
  if (last >= 0 && g_tbSlotReady[last].load(std::memory_order_acquire))
    return JNI_TRUE;
  return g_currentFrameReady ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nWaitForRender(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  uint64_t _prof_wr_t0 = mach_absolute_time();
  int last = g_tbLastCompleted.load(std::memory_order_acquire);
  if (last >= 0 && g_tbSlotReady[last].load(std::memory_order_acquire)) {
    g_prof_waitRender_acc += (mach_absolute_time() - _prof_wr_t0);
    return;
  }
  static int waitDbgCount = 0;
  waitDbgCount++;
  if (waitDbgCount <= 5 || waitDbgCount % 500 == 0) {
    dbg("WAIT_DBG: fast-path MISS last=%d ready=[%d,%d,%d] frameReady=%d\n",
        last, g_tbSlotReady[0].load(std::memory_order_relaxed) ? 1 : 0,
        g_tbSlotReady[1].load(std::memory_order_relaxed) ? 1 : 0,
        g_tbSlotReady[2].load(std::memory_order_relaxed) ? 1 : 0,
        g_currentFrameReady ? 1 : 0);
  }
  if (!g_currentFrameReady) {
    int spins = 0;
    while (!g_currentFrameReady && spins < 10000) {
      std::this_thread::yield();
      spins++;
    }
    if (!g_currentFrameReady && g_depthCmdBuffer) {
      [g_depthCmdBuffer waitUntilCompleted];
    }
  }
  g_prof_waitRender_acc += (mach_absolute_time() - _prof_wr_t0);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nBindIOSurfaceToTexture(
    JNIEnv *, jclass, jlong handle, jint glTexture) {
  (void)handle;
  uint64_t _prof_cgl_t0 = mach_absolute_time();

  int blitSlot = g_tbLastCompleted.load(std::memory_order_acquire);
  IOSurfaceRef blitSurface =
      (blitSlot >= 0) ? g_tbIOSurface[blitSlot] : g_ioSurface;
  if (!blitSurface || glTexture == 0 || g_rtWidth <= 0 || g_rtHeight <= 0)
    return JNI_FALSE;
  CGLContextObj cglCtx = CGLGetCurrentContext();
  if (!cglCtx)
    return JNI_FALSE;
  int w = std::max(1, (int)(g_rtWidth * g_scale));
  int h = std::max(1, (int)(g_rtHeight * g_scale));
  glBindTexture(GL_TEXTURE_RECTANGLE_ARB, (GLuint)glTexture);
  CGLError err = CGLTexImageIOSurface2D(
      cglCtx, GL_TEXTURE_RECTANGLE_ARB, GL_RGBA, (GLsizei)w, (GLsizei)h,
      GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, blitSurface, 0);
  glBindTexture(GL_TEXTURE_RECTANGLE_ARB, 0);
  g_prof_cglBind_acc += (mach_absolute_time() - _prof_cgl_t0);
  return (err == kCGLNoError) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nReadbackPixels(
    JNIEnv *env, jclass, jlong handle, jobject dest) {
  (void)handle;
  if (!g_color || !dest || !env)
    return JNI_FALSE;
  void *destPtr = env->GetDirectBufferAddress(dest);
  if (!destPtr)
    return JNI_FALSE;
  int w = (int)g_color.width;
  int h = (int)g_color.height;
  jlong capacity = env->GetDirectBufferCapacity(dest);
  if (capacity < (jlong)(w * h * 4))
    return JNI_FALSE;
  [g_color getBytes:destPtr
        bytesPerRow:(NSUInteger)(w * 4)
         fromRegion:MTLRegionMake2D(0, 0, w, h)
        mipmapLevel:0];
  return JNI_TRUE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nReadbackDepth(
    JNIEnv *env, jclass, jlong handle, jobject dest) {
  (void)handle;
  if (!g_depthReadBuffer || !dest || !env)
    return JNI_FALSE;
  void *destPtr = env->GetDirectBufferAddress(dest);
  if (!destPtr)
    return JNI_FALSE;
  jlong capacity = env->GetDirectBufferCapacity(dest);
  NSUInteger bufLen = g_depthReadBuffer.length;
  if (capacity < (jlong)bufLen)
    return JNI_FALSE;

  if (!g_currentFrameReady && g_depthCmdBuffer) {
    [g_depthCmdBuffer waitUntilCompleted];
  }
  if (g_depthCmdBuffer) {
    [g_depthCmdBuffer release];
    g_depthCmdBuffer = nil;
  }
  memcpy(destPtr, g_depthReadBuffer.contents, bufLen);
  return JNI_TRUE;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetEntityPipelineHandle(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return (jlong)(uintptr_t)(__bridge void *)g_pipelineEntity;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetEntityTranslucentPipelineHandle(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return (jlong)(uintptr_t)(__bridge void *)g_pipelineEntityTranslucent;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetEntityEmissivePipelineHandle(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return (jlong)(uintptr_t)(__bridge void *)g_pipelineEntityEmissive;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetParticlePipelineHandle(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return (jlong)(uintptr_t)(__bridge void *)g_pipelineParticle;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetEntityOverlay(
    JNIEnv *, jclass, jlong frameContext, jfloat hurtTime, jfloat whiteFlash,
    jfloat alpha) {
  (void)frameContext;
  (void)alpha;
  g_entityOverlayParams[0] = hurtTime;
  g_entityOverlayParams[1] = whiteFlash;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetWaterFog(
    JNIEnv *, jclass, jlong frameContext, jfloat waterFog) {
  (void)frameContext;
  g_entityOverlayParams[2] = waterFog;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetSkyBrightness(
    JNIEnv *, jclass, jlong frameContext, jfloat brightness) {
  (void)frameContext;
  g_skyBrightness = brightness;

  g_entityOverlayParams[3] = brightness;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetEntityTintColor(
    JNIEnv *, jclass, jlong frameContext, jfloat r, jfloat g, jfloat b,
    jfloat a) {
  (void)frameContext;
  g_entityTintColor[0] = r;
  g_entityTintColor[1] = g;
  g_entityTintColor[2] = b;
  g_entityTintColor[3] = a;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nBindEntityTexture(
    JNIEnv *, jclass, jlong frameContext, jlong textureHandle) {
  (void)frameContext;
  if (textureHandle == 0) {
    g_entityTexture = nil;
    if (g_frameCount < 5) {
      dbg("nBindEntityTexture: clearing entity texture\n");
    }
    return;
  }
  g_entityTexture = (__bridge id<MTLTexture>)(void *)(uintptr_t)textureHandle;
  if (g_frameCount < 5) {
    dbg("nBindEntityTexture: set g_entityTexture=%p (handle=%lld)\n",
        g_entityTexture, textureHandle);
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawEntityBuffer(
    JNIEnv *, jclass, jlong frameContext, jlong vertexBuffer, jint vertexCount,
    jint baseVertex, jint renderFlags) {
  (void)frameContext;
  if (!g_currentEncoder || vertexCount <= 0)
    return;
  ResolvedBuf vbRes = resolve_buffer((uint64_t)vertexBuffer);
  if (!vbRes.buf)
    return;
  id<MTLBuffer> vb = vbRes.buf;
  if (!(renderFlags & 0x8)) {
    id<MTLRenderPipelineState> pipeline = g_pipelineEntity;
    id<MTLDepthStencilState> depthSt =
        g_depthState ? g_depthState : g_depthStateNoWrite;
    if (renderFlags & 0x2) {
      pipeline = g_pipelineEntityEmissive ? g_pipelineEntityEmissive
                                          : g_pipelineEntity;
    } else if (renderFlags & 0x1) {
      pipeline = g_pipelineEntityTranslucent ? g_pipelineEntityTranslucent
                                             : g_pipelineEntity;
      depthSt = g_depthStateNoWrite ? g_depthStateNoWrite : g_depthState;
    }
    if (!pipeline) {
      pipeline = g_pipelineInhouse;
      if (!pipeline)
        return;
    }
    [g_currentEncoder setRenderPipelineState:pipeline];
    [g_currentEncoder setDepthStencilState:depthSt];
  }
  [g_currentEncoder setVertexBuffer:vb
                             offset:(NSUInteger)vbRes.offset
                            atIndex:0];
  [g_currentEncoder setFragmentBytes:g_entityOverlayParams
                              length:sizeof(g_entityOverlayParams)
                             atIndex:5];
  if (g_entityTexture) {
    [g_currentEncoder setFragmentTexture:g_entityTexture atIndex:0];
  } else {
    if (g_blockAtlas) {
      [g_currentEncoder setFragmentTexture:g_blockAtlas atIndex:0];
    }
  }
  if (g_lightmap) {
    [g_currentEncoder setFragmentTexture:g_lightmap atIndex:1];
  }

  [g_currentEncoder setCullMode:MTLCullModeNone];
  [g_currentEncoder drawPrimitives:MTLPrimitiveTypeTriangle
                       vertexStart:(NSUInteger)baseVertex
                       vertexCount:(NSUInteger)vertexCount];
  g_drawCallCount++;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawEntityBufferIndexed(
    JNIEnv *, jclass, jlong frameContext, jlong vertexBuffer, jlong indexBuffer,
    jint indexCount, jint baseIndex, jint renderFlags) {
  (void)frameContext;
  if (!g_currentEncoder || indexCount <= 0)
    return;
  ResolvedBuf vbRes = resolve_buffer((uint64_t)vertexBuffer);
  ResolvedBuf ibRes = resolve_buffer((uint64_t)indexBuffer);
  if (!vbRes.buf || !ibRes.buf)
    return;
  id<MTLBuffer> vb = vbRes.buf;
  id<MTLBuffer> ib = ibRes.buf;
  id<MTLRenderPipelineState> pipeline = g_pipelineEntity;
  id<MTLDepthStencilState> depthSt =
      g_depthState ? g_depthState : g_depthStateNoWrite;
  if (renderFlags & 0x2) {
    pipeline =
        g_pipelineEntityEmissive ? g_pipelineEntityEmissive : g_pipelineEntity;
  } else if (renderFlags & 0x1) {
    pipeline = g_pipelineEntityTranslucent ? g_pipelineEntityTranslucent
                                           : g_pipelineEntity;
    depthSt = g_depthStateNoWrite ? g_depthStateNoWrite : g_depthState;
  }
  if (!pipeline) {
    pipeline = g_pipelineInhouse;
    if (!pipeline)
      return;
  }
  [g_currentEncoder setRenderPipelineState:pipeline];
  [g_currentEncoder setDepthStencilState:depthSt];
  [g_currentEncoder setVertexBuffer:vb
                             offset:(NSUInteger)vbRes.offset
                            atIndex:0];
  [g_currentEncoder setFragmentBytes:g_entityOverlayParams
                              length:sizeof(g_entityOverlayParams)
                             atIndex:5];
  if (g_entityTexture) {
    [g_currentEncoder setFragmentTexture:g_entityTexture atIndex:0];
  } else if (g_blockAtlas) {
    [g_currentEncoder setFragmentTexture:g_blockAtlas atIndex:0];
  }
  if (g_lightmap) {
    [g_currentEncoder setFragmentTexture:g_lightmap atIndex:1];
  }

  [g_currentEncoder setCullMode:MTLCullModeNone];
  [g_currentEncoder
      drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                 indexCount:(NSUInteger)indexCount
                  indexType:MTLIndexTypeUInt32
                indexBuffer:ib
          indexBufferOffset:(NSUInteger)(ibRes.offset +
                                         (size_t)baseIndex * sizeof(uint32_t))];
  g_drawCallCount++;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nUploadCameraUniforms(
    JNIEnv *env, jclass, jlong handle, jfloatArray viewProj, jfloatArray proj,
    jfloatArray modelView, jfloatArray cameraPos, jfloatArray frustumPlanes,
    jfloat screenW, jfloat screenH, jfloat nearPlane, jfloat farPlane,
    jint totalChunks) {
  (void)handle;
  int bufIdx = g_currentBufferIndex % kTripleBufferCount;
  id<MTLBuffer> buf = g_tripleBuffers[bufIdx];
  if (!buf)
    return;
  CameraUniformsCPU *u = (CameraUniformsCPU *)[buf contents];
  if (viewProj && env->GetArrayLength(viewProj) >= 16)
    env->GetFloatArrayRegion(viewProj, 0, 16, u->viewProjection);
  if (proj && env->GetArrayLength(proj) >= 16)
    env->GetFloatArrayRegion(proj, 0, 16, u->projection);
  if (modelView && env->GetArrayLength(modelView) >= 16)
    env->GetFloatArrayRegion(modelView, 0, 16, u->modelView);
  if (cameraPos && env->GetArrayLength(cameraPos) >= 4)
    env->GetFloatArrayRegion(cameraPos, 0, 4, u->cameraPosition);
  if (frustumPlanes && env->GetArrayLength(frustumPlanes) >= 24)
    env->GetFloatArrayRegion(frustumPlanes, 0, 24, u->frustumPlanes);
  u->screenSize[0] = screenW;
  u->screenSize[1] = screenH;
  u->nearPlane = nearPlane;
  u->farPlane = farPlane;
  u->frameIndex = (uint32_t)g_frameCount;

  u->hizMipCount = g_useMemorylessTargets ? 0 : g_hizMipCount;
  u->totalChunks = (uint32_t)totalChunks;
  u->waterFog = g_entityOverlayParams[2];
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nUploadSubChunkData(
    JNIEnv *env, jclass, jlong handle, jobject directBuffer, jint count) {
  (void)handle;
  if (!g_device || !directBuffer || count <= 0)
    return;
  void *ptr = env->GetDirectBufferAddress(directBuffer);
  jlong cap = env->GetDirectBufferCapacity(directBuffer);
  if (!ptr)
    return;
  size_t entrySize = 48;
  size_t totalSize = (size_t)count * entrySize;
  if ((size_t)cap < totalSize)
    return;
  if (!g_subChunkBuffer || g_subChunkBuffer.length < totalSize) {
    if (g_subChunkBuffer)
      [g_subChunkBuffer release];
    g_subChunkBuffer =
        [g_device newBufferWithLength:totalSize
                              options:MTLResourceStorageModeShared];
  }
  memcpy([g_subChunkBuffer contents], ptr, totalSize);
  g_gpuSubChunkCount = (uint32_t)count;
  size_t argsSize = (size_t)count * sizeof(uint32_t) * 4;
  if (!g_cullDrawArgsBuffer || g_cullDrawArgsBuffer.length < argsSize) {
    if (g_cullDrawArgsBuffer)
      [g_cullDrawArgsBuffer release];
    g_cullDrawArgsBuffer =
        [g_device newBufferWithLength:argsSize
                              options:MTLResourceStorageModeShared];
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetGPUDrivenEnabled(
    JNIEnv *, jclass, jlong handle, jboolean enabled) {
  (void)handle;
  MetalFeatureCaps caps = current_feature_caps();
  g_gpuDrivenEnabled = (enabled == JNI_TRUE) && caps.indirectCommandBuffers;
  dbg("GPU-driven rendering: %s\n",
      g_gpuDrivenEnabled ? "enabled" : "disabled");
}
static int g_cullMode = 0;
extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nRunGPUCulling(
    JNIEnv *, jclass, jlong handle, jint chunkCount) {
  (void)handle;
  if (!g_device || chunkCount <= 0)
    return 0;
  if (!g_visibleIndicesBuffer || !g_cullDrawCountBuffer) {
    dbg("GPU Cull: buffers not allocated\n");
    return 0;
  }
  uint32_t count = (uint32_t)chunkCount;
  size_t neededSize = (size_t)count * sizeof(uint32_t);
  if (g_visibleIndicesBuffer.length < neededSize) {
    if (g_visibleIndicesBuffer)
      [g_visibleIndicesBuffer release];
    g_visibleIndicesBuffer =
        [g_device newBufferWithLength:neededSize
                              options:MTLResourceStorageModeShared];
  }
  if (g_cullMode == 1 && g_queue && g_cullEncodePipeline &&
      g_resetCullPipeline && g_subChunkBuffer && g_cullStatsBuffer) {
    int bufIdx = g_currentBufferIndex % kTripleBufferCount;
    id<MTLBuffer> cameraBuf = g_tripleBuffers[bufIdx];
    if (!cameraBuf) {
      g_cullMode = 0;
      goto cpu_path;
    }
    CameraUniformsCPU *cam = (CameraUniformsCPU *)[cameraBuf contents];
    cam->totalChunks = count;
    cam->hizMipCount = 0;
    id<MTLTexture> hizTex = g_hizPyramid;
    if (!hizTex) {
      if (!g_hizFallbackTexture) {
        MTLTextureDescriptor *desc = [MTLTextureDescriptor
            texture2DDescriptorWithPixelFormat:MTLPixelFormatR32Float
                                         width:1
                                        height:1
                                     mipmapped:NO];
        desc.usage = MTLTextureUsageShaderRead;
        desc.storageMode = MTLStorageModePrivate;
        g_hizFallbackTexture = [g_device newTextureWithDescriptor:desc];
      }
      hizTex = g_hizFallbackTexture;
    }
    @autoreleasepool {
      id<MTLCommandBuffer> cmdBuf = [g_queue commandBuffer];
      if (!cmdBuf)
        goto cpu_path;
      id<MTLComputeCommandEncoder> encoder = [cmdBuf computeCommandEncoder];
      if (!encoder)
        goto cpu_path;
      [encoder setComputePipelineState:g_resetCullPipeline];
      [encoder setBuffer:g_cullDrawCountBuffer offset:0 atIndex:0];
      [encoder setBuffer:g_cullStatsBuffer offset:0 atIndex:1];
      [encoder dispatchThreads:MTLSizeMake(1, 1, 1)
          threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
      [encoder setComputePipelineState:g_cullEncodePipeline];
      [encoder setBuffer:g_subChunkBuffer offset:0 atIndex:0];
      [encoder setBuffer:g_visibleIndicesBuffer offset:0 atIndex:1];
      [encoder setBuffer:g_cullDrawCountBuffer offset:0 atIndex:2];
      [encoder setBuffer:g_cullStatsBuffer offset:0 atIndex:3];
      [encoder setBuffer:cameraBuf offset:0 atIndex:4];
      [encoder setTexture:hizTex atIndex:0];
      NSUInteger threadCount = (NSUInteger)count;
      NSUInteger maxTG =
          (NSUInteger)g_cullEncodePipeline.maxTotalThreadsPerThreadgroup;
      NSUInteger tgSize =
          std::min(threadCount, std::min(maxTG, (NSUInteger)256));
      [encoder dispatchThreads:MTLSizeMake(threadCount, 1, 1)
          threadsPerThreadgroup:MTLSizeMake(tgSize, 1, 1)];
      [encoder endEncoding];
      [cmdBuf commit];
      [cmdBuf waitUntilCompleted];
    }
    uint32_t visibleCount = *(uint32_t *)[g_cullDrawCountBuffer contents];
    if (g_frameCount < 5 || (g_frameCount % 300 == 0)) {
      uint32_t *stats = (uint32_t *)[g_cullStatsBuffer contents];
      dbg("GPU Cull [compute]: input=%u visible=%u frustumCulled=%u "
          "distCulled=%u\n",
          count, visibleCount, stats[1], stats[3]);
    }
    return (jint)visibleCount;
  }
cpu_path: {
  uint32_t *indices = (uint32_t *)[g_visibleIndicesBuffer contents];
  for (uint32_t i = 0; i < count; i++) {
    indices[i] = i;
  }
  *(uint32_t *)[g_cullDrawCountBuffer contents] = count;
  if (g_frameCount < 5 || (g_frameCount % 300 == 0)) {
    dbg("GPU Cull [cpu-passthrough]: all %u chunks marked visible\n", count);
  }
  return (jint)count;
}
}
extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetGPUVisibleCount(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  if (!g_cullDrawCountBuffer)
    return 0;
  uint32_t *count = (uint32_t *)[g_cullDrawCountBuffer contents];
  return (jint)(*count);
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nExecuteIndirectDraws(
    JNIEnv *, jclass, jlong frameContext, jlong vertexBuffer,
    jlong indexBuffer) {
  (void)frameContext;
  (void)vertexBuffer;
  if (!g_currentEncoder || !g_visibleIndicesBuffer || !g_cullDrawCountBuffer ||
      !g_subChunkBuffer)
    return;
  uint32_t visibleCount = *(uint32_t *)[g_cullDrawCountBuffer contents];
  if (visibleCount == 0)
    return;
  visibleCount = std::min(visibleCount, g_maxGPUDrawCalls);
  if (!g_currentPipeline && g_pipelineInhouse) {
    [g_currentEncoder setRenderPipelineState:g_pipelineInhouse];
    g_currentPipeline = g_pipelineInhouse;
    if (g_depthState)
      [g_currentEncoder setDepthStencilState:g_depthState];
  }
  if (!g_currentPipeline)
    return;
  struct SubChunkCPU {
    float aabbMin[4];
    float aabbMax[4];
    uint32_t bufHandleHi;
    uint32_t bufHandleLo;
    uint32_t indexCount;
    uint32_t flags;
  };
  const uint32_t *visibleIndices =
      (const uint32_t *)[g_visibleIndicesBuffer contents];
  const SubChunkCPU *chunks = (const SubChunkCPU *)[g_subChunkBuffer contents];
  const float *chunkUniforms =
      g_chunkUniformsBuffer ? (const float *)[g_chunkUniformsBuffer contents]
                            : nullptr;
  ResolvedBuf ibRes = resolve_buffer((uint64_t)indexBuffer);
  id<MTLBuffer> lastVB = nil;
  size_t lastVBOffset = 0;
  for (uint32_t i = 0; i < visibleCount; i++) {
    uint32_t chunkIdx = visibleIndices[i];
    if (chunkIdx >= g_gpuSubChunkCount)
      continue;
    const SubChunkCPU &entry = chunks[chunkIdx];
    uint64_t bufHandle =
        ((uint64_t)entry.bufHandleHi << 32) | (uint64_t)entry.bufHandleLo;
    uint32_t idxCount = entry.indexCount;
    if (idxCount == 0)
      continue;
    ResolvedBuf vbRes = resolve_buffer(bufHandle);
    if (!vbRes.buf)
      continue;
    if (chunkUniforms) {
      [g_currentEncoder setVertexBytes:&chunkUniforms[chunkIdx * 4]
                                length:16
                               atIndex:4];
    }
    if (vbRes.buf != lastVB || vbRes.offset != lastVBOffset) {
      [g_currentEncoder setVertexBuffer:vbRes.buf
                                 offset:(NSUInteger)vbRes.offset
                                atIndex:0];
      lastVB = vbRes.buf;
      lastVBOffset = vbRes.offset;
    }
    if (ibRes.buf) {
      [g_currentEncoder drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                                   indexCount:(NSUInteger)idxCount
                                    indexType:MTLIndexTypeUInt32
                                  indexBuffer:ibRes.buf
                            indexBufferOffset:(NSUInteger)ibRes.offset];
    } else {
      [g_currentEncoder drawPrimitives:MTLPrimitiveTypeTriangle
                           vertexStart:0
                           vertexCount:(NSUInteger)idxCount];
    }
    g_drawCallCount++;
  }
}
extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetThermalState(
    JNIEnv *, jclass) {
  return (jint)g_thermalState;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetThermalLODReduction(
    JNIEnv *, jclass) {
  return (jint)g_lodRadiusReduction;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetRenderDistance(
    JNIEnv *, jclass, jint distanceBlocks) {

  if (distanceBlocks > 0)
    g_configuredRenderDistBlocks = (int)distanceBlocks;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetFeatureFlags(
    JNIEnv *, jclass, jboolean icb, jboolean meshShaders, jboolean argBuffers,
    jboolean progBlend, jboolean memoryless) {
  MetalFeatureCaps caps = current_feature_caps();
  bool prevMemoryless = g_useMemorylessTargets;
  bool requestedArgumentBuffers =
      ((argBuffers == JNI_TRUE) || (icb == JNI_TRUE)) && caps.argumentBuffers;
  g_gpuDrivenEnabled = (icb == JNI_TRUE) && caps.indirectCommandBuffers &&
                       requestedArgumentBuffers;
  g_meshShadersActive =
      (meshShaders == JNI_TRUE) && caps.meshShaders && g_meshPipelineCount > 0;
  g_useArgumentBuffers = requestedArgumentBuffers;
  g_useProgrammableBlending = (progBlend == JNI_TRUE);
  g_useMemorylessTargets = (memoryless == JNI_TRUE) && caps.memorylessTargets;

  if (prevMemoryless != g_useMemorylessTargets) {
    g_rtWidth = 0;
    g_rtHeight = 0;
  }
  dbg("nSetFeatureFlags: ICB=%d mesh=%d argBuf=%d OIT=%d memoryless=%d\n",
      g_gpuDrivenEnabled ? 1 : 0, g_meshShadersActive ? 1 : 0,
      g_useArgumentBuffers ? 1 : 0, g_useProgrammableBlending ? 1 : 0,
      g_useMemorylessTargets ? 1 : 0);
  NSLog(@"[MetalRender] FEATURES SET — ICB(GPU-driven)=%@ | MeshShaders=%@ | "
        @"ArgumentBuffers=%@ | OIT/ProgBlend=%@ | Memoryless=%@",
        g_gpuDrivenEnabled ? @"ON" : @"OFF",
        g_meshShadersActive ? @"ON" : @"OFF",
        g_useArgumentBuffers ? @"ON" : @"OFF",
        g_useProgrammableBlending ? @"ON" : @"OFF",
        g_useMemorylessTargets ? @"ON" : @"OFF");
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawDeferredWaterPass(
    JNIEnv *, jclass, jlong frameContext) {
  (void)frameContext;
  if (g_useProgrammableBlending)
    return;
  if (!g_currentEncoder || !g_pipelineInhouse || !g_depthStateNoWrite)
    return;
  if (g_thermalQualityLevel >= 2)
    return;
  if (!g_deferredWaterCmds || g_deferredWaterCmdCount <= 0)
    return;
  if (!g_deferredWaterIB || !g_deferredWaterOffsetBuf)
    return;

  @autoreleasepool {
    static const int VERTEX_STRIDE = 16;

    id<MTLRenderPipelineState> waterPipeline =
        g_pipelineWater ? g_pipelineWater : g_pipelineInhouse;
    [g_currentEncoder setRenderPipelineState:waterPipeline];
    g_currentPipeline = waterPipeline;
    if (g_blockAtlas)
      [g_currentEncoder setFragmentTexture:g_blockAtlas atIndex:0];
    if (g_megaVB)
      [g_currentEncoder setVertexBuffer:g_megaVB offset:0 atIndex:0];
    [g_currentEncoder setVertexBytes:g_projMatrix length:64 atIndex:1];
    [g_currentEncoder setVertexBytes:g_mvMatrix length:64 atIndex:2];
    float camPos[4] = {0.0f, 0.0f, 0.0f, g_skyBrightness};
    [g_currentEncoder setVertexBytes:camPos length:16 atIndex:3];
    [g_currentEncoder setVertexBuffer:g_deferredWaterOffsetBuf
                               offset:0
                              atIndex:4];
    [g_currentEncoder setFragmentBytes:g_entityOverlayParams
                                length:sizeof(g_entityOverlayParams)
                               atIndex:5];
    [g_currentEncoder setDepthStencilState:g_depthStateNoWrite];
    [g_currentEncoder setCullMode:MTLCullModeNone];
    [g_currentEncoder setDepthBias:0.0f slopeScale:0.0f clamp:0.0f];

    int waterDraws = 0;
    for (int i = 0; i < g_deferredWaterCmdCount; i++) {
      const DeferredWaterCmd &cmd = g_deferredWaterCmds[i];
      int waterIdxCount = cmd.idxCount - cmd.opaqueIdxCount;
      if (waterIdxCount <= 0)
        continue;
      int opaqueVertCount = cmd.opaqueIdxCount / 6 * 4;
      if (cmd.isMega) {
        [g_currentEncoder
            drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                       indexCount:(NSUInteger)waterIdxCount
                        indexType:MTLIndexTypeUInt32
                      indexBuffer:g_deferredWaterIB
                indexBufferOffset:g_deferredWaterIBOffset
                    instanceCount:1
                       baseVertex:(NSInteger)(cmd.megaOffset / VERTEX_STRIDE) +
                                  opaqueVertCount
                     baseInstance:(NSUInteger)cmd.instanceIdx];
      } else if (cmd.resolvedBuf) {
        [g_currentEncoder setVertexBuffer:cmd.resolvedBuf offset:0 atIndex:0];
        [g_currentEncoder drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                                     indexCount:(NSUInteger)waterIdxCount
                                      indexType:MTLIndexTypeUInt32
                                    indexBuffer:g_deferredWaterIB
                              indexBufferOffset:g_deferredWaterIBOffset
                                  instanceCount:1
                                     baseVertex:opaqueVertCount
                                   baseInstance:(NSUInteger)cmd.instanceIdx];
        if (g_megaVB)
          [g_currentEncoder setVertexBuffer:g_megaVB offset:0 atIndex:0];
      }
      waterDraws++;
      g_drawCallCount++;
    }

    if (g_depthState)
      [g_currentEncoder setDepthStencilState:g_depthState];
    [g_currentEncoder setDepthBias:0.0f slopeScale:0.0f clamp:0.0f];
    [g_currentEncoder setCullMode:MTLCullModeBack];
    if (g_frameCount < 5 || g_frameCount % 600 == 0) {
      dbg("Deferred water pass: drew %d translucent chunk draws\n", waterDraws);
    }
    g_deferredWaterCmdCount = 0;
    g_deferredWaterIB = nil;
    g_deferredWaterIBOffset = 0;
    g_deferredWaterOffsetBuf = nil;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawOITPass(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  if (!g_useProgrammableBlending)
    return;
  if (!g_device || !g_currentCmdBuffer || !g_color)
    return;
  if (!g_oitAccumTex || !g_oitRevealTex)
    return;
  if (!g_pipelineOITAccum || !g_pipelineOITComposite)
    return;
  if (g_oitCmdsCount <= 0)
    return;

  @autoreleasepool {

    if (g_currentEncoder) {
      [g_currentEncoder endEncoding];
      [g_currentEncoder release];
      g_currentEncoder = nil;
    }

    {
      MTLRenderPassDescriptor *rp =
          [MTLRenderPassDescriptor renderPassDescriptor];

      rp.colorAttachments[0].texture = g_oitAccumTex;
      rp.colorAttachments[0].loadAction = MTLLoadActionClear;
      rp.colorAttachments[0].storeAction = MTLStoreActionStore;
      rp.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 0);

      rp.colorAttachments[1].texture = g_oitRevealTex;
      rp.colorAttachments[1].loadAction = MTLLoadActionClear;
      rp.colorAttachments[1].storeAction = MTLStoreActionStore;
      rp.colorAttachments[1].clearColor = MTLClearColorMake(1, 0, 0, 0);

      if (!g_useMemorylessTargets && g_depth) {
        rp.depthAttachment.texture = g_depth;
        rp.depthAttachment.loadAction = MTLLoadActionLoad;
        rp.depthAttachment.storeAction = MTLStoreActionDontCare;
      }
      id<MTLRenderCommandEncoder> enc =
          [g_currentCmdBuffer renderCommandEncoderWithDescriptor:rp];
      if (enc) {
        [enc setRenderPipelineState:g_pipelineOITAccum];
        if (!g_useMemorylessTargets && g_depthStateNoWrite)
          [enc setDepthStencilState:g_depthStateNoWrite];
        [enc setCullMode:MTLCullModeNone];
        MTLViewport vp;
        vp.originX = 0;
        vp.originY = 0;
        vp.width = (double)g_oitAccumTex.width;
        vp.height = (double)g_oitAccumTex.height;
        vp.znear = 0.0;
        vp.zfar = 1.0;
        [enc setViewport:vp];

        [enc setVertexBytes:g_projMatrix length:64 atIndex:1];
        [enc setVertexBytes:g_mvMatrix length:64 atIndex:2];
        float camPos[4] = {(float)g_camX, (float)g_camY, (float)g_camZ,
                           g_skyBrightness};
        [enc setVertexBytes:camPos length:16 atIndex:3];
        if (g_blockAtlas)
          [enc setFragmentTexture:g_blockAtlas atIndex:0];
        if (g_megaVB)
          [enc setVertexBuffer:g_megaVB offset:0 atIndex:0];
        if (g_oitOffsetBuf)
          [enc setVertexBuffer:g_oitOffsetBuf offset:0 atIndex:4];
        static const int VERTEX_STRIDE = 16;
        for (int i = 0; i < g_oitCmdsCount; i++) {
          const OITCachedCmd &c = g_oitCmds[i];
          if (c.translucentIdxCount <= 0)
            continue;
          if (c.isMega && g_megaVB) {
            [enc drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                            indexCount:(NSUInteger)c.translucentIdxCount
                             indexType:MTLIndexTypeUInt32
                           indexBuffer:g_oitIB
                     indexBufferOffset:g_oitIBOffset
                         instanceCount:1
                            baseVertex:(NSInteger)(c.megaOffset /
                                                   VERTEX_STRIDE) +
                                       c.opaqueVertCount
                          baseInstance:(NSUInteger)c.instanceIdx];
          } else if (c.resolvedBuf) {
            [enc setVertexBuffer:c.resolvedBuf offset:0 atIndex:0];
            [enc drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                            indexCount:(NSUInteger)c.translucentIdxCount
                             indexType:MTLIndexTypeUInt32
                           indexBuffer:g_oitIB
                     indexBufferOffset:g_oitIBOffset
                         instanceCount:1
                            baseVertex:c.opaqueVertCount
                          baseInstance:(NSUInteger)c.instanceIdx];
            if (g_megaVB)
              [enc setVertexBuffer:g_megaVB offset:0 atIndex:0];
          }
          g_drawCallCount++;
        }
        [enc endEncoding];
      }
    }

    {
      MTLRenderPassDescriptor *cRp =
          [MTLRenderPassDescriptor renderPassDescriptor];
      cRp.colorAttachments[0].texture = g_color;
      cRp.colorAttachments[0].loadAction = MTLLoadActionLoad;
      cRp.colorAttachments[0].storeAction = MTLStoreActionStore;
      id<MTLRenderCommandEncoder> cEnc =
          [g_currentCmdBuffer renderCommandEncoderWithDescriptor:cRp];
      if (cEnc) {
        [cEnc setRenderPipelineState:g_pipelineOITComposite];
        [cEnc setFragmentTexture:g_oitAccumTex atIndex:0];
        [cEnc setFragmentTexture:g_oitRevealTex atIndex:1];

        [cEnc drawPrimitives:MTLPrimitiveTypeTriangle
                 vertexStart:0
                 vertexCount:3];
        g_drawCallCount++;
        [cEnc endEncoding];
      }
    }

    {
      static MTLRenderPassDescriptor *s_resumeRP = nil;
      if (!s_resumeRP) {
        s_resumeRP = [[MTLRenderPassDescriptor renderPassDescriptor] retain];
        s_resumeRP.colorAttachments[0].storeAction = MTLStoreActionStore;
        s_resumeRP.depthAttachment.clearDepth = 1.0;
      }
      s_resumeRP.colorAttachments[0].texture = g_color;
      s_resumeRP.colorAttachments[0].loadAction = MTLLoadActionLoad;

      if (!g_useMemorylessTargets && g_depth) {
        s_resumeRP.depthAttachment.texture = g_depth;
        s_resumeRP.depthAttachment.loadAction = MTLLoadActionLoad;
        s_resumeRP.depthAttachment.storeAction = MTLStoreActionStore;
      } else {
        s_resumeRP.depthAttachment.texture = nil;
        s_resumeRP.depthAttachment.loadAction = MTLLoadActionDontCare;
        s_resumeRP.depthAttachment.storeAction = MTLStoreActionDontCare;
      }
      g_currentEncoder = [[g_currentCmdBuffer
          renderCommandEncoderWithDescriptor:s_resumeRP] retain];
      if (g_currentEncoder) {
        MTLViewport vp;
        vp.originX = 0;
        vp.originY = 0;
        vp.width = (double)g_color.width;
        vp.height = (double)g_color.height;
        vp.znear = 0.0;
        vp.zfar = 1.0;
        [g_currentEncoder setViewport:vp];
        [g_currentEncoder setVertexBytes:g_projMatrix length:64 atIndex:1];
        [g_currentEncoder setVertexBytes:g_mvMatrix length:64 atIndex:2];
        float camPos[4] = {(float)g_camX, (float)g_camY, (float)g_camZ,
                           g_skyBrightness};
        [g_currentEncoder setVertexBytes:camPos length:16 atIndex:3];
        float chunkOff[4] = {0, 0, 0, 0};
        [g_currentEncoder setVertexBytes:chunkOff length:16 atIndex:4];
        [g_currentEncoder setFrontFacingWinding:MTLWindingCounterClockwise];
        [g_currentEncoder setCullMode:MTLCullModeBack];
        if (g_blockAtlas)
          [g_currentEncoder setFragmentTexture:g_blockAtlas atIndex:0];
        g_currentPipeline = nil;
      }
    }
    dbg("OIT pass: drew %d translucent cmds\n", g_oitCmdsCount);
    g_oitCmdsCount = 0;
  }
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetAvailableMemory(
    JNIEnv *, jclass) {
  mach_port_t host = mach_host_self();
  vm_size_t pageSize;
  host_page_size(host, &pageSize);
  vm_statistics64_data_t vmStats;
  mach_msg_type_number_t count = HOST_VM_INFO64_COUNT;
  if (host_statistics64(host, HOST_VM_INFO64, (host_info64_t)&vmStats,
                        &count) == KERN_SUCCESS) {
    uint64_t freePages = vmStats.free_count + vmStats.inactive_count;
    return (jlong)(freePages * pageSize);
  }

  return (jlong)(MEGA_VB_CAPACITY);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetHiZMipCount(
    JNIEnv *, jclass) {
  return (jint)g_hizMipCount;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetGPUCullStats(
    JNIEnv *env, jclass, jintArray outStats) {
  if (!outStats || !g_cullStatsBuffer)
    return;
  if (env->GetArrayLength(outStats) < 5)
    return;
  uint32_t *stats = (uint32_t *)[g_cullStatsBuffer contents];
  jint jstats[5] = {(jint)stats[0], (jint)stats[1], (jint)stats[2],
                    (jint)stats[3], (jint)stats[4]};
  env->SetIntArrayRegion(outStats, 0, 5, jstats);
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nUploadChunkUniforms(
    JNIEnv *env, jclass, jlong handle, jobject directBuffer, jint count) {
  (void)handle;
  if (!g_device || !directBuffer || count <= 0)
    return;
  void *ptr = env->GetDirectBufferAddress(directBuffer);
  jlong cap = env->GetDirectBufferCapacity(directBuffer);
  if (!ptr)
    return;
  size_t entrySize = 16;
  size_t totalSize = (size_t)count * entrySize;
  if ((size_t)cap < totalSize)
    return;
  if (!g_chunkUniformsBuffer || g_chunkUniformsBuffer.length < totalSize) {
    if (g_chunkUniformsBuffer)
      [g_chunkUniformsBuffer release];
    g_chunkUniformsBuffer =
        [g_device newBufferWithLength:totalSize
                              options:MTLResourceStorageModeShared];
  }
  memcpy([g_chunkUniformsBuffer contents], ptr, totalSize);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nAreMeshShadersActive(
    JNIEnv *, jclass) {
  return g_meshShadersActive ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nIsGPUDrivenActive(
    JNIEnv *, jclass) {
  return g_gpuDrivenEnabled ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nAreArgumentBuffersActive(
    JNIEnv *, jclass) {
  return g_useArgumentBuffers ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nAreMemorylessTargetsActive(
    JNIEnv *, jclass) {
  return g_useMemorylessTargets ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nIsMegaBufferActive(
    JNIEnv *, jclass) {
  return g_megaVB ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nFlushDeferredDeletions(
    JNIEnv *, jclass) {
  @autoreleasepool {
    int freed = 0;
    {
      std::lock_guard<std::mutex> dLock(g_deferredMutex);
      for (auto &dd : g_deferredDeletions) {
        if (dd.isMega) {
          megaFree(dd.handle);
        } else {
          std::unique_lock<std::shared_mutex> bufLock(g_bufferMutex);
          auto it = g_buffers.find(dd.handle);
          if (it != g_buffers.end()) {
            [it->second release];
            g_buffers.erase(it);
          }
        }
        freed++;
      }
      g_deferredDeletions.clear();
    }

    {
      std::unique_lock<std::shared_mutex> mLock(g_megaMutex);
      megaCoalesceFreeList();
    }
    dbg("nFlushDeferredDeletions: freed %d buffers, mega free list coalesced\n",
        freed);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nClearAllChunkRegistrations(
    JNIEnv *, jclass) {
  std::unique_lock<std::shared_mutex> lock(g_meshRegMutex);

  for (size_t i = 0; i < g_nativeMeshes.size(); i++) {
    g_nativeMeshes[i].active = false;
  }

  g_meshFreeSlots.clear();
  for (size_t i = 0; i < g_nativeMeshes.size(); i++) {
    g_meshFreeSlots.push_back(i);
  }

  g_meshKeyToIdx.clear();

  g_hasStaleDrawList = false;
  g_staleDrawCount = 0;
  g_staleMegaCount = 0;
  g_activeMeshIndices.clear();
  g_activeMeshCount = 0;
  dbg("nClearAllChunkRegistrations: cleared all %zu mesh slots\n",
      g_nativeMeshes.size());
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nFlushFrames(
    JNIEnv *, jclass) {
  @autoreleasepool {
    dbg("nFlushFrames: draining all in-flight GPU frames\n");

    for (int i = 0; i < kTripleBufferCount; i++) {
      if (g_tbCmdBuf[i]) {
        [g_tbCmdBuf[i] waitUntilCompleted];
      }
    }

    if (g_frameSemaphore) {
      for (int i = 0; i < kTripleBufferCount; i++)
        dispatch_semaphore_signal(g_frameSemaphore);
      dispatch_release(g_frameSemaphore);
    }
    g_frameSemaphore = dispatch_semaphore_create(kTripleBufferCount - 1);
    dbg("nFlushFrames: semaphore recreated (count=%d)\n",
        kTripleBufferCount - 1);

    for (int i = 0; i < kTripleBufferCount; i++) {
      g_tbSlotReady[i].store(true, std::memory_order_release);
    }
    g_currentFrameReady = true;

    g_gpuNeedsRecovery.store(false, std::memory_order_release);
    dbg("nFlushFrames: complete — semaphore clean, all slots ready\n");
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nWatchdogReset(
    JNIEnv *, jclass) {
  @autoreleasepool {
    dbg("WATCHDOG: Reset triggered!\n");

    if (g_frameSemaphore) {
      dispatch_semaphore_signal(g_frameSemaphore);
    }

    g_currentFrameReady = true;

    for (int i = 0; i < 3; i++) {
      g_tbSlotReady[i].store(true, std::memory_order_release);
    }
    dbg("WATCHDOG: Semaphore signalled, frame marked ready\n");
  }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nMegaDefragment(
    JNIEnv *, jclass) {
  @autoreleasepool {
    std::unique_lock<std::shared_mutex> lock(g_megaMutex);
    int beforeBlocks = (int)g_megaFreeList.size();
    megaCoalesceFreeList();
    int afterBlocks = (int)g_megaFreeList.size();
    if (beforeBlocks != afterBlocks) {
      dbg("MEGA_DEFRAG: %d -> %d free blocks\n", beforeBlocks, afterBlocks);
    }
    return (jint)afterBlocks;
  }
}