package com.mythos.nexus.ui;

import com.mythos.nexus.core.NexusConfig;
import com.mythos.nexus.session.BotSession;
import com.mythos.nexus.session.SessionManager;
import com.mythos.nexus.session.TickScheduler;

import static com.mythos.nexus.ui.NexusGeometry.hit;
import static com.mythos.nexus.ui.NexusGeometry.sessionKey;

/**
 * Right-panel view for TICKRATE — adaptive-capacity controls, min-FPS floor,
 * priority-band curve, per-band live "current %" bars, and (when a bot is
 * selected) per-bot overrides for pathing / idle tick rate.
 *
 * Same content as the old tickrate overlay, rearranged for the narrow strip.
 */
final class NexusTickratePanel extends NexusPanel {

    private static final int CURVE_H = 90;
    private static final int NODE_R = 5;

    /** Curve node being dragged, -1 = none. */
    private int dragNode = -1;
    /** Y-offset of the grab point inside the node, so dragging doesn't snap. */
    private int dragOffY = 0;

    NexusTickratePanel(NexusScreen s) { super(s); }


    /** Top of the panel's scrollable content — accounts for the panel's
     *  scroll offset so click()/drag() hit-test the same Y coords draw() emits. */
    private int drawTop() { return 10 - s.rightScroll; }

