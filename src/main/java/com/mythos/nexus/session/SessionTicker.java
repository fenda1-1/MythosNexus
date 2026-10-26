package com.mythos.nexus.session;

import com.mythos.nexus.core.NexusConfig;
import com.mythos.nexus.core.NexusPlugin;
import com.mythos.nexus.core.NexusRefs;
import net.minecraft.network.Packet;

/**
 * Per-session tick pumping: once per Minecraft.runTick, drain the packet
 * queue, flush deferred mounts, and drive every online background session's
 * world tick + registered per-session tick hooks inside its own context.
 * Extracted from NexusHooks.runSessionTicks.
 */
public final class SessionTicker {

    private SessionTicker() {}

    /**
     * Registered per-session tick hooks — MythosScript registers via
     * NexusHooks.registerTickHook through reflection (different jar, no
     * compile dep). Hooks run inside each session's context so they see
     * that bot's world/player.
     */
    static final java.util.List<Runnable> TICK_HOOKS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Lightweight hooks that run EVERY tickOnce iteration — not gated by the
     * adaptive tick-rate accumulator. For cheap per-iteration work that must
     * not be slowed by a throttled session rate: DelayScheduler's wall-clock
     * countdown (a "2 tick" delay at 20% rate would otherwise execute up to
     * 5x late). Registered via NexusHooks.registerFastTickHook reflection.
     */
    static final java.util.List<Runnable> FAST_HOOKS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void registerTickHook(Runnable r) {
        TICK_HOOKS.add(r);
    }

    public static void registerFastTickHook(Runnable r) {
        FAST_HOOKS.add(r);
    }

    /** Last-persisted online set hash — skip file IO when nothing changed. */
    private static int lastOnlineHash = -1;

    /** Snapshot the online session set to NexusConfig whenever it changes. */
    private static void persistOnlineSnapshot(SessionManager mgr) {
        // Cheap: hash the set first, only write JSON when it differs.
        int hash = 0;
        java.util.List<BotSession> all = mgr.sessions();
        int bots = 0;
        for (BotSession s : all) {
            if (s.id == 0) continue; // human
            bots++;
            if (s.state == BotSession.State.ONLINE) hash = 31 * hash + s.username.hashCode();
        }

        if (bots == 0) return;
        if (hash != lastOnlineHash) {
            lastOnlineHash = hash;
            NexusConfig.saveLastOnline(all);
        }
    }

    /**
     * Run all registered per-session ticks for every online session,
     * each inside its own withContext so MythosScript sees that bot's
     * world/player. Called from Minecraft.runTick head injection.
     * Order: mounted session LAST so its visuals win.
     */
    public static void run() {
        PacketPump.pump(); // drain packet queue first — before session ticks
        // 发布人物同步的源快照。
        try {
            SyncEngine.clientTick();
        } catch (Throwable t) {
            com.mythos.nexus.core.NexusPlugin.LOGGER.warn("sync source tick failed", t);
        }
        // 数据包级同步：把主控发出的动作包重放到同组账号的连接上。
        try {
            PacketSync.tick();
        } catch (Throwable t) {
            com.mythos.nexus.core.NexusPlugin.LOGGER.warn("packet sync tick failed", t);
        }
        SessionManager mgr = SessionManager.get();
        // Persist the online set whenever it changes so a JVM restart can
        // re-add + reconnect the same multi-box roster.
        persistOnlineSnapshot(mgr);
        // A mount deferred because the bot was mid-respawn completes here
        // once the new EntityPlayerSP is alive and the server has sent its
        // position — mounting a corpse was the control-freeze bug.
        mgr.flushPendingMount();
        // Render queued live-viewport previews — GL work here, not in drawScreen.
        // Guard the whole pass: a throw must never escape into Minecraft.runTick
        // (it aborts the frame and can leave the shared Tessellator mid-batch →
        // "Already building!" crash on the next framebufferRender blit).
        try {
            com.mythos.nexus.session.LiveViewportRenderer.renderAllTick();
        } catch (Throwable t) {
            com.mythos.nexus.core.NexusPlugin.LOGGER.warn("live-viewport tick failed", t);
        }
        java.util.List<BotSession> all = mgr.sessions();
        // TickScheduler.tick() must run even with zero bots — the frame-time
        // EWMA keeps tracking the mounted player's fps so capacity stays
        // correct when the first bot comes online (otherwise capacity stays
        // frozen at whatever it was when the last bot left, typically 0).
        int onlineBots = 0;
        for (BotSession s : all)
            if (s.state == BotSession.State.ONLINE && s.world != null
                    && !mgr.isForeground(s)) onlineBots++;
        TickScheduler.tick(onlineBots);
        // Drain any world-event side-effects the bot tick threads staged —
        // each background WorldClient has a queue-only listener stub that
        // records events instead of posting them to the shared RenderGlobal;
        // replaying them here on the client thread keeps markAndNotifyBlock /
        // playEvent / sound off the render queue.
        EventQueueStub.drainAll();
        if (all.isEmpty()) return;
        // Background bots now tick on their own BotTicker threads — the client
        // thread only drives the mounted session here (vanilla handles the
        // rest). The mounted session is the human (mounted==null) or the
        // mounted bot.
        BotSession mounted = mgr.mounted();
        if (mounted != null && mounted.world != null
                && mounted.state == BotSession.State.ONLINE) {
            tickMounted(mounted);
        }
    }

