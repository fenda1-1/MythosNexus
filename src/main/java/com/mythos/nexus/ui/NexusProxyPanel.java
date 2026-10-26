package com.mythos.nexus.ui;

import com.mythos.nexus.core.NexusConfig;
import com.mythos.nexus.session.BotSession;
import com.mythos.nexus.session.SessionManager;

import java.util.List;

import static com.mythos.nexus.ui.NexusGeometry.hit;
import static com.mythos.nexus.ui.NexusGeometry.sessionKey;

/** Right panel for the PROXIES view — proxy list, add form, per-account assign. */
final class NexusProxyPanel extends NexusPanel {

    // List geometry shared by draw() and click().
    private static final int LIST_TOP = 46, LIST_H = 140,
            ROW_H = 30, ROW_STEP = 34;

    private static final String[] TYPES = {"socks5", "http"};
    private static final String[] TYPE_LABELS = {"SOCKS5", "HTTP"};
    private static final int TYPE_W = 44, TYPE_H = 14;
    private String addType = "socks5";
    /** Index of the proxy loaded into the form, -1 = creating a new one. */
    private int editIndex = -1;
    private static final int BTN_GAP = 6;

    NexusProxyPanel(NexusScreen s) { super(s); }

    private void clearForm() {
        editIndex = -1;
        addType = "socks5";
        s.proxyHostField.setText("");
        s.proxyUserField.setText("");
        s.proxyPassField.setText("");
    }

    private void loadForm(int i, NexusConfig.Proxy p) {
        editIndex = i;
        addType = "http".equalsIgnoreCase(p.type) ? "http" : "socks5";
        String h = p.host == null ? "" : p.host;
        s.proxyHostField.setText((h.indexOf(':') >= 0 ? "[" + h + "]" : h) + ":" + p.port);
        s.proxyUserField.setText(p.user == null ? "" : p.user);
        s.proxyPassField.setText(p.pass == null ? "" : p.pass);
        s.proxyHostField.setCursorPositionZero();
        s.proxyUserField.setCursorPositionZero();
        s.proxyPassField.setCursorPositionZero();
    }

    private void saveForm() {
        String addr = s.proxyHostField.getText().trim();
        String u = s.proxyUserField.getText().trim();
        String pw = s.proxyPassField.getText().trim();
        // optional scheme prefix picks the type, e.g. socks5://1.2.3.4:1080
        int sch = addr.indexOf("://");
        if (sch > 0) {
            String scheme = addr.substring(0, sch).toLowerCase();
            if (scheme.startsWith("socks")) addType = "socks5";
            else if (scheme.startsWith("http")) addType = "http";
            addr = addr.substring(sch + 3);
        }
        int colon = addr.lastIndexOf(':');
        String h = colon > 0 ? addr.substring(0, colon).trim() : "";
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        int port;
        try { port = colon > 0 ? Integer.parseInt(addr.substring(colon + 1).trim()) : 0; }
        catch (NumberFormatException e) { port = 0; }
        if (h.isEmpty() || port <= 0 || port > 65535) {
            s.toast("地址格式应为 ip:port", 2000);
            return;
        }
        NexusConfig.Proxy px = new NexusConfig.Proxy();
        px.host = h; px.port = port;
        px.user = u; px.pass = pw;
        px.type = addType;
        List<NexusConfig.Proxy> ps = NexusConfig.proxies();
        if (editIndex >= 0 && editIndex < ps.size()) {
            NexusConfig.updateProxy(editIndex, px);
            s.proxyTestResult.remove(editIndex);
            s.toast("已保存修改: " + px.spec(), 2000);
        } else {
            NexusConfig.addProxy(px);
            editIndex = NexusConfig.proxies().size() - 1;
            s.toast("已新建代理: " + px.spec(), 2000);
        }
    }

    private int typeChipX(int rx, int t) {
        return rx + s.panelW() - NexusScreen.PAD - (TYPES.length - t) * (TYPE_W + 4) + 4;
    }

