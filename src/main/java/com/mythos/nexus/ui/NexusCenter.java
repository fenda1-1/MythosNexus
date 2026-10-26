package com.mythos.nexus.ui;

import com.mythos.nexus.MythosNexusMod;
import com.mythos.nexus.core.NexusConfig;
import com.mythos.nexus.session.BotSession;
import com.mythos.nexus.session.SessionManager;
import com.mythos.nexus.session.TickScheduler;
import org.lwjgl.input.Keyboard;

import java.util.List;

import static com.mythos.nexus.ui.NexusGeometry.hit;
import static com.mythos.nexus.ui.NexusGeometry.stateText;

/** Center column: title, search box, mode icons, card grid + scrollbar. */
final class NexusCenter extends NexusPanel {

    // Grid geometry shared by draw() and click()/scroll()/drag().
    static final int GRID_TOP = 34;
    static final int GRID_BOTTOM_PAD = 20;   // gridBottom = height - this
    // Mode-icon X offsets from the right edge of the center column (cx+midW).
    static final int ICON_ADD = -32, ICON_LIVE = -54, ICON_PAGE = -76,
            ICON_WF = -98, ICON_DENSITY = -118, ICON_SORT = -142;
    static final int ICON_Y = 8, ICON_SIZE = 18;

    NexusCenter(NexusScreen s) { super(s); }

