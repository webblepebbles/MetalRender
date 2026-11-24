#include "spirv_translate.h"

#include "third_party/spirv_cross/spirv_msl.hpp"

#include <exception>

bool mr_spirv_to_msl(const uint32_t *words, size_t wordCount, bool vertexStage, const int *bindings,
                     size_t bindingCount, std::string &msl, std::string &error) {
  try {
    spirv_cross::CompilerMSL compiler(words, wordCount);

    spirv_cross::CompilerMSL::Options options;
    options.set_msl_version(2, 4);
    options.platform = spirv_cross::CompilerMSL::Options::macOS;
    options.argument_buffers = false;
    options.enable_decoration_binding = false;
    options.pad_fragment_output_components = true;
    compiler.set_msl_options(options);

    spirv_cross::CompilerGLSL::Options common = compiler.get_common_options();
    common.vertex.flip_vert_y = true;
    compiler.set_common_options(common);

    spv::ExecutionModel stage = vertexStage ? spv::ExecutionModelVertex : spv::ExecutionModelFragment;
    for (size_t i = 0; i + 3 < bindingCount; i += 4) {
      spirv_cross::MSLResourceBinding binding;
      binding.stage = stage;
      binding.desc_set = (uint32_t)bindings[i];
      binding.binding = (uint32_t)bindings[i + 1];
      uint32_t slot = (uint32_t)bindings[i + 3];
      binding.msl_buffer = slot;
      binding.msl_texture = slot;
      binding.msl_sampler = slot;
      compiler.add_msl_resource_binding(binding);
    }

    msl = compiler.compile();
    return true;
  } catch (const std::exception &e) {
    error = e.what();
    return false;
  }
}
