package com.mythos.nexus.session;

import com.mythos.nexus.core.NexusLog;
import com.mythos.nexus.core.NexusRefs;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Per-session IWorldEventListener that REPLACES the shared RenderGlobal
 * listener on a background bot's WorldClient.
 *
 * Why it exists: World.eventListeners normally forwards block/light/entity/
 * sound/particle notifications into the RenderGlobal of the mounted session.
 * A background bot ticking on its own thread would otherwise write into that
 * shared RenderGlobal's queues (render-chunk rebuilds, sound events,
 * particles) — exactly the cross-thread write side-effect the parallel-tick
 * design forbids. This stub records those events on a thread-safe queue and
 * the CLIENT thread drains them once per frame into the real RenderGlobal
 * (and other listeners), preserving rendering without the unsafe writes.
 *
 * RenderGlobal is only ever touched on the client thread (the same thread
 * that ran the original serial SessionTicker), so nothing here needs to be
 * thread-safe beyond the queue itself.
 */
public final class EventQueueStub
        implements net.minecraft.world.IWorldEventListener {

    /** A captured world-event, tagged with the session that produced it. */
    static final class Pending {
        final BotSession session;
        final int kind;
        int x, y, z, x2, y2, z2, i;
        double dx, dy, dz, dx2, dy2, dz2;
        float f1, f2;
        net.minecraft.util.math.BlockPos pos;
        net.minecraft.block.state.IBlockState oldS, newS;
        net.minecraft.entity.Entity entity;
        net.minecraft.entity.player.EntityPlayer player;
        net.minecraft.util.SoundEvent sound;
        net.minecraft.util.SoundCategory cat;
        int[] ints;
        boolean bool1, bool2;

        Pending(BotSession s, int kind) { session = s; this.kind = kind; }
    }

    private static final ConcurrentLinkedQueue<Pending> QUEUE =
            new ConcurrentLinkedQueue<>();

    private final BotSession session;

    public EventQueueStub(BotSession session) { this.session = session; }


    public void notifyBlockUpdate(net.minecraft.world.World w,
            net.minecraft.util.math.BlockPos p,
            net.minecraft.block.state.IBlockState oldS,
            net.minecraft.block.state.IBlockState newS, int flags) {
        Pending e = new Pending(session, 0);
        e.pos = p; e.oldS = oldS; e.newS = newS; e.i = flags;
        QUEUE.add(e);
    }

    public void notifyLightSet(net.minecraft.util.math.BlockPos p) {
        Pending e = new Pending(session, 1); e.pos = p; QUEUE.add(e);
    }

    public void markBlockRangeForRenderUpdate(int x1, int y1, int z1,
            int x2, int y2, int z2) {
        Pending e = new Pending(session, 2);
        e.x = x1; e.y = y1; e.z = z1; e.x2 = x2; e.y2 = y2; e.z2 = z2;
        QUEUE.add(e);
    }

    public void playSoundToAllNearExcept(
            net.minecraft.entity.player.EntityPlayer p,
            net.minecraft.util.SoundEvent s,
            net.minecraft.util.SoundCategory c,
            double x, double y, double z, float v, float pt) {
        Pending e = new Pending(session, 3);
        e.player = p; e.sound = s; e.cat = c;
        e.dx = x; e.dy = y; e.dz = z; e.f1 = v; e.f2 = pt;
        QUEUE.add(e);
    }

    public void playRecord(net.minecraft.util.SoundEvent s,
            net.minecraft.util.math.BlockPos p) {
        Pending e = new Pending(session, 4); e.sound = s; e.pos = p; QUEUE.add(e);
    }

    public void spawnParticle(int id, boolean ignoreRange,
            double x, double y, double z, double xv, double yv, double zv,
            int... data) {
        Pending e = new Pending(session, 5);
        e.i = id; e.bool1 = ignoreRange;
        e.dx = x; e.dy = y; e.dz = z; e.dx2 = xv; e.dy2 = yv; e.dz2 = zv;
        e.ints = data;
        QUEUE.add(e);
    }

    public void spawnParticle(int id, boolean ignoreRange, boolean minimal,
            double x, double y, double z, double xv, double yv, double zv,
            int... data) {
        Pending e = new Pending(session, 6);
        e.i = id; e.bool1 = ignoreRange; e.bool2 = minimal;
        e.dx = x; e.dy = y; e.dz = z; e.dx2 = xv; e.dy2 = yv; e.dz2 = zv;
        e.ints = data;
        QUEUE.add(e);
    }

    public void onEntityAdded(net.minecraft.entity.Entity ent) {
        Pending e = new Pending(session, 7); e.entity = ent; QUEUE.add(e);
    }

    public void onEntityRemoved(net.minecraft.entity.Entity ent) {
        Pending e = new Pending(session, 8); e.entity = ent; QUEUE.add(e);
    }

    public void broadcastSound(int id, net.minecraft.util.math.BlockPos p,
            int data) {
        Pending e = new Pending(session, 9);
        e.i = id; e.pos = p; e.x = data; QUEUE.add(e);
    }

    public void playEvent(net.minecraft.entity.player.EntityPlayer p, int type,
            net.minecraft.util.math.BlockPos pos, int data) {
        Pending e = new Pending(session, 10);
        e.player = p; e.i = type; e.pos = pos; e.x = data;
        QUEUE.add(e);
    }

    public void sendBlockBreakProgress(int id, net.minecraft.util.math.BlockPos p,
            int progress) {
        Pending e = new Pending(session, 11);
        e.i = id; e.pos = p; e.x = progress; QUEUE.add(e);
    }


    /**
     * Called once per client frame from SessionTicker. Replays every captured
     * event into the REAL listeners on the same WorldClient — but ONLY if the
     * producing session is the mounted one (the only session whose world the
     * RenderGlobal is actually showing). Events from background sessions are
     * dropped: their world isn't on screen, so spending render time on them
     * is waste.
     *
     * The real listeners live on the session's WorldClient.eventListeners
     * list MINUS this stub — we captured the original RenderGlobal-backed
     * listener before swapping. Replaying into them here is exactly what the
     * old serial tick did implicitly by letting the events fire on the
     * client thread.
     */
    public static void drainAll() {
        Pending e;
        SessionManager mgr = SessionManager.get();
        int n = 0;
        while ((e = QUEUE.poll()) != null) {
            n++;
            try {
                // Only forward events the mounted session produced — anything
                // from a background session targets a world that isn't on
                // screen. Dropping keeps the render thread light.
                if (!mgr.isForeground(e.session)) continue;
                replay(e);
            } catch (Throwable t) {
                NexusLog.error("event-drain failed kind=" + e.kind, t);
            }
        }
    }

    /** World.eventListeners is protected — resolved once, MCP+SRG names. */
    private static java.lang.reflect.Field EVENT_LISTENERS;
    @SuppressWarnings("unchecked")
    private static List<net.minecraft.world.IWorldEventListener> listenersOf(
            net.minecraft.world.World w) {
        java.lang.reflect.Field f = EVENT_LISTENERS;
        if (f == null) {
            try {
                try { f = net.minecraft.world.World.class.getDeclaredField("eventListeners"); }
                catch (NoSuchFieldException e) { f = net.minecraft.world.World.class.getDeclaredField("field_73021_x"); }
                f.setAccessible(true);
            } catch (Throwable t) {
                // Not latched: resolution is retried, but the failure is only
                // reported once — this path runs per replayed event.
                if (NexusRefs.firstTime("eventstub:eventListeners")) {
                    NexusLog.error("resolve World.eventListeners failed", t);
                }
                return java.util.Collections.emptyList();
            }
            EVENT_LISTENERS = f;
        }
        try { return (List<net.minecraft.world.IWorldEventListener>) f.get(w); }
        catch (Throwable t) {
            NexusRefs.warnOnce("eventstub:get:eventListeners", t,
                    "EventQueueStub: World.eventListeners get failed, replay skipped");
            return java.util.Collections.emptyList();
        }
    }

    /** Replay one captured event into the session world's real listeners. */
    private static void replay(Pending e) {
        net.minecraft.world.World w = e.session.world;
        if (w == null) return;
        List<net.minecraft.world.IWorldEventListener> listeners = listenersOf(w);
        for (net.minecraft.world.IWorldEventListener l : listeners) {
            if (l instanceof EventQueueStub) continue; // don't re-feed ourselves
            switch (e.kind) {
                case 0: l.notifyBlockUpdate(w, e.pos, e.oldS, e.newS, e.i); break;
                case 1: l.notifyLightSet(e.pos); break;
                case 2: l.markBlockRangeForRenderUpdate(e.x, e.y, e.z, e.x2, e.y2, e.z2); break;
                case 3: l.playSoundToAllNearExcept(e.player, e.sound, e.cat,
                        e.dx, e.dy, e.dz, e.f1, e.f2); break;
                case 4: l.playRecord(e.sound, e.pos); break;
                case 5: l.spawnParticle(e.i, e.bool1, e.dx, e.dy, e.dz,
                        e.dx2, e.dy2, e.dz2, e.ints); break;
                case 6: l.spawnParticle(e.i, e.bool1, e.bool2, e.dx, e.dy, e.dz,
                        e.dx2, e.dy2, e.dz2, e.ints); break;
                case 7: l.onEntityAdded(e.entity); break;
                case 8: l.onEntityRemoved(e.entity); break;
                case 9: l.broadcastSound(e.i, e.pos, e.x); break;
                case 10: l.playEvent(e.player, e.i, e.pos, e.x); break;
                case 11: l.sendBlockBreakProgress(e.i, e.pos, e.x); break;
            }
        }
    }
}
