package com.mythos.nexus.ui;

import com.mythos.nexus.session.BotSession;
import com.mythos.nexus.session.SessionManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mythos.nexus.ui.NexusGeometry.hit;

/** Right-panel SCRIPTS view: MythosScript sequences grouped category → sub-category, run/stop per account. */
final class NexusScriptPanel extends NexusPanel {

    private static final int ROW = 16, STEP = 18, DOUBLE_CLICK_MS = 350;

    private final Set<String> expanded = new HashSet<>();
    private final UiHits hits = new UiHits();
    private String selectedSeq;
    private int scroll, contentH, viewH;
    private String lastClickKey;
    private long lastClickMs;

    NexusScriptPanel(NexusScreen s) { super(s); }


    /** Selected accounts, or every online bot when nothing is selected. */
    List<BotSession> targets() {
        List<BotSession> sel = s.selectedSessions();
        if (!sel.isEmpty()) return sel;
        BotSession human = SessionManager.get().humanSession();
        List<BotSession> out = new ArrayList<>();
        for (BotSession b : SessionManager.get().sessions())
            if (b != human && b.state == BotSession.State.ONLINE) out.add(b);
        return out;
    }

    private static boolean runnable(BotSession b) {
        return b == SessionManager.get().humanSession() || b.state == BotSession.State.ONLINE;
    }

    void runSelected() {
        if (!MythosScripts.available()) { s.toast("未检测到 MythosScript", 2000); return; }
        if (selectedSeq == null) { s.toast("先在脚本面板选择一个序列", 2000); return; }
        runOn(targets(), selectedSeq);
    }

    void stopSelected() {
        if (!MythosScripts.available()) { s.toast("未检测到 MythosScript", 2000); return; }
        int n = 0;
        for (BotSession b : targets()) if (runnable(b)) { MythosScripts.stop(b); n++; }
        s.toast(n == 0 ? "没有在线的目标账号" : "已停止 " + n + " 个账号的序列", 2000);
    }

    private void runOn(List<BotSession> ts, String seq) {
        int n = 0;
        for (BotSession b : ts) if (runnable(b)) { MythosScripts.run(b, seq); n++; }
        s.toast(n == 0 ? "没有在线的目标账号" : "执行 " + seq + " → " + n + " 个账号", 2000);
    }

    private void runOne(BotSession b) {
        if (selectedSeq == null) { s.toast("先选择一个序列", 1500); return; }
        List<BotSession> one = new ArrayList<>();
        one.add(b);
        runOn(one, selectedSeq);
    }

    private void stopOne(BotSession b) {
        MythosScripts.stop(b);
        s.toast("已停止 " + b.username, 1500);
    }

    private boolean doubleClick(String key) {
        long now = System.currentTimeMillis();
        boolean dbl = key.equals(lastClickKey) && now - lastClickMs < DOUBLE_CLICK_MS;
        lastClickKey = dbl ? null : key;
        lastClickMs = now;
        return dbl;
    }

    private void toggle(String key) {
        if (!expanded.remove(key)) expanded.add(key);
    }


    void draw(int rx, int mx, int my) {
        hits.clear();
        int pad = NexusScreen.PAD;
        int x = rx + pad, w = s.panelW() - pad * 2;
        int y = 10;
        s.font().drawString("脚本", x, y, NexusTheme.TEXT_TITLE);
        List<BotSession> ts = targets();
        String tgt = s.selected.isEmpty()
                ? "目标: 全部在线 bot (" + ts.size() + ")" : "目标: 选中 " + ts.size() + " 个账号";
        s.font().drawString(s.font().trimStringToWidth(tgt, w), x, y + 12, NexusTheme.TEXT_SUB);
        y += 28;

        if (!MythosScripts.available()) {
            s.font().drawString("未检测到 MythosScript", x, y, NexusTheme.WARN);
            return;
        }

        int bw = (w - 8) / 3;
        s.drawButton(x, y, bw, 16, "执行", selectedSeq == null, mx, my);
        if (selectedSeq != null) hits.add(x, y, bw, 16, this::runSelected);
        s.drawButton(x + bw + 4, y, bw, 16, "停止", false, mx, my);
        hits.add(x + bw + 4, y, bw, 16, this::stopSelected);
        s.drawButton(x + (bw + 4) * 2, y, w - (bw + 4) * 2, 16, "刷新", false, mx, my);
        hits.add(x + (bw + 4) * 2, y, w - (bw + 4) * 2, 16, MythosScripts::invalidate);
        hoverTip(mx, my, x, y, bw, 16, "执行所选序列", "目标账号见上方 · 快捷键可在快捷键面板修改");
        hoverTip(mx, my, x + bw + 4, y, bw, 16, "停止序列", "停止目标账号正在运行的序列");
        y += 22;

        String seqLine = selectedSeq == null ? "单击选择序列 · 双击直接执行" : "已选: " + selectedSeq;
        s.font().drawString(s.font().trimStringToWidth(seqLine, w), x, y,
                selectedSeq == null ? NexusTheme.TEXT_FAINT : NexusTheme.ACCENT);
        y += 14;
        Ui.divider(x, y, w);
        y += 4;

        int top = y, bottom = s.height - 6;
        viewH = bottom - top;
        int maxScroll = Math.max(0, contentH - viewH);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        UiHits.scissor(s, rx, top, s.panelW(), viewH);
        int yy = top - scroll;
        yy = drawTree(x, w, yy, top, bottom, mx, my);
        yy += 6;
        yy = drawAccounts(x, w, yy, top, bottom, mx, my);
        UiHits.endScissor();
        contentH = yy + scroll - top;
        UiHits.scrollbar(rx + s.panelW() - 4, top, viewH, contentH, scroll, NexusTheme.BORDER_SOFT);
    }

