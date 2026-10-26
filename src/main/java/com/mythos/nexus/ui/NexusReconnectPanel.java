package com.mythos.nexus.ui;

import com.mythos.nexus.core.NexusConfig;
import com.mythos.nexus.session.BotSession;
import com.mythos.nexus.session.ReconnectManager;
import com.mythos.nexus.session.SessionManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.mythos.nexus.ui.NexusGeometry.hit;
import static com.mythos.nexus.ui.NexusGeometry.sessionKey;

final class NexusReconnectPanel extends NexusPanel {

    private static final int ROW_H = 28, ROW_GAP = 3;
    private static final int TOGGLE_W = 42, TOGGLE_H = 15;
    private static final int SEL_H = 56;
    private static final int LIST_TOP = 108;
    private static final int EV_LINE_H = 10;
    private static final int LIST_MIN = 2 * (ROW_H + ROW_GAP);
    private static final int DELAY_X = 26, ATT_LBL_X = 96, ATT_X = 120, STEP_W = 64;

    NexusReconnectPanel(NexusScreen s) { super(s); }

    private int lastMx, lastMy;


    private int bw() { return s.panelW() - NexusScreen.PAD * 2; }

    private int[] bands() {
        int avail = s.height - LIST_TOP;              // space below the header
        int evWant = Math.max(30, Math.min(120, avail / 3));
        int evH = Math.min(evWant, avail - SEL_H - LIST_MIN - 16);
        evH = Math.max(24, evH);
        int selH = Math.min(SEL_H, Math.max(40, avail - evH - LIST_MIN - 16));
        int listH = Math.max(ROW_H, avail - evH - selH - 16);
        return new int[]{evH, selH, listH};
    }

    private int listBottom() { return LIST_TOP + bands()[2]; }
    private int selTop()     { return listBottom() + 6; }
    private int evTop()      { return s.height - bands()[0] - 4; }

    private int visibleRows() {
        return Math.max(0, (listBottom() - LIST_TOP + ROW_GAP) / (ROW_H + ROW_GAP));
    }
    private int visibleEvLines() {
        return Math.max(1, (bands()[0] - 14) / EV_LINE_H);
    }
    private int thumbX() { return s.width - s.rightW + s.panelW() - NexusScreen.SCROLL_W - 4; }
    private int evThumbX() { return thumbX(); }

    private static final class RowView {
        final BotSession session;
        final ReconnectManager.Row queue;
        final boolean autoReconnect;
        final String key;
        RowView(BotSession session, ReconnectManager.Row queue, boolean autoReconnect) {
            this.session = session;
            this.queue = queue;
            this.autoReconnect = autoReconnect;
            this.key = sessionKey(session);
        }
    }

    private List<RowView> snapshot() {
        ReconnectManager rm = ReconnectManager.get();
        List<BotSession> all = new ArrayList<>();
        for (BotSession b : SessionManager.get().sessions())
            if (b != SessionManager.get().humanSession()) all.add(b);

        final Map<BotSession, ReconnectManager.Row> rows = new HashMap<>();
        for (BotSession b : all) {
            ReconnectManager.Row r = rm.rowFor(b);
            if (r != null) rows.put(b, r);
        }
        all.sort((a, b) -> {
            int ra = rank(a, rows.get(a)), rb = rank(b, rows.get(b));
            if (ra != rb) return ra - rb;
            return a.username.compareToIgnoreCase(b.username);
        });
        List<RowView> out = new ArrayList<>(all.size());
        for (BotSession b : all) {
            String key = sessionKey(b);
            out.add(new RowView(b, rows.get(b), NexusConfig.bindingFor(key).autoReconnect));
        }
        return out;
    }

    private static int rank(BotSession b, ReconnectManager.Row r) {
        if (r != null) return r.inFlight ? 0 : 1;
        if (b.state == BotSession.State.ONLINE) return 2;
        if (b.state == BotSession.State.CONNECTING) return 3;
        return 4;
    }


