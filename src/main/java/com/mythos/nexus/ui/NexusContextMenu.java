package com.mythos.nexus.ui;

import com.mythos.nexus.core.NexusConfig;
import com.mythos.nexus.session.BotSession;
import com.mythos.nexus.session.SessionManager;

import java.util.List;

import static com.mythos.nexus.ui.NexusGeometry.hit;
import static com.mythos.nexus.ui.NexusGeometry.sessionKey;

/** Right-click menus (account card + group) and the unsaved-changes dialog. */
final class NexusContextMenu {

    private final NexusScreen s;

    NexusContextMenu(NexusScreen s) { this.s = s; }

    private static final class Item {
        final String id, label, hint;
        Item(String id, String label, String hint) {
            this.id = id; this.label = label; this.hint = hint;
        }
    }

    private List<Item> menuItems() {
        boolean isLive = s.cardMode == NexusScreen.CardMode.LIVE;
        BotSession sel = s.firstSelected();
        String ar = "自动重连";
        if (sel != null && sel != SessionManager.get().humanSession()) {
            boolean on = NexusConfig.bindingFor(sessionKey(sel)).autoReconnect;
            ar = "自动重连: " + (on ? "开" : "关");
        }
        List<Item> items = new java.util.ArrayList<>();
        items.add(new Item("rename", "重命名", ""));
        items.add(new Item("connect", "连接", ""));
        items.add(new Item("disconnect", "断开", ""));
        items.add(new Item("reconnect", ar, ""));
        items.add(new Item("copy", "复制", "Ctrl+C"));
        items.add(new Item("paste", "粘贴", "Ctrl+V"));
        items.add(new Item("delete", "删除", "Del"));
        if (isLive) items.add(new Item("fullscreen", "全屏", ""));
        return items;
    }

    void drawContextMenu(int mx, int my) {
        int w = 150;
        List<Item> items = menuItems();
        int h = items.size() * 20 + 8;
        Ui.card(s.menuX, s.menuY, w, h, NexusTheme.BG_SIDEBAR,
                NexusTheme.BORDER_SOFT, 4);
        int iy = s.menuY + 4;
        for (Item it : items) {
            boolean hov = hit(mx, my, s.menuX + 2, iy, w - 4, 18);
            if (hov) Ui.roundedRect(s.menuX + 2, iy, w - 4, 18, 3,
                    NexusTheme.HOVER_WASH);
            s.font().drawString(it.label, s.menuX + 10, iy + 5,
                    hov ? NexusTheme.TEXT_TITLE : NexusTheme.TEXT_LABEL);
            if (!it.hint.isEmpty())
                s.font().drawString(it.hint,
                        s.menuX + w - 10 - s.font().getStringWidth(it.hint),
                        iy + 5, NexusTheme.TEXT_FAINT);
            iy += 20;
        }
    }

    void handleMenuClick(int mx, int my) {
        int w = 150;
        List<Item> items = menuItems();
        int ih = 20;
        int iy = s.menuY + 4;
        for (Item it : items) {
            if (hit(mx, my, s.menuX + 2, iy, w - 4, ih)) {
                doMenuAction(it.id);
                s.menuOpen = false;
                return;
            }
            iy += ih;
        }
        s.menuOpen = false;   // clicked outside — close
    }

