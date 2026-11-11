package com.pebbles_boon.metalrender.render.far;

public final class FarDownsample {
    private static final ThreadLocal<int[][]> SCRATCH = ThreadLocal.withInitial(() -> new int[6][1024]);

    private FarDownsample() {
    }

    public static int[] merge(int[] a, int[] b, int[] c, int[] d, int shift) {
        int sub = 1 << shift;
        int[][] s = SCRATCH.get();
        int[] cellState = s[0];
        int[] cellLo = s[1];
        int[] cellTint = s[2];
        int[] cellTop = s[3];
        int[] cellCov = s[4];
        int[] cellLeaf = s[5];
        int maxCell = 0;
        int[][] kids = { a, b, c, d };
        for (int[] k : kids) {
            for (int i = 0; i < k.length; i += 3) {
                int top = FarTileData.y0(k[i]) + FarTileData.len(k[i]);
                maxCell = Math.max(maxCell, (top + sub - 1) >> shift);
            }
        }
        if (maxCell == 0) {
            return FarTileData.EMPTY;
        }
        maxCell = Math.min(maxCell, 1023);
        java.util.Arrays.fill(cellState, 0, maxCell, -1);
        java.util.Arrays.fill(cellTop, 0, maxCell, -1);
        java.util.Arrays.fill(cellCov, 0, maxCell, 0);
        java.util.Arrays.fill(cellLeaf, 0, maxCell, 0);
        for (int[] k : kids) {
            for (int i = 0; i < k.length; i += 3) {
                int lo = k[i];
                int y0 = FarTileData.y0(lo);
                int top = y0 + FarTileData.len(lo);
                int p0 = y0 >> shift;
                int p1 = Math.min(maxCell, (top + sub - 1) >> shift);
                boolean leaf = FarStateIds.isLeaf(k[i + 1]);
                boolean water = k[i + 1] == FarStateIds.WATER;
                for (int p = p0; p < p1; p++) {
                    int overlap = Math.min(top, (p + 1) * sub) - Math.max(y0, p * sub);
                    cellCov[p] += overlap;
                    if (leaf) {
                        cellLeaf[p] += overlap;
                    }
                    if (top > cellTop[p] || (top == cellTop[p] && !water && cellState[p] == FarStateIds.WATER)) {
                        cellTop[p] = top;
                        cellState[p] = k[i + 1];
                        cellLo[p] = lo;
                        cellTint[p] = k[i + 2];
                    }
                }
            }
        }
        for (int q = 0; q < maxCell; q++) {
            if (cellState[q] < 0 || cellState[q] == FarStateIds.WATER) {
                continue;
            }
            if (FarStateIds.isLeaf(cellState[q]) ? cellLeaf[q] < (3 * sub + 1) / 2 : cellCov[q] < 2 * sub) {
                cellState[q] = -1;
            }
        }
        int runs = 0;
        int[] out = new int[maxCell * 3];
        int p = 0;
        while (p < maxCell) {
            if (cellState[p] < 0) {
                p++;
                continue;
            }
            int st = cellState[p];
            int start = p;
            int topCell = p;
            while (p < maxCell && cellState[p] == st) {
                topCell = p;
                p++;
            }
            int len = p - start;
            int src = cellLo[topCell];
            out[runs * 3] = FarTileData.lo(start, len, FarTileData.sky(src), FarTileData.block(src));
            out[runs * 3 + 1] = st;
            out[runs * 3 + 2] = cellTint[topCell];
            runs++;
        }
        return java.util.Arrays.copyOf(out, runs * 3);
    }
}
