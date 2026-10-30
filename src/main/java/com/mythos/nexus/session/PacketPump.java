package com.mythos.nexus.session;

import com.mythos.nexus.core.NexusLog;
import com.mythos.nexus.core.NexusPlugin;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.SPacketAdvancementInfo;
import net.minecraft.network.play.server.SPacketAnimation;
import net.minecraft.network.play.server.SPacketCamera;
import net.minecraft.network.play.server.SPacketChangeGameState;
import net.minecraft.network.play.server.SPacketChat;
import net.minecraft.network.play.server.SPacketCollectItem;
import net.minecraft.network.play.server.SPacketCombatEvent;
import net.minecraft.network.play.server.SPacketCustomPayload;
import net.minecraft.network.play.server.SPacketCustomSound;
import net.minecraft.network.play.server.SPacketEffect;
import net.minecraft.network.play.server.SPacketExplosion;
import net.minecraft.network.play.server.SPacketMaps;
import net.minecraft.network.play.server.SPacketParticles;
import net.minecraft.network.play.server.SPacketPlayerListHeaderFooter;
import net.minecraft.network.play.server.SPacketRecipeBook;
import net.minecraft.network.play.server.SPacketSelectAdvancementsTab;
import net.minecraft.network.play.server.SPacketSoundEffect;
import net.minecraft.network.play.server.SPacketStatistics;
import net.minecraft.network.play.server.SPacketTabComplete;
import net.minecraft.network.play.server.SPacketTitle;
import net.minecraft.network.play.server.SPacketUpdateBossInfo;


/**
 * The packet dispatch queue: netty threads enqueue server packets here via
 * NexusHooks.onPacketRead, and the client thread drains the queue once per
 * tick inside each session's context (SessionTicker calls pump()).
 */
public final class PacketPump {

    private PacketPump() {}

    /** A packet queued for dispatch on the client thread. */
    private static final class QueuedPacket {
        final BotSession ctx;
        final Packet<INetHandler> packet;
        final INetHandler handler;
        QueuedPacket(BotSession ctx, Packet<INetHandler> packet, INetHandler handler) {
            this.ctx = ctx; this.packet = packet; this.handler = handler;
        }
    }

    static final int MAX_PENDING_PACKETS = 20000;

    /** Shared queue for the human/mounted session, drained on the client thread. */
    private static final java.util.concurrent.ArrayBlockingQueue<QueuedPacket>
            PENDING_PACKETS = new java.util.concurrent.ArrayBlockingQueue<>(MAX_PENDING_PACKETS);

    /** Per-session queues for bot sessions dispatched on their own threads.
     *  Keyed by the session — ConcurrentHashMap so enqueue from netty and
     *  dequeue from the bot thread never contend on a lock. */
    private static final java.util.concurrent.ConcurrentHashMap<BotSession,
            java.util.concurrent.ArrayBlockingQueue<QueuedPacket>> PER_SESSION =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Packets dropped because a queue was full — surfaced in the watchdog
     *  line so a stuck session is visible instead of silent. */
    private static final java.util.concurrent.atomic.AtomicLong PACKET_OVERFLOW =
            new java.util.concurrent.atomic.AtomicLong();

    /** Enqueue a packet for client-thread dispatch inside ctx's context.
     *  Called from NexusHooks.onPacketRead on the netty IO thread. Routes to
     *  the owning session's queue when it has a dedicated worker, else the
     *  shared queue (human/mounted + not-yet-online sessions). */
    public static void enqueue(BotSession ctx, Packet<INetHandler> packet,
                               INetHandler handler) {
        // Gating this on hasTicker() split one session's stream across two
        // threads when its ticker started mid-connect; the resulting
        // out-of-order spawn->move made getEntityByID return null and every
        // movement packet NPE'd. Packets queued before the ticker exists just
        // wait for pumpFor's first tick (drop() clears them on disconnect).
        if (ctx != null && !SessionManager.get().isForeground(ctx)) {
            java.util.concurrent.ArrayBlockingQueue<QueuedPacket> q = PER_SESSION.get(ctx);
            if (q == null) {
                java.util.concurrent.ArrayBlockingQueue<QueuedPacket> nq =
                        new java.util.concurrent.ArrayBlockingQueue<>(MAX_PENDING_PACKETS);
                java.util.concurrent.ArrayBlockingQueue<QueuedPacket> prev =
                        PER_SESSION.putIfAbsent(ctx, nq);
                q = prev != null ? prev : nq;
            }
            if (!q.offer(new QueuedPacket(ctx, packet, handler))) {
                overflow(ctx, packet);
                return;
            }
            bumpEnq(ctx, packet.getClass().getSimpleName());
            return;
        }
        if (!PENDING_PACKETS.offer(new QueuedPacket(ctx, packet, handler))) {
            overflow(ctx, packet);
        }
    }

