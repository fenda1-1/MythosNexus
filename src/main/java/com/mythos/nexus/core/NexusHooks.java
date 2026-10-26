package com.mythos.nexus.core;

import com.mythos.nexus.session.BotConnection;
import com.mythos.nexus.session.BotSession;
import com.mythos.nexus.session.PacketPump;
import com.mythos.nexus.session.SessionManager;
import com.mythos.nexus.session.SessionTicker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Static entry points called from transformed bytecode. Kept dependency-free
 * (Object params) so the transformer never drags client classes into the
 * coremod classloader early.
 *
 * The packet queue and per-session tick pump live in
 * {@link com.mythos.nexus.session.PacketPump} and
 * {@link com.mythos.nexus.session.SessionTicker} — this class is the thin
 * ASM-entry shell (onPacketRead, guards, disconnect ctx, watchdog) that
 * delegates to them.
 */
public final class NexusHooks {

    private NexusHooks() {}

    // Any path that leaves the shared Tessellator mid-batch (a preview render
    // aborting, a mounted-world entity render throwing, a GUI quad interrupted)
    // makes the NEXT frame's framebufferRenderExt hit "Already building!".
    // Flush it at the head of updateCameraAndRender so a dirty batch can never
    // survive across a frame boundary into the main FBO blit.
    private static java.lang.reflect.Field fBufIsDrawing;
    private static boolean fBufIsDrawingTried;
    /** Public so the Framebuffer transformer can call it before every blit begin. */
    public static void flushTessellatorIfBuilding() {
        try {
            net.minecraft.client.renderer.Tessellator tess =
                    net.minecraft.client.renderer.Tessellator.getInstance();
            net.minecraft.client.renderer.BufferBuilder buf = tess.getBuffer();
            if (!fBufIsDrawingTried) {
                fBufIsDrawingTried = true;
                java.lang.reflect.Field f = null;
                for (String n : new String[]{"isDrawing", "field_179010_r"}) {
                    try { f = net.minecraft.client.renderer.BufferBuilder.class.getDeclaredField(n); break; }
                    catch (NoSuchFieldException ignored) {}
                }
                if (f != null) { f.setAccessible(true); fBufIsDrawing = f; }
            }
            if (fBufIsDrawing != null && fBufIsDrawing.getBoolean(buf)) {
                tess.draw();
            }
        } catch (Throwable ignored) {}
    }

    // Tessellator.getInstance() returns one process-wide singleton whose
    // BufferBuilder.isDrawing flag crashes "Already building!" the moment two
    // threads begin() it concurrently. A bound bot tick thread (packet
    // dispatch, particle/effect handlers, mounted-respawn renderGlobal calls)
    // can reach begin() while the client thread is mid-blit in
    // framebufferRenderExt — the shared flag is the collision.
    //
    // Fix is two-sided:
    //  * tessellatorForThread() gives a bound bot thread its own ThreadLocal
    //    Tessellator, so begin()/pos()/endVertex() mutate a private buffer and
    //    never touch the shared isDrawing flag. The client thread keeps the
    //    real singleton.
    //  * vertexUploadGuard() swallows the GL half of draw() on a bot thread:
    //    Tessellator.draw() still runs finishDrawing() (pure CPU — clears
    //    isDrawing, rewinds the buffer) but WorldVertexBufferUploader.draw's
    //    glVertexPointer/glDrawArrays would crash without a GL context, so we
    //    reset() the CPU buffer and return before any GL is issued.
    private static final ThreadLocal<net.minecraft.client.renderer.Tessellator> BOT_TESS =
            new ThreadLocal<net.minecraft.client.renderer.Tessellator>() {
                @Override
                protected net.minecraft.client.renderer.Tessellator initialValue() {
                    // 32 KiB matches vanilla's DefaultVertexFormats budget tier;
                    // a bot never draws more than a stray particle quad anyway.
                    return new net.minecraft.client.renderer.Tessellator(32768);
                }
            };

    /**
     * Replacement for Tessellator.getInstance() at every rewritten call site.
     * Returns the shared INSTANCE on the client thread; a private ThreadLocal
     * Tessellator on a bound bot thread so concurrent begin() never races.
     */
    public static net.minecraft.client.renderer.Tessellator tessellatorForThread() {
        if (com.mythos.nexus.session.NexusCtx.onBotThread()) {
            return BOT_TESS.get();
        }
        return net.minecraft.client.renderer.Tessellator.getInstance();
    }

    /**
     * Head guard on WorldVertexBufferUploader.draw(BufferBuilder). Returns true
     * when running on a bound bot thread: reset() drops the CPU-side vertex
     * count and the method returns before any gl*Pointer/glDrawArrays runs —
     * there is no GL context on a bot thread. finishDrawing() already cleared
     * isDrawing on the caller's own buffer before this is reached, so skipping
     * here leaves the buffer consistent.
     */
    public static boolean vertexUploadGuard(Object bufferBuilderObj) {
        if (!com.mythos.nexus.session.NexusCtx.onBotThread()) return false;
        try {
            if (bufferBuilderObj instanceof net.minecraft.client.renderer.BufferBuilder) {
                ((net.minecraft.client.renderer.BufferBuilder) bufferBuilderObj).reset();
            }
        } catch (Throwable ignored) {}
        return true;
    }

