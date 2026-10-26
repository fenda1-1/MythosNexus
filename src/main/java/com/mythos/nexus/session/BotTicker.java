package com.mythos.nexus.session;

import com.mythos.nexus.core.NexusLog;
import com.mythos.nexus.core.NexusPlugin;

/**
 * The dedicated tick worker for ONE background bot session.
 *
 * Replaces the serial per-session slice SessionTicker used to run on the
 * client thread. The worker runs on its own thread so a bot's
 * world.tick + packet dispatch + MythosScript hooks execute concurrently
 * with the mounted session's render loop — the entire point of the
 * parallel-tick refactor.
 *
 * Thread model:
 *   - started lazily the first time a session goes ONLINE with a world;
 *   - binds NexusCtx to the owning session once at thread start — every
 *     rewritten field read inside the tick then resolves to this session;
 *   - each loop iteration drains the session's packet queue then runs the
 *     tick slice (playerController.updateController + world.tick +
 *     updateEntities + TICK_HOOKS) under the same adaptive down-clock that
 *     used to throttle background bots;
 *   - sleeps ~50ms per iteration to hold ~20 TPS, self-terminates when the
 *     session disconnects / unmounts / is removed.
 *
 * The mounted session and the human never get a BotTicker — they keep the
 * vanilla client-thread tick.
 */
final class BotTicker implements Runnable {

    /** The session this worker drives. */
    private final BotSession session;

    /** Target tick period — matches Minecraft's 20 TPS. */
    private static final long TICK_NS = 50_000_000L;

    BotTicker(BotSession session) {
        this.session = session;
    }

    /**
     * Entry point — runs on a thread named nexus-bot-<username>. Binds the
     * context once, then loops until the session leaves the ONLINE-with-world
     * state or is closed.
     */
    @Override
    public void run() {
        NexusCtx.bind(session);
        try {
            NexusLog.session("[ticker] start {}", session.username);
            while (keepRunning() && !session.tickerStopRequested()) {
                long start = System.nanoTime();
                try {
                    tickOnce();
                } catch (Throwable t) {
                    // One bad tick must never kill the worker or the session.
                    NexusLog.error("[ticker] {} tick failed: {}", t,
                            session.username, t.toString());
                }
                sleepFor(start);
            }
        } finally {
            // The session may still dispatch a packet or two after this —
            // PacketPump.drop in disconnect() frees its queue.
            NexusCtx.unbind();
            synchronized (session) {
                if (session.ticker == Thread.currentThread()) session.ticker = null;
            }
            NexusLog.session("[ticker] stop {}", session.username);
        }
    }

    /** Continue while the session is alive and may still have packets/ticks to
     *  run. CONNECTING is included on purpose: the world doesn't exist yet, and
     *  it is THIS thread's pumpFor that dispatches the SPacketJoinGame which
     *  builds it. Requiring ONLINE+world here would deadlock the login (world
     *  can only be created by a packet this thread hasn't run yet). tickOnce
     *  guards the actual world.tick with ctx.world != null. */
    private boolean keepRunning() {
        return !session.closed
                && (session.state == BotSession.State.CONNECTING
                    || session.state == BotSession.State.ONLINE);
    }

