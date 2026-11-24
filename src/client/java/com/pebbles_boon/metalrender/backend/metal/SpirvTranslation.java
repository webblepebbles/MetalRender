package com.pebbles_boon.metalrender.backend.metal;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class SpirvTranslation {
    private static final int[] FREE_BUFFER_SLOTS = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 30, 31};
    private static final int MAX_TEXTURE_SLOT = 14;
    private static final int KIND_BUFFER = 0;
    private static final int KIND_TEXTURE = 1;

    private final long vertexLibrary;
    private final long fragmentLibrary;
    private final int[] vertexLayout;
    private final Map<String, Integer> bufferSlots;
    private final Map<String, Integer> textureSlots;

    private SpirvTranslation(long vertexLibrary, long fragmentLibrary, int[] vertexLayout,
            Map<String, Integer> bufferSlots, Map<String, Integer> textureSlots) {
        this.vertexLibrary = vertexLibrary;
        this.fragmentLibrary = fragmentLibrary;
        this.vertexLayout = vertexLayout;
        this.bufferSlots = bufferSlots;
        this.textureSlots = textureSlots;
    }

    static SpirvTranslation create(MetalDeviceBackend device, BackendRenderPipeline.CreateInfo create) {
        try {
            return translate(device, create);
        } catch (Exception | LinkageError e) {
            MetalLogger.warn("spirv translation of %s failed: %s", create.name(), e);
            return null;
        }
    }

    private static SpirvTranslation translate(MetalDeviceBackend device,
            BackendRenderPipeline.CreateInfo create) throws Exception {
        SpvModule vertex = null;
        SpvModule fragment = null;
        for (BackendRenderPipeline.CreateInfo.Shader shader : create.shaders()) {
            SpvModule module = shader.module();
            if (module == null) {
                continue;
            }
            if (module.type() == ShaderType.VERTEX) {
                vertex = module;
            } else if (module.type() == ShaderType.FRAGMENT) {
                fragment = module;
            }
        }
        if (vertex == null || fragment == null) {
            MetalLogger.warn("pipeline %s has no vertex and fragment spirv", create.name());
            return null;
        }
        Map<String, SpvModule.Reflection.Descriptor> vertexDescriptors = descriptors(vertex);
        Map<String, SpvModule.Reflection.Descriptor> fragmentDescriptors = descriptors(fragment);

        Map<String, Integer> bufferSlots = new HashMap<>();
        Map<String, Integer> textureSlots = new HashMap<>();
        if (!assignSlots(create, bufferSlots, textureSlots)) {
            return null;
        }
        List<Integer> vertexTable = new ArrayList<>();
        List<Integer> fragmentTable = new ArrayList<>();
        for (BindGroupLayout.UniformDescription uniform : create.uniforms()) {
            String name = uniform.name();
            boolean texture = uniform.type() == UniformType.COMBINED_IMAGE_SAMPLER;
            int slot = texture ? textureSlots.get(name) : bufferSlots.get(name);
            int kind = texture ? KIND_TEXTURE : KIND_BUFFER;
            addBinding(vertexTable, vertexDescriptors.get(name), kind, slot);
            addBinding(fragmentTable, fragmentDescriptors.get(name), kind, slot);
        }

        long vertexLibrary = MetalNative.nSpirvLibraryCreate(device.deviceHandle(), direct(vertex.spv()),
                length(vertex.spv()), true, toArray(vertexTable));
        if (vertexLibrary == 0) {
            MetalLogger.warn("pipeline %s vertex translation failed: %s", create.name(),
                    MetalNative.nLastError());
            return null;
        }
        long fragmentLibrary = MetalNative.nSpirvLibraryCreate(device.deviceHandle(), direct(fragment.spv()),
                length(fragment.spv()), false, toArray(fragmentTable));
        if (fragmentLibrary == 0) {
            MetalLogger.warn("pipeline %s fragment translation failed: %s", create.name(),
                    MetalNative.nLastError());
            MetalNative.nRelease(vertexLibrary);
            return null;
        }
        MetalLogger.info("translated pipeline %s from spirv", create.name());
        return new SpirvTranslation(vertexLibrary, fragmentLibrary, vertexLayout(create), bufferSlots,
                textureSlots);
    }

    private static boolean assignSlots(BackendRenderPipeline.CreateInfo create,
            Map<String, Integer> bufferSlots, Map<String, Integer> textureSlots) {
        int nextFree = 0;
        boolean[] usedTextures = new boolean[MAX_TEXTURE_SLOT + 1];
        for (BindGroupLayout.UniformDescription uniform : create.uniforms()) {
            String name = uniform.name();
            if (uniform.type() == UniformType.COMBINED_IMAGE_SAMPLER) {
                int known = MetalBindings.textureSlot(name, -1);
                if (known >= 0 && known <= MAX_TEXTURE_SLOT) {
                    textureSlots.put(name, known);
                    usedTextures[known] = true;
                }
            } else if (uniform.type() == UniformType.UNIFORM_BUFFER) {
                int known = MetalBindings.bufferSlot(name);
                if (known >= 0) {
                    bufferSlots.put(name, known);
                }
            } else {
                int texel = MetalBindings.texelBufferSlot(name);
                if (texel < 0) {
                    MetalLogger.warn("pass %s uses unsupported texel buffer %s", create.name(), name);
                    return false;
                }
                bufferSlots.put(name, texel);
            }
        }
        for (BindGroupLayout.UniformDescription uniform : create.uniforms()) {
            String name = uniform.name();
            if (uniform.type() == UniformType.COMBINED_IMAGE_SAMPLER && !textureSlots.containsKey(name)) {
                int slot = 0;
                while (slot <= MAX_TEXTURE_SLOT && usedTextures[slot]) {
                    slot++;
                }
                if (slot > MAX_TEXTURE_SLOT) {
                    MetalLogger.warn("pass %s has too many textures", create.name());
                    return false;
                }
                usedTextures[slot] = true;
                textureSlots.put(name, slot);
            } else if (uniform.type() == UniformType.UNIFORM_BUFFER && !bufferSlots.containsKey(name)) {
                if (nextFree >= FREE_BUFFER_SLOTS.length) {
                    MetalLogger.warn("pass %s has too many uniform blocks", create.name());
                    return false;
                }
                bufferSlots.put(name, FREE_BUFFER_SLOTS[nextFree++]);
            }
        }
        return true;
    }

    private static Map<String, SpvModule.Reflection.Descriptor> descriptors(SpvModule module)
            throws Exception {
        Map<String, SpvModule.Reflection.Descriptor> out = new HashMap<>();
        SpvModule.Reflection reflection = module.reflect();
        if (reflection == null) {
            return out;
        }
        for (SpvModule.Reflection.Descriptor descriptor : reflection.descriptors()) {
            out.put(descriptor.name(), descriptor);
        }
        return out;
    }

    private static void addBinding(List<Integer> table, SpvModule.Reflection.Descriptor descriptor, int kind,
            int slot) {
        if (descriptor == null) {
            return;
        }
        table.add(descriptor.descriptorSetIndex());
        table.add(descriptor.binding());
        table.add(kind);
        table.add(slot);
    }

    private static int[] vertexLayout(BackendRenderPipeline.CreateInfo create) {
        if (create.vertexBuffers().isEmpty() || create.attribBindings().isEmpty()) {
            return null;
        }
        List<Integer> layout = new ArrayList<>();
        layout.add(create.vertexBuffers().get(0).stride());
        for (BackendRenderPipeline.CreateInfo.AttribBinding binding : create.attribBindings()) {
            if (binding.bufferSlot() != 0) {
                continue;
            }
            int metalFormat = MetalCompiledPipeline.vertexFormat(binding.format());
            if (metalFormat == 0) {
                continue;
            }
            layout.add(binding.location());
            layout.add(metalFormat);
            layout.add(binding.offset());
        }
        return layout.size() == 1 ? null : toArray(layout);
    }

    private static int length(ByteBuffer spv) {
        ByteBuffer source = spv.duplicate();
        if (!source.hasRemaining()) {
            source.clear();
        }
        return source.remaining();
    }

    private static ByteBuffer direct(ByteBuffer spv) {
        ByteBuffer source = spv.duplicate();
        if (!source.hasRemaining()) {
            source.clear();
        }
        ByteBuffer copy = ByteBuffer.allocateDirect(source.remaining()).order(ByteOrder.nativeOrder());
        copy.put(source);
        copy.flip();
        return copy;
    }

    private static int[] toArray(List<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    long vertexLibrary() {
        return vertexLibrary;
    }

    long fragmentLibrary() {
        return fragmentLibrary;
    }

    int[] vertexLayout() {
        return vertexLayout;
    }

    int bufferSlot(String name) {
        Integer slot = bufferSlots.get(name);
        return slot != null ? slot : -1;
    }

    int textureSlot(String name) {
        Integer slot = textureSlots.get(name);
        return slot != null ? slot : -1;
    }

    void release() {
        MetalNative.nRelease(vertexLibrary);
        MetalNative.nRelease(fragmentLibrary);
    }
}