    /** Called at head of NetworkManager.channelRead0. Returns true = packet consumed. */
    public static boolean onPacketRead(Object networkManagerObj, Packet<?> packet) {
        if (!(networkManagerObj instanceof NetworkManager)) return false;
        NetworkManager nm = (NetworkManager) networkManagerObj;
        SessionManager mgr = SessionManager.get();
        BotSession session = mgr.sessionFor(nm);

        // Every PLAY-state packet — the human's included — is marshalled to
        // the client thread inside its owner's context. mc.world/player are
        // per-session state while this mod runs; letting the human's packets
        // hit the vanilla path on the netty thread lets them mutate whatever
        // session happens to be mounted (camera jitter between accounts).
        if (session == null && mgr.humanSession().connection == nm)
            session = mgr.humanSession();
        if (session == null) return false; // login/status/unowned connection
        final BotSession ctx = session;

        // Auto-respawn: SPacketCombatEvent(ENTITY_DIED) arrives before the
        // GuiGameOver ever opens — swallow it and send PERFORM_RESPAWN on the
        // session's own NetworkManager. Bots self-heal; the human gets the
        // same treatment since the whole point is a multi-box client.
        if (handleAutoRespawn(nm, packet, ctx)) return true;

        // Route by the connection's actual protocol state, not by whether a
        // handler reference happens to be cached on the session: on reconnect
        // the attribute is still LOGIN until NetHandlerLoginClient flips it,
        // so login packets (SPacketEnableCompression/LoginSuccess) must fall
        // through to the vanilla login handler here even if a stale handler
        // survived on ctx.
        net.minecraft.network.EnumConnectionState connState =
                nm.channel().attr(NetworkManager.PROTOCOL_ATTRIBUTE_KEY).get();
        if (connState != net.minecraft.network.EnumConnectionState.PLAY)
            return false; // login/status phase — vanilla handler runs it

        final net.minecraft.network.INetHandler handler = ctx.handler;
        if (handler == null) return false; // PLAY state but handler not bound yet

        // Keepalive: respond on the netty thread immediately — no need for
        // the scheduled-task/context-swap path since handleKeepAlive only
        // sends a packet back and touches no Minecraft state. Saves the
        // 50ms tick delay that caused 15s server timeouts.
        {
            String cn = packet.getClass().getName();
            if (cn.equals("jv") || cn.endsWith("SPacketKeepAlive")) {
                // Every keepalive (one per session per ~15 s) in the main log is
                // pure noise; the failures below stay on LOGGER.warn.
                NexusLog.packet("keepalive -> %s", ctx.username);
                try {
                    @SuppressWarnings("unchecked")
                    Packet<net.minecraft.network.INetHandler> kp =
                            (Packet<net.minecraft.network.INetHandler>) packet;
                    kp.processPacket(handler);
                } catch (Throwable t) {
                    NexusPlugin.LOGGER.warn("keepalive dispatch failed for {}", ctx.username, t);
                }
                return true;
            }
        }

        @SuppressWarnings("unchecked")
        final Packet<net.minecraft.network.INetHandler> p =
                (Packet<net.minecraft.network.INetHandler>) packet;

        // The packets that decide whether a session's player is usable —
        // cheap enough to always record, invaluable when debugging mount
        // timing. Cheap name match on both SRG and obf names.
        {
            String cn = packet.getClass().getName();
            if (cn.endsWith("SPacketRespawn") || cn.endsWith("SPacketPlayerPosLook")
                    || cn.endsWith("SPacketJoinGame") || cn.endsWith("SPacketHeldItemChange")) {
                NexusLog.packet("%s sess=%s handler=%s", cn.substring(cn.lastIndexOf('.') + 1),
                        ctx.username,
                        handler.getClass().getSimpleName());
            }
        }

        // Queue the packet for dispatch on the client thread inside
        // withContext. We use our own queue instead of
        // Minecraft.addScheduledTask because the vanilla method NPEs on
        // multiplayer connections (getServer() returns null for dedicated-
        // server clients -> getTickCounter() NPE -> exceptionCaught ->
        // closeChannel -> server sees timeout).
        PacketPump.enqueue(ctx, p, handler);
        return true;
    }

    /**
     * Fires when a channel closes for ANY reason (kick, timeout, socket error,
     * local close). Bots never run NetworkManager.tick(), so NHPC.onDisconnect
     * would otherwise never run and the session would stay ONLINE forever with
     * a dead world/player — mounting it then corrupts the shared RenderGlobal.
     * Runs on the Netty IO thread; we only touch our own session fields here.
     */
    public static void onChannelInactive(NetworkManager nm) {
        try {
            SessionManager mgr = SessionManager.get();
            BotSession s = mgr.sessionFor(nm);
            // A human reconnect removes the old NetworkManager mapping before
            // the old channel's delayed inactive callback arrives. Do not let
            // that stale channel tear down the newly connected human session.
            if (s == null && mgr.humanSession().connection == nm) {
                s = mgr.humanSession();
            }
            if (s == null) {
                NexusPlugin.LOGGER.info("ignoring channelInactive for unmapped NetworkManager {}", nm);
                return;
            }
            BotConnection.onChannelClosed(s);
        } catch (Throwable t) {
            NexusLog.error("Nexus", t, "channelInactive");
        }
    }

    /** Diagnostic: a pipeline exception is about to close this channel.
     *  Runs on the Netty IO thread; print the real throwable (vanilla hides
     *  it at DEBUG). */
    public static void onNettyError(NetworkManager nm, Throwable t) {
        try {
            BotSession s = SessionManager.get().sessionFor(nm);
            NexusPlugin.LOGGER.warn("netty exception sess={} thr={}",
                    s == null ? "?" : s.username,
                    Thread.currentThread().getName(), t);
            NexusLog.error("netty exception sess=%s thr=%s", t,
                    s == null ? "?" : s.username,
                    Thread.currentThread().getName());
        } catch (Throwable ignored) {}
    }

    /** Diagnostic: closeChannel fired (remote end via channelInactive, or a
     *  local close). Prints reason + thread so a silent mass-close names its
     *  origin. */
    public static void onChannelClose(NetworkManager nm, Object reason) {
        try {
            BotSession s = SessionManager.get().sessionFor(nm);
            NexusPlugin.LOGGER.warn("closeChannel sess={} thr={} reason={}",
                    s == null ? "?" : s.username,
                    Thread.currentThread().getName(), reason);
        } catch (Throwable ignored) {}
    }

    /** Drain the packet queue — kept as a stable public hook; delegates to
     *  PacketPump. Also invoked from runSessionTicks head. */
    public static void pumpPackets() {
        PacketPump.pump();
    }

    /**
     * Head guard injected into Minecraft.runTickMouse / processKeyBinds /
     * runTickKeyboard. Returns true → skip the body. Vanilla assumes
     * world+player non-null together; a mounted session that isn't in a
     * world yet (connecting, or human at title) satisfies neither, and the
     * mouse/key handlers NPE.
     */

    /**
     * Guard for EntityRenderer.updateCameraAndRender (func_181560_a). Unlike
     * tickGuard, the title screen (world==null AND player==null) is a LEGAL
     * state that must still render — updateCameraAndRender also draws the
     * GuiScreen. We only skip in the INCONSISTENT state where the world got
     * torn down but a stale renderViewEntity still points at a dead player
     * (the original NPE at func_181560_a:1039).
     */
    public static boolean renderGuard(Object erObj) {
        // Flush any tessellator batch a previous pass left mid-build — this is
        // the one choke point every world render passes through, so a dirty
        // batch dies here instead of crashing the next framebufferRender blit.
        flushTessellatorIfBuilding();
        // erObj is EntityRenderer — grab its mc field reflectively to dodge obf names.
        net.minecraft.client.Minecraft mc;
        try {
            java.lang.reflect.Field f = erObj.getClass().getDeclaredField("field_78531_r"); // mc
            f.setAccessible(true);
            mc = (net.minecraft.client.Minecraft) f.get(erObj);
        } catch (Throwable t) {
            try {
                java.lang.reflect.Field f = erObj.getClass().getDeclaredField("mc");
                f.setAccessible(true);
                mc = (net.minecraft.client.Minecraft) f.get(erObj);
            } catch (Throwable t2) { return true; }
        }
        if (mc == null) return true;
        // A GuiScreen is open: updateCameraAndRender must always run so the
        // screen's drawScreen paints. drawScreen does not touch
        // renderViewEntity, and an NPE in the world-render part is swallowed
        // by the method-wide try/catch — blocking the whole frame here is what
        // left the Nexus account UI permanently blue when mounted==none.
        if (mc.currentScreen != null) return false;
        // Title screen (world==null && viewEntity==null): render GUI, legal.
        if (mc.world == null && mc.getRenderViewEntity() == null) return false;
        // world!=null && viewEntity!=null: render world+GUI, legal.
        if (mc.world != null && mc.getRenderViewEntity() != null) return false;
        // Inconsistent states — either direction dereferences a null:
        //   world!=null, view==null → isSpectator() NPE at func_181560_a:1039
        //   world==null, view!=null → world access NPEs deeper in the call
        return true;
    }

