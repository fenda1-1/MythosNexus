package com.mythos.nexus.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Debug file logger — one directory per game launch, one file per channel.
 *
 *   logs/nexus/2026-10-08_22-23-08/
 *       session.log   mount/unmount/persist/capture/ctx-pop field swaps
 *       packet.log    important packet dispatch (respawn/join/poslook/combat)
 *       tick.log      per-session tick summaries (throttled)
 *       baritone.log  NexusTickBridge / session-baritone state
 *       watchdog.log  the [wd] snapshots
 *       error.log     every warn/error also lands here so one file has all failures
 *
 * Plain PrintWriter, no log4j — the coremod may run before log4j is ready and
 * we want these files even when the client dies mid-startup. Every writer is
 * flushed per line; do not call FileDescriptor.sync() here because packet
 * diagnostics can run hundreds of times per second on the client thread.
 *
 * MythosScript can piggyback via reflection:
 *   NexusLog.log("baritone", "msg") — same directory, same files.
 */
public final class NexusLog {

    private NexusLog() {}

    private static final Map<String, PrintWriter> WRITERS = new ConcurrentHashMap<>();
    private static final Map<String, FileOutputStream> STREAMS = new ConcurrentHashMap<>();
    private static final File DIR;
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS");
    private static final Object LOCK = new Object(); // one writer per channel, still guard writes

    /** Off by default (files grew to GBs); enable with -Dnexus.debugLog=true. */
    public static final boolean ENABLED = Boolean.getBoolean("nexus.debugLog");

    static {
        File base = new File("logs" + File.separator + "nexus"
                + File.separator + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date()));
        DIR = base;
        if (ENABLED) {
            //noinspection ResultOfMethodCallIgnored
            base.mkdirs();
            log("session", "nexus debug log started, dir=" + base.getAbsolutePath());
        }
    }

    /** Directory this launch is logging to (for the startup banner). */
    public static String dir() { return ENABLED ? DIR.getAbsolutePath() : "(disabled)"; }

    /** Generic entry point — also reachable by reflection from MythosScript. */
    public static void log(String channel, String msg) {
        if (!ENABLED) return;
        try {
            PrintWriter w = WRITERS.computeIfAbsent(channel, NexusLog::open);
            String line = "[" + TS.format(new Date()) + "] ["
                    + Thread.currentThread().getName() + "] " + msg;
            synchronized (LOCK) {
                w.println(line);
                w.flush();
            }
            if ("error".equals(channel) || msg.startsWith("!!")) {
                mirror(line);
            }
        } catch (Throwable ignored) {
            // logging must never break the game
        }
    }

    public static void session(String fmt, Object... a) { log("session", fmt(fmt, a)); }
    public static void packet(String fmt, Object... a)  { log("packet",  fmt(fmt, a)); }
    public static void tick(String fmt, Object... a)    { log("tick",    fmt(fmt, a)); }
    public static void baritone(String fmt, Object... a){ log("baritone",fmt(fmt, a)); }
    public static void watchdog(String fmt, Object... a){ log("watchdog",fmt(fmt, a)); }
    /** Errors: own file AND mirrored into error.log for one-stop grepping. */
    public static void error(String fmt, Object... a) {
        if (!ENABLED) { NexusPlugin.LOGGER.warn(fmt(fmt, a)); return; }
        log("error", fmt(fmt, a));
    }

    public static void error(String fmt, Throwable t, Object... a) {
        if (!ENABLED) { NexusPlugin.LOGGER.warn(fmt(fmt, a), t); return; }
        log("error", fmt(fmt, a) + " | " + t);
        try {
            PrintWriter w = WRITERS.computeIfAbsent("error", NexusLog::open);
            synchronized (LOCK) { t.printStackTrace(w); w.flush(); }
        } catch (Throwable ignored) {}
    }

    private static void mirror(String line) {
        try {
            PrintWriter w = WRITERS.computeIfAbsent("error", NexusLog::open);
            synchronized (LOCK) { w.println(line); w.flush(); }
        } catch (Throwable ignored) {}
    }

    private static PrintWriter open(String channel) {
        try {
            FileOutputStream out = new FileOutputStream(
                    new File(DIR, channel + ".log"), true);
            STREAMS.put(channel, out);
            return new PrintWriter(out, true);
        } catch (Throwable t) {
            t.printStackTrace(); // visible in launch.log even if log4j isn't up
            // last resort: a writer that swallows everything
            return new PrintWriter(new java.io.StringWriter());
        }
    }

    private static String fmt(String fmt, Object... a) {
        return a == null || a.length == 0 ? fmt : String.format(fmt, a);
    }
}
