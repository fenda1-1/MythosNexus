package com.mythos.nexus.ui;

import com.mythos.nexus.core.NexusConfig;
import com.mythos.nexus.session.BotSession;
import com.mythos.nexus.session.SessionManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.mythos.nexus.ui.NexusGeometry.hit;
import static com.mythos.nexus.ui.NexusGeometry.sessionKey;

/** Left rail: nav items, group tree with expand/collapse, inline rename, scrollbar. */
final class NexusRail extends NexusPanel {

    enum NavIcon { USER, FOLDER, PROXY, DOC }

    // Group-list geometry — shared by draw(), click() and the screen's
    // dragCardOnGroup()/wheel scroll. First row Y, row height, list bottom.
    static final int GROUP_Y0 = 34 + 30 + 10 + 10;   // first group row y
    static final int GROUP_ROW = 22;                  // row step
    static final int GROUP_BOTTOM = 80;               // height - this = list bottom

    /** One visible row in the group tree — flat path + depth + whether it has children. */
    static final class Row {
        final String path;      // full slash path e.g. "PVE/小怪"
        final int    depth;     // 0=root, 1=child, 2=grandchild…
        final boolean hasChildren;
        Row(String p, int d, boolean c) { path = p; depth = d; hasChildren = c; }
    }

    NexusRail(NexusScreen s) { super(s); }

    /** Flattened tree of groups — parents always appear, children only while expanded. */
    static List<Row> visibleRows(NexusScreen s) {
        // group paths → tree nodes in first-seen order
        Map<String, List<String>> children = new LinkedHashMap<>();
        List<String> roots = new ArrayList<>();
        for (String g : NexusScreen.allGroups()) {
            int slash = g.lastIndexOf('/');
            String parent = slash < 0 ? null : g.substring(0, slash);
            if (parent == null) {
                if (!roots.contains(g)) roots.add(g);
            } else {
                children.computeIfAbsent(parent, k -> new ArrayList<>()).add(g);
            }
        }
        List<Row> out = new ArrayList<>();
        for (String r : roots) emit(children, r, 0, s, out);
        return out;
    }

    private static void emit(Map<String, List<String>> children, String path,
                             int depth, NexusScreen s, List<Row> out) {
        List<String> kids = children.get(path);
        boolean hasKids = kids != null && !kids.isEmpty();
        out.add(new Row(path, depth, hasKids));
        if (hasKids && !s.collapsedGroups.contains(path)) {
            for (String k : kids) emit(children, k, depth + 1, s, out);
        }
    }

    void draw(int mx, int my) {
        Ui.fill(0, 0, s.railW, s.height, NexusTheme.BG_SIDEBAR);
        Ui.vDivider(s.railW, 0, s.height);

        int togX = s.railW - 22, togY = 8;
        boolean togHov = hit(mx, my, togX, togY, 16, 16);
        if (togHov) Ui.fill(togX, togY, 16, 16, NexusTheme.HOVER_WASH);
        NexusIcons.iconChevron(togX + 8, togY + 8, !s.railCollapsed,
                togHov ? NexusTheme.ACCENT_DEEP : NexusTheme.TEXT_SUB);

        int y = 34;
        drawNavItem(mx, my, y, "账号管理", NexusScreen.View.ACCOUNTS, NavIcon.USER);  y += 30;

        if (!s.railCollapsed) {
            y += 10;
            Ui.divider(NexusScreen.PAD, y, s.railW - NexusScreen.PAD * 2);
            y += 10;
            int gy = y;   // == GROUP_Y0
            List<Row> rows = visibleRows(s);
            int listBottom = s.height - GROUP_BOTTOM;
            for (int i = s.groupScroll; i < rows.size(); i++) {
                Row r = rows.get(i);
                if (s.renamingGroup != null && s.renamingGroup.equals(r.path)) {
                    s.groupEditField.x = NexusScreen.PAD + 2 + r.depth * 14;
                    s.groupEditField.y = gy + (GROUP_ROW - 8) / 2 - 2;
                    Ui.inputFrame(NexusScreen.PAD - 5 + r.depth * 14, gy + 1,
                            s.railW - NexusScreen.PAD * 2 + 10 - r.depth * 14, 18,
                            s.groupEditField.isFocused());
                    s.groupEditField.drawTextBox();
                } else {
                    drawGroupItem(mx, my, gy, r);
                }
                gy += GROUP_ROW;
                if (gy > listBottom) break;
            }
            // group scrollbar (right edge of rail)
            int grpH = rows.size() * GROUP_ROW;
            if (grpH > listBottom - y) {
                int trackY = y, trackH = listBottom - y;
                int thumbH = Math.max(20, trackH * trackH / grpH);
                int thumbY = trackY + (trackH - thumbH) * s.groupScroll
                        / Math.max(1, rows.size() - (trackH / GROUP_ROW));
                s.grpTrackY = trackY; s.grpTrackH = trackH;
                s.grpThumbY = thumbY; s.grpThumbH = thumbH;
                boolean thov = s.dragScroll == 1
                        || hit(mx, my, s.railW - NexusScreen.SCROLL_W - 2, thumbY - 2,
                               NexusScreen.SCROLL_W + 4, thumbH + 4);
                Ui.fill(s.railW - NexusScreen.SCROLL_W - 1, thumbY, NexusScreen.SCROLL_W, thumbH,
                        thov ? NexusTheme.ACCENT : NexusTheme.BORDER_SOFT);
            } else {
                s.grpThumbH = 0;
            }
            // "+ 新建分组" at bottom of group list
            int addY = y + Math.min(rows.size(), (listBottom - y) / GROUP_ROW) * GROUP_ROW + 4;
            boolean hov = hit(mx, my, NexusScreen.PAD - 6, addY, s.railW - NexusScreen.PAD * 2 + 12, 20);
            if (hov) Ui.roundedRect(NexusScreen.PAD - 5, addY, s.railW - NexusScreen.PAD * 2 + 10, 20,
                    3, NexusTheme.HOVER_WASH);
            NexusIcons.iconPlus(NexusScreen.PAD + 4, addY + 10,
                    hov ? NexusTheme.ACCENT : NexusTheme.TEXT_SUB);
            s.font().drawString("新建分组", NexusScreen.PAD + 16, addY + 6,
                    hov ? NexusTheme.ACCENT : NexusTheme.TEXT_SUB);
        }
    }

