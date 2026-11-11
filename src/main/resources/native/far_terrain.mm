#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <jni.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <map>
#include <mutex>
#include <unordered_map>
#include <vector>
#include "far_terrain.h"

namespace {

constexpr uint32_t kRanges = 21;
constexpr uint32_t kLists = 6;
constexpr uint32_t kOffsets = 34 * 34 + 1;
constexpr uint32_t kMaskSize = 128;

struct TileRec {
  int32_t ox, oz, minY;
  uint32_t level, blob, tint, quadBase, quadCap;
  int32_t yLo, yHi;
  uint32_t flags, used;
  uint32_t counts[21];
  uint32_t starts[21];
  uint32_t lookPal;
  uint32_t plane;
};
static_assert(sizeof(TileRec) == 224, "far tile record");

struct CullParams {
  int32_t nearBox[4];
  float farRadius;
  uint32_t listCap;
  uint32_t tileCount;
  uint32_t reverseZ;
};

struct DrawParams {
  int32_t camBlock[4];
  float camFrac[4];
  float depthPush;
  uint32_t reverseZ;
  uint32_t pad0, pad1;
};

struct CullFrame {
  int32_t camBlock[4];
  float camFrac[4];
  float planes[4][4];
  float viewDist;
  uint32_t sectionCount;
  uint32_t listCapacity;
  uint32_t pad;
  float prevViewProj[16];
  float prevOffset[4];
  uint32_t hizSize[2];
  uint32_t hizMips;
  uint32_t hizFlags;
};

struct FogRecon {
  float r[24];
};

class RangeAlloc {
public:
  void init(uint32_t cap) {
    capacity = cap;
    freeTotal = cap;
    byOff.clear();
    if (cap)
      byOff[0] = cap;
  }
  bool alloc(uint32_t n, uint32_t &off) {
    if (n == 0) {
      off = 0;
      return true;
    }
    for (auto it = byOff.begin(); it != byOff.end(); ++it) {
      if (it->second >= n) {
        off = it->first;
        uint32_t rest = it->second - n;
        byOff.erase(it);
        if (rest)
          byOff[off + n] = rest;
        freeTotal -= n;
        return true;
      }
    }
    return false;
  }
  void release(uint32_t off, uint32_t n) {
    if (n == 0)
      return;
    freeTotal += n;
    auto next = byOff.lower_bound(off);
    if (next != byOff.begin()) {
      auto prev = std::prev(next);
      if (prev->first + prev->second == off) {
        off = prev->first;
        n += prev->second;
        byOff.erase(prev);
      }
    }
    next = byOff.lower_bound(off);
    if (next != byOff.end() && off + n == next->first) {
      n += next->second;
      byOff.erase(next);
    }
    byOff[off] = n;
  }
  uint32_t capacity = 0;
  uint64_t freeTotal = 0;
  std::map<uint32_t, uint32_t> byOff;
};

enum TileState : uint8_t { kPending, kBuilding, kBuilt };

struct Tile {
  int64_t key = 0;
  uint32_t version = 0;
  uint32_t slot = 0;
  uint32_t blobOff = 0, blobLen = 0;
  uint32_t quadOff = 0, quadCap = 0;
  uint32_t quadRaw = 0, quadRawLen = 0, stride = 3;
  uint32_t tintOff = 0, tintLen = 0;
  uint32_t used = 0;
  TileState state = kPending;
  bool doomed = false;
  bool live = false;
  uint64_t retireTag = 0;
};

struct Retired {
  Tile tile;
  uint64_t tag;
};

std::mutex g_mutex;
bool g_ready = false;
bool g_failed = false;
id<MTLDevice> g_dev = nil;
id<MTLComputePipelineState> g_build = nil, g_cullReset = nil, g_cull = nil, g_cullFinish = nil;
id<MTLRenderPipelineState> g_pso[6] = {nil, nil, nil, nil, nil, nil};
id<MTLBuffer> g_tiles = nil, g_blobs = nil, g_quads = nil, g_tints = nil, g_looks = nil, g_mask = nil;
id<MTLBuffer> g_live[2] = {nil, nil};
id<MTLBuffer> g_lists[2] = {nil, nil};
id<MTLBuffer> g_args[2] = {nil, nil};
uint32_t g_listCap[2] = {0, 0};
id<MTLBuffer> g_buildList[2] = {nil, nil};
RangeAlloc g_blobAlloc, g_quadAlloc, g_tintAlloc;
std::vector<uint32_t> g_freeSlots;
uint32_t g_slotHigh = 0;
uint32_t g_slotCap = 0;
std::unordered_map<int64_t, std::vector<Tile>> g_pending;
std::unordered_map<int64_t, Tile> g_liveTiles;
std::vector<Tile> g_building;
std::vector<Retired> g_retired;
std::vector<id> g_retiredObjects;
std::vector<uint64_t> g_retiredObjectTags;
std::vector<int64_t> g_builtReady;
std::vector<uint32_t> g_liveSlots;
bool g_liveDirty = true;
uint32_t g_liveCount[2] = {0, 0};
uint32_t g_version = 0;
uint64_t g_liveQuads = 0;
int g_drawSlot = -1;
int g_waterSlot = -1;
float g_farRadius = 0.0f;
int32_t g_nearBox[4] = {1, 1, 0, 0};
bool g_cullDirty = true;
int g_cullSlot = 0;
int g_buildFlip = 0;
uint8_t g_lastCull[sizeof(CullFrame) + sizeof(CullParams)];
uint32_t g_buildsPerFrame = 48;
std::atomic<long long> g_drawnQuads{0};
std::atomic<long long> g_drawnTiles{0};
std::atomic<long long> g_cullUs{0};
id<MTLBuffer> g_statsRead[2] = {nil, nil};
struct BuildBatch {
  std::vector<Tile> tiles;
  std::atomic<bool> done{false};
};
std::vector<BuildBatch *> g_batches;
uint32_t g_lookCount = 0;
float g_depthPush = 2.0e-5f;
uint32_t g_opts = 0;
id<MTLComputePipelineState> g_spriteAvgPso = nil;
id<MTLBuffer> g_spriteAvg = nil;
id g_spriteSrc = nil;
id g_atlasSrc = nil;
uint64_t g_spriteFrame = 0;

void retire_object(id obj) {
  if (!obj)
    return;
  g_retiredObjects.push_back(obj);
  g_retiredObjectTags.push_back(mr_host_frame_in_progress());
}

void free_tile_now(const Tile &t) {
  if (t.blobLen)
    g_blobAlloc.release(t.blobOff, t.blobLen);
  g_quadAlloc.release(t.quadRaw, t.quadRawLen);
  g_tintAlloc.release(t.tintOff, t.tintLen);
  TileRec *recs = (TileRec *)[g_tiles contents];
  memset(&recs[t.slot], 0, sizeof(TileRec));
  g_freeSlots.push_back(t.slot);
}

void retire_tile(const Tile &t) {
  Retired r;
  r.tile = t;
  r.tag = mr_host_frame_in_progress();
  g_retired.push_back(r);
}

void collect() {
  uint64_t done = mr_host_frame_completed();
  size_t w = 0;
  for (size_t i = 0; i < g_retired.size(); i++) {
    if (g_retired[i].tag > done) {
      g_retired[w++] = g_retired[i];
      continue;
    }
    free_tile_now(g_retired[i].tile);
  }
  g_retired.resize(w);
  w = 0;
  for (size_t i = 0; i < g_retiredObjects.size(); i++) {
    if (g_retiredObjectTags[i] > done) {
      g_retiredObjects[w] = g_retiredObjects[i];
      g_retiredObjectTags[w] = g_retiredObjectTags[i];
      w++;
      continue;
    }
    [g_retiredObjects[i] release];
  }
  g_retiredObjects.resize(w);
  g_retiredObjectTags.resize(w);
}

void make_live(Tile t) {
  auto it = g_liveTiles.find(t.key);
  if (it != g_liveTiles.end()) {
    g_liveQuads -= it->second.used;
    retire_tile(it->second);
  }
  t.live = true;
  g_liveQuads += t.used;
  g_liveTiles[t.key] = t;
  g_liveDirty = true;
}

void finish_batches() {
  size_t w = 0;
  for (size_t i = 0; i < g_batches.size(); i++) {
    BuildBatch *b = g_batches[i];
    if (!b->done.load(std::memory_order_acquire)) {
      g_batches[w++] = b;
      continue;
    }
    TileRec *recs = (TileRec *)[g_tiles contents];
    for (Tile &t : b->tiles) {
      uint32_t used = std::min(recs[t.slot].used, t.quadCap);
      uint32_t keep = (used + 31u) & ~31u;
      keep = std::min(keep, t.quadCap);
      if (keep < t.quadCap) {
        uint32_t newLen = (t.quadOff * t.stride - t.quadRaw) + keep * t.stride;
        if (newLen < t.quadRawLen) {
          g_quadAlloc.release(t.quadRaw + newLen, t.quadRawLen - newLen);
          t.quadRawLen = newLen;
        }
        t.quadCap = keep;
        recs[t.slot].quadCap = keep;
      }
      t.used = used;
      t.state = kBuilt;
      if (t.blobLen) {
        g_blobAlloc.release(t.blobOff, t.blobLen);
        t.blobLen = 0;
      }
      if (t.doomed) {
        retire_tile(t);
        continue;
      }
      auto pit = g_pending.find(t.key);
      bool superseded = false;
      if (pit != g_pending.end()) {
        for (Tile &o : pit->second)
          if (o.version > t.version)
            superseded = true;
      }
      if (superseded) {
        retire_tile(t);
        continue;
      }
      if (g_liveTiles.count(t.key)) {
        make_live(t);
      } else {
        auto &vec = g_pending[t.key];
        bool replaced = false;
        for (Tile &o : vec) {
          if (o.state == kBuilt) {
            retire_tile(o);
            o = t;
            replaced = true;
          }
        }
        if (!replaced)
          vec.push_back(t);
        g_builtReady.push_back(t.key);
      }
    }
    delete b;
  }
  g_batches.resize(w);
}

bool ensure_pipelines() {
  if (g_ready)
    return true;
  if (g_failed)
    return false;
  id<MTLLibrary> lib = mr_host_library();
  g_dev = mr_host_device();
  if (!lib || !g_dev) {
    return false;
  }
  NSError *err = nil;
  NSString *kernels[5] = {@"far_build", @"far_cull_reset", @"far_cull", @"far_cull_finish", @"far_sprite_avg"};
  id<MTLComputePipelineState> *targets[5] = {&g_build, &g_cullReset, &g_cull, &g_cullFinish, &g_spriteAvgPso};
  for (int i = 0; i < 5; i++) {
    id<MTLFunction> fn = [lib newFunctionWithName:kernels[i]];
    if (!fn) {
      NSLog(@"metalrender far: missing kernel %@", kernels[i]);
      g_failed = true;
      return false;
    }
    *targets[i] = [g_dev newComputePipelineStateWithFunction:fn error:&err];
    [fn release];
    if (!*targets[i]) {
      NSLog(@"metalrender far: kernel %@ failed %@", kernels[i], err);
      g_failed = true;
      return false;
    }
  }
  id<MTLFunction> vs = [lib newFunctionWithName:@"far_vs"];
  NSString *frags[3] = {@"far_fs_solid", @"far_fs_cutout", @"far_fs_water"};
  for (int m = 0; m < 2; m++) {
    MTLFunctionConstantValues *cv = [[MTLFunctionConstantValues alloc] init];
    bool masked = m == 1;
    [cv setConstantValue:&masked type:MTLDataTypeBool atIndex:0];
    for (int k = 0; k < 3; k++) {
      id<MTLFunction> fs = [lib newFunctionWithName:frags[k] constantValues:cv error:&err];
      if (!vs || !fs) {
        NSLog(@"metalrender far: missing render function %@ %@", frags[k], err);
        [cv release];
        g_failed = true;
        return false;
      }
      MTLRenderPipelineDescriptor *d = [[MTLRenderPipelineDescriptor alloc] init];
      d.vertexFunction = vs;
      d.fragmentFunction = fs;
      MTLRenderPipelineColorAttachmentDescriptor *ca = d.colorAttachments[0];
      ca.pixelFormat = MTLPixelFormatBGRA8Unorm;
      if (k == 2) {
        ca.blendingEnabled = YES;
        ca.rgbBlendOperation = MTLBlendOperationAdd;
        ca.alphaBlendOperation = MTLBlendOperationAdd;
        ca.sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
        ca.destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
        ca.sourceAlphaBlendFactor = MTLBlendFactorOne;
        ca.destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
      } else {
        ca.blendingEnabled = NO;
      }
      d.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
      d.label = @"FarTerrain";
      g_pso[m * 3 + k] = mr_host_make_pipeline(d, &err);
      [d release];
      [fs release];
      if (!g_pso[m * 3 + k]) {
        NSLog(@"metalrender far: pipeline failed %@", err);
        [cv release];
        g_failed = true;
        return false;
      }
    }
    [cv release];
  }
  [vs release];
  return true;
}

bool alloc_buffers(uint64_t budgetBytes) {
  uint64_t quadBytes = budgetBytes * 80 / 100;
  uint64_t blobBytes = budgetBytes * 14 / 100;
  uint64_t tintBytes = std::max<uint64_t>(budgetBytes * 6 / 100, 4u << 20);
  g_slotCap = 65536;
  g_tiles = [g_dev newBufferWithLength:(NSUInteger)g_slotCap * sizeof(TileRec) options:MTLResourceStorageModeShared];
  g_blobs = [g_dev newBufferWithLength:(NSUInteger)blobBytes options:MTLResourceStorageModeShared];
  g_quads = [g_dev newBufferWithLength:(NSUInteger)quadBytes options:MTLResourceStorageModePrivate];
  g_tints = [g_dev newBufferWithLength:(NSUInteger)tintBytes options:MTLResourceStorageModeShared];
  g_mask = [g_dev newBufferWithLength:kMaskSize * kMaskSize options:MTLResourceStorageModeShared];
  g_looks = [g_dev newBufferWithLength:26 * 4 * 16 options:MTLResourceStorageModeShared];
  for (int i = 0; i < 2; i++) {
    g_live[i] = [g_dev newBufferWithLength:g_slotCap * 4 options:MTLResourceStorageModeShared];
    g_args[i] = [g_dev newBufferWithLength:256 options:MTLResourceStorageModeShared];
    g_buildList[i] = [g_dev newBufferWithLength:4096 * 4 options:MTLResourceStorageModeShared];
    g_listCap[i] = 16384;
    g_lists[i] = [g_dev newBufferWithLength:(NSUInteger)g_listCap[i] * 8 * kLists options:MTLResourceStorageModePrivate];
  }
  if (!g_tiles || !g_blobs || !g_quads || !g_tints || !g_mask || !g_looks || !g_live[0] || !g_live[1] || !g_args[0] ||
      !g_args[1] || !g_lists[0] || !g_lists[1] || !g_buildList[0] || !g_buildList[1])
    return false;
  memset([g_tiles contents], 0, g_tiles.length);
  memset([g_mask contents], 0, g_mask.length);
  memset([g_looks contents], 0, g_looks.length);
  g_blobAlloc.init((uint32_t)(blobBytes / 4));
  g_quadAlloc.init((uint32_t)std::min<uint64_t>(quadBytes / 4, 0xFFFFFFF0ull));
  g_tintAlloc.init((uint32_t)(tintBytes / 4));
  g_slotHigh = 0;
  g_freeSlots.clear();
  return true;
}

void clear_all_locked() {
  for (auto &e : g_liveTiles)
    retire_tile(e.second);
  g_liveTiles.clear();
  for (auto &e : g_pending)
    for (Tile &t : e.second) {
      if (t.state == kBuilding)
        t.doomed = true;
      else
        retire_tile(t);
    }
  g_pending.clear();
  for (BuildBatch *b : g_batches)
    for (Tile &t : b->tiles)
      t.doomed = true;
  g_builtReady.clear();
  g_liveQuads = 0;
  g_liveDirty = true;
  if (g_mask)
    memset([g_mask contents], 0, g_mask.length);
}

uint32_t take_slot() {
  if (!g_freeSlots.empty()) {
    uint32_t s = g_freeSlots.back();
    g_freeSlots.pop_back();
    return s;
  }
  if (g_slotHigh >= g_slotCap)
    return UINT32_MAX;
  return g_slotHigh++;
}

void ensure_list_capacity(int slot, uint32_t want) {
  if (g_listCap[slot] >= want)
    return;
  uint32_t cap = g_listCap[slot];
  while (cap < want)
    cap *= 2;
  id<MTLBuffer> nb = [g_dev newBufferWithLength:(NSUInteger)cap * 8 * kLists options:MTLResourceStorageModePrivate];
  if (!nb)
    return;
  retire_object(g_lists[slot]);
  g_lists[slot] = nb;
  g_listCap[slot] = cap;
}

}