    /** A full queue means this session stopped draining — its ticker died or
     *  its world never came up. Drop the newest packet (the backlog is already
     *  stale) and count it, so one broken session cannot OOM the process that
     *  hosts every other session. */
    private static void overflow(BotSession ctx, Packet<INetHandler> packet) {
        long n = PACKET_OVERFLOW.incrementAndGet();
        if (n % 500 == 1) {
            NexusPlugin.LOGGER.warn("packet queue full ({} dropped) — newest {} for {} dropped",
                    n, packet.getClass().getSimpleName(),
                    ctx == null ? "human" : ctx.username);
        }
    }

    /** Total packets dropped because a queue was full. */
    public static long overflowCount() { return PACKET_OVERFLOW.get(); }

    /** Drain every queued packet for `ctx` — called ON the session's own
     *  tick thread by BotTicker. Returns the number drained. */
    public static int pumpFor(BotSession ctx) {
        java.util.concurrent.ArrayBlockingQueue<QueuedPacket> q = PER_SESSION.get(ctx);
        if (q == null) return 0;
        int n = 0;
        QueuedPacket qp;
        while ((qp = q.poll()) != null) { n++; dispatch(qp); }
        return n;
    }

    /** Drop a session's queue — called on disconnect/remove so a dead worker
     *  doesn't accumulate packets for a world that's gone. */
    public static void drop(BotSession ctx) { PER_SESSION.remove(ctx); }

    /** Drain the shared client-thread queue (human/mounted session) — called
     *  from SessionTicker.run head on the client thread. */
    public static void pump() {
        QueuedPacket qp;
        while ((qp = PENDING_PACKETS.poll()) != null) {
            dispatch(qp);
        }
    }