    void doMenuAction(String id) {
        List<BotSession> sel = s.selectedSessions();
        switch (id) {
            case "rename":  // 重命名 — focus name field
                s.fName.setFocused(true);
                break;
            case "connect":  // 连接 — through the join queue, not all at once
                s.runBatch("连接", sel, bs -> {
                    if (bs == SessionManager.get().humanSession()) return;
                    if (bs.state == BotSession.State.OFFLINE
                            || bs.state == BotSession.State.DISCONNECTED
                            || bs.state == BotSession.State.ERROR)
                        com.mythos.nexus.session.ReconnectManager.get()
                                .enqueue(bs, 0, "批量连接");
                });
                break;
            case "disconnect":  // 断开 — batch disconnect with progress
                s.runBatch("断开", sel, bs -> {
                    if (bs == SessionManager.get().humanSession()) return;
                    if (bs.state == BotSession.State.ONLINE
                            || bs.state == BotSession.State.CONNECTING)
                        bs.disconnect("batch");
                });
                break;
            case "reconnect": {  // 自动重连开/关 — per-card quick toggle
                boolean anyOff = false;
                for (BotSession bs : sel) {
                    if (bs == SessionManager.get().humanSession()) continue;
                    if (!NexusConfig.bindingFor(sessionKey(bs)).autoReconnect) anyOff = true;
                }
                for (BotSession bs : sel) {
                    if (bs == SessionManager.get().humanSession()) continue;
                    NexusConfig.setAutoReconnect(sessionKey(bs), anyOff);
                    bs.logEvent("自动重连 -> " + (anyOff ? "开" : "关"));
                }
                s.toast(anyOff ? "已开启选中账号的自动重连" : "已关闭选中账号的自动重连", 1500);
                break;
            }
            case "copy":  // 复制 — copy selected session(s) to new cards
                for (BotSession ss : sel) {
                    if (ss == SessionManager.get().humanSession()) continue;
                    BotSession copy = SessionManager.get()
                            .createSession(ss.username + "_copy", ss.serverHost, ss.serverPort);
                    copy.group = ss.group;
                    copy.proxyIndex = ss.proxyIndex;
                    s.dirty = true;
                }
                break;
            case "paste": {  // 粘贴 — duplicates first selected card
                BotSession src = s.firstSelected();
                if (src != null && src != SessionManager.get().humanSession()) {
                    BotSession copy = SessionManager.get()
                            .createSession(src.username + "_copy",
                                    src.serverHost, src.serverPort);
                    copy.group = src.group;
                    copy.proxyIndex = src.proxyIndex;
                    s.dirty = true;
                }
                break;
            }
            case "delete":  // runBatch skips userClosed sessions, so delete synchronously like Del
                s.deleteSelected();
                break;
            case "fullscreen":  // 全屏 — LIVE only: expand this card to fill the grid
                if (s.cardMode == NexusScreen.CardMode.LIVE)
                    s.liveFullscreen = s.menuIdx;
                break;
        }
    }


    // Removed: "颜色" (user said it did nothing useful — swatch cycled but
    // nobody used it), "设置 tick" (the TICKRATE side panel owns that now).
    private static final String[] GROUP_ITEMS = {
        "重命名", "删除", "新建子分类",
        "全部连接", "全部断开"
    };

    void drawGroupMenu(int mx, int my) {
        String[] items = GROUP_ITEMS;
        int w = 120, h = items.length * 20 + 8;
        if (s.groupMenuY + h > s.height - 8) s.groupMenuY = s.height - 8 - h;
        Ui.card(s.groupMenuX, s.groupMenuY, w, h, NexusTheme.BG_SIDEBAR,
                NexusTheme.BORDER_SOFT, 4);
        int iy = s.groupMenuY + 4;
        for (String it : items) {
            boolean hov = hit(mx, my, s.groupMenuX + 2, iy, w - 4, 18);
            if (hov) Ui.roundedRect(s.groupMenuX + 2, iy, w - 4, 18, 3,
                    NexusTheme.HOVER_WASH);
            s.font().drawString(it, s.groupMenuX + 10, iy + 5,
                    hov ? ("删除".equals(it) ? NexusTheme.ERR : NexusTheme.TEXT_TITLE)
                        : NexusTheme.TEXT_LABEL);
            iy += 20;
        }
    }

    void handleGroupMenuClick(int mx, int my) {
        String[] items = GROUP_ITEMS;
        int w = 120, ih = 20;
        int iy = s.groupMenuY + 4;
        for (int i = 0; i < items.length; i++) {
            if (hit(mx, my, s.groupMenuX + 2, iy, w - 4, ih)) {
                doGroupAction(i);
                s.groupMenuOpen = false;
                return;
            }
            iy += ih;
        }
        s.groupMenuOpen = false;
    }

