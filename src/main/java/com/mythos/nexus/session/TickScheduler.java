package com.mythos.nexus.session;

import com.mythos.nexus.core.NexusConfig;

/**
 * Adaptive tick-rate scheduler: splits background-bot world-tick budget
 * across priority bands so the mounted player keeps a target FPS.
 *
 * <h2>Model</h2>
 * The user drags a 6-node "priority curve" — curve[b] = the relative share
 * band b should get *of whatever headroom exists*. It's a shape, not an
 * absolute percentage: when the frame-time controller says there's 50%
 * capacity, every band's share scales down proportionally, but the *shape*
 * (band 0 ≫ band 5) is preserved exactly.
 *
 * <h2>Allocation</h2>
 * Weighted water-filling: each band's demand is its curve value. Total
 * allowed spend = capacity × Σ curve. Bands are fed in curve order —
 * the highest-priority band eats first and is always the last to throttle.
 * Idle bots cost IDLE_WEIGHT of a pathing bot in the same band.
 *
 * <h2>Controller</h2>
 * Asymmetric proportional control on frame-time EWMA. Fast to cut (protect
 * the player's frame), slow to recover (avoid oscillation). Capacity is
 * clamped [0, 1] and smoothed so one bad frame doesn't slam every bot to 0.
 */
public final class TickScheduler {

    private TickScheduler() {}

    /** Sessions per priority band — fixed at 5 per user spec. */
    public static final int BAND_SIZE = 5;

    /** Number of curve nodes / priority bands. */
    public static final int BAND_COUNT = 6;

    /** Idle bots cost this fraction of a pathing bot in the same band. */
    private static final double IDLE_WEIGHT = 0.3;

    /** Smoothed process CPU usage 0..1 (EWMA of getProcessCpuLoad). */
    private static double avgCpuLoad = 0.0;
    /** Estimated CPU cost of the mounted player alone — learned while zero
     *  background bots are ONLINE. The scheduler subtracts this to figure
     *  out how much cpu the bots themselves are burning. */
    private static double baselineCpu = 0.0;
    private static double capacity = 1.0;
    private static long lastRecomputeNanos;
    private static int lastOnlineBots = -1;

    /** Per-tick allocation cache — computed once per run() call. pct[band]
     *  holds the effective pathing-bot rate for that band after water-filling. */
    private static final double[] bandPctCache = new double[BAND_COUNT];
    private static long allocCacheNanos;

    /** Process-CPU ceiling as a 0..1 fraction. */
    private static double cpuFullSpeed() {
        int pct = NexusConfig.maxCpuPct();
        if (pct <= 0) pct = 40;
        return pct / 100.0;
    }

    /** Called once per SessionTicker.run() — updates the controller and
     *  re-computes the per-band allocation. `botCount` is how many
     *  *background* sessions have a world right now (mounted player excluded).
     *  When 0 we learn the baseline cpu so the controller can tell
     *  "mounted player alone already at cap" from "bots ate the budget".
     *
     *  The signal is the real MC-process CPU usage —
     *  com.sun.management.OperatingSystemMXBean.getProcessCpuLoad — not the
     *  frame rate. FPS was misleading because render is on its own thread:
     *  bots could starve the renderer down to 1 fps while world.tick loops
     *  kept full speed. CPU% maps directly onto "how much work are we
     *  allowed to give bot threads". */
    public static void tick(int botCount) {
        long now = System.nanoTime();
        double cpu = sampleCpu();
        if (cpu >= 0) {
            avgCpuLoad = avgCpuLoad * 0.85 + cpu * 0.15;
        }
        lastRecomputeNanos = now;

        if (botCount == 0) {
            // Mounted player alone — this is the floor we can never cut.
            baselineCpu = baselineCpu * 0.9 + avgCpuLoad * 0.1;
            capacity = 1.0;
        } else {
            double cap = cpuFullSpeed();
            double headroom = cap - baselineCpu;
            if (headroom < 0) headroom = 0;
            double botSpend = avgCpuLoad - baselineCpu;
            double err = headroom <= 0 ? 1.0 : (botSpend - headroom) / cap;
            if (err > 0) {
                capacity -= err * 0.30;    // drop fast when over budget
            } else {
                capacity += (-err) * 0.08; // recover toward headroom
            }
            double maxSpendable = cap > 0 ? headroom / cap : 0;
            if (capacity > maxSpendable) capacity = maxSpendable;
            if (capacity < 0.0) capacity = 0.0;
            if (capacity > 1.0) capacity = 1.0;
        }
        lastOnlineBots = botCount;

        allocCacheNanos = now;
        recomputeAllocation();
    }