    void draw(int mx, int my) {
        int cx = s.railW + NexusScreen.SPLIT_W;
        Ui.fill(cx, 0, s.midW, s.height, NexusTheme.BG_CARD);

        String title = s.groupFilter == null ? "账号列表"
                : "账号列表 — " + s.groupFilter;
        s.font().drawString(title, cx + NexusScreen.PAD, 12, NexusTheme.TEXT_LABEL);
        int n = s.filteredSessions().size();
        int titleEnd = cx + NexusScreen.PAD + s.font().getStringWidth(title) + 12;
        // search box — frame centered on the text (fields draw at their own x/y)
        boolean showSearch = s.midW >= 360;
        if (showSearch) {
            Ui.inputFrame(s.searchField.x - 4, s.searchField.y - 5, s.searchField.width + 8, 18,
                    s.searchField.isFocused());
            s.searchField.drawTextBox();
            if (s.searchField.getText().isEmpty() && !s.searchField.isFocused())
                s.font().drawString("搜索…", s.searchField.x, s.searchField.y,
                        NexusTheme.TEXT_FAINT);
        }
        // count — only when it fits before the search box / mode icons
        int countEnd = titleEnd + s.font().getStringWidth(n + " 个账号  " + s.selected.size() + " 选中");
        int iconsStart = cx + s.midW - 76;
        int limit = showSearch ? s.searchField.x - 8 : iconsStart - 8;
        if (countEnd <= limit)
            s.font().drawString(n + " 个账号  " + s.selected.size() + " 选中",
                    titleEnd, 12, NexusTheme.TEXT_FAINT);

        int gridTop = GRID_TOP;
        int gridBottom = s.height - GRID_BOTTOM_PAD;
        int availW = s.midW - NexusScreen.PAD * 2;
        boolean live = s.cardMode == NexusScreen.CardMode.LIVE;
        // LIVE mode renders 16:9 viewport cards in a grid; other modes use
        // the compact 200×70 text card.
        int lc = liveCols();
        int liveCardW = (availW - (lc - 1) * NexusScreen.CARD_GAP) / lc;
        int liveCardH = Math.max(80, liveCardW * 9 / 16);
        int cardHitH = s.cardHeight();   // density-dependent height for non-LIVE cards
        int cols = live ? lc
                : Math.max(1, availW / (NexusScreen.CARD_W + NexusScreen.CARD_GAP));
        int cardW = (availW - (cols - 1) * NexusScreen.CARD_GAP) / cols;
        int cardH = live ? liveCardH : cardHitH;
        int rows = Math.max(1, (gridBottom - gridTop + NexusScreen.CARD_GAP)
                / (cardH + NexusScreen.CARD_GAP));

        // view-mode icons (waterfall / page / live / add) + density + sort
        int wfX = cx + s.midW + ICON_WF, pgX = cx + s.midW + ICON_PAGE,
            lvX = cx + s.midW + ICON_LIVE, addX = cx + s.midW + ICON_ADD, icY = ICON_Y;
        // Density toggle — cycles NORMAL→COMPACT→DETAILED.
        // Self-drawn icon (3 stacked bars) so it doesn't overlap the search box.
        int denX = cx + s.midW + ICON_DENSITY, denY = icY;
        boolean denHov = hit(mx, my, denX, denY, 18, 18);
        if (denHov) Ui.roundedRect(denX, denY, 18, 18, 3, NexusTheme.HOVER_WASH);
        NexusIcons.iconDensity(denX + 9, denY + 9,
                denHov ? NexusTheme.ACCENT : NexusTheme.TEXT_SUB,
                NexusConfig.cardDensity());
        // Sort dropdown — cycles DEFAULT→STATE→NAME→UPTIME
        int sortX = denX - 24, sortY = icY;
        boolean sortHov = hit(mx, my, sortX, sortY, 18, 18);
        if (sortHov) Ui.roundedRect(sortX, sortY, 18, 18, 3, NexusTheme.HOVER_WASH);
        String sortLbl = "NAME".equals(NexusConfig.cardSort()) ? "A"
                : "STATE".equals(NexusConfig.cardSort()) ? "S"
                : "UPTIME".equals(NexusConfig.cardSort()) ? "U" : "·";
        s.font().drawString(sortLbl, sortX + 5, sortY + 5,
                sortHov ? NexusTheme.ACCENT : NexusTheme.TEXT_SUB);
        boolean wfHov = hit(mx, my, wfX, icY, 18, 18);
        boolean pgHov = hit(mx, my, pgX, icY, 18, 18);
        boolean lvHov = hit(mx, my, lvX, icY, 18, 18);
        boolean addHov = hit(mx, my, addX, icY, 18, 18);
        if (s.cardMode == NexusScreen.CardMode.WATERFALL)
            Ui.roundedRect(wfX, icY, 18, 18, 3, NexusTheme.BG_CARD_HOV);
        else if (wfHov)
            Ui.roundedRect(wfX, icY, 18, 18, 3, NexusTheme.HOVER_WASH);
        if (s.cardMode == NexusScreen.CardMode.PAGE)
            Ui.roundedRect(pgX, icY, 18, 18, 3, NexusTheme.BG_CARD_HOV);
        else if (pgHov)
            Ui.roundedRect(pgX, icY, 18, 18, 3, NexusTheme.HOVER_WASH);
        if (live)
            Ui.roundedRect(lvX, icY, 18, 18, 3, NexusTheme.BG_CARD_HOV);
        else if (lvHov)
            Ui.roundedRect(lvX, icY, 18, 18, 3, NexusTheme.HOVER_WASH);
        if (s.addMode)
            Ui.roundedRect(addX, icY, 18, 18, 3, NexusTheme.BG_CARD_HOV);
        else if (addHov)
            Ui.roundedRect(addX, icY, 18, 18, 3, NexusTheme.HOVER_WASH);
        NexusIcons.iconWaterfall(wfX + 9, icY + 9,
                s.cardMode == NexusScreen.CardMode.WATERFALL ? NexusTheme.ACCENT : NexusTheme.TEXT_SUB);
        NexusIcons.iconPage(pgX + 9, icY + 9,
                s.cardMode == NexusScreen.CardMode.PAGE ? NexusTheme.ACCENT : NexusTheme.TEXT_SUB);
        NexusIcons.iconLive(lvX + 9, icY + 9,
                live ? NexusTheme.ACCENT : NexusTheme.TEXT_SUB);
        NexusIcons.iconPlus(addX + 9, icY + 9,
                s.addMode ? NexusTheme.ACCENT : NexusTheme.TEXT_SUB);

        List<BotSession> list = s.filteredSessions();
        int total = list.size();

        // Only run the offscreen pipeline while LIVE mode is actually up —
        // renderAll() is a no-op for mounted/offline sessions. Dispose when
        // the user switches away so GL resources don't leak.
        if (live && !NexusConfig.liveFrozen()) {
            // Only render previews for sessions actually visible this frame —
            // renderAll on the full list would burn GPU on off-screen bots.
            int visCount = s.liveFullscreen >= 0 ? 1 : cols * rows;
            int visStart = s.liveFullscreen >= 0 ? s.liveFullscreen : s.listScroll;
            // listScroll can outlive a shrinking list (bot disconnected while
            // scrolled) — clamp before subList or it crashes the frame.
            if (visStart >= list.size()) visStart = Math.max(0, list.size() - 1);
            int visEnd = Math.min(list.size(), visStart + visCount);
            java.util.List<BotSession> visible = list.subList(visStart, visEnd);
            com.mythos.nexus.session.LiveViewportRenderer.renderAll(visible);
            // fullscreenSession pin is owned by renderAllTick — it pins
            // sessions.get(0) whenever pendingSessions.size()==1, covering
            // both context-menu fullscreen AND a 1xN grid.
            s.liveWasActive = true;
        } else if (s.liveWasActive && !live) {
            com.mythos.nexus.session.LiveViewportRenderer.disposeAll();
            com.mythos.nexus.session.LiveViewportRenderer.fullscreenSession = null;
            s.liveWasActive = false;
        }

        if (s.cardMode == NexusScreen.CardMode.PAGE) {
            // PAGE mode: same multi-column grid as waterfall, but paginated
            // instead of scrolled. cols = availW / (CARD_W + GAP).
            int pages = Math.max(1, (total + cols * rows - 1) / (cols * rows));
            s.pageIndex = Math.max(0, Math.min(s.pageIndex, pages - 1));
            s.listScroll = 0;
            s.gridThumbH = 0;
            int start = s.pageIndex * cols * rows;
            int drawn = 0;
            for (int i = start; i < total && drawn < cols * rows; i++, drawn++) {
                BotSession bs = list.get(i);
                int col = drawn % cols, row = drawn / cols;
                int x = cx + NexusScreen.PAD + col * (cardW + NexusScreen.CARD_GAP);
                int y = gridTop + row * (cardHitH + NexusScreen.CARD_GAP);
                drawCard(x, y, cardW, cardHitH, bs, i, mx, my);
            }
            // Page indicator — bottom of center, with prev/next click zones
            String pg = "第 " + (s.pageIndex + 1) + "/" + pages + " 页";
            int pgY = gridBottom - 14;
            s.font().drawString(pg, cx + NexusScreen.PAD, pgY,
                    NexusTheme.TEXT_FAINT);
            int arrowY = pgY - 2;
            boolean prevHov = hit(mx, my, cx + NexusScreen.PAD + s.font().getStringWidth(pg) + 8, arrowY, 14, 14);
            boolean nextHov = hit(mx, my, cx + NexusScreen.PAD + s.font().getStringWidth(pg) + 26, arrowY, 14, 14);
            if (prevHov) Ui.roundedRect(cx + NexusScreen.PAD + s.font().getStringWidth(pg) + 8, arrowY, 14, 14, 2, NexusTheme.HOVER_WASH);
            if (nextHov) Ui.roundedRect(cx + NexusScreen.PAD + s.font().getStringWidth(pg) + 26, arrowY, 14, 14, 2, NexusTheme.HOVER_WASH);
            s.font().drawString("<", cx + NexusScreen.PAD + s.font().getStringWidth(pg) + 12, pgY,
                    s.pageIndex > 0 ? NexusTheme.TEXT_SUB : NexusTheme.TEXT_FAINT);
            s.font().drawString(">", cx + NexusScreen.PAD + s.font().getStringWidth(pg) + 30, pgY,
                    s.pageIndex < pages - 1 ? NexusTheme.TEXT_SUB : NexusTheme.TEXT_FAINT);
        } else if (live) {
            // LIVE fullscreen — one card fills the whole grid area
            if (s.liveFullscreen >= 0 && s.liveFullscreen < list.size()) {
                drawLiveCard(cx + NexusScreen.PAD, gridTop,
                        availW, gridBottom - gridTop,
                        list.get(s.liveFullscreen), s.liveFullscreen, mx, my);
                // Close button on the fullscreen card
                int cX = cx + availW - 20, cY = gridTop + 6;
                boolean cHov = hit(mx, my, cX, cY, 14, 14);
                if (cHov) Ui.roundedRect(cX, cY, 14, 14, 2, NexusTheme.HOVER_WASH);
                NexusIcons.drawCloseX(cX + 7, cY + 7, 3,
                        cHov ? NexusTheme.ERR : NexusTheme.TEXT_SUB);
                // Esc or click outside returns to grid — handled in mouseClicked
            } else {
            // LIVE: cols×rows grid of viewport cards, grid scrollbar.
            int visible = cols * rows;
            int maxScroll = Math.max(0, total - visible);
            s.listScroll = Math.max(0, Math.min(s.listScroll, maxScroll));
            int drawn = 0;
            for (int i = s.listScroll; i < list.size() && drawn < visible; i++, drawn++) {
                BotSession bs = list.get(i);
                int col = drawn % cols, row = drawn / cols;
                int x = cx + NexusScreen.PAD + col * (cardW + NexusScreen.CARD_GAP);
                int y = gridTop + row * (cardH + NexusScreen.CARD_GAP);
                drawLiveCard(x, y, cardW, cardH, bs, i, mx, my);
            }
            if (total > visible) {
                int trackX = cx + s.midW - NexusScreen.SCROLL_W - 2;
                int trackH = gridBottom - gridTop;
                int thumbH = Math.max(24, trackH * visible / total);
                int thumbY = gridTop + (trackH - thumbH) * s.listScroll / Math.max(1, maxScroll);
                s.gridTrackY = gridTop; s.gridTrackH = trackH;
                s.gridThumbY = thumbY; s.gridThumbH = thumbH;
                boolean thov = s.dragScroll == 2
                        || hit(mx, my, trackX - 3, thumbY - 2, NexusScreen.SCROLL_W + 6, thumbH + 4);
                Ui.fill(trackX, thumbY, NexusScreen.SCROLL_W, thumbH,
                        thov ? NexusTheme.ACCENT : NexusTheme.BORDER_SOFT);
            } else {
                s.gridThumbH = 0;
            }
            // Column-count controls: [-] N [+] at bottom of grid area,
            // aligned with the page indicator row
            int ctrlY = gridBottom - 14;
            int minusX = cx + s.midW - 100, plusX = cx + s.midW - 60;
            String cLabel = "列:" + cols;
            int clX = minusX + 20;
            boolean minusHov = hit(mx, my, minusX, ctrlY - 3, 14, 14);
            boolean plusHov = hit(mx, my, plusX, ctrlY - 3, 14, 14);
            Ui.roundedRect(minusX, ctrlY - 3, 14, 14, 2,
                    minusHov ? NexusTheme.HOVER_WASH : NexusTheme.BG_CARD);
            Ui.roundedRect(plusX, ctrlY - 3, 14, 14, 2,
                    plusHov ? NexusTheme.HOVER_WASH : NexusTheme.BG_CARD);
            NexusIcons.iconMinus(minusX + 7, ctrlY + 4,
                    cols > 1 ? (minusHov ? NexusTheme.ACCENT : NexusTheme.TEXT_LABEL) : NexusTheme.TEXT_FAINT);
            s.font().drawString(cLabel, clX, ctrlY, NexusTheme.TEXT_FAINT);
            NexusIcons.iconPlus(plusX + 7, ctrlY + 4,
                    cols < 4 ? (plusHov ? NexusTheme.ACCENT : NexusTheme.TEXT_LABEL) : NexusTheme.TEXT_FAINT);
            // Freeze toggle — pauses all preview rendering to save GPU
            int fzX = plusX + 24;
            boolean fzHov = hit(mx, my, fzX, ctrlY - 3, 14, 14);
            Ui.roundedRect(fzX, ctrlY - 3, 14, 14, 2,
                    fzHov ? NexusTheme.HOVER_WASH : NexusTheme.BG_CARD);
            boolean frozen = NexusConfig.liveFrozen();
            if (frozen)
                NexusIcons.iconPlay(fzX + 7, ctrlY + 4, NexusTheme.WARN);
            else
                NexusIcons.iconPause(fzX + 7, ctrlY + 4, NexusTheme.TEXT_LABEL);
            if (fzHov) s.hoverText = frozen
                    ? "恢复 LIVE 渲染\n所有预览已暂停，点击恢复"
                    : "暂停 LIVE 渲染\n冻结所有预览省 GPU/CPU";
            }   // end LIVE grid else-branch
        } else {
            int visible = cols * rows;
            int maxScroll = Math.max(0, total - visible);
            s.listScroll = Math.max(0, Math.min(s.listScroll, maxScroll));
            int drawn = 0;
            for (int i = s.listScroll; i < list.size() && drawn < visible; i++, drawn++) {
                BotSession bs = list.get(i);
                int col = drawn % cols, row = drawn / cols;
                int x = cx + NexusScreen.PAD + col * (cardW + NexusScreen.CARD_GAP);
                int y = gridTop + row * (cardHitH + NexusScreen.CARD_GAP);
                drawCard(x, y, cardW, cardHitH, bs, i, mx, my);
            }
            // grid scrollbar on right edge of center column
            if (total > visible) {
                int trackX = cx + s.midW - NexusScreen.SCROLL_W - 2;
                int trackH = gridBottom - gridTop;
                int thumbH = Math.max(24, trackH * visible / total);
                int thumbY = gridTop + (trackH - thumbH) * s.listScroll / Math.max(1, maxScroll);
                s.gridTrackY = gridTop; s.gridTrackH = trackH;
                s.gridThumbY = thumbY; s.gridThumbH = thumbH;
                boolean thov = s.dragScroll == 2
                        || hit(mx, my, trackX - 3, thumbY - 2, NexusScreen.SCROLL_W + 6, thumbH + 4);
                Ui.fill(trackX, thumbY, NexusScreen.SCROLL_W, thumbH,
                        thov ? NexusTheme.ACCENT : NexusTheme.BORDER_SOFT);
            } else {
                s.gridThumbH = 0;
            }
        }

        // hover tooltips for the mode icons (drawn last, on top)
        if (denHov) s.drawTooltip("密度: " + NexusConfig.cardDensity(), denX - 10, denY + 22);
        if (sortHov) s.drawTooltip("排序: " + NexusConfig.cardSort(), sortX - 10, sortY + 22);
        if (wfHov) s.drawTooltip("瀑布流", wfX - 14, icY + 22);
        if (pgHov) s.drawTooltip("分页", pgX - 6, icY + 22);
        if (lvHov) s.drawTooltip("实时渲染", lvX - 14, icY + 22);
        if (addHov) s.drawTooltip("新建账号", addX - 18, icY + 22);
    }