    /**
     * One tick slice — the set of work SessionTicker used to run per session.
     * Everything here executes inside the bound NexusCtx, so mc.player /
     * mc.world / mc.playerController / currentScreen reads inside
     * tick-reachable (rewritten) classes resolve to THIS session, never the
     * mounted one.
     */
    private void tickOnce() {
        final BotSession ctx = session;
        final SessionManager mgr = SessionManager.get();

        // If this session became mounted while we were mid-tick, stop — the
        // client thread owns mounted-session ticks and double-ticking a live
        // world corrupts it.
        if (mgr.isForeground(ctx)) return;

        PacketPump.pumpFor(ctx);

        // These were enqueued by foreign threads and MUST run here on the
        // session's own tick thread — running them inline on an MCP pool
        // thread raced the single-threaded iteration inside PathingControl
        // Manager / feature managers and produced ConcurrentModification
        // Exception. Serializing on the tick thread restores the invariant.
        ctx.drainTasks();

        // Per-bot override wins; otherwise idle bots (no active path) drop to
        // the global idle rate so parked sessions don't burn a full 20 TPS
        // each under a 15+ bot load. Pathing bots stay at 100 unless the user
        // explicitly overrides — combat/pathing must not be degraded.
        ctx.tickSkipCount++;
        String key = ctx.username + "@" + ctx.serverHost + ":" + ctx.serverPort;
        boolean hasPath = SessionTicker.hasActivePath(ctx);
        int perBotOverride = hasPath
                ? com.mythos.nexus.core.NexusConfig.tickRatePathingFor(key)
                : com.mythos.nexus.core.NexusConfig.tickRateIdleFor(key);
        int pct;
        if (perBotOverride >= 0) {
            pct = perBotOverride;
        } else if (com.mythos.nexus.core.NexusConfig.adaptiveTick()) {
            // The adaptive scheduler is the ONLY path that keeps N bots from
            // saturating the CPU — the plain pathing/idle rates let every
            // pathing bot run at 100% and the render thread starves to 1 fps.
            // The controller is updated once per client tick by SessionTicker.
            // Do not update it here: every bot has its own ticker thread, so
            // doing so would race the controller and repeatedly drive capacity
            // toward zero under a multi-bot load.
            int idx = mgr.sessions().indexOf(ctx);
            pct = TickScheduler.pctFor(idx < 0 ? 0 : idx, hasPath, -1);
        } else {
            pct = hasPath
                    ? com.mythos.nexus.core.NexusConfig.tickRatePathing()
                    : com.mythos.nexus.core.NexusConfig.tickRateIdle();
        }
        ctx.tickRateAcc += pct;
        boolean runFullTick = ctx.tickRateAcc >= 100;
        if (runFullTick) ctx.tickRateAcc -= 100;

        for (Runnable hook : SessionTicker.FAST_HOOKS) hook.run();

        antiIdle(ctx);

        if (ctx.player != null && !ctx.player.isEntityAlive()) {
            SessionTicker.sendRespawnPacket(ctx);
        }

        // Only run when the adaptive accumulator rolled over — otherwise
        // background bots would tick every iteration instead of at their
        // assigned pct.
        // Skip the world tick + session hooks while a mount is swapping the
        // shared mc singleton — baritone's tick reads mc.playerController and
        // the render pipeline reads mc.renderGlobal/displayWidth, all of which
        // are mid-swap here (the mount switch crash left the shared Tessellator
        // in "Already building!" state from a bot render racing the swap).
        if (runFullTick && ctx.world != null
                && !SessionManager.isMountInProgress()) {
            try {
                // Strip MovementInputFromOptions — it reads shared keybinds and
                // would steer every background bot along with whoever is
                // mounted. Baritone reinstalls PlayerMovementInput itself.
                if (ctx.player != null && ctx.player.movementInput != null
                        && ctx.player.movementInput.getClass().getName()
                                .equals("net.minecraft.util.MovementInputFromOptions")) {
                    ctx.player.movementInput = new net.minecraft.util.MovementInput();
                }
                // updateController drives clickBlock/dig progress for baritone.
                if (ctx.playerController != null) ctx.playerController.updateController();
                // Self-heal: if the player entity fell out of loadedEntityList
                // (stale isDead cleanup, cross-world leak, or a missed spawn),
                // put it back so updateEntities actually ticks it — otherwise
                // posX freezes and sendPacket keeps reporting the stale value
                // (the "background teleport" the server sees as moved-too-
                // quickly rubber-bands).
                if (ctx.player != null && !ctx.world.loadedEntityList.contains(ctx.player)) {
                    ctx.player.preparePlayerToSpawn();
                    ctx.world.spawnEntity(ctx.player);
                }
                ctx.world.tick();
                // World.tick() is weather-only on a client — vanilla entity
                // ticking lives in World.updateEntities(), which only ever ran
                // for the mounted world via Minecraft.runTick. Background
                // worlds therefore never swept dead entities and never ran
                // the setPositionAndRotationDirect interpolation: every
                // move/teleport packet only advanced serverPosX while posX
                // stayed frozen and SPacketDestroyEntities left ghosts in
                // loadedEntityList forever (the "frozen world" bug). Calling
                // world.updateEntities() wholesale is unsafe: one bad entity
                // (e.g. an EntityOtherPlayerMP whose NetworkPlayerInfo hasn't
                // arrived — hasPlayerInfo NPE → ReportedException) aborts the
                // whole sweep. Replicate the vanilla loop with a per-entity
                // guard instead.
                tickWorldEntities(ctx);
                // Tick our own player separately — kept from the old code so
                // its hasPlayerInfo NPE stays non-fatal.
                if (ctx.player != null) {
                    try { ctx.player.onUpdate(); }
                    catch (NullPointerException ignored) {
                        // hasPlayerInfo() NPEs until SPacketPlayerListItem arrives
                        // for this bot's own UUID — swallow it, retry next tick.
                    }
                }
            } catch (Throwable t) {
                NexusPlugin.LOGGER.warn("session {} world.tick failed",
                        ctx.username, t);
            }
            // MythosScript hooks — ran only on a full (gated) tick.
            for (Runnable hook : SessionTicker.TICK_HOOKS) hook.run();
        }
    }

