package com.mythos.nexus.ui;

import com.mythos.nexus.core.NexusConfig;

import static com.mythos.nexus.ui.NexusGeometry.hit;

/**
 * "settings" overlay — checkboxes controlling which panel icons stay shown
 * in the right icon strip. One definition of the card geometry and item rows
 * is shared by draw() and click().
 */
final class SettingsOverlay implements NexusOverlay {

    private static final int W = 260, ROW_H = 20, ROW_STEP = 24, LIST_TOP = 52;

    /** Toggleable panel icons: {iconId, title, description}. The ids match
     *  NexusScreen.drawRightIcons. The settings icon itself is not listed —
     *  it is always shown, since it is the only way back into this overlay. */
    static final String[][] ITEMS = {
        {"edit",   "账号", "添加 / 编辑 / 选中账号"},
        {"reconnect", "自动重连", "队列 · 随机间隔 · 统一开关"},
        {"status", "状态", "在线时长 · HP · 附近实体"},
        {"log",    "日志", "会话事件日志"},
        {"proxy",  "代理", "代理服务器列表"},
        {"script", "脚本", "MythosScript 序列执行"},
        {"cmd",    "命令", "全部命令列表"},
        {"help",   "快捷键", "双击录制组合键"},
        {"theme",  "主题", "预设 / 新建 / 导入 / AI 生成"},
        {"tick",   "Tick", "自适应调度 · 优先级曲线"},
        {"sync",   "人物同步", "同步分组 · 聚拢 · 动作复制"},
        {"info",   "信息", "支持作者 · 交流群 · 开源"},
    };
    private static final int H = LIST_TOP + ITEMS.length * ROW_STEP + 28;

    private final NexusScreen s;

    SettingsOverlay(NexusScreen s) { this.s = s; }

    @Override public String id() { return "settings"; }

    private int x() { return (s.width - W) / 2; }
    private int y() { return (s.height - H) / 2; }

    @Override
    public void draw(int mx, int my) {
        int x = x(), y = y();
        Ui.card(x, y, W, H, NexusTheme.BG_SIDEBAR, NexusTheme.BORDER_SOFT, 6);
        s.font().drawString("图标面板设置", x + 16, y + 12, NexusTheme.TEXT_TITLE);
        s.font().drawString("勾选要显示在右侧栏的图标", x + 16, y + 30, NexusTheme.TEXT_SUB);
        int oy = y + LIST_TOP;
        for (String[] it : ITEMS) {
            boolean pinned = NexusConfig.iconPinned(it[0]);
            boolean hov = hit(mx, my, x + 16, oy, W - 32, ROW_H);
            if (hov) Ui.roundedRect(x + 16, oy, W - 32, ROW_H, 3, NexusTheme.HOVER_WASH);
            Ui.fill(x + 20, oy + 4, 12, 12,
                    pinned ? NexusTheme.ACCENT : NexusTheme.BORDER_SOFT);
            Ui.fill(x + 22, oy + 6, 8, 8, NexusTheme.BG_SIDEBAR);
            if (pinned) NexusIcons.drawCloseX(x + 26, oy + 10, 3, NexusTheme.ACCENT);
            s.font().drawString(it[1], x + 40, oy + 6,
                    hov ? NexusTheme.TEXT_TITLE : NexusTheme.TEXT_LABEL);
            String d = it[2];
            if (x + 70 + s.font().getStringWidth(d) < x + W - 8) {
                s.font().drawString(d, x + 70, oy + 6, NexusTheme.TEXT_FAINT);
            }
            oy += ROW_STEP;
        }
        s.font().drawString("Esc 或点击外部关闭", x + W - 160, y + H - 20,
                NexusTheme.TEXT_FAINT);
    }

    @Override
    public boolean click(int mx, int my, int btn) {
        int x = x(), y = y();
        if (!hit(mx, my, x, y, W, H)) {
            s.overlayPanel = null;
            return true;
        }
        int oy = y + LIST_TOP;
        for (String[] it : ITEMS) {
            if (hit(mx, my, x + 16, oy, W - 32, ROW_H)) {
                NexusConfig.setIconPinned(it[0], !NexusConfig.iconPinned(it[0]));
                return true;
            }
            oy += ROW_STEP;
        }
        return true;
    }

    @Override
    public boolean keyTyped(char c, int key) { return false; }
}
