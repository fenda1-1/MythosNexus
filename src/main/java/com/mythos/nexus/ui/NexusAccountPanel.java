package com.mythos.nexus.ui;

import com.mythos.nexus.MythosNexusMod;
import com.mythos.nexus.core.NexusConfig;
import com.mythos.nexus.session.BotSession;
import com.mythos.nexus.session.SessionManager;

import static com.mythos.nexus.ui.NexusGeometry.currentServer;
import static com.mythos.nexus.ui.NexusGeometry.hit;
import static com.mythos.nexus.ui.NexusGeometry.sessionKey;

/** Right panel for the ACCOUNTS view — edit fields, group assign, actions. */
final class NexusAccountPanel extends NexusPanel {

    NexusAccountPanel(NexusScreen s) { super(s); }

    private static int parseFieldInt(net.minecraft.client.gui.GuiTextField f, int def) {
        try { return Integer.parseInt(f.getText().trim()); }
        catch (Throwable t) { return def; }
    }

    /** Compact [-][value][+] stepper used for numeric per-account settings. */
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
        s.font().drawString(val,
                sx + w / 2 - s.font().getStringWidth(val) / 2, sy + 5,
                NexusTheme.TEXT_TITLE);
    }

    // Screen-space Y of the LIVE drag bars from the last draw (already scrolled).
    private int liveBarY = -1, liveBarH = 16, liveBar2Y = -1;
    private boolean liveBarsShown = false;
    /** 0 = none, 1 = width bar, 2 = height bar. */
    private int liveDrag = 0;

    private void setLiveFromBar(int mx) {
        int barX = s.width - s.rightW + NexusScreen.PAD;
        int bw = Math.max(1, s.panelW() - NexusScreen.PAD * 2);
        int rel = Math.max(0, Math.min(bw, mx - barX));
        if (liveDrag == 1) {
            int dw = s.mc().displayWidth;
            s.fLiveW.setText(String.valueOf(Math.max(320, Math.min(dw, 320 + rel * (dw - 320) / bw))));
        } else if (liveDrag == 2) {
            int dh = s.mc().displayHeight;
            s.fLiveH.setText(String.valueOf(Math.max(180, Math.min(dh, 180 + rel * (dh - 180) / bw))));
        }
    }

    void draw(int rx, int mx, int my) {
        BotSession sel = s.firstSelected();
        boolean editable = !s.addMode && sel != null
                && sel != SessionManager.get().humanSession();

        s.font().drawString(s.addMode || !editable ? "添加账号" : "编辑账号",
                rx + NexusScreen.PAD, 12, NexusTheme.TEXT_TITLE);
        s.font().drawString(editable ? sel.username : "离线模式",
                rx + NexusScreen.PAD, 24, NexusTheme.TEXT_SUB);

        s.font().drawString("用户名",     rx + NexusScreen.PAD, s.fName.y - 16, NexusTheme.TEXT_SUB);
        s.font().drawString("服务器地址", rx + NexusScreen.PAD, s.fServer.y - 16, NexusTheme.TEXT_SUB);
        Ui.inputFrame(s.fName.x - 4, s.fName.y - 6,
                s.panelW() - NexusScreen.PAD * 2, 20, s.fName.isFocused());
        Ui.inputFrame(s.fServer.x - 4, s.fServer.y - 6,
                s.panelW() - NexusScreen.PAD * 2, 20, s.fServer.isFocused());

        int bw = s.panelW() - NexusScreen.PAD * 2;
        int baseY = s.fServer.y + 26;
        s.clipPush(rx, baseY, s.panelW(), s.height - baseY);
        int ay = baseY - s.rightScroll;
        s.drawButton(rx + NexusScreen.PAD, ay, bw, 18, "填入当前服务器", false, mx, my);
        ay += 24;

        s.drawButton(rx + NexusScreen.PAD, ay, bw, 22, editable ? "保存修改 (Ctrl+S)" : "添加账号",
                false, mx, my);
        ay += 30;
        if (editable) {
            String lbl = sel.state == BotSession.State.ONLINE
                    || sel.state == BotSession.State.CONNECTING
                    ? "断开连接" : "重新连接";
            s.drawButton(rx + NexusScreen.PAD, ay, bw, 22, lbl, false, mx, my);
            ay += 30;
            s.drawButton(rx + NexusScreen.PAD, ay, bw, 22,
                    SessionManager.get().mounted() == sel ? "当前视角" : "切换到视角",
                    SessionManager.get().mounted() == sel, mx, my);
            ay += 30;
        }

        Ui.divider(rx + NexusScreen.PAD, ay, bw); ay += 8;
        s.font().drawString("分组", rx + NexusScreen.PAD, ay, NexusTheme.TEXT_SUB); ay += 14;
        for (String g : NexusScreen.allGroups()) {
            boolean hov = hit(mx, my, rx + NexusScreen.PAD, ay, bw, 20);
            boolean cur = editable && sel.group.equals(g);
            if (hov || cur)
                Ui.roundedRect(rx + NexusScreen.PAD, ay, bw, 20, 3,
                        cur ? NexusTheme.BG_CARD_HOV : NexusTheme.HOVER_WASH);
            s.font().drawString(g, rx + NexusScreen.PAD + 6, ay + 6,
                    cur ? NexusTheme.ACCENT : NexusTheme.TEXT_LABEL);
            ay += 23;
        }
        // new group input (field follows the drawn row so clicks line up)
        ay += 4;
        s.groupNameField.x = rx + NexusScreen.PAD + 4;
        s.groupNameField.y = ay + 6;
        Ui.inputFrame(rx + NexusScreen.PAD, ay, bw, 20, s.groupNameField.isFocused());
        ay += 24;
        s.drawButton(rx + NexusScreen.PAD, ay, bw, 20, "新建并归入", false, mx, my);
        ay += 26;

        if (editable) {
            Ui.divider(rx + NexusScreen.PAD, ay, bw); ay += 8;
            s.font().drawString("备注", rx + NexusScreen.PAD, ay, NexusTheme.TEXT_SUB); ay += 14;
            s.fNote.x = rx + NexusScreen.PAD + 4;
            s.fNote.y = ay + 4;
            Ui.inputFrame(rx + NexusScreen.PAD, ay, bw, 20, s.fNote.isFocused());
            ay += 26;

            s.drawButton(rx + NexusScreen.PAD, ay, bw, 20,
                    "自动重连设置 →", false, mx, my);
            ay += 26;

            Ui.divider(rx + NexusScreen.PAD, ay, bw); ay += 8;
            s.font().drawString("状态", rx + NexusScreen.PAD, ay, NexusTheme.TEXT_SUB); ay += 14;
            s.font().drawString("在线时长  " + sel.onlineSeconds() + " 秒",
                    rx + NexusScreen.PAD, ay, NexusTheme.TEXT_LABEL); ay += 12;
            s.font().drawString("内存占用  " + (sel.estimatedMemoryBytes() / 1024 / 1024) + " MB",
                    rx + NexusScreen.PAD, ay, NexusTheme.TEXT_LABEL); ay += 12;
            s.font().drawString("收 " + sel.bytesReceived + " / 发 " + sel.bytesSent,
                    rx + NexusScreen.PAD, ay, NexusTheme.TEXT_LABEL); ay += 12;
            ay += 4;
        }

        // LIVE viewport resolution — shown when LIVE card mode is active.
        // Editable in the right panel; drag bars for quick adjustment.
        if (s.cardMode == NexusScreen.CardMode.LIVE && (editable || s.addMode)) {
            Ui.divider(rx + NexusScreen.PAD, ay, bw); ay += 8;
            s.font().drawString("实时渲染分辨率", rx + NexusScreen.PAD, ay,
                    NexusTheme.TEXT_SUB); ay += 14;

            int half = (bw - 8) / 2;
            s.fLiveW.x = rx + NexusScreen.PAD + 4;
            s.fLiveH.x = rx + NexusScreen.PAD + 4 + half + 8;
            s.fLiveW.y = ay + 4; s.fLiveH.y = ay + 4;
            Ui.inputFrame(rx + NexusScreen.PAD, ay, half, 20, s.fLiveW.isFocused());
            Ui.inputFrame(rx + NexusScreen.PAD + half + 8, ay, bw - half - 8, 20,
                    s.fLiveH.isFocused());
            s.font().drawString("宽", rx + NexusScreen.PAD + 2, ay + 24,
                    NexusTheme.TEXT_FAINT);
            s.font().drawString("高", rx + NexusScreen.PAD + half + 10, ay + 24,
                    NexusTheme.TEXT_FAINT);
            ay += 40;

            // Width drag bar — full-width slider. No hover magnification.
            // Min 320, max = display resolution.
            int dispW = s.mc().displayWidth;
            int curW = parseFieldInt(s.fLiveW, 320);
            int dragY = ay;
            int dragH = 16;
            liveBarsShown = true;
            liveBarY = dragY;
            liveBarH = dragH;
            if (liveDrag == 1 || hit(mx, my, rx + NexusScreen.PAD, dragY, bw, dragH)) {
                s.hoverText = "拖动调整 LIVE 视口宽度"; s.hoverX = mx; s.hoverY = my;
            }
            int fill = (int)((long)(curW - 320) * bw / Math.max(1, dispW - 320));
            Ui.fill(rx + NexusScreen.PAD, dragY, bw, dragH, NexusTheme.BG_CARD);
            Ui.fill(rx + NexusScreen.PAD, dragY, Math.min(fill, bw), dragH, NexusTheme.ACCENT);
            String wLabel = String.valueOf(curW);
            s.font().drawString(wLabel,
                    rx + NexusScreen.PAD + bw / 2 - s.font().getStringWidth(wLabel) / 2,
                    dragY + 4, NexusTheme.TEXT_TITLE);
            ay += dragH + 10;

            // Height drag bar — its Y sits liveBarY + liveBarH + 10 in
            // content space (screen coords: + rightScroll like liveBarY).
            int dispH = s.mc().displayHeight;
            int curH = parseFieldInt(s.fLiveH, 180);
            int hFill = (int)((long)(curH - 180) * bw / Math.max(1, dispH - 180));
            int hDragY = ay;
            liveBar2Y = hDragY;
            if (liveDrag == 2 || hit(mx, my, rx + NexusScreen.PAD, hDragY, bw, dragH)) {
                s.hoverText = "拖动调整 LIVE 视口高度"; s.hoverX = mx; s.hoverY = my;
            }
            Ui.fill(rx + NexusScreen.PAD, hDragY, bw, dragH, NexusTheme.BG_CARD);
            Ui.fill(rx + NexusScreen.PAD, hDragY, Math.min(hFill, bw), dragH, NexusTheme.ACCENT);
            String hLabel = String.valueOf(curH);
            s.font().drawString(hLabel,
                    rx + NexusScreen.PAD + bw / 2 - s.font().getStringWidth(hLabel) / 2,
                    hDragY + 4, NexusTheme.TEXT_TITLE);
            ay += dragH + 10;
        } else {
            liveBarsShown = false;   // not in LIVE mode — no bars
        }

        // Import / export — bottom of the edit panel
        Ui.divider(rx + NexusScreen.PAD, ay, bw); ay += 8;
        s.font().drawString("备份", rx + NexusScreen.PAD, ay, NexusTheme.TEXT_SUB); ay += 14;
        int halfW = (bw - 8) / 2;
        s.drawButton(rx + NexusScreen.PAD, ay, halfW, 18, "导出", false, mx, my);
        s.drawButton(rx + NexusScreen.PAD + halfW + 8, ay, bw - halfW - 8, 18,
                "导入", false, mx, my);
        ay += 26;

        s.clipPop();
        s.rightContentBottom = ay + s.rightScroll;
        int viewH = s.height - 20;
        int thumbX = rx + s.panelW() - NexusScreen.SCROLL_W - 4;
        if (s.rightContentBottom > viewH) {
            int trackY = baseY, trackH = Math.max(1, viewH - baseY);
            int thumbH = Math.max(24, trackH * viewH / s.rightContentBottom);
            int thumbY = trackY + (trackH - thumbH) * s.rightScroll
                    / Math.max(1, s.rightContentBottom - viewH);
            s.rightTrackY = trackY; s.rightTrackH = trackH;
            s.rightThumbY = thumbY; s.rightThumbH = thumbH;
            boolean thov = s.dragScroll == 3
                    || hit(mx, my, thumbX - 3, thumbY - 2,
                           NexusScreen.SCROLL_W + 6, thumbH + 4);
            Ui.fill(thumbX, thumbY, NexusScreen.SCROLL_W, thumbH,
                    thov ? NexusTheme.ACCENT : NexusTheme.BORDER_SOFT);
        } else {
            s.rightThumbH = 0;
        }
    }

    //      mouseClickMove) ----

    @Override
    boolean click(int mx, int my, int btn) {
        // text fields first — they track their own focus
        s.fName.mouseClicked(mx, my, btn);
        s.fServer.mouseClicked(mx, my, btn);
        s.groupNameField.mouseClicked(mx, my, btn);
        s.fNote.mouseClicked(mx, my, btn);
        if (s.cardMode == NexusScreen.CardMode.LIVE) {
            s.fLiveW.mouseClicked(mx, my, btn);
            s.fLiveH.mouseClicked(mx, my, btn);
            // LIVE W/H drag bars — set the value from bar position.
            int barX = s.width - s.rightW + NexusScreen.PAD;
            int bw = s.panelW() - NexusScreen.PAD * 2;
            if (liveBarsShown && btn == 0
                    && hit(mx, my, barX, liveBarY, bw, liveBarH)) {
                liveDrag = 1;
                setLiveFromBar(mx);
                return true;
            }
            if (liveBarsShown && btn == 0
                    && hit(mx, my, barX, liveBar2Y, bw, liveBarH)) {
                liveDrag = 2;
                setLiveFromBar(mx);
                return true;
            }
        }

        int rx = s.width - s.rightW;
        int thumbX = rx + s.panelW() - NexusScreen.SCROLL_W - 4;
        if (s.rightThumbH > 0 && hit(mx, my, thumbX - 3,
                s.rightThumbY - 2, NexusScreen.SCROLL_W + 6, s.rightThumbH + 4)) {
            s.dragScroll = 3; s.dragOffY = my - s.rightThumbY; return true;
        }

        // button/row walk — mirrors draw()'s ay progression exactly.
        int ax = rx + NexusScreen.PAD;
        int bw = s.panelW() - NexusScreen.PAD * 2;
        BotSession sel = s.firstSelected();
        boolean editable = !s.addMode && sel != null
                && sel != SessionManager.get().humanSession();
        int baseY = s.fServer.y + 26;
        if (my < baseY) return false;
        int ay = baseY - s.rightScroll;

        if (hit(mx, my, ax, ay, bw, 18)) {   // 填入当前服务器
            String cs = currentServer();
            if (cs != null && !cs.isEmpty()) s.fServer.setText(cs);
            return true;
        }
        ay += 24;
        if (hit(mx, my, ax, ay, bw, 22)) {   // 保存修改 / 添加账号
            if (editable) s.applyEdit(sel);
            else {
                String u = s.fName.getText().trim();
                String sv = s.fServer.getText().trim();
                if (!u.isEmpty() && !sv.isEmpty()
                        && !SessionManager.get().hasSessionNamed(u))
                    MythosNexusMod.addAndConnectBot(u, sv);
            }
            return true;
        }
        ay += 30;
        if (editable) {
            if (hit(mx, my, ax, ay, bw, 22)) {   // 断开/重新连接
                if (sel.state == BotSession.State.ONLINE
                        || sel.state == BotSession.State.CONNECTING)
                    sel.disconnect("user");
                else
                    com.mythos.nexus.session.BotConnection.connect(sel);
                return true;
            }
            ay += 30;
            if (hit(mx, my, ax, ay, bw, 22)) {   // 切换到视角
                if (SessionManager.get().mounted() != sel)
                    MythosNexusMod.switchTo(sel);
                return true;
            }
            ay += 30;
        }
        ay += 8 + 14;   // divider + "分组" label
        for (String g : NexusScreen.allGroups()) {
            if (hit(mx, my, ax, ay, bw, 20)) {
                if (editable) {
                    sel.group = g;
                    NexusConfig.setGroup(sessionKey(sel), g);
                    sel.logEvent("group -> " + g);
                }
                return true;
            }
            ay += 23;
        }
        ay += 4 + 24;   // new-group field row
        if (hit(mx, my, ax, ay, bw, 20)) {   // 新建并归入
            String g = s.groupNameField.getText().trim();
            if (!g.isEmpty() && editable) {
                sel.group = g;
                NexusConfig.setGroup(sessionKey(sel), g);
                sel.logEvent("group -> " + g);
                s.groupNameField.setText("");
            }
            return true;
        }
        ay += 26;
        if (editable) {
            ay += 8 + 14 + 26;   // divider + "备注" label + note field
            if (hit(mx, my, ax, ay, bw, 20)) {   // 自动重连设置 → RECONNECT panel
                s.showView(NexusScreen.View.RECONNECT);
                return true;
            }
            ay += 26;
            ay += 8 + 14 + 12 + 12 + 12 + 4;   // divider + "状态" + 3 stat lines + pad
        }
        // LIVE section consumes ay but has no extra click targets — fields
        // and drag bars were handled above.
        if (s.cardMode == NexusScreen.CardMode.LIVE && (editable || s.addMode))
            ay += 8 + 14 + 40 + (16 + 10) * 2;
        // 备份 — export / import
        ay += 8 + 14;
        int halfW = (bw - 8) / 2;
        if (hit(mx, my, ax, ay, halfW, 18)) { s.exportAccounts(false); return true; }
        if (hit(mx, my, ax + halfW + 8, ay, bw - halfW - 8, 18)) {
            s.importAccounts(); return true;
        }
        return false;
    }

    /** Wheel over the right panel in ACCOUNTS view scrolls the content. */
    @Override
    boolean scroll(int dWheel) {
        int maxR = Math.max(0, s.rightContentBottom - (s.height - 20));
        s.rightScroll = Math.max(0, Math.min(maxR,
                s.rightScroll - Integer.signum(dWheel) * 20));
        return true;
    }

    @Override
    void release() {
        if (liveDrag != 0) {
            liveDrag = 0;
            s.applyLiveSize();
        }
    }

    /** LIVE bar drag, or right-panel scrollbar drag (dragScroll == 3). */
    @Override
    boolean drag(int mx, int my) {
        if (liveDrag != 0) {
            setLiveFromBar(mx);
            return true;
        }
        if (s.dragScroll != 3 || s.rightThumbH <= 0 || s.rightTrackH <= s.rightThumbH)
            return false;
        int ty = Math.max(s.rightTrackY, Math.min(my - s.dragOffY,
                s.rightTrackY + s.rightTrackH - s.rightThumbH));
        int viewH = s.height - 20;
        int maxR = Math.max(0, s.rightContentBottom - viewH);
        s.rightScroll = Math.max(0, Math.min(maxR,
                (ty - s.rightTrackY) * maxR / Math.max(1, s.rightTrackH - s.rightThumbH)));
        return true;
    }
}