    /** One group row in the rail — click = filter, expand arrow if it has children. */
    void drawGroupItem(int mx, int my, int y, Row r) {
        int x = s.railCollapsed ? 0 : NexusScreen.PAD - 6;
        int w = s.railCollapsed ? s.railW : s.railW - NexusScreen.PAD * 2 + 12;
        int h = 18;
        boolean hov = hit(mx, my, x, y, w, h);
        boolean active = r.path.equals(s.groupFilter);
        if (hov || active)
            Ui.roundedRect(x + 1, y + 1, w - 2, h - 2, 3,
                    active ? NexusTheme.BG_CARD_HOV : NexusTheme.HOVER_WASH);

        int indent = s.railCollapsed ? 0 : r.depth * 14;
        int icx = s.railCollapsed ? s.railW / 2 : x + 14 + indent;
        int icy = y + h / 2;

        if (r.hasChildren && !s.railCollapsed) {
            boolean collapsed = s.collapsedGroups.contains(r.path);
            boolean arrowHov = hit(mx, my, icx - 11, icy - 6, 10, 12);
            // chevron: right = collapsed, down = expanded
            int ax = icx - 8, ay = icy;
            int col = arrowHov ? NexusTheme.ACCENT : NexusTheme.TEXT_FAINT;
            if (collapsed) {
                Ui.fill(ax,     ay - 3, 2, 2, col);
                Ui.fill(ax + 2, ay - 1, 2, 2, col);
                Ui.fill(ax,     ay + 1, 2, 2, col);
            } else {
                Ui.fill(ax,     ay - 1, 2, 2, col);
                Ui.fill(ax + 2, ay + 1, 2, 2, col);
                Ui.fill(ax + 4, ay - 1, 2, 2, col);
            }
        }

        NexusIcons.iconFolder(icx, icy, active ? NexusTheme.ACCENT
                : (hov ? NexusTheme.ACCENT : NexusTheme.TEXT_LABEL));

        if (!s.railCollapsed) {
            String name = r.path.substring(r.path.lastIndexOf('/') + 1);
            int maxW = s.railW - (x + 30 + indent) - 24;
            String clipped = s.font().trimStringToWidth(name, maxW);
            s.font().drawString(clipped, x + 30 + indent, y + (h - 8) / 2,
                    active ? NexusTheme.ACCENT : NexusTheme.TEXT_LABEL);
            // Online count badge — counts members in this subtree.
            int on = 0, tot = 0;
            for (BotSession ss : SessionManager.get().sessions()) {
                if (ss == SessionManager.get().humanSession()) continue;
                if (ss.group.equals(r.path) || ss.group.startsWith(r.path + "/")) {
                    tot++;
                    if (ss.state == BotSession.State.ONLINE) on++;
                }
            }
            if (tot > 0) {
                String badge = on + "/" + tot;
                s.font().drawString(badge,
                        x + w - 26 - s.font().getStringWidth(badge),
                        y + (h - 8) / 2, NexusTheme.TEXT_FAINT);
            }
            // "..." more-actions icon on the far right — opens group menu.
            boolean moreHov = hit(mx, my, x + w - 20, y, 18, h);
            if (moreHov) Ui.roundedRect(x + w - 20, y + 1, 18, h - 2, 3,
                    NexusTheme.HOVER_WASH);
            NexusIcons.iconMore(x + w - 11, y + h / 2,
                    moreHov ? NexusTheme.TEXT_TITLE : NexusTheme.TEXT_SUB);
        }
    }

