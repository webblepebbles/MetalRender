package com.pebbles_boon.metalrender.backend.metal;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;

public final class MetalTexture extends BaseGpuTexture {
    private long handle;
    private final int pixelFormat;
    private final int textureType;
    private final int layers;

    MetalTexture(MetalDeviceBackend device, int usage, String label, GpuFormat format, int width,
            int height, int depthOrLayers, int mipLevels) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        this.pixelFormat = pixelFormat(format);
        boolean cube = (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
        if (cube) {
            this.textureType = MetalNative.TEXTURE_CUBE;
        } else if (depthOrLayers > 1) {
            this.textureType = MetalNative.TEXTURE_2D_ARRAY;
        } else {
            this.textureType = MetalNative.TEXTURE_2D;
        }
        this.layers = cube ? 6 : Math.max(1, depthOrLayers);
        int metalUsage = MetalNative.USAGE_PIXEL_FORMAT_VIEW;
        if ((usage & GpuTexture.USAGE_TEXTURE_BINDING) != 0) {
            metalUsage |= MetalNative.USAGE_SHADER_READ;
        }
        boolean attachment = (usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0;
        if (attachment) {
            metalUsage |= MetalNative.USAGE_RENDER_TARGET;
        }
        int storage = attachment ? MetalNative.STORAGE_PRIVATE : MetalNative.STORAGE_SHARED;
        this.handle = MetalNative.nTextureCreate(device.deviceHandle(), textureType, pixelFormat,
                width, height, depthOrLayers, mipLevels, metalUsage, storage);
        if (handle == 0) {
            throw new IllegalStateException("Metal texture creation failed for " + label + ": "
                    + MetalNative.nLastError());
        }
        if (label != null) {
            MetalNative.nSetLabel(handle, label);
        }
    }

    static int pixelFormat(GpuFormat format) {
        return switch (format) {
            case R8_UNORM -> MetalNative.PIXEL_R8;
            case R8_SNORM -> 12;
            case R8_UINT -> 13;
            case R8_SINT -> MetalNative.PIXEL_R8I;
            case RG8_UNORM -> 30;
            case RG8_SNORM -> 32;
            case RG8_UINT -> 33;
            case RG8_SINT -> 34;
            case RGBA8_UNORM -> MetalNative.PIXEL_RGBA8;
            case RGBA8_SNORM -> 72;
            case RGBA8_UINT -> 73;
            case RGBA8_SINT -> 74;
            case R16_UNORM -> 20;
            case R16_SNORM -> 22;
            case R16_UINT -> 23;
            case R16_SINT -> 24;
            case R16_FLOAT -> 25;
            case RG16_UNORM -> 60;
            case RG16_SNORM -> 62;
            case RG16_UINT -> 63;
            case RG16_SINT -> 64;
            case RG16_FLOAT -> 65;
            case RGBA16_UNORM -> 110;
            case RGBA16_SNORM -> 112;
            case RGBA16_UINT -> 113;
            case RGBA16_SINT -> 114;
            case RGBA16_FLOAT -> 115;
            case R32_UINT -> 53;
            case R32_SINT -> 54;
            case R32_FLOAT -> 55;
            case RG32_UINT -> 103;
            case RG32_SINT -> 104;
            case RG32_FLOAT -> 105;
            case RGBA32_UINT -> 123;
            case RGBA32_SINT -> 124;
            case RGBA32_FLOAT -> 125;
            case D32_FLOAT -> MetalNative.PIXEL_DEPTH32;
            default -> throw new IllegalArgumentException("Unsupported texture format " + format);
        };
    }

    public long handle() {
        return handle;
    }

    int pixelFormat() {
        return pixelFormat;
    }

    int textureType() {
        return textureType;
    }

    int layers() {
        return layers;
    }

    boolean isCube() {
        return textureType == MetalNative.TEXTURE_CUBE;
    }

    @Override
    public boolean isClosed() {
        return handle == 0;
    }

    @Override
    public void close() {
        if (handle != 0) {
            MetalNative.nRelease(handle);
            handle = 0;
        }
    }
}
