package com.pebbles_boon.metalrender.backend.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.renderer.ShaderDefines;

public final class MetalCompiledPipeline implements BackendRenderPipeline {
    private static final int FC_HAS_BLEND = 0;
    private static final int FC_ALPHA_CUTOUT = 1;
    private static final int FC_NO_OVERLAY = 2;
    private static final int FC_EMISSIVE = 3;
    private static final int FC_PER_FACE_LIGHTING = 4;
    private static final int FC_NO_CARDINAL_LIGHTING = 5;
    private static final int FC_APPLY_TEXTURE_MATRIX = 6;
    private static final int FC_DISSOLVE = 7;
    private static final int FC_PORTAL_LAYERS = 8;
    private static final int FC_VS_VARIANT = 9;
    private static final int FC_FS_VARIANT = 10;
    private static final int FC_HAS_COLOR = 11;
    private static final int FC_HAS_UV0 = 12;
    private static final int FC_HAS_UV1 = 13;
    private static final int FC_HAS_UV2 = 14;
    private static final int FC_HAS_NORMAL = 15;
    private static final int FC_HAS_LINE_WIDTH = 16;
    private static final int FC_HAS_POSITION = 17;
    private static final int FC_HAS_PROJECTION = 18;
    private static final int FC_HAS_DYNAMIC_TRANSFORMS = 19;
    private static final int FC_GLINT = 20;
    private static final int FC_GLINT_SPECIAL = 21;
    private static final int FC_HAS_UV3 = 22;

    private final MetalDeviceBackend device;
    private final RenderPipeline info;
    private final String name;
    private final List<String> uniformNames;
    private boolean closed;
    private final FamilyTable.Family family;
    private final boolean drawable;
    private final int[] constants;
    private final int[] vertexLayout;
    private final List<String> samplers;
    private final int[] blend;
    private final int writeMask;
    private final int primitive;
    private final int topology;
    private final boolean triangleFan;
    private final int cullMode;
    private final int fillMode;
    private final float depthBias;
    private final float slopeScale;
    private final int depthCompare;
    private final boolean depthWrite;
    private final Map<Long, Long> states = new HashMap<>();
    private boolean failed;

