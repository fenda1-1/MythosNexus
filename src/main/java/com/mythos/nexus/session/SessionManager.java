package com.mythos.nexus.session;

import com.mythos.nexus.core.NexusPlugin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.NetworkManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns every bot session plus the human pseudo-session. Two lookup
 * directions:
 *   NetworkManager -> BotSession   (which account does this socket belong to)
 *   NetHandlerPlayClient -> BotSession (set when the handler ctor runs)
 *
 * Mount model: at most ONE context is "live" in the Minecraft singleton at a
 * time (world/player/controller/renderViewEntity). mount() snapshots the
 * outgoing owner's fields into its BotSession and writes the incoming
 * session's fields into Minecraft. The human is session id 0 — mounting it
 * returns the UI/title to whatever the human was doing.
 */
public final class SessionManager {

    private static final SessionManager INSTANCE = new SessionManager();
    public static SessionManager get() { return INSTANCE; }

    private final AtomicInteger idSeq = new AtomicInteger(1);
    private final List<BotSession> sessions = new ArrayList<>();
    private final Map<NetworkManager, BotSession> byConn = new ConcurrentHashMap<>();
    private final Map<NetHandlerPlayClient, BotSession> byHandler = new ConcurrentHashMap<>();
    private final long startNanos = System.nanoTime();

    /** Non-zero while completeMount is swapping the shared mc singleton.
     *  Preview rendering (LiveViewportRenderer) and per-bot baritone ticks
     *  must not touch mc.world/player/controller/renderGlobal/displayWidth
     *  in this window — a bot render that swaps mc mid-mount can leave the
     *  shared Tessellator in "Already building!" state or read a null/
     *  half-swapped playerController. Volatile so the bot ticker threads see
     *  the flag without a lock. */
    private static final java.util.concurrent.atomic.AtomicInteger MOUNTING =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** True while a mount is swapping the shared Minecraft singleton. */
    public static boolean isMountInProgress() { return MOUNTING.get() > 0; }

    private SessionManager() {}


    public synchronized BotSession createSession(String username, String host, int port) {
        BotSession s = new BotSession(idSeq.getAndIncrement(), username);
        s.serverHost = host;
        s.serverPort = port;
        // Restore the persisted group/proxy for this account key.
        String key = username + "@" + host + ":" + port;
        com.mythos.nexus.core.NexusConfig.Binding b =
                com.mythos.nexus.core.NexusConfig.bindingFor(key);
        s.group = b.group;
        s.proxyIndex = b.proxy;

        com.mythos.nexus.core.NexusConfig.rememberAccount(username, host, port);
        sessions.add(s);
        return s;
    }

    public synchronized void removeSession(BotSession s) {
        s.disconnect("removed");
        sessions.remove(s);

        ReconnectManager.get().cancel(s);
        if (s.connection != null) byConn.remove(s.connection);
        if (s.handler != null) byHandler.remove(s.handler);
        if (mounted == s) mount(human); // fall back to human view
        // Release per-session resources that would otherwise leak
        // WorldClient/EntityPlayerSP/RenderGlobal/Framebuffer.
        s.releaseReferences();
        LiveViewportRenderer.dispose(s);
        MythosStateSwap.clearSession(s);
    }

    /** True if a session with this username already exists (any state). */
    public synchronized boolean hasSessionNamed(String username) {
        if (username == null || username.isEmpty()) return false;
        for (BotSession s : sessions) {
            if (username.equalsIgnoreCase(s.username)) return true;
        }
        // Also check the human pseudo-session's real name.
        if (username.equalsIgnoreCase(human.username)) return true;
        return false;
    }

    public List<BotSession> sessions() {
        // The human pseudo-session is a peer in the list — sync its display
        // fields from the live Minecraft singleton so the UI shows the real
        // account name and online state instead of a "<you>" placeholder.
        syncHuman();
        synchronized (sessions) {
            ArrayList<BotSession> all = new ArrayList<>(sessions.size() + 1);
            all.add(human);
            all.addAll(sessions);
            return Collections.unmodifiableList(all);
        }
    }

    /** Keep the human pseudo-session's display fields in step with reality.
     *  Only valid while the human is the mounted session — when a bot is
     *  mounted the mc singleton points at the bot's world/player, not the
     *  human's. We must NOT overwrite human.player/world in that case, or
     *  mount(human) would persist the bot's player back. */
    private void syncHuman() {
        Minecraft mc = com.mythos.nexus.core.NexusHooks.resolveMinecraft();
        if (mc == null) return;
        try {
            String name = mc.getSession() != null
                    ? mc.getSession().getUsername() : "<you>";
            human.username = name != null && !name.isEmpty() ? name : "<you>";
        } catch (Throwable ignored) {}
        // Only refresh live fields while the human IS the mounted context AND
        // mc.player is actually the human's entity — during the join window a
        // background bot's dispatch can leave mc.player pointing at the bot
        // while the human is still on GuiConnecting (mounted==null). Absorbing
        // that rebinds human.player to a bot entity; baritone then pathfinds
        // the bot, not the human ("walks off-route after mounting").
        if ((mounted == null || mounted == human)
                && mc.player != null && human.username != null
                && human.username.equalsIgnoreCase(mc.player.getName())) {
            human.player = mc.player;
            human.world = mc.world;
            human.playerController = mc.playerController;
        }
        human.state = human.player != null && human.world != null
                ? BotSession.State.ONLINE
                : BotSession.State.OFFLINE;
        try {
            if (humanHandler != null) {
                java.net.SocketAddress a = humanHandler.getNetworkManager()
                        .getRemoteAddress();
                if (a instanceof java.net.InetSocketAddress) {
                    java.net.InetSocketAddress ia = (java.net.InetSocketAddress) a;
                    human.serverHost = ia.getHostString();
                    human.serverPort = ia.getPort();
                }
            }
        } catch (Throwable ignored) {}
    }


