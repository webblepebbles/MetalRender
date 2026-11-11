package com.pebbles_boon.metalrender.render.far;

import com.pebbles_boon.metalrender.util.MetalLogger;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public final class FarStore {
    private static final int MAGIC = 0x46415232;
    private static final int FORMAT = 4;
    private static final int LEGACY_FORMAT = 3;
    private static final int REGION = 8;

    private final Path dir;
    private final long memBudget;
    private final long diskBudget;
    private final long seedPrint;
    private final long registryPrint;
    private final LinkedHashMap<Long, FarTileData> mem = new LinkedHashMap<>(256, 0.75f, true);
    private final Set<Long> dirtyRegions = new HashSet<>();
    private final Map<Long, Long> regionSizes = new HashMap<>();
    private final Set<Long> missingOnDisk = new HashSet<>();
    private final Set<Long> loadedRegions = new HashSet<>();
    private final Map<Long, Set<Long>> diskIndex = new HashMap<>();
    private long memBytes;
    private volatile int focusX;
    private volatile int focusZ;
    private boolean diskScanned;

    public FarStore(Path dir, long memBudget, long diskBudget, long seedPrint, long registryPrint) {
        this.dir = dir;
        this.memBudget = memBudget;
        this.diskBudget = diskBudget;
        this.seedPrint = seedPrint;
        this.registryPrint = registryPrint;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            MetalLogger.warn("far store dir failed: %s", e.toString());
        }
    }

    public long seedFingerprint() {
        return seedPrint;
    }

    public void focus(int x, int z) {
        focusX = x;
        focusZ = z;
    }

    private static long regionKey(long tileKey) {
        int l = FarKeys.level(tileKey);
        return FarKeys.key(l, Math.floorDiv(FarKeys.tx(tileKey), REGION), Math.floorDiv(FarKeys.tz(tileKey), REGION));
    }

    private Path regionPath(long rk) {
        return dir.resolve("L" + FarKeys.level(rk) + "_" + FarKeys.tx(rk) + "_" + FarKeys.tz(rk) + ".far");
    }

    public synchronized FarTileData peek(long key) {
        return mem.get(key);
    }

    public FarTileData get(long key, boolean create) {
        synchronized (this) {
            FarTileData t = mem.get(key);
            if (t != null) {
                return t;
            }
        }
        long rk = regionKey(key);
        Map<Long, FarTileData> loaded = null;
        boolean known;
        synchronized (this) {
            Set<Long> idx = diskIndex.get(rk);
            known = missingOnDisk.contains(rk) || loadedRegions.contains(rk) || (idx != null && !idx.contains(key));
        }
        if (!known) {
            loaded = readRegion(rk);
            if (loaded == null) {
                synchronized (this) {
                    missingOnDisk.add(rk);
                }
            }
        }
        if (loaded != null) {
            synchronized (this) {
                loadedRegions.add(rk);
                diskIndex.put(rk, new HashSet<>(loaded.keySet()));
            }
        }
        synchronized (this) {
            FarTileData t = mem.get(key);
            if (t != null) {
                return t;
            }
            if (loaded != null) {
                for (Map.Entry<Long, FarTileData> e : loaded.entrySet()) {
                    if (!mem.containsKey(e.getKey())) {
                        put(e.getValue());
                    }
                }
                t = mem.get(key);
                if (t != null) {
                    return t;
                }
            }
            if (!create) {
                return null;
            }
            t = new FarTileData(key);
            t.fingerprint = seedPrint;
            put(t);
            return t;
        }
    }

    private void put(FarTileData t) {
        mem.put(t.key, t);
        memBytes += t.bytes();
        evict();
    }

    public synchronized void markDirty(FarTileData t) {
        t.dirty = true;
        dirtyRegions.add(regionKey(t.key));
    }

    private void evict() {
        if (memBytes <= memBudget) {
            return;
        }
        var it = mem.entrySet().iterator();
        while (it.hasNext() && memBytes > memBudget * 9 / 10) {
            FarTileData t = it.next().getValue();
            if (t.dirty) {
                continue;
            }
            memBytes -= t.bytes();
            it.remove();
            loadedRegions.remove(regionKey(t.key));
        }
        if (memBytes > memBudget * 2) {
            memBytes = 0;
            for (FarTileData t : mem.values()) {
                memBytes += t.bytes();
            }
        }
    }

    public void flush() {
        List<Long> regions;
        synchronized (this) {
            regions = new ArrayList<>(dirtyRegions);
            dirtyRegions.clear();
        }
        for (long rk : regions) {
            writeRegion(rk);
        }
        enforceDisk();
    }

    private void writeRegion(long rk) {
        boolean complete;
        synchronized (this) {
            complete = loadedRegions.contains(rk) || missingOnDisk.contains(rk);
        }
        Map<Long, FarTileData> merged = complete ? null : readRegion(rk);
        if (merged == null) {
            merged = new HashMap<>();
        }
        List<FarTileData> fresh = new ArrayList<>();
        synchronized (this) {
            for (FarTileData t : mem.values()) {
                if (regionKey(t.key) == rk) {
                    merged.put(t.key, t);
                    if (t.dirty) {
                        fresh.add(t);
                    }
                }
            }
            missingOnDisk.remove(rk);
            loadedRegions.add(rk);
            diskIndex.put(rk, new HashSet<>(merged.keySet()));
        }
        Path path = regionPath(rk);
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (OutputStream fos = Files.newOutputStream(tmp);
                DeflaterOutputStream dos = new DeflaterOutputStream(new BufferedOutputStream(fos),
                        new Deflater(Deflater.BEST_SPEED), 65536);
                DataOutputStream out = new DataOutputStream(dos)) {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT);
            out.writeLong(registryPrint);
            out.writeInt(merged.size());
            for (FarTileData t : merged.values()) {
                writeTile(out, t);
            }
        } catch (IOException e) {
            MetalLogger.warn("far store write failed: %s", e.toString());
            return;
        }
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            synchronized (this) {
                regionSizes.put(rk, Files.size(path));
            }
            for (FarTileData t : fresh) {
                t.dirty = false;
            }
        } catch (IOException e) {
            MetalLogger.warn("far store move failed: %s", e.toString());
        }
    }

    private void writeTile(DataOutputStream out, FarTileData t) throws IOException {
        synchronized (t) {
            out.writeLong(t.key);
            out.writeLong(t.fingerprint);
            out.write(t.state);
            for (int i = 0; i < FarTileData.COLS; i++) {
                int[] c = t.cols[i];
                writeVar(out, c.length / 3);
                for (int k = 0; k < c.length; k += 3) {
                    out.writeInt(c[k]);
                    writeVar(out, c[k + 1]);
                    out.writeByte(c[k + 2] >>> 16);
                    out.writeByte(c[k + 2] >>> 8);
                    out.writeByte(c[k + 2]);
                }
            }
        }
    }

    private Map<Long, FarTileData> readRegion(long rk) {
        Path path = regionPath(rk);
        if (!Files.exists(path)) {
            return null;
        }
        Map<Long, FarTileData> out = new HashMap<>();
        try (InputStream fis = Files.newInputStream(path);
                DataInputStream in = new DataInputStream(
                        new InflaterInputStream(new BufferedInputStream(fis), new java.util.zip.Inflater(), 65536))) {
            int magic = in.readInt();
            int format = in.readInt();
            if (magic != MAGIC || (format != FORMAT && format != LEGACY_FORMAT) || in.readLong() != registryPrint) {
                Files.deleteIfExists(path);
                return null;
            }
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                long key = in.readLong();
                FarTileData t = new FarTileData(key);
                long print = in.readLong();
                in.readFully(t.state);
                boolean seedOk = print == seedPrint;
                for (int c = 0; c < FarTileData.COLS; c++) {
                    int spans = readVar(in);
                    int[] col = new int[spans * 3];
                    for (int k = 0; k < spans; k++) {
                        col[k * 3] = in.readInt();
                        col[k * 3 + 1] = readVar(in);
                        int r = in.readUnsignedByte();
                        int g = in.readUnsignedByte();
                        int b = in.readUnsignedByte();
                        col[k * 3 + 2] = (r << 16) | (g << 8) | b;
                    }
                    if (t.state[c] == FarTileData.SEED && !seedOk) {
                        t.state[c] = FarTileData.MISSING;
                        col = FarTileData.EMPTY;
                    } else if (format == LEGACY_FORMAT && t.level > FarKeys.VCAP) {
                        int factor = 1 << (t.level - FarKeys.VCAP);
                        for (int k = 0; k < col.length; k += 3) {
                            int old = col[k];
                            col[k] = FarTileData.lo(FarTileData.y0(old) * factor, FarTileData.len(old) * factor,
                                    FarTileData.sky(old), FarTileData.block(old));
                        }
                    }
                    t.cols[c] = spans == 0 ? FarTileData.EMPTY : col;
                }
                t.fingerprint = seedPrint;
                out.put(key, t);
            }
        } catch (IOException | RuntimeException e) {
            MetalLogger.warn("far store read failed for %s: %s", path.getFileName(), e.toString());
            return null;
        }
        return out;
    }

    private void enforceDisk() {
        try {
            synchronized (this) {
                if (!diskScanned) {
                    diskScanned = true;
                    try (var stream = Files.list(dir)) {
                        stream.filter(p -> p.getFileName().toString().endsWith(".far")).forEach(p -> {
                            long rk = parseRegion(p.getFileName().toString());
                            if (rk != Long.MIN_VALUE) {
                                try {
                                    regionSizes.put(rk, Files.size(p));
                                } catch (IOException ignored) {
                                }
                            }
                        });
                    }
                }
            }
            long total;
            List<Map.Entry<Long, Long>> entries;
            synchronized (this) {
                total = 0;
                for (long v : regionSizes.values()) {
                    total += v;
                }
                if (total <= diskBudget) {
                    return;
                }
                entries = new ArrayList<>(regionSizes.entrySet());
            }
            int fx = focusX;
            int fz = focusZ;
            entries.sort(
                    (a, b) -> Double.compare(regionDistance(b.getKey(), fx, fz), regionDistance(a.getKey(), fx, fz)));
            for (Map.Entry<Long, Long> e : entries) {
                if (total <= diskBudget * 9 / 10) {
                    break;
                }
                Files.deleteIfExists(regionPath(e.getKey()));
                total -= e.getValue();
                synchronized (this) {
                    regionSizes.remove(e.getKey());
                    missingOnDisk.add(e.getKey());
                    diskIndex.remove(e.getKey());
                }
            }
        } catch (IOException e) {
            MetalLogger.warn("far store disk cap failed: %s", e.toString());
        }
    }

    private static double regionDistance(long rk, int fx, int fz) {
        int span = FarKeys.size(FarKeys.level(rk)) * REGION;
        double cx = (FarKeys.tx(rk) + 0.5) * span - fx;
        double cz = (FarKeys.tz(rk) + 0.5) * span - fz;
        return Math.sqrt(cx * cx + cz * cz) / Math.max(1.0, Math.sqrt(span));
    }

    private static long parseRegion(String name) {
        try {
            String core = name.substring(1, name.length() - 4);
            String[] parts = core.split("_");
            return FarKeys.key(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (RuntimeException e) {
            return Long.MIN_VALUE;
        }
    }

    public synchronized long memoryBytes() {
        return memBytes;
    }

    public synchronized long diskBytes() {
        long total = 0;
        for (long v : regionSizes.values()) {
            total += v;
        }
        return total;
    }

    private static void writeVar(DataOutputStream out, int v) throws IOException {
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    private static int readVar(DataInputStream in) throws IOException {
        int v = 0;
        int shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return v;
            }
            shift += 7;
            if (shift > 28) {
                throw new IOException("bad varint");
            }
        }
    }
}
