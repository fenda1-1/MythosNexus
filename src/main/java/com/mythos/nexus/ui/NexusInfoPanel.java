package com.mythos.nexus.ui;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.List;

/** Right-panel view INFO — support-the-author page (QR on top, info cards below). */
final class NexusInfoPanel extends NexusPanel {

    private static final String QR_PATH = "/assets/mythosnexus/sponsor.jpg";
    private static final int QR_MAX = 150;
    private static final int STEP = 18;

    private static final String[][] CARDS = {
        {"作者：fenda", "QQ 交流群：954821473\n欢迎加入，一起把项目做得更好。"},
        {"GitHub 开源 · 共同完善",
         "本项目已在 GitHub 开源。欢迎探索源码、分享想法，也欢迎进群提出建议，让下一次更新更贴近你的需要。"},
        {"加入交流群 · 抢先体验",
         "抢先测试  /  最新测试版优先在群内发布。\n"
         + "反馈直达  /  群内反馈的 Bug 优先跟进与修复。\n"
         + "交流答疑  /  使用中遇到问题，随时在群内提问。\n"
         + "经验分享  /  还有许多长期使用的资深群友，一起交流技巧、少走弯路。"},
        {"喜欢这个项目？支持随心",
         "项目免费使用，打赏完全自愿。如果它帮到了你，欢迎扫码支持 fenda 持续维护。"
         + "你的建议、Bug 反馈和经验分享，同样是珍贵的支持。谢谢每一位同行的你。"},
    };

    private static ResourceLocation qrTex;
    private static int qrW, qrH;
    private static boolean qrTried;

    private int scroll;
    private int contentH;

    NexusInfoPanel(NexusScreen s) { super(s); }

    private void ensureQr() {
        if (qrTried) return;
        qrTried = true;
        try (InputStream in = NexusInfoPanel.class.getResourceAsStream(QR_PATH)) {
            if (in == null) return;
            BufferedImage img = ImageIO.read(in);
            if (img == null) return;
            qrW = img.getWidth();
            qrH = img.getHeight();
            qrTex = s.mc().getTextureManager()
                    .getDynamicTextureLocation("mythosnexus_sponsor_qr", new DynamicTexture(img));
        } catch (Exception ignored) {
            qrTex = null;
        }
    }

    void draw(int rx, int mx, int my) {
        ensureQr();
        int pw = s.panelW();
        int ax = rx + NexusScreen.PAD;
        int bw = pw - NexusScreen.PAD * 2;
        int viewTop = 0, viewH = s.height;

        UiHits.scissor(s, rx, viewTop, pw, viewH);
        int y = 10 - scroll;

        s.font().drawString("支持作者", ax, y, NexusTheme.TEXT_TITLE);
        y += 12;
        for (String line : wrap("与 fenda 一起，让每一次更新都更好。", bw)) {
            s.font().drawString(line, ax, y, NexusTheme.TEXT_SUB);
            y += 10;
        }
        y += 6;

        // QR card — white frame so the code scans on dark themes
        int size = Math.min(QR_MAX, bw - 16);
        int qrH2 = qrTex != null && qrW > 0 ? size * qrH / qrW : size;
        int cardH = qrH2 + 30;
        Ui.card(ax, y, bw, cardH, NexusTheme.BG_CARD, NexusTheme.BORDER_SOFT);
        int fx = ax + (bw - size) / 2 - 3, fy = y + 6;
        Ui.roundedRect(fx - 1, fy - 1, size + 8, qrH2 + 8, 4, NexusTheme.ACCENT);
        Ui.roundedRect(fx, fy, size + 6, qrH2 + 6, 3, 0xFFFFFFFF);
        if (qrTex != null) {
            Ui.enableBlend();
            GlStateManager.color(1f, 1f, 1f, 1f);
            s.mc().getTextureManager().bindTexture(qrTex);
            Gui.drawScaledCustomSizeModalRect(fx + 3, fy + 3, 0, 0, qrW, qrH,
                    size, qrH2, qrW, qrH);
        } else {
            String miss = "二维码加载失败";
            s.font().drawString(miss, fx + 3 + (size - s.font().getStringWidth(miss)) / 2,
                    fy + 3 + qrH2 / 2 - 4, 0xFF888888);
        }
        String qt = "自愿打赏 · 感谢支持";
        s.font().drawString(qt, ax + (bw - s.font().getStringWidth(qt)) / 2,
                fy + qrH2 + 12, NexusTheme.TEXT_LABEL);
        y += cardH + 8;

        for (String[] c : CARDS) {
            List<String> body = new java.util.ArrayList<>();
            for (String para : c[1].split("\n")) body.addAll(wrap(para, bw - 12));
            int h = 8 + 12 + body.size() * 10 + 6;
            Ui.card(ax, y, bw, h, NexusTheme.BG_CARD, NexusTheme.BORDER_SOFT);
            Ui.fill(ax, y + 6, 2, 10, NexusTheme.ACCENT);
            s.font().drawString(c[0], ax + 6, y + 8, NexusTheme.TEXT_TITLE);
            int ly = y + 20;
            for (String line : body) {
                s.font().drawString(line, ax + 6, ly, NexusTheme.TEXT_SUB);
                ly += 10;
            }
            y += h + 6;
        }
        contentH = y + scroll - 10 + 4;
        UiHits.endScissor();

        clampScroll();
        if (contentH > viewH) UiHits.scrollbar(rx + pw - 4, viewTop, viewH, contentH, scroll,
                NexusTheme.TEXT_FAINT);
    }

    private List<String> wrap(String text, int w) {
        return s.font().listFormattedStringToWidth(text, Math.max(20, w));
    }

    private void clampScroll() {
        int max = Math.max(0, contentH - s.height);
        if (scroll > max) scroll = max;
        if (scroll < 0) scroll = 0;
    }

    @Override
    boolean scroll(int dWheel) {
        if (dWheel == 0) return false;
        scroll += dWheel > 0 ? -STEP : STEP;
        clampScroll();
        return true;
    }
}
