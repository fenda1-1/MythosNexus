package com.mythos.nexus.ui;

import com.mythos.nexus.MythosNexusMod;
import com.mythos.nexus.core.NexusConfig;
import com.mythos.nexus.session.BotSession;
import com.mythos.nexus.session.SessionManager;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.mythos.nexus.ui.NexusGeometry.blend;
import static com.mythos.nexus.ui.NexusGeometry.clamp01;
import static com.mythos.nexus.ui.NexusGeometry.currentServer;
import static com.mythos.nexus.ui.NexusGeometry.hit;
import static com.mythos.nexus.ui.NexusGeometry.parsePort;
import static com.mythos.nexus.ui.NexusGeometry.sessionKey;

/**
 * 账号管理 — 全屏三栏。
 *   左：导航 + 分组（常驻）
 *   中：账号卡片网格，多选/右键菜单/滚动条
 *   右：随视图切换 — ACCOUNTS = 编辑选中卡片，PROXIES = 代理，LOG = 日志
 * 栏间有拖动条，悬停加粗提示可拖。
 */
public class NexusScreen extends GuiScreen {

    // NexusScreen.PAD-style references in the panels working ----
    static final int RAIL_EXPANDED_W = NexusLayout.RAIL_EXPANDED_W;
    static final int RAIL_COLLAPSED_W = NexusLayout.RAIL_COLLAPSED_W;
    static final int rightW_MIN = NexusLayout.rightW_MIN;
    int rightW = 280;
    static final int PAD = NexusLayout.PAD;
    static final int TOP_H = NexusLayout.TOP_H;
    static final int SPLIT_W = NexusLayout.SPLIT_W;
    static final int CARD_W = NexusLayout.CARD_W;
    static final int CARD_H = NexusLayout.CARD_H;
    static final int CARD_GAP = NexusLayout.CARD_GAP;
    static final int SCROLL_W = NexusLayout.SCROLL_W;
    static final int BATCH_H = NexusLayout.BATCH_H;

    int railW = RAIL_EXPANDED_W;
    int midW;
    boolean railCollapsed = false;

    boolean draggingLeft = false;
    boolean draggingRight = false;
    float hoverAnimLeft = 0f, hoverAnimRight = 0f;

    enum View { ACCOUNTS, RECONNECT, PROXIES, LOG, STATUS, THEME, TICKRATE, HELP, SCRIPTS, COMMANDS, INFO }
    View view = View.ACCOUNTS;

    enum CardMode { WATERFALL, PAGE, LIVE }
    CardMode cardMode = ScreenModel.restoreCardMode();
    int pageIndex = 0;
    /** true when LIVE mode rendered viewports this frame — dispose on exit. */
    boolean liveWasActive = false;
    /** LIVE mode column count (1-4). */
    int liveCols = 2;
    /** LIVE fullscreen card index (-1 = grid view). */
    int liveFullscreen = -1;

    /** Card density: NORMAL/COMPACT/DETAILED — persisted. */
    String cardDensity() { return NexusConfig.cardDensity(); }
    /** Card height for the current density. */
    int cardHeight() { return ScreenModel.cardHeight(); }

    // scrollbar drag state + last-frame thumb rects
    int dragScroll = 0;   // 0 none, 1 groups, 2 grid, 3 right, 4 log
    int dragOffY = 0;     // grab offset inside the dragged thumb
    int grpThumbY, grpThumbH, grpTrackY, grpTrackH;
    int gridThumbY, gridThumbH, gridTrackY, gridTrackH;
    int rightThumbY, rightThumbH, rightTrackY, rightTrackH;

    GuiTextField fName;      // edit: username
    GuiTextField fServer;    // edit: server (host:port)
    GuiTextField proxyHostField;
    GuiTextField proxyUserField;
    GuiTextField proxyPassField;
    GuiTextField groupNameField;
    GuiTextField searchField;
    GuiTextField groupEditField;   // inline rail rename
    GuiTextField fNote;            // per-account note
    GuiTextField fDelay;           // global batch connect interval (ms)
    GuiTextField fLiveW;           // LIVE viewport width
    GuiTextField fLiveH;           // LIVE viewport height
    String renamingGroup = null;

    /** When true, right panel shows "add new account" instead of editing selected. */
    boolean addMode = false;

    boolean groupMenuOpen = false;
    int groupMenuX, groupMenuY;
    String groupMenuTarget = null;

    /** null = show all; otherwise filter to this group. */
    String groupFilter = null;
    /** Indices into filteredSessions() of selected cards. */
    final Set<Integer> selected = new HashSet<>();
    /** Anchor for shift-click range selection. */
    int anchor = -1;
    int listScroll = 0;
    int groupScroll = 0;
    /** Group paths hidden by the tree expand arrow (persistent until toggled). */
    final Set<String> collapsedGroups = new HashSet<>();
    int proxyScroll = 0;
    int proxyThumbY = -1, proxyThumbH = 0, proxyTrackY = -1, proxyTrackH = 0;
    int rightScroll = 0;   // right panel scroll offset
    int rightContentBottom = 0; // tracks right panel content extent

    int rcScroll = 0;
    int rcThumbY, rcThumbH, rcTrackY, rcTrackH;
    int rcEvScroll = 0;
    int rcEvThumbY, rcEvThumbH, rcEvTrackY, rcEvTrackH;

    /** level filter: null = all, else "INFO"/"WARN"/"ERROR". */
    String logFilter = null;
    int logScroll = 0;
    final Set<Integer> logSelected = new HashSet<>();
    int logAnchor = -1;
    int logThumbY, logThumbH, logTrackY, logTrackH;
    long lastLogClickNanos;
    int lastLogClickIdx = -1;

    /** Right-click context menu: mx,my + card index (-1 = none). */
    int menuX, menuY, menuIdx = -1;
    boolean menuOpen = false;

    /** Unsaved-edit dialog when closing. */
    boolean dirty = false;
    boolean showUnsavedDialog = false;
    String unsavedAction = "close"; // "close" or "save"

    long lastClickNanos;
    int lastClickIdx = -1;

    /** Progress of an in-flight batch operation: {done, total} or null. */
    int[] batchProgress;
    String batchLabel;
    /** proxy-picker popup (batch "代理" or group "分配代理"). */
    boolean proxyPickOpen = false;
    int proxyPickX, proxyPickY;
    List<BotSession> proxyPickTargets = new ArrayList<>();
    /** proxy index -> "123ms" / "失败"; filled by background tests. */
    final java.util.Map<Integer, String> proxyTestResult = new java.util.HashMap<>();
    /** card drag-to-group state. */
    boolean draggingCard = false;
    int dragCardIdx = -1, dragCardX, dragCardY, pressCardIdx = -1, pressX, pressY;
    /** Overlay panel: null = closed, else an overlays-map id. */
    String overlayPanel;