bool mr_far_active() { return g_ready && (g_liveCount[0] + g_liveCount[1]) > 0; }

void mr_far_prepare() {
  if (!g_ready)
    return;
  @autoreleasepool {
    std::lock_guard<std::mutex> lock(g_mutex);
    collect();
    finish_batches();
    g_drawSlot = -1;
    CullFrame frame;
    mr_host_fill_cull_frame(&frame, sizeof(frame));
    if ((g_opts & 16u) == 0u)
      frame.hizFlags = 0u;
    bool liveChanged = g_liveDirty;
    if (g_liveDirty) {
      g_liveSlots.clear();
      g_liveSlots.reserve(g_liveTiles.size());
      for (auto &e : g_liveTiles)
        g_liveSlots.push_back(e.second.slot);
      g_liveDirty = false;
    }
    uint32_t liveN = (uint32_t)std::min<size_t>(g_liveSlots.size(), g_slotCap);
    CullParams params;
    memcpy(params.nearBox, g_nearBox, sizeof(params.nearBox));
    params.farRadius = g_farRadius;
    params.listCap = 0;
    params.tileCount = liveN;
    params.reverseZ = mr_host_reverse_z() ? 1u : 0u;
    uint8_t key[sizeof(CullFrame) + sizeof(CullParams)];
    memcpy(key, &frame, sizeof(CullFrame));
    memcpy(key + sizeof(CullFrame), &params, sizeof(CullParams));
    bool reuse = !liveChanged && !g_cullDirty && memcmp(key, g_lastCull, sizeof(key)) == 0;
    int slot = reuse ? g_cullSlot : (g_cullSlot ^ 1);
    if (!reuse) {
      memcpy(g_lastCull, key, sizeof(key));
      g_cullDirty = false;
      g_cullSlot = slot;
      if (liveN)
        memcpy([g_live[slot] contents], g_liveSlots.data(), liveN * 4);
      g_liveCount[slot] = liveN;
    }
    std::vector<Tile> toBuild;
    for (auto &e : g_pending) {
      for (Tile &t : e.second) {
        if (t.state == kPending && !t.doomed && toBuild.size() < g_buildsPerFrame) {
          t.state = kBuilding;
          toBuild.push_back(t);
        }
      }
    }
    for (auto it = g_pending.begin(); it != g_pending.end();) {
      auto &vec = it->second;
      vec.erase(std::remove_if(vec.begin(), vec.end(), [](const Tile &t) { return t.state == kBuilding; }),
                vec.end());
      if (vec.empty())
        it = g_pending.erase(it);
      else
        ++it;
    }
    if (!reuse) {
      uint32_t want = (uint32_t)std::min<uint64_t>(g_liveQuads / 32 + (uint64_t)liveN * 21 + 8192, 1u << 23);
      ensure_list_capacity(slot, want);
    }
    id<MTLBuffer> sprites = mr_host_sprites();
    id<MTLTexture> atlas = mr_host_atlas();
    uint32_t spriteN = sprites ? (uint32_t)(sprites.length / 8) : 0u;
    g_spriteFrame++;
    bool spritesStale = (g_opts & 1u) && sprites && atlas && spriteN &&
                        (sprites != g_spriteSrc || atlas != g_atlasSrc || g_spriteFrame % 1200 == 0 ||
                         !g_spriteAvg || g_spriteAvg.length < (NSUInteger)spriteN * 8);
    if (reuse && toBuild.empty() && !spritesStale) {
      g_drawSlot = slot;
      return;
    }
    id<MTLCommandBuffer> cb = [mr_host_queue() commandBuffer];
    cb.label = @"metalrender far prepare";
    id<MTLComputeCommandEncoder> ce = [cb computeCommandEncoder];
    if (spritesStale) {
      if (!g_spriteAvg || g_spriteAvg.length < (NSUInteger)spriteN * 8) {
        id<MTLBuffer> nb = [g_dev newBufferWithLength:(NSUInteger)spriteN * 8 options:MTLResourceStorageModePrivate];
        if (nb) {
          retire_object(g_spriteAvg);
          g_spriteAvg = nb;
        }
      }
      if (g_spriteAvg && g_spriteAvg.length >= (NSUInteger)spriteN * 8) {
        g_spriteSrc = sprites;
        g_atlasSrc = atlas;
        [ce setComputePipelineState:g_spriteAvgPso];
        [ce setBuffer:sprites offset:0 atIndex:0];
        [ce setBuffer:g_spriteAvg offset:0 atIndex:1];
        [ce setBytes:&spriteN length:4 atIndex:2];
        [ce setTexture:atlas atIndex:0];
        [ce dispatchThreadgroups:MTLSizeMake(spriteN, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
        [ce memoryBarrierWithScope:MTLBarrierScopeBuffers];
      }
    }
    if (!toBuild.empty()) {
      g_buildFlip ^= 1;
      uint32_t *bl = (uint32_t *)[g_buildList[g_buildFlip] contents];
      uint32_t n = (uint32_t)std::min<size_t>(toBuild.size(), 4096);
      for (uint32_t i = 0; i < n; i++)
        bl[i] = toBuild[i].slot;
      [ce setComputePipelineState:g_build];
      [ce setBuffer:g_tiles offset:0 atIndex:0];
      [ce setBuffer:g_blobs offset:0 atIndex:1];
      [ce setBuffer:g_looks offset:0 atIndex:2];
      [ce setBuffer:g_quads offset:0 atIndex:3];
      [ce setBuffer:g_buildList[g_buildFlip] offset:0 atIndex:4];
      [ce setBuffer:g_tints offset:0 atIndex:5];
      [ce dispatchThreadgroups:MTLSizeMake(n, 1, 1) threadsPerThreadgroup:MTLSizeMake(224, 1, 1)];
      [ce memoryBarrierWithScope:MTLBarrierScopeBuffers];
      BuildBatch *batch = new BuildBatch();
      batch->tiles = toBuild;
      g_batches.push_back(batch);
      [cb addCompletedHandler:^(id<MTLCommandBuffer>) {
        batch->done.store(true, std::memory_order_release);
      }];
    }
    params.listCap = g_listCap[slot];
    if (reuse) {
      [ce endEncoding];
      [cb commit];
      g_drawSlot = slot;
      return;
    }
    [ce setComputePipelineState:g_cullReset];
    [ce setBuffer:g_args[slot] offset:0 atIndex:0];
    [ce dispatchThreads:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
    if (liveN > 0) {
      [ce setComputePipelineState:g_cull];
      [ce setBytes:&frame length:sizeof(frame) atIndex:0];
      [ce setBytes:&params length:sizeof(params) atIndex:1];
      [ce setBuffer:g_tiles offset:0 atIndex:2];
      [ce setBuffer:g_live[slot] offset:0 atIndex:3];
      [ce setBuffer:g_args[slot] offset:0 atIndex:4];
      [ce setBuffer:g_lists[slot] offset:0 atIndex:5];
      [ce setBuffer:g_mask offset:0 atIndex:6];
      [ce setTexture:mr_host_hiz(frame.hizFlags != 0u) atIndex:0];
      [ce dispatchThreadgroups:MTLSizeMake(liveN, 1, 1) threadsPerThreadgroup:MTLSizeMake(32, 1, 1)];
    }
    [ce setComputePipelineState:g_cullFinish];
    [ce setBuffer:g_args[slot] offset:0 atIndex:0];
    [ce setBytes:&params length:sizeof(params) atIndex:1];
    [ce dispatchThreads:MTLSizeMake(kLists, 1, 1) threadsPerThreadgroup:MTLSizeMake(kLists, 1, 1)];
    [ce endEncoding];
    id<MTLBuffer> argsBuf = g_args[slot];
    [cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
      const uint32_t *a = (const uint32_t *)[argsBuf contents];
      g_drawnTiles.store(a[kLists * 5], std::memory_order_relaxed);
      g_drawnQuads.store(a[kLists * 5 + 1], std::memory_order_relaxed);
      if (done.GPUEndTime > done.GPUStartTime)
        g_cullUs.store((long long)((done.GPUEndTime - done.GPUStartTime) * 1000000.0), std::memory_order_relaxed);
    }];
    [cb commit];
    g_drawSlot = slot;
  }
}

static void far_bind(id<MTLRenderCommandEncoder> enc, int slot) {
  DrawParams dp;
  CullFrame frame;
  mr_host_fill_cull_frame(&frame, sizeof(frame));
  memcpy(dp.camBlock, frame.camBlock, sizeof(dp.camBlock));
  memcpy(dp.camFrac, frame.camFrac, sizeof(dp.camFrac));
  dp.depthPush = g_depthPush;
  dp.reverseZ = mr_host_reverse_z() ? 1u : 0u;
  dp.pad0 = dp.pad1 = 0;
  [enc setFrontFacingWinding:mr_host_ext_winding()];
  [enc setDepthBias:0.0f slopeScale:0.0f clamp:0.0f];
  [enc setVertexBytes:mr_host_proj() length:64 atIndex:1];
  [enc setVertexBytes:mr_host_mv() length:64 atIndex:2];
  [enc setVertexBytes:&dp length:sizeof(dp) atIndex:4];
  [enc setVertexBuffer:g_quads offset:0 atIndex:5];
  [enc setVertexBuffer:g_tiles offset:0 atIndex:6];
  [enc setVertexBuffer:g_looks offset:0 atIndex:7];
  [enc setVertexBuffer:g_tints offset:0 atIndex:8];
  size_t fogSize = 0;
  const void *fog = mr_host_fog_uniforms(&fogSize);
  [enc setFragmentBytes:fog length:fogSize atIndex:6];
  FogRecon recon;
  mr_host_fill_fog_recon(&recon, sizeof(recon));
  [enc setFragmentBytes:&recon length:sizeof(recon) atIndex:7];
  [enc setFragmentBuffer:mr_host_sprites() offset:0 atIndex:8];
  [enc setFragmentBuffer:g_looks offset:0 atIndex:9];
  [enc setFragmentBuffer:g_mask offset:0 atIndex:10];
  uint32_t flat = ((g_opts & 1u) && g_spriteAvg && g_spriteSrc == mr_host_sprites()) ? 1u : 0u;
  [enc setFragmentBytes:&flat length:4 atIndex:11];
  [enc setFragmentBuffer:(g_spriteAvg ? g_spriteAvg : g_mask) offset:0 atIndex:12];
  (void)slot;
}

static void far_draw_list(id<MTLRenderCommandEncoder> enc, int slot, uint32_t li, id<MTLRenderPipelineState> pso) {
  [enc setRenderPipelineState:mr_host_ext_pso(pso)];
  [enc setVertexBuffer:g_lists[slot] offset:(NSUInteger)li * g_listCap[slot] * 8 atIndex:0];
  [enc drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                   indexType:MTLIndexTypeUInt16
                 indexBuffer:mr_host_quad_index()
           indexBufferOffset:0
              indirectBuffer:g_args[slot]
        indirectBufferOffset:(NSUInteger)li * 20];
  mr_host_count_draw();
}

void mr_far_encode(id<MTLRenderCommandEncoder> enc) {
  if (!g_ready || g_drawSlot < 0 || !enc)
    return;
  int slot = g_drawSlot;
  g_waterSlot = slot;
  g_drawSlot = -1;
  if (g_liveCount[slot] == 0 || !mr_host_sprites() || !mr_host_quad_index())
    return;
  id<MTLDepthStencilState> ds = mr_host_depth_state(true);
  if (ds)
    [enc setDepthStencilState:ds];
  [enc setCullMode:MTLCullModeBack];
  far_bind(enc, slot);
  far_draw_list(enc, slot, 0, g_pso[0]);
  far_draw_list(enc, slot, 3, g_pso[3]);
  far_draw_list(enc, slot, 1, g_pso[1]);
  far_draw_list(enc, slot, 4, g_pso[4]);
}

void mr_far_encode_water(id<MTLRenderCommandEncoder> enc) {
  int slot = g_waterSlot;
  g_waterSlot = -1;
  if (!g_ready || slot < 0 || !enc || g_liveCount[slot] == 0 || !mr_host_sprites() || !mr_host_quad_index())
    return;
  id<MTLDepthStencilState> ds = mr_host_depth_state(false);
  if (ds)
    [enc setDepthStencilState:ds];
  [enc setCullMode:MTLCullModeNone];
  far_bind(enc, slot);
  far_draw_list(enc, slot, 2, g_pso[2]);
  far_draw_list(enc, slot, 5, g_pso[5]);
}

#define FAR_JNI(name) Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_##name

extern "C" JNIEXPORT jboolean JNICALL FAR_JNI(nFarConfigure)(JNIEnv *, jclass, jint budgetMb, jint buildsPerFrame) {
  @autoreleasepool {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_buildsPerFrame = (uint32_t)std::max(4, std::min(256, (int)buildsPerFrame));
    if (g_ready)
      return JNI_TRUE;
    if (!ensure_pipelines())
      return JNI_FALSE;
    uint64_t budget = (uint64_t)std::max(64, std::min(4096, (int)budgetMb)) << 20;
    if (!alloc_buffers(budget)) {
      g_failed = true;
      return JNI_FALSE;
    }
    g_ready = true;
    return JNI_TRUE;
  }
}

extern "C" JNIEXPORT void JNICALL FAR_JNI(nFarSetOptions)(JNIEnv *, jclass, jint bits) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if ((uint32_t)bits != g_opts) {
    g_opts = (uint32_t)bits;
    g_cullDirty = true;
  }
}

extern "C" JNIEXPORT jboolean JNICALL FAR_JNI(nFarReady)(JNIEnv *, jclass) { return g_ready ? JNI_TRUE : JNI_FALSE; }

extern "C" JNIEXPORT void JNICALL FAR_JNI(nFarSetLooks)(JNIEnv *env, jclass, jobject data, jint count) {
  void *bytes = data ? env->GetDirectBufferAddress(data) : nullptr;
  if (!bytes || count <= 0)
    return;
  @autoreleasepool {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_ready)
      return;
    NSUInteger len = (NSUInteger)count * 26 * 4;
    id<MTLBuffer> nb = [g_dev newBufferWithBytes:bytes length:len options:MTLResourceStorageModeShared];
    if (!nb)
      return;
    retire_object(g_looks);
    g_looks = nb;
    g_lookCount = (uint32_t)count;
  }
}