    /**
     * RenderManager.renderEntity 头部守卫：保证 renderViewEntity 非 null。
     * 为 null 时用 mc.getRenderViewEntity() 填回，填不上就跳过本次实体渲染。
     *
     * @return true 表示调用方应直接 return
     */
    public static boolean renderViewEntityGuard(Object renderManager) {
        if (renderManager == null) return false;
        java.lang.reflect.Field f = renderViewField(renderManager.getClass());
        if (f == null) return false;
        try {
            if (f.get(renderManager) != null) return false;
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            net.minecraft.entity.Entity fix = mc == null ? null : mc.getRenderViewEntity();
            if (fix == null) {
                logViewFill("renderEntity 阶段 renderViewEntity 为空且无可补，跳过该实体");
                return true;
            }
            f.set(renderManager, fix);
            logViewFill("renderEntity 阶段 renderViewEntity 为空，已补齐");
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    private static volatile boolean viewFillLogged;

    private static void logViewFill(String what) {
        if (viewFillLogged) return;
        viewFillLogged = true;
        NexusPlugin.LOGGER.info("[guard] {}", what);
    }

    private static volatile java.lang.reflect.Field RM_VIEW_FIELD;

    /**
     * RenderManager.renderViewEntity 在 obf / srg / mcp 三种状态下名字都不同，
     * 先按已知名字找，再按「这个类里唯一的 Entity 类型字段」兜底 —— 不能靠名字
     * 硬编码，否则换映射就静默失效。
     */
    private static java.lang.reflect.Field renderViewField(Class<?> rmClass) {
        java.lang.reflect.Field cached = RM_VIEW_FIELD;
        if (cached != null && cached.getDeclaringClass() == rmClass) return cached;
        for (String n : new String[]{"field_78734_h", "renderViewEntity"}) {
            try {
                java.lang.reflect.Field c = rmClass.getDeclaredField(n);
                c.setAccessible(true);
                RM_VIEW_FIELD = c;
                return c;
            } catch (Throwable ignored) {
            }
        }
        for (java.lang.reflect.Field c : rmClass.getDeclaredFields()) {
            if (c.getType() == net.minecraft.entity.Entity.class) {
                try { c.setAccessible(true); } catch (Throwable ignored) { }
                RM_VIEW_FIELD = c;
                return c;
            }
        }
        return null;
    }

    /** 每帧把 RenderManager.renderViewEntity 补上；已非 null 或无可补时不动。 */
    public static void fillRenderViewEntity() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (mc == null) return;
            net.minecraft.entity.Entity view = mc.getRenderViewEntity();
            if (view == null) return;
            Object rm = mc.getRenderManager();
            if (rm == null) return;
            java.lang.reflect.Field f = renderViewField(rm.getClass());
            if (f != null && f.get(rm) == null) {
                f.set(rm, view);
                logViewFill("每帧补帧阶段 renderViewEntity 为空，已补齐");
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * EntityRenderer calls IntegratedServer.is...() from its render loop even
     * for a normal multiplayer client, where getIntegratedServer() is null.
     * Keep that vanilla probe harmless and preserve the branch's intent:
     * without an integrated server there is nothing to pause/update.
     */
    /**
     * Head guard injected into EntityRenderer.getMouseOver (func_78473_a).
     * getMouseOver ray-traces through mc.world unconditionally; on a client
     * thread during a mount/disconnect window mc.world is transiently null
     * while renderViewEntity still holds a stale entity, so the
     * getEntitiesInAABBexcluding call NPEs (crash EntityRenderer.java:356).
     * Skip when the world isn't there to trace — mouse-over just stays stale
     * for one tick, which is harmless. We deliberately skip even when
     * world==null && view==null (title screen): vanilla only calls
     * getMouseOver when in a world, but a bot handoff can re-enter it early.
     */
    public static boolean mouseGuard(Object erObj) {
        net.minecraft.client.Minecraft mc;
        try {
            java.lang.reflect.Field f = erObj.getClass().getDeclaredField("field_78531_r"); // mc
            f.setAccessible(true);
            mc = (net.minecraft.client.Minecraft) f.get(erObj);
        } catch (Throwable t) {
            try {
                java.lang.reflect.Field f = erObj.getClass().getDeclaredField("mc");
                f.setAccessible(true);
                mc = (net.minecraft.client.Minecraft) f.get(erObj);
            } catch (Throwable t2) { return true; }
        }
        if (mc == null) return true;
        // getMouseOver dereferences mc.world AND the render view entity.
        // Skip whenever either half is missing — the result is only used for
        // crosshair highlight/pick, which can wait a tick.
        return mc.world == null || mc.getRenderViewEntity() == null;
    }

    /**
     * Head guard injected into WorldClient.updateBlocks. updateBlocks drives
     * client-side ambience: randomDisplayTick particles, mood sounds, void-
     * fog and barrier particles — every path ends in mc.effectRenderer /
     * mc.getSoundHandler, i.e. GL + OpenAL. On a background bot thread there
     * is no OpenGL context, so these calls throw "No OpenGL context found in
     * the current thread" and the tick aborts. A background world is never
     * rendered, so skipping its ambient particles/sounds is purely a win —
     * real (gameplay) block random ticks are server-side and unaffected.
     * The mounted/foreground world keeps the vanilla path.
     */
    public static boolean backgroundWorldGuard(Object thisObj) {
        return com.mythos.nexus.session.NexusCtx.onBotThread();
    }

    public static boolean safeIntegratedServerReady(Object server) {
        if (server == null) return true;
        try {
            if (server instanceof net.minecraft.server.integrated.IntegratedServer)
                return ((net.minecraft.server.integrated.IntegratedServer) server).isServerRunning();
        } catch (Throwable ignored) {}
        return true;
    }

    /**
     * Resolve Minecraft.getMinecraft() reflectively — the compile-time name
     * doesn't exist in the obf jar (it's func_71410_x there).
     */
    private static java.lang.reflect.Method getMcM;
    private static boolean getMcTried;

    public static Minecraft resolveMinecraft() {
        if (!getMcTried) {
            getMcTried = true;
            try { getMcM = Minecraft.class.getDeclaredMethod("func_71410_x"); }
            catch (Throwable t) {
                try { getMcM = Minecraft.class.getDeclaredMethod("getMinecraft"); }
                catch (Throwable t2) {
                    // Latching this failure makes every caller degrade silently
                    // (tickGuard skips all ticks, the live view stays empty), so
                    // report it once instead of staying null forever.
                    NexusRefs.warnOnce("hooks:resolveMinecraft", t2,
                            "NexusHooks.resolveMinecraft: neither func_71410_x nor "
                            + "getMinecraft exists on Minecraft — all tick guards will skip");
                }
            }
            if (getMcM != null) getMcM.setAccessible(true);
        }
        if (getMcM == null) return null;
        try { return (Minecraft) getMcM.invoke(null); }
        catch (Throwable t) {
            NexusRefs.warnOnce("hooks:getMinecraft:invoke", t,
                    "NexusHooks.resolveMinecraft: getMinecraft() invoke failed");
            return null;
        }
    }

    public static boolean tickGuard(Object thisObj) {
        // thisObj is Minecraft for the tick handlers, McpGameService for
        // McpGameService.tick, EntityRenderer for updateRenderer. Resolve
        // Minecraft either way.
        Minecraft mc;
        if (thisObj instanceof Minecraft) {
            mc = (Minecraft) thisObj;
        } else {
            mc = resolveMinecraft();
            if (mc == null) return true; // can't resolve → skip tick, safe
        }
        if (mc.player != null && mc.world != null) return false;
        if (!tickSkipLogged) {
            tickSkipLogged = true;
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            NexusPlugin.LOGGER.info("[guard] tick 跳过 player={} world={} caller={}",
                    mc.player != null, mc.world != null,
                    st.length > 2 ? st[2].getClassName() : "?");
        }
        return true;
    }

    private static volatile boolean tickSkipLogged;

    // Two probes: (a) client-thread heartbeat via runSessionTicks (already
    // injected at runTick head, fires every vanilla tick ~20Hz); (b) a daemon
    // thread that diffs heartbeat rate, GL error state, framebuffer binding
    // and Display flags every 2s and logs a single-line snapshot. When the
    // screen goes black the last snapshot before the stall is the smoking gun.

    private static final AtomicLong tickCounter = new AtomicLong();
    private static final AtomicLong glErrSticky = new AtomicLong(-1);
    private static Thread watchdogThread;
    private static net.minecraft.client.entity.EntityPlayerSP lastHumanPlayer;
    private static int lastHumanTick;
    private static double lastHumanX = Double.NaN, lastHumanY, lastHumanZ;

    /** GL11.glGetError — resolved once; heartbeat() runs every tick. */
    private static volatile java.lang.reflect.Method glGetErrorM;

    private static void heartbeat() {
        tickCounter.incrementAndGet();
        // Sample GL error cheaply once per second (every 20 ticks) — calling
        // glGetError every tick would hide real errors behind our own probe.
        if (tickCounter.get() % 20 == 0) {
            try {
                java.lang.reflect.Method m = glGetErrorM;
                if (m == null) {
                    m = Class.forName("org.lwjgl.opengl.GL11").getMethod("glGetError");
                    glGetErrorM = m;
                }
                int err = (Integer) m.invoke(null);
                if (err != 0) glErrSticky.compareAndSet(-1, err);
            } catch (Throwable t) {
                // Retried on the next probe (the handle may appear once LWJGL's
                // GL context is up); only the first failure is reported.
                NexusRefs.warnOnce("hooks:glGetError", t,
                        "heartbeat: GL11.glGetError unavailable, GL error tracking disabled");
            }
        }
        // Track the human's player while a bot is mounted — only log when
        // the position moves (server should never teleport <you> while
        // we're driving a bot's connection).
        net.minecraft.client.Minecraft mc = resolveMinecraft();
        if (mc == null) return;
        net.minecraft.client.entity.EntityPlayerSP hp =
                SessionManager.get().humanSession().player;
        if (hp != null) {
            if (hp != lastHumanPlayer || hp.ticksExisted != lastHumanTick) {
                double dx = hp.posX - lastHumanX;
                double dy = hp.posY - lastHumanY;
                double dz = hp.posZ - lastHumanZ;
                double dist2 = dx*dx + dy*dy + dz*dz;
                if (lastHumanPlayer == null || dist2 > 0.001) {
                    net.minecraft.client.entity.EntityPlayerSP cp = mc.player;
                    NexusLog.tick(
                            "human pos=%.1f,%.1f,%.1f ticks=%d mc.player=%s",
                            hp.posX, hp.posY, hp.posZ, hp.ticksExisted,
                            cp == null ? "null"
                                    : Integer.toHexString(System.identityHashCode(cp)) + "@" + cp.getName());
                }
                lastHumanPlayer = hp;
                lastHumanTick = hp.ticksExisted;
                lastHumanX = hp.posX;
                lastHumanY = hp.posY;
                lastHumanZ = hp.posZ;
            }
        } else {
            lastHumanPlayer = null;
            lastHumanX = Double.NaN;
        }
        // Track the mounted bot's player — did its EntityPlayerSP actually move?
        net.minecraft.client.entity.EntityPlayerSP mp = mc.player;
        if (mp != null && mp != hp) {
            double dx = mp.posX - lastBotX;
            double dy = mp.posY - lastBotY;
            double dz = mp.posZ - lastBotZ;
            double dist2 = dx*dx + dy*dy + dz*dz;
            if (mp != lastBotPlayer || dist2 > 0.01) {
                NexusLog.tick(
                        "bot pos=%.1f,%.1f,%.1f ticks=%d sess=%s",
                        mp.posX, mp.posY, mp.posZ, mp.ticksExisted,
                        mp.getName());
            }
            lastBotPlayer = mp;
            lastBotX = mp.posX;
            lastBotY = mp.posY;
            lastBotZ = mp.posZ;
        } else {
            lastBotPlayer = null;
            lastBotX = Double.NaN;
        }
    }

    private static net.minecraft.client.entity.EntityPlayerSP lastBotPlayer;
    private static double lastBotX = Double.NaN, lastBotY, lastBotZ;

    /** Spawn once — cheap and idempotent. */
    private static void startWatchdog() {
        if (watchdogThread != null) return;
        watchdogThread = new Thread(new Runnable() {
            @Override public void run() {
                long lastTick = 0;
                while (true) {
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { return; }
                    Minecraft mc = resolveMinecraft();
                    if (mc == null) continue;
                    long t = tickCounter.get();
                    long ticks = t - lastTick;
                    lastTick = t;
                    long gle = glErrSticky.getAndSet(-1);
                    // A snapshot every 2 s belongs in watchdog.log (enable with
                    // -Dnexus.debugLog=true), not in the console. Only a stalled
                    // tick or a GL error is worth the main log, because those are
                    // the two states that actually freeze the screen.
                    boolean anomaly = ticks == 0 || gle >= 0;
                    if (!NexusLog.ENABLED && !anomaly) {
                        continue;
                    }
                    // tick rate, mounted session, world/player/screen, glErr
                    SessionManager mgr = SessionManager.get();
                    BotSession m = mgr.mounted();
                    String mname = m == null ? "none" : m.username;
                    String w = mc.world == null ? "null" : Integer.toHexString(System.identityHashCode(mc.world));
                    String p = mc.player == null ? "null" : Integer.toHexString(System.identityHashCode(mc.player));
                    String s = mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName();
                    Object ve = mc.getRenderViewEntity();
                    String veS = ve == null ? "null" : Integer.toHexString(System.identityHashCode(ve));
                    Object rg = mc.renderGlobal;
                    String rgwS = "null";
                    if (rg != null) {
                        try {
                            java.lang.reflect.Field wf = rg.getClass().getDeclaredField("field_72769_h"); // RenderGlobal.world
                            wf.setAccessible(true);
                            Object rgw = wf.get(rg);
                            rgwS = rgw == null ? "null" : Integer.toHexString(System.identityHashCode(rgw));
                        } catch (Throwable t2) { try { java.lang.reflect.Field wf = rg.getClass().getDeclaredField("world"); wf.setAccessible(true); Object rgw = wf.get(rg); rgwS = rgw == null ? "null" : Integer.toHexString(System.identityHashCode(rgw)); } catch (Throwable t3) { rgwS = "?"; } }
                    }
                    String rgS = (rg == null ? "null" : Integer.toHexString(System.identityHashCode(rg))) + "/" + rgwS;
                    // Display state via reflection (coremod can reference it but
                    // avoid hard dependency — some test contexts have no Display)
                    String disp;
                    try {
                        Class<?> d = Class.forName("org.lwjgl.opengl.Display");
                        boolean close = (Boolean) d.getMethod("isCloseRequested").invoke(null);
                        boolean active = (Boolean) d.getMethod("isActive").invoke(null);
                        boolean visible = (Boolean) d.getMethod("isVisible").invoke(null);
                        disp = "close=" + close + " active=" + active + " vis=" + visible;
                    } catch (Throwable t2) { disp = "err"; }
                    String line = "[wd] tick+" + ticks + "/2s mounted=" + mname
                            + " w=" + w + " p=" + p + " ve=" + veS + " rg=" + rgS
                            + " screen=" + s + " gl=" + gle + " disp[" + disp + "]";
                    if (NexusLog.ENABLED) {
                        NexusLog.log("watchdog", line);
                    }
                    if (anomaly) {
                        NexusPlugin.LOGGER.info(line);
                    }
                }
            }
        }, "nexus-watchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.start();
    }

    /**
     * displayGuiScreen writes mc.currentScreen AND re-inits GuiGameOver /
     * other screens against the *mounted* session's world — if a bot's
     * packet opens a death/gameover screen while it's mounted, or the
     * human's screen flickers into a bot's world, that NPEs.
     *
     * The lazy fix: wrap displayGuiScreen itself. When a bot's packet is
     * dispatching inside withContext the session fields ARE live, so the
     * screen init is safe; the crash only happens when something calls
     * displayGuiScreen on the client thread while an OFFLINE session is
     * mounted (world==null → GuiGameOver.initGui does
     * mc.world.getWorldInfo() → NPE). Gate that case: if the incoming
     * screen is a GuiGameOver and there's no live world, swallow the call —
     * a dead player on a session with no world is a no-op anyway.
     *
     * @return true to skip vanilla displayGuiScreen
     */
    public static boolean guiGuard(Object mcObj, Object screenObj) {
        Minecraft mc = (Minecraft) mcObj;
        // Any displayGuiScreen — including the `null` "close screen" form a
        // first SPacketPlayerPosLook sends to dismiss GuiDownloadTerrain — a
        // BACKGROUND session dispatches belongs to that bot, not to the
        // mounted player. Vanilla displayGuiScreen touches LWJGL/GL
        // (ScaledResolution/setIngameFocus/SoundHandler resume) which has no
        // context on a bot thread → "No OpenGL context" crash. Swallow the
        // whole call; stash the desired screen on savedScreen so a later
        // mount restores exactly what the bot was shown. screenObj may be
        // null (plain close) — that's just savedScreen=null, still correct.
        BotSession gctx = CTX.get();
        if (gctx != null && !SessionManager.get().isForeground(gctx)) {
            gctx.savedScreen = (net.minecraft.client.gui.GuiScreen) screenObj;
            // displayGuiScreen is swallowed, so GuiContainer.initGui() never
            // runs and `player.openContainer = inventorySlots` never happens.
            // Without this the bot's openContainer stays inventoryContainer
            // (46 slots): the server's SPacketWindowItems then overflows it
            // (IndexOutOfBounds at index 46) and every windowClick targets the
            // wrong container. Replay the one initGui side-effect the session
            // actually needs — install the new container on the BOT's player.
            if (screenObj instanceof net.minecraft.client.gui.inventory.GuiContainer
                    && gctx.player != null) {
                gctx.player.openContainer =
                        ((net.minecraft.client.gui.inventory.GuiContainer) screenObj).inventorySlots;
            }
            return true;
        }
        if (screenObj == null) return false;
        String name = screenObj.getClass().getName();
        if (name.endsWith("GuiGameOver") || name.endsWith("GuiDisconnected")) {
            // Death / disconnect screens NPE on mc.world when the mounted
            // session is offline — swallow; the triggering packet is already
            // in the log and a dead player on a world-less session is a no-op.
            // Human kicked: vanilla already nulled mc.world, the screen must show.
            return mc.world == null && SessionManager.get().botMounted();
        }
        return false;
    }

    /**
     * loadWorld calls renderGlobal.setWorldAndLoadRenderers(wc) which frees
     * the shared viewFrustum + restarts chunk workers = one black frame on
     * whatever session is mounted. When a background session dispatches
     * SPacketJoinGame/SPacketRespawn that call must NOT touch the shared
     * renderer — the bot's world still lands in mc.world, it just doesn't
     * own RenderGlobal. Mounted-session dispatches (the human joining or a
     * mounted bot's respawn) still need the real rebuild, so only skip
     * while a non-mounted session is on CTX.
     *
     * Signature matches the bytecode splice in transformMinecraft: the
     * original call's `renderGlobal` and `wc` args are forwarded; we either
     * delegate or drop.
     */
    public static void loadWorldGuard(Object renderGlobalObj, Object worldObj) {
        BotSession ctx = CTX.get();
        // Bot tick threads have no GL context and never own RenderGlobal.
        if (ctx == null && com.mythos.nexus.session.NexusCtx.onBotThread()) return;
        // Delegate to the real rebuild for the FOREGROUND owner — not just a
        // mounted bot. When mounted==null the human pseudo-session IS the
        // foreground and carries a non-null ctx; the old `ctx==mounted()` test
        // treated that as background and dropped setWorldAndLoadRenderers, so
        // RenderGlobal.world stayed null and the joined world rendered as a
        // solid blue sky-clear. isForeground() resolves mounted or the human
        // correctly in both cases.
        if (ctx == null || SessionManager.get().isForeground(ctx)) {
            ((net.minecraft.client.renderer.RenderGlobal) renderGlobalObj)
                    .setWorldAndLoadRenderers(
                            (net.minecraft.client.multiplayer.WorldClient) worldObj);
        }
        // else: a background session's join/respawn — keep its world in
        // mc.world (already written by loadWorld before this call), just
        // leave RenderGlobal pointed at the mounted session's world.
    }

    /**
     * Session-scoped replacement for Minecraft.loadWorld/func_71353_a when it
     * runs on a background session's context (bot tick thread). Vanilla
     * loadWorld does ~225 bytecode ops that write the SHARED mc singleton
     * (world/player/playerController/renderViewEntity/myNetworkManager) and
     * call SHARED render+sound objects (entityRenderer.updateRenderer → GL,
     * ParticleManager.clearEffects, TileEntityRendererDispatcher.setWorld,
     * clearRenderCache, mcSoundHandler, LoadingScreenRenderer, System.gc,
     * WorldEvent Unload posts). On a bot thread that both (a) never lands the
     * bot's world in BotSession.world — so markOnlineIfReady never fires and
     * the bot is stuck CONNECTING — and (b) races/stomps the mounted
     * session's render state on the client thread.
     *
     * Here we perform only the parts a background session actually needs, on
     * the SESSION's own fields, never touching shared objects:
     *   s.world = wc
     *   s.playerController = the controller handleJoinGame already routed in
     *     (NetHandlerPlayClient is field-ctx → its putfield wrote s.playerController)
     *   s.player = playerController.createPlayer(wc, ...)   [if null]
     *   player.movementInput = MovementInputFromOptions(gameSettings)
     *   playerController.flipPlayer(player) + .setController(player)
     * renderViewEntity resolves to s.player through NexusCtx, so nothing else
     * needs wiring.
     *
     * Returns true when it handled the call (background session) — the
     * caller's real loadWorld body is skipped. Returns false on the client
     * thread / mounted session so vanilla still runs for the human.
     */
    public static boolean loadWorldForBot(Object mcObj, Object wcObj) {
        BotSession s = CTX.get();
        // Foreground (mounted or, when nothing is mounted, the human) must run
        // the REAL vanilla loadWorld — it owns the shared mc fields. Checking
        // `s == mounted` alone is wrong because mounted==null while the human
        // is the foreground owner; isForeground covers both cases.
        // No dispatch context on a bot tick thread = a stale NetworkManager's
        // disconnect (handler already unmapped). Vanilla loadWorld here would
        // null the shared mc.player/world off-thread — drop it.
        if (s == null && com.mythos.nexus.session.NexusCtx.onBotThread()) {
            NexusPlugin.LOGGER.warn("loadWorld on bot thread without session ctx ({}) — dropped",
                    com.mythos.nexus.session.NexusCtx.current().username);
            return true;
        }
        // GuiIngameMenu "Disconnect" while a bot is mounted: vanilla would tear
        // down the shared client (particles, TE dispatcher, server data) on
        // behalf of the bot. Close just the bot and hand the view back.
        if (s == null && wcObj == null && !Boolean.TRUE.equals(DISC_MOUNTED.get())) {
            SessionManager mgr = SessionManager.get();
            BotSession m = mgr.mounted();
            // Offline bot mounted (title screen): nothing shared to tear down.
            if (m != null && m == mgr.offlineMounted()) return true;
            if (m != null && m != mgr.humanSession()) {
                m.userClosed = true;
                if (mgr.humanSession().world != null && mgr.humanSession().player != null) {
                    NexusPlugin.LOGGER.info("quit while mounted on {} — closing bot, back to human", m.username);
                    mgr.mountHuman();
                    m.disconnect("Quitting");
                    quitSuppressUntil = System.currentTimeMillis() + 2000;
                    return true;
                }
            }
        }
        if (s == null || SessionManager.get().isForeground(s)) return false;
        Minecraft mc = (Minecraft) mcObj;
        net.minecraft.client.multiplayer.WorldClient wc =
                (net.minecraft.client.multiplayer.WorldClient) wcObj;
        s.world = wc;
        if (wc == null) {
            // loadWorld(null) — disconnect teardown. Drop the session's own
            // entities only; the shared client is untouched.
            s.player = null;
            return true;
        }
        if (s.playerController == null) {
            if (s.handler == null) {
                NexusPlugin.LOGGER.warn("loadWorldForBot {}: handler still null — player will NPE on tick", s.username);
            }
            s.playerController = new net.minecraft.client.multiplayer.PlayerControllerMP(
                    mc, s.handler);
        }
        if (s.player == null || s.player.isDead || s.player.world != wc) {
            // Reuse respawnForBot — it removes the stale entity (if any),
            // rebuilds a fresh EntityPlayerSP on this world, and spawns it.
            return respawnForBot(mc, s.player != null ? s.player.dimension : 0);
        }
        // s.player is alive and belongs to this world — just ensure it is in
        // loadedEntityList (it should be, unless something removed it).
        s.player.preparePlayerToSpawn();
        if (!wc.loadedEntityList.contains(s.player)) {
            wc.spawnEntity(s.player);
        }
        s.player.movementInput = new net.minecraft.util.MovementInputFromOptions(
                mc.gameSettings);
        s.playerController.setPlayerCapabilities(s.player);
        s.markOnlineIfReady();
        return true;
    }

    /**
     * Bot-side setDimensionAndSpawnPlayer (SPacketRespawn). The vanilla method
     * (Minecraft.java:2054-2087) rebuilds the player entity and re-adds it to
     * the world — but it does so on the shared mc.world/player/renderViewEntity
     * singletons, so on a bot tick thread it would corrupt the mounted session
     * and never respawn the bot's own entity. Reproduce the essential parts on
     * the bound session's fields: swap the stale player for a fresh
     * EntityPlayerSP, carry over stats/recipes/data, then prepare+spawn so the
     * new player is a live world entity (addedToChunk=true, in loadedEntityList)
     * — the same two calls loadWorldForBot needs.
     *
     * Returns true when it handled the call (background session) — the caller's
     * vanilla body is skipped. False on client thread / foreground so the human
     * still respawns normally.
     */
    public static boolean respawnForBot(Object mcObj, int dimension) {
        BotSession s = CTX.get();
        if (s == null || SessionManager.get().isForeground(s)) return false;
        Minecraft mc = (Minecraft) mcObj;
        net.minecraft.client.multiplayer.WorldClient wc = s.world;
        if (wc == null) {
            // Respawn with no world — join flow will rebuild it via loadWorld.
            return true;
        }
        if (s.playerController == null) {
            s.playerController = new net.minecraft.client.multiplayer.PlayerControllerMP(
                    mc, s.handler);
        }
        net.minecraft.client.entity.EntityPlayerSP old = s.player;
        int oldId = old != null ? old.getEntityId() : 0;
        String brand = old != null ? old.getServerBrand() : null;
        // Remove EVERY EntityPlayerSP currently in loadedEntityList — not just
        // `old`. A previous loadWorldForBot/respawn may have left a stale
        // EntityPlayerSP ticking on this bot thread; it keeps calling
        // onUpdateWalkingPlayer → sendPacket on this session's channel with its
        // own (drifting) position while s.player sits frozen — that's the
        // "teleport" the server sees. Purge all of them before spawning the
        // fresh player.
        for (int i = wc.loadedEntityList.size() - 1; i >= 0; i--) {
            net.minecraft.entity.Entity e = wc.loadedEntityList.get(i);
            if (e instanceof net.minecraft.client.entity.EntityPlayerSP) {
                wc.removeEntity(e);
            }
        }
        net.minecraft.client.entity.EntityPlayerSP np = s.playerController.createPlayer(
                wc,
                old != null ? old.getStatFileWriter() : new net.minecraft.stats.StatisticsManager(),
                old != null ? old.getRecipeBook() : new net.minecraft.client.util.RecipeBookClient());
        if (np.connection == null) {
            NexusPlugin.LOGGER.warn("respawnForBot {}: EntityPlayerSP.connection is null — handler={}", s.username, s.handler);
        }
        if (np.getGameProfile() == null) {
            NexusPlugin.LOGGER.warn("respawnForBot {}: EntityPlayerSP.gameProfile is null", s.username);
        }
        if (old != null) {
            try { np.getDataManager().setEntryValues(old.getDataManager().getAll()); }
            catch (Throwable ignored) {}
            try { np.updateSyncFields(old); } catch (Throwable ignored) {}
            try { np.setReducedDebug(old.hasReducedDebug()); } catch (Throwable ignored) {}
        }
        np.dimension = dimension;
        if (brand != null) np.setServerBrand(brand);
        np.preparePlayerToSpawn();
        wc.spawnEntity(np);
        s.playerController.flipPlayer(np);
        // Same as vanilla: install MovementInputFromOptions so the field type
        // matches what loadWorld produces. BotTicker strips it to a bare
        // MovementInput on the next tick — Baritone then installs its own
        // PlayerMovementInput when it takes control.
        np.movementInput = new net.minecraft.util.MovementInputFromOptions(mc.gameSettings);
        if (oldId != 0) np.setEntityId(oldId);
        s.playerController.setPlayerCapabilities(np);
        s.player = np;
        s.markOnlineIfReady();
        return true;
    }

    /**
     * True while a non-mounted session is dispatching packets inside
     * withContext.  MythosScript (and any other mod reading
     * Minecraft.getMinecraft() from the packet thread) sees the *mounted*
     * session's world/player during that window — which is exactly wrong.
     * Callers that can ask this guard skip their work entirely; callers
     * that can't (background world rendering for non-mounted bots is the
     * whole point of this mod) must tolerate the singleton pointing at a
     * different session for the duration of the dispatch.
     */
    private static final ThreadLocal<BotSession> CTX = new ThreadLocal<>();
    public static void enterContext(BotSession s) { CTX.set(s); }
    public static void exitContext()            { CTX.remove(); }
    /** The session currently dispatching on this thread, or null if the
     *  thread is outside withContext (e.g. the client tick loop). */
    public static BotSession inContext() { return CTX.get(); }
    /** True when the live mc.world/mc.player belong to a DIFFERENT session
     *  than the one whose code is running — i.e. MythosScript features
     *  should freeze their *static* state reads for this tick.  Returns
     *  false on the client thread (no in-flight session context), true on
     *  a packet thread while a non-mounted session dispatches. */
    public static boolean contextMismatch() {
        BotSession ctx = CTX.get();
        return ctx != null && ctx != SessionManager.get().mounted();
    }

    //
    // NetHandlerPlayClient.onDisconnect runs mc.loadWorld(null) +
    // displayGuiScreen(GuiDisconnected). For a bot handler that would nuke
    // the MOUNTED session's world and hang the client. The transformer
    // wraps the body: push the owning session's context (its fields get the
    // loadWorld(null) clearing + the GuiDisconnected lands in its
    // savedScreen), then pop and restore whatever screen was showing.
    private static final ThreadLocal<BotSession> DISC_CTX = new ThreadLocal<>();
    private static final ThreadLocal<Object> DISC_SCREEN = new ThreadLocal<>();

    /** @return true = skip the vanilla onDisconnect body. */
    public static boolean pushDisconnectCtx(Object handler) {
        if (!(handler instanceof NetHandlerPlayClient)) return false;
        SessionManager mgr = SessionManager.get();
        BotSession s = mgr.sessionForHandler((NetHandlerPlayClient) handler);
        if (s == null) {
            // Unmapped handler on a bot thread (stale NM ticked after reconnect):
            // vanilla would build GuiMainMenu off-thread and wipe shared mc state.
            return com.mythos.nexus.session.NexusCtx.onBotThread();
        }
        s.state = BotSession.State.DISCONNECTED;
        boolean clientThread = resolveMinecraft().isCallingFromMinecraftThread();
        if (!clientThread && !mgr.isForeground(s)) {
            // Background bot: vanilla body needs GL (GuiMainMenu ctor) and would
            // leave the bot's world/player installed in mc if it threw.
            // BotConnection.onChannelClosed owns cleanup + auto-reconnect.
            s.world = null;
            s.player = null;
            s.playerController = null;
            s.savedScreen = null;
            return true;
        }
        if (s == mgr.humanSession() && mgr.isForeground(s)) {
            // Plain vanilla: loadWorld(null) + GuiDisconnected with the kick reason.
            return false;
        }
        DISC_CTX.set(s);
        if (mgr.isForeground(s)) {
            // A mounted session's disconnect must run in the LIVE context: the
            // singleton holds the session's own fields and vanilla loadWorld(null)
            // has to tear down the real viewFrustum (a pushed ctx would let it
            // clear session fields the world unload never touched). The GuiDisconnected
            // would land on the user's GUI — nothing restores the mount. Instead
            // null out the singleton by hand (loadWorld(null) minus the screen pop)
            // and flag the pop to skip BOTH halves. The scheduled mountHuman() in
            // BotConnection then re-persists the human fields.
            DISC_MOUNTED.set(true);
            DISC_SCREEN.set(resolveMinecraft().currentScreen);
            Minecraft mc = resolveMinecraft();
            try {
                java.lang.reflect.Field fw = net.minecraft.client.Minecraft.class
                        .getDeclaredField("field_71462_r");   // mc.world
                java.lang.reflect.Field fp = net.minecraft.client.Minecraft.class
                        .getDeclaredField("field_71439_g");   // mc.player
                java.lang.reflect.Field fc = net.minecraft.client.Minecraft.class
                        .getDeclaredField("field_71442_b");   // mc.playerController
                java.lang.reflect.Field fv = net.minecraft.client.Minecraft.class
                        .getDeclaredField("field_175622_Z");  // renderViewEntity
                fw.setAccessible(true); fp.setAccessible(true); fc.setAccessible(true); fv.setAccessible(true);
                fw.set(mc, null); fp.set(mc, null); fc.set(mc, null); fv.set(mc, null);
            } catch (Throwable ignored) {}
            if (mc.renderGlobal != null) {
                mc.renderGlobal.setWorldAndLoadRenderers(null);
            }
            return false;
        }
        Minecraft mc = resolveMinecraft();
        DISC_SCREEN.set(mc.currentScreen);
        s.enterContextNoStatics();
        return false;
    }

    private static final ThreadLocal<Boolean> DISC_MOUNTED = new ThreadLocal<>();

    private static volatile long quitSuppressUntil;

    /** True once after a mounted-bot quit: the server list GuiIngameMenu opens next must be skipped. */
    public static boolean consumeQuitSuppress() {
        if (System.currentTimeMillis() > quitSuppressUntil) return false;
        quitSuppressUntil = 0;
        return true;
    }

    public static void popDisconnectCtx() {
        BotSession s = DISC_CTX.get();
        if (s == null) return;
        DISC_CTX.remove();
        Minecraft mc = resolveMinecraft();
        try {
            if (DISC_MOUNTED.get() != null && DISC_MOUNTED.get()) {
                DISC_MOUNTED.remove();
                // Push-side already nulled the shared singleton and tore down the
                // viewFrustum manually (loadWorld(null) minus the GUI hop). Vanilla's
                // onDisconnect body ran inside this session's packet pump — record
                // the cleared fields so a later remount shows the disconnect reason,
                // then restore the user's GUI. The scheduled mountHuman() in
                // BotConnection re-persists the human's world/player.
                s.savedScreen = mc.currentScreen;
                s.world = null;
                s.player = null;
                s.playerController = null;
                Object prev = DISC_SCREEN.get();
                DISC_SCREEN.remove();
                mc.displayGuiScreen((net.minecraft.client.gui.GuiScreen) prev);
                return;
            }
            // Whatever the vanilla body did to the singleton is session
            // state now: world/player cleared, GuiDisconnected on screen.
            // Stash the screen so a later mount shows the reason, then
            // restore the mounted session's screen.
            s.savedScreen = mc.currentScreen;
            s.exitContextNoStatics();
            // loadWorld(null) inside the disconnect freed viewFrustum and
            // stopped chunk-render workers — rebuild RenderGlobal for the
            // mounted session's world so it renders again.
            BotSession mounted = SessionManager.get().mounted();
            if (mounted != null && mc.renderGlobal != null) {
                BotSession.restoreRenderGlobal(mc, mounted.world);
            }
            Object prev = DISC_SCREEN.get();
            DISC_SCREEN.remove();
            mc.displayGuiScreen((net.minecraft.client.gui.GuiScreen) prev);
        } catch (Throwable t) {
            NexusPlugin.LOGGER.error("disconnect ctx pop failed for {}", s.username, t);
        }
    }

    //
    // MythosScript's features are driven by Forge's ClientTickEvent on the
    // *client* thread — which only ever sees the mounted session's world.
    // To give every online bot its own MythosScript tick we re-fire the
    // event inside each session's withContext, but ONLY for handlers we
    // explicitly opt in (re-firing every mod would double-fire Forge's own
    // listeners).
    //
    // The actual pumping lives in SessionTicker — this class keeps the
    // public ASM entry points.

    /** MythosScript registers its per-session tick hooks here via reflection
     *  (it's a different jar — no compile dep). Delegates to SessionTicker. */
    public static void registerTickHook(Runnable r) {
        SessionTicker.registerTickHook(r);
    }

    /** Cheap hooks that run every bot tick-thread iteration regardless of the
     *  session's throttled tick rate — DelayScheduler's wall-clock countdown
     *  lives here so a "2 tick" delay isn't stretched by a 20% rate. */
    public static void registerFastTickHook(Runnable r) {
        SessionTicker.registerFastTickHook(r);
    }

    /**
     * Run all registered per-session ticks for every online session,
     * each inside its own withContext so MythosScript sees that bot's
     * world/player. Called from Minecraft.runTick head injection.
     */
    public static void runSessionTicks() {
        heartbeat();          // tick counter + periodic glGetError sample
        startWatchdog();      // idempotent; spawns the [wd] logger thread
        SessionTicker.run();  // packet pump + pendingMount flush + per-bot ticks
    }

    /**
     * True when this packet is SPacketCombatEvent(ENTITY_DIED) — and a
     * CPacketClientStatus(PERFORM_RESPAWN) has already been sent back on nm.
     * Consumed so NHPC never opens GuiGameOver for this session.
     */
    private static boolean handleAutoRespawn(NetworkManager nm, Packet<?> packet,
                                             BotSession ctx) {
        // Runtime class is jo (obf) not SPacketCombatEvent — match either.
        String cn = packet.getClass().getName();
        if (!(cn.equals("jo") || cn.endsWith("SPacketCombatEvent"))) return false;
        NexusPlugin.LOGGER.info("auto-respawn: saw {} packet on session {}", cn, ctx.username);
        try {
            // In obf env the Event field is 'a', in SRG field_179776_a.
            java.lang.reflect.Field f;
            try { f = packet.getClass().getDeclaredField("field_179776_a"); }
            catch (NoSuchFieldException e) { f = packet.getClass().getDeclaredField("a"); }
            f.setAccessible(true);
            Object ev = f.get(packet);
            // ENTITY_DIED is the 3rd enum constant (a/b/c in obf). Ordinal is stable.
            if (!(ev instanceof Enum) || ((Enum<?>) ev).ordinal() != 2) return false;
            // send PERFORM_RESPAWN on the session's own channel
            Class<?> state = findClass(
                    "net.minecraft.network.play.client.CPacketClientStatus$State", "lb$a");
            // PERFORM_RESPAWN is the first enum constant (a/b/c in obf → ordinal 0).
            Object perf = state.getEnumConstants()[0];
            Class<?> pkt = findClass(
                    "net.minecraft.network.play.client.CPacketClientStatus", "lb");
            Object respawnPkt = pkt.getDeclaredConstructor(state).newInstance(perf);
            @SuppressWarnings("unchecked")
            Packet<net.minecraft.network.INetHandler> p =
                    (Packet<net.minecraft.network.INetHandler>) respawnPkt;
            nm.sendPacket(p);
            NexusPlugin.LOGGER.info("auto-respawn sent for {}", ctx.username);
            return true;
        } catch (Throwable t) {
            NexusPlugin.LOGGER.warn("auto-respawn failed", t);
            return false;
        }
    }

    private static Class<?> findClass(String mcp, String obf) throws ClassNotFoundException {
        try { return Class.forName(mcp); } catch (ClassNotFoundException e) { return Class.forName(obf); }
    }

    /** Called at tail of NHPC.<init>: link handler to the session that owns its NetworkManager. */
    public static void onClientHandlerCreated(Object networkManagerObj, Object handlerObj) {
        boolean ok = networkManagerObj instanceof NetworkManager
                && handlerObj instanceof NetHandlerPlayClient;
        NexusPlugin.LOGGER.info("[nexus] onClientHandlerCreated nm={} handler={} match={}",
                networkManagerObj == null ? "null" : networkManagerObj.getClass().getSimpleName(),
                handlerObj == null ? "null" : handlerObj.getClass().getSimpleName(), ok);
        if (!ok) return;
        SessionManager.get().bindHandler((NetworkManager) networkManagerObj,
                (NetHandlerPlayClient) handlerObj);
    }
}
