package com.mythos.nexus.ui;

/** Static pixel icons for the Nexus UI — pure Ui.fill/dot compositions. */
final class NexusIcons {
    private NexusIcons() {}

    static void iconUser(int cx, int cy, int color) {
        Ui.dot(cx, cy - 4, color);
        Ui.fill(cx - 5, cy + 1, 10, 6, color);
    }
    static void iconFolder(int cx, int cy, int color) {
        Ui.fill(cx - 6, cy - 4, 12, 9, color);
        Ui.fill(cx - 6, cy - 5, 5, 2, color);
        Ui.fill(cx - 5, cy - 2, 10, 1, NexusTheme.BG_SIDEBAR);
    }
    static void iconChevron(int cx, int cy, boolean left, int color) {
        int d = left ? -1 : 1;
        Ui.fill(cx - 3 * d, cy - 4, 2, 8, color);
        Ui.fill(cx - d,     cy - 3, 2, 6, color);
        Ui.fill(cx + d,     cy - 2, 2, 4, color);
    }
    static void iconPlus(int cx, int cy, int color) {
        Ui.fill(cx - 5, cy - 1, 10, 2, color);
        Ui.fill(cx - 1, cy - 5, 2, 10, color);
    }
    /** Minus sign — horizontal bar. */
    static void iconMinus(int cx, int cy, int color) {
        Ui.fill(cx - 5, cy - 1, 10, 2, color);
    }
    /** Pause glyph — two vertical bars. */
    static void iconPause(int cx, int cy, int color) {
        Ui.fill(cx - 4, cy - 4, 3, 8, color);
        Ui.fill(cx + 1, cy - 4, 3, 8, color);
    }
    /** Play glyph — right-pointing triangle (staircase). */
    static void iconPlay(int cx, int cy, int color) {
        Ui.fill(cx - 3, cy - 4, 2, 8, color);
        Ui.fill(cx - 1, cy - 3, 2, 6, color);
        Ui.fill(cx + 1, cy - 2, 2, 4, color);
        Ui.fill(cx + 3, cy - 1, 1, 2, color);
    }
    static void iconWaterfall(int cx, int cy, int color) {
        Ui.fill(cx - 6, cy - 5, 3, 10, color);
        Ui.fill(cx - 1, cy - 5, 3, 6, color);
        Ui.fill(cx + 4, cy - 5, 3, 8, color);
    }
    static void iconPage(int cx, int cy, int color) {
        Ui.fill(cx - 6, cy - 5, 12, 2, color);
        Ui.fill(cx - 6, cy - 1, 12, 2, color);
        Ui.fill(cx - 6, cy + 3, 12, 2, color);
    }
    /** Live-viewport icon — an eye. */
    static void iconLive(int cx, int cy, int color) {
        Ui.fill(cx - 6, cy - 3, 12, 6, color);
        Ui.fill(cx - 5, cy - 4, 10, 8, color);
        Ui.fill(cx - 4, cy - 2, 8, 4, NexusTheme.BG_SIDEBAR);
        Ui.dot(cx, cy, NexusTheme.BG_CARD);
    }
    static void iconProxy(int cx, int cy, int color) {
        Ui.dot(cx, cy, color);
        Ui.fill(cx - 6, cy - 1, 12, 2, color);
    }
    static void iconDoc(int cx, int cy, int color) {
        Ui.fill(cx - 4, cy - 6, 8, 12, color);
        Ui.fill(cx - 3, cy - 4, 6, 1, NexusTheme.BG_SIDEBAR);
        Ui.fill(cx - 3, cy - 1, 6, 1, NexusTheme.BG_SIDEBAR);
        Ui.fill(cx - 3, cy + 2, 6, 1, NexusTheme.BG_SIDEBAR);
    }
    /** Gear icon — three spokes from center. */
    static void iconSettings(int cx, int cy, int color) {
        Ui.fill(cx - 1, cy - 6, 2, 4, color);   // top spoke
        Ui.fill(cx - 5, cy + 2, 4, 2, color);   // left spoke
        Ui.fill(cx + 2, cy + 2, 4, 2, color);   // right spoke
        Ui.dot(cx, cy, color);                    // center
    }

