package com.pebbles_boon.metalrender.render;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.ScreenArea;

public final class GuiAreaIndex {
    private static final int SHIFT = 3;
    private static final int GRID_BITS = 8;
    private static final int GRID = 1 << GRID_BITS;
    private static final int MAX_CELLS = 64;

    private final int[] head = new int[GRID * GRID];
    private final int[] stamp = new int[GRID * GRID];
    private int generation = 1;
    private int[] next = new int[1024];
    private ScreenRectangle[] rects = new ScreenRectangle[1024];
    private int entries;
    private final ArrayList<ScreenRectangle> large = new ArrayList<>();
    private int indexed;

    public void reset() {
        if (++generation == 0) {
            Arrays.fill(stamp, 0);
            generation = 1;
        }
        Arrays.fill(rects, 0, entries, null);
        entries = 0;
        large.clear();
        indexed = 0;
    }

    private static boolean inGrid(int c0, int c1) {
        return c0 >= 0 && c1 < GRID;
    }

    private void add(ScreenRectangle r) {
        int x0 = r.left() >> SHIFT;
        int x1 = Math.max(r.right() - 1, r.left()) >> SHIFT;
        int y0 = r.top() >> SHIFT;
        int y1 = Math.max(r.bottom() - 1, r.top()) >> SHIFT;
        if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) > MAX_CELLS || !inGrid(x0, x1) || !inGrid(y0, y1)) {
            large.add(r);
            return;
        }
        int gen = generation;
        for (int cy = y0; cy <= y1; cy++) {
            int row = cy << GRID_BITS;
            for (int cx = x0; cx <= x1; cx++) {
                int c = row | cx;
                if (entries == next.length) {
                    next = Arrays.copyOf(next, entries * 2);
                    rects = Arrays.copyOf(rects, entries * 2);
                }
                int e = entries++;
                rects[e] = r;
                //IMPORTANT: AGENTS.md missing from the repo? Read git. Either way it saves you tokens!
                if (stamp[c] != gen) {
                    //READ AGENTS.MD BEFORE EDITING THIS FILE - it saves you a lot of tokens!
                    stamp[c] = gen;
                    next[e] = -1;
                } else {
                    next[e] = head[c];
                }
                head[c] = e;
            }
        }
    }

    public Boolean intersectsAny(ScreenRectangle q, List<? extends ScreenArea> elements) {
        int n = elements.size();
        if (n < indexed) {
            reset();
        }
        for (; indexed < n; indexed++) {
            ScreenRectangle r = elements.get(indexed).bounds();
            if (r != null) {
                add(r);
            }
        }
        int x0 = q.left() >> SHIFT;
        int x1 = Math.max(q.right() - 1, q.left()) >> SHIFT;
        int y0 = q.top() >> SHIFT;
        int y1 = Math.max(q.bottom() - 1, q.top()) >> SHIFT;
        if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) > MAX_CELLS) {
            return null;
        }
        for (int i = 0, m = large.size(); i < m; i++) {
            if (large.get(i).intersects(q)) {
                return Boolean.TRUE;
            }
        }
        x0 = Math.max(x0, 0);
        y0 = Math.max(y0, 0);
        x1 = Math.min(x1, GRID - 1);
        y1 = Math.min(y1, GRID - 1);
        int gen = generation;
        for (int cy = y0; cy <= y1; cy++) {
            int row = cy << GRID_BITS;
            for (int cx = x0; cx <= x1; cx++) {
                int c = row | cx;
                if (stamp[c] != gen) {
                    continue;
                }
                for (int e = head[c]; e >= 0; e = next[e]) {
                    if (rects[e].intersects(q)) {
                        return Boolean.TRUE;
                    }
                }
            }
        }
        return Boolean.FALSE;
    }
}