    void draw(int rx, int mx, int my) {
        int pw = s.panelW();
        int bw = pw - NexusScreen.PAD * 2;
        int ay = drawTop();

        s.font().drawString("Tick 调度", rx + NexusScreen.PAD, ay, NexusTheme.TEXT_TITLE);
        ay += 14;
        s.font().drawString("拖节点调优先级曲线 · 自动保激活玩家帧率",
                rx + NexusScreen.PAD, ay, NexusTheme.TEXT_FAINT);
        ay += 16;

        boolean adapt = NexusConfig.adaptiveTick();
        String adaptLbl = adapt ? "自适应:开" : "自适应:关";
        boolean adaptHov = hit(mx, my, rx + NexusScreen.PAD, ay, bw, 20);
        Ui.button(rx + NexusScreen.PAD, ay, bw, 20, adaptHov, adapt);
        s.font().drawString(adaptLbl,
                rx + NexusScreen.PAD + bw / 2 - s.font().getStringWidth(adaptLbl) / 2,
                ay + 6, adapt ? NexusTheme.ACCENT : NexusTheme.TEXT_LABEL);
        ay += 26;

        int maxCpu = NexusConfig.maxCpuPct();
        s.font().drawString("进程最大 CPU 使用率 %", rx + NexusScreen.PAD, ay, NexusTheme.TEXT_LABEL);
        ay += 12;
        drawStepper(rx + NexusScreen.PAD, ay, 100, String.valueOf(maxCpu), mx, my);
        if (adapt) {
            double cpu = TickScheduler.avgCpuLoad();
            String cpuStr = cpu < 0 ? "?%" : String.format("%.0f%%", cpu * 100);
            s.font().drawString(String.format("容量 %.0f%% · %s",
                            TickScheduler.capacity() * 100, cpuStr),
                    rx + NexusScreen.PAD + 110, ay + 5, NexusTheme.TEXT_SUB);
        }
        ay += 26;

        int minBand = NexusConfig.minBandPct();
        s.font().drawString("最低组 ticks (任何分组保底)", rx + NexusScreen.PAD, ay, NexusTheme.TEXT_LABEL);
        ay += 12;
        drawStepper(rx + NexusScreen.PAD, ay, 100, minBand + "%", mx, my);
        ay += 26;

        s.font().drawString("优先级曲线 (0=最慢 100=全速)",
                rx + NexusScreen.PAD, ay, NexusTheme.TEXT_LABEL);
        ay += 12;
        int cx = rx + NexusScreen.PAD;
        int cw = bw;
        int cy = ay;   // TOP of the curve rect — keep for hit-test math
        Ui.fill(cx, cy, cw, CURVE_H, NexusTheme.BG_CARD);
        for (int pct : new int[]{25, 50, 75}) {
            int gy = cy + CURVE_H - 8 - (pct * (CURVE_H - 16) / 100);
            Ui.fill(cx, gy, cw, 1, NexusTheme.BORDER_SOFT);
        }
        float[] curve = NexusConfig.priorityCurve();
        int samples = (curve.length - 1) * 24;
        int prevY = -1;
        for (int i = 0; i <= samples; i++) {
            double t = i * (curve.length - 1) / (double) samples;
            double v = catmullRom(curve, t);
            int sx = cx + 8 + (int)(t * (cw - 16) / (curve.length - 1));
            int sy = cy + CURVE_H - 8 - (int)(v * (CURVE_H - 16) / 100.0);
            if (prevY >= 0) {
                int top = Math.min(prevY, sy), h = Math.abs(prevY - sy) + 1;
                Ui.fill(sx - 1, top, 2, h, NexusTheme.ACCENT);
            }
            prevY = sy;
        }
        for (int b = 0; b < curve.length; b++) {
            int nx = cx + 8 + b * (cw - 16) / 5;
            int ny = cy + CURVE_H - 8 - (int)(curve[b] * (CURVE_H - 16) / 100.0);
            boolean hov = hit(mx, my, nx - NODE_R - 2, ny - NODE_R - 2,
                    (NODE_R + 2) * 2, (NODE_R + 2) * 2);
            int col = bandColor(b);
            if (hov || dragNode == b) {
                Ui.fill(nx - NODE_R - 1, ny - NODE_R - 1,
                        (NODE_R + 1) * 2, (NODE_R + 1) * 2, NexusTheme.TEXT_TITLE);
            }
            Ui.fill(nx - NODE_R, ny - NODE_R, NODE_R * 2, NODE_R * 2, col);
            if (hov || dragNode == b) {
                String lbl = (int) curve[b] + "%";
                s.font().drawString(lbl, nx - s.font().getStringWidth(lbl) / 2,
                        ny - 14, NexusTheme.TEXT_TITLE);
            }
            s.font().drawString((b + 1) + "", nx - 2, cy + CURVE_H + 2,
                    NexusTheme.TEXT_FAINT);
        }
        ay += CURVE_H + 16;

        s.font().drawString("当前生效 (寻路中):", rx + NexusScreen.PAD, ay, NexusTheme.TEXT_LABEL);
        ay += 12;
        int barW = (bw - 10) / 6;
        for (int b = 0; b < 6; b++) {
            int pct = TickScheduler.pctFor(b * TickScheduler.BAND_SIZE, true, -1);
            int bx = rx + NexusScreen.PAD + b * (barW + 2);
            Ui.fill(bx, ay, barW, 10, NexusTheme.BG_CARD);
            Ui.fill(bx, ay, barW * pct / 100, 10, bandColor(b));
            s.font().drawString(pct + "%", bx + 1, ay + 1, NexusTheme.TEXT_TITLE);
        }
        ay += 18;

        BotSession selB = s.firstSelected();
        if (selB != null && selB != SessionManager.get().humanSession()) {
            String key = sessionKey(selB);
            Ui.divider(rx + NexusScreen.PAD, ay, bw); ay += 6;
            s.font().drawString("选中bot: " + selB.username,
                    rx + NexusScreen.PAD, ay, NexusTheme.TEXT_TITLE);
            ay += 12;
            int pOvr = NexusConfig.tickRatePathingFor(key);
            int iOvr = NexusConfig.tickRateIdleFor(key);
            String hint = (pOvr >= 0 || iOvr >= 0)
                    ? String.format("覆盖: 寻路%d%% · 待机%d%%",
                            pOvr >= 0 ? pOvr : NexusConfig.tickRatePathing(),
                            iOvr >= 0 ? iOvr : NexusConfig.tickRateIdle())
                    : "跟随全局";
            s.font().drawString(hint, rx + NexusScreen.PAD, ay, NexusTheme.TEXT_SUB);
            ay += 12;
            s.font().drawString("寻路覆盖:", rx + NexusScreen.PAD, ay, NexusTheme.TEXT_LABEL);
            drawStepper(rx + NexusScreen.PAD + 70, ay - 2, 80,
                    pOvr < 0 ? "全局" : pOvr + "%", mx, my);
            ay += 18;
            s.font().drawString("待机覆盖:", rx + NexusScreen.PAD, ay, NexusTheme.TEXT_LABEL);
            drawStepper(rx + NexusScreen.PAD + 70, ay - 2, 80,
                    iOvr < 0 ? "全局" : iOvr + "%", mx, my);
            ay += 22;
            if (pOvr >= 0 || iOvr >= 0) {
                boolean hov = hit(mx, my, rx + NexusScreen.PAD, ay, 70, 18);
                Ui.button(rx + NexusScreen.PAD, ay, 70, 18, hov, false);
                s.font().drawString("重置覆盖", rx + NexusScreen.PAD + 10, ay + 5,
                        NexusTheme.TEXT_LABEL);
            }
        } else {
            s.font().drawString("选中账号卡可设单bot覆盖",
                    rx + NexusScreen.PAD, ay, NexusTheme.TEXT_FAINT);
        }
    }