    void draw(int rx, int mx, int my) {
        lastMx = mx; lastMy = my;
        int bw = bw();
        int ax = rx + NexusScreen.PAD;

        s.font().drawString("自动重连", ax, 6, NexusTheme.TEXT_TITLE);
        s.font().drawString("掉线后按随机间隔依次重进", ax, 18, NexusTheme.TEXT_FAINT);

        int[] stats = NexusConfig.autoReconnectStats();
        boolean allOn = stats[1] > 0 && stats[0] == stats[1];
        boolean none = stats[0] == 0;
        String master = allOn ? "全部关闭 (" + stats[0] + "/" + stats[1] + " 开)"
                : "全部开启 (" + stats[0] + "/" + stats[1] + " 开)";
        boolean masterHov = hit(mx, my, ax, 30, bw, 18);
        Ui.button(ax, 30, bw, 18, masterHov, allOn);
        s.font().drawString(master, ax + bw / 2 - s.font().getStringWidth(master) / 2,
                35, allOn ? NexusTheme.ACCENT : (none ? NexusTheme.TEXT_LABEL : NexusTheme.WARN));
        if (masterHov) {
            s.hoverText = "一键设置所有 bot 的自动重连\n"
                    + (allOn ? "点击 = 全部关闭" : "点击 = 全部开启");
            s.hoverX = mx; s.hoverY = my;
        }

        s.font().drawString("随机加入间隔 (秒) · 同服依次进", ax, 52, NexusTheme.TEXT_LABEL);
        int halfStepper = Math.min(84, (bw - 24) / 2);
        drawStepper(ax, 63, halfStepper,
                String.valueOf(NexusConfig.rejoinMinMs() / 1000), mx, my);
        s.font().drawString("~", ax + halfStepper + 4, 68, NexusTheme.TEXT_SUB);
        drawStepper(ax + halfStepper + 16, 63, halfStepper,
                String.valueOf(NexusConfig.rejoinMaxMs() / 1000), mx, my);

        int half = (bw - 8) / 2;
        boolean serial = NexusConfig.rejoinSerial();
        boolean serialHov = hit(mx, my, ax, 85, half, 18);
        Ui.button(ax, 85, half, 18, serialHov, serial);
        String serialLbl = serial ? "同服串行:开" : "同服串行:关";
        s.font().drawString(serialLbl, ax + half / 2 - s.font().getStringWidth(serialLbl) / 2,
                90, serial ? NexusTheme.ACCENT : NexusTheme.TEXT_LABEL);
        s.drawButton(ax + half + 8, 85, bw - half - 8, 18, "清空队列", false, mx, my);
        if (serialHov) {
            s.hoverText = "同一服务器同时只进一个\n关闭 = 全部一起进（旧行为）";
            s.hoverX = mx; s.hoverY = my;
        }

        Ui.divider(ax, 105, bw);

        int pending = ReconnectManager.get().pendingCount();
        List<RowView> rows = snapshot();
        int visible = visibleRows();
        int maxScroll = Math.max(0, rows.size() - visible);
        s.rcScroll = Math.max(0, Math.min(s.rcScroll, maxScroll));

        int listTop = LIST_TOP, listBottom = listBottom();
        s.rcThumbH = 0;
        if (maxScroll > 0) {
            int trackH = listBottom - listTop;
            int thumbH = Math.max(18, trackH * visible / rows.size());
            int thumbY = listTop + (trackH - thumbH) * s.rcScroll / Math.max(1, maxScroll);
            s.rcTrackY = listTop; s.rcTrackH = trackH;
            s.rcThumbY = thumbY; s.rcThumbH = thumbH;
            Ui.fill(thumbX(), listTop, NexusScreen.SCROLL_W, trackH, NexusTheme.BG_CARD);
            boolean thov = s.dragScroll == 5
                    || hit(mx, my, thumbX() - 3, thumbY - 2,
                           NexusScreen.SCROLL_W + 6, thumbH + 4);
            Ui.fill(thumbX(), thumbY, NexusScreen.SCROLL_W, thumbH,
                    thov ? NexusTheme.ACCENT : NexusTheme.BORDER_SOFT);
        }

        BotSession selectedBot = s.firstSelected();
        for (int i = s.rcScroll; i < rows.size() && i < s.rcScroll + visible; i++) {
            RowView r = rows.get(i);
            int y = listTop + (i - s.rcScroll) * (ROW_H + ROW_GAP);
            boolean sel = selectedBot != null && selectedBot == r.session;
            boolean rowHov = hit(mx, my, ax, y, bw - NexusScreen.SCROLL_W - 6, ROW_H);
            Ui.card(ax, y, bw - NexusScreen.SCROLL_W - 4, ROW_H,
                    sel ? NexusTheme.BG_CARD_HOV : NexusTheme.BG_SIDEBAR,
                    sel ? NexusTheme.ACCENT : NexusTheme.BORDER_SOFT, 3);
            if (rowHov && !sel)
                Ui.roundedRect(ax + 1, y + 1, bw - NexusScreen.SCROLL_W - 6, ROW_H - 2, 3,
                        NexusTheme.HOVER_WASH);

            int dot = r.queue != null && r.queue.inFlight ? NexusTheme.WARN
                    : r.queue != null ? NexusTheme.ACCENT
                    : r.session.state == BotSession.State.ONLINE ? NexusTheme.OK
                    : r.session.state == BotSession.State.ERROR ? NexusTheme.ERR
                    : NexusTheme.IDLE;
            Ui.dot(ax + 9, y + 10, dot);
            s.font().drawString(NexusGeometry.trim(r.session.username, 12),
                    ax + 18, y + 5, NexusTheme.TEXT_TITLE);
            s.font().drawString(phaseText(r), ax + 18, y + 17, NexusTheme.TEXT_FAINT);

            int tx = ax + bw - NexusScreen.SCROLL_W - 4 - TOGGLE_W - 4;
            int ty = y + (ROW_H - TOGGLE_H) / 2;
            boolean tovHov = hit(mx, my, tx, ty, TOGGLE_W, TOGGLE_H);
            Ui.roundedRect(tx, ty, TOGGLE_W, TOGGLE_H, 3,
                    r.autoReconnect ? NexusTheme.ACCENT
                            : (tovHov ? NexusTheme.HOVER_WASH : NexusTheme.BG_CARD));
            String tLbl = r.autoReconnect ? "开" : "关";
            s.font().drawString(tLbl, tx + TOGGLE_W / 2 - s.font().getStringWidth(tLbl) / 2,
                    ty + 4, r.autoReconnect ? NexusTheme.BG_SIDEBAR : NexusTheme.TEXT_LABEL);
        }
        if (rows.isEmpty())
            s.font().drawString("还没有账号", ax, listTop + 4, NexusTheme.TEXT_FAINT);
        else if (pending > 0)
            s.font().drawString("队列 " + pending + " 个等待",
                    ax + bw - NexusScreen.SCROLL_W - 6
                            - s.font().getStringWidth("队列 " + pending + " 个等待"),
                    listBottom - 10, NexusTheme.TEXT_FAINT);

        int sy = selTop();
        int sh = bands()[1];
        Ui.card(ax, sy, bw, sh, NexusTheme.BG_SIDEBAR, NexusTheme.BORDER_SOFT, 3);
        if (selectedBot != null && selectedBot != SessionManager.get().humanSession()) {
            String key = sessionKey(selectedBot);
            int stateCol = selectedBot.state == BotSession.State.ONLINE ? NexusTheme.OK
                    : selectedBot.state == BotSession.State.CONNECTING ? NexusTheme.WARN
                    : selectedBot.state == BotSession.State.ERROR ? NexusTheme.ERR
                    : NexusTheme.IDLE;
            Ui.dot(ax + 9, sy + 10, stateCol);
            s.font().drawString(NexusGeometry.trim(selectedBot.username, 14),
                    ax + 18, sy + 5, NexusTheme.TEXT_TITLE);
            s.font().drawString("单bot", ax + bw - 34, sy + 5, NexusTheme.TEXT_FAINT);

            ReconnectManager.Row q = ReconnectManager.get().rowFor(selectedBot);
            String info = q != null
                    ? (q.inFlight ? "连接中 · " : "排队 #" + q.position + " · ") + q.server
                    : NexusGeometry.stateText(selectedBot) + " · "
                      + selectedBot.serverHost + ":" + selectedBot.serverPort;
            s.font().drawString(NexusGeometry.trim(info, Math.max(8, (bw - 16) / 6)),
                    ax + 18, sy + 17, NexusTheme.TEXT_SUB);

            boolean ar = NexusConfig.bindingFor(key).autoReconnect;
            int tw = 46, tx = ax + bw - tw - 6, ty = sy + 30;
            boolean arHov = hit(mx, my, tx, ty, tw, 14);
            Ui.roundedRect(tx, ty, tw, 14, 3, ar ? NexusTheme.ACCENT
                    : (arHov ? NexusTheme.HOVER_WASH : NexusTheme.BG_CARD));
            String arLbl = "重连 " + (ar ? "开" : "关");
            s.font().drawString(arLbl, tx + tw / 2 - s.font().getStringWidth(arLbl) / 2,
                    ty + 3, ar ? NexusTheme.BG_SIDEBAR : NexusTheme.TEXT_LABEL);

            s.font().drawString("间隔", ax, sy + 33, NexusTheme.TEXT_LABEL);
            drawStepper(ax + DELAY_X, sy + 30, STEP_W,
                    (NexusConfig.reconnectDelayMs(selectedBot) / 1000) + "s", mx, my);
            s.font().drawString("次数", ax + ATT_LBL_X, sy + 33, NexusTheme.TEXT_LABEL);
            int maxAtt = NexusConfig.reconnectMaxAttempts(selectedBot);
            drawStepper(ax + ATT_X, sy + 30, STEP_W,
                    maxAtt <= 0 ? "∞" : String.valueOf(maxAtt), mx, my);
        } else {
            s.font().drawString("未选中 bot", ax + 6, sy + 6, NexusTheme.TEXT_TITLE);
            s.font().drawString("点队列里的一行 = 打开它的单独面板", ax + 6, sy + 20,
                    NexusTheme.TEXT_FAINT);
            s.font().drawString("（间隔 / 次数 / 自动重连 都在那里改）", ax + 6, sy + 32,
                    NexusTheme.TEXT_FAINT);
        }

        int evTop = evTop(), evH = bands()[0];
        Ui.divider(ax, evTop - 4, bw);
        s.font().drawString("事件", ax, evTop + 2, NexusTheme.TEXT_SUB);
        if (s.rcEvScroll > 0)
            s.font().drawString("已上翻 " + s.rcEvScroll + " 行",
                    ax + bw - s.font().getStringWidth("已上翻 " + s.rcEvScroll + " 行"),
                    evTop + 2, NexusTheme.TEXT_FAINT);

        List<ReconnectManager.Event> ev = ReconnectManager.get().events();
        int lines = visibleEvLines();
        int maxEv = Math.max(0, ev.size() - lines);
        s.rcEvScroll = Math.max(0, Math.min(s.rcEvScroll, maxEv));
        int start = Math.max(0, ev.size() - lines - s.rcEvScroll);
        int end = Math.min(ev.size(), start + lines);

        s.rcEvThumbH = 0;
        if (maxEv > 0) {
            int trackH = evH - 14;
            int thumbH = Math.max(14, trackH * lines / ev.size());
            int thumbY = evTop + 14 + (trackH - thumbH) * s.rcEvScroll / Math.max(1, maxEv);
            s.rcEvTrackY = evTop + 14; s.rcEvTrackH = trackH;
            s.rcEvThumbY = thumbY; s.rcEvThumbH = thumbH;
            Ui.fill(evThumbX(), evTop + 14, NexusScreen.SCROLL_W, trackH, NexusTheme.BG_CARD);
            boolean thov = s.dragScroll == 6
                    || hit(mx, my, evThumbX() - 3, thumbY - 2,
                           NexusScreen.SCROLL_W + 6, thumbH + 4);
            Ui.fill(evThumbX(), thumbY, NexusScreen.SCROLL_W, thumbH,
                    thov ? NexusTheme.ACCENT : NexusTheme.BORDER_SOFT);
        }
        int ey = evTop + 14;
        for (int i = start; i < end; i++) {
            ReconnectManager.Event e = ev.get(i);
            s.font().drawString(e.time, ax, ey, NexusTheme.TEXT_FAINT);
            int msgX = ax + s.font().getStringWidth(e.time) + 6;
            String msg = NexusGeometry.trim(e.msg,
                    Math.max(6, (bw - (msgX - ax) - NexusScreen.SCROLL_W - 6) / 6));
            s.font().drawString(msg, msgX, ey,
                    "WARN".equals(e.level) ? NexusTheme.WARN : NexusTheme.TEXT_LABEL);
            ey += EV_LINE_H;
        }
        if (ev.isEmpty()) s.font().drawString("暂无事件", ax, ey, NexusTheme.TEXT_FAINT);
    }