    final NexusContextMenu menus = new NexusContextMenu(this);
    final NexusRail rail = new NexusRail(this);
    final NexusCenter center = new NexusCenter(this);
    final NexusAccountPanel rightAccounts = new NexusAccountPanel(this);
    final NexusReconnectPanel rightReconnect = new NexusReconnectPanel(this);
    final NexusProxyPanel rightProxies = new NexusProxyPanel(this);
    final NexusLogPanel rightLog = new NexusLogPanel(this);
    final NexusStatusPanel rightStatus = new NexusStatusPanel(this);
    final NexusThemePanel rightTheme = new NexusThemePanel(this);
    final NexusTickratePanel rightTickrate = new NexusTickratePanel(this);
    final NexusHelpPanel rightHelp = new NexusHelpPanel(this);
    final NexusScriptPanel rightScript = new NexusScriptPanel(this);
    final NexusInfoPanel rightInfo = new NexusInfoPanel(this);
    final NexusCommandPanel rightCommands = new NexusCommandPanel(this);
    final CommandPaletteOverlay palette = new CommandPaletteOverlay(this);
    final KeyRecordOverlay keyRecord = new KeyRecordOverlay(this);

    // modal overlays, looked up by overlayPanel id
    private final java.util.Map<String, NexusOverlay> overlays = new java.util.HashMap<>();
    { overlays.put("palette",  palette);
      overlays.put("keyrecord", keyRecord);
      overlays.put("settings", new SettingsOverlay(this));
      overlays.put("tickrate", new TickrateOverlay(this)); }

    // panels see GuiScreen's protected members through these
    net.minecraft.client.gui.FontRenderer font() { return fontRenderer; }
    net.minecraft.client.Minecraft mc() { return mc; }

    /** Show a transient toast near the bottom of the screen. */
    void toast(String text, long ms) {
        toastText = text;
        toastUntilMs = System.currentTimeMillis() + ms;
    }

    /** Tooltip state — card widgets set hoverText when they want a bubble. */
    String hoverText;
    int hoverX, hoverY;
    /** Transient toast — shows for toastUntilMs then auto-clears. */
    String toastText;
    long toastUntilMs;

    void drawTooltip(String text, int x, int y) {
        int w = fontRenderer.getStringWidth(text) + 8;
        // Anchor left of the point so tooltips near the right edge (icon strip,
        // theme-panel header buttons) don't run off screen.
        int x2 = x - w - 8;
        if (x2 < 4) x2 = Math.min(x + 12, width - w - 4);
        int y2 = y - 8;
        if (y2 < 4) y2 = 4;
        if (y2 + 18 > height - 4) y2 = height - 22;
        Ui.card(x2, y2, w, 18, NexusTheme.BG_SIDEBAR, NexusTheme.BORDER_SOFT, 3);
        fontRenderer.drawString(text, x2 + 4, y2 + 5, NexusTheme.TEXT_LABEL);
    }