    /**
     * The mounted session's client-thread tick — Forge tick hooks still run
     * inside its context so per-session MythosScript state stays attributed.
     * Vanilla's own world tick runs elsewhere in runTick; we only fire the
     * per-session hooks the mounted session is owed.
     */
    private static void tickMounted(BotSession ctx) {
        for (Runnable hook : TICK_HOOKS) {
            try {
                hook.run();
            } catch (Throwable t) {
                com.mythos.nexus.core.NexusPlugin.LOGGER.warn(
                        "mounted session tick hook failed for {}", ctx.username, t);
            }
        }
    }

    /** True when the session's Baritone has an active path — drives the
     *  world-tick rate (pathing % when active, idle % when parked).
     *  Baritone instances live inside MythosScript's BaritoneProvider map
     *  keyed by username; SessionTicker can't reach the provider directly
     *  (different jar), so we bridge through the reflective lookup that
     *  NexusTickBridge already performs — it stashes the session's IBaritone
     *  on ctx.baritone the first time it drives that session. */
    public static boolean hasActivePath(BotSession ctx) {
        Object bar = ctx.baritone;
        if (bar == null) {
            // Lazy-resolve once: ask the BaritoneProvider map directly via the
            // same reflection path NexusTickBridge uses — covers the window
            // before the first tickAll fires for this session.
            bar = resolveBaritone(ctx);
            if (bar != null) ctx.baritone = bar;
            else return false;
        }
        try {
            if (ctx.baritonePathingM == null) {
                ctx.baritonePathingM = bar.getClass().getMethod("getPathingBehavior");
            }
            Object pb = ctx.baritonePathingM.invoke(bar);
            if (pb == null) return false;
            if (ctx.baritonePathCurrentM == null) {
                ctx.baritonePathCurrentM = pb.getClass().getMethod("getCurrent");
            }
            Object cur = ctx.baritonePathCurrentM.invoke(pb);
            return cur != null;
        } catch (Throwable t) {
            NexusRefs.warnOnce("ticker:hasActivePath", t,
                    "SessionTicker.hasActivePath: reflection on %s failed, treating as idle",
                    bar.getClass().getName());
            return false;   // any reflection failure = treat as idle (cheap)
        }
    }

    /** BaritoneAPI.getProvider() and the provider's sessionBaritones field.
     *  Both are resolved once: hasActivePath calls this every tick for every
     *  session that has no baritone yet, and neither handle ever changes.
     *  volatile = benign race (two threads may resolve the same handle). */
    private static volatile java.lang.reflect.Method baritoneGetProvider;
    private static volatile java.lang.reflect.Field sessionBaritonesField;

    /** Look up the per-session IBaritone through MythosScript's
     *  BaritoneProvider.sessionBaritones map — a read-only ConcurrentHashMap
     *  get, no creation. Returns null when the session has no baritone yet. */
    private static Object resolveBaritone(BotSession ctx) {
        try {
            java.lang.reflect.Method getProvider = baritoneGetProvider;
            if (getProvider == null) {
                getProvider = NexusRefs.method(
                        NexusRefs.cls(NexusRefs.BARITONE_API), "getProvider");
                baritoneGetProvider = getProvider;
            }
            Object provider = getProvider.invoke(null);
            if (provider == null) return null;
            java.lang.reflect.Field f = sessionBaritonesField;
            if (f == null) {
                f = NexusRefs.field(provider.getClass(), "sessionBaritones");
                f.setAccessible(true);
                sessionBaritonesField = f;
            }
            java.util.Map<?, ?> map = (java.util.Map<?, ?>) f.get(provider);
            return map == null ? null : map.get(ctx.username);
        } catch (Throwable t) {
            NexusRefs.warnOnce("ticker:resolveBaritone", t,
                    "SessionTicker.resolveBaritone: cannot reach BaritoneProvider.sessionBaritones");
            return null;
        }
    }