    /**
     * Per-session replacement for World.updateEntities() — see the call-site
     * comment for why the vanilla method can't run as-is. Replicates the
     * regular-entity half of that loop: sweep isDead entries (chunk removal +
     * loadedEntityList removal + onEntityRemoved, exactly like vanilla lines
     * 1535-1544), then tick each live non-riding entity via world.updateEntity
     * under a per-entity try/catch so one ticking crash can't starve the rest.
     * ctx.player is skipped here and ticked explicitly by the caller, matching
     * the historical behaviour.
     */
    private void tickWorldEntities(BotSession ctx) {
        net.minecraft.world.World w = ctx.world;
        java.util.List<net.minecraft.entity.Entity> list = w.loadedEntityList;
        for (int i = 0; i < list.size(); i++) {
            net.minecraft.entity.Entity e = list.get(i);
            net.minecraft.entity.Entity riding = e.getRidingEntity();
            if (riding != null) {
                if (!riding.isDead && riding.isPassenger(e)) continue;
                e.dismountRidingEntity();
            }
            if (!e.isDead && e != ctx.player
                    && !(e instanceof net.minecraft.entity.player.EntityPlayerMP)) {
                try { w.updateEntity(e); }
                catch (Throwable ignored) { /* per-entity guard — never starve the sweep */ }
            }
            if (e.isDead) {
                int cx = e.chunkCoordX, cz = e.chunkCoordZ;
                if (e.addedToChunk && w.getChunkFromChunkCoords(cx, cz).isLoaded()) {
                    w.getChunkFromChunkCoords(cx, cz).removeEntity(e);
                }
                list.remove(i--);
                w.onEntityRemoved(e);
            }
        }
    }

    /** Same anti-idle sneak pulse the old SessionTicker sent every ~60s. */
    private void antiIdle(BotSession ctx) {
        // CONNECTING sessions have no player yet — sneak packet needs one.
        if (ctx.player == null || ctx.connection == null) return;
        long now = System.nanoTime();
        if (now - ctx.lastAntiIdleNanos <= 60e9) return;
        ctx.lastAntiIdleNanos = now;
        try {
            net.minecraft.network.play.client.CPacketEntityAction sneak =
                    new net.minecraft.network.play.client.CPacketEntityAction(
                            ctx.player,
                            net.minecraft.network.play.client.CPacketEntityAction
                                    .Action.START_SNEAKING);
            ctx.connection.sendPacket(sneak);
            net.minecraft.network.play.client.CPacketEntityAction unsneak =
                    new net.minecraft.network.play.client.CPacketEntityAction(
                            ctx.player,
                            net.minecraft.network.play.client.CPacketEntityAction
                                    .Action.STOP_SNEAKING);
            ctx.connection.sendPacket(unsneak);
        } catch (Throwable ignored) {}
    }


    /** Sleep for the remainder of the 50ms tick budget — keeps ~20 TPS. */
    private void sleepFor(long startNanos) {
        long elapsed = System.nanoTime() - startNanos;
        long sleep = TICK_NS - elapsed;
        if (sleep > 0) {
            try { Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L)); }
            catch (InterruptedException e) { /* stopTicker() woke us; loop re-checks tickerStopRequested */ }
        }
    }
}
