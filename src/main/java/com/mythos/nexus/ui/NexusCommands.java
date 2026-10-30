package com.mythos.nexus.ui;

import com.mythos.nexus.MythosNexusMod;
import com.mythos.nexus.core.NexusConfig;
import com.mythos.nexus.session.BotConnection;
import com.mythos.nexus.session.BotSession;
import com.mythos.nexus.session.SessionManager;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Nexus command registry + rebindable key combos (shared by palette, sidebar and keybind editor). */
final class NexusCommands {
    private NexusCommands() {}

    /** One key plus an exact modifier set. */
    static final class Combo {
        final int key;
        final boolean ctrl, shift, alt;

        Combo(int key, boolean ctrl, boolean shift, boolean alt) {
            this.key = key; this.ctrl = ctrl; this.shift = shift; this.alt = alt;
        }

        static Combo of(String spec) {
            if (spec == null || spec.isEmpty()) return null;
            boolean c = false, s = false, a = false;
            int key = 0;
            for (String part : spec.split("\\+")) {
                String p = part.trim().toLowerCase();
                if (p.equals("ctrl")) c = true;
                else if (p.equals("shift")) s = true;
                else if (p.equals("alt")) a = true;
                else {
                    try { key = Integer.parseInt(p); } catch (NumberFormatException e) { return null; }
                }
            }
            return key <= 0 ? null : new Combo(key, c, s, a);
        }

        String encode() {
            return (ctrl ? "ctrl+" : "") + (shift ? "shift+" : "") + (alt ? "alt+" : "") + key;
        }

