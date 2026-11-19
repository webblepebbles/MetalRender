package com.pebbles_boon.metalrender.backend.metal;

import com.mojang.renderpearl.backend.common.BaseGpuTextureView;

public final class MetalTextureView extends BaseGpuTextureView {
    private long handle;
    private boolean closed;

    MetalTextureView(MetalTexture texture, int baseMipLevel, int mipLevels) {
        super(texture, baseMipLevel, mipLevels);
        this.handle = MetalNative.nTextureView(texture.handle(), texture.pixelFormat(),
                texture.textureType(), baseMipLevel, mipLevels, 0, texture.layers());
        if (handle == 0) {
            throw new IllegalStateException("Metal texture view creation failed for "
                    + texture.getLabel() + ": " + MetalNative.nLastError());
        }
    }

    @Override
    //VERY IMPORTANT!!! Read AGENTS.md first (or git history if it is missing). Saves you tokens!
    public MetalTexture texture() {
        return (MetalTexture) super.texture();
    }

    public long handle() {
        return handle;
    }

    @Override
    public boolean isClosed() {
        return closed || texture().isClosed();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (handle != 0) {
                MetalNative.nRelease(handle);
                handle = 0;
            }
        }
    }
}