extern "C" JNIEXPORT jint JNICALL FAR_JNI(nFarUploadTile)(JNIEnv *env, jclass, jlong key, jint level, jint ox, jint oz,
                                                        jint minY, jint yLo, jint yHi, jobject blob, jint blobUints,
                                                        jobject tints, jint tintCount, jint quadBound,
                                                        jint flags, jint lookPal, jint plane) {
  uint8_t *blobPtr = blob ? (uint8_t *)env->GetDirectBufferAddress(blob) : nullptr;
  uint8_t *tintPtr = tints ? (uint8_t *)env->GetDirectBufferAddress(tints) : nullptr;
  if (!blobPtr || blobUints < (jint)kOffsets)
    return -2;
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g_ready)
    return -2;
  collect();
  Tile t;
  t.key = key;
  t.version = ++g_version;
  uint32_t qcap = (uint32_t)std::max(32, ((int)quadBound + 31) & ~31);
  uint32_t tl = (uint32_t)std::max(1, (int)tintCount);
  if (!g_blobAlloc.alloc((uint32_t)blobUints, t.blobOff))
    return -1;
  t.blobLen = (uint32_t)blobUints;
  t.stride = (flags & 2) ? 2u : 3u;
  t.quadRawLen = qcap * t.stride + t.stride - 1u;
  if (!g_quadAlloc.alloc(t.quadRawLen, t.quadRaw)) {
    g_blobAlloc.release(t.blobOff, t.blobLen);
    return -1;
  }
  t.quadOff = (t.quadRaw + t.stride - 1u) / t.stride;
  t.quadCap = qcap;
  if (!g_tintAlloc.alloc(tl, t.tintOff)) {
    g_blobAlloc.release(t.blobOff, t.blobLen);
    g_quadAlloc.release(t.quadRaw, t.quadRawLen);
    return -1;
  }
  t.tintLen = tl;
  t.slot = take_slot();
  if (t.slot == UINT32_MAX) {
    g_blobAlloc.release(t.blobOff, t.blobLen);
    g_quadAlloc.release(t.quadRaw, t.quadRawLen);
    g_tintAlloc.release(t.tintOff, t.tintLen);
    return -1;
  }
  memcpy((uint32_t *)[g_blobs contents] + t.blobOff, blobPtr, (size_t)blobUints * 4);
  uint32_t *tintDst = (uint32_t *)[g_tints contents] + t.tintOff;
  if (tintPtr && tintCount > 0)
    memcpy(tintDst, tintPtr, (size_t)tintCount * 4);
  else
    tintDst[0] = 0xFFFFFFFFu;
  TileRec *rec = (TileRec *)[g_tiles contents] + t.slot;
  memset(rec, 0, sizeof(TileRec));
  rec->ox = ox;
  rec->oz = oz;
  rec->minY = minY;
  rec->level = (uint32_t)level;
  rec->blob = t.blobOff;
  rec->tint = t.tintOff;
  rec->quadBase = t.quadOff;
  rec->quadCap = t.quadCap;
  rec->yLo = yLo;
  rec->yHi = yHi;
  rec->flags = 1u | ((uint32_t)flags & ~1u);
  rec->lookPal = (uint32_t)lookPal;
  rec->plane = (uint32_t)plane;
  auto &vec = g_pending[key];
  for (Tile &o : vec) {
    if (o.state == kPending) {
      free_tile_now(o);
      o.doomed = true;
    }
  }
  vec.erase(std::remove_if(vec.begin(), vec.end(), [](const Tile &o) { return o.doomed && o.state == kPending; }),
            vec.end());
  for (BuildBatch *b : g_batches)
    for (Tile &o : b->tiles)
      if (o.key == key && o.version < t.version)
        o.doomed = true;
  vec.push_back(t);
  return (jint)t.version;
}