    void draw(int rx, int mx, int my) {
        s.font().drawString("代理", rx + NexusScreen.PAD, 12, NexusTheme.TEXT_TITLE);
        s.font().drawString("SOCKS5 / HTTP", rx + NexusScreen.PAD, 24, NexusTheme.TEXT_SUB);

        int py = s.proxyHostField.y - 30;
        if (editIndex >= NexusConfig.proxies().size()) editIndex = -1;
        s.font().drawString(editIndex >= 0 ? "编辑代理 #" + (editIndex + 1) : "新建代理",
                rx + NexusScreen.PAD, py, editIndex >= 0 ? NexusTheme.ACCENT : NexusTheme.TEXT_SUB);
        for (int t = 0; t < TYPES.length; t++) {
            int tx = typeChipX(rx, t), ty = py - 3;
            boolean on = TYPES[t].equals(addType);
            boolean hov = hit(mx, my, tx, ty, TYPE_W, TYPE_H);
            Ui.card(tx, ty, TYPE_W, TYPE_H, on ? NexusTheme.ACCENT : (hov ? NexusTheme.BG_CARD_HOV : NexusTheme.BG_CARD),
                    on ? NexusTheme.ACCENT : NexusTheme.BORDER_SOFT);
            String lbl = TYPE_LABELS[t];
            s.font().drawString(lbl, tx + (TYPE_W - s.font().getStringWidth(lbl)) / 2, ty + 3,
                    on ? 0xFFFFFFFF : NexusTheme.TEXT_LABEL);
        }

        s.font().drawString("地址 (ip:port)", rx + NexusScreen.PAD, s.proxyHostField.y - 16, NexusTheme.TEXT_FAINT);
        s.font().drawString("user(可选)", rx + NexusScreen.PAD, s.proxyUserField.y - 16, NexusTheme.TEXT_FAINT);
        s.font().drawString("pass(可选)", rx + NexusScreen.PAD, s.proxyPassField.y - 16, NexusTheme.TEXT_FAINT);
        Ui.inputFrame(s.proxyHostField.x - 4, s.proxyHostField.y - 6,
                s.panelW() - NexusScreen.PAD * 2, 20, s.proxyHostField.isFocused());
        Ui.inputFrame(s.proxyUserField.x - 4, s.proxyUserField.y - 6,
                s.panelW() - NexusScreen.PAD * 2, 20, s.proxyUserField.isFocused());
        Ui.inputFrame(s.proxyPassField.x - 4, s.proxyPassField.y - 6,
                s.panelW() - NexusScreen.PAD * 2, 20, s.proxyPassField.isFocused());

        int addY = s.proxyPassField.y + 22;
        int halfW = (s.panelW() - NexusScreen.PAD * 2 - BTN_GAP) / 2;
        s.drawButton(rx + NexusScreen.PAD, addY, halfW, 22, "新建", false, mx, my);
        s.drawButton(rx + NexusScreen.PAD + halfW + BTN_GAP, addY, halfW, 22, "保存", false, mx, my);
        if (hit(mx, my, rx + NexusScreen.PAD, addY, halfW, 22)) {
            s.hoverText = "清空输入框，新建代理"; s.hoverX = mx; s.hoverY = my;
        } else if (hit(mx, my, rx + NexusScreen.PAD + halfW + BTN_GAP, addY, halfW, 22)) {
            s.hoverText = editIndex >= 0 ? "保存对 #" + (editIndex + 1) + " 的修改" : "保存为新代理";
            s.hoverX = mx; s.hoverY = my;
        }
        py = addY + 30;

        // connect delay between batch connections (ms)
        s.font().drawString("连接间隔(ms)", rx + NexusScreen.PAD, py, NexusTheme.TEXT_SUB); py += 12;
        s.fDelay.x = rx + NexusScreen.PAD + 4;
        s.fDelay.y = py + 4;
        Ui.inputFrame(rx + NexusScreen.PAD, py, s.panelW() - NexusScreen.PAD * 2, 20, s.fDelay.isFocused());
        py += 26;

        BotSession sel = s.firstSelected();
        List<NexusConfig.Proxy> ps = NexusConfig.proxies();
        boolean assignMode = sel != null && sel != SessionManager.get().humanSession();

        py += 8;
        Ui.divider(rx + NexusScreen.PAD, py, s.panelW() - NexusScreen.PAD * 2); py += 8;
        String listTitle = assignMode ? "给 \"" + sel.username + "\" 用" : "已保存代理";
        int totalRows = ps.size() + (assignMode ? 1 : 0);
        s.font().drawString(listTitle + " (" + totalRows + ")",
                rx + NexusScreen.PAD, py, NexusTheme.TEXT_SUB); py += 12;

        int cardTop = py;
        int cardH = Math.min(LIST_H, totalRows * ROW_STEP + 8);
        lastCardTop = cardTop;
        lastCardH = cardH;
        int visibleRows = (cardH - 8) / ROW_STEP;
        lastMaxScroll = Math.max(0, totalRows - visibleRows);
        s.proxyScroll = Math.max(0, Math.min(s.proxyScroll, lastMaxScroll));
        Ui.card(rx + NexusScreen.PAD, cardTop, s.panelW() - NexusScreen.PAD * 2, cardH,
                NexusTheme.BG_CARD, NexusTheme.BORDER_SOFT, 4);
        int rowY = cardTop + 4;
        int listBottom = cardTop + cardH - 4;
        int start = assignMode ? s.proxyScroll - 1 : s.proxyScroll;
        for (int i = start; i < ps.size(); i++) {
            if (rowY + ROW_H > listBottom) break;
            boolean directRow = i < 0;
            boolean cur = assignMode && sel != null && sel.proxyIndex == i;
            boolean editing = !directRow && i == editIndex;
            boolean hov = hit(mx, my, rx + NexusScreen.PAD + 4, rowY,
                    s.panelW() - NexusScreen.PAD * 2 - 8, ROW_H);
            if (hov || cur || editing)
                Ui.roundedRect(rx + NexusScreen.PAD + 4, rowY, s.panelW() - NexusScreen.PAD * 2 - 8, ROW_H,
                        3, (cur || editing) ? NexusTheme.BG_CARD_HOV : NexusTheme.HOVER_WASH);
            if (editing)
                Ui.fill(rx + NexusScreen.PAD + 4, rowY + 4, 2, ROW_H - 8, NexusTheme.ACCENT);
            String lbl = directRow ? "直连" : ps.get(i).spec();
            int lblMax = s.panelW() - NexusScreen.PAD * 2 - 100 - 10 - 4;
            if (s.font().getStringWidth(lbl) > lblMax)
                lbl = s.font().trimStringToWidth(lbl, Math.max(0, lblMax - s.font().getStringWidth("…"))) + "…";
            s.font().drawString(lbl, rx + NexusScreen.PAD + 10, rowY + 5,
                    cur ? NexusTheme.ACCENT : NexusTheme.TEXT_TITLE);
            if (cur)
                s.font().drawString("✓", rx + NexusScreen.PAD + s.panelW() - NexusScreen.PAD * 2 - 30,
                        rowY + 11, NexusTheme.ACCENT);
            if (!directRow) {
                String res = s.proxyTestResult.get(i);
                int testX = rx + NexusScreen.PAD + s.panelW() - NexusScreen.PAD * 2 - 66;
                boolean tov = hit(mx, my, testX, rowY + 5, 34, 18);
                s.font().drawString(res == null ? "测速" : res, testX, rowY + 11,
                        tov ? NexusTheme.ACCENT : NexusTheme.TEXT_FAINT);
                int ex = testX - 34;
                boolean eov = hit(mx, my, ex, rowY + 5, 30, 18);
                s.font().drawString("编辑", ex, rowY + 11,
                        (eov || editing) ? NexusTheme.ACCENT : NexusTheme.TEXT_FAINT);
                if (eov) { s.hoverText = "回填到输入框编辑"; s.hoverX = mx; s.hoverY = my; }
                int dx = rx + NexusScreen.PAD + s.panelW() - NexusScreen.PAD * 2 - 18;
                boolean dov = hit(mx, my, dx - 6, rowY, 20, ROW_H);
                s.font().drawString("x", dx, rowY + 11,
                        dov ? NexusTheme.ERR : NexusTheme.TEXT_FAINT);
                if (tov) {
                    s.hoverText = "测试代理延迟"; s.hoverX = mx; s.hoverY = my;
                } else if (dov) {
                    s.hoverText = "删除代理"; s.hoverX = mx; s.hoverY = my;
                }
            }
            rowY += ROW_STEP;
        }

        // proxy-list scrollbar — widens on hover / while dragging
        if (lastMaxScroll > 0) {
            int trackH = cardH - 8;
            int thumbH = Math.max(16, trackH * visibleRows / totalRows);
            int thumbY = cardTop + 4 + (trackH - thumbH) * s.proxyScroll / lastMaxScroll;
            int sbRight = rx + NexusScreen.PAD + s.panelW() - NexusScreen.PAD * 2 - 2;
            sbHitX = sbRight - SB_HIT_W;
            boolean thov = proxyDrag || hit(mx, my, sbHitX, cardTop + 4, SB_HIT_W + 2, trackH);
            int sw = thov ? 6 : 3;
            Ui.fill(sbRight - sw, cardTop + 4, sw, trackH, NexusTheme.BORDER_SOFT);
            Ui.fill(sbRight - sw, thumbY, sw, thumbH,
                    thov ? NexusTheme.ACCENT : NexusTheme.TEXT_FAINT);
            s.proxyThumbY = thumbY;
            s.proxyThumbH = thumbH;
            s.proxyTrackY = cardTop + 4;
            s.proxyTrackH = trackH;
        } else {
            s.proxyThumbY = -1;
        }
    }


