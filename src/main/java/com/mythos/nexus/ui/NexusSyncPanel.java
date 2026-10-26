package com.mythos.nexus.ui;

import com.mythos.nexus.core.NexusConfig;
import com.mythos.nexus.session.BotSession;
import com.mythos.nexus.session.SessionManager;
import com.mythos.nexus.session.SyncEngine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.mythos.nexus.ui.NexusGeometry.hit;
import static com.mythos.nexus.ui.NexusGeometry.sessionKey;

/**
 * 人物同步面板：分组卡片可展开，展开后逐个列出成员并单独移出；
 * 上方是全局面板开关与聚拢半径，下方实时显示每组主控和跟随数量。
 */
final class NexusSyncPanel extends NexusPanel {

    private static final int ROW_H = 26, ROW_GAP = 3;
    private static final int MEMBER_H = 15, ADDROW_H = 17;
    private static final int TOGGLE_W = 42, TOGGLE_H = 15;
    private static final int LIST_TOP = 96;
    private static final int MANAGE_H = 48;
    private static final int INFO_H = 62;
    private static final int LINE_H = 11;
    private static final int STEP_W = 72;

    NexusSyncPanel(NexusScreen s) { super(s); }

    private String selected = null;
    private final Set<String> expanded = new HashSet<>();

    private int bw() { return s.panelW() - NexusScreen.PAD * 2; }
    private int cardW() { return bw() - NexusScreen.SCROLL_W - 4; }

    /** 列表、管理条、状态区的可用高度分配。 */
    private int[] bands() {
        int avail = s.height - LIST_TOP;
        int infoH = Math.min(INFO_H, Math.max(LINE_H * 2 + 16, avail / 3));
        int manageH = MANAGE_H;
        int listMin = ROW_H + ROW_GAP + ADDROW_H + 2 * MEMBER_H;
        int listH = Math.max(ROW_H, avail - infoH - manageH - 18);
        if (listH < listMin) {
            infoH = Math.max(LINE_H * 2 + 16, avail - manageH - listMin - 18);
            listH = Math.max(ROW_H, avail - infoH - manageH - 18);
        }
        return new int[]{listH, manageH, infoH};
    }

    private int listBottom() { return LIST_TOP + bands()[0]; }
    private int manageTop()  { return listBottom() + 6; }
    private int infoTop()    { return manageTop() + bands()[1] + 6; }
    private int thumbX() { return s.width - s.rightW + s.panelW() - NexusScreen.SCROLL_W - 4; }

    private List<NexusConfig.SyncGroup> groups() { return NexusConfig.syncGroups(); }

