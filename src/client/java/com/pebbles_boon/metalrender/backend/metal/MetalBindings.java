package com.pebbles_boon.metalrender.backend.metal;

import com.pebbles_boon.metalrender.util.MetalLogger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class MetalBindings {
    static final int CUBE_TEXTURE_SLOT = 15;

    private record Block(int slot, int id) {
    }

    private static final Map<String, Block> BLOCKS = new HashMap<>();
    private static final Map<String, Integer> TEXTURES = new HashMap<>();

    static {
        BLOCKS.put("Projection", new Block(16, 0));
        BLOCKS.put("DynamicTransforms", new Block(19, 1));
        BLOCKS.put("Fog", new Block(18, 2));
        BLOCKS.put("Globals", new Block(17, 3));
        BLOCKS.put("Lighting", new Block(20, 4));
        BLOCKS.put("ChunkSection", new Block(21, 5));
        BLOCKS.put("LightmapInfo", new Block(22, 6));
        BLOCKS.put("SpriteAnimationInfo", new Block(23, 7));
        BLOCKS.put("SamplerInfo", new Block(25, 8));
        BLOCKS.put("BlurConfig", new Block(26, 9));
        BLOCKS.put("BlitConfig", new Block(26, 10));
        BLOCKS.put("ColorConfig", new Block(26, 11));
        BLOCKS.put("BitsConfig", new Block(26, 12));
        BLOCKS.put("InvertConfig", new Block(26, 13));
        BLOCKS.put("RotScaleConfig", new Block(27, 14));
        BLOCKS.put("SpiderConfig", new Block(26, 15));
        BLOCKS.put("CloudInfo", new Block(24, 16));
        BLOCKS.put("TerrainUniform", new Block(29, 17));

        TEXTURES.put("Sampler0", 0);
        TEXTURES.put("Sampler1", 1);
        TEXTURES.put("Sampler2", 2);
        TEXTURES.put("DissolveMaskSampler", 3);
        TEXTURES.put("GlintSampler", 4);
        TEXTURES.put("InSampler", 0);
        TEXTURES.put("BlurSampler", 1);
        TEXTURES.put("Sprite", 0);
        TEXTURES.put("CurrentSprite", 0);
        TEXTURES.put("NextSprite", 1);
        TEXTURES.put("MainSampler", 0);
        TEXTURES.put("MainDepthSampler", 1);
        TEXTURES.put("TranslucentSampler", 2);
        TEXTURES.put("TranslucentDepthSampler", 3);
        TEXTURES.put("ItemEntitySampler", 4);
        TEXTURES.put("ItemEntityDepthSampler", 5);
        TEXTURES.put("ParticlesSampler", 6);
        TEXTURES.put("ParticlesDepthSampler", 7);
        TEXTURES.put("WeatherSampler", 8);
        TEXTURES.put("WeatherDepthSampler", 9);
        TEXTURES.put("CloudsSampler", 10);
        TEXTURES.put("CloudsDepthSampler", 11);
    }

    private final int[] blockSizes;
    private final Set<String> checked = new HashSet<>();

    MetalBindings(int[] blockSizes) {
        this.blockSizes = blockSizes;
    }

    static int bufferSlot(String name) {
        Block block = BLOCKS.get(name);
        return block != null ? block.slot : -1;
    }

    static int texelBufferSlot(String name) {
        return "CloudFaces".equals(name) ? 28 : -1;
    }

    static int textureSlot(String name, int position) {
        Integer slot = TEXTURES.get(name);
        return slot != null ? slot : position;
    }

    void checkSize(String name, long length) {
        if (checked.contains(name)) {
            return;
        }
        Block block = BLOCKS.get(name);
        if (block == null || block.id < 0 || blockSizes == null || block.id >= blockSizes.length) {
            checked.add(name);
            return;
        }
        int expected = blockSizes[block.id];
        if (length < expected) {
            throw new IllegalStateException("Uniform block " + name + " is " + length
                    + " bytes but the Metal layout needs " + expected);
        }
        if (length > expected && !"SamplerInfo".equals(name)) {
            MetalLogger.warn("uniform block %s is %d bytes, Metal layout reads %d", name, length,
                    expected);
        }
        checked.add(name);
    }
}
