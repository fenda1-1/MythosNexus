package com.mythos.nexus.session;

import com.mythos.nexus.core.NexusPlugin;
import com.mythos.nexus.core.NexusRefs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.NetworkManager;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * One bot's full client-side context: its connection, its world, its player.
 * Exactly one session may be "mounted" — the one written into the Minecraft
 * singleton fields. Mounted session renders and receives input; the rest are
 * packet-fed background state.
 *
 * The mount-context machinery (singleton field swapping) lives in
 * {@link SessionContext} — this class keeps the session state and thin
 * delegates that preserve the public API.
 */
public class BotSession {

    public enum State { OFFLINE, CONNECTING, ONLINE, DISCONNECTED, ERROR }

    public final int id;
    /** Username is mutable only for the human pseudo-session — its real
     *  name isn't known until Minecraft.getSession() is populated. */
    public String username;
    public String serverHost;
    public int serverPort = 25565;

    /** Display group (session-local; persisted via NexusConfig bindings). */
    public volatile String group = "默认";
    /** Index into NexusConfig.proxies(), -1 = direct connection. */
    public volatile int proxyIndex = -1;

    public volatile State state = State.OFFLINE;
    /** Timestamp of the last auto-respawn packet we sent on our connection —
     *  deduplicates the per-tick respawn retry while the player is dead. */
    public volatile long lastAutoRespawnNanos = 0;
    public volatile String lastError = null;
    public volatile boolean closed = false;
    /** true only when disconnect() was called by us (user/manager), so
     *  onDisconnect can tell a drop apart from a deliberate close. */
    public volatile boolean userClosed = false;

    /** Assigned on the connect thread, read by the BotTicker for packet
     *  writes + by the render thread for status — volatile for visibility. */
    public volatile NetworkManager connection;
    public volatile NetHandlerPlayClient handler;

    /** World/player this session owns. Re-read from mc fields after dispatch
     *  because SPacketJoinGame / respawn create fresh instances.
     *
     *  volatile: background sessions now dispatch packets on their own
     *  BotTicker thread (NexusCtx.setWorld/setPlayer write these), while the
     *  client render thread reads them in LiveViewportRenderer.ensurePreview.
     *  Without a happens-before edge the render thread sees a stale null
     *  forever and the live viewport stays a blank card. */
    public volatile WorldClient world;
    public volatile EntityPlayerSP player;

    /** PlayerControllerMP for this session (mounted only matters for input,
     *  but each session keeps its own so gamemode per bot is independent). */
    public volatile PlayerControllerMP playerController;

    /** Chat lines received while in background (ring buffer). */
    public final Deque<String> chatLog = new ArrayDeque<>();

    /** Per-session event log (connect/disconnect/error/mount) for the log pane. */
    public static final class LogEntry {
        public final String time;
        public final String level;   // INFO / WARN / ERROR
        public final String msg;
        LogEntry(String time, String level, String msg) {
            this.time = time; this.level = level; this.msg = msg;
        }
    }
    public final Deque<LogEntry> eventLog = new ArrayDeque<>();
    public void logEvent(String msg) { logEvent("INFO", msg); }
    public void logEvent(String level, String msg) {
        LogEntry e = new LogEntry(new java.text.SimpleDateFormat("HH:mm:ss")
                .format(new java.util.Date()), level, msg);
        synchronized (eventLog) {
            eventLog.addLast(e);
            while (eventLog.size() > 200) eventLog.pollFirst();
        }
    }

    /** Pending GUI the server asked to open while in background. */
    public Object pendingGui; // GuiScreen — kept loose to avoid load order issues

    /** The GuiScreen that was live when this session was last unmounted.
     *  Captured/restored alongside world/player so GuiGameOver etc. stay
     *  per-session instead of leaking into the shared mc.currentScreen. */
    public volatile net.minecraft.client.gui.GuiScreen savedScreen;

    /** Camera mode each session had when unmounted (F5 cycles are
     *  per-player; without this a front-view on one account shows a giant
     *  face when you mount another). */
    public volatile int savedThirdPersonView = 0;

    public volatile long bytesReceived;
    public volatile long bytesSent;
    public volatile long lastPacketNanos;
    public volatile long packetFailures;
    /** Wall time this session was mounted, ms. */
    public volatile long mountedNanos;
    private volatile long mountedSince;
    /** nanoTime when this session first went ONLINE (0 = never). */
    public volatile long onlineSinceNanos;
    /** nanoTime when an auto-reconnect is scheduled to fire (0 = not scheduled). */
    public volatile long reconnectAtNanos;
    /** Attempt counter for auto-reconnect — reset when the session goes ONLINE. */
    public volatile int reconnectAttempts;
    /** Last anti-idle sneak-pulse time (nanoTime), 0 = not yet sent. */
    public volatile long lastAntiIdleNanos;
    /** Tick counter for background downclocking — only every Nth tick runs the world. */
    public volatile int tickSkipCount;
    /** Tick accumulator for the configurable world-tick rate — persists across
     *  ticks so a percentage like 20% means every 5th tick, not every 5th tick
     *  plus a big gap. Rollover at 10000 to stay in int range. */
    public volatile int tickRateAcc;
    /** Dedicated tick worker for this session while it is a non-mounted
     *  background bot. Null when mounted / human / not yet online; volatile so
     *  netty enqueue and the client thread see the same worker state. */
    volatile Thread ticker;

