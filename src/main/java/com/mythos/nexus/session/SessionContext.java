package com.mythos.nexus.session;

import com.mythos.nexus.core.NexusPlugin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;

import java.lang.reflect.Field;

/**
 * The mount-context machinery for one BotSession: reflective handles into the
 * Minecraft singleton, the saved prev* snapshot, and the push/pop pair that
 * moves this session's world/player/controller/screen in and out of it.
 * Session state itself stays in BotSession.
 */
public class SessionContext {

    final BotSession s;

    SessionContext(BotSession s) {
        this.s = s;
    }

    private static Field fWorld, fPlayer, fController, fViewEntity,
            fRgWorld, fPmWorld, fGsThirdPerson, fCurrentScreen;
    static {
        try {
            Class<?> mc = Minecraft.class;
            fWorld      = pick(mc, "world", "field_71441_e", "f");
            fPlayer     = pick(mc, "player", "field_71439_g", "h");
            fController = pick(mc, "playerController", "field_71442_b", "c");
            fViewEntity = pick(mc, "renderViewEntity", "field_175622_Z", "ad");
            // currentScreen is part of the per-session context too: a bot's
            // SPacketJoinGame dispatch calls displayGuiScreen(null) to close
            // the "connecting" screen — without swapping this field it would
            // blank the mounted session's GUI (e.g. the human's GuiMainMenu).
            fCurrentScreen = pick(mc, "currentScreen", "field_71462_r", "m");
            // RenderGlobal iterates entities out of ITS private `world`
            // field (set by setWorldAndLoadRenderers inside loadWorld) — NOT
            // out of mc.world. Whichever session's loadWorld ran last owns
            // it; leaving it stale renders the wrong session's chunk map
            // (mounted bot sees another session's entities / none of its
            // own). Swap it on mount like the rest of the context.
            fRgWorld    = pick(net.minecraft.client.renderer.RenderGlobal.class,
                            "world", "field_72769_h", "k");
            fPmWorld    = pick(net.minecraft.client.particle.ParticleManager.class,
                            "world", "field_78878_a", "a");
            fGsThirdPerson = pick(net.minecraft.client.settings.GameSettings.class,
                            "thirdPersonView", "field_74320_O", "aw");
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }
    private static Field pick(Class<?> c, String mcp, String srg, String obf) throws Exception {
        Field f;
        try { f = c.getDeclaredField(mcp); }
        catch (NoSuchFieldException e) {
            try { f = c.getDeclaredField(srg); }
            catch (NoSuchFieldException e2) {
                try { f = c.getDeclaredField(obf); }
                catch (NoSuchFieldException e3) {
                    // The bare name only says which spelling failed last; the
                    // class + every name tried is what makes a client crash
                    // report actionable.
                    throw new NoSuchFieldException("no field " + c.getName() + "#" + mcp
                            + " (tried " + srg + ", " + obf + ")");
                }
            }
        }
        f.setAccessible(true);
        return f;
    }

    private Object prevWorld, prevPlayer, prevController, prevView, prevRgWorld,
            prevScreen;

    /** Push session context into the Minecraft singleton, run r, restore. */
    public void withContext(Runnable r) {
        // On this session's own tick thread the field swap is both redundant
        // and racy: the transformer rewrites every mc.player/mc.world/
        // playerController/read inside tick-reachable classes through
        // NexusCtx, which resolves THIS thread's bound session — so the real
        // singleton fields are never read here. Swapping them anyway is a
        // non-atomic 5-field write that 30 concurrent bot threads all perform
        // against the one shared singleton the mounted session renders from.
        // Skip the swap; still set the inContext marker (guiGuard /
        // loadWorldGuard / contextMismatch read it) and still run
        // captureLiveContext so a packet's NexusCtx-routed writes back into
        // s.player/s.world get absorbed (respawn moves mc.player into ctx).
        if (NexusCtx.current() == s) {
            com.mythos.nexus.core.NexusHooks.enterContext(s);
            try {
                r.run();
            } finally {
                com.mythos.nexus.core.NexusHooks.exitContext();
                captureLiveContextOnBoundThread();
            }
            return;
        }
        Minecraft mc = com.mythos.nexus.core.NexusHooks.resolveMinecraft();
        // Only the foreground owner may keep changes in the singleton.
        // The original account is an ordinary background session when parked.
        BotSession previousContext = com.mythos.nexus.core.NexusHooks.inContext();
        if (previousContext == s) { r.run(); return; }
        if (SessionManager.get().isForeground(s)) {
            com.mythos.nexus.core.NexusHooks.enterContext(s);
            try {
                r.run();
            } finally {
                captureLiveContext(mc);
                if (previousContext == null) com.mythos.nexus.core.NexusHooks.exitContext();
                else com.mythos.nexus.core.NexusHooks.enterContext(previousContext);
            }
            return;
        }
        try {
            prevWorld = fWorld.get(mc);
            prevPlayer = fPlayer.get(mc);
            prevController = fController.get(mc);
            prevView = fViewEntity.get(mc);
            prevScreen = fCurrentScreen.get(mc);
            if (mc.renderGlobal != null)
                prevRgWorld = fRgWorld.get(mc.renderGlobal);

            // Don't write savedScreen into mc.currentScreen — the mounted
            // session's GUI must stay on screen during a background packet
            // dispatch. But capture prevScreen so pop can detect when a
            // packet called displayGuiScreen during dispatch.
            fWorld.set(mc, s.world);
            fPlayer.set(mc, s.player);
            fController.set(mc, s.playerController);
            fViewEntity.set(mc, s.player);
            // mc.currentScreen stays untouched: it belongs to the mounted
            // session, and pushing a stale savedScreen in while a background
            // packet dispatches lets displayGuiScreen(null) from JoinGame wipe
            // the user's GUI on pop-restore. Background sessions keep their own
            // screens in savedScreen until they mount.
            // renderGlobal is different: its private `world` must be live during
            // dispatch (block/particle/playEvent handlers read it), yet it owns
            // the foreground chunk renderers, so only the mounted session may
            // repoint it.
            if (s.world != null && mc.renderGlobal != null
                    && s == SessionManager.get().mounted())
                fRgWorld.set(mc.renderGlobal, s.world);
        } catch (IllegalAccessException e) {
            NexusPlugin.LOGGER.error("context push failed", e);
            return;
        }
        // Tell NexusHooks (and by extension MythosScript's shared-statics
        // guard) which session owns the live fields while this runs.
        com.mythos.nexus.core.NexusHooks.enterContext(s);
        try {
            r.run();
        } finally {
            if (previousContext == null) com.mythos.nexus.core.NexusHooks.exitContext();
            else com.mythos.nexus.core.NexusHooks.enterContext(previousContext);
            try {
                captureLiveContext(mc);

                // A packet calling displayGuiScreen during dispatch writes to
                // the shared mc.currentScreen. If it changed while we were in
                // ctx, that's a screen the session wants — stash it in
                // savedScreen so a later mount restores it, then put the
                // mounted session's screen back so the user sees their GUI.
                Object scr = fCurrentScreen.get(mc);
                if (scr != prevScreen) {
                    s.savedScreen = (net.minecraft.client.gui.GuiScreen) scr;
                    fCurrentScreen.set(mc, prevScreen);
                }

                fWorld.set(mc, prevWorld);
                fPlayer.set(mc, prevPlayer);
                fController.set(mc, prevController);
                fViewEntity.set(mc, prevView);
                if (mc.renderGlobal != null) fRgWorld.set(mc.renderGlobal, prevRgWorld);
                // loadWorld during join/respawn may have rebuilt the renderer.
                restoreRenderGlobal(mc, prevWorld);
            } catch (IllegalAccessException e) {
                NexusPlugin.LOGGER.error("context restore failed", e);
            }
        }
    }

    /** Capture replacements (including null on disconnect), without stealing input.
     *  Foreign-entity guard: if mc.player currently belongs to another session
     *  (its name doesn't match ours), do NOT absorb it — keep our own pointer
     *  so the next push restores our entity into the singleton. Without this,
     *  one dispatch that leaves mc.player pointing at another session (e.g. a
     *  bot's packet while the human is still on GuiConnecting) rebinds this
     *  ctx.player to the foreign body and baritone pathfinds that body. */
    private void captureLiveContext(Minecraft mc) {
        EntityPlayerSP oldPlayer = s.player;
        boolean playerIsOurs = mc.player == null || (s.username != null
                && s.username.equalsIgnoreCase(mc.player.getName()));
        if (s.world != mc.world || (playerIsOurs && s.player != mc.player)
                || (!playerIsOurs && s.player != null)) {
            com.mythos.nexus.core.NexusLog.session(
                    "context changed sess=%s world=%s->%s player=%s->%s (absorb=%s)",
                    s.username, id(s.world), id(mc.world), id(s.player), id(mc.player),
                    playerIsOurs);
        }
        if (playerIsOurs) {
            // mc.player is OUR entity → mc.world is OUR world too.
            s.world = mc.world;
            s.player = mc.player;
        } else if (s.player == null || !s.username.equalsIgnoreCase(s.player.getName())) {
            // Our stored player is also wrong/null — try to recover OUR entity
            // from the world we just captured (it's ours by definition of the
            // ctx), matching by username.
            EntityPlayerSP ours = null;
            if (s.world != null && s.username != null) {
                for (Object e : s.world.playerEntities) {
                    if (e instanceof EntityPlayerSP
                            && s.username.equalsIgnoreCase(((EntityPlayerSP) e).getName())) {
                        ours = (EntityPlayerSP) e; break;
                    }
                }
            }
            if (ours != null) {
                com.mythos.nexus.core.NexusLog.session(
                        "player rebind sess=%s %s->%s (self-heal)",
                        s.username, id(s.player), id(ours));
                s.player = ours;
            }
        }
        s.playerController = playerIsOurs ? mc.playerController : s.playerController;
        maybeCreateController(mc, oldPlayer);
        markOnlineIfReady();
    }

    /** Bound-bot-thread variant of captureLiveContext. On a tick thread the
     *  shared mc.player/mc.world belong to the MOUNTED session, so the
     *  foreign-entity absorb logic must NOT read them — the packet's
     *  NexusCtx-routed writes already landed in s.player/s.world directly.
     *  All that remains is: swap in a fresh PlayerControllerMP if the player
     *  entity was replaced (respawn / join), and run the ONLINE transition. */
    private void captureLiveContextOnBoundThread() {
        EntityPlayerSP oldPlayer = s.player;
        // s.player may have been reassigned by a packet (NexusCtx.setPlayer →
        // s.player). Detect the swap the same way captureLiveContext does —
        // by comparing connection owner — but against the session's own
        // fields, never the shared singleton.
        Minecraft mc = com.mythos.nexus.core.NexusHooks.resolveMinecraft();
        maybeCreateController(mc, oldPlayer);
        markOnlineIfReady();
    }

    /** Replace playerController when a packet swapped in a new player entity
     *  whose connection differs from the controller's held NetHandler. */
    private void maybeCreateController(Minecraft mc, EntityPlayerSP oldPlayer) {
        if (s.player != null && s.player != oldPlayer && s.player.connection != null
                && s.playerController != null
                && controllerConnection(s.playerController) != s.player.connection) {
            com.mythos.nexus.core.NexusLog.session(
                    "playerController rebind sess=%s oldPlayer=%s newPlayer=%s",
                    s.username, id(oldPlayer), id(s.player));
            s.playerController = new PlayerControllerMP(mc, s.player.connection);
            // Only write the shared field when this session is the foreground
            // owner — a bound bot thread's reads resolve through NexusCtx to
            // s.playerController, so touching mc.playerController here would
            // just race the mounted session.
            if (NexusCtx.current() == null) {
                try { fController.set(mc, s.playerController); }
                catch (IllegalAccessException ignored) {}
            }
        }
    }

    /** CONNECTING→ONLINE transition once the session has a live world+player. */
    private void markOnlineIfReady() {
        if (s.world != null && s.player != null && s.state == BotSession.State.CONNECTING) {
            s.state = BotSession.State.ONLINE;
            s.onlineSinceNanos = System.nanoTime();
            s.reconnectAtNanos = 0;   // cancel any pending reconnect countdown
            s.reconnectAttempts = 0; // successful login resets the attempt counter
            NexusPlugin.LOGGER.info("bot {} is now ONLINE", s.username);
            // Coming online with a world on a background session → give it a
            // dedicated tick thread. No-op for the human/mounted session.
            s.ensureTicker();
        }
    }

    /**
     * loadWorld(null) inside a dispatch (disconnect, some respawn paths)
     * tears the SHARED RenderGlobal down: viewFrustum GL resources freed,
     * ChunkRenderDispatcher worker threads stopped, world=null. A plain
     * field restore can't undo that — the next packet's markAndNotifyBlock
     * or playEvent NPEs inside RenderGlobal. If the restored mc.world no
     * longer matches renderGlobal's world, run the full
     * setWorldAndLoadRenderers to rebuild/repoint it (also rewires
     * renderManager.world and the world event-listener registration).
     * fallbackWorld = the world that was live before the dispatch.
     */
    public static void restoreRenderGlobal(Minecraft mc, Object fallbackWorld) {
        try {
            if (mc.renderGlobal == null) return;
            Object w = fWorld.get(mc);
            Object cur = fRgWorld.get(mc.renderGlobal);
            if (w == cur) return; // nothing changed inside the dispatch
            if (w instanceof WorldClient) {
                mc.renderGlobal.setWorldAndLoadRenderers((WorldClient) w);
            } else if (fallbackWorld instanceof WorldClient) {
                // Restored session has no world — don't tear RenderGlobal
                // down; put the previously live world back so packet
                // handlers hitting markAndNotifyBlock/playEvent don't NPE.
                mc.renderGlobal.setWorldAndLoadRenderers((WorldClient) fallbackWorld);
            }
        } catch (Throwable t) {
            NexusPlugin.LOGGER.warn("renderGlobal restore failed", t);
        }
    }

    /**
     * Manual push/pop pair for code paths that can't use withContext's
     * try/finally (e.g. bytecode HEAD/RETURN injection on a method whose
     * body must run inside our context). Same semantics as withContext —
     * push swaps this session's fields in, pop captures changes and
     * restores the previous fields.
     */
    public void enterContextNoStatics() {
        Minecraft mc = com.mythos.nexus.core.NexusHooks.resolveMinecraft();
        try {
            if (SessionManager.get().isForeground(s)) {
                // Mounted-session disconnect push: the singleton already holds THIS
                // session's fields (that's what vanilla's loadWorld(null) has to
                // clear). Stashing the singleton as prev* would hand the bot's own
                // dying world/player back to pop — we want the HUMAN's fields
                // instead, so when pop restores the singleton the mountHuman() that
                // follows sees the human world again (not the bot's torn-down one).
                BotSession human = SessionManager.get().humanSession();
                prevWorld = human.world;
                prevPlayer = human.player;
                prevController = human.playerController;
                prevView = human.player;
                prevScreen = fCurrentScreen.get(mc);
                if (mc.renderGlobal != null)
                    prevRgWorld = fRgWorld.get(mc.renderGlobal);
            } else {
                prevWorld = fWorld.get(mc);
                prevPlayer = fPlayer.get(mc);
                prevController = fController.get(mc);
                prevView = fViewEntity.get(mc);
                prevScreen = fCurrentScreen.get(mc);
                if (mc.renderGlobal != null)
                    prevRgWorld = fRgWorld.get(mc.renderGlobal);
            }
            fWorld.set(mc, s.world);
            fPlayer.set(mc, s.player);
            fController.set(mc, s.playerController);
            fViewEntity.set(mc, s.player);
            // Leave mc.currentScreen alone — it's the mounted session's GUI.
            // We still snapshot prevScreen so pop can detect a packet-driven
            // displayGuiScreen during our dispatch window.
            if (s.world != null && mc.renderGlobal != null
                    && s == SessionManager.get().mounted())
                fRgWorld.set(mc.renderGlobal, s.world);
        } catch (IllegalAccessException e) {
            NexusPlugin.LOGGER.error("context push failed", e);
        }
        com.mythos.nexus.core.NexusHooks.enterContext(s);
    }

    public void exitContextNoStatics() {
        Minecraft mc = com.mythos.nexus.core.NexusHooks.resolveMinecraft();
        com.mythos.nexus.core.NexusHooks.exitContext();
        try {
            captureLiveContext(mc);
            // Packet-driven displayGuiScreen writes mc.currentScreen while in
            // ctx. Stash as OUR savedScreen so a later mount shows it, then
            // restore the mounted session's screen so the user keeps their
            // GUI.
            Object scr = fCurrentScreen.get(mc);
            if (scr != prevScreen) {
                s.savedScreen = (net.minecraft.client.gui.GuiScreen) scr;
                fCurrentScreen.set(mc, prevScreen);
            }
            fWorld.set(mc, prevWorld);
            fPlayer.set(mc, prevPlayer);
            fController.set(mc, prevController);
            fViewEntity.set(mc, prevView);
            if (mc.renderGlobal != null) fRgWorld.set(mc.renderGlobal, prevRgWorld);
            restoreRenderGlobal(mc, prevWorld);
        } catch (IllegalAccessException e) {
            NexusPlugin.LOGGER.error("context pop failed", e);
        }
    }

    private static String id(Object o) {
        return o == null ? "null" : Integer.toHexString(System.identityHashCode(o));
    }

    /** Reflect PlayerControllerMP's held NetHandlerPlayClient (field 'b'/netHandler). */
    private static Object controllerConnection(PlayerControllerMP pc) {
        try {
            for (Field f : PlayerControllerMP.class.getDeclaredFields()) {
                if (NetHandlerPlayClient.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f.get(pc);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
    private void dbg(String fmt, Object... a) {
        // only log when nexus.debug is set — routine ctx pop spam drowns
        // the useful lines otherwise
        if (Boolean.getBoolean("nexus.debug"))
            NexusPlugin.LOGGER.info("[sess {} {}] {}", s.id, s.username, String.format(fmt, a));
    }


    /**
     * Write this session's world/player/controller/renderViewEntity into the
     * Minecraft singleton permanently (no auto-restore). Returns nothing —
     * the displaced objects are captured by the caller if it wants to keep
     * them (e.g. human session stores them before a bot mounts).
     */
    public void persistIntoMinecraft() {
        Minecraft mc = com.mythos.nexus.core.NexusHooks.resolveMinecraft();
        try {
            fWorld.set(mc, s.world);
            fPlayer.set(mc, s.player);
            fController.set(mc, s.playerController);
            // renderViewEntity must be THIS session's player — if it's left
            // pointing at another session's entity, that entity is skipped
            // by the renderer (invisible except shadow) while ours renders
            // as a normal entity (face in first person).
            fViewEntity.set(mc, s.player != null ? s.player : fPlayer.get(mc));
            // Point the shared RenderGlobal at THIS session's world — its
            // `world` field drives the chunk entity lists the render pass
            // iterates, and it is left over from whichever loadWorld ran
            // last. Without this the mounted bot renders the previous
            // session's entities and none of its own. Must be the full
            // setWorldAndLoadRenderers — a prior disconnect may have freed
            // viewFrustum and stopped the chunk-render worker threads, and
            // a field-set can't resurrect those.
            if (s.world != null && mc.renderGlobal != null
                    && fRgWorld.get(mc.renderGlobal) != s.world) {
                mc.renderGlobal.setWorldAndLoadRenderers(s.world);
            }
            // A vanilla loadWorld(null) leaves these on a null world; hitting a
            // block then NPEs in ParticleManager.addBlockHitEffects.
            if (s.world != null) {
                if (mc.effectRenderer != null && fPmWorld.get(mc.effectRenderer) != s.world)
                    mc.effectRenderer.clearEffects(s.world);
                net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher.instance.setWorld(s.world);
            }
            // Restore this session's camera mode so another account's F5
            // doesn't become ours.
            fGsThirdPerson.set(mc.gameSettings, s.savedThirdPersonView);
            // A session baritone keeps this bot's movementInput pointed at
            // PlayerMovementInput while it paths. If the bot is ACTIVELY
            // pathing we must leave that input installed — mounting it to
            // watch autopilot means baritone keeps driving; ripping it out
            // mid-path makes the bot stop obeying the path and the server
            // rubber-bands it back (the "瞬间移动" report). Only when the
            // mounted bot is idle do we hand control to the real keyboard.
            boolean activelyPathing = SessionTicker.hasActivePath(s);
            EntityPlayerSP sp = s.player;
            if (sp != null && sp.movementInput != null && !activelyPathing
                    && !sp.movementInput.getClass().getName()
                            .equals("net.minecraft.util.MovementInputFromOptions")) {
                com.mythos.nexus.core.NexusLog.session(
                        "mount: restoring MovementInputFromOptions for %s (was %s)",
                        s.username, sp.movementInput.getClass().getName());
                sp.movementInput = new net.minecraft.util.MovementInputFromOptions(
                        mc.gameSettings);
            }
            // The Nexus account screen is a management overlay, not a bot
            // game screen. Never restore it while mounting a bot, otherwise
            // keyboard/mouse input remains captured by the overlay.
            net.minecraft.client.gui.GuiScreen screen = s.savedScreen;
            if (screen != null && screen.getClass().getName()
                    .equals("com.mythos.nexus.ui.NexusScreen")) screen = null;
            // A screen ticked with mc.player == null NPEs inside modded
            // containers (DragonCore sl). Keep it parked until the next
            // mount when there's a player to tick it against.
            if (sp != null) {
                mc.displayGuiScreen(screen);
            } else if (s.world == null && s != SessionManager.get().humanSession()) {
                // Offline bot: vanilla substitutes the title screen for null.
                mc.displayGuiScreen(null);
            } else if (screen != null) {
                s.savedScreen = screen;   // keep for next mount
            }
            // TickingTaskGuard / runTick mouse path also NPEs when
            // playerController is null with a non-null world — but a null
            // playerController alongside null world is the plain "title
            // screen" combination vanilla already handles.
            dbg("persisted w=%s p=%s c=%s rve=%s", id(s.world), id(s.player), id(s.playerController), id(fViewEntity.get(mc)));
            com.mythos.nexus.core.NexusLog.session(
                    "persist sess=%s w=%s p=%s alive=%s pos=%.1f,%.1f,%.1f ctrl=%s rve=%s screen=%s",
                    s.username, id(s.world), id(sp),
                    sp == null ? "?" : String.valueOf(sp.isEntityAlive()),
                    sp == null ? 0 : sp.posX, sp == null ? 0 : sp.posY,
                    sp == null ? 0 : sp.posZ,
                    id(s.playerController), id(fViewEntity.get(mc)),
                    screen == null ? "null" : screen.getClass().getSimpleName());
        } catch (IllegalAccessException e) {
            NexusPlugin.LOGGER.error("mount persist failed", e);
            com.mythos.nexus.core.NexusLog.error("mount persist failed for " + s.username, e);
        }
        // If this session died while in background, its screen was set to
        // GuiGameOver on the shared mc.currentScreen. Auto-respawn on mount
        // so the bot comes back without a click.
        if (mc.currentScreen != null && mc.currentScreen.getClass().getName()
                .equals("net.minecraft.client.gui.GuiGameOver")) {
            mc.displayGuiScreen(null);
            if (s.connection != null) {
                try {
                    // lb = CPacketClientStatus, lb$a = its State enum (a=PERFORM_RESPAWN).
                    // Class.forName("lb$a") fails because LaunchClassLoader doesn't
                    // index inner classes — get it via lb's declared classes.
                    Class<?> pkt = s.connection.getClass().getClassLoader().loadClass("lb");
                    Class<?> state = pkt.getDeclaredClasses()[0];
                    Object perf = state.getEnumConstants()[0];
                    Object respawn = pkt.getDeclaredConstructor(state).newInstance(perf);
                    // NetworkManager.sendPacket is `a(Lht;)V` in obf, `sendPacket` in dev.
                    for (java.lang.reflect.Method m : s.connection.getClass().getDeclaredMethods()) {
                        Class<?>[] p = m.getParameterTypes();
                        if (p.length == 1 && p[0].isAssignableFrom(respawn.getClass())) {
                            m.setAccessible(true);
                            m.invoke(s.connection, respawn);
                            break;
                        }
                    }
                    NexusPlugin.LOGGER.info("mount auto-respawn sent for {}", s.username);
                } catch (Throwable t) {
                    NexusPlugin.LOGGER.warn("mount auto-respawn failed for {}", s.username, t);
                }
            }
        }
    }

    /**
     * Snapshot the current Minecraft singleton fields INTO this session
     * (used to stash the human player's context before a bot takes over, or
     * to re-capture a bot's fields after respawn).
     */
    public void captureFromMinecraft() {
        Minecraft mc = com.mythos.nexus.core.NexusHooks.resolveMinecraft();
        try {
            Object w = fWorld.get(mc);
            Object p = fPlayer.get(mc);
            Object c = fController.get(mc);
            // Absorb only when the singleton still holds OUR entity (or null,
            // i.e. disconnect). During a mount handoff mc.player may already be
            // the incoming session's, and storing it rebinds ctx.player to a
            // foreign body — baritone then pathfinds it instead of ours.
            boolean absorb = p == null
                    || s == SessionManager.get().humanSession()   // human owns whatever the singleton holds
                    || (p instanceof EntityPlayerSP && s.username != null
                        && s.username.equalsIgnoreCase(((EntityPlayerSP) p).getName()));
            if (absorb) {
                s.world = (w instanceof WorldClient) ? (WorldClient) w : null;
                s.player = (EntityPlayerSP) p;
                s.playerController = (c instanceof PlayerControllerMP) ? (PlayerControllerMP) c : null;
            }
            // Offline bot's title/multiplayer screen must not resurface later.
            s.savedScreen = (s.world == null && s != SessionManager.get().humanSession())
                    ? null : mc.currentScreen;
            s.savedThirdPersonView = mc.gameSettings.thirdPersonView;
            // If we leave MovementInputFromOptions on this player after
            // unmounting, every background tick will re-read the shared
            // keyboard and steer this bot along with whoever is mounted.
            // Strip it back to a dead MovementInput so only the mounted
            // session consumes real keys; baritone will reinstall
            // PlayerMovementInput itself if it needs to drive.
            // Snapshot: netty's onChannelClosed may null s.player concurrently.
            EntityPlayerSP sp = s.player;
            if (sp != null
                    && sp.movementInput != null
                    && sp.movementInput.getClass().getName()
                            .equals("net.minecraft.util.MovementInputFromOptions")) {
                com.mythos.nexus.core.NexusLog.session(
                        "unmount: stripping MovementInputFromOptions from %s", s.username);
                sp.movementInput = new net.minecraft.util.MovementInput();
            }
            com.mythos.nexus.core.NexusLog.session(
                    "capture sess=%s w=%s p=%s ctrl=%s screen=%s",
                    s.username, id(s.world), id(s.player), id(s.playerController),
                    s.savedScreen == null ? "null" : s.savedScreen.getClass().getSimpleName());
        } catch (IllegalAccessException e) {
            NexusPlugin.LOGGER.error("capture failed", e);
            com.mythos.nexus.core.NexusLog.error("capture failed for " + s.username, e);
        }
    }

    /**
     * Drop the saved prev* snapshot so a dead session doesn't pin a whole
     * WorldClient + chunk store through these fields. Repopulated on the
     * next mount/dispatch, so nulling is free.
     */
    void releaseReferences() {
        prevWorld = null;
        prevPlayer = null;
        prevController = null;
        prevView = null;
        prevRgWorld = null;
        prevScreen = null;
    }
}
