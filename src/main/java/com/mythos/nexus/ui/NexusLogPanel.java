package com.mythos.nexus.ui;

import com.mythos.nexus.session.BotSession;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.List;

import static com.mythos.nexus.ui.NexusGeometry.hit;
import static com.mythos.nexus.ui.NexusGeometry.trim;

/** Right panel for the LOG view — level filters, action buttons, event cards. */
final class NexusLogPanel extends NexusPanel {

    // Geometry shared by draw() and click()/scroll()/drag().
    private static final int CHIP_Y = 28, CHIP_W = 40, CHIP_H = 16, CHIP_GAP = 4;
    private static final int BTN_H = 18;
    private static final int CARD_H = 30, CARD_GAP = 4;
    private static final int LIST_PAD_BOTTOM = 12;
    private static final String[] CHIP_LV = {null, "INFO", "WARN", "ERROR"};

    NexusLogPanel(NexusScreen s) { super(s); }

    void draw(int rx, int mx, int my) {
        int bw = s.panelW() - NexusScreen.PAD * 2;
        s.font().drawString("事件日志", rx + NexusScreen.PAD, 12, NexusTheme.TEXT_TITLE);
        BotSession sel = s.firstSelected();
        if (sel == null) {
            s.font().drawString("先在中栏选个账号", rx + NexusScreen.PAD, 30, NexusTheme.TEXT_SUB);
            s.logThumbH = 0;
            return;
        }

        String[] lb = {"全部", "INFO", "WARN", "ERROR"};
        int cx0 = rx + NexusScreen.PAD;
        for (int i = 0; i < CHIP_LV.length; i++) {
            int cxp = cx0 + i * (CHIP_W + CHIP_GAP);
            boolean cur = CHIP_LV[i] == null ? s.logFilter == null : CHIP_LV[i].equals(s.logFilter);
            boolean hov = hit(mx, my, cxp, CHIP_Y, CHIP_W, CHIP_H);
            Ui.roundedRect(cxp, CHIP_Y, CHIP_W, CHIP_H, 3,
                    cur ? NexusTheme.BG_CARD_HOV : (hov ? NexusTheme.HOVER_WASH : NexusTheme.BG_SIDEBAR));
            s.font().drawString(lb[i], cxp + (CHIP_W - s.font().getStringWidth(lb[i])) / 2,
                    CHIP_Y + 4, cur ? NexusTheme.ACCENT : NexusTheme.TEXT_LABEL);
        }

        int ay = CHIP_Y + 22;
        int abw = (bw - 2 * CHIP_GAP) / 3;
        s.drawButton(rx + NexusScreen.PAD, ay, abw, BTN_H, "清空", false, mx, my);
        s.drawButton(rx + NexusScreen.PAD + abw + CHIP_GAP, ay, abw, BTN_H, "复制", false, mx, my);
        s.drawButton(rx + NexusScreen.PAD + 2 * (abw + CHIP_GAP), ay, abw, BTN_H, "复制全部", false, mx, my);

        List<BotSession.LogEntry> entries = new ArrayList<>();
        synchronized (sel.eventLog) {
            for (BotSession.LogEntry e : sel.eventLog)
                if (s.logFilter == null || s.logFilter.equals(e.level)) entries.add(e);
        }
        int total = entries.size();

        int listTop = ay + 26;
        int listBottom = s.height - LIST_PAD_BOTTOM;
        int viewH = listBottom - listTop;
        int visible = Math.max(1, viewH / (CARD_H + CARD_GAP));
        int maxScroll = Math.max(0, total - visible);
        s.logScroll = Math.max(0, Math.min(s.logScroll, maxScroll));

        s.logThumbH = 0;
        if (total > visible) {
            s.logTrackY = listTop; s.logTrackH = viewH;
            int thumbH = Math.max(20, viewH * visible / total);
            int thumbY = listTop + (viewH - thumbH) * s.logScroll / Math.max(1, maxScroll);
            s.logThumbY = thumbY; s.logThumbH = thumbH;
            boolean thov = s.dragScroll == 4
                    || hit(mx, my, s.width - NexusScreen.SCROLL_W - 3, thumbY - 2, NexusScreen.SCROLL_W + 6, thumbH + 4);
            Ui.fill(s.width - NexusScreen.SCROLL_W - 1, thumbY, NexusScreen.SCROLL_W, thumbH,
                    thov ? NexusTheme.ACCENT : NexusTheme.BORDER_SOFT);
        }

        // clip: draw cards top-to-bottom (oldest first)
        for (int i = s.logScroll; i < total && i < s.logScroll + visible; i++) {
            int cy2 = listTop + (i - s.logScroll) * (CARD_H + CARD_GAP);
            BotSession.LogEntry e = entries.get(i);
            boolean selc = s.logSelected.contains(i);
            boolean hov = hit(mx, my, rx + NexusScreen.PAD, cy2, bw, CARD_H);
            Ui.card(rx + NexusScreen.PAD, cy2, bw, CARD_H,
                    selc ? NexusTheme.BG_CARD_HOV : NexusTheme.BG_SIDEBAR,
                    selc ? NexusTheme.ACCENT : NexusTheme.BORDER_SOFT, 3);
            if (hov && !selc)
                Ui.roundedRect(rx + NexusScreen.PAD + 1, cy2 + 1, bw - 2, CARD_H - 2, 3,
                        NexusTheme.HOVER_WASH);
            int lvColor = "ERROR".equals(e.level) ? NexusTheme.ERR
                    : "WARN".equals(e.level) ? NexusTheme.WARN : NexusTheme.TEXT_FAINT;
            s.font().drawString(e.time, rx + NexusScreen.PAD + 6, cy2 + 5, NexusTheme.TEXT_FAINT);
            s.font().drawString(e.level, rx + NexusScreen.PAD + 6 + s.font().getStringWidth(e.time) + 6,
                    cy2 + 5, lvColor);
            String m = trim(e.msg, Math.max(8, (bw - 16) / 6));
            s.font().drawString(m, rx + NexusScreen.PAD + 6, cy2 + 17, NexusTheme.TEXT_LABEL);
        }
    }