    /** Deferred tasks enqueued by OTHER threads (MCP pool, netty, GUI) that
     *  must run on THIS session's own tick thread — e.g. a Baritone command
     *  that mutates PathingControlManager.processes. Drained once per tick by
     *  BotTicker.tickOnce after the packet pump, inside the bound NexusCtx, so
     *  single-threaded-iteration assumptions inside MythosScript/Baritone
     *  hold. Same serialization guarantee the old client-thread loop had. */
    public final java.util.concurrent.ConcurrentLinkedQueue<Runnable> taskQueue =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /**
     * Enqueue r to run on this session's own tick thread and block the caller
     * until it completes (bounded by timeoutMs). Used by MCP/session tools so
     * cross-thread state mutations (Baritone processes, kill-aura, feature
     * managers) happen on the session's own thread instead of racing the tick.
     *
     * Returns the runnable's outcome via {@code Future}. If this session has
     * no live ticker (mounted / human / offline) the task runs inline on the
     * calling thread inside withContext — those contexts already serialize on
     * the client thread, so inline is correct and avoids a deadlock.
     */
    public java.util.concurrent.Future<?> runOnTick(Runnable r) {
        Thread t = ticker;
        boolean onMyThread = t != null && Thread.currentThread() == t;
        // Mounted/human sessions and calls made from the tick thread itself run
        // inline — their context already serializes them on the owning thread.
        if (t == null || onMyThread) {
            withContext(r);
            java.util.concurrent.CompletableFuture<Void> done =
                    new java.util.concurrent.CompletableFuture<>();
            done.complete(null);
            return done;
        }
        java.util.concurrent.CompletableFuture<Void> fut =
                new java.util.concurrent.CompletableFuture<>();
        taskQueue.offer(() -> {
            try { r.run(); fut.complete(null); }
            catch (Throwable th) { fut.completeExceptionally(th); }
        });
        return fut;
    }

    /** Drain every queued task. Called by BotTicker.tickOnce on the session's
     *  own thread — NOT inside withContext because the ticker thread is already
     *  bound via NexusCtx.bind(this) and swapping shared mc fields here would
     *  be wrong; tasks run under that bound context directly. */
    void drainTasks() {
        Runnable r;
        int guard = 0;
        while ((r = taskQueue.poll()) != null && guard++ < 256) {
            try { r.run(); }
            catch (Throwable th) {
                com.mythos.nexus.core.NexusLog.error(
                        "[ticker] {} task failed: {}", th, username, th.toString());
            }
        }
    }

    /** Per-session Baritone instance (IBaritone via reflection in
     *  NexusTickBridge). Null until the session has been driven once. */
    public Object baritone;
    /** Cached getPathingBehavior Method on ctx.baritone's class — one
     *  getMethod lookup the first time we see this baritone. */
    public transient java.lang.reflect.Method baritonePathingM;
    /** Cached PathingBehavior.getCurrent Method — looked up once per
     *  PathingBehavior instance (same class across bots). */
    public transient java.lang.reflect.Method baritonePathCurrentM;

    /** Seconds this session has been continuously ONLINE (0 if not online). */
    public long onlineSeconds() {
        long t = onlineSinceNanos;
        return t == 0 ? 0 : (System.nanoTime() - t) / 1_000_000_000L;
    }

    /** The mount-context machinery — pushes this session's fields into the
     *  Minecraft singleton and restores them afterwards. */
    final SessionContext ctx;

    BotSession(int id, String username) {
        this.id = id;
        this.username = username;
        this.ctx = new SessionContext(this);
    }


    /** Push session context into the Minecraft singleton, run r, restore. */
    public void withContext(Runnable r) { ctx.withContext(r); }

    /** Manual push — see SessionContext.enterContextNoStatics. */
    public void enterContextNoStatics() { ctx.enterContextNoStatics(); }

    /** Manual pop — see SessionContext.exitContextNoStatics. */
    public void exitContextNoStatics() { ctx.exitContextNoStatics(); }

    /** Rebuild/repoint the shared RenderGlobal after a dispatch tore it down. */
    public static void restoreRenderGlobal(Minecraft mc, Object fallbackWorld) {
        SessionContext.restoreRenderGlobal(mc, fallbackWorld);
    }

