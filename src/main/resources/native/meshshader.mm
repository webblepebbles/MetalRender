#include <Metal/Metal.h>
#include <algorithm>
#include <cstdio>
#include <cstring>
#include <jni.h>
#include <mutex>
#include <unordered_map>
extern id<MTLDevice> g_device;
extern id<MTLRenderCommandEncoder> g_currentEncoder;
extern id<MTLDepthStencilState> g_depthState;
extern id<MTLBuffer> g_subChunkBuffer;
extern id<MTLRenderPipelineState> g_pipelineMeshOpaque;
extern id<MTLRenderPipelineState> g_pipelineMeshCutout;
extern id<MTLRenderPipelineState> g_pipelineMeshEmissive;
extern id<MTLBuffer> g_cullDrawArgsBuffer;
extern id<MTLBuffer> g_cullDrawCountBuffer;
extern uint32_t g_gpuSubChunkCount;
extern id<MTLBuffer> g_tripleBuffers[];
extern int g_currentBufferIndex;
extern bool g_meshShadersActive;
extern uint32_t g_meshPipelineCount;
extern uint32_t g_drawCallCount;
static const int kTripleBufferCount = 3;
static inline void meshDbg(const char *fmt, ...) {
#ifdef METALRENDER_DEBUG
  va_list args;
  va_start(args, fmt);
  fprintf(stderr, "[MeshShader] ");
  vfprintf(stderr, fmt, args);
  va_end(args);
#else
  (void)fmt;
#endif
}
struct MeshPipelineEntry {
  id<MTLRenderPipelineState> pipeline;
  NSString *objectFunc;
  NSString *meshFunc;
  NSString *fragmentFunc;
};
static std::mutex g_pipelineMutex;
static std::unordered_map<uint64_t, MeshPipelineEntry> g_meshPipelines;
static uint64_t g_nextPipelineHandle = 0x4D534800;
static id<MTLRenderPipelineState>
buildMeshPipeline(id<MTLDevice> device, id<MTLLibrary> library,
                  NSString *objectFuncName, NSString *meshFuncName,
                  NSString *fragmentFuncName, NSError **outError) {
  if (!device || !library)
    return nil;
  bool supported = false;
  if (@available(macOS 13.0, *)) {
    supported = [device supportsFamily:MTLGPUFamilyApple7];
  }
  if (!supported) {
    if (outError)
      *outError =
          [NSError errorWithDomain:@"MeshShader"
                              code:-1
                          userInfo:@{
                            NSLocalizedDescriptionKey :
                                @"Mesh shaders not supported on this computre"
                          }];
    return nil;
  }
  id<MTLFunction> objectFunc = [library newFunctionWithName:objectFuncName];
  id<MTLFunction> meshFunc = [library newFunctionWithName:meshFuncName];
  id<MTLFunction> fragmentFunc = [library newFunctionWithName:fragmentFuncName];
  if (!objectFunc) {
    meshDbg("ERROR: Object function '%s' not found in library\n",
            [objectFuncName UTF8String]);
    return nil;
  }
  if (!meshFunc) {
    meshDbg("ERROR: Mesh function '%s' not found in library\n",
            [meshFuncName UTF8String]);
    return nil;
  }
  if (!fragmentFunc) {
    meshDbg("ERROR: Fragment function '%s' not found in library\n",
            [fragmentFuncName UTF8String]);
    return nil;
  }
  if (@available(macOS 13.0, *)) {
    MTLMeshRenderPipelineDescriptor *desc =
        [[MTLMeshRenderPipelineDescriptor alloc] init];
    desc.objectFunction = objectFunc;
    desc.meshFunction = meshFunc;
    desc.fragmentFunction = fragmentFunc;
    desc.label =
        [NSString stringWithFormat:@"MeshPipeline_%@_%@_%@", objectFuncName,
                                   meshFuncName, fragmentFuncName];
    desc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;

    desc.colorAttachments[0].blendingEnabled = NO;
    desc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;

    desc.maxTotalThreadsPerObjectThreadgroup = 1;
    desc.maxTotalThreadsPerMeshThreadgroup = 256;

    desc.payloadMemoryLength = 16;
    id<MTLRenderPipelineState> pipeline =
        [device newRenderPipelineStateWithMeshDescriptor:desc
                                                 options:0
                                              reflection:nil
                                                   error:outError];
    return pipeline;
  }
  return nil;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MeshShaderNative_dispatchTerrain(
    JNIEnv *, jclass, jlong handle, jint visibleRegionCount,
    jlong indirectBufferAddr) {
  (void)handle;
  if (!g_currentEncoder || visibleRegionCount <= 0)
    return;
  uint32_t regionCount = (uint32_t)visibleRegionCount;
  id<MTLRenderPipelineState> pipeline = nil;
  {
    std::lock_guard<std::mutex> lock(g_pipelineMutex);
    for (auto &kv : g_meshPipelines) {
      if (kv.second.pipeline) {
        pipeline = kv.second.pipeline;
        break;
      }
    }
  }
  if (!pipeline) {
    meshDbg("dispatchTerrain: No mesh pipeline available\n");
    return;
  }
  if (@available(macOS 13.0, *)) {
    [g_currentEncoder setRenderPipelineState:pipeline];
    if (g_depthState)
      [g_currentEncoder setDepthStencilState:g_depthState];
    int bufIdx = g_currentBufferIndex % kTripleBufferCount;
    id<MTLBuffer> camBuf = g_tripleBuffers[bufIdx];
    if (camBuf) {
      [g_currentEncoder setObjectBuffer:camBuf offset:0 atIndex:0];
      [g_currentEncoder setMeshBuffer:camBuf offset:0 atIndex:0];
      [g_currentEncoder setFragmentBuffer:camBuf offset:0 atIndex:0];
    }
    if (g_subChunkBuffer) {
      [g_currentEncoder setObjectBuffer:g_subChunkBuffer offset:0 atIndex:1];
      [g_currentEncoder setMeshBuffer:g_subChunkBuffer offset:0 atIndex:1];
    }
    if (g_cullDrawArgsBuffer) {
      [g_currentEncoder setObjectBuffer:g_cullDrawArgsBuffer
                                 offset:0
                                atIndex:2];
    }
    if (indirectBufferAddr != 0) {
      id<MTLBuffer> argBuf = (__bridge id<MTLBuffer>)(void *)indirectBufferAddr;
      [g_currentEncoder setObjectBuffer:argBuf offset:0 atIndex:3];
      [g_currentEncoder setMeshBuffer:argBuf offset:0 atIndex:2];
    }
    MTLSize objectThreadgroups = MTLSizeMake(regionCount, 1, 1);
    MTLSize objectThreadsPerGroup = MTLSizeMake(256, 1, 1);
    MTLSize meshThreadsPerGroup = MTLSizeMake(256, 1, 1);
    [g_currentEncoder drawMeshThreadgroups:objectThreadgroups
               threadsPerObjectThreadgroup:objectThreadsPerGroup
                 threadsPerMeshThreadgroup:meshThreadsPerGroup];
    g_drawCallCount++;
    meshDbg("Dispatched %u mesh threadgroups\n", regionCount);
  }
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MeshShaderNative_createMeshPipeline(
    JNIEnv *env, jclass, jlong deviceHandle, jlong libraryHandle,
    jstring objectFunc, jstring meshFunc, jstring fragmentFunc) {
  (void)deviceHandle;
  id<MTLDevice> device = g_device;
  id<MTLLibrary> library = (__bridge id<MTLLibrary>)(void *)libraryHandle;
  if (!device || !library) {
    meshDbg("createMeshPipeline: device or library is null\n");
    return 0;
  }
  const char *objCStr = env->GetStringUTFChars(objectFunc, nullptr);
  const char *meshCStr = env->GetStringUTFChars(meshFunc, nullptr);
  const char *fragCStr = env->GetStringUTFChars(fragmentFunc, nullptr);
  NSString *objName = [NSString stringWithUTF8String:objCStr];
  NSString *meshName = [NSString stringWithUTF8String:meshCStr];
  NSString *fragName = [NSString stringWithUTF8String:fragCStr];
  env->ReleaseStringUTFChars(objectFunc, objCStr);
  env->ReleaseStringUTFChars(meshFunc, meshCStr);
  env->ReleaseStringUTFChars(fragmentFunc, fragCStr);
  NSError *error = nil;
  id<MTLRenderPipelineState> pipeline =
      buildMeshPipeline(device, library, objName, meshName, fragName, &error);
  if (!pipeline) {
    meshDbg("Failed to create mesh pipeline: %s\n",
            error ? [[error localizedDescription] UTF8String]
                  : "unknown error");
    return 0;
  }
  uint64_t handle;
  {
    std::lock_guard<std::mutex> lock(g_pipelineMutex);
    handle = g_nextPipelineHandle++;
    MeshPipelineEntry entry;
    entry.pipeline = pipeline;
    entry.objectFunc = objName;
    entry.meshFunc = meshName;
    entry.fragmentFunc = fragName;
    g_meshPipelines[handle] = entry;
  }
  meshDbg("Created mesh pipeline handle=0x%llx (%s, %s, %s)\n",
          (unsigned long long)handle, [objName UTF8String],
          [meshName UTF8String], [fragName UTF8String]);
  g_meshShadersActive = true;
  return (jlong)handle;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MeshShaderNative_drawMeshThreadgroups(
    JNIEnv *, jclass, jlong frameContext, jlong pipelineHandle,
    jint objectThreadgroups, jint meshThreadsPerGroup, jlong argumentBuffer) {
  (void)frameContext;
  if (!g_currentEncoder || objectThreadgroups <= 0)
    return;
  id<MTLRenderPipelineState> pipeline = nil;
  {
    std::lock_guard<std::mutex> lock(g_pipelineMutex);
    auto it = g_meshPipelines.find((uint64_t)pipelineHandle);
    if (it != g_meshPipelines.end()) {
      pipeline = it->second.pipeline;
    }
  }
  if (!pipeline) {
    meshDbg("drawMeshThreadgroups: invalid pipeline handle 0x%llx\n",
            (unsigned long long)pipelineHandle);
    return;
  }
  if (@available(macOS 13.0, *)) {
    [g_currentEncoder setRenderPipelineState:pipeline];
    if (g_depthState)
      [g_currentEncoder setDepthStencilState:g_depthState];
    int bufIdx = g_currentBufferIndex % kTripleBufferCount;
    id<MTLBuffer> camBuf = g_tripleBuffers[bufIdx];
    if (camBuf) {
      [g_currentEncoder setObjectBuffer:camBuf offset:0 atIndex:0];
      [g_currentEncoder setMeshBuffer:camBuf offset:0 atIndex:0];
      [g_currentEncoder setFragmentBuffer:camBuf offset:0 atIndex:0];
    }
    if (g_subChunkBuffer) {
      [g_currentEncoder setObjectBuffer:g_subChunkBuffer offset:0 atIndex:1];
      [g_currentEncoder setMeshBuffer:g_subChunkBuffer offset:0 atIndex:1];
    }
    if (argumentBuffer != 0) {
      id<MTLBuffer> argBuf = (__bridge id<MTLBuffer>)(void *)argumentBuffer;
      [g_currentEncoder setObjectBuffer:argBuf offset:0 atIndex:2];
      [g_currentEncoder setMeshBuffer:argBuf offset:0 atIndex:2];
    }
    uint32_t meshTpg = std::max((int)meshThreadsPerGroup, 1);
    meshTpg = std::min(meshTpg, (uint32_t)256);
    MTLSize objTG = MTLSizeMake((uint32_t)objectThreadgroups, 1, 1);
    MTLSize objTPG = MTLSizeMake(256, 1, 1);
    MTLSize meshTPG = MTLSizeMake(meshTpg, 1, 1);
    [g_currentEncoder drawMeshThreadgroups:objTG
               threadsPerObjectThreadgroup:objTPG
                 threadsPerMeshThreadgroup:meshTPG];
    g_drawCallCount++;
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MeshShaderNative_destroyMeshPipeline(
    JNIEnv *, jclass, jlong pipelineHandle) {
  std::lock_guard<std::mutex> lock(g_pipelineMutex);
  auto it = g_meshPipelines.find((uint64_t)pipelineHandle);
  if (it != g_meshPipelines.end()) {
    meshDbg("Destroying mesh pipeline handle=0x%llx\n",
            (unsigned long long)pipelineHandle);
    g_meshPipelines.erase(it);
  }
  g_meshPipelineCount = (uint32_t)g_meshPipelines.size();
  if (g_meshPipelines.empty()) {
    g_meshShadersActive = false;
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MeshShaderNative_uploadMeshletBuffer(
    JNIEnv *env, jclass, jlong deviceHandle, jobject directBuffer, jint count) {
  (void)deviceHandle;
  if (!g_device || !directBuffer || count <= 0)
    return;
  void *ptr = env->GetDirectBufferAddress(directBuffer);
  jlong cap = env->GetDirectBufferCapacity(directBuffer);
  if (!ptr)
    return;
  size_t entrySize = 32;
  size_t totalSize = (size_t)count * entrySize;
  if ((size_t)cap < totalSize)
    return;
  if (!g_subChunkBuffer || g_subChunkBuffer.length < totalSize) {
    g_subChunkBuffer =
        [g_device newBufferWithLength:totalSize
                              options:MTLStorageModeShared];
  }
  memcpy([g_subChunkBuffer contents], ptr, totalSize);
  g_gpuSubChunkCount = (uint32_t)count;
  meshDbg("Uploaded %d meshlet descriptors (%zu bytes)\n", count, totalSize);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MeshShaderNative_getActivePipelineCount(
    JNIEnv *, jclass) {
  std::lock_guard<std::mutex> lock(g_pipelineMutex);
  return (jint)g_meshPipelines.size();
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MeshShaderNative_createTerrainMeshPipelines(
    JNIEnv *env, jclass, jlong libraryHandle) {
  id<MTLLibrary> library = (__bridge id<MTLLibrary>)(void *)libraryHandle;
  if (!g_device || !library)
    return nullptr;
  struct PipelineSpec {
    const char *objectFunc;
    const char *meshFunc;
    const char *fragmentFunc;
  };
  PipelineSpec specs[] = {
      {"object_terrain", "mesh_terrain", "fragment_terrain_mesh_opaque"},
      {"object_terrain", "mesh_terrain", "fragment_terrain_mesh_cutout"},
      {"object_terrain", "mesh_terrain", "fragment_terrain_mesh_emissive"},
  };
  int specCount = 3;
  jlong handles[3] = {0, 0, 0};
  for (int i = 0; i < specCount; i++) {
    NSError *error = nil;
    NSString *objName = [NSString stringWithUTF8String:specs[i].objectFunc];
    NSString *meshName = [NSString stringWithUTF8String:specs[i].meshFunc];
    NSString *fragName = [NSString stringWithUTF8String:specs[i].fragmentFunc];
    id<MTLRenderPipelineState> pipeline = buildMeshPipeline(
        g_device, library, objName, meshName, fragName, &error);
    if (pipeline) {
      std::lock_guard<std::mutex> lock(g_pipelineMutex);
      uint64_t h = g_nextPipelineHandle++;
      MeshPipelineEntry entry;
      entry.pipeline = pipeline;
      entry.objectFunc = objName;
      entry.meshFunc = meshName;
      entry.fragmentFunc = fragName;
      g_meshPipelines[h] = entry;
      handles[i] = (jlong)h;
      meshDbg("Created terrain mesh pipeline[%d] handle=0x%llx (%s)\n", i,
              (unsigned long long)h, specs[i].fragmentFunc);
    } else {
      meshDbg("Failed terrain mesh pipeline[%d] (%s): %s\n", i,
              specs[i].fragmentFunc,
              error ? [[error localizedDescription] UTF8String] : "unknown");
    }
  }
  g_meshPipelineCount = (uint32_t)g_meshPipelines.size();
  g_meshShadersActive = g_meshPipelineCount > 0;

  if (handles[0] != 0)
    g_pipelineMeshOpaque = g_meshPipelines[(uint64_t)handles[0]].pipeline;
  if (handles[1] != 0)
    g_pipelineMeshCutout = g_meshPipelines[(uint64_t)handles[1]].pipeline;
  if (handles[2] != 0)
    g_pipelineMeshEmissive = g_meshPipelines[(uint64_t)handles[2]].pipeline;

  jlongArray result = env->NewLongArray(specCount);
  env->SetLongArrayRegion(result, 0, specCount, handles);
  return result;
}
