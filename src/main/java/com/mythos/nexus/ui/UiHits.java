package com.mythos.nexus.ui;

import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/** Click regions recorded during draw, so click() always matches what was drawn. */
final class UiHits {
    private final List<int[]> rects = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();

    void clear() { rects.clear(); actions.clear(); }

    void add(int x, int y, int w, int h, Runnable r) {
        if (w <= 0 || h <= 0) return;
        rects.add(new int[]{x, y, w, h});
        actions.add(r);
    }

    /** Adds only the part of the rect inside [top, bottom). */
    void addClipped(int x, int y, int w, int h, int top, int bottom, Runnable r) {
        int y0 = Math.max(y, top), y1 = Math.min(y + h, bottom);
        if (y1 > y0) add(x, y0, w, y1 - y0, r);
    }

    /** Later regions are on top (buttons inside rows), so they win. */
    boolean click(int mx, int my) {
        for (int i = rects.size() - 1; i >= 0; i--) {
            int[] r = rects.get(i);
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                actions.get(i).run();
                return true;
            }
        }
        return false;
    }

    static void scissor(NexusScreen s, int x, int y, int w, int h) {
        int scale = s.guiScaleFactor;
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * scale, s.mc().displayHeight - (y + h) * scale,
                Math.max(0, w * scale), Math.max(0, h * scale));
    }

    static void endScissor() { GL11.glDisable(GL11.GL_SCISSOR_TEST); }

    static void scrollbar(int x, int top, int viewH, int contentH, int scroll, int color) {
        if (contentH <= viewH || viewH <= 0) return;
        int th = Math.max(12, viewH * viewH / contentH);
        int ty = top + (viewH - th) * scroll / Math.max(1, contentH - viewH);
        Ui.fill(x, ty, 3, th, color);
    }
}