    public BotSession sessionFor(NetworkManager nm) {
        return byConn.get(nm);
    }

    public BotSession sessionForHandler(NetHandlerPlayClient handler) {
        return byHandler.get(handler);
    }

    /** Called by the NHPC-ctor hook. */
    public void bindHandler(NetworkManager nm, NetHandlerPlayClient handler) {
        BotSession s = byConn.get(nm);
        com.mythos.nexus.core.NexusPlugin.LOGGER.info("[nexus] bindHandler nm={} sess={} handler={}",
                nm, s == null ? "null" : s.username, handler != null);
        if (s != null) {
            s.handler = handler;
            byHandler.put(handler, s);
        } else {
            com.mythos.nexus.core.NexusLog.error("bindHandler: no session for nm=%s — treating as human", nm);
            NetHandlerPlayClient oldHandler = human.handler;
            if (oldHandler != null && oldHandler != handler) byHandler.remove(oldHandler);
            humanHandler = handler;
            human.handler = handler;
            if (human.connection != null && human.connection != nm) byConn.remove(human.connection);
            human.connection = nm;
            human.closed = false;
            byConn.put(nm, human);
            byHandler.put(handler, human);
        }
    }

    private volatile NetHandlerPlayClient humanHandler;
    public NetHandlerPlayClient humanHandler() { return humanHandler; }

    /** The pseudo-session that owns the human player's context. */
    public BotSession humanSession() { return human; }

    public void associate(NetworkManager nm, BotSession s) {
        byConn.put(nm, s);
        s.connection = nm;
    }

    /**
     * Drop the session's cached NetHandlerPlayClient and its byHandler
     * reverse-mapping. Runs before (re)connect and on every disconnect path
     * so a stale handler can never be bound to packets on a new channel —
     * the previous connection's NetHandlerPlayClient is dead the moment the
     * channel closes, and only the NHPC-ctor hook may install the new one.
     */
    public void resetHandler(BotSession s) {
        if (s == null) return;
        NetHandlerPlayClient old = s.handler;
        if (old != null) byHandler.remove(old);
        s.handler = null;
    }


    /** Pseudo-session holding the human player's context while a bot is mounted. */
    private final BotSession human = new BotSession(0, "<you>");
    /** volatile — bot tick threads poll this through isForeground() every tick. */
    private volatile BotSession mounted; // null = nobody mounted (fresh start = human)

    public BotSession mounted() { return mounted; }
    public boolean isForeground(BotSession s) {
        return s == (mounted == null ? human : mounted);
    }
    /** True while a bot is mounted (human context parked). */
    public boolean botMounted() { return mounted != null && mounted != human; }

    /** Session we tried to mount while its player was mid-respawn; the
     *  per-tick check in NexusHooks completes the mount once the new
     *  EntityPlayerSP is alive and positioned. */
    private volatile BotSession pendingMount;

    /**
     * Persist-mount: stash the outgoing owner's fields, write the incoming
     * session's fields into the Minecraft singleton. s == human returns to
     * title/human view.
     */
    public void mount(BotSession s) {
        pendingMount = null; // a newer selection cancels a deferred switch
        if (mounted == s) return;
        if (s != human && isOfflineState(s.state)) {
            // Human still in a world: its NM keeps feeding packets that need
            // mc.world, so swapping in a world-less bot floods NPEs.
            boolean humanInWorld = isForeground(human)
                    ? Minecraft.getMinecraft().world != null
                    : human.world != null && human.player != null;
            if (humanInWorld) {
                NexusPlugin.LOGGER.warn("mount ignored for {}: offline while human is in a world", s.username);
                return;
            }
            // Offline account: mount anyway so the view shows the title
            // screen as that bot; joining a server from there connects it.
            com.mythos.nexus.core.NexusLog.session("mount OFFLINE sess=%s state=%s", s.username, s.state);
            s.world = null; s.player = null; s.playerController = null;
            completeMount(s);
            offlineMount = s;
            return;
        }
        if (s != human && s.state == BotSession.State.CONNECTING) {
            pendingMount = s;
            NexusPlugin.LOGGER.info("mount deferred for {} (connecting)", s.username);
            return;
        }
        if (s != human && (s.state != BotSession.State.ONLINE
                || s.world == null || s.player == null)) {
            NexusPlugin.LOGGER.warn("mount ignored for {}: state={} world={} player={}",
                    s.username, s.state, s.world != null, s.player != null);
            com.mythos.nexus.core.NexusLog.session(
                    "mount IGNORED sess=%s state=%s w=%s p=%s",
                    s.username, s.state, s.world != null, s.player != null);
            return;
        }
        // Mounting while the player entity is dead leaves us driving a
        // corpse: SPacketRespawn hasn't rebuilt world/player yet, and the
        // stale fields are what the "mount freezes controls" bug fed on.
        // Defer the mount — runSessionTicks completes it once the respawn
        // packets land (same safety net as the auto-respawn resend).
        if (s != human && s.player != null && !s.player.isEntityAlive()) {
            if (pendingMount != s) {
                pendingMount = s;
                NexusPlugin.LOGGER.info("mount deferred for {} (player respawning)", s.username);
                com.mythos.nexus.core.NexusLog.session(
                        "mount DEFERRED sess=%s p=%s alive=false pos=%.1f,%.1f,%.1f",
                        s.username, Integer.toHexString(System.identityHashCode(s.player)),
                        s.player.posX, s.player.posY, s.player.posZ);
            }
            return;
        }
        completeMount(s);
    }