    private static String phaseText(RowView r) {
        if (r.queue != null) {
            if (r.queue.inFlight) return "连接中… " + r.queue.server;
            if (r.queue.fireAtNanos > 0) {
                long remMs = (r.queue.fireAtNanos - System.nanoTime()) / 1_000_000L;
                String wait = remMs > 0 ? (remMs / 1000) + "s 后" : "即将";
                return "排队 #" + r.queue.position + " · " + wait + " · " + r.queue.server;
            }
            return "排队 #" + r.queue.position + " · " + r.queue.server;
        }
        switch (r.session.state) {
            case ONLINE:     return "在线 · " + r.session.serverHost + ":" + r.session.serverPort;
            case CONNECTING: return "连接中 · " + r.session.serverHost + ":" + r.session.serverPort;
            case ERROR:      return "失败 · " + NexusGeometry.trim(
                    r.session.lastError == null ? "" : r.session.lastError, 24);
            default:
                return (r.autoReconnect ? "离线" : "离线 · 不自动重连")
                        + " · " + r.session.serverHost + ":" + r.session.serverPort;
        }
    }

    private void drawStepper(int sx, int sy, int w, String val, int mx, int my) {
        Ui.fill(sx, sy, w, 18, NexusTheme.BG_CARD);
        boolean minus = hit(mx, my, sx, sy, 18, 18);
        boolean plus  = hit(mx, my, sx + w - 18, sy, 18, 18);
        Ui.roundedRect(sx, sy, 18, 18, 2,
                minus ? NexusTheme.HOVER_WASH : NexusTheme.BG_CARD_HOV);
        Ui.roundedRect(sx + w - 18, sy, 18, 18, 2,
                plus ? NexusTheme.HOVER_WASH : NexusTheme.BG_CARD_HOV);
        NexusIcons.iconMinus(sx + 9, sy + 9,
                minus ? NexusTheme.ACCENT : NexusTheme.TEXT_LABEL);
        NexusIcons.iconPlus(sx + w - 9, sy + 9,
                plus ? NexusTheme.ACCENT : NexusTheme.TEXT_LABEL);
        s.font().drawString(val, sx + w / 2 - s.font().getStringWidth(val) / 2, sy + 5,
                NexusTheme.TEXT_TITLE);
    }


