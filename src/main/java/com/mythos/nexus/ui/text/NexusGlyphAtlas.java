package com.mythos.nexus.ui.text;

import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 高清字体的字形图集 —— 字符子集化的落点。
 *
 * <p>只为界面里真正画到过的 (字符, 设备字号) 分配格子：一个 1024×1024 的 RGBA 页
 * 能装下两三千个汉字，够 Nexus 用好几轮；装满了自动再开一页。
 *
 * <p>格子按"货架"打包（每行高度取该行最高的字形），每个字形上下左右各留 1 像素
 * 透明边，避免双线性采样吃到邻居的像素。
 */
final class NexusGlyphAtlas {

    /** 单页边长（像素）。 */
    static final int PAGE = 1024;
    /** 格子之间的透明留白。 */
    private static final int PAD = 1;

    private static final int[] NONE = { -1, 0, 0 };

    private static final class Page {
        final int texture;
        int x = PAD;
        int y = PAD;
        int shelfH = 0;

        Page(int texture) { this.texture = texture; }
    }

    private final List<Page> pages = new ArrayList<Page>();
    /** 已分配的字形格数，仅用于日志/诊断。 */
    int allocated;

    int pageCount() { return pages.size(); }

    int textureOf(int page) {
        return pages.get(page).texture;
    }

    /**
     * 申请一块 w×h 的空位。返回 {@code {page, x, y}}；失败返回 {@code {-1, 0, 0}}。
     * 必须在渲染线程调用（会创建 GL 纹理）。
     */
    int[] alloc(int w, int h) {
        if (w <= 0 || h <= 0) return NONE;
        if (w > PAGE - 2 * PAD) w = PAGE - 2 * PAD;
        if (h > PAGE - 2 * PAD) h = PAGE - 2 * PAD;

        for (int i = 0; i < pages.size(); i++) {
            Page p = pages.get(i);
            if (p.x + w + PAD > PAGE) {          // 这一行放不下，换行
                p.y += p.shelfH + PAD;
                p.x = PAD;
                p.shelfH = 0;
            }
            if (p.y + h + PAD > PAGE) continue;  // 这一页满了，试下一页
            int[] slot = { i, p.x, p.y };
            p.x += w + PAD;
            if (h > p.shelfH) p.shelfH = h;
            allocated++;
            return slot;
        }

        Page np = new Page(createTexture());
        pages.add(np);
        int[] slot = { pages.size() - 1, np.x, np.y };
        np.x += w + PAD;
        np.shelfH = h;
        allocated++;
        return slot;
    }

    /** 把一个 alpha 覆盖图上传到刚申请到的格子里（白字 + alpha，画的时候用 glColor 染色）。 */
    void upload(int[] slot, BufferedImage img) {
        if (slot == null || slot[0] < 0 || img == null) return;
        int w = Math.min(img.getWidth(), PAGE);
        int h = Math.min(img.getHeight(), PAGE);
        if (w <= 0 || h <= 0) return;

        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                buf.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF)
                   .put((byte) ((row[x] >>> 24) & 0xFF));
            }
        }
        // 注意这里转成 Buffer 再 flip：Java 9+ 给 ByteBuffer 加了返回 ByteBuffer 的协变重载，
        // 直接用 JDK21 的 javac 编译会绑到那个重载上，丢进 Java 8 客户端就是
        // NoSuchMethodError: java.nio.ByteBuffer.flip()Ljava/nio/ByteBuffer;
        java.nio.Buffer view = buf;
        view.flip();

        GlStateManager.bindTexture(pages.get(slot[0]).texture);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, slot[1], slot[2], w, h,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
    }

    private static int createTexture() {
        int id = GL11.glGenTextures();
        GlStateManager.bindTexture(id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, PAGE, PAGE, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        return id;
    }

    /** 释放全部图集纹理（界面卸载时调用）。 */
    void dispose() {
        for (Page p : pages) GL11.glDeleteTextures(p.texture);
        pages.clear();
        allocated = 0;
    }
}
