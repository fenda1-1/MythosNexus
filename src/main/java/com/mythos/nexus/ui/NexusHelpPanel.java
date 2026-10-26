package com.mythos.nexus.ui;

import com.mythos.nexus.core.NexusConfig;
import com.mythos.nexus.ui.NexusCommands.Cmd;
import com.mythos.nexus.ui.NexusCommands.Combo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.mythos.nexus.ui.NexusGeometry.hit;

/**
 * Right-panel HELP view — keybind editor. Edits a draft; double-click a row to
 * record combos; footer 恢复默认 / 取消 / 保存 applies or discards the draft.
 */
final class NexusHelpPanel extends NexusPanel {

    private static final int ROW = 18, STEP = 20, FOOTER_H = 28, DOUBLE_CLICK_MS = 350;

    private final UiHits hits = new UiHits();
    private Map<String, List<Combo>> draft;
    private String selectedId, lastClickId;
    private long lastClickMs;
    private int scroll, contentH, viewH;

    NexusHelpPanel(NexusScreen s) { super(s); }

    private Map<String, List<Combo>> draft() {
        if (draft == null) draft = NexusCommands.snapshot();
        return draft;
    }

    boolean dirty() {
        if (draft == null) return false;
        Map<String, List<Combo>> saved = NexusCommands.snapshot();
        for (Cmd c : NexusCommands.ALL)
            if (!saved.get(c.id).equals(draft.get(c.id))) return true;
        return false;
    }

    private boolean changed(Cmd c) {
        return draft != null && !NexusCommands.bindings(c).equals(draft.get(c.id));
    }

    /** Opens the recorder for one command (also used by the palette's gear). */
    void beginRecord(Cmd c) {
        selectedId = c.id;
        s.keyRecord.open(c, draft());
    }

    void save() {
        Map<String, List<String>> out = new HashMap<>();
        for (Cmd c : NexusCommands.ALL) {
            List<Combo> l = draft().get(c.id);
            if (l == null || l.equals(c.defaults)) continue;
            List<String> enc = new ArrayList<>();
            for (Combo k : l) enc.add(k.encode());
            out.put(c.id, enc);
        }
        NexusConfig.setAllCommandBinds(out);
        draft = null;
        s.toast("快捷键已保存", 1500);
    }

    void cancel() {
        draft = null;
        s.toast("已撤销未保存的快捷键修改", 1500);
    }

    private void resetAll() {
        draft = new HashMap<>();
        for (Cmd c : NexusCommands.ALL) draft.put(c.id, new ArrayList<>(c.defaults));
    }

    /** Combos bound to more than one command in the draft. */
    private Map<Combo, Integer> usage() {
        Map<Combo, Integer> m = new HashMap<>();
        for (List<Combo> l : draft().values()) for (Combo k : l) m.merge(k, 1, Integer::sum);
        return m;
    }