extern "C" JNIEXPORT jint JNICALL FAR_JNI(nFarPollBuilt)(JNIEnv *env, jclass, jlongArray out) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!out)
    return 0;
  finish_batches();
  jsize len = env->GetArrayLength(out);
  jsize n = (jsize)std::min<size_t>(g_builtReady.size(), (size_t)len);
  if (n > 0) {
    std::vector<jlong> tmp(g_builtReady.begin(), g_builtReady.begin() + n);
    env->SetLongArrayRegion(out, 0, n, tmp.data());
    g_builtReady.erase(g_builtReady.begin(), g_builtReady.begin() + n);
  }
  return n;
}

extern "C" JNIEXPORT void JNICALL FAR_JNI(nFarCommit)(JNIEnv *env, jclass, jlongArray show, jint nShow,
                                                     jlongArray hide, jint nHide) {
  std::vector<jlong> s((size_t)std::max(0, (int)nShow));
  std::vector<jlong> h((size_t)std::max(0, (int)nHide));
  if (nShow > 0 && show)
    env->GetLongArrayRegion(show, 0, nShow, s.data());
  if (nHide > 0 && hide)
    env->GetLongArrayRegion(hide, 0, nHide, h.data());
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g_ready)
    return;
  for (jlong key : s) {
    auto it = g_pending.find(key);
    if (it == g_pending.end())
      continue;
    auto &vec = it->second;
    for (size_t i = 0; i < vec.size(); i++) {
      if (vec[i].state == kBuilt) {
        make_live(vec[i]);
        vec.erase(vec.begin() + (long)i);
        break;
      }
    }
    if (vec.empty())
      g_pending.erase(it);
  }
  for (jlong key : h) {
    auto lit = g_liveTiles.find(key);
    if (lit != g_liveTiles.end()) {
      g_liveQuads -= lit->second.used;
      retire_tile(lit->second);
      g_liveTiles.erase(lit);
      g_liveDirty = true;
    }
    auto pit = g_pending.find(key);
    if (pit != g_pending.end()) {
      for (Tile &t : pit->second) {
        if (t.state == kPending)
          free_tile_now(t);
        else if (t.state == kBuilt)
          retire_tile(t);
      }
      g_pending.erase(pit);
    }
    for (BuildBatch *b : g_batches)
      for (Tile &t : b->tiles)
        if (t.key == key)
          t.doomed = true;
  }
}

