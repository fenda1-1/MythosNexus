package com.mythos.nexus.ui;

import com.mythos.nexus.core.NexusConfig;
import com.mythos.nexus.session.BotSession;
import com.mythos.nexus.session.SessionManager;
import com.mythos.nexus.session.TickScheduler;
import org.lwjgl.input.Keyboard;

import static com.mythos.nexus.ui.NexusGeometry.hit;
import static com.mythos.nexus.ui.NexusGeometry.sessionKey;

/**
 * "tickrate" overlay — FPS floor + per-priority-band tick-rate curve editor.
 *
 * Layout:
 *   [top]    minFps input + adaptive on/off + current capacity readout
 *   [middle] draggable curve editor: 6 nodes (one per band), Y = tick %
 *   [bottom] per-band "寻路中" bars showing the scheduler's current output
 *
 * The curve is the *manual* value each band gets when the scheduler runs at
 * capacity=1.0. With adaptive on, actual pct = curve × capacity — so the
 * curve sets the priority split, the controller sets how much total headroom
 * the foreground player gets to keep.
 */
final class TickrateOverlay implements NexusOverlay {

    private static final int W = 320, H = 300;
    private static final int CURVE_W = W - 32, CURVE_H = 90;
    private static final int NODE_R = 5;

    private final NexusScreen s;

    /** Which curve node is being dragged, -1 = none. */
    private int dragNode = -1;

    TickrateOverlay(NexusScreen s) { this.s = s; }

    @Override public String id() { return "tickrate"; }

    private int x() { return (s.width - W) / 2; }
    private int y() { return (s.height - H) / 2; }


    private int curveX() { return x() + 16; }
    private int curveY() { return y() + 90; }
    /** Node cx for band i — evenly spaced inside the curve editor. */
    private int nodeX(int band) {
        return curveX() + 8 + band * (CURVE_W - 16) / 5;
    }
    /** Node cy for pct — 0% at bottom, 100% at top of the editor. */
    private int nodeY(int pct) {
        return curveY() + CURVE_H - 8 - (int)(pct * (CURVE_H - 16) / 100.0);
    }

