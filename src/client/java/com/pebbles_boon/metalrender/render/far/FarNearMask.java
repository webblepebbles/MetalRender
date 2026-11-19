package com.pebbles_boon.metalrender.render.far;

import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import java.util.Arrays;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;

final class FarNearMask {
    private static final int SIZE = 128;

    private byte[] columns = new byte[SIZE * SIZE];
    private byte[] next = new byte[SIZE * SIZE];
    private int minX = 1;
    private int minZ = 1;
    private int maxX;
    private int maxZ;
    private boolean initialized;
    private int pass;
    private final long[] seen = new long[SIZE * SIZE];

    void reset() {
        initialized = false;
        minX = minZ = 1;
        maxX = maxZ = 0;
    }

    boolean owns(int cx, int cz) {
        return cx >= minX && cx <= maxX && cz >= minZ && cz <= maxZ && columns[index(cx, cz)] != 0;
    }

    void update(ClientLevel level, int renderDistance, double camX, double camZ, float farRadius) {
        int oldMinX = minX;
        int oldMinZ = minZ;
        int oldMaxX = maxX;
        int oldMaxZ = maxZ;
        long nowMs = System.currentTimeMillis();
        pass++;
        var worldRenderer = com.pebbles_boon.metalrender.render.MetalWorldRenderer.getInstance();
        var mesher = worldRenderer != null ? worldRenderer.getChunkMesher() : null;
        int minSection = level.getMinSectionY();
        int sections = level.getSectionsCount();
        int camBlockX = Mth.floor(camX);
        int camBlockZ = Mth.floor(camZ);
        int cx = camBlockX >> 4;
        int cz = camBlockZ >> 4;
        int radius = Math.min(renderDistance + 1, SIZE / 2 - 1);
        minX = cx - radius;
        minZ = cz - radius;
        maxX = cx + radius;
        maxZ = cz + radius;

        float fracX = (float) (camX - camBlockX);
        float fracZ = (float) (camZ - camBlockZ);
        float limit = radius * 16.0f;
        float limitSquared = limit * limit;
        var source = level.getChunkSource();
        Arrays.fill(next, (byte) 0);
        for (int z = minZ; z <= maxZ; z++) {
            float dz = (z * 16 - camBlockZ) - fracZ + 8.0f;
            for (int x = minX; x <= maxX; x++) {
                float dx = (x * 16 - camBlockX) - fracX + 8.0f;
                int ix = index(x, z);
                boolean drawnByNear = net.minecraft.server.level.ChunkTrackingView.isInViewDistance(cx, cz,
                        renderDistance, x, z);
                var chunk = drawnByNear && dx * dx + dz * dz <= limitSquared ? source.getChunkNow(x, z) : null;
                if (chunk == null) {
                    seen[ix] = 0L;
                    continue;
                }
                if (seen[ix] == 0L) {
                    seen[ix] = nowMs;
                }
                boolean wasOwned = x >= oldMinX && x <= oldMaxX && z >= oldMinZ && z <= oldMaxZ && columns[ix] != 0;
                if (mesher == null) {
                    next[ix] = 1;
                    continue;
                }
                boolean recheck = wasOwned && ((pass + ix) & 31) == 0;
                if (wasOwned && !recheck) {
                    next[ix] = 1;
                    continue;
                }
                if (!wasOwned && nowMs - seen[ix] > 20000L) {
                    next[ix] = 1;
                    continue;
                }
                boolean ready = true;
                for (int i = 0; i < sections && ready; i++) {
                    if (!chunk.getSection(i).hasOnlyAir() && !mesher.hasMeshIgnoreDirty(x, minSection + i, z)) {
                        ready = false;
                    }
                }
                if (ready) {
                    next[ix] = 1;
                } else if (recheck) {
                    seen[ix] = nowMs;
                }
            }
        }
        NativeBridge.nFarSetView(farRadius, minX, minZ, maxX, maxZ);
        if (!initialized || !Arrays.equals(columns, next)) {
            NativeBridge.nFarSetNearColumns(next);
            byte[] previous = columns;
            columns = next;
            next = previous;
            initialized = true;
        }
    }

    private static int index(int cx, int cz) {
        return (cx & (SIZE - 1)) + (cz & (SIZE - 1)) * SIZE;
    }
}
