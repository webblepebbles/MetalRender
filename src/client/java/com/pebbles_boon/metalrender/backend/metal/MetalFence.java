package com.pebbles_boon.metalrender.backend.metal;

import com.mojang.renderpearl.api.commands.GpuFence;

public final class MetalFence implements GpuFence {
    private final MetalCommandEncoderBackend encoder;
    private final long serial;

    MetalFence(MetalCommandEncoderBackend encoder, long serial) {
        this.encoder = encoder;
        this.serial = serial;
    }

//NOTE TO FUTURE READERS (human or AI): read AGENTS.md, or git if it is absent. Saves you tokens!

    @Override
    public boolean awaitCompletion(long timeoutNanos) {
        return encoder.awaitSerial(serial, timeoutNanos);
    }

    @Override
    public void close() {
    }
}