    private void drawStepper(int sx, int sy, int w, String val, int mx, int my) {
        Ui.fill(sx, sy, w, 18, NexusTheme.BG_CARD);
        boolean minus = hit(mx, my, sx, sy, 18, 18);
        boolean plus  = hit(mx, my, sx + w - 18, sy, 18, 18);
        s.font().drawString("-", sx + 6, sy + 5,
                minus ? NexusTheme.ACCENT : NexusTheme.TEXT_SUB);
        s.font().drawString("+", sx + w - 12, sy + 5,
                plus ? NexusTheme.ACCENT : NexusTheme.TEXT_SUB);
        s.font().drawString(val,
                sx + w / 2 - s.font().getStringWidth(val) / 2, sy + 5,
                NexusTheme.TEXT_TITLE);
    }


    boolean click(int mx, int my, int btn) {
        int rx = s.width - s.rightW;
        int pw = s.panelW();
        int bw = pw - NexusScreen.PAD * 2;
        int ay = drawTop();

        ay += 14 + 16;   // title + subtitle
        if (hit(mx, my, rx + NexusScreen.PAD, ay, bw, 20)) {   // adaptive toggle
            NexusConfig.setAdaptiveTick(!NexusConfig.adaptiveTick());
            return true;
        }
        ay += 26;

        ay += 12;   // "最大 CPU %" label
        int maxCpu = NexusConfig.maxCpuPct();
        if (hit(mx, my, rx + NexusScreen.PAD, ay, 18, 18)) {
            NexusConfig.setMaxCpuPct(maxCpu - 5); return true;
        }
        if (hit(mx, my, rx + NexusScreen.PAD + 100 - 18, ay, 18, 18)) {
            NexusConfig.setMaxCpuPct(maxCpu + 5); return true;
        }
        ay += 26;

        ay += 12;   // "最低组 ticks" label
        int minBand = NexusConfig.minBandPct();
        if (hit(mx, my, rx + NexusScreen.PAD, ay, 18, 18)) {
            NexusConfig.setMinBandPct(minBand - 5); return true;
        }
        if (hit(mx, my, rx + NexusScreen.PAD + 100 - 18, ay, 18, 18)) {
            NexusConfig.setMinBandPct(minBand + 5); return true;
        }
        ay += 26;

        ay += 12;   // "优先级曲线" label
        int cx = rx + NexusScreen.PAD;
        int cw = bw;
        float[] curve = NexusConfig.priorityCurve();
        for (int b = 0; b < curve.length; b++) {
            int nx = cx + 8 + b * (cw - 16) / 5;
            int ny = ay + CURVE_H - 8 - (int)(curve[b] * (CURVE_H - 16) / 100.0);
            if (hit(mx, my, nx - NODE_R - 2, ny - NODE_R - 2,
                    (NODE_R + 2) * 2, (NODE_R + 2) * 2)) {
                dragNode = b;
                dragOffY = my - ny;   // grab point inside the node — prevents snap-to-100%
                return true;
            }
        }
        ay += CURVE_H + 16 + 12 + 18;   // curve + live bars

        BotSession selB = s.firstSelected();
        if (selB != null && selB != SessionManager.get().humanSession()) {
            String key = sessionKey(selB);
            ay += 6 + 12 + 12;   // divider + name + hint
            if (hit(mx, my, rx + NexusScreen.PAD + 70, ay - 2, 18, 18)) {
                int v = NexusConfig.tickRatePathingFor(key);
                NexusConfig.setTickRatePathingFor(key, v < 0 ? 95 : Math.max(0, v - 5));
                return true;
            }
            if (hit(mx, my, rx + NexusScreen.PAD + 70 + 80 - 18, ay - 2, 18, 18)) {
                int v = NexusConfig.tickRatePathingFor(key);
                NexusConfig.setTickRatePathingFor(key, v < 0 ? 100 : Math.min(100, v + 5));
                return true;
            }
            ay += 18;
            if (hit(mx, my, rx + NexusScreen.PAD + 70, ay - 2, 18, 18)) {
                int v = NexusConfig.tickRateIdleFor(key);
                NexusConfig.setTickRateIdleFor(key, v < 0 ? 15 : Math.max(0, v - 5));
                return true;
            }
            if (hit(mx, my, rx + NexusScreen.PAD + 70 + 80 - 18, ay - 2, 18, 18)) {
                int v = NexusConfig.tickRateIdleFor(key);
                NexusConfig.setTickRateIdleFor(key, v < 0 ? 20 : Math.min(100, v + 5));
                return true;
            }
            ay += 22;
            int pOvr = NexusConfig.tickRatePathingFor(key);
            int iOvr = NexusConfig.tickRateIdleFor(key);
            if (pOvr >= 0 || iOvr >= 0) {
                if (hit(mx, my, rx + NexusScreen.PAD, ay, 70, 18)) {
                    NexusConfig.setTickRatePathingFor(key, -1);
                    NexusConfig.setTickRateIdleFor(key, -1);
                    return true;
                }
            }
        }
        return false;
    }


