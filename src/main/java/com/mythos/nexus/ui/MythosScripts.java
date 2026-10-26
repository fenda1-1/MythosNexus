package com.mythos.nexus.ui;

import com.mythos.nexus.core.NexusRefs;
import com.mythos.nexus.session.BotSession;
import com.mythos.nexus.session.SessionManager;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
