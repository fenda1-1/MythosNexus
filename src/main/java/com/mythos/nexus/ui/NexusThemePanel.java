package com.mythos.nexus.ui;

import static com.mythos.nexus.ui.NexusGeometry.hit;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Right panel for THEME view.
 *
 * Layout (top → bottom, scrollable):
 *   header "主题" + [新建] [导入] [AI]
 *   ---- 预设主题 (scrollable card list) ----
 *   built-in + custom preset cards, each showing 3 swatches + name
 *   custom cards get an extra "✕" delete corner
 *   ---- 当前主题 (color swatch list) ----
 *   26 rows; click row → RGB slider editor
 *   ---- editor (when editing != null) ----
 *   name field, 4 channel sliders, preview, [保存] [取消]
 *
 * New/import/AI flows share the editor: a fresh palette pre-fills the sliders,
 * the user tweaks then hits 保存 — that lands it in CUSTOM + config.
 */
final class NexusThemePanel extends NexusPanel {

    /** Editor state — non-null while the RGB slider editor is open. */
    private String editingKey;          // COLOR_KEYS entry being tweaked
    private String editingThemeId;      // id for save (null = new theme)
    private String editingThemeName;    // name shown in the editor header

    /** Row-level scroll for the preset card list. */
    private int presetScroll;
    /** Scrollbar drag state for preset list. */
    private boolean presetDragging;
    private int presetDragStartY;
    private int presetDragStartScroll;

    NexusThemePanel(NexusScreen s) { super(s); }