    void drawCard(int x, int y, int w, int h, BotSession bs,
                  int idx, int mx, int my) {
        boolean sel = s.selected.contains(idx);
        boolean hov = hit(mx, my, x, y, w, h);
        int fill = sel ? NexusTheme.BG_CARD_HOV : NexusTheme.BG_SIDEBAR;
        int border = sel ? NexusTheme.ACCENT : NexusTheme.BORDER_SOFT;
        Ui.card(x, y, w, h, fill, border, NexusTheme.RADIUS_CARD - 2);
        if (hov && !sel)
            Ui.roundedRect(x + 1, y + 1, w - 2, h - 2, NexusTheme.RADIUS_CARD - 3,
                    NexusTheme.HOVER_WASH);

        // Density: NORMAL = full info, COMPACT = name+dot only,
        // DETAILED = full + ping/reconnect/proxy.
        boolean compact = "COMPACT".equals(NexusConfig.cardDensity());
        boolean detailed = "DETAILED".equals(NexusConfig.cardDensity());

        int dot;
        switch (bs.state) {
            case ONLINE:      dot = NexusTheme.OK;   break;
            case CONNECTING:  dot = NexusTheme.WARN; break;
            case ERROR:       dot = NexusTheme.ERR;  break;
            default:          dot = NexusTheme.IDLE;
        }

        if (compact) {
            // Compact: dot + name, one line
            Ui.dot(x + 12, y + h / 2, dot);
            s.font().drawString(bs.username, x + 22, y + h / 2 - 4,
                    NexusTheme.TEXT_TITLE);
            if (SessionManager.get().mounted() == bs)
                s.font().drawString("●", x + w - 16, y + h / 2 - 4, NexusTheme.ACCENT);
            return;
        }

        // Group color dot — left edge of card
        Integer gc = NexusConfig.groupColor(bs.group);
        if (gc != null) {
            Ui.dot(x + 4, y + 14, 0xFF000000 | gc);
        }

        Ui.dot(x + 12, y + 14, dot);
        s.font().drawString(bs.username, x + 22, y + 8, NexusTheme.TEXT_TITLE);

        if (!detailed) {
            s.font().drawString(bs.serverHost + ":" + bs.serverPort,
                    x + 22, y + 21, NexusTheme.TEXT_SUB);
            String sub = bs.group + "  ·  " + stateText(bs);
            s.font().drawString(sub, x + 22, y + 33, NexusTheme.TEXT_FAINT);
        } else {
            // Detailed: server + group + ping + proxy + reconnect countdown
            s.font().drawString(bs.serverHost + ":" + bs.serverPort,
                    x + 22, y + 21, NexusTheme.TEXT_SUB);
            String sub = bs.group + "  ·  " + stateText(bs);
            s.font().drawString(sub, x + 22, y + 33, NexusTheme.TEXT_FAINT);
            // Ping (ms since last packet, when online)
            if (bs.state == BotSession.State.ONLINE && bs.lastPacketNanos > 0) {
                long pingMs = (System.nanoTime() - bs.lastPacketNanos) / 1_000_000L;
                String ping = pingMs < 1000 ? pingMs + "ms" : (pingMs / 1000) + "s";
                s.font().drawString(ping, x + w - 14 - s.font().getStringWidth(ping),
                        y + 8, NexusTheme.TEXT_FAINT);
            }
            // Proxy icon — small dot = has proxy, number = index
            if (bs.proxyIndex >= 0) {
                NexusIcons.iconProxy(x + w - 18, y + 20, NexusTheme.ACCENT);
            }
            if (bs.reconnectAtNanos > 0) {
                long remMs = (bs.reconnectAtNanos - System.nanoTime()) / 1_000_000L;
                if (remMs > 0) {
                    String rc = "重连 " + (remMs / 1000) + "s";
                    s.font().drawString(rc, x + 22, y + 45, NexusTheme.WARN);
                }
            }
        }

        // Live status line: HP/hunger/pos when online, B/s + last-packet age.
        if (bs.state == BotSession.State.ONLINE && !detailed) {
            String hp = "";
            String pos = "";
            String net = "";
            if (bs.player != null) {
                hp = String.format("HP %.0f  饥 %d", bs.player.getHealth(),
                        bs.player.getFoodStats().getFoodLevel());
                pos = String.format("%.0f,%.0f,%.0f",
                        bs.player.posX, bs.player.posY, bs.player.posZ);
            }
            long rxB = bs.bytesReceived, txB = bs.bytesSent;
            net = "↓" + fmtBytes(rxB) + " ↑" + fmtBytes(txB);
            long ageNs = bs.lastPacketNanos;
            if (ageNs > 0) {
                long ageMs = (System.nanoTime() - ageNs) / 1_000_000L;
                net += "  " + ageMs + "ms";
            }
            String line = (hp + "  " + pos).trim();
            if (!line.isEmpty())
                s.font().drawString(line, x + 22, y + 45, NexusTheme.TEXT_SUB);
            if (!net.isEmpty())
                s.font().drawString(net, x + 22, y + 56, NexusTheme.TEXT_FAINT);
        }
        if (SessionManager.get().mounted() == bs)
            s.font().drawString("●", x + w - 16, y + 8, NexusTheme.ACCENT);

        // Priority-band chip — bottom-right corner. The band's color cycles
        // the group-color palette so every group of BAND_SIZE sessions that
        // were added together shares one tint. Hover shows "优先级 N".
        int band = TickScheduler.bandOf(idx);
        int chipCol = bandColor(band);
        // Priority chip — outlined so it stands out against the card
        Ui.fill(x + w - 13, y + h - 13, 10, 10, NexusTheme.TEXT_FAINT);
        Ui.fill(x + w - 12, y + h - 12, 8, 8, chipCol);
        if (hit(mx, my, x + w - 14, y + h - 14, 12, 12)) {
            s.hoverText = "第" + (band + 1) + "优先级";
            s.hoverX = mx + 8;
            s.hoverY = my - 14;
        }

        // Live tick-rate — left of the priority chip. Shows what the
        // scheduler is actually giving this bot right now (pathing %).
        int pct = TickScheduler.pctFor(idx,
                com.mythos.nexus.session.SessionTicker.hasActivePath(bs),
                NexusConfig.tickRatePathingFor(
                        com.mythos.nexus.ui.NexusGeometry.sessionKey(bs)));
        String pctStr = pct + "%";
        int pctW = s.font().getStringWidth(pctStr);
        int pctX = x + w - 16 - pctW;
        s.font().drawString(pctStr, pctX, y + h - 11, NexusTheme.TEXT_SUB);
        if (hit(mx, my, pctX - 2, y + h - 13, pctW + 4, 12)) {
            s.hoverText = "实时 tick 频率 " + pct + "%";
            s.hoverX = mx + 8;
            s.hoverY = my - 14;
        }
    }