    private int drawTree(int x, int w, int yy, int top, int bottom, int mx, int my) {
        List<MythosScripts.Seq> seqs = MythosScripts.sequences();
        Map<String, String> running = MythosScripts.running();
        Map<String, Integer> runCount = new HashMap<>();
        for (String seq : running.values()) runCount.merge(seq, 1, Integer::sum);

        Map<String, Map<String, List<MythosScripts.Seq>>> tree = new LinkedHashMap<>();
        for (String c : MythosScripts.categories()) tree.put(c, new LinkedHashMap<>());
        for (MythosScripts.Seq q : seqs) {
            String cat = q.category.isEmpty() ? "未分类" : q.category;
            tree.computeIfAbsent(cat, k -> new LinkedHashMap<>())
                .computeIfAbsent(q.subCategory, k -> new ArrayList<>()).add(q);
        }

        yy = section(x, w, yy, "序列 (" + seqs.size() + ")");
        if (seqs.isEmpty()) {
            s.font().drawString("没有可见序列", x + 4, yy + 4, NexusTheme.TEXT_FAINT);
            return yy + STEP;
        }
        for (Map.Entry<String, Map<String, List<MythosScripts.Seq>>> ce : tree.entrySet()) {
            String cat = ce.getKey();
            int total = 0;
            for (List<MythosScripts.Seq> l : ce.getValue().values()) total += l.size();
            if (total == 0) continue;
            String ck = "c:" + cat;
            boolean open = expanded.contains(ck);
            yy = groupRow(x, w, yy, top, bottom, mx, my, 0, cat, total, open, ck);
            if (!open) continue;
            for (Map.Entry<String, List<MythosScripts.Seq>> se : ce.getValue().entrySet()) {
                String sub = se.getKey();
                int indent = 10;
                if (sub != null && !sub.isEmpty()) {
                    String sk = "s:" + cat + "/" + sub;
                    boolean sOpen = expanded.contains(sk);
                    yy = groupRow(x, w, yy, top, bottom, mx, my, 10, sub, se.getValue().size(), sOpen, sk);
                    if (!sOpen) continue;
                    indent = 20;
                }
                for (MythosScripts.Seq q : se.getValue())
                    yy = seqRow(x, w, yy, top, bottom, mx, my, indent, q, runCount.getOrDefault(q.name, 0));
            }
        }
        return yy;
    }

    private int section(int x, int w, int yy, String title) {
        s.font().drawString(title, x, yy + 3, NexusTheme.TEXT_SUB);
        return yy + 14;
    }

    private int groupRow(int x, int w, int yy, int top, int bottom, int mx, int my,
                         int indent, String name, int count, boolean open, String key) {
        boolean hov = my >= top && my < bottom && hit(mx, my, x, yy, w, ROW);
        if (hov) Ui.roundedRect(x, yy, w, ROW, 3, NexusTheme.HOVER_WASH);
        s.font().drawString(open ? "▾" : "▸", x + indent + 2, yy + 4, NexusTheme.TEXT_SUB);
        String cnt = String.valueOf(count);
        int cw = s.font().getStringWidth(cnt);
        s.font().drawString(s.font().trimStringToWidth(name, w - indent - 20 - cw),
                x + indent + 12, yy + 4, hov ? NexusTheme.TEXT_TITLE : NexusTheme.TEXT_LABEL);
        s.font().drawString(cnt, x + w - cw - 4, yy + 4, NexusTheme.TEXT_FAINT);
        hits.addClipped(x, yy, w, ROW, top, bottom, () -> toggle(key));
        return yy + STEP;
    }