    void clipPush(int x, int y, int w, int h) {
        int sf = Math.max(1, guiScaleFactor);
        int ph = mc.displayHeight;
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * sf, ph - (y + h) * sf,
                Math.max(0, w) * sf, Math.max(0, h) * sf);
    }

    void clipPop() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    /** Two-line tooltip (title + subtitle) anchored left of the icon strip. */    void drawTooltip2(String title, String sub, int mx, int my) {
        int w = Math.max(fontRenderer.getStringWidth(title),
                         fontRenderer.getStringWidth(sub)) + 10;
        int h = 30;
        int x = mx - w - 8;                 // left of cursor
        int y = my - h / 2;
        if (y < 4) y = 4;
        if (y + h > height - 4) y = height - 4 - h;
        Ui.card(x, y, w, h, NexusTheme.BG_SIDEBAR, NexusTheme.BORDER_SOFT, 3);
        fontRenderer.drawString(title, x + 5, y + 5, NexusTheme.TEXT_TITLE);
        fontRenderer.drawString(sub,   x + 5, y + 18, NexusTheme.TEXT_FAINT);
    }

    void drawCloseButton(int mx, int my) {
        int closeX = width - 26, closeY = 6;
        boolean closeHov = hit(mx, my, closeX, closeY, 18, 18);
        if (closeHov) Ui.roundedRect(closeX, closeY, 18, 18, 3, NexusTheme.HOVER_WASH);
        NexusIcons.drawCloseX(closeX + 9, closeY + 9, 4,
                closeHov ? NexusTheme.TEXT_TITLE : NexusTheme.TEXT_SUB);
    }


    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        com.mythos.nexus.core.NexusConfig.applySavedTheme();
        fitColumns();
        relayout();
    }

    /**
     * Keep the three columns inside the window. At small GUI scales the fixed
     * minimums (rail + 200 center + 280 right) overflow, which made the right
     * panel draw over the center. Shrink the right panel first, then collapse
     * the rail, before recomputing the center width.
     */
    /** When true the account editor collapses to the icon strip only. */
    boolean rightCollapsed = false;
    /** Width of the permanent icon strip on the far right. */
    static final int ICON_STRIP = NexusLayout.ICON_STRIP;

    void fitColumns() { NexusLayout.fitColumns(this); }

    void relayout() {
        int rightX = width - rightW;
        int ax = rightX + PAD;
        int ay = TOP_H + 30;
        int fieldW = panelW() - PAD * 2 - 4;   // exclude the icon strip
        fName = new GuiTextField(0, fontRenderer, ax + 2, ay + 2, fieldW, 18);
        fServer = new GuiTextField(1, fontRenderer, ax + 2, ay + 34, fieldW, 18);
        for (GuiTextField f : new GuiTextField[]{fName, fServer}) {
            f.setEnableBackgroundDrawing(false);
            f.setTextColor(NexusTheme.TEXT_LABEL);
            f.setDisabledTextColour(NexusTheme.TEXT_FAINT);
        }
        fName.setMaxStringLength(16);
        fServer.setMaxStringLength(64);

        // Proxy form starts at ay — the proxy LIST card sits below the form
        // now, so the inputs are the first thing under the header.
        int py = ay + 8;
        proxyHostField = new GuiTextField(3, fontRenderer, ax + 2, py + 2,
                fieldW, 18);
        proxyUserField = new GuiTextField(5, fontRenderer, ax + 2, py + 34,
                fieldW, 18);
        proxyPassField = new GuiTextField(6, fontRenderer, ax + 2, py + 66,
                fieldW, 18);
        for (GuiTextField f : new GuiTextField[]{proxyHostField,
                proxyUserField, proxyPassField}) {
            f.setEnableBackgroundDrawing(false);
            f.setTextColor(NexusTheme.TEXT_LABEL);
            f.setDisabledTextColour(NexusTheme.TEXT_FAINT);
        }
        proxyHostField.setMaxStringLength(255);
        proxyUserField.setMaxStringLength(255);
        proxyPassField.setMaxStringLength(255);

        groupNameField = new GuiTextField(7, fontRenderer, ax + 2, ay + 2,
                fieldW, 18);
        groupNameField.setMaxStringLength(16);
        groupNameField.setEnableBackgroundDrawing(false);
        groupNameField.setTextColor(NexusTheme.TEXT_LABEL);
        groupNameField.setDisabledTextColour(NexusTheme.TEXT_FAINT);

        String prevSearch = searchField == null ? "" : searchField.getText();
        boolean searchFocus = searchField != null && searchField.isFocused();
        int densityLeft = railW + SPLIT_W + midW - 118;
        int sw = Math.min(220, Math.max(80, midW / 4));
        int sx = Math.max(railW + SPLIT_W + PAD + 80, densityLeft - sw - 8);
        searchField = new GuiTextField(8, fontRenderer, sx, 6, sw, 18);
        searchField.setMaxStringLength(64);
        searchField.setEnableBackgroundDrawing(false);
        searchField.setTextColor(NexusTheme.TEXT_LABEL);
        searchField.setDisabledTextColour(NexusTheme.TEXT_FAINT);
        searchField.setText(prevSearch);
        searchField.setFocused(searchFocus);

        groupEditField = new GuiTextField(9, fontRenderer, PAD, 0, railW - PAD * 2 - 24, 18);
        groupEditField.setMaxStringLength(16);
        groupEditField.setEnableBackgroundDrawing(false);
        groupEditField.setTextColor(NexusTheme.TEXT_LABEL);
        groupEditField.setDisabledTextColour(NexusTheme.TEXT_FAINT);

        fNote = new GuiTextField(10, fontRenderer, ax + 2, 0, fieldW, 18);
        fNote.setMaxStringLength(64);
        fNote.setEnableBackgroundDrawing(false);
        fNote.setTextColor(NexusTheme.TEXT_LABEL);
        fNote.setDisabledTextColour(NexusTheme.TEXT_FAINT);

        fDelay = new GuiTextField(11, fontRenderer, ax + 2, 0, fieldW, 18);
        fDelay.setMaxStringLength(6);
        fDelay.setEnableBackgroundDrawing(false);
        fDelay.setTextColor(NexusTheme.TEXT_LABEL);
        fDelay.setDisabledTextColour(NexusTheme.TEXT_FAINT);

        // populate edit fields from first selected session
        BotSession sel = firstSelected();
        if (!addMode && sel != null && sel != SessionManager.get().humanSession()) {
            fName.setText(sel.username);
            fServer.setText(sel.serverHost + ":" + sel.serverPort);
            fNote.setText(NexusConfig.bindingFor(sessionKey(sel)).note);
        } else {
            fName.setText("");
            fServer.setText("");
            fNote.setText("");
        }
        fDelay.setText(String.valueOf(NexusConfig.connectDelayMs()));

        // LIVE viewport resolution fields — persisted in config, edited in
        // the right panel when LIVE mode is active.
        String[] wh = NexusConfig.liveViewSize().split("x");
        int lw = wh.length == 2 ? parseIntSafe(wh[0], 320) : 320;
        int lh = wh.length == 2 ? parseIntSafe(wh[1], 180) : 180;
        com.mythos.nexus.session.LiveViewportRenderer.applyViewSize(lw, lh);
        int fW = (fieldW - 8) / 2;  // two fields side by side
        fLiveW = new GuiTextField(12, fontRenderer, ax + 2, 0, fW, 18);
        fLiveH = new GuiTextField(13, fontRenderer, ax + 2 + fW + 8, 0, fW, 18);
        fLiveW.setMaxStringLength(4); fLiveW.setEnableBackgroundDrawing(false);
        fLiveW.setTextColor(NexusTheme.TEXT_LABEL);
        fLiveW.setDisabledTextColour(NexusTheme.TEXT_FAINT);
        fLiveH.setMaxStringLength(4); fLiveH.setEnableBackgroundDrawing(false);
        fLiveH.setTextColor(NexusTheme.TEXT_LABEL);
        fLiveH.setDisabledTextColour(NexusTheme.TEXT_FAINT);
        fLiveW.setText(String.valueOf(com.mythos.nexus.session.LiveViewportRenderer.VIEW_W));
        fLiveH.setText(String.valueOf(com.mythos.nexus.session.LiveViewportRenderer.VIEW_H));
        // per-bot LIVE size override prefills the fields when a bot is selected
        if (sel != null && sel != SessionManager.get().humanSession()) {
            String lvs = NexusConfig.liveViewSizeFor(sessionKey(sel));
            if (lvs != null) {
                String[] p = lvs.split("x");
                if (p.length == 2) { fLiveW.setText(p[0]); fLiveH.setText(p[1]); }
            }
        }
    }


    private static int parseIntSafe(String s, int def) {
        return ScreenModel.parseIntSafe(s, def);
    }

    /** Read the W/H fields, clamp, persist, apply to the renderer.
     *  With a bot selected, writes the per-bot override; otherwise writes
     *  the global default. */
    void applyLiveSize() { NexusActions.applyLiveSize(this); }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        // Drop all offscreen viewports when the manager screen closes — the
        // next open rebuilds them fresh.
        com.mythos.nexus.session.LiveViewportRenderer.disposeAll();
        liveWasActive = false;
    }

    /** Nexus always lays out at GUI scale 2 ("中") regardless of the game's
     *  own GUI-scale option — the layout is designed for that size. */
    static final int FIXED_GUI_SCALE = 2;
    /** Effective scale factor (may drop below FIXED_GUI_SCALE on tiny windows,
     *  same rule as vanilla ScaledResolution). */
    int guiScaleFactor = FIXED_GUI_SCALE;

    private static int fixedScaleFor(net.minecraft.client.Minecraft m) {
        int dw = m.displayWidth, dh = m.displayHeight;
        int sf = 1;
        while (sf < FIXED_GUI_SCALE && dw / (sf + 1) >= 320 && dh / (sf + 1) >= 240) sf++;
        return sf;
    }

    @Override
    public void setWorldAndResolution(net.minecraft.client.Minecraft m, int w, int h) {
        // vanilla passes width/height from the game's GUI scale; replace them
        // with our fixed-scale size (also covers window resize via onResize)
        guiScaleFactor = fixedScaleFor(m);
        super.setWorldAndResolution(m,
                (int) Math.ceil(m.displayWidth / (double) guiScaleFactor),
                (int) Math.ceil(m.displayHeight / (double) guiScaleFactor));
    }

    private void applyFixedProjection() {
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0.0D, mc.displayWidth / (double) guiScaleFactor,
                mc.displayHeight / (double) guiScaleFactor, 0.0D, 1000.0D, 3000.0D);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.loadIdentity();
        GlStateManager.translate(0.0F, 0.0F, -2000.0F);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partial) {
        // mouseX/Y arrive in the game's GUI scale — recompute in ours
        int mx = Mouse.getX() * width / Math.max(1, mc.displayWidth);
        int my = height - Mouse.getY() * height / Math.max(1, mc.displayHeight) - 1;
        applyFixedProjection();
        try {
            drawScreenFixed(mx, my, partial);
        } finally {
            clipPop();
            // hand the game's own GUI projection back for anything drawn after
            mc.entityRenderer.setupOverlayRendering();
        }
    }

    private void drawScreenFixed(int mx, int my, float partial) {
        if (showUnsavedDialog) { menus.drawUnsavedDialog(mx, my); return; }
        drawRect(0, 0, width, height, NexusTheme.BG_APP);
        Ui.enableBlend();

        hoverAnimLeft  = clamp01(hoverAnimLeft  + ((isHoverLeftSplit(mx, my)  || draggingLeft)  ? 0.2f : -0.2f));
        hoverAnimRight = clamp01(hoverAnimRight + ((isHoverRightSplit(mx, my) || draggingRight) ? 0.2f : -0.2f));

        rail.draw(mx, my);
        drawSplitLeft(mx, my);
        center.draw(mx, my);
        drawSplitRight(mx, my);
        drawRight(mx, my);
        drawCloseButton(mx, my);   // on top of the right panel
        drawStatusBar(mx, my);
        if (groupMenuOpen) menus.drawGroupMenu(mx, my);
        if (menuOpen) menus.drawContextMenu(mx, my);
        if (proxyPickOpen) drawProxyPicker(mx, my);

        if (view == View.ACCOUNTS) {
            fName.drawTextBox();
            fServer.drawTextBox();
            groupNameField.drawTextBox();
            fNote.drawTextBox();
            if (cardMode == CardMode.LIVE) {
                fLiveW.drawTextBox();
                fLiveH.drawTextBox();
            }
        } else if (view == View.PROXIES) {
            proxyHostField.drawTextBox();
            proxyUserField.drawTextBox();
            proxyPassField.drawTextBox();
            fDelay.drawTextBox();
        }
        // overlay panel (help / settings / tickrate) — on top of everything
        if (overlayPanel != null) {
            NexusOverlay ov = overlays.get(overlayPanel);
            if (ov != null) ov.draw(mx, my); else overlayPanel = null;
        }

        if (draggingCard && dragCardIdx >= 0) {
            List<BotSession> l = filteredSessions();
            if (dragCardIdx < l.size()) {
                BotSession bs = l.get(dragCardIdx);
                Ui.card(dragCardX - 60, dragCardY - 12, 120, 24, NexusTheme.BG_CARD_HOV,
                        NexusTheme.ACCENT, 4);
                fontRenderer.drawString(bs.username, dragCardX - 54, dragCardY - 8,
                        NexusTheme.TEXT_TITLE);
            }
        }
        if (hoverText != null) {
            int nl = hoverText.indexOf('\n');
            if (nl > 0) {
                drawTooltip2(hoverText.substring(0, nl),
                             hoverText.substring(nl + 1), hoverX, hoverY);
            } else {
                drawTooltip(hoverText, hoverX, hoverY);
            }
            hoverText = null;   // consumed — every draw resets it
        }
        if (toastText != null && System.currentTimeMillis() < toastUntilMs) {
            drawTooltip(toastText, width / 2, height - 60);
        } else if (toastText != null) {
            toastText = null;
        }
        super.drawScreen(mx, my, partial);
    }


    /** Bottom bar over the center column: batch actions for the current selection. */
    // ponytail: batch action bar removed — right panel already has connect/
    // disconnect/delete/proxy buttons. The 4 bottom buttons were redundant.

    void drawProxyPicker(int mx, int my) {
        List<NexusConfig.Proxy> ps = NexusConfig.proxies();
        int w = 200, rowH = 20, h = (ps.size() + 1) * rowH + 8;
        Ui.card(proxyPickX, proxyPickY, w, h, NexusTheme.BG_SIDEBAR,
                NexusTheme.BORDER_SOFT, 4);
        fontRenderer.drawString("批量分配代理", proxyPickX + 8, proxyPickY + 5,
                NexusTheme.TEXT_TITLE);
        int ry = proxyPickY + 20;
        // 直连 = -1
        boolean h0 = hit(mx, my, proxyPickX + 2, ry, w - 4, rowH - 2);
        if (h0) Ui.roundedRect(proxyPickX + 2, ry, w - 4, rowH - 2, 3, NexusTheme.HOVER_WASH);
        fontRenderer.drawString("直连", proxyPickX + 10, ry + 5,
                h0 ? NexusTheme.TEXT_TITLE : NexusTheme.TEXT_LABEL);
        ry += rowH;
        for (NexusConfig.Proxy p : ps) {
            boolean hov = hit(mx, my, proxyPickX + 2, ry, w - 4, rowH - 2);
            if (hov) Ui.roundedRect(proxyPickX + 2, ry, w - 4, rowH - 2, 3, NexusTheme.HOVER_WASH);
            fontRenderer.drawString(p.spec(), proxyPickX + 10, ry + 5,
                    hov ? NexusTheme.TEXT_TITLE : NexusTheme.TEXT_LABEL);
            ry += rowH;
        }
    }

    void handleProxyPickClick(int mx, int my) {
        List<NexusConfig.Proxy> ps = NexusConfig.proxies();
        int w = 200, rowH = 20;
        int ry = proxyPickY + 20;
        int chosen = Integer.MIN_VALUE;
        if (hit(mx, my, proxyPickX + 2, ry, w - 4, rowH - 2)) chosen = -1;
        ry += rowH;
        for (int i = 0; i < ps.size(); i++) {
            if (hit(mx, my, proxyPickX + 2, ry, w - 4, rowH - 2)) { chosen = i; break; }
            ry += rowH;
        }
        if (chosen != Integer.MIN_VALUE) {
            for (BotSession s : proxyPickTargets) {
                if (s == SessionManager.get().humanSession()) continue;
                s.proxyIndex = chosen;
                NexusConfig.setProxy(sessionKey(s), chosen);
                s.logEvent("proxy -> " + (chosen < 0 ? "direct" : ps.get(chosen).spec()));
            }
            dirty = true;
        }
        proxyPickOpen = false;
    }


    void openPalette() {
        palette.open();
        overlayPanel = "palette";
    }

    void showView(View v) {
        view = v;
        if (rightCollapsed) {
            rightCollapsed = false;
            if (rightW < rightW_MIN) rightW = 280;
            fitColumns();
            relayout();
        }
    }

    void toggleRightPanel() {
        rightCollapsed = !rightCollapsed;
        if (!rightCollapsed && rightW < rightW_MIN) rightW = 280;
        fitColumns();
        relayout();
    }

    void requestClose() {
        if (dirty) { unsavedAction = "close"; showUnsavedDialog = true; }
        else mc.displayGuiScreen(null);
    }

    void selectAllFiltered() {
        List<BotSession> l = filteredSessions();
        selected.clear();
        for (int i = 0; i < l.size(); i++) selected.add(i);
    }

    void copySelected() {
        List<BotSession> sel = selectedSessions();
        if (sel.isEmpty()) { toast("未选中账号", 1500); return; }
        StringBuilder b = new StringBuilder();
        for (BotSession s0 : sel) {
            if (b.length() > 0) b.append('\n');
            b.append(s0.username).append(':').append(s0.serverHost).append(':').append(s0.serverPort);
        }
        setClipboardString(b.toString());
        toast("已复制 " + sel.size() + " 个账号", 1500);
    }

    void pasteAccount() {
        String clip = getClipboardString();
        if (clip == null || clip.trim().isEmpty()) { toast("剪贴板为空", 1500); return; }
        int n = 0;
        for (String line : clip.split("\\r?\\n")) {
            String[] parts = line.trim().split(":");
            if (parts.length < 2 || parts[0].isEmpty()) continue;
            int port = parts.length >= 3 ? parsePort(parts[2]) : 25565;
            SessionManager.get().createSession(parts[0], parts[1], port > 0 ? port : 25565);
            n++;
        }
        if (n > 0) { dirty = true; toast("已粘贴 " + n + " 个账号", 1500); }
        else toast("剪贴板格式应为 user:host:port", 2000);
    }


    boolean isHoverLeftSplit(int mx, int my) {
        return NexusLayout.isHoverLeftSplit(this, mx, my);
    }
    boolean isHoverRightSplit(int mx, int my) {
        return NexusLayout.isHoverRightSplit(this, mx, my);
    }

    void drawSplitLeft(int mx, int my) { NexusLayout.drawSplitLeft(this); }
    void drawSplitRight(int mx, int my) { NexusLayout.drawSplitRight(this); }


    void drawRight(int mx, int my) {
        int rx = width - rightW;
        int pw = panelW();
        // Panel region (left of strip) — only when expanded
        if (pw > 0) {
            Ui.fill(rx, 0, pw, height, NexusTheme.BG_SIDEBAR);
            Ui.vDivider(rx, 0, height);
            switch (view) {
                case ACCOUNTS: rightAccounts.draw(rx, mx, my); break;
                case RECONNECT: rightReconnect.draw(rx, mx, my); break;
                case PROXIES:  rightProxies.draw(rx, mx, my);  break;
                case LOG:      rightLog.draw(rx, mx, my);      break;
                case STATUS:   rightStatus.draw(rx, mx, my);   break;
                case THEME:    rightTheme.draw(rx, mx, my);    break;
                case TICKRATE: rightTickrate.draw(rx, mx, my); break;
                case HELP:     rightHelp.draw(rx, mx, my);     break;
                case SCRIPTS:  rightScript.draw(rx, mx, my);   break;
                case COMMANDS: rightCommands.draw(rx, mx, my); break;
                case INFO:     rightInfo.draw(rx, mx, my);     break;
            }
        }
        // Icon strip — always drawn, distinct bg + left divider
        int ix = rx + pw;
        Ui.fill(ix, 0, rightW - pw, height, NexusTheme.BG_CARD);
        if (pw > 0) Ui.vDivider(ix, 0, height);
        drawRightIcons(mx, my);
    }

    /** Right icon strip entries: {View, iconId, tipTitle, tipSub} — shared by draw and click. */
    static final Object[] ICON_STRIP_DEFS = {
        View.ACCOUNTS, "edit",   "账号",   "添加 / 编辑 / 选中账号",
        View.RECONNECT,"reconnect","自动重连","队列 · 随机间隔 · 统一开关",
        View.STATUS,   "status", "状态",   "在线时长 · HP · 附近实体",
        View.LOG,      "log",    "日志",   "会话事件日志",
        View.PROXIES,  "proxy",  "代理",   "代理服务器列表",
        View.SCRIPTS,  "script", "脚本",   "MythosScript 序列 · 执行 / 停止",
        View.COMMANDS, "cmd",    "命令",   "全部命令 · F1 打开命令面板",
        View.HELP,     "help",   "快捷键", "双击录制组合键",
        View.THEME,    "theme",  "主题",   "预设 / 新建 / 导入 / AI 生成",
        View.TICKRATE, "tick",   "Tick",   "自适应调度 · 优先级曲线",
        View.INFO,     "info",   "信息",   "支持作者 · 交流群 · 开源",
    };

    /** Width of the editable panel region — always excludes the icon strip. */
    int panelW() { return rightCollapsed ? 0 : rightW - ICON_STRIP; }

    /** Permanent icon strip on the far right; always visible, even collapsed. */
    void drawRightIcons(int mx, int my) {
        int iconX = width - 20, iconY = 40;
        Object[] strip = ICON_STRIP_DEFS;
        for (int i = 0; i < strip.length; i += 4) {
            View pv = (View) strip[i];
            String ic = (String) strip[i + 1];
            String tipT = (String) strip[i + 2];
            String tipS = (String) strip[i + 3];
            if (!NexusConfig.iconPinned(ic)) continue;   // hidden via 图标面板设置
            boolean hov = hit(mx, my, iconX, iconY, 16, 20);
            boolean active = view == pv && !(pv == View.ACCOUNTS && rightCollapsed);
            if (hov) Ui.roundedRect(iconX, iconY, 16, 20, 3, NexusTheme.HOVER_WASH);
            // collapsed && pv==view → panel is hidden: show dimmed (not accent)
            int color = (active && !rightCollapsed) ? NexusTheme.ACCENT
                    : (hov ? NexusTheme.TEXT_TITLE : NexusTheme.TEXT_SUB);
            int cx = iconX + 8, cy = iconY + 10;
            switch (ic) {
                case "edit":   NexusIcons.iconEdit(cx, cy, color);   break;
                case "reconnect": NexusIcons.iconReconnect(cx, cy, color); break;
                case "status": NexusIcons.iconStatus(cx, cy, color); break;
                case "log":    NexusIcons.iconLog(cx, cy, color);    break;
                case "proxy":  NexusIcons.iconProxy(cx, cy, color);  break;
                case "theme":  NexusIcons.iconTheme(cx, cy, color);  break;
                case "tick":   NexusIcons.iconTickRate(cx, cy, color); break;
                case "help":   NexusIcons.iconKeyboard(cx, cy, color); break;
                case "script": NexusIcons.iconScript(cx, cy, color);   break;
                case "cmd":    NexusIcons.iconCommand(cx, cy, color);  break;
                case "info":   NexusIcons.iconInfo(cx, cy, color);     break;
            }
            if (hov) { hoverText = tipT + "\n" + tipS; hoverX = mx; hoverY = my; }
            iconY += 24;
        }
        iconY += 4;
        Ui.divider(iconX - 2, iconY, 20);
        iconY += 4;
        // The settings icon is always pinned — unpinning it would lock the
        // user out of the only path back into that overlay.
        {
            boolean hov = hit(mx, my, iconX, iconY, 16, 20);
            if (hov) Ui.roundedRect(iconX, iconY, 16, 20, 3, NexusTheme.HOVER_WASH);
            NexusIcons.iconSettings(iconX + 8, iconY + 10,
                    hov ? NexusTheme.TEXT_TITLE : NexusTheme.TEXT_SUB);
            if (hov) { hoverText = "设置\n面板开关 · 常规选项"; hoverX = mx; hoverY = my; }
            iconY += 24;
        }
    }


    static List<String> allGroups() { return ScreenModel.allGroups(); }

    List<BotSession> filteredSessions() {
        return ScreenModel.filteredSessions(groupFilter,
                searchField == null ? "" : searchField.getText());
    }

    BotSession firstSelected() {
        return ScreenModel.firstSelected(selected, filteredSessions());
    }

    List<BotSession> selectedSessions() {
        return ScreenModel.selectedSessions(selected, filteredSessions());
    }

    void drawButton(int x, int y, int w, int h, String label,
                    boolean disabled, int mx, int my) {
        if (disabled) {
            Ui.roundedRect(x, y, w, h, NexusTheme.RADIUS_BTN, NexusTheme.IDLE);
            Ui.roundedRect(x + 1, y + 1, w - 2, h - 2,
                    NexusTheme.RADIUS_BTN - 1, NexusTheme.BG_SIDEBAR);
            int tw = fontRenderer.getStringWidth(label);
            fontRenderer.drawString(label, x + (w - tw) / 2,
                    y + (h - 8) / 2, NexusTheme.TEXT_FAINT);
            return;
        }
        boolean hov = hit(mx, my, x, y, w, h);
        Ui.button(x, y, w, h, hov, false);
        int tw = fontRenderer.getStringWidth(label);
        fontRenderer.drawString(label, x + (w - tw) / 2, y + (h - 8) / 2,
                NexusTheme.TEXT_TITLE);
    }


    @Override
    protected void mouseClicked(int mx, int my, int btn) throws IOException {
        // Overlay panels consume all clicks until closed
        if (overlayPanel != null) {
            NexusOverlay ov = overlays.get(overlayPanel);
            if (ov != null) { ov.click(mx, my, btn); return; }
            overlayPanel = null;   // unknown id — treat as closed
        }
        if (btn == 0 && hit(mx, my, width - 26, 6, 18, 18)) {
            mc.displayGuiScreen(null);
            return;
        }
        if (showUnsavedDialog) {
            menus.handleUnsavedClick(mx, my);
            return;
        }
        if (proxyPickOpen) {
            handleProxyPickClick(mx, my);
            return;
        }
        if (groupMenuOpen) {
            menus.handleGroupMenuClick(mx, my);
            return;
        }
        if (menuOpen) {
            menus.handleMenuClick(mx, my);
            return;
        }
        searchField.mouseClicked(mx, my, btn);
        if (view == View.ACCOUNTS && rightAccounts.click(mx, my, btn)) return;
        if (view == View.PROXIES  && rightProxies.click(mx, my, btn))  return;

        // rail: rename field / group menu / collapse / thumb / nav / chips
        if (mx < railW && rail.click(mx, my, btn)) return;

        // right icon column — MUST run before the right-splitter drag test.
        // When collapsed, rightW == ICON_STRIP so the splitter's hover rect
        // (width - rightW .. width - rightW + SPLIT_W) would otherwise sit
        // on top of the icons and eat every icon click as a "drag" press.
        {
            int iconX = width - 20, iconY = 40;
            for (int vi = 0; vi < ICON_STRIP_DEFS.length; vi += 4) {
                View pv = (View) ICON_STRIP_DEFS[vi];
                if (!NexusConfig.iconPinned((String) ICON_STRIP_DEFS[vi + 1])) continue;
                if (hit(mx, my, iconX, iconY, 16, 20)) {
                    if (pv == view && !rightCollapsed) {
                        // Same icon while expanded → collapse (toggle off).
                        rightCollapsed = true;
                    } else {
                        // Different icon, or collapsed → switch panel + expand.
                        view = pv;
                        rightCollapsed = false;
                        if (rightW < rightW_MIN) rightW = 280;
                    }
                    fitColumns();
                    relayout();
                    return;
                }
                iconY += 24;
            }
            iconY += 8;   // separator gap
            // Always drawn — the settings icon must never be unpinnable.
            if (hit(mx, my, iconX, iconY, 16, 20)) { overlayPanel = "settings"; return; }
            iconY += 24;
        }

        if (isHoverLeftSplit(mx, my))  { draggingLeft = true;  return; }
        if (isHoverRightSplit(mx, my)) { draggingRight = true; return; }

        if (center.click(mx, my, btn)) return;

        // right panel dispatch — STATUS consumes every right-region click.
        if (!rightCollapsed) {
            switch (view) {
                case ACCOUNTS: if (rightAccounts.click(mx, my, btn)) return; break;
                case RECONNECT: if (rightReconnect.click(mx, my, btn)) return; break;
                case PROXIES:  if (rightProxies.click(mx, my, btn))  return; break;
                case STATUS:   rightStatus.click(mx, my, btn); return;
                case LOG:      if (rightLog.click(mx, my, btn))     return; break;
                case THEME:    if (rightTheme.click(mx, my, btn))   return; break;
                case TICKRATE: if (rightTickrate.click(mx, my, btn)) return; break;
                case HELP:     if (rightHelp.click(mx, my, btn))     return; break;
                case SCRIPTS:  if (rightScript.click(mx, my, btn))   return; break;
                case COMMANDS: if (rightCommands.click(mx, my, btn)) return; break;
            }
        }
    }

    void commitGroupRename() { NexusActions.commitGroupRename(this); }

    void deleteSelected() { NexusActions.deleteSelected(this); }

    void applyEdit(BotSession s) { NexusActions.applyEdit(this, s); }

    /** Run a batch operation (connect / disconnect / delete) with a progress
     *  label shown in the status bar. Runs on a daemon thread; UI only reads
     *  batchProgress/batchLabel from the mc thread. */
    void runBatch(String label, java.util.List<BotSession> targets,
                  java.util.function.Consumer<BotSession> op) {
        NexusActions.runBatch(this, label, targets, op);
    }


    void drawStatusBar(int mx, int my) {
        int x = railW + SPLIT_W;
        int w = midW;
        int y = height - 18;
        Ui.fill(x, y, w, 18, NexusTheme.BG_SIDEBAR);

        java.util.List<BotSession> all = SessionManager.get().sessions();
        int online = 0;
        for (BotSession s : all) {
            if (s != SessionManager.get().humanSession()
                    && s.state == BotSession.State.ONLINE) online++;
        }
        int total = all.size() - 1;   // exclude human pseudo
        Runtime rt = Runtime.getRuntime();
        long usedMB = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        String stats = String.format("在线 %d/%d  ·  内存 %4dM  ·  CPU %2d%%  ·  线程 %2d",
                online, total, usedMB, processCpuPercent(), Thread.activeCount());
        fontRenderer.drawString(fontRenderer.trimStringToWidth(stats, Math.max(0, w - PAD * 2)),
                x + PAD, y + 5, NexusTheme.TEXT_FAINT);

        // Batch progress — replaces stats while running
        if (batchProgress != null) {
            String bp = batchLabel + " " + batchProgress[0] + "/" + batchProgress[1];
            fontRenderer.drawString(bp,
                    x + w / 2 - fontRenderer.getStringWidth(bp) / 2, y + 5,
                    NexusTheme.ACCENT);
        }

        // Memory warning — red when heap > 85% of max
        long maxMB = rt.maxMemory() / 1024 / 1024;
        if (usedMB > maxMB * 0.85) {
            String warn = "内存不足";
            fontRenderer.drawString(warn,
                    x + w - PAD - fontRenderer.getStringWidth(warn), y + 5,
                    NexusTheme.ERR);
        }
    }

    void saveAll() { NexusActions.saveAll(this); }


    void exportAccounts() { exportAccounts(false); }

    void exportAccounts(boolean csv) { NexusActions.exportAccounts(csv); }

    void importAccounts() { NexusActions.importAccounts(this); }


    /** Kick off a background TCP connect test for proxy i; result lands in proxyTestResult. */
    void testProxy(int i) { NexusActions.testProxy(this, i); }

    @Override
    protected void mouseReleased(int mx, int my, int state) {
        if (overlayPanel != null) {
            NexusOverlay ov = overlays.get(overlayPanel);
            if (ov != null) ov.mouseReleased(mx, my, state);
        }
        if (draggingCard && dragCardIdx >= 0) {
            dropCardOnGroup(mx, my);
        }
        draggingCard = false;
        dragCardIdx = -1;
        pressCardIdx = -1;
        draggingLeft = false;
        draggingRight = false;
        dragScroll = 0;
        rightProxies.release();
        rightAccounts.release(); rightStatus.release(); rightLog.release();
        super.mouseReleased(mx, my, state);
    }

    /** If released over a rail group row, move the dragged card's session there. */
    void dropCardOnGroup(int mx, int my) {
        NexusActions.dropCardOnGroup(this, mx, my);
    }

    @Override
    protected void mouseClickMove(int mx, int my, int btn, long since) {
        // Overlay drag (curve editor) takes precedence — it's modal.
        if (overlayPanel != null) {
            NexusOverlay ov = overlays.get(overlayPanel);
            if (ov != null && ov.mouseDragged(mx, my, btn)) return;
        }
        // card drag-to-group: start once the cursor moves past a threshold
        if (pressCardIdx >= 0 && !draggingCard
                && (Math.abs(mx - pressX) > 5 || Math.abs(my - pressY) > 5)) {
            draggingCard = true;
            dragCardIdx = pressCardIdx;
        }
        if (draggingCard) {
            dragCardX = mx; dragCardY = my;
            return;
        }
        if (draggingLeft) {
            railW = Math.max(RAIL_COLLAPSED_W, Math.min(mx, width / 3));
            fitColumns();
            relayout();
        } else if (draggingRight) {
            // dragging splitter changes the PANEL width; icon strip stays
            // a constant 24px on the far right.
            int newPanelW = Math.max(rightW_MIN, Math.min(width - mx - ICON_STRIP,
                    width - railW - SPLIT_W * 2 - 200));
            rightW = newPanelW + ICON_STRIP;
            rightCollapsed = false;   // manual resize restores the panel
            fitColumns();
            relayout();
        } else {
            rail.drag(mx, my);
            center.drag(mx, my);
            rightAccounts.drag(mx, my);
            rightReconnect.drag(mx, my);
            rightLog.drag(mx, my);
            rightProxies.drag(mx, my);  // proxy-list thumb (panel-local drag)
            rightStatus.drag(mx, my);   // entity-list thumb (panel-local drag)
            rightTheme.drag(mx, my);
            rightTickrate.drag(mx, my);
            rightHelp.drag(mx, my);
            rightScript.drag(mx, my);
            rightCommands.drag(mx, my);
        }
        super.mouseClickMove(mx, my, btn, since);
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        // Overlays are modal for the keyboard too; Esc closes unless consumed.
        if (overlayPanel != null) {
            NexusOverlay ov = overlays.get(overlayPanel);
            if (ov != null && ov.keyTyped(c, key)) return;
            if (key == Keyboard.KEY_ESCAPE) overlayPanel = null;
            return;
        }
        // inline group rename takes the keyboard first
        if (renamingGroup != null && groupEditField.isFocused()) {
            if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                commitGroupRename();
                relayout();
                return;
            }
            if (key == Keyboard.KEY_ESCAPE) {
                renamingGroup = null;
                groupEditField.setFocused(false);
                relayout();
                return;
            }
            groupEditField.textboxKeyTyped(c, key);
            return;
        }
        // typing in a field → never trigger the manager hotkey
        boolean typing = fName.isFocused() || fServer.isFocused()
                || groupNameField.isFocused() || searchField.isFocused()
                || proxyHostField.isFocused()
                || proxyUserField.isFocused() || proxyPassField.isFocused()
                || fNote.isFocused() || fDelay.isFocused()
                || fLiveW.isFocused() || fLiveH.isFocused();
        if (typing) {
            // F-keys and Ctrl/Alt combos still run commands, except text-editing ones
            if (commandKeyWhileTyping(key)) {
                NexusCommands.Cmd cmd = NexusCommands.match(key);
                if (cmd != null) { NexusCommands.execute(this, cmd); return; }
            }
            // Enter in the search box connects the first matching account
            if (searchField.isFocused() && (key == Keyboard.KEY_RETURN
                    || key == Keyboard.KEY_NUMPADENTER)) {
                List<BotSession> l = filteredSessions();
                if (!l.isEmpty()) MythosNexusMod.switchTo(l.get(0));
                return;
            }
            if (searchField.isFocused()) searchField.textboxKeyTyped(c, key);
            if (view == View.ACCOUNTS) {
                if (fName.isFocused()) fName.textboxKeyTyped(c, key);
                if (fServer.isFocused()) fServer.textboxKeyTyped(c, key);
                if (groupNameField.isFocused()) groupNameField.textboxKeyTyped(c, key);
                if (fNote.isFocused()) fNote.textboxKeyTyped(c, key);
                if (fLiveW.isFocused()) { fLiveW.textboxKeyTyped(c, key); applyLiveSize(); }
                if (fLiveH.isFocused()) { fLiveH.textboxKeyTyped(c, key); applyLiveSize(); }
            } else if (view == View.PROXIES) {
                if (proxyHostField.isFocused()) proxyHostField.textboxKeyTyped(c, key);
                if (proxyUserField.isFocused()) proxyUserField.textboxKeyTyped(c, key);
                if (proxyPassField.isFocused()) proxyPassField.textboxKeyTyped(c, key);
                if (fDelay.isFocused()) fDelay.textboxKeyTyped(c, key);
            }
            return;
        }
        if (key != 0 && !NexusCommands.isModifier(key)) {
            NexusCommands.Cmd cmd = NexusCommands.match(key);
            if (cmd != null) { NexusCommands.execute(this, cmd); return; }
        }
        if (key == MythosNexusMod.KEY_MANAGER.getKeyCode()) {
            if (dirty) { showUnsavedDialog = true; return; }
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(c, key);
    }

    private static boolean commandKeyWhileTyping(int key) {
        if ((key >= Keyboard.KEY_F1 && key <= Keyboard.KEY_F10) || key == Keyboard.KEY_F11 || key == Keyboard.KEY_F12)
            return true;
        if (!NexusCommands.ctrlDown() && !NexusCommands.altDown()) return false;
        switch (key) {
            case Keyboard.KEY_A: case Keyboard.KEY_C: case Keyboard.KEY_V: case Keyboard.KEY_X:
            case Keyboard.KEY_Z: case Keyboard.KEY_BACK: case Keyboard.KEY_DELETE:
            case Keyboard.KEY_LEFT: case Keyboard.KEY_RIGHT: case Keyboard.KEY_HOME: case Keyboard.KEY_END:
                return false;
            default:
                return !NexusCommands.isModifier(key);
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d != 0 && overlayPanel != null) {
            if ("palette".equals(overlayPanel)) palette.scroll(d);
            return;
        }
        if (d != 0) {
            int mx = Mouse.getEventX() * width / mc.displayWidth;
            int cx = railW + SPLIT_W;
            int rx = width - rightW;
            if (mx < railW) rail.scroll(d);
            else if (mx >= cx && mx < rx) center.scroll(d);
            else {
                switch (view) {
                    case ACCOUNTS: rightAccounts.scroll(d); break;
                    case RECONNECT: rightReconnect.scroll(d); break;
                    case STATUS:   rightStatus.scroll(d);   break;
                    case LOG:      rightLog.scroll(d);       break;
                    case PROXIES:  rightProxies.scroll(d);   break;
                    case THEME:    rightTheme.scroll(d);      break;
                    case TICKRATE: rightTickrate.scroll(d);   break;
                    case HELP:     rightHelp.scroll(d);        break;
                    case SCRIPTS:  rightScript.scroll(d);      break;
                    case INFO:     rightInfo.scroll(d);        break;
                    case COMMANDS: rightCommands.scroll(d);    break;
                }
            }
        }
    }

    @Override public boolean doesGuiPauseGame() { return false; }

    /** Process CPU load as an integer percent, or -1 when the MXBean can't
     *  report it (non-HotSpot JVM). */
    private static int processCpuPercent() {
        // getProcessCpuLoad is a slow native call on Windows (PDH); calling it
        // per frame dropped the game to single-digit FPS. Sample off-thread.
        cpuWantedUntilMs = System.currentTimeMillis() + 3000L;
        if (CPU_SAMPLER_STARTED.compareAndSet(false, true)) {
            Thread t = new Thread(NexusScreen::cpuSampleLoop, "Nexus-CpuSampler");
            t.setDaemon(true);
            t.start();
        }
        return cpuPercent;
    }

    private static volatile int cpuPercent = -1;
    private static volatile long cpuWantedUntilMs = 0L;
    private static final java.util.concurrent.atomic.AtomicBoolean CPU_SAMPLER_STARTED =
            new java.util.concurrent.atomic.AtomicBoolean();

    private static void cpuSampleLoop() {
        Object os;
        java.lang.reflect.Method m;
        try {
            os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            // com.sun.management.OperatingSystemMXBean exposes
            // getProcessCpuLoad; reflect so a non-com.sun bean doesn't break.
            m = os.getClass().getMethod("getProcessCpuLoad");
            m.setAccessible(true);
        } catch (Throwable t) {
            return;
        }
        while (true) {
            // only sample while the screen is drawing the readout
            if (System.currentTimeMillis() < cpuWantedUntilMs) {
                try {
                    double load = (double) m.invoke(os);
                    cpuPercent = load < 0 ? -1 : (int) Math.round(load * 100.0);
                } catch (Throwable t) {
                    cpuPercent = -1;
                }
            }
            try { Thread.sleep(1000L); } catch (InterruptedException e) { return; }
        }
    }
}