    /**
     * Write this session's world/player/controller/renderViewEntity into the
     * Minecraft singleton permanently (no auto-restore). Returns nothing —
     * the displaced objects are captured by the caller if it wants to keep
     * them (e.g. human session stores them before a bot mounts).
     */
    public void persistIntoMinecraft() { ctx.persistIntoMinecraft(); }

    /**
     * Snapshot the current Minecraft singleton fields INTO this session
     * (used to stash the human player's context before a bot takes over, or
     * to re-capture a bot's fields after respawn).
     */
    public void captureFromMinecraft() { ctx.captureFromMinecraft(); }

    /** Called when this session becomes the visible one. */
    public void mount() {
        mountedSince = System.nanoTime();
    }

    /** Called when another session takes the foreground. */
    public void unmount() {
        if (mountedSince != 0) {
            mountedNanos += System.nanoTime() - mountedSince;
            mountedSince = 0;
        }
        // Going back to background — resume a per-session tick thread.
        ensureTicker();
    }


    /** True once a background tick worker exists for this session. PacketPump
     *  uses this to route packets to the per-session queue. */
    public boolean hasTicker() { return ticker != null; }

    /** Reference to the most recently stopped ticker thread. stopTicker()
     *  clears `ticker` but the underlying thread is only interrupted, not
     *  killed — it can outlive the field-clear and keep looping. We keep it
     *  around so ensureTicker() can reap a lingering zombie before spawning a
     *  replacement (prevents two workers ticking the same session). */
    private volatile Thread lastTicker;

    /** Set by stopTicker() to ask the worker loop to exit on its next pass.
     *  Cleared when a fresh ticker starts. The interrupt only wakes the sleep;
     *  THIS flag is what actually terminates the loop (an interrupt alone is
     *  consumed by Thread.sleep and would otherwise be lost). */
    private volatile boolean tickerStopRequested;

    /** True when the worker loop should wind down — checked each pass by
     *  BotTicker.run(). */
    boolean tickerStopRequested() { return tickerStopRequested; }

    /**
     * Start a dedicated BotTicker thread for this session if it doesn't have
     * one and it is a non-mounted ONLINE bot. Called from BotLoginHandler
     * (netty thread) when the session's world comes online, and again from
     * unmount() when a mounted session goes back to background.
     */
    public void ensureTicker() {
        // NOTE: no world==null guard — the ticker itself must already be
        // running during CONNECTING so it can drain the per-session queue
        // and dispatch SPacketJoinGame (the packet that BUILDS the world).
        // Gating on world!=null deadlocks the whole login: no world → no
        // ticker → JoinGame sits in PER_SESSION forever → no world.
        // The worker exits on its own when the server closes the channel
        // (state leaves CONNECTING/ONLINE) without clearing `ticker`.
        Thread cur = ticker;
        if (cur != null && !cur.isAlive()) { ticker = null; lastTicker = cur; }
        if (ticker != null || closed
                || SessionManager.get().isForeground(this)) return;
        // A half-dead ticker thread can linger after stopTicker() cleared the
        // field: the interrupt only wakes it from sleep, it does NOT exit
        // keepRunning(). If that zombie is still alive it will double-tick the
        // world alongside the new worker we are about to spawn -> the same
        // rubber-band teleport we are fixing. Reap it before starting fresh.
        if (lastTicker != null && lastTicker.isAlive()) {
            tickerStopRequested = true;
            lastTicker.interrupt();
        }
        synchronized (this) {
            if (ticker != null && ticker.isAlive()) return;
            tickerStopRequested = false;   // fresh worker must run, not exit
            Thread t = new Thread(new BotTicker(this), "nexus-bot-" + username);
            t.setDaemon(true);
            ticker = t;
            t.start();
        }
    }

    /** CONNECTING→ONLINE transition once this session has a live world+player.
     *  Called by NexusHooks.loadWorldForBot / respawnForBot after the JoinGame
     *  packet has built the world+player on this session — the per-session
     *  packet queue dispatches into NexusCtx, so the state transition must be
     *  triggered here (SessionContext.captureLiveContext is only reached on
     *  mount/unmount paths, not packet dispatch). */
    public void markOnlineIfReady() {
        if (world != null && player != null && state == State.CONNECTING) {
            state = State.ONLINE;
            onlineSinceNanos = System.nanoTime();
            reconnectAtNanos = 0;
            reconnectAttempts = 0;
            NexusPlugin.LOGGER.info("bot {} is now ONLINE", username);
            ensureTicker();
        }
    }