    /** Dispatch one queued packet inside its session's context. Used by both
     *  the client-thread pump() (mounted session) and pumpFor() (bot thread).
     *  Runs the packet's processPacket inside ctx's context so all mc.* reads
     *  resolve to that session. */
    private static void dispatch(QueuedPacket qp) {
            final BotSession ctx = qp.ctx;
            if (ctx.closed) return;
            // A background session has no renderer/particle manager of its
            // own. These packets are purely visual and feeding them through
            // the shared client objects corrupts the mounted world's queues
            // (or crashes ParticleManager/RenderGlobal). They are safe to
            // drop; gameplay and player position packets remain queued.
            final boolean foreground = SessionManager.get().isForeground(ctx);
            if (!foreground && isBackgroundVisualPacket(qp.packet)) return;
            try {
                final Packet<INetHandler> p = qp.packet;
                final INetHandler h = qp.handler;
                bumpDisp(ctx, p.getClass().getName());
                String pnn = p.getClass().getName();
                if ((pnn.contains("SPacketEntity") || pnn.contains("SPacketDestroyEntities"))
                        && WORLD_MISMATCH_CHECKS.add(ctx.username)) {
                    try {
                        java.lang.reflect.Field fw = h.getClass().getDeclaredField("field_147300_g");
                        fw.setAccessible(true);
                        Object wc = fw.get(h);
                        NexusLog.session("[pkt-probe] sess=%s s.world=%s nhpc.world=%s same=%s",
                                ctx.username,
                                System.identityHashCode(ctx.world),
                                System.identityHashCode(wc),
                                wc == ctx.world);
                    } catch (Throwable t) {
                        NexusLog.session("[pkt-probe] sess=%s probe-fail %s", ctx.username, t);
                    }
                }
                if (ctx.world != null && pnn.contains("SPacketEntity")) {
                    try {
                        long now2 = System.currentTimeMillis();
                        Long last2 = PROBE2_LAST.putIfAbsent(ctx.username, now2);
                        boolean fire = last2 == null;
                        if (!fire && now2 - last2 >= 30_000L) {
                            PROBE2_LAST.put(ctx.username, now2);
                            fire = true;
                        }
                        if (fire) {
                            int total = 0, miss = 0;
                            StringBuilder pos = new StringBuilder();
                            for (net.minecraft.entity.Entity en : ctx.world.loadedEntityList) {
                                total++;
                                if (ctx.world.getEntityByID(en.getEntityId()) == null) miss++;
                                if (pos.length() < 400)
                                    pos.append(String.format("#%d(%s)@%.1f,%.1f,%.1f ",
                                            en.getEntityId(),
                                            en.getClass().getSimpleName(),
                                            en.posX, en.posY, en.posZ));
                            }
                            NexusLog.session("[pkt-probe2] sess=%s loaded=%d idMapMiss=%d entities=%s",
                                    ctx.username, total, miss, pos);
                        }
                    } catch (Throwable t) {
                        NexusLog.session("[pkt-probe2] sess=%s probe-fail %s", ctx.username, t);
                    }
                }
                final int[] p3ent = { -1 };
                if (ctx.world != null && !SessionManager.get().isForeground(ctx)
                        && (pnn.contains("SPacketEntityTeleport")
                            || pnn.contains("SPacketEntity$S")
                            || pnn.endsWith("SPacketEntity"))) {
                    try {
                        for (java.lang.reflect.Field f : p.getClass().getDeclaredFields()) {
                            if (f.getType() == int.class) { f.setAccessible(true);
                                p3ent[0] = f.getInt(p); break; }
                        }
                        if (p3ent[0] >= 0) {
                            net.minecraft.entity.Entity tgt = ctx.world.getEntityByID(p3ent[0]);
                            if (tgt != null)
                                P3_POS.put(ctx.username + "|" + p3ent[0],
                                        tgt.posX + "," + tgt.posY + "," + tgt.posZ);
                            else
                                NexusLog.session("[pkt-probe3] sess=%s pkt=%s entId=%d NOT-IN-MAP loaded=%d",
                                        ctx.username, p.getClass().getSimpleName(),
                                        p3ent[0], ctx.world.loadedEntityList.size());
                        }
                    } catch (Throwable t) {
                        NexusLog.session("[pkt-probe3] sess=%s pre-fail %s", ctx.username, t);
                    }
                }
                ctx.withContext(new Runnable() {
                    private volatile boolean done;
                    @Override public void run() {
                        if (done) return; done = true;
                        if ((Packet<?>) p instanceof SPacketChat) {
                            try {
                                SPacketChat cp2 = (SPacketChat) (Packet<?>) p;
                                net.minecraft.util.text.ITextComponent tc = cp2.getChatComponent();
                                if (tc != null) ctx.addChat(tc);
                            } catch (Throwable ignored) {}
                            // GuiIngame/CustomGuiNewChat are client-wide; a background
                            // session must not mutate the shared chat list off-thread.
                            if (!foreground) return;
                        }
                        try {
                            p.processPacket(h);
                        } catch (Throwable t) {
                            // A not-yet-known entity is expected; swallow the packet
                            // but count it, never hide it.
                            long failures = ++ctx.packetFailures;
                            if (failures == 1 || failures % 100 == 0) {
                                NexusLog.error("packet failed sess=%s type=%s failures=%d",
                                        t, ctx.username, p.getClass().getSimpleName(), failures);
                            }
                            // Zombie session: disconnect so reconnect can take over.
                            if (failures >= 500 && failures % 500 == 0) {
                                ctx.logEvent("ERROR", "packet failure circuit breaker ("
                                        + failures + ") — disconnecting");
                                ctx.disconnect("packet failure circuit breaker");
                            }
                        }
                        if (p3ent[0] >= 0 && ctx.world != null) {
                            try {
                                net.minecraft.entity.Entity t2 = ctx.world.getEntityByID(p3ent[0]);
                                String key = ctx.username + "|" + p3ent[0];
                                String before = P3_POS.remove(key);
                                if (t2 != null && before != null) {
                                    String after = t2.posX + "," + t2.posY + "," + t2.posZ;
                                    NexusLog.session("[pkt-probe3] sess=%s pkt=%s entId=%d %s -> %s",
                                            ctx.username, p.getClass().getSimpleName(),
                                            p3ent[0], before, after);
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                });
            } catch (Throwable t) {
                NexusPlugin.LOGGER.warn("pump packet failed for {}", ctx.username, t);
            }
    }

    /** Enqueue/dispatch counters dumped every 60s per session (see maybeDump). */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>
            ENQ = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>
            DISP = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Long>
            LAST_DUMP = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> WORLD_MISMATCH_CHECKS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> PROBE2 =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.ConcurrentHashMap<String, Long>
            PROBE2_LAST = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, String>
            P3_POS = new java.util.concurrent.ConcurrentHashMap<>();

    private static void bumpEnq(BotSession ctx, String simpleName) {
        if (ctx == null) return;
        String key = ctx.username + "|" + simpleName;
        ENQ.computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        maybeDump(ctx);
    }

    private static void bumpDisp(BotSession ctx, String fullName) {
        if (ctx == null) return;
        String key = ctx.username + "|" + fullName;
        DISP.computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
    }

    private static void maybeDump(BotSession ctx) {
        long now = System.currentTimeMillis();
        String k = ctx.username;
        Long last = LAST_DUMP.get(k);
        if (last != null && now - last < 60_000L) return;
        if (LAST_DUMP.put(k, now) != null && last == null) { /* raced first-set, fine */ }
        StringBuilder sb = new StringBuilder("[pkt-dump] sess=").append(k).append('\n');
        for (java.util.Map.Entry<String, java.util.concurrent.atomic.AtomicLong> e : ENQ.entrySet()) {
            if (e.getKey().startsWith(k + "|"))
                sb.append("  enq ").append(e.getKey().substring(k.length() + 1)).append('=').append(e.getValue().get()).append('\n');
        }
        for (java.util.Map.Entry<String, java.util.concurrent.atomic.AtomicLong> e : DISP.entrySet()) {
            if (e.getKey().startsWith(k + "|"))
                sb.append("  disp ").append(e.getKey().substring(k.length() + 1)).append('=').append(e.getValue().get()).append('\n');
        }
        NexusLog.session(sb.toString());
    }

    /** Resolve a method by MCP or SRG name (runtime is obfuscated). */
    private static java.lang.reflect.Method m(Class<?> c, String... names)
            throws NoSuchMethodException {
        for (String n : names) {
            try { java.lang.reflect.Method x = c.getMethod(n); x.setAccessible(true); return x; }
            catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(c.getName() + "." + names[0]);
    }

    private static boolean isBackgroundVisualPacket(Packet<?> packet) {
        // Every check here is a real type check. Name suffixes appear to work
        // in the dev environment but fail after ForgeGradle reobf (for example
        // SPacketParticles becomes runtime class jg), reopening the race.
        // These packets either write shared GUI/render/audio singletons directly
        // or invoke arbitrary client-side visual code. Gameplay/state packets
        // remain on the bot thread; these are deliberately dropped for a
        // background session because that session has no visible client.
        return packet instanceof SPacketUpdateBossInfo
                || packet instanceof SPacketParticles
                || packet instanceof SPacketEffect
                || packet instanceof SPacketSoundEffect
                || packet instanceof SPacketCustomSound
                || packet instanceof SPacketExplosion
                || packet instanceof SPacketAnimation
                || packet instanceof SPacketCollectItem
                || packet instanceof SPacketMaps
                || packet instanceof SPacketTitle
                || packet instanceof SPacketPlayerListHeaderFooter
                || packet instanceof SPacketTabComplete
                || packet instanceof SPacketRecipeBook
                || packet instanceof SPacketSelectAdvancementsTab
                || packet instanceof SPacketCamera
                || packet instanceof SPacketChangeGameState
                || packet instanceof SPacketCombatEvent
                || packet instanceof SPacketCustomPayload
                || packet instanceof SPacketAdvancementInfo
                || packet instanceof SPacketStatistics;
    }
}