    /** Gauge icon — arc + needle pointing up-right. */
    static void iconTickRate(int cx, int cy, int color) {
        // arc: three dots along top
        Ui.dot(cx - 5, cy - 2, color);
        Ui.dot(cx,     cy - 5, color);
        Ui.dot(cx + 5, cy - 2, color);
        // needle: diagonal line to center
        for (int i = 1; i <= 4; i++) Ui.fill(cx + i, cy - i, 1, 1, color);
        Ui.fill(cx - 4, cy + 2, 8, 2, color);
    }

    /** Density icon — 3 horizontal stacked bars, spacing varies by density. */
    static void iconDensity(int cx, int cy, int color, String density) {
        int gap = "COMPACT".equals(density) ? 2 : "DETAILED".equals(density) ? 4 : 3;
        for (int i = -1; i <= 1; i++) {
            Ui.fill(cx - 6, cy + i * gap - 1, 12, 2, color);
        }
    }

    /** "More" icon — three horizontal dots. */
    static void iconMore(int cx, int cy, int color) {
        Ui.dot(cx - 4, cy, color);
        Ui.dot(cx,     cy, color);
        Ui.dot(cx + 4, cy, color);
    }

    /** Pencil/edit icon. */
    static void iconEdit(int cx, int cy, int color) {
        for (int i = 0; i < 7; i++) Ui.fill(cx - 5 + i, cy + 4 - i, 2, 2, color);
        Ui.fill(cx - 6, cy + 6, 4, 2, color);   // tip line
    }

    /** Document/log icon. */
    static void iconLog(int cx, int cy, int color) {
        Ui.fill(cx - 4, cy - 6, 7, 1, color);
        Ui.fill(cx - 4, cy + 5, 7, 1, color);
        Ui.fill(cx - 4, cy - 6, 1, 12, color);
        Ui.fill(cx + 2, cy - 6, 1, 12, color);
        Ui.fill(cx + 2, cy - 5, 1, 1, color);
        Ui.fill(cx - 2, cy - 2, 4, 1, color);
        Ui.fill(cx - 2, cy + 1, 4, 1, color);
    }


    /** Pulse/heartbeat icon for the status panel. */
    static void iconStatus(int cx, int cy, int color) {
        Ui.fill(cx - 7, cy - 1, 3, 2, color);
        Ui.fill(cx - 4, cy - 3, 2, 6, color);
        Ui.fill(cx - 2, cy - 1, 2, 2, color);
        Ui.fill(cx,     cy - 5, 2, 10, color);
        Ui.fill(cx + 2, cy - 1, 2, 2, color);
        Ui.fill(cx + 4, cy - 1, 3, 2, color);
    }

    /** Palette/theme icon — overlapping color swatches. */
    static void iconTheme(int cx, int cy, int color) {
        // 4 small squares in 2x2 grid — reads as a palette
        Ui.fill(cx - 5, cy - 5, 4, 4, color);
        Ui.fill(cx + 1, cy - 5, 4, 4, color);
        Ui.fill(cx - 5, cy + 1, 4, 4, color);
        Ui.fill(cx + 1, cy + 1, 4, 4, color);
    }

    /** Script icon — play triangle inside a frame. */
    static void iconScript(int cx, int cy, int color) {
        Ui.fill(cx - 6, cy - 6, 12, 1, color);
        Ui.fill(cx - 6, cy + 5, 12, 1, color);
        Ui.fill(cx - 6, cy - 6, 1, 12, color);
        Ui.fill(cx + 5, cy - 6, 1, 12, color);
        Ui.fill(cx - 2, cy - 3, 1, 6, color);
        Ui.fill(cx - 1, cy - 2, 1, 4, color);
        Ui.fill(cx,     cy - 1, 1, 2, color);
    }

