package com.pebbles_boon.metalrender.render.far;

import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.render.chunk.CustomChunkMesher;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.multiplayer.ClientLevel;

public final class FarNearMask {
    private static final int CHECKS_PER_FRAME = 384;

    private final Long2LongOpenHashMap loadedAt = new Long2LongOpenHashMap();
    private final LongOpenHashSet ready = new LongOpenHashSet();
    private final ConcurrentLinkedQueue<Long> invalid = new ConcurrentLinkedQueue<>();
    private final IntArrayList batch = new IntArrayList();
    private int cursor;
    private int frame;

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public void reset() {
        loadedAt.clear();
        ready.clear();
        invalid.clear();
        batch.clear();
        cursor = 0;
    }

    public void onLoaded(int cx, int cz) {
        loadedAt.put(key(cx, cz), System.currentTimeMillis());
    }

    private volatile boolean clearAll;

    public void invalidate(int cx, int cz) {
        invalid.add(key(cx, cz));
    }

    public void invalidateAll() {
        clearAll = true;
    }

    public boolean isReady(int cx, int cz) {
        return ready.contains(key(cx, cz));
    }

    private void set(long k, boolean value) {
        boolean has = ready.contains(k);
        if (has == value) {
            return;
        }
        if (value) {
            ready.add(k);
        } else {
            ready.remove(k);
        }
        batch.add((int) (k >> 32));
        batch.add((int) k);
        batch.add(value ? 1 : 0);
    }

    public void update(ClientLevel level, int rd, int pcx, int pcz) {
        MetalWorldRenderer wr = MetalWorldRenderer.getInstance();
        CustomChunkMesher mesher = wr != null ? wr.getChunkMesher() : null;
        if (mesher == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (clearAll) {
            clearAll = false;
            var all = new LongOpenHashSet(ready);
            var it0 = all.iterator();
            while (it0.hasNext()) {
                set(it0.nextLong(), false);
            }
        }
        Long inv;
        while ((inv = invalid.poll()) != null) {
            set(inv, false);
        }
        int r = rd + 1;
        int side = 2 * r + 1;
        int total = side * side;
        for (int n = 0; n < CHECKS_PER_FRAME && n < total; n++) {
            int idx = cursor++ % total;
            int cx = pcx - r + idx % side;
            int cz = pcz - r + idx / side;
            long k = key(cx, cz);
            double ddx = (cx - pcx) * 16.0;
            double ddz = (cz - pcz) * 16.0;
            double lim = rd * 16.0 - 16.0;
            if (ddx * ddx + ddz * ddz > lim * lim) {
                set(k, false);
                continue;
            }
            long since = loadedAt.get(k);
            if (since == 0L) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) {
                    set(k, false);
                    continue;
                }
                since = now;
                loadedAt.put(k, now);
            }
            set(k, FarTerrainManager.columnReady(level, mesher, cx, cz, now - since));
        }
        if (cursor > total * 1024) {
            cursor = 0;
        }
        if (++frame % 4 == 0) {
            var it = ready.iterator();
            while (it.hasNext()) {
                long k = it.nextLong();
                int cx = (int) (k >> 32);
                int cz = (int) k;
                double ex = (cx - pcx) * 16.0;
                double ez = (cz - pcz) * 16.0;
                double el = rd * 16.0 - 16.0;
                if (Math.abs(cx - pcx) > r || Math.abs(cz - pcz) > r || ex * ex + ez * ez > el * el) {
                    it.remove();
                    batch.add(cx);
                    batch.add(cz);
                    batch.add(0);
                }
            }
            var lit = loadedAt.keySet().iterator();
            while (lit.hasNext()) {
                long k = lit.nextLong();
                int cx = (int) (k >> 32);
                int cz = (int) k;
                if (Math.abs(cx - pcx) > r + 8 || Math.abs(cz - pcz) > r + 8) {
                    lit.remove();
                }
            }
        }
        if (!batch.isEmpty()) {
            int[] arr = batch.toIntArray();
            NativeBridge.nFarSetNearColumns(arr, arr.length / 3);
            batch.clear();
        }
    }
}