    @Override
    boolean click(int mx, int my, int btn) {
        int rx = s.width - s.rightW;
        int bw = bw();
        int ax = rx + NexusScreen.PAD;

        if (hit(mx, my, ax, 30, bw, 18)) {   // 统一开关
            int[] stats = NexusConfig.autoReconnectStats();
            boolean allOn = stats[1] > 0 && stats[0] == stats[1];
            NexusConfig.setAllAutoReconnect(!allOn);
            s.toast(!allOn ? "已开启全部 " + stats[1] + " 个 bot 的自动重连"
                    : "已关闭全部 bot 的自动重连", 1800);
            return true;
        }

        int halfStepper = Math.min(84, (bw - 24) / 2);
        if (hit(mx, my, ax, 63, 18, 18)) {
            NexusConfig.setRejoinMinMs(NexusConfig.rejoinMinMs() - 1000); return true;
        }
        if (hit(mx, my, ax + halfStepper - 18, 63, 18, 18)) {
            NexusConfig.setRejoinMinMs(NexusConfig.rejoinMinMs() + 1000); return true;
        }
        int maxX = ax + halfStepper + 16;
        if (hit(mx, my, maxX, 63, 18, 18)) {
            NexusConfig.setRejoinMaxMs(NexusConfig.rejoinMaxMs() - 1000); return true;
        }
        if (hit(mx, my, maxX + halfStepper - 18, 63, 18, 18)) {
            NexusConfig.setRejoinMaxMs(NexusConfig.rejoinMaxMs() + 1000); return true;
        }

        int half = (bw - 8) / 2;
        if (hit(mx, my, ax, 85, half, 18)) {   // 同服串行
            NexusConfig.setRejoinSerial(!NexusConfig.rejoinSerial());
            return true;
        }
        if (hit(mx, my, ax + half + 8, 85, bw - half - 8, 18)) {   // 清空队列
            ReconnectManager.get().clearAll();
            return true;
        }

        if (s.rcThumbH > 0 && hit(mx, my, thumbX() - 3, s.rcThumbY - 2,
                NexusScreen.SCROLL_W + 6, s.rcThumbH + 4)) {
            s.dragScroll = 5; s.dragOffY = my - s.rcThumbY; return true;
        }
        if (s.rcEvThumbH > 0 && hit(mx, my, evThumbX() - 3, s.rcEvThumbY - 2,
                NexusScreen.SCROLL_W + 6, s.rcEvThumbH + 4)) {
            s.dragScroll = 6; s.dragOffY = my - s.rcEvThumbY; return true;
        }

        List<RowView> rows = snapshot();
        int listTop = LIST_TOP, visible = visibleRows();
        for (int i = s.rcScroll; i < rows.size() && i < s.rcScroll + visible; i++) {
            RowView r = rows.get(i);
            int y = listTop + (i - s.rcScroll) * (ROW_H + ROW_GAP);
            int tx = ax + bw - NexusScreen.SCROLL_W - 4 - TOGGLE_W - 4;
            int ty = y + (ROW_H - TOGGLE_H) / 2;
            if (hit(mx, my, tx, ty, TOGGLE_W, TOGGLE_H)) {
                NexusConfig.setAutoReconnect(r.key, !r.autoReconnect);
                r.session.logEvent("自动重连 -> " + (!r.autoReconnect ? "开" : "关"));
                return true;
            }
            if (hit(mx, my, ax, y, bw - NexusScreen.SCROLL_W - 6, ROW_H)) {
                selectSession(r.session);
                return true;
            }
        }

        BotSession selB = s.firstSelected();
        int sy = selTop();
        if (selB != null && selB != SessionManager.get().humanSession()) {
            int tw = 46, tx = ax + bw - tw - 6, ty = sy + 30;
            if (hit(mx, my, tx, ty, tw, 14)) {
                boolean ar = NexusConfig.bindingFor(sessionKey(selB)).autoReconnect;
                NexusConfig.setAutoReconnect(sessionKey(selB), !ar);
                selB.logEvent("自动重连 -> " + (!ar ? "开" : "关"));
                return true;
            }
            if (hit(mx, my, ax + DELAY_X, sy + 30, 18, 18)) {
                NexusConfig.setReconnectDelayMs(selB,
                        Math.max(0, NexusConfig.reconnectDelayMs(selB) - 1000));
                return true;
            }
            if (hit(mx, my, ax + DELAY_X + STEP_W - 18, sy + 30, 18, 18)) {
                NexusConfig.setReconnectDelayMs(selB,
                        NexusConfig.reconnectDelayMs(selB) + 1000);
                return true;
            }
            if (hit(mx, my, ax + ATT_X, sy + 30, 18, 18)) {
                NexusConfig.setReconnectMaxAttempts(selB,
                        Math.max(0, NexusConfig.reconnectMaxAttempts(selB) - 1));
                return true;
            }
            if (hit(mx, my, ax + ATT_X + STEP_W - 18, sy + 30, 18, 18)) {
                NexusConfig.setReconnectMaxAttempts(selB,
                        NexusConfig.reconnectMaxAttempts(selB) + 1);
                return true;
            }
        }
        return false;
    }

