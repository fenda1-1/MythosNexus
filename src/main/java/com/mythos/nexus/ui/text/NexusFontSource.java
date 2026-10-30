package com.mythos.nexus.ui.text;

import com.mythos.nexus.core.NexusPlugin;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 高清字体的来源 —— 只回答"字体从哪来"，不做任何 GL 或排版工作。
 *
 * <p>优先级（先命中先用）：
 * <ol>
 *   <li>{@code config/mythosnexus/ui-font.ttf} —— 用户自己丢进来的字体</li>
 *   <li>{@code config/mythosnexus/} 下任意 {@code .ttf}</li>
 *   <li>MythosScript 随包字体 {@code assets/mythos_script/fonts/NotoSansCJKsc-Regular.ttf}
 *       —— Forge 1.12 所有 mod 共用一个 classloader，可以直接读到，不必再打包 16MB</li>
 *   <li>系统字体（微软雅黑 / 思源黑体 / 黑体 …）</li>
 * </ol>
 *
 * <p>每个候选都会真正画一个字来验证：{@code canDisplayUpTo} 对逻辑字体不可靠，
 * 而缺字时 AWT 画出来的是豆腐块（不透明），所以要"能宣称显示"+"真的画出了像素"两条
 * 同时成立才算可用。
 */
final class NexusFontSource {

    private NexusFontSource() {}

    /** MythosScript 的随包 TTF（用 .ttf 不用 .otf：Java 8 上 CFF/T2K 轮廓会卡住渲染线程）。 */
    private static final String SHARED_RESOURCE = "/assets/mythos_script/fonts/NotoSansCJKsc-Regular.ttf";
    /** Nexus 自带的兜底字体（当前没有打包，缺了就走系统字体）。 */
    private static final String OWN_RESOURCE = "/assets/mythosnexus/fonts/ui-font.ttf";
    private static final String CONFIG_FILE = "config/mythosnexus/ui-font.ttf";
    private static final String CONFIG_DIR = "config/mythosnexus";
    private static final String[] SYSTEM_NAMES = {
            "Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans CJK SC",
            "Source Han Sans SC", "SimHei", "PingFang SC", "Segoe UI",
            "SansSerif", "Dialog"
    };
    /** 探测串：Nexus 界面里真实出现过的两类字符（汉字 + 拉丁）。 */
    private static final String PROBE = "控制中心 Nx";

    /** 解析成功的字体；null = 还在加载或加载失败（此时一律回退原版字体）。 */
    static volatile Font resolved;
    /** 字体来源描述，仅供日志。 */
    static volatile String resolvedFrom = "";
    /** 加载流程走完（成功或失败）。 */
    static volatile boolean finished;

    private static volatile boolean loading;

    /**
     * 后台线程解析字体。一次 16MB 的 CJK TTF 解析要几百毫秒，绝不能卡在渲染线程上
     * （MythosScript 的 BundledUiFont 也是这么做的）。
     */
    static void warmUp() {
        synchronized (NexusFontSource.class) {
            if (loading || finished) return;
            loading = true;
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Font f = resolve();
                    if (f == null) {
                        NexusPlugin.LOGGER.warn("[nexus.font] 没找到可用的高清字体，回退原版字体");
                    } else {
                        resolved = f;
                        NexusPlugin.LOGGER.info("[nexus.font] 高清字体就绪: {} (来源: {})",
                                f.getFontName(), resolvedFrom);
                    }
                } catch (Throwable t2) {
                    NexusPlugin.LOGGER.warn("[nexus.font] 加载高清字体失败，回退原版字体", t2);
                } finally {
                    loading = false;
                    finished = true;
                }
            }
        }, "Nexus-Font-Load");
        t.setDaemon(true);
        t.start();
    }

    private static Font resolve() {
        Font f = fromFile(new File(CONFIG_FILE));
        if (usable(f, "配置 ui-font.ttf")) return f;

        for (File alt : configFonts()) {
            f = fromFile(alt);
            if (usable(f, "配置目录 " + alt.getName())) return f;
        }

        f = fromResource(SHARED_RESOURCE);
        if (usable(f, "MythosScript 随包 Noto Sans CJK")) return f;

        f = fromResource(OWN_RESOURCE);
        if (usable(f, "Nexus 随包字体")) return f;

        for (String name : SYSTEM_NAMES) {
            Font sys = new Font(name, Font.PLAIN, 12);
            if (usable(sys, "系统字体 " + name)) return sys;
        }
        return null;
    }

    private static List<File> configFonts() {
        List<File> out = new ArrayList<File>();
        try {
            File dir = new File(CONFIG_DIR);
            File[] list = dir.listFiles();
            if (list == null) return out;
            for (File c : list) {
                if (c.isFile() && c.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".ttf")) {
                    out.add(c);
                }
            }
        } catch (Throwable ignored) {
            // 目录不可读就当没有
        }
        return out;
    }

    private static boolean usable(Font f, String from) {
        if (f == null) return false;
        try {
            if (f.canDisplayUpTo(PROBE) >= 0) return false;
        } catch (Throwable t) {
            return false;
        }
        if (!renders(f)) return false;
        resolvedFrom = from;
        return true;
    }

    /** 真的画一遍探测串，确认有像素落下来（有的字体能宣称显示却是空白/豆腐块）。 */
    private static boolean renders(Font base) {
        BufferedImage img = null;
        Graphics2D g = null;
        try {
            Font f = base.deriveFont(Font.PLAIN, 24f);
            img = new BufferedImage(72, 48, BufferedImage.TYPE_INT_ARGB);
            g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                    RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            g.setFont(f);
            g.setColor(Color.WHITE);
            g.drawString(PROBE, 2, 34);
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    if ((img.getRGB(x, y) >>> 24) != 0) return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            if (g != null) g.dispose();
        }
    }

    private static Font fromFile(File f) {
        InputStream in = null;
        try {
            if (f == null || !f.isFile() || f.length() <= 0) return null;
            in = new FileInputStream(f);
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Throwable t) {
            NexusPlugin.LOGGER.warn("[nexus.font] 读取字体文件失败: {}", f);
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    private static Font fromResource(String path) {
        InputStream in = null;
        try {
            in = NexusFontSource.class.getResourceAsStream(path);
            if (in == null) return null;
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }
}