    @Override
    boolean click(int mx, int my, int btn) {
        int rx = s.width - s.rightW;
        int ax = rx + NexusScreen.PAD;
        int bw = s.panelW() - NexusScreen.PAD * 2;
        List<NexusConfig.Proxy> ps = NexusConfig.proxies();

        s.proxyHostField.mouseClicked(mx, my, btn);
        s.proxyUserField.mouseClicked(mx, my, btn);
        s.proxyPassField.mouseClicked(mx, my, btn);
        s.fDelay.mouseClicked(mx, my, btn);

        int typeY = s.proxyHostField.y - 30 - 3;
        for (int t = 0; t < TYPES.length; t++) {
            if (hit(mx, my, typeChipX(rx, t), typeY, TYPE_W, TYPE_H)) { addType = TYPES[t]; return true; }
        }

        // scrollbar before rows — a whole-row assign click would eat it
        if (s.proxyThumbY > 0 && hit(mx, my, sbHitX, s.proxyTrackY, SB_HIT_W + 2, s.proxyTrackH)) {
            if (my >= s.proxyThumbY && my < s.proxyThumbY + s.proxyThumbH) {
                proxyDragOffY = my - s.proxyThumbY;
            } else {
                // track click: centre the thumb on the cursor, then keep dragging
                proxyDragOffY = s.proxyThumbH / 2;
            }
            proxyDrag = true;
            drag(mx, my);
            return true;
        }

        // proxy list — geometry recorded by the last draw()
        int addY = s.proxyPassField.y + 22;
        BotSession sel = s.firstSelected();
        boolean assignMode = sel != null && sel != SessionManager.get().humanSession();
        int rowY = lastCardTop + 4;
        int listBottom = lastCardTop + lastCardH - 4;
        int start = assignMode ? s.proxyScroll - 1 : s.proxyScroll;
        for (int i = start; i < ps.size(); i++) {
            if (rowY + ROW_H > listBottom) break;
            boolean directRow = i < 0;
            if (!directRow) {
                int testX = ax + bw - 66;
                int dx = ax + bw - 18;
                if (hit(mx, my, testX - 34, rowY + 5, 30, 18)) {   // 编辑
                    loadForm(i, ps.get(i));
                    return true;
                }
                if (hit(mx, my, testX, rowY + 5, 34, 18)) {   // 测速
                    s.testProxy(i);
                    return true;
                }
                if (hit(mx, my, dx - 6, rowY, 20, ROW_H)) {   // delete
                    NexusConfig.removeProxy(i);
                    if (i == editIndex) clearForm();
                    else if (i < editIndex) editIndex--;
                    return true;
                }
            }
            // whole-row click assigns when a bot is selected
            if (assignMode && sel != null && hit(mx, my, ax, rowY, bw, ROW_H)) {
                sel.proxyIndex = i;
                NexusConfig.setProxy(sessionKey(sel), i);
                sel.logEvent("proxy -> " + (i < 0 ? "direct" : ps.get(i).spec()));
                return true;
            }
            rowY += ROW_STEP;
        }

        int halfW = (bw - BTN_GAP) / 2;
        if (hit(mx, my, ax, addY, halfW, 22)) {                    // 新建
            clearForm();
            s.proxyHostField.setFocused(true);
            return true;
        }
        if (hit(mx, my, ax + halfW + BTN_GAP, addY, halfW, 22)) {  // 保存
            saveForm();
            return true;
        }
        return false;
    }