    private int seqRow(int x, int w, int yy, int top, int bottom, int mx, int my,
                       int indent, MythosScripts.Seq q, int runs) {
        boolean sel = q.name.equals(selectedSeq);
        boolean hov = my >= top && my < bottom && hit(mx, my, x, yy, w, ROW);
        if (sel) Ui.roundedRect(x, yy, w, ROW, 3, NexusTheme.BG_CARD_HOV);
        else if (hov) Ui.roundedRect(x, yy, w, ROW, 3, NexusTheme.HOVER_WASH);
        String badge = runs > 0 ? "运行 " + runs : "";
        int bw = badge.isEmpty() ? 0 : s.font().getStringWidth(badge) + 6;
        s.font().drawString(s.font().trimStringToWidth(q.name, w - indent - 10 - bw),
                x + indent + 6, yy + 4, sel ? NexusTheme.ACCENT : NexusTheme.TEXT_LABEL);
        if (runs > 0) s.font().drawString(badge, x + w - bw, yy + 4, NexusTheme.ACCENT);
        if (hov) {
            String sub = q.note.isEmpty() ? "单击选择 · 双击对目标账号执行" : q.note;
            s.hoverText = q.name + "\n" + sub; s.hoverX = mx; s.hoverY = my;
        }
        String name = q.name;
        hits.addClipped(x, yy, w, ROW, top, bottom, () -> {
            selectedSeq = name;
            if (doubleClick("q:" + name)) runOn(targets(), name);
        });
        return yy + STEP;
    }

    private int drawAccounts(int x, int w, int yy, int top, int bottom, int mx, int my) {
        Ui.divider(x, yy, w);
        yy += 2;
        yy = section(x, w, yy, "账号 · 执行所选序列 / 停止");
        BotSession human = SessionManager.get().humanSession();
        for (BotSession b : SessionManager.get().sessions()) {
            boolean online = runnable(b);
            boolean hov = my >= top && my < bottom && hit(mx, my, x, yy, w, ROW);
            if (hov) Ui.roundedRect(x, yy, w, ROW, 3, NexusTheme.HOVER_WASH);
            int dot = b.state == BotSession.State.ONLINE || b == human ? NexusTheme.ACCENT
                    : b.state == BotSession.State.CONNECTING ? NexusTheme.WARN : NexusTheme.IDLE;
            Ui.dot(x + 5, yy + 8, dot);
            int btnW = 26;
            int bx2 = x + w - btnW, bx1 = bx2 - btnW - 2;
            String run = MythosScripts.runningOn(b);
            String name = (b.username == null ? "?" : b.username) + (b == human ? " (本体)" : "");
            int nameMax = bx1 - (x + 12) - 4;
            int nw = Math.min(s.font().getStringWidth(name), nameMax);
            s.font().drawString(s.font().trimStringToWidth(name, nameMax), x + 12, yy + 4,
                    online ? NexusTheme.TEXT_LABEL : NexusTheme.TEXT_FAINT);
            if (run != null && nameMax - nw > 20)
                s.font().drawString(s.font().trimStringToWidth("· " + run, nameMax - nw - 4),
                        x + 12 + nw + 4, yy + 4, NexusTheme.ACCENT);
            if (online) {
                smallButton(bx1, yy + 1, btnW, ROW - 2, "执行", selectedSeq != null, mx, my, top, bottom,
                        () -> runOne(b));
                smallButton(bx2, yy + 1, btnW, ROW - 2, "停止", run != null, mx, my, top, bottom,
                        () -> stopOne(b));
            }
            yy += STEP;
        }
        return yy;
    }

    private void smallButton(int x, int y, int w, int h, String label, boolean enabled,
                             int mx, int my, int top, int bottom, Runnable r) {
        boolean hov = enabled && my >= top && my < bottom && hit(mx, my, x, y, w, h);
        Ui.roundedRect(x, y, w, h, 3, hov ? NexusTheme.BG_CARD_HOV : NexusTheme.BG_CARD);
        int tw = s.font().getStringWidth(label);
        s.font().drawString(label, x + (w - tw) / 2, y + (h - 8) / 2,
                enabled ? (hov ? NexusTheme.TEXT_TITLE : NexusTheme.TEXT_LABEL) : NexusTheme.TEXT_FAINT);
        if (enabled) hits.addClipped(x, y, w, h, top, bottom, r);
    }

    private void hoverTip(int mx, int my, int x, int y, int w, int h, String t, String sub) {
        if (hit(mx, my, x, y, w, h)) { s.hoverText = t + "\n" + sub; s.hoverX = mx; s.hoverY = my; }
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