    @Override
    public void draw(int mx, int my) {
        int x = x(), y = y();
        Ui.card(x, y, W, H, NexusTheme.BG_SIDEBAR, NexusTheme.BORDER_SOFT, 6);
        s.font().drawString("后台bot世界tick频率", x + 16, y + 10, NexusTheme.TEXT_TITLE);
        s.font().drawString("拖动节点调整各优先级组的tick率 · 自动分配按进程CPU上限", x + 16, y + 24,
                NexusTheme.TEXT_FAINT);

        int ty = y + 40;
        s.font().drawString("进程最大CPU%:", x + 16, ty, NexusTheme.TEXT_LABEL);
        // [-][value][+] click zones; direct typing is too fiddly for a slider.
        int maxCpu = NexusConfig.maxCpuPct();
        drawStepper(x + 16, ty + 12, 110, maxCpu + "", mx, my);
        boolean adapt = NexusConfig.adaptiveTick();
        String adaptLbl = adapt ? "自适应:开" : "自适应:关";
        boolean adaptHov = hit(mx, my, x + W - 100, ty + 8, 84, 18);
        Ui.button(x + W - 100, ty + 8, 84, 18, adaptHov, adapt);
        s.font().drawString(adaptLbl,
                x + W - 100 + 42 - s.font().getStringWidth(adaptLbl) / 2,
                ty + 8 + 5,
                adapt ? NexusTheme.ACCENT : NexusTheme.TEXT_LABEL);

        // capacity readout — only interesting when adaptive is on
        if (adapt) {
            double cap = TickScheduler.capacity();
            double cpu = TickScheduler.avgCpuLoad();
            String cpuStr = cpu < 0 ? "?" : String.format("%.0f%%", cpu * 100);
            s.font().drawString(String.format("当前容量 %.0f%%  ·  CPU %s",
                            cap * 100, cpuStr),
                    x + 140, ty + 18, NexusTheme.TEXT_SUB);
        }

        int cx = curveX(), cy = curveY();
        Ui.fill(cx, cy, CURVE_W, CURVE_H, NexusTheme.BG_CARD);
        for (int pct : new int[]{25, 50, 75}) {
            int gy = cy + CURVE_H - 8 - (pct * (CURVE_H - 16) / 100);
            Ui.fill(cx, gy, CURVE_W, 1, NexusTheme.BORDER_SOFT);
        }
        float[] curve = NexusConfig.priorityCurve();
        // Catmull-Rom through the 6 control points — 24 samples per segment
        // reads as a smooth curve even at this small size. We draw it as a
        // series of 1px-tall vertical slivers so no GL lines are needed.
        int samples = (curve.length - 1) * 24;
        int prevY = -1;
        for (int i = 0; i <= samples; i++) {
            double t = i * (curve.length - 1) / (double) samples;
            double v = catmullRom(curve, t);
            int sx = cx + 8 + (int)(t * (CURVE_W - 16) / (curve.length - 1));
            int sy = cy + CURVE_H - 8 - (int)(v * (CURVE_H - 16) / 100.0);
            // Vertical sliver connecting to the previous sample — fills the
            // diagonal so the curve reads as a stroke, not dots.
            if (prevY >= 0) {
                int top = Math.min(prevY, sy), h = Math.abs(prevY - sy) + 1;
                Ui.fill(sx - 1, top, 2, h, NexusTheme.ACCENT);
            }
            prevY = sy;
        }
        for (int b = 0; b < curve.length; b++) {
            int nx = nodeX(b), ny = nodeY((int) curve[b]);
            boolean hov = hit(mx, my, nx - NODE_R - 2, ny - NODE_R - 2,
                    (NODE_R + 2) * 2, (NODE_R + 2) * 2);
            int col = bandColor(b);
            if (hov || dragNode == b) {
                Ui.fill(nx - NODE_R - 1, ny - NODE_R - 1,
                        (NODE_R + 1) * 2, (NODE_R + 1) * 2, NexusTheme.TEXT_TITLE);
            }
            Ui.fill(nx - NODE_R, ny - NODE_R, NODE_R * 2, NODE_R * 2, col);
            // pct label above the hovered node
            if (hov || dragNode == b) {
                String lbl = (int) curve[b] + "%";
                s.font().drawString(lbl, nx - s.font().getStringWidth(lbl) / 2,
                        ny - 14, NexusTheme.TEXT_TITLE);
            }
            // band number under the editor
            String num = (b + 1) + "";
            s.font().drawString(num, nx - 2, cy + CURVE_H + 2, NexusTheme.TEXT_FAINT);
        }

        int by = cy + CURVE_H + 18;
        s.font().drawString("当前生效 (寻路中):", x + 16, by, NexusTheme.TEXT_LABEL);
        by += 12;
        // Show the actual scheduler output for a pathing bot in each band.
        // Sample botIndex at band midpoint so the curve readout matches what
        // SessionTicker would compute for that band.
        int barW = (CURVE_W - 10) / 6;
        for (int b = 0; b < 6; b++) {
            int sampleIdx = b * TickScheduler.BAND_SIZE;
            int pct = TickScheduler.pctFor(sampleIdx, true, -1);
            int bx = x + 16 + b * (barW + 2);
            Ui.fill(bx, by, barW, 10, NexusTheme.BG_CARD);
            Ui.fill(bx, by, barW * pct / 100, 10, bandColor(b));
            s.font().drawString(pct + "%", bx + 1, by + 2, NexusTheme.TEXT_TITLE);
        }

        s.font().drawString("Esc 或点击外部关闭", x + W - 160, y + H - 18,
                NexusTheme.TEXT_FAINT);
    }