    boolean drag(int mx, int my) {
        if (dragNode < 0) return false;
        // Apply the grab offset so the node doesn't jump on first drag.
        // Curve top = drawTop() + title(14) + sub(16) + toggle(26)
        //           + minFps label(12) + stepper(26) + minBand label(12)
        //           + minBand stepper(26) + curve label(12).
        int curveTop = drawTop() + 14 + 16 + 26 + 12 + 26 + 12 + 26 + 12;
        int effectiveY = my - dragOffY;
        // Clamp the drag into the curve rect — outside it, pinning to the
        // boundary is correct (no reading values off into the margins).
        effectiveY = Math.max(curveTop + 8, Math.min(curveTop + CURVE_H - 8, effectiveY));
        int relY = effectiveY - (curveTop + CURVE_H - 8);
        // relY is measured DOWN from the curve's baseline: 0 at 100%, and
        // CURVE_H-16 at 0%. pct = -relY scaled — not 100-relY (that was the
        // snap-to-100 bug: pct came out as 100+oldCurve, clamped to 100).
        int pct = Math.max(0, Math.min(100, -relY * 100 / (CURVE_H - 16)));
        NexusConfig.setPriorityCurvePoint(dragNode, pct);
        return true;
    }

    void mouseReleased() { dragNode = -1; }

    boolean scroll(int d) { return false; }


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
        return 0.5 * ((2.0 * p1)
                + (-p0 + p2) * f
                + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * f * f
                + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * f * f * f);
    }

    private static int bandColor(int band) {
        int[] palette = {
            0xFFE57373, 0xFF81C784, 0xFF64B5F6, 0xFFFFB74D,
            0xFFBA68C8, 0xFF4DB6AC, 0xFFFFF176, 0xFFA1887F,
        };
        return palette[band % palette.length];
    }
}
