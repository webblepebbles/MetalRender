#ifndef METALRENDER_SPIRV_TRANSLATE_H
#define METALRENDER_SPIRV_TRANSLATE_H

#include <cstddef>
#include <cstdint>
#include <string>

#define MR_SPIRV_ENTRY "main0"

bool mr_spirv_to_msl(const uint32_t *words, size_t wordCount, bool vertexStage, const int *bindings,
                     size_t bindingCount, std::string &msl, std::string &error);

#endif