    /** Command icon — terminal prompt "&gt;_". */
    static void iconCommand(int cx, int cy, int color) {
        Ui.fill(cx - 6, cy - 4, 2, 2, color);
        Ui.fill(cx - 4, cy - 2, 2, 2, color);
        Ui.fill(cx - 2, cy,     2, 2, color);
        Ui.fill(cx - 4, cy + 2, 2, 2, color);
        Ui.fill(cx - 6, cy + 4, 2, 2, color);
        Ui.fill(cx + 1, cy + 4, 5, 2, color);
    }

    /** Keyboard icon — key caps inside a frame. */
    static void iconKeyboard(int cx, int cy, int color) {
        Ui.fill(cx - 7, cy - 4, 14, 1, color);
        Ui.fill(cx - 7, cy + 4, 14, 1, color);
        Ui.fill(cx - 7, cy - 4, 1, 9, color);
        Ui.fill(cx + 6, cy - 4, 1, 9, color);
        for (int i = 0; i < 4; i++) Ui.fill(cx - 5 + i * 3, cy - 2, 2, 1, color);
        for (int i = 0; i < 3; i++) Ui.fill(cx - 4 + i * 3, cy, 2, 1, color);
        Ui.fill(cx - 3, cy + 2, 6, 1, color);
    }

    /** Reconnect icon — a ring with a gap at the top-right and an arrow head
     *  closing it, i.e. the usual "reload" glyph. */
    static void iconReconnect(int cx, int cy, int color) {
        Ui.fill(cx - 5, cy - 5, 8, 1, color);    // ring top
        Ui.fill(cx - 6, cy - 4, 1, 9, color);    // ring left
        Ui.fill(cx - 5, cy + 5, 10, 1, color);   // ring bottom
        Ui.fill(cx + 5, cy - 1, 1, 6, color);    // ring right (below the gap)
        // arrow head at the gap, pointing down-right into the ring
        Ui.fill(cx + 1, cy - 6, 1, 3, color);
        Ui.fill(cx + 2, cy - 5, 2, 1, color);
        Ui.fill(cx + 2, cy - 3, 2, 1, color);
    }

    /** Sync icon — two figures standing shoulder to shoulder under a shared bar. */
    static void iconSync(int cx, int cy, int color) {
        Ui.fill(cx - 6, cy - 7, 12, 2, color);   // shared link bar
        // left figure
        Ui.fill(cx - 5, cy - 4, 3, 3, color);    // head
        Ui.fill(cx - 5, cy, 3, 5, color);        // body
        // right figure
        Ui.fill(cx + 2, cy - 4, 3, 3, color);
        Ui.fill(cx + 2, cy, 3, 5, color);
        // the tie between them
        Ui.fill(cx - 1, cy - 2, 2, 1, color);
        Ui.fill(cx - 1, cy + 1, 2, 1, color);
    }

    /** Info icon — "i" inside a ring. */    static void iconInfo(int cx, int cy, int color) {
        Ui.fill(cx - 3, cy - 7, 6, 1, color);
        Ui.fill(cx - 3, cy + 6, 6, 1, color);
        Ui.fill(cx - 7, cy - 3, 1, 6, color);
        Ui.fill(cx + 6, cy - 3, 1, 6, color);
        Ui.fill(cx - 5, cy - 6, 2, 1, color);
        Ui.fill(cx + 3, cy - 6, 2, 1, color);
        Ui.fill(cx - 5, cy + 5, 2, 1, color);
        Ui.fill(cx + 3, cy + 5, 2, 1, color);
        Ui.fill(cx - 6, cy - 5, 1, 2, color);
        Ui.fill(cx + 5, cy - 5, 1, 2, color);
        Ui.fill(cx - 6, cy + 3, 1, 2, color);
        Ui.fill(cx + 5, cy + 3, 1, 2, color);
        Ui.fill(cx - 1, cy - 4, 2, 2, color);
        Ui.fill(cx - 1, cy - 1, 2, 5, color);
    }

    static void drawCloseX(int cx, int cy, int r, int color) {
        // Draw an X using two diagonal lines
        for (int i = -r; i <= r; i++) {
            Ui.fill(cx + i, cy + i, 1, 1, color);
            Ui.fill(cx + i, cy - i, 1, 1, color);
        }
    }
}