    void draw(int rx, int mx, int my) {
        int pw = s.panelW();
        int bw = pw - NexusScreen.PAD * 2;
        int ay = 10;

        s.font().drawString("主题", rx + NexusScreen.PAD, ay + 4, NexusTheme.TEXT_TITLE);
        int bx = rx + NexusScreen.PAD + bw - 22;
        drawHeaderBtn(bx, ay, 20, "＋", "新建", mx, my); bx -= 46;
        drawHeaderBtn(bx, ay, 42, "导入", null, mx, my); bx -= 50;
        drawHeaderBtn(bx, ay, 46, "AI 生成", null, mx, my);
        ay += 30;
        Ui.divider(rx + NexusScreen.PAD, ay, bw); ay += 8;

        // preset card list — fixed 3 visible, scroll for more (scrollbar or wheel)
        java.util.List<NexusTheme.Preset> themes = NexusTheme.all();
        int cardH = 52, gap = 6, visible = 3;
        if (visible < 1) visible = 1;
        int maxScroll = Math.max(0, themes.size() - visible);
        if (presetScroll > maxScroll) presetScroll = maxScroll;
        if (presetScroll < 0) presetScroll = 0;

        int listY = ay;
        for (int i = presetScroll; i < themes.size() && i < presetScroll + visible; i++) {
            NexusTheme.Preset p = themes.get(i);
            int cy = listY + (i - presetScroll) * (cardH + gap);
            boolean hov = hit(mx, my, rx + NexusScreen.PAD, cy, bw - 14, cardH);
            boolean isCustom = NexusTheme.CUSTOM.contains(p);
            Ui.card(rx + NexusScreen.PAD, cy, bw - 14, cardH,
                    hov ? NexusTheme.BG_CARD_HOV : swatch(p, "BG_CARD"),
                    NexusTheme.BORDER_SOFT);
            Ui.fill(rx + NexusScreen.PAD + 8, cy + 8, 16, 12, swatch(p, "BG_CARD"));
            Ui.fill(rx + NexusScreen.PAD + 8, cy + 22, 16, 12, swatch(p, "ACCENT"));
            Ui.fill(rx + NexusScreen.PAD + 8, cy + 36, 16, 12, swatch(p, "TEXT_TITLE"));
            s.font().drawString(p.name,
                    rx + NexusScreen.PAD + 32, cy + 20, swatch(p, "TEXT_TITLE"));
            if (isCustom) {
                int dx = rx + NexusScreen.PAD + bw - 14 - 14;
                boolean dhov = hit(mx, my, dx, cy + 4, 12, 12);
                s.font().drawString("✕", dx + 2, cy + 6,
                        dhov ? NexusTheme.ERR : NexusTheme.TEXT_FAINT);
            }
            // "使用中" tag for the active theme
            if (p.id.equals(com.mythos.nexus.core.NexusConfig.themePresetId())) {
                int tagW = s.font().getStringWidth("使用中");
                s.font().drawString("使用中",
                        rx + NexusScreen.PAD + bw - 14 - tagW - 4, cy + cardH - 12,
                        NexusTheme.ACCENT);
            }
        }
        if (themes.size() > visible) {
            int trackY = listY, trackH = visible * (cardH + gap) - gap;
            int thumbH = Math.max(16, trackH * visible / themes.size());
            int thumbY = trackY + (trackH - thumbH) * presetScroll / Math.max(1, maxScroll);
            int sbx = rx + NexusScreen.PAD + bw - 10;
            Ui.fill(sbx, trackY, 4, trackH, NexusTheme.BG_CARD);
            Ui.fill(sbx, thumbY, 4, thumbH,
                    presetDragging ? NexusTheme.ACCENT : NexusTheme.BORDER_SOFT);
        }
        ay += visible * (cardH + gap) + 6;
        Ui.divider(rx + NexusScreen.PAD, ay, bw); ay += 8;

        if (editingKey == null && editingThemeId == null) {
            s.font().drawString("点击色卡编辑", rx + NexusScreen.PAD, ay, NexusTheme.TEXT_SUB);
            ay += 14;
            for (String k : NexusTheme.COLOR_KEYS) {
                int rowH = 16;
                if (ay + rowH > s.height - 20) break;
                boolean hov = hit(mx, my, rx + NexusScreen.PAD, ay, bw - 14, rowH);
                if (hov) Ui.roundedRect(rx + NexusScreen.PAD, ay, bw - 14, rowH, 3,
                        NexusTheme.HOVER_WASH);
                Ui.fill(rx + NexusScreen.PAD + 4, ay + 2, 14, 12, NexusTheme.get(k));
                s.font().drawString(NexusTheme.labelOf(k),
                        rx + NexusScreen.PAD + 24, ay + 4, NexusTheme.TEXT_LABEL);
                String hex = String.format("#%06X", NexusTheme.get(k) & 0xFFFFFF);
                s.font().drawString(hex, rx + NexusScreen.PAD + bw - 14 - 44, ay + 4,
                        NexusTheme.TEXT_FAINT);
                ay += rowH;
            }
            return;
        }

        drawEditor(rx, mx, my, ay, bw);
    }

    private void drawHeaderBtn(int bx, int by, int bw, String label, String alt,
                               int mx, int my) {
        boolean hov = hit(mx, my, bx, by, bw, 20);
        Ui.button(bx, by, bw, 20, hov, false);
        int tw = s.font().getStringWidth(label);
        s.font().drawString(label, bx + bw / 2 - tw / 2, by + 6,
                hov ? NexusTheme.TEXT_TITLE : NexusTheme.TEXT_LABEL);
        if (alt != null && hov) {
            s.toast(alt, 2000);
            // Anchor the tooltip left of the button so it can't run off the
            // right edge — the header +/导入/AI buttons sit near the icon strip.
            s.hoverX = Math.max(4, bx - 10);
            s.hoverY = by;
        }
    }

    private int swatch(NexusTheme.Preset p, String key) {
        Integer v = p.colors.get(key);
        return v != null ? v : 0xFF000000;
    }