        String label() {
            StringBuilder b = new StringBuilder();
            if (ctrl) b.append("Ctrl + ");
            if (shift) b.append("Shift + ");
            if (alt) b.append("Alt + ");
            b.append(keyName(key));
            return b.toString();
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Combo)) return false;
            Combo c = (Combo) o;
            return c.key == key && c.ctrl == ctrl && c.shift == shift && c.alt == alt;
        }
        @Override public int hashCode() { return key * 8 + (ctrl ? 4 : 0) + (shift ? 2 : 0) + (alt ? 1 : 0); }
    }

    static final class Cmd {
        final String id, title, group, desc;
        final List<Combo> defaults;
        final Consumer<NexusScreen> action;

        Cmd(String id, String title, String group, String desc, Consumer<NexusScreen> action, Combo... defaults) {
            this.id = id; this.title = title; this.group = group; this.desc = desc;
            this.action = action;
            List<Combo> d = new ArrayList<>();
            Collections.addAll(d, defaults);
            this.defaults = Collections.unmodifiableList(d);
        }
    }

    static String keyName(int key) {
        switch (key) {
            case Keyboard.KEY_RETURN: return "Enter";
            case Keyboard.KEY_NUMPADENTER: return "NumEnter";
            case Keyboard.KEY_DELETE: return "Del";
            case Keyboard.KEY_BACK: return "Backspace";
            case Keyboard.KEY_SPACE: return "Space";
            case Keyboard.KEY_ESCAPE: return "Esc";
            case Keyboard.KEY_TAB: return "Tab";
            case Keyboard.KEY_EQUALS: return "=";
            case Keyboard.KEY_MINUS: return "-";
            case Keyboard.KEY_ADD: return "小键盘 +";
            case Keyboard.KEY_SUBTRACT: return "小键盘 -";
            default:
                String n = Keyboard.getKeyName(key);
                return n == null ? "#" + key : n;
        }
    }

    static boolean isModifier(int key) {
        return key == Keyboard.KEY_LCONTROL || key == Keyboard.KEY_RCONTROL
                || key == Keyboard.KEY_LSHIFT || key == Keyboard.KEY_RSHIFT
                || key == Keyboard.KEY_LMENU || key == Keyboard.KEY_RMENU
                || key == Keyboard.KEY_LMETA || key == Keyboard.KEY_RMETA;
    }

    static boolean ctrlDown() { return Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL); }
    static boolean shiftDown() { return Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT); }
    static boolean altDown() { return Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU); }

    static Combo held(int key) { return new Combo(key, ctrlDown(), shiftDown(), altDown()); }

    static String heldModifiersLabel() {
        StringBuilder b = new StringBuilder();
        if (ctrlDown()) b.append("Ctrl + ");
        if (shiftDown()) b.append("Shift + ");
        if (altDown()) b.append("Alt + ");
        return b.toString();
    }

    private static Combo k(int key) { return new Combo(key, false, false, false); }
    private static Combo ctrl(int key) { return new Combo(key, true, false, false); }
    private static Combo ctrlShift(int key) { return new Combo(key, true, true, false); }
    private static Combo alt(int key) { return new Combo(key, false, false, true); }

    static final List<Cmd> ALL;
    static {
        List<Cmd> l = new ArrayList<>();
        l.add(new Cmd("palette", "打开命令面板", "通用", "搜索并执行任意命令",
                s -> s.openPalette(), k(Keyboard.KEY_F1)));
        l.add(new Cmd("save", "保存所有修改", "通用", "写入账号 / 分组 / 代理配置",
                NexusScreen::saveAll, ctrl(Keyboard.KEY_S)));
        l.add(new Cmd("find", "聚焦搜索框", "通用", "定位到顶部搜索框",
                s -> s.searchField.setFocused(true), ctrl(Keyboard.KEY_F)));
        l.add(new Cmd("toggleRight", "收起 / 展开右侧栏", "通用", "切换右侧面板显示",
                NexusScreen::toggleRightPanel, ctrl(Keyboard.KEY_B)));
        l.add(new Cmd("close", "关闭管理器", "通用", "关闭 Nexus 界面 (Esc 也可)",
                NexusScreen::requestClose));

        // 连续百分比缩放：每次 5%，按住连续，范围 50% ~ 300%
        l.add(new Cmd("ui.zoomIn", "放大界面", "视图", "整体文字与布局 +5%，按住连续 (Ctrl + + / Ctrl + 滚轮)",
                s -> s.zoomBy(1), ctrl(Keyboard.KEY_EQUALS), ctrlShift(Keyboard.KEY_EQUALS),
                ctrl(Keyboard.KEY_ADD)));
        l.add(new Cmd("ui.zoomOut", "缩小界面", "视图", "整体文字与布局 -5%，按住连续 (Ctrl + - / Ctrl + 滚轮)",
                s -> s.zoomBy(-1), ctrl(Keyboard.KEY_MINUS), ctrl(Keyboard.KEY_SUBTRACT)));
        l.add(new Cmd("ui.zoomReset", "重置界面大小", "视图", "恢复 100% 设计基准 (Ctrl + 0)",
                NexusScreen::resetZoom, ctrl(Keyboard.KEY_0)));
        l.add(new Cmd("ui.hdFont", "切换高清字体", "视图",
                "开/关字形图集高清字体（关闭后重新打开界面生效；缩放非整数比例时原版字体会糊）",
                NexusScreen::toggleHdFont));

        l.add(new Cmd("selall", "全选当前页", "账号", "选中当前筛选下全部账号",
                NexusScreen::selectAllFiltered, ctrl(Keyboard.KEY_A)));
        l.add(new Cmd("deselect", "取消选择", "账号", "清空当前选中",
                s -> s.selected.clear(), ctrl(Keyboard.KEY_D)));
        l.add(new Cmd("mount", "切换到选中账号视角", "账号", "挂载第一个选中账号",
                s -> { BotSession b = s.firstSelected(); if (b != null) MythosNexusMod.switchTo(b); },
                k(Keyboard.KEY_RETURN)));
        l.add(new Cmd("connect", "连接选中账号", "账号", "批量连接离线 / 断开的账号",
                NexusCommands::connectSelected, ctrlShift(Keyboard.KEY_C)));
        l.add(new Cmd("disconnect", "断开选中账号", "账号", "批量断开在线账号",
                NexusCommands::disconnectSelected, ctrlShift(Keyboard.KEY_X)));
        l.add(new Cmd("delete", "删除选中账号", "账号", "删除当前选中的账号卡片",
                s -> { if (!s.selected.isEmpty()) s.deleteSelected(); }, k(Keyboard.KEY_DELETE)));
        l.add(new Cmd("copy", "复制选中账号", "账号", "复制为 user:host:port 文本",
                NexusScreen::copySelected, ctrl(Keyboard.KEY_C)));
        l.add(new Cmd("paste", "粘贴账号", "账号", "从剪贴板 user:host:port 新建账号",
                NexusScreen::pasteAccount, ctrl(Keyboard.KEY_V)));
        l.add(new Cmd("export", "导出账号 (JSON)", "账号", "复制全部账号到剪贴板",
                s -> s.exportAccounts(false), ctrl(Keyboard.KEY_E)));
        l.add(new Cmd("exportcsv", "导出账号 (CSV)", "账号", "复制全部账号到剪贴板",
                s -> s.exportAccounts(true), ctrlShift(Keyboard.KEY_E)));
        l.add(new Cmd("import", "导入账号", "账号", "从剪贴板导入 JSON 或 CSV",
                NexusScreen::importAccounts, ctrl(Keyboard.KEY_I)));

        l.add(new Cmd("scriptRun", "执行选中序列", "脚本", "在选中账号 (未选则全部在线) 上执行",
                s -> s.rightScript.runSelected(), ctrl(Keyboard.KEY_R)));
        l.add(new Cmd("scriptStop", "停止序列", "脚本", "停止选中账号 (未选则全部在线) 的序列",
                s -> s.rightScript.stopSelected(), ctrlShift(Keyboard.KEY_R)));

        l.add(new Cmd("view.manager", "打开账号管理", "面板", "游戏内按 Z 打开 / 再按 Z 关闭",
                s -> com.mythos.nexus.MythosNexusMod.openManager(), k(Keyboard.KEY_Z)));
        l.add(new Cmd("view.accounts", "打开账号面板", "面板", "添加 / 编辑 / 选中账号",
                s -> s.showView(NexusScreen.View.ACCOUNTS), alt(Keyboard.KEY_1)));
        l.add(new Cmd("view.reconnect", "打开自动重连面板", "面板", "队列 · 随机间隔 · 统一开关",
                s -> s.showView(NexusScreen.View.RECONNECT), alt(Keyboard.KEY_R)));
        l.add(new Cmd("view.sync", "打开人物同步面板", "面板", "同步分组 · 聚拢 · 动作复制",
                s -> s.showView(NexusScreen.View.SYNC), alt(Keyboard.KEY_Y)));
        l.add(new Cmd("view.status", "打开状态面板", "面板", "在线时长 · HP · 附近实体",
                s -> s.showView(NexusScreen.View.STATUS), alt(Keyboard.KEY_2)));
        l.add(new Cmd("view.log", "打开日志面板", "面板", "会话事件日志",
                s -> s.showView(NexusScreen.View.LOG), alt(Keyboard.KEY_3)));
        l.add(new Cmd("view.proxy", "打开代理面板", "面板", "代理服务器列表",
                s -> s.showView(NexusScreen.View.PROXIES), alt(Keyboard.KEY_4)));
        l.add(new Cmd("view.script", "打开脚本面板", "面板", "MythosScript 序列 · 执行 / 停止",
                s -> s.showView(NexusScreen.View.SCRIPTS), alt(Keyboard.KEY_5)));
        l.add(new Cmd("view.commands", "打开命令面板 (侧栏)", "面板", "全部命令列表",
                s -> s.showView(NexusScreen.View.COMMANDS), alt(Keyboard.KEY_6)));
        l.add(new Cmd("view.keys", "打开快捷键面板", "面板", "双击录制组合键",
                s -> s.showView(NexusScreen.View.HELP), alt(Keyboard.KEY_7)));
        l.add(new Cmd("view.theme", "打开主题面板", "面板", "预设 / 新建 / 导入 / AI 生成",
                s -> s.showView(NexusScreen.View.THEME), alt(Keyboard.KEY_8)));
        l.add(new Cmd("view.tick", "打开 Tick 面板", "面板", "自适应调度 · 优先级曲线",
                s -> s.showView(NexusScreen.View.TICKRATE), alt(Keyboard.KEY_9)));
        l.add(new Cmd("view.info", "打开信息面板", "面板", "支持作者 · 交流群 · 开源",
                s -> s.showView(NexusScreen.View.INFO), alt(Keyboard.KEY_0)));
        l.add(new Cmd("settings", "图标面板设置", "面板", "选择右侧栏显示的图标",
                s -> s.overlayPanel = "settings"));
        ALL = Collections.unmodifiableList(l);
    }

    static Cmd byId(String id) {
        for (Cmd c : ALL) if (c.id.equals(id)) return c;
        return null;
    }

    /** Effective combos: stored override, else defaults. */
    static List<Combo> bindings(Cmd c) {
        List<String> stored = NexusConfig.commandBinds(c.id);
        if (stored == null) return c.defaults;
        List<Combo> out = new ArrayList<>();
        for (String spec : stored) {
            Combo k = Combo.of(spec);
            if (k != null && !out.contains(k)) out.add(k);
        }
        return out;
    }

    static Map<String, List<Combo>> snapshot() {
        Map<String, List<Combo>> m = new HashMap<>();
        for (Cmd c : ALL) m.put(c.id, new ArrayList<>(bindings(c)));
        return m;
    }

    static String label(List<Combo> combos) {
        if (combos == null || combos.isEmpty()) return "未绑定";
        StringBuilder b = new StringBuilder();
        for (Combo k : combos) {
            if (b.length() > 0) b.append(" / ");
            b.append(k.label());
        }
        return b.toString();
    }

    /** First command bound to exactly this key + held modifiers. */
    static Cmd match(int key) {
        if (key <= 0 || isModifier(key)) return null;
        Combo pressed = held(key);
        for (Cmd c : ALL) if (bindings(c).contains(pressed)) return c;
        return null;
    }

    static void execute(NexusScreen s, Cmd c) {
        try {
            c.action.accept(s);
        } catch (Throwable t) {
            s.toast("命令失败: " + c.title + " — " + t, 2500);
        }
    }

    private static void connectSelected(NexusScreen s) {
        List<BotSession> sel = s.selectedSessions();
        if (sel.isEmpty()) { s.toast("未选中账号", 1500); return; }
        s.runBatch("连接", sel, bs -> {
            if (bs == SessionManager.get().humanSession()) return;
            if (bs.state == BotSession.State.OFFLINE || bs.state == BotSession.State.DISCONNECTED
                    || bs.state == BotSession.State.ERROR)
                com.mythos.nexus.session.ReconnectManager.get().enqueue(bs, 0, "批量连接");
        });
    }

    private static void disconnectSelected(NexusScreen s) {
        List<BotSession> sel = s.selectedSessions();
        if (sel.isEmpty()) { s.toast("未选中账号", 1500); return; }
        s.runBatch("断开", sel, bs -> {
            if (bs == SessionManager.get().humanSession()) return;
            if (bs.state == BotSession.State.ONLINE || bs.state == BotSession.State.CONNECTING)
                bs.disconnect("batch");
        });
    }
}
