package com.mythos.nexus.ui;

import com.mythos.nexus.ui.NexusCommands.Cmd;
import com.mythos.nexus.ui.NexusCommands.Combo;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.mythos.nexus.ui.NexusGeometry.hit;

/**
 * "keyrecord" overlay — records any number of key combos for one command into
 * the keybind panel's draft. Enter confirms, Del clears, Esc cancels (with no
 * modifier held; with modifiers those keys are recorded like any other).
 */
final class KeyRecordOverlay implements NexusOverlay {

    private static final int W = 340, ROW = 18;

    private final NexusScreen s;
    private final UiHits hits = new UiHits();
    private Cmd cmd;
    private Map<String, List<Combo>> drafts;
    private final List<Combo> draft = new ArrayList<>();
    private String returnTo;

    KeyRecordOverlay(NexusScreen s) { this.s = s; }

    @Override public String id() { return "keyrecord"; }

    /** Edits {@code drafts.get(c.id)} in place on confirm. */
    void open(Cmd c, Map<String, List<Combo>> drafts) {
        this.cmd = c;
        this.drafts = drafts;
        draft.clear();
        List<Combo> cur = drafts.get(c.id);
        if (cur != null) draft.addAll(cur);
        returnTo = "keyrecord".equals(s.overlayPanel) ? null : s.overlayPanel;
        s.overlayPanel = "keyrecord";
    }

    private int h() { return 132 + Math.max(1, draft.size()) * ROW; }
    private int x() { return (s.width - W) / 2; }
    private int y() { return (s.height - h()) / 2; }

    private void close() { s.overlayPanel = returnTo; returnTo = null; }

    private void commit() {
        drafts.put(cmd.id, new ArrayList<>(draft));
        close();
    }

    private String conflictOf(Combo k) {
        for (Cmd o : NexusCommands.ALL) {
            if (o == cmd) continue;
            List<Combo> l = drafts.get(o.id);
            if (l != null && l.contains(k)) return o.title;
        }
        return null;
    }

    @Override
    public void draw(int mx, int my) {
        if (cmd == null) { close(); return; }
        hits.clear();
        Ui.fill(0, 0, s.width, s.height, 0x88000000);
        int x = x(), y = y(), h = h();
        Ui.card(x, y, W, h, NexusTheme.BG_SIDEBAR, NexusTheme.ACCENT, 6);
        s.font().drawString("录制快捷键", x + 14, y + 12, NexusTheme.TEXT_TITLE);
        s.font().drawString(s.font().trimStringToWidth(cmd.title + " · " + cmd.group, W - 100),
                x + 80, y + 12, NexusTheme.ACCENT);
        s.font().drawString("按下任意组合键添加 · 可添加多个", x + 14, y + 28, NexusTheme.TEXT_SUB);
        s.font().drawString("Enter 确认 · Delete 清空 · Esc 取消", x + 14, y + 40, NexusTheme.TEXT_FAINT);

        // live preview of held modifiers
        int py = y + 56;
        Ui.inputFrame(x + 14, py, W - 28, 18, true);
        String held = NexusCommands.heldModifiersLabel();
        String preview = held.isEmpty() ? "等待按键..." : "[" + held + " + ...]";
        s.font().drawString(preview, x + 20, py + 5,
                held.isEmpty() ? NexusTheme.TEXT_FAINT : NexusTheme.WARN);

        int ly = py + 26;
        s.font().drawString("已录制 (" + draft.size() + ")", x + 14, ly, NexusTheme.TEXT_SUB);
        ly += 12;
        if (draft.isEmpty()) {
            s.font().drawString("未绑定", x + 20, ly + 5, NexusTheme.TEXT_FAINT);
        }
        for (int i = 0; i < draft.size(); i++) {
            Combo k = draft.get(i);
            int ry = ly + i * ROW;
            boolean hov = hit(mx, my, x + 14, ry, W - 28, ROW - 2);
            Ui.roundedRect(x + 14, ry, W - 28, ROW - 2, 3, hov ? NexusTheme.BG_CARD_HOV : NexusTheme.BG_CARD);
            s.font().drawString("[" + k.label() + "]", x + 20, ry + 4, NexusTheme.TEXT_TITLE);
            String conflict = conflictOf(k);
            if (conflict != null) {
                String t = s.font().trimStringToWidth("与「" + conflict + "」冲突", 120);
                s.font().drawString(t, x + W - 36 - s.font().getStringWidth(t), ry + 4, NexusTheme.WARN);
            }
            int cx = x + W - 24;
            boolean xh = hit(mx, my, cx - 6, ry, 14, ROW - 2);
            NexusIcons.drawCloseX(cx, ry + 8, 3, xh ? NexusTheme.ERR : NexusTheme.TEXT_SUB);
            final int idx = i;
            hits.add(cx - 6, ry, 14, ROW - 2, () -> draft.remove(idx));
        }

        int by = y + h - 26, bw = (W - 28 - 12) / 4;
        String[] labels = {"默认", "清空", "取消", "确认"};
        Runnable[] acts = {
            () -> { draft.clear(); draft.addAll(cmd.defaults); },
            draft::clear,
            this::close,
            this::commit,
        };
        for (int i = 0; i < 4; i++) {
            int bx = x + 14 + i * (bw + 4);
            s.drawButton(bx, by, bw, 18, labels[i], false, mx, my);
            hits.add(bx, by, bw, 18, acts[i]);
        }
    }

    @Override
    public boolean click(int mx, int my, int btn) {
        if (btn == 0) hits.click(mx, my);
        return true;
    }

    @Override
    public boolean keyTyped(char c, int key) {
        if (cmd == null) return false;
        if (key <= 0 || NexusCommands.isModifier(key)) return true;
        boolean mods = NexusCommands.ctrlDown() || NexusCommands.shiftDown() || NexusCommands.altDown();
        if (!mods) {
            if (key == Keyboard.KEY_ESCAPE) { close(); return true; }
            if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) { commit(); return true; }
            if (key == Keyboard.KEY_DELETE) { draft.clear(); return true; }
        }
        Combo k = NexusCommands.held(key);
        if (!draft.contains(k)) draft.add(k);
        return true;
    }
}