    void drawNavItem(int mx, int my, int y, String label, NexusScreen.View v, NavIcon ic) {
        int x = s.railCollapsed ? 0 : NexusScreen.PAD - 6;
        int w = s.railCollapsed ? s.railW : s.railW - NexusScreen.PAD * 2 + 12;
        int h = 24;
        boolean hov = hit(mx, my, x, y, w, h);
        boolean active = (s.view == v);
        if (hov || active)
            Ui.roundedRect(x + 1, y + 1, w - 2, h - 2, 4,
                    active ? NexusTheme.BG_CARD_HOV : NexusTheme.HOVER_WASH);
        int icx = s.railCollapsed ? s.railW / 2 : x + 14;
        int icy = y + h / 2;
        int iconColor = active ? NexusTheme.ACCENT
                : (hov ? NexusTheme.ACCENT : NexusTheme.TEXT_LABEL);
        switch (ic) {
            case USER:   NexusIcons.iconUser(icx, icy, iconColor);   break;
            case FOLDER: NexusIcons.iconFolder(icx, icy, iconColor); break;
            case PROXY:  NexusIcons.iconProxy(icx, icy, iconColor);  break;
            case DOC:    NexusIcons.iconDoc(icx, icy, iconColor);    break;
        }
        if (!s.railCollapsed) {
            int maxW = s.railW - (x + 30) - 4;
            String clipped = s.font().trimStringToWidth(label, maxW);
            s.font().drawString(clipped, x + 30, y + (h - 8) / 2,
                    active ? NexusTheme.TEXT_TITLE
                           : (hov ? NexusTheme.TEXT_TITLE : NexusTheme.TEXT_LABEL));
        }
    }

    boolean navHit(int mx, int my, int y, NexusScreen.View v) {
        int x = s.railCollapsed ? 0 : NexusScreen.PAD - 6;
        int w = s.railCollapsed ? s.railW : s.railW - NexusScreen.PAD * 2 + 12;
        return hit(mx, my, x, y, w, 24);
    }


    /** Group row rect for row index i (index into visibleRows()). */
    private int groupRowY(int i) {
        return GROUP_Y0 + (i - s.groupScroll) * GROUP_ROW;
    }