    void doGroupAction(int idx) {
        String g = s.groupMenuTarget;
        if (g == null) return;
        switch (idx) {
            case 0:   // rename — inline field in the rail
                s.renamingGroup = g;
                s.groupEditField.setText(g);
                s.groupEditField.setFocused(true);
                break;
            case 1:   // delete — this group + descendants fall back to 默认
                for (BotSession ss : SessionManager.get().sessions()) {
                    if (g.equals(ss.group) || ss.group.startsWith(g + "/")) {
                        ss.group = "默认";
                        NexusConfig.setGroup(sessionKey(ss), "默认");
                        ss.logEvent("group 删除: " + g);
                    }
                }
                // drop the persistent placeholder too — user explicitly deleted it
                NexusConfig.removeGroup(g);
                if (g.equals(s.groupFilter)
                        || (s.groupFilter != null && s.groupFilter.startsWith(g + "/")))
                    s.groupFilter = null;
                s.collapsedGroups.remove(g);
                s.relayout();
                break;
            case 2:   // new subcategory — auto-enter rename so user names it
                String base = g + "/子";
                int n = 1;
                while (NexusScreen.allGroups().contains(base + n)) n++;
                String child = base + n;
                NexusConfig.addGroup(child);   // persist even while empty
                BotSession sel = s.firstSelected();
                if (sel != null && sel != SessionManager.get().humanSession()) {
                    sel.group = child;
                    NexusConfig.setGroup(sessionKey(sel), child);
                    sel.logEvent("group -> " + child);
                }
                s.groupFilter = child;
                s.renamingGroup = child;
                s.groupEditField.setText(child);
                s.groupEditField.setFocused(true);
                s.relayout();
                break;
            case 3:   // 全部连接 — batch connect every member of this group
                s.runBatch("连接", groupMembers(g), bs -> {
                    if (bs == SessionManager.get().humanSession()) return;
                    if (bs.state == BotSession.State.OFFLINE
                            || bs.state == BotSession.State.DISCONNECTED
                            || bs.state == BotSession.State.ERROR)
                        com.mythos.nexus.session.ReconnectManager.get()
                                .enqueue(bs, 0, "分组连接");
                });
                break;
            case 4:   // 全部断开 — batch disconnect every member
                s.runBatch("断开", groupMembers(g), bs -> {
                    if (bs == SessionManager.get().humanSession()) return;
                    if (bs.state == BotSession.State.ONLINE
                            || bs.state == BotSession.State.CONNECTING)
                        bs.disconnect("group batch");
                });
                break;
        }
    }

    private static List<BotSession> groupMembers(String g) {
        List<BotSession> out = new java.util.ArrayList<>();
        for (BotSession ss : SessionManager.get().sessions()) {
            if (ss != SessionManager.get().humanSession() && g.equals(ss.group))
                out.add(ss);
        }
        return out;
    }


    void drawUnsavedDialog(int mx, int my) {
        s.drawRect(0, 0, s.width, s.height, NexusTheme.BG_APP);
        int dw = 300, dh = 120;
        int dx = (s.width - dw) / 2, dy = (s.height - dh) / 2;
        Ui.card(dx, dy, dw, dh, NexusTheme.BG_SIDEBAR,
                NexusTheme.BORDER_SOFT, 6);
        s.font().drawString("有未保存的修改", dx + 20, dy + 16,
                NexusTheme.TEXT_TITLE);
        s.font().drawString("直接关闭将丢失修改。", dx + 20, dy + 34,
                NexusTheme.TEXT_SUB);
        s.drawButton(dx + 20, dy + dh - 36, 120, 22, "保存并关闭", false, mx, my);
        s.drawButton(dx + dw - 140, dy + dh - 36, 120, 22, "不保存", false, mx, my);
    }

    void handleUnsavedClick(int mx, int my) {
        int dw = 300, dh = 120;
        int dx = (s.width - dw) / 2, dy = (s.height - dh) / 2;
        if (hit(mx, my, dx + 20, dy + dh - 36, 120, 22)) {
            s.saveAll();
            s.showUnsavedDialog = false;
            s.mc().displayGuiScreen(null);
        } else if (hit(mx, my, dx + dw - 140, dy + dh - 36, 120, 22)) {
            s.showUnsavedDialog = false;
            s.mc().displayGuiScreen(null);
        }
    }
}