    /** Deterministic band color — same palette family as group colors. */
    private static int bandColor(int band) {
        int[] palette = {
            0xFFE57373, 0xFF81C784, 0xFF64B5F6, 0xFFFFB74D,
            0xFFBA68C8, 0xFF4DB6AC, 0xFFFFF176, 0xFFA1887F,
        };
        return palette[band % palette.length];
    }

    /**
     * LIVE mode card: the session's offscreen framebuffer blitted like a
     * screenshot, plus a compact status strip. The offscreen pass already ran
     * earlier in draw() — this only reads the FBO texture back.
     */
    void drawLiveCard(int x, int y, int w, int h, BotSession bs,
                      int idx, int mx, int my) {
        boolean sel = s.selected.contains(idx);
        boolean hov = hit(mx, my, x, y, w, h);
        int border = sel ? NexusTheme.ACCENT : NexusTheme.BORDER_SOFT;
        Ui.card(x, y, w, h, NexusTheme.BG_SIDEBAR, border, NexusTheme.RADIUS_CARD - 2);
        if (hov && !sel)
            Ui.roundedRect(x + 1, y + 1, w - 2, h - 2, NexusTheme.RADIUS_CARD - 3,
                    NexusTheme.HOVER_WASH);

        int dot;
        switch (bs.state) {
            case ONLINE:      dot = NexusTheme.OK;   break;
            case CONNECTING:  dot = NexusTheme.WARN; break;
            case ERROR:       dot = NexusTheme.ERR;  break;
            default:          dot = NexusTheme.IDLE;
        }
        Ui.dot(x + 10, y + 12, dot);
        s.font().drawString(bs.username, x + 20, y + 7, NexusTheme.TEXT_TITLE);
        s.font().drawString(bs.serverHost + ":" + bs.serverPort,
                x + 20, y + 19, NexusTheme.TEXT_SUB);

        int viewX = x + 10, viewY = y + 30;
        int viewW = w - 20, viewH = h - 44;
        net.minecraft.client.shader.Framebuffer fb =
                com.mythos.nexus.session.LiveViewportRenderer.framebufferFor(bs);
        if (fb != null && SessionManager.get().mounted() != bs) {
            // Blit the session's framebuffer texture — MC FBOs store the image
            // upside-down, so we tessellate with V flipped.
            net.minecraft.client.renderer.Tessellator tess =
                    net.minecraft.client.renderer.Tessellator.getInstance();
            net.minecraft.client.renderer.BufferBuilder buf = tess.getBuffer();
            net.minecraft.client.renderer.GlStateManager.enableTexture2D();
            net.minecraft.client.renderer.GlStateManager.color(1f, 1f, 1f, 1f);
            org.lwjgl.opengl.GL11.glBindTexture(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D, fb.framebufferTexture);
            buf.begin(7, net.minecraft.client.renderer.vertex.DefaultVertexFormats
                    .POSITION_TEX);
            buf.pos(viewX,         viewY + viewH, 0.0D).tex(0.0D, 0.0D).endVertex();
            buf.pos(viewX + viewW, viewY + viewH, 0.0D).tex(1.0D, 0.0D).endVertex();
            buf.pos(viewX + viewW, viewY,         0.0D).tex(1.0D, 1.0D).endVertex();
            buf.pos(viewX,         viewY,         0.0D).tex(0.0D, 1.0D).endVertex();
            tess.draw();
            // Unbind the FBO texture via GlStateManager so its cached
            // textureName updates — raw GL11.glBindTexture leaves
            // enableTexture2D() re-binding the FBO on the next call,
            // which shows up as black bars instead of text.
            net.minecraft.client.renderer.GlStateManager.bindTexture(0);
        } else {
            Ui.fill(viewX, viewY, viewW, viewH, NexusTheme.BG_CARD);
            String msg = bs.state == BotSession.State.ONLINE
                    ? (SessionManager.get().mounted() == bs ? "前台" : "…")
                    : "离线";
            int msgW = s.font().getStringWidth(msg);
            s.font().drawString(msg, viewX + (viewW - msgW) / 2,
                    viewY + viewH / 2 - 4, NexusTheme.TEXT_FAINT);
        }

        if (SessionManager.get().mounted() == bs)
            s.font().drawString("●", x + w - 14, y + 7, NexusTheme.ACCENT);
    }