    /** 列表按像素滚动。 */
    private int contentHeight() {
        int h = 0;
        for (NexusConfig.SyncGroup g : groups()) {
            h += ROW_H + ROW_GAP;
            if (expanded.contains(g.name)) h += g.members.size() * MEMBER_H + ADDROW_H;
        }
        return h;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - (listBottom() - LIST_TOP));
    }

    private NexusConfig.SyncGroup selectedGroup() {
        List<NexusConfig.SyncGroup> all = groups();
        if (selected != null)
            for (NexusConfig.SyncGroup g : all) if (g.name.equals(selected)) return g;
        return all.isEmpty() ? null : all.get(0);
    }

    private static BotSession sessionByKey(String key) {
        for (BotSession b : SessionManager.get().sessions())
            if (sessionKey(b).equals(key)) return b;
        return null;
    }

    void draw(int rx, int mx, int my) {
        int bw = bw();
        int ax = rx + NexusScreen.PAD;
        List<NexusConfig.SyncGroup> all = groups();
        int[] stats = NexusConfig.syncGroupStats();

        s.font().drawString("人物同步", ax, 6, NexusTheme.TEXT_TITLE);
        s.font().drawString("同组动作一致 · 超出半径先聚拢", ax, 18, NexusTheme.TEXT_FAINT);

        boolean newHov = hit(mx, my, ax, 33, (bw - 8) / 2, 18);
        Ui.button(ax, 33, (bw - 8) / 2, 18, newHov, false);
        String newLabel = "+ 新建分组";
        s.font().drawString(newLabel,
                ax + (bw - 8) / 4 - s.font().getStringWidth(newLabel) / 2, 38,
                newHov ? NexusTheme.TEXT_TITLE : NexusTheme.TEXT_LABEL);

        int allX = ax + (bw + 8) / 2;
        boolean allOn = stats[1] > 0 && stats[0] == stats[1];
        boolean allHov = hit(mx, my, allX, 33, bw - (bw - 8) / 2, 18);
        Ui.button(allX, 33, bw - (bw - 8) / 2, 18, allHov, allOn);
        String allLabel = allOn ? "全部关闭" : "全部开启";
        s.font().drawString(allLabel,
                allX + (bw - (bw - 8) / 2) / 2 - s.font().getStringWidth(allLabel) / 2, 38,
                allOn ? NexusTheme.ACCENT : NexusTheme.TEXT_LABEL);

        s.font().drawString("聚拢半径", ax, 57, NexusTheme.TEXT_LABEL);
        drawStepper(ax + 52, 55, STEP_W,
                (int) NexusConfig.syncGatherRange() + " 格", mx, my);
        int gx = ax + 52 + STEP_W + 8;
        boolean gatherOn = NexusConfig.syncAutoGather();
        boolean gHov = hit(mx, my, gx, 55, bw - (gx - ax), 18);
        Ui.button(gx, 55, bw - (gx - ax), 18, gHov, gatherOn);
        String gLabel = gatherOn ? "聚拢 开" : "聚拢 关";
        s.font().drawString(gLabel, gx + (bw - (gx - ax)) / 2 - s.font().getStringWidth(gLabel) / 2,
                60, gatherOn ? NexusTheme.ACCENT : NexusTheme.TEXT_LABEL);

        Ui.divider(ax, LIST_TOP - 9, bw);

        int maxScroll = maxScroll();
        s.syncScroll = Math.max(0, Math.min(s.syncScroll, maxScroll));
        NexusConfig.SyncGroup sel = selectedGroup();
        if (sel != null) selected = sel.name;

        s.clipPush(ax, LIST_TOP, bw, bands()[0]);
        int y = LIST_TOP - s.syncScroll;
        for (NexusConfig.SyncGroup g : all) {
            drawHeader(g, ax, y, sel, mx, my);
            y += ROW_H + ROW_GAP;
            if (!expanded.contains(g.name)) continue;
            for (String key : g.members) {
                drawMember(g, key, ax, y, mx, my);
                y += MEMBER_H;
            }
            drawAddRow(g, ax, y, mx, my);
            y += ADDROW_H;
        }
        s.clipPop();

        if (all.isEmpty()) {
            s.font().drawString("还没有分组 — 先点「+ 新建分组」", ax, LIST_TOP + 4,
                    NexusTheme.TEXT_FAINT);
        }
        s.syncThumbH = 0;
        if (maxScroll > 0) {
            int trackH = bands()[0];
            int thumbH = Math.max(14, trackH * trackH / Math.max(1, contentHeight()));
            int thumbY = LIST_TOP + (trackH - thumbH) * s.syncScroll / maxScroll;
            s.syncTrackY = LIST_TOP; s.syncTrackH = trackH;
            s.syncThumbY = thumbY; s.syncThumbH = thumbH;
            Ui.fill(thumbX(), LIST_TOP, NexusScreen.SCROLL_W, trackH, NexusTheme.BG_CARD);
            boolean thov = s.dragScroll == 8
                    || hit(mx, my, thumbX() - 3, thumbY - 2,
                           NexusScreen.SCROLL_W + 6, thumbH + 4);
            Ui.fill(thumbX(), thumbY, NexusScreen.SCROLL_W, thumbH,
                    thov ? NexusTheme.ACCENT : NexusTheme.BORDER_SOFT);
        }

        int mTop = manageTop();
        Ui.divider(ax, mTop - 3, bw);
        if (sel == null) {
            s.font().drawString("选一个分组后可以管理成员", ax, mTop + 4, NexusTheme.TEXT_FAINT);
        } else {
            s.font().drawString(NexusGeometry.trim(sel.name, 14), ax, mTop + 2,
                    NexusTheme.TEXT_TITLE);
            String stat = sel.members.size() + " 账号";
            s.font().drawString(stat, ax + bw - s.font().getStringWidth(stat), mTop + 3,
                    NexusTheme.TEXT_FAINT);
            int half = (bw - 6) / 2;
            boolean addHov = hit(mx, my, ax, mTop + 15, half, 16);
            boolean rmHov  = hit(mx, my, ax + half + 6, mTop + 15, bw - half - 6, 16);
            Ui.button(ax, mTop + 15, half, 16, addHov, false);
            Ui.button(ax + half + 6, mTop + 15, bw - half - 6, 16, rmHov, false);
            String addL = "加入选中 (" + s.selectedSessions().size() + ")";
            String rmL = "删除分组";
            s.font().drawString(addL,
                    ax + half / 2 - s.font().getStringWidth(addL) / 2,
                    mTop + 19, addHov ? NexusTheme.TEXT_TITLE : NexusTheme.TEXT_LABEL);
            s.font().drawString(rmL,
                    ax + half + 6 + (bw - half - 6) / 2 - s.font().getStringWidth(rmL) / 2,
                    mTop + 19, rmHov ? NexusTheme.ERR : NexusTheme.TEXT_LABEL);
        }

        int iTop = infoTop();
        Ui.divider(ax, iTop - 4, bw);
        String drv = SyncEngine.driver();
        s.font().drawString(drv.isEmpty() ? "主控: 未连接" : "主控: " + drv, ax, iTop + 2,
                drv.isEmpty() ? NexusTheme.TEXT_FAINT : NexusTheme.ACCENT);
        int lines = Math.max(1, (bands()[2] - 26) / LINE_H);
        List<String[]> roster = SyncEngine.roster();
        int ey = iTop + 14;
        if (roster.isEmpty()) {
            s.font().drawString("没有启动的分组", ax, ey, NexusTheme.TEXT_FAINT);
        } else {
            for (int i = 0; i < roster.size() && i < lines; i++) {
                String[] r = roster.get(i);
                String name = NexusGeometry.trim(r[0], 8);
                s.font().drawString(name, ax, ey, NexusTheme.TEXT_LABEL);
                int nx = ax + s.font().getStringWidth(name) + 6;
                String rest = r[2] + " · " + r[3] + " · " + r[4];
                s.font().drawString(NexusGeometry.trim(rest,
                        Math.max(6, (bw - (nx - ax) - NexusScreen.SCROLL_W) / 6)),
                        nx, ey, NexusTheme.TEXT_FAINT);
                ey += LINE_H;
            }
        }
    }

    private void drawHeader(NexusConfig.SyncGroup g, int ax, int y, NexusConfig.SyncGroup sel,
                            int mx, int my) {
        boolean isSel = sel != null && g.name.equals(sel.name);
        Ui.card(ax, y, cardW(), ROW_H, isSel ? NexusTheme.BG_CARD_HOV : NexusTheme.BG_SIDEBAR,
                isSel ? NexusTheme.ACCENT : NexusTheme.BORDER_SOFT, 3);
        Ui.dot(ax + 9, y + 9, g.enabled ? NexusTheme.OK : NexusTheme.IDLE);
        drawChevron(ax + 15, y + 6, expanded.contains(g.name), NexusTheme.TEXT_FAINT);
        s.font().drawString(NexusGeometry.trim(g.name, 11), ax + 24, y + 4,
                isSel ? NexusTheme.TEXT_TITLE : NexusTheme.TEXT_LABEL);
        String sub = g.members.size() + " 账号 · " + (g.enabled ? "已启动" : "已停止");
        s.font().drawString(sub, ax + 24, y + 15, NexusTheme.TEXT_FAINT);

        int tx = ax + cardW() - TOGGLE_W - 4, ty = y + (ROW_H - TOGGLE_H) / 2;
        boolean thov = hit(mx, my, tx, ty, TOGGLE_W, TOGGLE_H);
        Ui.button(tx, ty, TOGGLE_W, TOGGLE_H, thov, g.enabled);
        String on = g.enabled ? "启动" : "停止";
        s.font().drawString(on, tx + TOGGLE_W / 2 - s.font().getStringWidth(on) / 2, ty + 4,
                g.enabled ? NexusTheme.ACCENT : NexusTheme.TEXT_LABEL);
    }

    private void drawMember(NexusConfig.SyncGroup g, String key, int ax, int y, int mx, int my) {
        BotSession b = sessionByKey(key);
        boolean online = b != null && b.state == BotSession.State.ONLINE;
        String name = b != null ? b.username : key.substring(0, Math.max(0, key.indexOf('@')));
        String server = key.substring(key.indexOf('@') + 1);
        Ui.fill(ax + 6, y, cardW() - 12, MEMBER_H - 1, NexusTheme.BG_APP);
        Ui.dot(ax + 14, y + 6, online ? NexusTheme.OK : NexusTheme.IDLE);
        s.font().drawString(NexusGeometry.trim(name, 10), ax + 22, y + 3,
                online ? NexusTheme.TEXT_LABEL : NexusTheme.TEXT_FAINT);
        int nameW = s.font().getStringWidth(NexusGeometry.trim(name, 10));
        s.font().drawString(NexusGeometry.trim(server, 8), ax + 28 + nameW, y + 3,
                NexusTheme.TEXT_FAINT);

        int rx = ax + cardW() - 40, rw = 34;
        boolean rhov = hit(mx, my, rx, y + 1, rw, MEMBER_H - 3);
        Ui.roundedRect(rx, y + 1, rw, MEMBER_H - 3, 2,
                rhov ? NexusTheme.ERR : NexusTheme.BG_CARD);
        String label = "移出";
        s.font().drawString(label, rx + rw / 2 - s.font().getStringWidth(label) / 2, y + 4,
                rhov ? NexusTheme.TEXT_TITLE : NexusTheme.TEXT_SUB);
    }

    private void drawAddRow(NexusConfig.SyncGroup g, int ax, int y, int mx, int my) {
        boolean hov = hit(mx, my, ax + 6, y, cardW() - 12, ADDROW_H - 2);
        Ui.roundedRect(ax + 6, y, cardW() - 12, ADDROW_H - 2, 2,
                hov ? NexusTheme.HOVER_WASH : NexusTheme.BG_CARD);
        String label = "+ 加入选中 (" + s.selectedSessions().size() + ")";
        s.font().drawString(label, ax + cardW() / 2 - s.font().getStringWidth(label) / 2, y + 4,
                hov ? NexusTheme.ACCENT : NexusTheme.TEXT_SUB);
    }

    private static void drawChevron(int x, int y, boolean open, int color) {
        if (open) {
            Ui.fill(x, y + 4, 5, 1, color);
            Ui.fill(x + 1, y + 5, 3, 1, color);
            Ui.fill(x + 2, y + 6, 1, 1, color);
        } else {
            Ui.fill(x + 1, y + 3, 1, 5, color);
            Ui.fill(x + 2, y + 4, 1, 3, color);
            Ui.fill(x + 3, y + 5, 1, 1, color);
        }
    }

    private void drawStepper(int sx, int sy, int w, String val, int mx, int my) {
        Ui.fill(sx, sy, w, 18, NexusTheme.BG_CARD);
        boolean minus = hit(mx, my, sx, sy, 18, 18);
        boolean plus  = hit(mx, my, sx + w - 18, sy, 18, 18);
        Ui.roundedRect(sx, sy, 18, 18, 2, minus ? NexusTheme.HOVER_WASH : NexusTheme.BG_CARD_HOV);
        Ui.roundedRect(sx + w - 18, sy, 18, 18, 2, plus ? NexusTheme.HOVER_WASH : NexusTheme.BG_CARD_HOV);
        NexusIcons.iconMinus(sx + 9, sy + 9, minus ? NexusTheme.ACCENT : NexusTheme.TEXT_LABEL);
        NexusIcons.iconPlus(sx + w - 9, sy + 9, plus ? NexusTheme.ACCENT : NexusTheme.TEXT_LABEL);
        s.font().drawString(val, sx + w / 2 - s.font().getStringWidth(val) / 2, sy + 5,
                NexusTheme.TEXT_TITLE);
    }

    @Override
    boolean click(int mx, int my, int btn) {
        int rx = s.width - s.rightW;
        int bw = bw();
        int ax = rx + NexusScreen.PAD;

        if (hit(mx, my, ax, 33, (bw - 8) / 2, 18)) {
            String name = "同步组 " + (groups().size() + 1);
            if (NexusConfig.addSyncGroup(name)) {
                selected = name;
                expanded.add(name);
                s.toast("已创建 " + name, 1500);
            }
            return true;
        }
        int allX = ax + (bw + 8) / 2;
        if (hit(mx, my, allX, 33, bw - (bw - 8) / 2, 18)) {
            int[] stats = NexusConfig.syncGroupStats();
            boolean allOn = stats[1] > 0 && stats[0] == stats[1];
            for (NexusConfig.SyncGroup g : groups())
                NexusConfig.setSyncGroupEnabled(g.name, !allOn);
            s.toast(!allOn ? "已启动全部同步分组" : "已停止全部同步分组", 1600);
            return true;
        }

        if (hit(mx, my, ax + 52, 55, 18, 18)) {
            NexusConfig.setSyncGatherRange(NexusConfig.syncGatherRange() - 1); return true;
        }
        if (hit(mx, my, ax + 52 + STEP_W - 18, 55, 18, 18)) {
            NexusConfig.setSyncGatherRange(NexusConfig.syncGatherRange() + 1); return true;
        }
        int gx = ax + 52 + STEP_W + 8;
        if (hit(mx, my, gx, 55, bw - (gx - ax), 18)) {
            NexusConfig.setSyncAutoGather(!NexusConfig.syncAutoGather()); return true;
        }

        if (s.syncThumbH > 0 && hit(mx, my, thumbX() - 3, s.syncThumbY - 2,
                NexusScreen.SCROLL_W + 6, s.syncThumbH + 4)) {
            s.dragScroll = 8; s.dragOffY = my - s.syncThumbY; return true;
        }

        int y = LIST_TOP - s.syncScroll;
        int listBottom = listBottom();
        for (NexusConfig.SyncGroup g : groups()) {
            if (y >= LIST_TOP - ROW_H && y <= listBottom) {
                int tx = ax + cardW() - TOGGLE_W - 4, ty = y + (ROW_H - TOGGLE_H) / 2;
                if (hit(mx, my, tx, ty, TOGGLE_W, TOGGLE_H)) {
                    NexusConfig.setSyncGroupEnabled(g.name, !g.enabled);
                    s.toast(g.name + (g.enabled ? " 已停止" : " 已启动"), 1400);
                    return true;
                }
                if (hit(mx, my, ax, y, cardW(), ROW_H)) {
                    selected = g.name;
                    if (!expanded.remove(g.name)) expanded.add(g.name);
                    return true;
                }
            }
            y += ROW_H + ROW_GAP;
            if (!expanded.contains(g.name)) continue;

            for (String key : g.members) {
                if (y >= LIST_TOP - MEMBER_H && y <= listBottom) {
                    int rmx = ax + cardW() - 40, rw = 34;
                    if (hit(mx, my, rmx, y + 1, rw, MEMBER_H - 3)) {
                        NexusConfig.syncRemoveMember(g.name, key);
                        s.toast("已移出 " + key, 1400);
                        return true;
                    }
                }
                y += MEMBER_H;
            }
            if (y >= LIST_TOP - ADDROW_H && y <= listBottom
                    && hit(mx, my, ax + 6, y, cardW() - 12, ADDROW_H - 2)) {
                List<String> keys = new ArrayList<>();
                for (BotSession b : s.selectedSessions()) keys.add(sessionKey(b));
                if (keys.isEmpty()) { s.toast("先在账号列表里选中账号", 1600); return true; }
                NexusConfig.syncAddMembers(g.name, keys);
                s.toast(keys.size() + " 个账号已加入 " + g.name, 1600);
                return true;
            }
            y += ADDROW_H;
        }

        NexusConfig.SyncGroup sel = selectedGroup();
        if (sel != null) {
            int mTop = manageTop();
            int half = (bw - 6) / 2;
            if (hit(mx, my, ax, mTop + 15, half, 16)) {
                List<String> keys = new ArrayList<>();
                for (BotSession b : s.selectedSessions()) keys.add(sessionKey(b));
                if (keys.isEmpty()) { s.toast("先在账号列表里选中账号", 1600); return true; }
                NexusConfig.syncAddMembers(sel.name, keys);
                expanded.add(sel.name);
                s.toast(keys.size() + " 个账号已加入 " + sel.name, 1600);
                return true;
            }
            if (hit(mx, my, ax + half + 6, mTop + 15, bw - half - 6, 16)) {
                String name = sel.name;
                NexusConfig.removeSyncGroup(name);
                expanded.remove(name);
                if (name.equals(selected)) selected = null;
                s.toast("已删除 " + name, 1600);
                return true;
            }
        }
        return false;
    }

    @Override
    boolean scroll(int dWheel) {
        int maxScroll = maxScroll();
        if (maxScroll <= 0) return false;
        s.syncScroll = Math.max(0, Math.min(maxScroll,
                s.syncScroll - Integer.signum(dWheel) * (ROW_H + ROW_GAP)));
        return true;
    }

    @Override
    boolean drag(int mx, int my) {
        if (s.dragScroll != 8) return false;
        int trackH = s.syncTrackH;
        int maxScroll = maxScroll();
        if (s.syncThumbH <= 0 || maxScroll <= 0 || trackH <= s.syncThumbH) return true;
        int ty = Math.max(s.syncTrackY,
                Math.min(my - s.dragOffY, s.syncTrackY + trackH - s.syncThumbH));
        s.syncScroll = Math.max(0, Math.min(maxScroll,
                (ty - s.syncTrackY) * maxScroll / Math.max(1, trackH - s.syncThumbH)));
        return true;
    }

    @Override
    void release() { }
}