    /** Wheel over the right panel in PROXIES view scrolls the proxy list. */
    @Override
    boolean scroll(int dWheel) {
        s.proxyScroll = Math.max(0, Math.min(lastMaxScroll,
                s.proxyScroll - Integer.signum(dWheel)));
        return true;
    }

    /** Proxy-list scrollbar drag — reuses proxyThumbY/H from draw(). */
    @Override
    boolean drag(int mx, int my) {
        if (!proxyDrag) return false;
        int trackH = s.proxyTrackH;
        int span = trackH - s.proxyThumbH;
        if (span <= 0 || lastMaxScroll <= 0) { proxyDrag = false; return false; }
        int ty = Math.max(s.proxyTrackY, Math.min(my - proxyDragOffY,
                s.proxyTrackY + span));
        s.proxyScroll = Math.max(0, Math.min(lastMaxScroll,
                ((ty - s.proxyTrackY) * lastMaxScroll + span / 2) / span));
        return true;
    }

    @Override
    void release() { proxyDrag = false; }

    /** Hover/grab width of the scrollbar column (bar itself is 3px, 6px on hover). */
    private static final int SB_HIT_W = 10;
    private boolean proxyDrag = false;
    private int proxyDragOffY = 0;
    /** From the last draw(): max scroll rows and the scrollbar hit column x. */
    private int lastMaxScroll = 0;
    private int sbHitX = 0;
    private int lastCardTop = 0, lastCardH = 0;
}