    /** Process CPU% via com.sun.management.OperatingSystemMXBean.
     *  Returns -1 when unavailable.
     *
     *  IMPORTANT: the getProcessCpuLoad native call can block for SECONDS on
     *  Windows/JDK8 (seen live: client thread stuck inside
     *  OperatingSystemImpl.getProcessCpuLoad across two jstack dumps 3s
     *  apart → 1 fps). Never call it inline — sampleCpu() only reads the
     *  volatile cache a daemon thread refreshes. */
    private static java.lang.reflect.Method cpuLoadM;
    private static boolean cpuLoadTried;
    private static Object cpuBean;
    private static volatile boolean samplerStarted;
    /** Last sample result 0..1 — exposed for the UI diagnostic line. -1 = fail. */
    private static volatile double lastSampledCpu = -1;
    private static volatile long lastSampleNanos;

    private static void startSampler() {
        if (samplerStarted) return;
        samplerStarted = true;
        if (!cpuLoadTried) {
            cpuLoadTried = true;
            try {
                java.lang.management.OperatingSystemMXBean os =
                        java.lang.management.ManagementFactory.getOperatingSystemMXBean();
                cpuLoadM = os.getClass().getMethod("getProcessCpuLoad");
                cpuLoadM.setAccessible(true);
                cpuBean = os;
            } catch (Throwable t) {
                cpuLoadM = null;
                cpuBean = null;
            }
        }
        if (cpuLoadM == null) return;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    try {
                        lastSampledCpu =
                                ((Number) cpuLoadM.invoke(cpuBean)).doubleValue();
                        lastSampleNanos = System.nanoTime();
                    } catch (Throwable ignored) {
                        lastSampledCpu = -1;
                    }
                    try { Thread.sleep(250); } catch (InterruptedException e) { return; }
                }
            }
        }, "nexus-cpu-sampler");
        t.setDaemon(true);
        t.start();
    }

    /** Never blocks — returns the last sample the daemon refreshed, or -1 if
     *  none arrived yet / the MXBean isn't there. */
    private static double sampleCpu() {
        startSampler();
        return lastSampledCpu;
    }

    /** For the UI: -1 means the cpu sampler isn't working. */
    public static double lastSampledCpu() { return lastSampledCpu; }

    /** Priority band index for a session position — 0 is highest. */
    public static int bandOf(int sessionIndex) {
        return sessionIndex / BAND_SIZE;
    }

    /**
     * Compute the world-tick percentage for a session this tick.
     *
     * @param sessionIndex index into SessionManager.sessions()
     * @param pathing      true when the session's Baritone has an active path
     * @param perBotOverride -1 = follow the curve; 0..100 bypasses everything
     */
    public static int pctFor(int sessionIndex, boolean pathing, int perBotOverride) {
        if (perBotOverride >= 0) return perBotOverride;
        int band = bandOf(sessionIndex);
        // Lazy-fill if the overlay sampled pctFor before tick() ran.
        if (allocCacheNanos == 0) recomputeAllocation();
        double pct = band < BAND_COUNT ? bandPctCache[band]
                                       : bandPctCache[BAND_COUNT - 1];
        if (!pathing) pct *= IDLE_WEIGHT;
        if (pct > 100.0) pct = 100.0;
        if (pct < 0.0) pct = 0.0;
        return (int) pct;
    }

    /**
     * Proportional allocation with a priority floor. Each band's nominal
     * share is `curve[b]`; with adaptive on we scale every band by the
     * capacity — so the *relative* shape the user drew is preserved at
     * any load, while total spend tracks the controller.
     *
     * On top of the proportional share, band 0 (highest priority) gets a
     * minimum floor of BAND0_FLOOR pct — even at capacity≈0 the top-
     * priority bots keep ticking enough to stay responsive. Below band 0
     * there is no floor: when the frame is over budget the low bands starve
     * first, which is the entire point of priorities.
     */
    private static void recomputeAllocation() {
        float[] curve = NexusConfig.priorityCurve();
        double scale = NexusConfig.adaptiveTick() ? capacity : 1.0;
        double floor = NexusConfig.minBandPct();
        for (int b = 0; b < BAND_COUNT; b++) {
            double base = b < curve.length ? curve[b] : 0;
            double pct = base * scale;
            // Every band keeps a hard floor so no group ever stalls at 0.
            if (pct < floor) pct = floor;
            bandPctCache[b] = Math.min(100.0, pct);
        }
    }

    /** Current capacity 0..1 — exposed for the overlay UI. */
    public static double capacity() { return capacity; }
    /** Smoothed process CPU load 0..1 — for the overlay UI. */
    public static double avgCpuLoad() { return avgCpuLoad; }

    /** Reset controller state — used by the offline simulator test. */
    public static void resetForTest() {
        avgCpuLoad = 0.0;
        baselineCpu = 0.0;
        capacity = 1.0;
        lastRecomputeNanos = 0;
        allocCacheNanos = 0;
    }
}