    private void drawStepper(int sx, int sy, int w, String val, int mx, int my) {
        Ui.fill(sx, sy, w, 18, NexusTheme.BG_CARD);
        boolean minus = hit(mx, my, sx, sy, 18, 18);
        boolean plus = hit(mx, my, sx + w - 18, sy, 18, 18);
        s.font().drawString("-", sx + 6, sy + 5,
                minus ? NexusTheme.ACCENT : NexusTheme.TEXT_SUB);
        s.font().drawString("+", sx + w - 12, sy + 5,
                plus ? NexusTheme.ACCENT : NexusTheme.TEXT_SUB);
        s.font().drawString(val,
                sx + w / 2 - s.font().getStringWidth(val) / 2, sy + 5,
                NexusTheme.TEXT_TITLE);
    }

    @Override
    public boolean click(int mx, int my, int btn) {
        int x = x(), y = y();
        if (!hit(mx, my, x, y, W, H)) {
            s.overlayPanel = null;
            return true;
        }
        int ty = y + 40;
        if (hit(mx, my, x + W - 100, ty + 8, 84, 18)) {
            NexusConfig.setAdaptiveTick(!NexusConfig.adaptiveTick());
            return true;
        }
        int maxCpu = NexusConfig.maxCpuPct();
        if (hit(mx, my, x + 16, ty + 12, 18, 18)) {
            NexusConfig.setMaxCpuPct(maxCpu - 5);
            return true;
        }
        if (hit(mx, my, x + 16 + 110 - 18, ty + 12, 18, 18)) {
            NexusConfig.setMaxCpuPct(maxCpu + 5);
            return true;
        }
        // curve node hit → start drag
        float[] curve = NexusConfig.priorityCurve();
        for (int b = 0; b < curve.length; b++) {
            int nx = nodeX(b), ny = nodeY((int) curve[b]);
            if (hit(mx, my, nx - NODE_R - 2, ny - NODE_R - 2,
                    (NODE_R + 2) * 2, (NODE_R + 2) * 2)) {
                dragNode = b;
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(int mx, int my, int btn) {
        if (dragNode < 0) return false;
        // Map mouse Y back to 0..100 pct; clamp inside the curve editor.
        int cy = curveY();
        int relY = my - (cy + CURVE_H - 8);
        int pct = Math.max(0, Math.min(100,
                100 - relY * 100 / (CURVE_H - 16)));
        NexusConfig.setPriorityCurvePoint(dragNode, pct);
        return true;
    }

    @Override
    public boolean mouseReleased(int mx, int my, int btn) {
        if (dragNode >= 0) {
            dragNode = -1;
            return true;
        }
        return false;
    }

    @Override
    public boolean keyTyped(char c, int key) { return false; }

    /**
     * Catmull-Rom spline through `pts` evaluated at parameter t in
     * [0, pts.length-1]. Endpoints are clamped so the curve passes through
     * the first and last control points with a natural tangent.
     */
    private static double catmullRom(float[] pts, double t) {
        int n = pts.length;
        if (n == 0) return 0;
        if (n == 1) return pts[0];
        int i = (int) Math.floor(t);
        if (i < 0) i = 0;
        if (i > n - 2) i = n - 2;
        double f = t - i;
        double p0 = pts[Math.max(0, i - 1)];
        double p1 = pts[i];
        double p2 = pts[i + 1];
        double p3 = pts[Math.min(n - 1, i + 2)];
        // standard Catmull-Rom basis, tension 0.5
        return 0.5 * ((2.0 * p1)
                + (-p0 + p2) * f
                + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * f * f
                + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * f * f * f);
    }

    /** Same palette as the card chip — visual link between badge and node. */
    private static int bandColor(int band) {
        int[] palette = {
            0xFFE57373, 0xFF81C784, 0xFF64B5F6, 0xFFFFB74D,
            0xFFBA68C8, 0xFF4DB6AC, 0xFFFFF176, 0xFFA1887F,
        };
        return palette[band % palette.length];
    }
}