extern "C" JNIEXPORT void JNICALL FAR_JNI(nFarSetNearColumns)(JNIEnv *env, jclass, jintArray packed, jint count) {
  if (!packed || count <= 0)
    return;
  std::vector<jint> v((size_t)count * 3);
  env->GetIntArrayRegion(packed, 0, count * 3, v.data());
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g_ready)
    return;
  uint8_t *m = (uint8_t *)[g_mask contents];
  for (jint i = 0; i < count; i++) {
    int cx = v[(size_t)i * 3];
    int cz = v[(size_t)i * 3 + 1];
    m[(uint32_t)(cx & 127) + (uint32_t)(cz & 127) * kMaskSize] = v[(size_t)i * 3 + 2] != 0 ? 1 : 0;
  }
  g_cullDirty = true;
}

extern "C" JNIEXPORT void JNICALL FAR_JNI(nFarSetView)(JNIEnv *, jclass, jfloat farRadius, jint minCx, jint minCz,
                                                      jint maxCx, jint maxCz) {
  std::lock_guard<std::mutex> lock(g_mutex);
  g_farRadius = farRadius;
  g_nearBox[0] = minCx;
  g_nearBox[1] = minCz;
  g_nearBox[2] = maxCx;
  g_nearBox[3] = maxCz;
}