    /** Finish a deferred mount once the player is alive — called every tick. */
    static boolean isOfflineState(BotSession.State st) {
        return st == BotSession.State.OFFLINE || st == BotSession.State.DISCONNECTED
                || st == BotSession.State.ERROR;
    }

    /** Mount s automatically once its login completes (call after any mountHuman()). */
    public void mountWhenOnline(BotSession s) { pendingMount = s; }

    /** Mounted bot that has no world (shows the title screen as that account). */
    public BotSession offlineMounted() {
        BotSession m = mounted;
        return (m != null && m == offlineMount && m.world == null) ? m : null;
    }

    // Set only by the offline-mount path; a mounted bot whose world was just
    // nulled by disconnect() is NOT offline-mounted (vanilla teardown pending).
    private volatile BotSession offlineMount;

    public void flushPendingMount() {
        BotSession s = pendingMount;
        if (s == null) return;
        if (s.state == BotSession.State.CONNECTING
                || (s.state == BotSession.State.ONLINE && (s.world == null || s.player == null))) {
            return; // still logging in — keep waiting
        }
        if (s.state != BotSession.State.ONLINE || s.world == null || s.player == null) {
            pendingMount = null;
            com.mythos.nexus.core.NexusLog.session(
                    "pendingMount dropped sess=%s state=%s", s.username, s.state);
            return;
        }
        // The player reference may have been swapped mid-respawn — check the
        // *current* session player, not the one we captured when deferring.
        if (s.player == null || !s.player.isEntityAlive()) return;
        pendingMount = null;
        com.mythos.nexus.core.NexusLog.session(
                "pendingMount completing sess=%s alive=%s pos=%.1f,%.1f,%.1f",
                s.username, true, s.player.posX, s.player.posY, s.player.posZ);
        if (mounted == s) return; // already there
        mount(s);                 // normal path — player is alive now
    }

    private void completeMount(BotSession s) {
        // Outgoing owner: real mounted session, or the human (whose fields
        // are whatever the Minecraft singleton currently holds).
        BotSession outgoing = mounted != null ? mounted : human;
        // The incoming session must not tick on its own thread anymore —
        // the client thread drives mounted sessions. Stop its worker BEFORE
        // we swap its fields into the singleton so it can't mid-tick the
        // world while we repoint it.
        s.stopTicker();
        MOUNTING.incrementAndGet();
        try {
            outgoing.captureFromMinecraft();
            mounted = s;
            offlineMount = null;
            s.mount();
            s.persistIntoMinecraft();
            // Unmount the outgoing AFTER `mounted` is repointed — unmount()
            // calls ensureTicker(), which early-returns while this session is
            // still the foreground. Doing it before the swap would leave the
            // outgoing bot with no ticker thread forever (its position then
            // freezes / the old half-dead ticker double-ticks the world ->
            // rubber-band on server).
            outgoing.unmount();
            NexusPlugin.LOGGER.info("mount -> {} (was {})", s.username, outgoing.username);
            s.logEvent("mounted");
            outgoing.logEvent("unmounted");
            com.mythos.nexus.core.NexusLog.session("mount -> %s (was %s)",
                    s.username, outgoing.username);
            // MythosScript's shared statics must follow the mount — bot1's
            // kill-aura must stay on for bot1 while bot2 runs clean.
            MythosStateSwap.swapTo(outgoing, s);
        } finally {
            MOUNTING.decrementAndGet();
        }
    }

    /** Shortcut: return view to the human. */
    public void mountHuman() { mount(human); }

    /** Dispatch guard: run r inside the session's context on the calling thread. */
    public void withSession(BotSession s, Runnable r) { s.withContext(r); }




    public long totalMountedNanos() {
        long t = 0;
        for (BotSession s : sessions()) t += s.mountedNanos;
        return t;
    }
    public long uptimeNanos() { return System.nanoTime() - startNanos; }
}