    private void selectSession(BotSession target) {
        List<BotSession> filtered = s.filteredSessions();
        for (int i = 0; i < filtered.size(); i++) {
            if (filtered.get(i) == target) {
                s.addMode = false;
                s.selected.clear();
                s.selected.add(i);
                s.anchor = i;
                s.relayout();
                return;
            }
        }
        s.toast("该账号被分组 / 搜索过滤了，先清空筛选", 1800);
    }

    @Override
    boolean scroll(int dWheel) {
        int dir = Integer.signum(dWheel);
        if (lastMy >= evTop() - 6) {
            int lines = visibleEvLines();
            int maxEv = Math.max(0, ReconnectManager.get().events().size() - lines);
            s.rcEvScroll = Math.max(0, Math.min(maxEv, s.rcEvScroll + dir * 3));
            return true;
        }
        int maxS = Math.max(0, snapshot().size() - visibleRows());
        s.rcScroll = Math.max(0, Math.min(maxS, s.rcScroll - dir));
        return true;
    }

    @Override
    boolean drag(int mx, int my) {
        if (s.dragScroll == 5 && s.rcThumbH > 0 && s.rcTrackH > s.rcThumbH) {
            int ty = Math.max(s.rcTrackY, Math.min(my - s.dragOffY,
                    s.rcTrackY + s.rcTrackH - s.rcThumbH));
            int maxS = Math.max(0, snapshot().size() - visibleRows());
            s.rcScroll = Math.max(0, Math.min(maxS,
                    (ty - s.rcTrackY) * maxS / Math.max(1, s.rcTrackH - s.rcThumbH)));
            return true;
        }
        if (s.dragScroll == 6 && s.rcEvThumbH > 0 && s.rcEvTrackH > s.rcEvThumbH) {
            int ty = Math.max(s.rcEvTrackY, Math.min(my - s.dragOffY,
                    s.rcEvTrackY + s.rcEvTrackH - s.rcEvThumbH));
            int lines = visibleEvLines();
            int maxEv = Math.max(0, ReconnectManager.get().events().size() - lines);
            s.rcEvScroll = Math.max(0, Math.min(maxEv,
                    (ty - s.rcEvTrackY) * maxEv / Math.max(1, s.rcEvTrackH - s.rcEvThumbH)));
            return true;
        }
        return false;
    }
}
