package com.pebbles_boon.metalrender.backend.metal;

import java.util.ArrayList;
import java.util.List;

final class MetalStagingRing {
    static final long BLOCK_SIZE = 16L * 1024L * 1024L;

    static final class Block {
        final long handle;
        final long contents;
        final long capacity;
        long offset;
        long lastUseSerial;
        MetalBuffer wrapper;

        boolean inUse(long completedSerial) {
            return lastUseSerial > completedSerial || (wrapper != null && wrapper.lastUseFrame > completedSerial);
        }

        Block(long handle, long contents, long capacity) {
            this.handle = handle;
            this.contents = contents;
            this.capacity = capacity; 
        }
    }

    record Allocation(long buffer, long offset, long address, Block block) {
    }

    private final long device;
    private final List<Block> blocks = new ArrayList<>();
    private Block current;

    MetalStagingRing(long device) {
        this.device = device;
    }

    Allocation allocate(long size, long alignment, long openSerial, long completedSerial) {
        long align = Math.max(4, alignment);
        if (current != null) {
            long start = (current.offset + align - 1) / align * align;
            if (start + size <= current.capacity) {
                current.offset = start + size;
                current.lastUseSerial = openSerial;
                return new Allocation(current.handle, start, current.contents + start, current);
            }
        }
        Block chosen = null;
        for (int i = blocks.size() - 1; i >= 0; i--) {
            Block block = blocks.get(i);
            if (block == current || block.inUse(completedSerial)) {
                continue;
            }
            if (block.capacity > BLOCK_SIZE && block.capacity < size) {
                MetalNative.nRelease(block.handle);
                blocks.remove(i);
                continue;
            }
            if (block.capacity >= size) {
                chosen = block;
                break;
            }
        }
        if (chosen == null) {
            long capacity = Math.max(BLOCK_SIZE, size);
            long handle = MetalNative.nBufferCreate(device, capacity, true);
            if (handle == 0) {
                throw new IllegalStateException("Metal staging allocation of " + capacity
                        + " bytes failed: " + MetalNative.nLastError());
            }
            chosen = new Block(handle, MetalNative.nBufferContents(handle), capacity);
            blocks.add(chosen);
        }
        chosen.offset = size;
        chosen.lastUseSerial = openSerial;
        current = chosen;
        return new Allocation(chosen.handle, 0, chosen.contents, chosen);
    }

    //READ AGENTS.MD BEFORE EDITING THIS FILE - it saves you a lot of tokens!
    void trim(long completedSerial) {
        for (int i = blocks.size() - 1; i >= 0; i--) {
            Block block = blocks.get(i);
            if (block != current && block.capacity > BLOCK_SIZE && !block.inUse(completedSerial)) {
                MetalNative.nRelease(block.handle);
                blocks.remove(i);
            }
        }
    }

    void close() {
        for (Block block : blocks) {
            MetalNative.nRelease(block.handle);
        }
        blocks.clear();
        current = null;
    }
}
