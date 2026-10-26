package com.mythos.nexus.ui;

import static com.mythos.nexus.ui.NexusGeometry.blend;

/**
 * Layout constants and the split-bar / column-width math for
 * {@link NexusScreen}. Methods take the screen because the mutable fields
 * (railW, midW, rightW, railCollapsed, draggingLeft, draggingRight,
 * hoverAnimLeft, hoverAnimRight) are read directly
 * by the panels via s.field and must stay on NexusScreen.
 */
final class NexusLayout {

    private NexusLayout() {}

    static final int RAIL_EXPANDED_W = 172;
    static final int RAIL_COLLAPSED_W = 48;
    static final int rightW_MIN = 240;
    static final int PAD = 18;
    static final int TOP_H = 52;
    static final int SPLIT_W = 7;
    static final int CARD_W = 200;
    static final int CARD_H = 70;
    static final int CARD_GAP = 6;
    static final int SCROLL_W = 6;
    static final int BATCH_H = 30;
    /** Width of the permanent icon strip on the far right. */
    static final int ICON_STRIP = 24;

    /**
     * Keep the three columns inside the window. At small GUI scales the fixed
     * minimums (rail + 200 center + 280 right) overflow, which made the right
     * panel draw over the center. Shrink the right panel first, then collapse
     * the rail, before recomputing the center width.
     */
    static void fitColumns(NexusScreen s) {
        int minMid = 160;
        if (s.rightCollapsed) s.rightW = ICON_STRIP;
        int room = s.width - SPLIT_W * 2 - minMid;
        if (s.railW + s.rightW > room) {
            s.rightW = Math.max(s.rightCollapsed ? ICON_STRIP : rightW_MIN,
                    room - s.railW);
        }
        if (s.railW + s.rightW > room) {
            s.railW = Math.max(RAIL_COLLAPSED_W, room - s.rightW);
            s.railCollapsed = s.railW <= RAIL_COLLAPSED_W;
        }
        s.midW = Math.max(80, s.width - s.railW - s.rightW - SPLIT_W * 2);
    }

    static boolean isHoverLeftSplit(NexusScreen s, int mx, int my) {
        return mx >= s.railW && mx < s.railW + SPLIT_W && my >= 0 && my < s.height;
    }

    static boolean isHoverRightSplit(NexusScreen s, int mx, int my) {
        // Collapsed = no panel to resize; the splitter rect would sit on top of
        // the icon strip and eat icon clicks as drags.
        if (s.rightCollapsed) return false;
        int rx = s.width - s.rightW;
        return mx >= rx - SPLIT_W && mx < rx && my >= 0 && my < s.height;
    }

    static void drawSplitLeft(NexusScreen s) {
        int w = 1 + (int) (s.hoverAnimLeft * 3);
        Ui.fill(s.railW + SPLIT_W / 2 - w / 2, 0, w, s.height,
                blend(NexusTheme.DIVIDER, NexusTheme.ACCENT, s.hoverAnimLeft));
    }

    static void drawSplitRight(NexusScreen s) {
        int rx = s.width - s.rightW;
        int w = 1 + (int) (s.hoverAnimRight * 3);
        Ui.fill(rx - SPLIT_W / 2 - w / 2, 0, w, s.height,
                blend(NexusTheme.DIVIDER, NexusTheme.ACCENT, s.hoverAnimRight));
    }
}