    /** Request the ticker stop — interrupt + clear the reference. The
     *  reference is saved in lastTicker so a later ensureTicker() can detect
     *  and reap a still-alive zombie before it spawns a replacement. */
    public void stopTicker() {
        Thread t = ticker;
        ticker = null;
        if (t != null) {
            lastTicker = t;
            tickerStopRequested = true;   // tell the loop to exit, not just wake
            t.interrupt();                // wake the sleep so it exits promptly
        }
    }

    public void addChat(ITextComponent c) {
        String s = c == null ? "" : c.getFormattedText();
        synchronized (chatLog) {
            chatLog.addLast(s);
            while (chatLog.size() > 200) chatLog.pollFirst();
        }
    }

    public void disconnect(String reason) {
        SessionManager mgr = SessionManager.get();
        if (mgr.mounted() == this) {
            // Hand the shared client back first; otherwise the closed NM's
            // vanilla teardown runs against our mounted state and nulls the
            // world the human view is about to inherit.
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (!mc.isCallingFromMinecraftThread()) {
                userClosed = true;
                mc.addScheduledTask(() -> disconnect(reason));
                return;
            }
            mgr.mountHuman();
        }
        closed = true;
        userClosed = true;
        state = State.DISCONNECTED;
        onlineSinceNanos = 0;
        lastError = reason;
        ReconnectManager.get().cancel(this);
        if (connection != null) {
            try {
                // NetworkManager.closeChannel waits uninterruptibly for the
                // Netty event loop. A mounted bot is disconnected on the
                // client thread, so close its channel asynchronously instead.
                io.netty.channel.Channel channel = connection.channel();
                if (channel != null) channel.close();
                else connection.closeChannel(new TextComponentString(reason));
            }
            catch (Throwable ignored) {}
        }
        SessionManager.get().resetHandler(this);
        stopTicker();
        PacketPump.drop(this);
        unmount();
        releaseReferences();
        // A stale controller keeps the closed NetworkManager reachable; the next
        // ticker's updateController would fire its onDisconnect with no handler
        // mapping and run vanilla loadWorld(null) on the shared mc off-thread.
        world = null;
        player = null;
        playerController = null;
    }

    /**
     * Drop every cached object-reference this session holds onto a (possibly
     * dead) world/GUI. Called on disconnect and on session removal — without
     * it, ~330 parked sessions each pin an entire WorldClient + its chunk
     * store via prevWorld/prevRgWorld/savedScreen (the 647MB Chunk dominator
     * path in the MAT report).
     * prevXxx/savedScreen/pendingGui are repopulated on the next mount/dispatch,
     * so nulling is free. handler is cleared by the disconnect path itself
     * (SessionManager.resetHandler) — a stale handler must not survive into a
     * reconnect or LOGIN packets get routed to the PLAY pump.
     */
    public void releaseReferences() {
        ctx.releaseReferences();
        savedScreen = null;
        pendingGui = null;
        // world/player/playerController are cleared by the disconnect path
        // itself (BotLoginHandler.onDisconnect or disconnect() callers) — do
        // NOT also clear them here unconditionally or we'd drop a session the
        // caller only wanted to release stale views of.
    }


    /** ChunkProviderClient#chunkMapping (Long2ObjectMap of live chunks),
     *  resolved on first use — the UI calls estimatedMemoryBytes() every
     *  status refresh. volatile: benign race, both threads store the same
     *  handle. */
    private static volatile java.lang.reflect.Field chunkMappingField;

    /** Rough per-bot memory estimate: world chunks loaded * ~100KB each. */
    public long estimatedMemoryBytes() {
        WorldClient w = world;
        if (w == null) return 0;
        try {
            ChunkProviderClient cp = w.getChunkProvider();
            java.lang.reflect.Field f = chunkMappingField;
            if (f == null) {
                try {
                    f = NexusRefs.field(ChunkProviderClient.class, "chunkMapping");
                } catch (NoSuchFieldException e) {
                    f = NexusRefs.field(ChunkProviderClient.class, "field_73236_b");
                }
                f.setAccessible(true);
                chunkMappingField = f;
            }
            int chunks = ((java.util.Map<?, ?>) f.get(cp)).size();
            return (long) chunks * 100L * 1024L;
        } catch (Throwable t) {
            NexusRefs.warnOnce("session:estimatedMemory", t,
                    "BotSession.estimatedMemoryBytes: cannot read {}#chunkMapping, reporting 0",
                    ChunkProviderClient.class.getName());
            return 0;
        }
    }

    /** CPU is not per-thread measurable cheaply; report mounted-time share. */
    public double cpuShare(long totalMountedNanos, long uptimeNanos) {
        long own = mountedNanos + (mountedSince != 0 ? System.nanoTime() - mountedSince : 0);
        long denom = Math.max(1L, uptimeNanos);
        return (double) own / (double) denom;
    }
}
