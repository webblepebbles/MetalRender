package com.pebbles_boon.metalrender.culling;

import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.util.MetalLogger;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

public class CullingOrcreator {
    public static final int CLUSTER_X = 8;
    public static final int CLUSTER_Z = 8;
    private static final int MAX_REGIONS = 4096;

    private final long[] visibleClusterKeys = new long[MAX_REGIONS];
    private final LongOpenHashSet visibleClusterSet = new LongOpenHashSet();
    private volatile long lastUploadNs;
    private volatile int visibleClusterCount;
    private volatile boolean active;
    private volatile boolean cpuFallbackEnabled;
    private volatile int droppedClusterCount;
    private volatile long lastCapWarnMs;
    private volatile boolean capped;

    private int[] sectionVisits = new int[0];
    private byte[] sectionQueued = new byte[0];
    private byte[] sectionExpanded = new byte[0];
    private byte[] sectionPendingExits = new byte[0];
    private long[] sectionQueue = new long[0];
    private long[] sectionReached = new long[0];
    private int sectionToken;
    private long[] sectionMasks = new long[0];
    //NOTE TO FUTURE READERS (human or AI): read AGENTS.md, or git if it is absent. Saves you tokens!
    private static final int[] SECTION_DX = {0, 0, 0, 0, -1, 1};
    private static final int[] SECTION_DY = {-1, 1, 0, 0, 0, 0};
    private static final int[] SECTION_DZ = {0, 0, -1, 1, 0, 0};

    private static final boolean DIRECTIONAL_WALK =
            com.pebbles_boon.metalrender.config.MetalRenderConfig.getBool("metalrender.directionalCaveWalk", true);

    private static int outwardExits(int x, int y, int z, int camX, int camY, int camZ) {
        if (!DIRECTIONAL_WALK) return 63;
        int m = 0;
        if (y <= camY) m |= 1;
        if (y >= camY) m |= 2;
        if (z <= camZ) m |= 4;
        if (z >= camZ) m |= 8;
        if (x <= camX) m |= 16;
        if (x >= camX) m |= 32;
        return m;
    }

    private static long sectionKey(int x, int y, int z) {
        return ((long) (x & 0x3fffff) << 42) | ((long) (y & 0xfffff) << 22) | (z & 0x3fffff);
    }

    public long[] computeVisibleSections(int camSecX, int camSecY, int camSecZ, int radiusChunks,
            int minSecY, int maxSecY, it.unimi.dsi.fastutil.longs.Long2LongMap masks) {
        return walkSections(camSecX, camSecY, camSecZ, radiusChunks, minSecY, maxSecY, masks, false);
    }

    public long[] computeVisibleLoadedSections(int camSecX, int camSecY, int camSecZ, int radiusChunks,
            int minSecY, int maxSecY, it.unimi.dsi.fastutil.longs.Long2LongMap masks) {
        if (camSecY < minSecY || camSecY > maxSecY || radiusChunks < 0) return new long[0];
        int low = camSecY, high = camSecY;
        boolean loaded = false;
        var entries = it.unimi.dsi.fastutil.longs.Long2LongMaps.fastIterator(masks);
        while (entries.hasNext()) {
            long key = entries.next().getLongKey();
            int x = (int) (key >> 42), y = (int) (key << 22 >> 44), z = (int) (key << 42 >> 42);
            if (Math.abs((long) x - camSecX) <= radiusChunks && Math.abs((long) z - camSecZ) <= radiusChunks
                    && y >= minSecY && y <= maxSecY) {
                low = Math.min(low, y);
                high = Math.max(high, y);
                loaded = true;
            }
        }
        if (!loaded) return new long[0];
        return walkSections(camSecX, camSecY, camSecZ, radiusChunks,
                Math.max(minSecY, low - 1), Math.min(maxSecY, high + 1), masks, true);
    }

