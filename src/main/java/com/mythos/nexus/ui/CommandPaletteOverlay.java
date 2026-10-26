package com.mythos.nexus.ui;

import com.mythos.nexus.ui.NexusCommands.Cmd;
import com.mythos.nexus.ui.NexusCommands.Combo;
import net.minecraft.util.ChatAllowedCharacters;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.mythos.nexus.ui.NexusGeometry.hit;

/** "palette" overlay — searchable command list (F1), Enter/click runs, gear records keys. */
final class CommandPaletteOverlay implements NexusOverlay {

    private static final int ROW_H = 26, HEADER = 52;

    private final NexusScreen s;
    private final UiHits hits = new UiHits();
    private String query = "";
    private int sel, scroll;
    private List<Cmd> shown = new ArrayList<>();

    CommandPaletteOverlay(NexusScreen s) { this.s = s; }

    @Override public String id() { return "palette"; }

    void open() {
        query = "";
        sel = 0;
        scroll = 0;
    }

    private int w() { return Math.min(460, s.width - 40); }
    private int h() { return Math.min(340, s.height - 40); }
    private int x() { return (s.width - w()) / 2; }
    private int y() { return (s.height - h()) / 2; }
    private int visibleRows() { return Math.max(1, (h() - HEADER - 22) / ROW_H); }

    private List<Cmd> filter() {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Cmd> out = new ArrayList<>();
        for (Cmd c : NexusCommands.ALL) {
            if (q.isEmpty() || (c.title + " " + c.group + " " + c.desc + " " + c.id)
                    .toLowerCase(Locale.ROOT).contains(q))
                out.add(c);
        }
        return out;
    }