    private static String fmtBytes(long b) {
        if (b < 1024) return b + "B";
        if (b < 1024 * 1024) return (b / 1024) + "K";
        return String.format("%.1fM", b / 1048576.0);
    }

    //      mouseClickMove — geometry now shared with draw() above) ----

    private int liveCols() { return Math.max(1, Math.min(4, s.liveCols)); }

    private int gridBottom() { return s.height - GRID_BOTTOM_PAD; }
    private int availW()   { return s.midW - NexusScreen.PAD * 2; }

    /** Rows that fit in the grid for a given card height. */
    private int gridRows(int cardH) {
        return Math.max(1, (gridBottom() - GRID_TOP + NexusScreen.CARD_GAP)
                / (cardH + NexusScreen.CARD_GAP));
    }

    /** Non-LIVE column count / card height helpers shared by click+scroll. */
    private int cols() {
        return s.cardMode == NexusScreen.CardMode.LIVE ? liveCols()
                : Math.max(1, availW() / (NexusScreen.CARD_W + NexusScreen.CARD_GAP));
    }
    private int cardH() {
        if (s.cardMode == NexusScreen.CardMode.LIVE) {
            int lc = liveCols();
            int cw = (availW() - (lc - 1) * NexusScreen.CARD_GAP) / lc;
            return Math.max(80, cw * 9 / 16);
        }
        return s.cardHeight();
    }