    private long[] walkSections(int camSecX, int camSecY, int camSecZ, int radiusChunks,
            int minSecY, int maxSecY, it.unimi.dsi.fastutil.longs.Long2LongMap masks, boolean loadedOnly) {
        if (radiusChunks < 0 || camSecY < minSecY || camSecY > maxSecY) return new long[0];
        long width = 2L * radiusChunks + 1;
        long height = (long) maxSecY - minSecY + 1;
        if (height <= 0 || width > 46340 || height > Integer.MAX_VALUE / (width * width)) return new long[0];
        int w = (int) width, h = (int) height, size = w * w * h;
        if (sectionVisits.length < size) {
            sectionVisits = new int[size];
            sectionMasks = new long[size];
            sectionQueued = new byte[size];
            sectionExpanded = new byte[size];
            sectionPendingExits = new byte[size];
            sectionQueue = new long[size];
            sectionReached = new long[size];
            sectionToken = 0;
        }
        if (++sectionToken == (1 << 26)) {
            java.util.Arrays.fill(sectionVisits, 0);
            sectionToken = 1;
        }
        int stamp = sectionToken << 6;
        int x0 = camSecX - radiusChunks, z0 = camSecZ - radiusChunks;
        java.util.Arrays.fill(sectionMasks, 0, size, (1L << 36) - 1);
        var entries = it.unimi.dsi.fastutil.longs.Long2LongMaps.fastIterator(masks);
        while (entries.hasNext()) {
            var entry = entries.next();
            long key = entry.getLongKey();
            int x = (int) (key >> 42), y = (int) (key << 22 >> 44), z = (int) (key << 42 >> 42);
            if (x >= x0 && x < x0 + w && z >= z0 && z < z0 + w && y >= minSecY && y <= maxSecY) {
                sectionMasks[((x - x0) * h + y - minSecY) * w + z - z0] = entry.getLongValue() | Long.MIN_VALUE;
            }
        }
        int startIndex = (radiusChunks * h + camSecY - minSecY) * w + radiusChunks;
        long cameraKey = sectionKey(camSecX, camSecY, camSecZ);
        sectionVisits[startIndex] = stamp | 63;
        sectionExpanded[startIndex] = 0;
        sectionPendingExits[startIndex] = 63;
        sectionQueued[startIndex] = 1;
        sectionQueue[0] = cameraKey;
        int reached = 0;
        if (!loadedOnly || sectionMasks[startIndex] < 0) sectionReached[reached++] = cameraKey;
        int head = 0, tail = size == 1 ? 0 : 1, queued = 1;
        while (queued > 0) {
            if ((head & 1023) == 0 && Thread.currentThread().isInterrupted()) return new long[0];
            long key = sectionQueue[head];
            if (++head == size) head = 0;
            queued--;
            int x = (int) (key >> 42), y = (int) (key << 22 >> 44), z = (int) (key << 42 >> 42);
            int index = ((x - x0) * h + y - minSecY) * w + z - z0;
            sectionQueued[index] = 0;
            int freshExits = sectionPendingExits[index] & ~sectionExpanded[index]
                    & outwardExits(x, y, z, camSecX, camSecY, camSecZ);
            sectionExpanded[index] |= (byte) freshExits;
            for (int direction = 0; direction < 6; direction++) {
                if ((freshExits & (1 << direction)) == 0) continue;
                int nx = x + SECTION_DX[direction], ny = y + SECTION_DY[direction], nz = z + SECTION_DZ[direction];
                if (nx < x0 || nx >= x0 + w || nz < z0 || nz >= z0 + w || ny < minSecY || ny > maxSecY) continue;
                int nextIndex = ((nx - x0) * h + ny - minSecY) * w + nz - z0;
                int nextIncoming = 1 << (direction ^ 1);
                boolean first = (sectionVisits[nextIndex] >>> 6) != sectionToken;
                if (first) {
                    sectionVisits[nextIndex] = stamp;
                    sectionExpanded[nextIndex] = 0;
                    sectionPendingExits[nextIndex] = 0;
                    sectionQueued[nextIndex] = 0;
                    if (!loadedOnly || sectionMasks[nextIndex] < 0) sectionReached[reached++] = sectionKey(nx, ny, nz);
                } else if ((sectionVisits[nextIndex] & nextIncoming) != 0) continue;
                sectionVisits[nextIndex] |= nextIncoming;
                int nextExits = (int) (sectionMasks[nextIndex] >>> ((direction ^ 1) * 6)) & 63;
                sectionPendingExits[nextIndex] |= (byte) nextExits;
                if (sectionQueued[nextIndex] == 0 && (nextExits & ~sectionExpanded[nextIndex]
                        & outwardExits(nx, ny, nz, camSecX, camSecY, camSecZ)) != 0) {
                    sectionQueue[tail] = sectionKey(nx, ny, nz);
                    if (++tail == size) tail = 0;
                    queued++;
                    sectionQueued[nextIndex] = 1;
                }
            }
        }
        return java.util.Arrays.copyOf(sectionReached, reached);
    }

    private static long clusterKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int floorDiv(int value, int divisor) {
        return Math.floorDiv(value, divisor);
    }

