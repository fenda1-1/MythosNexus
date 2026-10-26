package com.mythos.nexus.ui;

import com.mythos.nexus.ui.NexusCommands.Cmd;
import com.mythos.nexus.ui.NexusCommands.Combo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.mythos.nexus.ui.NexusGeometry.hit;

/** Right-panel COMMANDS view: every command grouped, click to run, bound keys on the right. */
final class NexusCommandPanel extends NexusPanel {

    private static final int ROW = 18, STEP = 20;

    private final UiHits hits = new UiHits();
    private int scroll, contentH, viewH;

    NexusCommandPanel(NexusScreen s) { super(s); }

    void draw(int rx, int mx, int my) {
        hits.clear();
        int pad = NexusScreen.PAD;
        int x = rx + pad, w = s.panelW() - pad * 2;
        int y = 10;
        s.font().drawString("命令", x, y, NexusTheme.TEXT_TITLE);
        List<Combo> pal = NexusCommands.bindings(NexusCommands.byId("palette"));
        String hint = "点击执行 · " + (pal.isEmpty() ? "命令面板" : pal.get(0).label() + " 打开命令面板");
        s.font().drawString(s.font().trimStringToWidth(hint, w), x, y + 12, NexusTheme.TEXT_SUB);
        y += 28;
        Ui.divider(x, y, w);
        y += 4;

        int top = y, bottom = s.height - 8;
        viewH = bottom - top;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentH - viewH)));

        Map<String, List<Cmd>> groups = new LinkedHashMap<>();
        for (Cmd c : NexusCommands.ALL) groups.computeIfAbsent(c.group, k -> new ArrayList<>()).add(c);

        UiHits.scissor(s, rx, top, s.panelW(), viewH);
        int yy = top - scroll;
        for (Map.Entry<String, List<Cmd>> g : groups.entrySet()) {
            s.font().drawString(g.getKey(), x, yy + 4, NexusTheme.TEXT_FAINT);
            yy += 14;
            for (Cmd c : g.getValue()) {
                boolean hov = my >= top && my < bottom && hit(mx, my, x, yy, w, ROW);
                if (hov) Ui.roundedRect(x, yy, w, ROW, 3, NexusTheme.HOVER_WASH);
                List<Combo> binds = NexusCommands.bindings(c);
                String label = binds.isEmpty() ? "" : binds.get(0).label() + (binds.size() > 1 ? " …" : "");
                int lw = s.font().getStringWidth(label);
                if (!label.isEmpty())
                    s.font().drawString(label, x + w - lw - 4, yy + 5, NexusTheme.WARN);
                s.font().drawString(s.font().trimStringToWidth(c.title, w - lw - 16), x + 4, yy + 5,
                        hov ? NexusTheme.TEXT_TITLE : NexusTheme.TEXT_LABEL);
                if (hov) {
                    s.hoverText = c.title + "\n" + c.desc + " · " + NexusCommands.label(binds);
                    s.hoverX = mx; s.hoverY = my;
                }
                hits.addClipped(x, yy, w, ROW, top, bottom, () -> NexusCommands.execute(s, c));
                yy += STEP;
            }
            yy += 4;
        }
        UiHits.endScissor();
        contentH = yy + scroll - top;
        UiHits.scrollbar(rx + s.panelW() - 4, top, viewH, contentH, scroll, NexusTheme.BORDER_SOFT);
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