    @Override
    boolean click(int mx, int my, int btn) {
        // inline group rename field — click inside keeps focus, click away commits
        if (s.renamingGroup != null) {
            List<Row> rows = visibleRows(s);
            int idx = -1, depth = 0;
            for (int i = 0; i < rows.size(); i++)
                if (rows.get(i).path.equals(s.renamingGroup)) { idx = i; depth = rows.get(i).depth; break; }
            if (idx >= s.groupScroll && idx >= 0) {
                int rowY = groupRowY(idx);
                if (hit(mx, my, NexusScreen.PAD - 5 + depth * 14, rowY + 1,
                        s.railW - NexusScreen.PAD * 2 + 10 - depth * 14, 18)) {
                    s.groupEditField.mouseClicked(mx, my, btn);
                    return true;
                }
            }
            s.commitGroupRename();
            s.relayout();
            return true;
        }

        // group "..." icon — opens the batch menu for that group.
        if (!s.groupMenuOpen && !s.railCollapsed) {
            List<Row> rows = visibleRows(s);
            int gy = GROUP_Y0;
            int listBottom = s.height - GROUP_BOTTOM;
            for (int i = s.groupScroll; i < rows.size(); i++) {
                if (gy > listBottom) break;
                int gx = NexusScreen.PAD - 6, gw = s.railW - NexusScreen.PAD * 2 + 12;
                if (hit(mx, my, gx + gw - 20, gy, 18, 18)) {
                    s.groupMenuOpen = true;
                    s.groupMenuTarget = rows.get(i).path;
                    s.groupMenuX = Math.min(gx + gw, s.width - 130);
                    s.groupMenuY = gy + 20;
                    return true;
                }
                gy += GROUP_ROW;
            }
        }

        int togX = s.railW - 22, togY = 8;
        if (hit(mx, my, togX, togY, 16, 16)) {
            s.railCollapsed = !s.railCollapsed;
            s.railW = s.railCollapsed
                    ? NexusScreen.RAIL_COLLAPSED_W : NexusScreen.RAIL_EXPANDED_W;
            s.fitColumns();
            s.relayout();
            return true;
        }

        if (s.grpThumbH > 0 && hit(mx, my, s.railW - NexusScreen.SCROLL_W - 3,
                s.grpThumbY - 2, NexusScreen.SCROLL_W + 6, s.grpThumbH + 4)) {
            s.dragScroll = 1;
            s.dragOffY = my - s.grpThumbY;
            return true;
        }

        // nav items — only ACCOUNTS remains (proxies/log removed from nav)
        int ny = 34;
        if (navHit(mx, my, ny, NexusScreen.View.ACCOUNTS)) {
            s.view = NexusScreen.View.ACCOUNTS;
            s.relayout();
            return true;
        }

        // group tree rows (always visible when expanded)
        if (!s.railCollapsed) {
            List<Row> rows = visibleRows(s);
            int listBottom = s.height - GROUP_BOTTOM;
            int gy = GROUP_Y0;
            for (int i = s.groupScroll; i < rows.size(); i++) {
                Row r = rows.get(i);
                boolean hitRow = hit(mx, my, NexusScreen.PAD - 6, gy,
                        s.railW - NexusScreen.PAD * 2 + 12, 18);
                if (hitRow) {
                    // expand arrow first — clicking it doesn't select the filter
                    if (r.hasChildren) {
                        int indent = r.depth * 14;
                        int ax = NexusScreen.PAD - 6 + 14 + indent - 11;
                        if (hit(mx, my, ax, gy + 4, 10, 10)) {
                            if (!s.collapsedGroups.remove(r.path))
                                s.collapsedGroups.add(r.path);
                            return true;
                        }
                    }
                    if (btn == 1) {   // right-click → group menu
                        s.groupMenuOpen = true;
                        s.groupMenuX = mx; s.groupMenuY = my;
                        s.groupMenuTarget = r.path;
                    } else {
                        s.groupFilter = r.path.equals(s.groupFilter) ? null : r.path;
                        s.selected.clear();
                        s.anchor = -1;
                        s.relayout();
                    }
                    return true;
                }
                gy += GROUP_ROW;
                if (gy > listBottom) break;
            }
            int addY = gy + 4;
            if (hit(mx, my, NexusScreen.PAD - 6, addY,
                    s.railW - NexusScreen.PAD * 2 + 12, 20)) {
                // auto-enter rename for the new group — user types name + Enter.
                // Persist as an "extra" group so it survives even while empty.
                String base = "组";
                int n = 1;
                while (NexusScreen.allGroups().contains(base + n)) n++;
                String g = base + n;
                NexusConfig.addGroup(g);
                // seed by pinning the first-selected account when there is one
                BotSession sel = s.firstSelected();
                if (sel != null && sel != SessionManager.get().humanSession()) {
                    sel.group = g;
                    NexusConfig.setGroup(sessionKey(sel), g);
                    sel.logEvent("group -> " + g);
                }
                s.groupFilter = g;
                s.renamingGroup = g;
                s.groupEditField.setText(g);
                s.groupEditField.setFocused(true);
                s.relayout();
                return true;
            }
        }
        return false;
    }

    /** Mouse wheel over the rail scrolls the group list. */
    @Override
    boolean scroll(int dWheel) {
        List<Row> rows = visibleRows(s);
        int listBottom = s.height - GROUP_BOTTOM;
        int maxG = Math.max(0, rows.size() - (listBottom - GROUP_Y0) / GROUP_ROW);
        s.groupScroll = Math.max(0, Math.min(maxG,
                s.groupScroll - Integer.signum(dWheel)));
        return true;
    }

    /** Group-list scrollbar drag (dragScroll == 1). */
    @Override
    boolean drag(int mx, int my) {
        if (s.dragScroll != 1 || s.grpThumbH <= 0 || s.grpTrackH <= s.grpThumbH)
            return false;
        int ty = Math.max(s.grpTrackY, Math.min(my - s.dragOffY,
                s.grpTrackY + s.grpTrackH - s.grpThumbH));
        List<Row> rows = visibleRows(s);
        int listBottom = s.height - GROUP_BOTTOM;
        int maxG = Math.max(0, rows.size() - (listBottom - GROUP_Y0) / GROUP_ROW);
        s.groupScroll = Math.max(0, Math.min(maxG,
                (ty - s.grpTrackY) * maxG / Math.max(1, s.grpTrackH - s.grpThumbH)));
        return true;
    }
}