    private void clampSel() {
        sel = Math.max(0, Math.min(sel, shown.size() - 1));
        int vis = visibleRows();
        if (sel < scroll) scroll = sel;
        if (sel >= scroll + vis) scroll = sel - vis + 1;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, shown.size() - vis)));
    }

    private void run(Cmd c) {
        s.overlayPanel = null;
        NexusCommands.execute(s, c);
    }

    private void record(Cmd c) {
        s.overlayPanel = null;
        s.showView(NexusScreen.View.HELP);
        s.rightHelp.beginRecord(c);
    }

    @Override
    public void draw(int mx, int my) {
        hits.clear();
        shown = filter();
        clampSel();
        Ui.fill(0, 0, s.width, s.height, 0x88000000);
        int x = x(), y = y(), w = w(), h = h();
        Ui.card(x, y, w, h, NexusTheme.BG_SIDEBAR, NexusTheme.BORDER_SOFT, 6);
        s.font().drawString("命令面板", x + 14, y + 10, NexusTheme.TEXT_TITLE);
        String cnt = shown.size() + " 个命令";
        s.font().drawString(cnt, x + w - 14 - s.font().getStringWidth(cnt), y + 10, NexusTheme.TEXT_FAINT);

        int fy = y + 24;
        Ui.inputFrame(x + 14, fy, w - 28, 18, true);
        boolean caret = (System.currentTimeMillis() / 500) % 2 == 0;
        if (query.isEmpty())
            s.font().drawString("搜索命令..." , x + 20, fy + 5, NexusTheme.TEXT_FAINT);
        String q = s.font().trimStringToWidth(query, w - 60, true);
        s.font().drawString(q + (caret ? "_" : ""), x + 20, fy + 5, NexusTheme.TEXT_TITLE);
        if (!query.isEmpty()) {
            int cx = x + w - 24;
            NexusIcons.drawCloseX(cx, fy + 9, 3, hit(mx, my, cx - 6, fy, 14, 18) ? NexusTheme.ERR : NexusTheme.TEXT_SUB);
            hits.add(cx - 6, fy, 14, 18, () -> { query = ""; sel = 0; scroll = 0; });
        }

        int ly = y + HEADER, vis = visibleRows();
        if (shown.isEmpty())
            s.font().drawString("没有匹配的命令", x + 20, ly + 8, NexusTheme.TEXT_FAINT);
        for (int i = 0; i < vis && scroll + i < shown.size(); i++) {
            int idx = scroll + i;
            Cmd c = shown.get(idx);
            int ry = ly + i * ROW_H;
            int rw = w - 28;
            boolean hov = hit(mx, my, x + 14, ry, rw, ROW_H - 2);
            if (idx == sel) Ui.roundedRect(x + 14, ry, rw, ROW_H - 2, 3, NexusTheme.BG_CARD_HOV);
            else if (hov) Ui.roundedRect(x + 14, ry, rw, ROW_H - 2, 3, NexusTheme.HOVER_WASH);

            // gear (record), then binding chip, right-aligned
            int gx = x + 14 + rw - 12;
            boolean gh = hit(mx, my, gx - 8, ry, 16, ROW_H - 2);
            NexusIcons.iconSettings(gx, ry + 12, gh ? NexusTheme.ACCENT : NexusTheme.TEXT_SUB);
            List<Combo> binds = NexusCommands.bindings(c);
            String label = NexusCommands.label(binds);
            String chip = s.font().trimStringToWidth(label, rw / 2 - 20);
            int cw = s.font().getStringWidth(chip) + 8;
            int chipX = gx - 12 - cw;
            Ui.roundedRect(chipX, ry + 5, cw, 14, 3, NexusTheme.BG_CARD);
            s.font().drawString(chip, chipX + 4, ry + 8, binds.isEmpty() ? NexusTheme.TEXT_FAINT : NexusTheme.WARN);

            int tw = chipX - (x + 22) - 6;
            s.font().drawString(s.font().trimStringToWidth(c.title, tw), x + 22, ry + 3,
                    idx == sel ? NexusTheme.ACCENT : NexusTheme.TEXT_TITLE);
            s.font().drawString(s.font().trimStringToWidth(c.group + " · " + c.desc, tw), x + 22, ry + 14,
                    NexusTheme.TEXT_FAINT);

            hits.add(x + 14, ry, rw, ROW_H - 2, () -> run(c));
            hits.add(gx - 8, ry, 16, ROW_H - 2, () -> record(c));
            if (gh) { s.hoverText = "录制快捷键\n在快捷键面板中修改并保存"; s.hoverX = mx; s.hoverY = my; }
        }
        UiHits.scrollbar(x + w - 9, ly, vis * ROW_H, shown.size() * ROW_H, scroll * ROW_H, NexusTheme.BORDER_SOFT);
        s.font().drawString("↑↓ 选择 · Enter 执行 · Esc 关闭", x + 14, y + h - 14, NexusTheme.TEXT_FAINT);
    }

    void scroll(int d) {
        int vis = visibleRows();
        scroll = Math.max(0, Math.min(scroll - Integer.signum(d), Math.max(0, shown.size() - vis)));
        if (sel < scroll) sel = scroll;
        if (sel >= scroll + vis) sel = scroll + vis - 1;
    }

    @Override
    public boolean click(int mx, int my, int btn) {
        if (!hit(mx, my, x(), y(), w(), h())) { s.overlayPanel = null; return true; }
        if (btn == 0) hits.click(mx, my);
        return true;
    }

    @Override
    public boolean keyTyped(char c, int key) {
        int vis = visibleRows();
        switch (key) {
            case Keyboard.KEY_ESCAPE: s.overlayPanel = null; return true;
            case Keyboard.KEY_UP:     sel--; clampSel(); return true;
            case Keyboard.KEY_DOWN:   sel++; clampSel(); return true;
            case Keyboard.KEY_PRIOR:  sel -= vis; clampSel(); return true;
            case Keyboard.KEY_NEXT:   sel += vis; clampSel(); return true;
            case Keyboard.KEY_HOME:   sel = 0; clampSel(); return true;
            case Keyboard.KEY_END:    sel = shown.size() - 1; clampSel(); return true;
            case Keyboard.KEY_RETURN:
            case Keyboard.KEY_NUMPADENTER:
                shown = filter();
                if (!shown.isEmpty()) run(shown.get(Math.max(0, Math.min(sel, shown.size() - 1))));
                return true;
            case Keyboard.KEY_BACK:
                if (!query.isEmpty()) {
                    query = NexusCommands.ctrlDown() ? "" : query.substring(0, query.length() - 1);
                    sel = 0; scroll = 0;
                }
                return true;
            default:
                break;
        }
        Cmd pal = NexusCommands.byId("palette");
        if (pal != null && !NexusCommands.isModifier(key)
                && NexusCommands.bindings(pal).contains(NexusCommands.held(key))) {
            s.overlayPanel = null;
            return true;
        }
        if (!NexusCommands.ctrlDown() && !NexusCommands.altDown() && ChatAllowedCharacters.isAllowedCharacter(c)) {
            query += c;
            sel = 0; scroll = 0;
        }
        return true;
    }
}