    public boolean markCluster(int clusterX, int clusterZ) {
        if (visibleClusterCount >= MAX_REGIONS) {
            return false;
        }
        long key = clusterKey(clusterX, clusterZ);
        if (!visibleClusterSet.add(key)) {
            return false;
        }
        visibleClusterKeys[visibleClusterCount++] = key;
        return true;
    }

    public void resetMarks() {
        visibleClusterSet.clear();
        visibleClusterCount = 0;
        capped = false;
    }

    public void uploadToGpu(float[] frustumPlanes) {
        if (!active || !NativeBridge.isLibLoaded()) {
            return;
        }
        try {
            if (capped) {
                NativeBridge.nSetClusterCullingEnabled(false);
            } else {
                NativeBridge.nUploadClusterVisibilityKeys(visibleClusterKeys, visibleClusterCount);
                NativeBridge.nSetClusterCullingEnabled(visibleClusterCount > 0);
            }
            lastUploadNs = System.nanoTime();
        } catch (UnsatisfiedLinkError e) {
            MetalLogger.warn("cluster visibility upload missing: " + e.getMessage());
            active = false;
            NativeBridge.nSetClusterCullingEnabled(false);
        }
    }

    public int rebuildFromFrustumCpu(FrustumCuller culler, int chunkRadius,
            float cameraX, float cameraY, float cameraZ) {
        return rebuildFromFrustumCpu(culler, chunkRadius, cameraX, cameraY, cameraZ, -64, 320);
    }

    public int rebuildFromFrustumCpu(FrustumCuller culler, int chunkRadius,
            float cameraX, float cameraY, float cameraZ, int minBlockY, int maxBlockY) {
        if (!active || !cpuFallbackEnabled || culler == null) {
            resetMarks();
            return 0;
        }
        if (maxBlockY < minBlockY) {
            int t = minBlockY;
            minBlockY = maxBlockY;
            maxBlockY = t;
        }
        resetMarks();
        droppedClusterCount = 0;
        int clusterRadius = Math.min(MAX_REGIONS / 2,
                Math.max(1, (chunkRadius + CLUSTER_X - 1) / CLUSTER_X + 1));
        int cameraChunkX = (int) Math.floor(cameraX / 16.0f);
        int cameraChunkZ = (int) Math.floor(cameraZ / 16.0f);
        int centerClusterX = floorDiv(cameraChunkX, CLUSTER_X);
        int centerClusterZ = floorDiv(cameraChunkZ, CLUSTER_Z);
        float relMinY = (float) minBlockY - cameraY;
        float relMaxY = (float) maxBlockY - cameraY;
        for (int dx = -clusterRadius; dx <= clusterRadius; dx++) {
            for (int dz = -clusterRadius; dz <= clusterRadius; dz++) {
                int clusterX = centerClusterX + dx;
                int clusterZ = centerClusterZ + dz;
                float minX = clusterX * CLUSTER_X * 16.0f;
                float minZ = clusterZ * CLUSTER_Z * 16.0f;
                float maxX = minX + CLUSTER_X * 16.0f;
                float maxZ = minZ + CLUSTER_Z * 16.0f;
                if (!culler.testBoundingBox(minX - cameraX, relMinY,
                        minZ - cameraZ, maxX - cameraX, relMaxY,
                        maxZ - cameraZ)) {
                    continue;
                }
                if (!markCluster(clusterX, clusterZ)) {
                    droppedClusterCount++;
                }
            }
        }
        if (droppedClusterCount > 0) {
            capped = true;
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastCapWarnMs > 5000L) {
                lastCapWarnMs = nowMs;
                MetalLogger.warn("cluster cull capped at %d regions, dropped %d (fail-visible)",
                        MAX_REGIONS, droppedClusterCount);
            }
        }
        return visibleClusterCount;
    }

    public void setActive(boolean v) {
        active = v;
        if (!v) {
            resetMarks();
            lastUploadNs = 0L;
        }
        if (NativeBridge.isLibLoaded()) {
            NativeBridge.nSetClusterCullingEnabled(v);
        }
    }

    public void setCpuFallbackEnabled(boolean v) {
        cpuFallbackEnabled = v;
    }

    public boolean isActive() {
        return active;
    }

    public void shutdown() {
        active = false;
        cpuFallbackEnabled = false;
        lastUploadNs = 0L;
        resetMarks();
        if (NativeBridge.isLibLoaded()) {
            NativeBridge.nSetClusterCullingEnabled(false);
        }
    }
}
