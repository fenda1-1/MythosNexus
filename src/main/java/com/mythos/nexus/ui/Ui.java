package com.mythos.nexus.ui;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;

/** Drawing helpers for the 淡橘日光 Nexus UI: rounded rects with real corner
 *  arcs, warm drop shadows, hairline peach borders. */
final class Ui {

    /** Filled rounded rect + soft drop shadow + 1px border (card). */
    static void card(int x, int y, int w, int h, int fill, int border) {
        card(x, y, w, h, fill, border, NexusTheme.RADIUS_CARD);
    }

    static void card(int x, int y, int w, int h, int fill, int border, int radius) {
        roundedRect(x + NexusTheme.SHADOW_OFFSET, y + NexusTheme.SHADOW_OFFSET,
                w, h, radius, NexusTheme.SHADOW);
        roundedRect(x, y, w, h, radius, border);
        int bw = 1;
        if (w - bw * 2 > 0 && h - bw * 2 > 0)
            roundedRect(x + bw, y + bw, w - bw * 2, h - bw * 2,
                    Math.max(0, radius - bw), fill);
    }

    /** Button face — rounded, 1px border, optional hover accent border. */
    static void button(int x, int y, int w, int h, boolean hovered, boolean pressed) {
        int bg = pressed ? NexusTheme.BTN_BG_PRESS
                : hovered ? NexusTheme.BTN_BG_HOV : NexusTheme.BTN_BG;
        int border = hovered ? NexusTheme.BTN_BORDER_HOV : NexusTheme.BTN_BORDER;
        roundedRect(x + 1, y + 2, w, h, NexusTheme.RADIUS_BTN, NexusTheme.SHADOW);
        roundedRect(x, y, w, h, NexusTheme.RADIUS_BTN, border);
        roundedRect(x + 1, y + 1, w - 2, h - 2, NexusTheme.RADIUS_BTN - 1, bg);
    }

    /** Input face — white rounded rect + peach border (stronger when focused). */
    static void inputFrame(int x, int y, int w, int h, boolean focused) {
        int border = focused ? NexusTheme.BORDER_ACCENT : NexusTheme.BORDER_SOFT;
        roundedRect(x, y, w, h, NexusTheme.RADIUS_INPUT, border);
        roundedRect(x + 1, y + 1, w - 2, h - 2, NexusTheme.RADIUS_INPUT - 1,
                NexusTheme.BG_INPUT);
    }

    static void roundedRect(int x, int y, int w, int h, int radius, int argb) {
        if (w <= 0 || h <= 0 || (argb >>> 24) == 0) return;
        boolean alphaOn = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        GlStateManager.disableAlpha();
        int r = Math.max(0, Math.min(Math.min(w, h) / 2, radius));
        if (r <= 1) {
            Gui.drawRect(x, y, x + w, y + h, argb);
            if (alphaOn) GlStateManager.enableAlpha();
            return;
        }
        // three rects cover the body; four triangle-fan quarter circles close the corners
        Gui.drawRect(x + r, y, x + w - r, y + r, argb);
        Gui.drawRect(x + r, y + h - r, x + w - r, y + h, argb);
        Gui.drawRect(x, y + r, x + w, y + h - r, argb);
        quarterCircle(x + r,         y + r,         r, 180, 270, argb);
        quarterCircle(x + w - r,     y + r,         r, 270, 360, argb);
        quarterCircle(x + w - r,     y + h - r,     r,   0,  90, argb);
        quarterCircle(x + r,         y + h - r,     r,  90, 180, argb);
        if (alphaOn) GlStateManager.enableAlpha();
    }

    private static void quarterCircle(int cx, int cy, int r, int fromDeg, int toDeg, int argb) {
        applyColor(argb);
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex2f(cx, cy);
        int segs = Math.max(4, r / 2);
        for (int i = 0; i <= segs; i++) {
            float deg = toDeg - (toDeg - fromDeg) * i / (float) segs;
            double rad = Math.toRadians(deg);
            GL11.glVertex2f(cx + (float) Math.cos(rad) * r,
                            cy + (float) Math.sin(rad) * r);
        }
        GL11.glEnd();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private static void applyColor(int argb) {
        float a = ((argb >> 24) & 255) / 255f;
        float r = ((argb >> 16) & 255) / 255f;
        float g = ((argb >>  8) & 255) / 255f;
        float b = ((argb      ) & 255) / 255f;
        GlStateManager.color(r, g, b, a);
    }

    static void fill(int x, int y, int w, int h, int argb) {
        Gui.drawRect(x, y, x + w, y + h, argb);
    }

    static void hover(int x, int y, int w, int h, boolean hovered) {
        if (hovered) fill(x, y, w, h, NexusTheme.HOVER_WASH);
    }

    static void divider(int x, int y, int w) {
        Gui.drawRect(x, y, x + w, y + 1, NexusTheme.DIVIDER);
    }

    static void vDivider(int x, int y, int h) {
        Gui.drawRect(x, y, x + 1, y + h, NexusTheme.DIVIDER);
    }

    /** Round status dot — filled circle approximated by the rounded-rect fan. */
    static void dot(int cx, int cy, int argb) {
        roundedRect(cx - 3, cy - 3, 6, 6, 3, argb);
    }

    static void enableBlend() {
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);
    }
}