    private void drawEditor(int rx, int mx, int my, int ay, int bw) {
        // Header: theme name + back
        s.font().drawString("← 返回", rx + NexusScreen.PAD, ay, NexusTheme.ACCENT);
        String nm = editingThemeName == null ? "" : editingThemeName;
        int nmW = Math.min(s.font().getStringWidth(nm), bw - 60);
        s.font().drawString(nm, rx + NexusScreen.PAD + bw - nmW, ay, NexusTheme.TEXT_SUB);
        ay += 16;

        if (editingThemeId != null) {
            // New-theme / imported theme editor — name + all colors + save/cancel
            s.font().drawString("点色卡改色 · 填完点保存", rx + NexusScreen.PAD, ay,
                    NexusTheme.TEXT_FAINT);
            ay += 14;
            // preview swatch uses BG_CARD as backdrop
            Ui.fill(rx + NexusScreen.PAD, ay, bw - 14, 28, NexusTheme.BG_CARD);
            s.font().drawString(nm.isEmpty() ? "新主题" : nm,
                    rx + NexusScreen.PAD + 8, ay + 10, NexusTheme.TEXT_TITLE);
            ay += 36;
            // Save / Cancel pinned right under the header — always visible
            // before the (scrollable) color grid, so users never hunt for it.
            int btnW = 60;
            boolean hov = hit(mx, my, rx + NexusScreen.PAD, ay, btnW, 22);
            Ui.button(rx + NexusScreen.PAD, ay, btnW, 22, hov, false);
            s.font().drawString("保存", rx + NexusScreen.PAD + btnW / 2 - 10, ay + 7,
                    NexusTheme.TEXT_TITLE);
            boolean hov2 = hit(mx, my, rx + NexusScreen.PAD + btnW + 8, ay, btnW, 22);
            Ui.button(rx + NexusScreen.PAD + btnW + 8, ay, btnW, 22, hov2, false);
            s.font().drawString("取消", rx + NexusScreen.PAD + btnW + 8 + btnW / 2 - 10, ay + 7,
                    NexusTheme.TEXT_LABEL);
            ay += 30;
            // compact color grid — 2 columns of color keys
            int colW = (bw - 14) / 2 - 4;
            int rowH = 15;
            for (int i = 0; i < NexusTheme.COLOR_KEYS.length; i++) {
                String k = NexusTheme.COLOR_KEYS[i];
                int col = i % 2, row = i / 2;
                int cx = rx + NexusScreen.PAD + col * (colW + 8);
                int cy = ay + row * rowH;
                if (cy + rowH > s.height - 60) break;
                boolean hov3 = hit(mx, my, cx, cy, colW, rowH);
                if (hov3) Ui.roundedRect(cx, cy, colW, rowH, 3, NexusTheme.HOVER_WASH);
                Ui.fill(cx + 2, cy + 2, 12, 11, NexusTheme.get(k));
                s.font().drawString(NexusTheme.labelOf(k), cx + 18, cy + 4,
                        NexusTheme.TEXT_LABEL);
                if (editingKey != null && editingKey.equals(k)) {
                    Ui.roundedRect(cx, cy, colW, rowH, 3, NexusTheme.ACCENT);
                }
            }
            ay += 14 * rowH + 6;
            // if editingKey set → slider block for that key
            if (editingKey != null) {
                Ui.divider(rx + NexusScreen.PAD, ay, bw - 14); ay += 6;
                s.font().drawString(NexusTheme.labelOf(editingKey),
                        rx + NexusScreen.PAD, ay, NexusTheme.TEXT_TITLE);
                ay += 14;
                ay = drawSliders(rx, ay, bw - 14, editingKey, mx, my);
            }
            return;
        }

        s.font().drawString(NexusTheme.labelOf(editingKey),
                rx + NexusScreen.PAD, ay, NexusTheme.TEXT_TITLE);
        ay += 16;
        Ui.fill(rx + NexusScreen.PAD, ay, bw - 14, 34, NexusTheme.get(editingKey));
        ay += 42;
        ay = drawSliders(rx, ay, bw - 14, editingKey, mx, my);
        String hex = String.format("#%08X", NexusTheme.get(editingKey));
        s.font().drawString(hex, rx + NexusScreen.PAD, ay + 4, NexusTheme.TEXT_TITLE);
        s.font().drawString("改完点返回自动存为自定义", rx + NexusScreen.PAD + 60, ay + 4,
                NexusTheme.TEXT_FAINT);
    }

