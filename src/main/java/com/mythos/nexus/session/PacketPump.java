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

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The packet dispatch queue: netty threads enqueue server packets here via
 * NexusHooks.onPacketRead, and the client thread drains the queue once per
 * tick inside each session's context (SessionTicker calls pump()).
 * Extracted from NexusHooks.
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

    /** Legacy shared queue — kept for the human/mounted session which still
     *  drains on the client thread. Each background bot instead owns its own
     *  queue drained by its dedicated tick thread (see BotTicker) so a worker
     *  can never steal another session's packets mid-dispatch. */
    private static final ConcurrentLinkedQueue<QueuedPacket>
            PENDING_PACKETS = new ConcurrentLinkedQueue<>();

    /** Per-session queues for bot sessions dispatched on their own threads.
     *  Keyed by the session — ConcurrentHashMap so enqueue from netty and
     *  dequeue from the bot thread never contend on a lock. */
    private static final java.util.concurrent.ConcurrentHashMap<BotSession,
            ConcurrentLinkedQueue<QueuedPacket>> PER_SESSION =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Enqueue a packet for client-thread dispatch inside ctx's context.
     *  Called from NexusHooks.onPacketRead on the netty IO thread. Routes to
     *  the owning session's queue when it has a dedicated worker, else the
     *  shared queue (human/mounted + not-yet-online sessions). */
    public static void enqueue(BotSession ctx, Packet<INetHandler> packet,
                               INetHandler handler) {
        // Route every packet for a NON-foreground session to that session's
        // own queue, drained serially by its dedicated tick thread. Gating on
        // hasTicker() — the old behaviour — split one session's packet stream
        // across two threads the moment its ticker started mid-connect: the
        // client-thread pump() then dispatched a background bot's spawn/move
        // packets inside the bot's withContext (a real mc.* swap on the render
        // thread) while its own thread dispatched the tail — out-of-order
        // spawn→move meant getEntityByID returned null and every movement
        // packet NPE'd until the circuit-breaker killed the bot.
        // Queued-before-ticker packets simply wait: pumpFor drains them the
        // first tick once the world is online, and drop() clears them on
        // disconnect so nothing leaks for a session that never finishes
        // connecting.
        if (ctx != null && !SessionManager.get().isForeground(ctx)) {
            ConcurrentLinkedQueue<QueuedPacket> q = PER_SESSION.get(ctx);
            if (q == null) {
                ConcurrentLinkedQueue<QueuedPacket> nq = new ConcurrentLinkedQueue<>();
                ConcurrentLinkedQueue<QueuedPacket> prev =
                        PER_SESSION.putIfAbsent(ctx, nq);
                q = prev != null ? prev : nq;
            }
            q.add(new QueuedPacket(ctx, packet, handler));
            bumpEnq(ctx, packet.getClass().getSimpleName());
            return;
        }
        PENDING_PACKETS.add(new QueuedPacket(ctx, packet, handler));
    }

    /** Drain every queued packet for `ctx` — called ON the session's own
     *  tick thread by BotTicker. Returns the number drained. */
    public static int pumpFor(BotSession ctx) {
        ConcurrentLinkedQueue<QueuedPacket> q = PER_SESSION.get(ctx);
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
                // One-shot identity probe: is the world this handler writes to
                // the same object as s.world? First entity-movement packet per
                // session logs both identity hashes.
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
                // Probe 2: loadedEntityList vs entitiesById consistency — if
                // spawns land in loadedEntityList but not the id map, every
                // getEntityByID in handleEntity/Teleport/Destroy returns null
                // and the world looks frozen while still showing entities.
                // Periodic: every 30s per session, plus a per-entity mutation
                // snapshot so we see whether handleEntity writes actually stick.
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
                // Probe 3: for move/teleport packets, snapshot the target's
                // position BEFORE and AFTER processPacket to prove whether the
                // handler writes. SPacketEntity* has getEntity(World) accessor
                // but the entityId field is private — read via reflection.
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
                        // Intercept chat packets before dispatch — feed the
                        // session's chat ring buffer for aggregation.
                        if ((Packet<?>) p instanceof SPacketChat) {
                            try {
                                SPacketChat cp2 = (SPacketChat) (Packet<?>) p;
                                net.minecraft.util.text.ITextComponent tc = cp2.getChatComponent();
                                if (tc != null) ctx.addChat(tc);
                            } catch (Throwable ignored) {}
                            // GuiIngame and CustomGuiNewChat are client-wide. Keep
                            // background chat in the per-session ring, but never
                            // let vanilla mutate the shared chat list off-thread.
                            if (!foreground) return;
                        }
                        try {
                            p.processPacket(h);
                        } catch (Throwable t) {
                            // Entity movement/block packets can legitimately
                            // arrive for an entity that was not present in a
                            // session's local WorldClient yet. Discard that
                            // packet instead of transitioning the session to
                            // ERROR and starving its later corrections.
                            // Keep draining later packets, but never hide failures.
                            long failures = ++ctx.packetFailures;
                            if (failures == 1 || failures % 100 == 0) {
                                NexusLog.error("packet failed sess=%s type=%s failures=%d",
                                        t, ctx.username, p.getClass().getSimpleName(), failures);
                            }
                            // Circuit breaker: a session that can't process
                            // any packet is a zombie — disconnect it so the
                            // reconnect logic or the user can restart clean.
                            if (failures >= 500 && failures % 500 == 0) {
                                ctx.logEvent("ERROR", "packet failure circuit breaker ("
                                        + failures + ") — disconnecting");
                                ctx.disconnect("packet failure circuit breaker");
                            }
                        }
                        // Probe 3 post-check: did the handler mutate pos?
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

    // dispatch). Dumps once per 60s per session to session.log so we can see
    // whether entity packets are being consumed before the queue, dropped at
    // enqueue, or dying inside dispatch. ----
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