    @Override
    boolean click(int mx, int my, int btn) {
        int cx = s.railW + NexusScreen.SPLIT_W;

        // view-mode icons — usable from every right-panel view (the grid is
        // the primary surface regardless of which panel is open)
        if (true) {
            if (hit(mx, my, cx + s.midW + ICON_DENSITY, ICON_Y, ICON_SIZE, ICON_SIZE)) {
                String cur = NexusConfig.cardDensity();
                NexusConfig.setCardDensity("NORMAL".equals(cur) ? "COMPACT"
                        : "COMPACT".equals(cur) ? "DETAILED" : "NORMAL");
                s.listScroll = 0; s.pageIndex = 0;
                return true;
            }
            if (hit(mx, my, cx + s.midW + ICON_SORT, ICON_Y, ICON_SIZE, ICON_SIZE)) {
                String cur = NexusConfig.cardSort();
                NexusConfig.setCardSort("DEFAULT".equals(cur) ? "STATE"
                        : "STATE".equals(cur) ? "NAME"
                        : "NAME".equals(cur) ? "UPTIME" : "DEFAULT");
                s.selected.clear(); s.anchor = -1;
                return true;
            }
            if (hit(mx, my, cx + s.midW + ICON_WF, ICON_Y, ICON_SIZE, ICON_SIZE)) {
                s.cardMode = NexusScreen.CardMode.WATERFALL; s.listScroll = 0;
                NexusConfig.setCardMode("WATERFALL"); return true;
            }
            if (hit(mx, my, cx + s.midW + ICON_PAGE, ICON_Y, ICON_SIZE, ICON_SIZE)) {
                s.cardMode = NexusScreen.CardMode.PAGE; s.pageIndex = 0;
                NexusConfig.setCardMode("PAGE"); return true;
            }
            if (hit(mx, my, cx + s.midW + ICON_LIVE, ICON_Y, ICON_SIZE, ICON_SIZE)) {
                s.cardMode = NexusScreen.CardMode.LIVE;
                s.pageIndex = 0; s.listScroll = 0;
                NexusConfig.setCardMode("LIVE"); return true;
            }
            if (hit(mx, my, cx + s.midW + ICON_ADD, ICON_Y, ICON_SIZE, ICON_SIZE)) {
                s.addMode = !s.addMode;
                s.selected.clear();
                s.relayout();
                return true;
            }
        }

        if (s.gridThumbH > 0 && hit(mx, my, cx + s.midW - NexusScreen.SCROLL_W - 5,
                s.gridThumbY - 2, NexusScreen.SCROLL_W + 6, s.gridThumbH + 4)) {
            s.dragScroll = 2; s.dragOffY = my - s.gridThumbY; return true;
        }

        // Card clicks work in EVERY right-panel view — the account grid is the
        // primary navigation surface; hiding it just because STATUS/LOG/THEME
        // is open made the account list dead until the user went back to edit.
        // Only non-card chrome below (PAGE arrows / LIVE controls) is gated.

        // PAGE prev/next arrows — bottom of grid
        if (s.cardMode == NexusScreen.CardMode.PAGE) {
            int pgY = gridBottom() - 14;
            String pg = "第 " + (s.pageIndex + 1) + "/" + "? 页";
            int pgEnd = cx + NexusScreen.PAD + s.font().getStringWidth(pg);
            if (hit(mx, my, pgEnd + 8, pgY - 2, 14, 14)) {
                s.pageIndex = Math.max(0, s.pageIndex - 1); return true;
            }
            if (hit(mx, my, pgEnd + 26, pgY - 2, 14, 14)) {
                s.pageIndex++; return true;
            }
        }
        // LIVE column controls + freeze toggle
        if (s.cardMode == NexusScreen.CardMode.LIVE) {
            int ctrlY = gridBottom() - 14;
            int minusX = cx + s.midW - 100, plusX = cx + s.midW - 60;
            int fzX = plusX + 24;
            if (hit(mx, my, fzX, ctrlY - 3, 14, 14)) {
                NexusConfig.setLiveFrozen(!NexusConfig.liveFrozen()); return true;
            }
            if (hit(mx, my, minusX, ctrlY - 3, 14, 14)) {
                s.liveCols = Math.max(1, s.liveCols - 1); s.listScroll = 0; return true;
            }
            if (hit(mx, my, plusX, ctrlY - 3, 14, 14)) {
                s.liveCols = Math.min(4, s.liveCols + 1); s.listScroll = 0; return true;
            }
        }

        // card clicks — unified grid logic for all modes
        boolean isLive = s.cardMode == NexusScreen.CardMode.LIVE;
        // LIVE fullscreen: close button or click outside returns to grid
        if (isLive && s.liveFullscreen >= 0) {
            int cX = cx + availW() - 20, cY = GRID_TOP + 6;
            if (hit(mx, my, cX, cY, 14, 14)) { s.liveFullscreen = -1; return true; }
            if (!hit(mx, my, cx + NexusScreen.PAD, GRID_TOP, availW(),
                    gridBottom() - GRID_TOP)) {
                s.liveFullscreen = -1; return true;
            }
            List<BotSession> fsList = s.filteredSessions();
            if (s.liveFullscreen < fsList.size()) {
                BotSession fs = fsList.get(s.liveFullscreen);
                s.addMode = false;
                if (btn == 1) {
                    s.menuX = mx; s.menuY = my;
                    s.menuIdx = s.liveFullscreen; s.menuOpen = true;
                    return true;
                }
                s.selected.clear(); s.selected.add(s.liveFullscreen);
                s.anchor = s.liveFullscreen;
                long now = System.nanoTime();
                if (s.lastClickIdx == s.liveFullscreen
                        && now - s.lastClickNanos < 300_000_000L) {
                    boolean isMounted = SessionManager.get().mounted() == fs
                            || (SessionManager.get().mounted() == null
                                && fs == SessionManager.get().humanSession());
                    if (!isMounted) MythosNexusMod.switchTo(fs);
                    s.lastClickIdx = -1;
                } else {
                    s.lastClickIdx = s.liveFullscreen;
                    s.lastClickNanos = now;
                }
            }
            return true;
        }

        int cols = cols();
        int cardW = (availW() - (cols - 1) * NexusScreen.CARD_GAP) / cols;
        int cardHitH = cardH();
        int rows = gridRows(cardHitH);
        int visible = cols * rows;
        List<BotSession> list = s.filteredSessions();
        int drawn = 0;
        int startIdx = s.cardMode == NexusScreen.CardMode.PAGE
                ? s.pageIndex * visible : s.listScroll;
        int endLimit = s.cardMode == NexusScreen.CardMode.PAGE
                ? startIdx + visible : list.size();
        for (int i = startIdx; i < list.size() && i < endLimit && drawn < visible;
                i++, drawn++) {
            int col = drawn % cols, row = drawn / cols;
            int x = cx + NexusScreen.PAD + col * (cardW + NexusScreen.CARD_GAP);
            int y = GRID_TOP + row * (cardHitH + NexusScreen.CARD_GAP);
            if (hit(mx, my, x, y, cardW, cardHitH)) {
                s.addMode = false;
                if (btn == 1) {   // right-click → context menu
                    if (!s.selected.contains(i)) {
                        s.selected.clear();
                        s.selected.add(i);
                        s.anchor = i;
                        s.relayout();
                    }
                    s.menuX = mx; s.menuY = my; s.menuIdx = i; s.menuOpen = true;
                    return true;
                }
                s.pressCardIdx = i; s.pressX = mx; s.pressY = my;
                boolean ctrl = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)
                        || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
                boolean shift = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)
                        || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
                if (shift && s.anchor >= 0) {
                    int lo = Math.min(s.anchor, i), hi = Math.max(s.anchor, i);
                    for (int k = lo; k <= hi; k++) s.selected.add(k);
                } else if (ctrl) {
                    if (!s.selected.add(i)) s.selected.remove(i);
                    s.anchor = i;
                } else {
                    s.selected.clear();
                    s.selected.add(i);
                    s.anchor = i;
                }
                long now = System.nanoTime();
                if (s.lastClickIdx == i && now - s.lastClickNanos < 300_000_000L) {
                    BotSession bs = list.get(i);
                    boolean isMounted = SessionManager.get().mounted() == bs
                            || (SessionManager.get().mounted() == null
                                && bs == SessionManager.get().humanSession());
                    if (!isMounted) MythosNexusMod.switchTo(bs);
                    s.lastClickIdx = -1;
                } else {
                    s.lastClickIdx = i;
                    s.lastClickNanos = now;
                }
                s.relayout();
                return true;
            }
        }
        return false;
    }

    /** Wheel over the center column: page flip in PAGE mode, else grid scroll. */
    @Override
    boolean scroll(int dWheel) {
        boolean live = s.cardMode == NexusScreen.CardMode.LIVE;
        int rows = gridRows(cardH());
        if (s.cardMode == NexusScreen.CardMode.PAGE) {
            int pvis = cols() * rows;
            int pages = Math.max(1,
                    (s.filteredSessions().size() + pvis - 1) / pvis);
            s.pageIndex = Math.max(0, Math.min(pages - 1,
                    s.pageIndex - Integer.signum(dWheel)));
        } else {
            int maxS = Math.max(0, s.filteredSessions().size() - cols() * rows);
            // one notch = one grid row
            s.listScroll = Math.max(0, Math.min(maxS,
                    s.listScroll - Integer.signum(dWheel) * Math.max(1, cols())));
        }
        return true;
    }

    /** Grid scrollbar drag (dragScroll == 2). */
    @Override
    boolean drag(int mx, int my) {
        if (s.dragScroll != 2 || s.gridThumbH <= 0 || s.gridTrackH <= s.gridThumbH)
            return false;
        int ty = Math.max(s.gridTrackY, Math.min(my - s.dragOffY,
                s.gridTrackY + s.gridTrackH - s.gridThumbH));
        int maxS = Math.max(0, s.filteredSessions().size() - cols() * gridRows(cardH()));
        s.listScroll = Math.max(0, Math.min(maxS,
                (ty - s.gridTrackY) * maxS / Math.max(1, s.gridTrackH - s.gridThumbH)));
        return true;
    }
}