    MetalCompiledPipeline(MetalDeviceBackend device, BackendRenderPipeline.CreateInfo create,
            RenderPipeline info) {
        this.device = device;
        this.info = info;
        this.name = create.name();
        this.family = info != null ? FamilyTable.lookup(info) : FamilyTable.Family.UNMAPPED;
        this.drawable = info != null && FamilyTable.drawable(info, family);
        List<VertexFormat> bindings = info != null ? info.getVertexFormatBindings() : List.of();
        VertexFormat format = !bindings.isEmpty() ? bindings.get(0) : null;
        this.vertexLayout = buildVertexLayout(create, format);
        List<String> uniformList = new ArrayList<>();
        List<String> samplerList = new ArrayList<>();
        for (BindGroupLayout.UniformDescription u : create.uniforms()) {
            uniformList.add(u.name());
            if (u.type() == UniformType.COMBINED_IMAGE_SAMPLER) {
                samplerList.add(u.name());
            }
        }
        this.uniformNames = uniformList;
        this.samplers = samplerList;
        this.constants = buildConstants(create, info, format);
        ColorTargetState color = create.colorTargetStates().isEmpty() ? ColorTargetState.DEFAULT
                : create.colorTargetStates().get(0);
        Optional<BlendFunction> blendFunction = color.blendFunction();
        if (blendFunction.isPresent()) {
            BlendFunction b = blendFunction.get();
            this.blend = new int[] {1, factor(b.color().sourceFactor()), factor(b.color().destFactor()),
                    factor(b.alpha().sourceFactor()), factor(b.alpha().destFactor())};
        } else {
            this.blend = new int[] {0, 0, 0, 0, 0};
        }
        int mask = 0;
        if (color.writeRed()) {
            mask |= 8;
        }
        if (color.writeGreen()) {
            mask |= 4;
        }
        if (color.writeBlue()) {
            mask |= 2;
        }
        if (color.writeAlpha()) {
            mask |= 1;
        }
        this.writeMask = mask;
        PrimitiveTopology mode = create.primitiveTopology();
        this.triangleFan = mode == PrimitiveTopology.TRIANGLE_FAN;
        this.primitive = switch (mode) {
            case POINTS -> MetalNative.PRIMITIVE_POINT;
            case DEBUG_LINES, LINES -> MetalNative.PRIMITIVE_LINE;
            case DEBUG_LINE_STRIP -> MetalNative.PRIMITIVE_LINE_STRIP;
            case TRIANGLE_STRIP -> MetalNative.PRIMITIVE_TRIANGLE_STRIP;
            default -> MetalNative.PRIMITIVE_TRIANGLE;
        };
        this.topology = switch (mode) {
            case POINTS -> MetalNative.TOPOLOGY_POINT;
            case DEBUG_LINES, LINES, DEBUG_LINE_STRIP -> MetalNative.TOPOLOGY_LINE;
            default -> MetalNative.TOPOLOGY_TRIANGLE;
        };
        this.cullMode = create.cull() ? MetalNative.CULL_BACK : MetalNative.CULL_NONE;
        this.fillMode = create.polygonMode() == PolygonMode.WIREFRAME ? MetalNative.FILL_LINES
                : MetalNative.FILL;
        DepthStencilState depth = create.depthStencilState();
        if (depth != null) {
            this.depthCompare = compare(depth.depthTest());
            this.depthWrite = depth.writeDepth();
            this.depthBias = depth.depthBiasConstant();
            this.slopeScale = depth.depthBiasScaleFactor();
        } else {
            this.depthCompare = MetalNative.COMPARE_ALWAYS;
            this.depthWrite = false;
            this.depthBias = 0.0f;
            this.slopeScale = 0.0f;
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
        release();
    }

    List<String> uniformNames() {
        return uniformNames;
    }

    List<String> samplers() {
        return samplers;
    }

    boolean hasVertexFormat() {
        return vertexLayout != null;
    }

    RenderPipeline info() {
        return info;
    }

    FamilyTable.Family family() {
        return family;
    }

    boolean drawsNothing() {
        return (drawable && !family.implemented()) || name.contains("oit_");
    }

    int primitive() {
        return primitive;
    }

    boolean triangleFan() {
        return triangleFan;
    }

    int cullMode() {
        return cullMode;
    }

    int fillMode() {
        return fillMode;
    }

    float depthBias() {
        return depthBias;
    }

    float slopeScale() {
        return slopeScale;
    }

    long depthState(boolean hasDepthAttachment) {
        if (!hasDepthAttachment) {
            return device.depthState(MetalNative.COMPARE_ALWAYS, false);
        }
        return device.depthState(depthCompare, depthWrite);
    }

    long pipelineState(int colorFormat, int depthFormat) {
        long key = ((long) colorFormat << 32) | (depthFormat & 0xFFFFFFFFL);
        Long cached = states.get(key);
        if (cached != null) {
            return cached;
        }
        if (failed) {
            return 0;
        }
        String vertexFunction;
        String fragmentFunction;
        if (drawable && family.implemented()) {
            vertexFunction = family.vertexFunction();
            fragmentFunction = family.fragmentFunction();
        } else {
            MetalLogger.warn("FALLBACK pipeline %s info=%s family=%s drawable=%s", name, info != null, family,
                    drawable);
            vertexFunction = vertexLayout == null ? "fallback_vertex_empty" : "fallback_vertex";
            fragmentFunction = "fallback_fragment";
        }
        long pso = MetalNative.nRenderPipelineCreate(device.deviceHandle(), device.libraryHandle(),
                vertexFunction, fragmentFunction, constants, vertexLayout, colorFormat, depthFormat,
                blend, writeMask, topology);
        if (pso == 0) {
            failed = true;
            MetalLogger.error("pipeline %s failed: %s", name, MetalNative.nLastError());
            return 0;
        }
        states.put(key, pso);
        return pso;
    }

    void release() {
        for (long pso : states.values()) {
            MetalNative.nRelease(pso);
        }
        states.clear();
        failed = false;
    }

    private static int[] buildVertexLayout(BackendRenderPipeline.CreateInfo create, VertexFormat format) {
        if (format == null || create.vertexBuffers().isEmpty()) {
            return buildVertexLayout(format);
        }
        List<VertexFormatElement> elements = new ArrayList<>(format.getElements());
        List<BackendRenderPipeline.CreateInfo.AttribBinding> bindings = new ArrayList<>(create.attribBindings());
        bindings.sort(java.util.Comparator.comparingInt(BackendRenderPipeline.CreateInfo.AttribBinding::offset));
        List<Integer> layout = new ArrayList<>();
        layout.add(create.vertexBuffers().get(0).stride());
        int cursor = 0;
        for (BackendRenderPipeline.CreateInfo.AttribBinding binding : bindings) {
            if (binding.bufferSlot() != 0) {
                continue;
            }
            VertexFormatElement match = null;
            for (int i = cursor; i < elements.size(); i++) {
                if (elements.get(i).format() == binding.format()) {
                    match = elements.get(i);
                    cursor = i + 1;
                    break;
                }
            }
            if (match == null) {
                continue;
            }
            int attribute = attributeIndex(match);
            int metalFormat = vertexFormat(match);
            if (attribute < 0 || metalFormat == 0) {
                continue;
            }
            layout.add(attribute);
            layout.add(metalFormat);
            layout.add(binding.offset());
        }
        if (layout.size() == 1) {
            return null;
        }
        int[] out = new int[layout.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = layout.get(i);
        }
        return out;
    }

    private static int[] buildVertexLayout(VertexFormat format) {
        if (format == null) {
            return null;
        }
        List<VertexFormatElement> elements = format.getElements();
        if (elements.isEmpty()) {
            return null;
        }
        List<Integer> layout = new ArrayList<>();
        layout.add(format.getVertexSize());
        for (VertexFormatElement element : elements) {
            int attribute = attributeIndex(element);
            int metalFormat = vertexFormat(element);
            if (attribute < 0 || metalFormat == 0) {
                continue;
            }
            layout.add(attribute);
            layout.add(metalFormat);
            layout.add(element.offset());
        }
        int[] out = new int[layout.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = layout.get(i);
        }
        return out;
    }

    static int attributeIndex(VertexFormatElement element) {
        return switch (element.name()) {
            case "Position" -> 0;
            case "Color" -> 1;
            case "UV0" -> 2;
            case "UV1" -> 3;
            case "UV2" -> 4;
            case "Normal" -> 5;
            case "LineWidth" -> 6;
            case "UV3" -> 7;
            default -> -1;
        };
    }

    static int vertexFormat(VertexFormatElement element) {
        GpuFormat format = element.format();
        int count = format.componentCount();
        boolean n;
        int kind;
        switch (format.componentType()) {
            case FLOAT_32 -> {
                n = false;
                kind = 0;
            }
            case UNORM_8 -> {
                n = true;
                kind = 1;
            }
            case UINT_8 -> {
                n = false;
                kind = 1;
            }
            case SNORM_8 -> {
                n = true;
                kind = 2;
            }
            case SINT_8 -> {
                n = false;
                kind = 2;
            }
            case UNORM_16 -> {
                n = true;
                kind = 3;
            }
            case UINT_16 -> {
                n = false;
                kind = 3;
            }
            case SNORM_16 -> {
                n = true;
                kind = 4;
            }
            case SINT_16 -> {
                n = false;
                kind = 4;
            }
            case UINT_32 -> {
                n = false;
                kind = 5;
            }
            case SINT_32 -> {
                n = false;
                kind = 6;
            }
            default -> {
                return 0;
            }
        }
        return switch (kind) {
            case 0 -> switch (count) {
                case 1 -> 28;
                case 2 -> 29;
                case 3 -> 30;
                case 4 -> 31;
                default -> 0;
            };
            case 1 -> switch (count) {
                case 1 -> n ? 47 : 45;
                case 2 -> n ? 7 : 1;
                case 3 -> n ? 8 : 2;
                case 4 -> n ? 9 : 3;
                default -> 0;
            };
            case 2 -> switch (count) {
                case 1 -> n ? 48 : 46;
                case 2 -> n ? 10 : 4;
                case 3 -> n ? 11 : 5;
                case 4 -> n ? 12 : 6;
                default -> 0;
            };
            case 3 -> switch (count) {
                case 1 -> n ? 51 : 49;
                case 2 -> n ? 19 : 13;
                case 3 -> n ? 20 : 14;
                case 4 -> n ? 21 : 15;
                default -> 0;
            };
            case 4 -> switch (count) {
                case 1 -> n ? 52 : 50;
                case 2 -> n ? 22 : 16;
                case 3 -> n ? 23 : 17;
                case 4 -> n ? 24 : 18;
                default -> 0;
            };
            case 5 -> switch (count) {
                case 1 -> 36;
                case 2 -> 37;
                case 3 -> 38;
                case 4 -> 39;
                default -> 0;
            };
            default -> switch (count) {
                case 1 -> 32;
                case 2 -> 33;
                case 3 -> 34;
                case 4 -> 35;
                default -> 0;
            };
        };
    }

    private static int[] buildConstants(BackendRenderPipeline.CreateInfo create, RenderPipeline info,
            VertexFormat format) {
        List<Integer> c = new ArrayList<>();
        ShaderDefines defines = info != null ? info.getShaderDefines() : ShaderDefines.EMPTY;
        boolean hasBlend = !create.colorTargetStates().isEmpty()
                && create.colorTargetStates().get(0).blendFunction().isPresent();
        addBool(c, FC_HAS_BLEND, hasBlend);
        addBool(c, FC_NO_OVERLAY, defines.flags().contains("NO_OVERLAY"));
        addBool(c, FC_EMISSIVE, defines.flags().contains("EMISSIVE"));
        addBool(c, FC_PER_FACE_LIGHTING, defines.flags().contains("PER_FACE_LIGHTING"));
        addBool(c, FC_NO_CARDINAL_LIGHTING, defines.flags().contains("NO_CARDINAL_LIGHTING"));
        addBool(c, FC_APPLY_TEXTURE_MATRIX, defines.flags().contains("APPLY_TEXTURE_MATRIX"));
        addBool(c, FC_DISSOLVE, defines.flags().contains("DISSOLVE"));
        addBool(c, FC_GLINT, defines.flags().contains("GLINT"));
        addBool(c, FC_GLINT_SPECIAL, defines.flags().contains("GLINT_SPECIAL"));
        addBool(c, FC_HAS_UV3, format != null && format.contains("UV3"));
        String cutout = defines.values().get("ALPHA_CUTOUT");
        if (cutout != null) {
            try {
                c.add(FC_ALPHA_CUTOUT);
                c.add(2);
                c.add(Float.floatToRawIntBits(Float.parseFloat(cutout)));
            } catch (NumberFormatException e) {
                c.remove(c.size() - 1);
                c.remove(c.size() - 1);
            }
        }
        String portal = defines.values().get("PORTAL_LAYERS");
        if (portal != null) {
            try {
                int layers = Integer.parseInt(portal.trim());
                c.add(FC_PORTAL_LAYERS);
                c.add(1);
                c.add(layers);
            } catch (NumberFormatException ignored) {
            }
        }
        addInt(c, FC_VS_VARIANT, info != null ? FamilyTable.vertexVariant(info) : -1);
        addInt(c, FC_FS_VARIANT, info != null ? FamilyTable.fragmentVariant(info) : -1);
        addBool(c, FC_HAS_POSITION, format != null && format.contains("Position"));
        addBool(c, FC_HAS_COLOR, format != null && format.contains("Color"));
        addBool(c, FC_HAS_UV0, format != null && format.contains("UV0"));
        addBool(c, FC_HAS_UV1, format != null && format.contains("UV1"));
        addBool(c, FC_HAS_UV2, format != null && format.contains("UV2"));
        addBool(c, FC_HAS_NORMAL, format != null && format.contains("Normal"));
        addBool(c, FC_HAS_LINE_WIDTH, format != null && format.contains("LineWidth"));
        boolean projection = false;
        boolean transforms = false;
        for (BindGroupLayout.UniformDescription u : create.uniforms()) {
            projection |= "Projection".equals(u.name());
            transforms |= "DynamicTransforms".equals(u.name());
        }
        addBool(c, FC_HAS_PROJECTION, projection);
        addBool(c, FC_HAS_DYNAMIC_TRANSFORMS, transforms);
        int[] out = new int[c.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = c.get(i);
        }
        return out;
    }

    private static void addBool(List<Integer> c, int index, boolean value) {
        c.add(index);
        c.add(0);
        c.add(value ? 1 : 0);
    }

    private static void addInt(List<Integer> c, int index, int value) {
        c.add(index);
        c.add(1);
        c.add(value);
    }

    static int compare(CompareOp op) {
        return switch (op) {
            case ALWAYS_PASS -> 7;
            case LESS_THAN -> 1;
            case LESS_THAN_OR_EQUAL -> 3;
            case EQUAL -> 2;
            case NOT_EQUAL -> 5;
            case GREATER_THAN_OR_EQUAL -> 6;
            case GREATER_THAN -> 4;
            case NEVER_PASS -> 0;
        };
    }

    static int factor(BlendFactor f) {
        return switch (f) {
            case ZERO -> 0;
            case ONE -> 1;
            case SRC_COLOR -> 2;
            case ONE_MINUS_SRC_COLOR -> 3;
            case SRC_ALPHA -> 4;
            case ONE_MINUS_SRC_ALPHA -> 5;
            case DST_COLOR -> 6;
            case ONE_MINUS_DST_COLOR -> 7;
            case DST_ALPHA -> 8;
            case ONE_MINUS_DST_ALPHA -> 9;
            case SRC_ALPHA_SATURATE -> 10;
            case CONSTANT_COLOR -> 11;
            case ONE_MINUS_CONSTANT_COLOR -> 12;
            case CONSTANT_ALPHA -> 13;
            case ONE_MINUS_CONSTANT_ALPHA -> 14;
        };
    }
}