extern "C" JNIEXPORT void JNICALL FAR_JNI(nFarClear)(JNIEnv *, jclass) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g_ready)
    return;
  clear_all_locked();
}

extern "C" JNIEXPORT jint JNICALL FAR_JNI(nFarStats)(JNIEnv *env, jclass, jlongArray out) {
  if (!out)
    return 0;
  jlong v[12] = {0};
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ready) {
      v[0] = (jlong)g_liveTiles.size();
      size_t pend = 0;
      for (auto &e : g_pending)
        pend += e.second.size();
      for (BuildBatch *b : g_batches)
        pend += b->tiles.size();
      v[1] = (jlong)pend;
      v[2] = (jlong)g_liveQuads;
      v[3] = (jlong)((uint64_t)(g_quadAlloc.capacity - g_quadAlloc.freeTotal) * 4);
      v[4] = (jlong)((uint64_t)g_quadAlloc.capacity * 4);
      v[5] = (jlong)((uint64_t)(g_blobAlloc.capacity - g_blobAlloc.freeTotal) * 4);
      v[6] = (jlong)((uint64_t)g_blobAlloc.capacity * 4);
      v[7] = (jlong)g_freeSlots.size() + (jlong)(g_slotCap - g_slotHigh);
    }
  }
  v[8] = g_drawnTiles.load(std::memory_order_relaxed);
  v[9] = g_drawnQuads.load(std::memory_order_relaxed);
  v[10] = g_cullUs.load(std::memory_order_relaxed);
  v[11] = g_ready ? 1 : 0;
  jsize len = env->GetArrayLength(out);
  env->SetLongArrayRegion(out, 0, std::min<jsize>(len, 12), v);
  return 12;
}