    /** Draw the 4 ARGB sliders for the given key; returns the new ay below. */
    private int drawSliders(int rx, int ay, int bw, String key, int mx, int my) {
        int color = NexusTheme.get(key);
        String[] chans = {"A","R","G","B"};
        for (int c = 0; c < 4; c++) {
            int v = (color >> (24 - c * 8)) & 0xFF;
            s.font().drawString(chans[c], rx + NexusScreen.PAD, ay + 4, NexusTheme.TEXT_SUB);
            int barX = rx + NexusScreen.PAD + 18;
            int barW = bw - 18 - 36;
            Ui.fill(barX, ay + 6, barW, 6, NexusTheme.BG_INPUT);
            int fill = (int)(barW * v / 255f);
            Ui.fill(barX, ay + 6, fill, 6, NexusTheme.ACCENT);
            Ui.roundedRect(barX + fill - 3, ay + 2, 6, 14, 3, NexusTheme.BORDER_ACCENT);
            s.font().drawString(String.valueOf(v), barX + barW + 6, ay + 4,
                    NexusTheme.TEXT_LABEL);
            ay += 20;
        }
        return ay;
    }


    boolean click(int mx, int my, int btn) {
        int rx = s.width - s.rightW;
        int pw = s.panelW();
        int bw = pw - NexusScreen.PAD * 2;
        int ay = 10;

        int bx = rx + NexusScreen.PAD + bw - 22;
        if (hit(mx, my, bx, ay, 20, 20)) { onNewTheme(); return true; }
        bx -= 46;
        if (hit(mx, my, bx, ay, 42, 20)) { onImport(); return true; }
        bx -= 50;
        if (hit(mx, my, bx, ay, 46, 20)) { onAiPrompt(); return true; }
        ay += 38;   // header + divider

        // preset list — fixed 3 visible, scroll for more
        java.util.List<NexusTheme.Preset> themes = NexusTheme.all();
        int cardH = 52, gap = 6, visible = 3;
        int maxScroll = Math.max(0, themes.size() - visible);
        int listY = ay;
        if (themes.size() > visible) {
            int trackH = visible * (cardH + gap) - gap;
            int thumbH = Math.max(16, trackH * visible / themes.size());
            int thumbY = listY + (trackH - thumbH) * presetScroll / Math.max(1, maxScroll);
            int sbx = rx + NexusScreen.PAD + bw - 10;
            if (hit(mx, my, sbx - 2, listY, 8, trackH)) {
                presetDragging = true;
                presetDragStartY = my;
                presetDragStartScroll = presetScroll;
                return true;
            }
        }
        for (int i = presetScroll; i < themes.size() && i < presetScroll + visible; i++) {
            int cy = listY + (i - presetScroll) * (cardH + gap);
            NexusTheme.Preset p = themes.get(i);
            if (!hit(mx, my, rx + NexusScreen.PAD, cy, bw - 14, cardH)) continue;
            boolean isCustom = NexusTheme.CUSTOM.contains(p);
            if (isCustom && hit(mx, my,
                    rx + NexusScreen.PAD + bw - 14 - 14, cy + 4, 12, 12)) {
                com.mythos.nexus.core.NexusConfig.removeCustomTheme(p.id);
                applySavedThemeRefresh();
                return true;
            }
            NexusTheme.apply(p);
            com.mythos.nexus.core.NexusConfig.setThemePresetId(p.id);
            com.mythos.nexus.core.NexusConfig.setThemeCustomColors(p.colors);
            editingKey = null; editingThemeId = null;
            return true;
        }
        ay += visible * (cardH + gap) + 14;

        if (editingKey == null && editingThemeId == null) {
            ay += 14;
            int rowH = 16;
            for (String k : NexusTheme.COLOR_KEYS) {
                if (ay + rowH > s.height - 20) break;
                if (hit(mx, my, rx + NexusScreen.PAD, ay, bw - 14, rowH)) {
                    editingKey = k; editingThemeId = null; return true;
                }
                ay += rowH;
            }
            return false;
        }

        if (hit(mx, my, rx + NexusScreen.PAD, ay, 36, 14)) {
            if (editingThemeId != null) {
                // cancel new-theme edit — discard
                editingKey = null; editingThemeId = null; editingThemeName = null;
            } else {
                // leaving single-key edit → save as custom
                persistAsCustom();
                editingKey = null;
            }
            return true;
        }
        ay += 16;

        if (editingThemeId != null) {
            ay += 14 + 36;
            // Save / Cancel pinned before the grid — click must hit the same
            // y-position as drawEditor emits.
            int btnW = 60;
            if (hit(mx, my, rx + NexusScreen.PAD, ay, btnW, 22)) {
                saveThemeAsNew();
                return true;
            }
            if (hit(mx, my, rx + NexusScreen.PAD + btnW + 8, ay, btnW, 22)) {
                editingKey = null; editingThemeId = null; editingThemeName = null;
                com.mythos.nexus.core.NexusConfig.applySavedTheme();
                return true;
            }
            ay += 30;
            int colW = (bw - 14) / 2 - 4;
            int rowH = 15;
            for (int i = 0; i < NexusTheme.COLOR_KEYS.length; i++) {
                int col = i % 2, row = i / 2;
                int cx = rx + NexusScreen.PAD + col * (colW + 8);
                int cy = ay + row * rowH;
                if (cy + rowH > s.height - 60) break;
                if (hit(mx, my, cx, cy, colW, rowH)) {
                    editingKey = NexusTheme.COLOR_KEYS[i];
                    return true;
                }
            }
            ay += 14 * rowH + 6;
            if (editingKey != null) {
                ay += 6 + 14;
                // slider clicks — handled in single-key path below
            }
        }

        // single-key / theme editor sliders
        if (editingKey != null) {
            // skip ahead to slider rows — approximate using same offsets as draw
            // (draw path computed them; here we recompute the same way)
            ay = 10 + 38 + visible * (cardH + gap) + 14 + 16; // header + preset + div + back
            if (editingThemeId != null) ay += 14 + 36 + 30 + 14 * 15 + 6 + 6 + 14;
            else ay += 16 + 42;
            for (int c = 0; c < 4; c++) {
                int barX = rx + NexusScreen.PAD + 18;
                int barW = (pw - NexusScreen.PAD * 2) - 14 - 18 - 36;
                if (hit(mx, my, barX, ay + 2, barW, 14)) {
                    int v = Math.max(0, Math.min(255, (int)((mx - barX) * 255.0 / barW)));
                    int color = NexusTheme.get(editingKey);
                    int shift = 24 - c * 8;
                    int mask = ~(0xFF << shift);
                    NexusTheme.set(editingKey, (color & mask) | (v << shift));
                    return true;
                }
                ay += 20;
            }
        }
        return false;
    }


