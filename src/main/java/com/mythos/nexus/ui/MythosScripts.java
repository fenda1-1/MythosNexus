package com.mythos.nexus.ui;

import com.mythos.nexus.core.NexusRefs;
import com.mythos.nexus.session.BotSession;
import com.mythos.nexus.session.SessionManager;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reflection bridge to MythosScript sequences (optional dependency). */
final class MythosScripts {
    private MythosScripts() {}

    static final class Seq {
        final String name, category, subCategory, note;
        Seq(String name, String category, String subCategory, String note) {
            this.name = name; this.category = category; this.subCategory = subCategory; this.note = note;
        }
    }

    private static boolean tried;
    private static Method mVisibleCategories, mAllVisible, mRun, mClearStack;
    private static Method mSeqName, mSeqCategory, mSeqSub, mSeqNote;
    private static Method mSnapshots, mSnapOwner, mSnapSeq, mStopFg, mStopBg;
    private static Method mWithContext;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Nexus-Script");
        t.setDaemon(true);
        return t;
    });

    private static List<Seq> seqCache = Collections.emptyList();
    private static List<String> catCache = Collections.emptyList();
    private static long seqAt;
    private static Map<String, String> runCache = Collections.emptyMap();
    private static long runAt;

    private static synchronized boolean init() {
        if (tried) return mRun != null;
        tried = true;
        try {
            Class<?> psm = NexusRefs.cls(NexusRefs.PATH_SEQUENCE_MANAGER);
            Class<?> pse = NexusRefs.cls(NexusRefs.PATH_SEQUENCE_EVENT_LISTENER);
            Class<?> seq = NexusRefs.cls(NexusRefs.PATH_SEQUENCE);
            Class<?> snap = NexusRefs.cls(NexusRefs.PROGRESS_SNAPSHOT);
            Class<?> ns = NexusRefs.cls(NexusRefs.NEXUS_SESSIONS);
            mVisibleCategories = NexusRefs.method(psm, "getVisibleCategories");
            mAllVisible = NexusRefs.method(psm, "getAllVisibleSequences");
            mClearStack = NexusRefs.method(psm, "clearRunSequenceCallStack");
            mSeqName = NexusRefs.method(seq, "getName");
            mSeqCategory = NexusRefs.method(seq, "getCategory");
            mSeqSub = optional(seq, "getSubCategory");
            mSeqNote = optional(seq, "getNote");
            mSnapshots = NexusRefs.method(pse, "getAllActiveProgressSnapshots");
            mSnapOwner = NexusRefs.method(snap, "getOwner");
            mSnapSeq = NexusRefs.method(snap, "getSequenceName");
            mStopFg = NexusRefs.method(pse, "stopForegroundSequenceByActionForOwner", String.class);
            mStopBg = NexusRefs.method(pse, "stopBackgroundRunnersForOwner", String.class);
            mWithContext = NexusRefs.method(ns, "withContext", Object.class, Runnable.class);
            mRun = NexusRefs.method(psm, "runPathSequence", String.class);
            resolveAutos();
        } catch (Throwable t) {
            NexusRefs.warnOnce("scripts:init", t,
                    "MythosScripts: sequence bridge unavailable, script panel disabled: %s",
                    t.toString());
            mRun = null;
        }
        return mRun != null;
    }

    private static Method optional(Class<?> c, String name) {
        try { return c.getMethod(name); } catch (Throwable t) { return null; }
    }

    static boolean available() { return init(); }

    static synchronized void invalidate() { seqAt = 0; runAt = 0; }

    /** Visible sequences, refreshed at most every 2s. */
    static synchronized List<Seq> sequences() {
        if (!init()) return Collections.emptyList();
        long now = System.currentTimeMillis();
        if (now - seqAt < 2000L) return seqCache;
        seqAt = now;
        try {
            List<Seq> out = new ArrayList<>();
            for (Object o : new ArrayList<>((List<?>) mAllVisible.invoke(null))) {
                String name = str(mSeqName, o);
                if (name.isEmpty() || name.startsWith("__")) continue;
                out.add(new Seq(name, str(mSeqCategory, o), str(mSeqSub, o), str(mSeqNote, o)));
            }
            seqCache = out;
            catCache = new ArrayList<>((List<String>) mVisibleCategories.invoke(null));
        } catch (Throwable t) {
            // Concurrent modification while MythosScript reloads is expected and
            // self-heals on the next refresh; anything else is a real failure.
            if (!(t instanceof java.util.ConcurrentModificationException)) {
                NexusRefs.warnOnce("scripts:sequences", t,
                        "MythosScripts.sequences: refresh failed, keeping last snapshot: %s",
                        t.toString());
            }
        }
        return seqCache;
    }

    static synchronized List<String> categories() {
        sequences();
        return catCache;
    }

    private static String str(Method m, Object o) {
        if (m == null) return "";
        try {
            Object v = m.invoke(o);
            return v == null ? "" : v.toString();
        } catch (Throwable t) {
            NexusRefs.warnOnce("scripts:str:" + m.getName(), t,
                    "MythosScripts.%s failed", m.getName());
            return "";
        }
    }

    /** owner (lower-case username, "" = human) → running sequence name; refreshed every 250ms. */
    static synchronized Map<String, String> running() {
        if (!init()) return Collections.emptyMap();
        long now = System.currentTimeMillis();
        if (now - runAt < 250L) return runCache;
        runAt = now;
        try {
            Map<String, String> m = new HashMap<>();
            for (Object o : (List<?>) mSnapshots.invoke(null)) {
                String owner = str(mSnapOwner, o).toLowerCase();
                if (!m.containsKey(owner)) m.put(owner, str(mSnapSeq, o));
            }
            runCache = m;
        } catch (Throwable t) {
            if (!(t instanceof java.util.ConcurrentModificationException)) {
                NexusRefs.warnOnce("scripts:running", t,
                        "MythosScripts.running: refresh failed, keeping last snapshot: %s",
                        t.toString());
            }
        }
        return runCache;
    }

    static String ownerOf(BotSession s) {
        return s == SessionManager.get().humanSession() || s.username == null ? "" : s.username;
    }

    static String runningOn(BotSession s) {
        Map<String, String> m = running();
        String v = m.get(ownerOf(s).toLowerCase());
        if (v == null && s == SessionManager.get().humanSession() && s.username != null)
            v = m.get(s.username.toLowerCase());
        return v;
    }

    static void run(BotSession s, String seqName) {
        if (!init()) return;
        dispatch(s, () -> {
            try { mRun.invoke(null, seqName); }
            catch (Throwable t) { s.logEvent("脚本执行失败: " + seqName + " " + cause(t)); }
        });
        s.logEvent("执行序列 " + seqName);
        runAt = 0;
    }

    static void stop(BotSession s) {
        if (!init()) return;
        String owner = ownerOf(s);
        dispatch(s, () -> {
            try {
                mStopFg.invoke(null, owner);
                mStopBg.invoke(null, owner);
                mClearStack.invoke(null);
            } catch (Throwable t) { s.logEvent("停止序列失败: " + cause(t)); }
        });
        s.logEvent("停止序列");
        runAt = 0;
    }

    // ------------------------------------------------------------------
    // 自动化：MythosScript 各功能的总开关（杀戮光环 / 自动钓鱼 / …）
    //
    // 这些开关都读写在 MythosScript 的"会话运行时"（rt()）里，是每会话状态，
    // 所以必须在目标会话自己的上下文里调用 —— 和序列的执行/停止共用 dispatch。
    // ------------------------------------------------------------------

    /** 一个功能总开关：反射句柄 + 显示名。 */
    static final class Auto {
        final String id, label, tip, handler, getter, setter, saver;
        /** getter 返回对象时：非 null 即"开启"（自动追怪返回当前规则）。 */
        final boolean nonNullOn;
        Method mGet, mSet, mSave;

        Auto(String id, String label, String tip, String handler,
             String getter, String setter, String saver, boolean nonNullOn) {
            this.id = id; this.label = label; this.tip = tip;
            this.handler = handler; this.getter = getter;
            this.setter = setter; this.saver = saver;
            this.nonNullOn = nonNullOn;
        }

        boolean ok() { return mGet != null && mSet != null; }
    }

    /** 面板里的排列顺序；和 MythosScript 物品栏界面的左键总开关一一对应。 */
    private static final List<Auto> AUTOS = Collections.unmodifiableList(java.util.Arrays.asList(
            new Auto("killaura", "杀戮光环", "自动攻击附近目标，无需手动点名",
                    NexusRefs.KILL_AURA_HANDLER, "enabled", "setEnabledSilently", "saveConfig", false),
            new Auto("fly", "飞行", "解除重力限制的飞行",
                    NexusRefs.FLY_HANDLER, "enabled", "setEnabledSilently", "saveConfig", false),
            new Auto("fishing", "自动钓鱼", "抛竿 / 收竿 / 重新抛竿全自动",
                    NexusRefs.AUTO_FISHING_HANDLER, "enabled", "setEnabledSilently", "saveConfig", false),
            new Auto("follow", "自动追怪", "按追怪规则自动接近目标",
                    NexusRefs.AUTO_FOLLOW_HANDLER, "getActiveRule", "setEnabled", null, true),
            new Auto("eat", "自动进食", "饥饿度过低时自动吃背包里的食物",
                    NexusRefs.AUTO_EAT_HANDLER, "autoEatEnabled", "setAutoEatEnabledSilently", "saveAutoEatConfig", false),
            new Auto("pickup", "自动拾取掉落物", "自动拾起附近的掉落物",
                    NexusRefs.AUTO_PICKUP_HANDLER, "globalEnabled", "setGlobalEnabledSilently", "saveConfig", false),
            new Auto("useitem", "静默使用物品", "不打开界面静默使用物品",
                    NexusRefs.AUTO_USE_ITEM_HANDLER, "globalEnabled", "setGlobalEnabledSilently", "saveConfig", false),
            new Auto("conditional", "条件执行", "满足条件时执行配置好的动作",
                    NexusRefs.CONDITIONAL_EXECUTION_HANDLER, "isGloballyEnabled", "setGlobalEnabledSilently", "saveConfig", false),
            new Auto("escape", "自动逃离", "血量危险时自动脱战撤离",
                    NexusRefs.AUTO_ESCAPE_HANDLER, "masterEnabled", "setMasterEnabled", "saveConfig", false),
            new Auto("parkour", "Baritone 跑酷", "让 Baritone 按跑酷规则移动",
                    NexusRefs.BARITONE_PARKOUR_HELPER, "isParkourModeEnabled", "setParkourModeEnabled", null, false)));

    /** owner（小写用户名，"" = 本体）→ 功能 id → 开关状态。 */
    private static final Map<String, Map<String, Boolean>> autoState = new ConcurrentHashMap<>();
    private static volatile long autoAt;

    private static void resolveAutos() {
        for (Auto a : AUTOS) {
            try {
                Class<?> c = NexusRefs.cls(a.handler);
                a.mGet = NexusRefs.method(c, a.getter);
                a.mSet = NexusRefs.method(c, a.setter, boolean.class);
                a.mSave = a.saver == null ? null : optional(c, a.saver);
                // 只认静态句柄：这些开关都是每会话静态入口，实例方法在这里调用会 NPE。
                if (!Modifier.isStatic(a.mGet.getModifiers()) || !Modifier.isStatic(a.mSet.getModifiers()))
                    throw new NoSuchMethodException(a.getter + "/" + a.setter + " 不是静态方法");
                if (a.mSave != null && !Modifier.isStatic(a.mSave.getModifiers())) a.mSave = null;
            } catch (Throwable t) {
                a.mGet = null; a.mSet = null; a.mSave = null;
                NexusRefs.warnOnce("auto:" + a.id, t,
                        "MythosScripts: 自动化开关 %s 不可用: %s", a.id, t.toString());
            }
        }
    }

    /** 当前 MythosScript 里实际可用的功能开关（类/方法缺失的会被跳过）。 */
    static List<Auto> autos() {
        if (!init()) return Collections.emptyList();
        List<Auto> out = new ArrayList<>();
        for (Auto a : AUTOS) if (a.ok()) out.add(a);
        return out;
    }

    private static boolean readOn(Auto a) throws Exception {
        Object v = a.mGet.invoke(null);
        return a.nonNullOn ? v != null : Boolean.TRUE.equals(v);
    }

    private static String key(BotSession b) { return ownerOf(b).toLowerCase(); }

    private static boolean canDrive(BotSession b) {
        return b == SessionManager.get().humanSession() || b.state == BotSession.State.ONLINE;
    }

    /** 让每个目标回报一次开关状态（最多 400ms 一次）。 */
    static void refreshAutos(List<BotSession> ts) {
        if (ts.isEmpty() || !init()) return;
        long now = System.currentTimeMillis();
        if (now - autoAt < 400L) return;
        autoAt = now;
        for (final BotSession b : ts) {
            if (!canDrive(b)) continue;
            dispatch(b, () -> {
                Map<String, Boolean> m = new ConcurrentHashMap<>();
                for (Auto a : AUTOS) {
                    if (!a.ok()) continue;
                    try { m.put(a.id, readOn(a)); } catch (Throwable ignored) { }
                }
                if (!m.isEmpty()) autoState.put(key(b), m);
            });
        }
    }

    /** {已开启数, 已知状态数}；已知为 0 表示还没读到。 */
    static int[] autoCounts(List<BotSession> ts, Auto a) {
        int on = 0, known = 0;
        for (BotSession b : ts) {
            Map<String, Boolean> m = autoState.get(key(b));
            Boolean v = m == null ? null : m.get(a.id);
            if (v == null) continue;
            known++;
            if (v) on++;
        }
        return new int[]{on, known};
    }

    /** 按第一个已知目标的状态取反，对全部可驱动目标生效；返回是否切到"开"。 */
    static boolean toggleAuto(List<BotSession> ts, Auto a) {
        if (!init() || !a.ok()) return false;
        boolean want = true;
        for (BotSession b : ts) {
            Map<String, Boolean> m = autoState.get(key(b));
            Boolean v = m == null ? null : m.get(a.id);
            if (v != null) { want = !v; break; }
        }
        final boolean on = want;
        for (final BotSession b : ts) {
            if (!canDrive(b)) continue;
            dispatch(b, () -> {
                try {
                    a.mSet.invoke(null, on);
                    if (a.mSave != null) a.mSave.invoke(null);
                } catch (Throwable t) {
                    b.logEvent("自动化开关失败: " + a.label + " " + cause(t));
                }
            });
            Map<String, Boolean> m = autoState.get(key(b));
            if (m == null) { m = new ConcurrentHashMap<>(); autoState.put(key(b), m); }
            m.put(a.id, on);
            b.logEvent((on ? "开启 " : "关闭 ") + a.label);
        }
        autoAt = System.currentTimeMillis();
        return on;
    }

    private static String cause(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return String.valueOf(t);
    }

    /** Ticker sessions block on their own tick thread; human/mounted run on the client thread. */
    private static void dispatch(BotSession s, Runnable body) {
        Runnable inCtx = () -> {
            try { mWithContext.invoke(null, s, body); }
            catch (Throwable t) { s.logEvent("脚本上下文失败: " + cause(t)); }
        };
        if (s.hasTicker()) POOL.submit(inCtx);
        else Minecraft.getMinecraft().addScheduledTask(inCtx);
    }
}