    List<BotSession.LogEntry> filteredLogEntries(BotSession sel) {
        List<BotSession.LogEntry> out = new ArrayList<>();
        synchronized (sel.eventLog) {
            for (BotSession.LogEntry e : sel.eventLog)
                if (s.logFilter == null || s.logFilter.equals(e.level)) out.add(e);
        }
        return out;
    }

    void copyLogEntries(BotSession sel, boolean all) {
        List<BotSession.LogEntry> entries = filteredLogEntries(sel);
        if (!all && s.logSelected.isEmpty()) all = true;   // nothing picked → copy all
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (!all && !s.logSelected.contains(i)) continue;
            BotSession.LogEntry e = entries.get(i);
            sb.append('[').append(e.time).append("] ").append(e.level)
              .append(' ').append(e.msg).append('\n');
        }
        if (sb.length() == 0) return;
        GuiScreen.setClipboardString(sb.toString());
    }

    //      mouseClickMove — geometry shared with draw() via the constants) ----

    private int listTop() { return CHIP_Y + 22 + 26; }
    private int listBottom() { return s.height - LIST_PAD_BOTTOM; }
    private int viewH() { return listBottom() - listTop(); }
    private int visible() { return Math.max(1, viewH() / (CARD_H + CARD_GAP)); }

    @Override
    boolean click(int mx, int my, int btn) {
        int rx = s.width - s.rightW;
        int bw = s.panelW() - NexusScreen.PAD * 2;
        BotSession sel = s.firstSelected();
        if (sel == null) return false;

        for (int i = 0; i < CHIP_LV.length; i++) {
            int cxp = rx + NexusScreen.PAD + i * (CHIP_W + CHIP_GAP);
            if (hit(mx, my, cxp, CHIP_Y, CHIP_W, CHIP_H)) {
                s.logFilter = CHIP_LV[i];
                s.logScroll = 0;
                s.logSelected.clear();
                return true;
            }
        }

        int ay = CHIP_Y + 22;
        int abw = (bw - 2 * CHIP_GAP) / 3;
        if (hit(mx, my, rx + NexusScreen.PAD, ay, abw, BTN_H)) {   // 清空
            synchronized (sel.eventLog) { sel.eventLog.clear(); }
            s.logSelected.clear(); s.logScroll = 0;
            return true;
        }
        if (hit(mx, my, rx + NexusScreen.PAD + abw + CHIP_GAP, ay, abw, BTN_H)) {   // 复制
            copyLogEntries(sel, false);
            return true;
        }
        if (hit(mx, my, rx + NexusScreen.PAD + 2 * (abw + CHIP_GAP), ay, abw, BTN_H)) {   // 复制全部
            copyLogEntries(sel, true);
            return true;
        }

        if (s.logThumbH > 0 && hit(mx, my, s.width - NexusScreen.SCROLL_W - 3,
                s.logThumbY - 2, NexusScreen.SCROLL_W + 6, s.logThumbH + 4)) {
            s.dragScroll = 4; s.dragOffY = my - s.logThumbY; return true;
        }

        // entry cards — ctrl/shift multi-select, dbl-click echoes to log
        List<BotSession.LogEntry> entries = filteredLogEntries(sel);
        for (int i = s.logScroll; i < entries.size(); i++) {
            int cy2 = listTop() + (i - s.logScroll) * (CARD_H + CARD_GAP);
            if (cy2 + CARD_H > s.height - LIST_PAD_BOTTOM) break;
            if (hit(mx, my, rx + NexusScreen.PAD, cy2, bw, CARD_H)) {
                boolean ctrl = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)
                        || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
                boolean shift = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)
                        || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
                if (shift && s.logAnchor >= 0) {
                    int lo = Math.min(s.logAnchor, i), hi = Math.max(s.logAnchor, i);
                    for (int k = lo; k <= hi; k++) s.logSelected.add(k);
                } else if (ctrl) {
                    if (!s.logSelected.add(i)) s.logSelected.remove(i);
                    s.logAnchor = i;
                } else {
                    s.logSelected.clear();
                    s.logSelected.add(i);
                    s.logAnchor = i;
                }
                long now = System.nanoTime();
                if (s.lastLogClickIdx == i && now - s.lastLogClickNanos < 300_000_000L) {
                    com.mythos.nexus.core.NexusPlugin.LOGGER.info("[Nexus log] {}", entries.get(i).msg);
                    s.lastLogClickIdx = -1;
                } else {
                    s.lastLogClickIdx = i; s.lastLogClickNanos = now;
                }
                return true;
            }
        }
        return false;
    }

    /** Wheel over the right panel in LOG view scrolls the entry list. */
    @Override
    boolean scroll(int dWheel) {
        BotSession sel = s.firstSelected();
        if (sel == null) return false;
        int total = filteredLogEntries(sel).size();
        int maxS = Math.max(0, total - visible());
        s.logScroll = Math.max(0, Math.min(maxS,
                s.logScroll - Integer.signum(dWheel)));
        return true;
    }

    /** Log scrollbar drag (dragScroll == 4). */
    @Override
    boolean drag(int mx, int my) {
        if (s.dragScroll != 4 || s.logThumbH <= 0 || s.logTrackH <= s.logThumbH)
            return false;
        int ty = Math.max(s.logTrackY, Math.min(my - s.dragOffY,
                s.logTrackY + s.logTrackH - s.logThumbH));
        BotSession sel = s.firstSelected();
        int total = sel == null ? 0 : filteredLogEntries(sel).size();
        int maxS = Math.max(0, total - visible());
        s.logScroll = Math.max(0, Math.min(maxS,
                (ty - s.logTrackY) * maxS / Math.max(1, s.logTrackH - s.logThumbH)));
        return true;
    }
}