    /** "新建" — start a fresh theme built on the current palette. */
    private void onNewTheme() {
        editingThemeId = "custom_" + System.currentTimeMillis();
        editingThemeName = "新主题";
        editingKey = null;
    }

    /** "导入" — read clipboard, parse strict JSON, load as editingTheme. */
    private void onImport() {
        CLIPBOARD.execute(() -> {
            final String json;
            try {
                Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
                json = (String) cb.getData(DataFlavor.stringFlavor);
            } catch (Throwable e) {
                onClient(() -> s.toast("剪贴板读取失败", 2000));
                return;
            }
            onClient(() -> {
                NexusTheme.Preset p = NexusTheme.parseThemeJson(json);
                if (p == null) { s.toast("剪贴板内容不是有效主题 JSON", 2000); return; }
                // Load into live fields so the editor shows it immediately
                NexusTheme.apply(p);
                editingThemeId = p.id;
                editingThemeName = p.name;
                editingKey = null;
            });
        });
    }

    /** Clipboard work runs on Nexus-Clipboard, never on the client thread:
     *  Windows blocks getSystemClipboard() while another process holds the
     *  clipboard open, and a blocked render thread is an unrecoverable freeze
     *  (Minecraft keeps ticking nothing). Results hop back with
     *  addScheduledTask, which is drained on the client thread each frame. */
    private static final java.util.concurrent.ExecutorService CLIPBOARD =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Nexus-Clipboard");
                t.setDaemon(true);
                return t;
            });

    private static void onClient(Runnable r) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        if (mc == null) return;
        mc.addScheduledTask(r);
    }

    /** "AI 生成" — copy the strict prompt to clipboard for the user to paste
     *  into their AI; the AI's JSON answer is then imported via 导入. */
    private void onAiPrompt() {
        CLIPBOARD.execute(() -> {
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(NexusTheme.AI_FORMAT_PROMPT), null);
                onClient(() -> s.toast("提示词已复制 — 贴给 AI 后用 [导入] 读回结果", 2500));
            } catch (Throwable e) {
                onClient(() -> s.toast("复制失败", 2000));
            }
        });
    }

    /** Persist current palette as the new/edited custom theme. */
    private void saveThemeAsNew() {
        Map<String,Integer> snap = new HashMap<>();
        for (String k : NexusTheme.COLOR_KEYS) snap.put(k, NexusTheme.get(k));
        String name = editingThemeName == null || editingThemeName.isEmpty()
                ? "自定义" : editingThemeName;
        com.mythos.nexus.core.NexusConfig.addCustomTheme(editingThemeId, name, snap);
        applySavedThemeRefresh();
        editingKey = null; editingThemeId = null; editingThemeName = null;
    }

    /** Save current live palette as a "custom" theme (presetId=""). */
    private void persistAsCustom() {
        com.mythos.nexus.core.NexusConfig.setThemePresetId("");
        Map<String,Integer> snap = new HashMap<>();
        for (String k : NexusTheme.COLOR_KEYS) snap.put(k, NexusTheme.get(k));
        com.mythos.nexus.core.NexusConfig.setThemeCustomColors(snap);
    }

    private void applySavedThemeRefresh() {
        com.mythos.nexus.core.NexusConfig.applySavedTheme();
    }


    boolean scroll(int d) {
        if (editingKey != null || editingThemeId != null) return false;
        presetScroll -= Integer.signum(d);   // one notch = one row
        if (presetScroll < 0) presetScroll = 0;
        return true;
    }

    boolean drag(int mx, int my) {
        if (!presetDragging) return false;
        java.util.List<NexusTheme.Preset> themes = NexusTheme.all();
        int pw = s.panelW();
        int bw = pw - NexusScreen.PAD * 2;
        int ay = 10 + 38;
        int cardH = 52, gap = 6;
        int visible = (s.height - ay - 220) / (cardH + gap);
        if (visible < 1) visible = 1;
        int maxScroll = Math.max(0, themes.size() - visible);
        int trackH = visible * (cardH + gap) - gap;
        int thumbH = Math.max(16, trackH * visible / themes.size());
        int movable = trackH - thumbH;
        if (movable > 0) {
            int delta = my - presetDragStartY;
            presetScroll = presetDragStartScroll
                    + delta * maxScroll / movable;
            if (presetScroll < 0) presetScroll = 0;
            if (presetScroll > maxScroll) presetScroll = maxScroll;
        }
        return true;
    }

    void mouseReleased() { presetDragging = false; }
}