    /**
     * Send CPacketClientStatus(PERFORM_RESPAWN) on a session's own
     * NetworkManager — same logic as the mount-path respawn but callable
     * while the session is in the background. Safe to call repeatedly:
     * the server ignores extra PERFORM_RESPAWN packets for a live player.
     */
    public static void sendRespawnPacket(BotSession s) {
        try {
            // Deduplicate: don't spam every tick — remember the last send
            // per session via a simple timestamp check on the session's
            // own lastAutoRespawnNanos field if it exists, else just send.
            long now = System.nanoTime();
            if (now - s.lastAutoRespawnNanos < 2_000_000_000L) return;
            s.lastAutoRespawnNanos = now;

            Class<?> pkt = findClass(
                    "net.minecraft.network.play.client.CPacketClientStatus", "lb");
            Class<?> state = pkt.getDeclaredClasses()[0];
            Object perf = state.getEnumConstants()[0];
            Object respawn = pkt.getDeclaredConstructor(state).newInstance(perf);
            if (s.connection == null) return;
            // sendPacket(Packet) is `a(Lht;)V` — exactly one arg, the packet.
            // The two-arg overload takes (Packet, GenericFutureListener, ...)
            // and must not match — require exactly one parameter AND its type
            // to be Packet or a supertype of our concrete packet class.
            for (java.lang.reflect.Method m : s.connection.getClass().getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 1) continue;
                if (!ps[0].isAssignableFrom(respawn.getClass())) continue;
                m.setAccessible(true);
                m.invoke(s.connection, respawn);
                NexusPlugin.LOGGER.info("bg auto-respawn sent for {}", s.username);
                return;
            }
            NexusPlugin.LOGGER.warn("bg auto-respawn: no sendPacket(Packet) match for {}", s.username);
        } catch (Throwable t) {
            NexusPlugin.LOGGER.warn("bg auto-respawn failed for {}", s.username, t);
        }
    }

    private static Class<?> findClass(String mcp, String obf) throws ClassNotFoundException {
        try { return Class.forName(mcp); } catch (ClassNotFoundException e) { return Class.forName(obf); }
    }

    /**
     * 让某个会话寻路到方块坐标。优先走 Baritone（同一个 IBaritone 实例，
     * 与其余寻路功能共享状态），拿不到 Baritone 时返回 false，由调用方
     * 自行退化成直线靠近。
     *
     * 反射是因为 Mythos-Nexus 与 MythosScript/Baritone 分属不同 jar，
     * 编译期没有依赖。
     */
    public static boolean sendGoto(BotSession s, int x, int y, int z) {
        Object bar = baritone(s);
        if (bar == null) return false;
        try {
            Object pb = bar.getClass().getMethod("getCustomGoalProcess").invoke(bar);
            if (pb == null) return false;
            // setGoalAndPath(Goal) — Goal is built reflectively because its
            // concrete type (GoalBlock) is Baritone's, not ours.
            Class<?> goalBlock = NexusRefs.cls("baritone.api.pathing.goals.GoalBlock");
            Object goal = goalBlock.getConstructor(int.class, int.class, int.class)
                    .newInstance(x, y, z);
            for (java.lang.reflect.Method m : pb.getClass().getMethods()) {
                if (!"setGoalAndPath".equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 1) continue;
                m.invoke(pb, goal);
                return true;
            }
            return false;
        } catch (Throwable t) {
            NexusRefs.warnOnce("ticker:sendGoto", t, "SessionTicker.sendGoto failed");
            return false;
        }
    }

    /** 取消该会话当前的寻路（同步引擎切回镜像时调用）。 */
    public static void sendStop(BotSession s) {
        Object bar = baritone(s);
        if (bar == null) return;
        try {
            Object pb = bar.getClass().getMethod("getPathingBehavior").invoke(bar);
            if (pb != null) {
                for (java.lang.reflect.Method m : pb.getClass().getMethods()) {
                    if (!"cancelEverything".equals(m.getName())) continue;
                    if (m.getParameterTypes().length != 0) continue;
                    m.invoke(pb);
                    break;
                }
            }
        } catch (Throwable t) {
            NexusRefs.warnOnce("ticker:sendStop", t, "SessionTicker.sendStop failed");
        }
    }

    /** Cached-or-resolved IBaritone for this session. */
    private static Object baritone(BotSession s) {
        Object bar = s.baritone;
        if (bar == null) {
            bar = resolveBaritone(s);
            if (bar != null) s.baritone = bar;
        }
        return bar;
    }
}
