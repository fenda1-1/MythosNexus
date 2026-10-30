package com.mythos.nexus.ui.text;

import com.mythos.nexus.core.NexusPlugin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * Nexus 的高清字体：替换掉"原版 ascii.png 8 像素位图被按 zoom 比例缩放"的画法。
 *
 * <p>失真根因：原版字体是一张 8 像素高的位图，GUI scale 不是整数时（Nexus 的
 * Ctrl +/- 可以给到 55%、125% 这种），显卡只能对这张小图做双线性插值 —— 笔画
 * 必然发虚。MythosScript 的 DetachedSharpFontRenderer 是"按设备像素把整串文字
 * 重新栅格化成一张贴图"，这里做同样的事，但以 **单个字形** 为单位：
 *
 * <ol>
 *   <li><b>字符子集化</b> —— 只为界面里真正画到过的 (字符, 设备字号) 栅格化，
 *       扔进一张 1024×1024 的图集（{@link NexusGlyphAtlas}）；不再有整张字符表，
 *       汉字也只在你真正用到时才占一个格子。</li>
 *   <li><b>设备像素对齐</b> —— 每个字形的位置都 round 到整设备像素，格子纹理
 *       1:1 贴上去，所以 50% 和 300% 都一样锐利（缩放只影响栅格化时的字号）。</li>
 *   <li><b>渲染线程只碰 GL</b> —— AWT 字体在后台线程解析（16MB 的 CJK TTF 要
 *       几百毫秒），字形栅格化按需在绘制时做（每字几十微秒），不会出现
 *       "等字体卡死一帧"的情况。</li>
 * </ol>
 *
 * <p>任何一步失败都自动退回原版字体（{@link #fallback()}），界面不会因为字体挂掉。
 */
public final class NexusFont extends FontRenderer {

    /** 逻辑行高：虚拟像素里的字号（原版 FONT_HEIGHT = 9，字身约 9.6 才够得着原版大写字母的 7px）。 */
    private static final double LOGICAL_TEXT_H = 9.6D;
    /** 基线相对文字顶部的比例（0.85 × 字号）。 */
    private static final double BASE_RATIO = 0.85D;
    /** 设备字号的硬边界：太小 AWT 会拒绝，太大纯属浪费。 */
    private static final int MIN_DEV_SIZE = 4;
    private static final int MAX_DEV_SIZE = 200;

    /** § 颜色码对应的颜色表（与原版一致）。 */
    private static final int[] CODE_COLORS = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };

    // ---- 单例安装 -----------------------------------------------------------

    private static NexusFont shared;
    private static boolean installTried;

    /** 提前在后台把字体解析好（mod 初始化时调用），打开界面就没有等待感。 */
    public static void warmUpAsync() {
        try {
            NexusFontSource.warmUp();
        } catch (Throwable t) {
            NexusPlugin.LOGGER.warn("[nexus.font] 预热高清字体失败", t);
        }
    }

    /**
     * 给 Nexus 界面装上高清字体。返回值直接赋给 {@code GuiScreen.fontRenderer}：
     * 字体还没解析完也返回同一个实例（内部逐帧回退原版），这样已经建好的
     * GuiTextField 等控件不会锁死在旧字体上。安装失败返回原版字体。
     */
    public static FontRenderer install(Minecraft mc) {
        // 配置里关掉（"hdFont": false）就整条链路都不碰
        if (!com.mythos.nexus.core.NexusConfig.hdFont()) return mc.fontRenderer;
        if (!installTried) {
            installTried = true;
            try {
                shared = new NexusFont(mc);
                NexusFontSource.warmUp();
            } catch (Throwable t) {
                NexusPlugin.LOGGER.warn("[nexus.font] 初始化高清字体失败，回退原版字体", t);
                shared = null;
            }
        }
        return shared != null ? shared : mc.fontRenderer;
    }

    /** 每帧告诉字体"一个虚拟像素对应多少设备像素"，字形按这个比例栅格化。 */
    public static void setScale(double devicePixelsPerVirtualPixel) {
        NexusFont f = shared;
        if (f != null && devicePixelsPerVirtualPixel > 0.01D) {
            f.devScale = devicePixelsPerVirtualPixel;
        }
    }

    /** 释放图集纹理。 */
    public static void disposeTextures() {
        NexusFont f = shared;
        if (f != null) f.atlas.dispose();
    }

    // ---- 实例状态 -----------------------------------------------------------

    private final Minecraft mcRef;
    private final NexusGlyphAtlas atlas = new NexusGlyphAtlas();
    private final Map<Integer, SizeInfo> sizes = new HashMap<Integer, SizeInfo>();

    /** 设备像素 / 虚拟像素，由 {@link #setScale(double)} 每帧更新。 */
    private double devScale = 1.0D;

    private NexusFont(Minecraft mc) {
        // 走原版构造：只是为了拿到 FONT_HEIGHT 等字段，同时也是失败时的回退对象
        super(mc.gameSettings,
                new ResourceLocation("textures/font/ascii.png"),
                mc.getTextureManager(), false);
        this.mcRef = mc;
    }

    /**
     * 回退字体：每次现取 {@code Minecraft.fontRenderer}，不缓存 —— MythosScript 的
     * DetachedSwingWindowManager 会临时改写这个字段来还原字体，缓存会导致拿了不存在的实例。
     */
    private FontRenderer fallback() {
        FontRenderer fr = mcRef == null ? null : mcRef.fontRenderer;
        return fr == this ? null : fr;
    }

    private boolean hdReady() {
        return NexusFontSource.resolved != null;
    }

    // ---- 绘制 ---------------------------------------------------------------

    @Override
    public int drawString(String text, float x, float y, int color, boolean shadow) {
        if (text == null || text.isEmpty()) return (int) x;
        SizeInfo si = sizeInfo();
        if (si == null) {
            FontRenderer fb = fallback();
            return fb == null ? (int) x : fb.drawString(text, x, y, color, shadow);
        }
        drawHd(text, x, y, color, shadow, si);
        return (int) x;
    }

    @Override
    public int drawString(String text, int x, int y, int color) {
        return drawString(text, (float) x, (float) y, color, false);
    }

    @Override
    public int drawStringWithShadow(String text, float x, float y, int color) {
        return drawString(text, x, y, color, true);
    }

    private void drawHd(String text, float x, float y, int color, boolean shadow, SizeInfo si) {
        final double scale = devScale > 0.01D ? devScale : 1.0D;
        final int alpha0 = (color >>> 24) == 0 ? 255 : (color >>> 24);
        final int defaultRgb = color & 0xFFFFFF;
        final int shadowShift = Math.max(1, (int) Math.round(scale));
        final float a = alpha0 / 255.0F;

        // 先把这一串用到的字形全部栅格化好：栅格化会 glGenTextures / glTexSubImage2D，
        // 这些调用不能夹在下面 glBegin/glEnd 的批次中间（会产生 GL_INVALID_OPERATION）
        int prep = 0;
        while (prep < text.length()) {
            char pc = text.charAt(prep);
            if (pc == '\u00a7' && prep + 1 < text.length()) { prep += 2; continue; }
            prep++;
            glyph(pc, si);
        }

        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        // 抗锯齿边缘是半透明的，alpha test 会把它切成硬边（MythosScript 也这么处理）
        GlStateManager.disableAlpha();

        int curRgb = defaultRgb;
        boolean bold = false;
        double devX = Math.round(x * scale);
        double devY = Math.round(y * scale);
        boolean batchOpen = false;
        int boundTexture = -1;

        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\u00a7' && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                i += 2;
                int idx = "0123456789abcdef".indexOf(code);
                if (idx >= 0) curRgb = CODE_COLORS[idx];
                else if (code == 'r') { curRgb = defaultRgb; bold = false; }
                else if (code == 'l') bold = true;
                continue;
            }
            i++;

            Glyph g = glyph(c, si);
            if (g == null) continue;
            if (g.page < 0) {                     // 空白字符（空格 / 制表符）
                devX += g.advance;
                continue;
            }

            int tex = atlas.textureOf(g.page);
            if (!batchOpen || tex != boundTexture) {
                if (batchOpen) GL11.glEnd();
                GlStateManager.bindTexture(tex);
                boundTexture = tex;
                GL11.glBegin(GL11.GL_QUADS);
                batchOpen = true;
            }

            float gx = (float) (Math.round(devX) + g.xoff);
            float gy = (float) (Math.round(devY) + si.base + g.yoff);
            float r = ((curRgb >> 16) & 0xFF) / 255.0F;
            float gg = ((curRgb >> 8) & 0xFF) / 255.0F;
            float b = (curRgb & 0xFF) / 255.0F;

            if (shadow) {
                float sd = 0.25F;                 // 原版阴影：颜色取 25% 亮度
                emitQuad(gx + shadowShift, gy + shadowShift, g, r * sd, gg * sd, b * sd, a, scale);
                if (bold) emitQuad(gx + shadowShift + 1, gy + shadowShift, g, r * sd, gg * sd, b * sd, a, scale);
            }
            emitQuad(gx, gy, g, r, gg, b, a, scale);
            if (bold) emitQuad(gx + 1, gy, g, r, gg, b, a, scale);

            devX += g.advance;
        }
        if (batchOpen) GL11.glEnd();

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableAlpha();
    }

    /** 画一个格子：设备像素坐标进、虚拟坐标出，纹理按 texel 1:1 贴上去。 */
    private static void emitQuad(float devX, float devY, Glyph g,
                                 float r, float gg, float b, float a, double scale) {
        float x0 = (float) (devX / scale);
        float y0 = (float) (devY / scale);
        float x1 = (float) ((devX + g.w) / scale);
        float y1 = (float) ((devY + g.h) / scale);
        float u0 = g.u / (float) NexusGlyphAtlas.PAGE;
        float v0 = g.v / (float) NexusGlyphAtlas.PAGE;
        float u1 = (g.u + g.w) / (float) NexusGlyphAtlas.PAGE;
        float v1 = (g.v + g.h) / (float) NexusGlyphAtlas.PAGE;
        // 必须走 GlStateManager（它缓存了当前颜色），直接用 glColor4f 会让缓存失真，
        // 后续 Ui 画矩形时就会跳过着色、沿用我们留下的颜色
        GlStateManager.color(r, gg, b, a);
        GL11.glTexCoord2f(u0, v0);
        GL11.glVertex2f(x0, y0);
        GL11.glTexCoord2f(u0, v1);
        GL11.glVertex2f(x0, y1);
        GL11.glTexCoord2f(u1, v1);
        GL11.glVertex2f(x1, y1);
        GL11.glTexCoord2f(u1, v0);
        GL11.glVertex2f(x1, y0);
    }

    // ---- 度量 ---------------------------------------------------------------

    @Override
    public int getStringWidth(String text) {
        if (text == null || text.isEmpty()) return 0;
        SizeInfo si = sizeInfo();
        if (si == null) {
            FontRenderer fb = fallback();
            return fb == null ? 0 : fb.getStringWidth(text);
        }
        double sum = 0.0D;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\u00a7' && i + 1 < text.length()) { i += 2; continue; }
            i++;
            sum += advance(c, si);
        }
        return (int) Math.round(sum / (devScale > 0.01D ? devScale : 1.0D));
    }

    @Override
    public int getCharWidth(char character) {
        SizeInfo si = sizeInfo();
        if (si == null) {
            FontRenderer fb = fallback();
            return fb == null ? 0 : fb.getCharWidth(character);
        }
        return (int) Math.round(advance(character, si) / (devScale > 0.01D ? devScale : 1.0D));
    }

    /** 字符前进宽度（设备像素，可能带小数；绘制时按累计值取整对位）。 */
    private float advance(char c, SizeInfo si) {
        Float v = si.advances.get(c);
        if (v != null) return v;
        float a;
        try {
            GlyphVector gv = si.font.createGlyphVector(si.frc, String.valueOf(c));
            a = gv.getGlyphMetrics(0).getAdvanceX();
        } catch (Throwable t) {
            a = si.dev * 0.5F;
        }
        si.advances.put(c, a);
        return a;
    }

    /** 当前缩放比例对应的字号表；字体换了 / 字号变了就重建。 */
    private SizeInfo sizeInfo() {
        if (!hdReady()) return null;
        Font src = NexusFontSource.resolved;
        int dev = (int) Math.round(LOGICAL_TEXT_H * (devScale > 0.01D ? devScale : 1.0D));
        if (dev < MIN_DEV_SIZE) dev = MIN_DEV_SIZE;
        if (dev > MAX_DEV_SIZE) dev = MAX_DEV_SIZE;
        SizeInfo si = sizes.get(dev);
        if (si == null || si.source != src) {
            si = new SizeInfo(src, dev);
            sizes.put(dev, si);
        }
        return si;
    }

    // ---- 字形栅格化（字符子集化的入口） -------------------------------------

    private Glyph glyph(char c, SizeInfo si) {
        Glyph g = si.glyphs.get(c);
        if (g != null) return g;
        g = raster(c, si);
        si.glyphs.put(c, g);
        return g;
    }

    private Glyph raster(char c, SizeInfo si) {
        String s = String.valueOf(c);
        GlyphVector gv;
        try {
            gv = si.font.createGlyphVector(si.frc, s);
        } catch (Throwable t) {
            return Glyph.blank(si.dev * 0.5F);
        }
        float advance = gv.getGlyphMetrics(0).getAdvanceX();
        Rectangle2D ink = gv.getPixelBounds(si.frc, 0.0F, 0.0F);
        int w = (int) Math.ceil(ink.getWidth());
        int h = (int) Math.ceil(ink.getHeight());
        int xoff = (int) Math.floor(ink.getX());
        int yoff = (int) Math.floor(ink.getY());
        if (w <= 0 || h <= 0) return Glyph.blank(advance);

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                    RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            g.setColor(Color.WHITE);
            // 墨迹左上角对齐到整像素再栅格化，边界锯齿最少
            g.drawGlyphVector(gv, -xoff, -yoff);
        } catch (Throwable t) {
            return Glyph.blank(advance);
        } finally {
            g.dispose();
        }

        int[] slot = atlas.alloc(w, h);
        if (slot == null || slot[0] < 0) return Glyph.blank(advance);
        atlas.upload(slot, img);
        return new Glyph(slot[0], slot[1], slot[2], w, h, xoff, yoff, advance);
    }

    private static final class Glyph {
        final int page, u, v, w, h, xoff, yoff;
        final float advance;

        Glyph(int page, int u, int v, int w, int h, int xoff, int yoff, float advance) {
            this.page = page;
            this.u = u;
            this.v = v;
            this.w = w;
            this.h = h;
            this.xoff = xoff;
            this.yoff = yoff;
            this.advance = advance;
        }

        /** 没有墨迹（空格等）：只贡献前进宽度。 */
        static Glyph blank(float advance) {
            return new Glyph(-1, 0, 0, 0, 0, 0, 0, advance);
        }
    }

    /** 一个设备字号下的字体、基线和缓存。 */
    private static final class SizeInfo {
        final Font source;
        final Font font;
        final FontRenderContext frc;
        final int dev;
        final int base;
        final Map<Character, Float> advances = new HashMap<Character, Float>();
        final Map<Character, Glyph> glyphs = new HashMap<Character, Glyph>();

        SizeInfo(Font source, int dev) {
            this.source = source;
            this.dev = dev;
            this.font = source.deriveFont(Font.PLAIN, (float) dev);
            this.frc = new FontRenderContext(null,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                    RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            this.base = (int) Math.round(dev * BASE_RATIO);
        }
    }
}