    void draw(int rx, int mx, int my) {
        hits.clear();
        int pad = NexusScreen.PAD;
        int x = rx + pad, w = s.panelW() - pad * 2;
        int y = 10;
        boolean dirty = dirty();
        s.font().drawString("快捷键", x, y, NexusTheme.TEXT_TITLE);
        if (dirty) s.font().drawString("● 未保存", x + w - s.font().getStringWidth("● 未保存"), y, NexusTheme.WARN);
        s.font().drawString(s.font().trimStringToWidth("双击命令录制组合键 · 可绑定多个", w), x, y + 12,
                NexusTheme.TEXT_SUB);
        y += 28;
        Ui.divider(x, y, w);
        y += 4;

        int top = y, bottom = s.height - FOOTER_H - 4;
        viewH = bottom - top;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentH - viewH)));
        Map<Combo, Integer> used = usage();

        Map<String, List<Cmd>> groups = new LinkedHashMap<>();
        for (Cmd c : NexusCommands.ALL) groups.computeIfAbsent(c.group, k -> new ArrayList<>()).add(c);

        UiHits.scissor(s, rx, top, s.panelW(), viewH);
        int yy = top - scroll;
        for (Map.Entry<String, List<Cmd>> g : groups.entrySet()) {
            s.font().drawString(g.getKey(), x, yy + 4, NexusTheme.TEXT_FAINT);
            yy += 14;
            for (Cmd c : g.getValue()) {
                List<Combo> binds = draft().get(c.id);
                boolean sel = c.id.equals(selectedId);
                boolean hov = my >= top && my < bottom && hit(mx, my, x, yy, w, ROW);
                if (sel) Ui.roundedRect(x, yy, w, ROW, 3, NexusTheme.BG_CARD_HOV);
                else if (hov) Ui.roundedRect(x, yy, w, ROW, 3, NexusTheme.HOVER_WASH);
                boolean conflict = false;
                for (Combo k : binds) if (used.getOrDefault(k, 0) > 1) conflict = true;
                String label = NexusCommands.label(binds);
                int lw = Math.min(s.font().getStringWidth(label), w / 2 + 10);
                String shown = s.font().trimStringToWidth(label, lw);
                int chipX = x + w - lw - 8;
                Ui.roundedRect(chipX, yy + 2, lw + 6, ROW - 4, 3, NexusTheme.BG_CARD);
                s.font().drawString(shown, chipX + 3, yy + 5,
                        conflict ? NexusTheme.ERR : binds.isEmpty() ? NexusTheme.TEXT_FAINT : NexusTheme.WARN);
                String title = (changed(c) ? "● " : "") + c.title;
                s.font().drawString(s.font().trimStringToWidth(title, chipX - x - 8), x + 4, yy + 5,
                        changed(c) ? NexusTheme.ACCENT : hov ? NexusTheme.TEXT_TITLE : NexusTheme.TEXT_LABEL);
                if (hov) {
                    s.hoverText = c.title + "\n" + c.desc + (conflict ? " · 存在冲突的组合键" : "") + " · 双击录制";
                    s.hoverX = mx; s.hoverY = my;
                }
                hits.addClipped(x, yy, w, ROW, top, bottom, () -> rowClicked(c));
                yy += STEP;
            }
            yy += 4;
        }
        UiHits.endScissor();
        contentH = yy + scroll - top;
        UiHits.scrollbar(rx + s.panelW() - 4, top, viewH, contentH, scroll, NexusTheme.BORDER_SOFT);

        // footer: 恢复默认 / 取消 / 保存
        int fy = s.height - FOOTER_H;
        Ui.divider(x, fy - 2, w);
        int bw = (w - 8) / 3;
        s.drawButton(x, fy + 4, bw, 18, "恢复默认", false, mx, my);
        hits.add(x, fy + 4, bw, 18, this::resetAll);
        s.drawButton(x + bw + 4, fy + 4, bw, 18, "取消", !dirty, mx, my);
        if (dirty) hits.add(x + bw + 4, fy + 4, bw, 18, this::cancel);
        s.drawButton(x + (bw + 4) * 2, fy + 4, w - (bw + 4) * 2, 18, "保存", !dirty, mx, my);
        if (dirty) hits.add(x + (bw + 4) * 2, fy + 4, w - (bw + 4) * 2, 18, this::save);
    }

    private void rowClicked(Cmd c) {
        long now = System.currentTimeMillis();
        boolean dbl = c.id.equals(lastClickId) && now - lastClickMs < DOUBLE_CLICK_MS;
        selectedId = c.id;
        lastClickId = dbl ? null : c.id;
        lastClickMs = now;
        if (dbl) beginRecord(c);
    }

    @Override
    boolean click(int mx, int my, int btn) {
        if (btn != 0) return false;
        return hits.click(mx, my);
    }

    @Override
    boolean scroll(int d) {
        scroll = Math.max(0, Math.min(scroll - Integer.signum(d) * STEP, Math.max(0, contentH - viewH)));
        return true;
    }
}
