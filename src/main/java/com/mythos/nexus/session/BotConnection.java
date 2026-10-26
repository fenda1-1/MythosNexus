package com.mythos.nexus.session;

import com.mojang.authlib.GameProfile;
import com.mythos.nexus.core.NexusConfig;
import com.mythos.nexus.core.NexusPlugin;
import com.mythos.nexus.session.LiveViewportRenderer;
import com.mythos.nexus.session.MythosStateSwap;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerLoginClient;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.handshake.client.C00Handshake;
import net.minecraft.network.login.client.CPacketLoginStart;
import net.minecraft.util.ITickable;
import net.minecraft.util.LazyLoadBase;
import net.minecraft.util.text.ITextComponent;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.UUID;

/**
 * Connect a bot: vanilla TCP + LOGIN pipeline identical to GuiConnecting,
 * but the result is registered in SessionManager instead of mounted as the
 * human player. Per-session proxy (SOCKS5/HTTP) via a ProxyHandler inserted
 * first in the pipeline — the bot's traffic exits through that proxy.
 *
 * The FML handshake is handled transparently by NetworkDispatcher, which is
 * created inside NetHandlerLoginClient's constructor — i.e. we get Forge
 * channel negotiation for free. When the server sends LoginSuccess, vanilla
 * NetHandlerLoginClient switches the NetworkManager to PLAY state and
 * constructs a NetHandlerPlayClient; our NHPC ctor hook binds it to the
 * owning BotSession.
 */
public final class BotConnection {

    private BotConnection() {}

    /** Begin async connect for a session. Offline-mode (no encryption/auth). */
    public static void connect(BotSession session) {
        if (session.state == BotSession.State.CONNECTING) return;
        SessionManager mgr = SessionManager.get();
        if (mgr.offlineMounted() == session) {
            // A foreground session gets no ticker; log in from the background
            // and switch the view back once the world arrives.
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (!mc.isCallingFromMinecraftThread()) {
                mc.addScheduledTask(() -> connect(session));
                return;
            }
            mgr.mountHuman();
            mgr.mountWhenOnline(session);
        }
        session.state = BotSession.State.CONNECTING;
        session.closed = false;
        session.userClosed = false;
        session.lastError = null;
        // Drop leftovers of the previous connection before the new ticker
        // starts — ticking a stale controller drives the old NetworkManager.
        if (!SessionManager.get().isForeground(session)) {
            session.world = null;
            session.player = null;
            session.playerController = null;
            SessionManager.get().resetHandler(session);
        }
        session.logEvent("connecting -> " + session.serverHost + ":" + session.serverPort
                + (session.proxyIndex >= 0 ? " via proxy#" + session.proxyIndex : ""));
        // Start the per-session tick worker NOW, before the world exists.
        // The bot's SPacketJoinGame (which builds its WorldClient) is routed to
        // the per-session queue and can only be dispatched by this thread's
        // pumpFor — deferring the start until world!=null deadlocks the login.
        session.ensureTicker();

        final String host = session.serverHost;
        final int port = session.serverPort;
        final String username = session.username;

        // Serialise: createNetworkManagerAndConnect lazily inits
        // NetworkManager.CLIENT_NIO_EVENTLOOP inside Bootstrap.group() — two
        // concurrent calls race and the loser gets NPE("group"). One lock is
        // cheaper than a custom bootstrap.
        new Thread(new Runnable() {
            @Override public void run() {
                synchronized (BotConnection.class) {
                    doConnect(session, host, port, username);
                }
            }
        }, "nexus-connect-" + username).start();
    }

    /** Lazily-init'd NIO event loop — same pattern as NetworkManager.CLIENT_NIO_EVENTLOOP. */
    static final LazyLoadBase<EventLoopGroup> EVENTLOOP =
            new LazyLoadBase<EventLoopGroup>() {
                @Override protected EventLoopGroup load() {
                    return new io.netty.channel.nio.NioEventLoopGroup(0,
                            new com.google.common.util.concurrent.ThreadFactoryBuilder()
                                    .setNameFormat("Nexus Client IO #%d")
                                    .setDaemon(true).build());
                }
            };

    private static void doConnect(BotSession session, String host, int port, String username) {
        try {
            NexusPlugin.LOGGER.info("doConnect {} -> {}:{} (resolve)", username, host, port);
            InetAddress addr = InetAddress.getByName(host);
            NexusPlugin.LOGGER.info("doConnect {} resolved, creating NM", username);

            // Try the bound proxy first, then every other proxy, then
            // direct — a refused proxy shouldn't strand a bot that could
            // reach the server another way.
            java.util.List<Integer> order = new java.util.ArrayList<>();
            order.add(session.proxyIndex);
            for (int i = 0; i < NexusConfig.proxies().size(); i++)
                if (i != session.proxyIndex) order.add(i);
            if (session.proxyIndex != -1) order.add(-1); // direct fallback last
            NetworkManager nm = null;
            Throwable lastErr = null;
            for (int pi : order) {
                try {
                    NexusConfig.Proxy p = NexusConfig.proxyAt(pi);
                    nm = (p != null && p.valid())
                            ? createViaProxy(host, port, p)
                            : NetworkManager.createNetworkManagerAndConnect(addr, port, false);
                    if (pi != session.proxyIndex)
                        session.logEvent("INFO", "proxy " + pi + " failover from " + session.proxyIndex);
                    break;
                } catch (Throwable t) { lastErr = t; nm = null; }
            }
            if (nm == null) throw lastErr != null ? lastErr : new IllegalStateException("no proxy reachable");
            // connect() completes before NetworkManager.channelActive sets its channel
            // (a proxy handler widens the gap); packets sent while channel()==null are
            // queued and nothing ticks this NM to flush them -> login never reaches the server.
            long activeDeadline = System.currentTimeMillis() + 10_000L;
            while (nm.channel() == null && System.currentTimeMillis() < activeDeadline)
                Thread.sleep(5);
            if (nm.channel() == null) throw new IllegalStateException("channel never became active");
            NexusPlugin.LOGGER.info("doConnect {} NM created, pipeline={}",
                    username, nm.channel() != null ? nm.channel().pipeline().names() : "null");
            // Byte counter — count bytes at the Netty level so the UI/MCP
            // can show real bandwidth, not packet-count estimates.
            if (nm.channel() != null) {
                nm.channel().pipeline().addFirst("nexus_bytes",
                        new io.netty.channel.ChannelDuplexHandler() {
                            @Override public void channelRead(
                                    io.netty.channel.ChannelHandlerContext ctx2, Object msg) throws Exception {
                                if (msg instanceof io.netty.buffer.ByteBuf)
                                    session.bytesReceived += ((io.netty.buffer.ByteBuf) msg).readableBytes();
                                super.channelRead(ctx2, msg);
                            }
                            @Override public void write(
                                    io.netty.channel.ChannelHandlerContext ctx2, Object msg,
                                    io.netty.channel.ChannelPromise promise) throws Exception {
                                if (msg instanceof io.netty.buffer.ByteBuf)
                                    session.bytesSent += ((io.netty.buffer.ByteBuf) msg).readableBytes();
                                super.write(ctx2, msg, promise);
                            }
                        });
            }
            nm.setNetHandler(new BotLoginHandler(session, nm));
            // A reconnect carries the previous session's NetHandlerPlayClient
            // in session.handler. If it stays non-null, onPacketRead treats
            // LOGIN packets as PLAY and dispatches SPacketEnableCompression to
            // the stale handler — and because the packet is consumed on the
            // pump thread, the vanilla side-effect (installing the compression
            // codec into the pipeline) never runs, so every subsequent server
            // frame is mis-framed ("Bad packet id"/"bytes extra") and the bot
            // falls into a disconnect→reconnect loop.
            SessionManager.get().resetHandler(session);
            SessionManager.get().associate(nm, session);
            NexusPlugin.LOGGER.info("doConnect {} associated, pipeline={}",
                    username, nm.channel() != null ? nm.channel().pipeline().names() : "null");
            nm.sendPacket(new C00Handshake(host, port,
                    EnumConnectionState.LOGIN, true));
            nm.sendPacket(new CPacketLoginStart(
                    new GameProfile(UUID.nameUUIDFromBytes(
                            ("OfflinePlayer:" + username).getBytes("UTF-8")),
                            username)));
        } catch (Throwable t) {
            session.state = BotSession.State.ERROR;
            session.lastError = String.valueOf(t);
            session.logEvent("ERROR", "connect failed: " + t);
            NexusPlugin.LOGGER.error("connect {} failed", username, t);
            // Initial-connect failure is still eligible for auto-reconnect
            // — a refused proxy or a downed server shouldn't permanently
            // strand a bot the user wants online.
            maybeAutoReconnect(session);
        }
    }

    /**
     * Same pipeline NetworkManager.createNetworkManagerAndConnect builds,
     * but with a proxy handler first so the bot's TCP exits through the proxy.
     * Reimplemented because the vanilla method doesn't accept a proxy.
     */
    private static NetworkManager createViaProxy(String host, int port,
                                                final NexusConfig.Proxy px) {
        final NetworkManager nm = new NetworkManager(
                net.minecraft.network.EnumPacketDirection.CLIENTBOUND);
        Class<? extends Channel> oclass = NioSocketChannel.class;
        new Bootstrap()
                .group(EVENTLOOP.getValue())
                .handler(new ChannelInitializer<Channel>() {
                    @Override protected void initChannel(Channel ch) throws Exception {
                        try {
                            ch.config().setOption(ChannelOption.TCP_NODELAY, true);
                        } catch (ChannelException ignored) {}
                        // proxy handshake first — after it completes, the
                        // rest of the pipeline talks to the real MC server
                        InetSocketAddress paddr = new InetSocketAddress(px.host, px.port);
                        if ("http".equalsIgnoreCase(px.type)) {
                            if (px.user != null && !px.user.isEmpty())
                                ch.pipeline().addLast("proxy",
                                        new HttpProxyHandler(paddr, px.user, px.pass));
                            else
                                ch.pipeline().addLast("proxy", new HttpProxyHandler(paddr));
                        } else {
                            if (px.user != null && !px.user.isEmpty())
                                ch.pipeline().addLast("proxy",
                                        new Socks5ProxyHandler(paddr, px.user, px.pass));
                            else
                                ch.pipeline().addLast("proxy", new Socks5ProxyHandler(paddr));
                        }
                        ch.pipeline().addLast("timeout",
                                new ReadTimeoutHandler(30));
                        // vanilla's splitter/prepender/codec/handler chain
                        ch.pipeline().addLast("splitter",
                                new net.minecraft.network.NettyVarint21FrameDecoder());
                        ch.pipeline().addLast("prepender",
                                new net.minecraft.network.NettyVarint21FrameEncoder());
                        ch.pipeline().addLast("decoder",
                                new net.minecraft.network.NettyPacketDecoder(
                                        net.minecraft.network.EnumPacketDirection.CLIENTBOUND));
                        ch.pipeline().addLast("encoder",
                                new net.minecraft.network.NettyPacketEncoder(
                                        net.minecraft.network.EnumPacketDirection.SERVERBOUND));
                        ch.pipeline().addLast("packet_handler", nm);
                    }
                })
                .channel(oclass)
                // Unresolved target + no-op resolver: the proxy handler then sends
                // the hostname in CONNECT. Providers like Bright Data reject
                // raw-IP targets on non-standard ports (policy_20021).
                .resolver(io.netty.resolver.NoopAddressResolverGroup.INSTANCE)
                .connect(InetSocketAddress.createUnresolved(host, port)).syncUninterruptibly();
        return nm;
    }

    /**
     * Minimal login handler — same shape as vanilla NetHandlerLoginClient
     * minus GUI references. FML inserts NetworkDispatcher during ctor, so the
     * Forge handshake runs exactly as it does for the human player.
     */
    private static class BotLoginHandler extends NetHandlerLoginClient {
        private final BotSession session;

        BotLoginHandler(BotSession session, NetworkManager nm) {
            // 1.12.2 parent signature: (NetworkManager, Minecraft, GuiScreen)
            super(nm, com.mythos.nexus.core.NexusHooks.resolveMinecraft(), null);
            this.session = session;
        }

        // The vanilla class handles: EncryptionRequest (skipped in offline),
        // LoginSuccess -> setConnectionState(PLAY) + new NetHandlerPlayClient
        // -> our NexusHooks.onClientHandlerCreated binds it to this session.

        @Override
        public void onDisconnect(ITextComponent reason) {
            session.state = BotSession.State.DISCONNECTED;
            session.closed = true;
            session.lastError = reason == null ? "disconnected" : reason.getUnformattedText();
            session.logEvent("WARN", "disconnected: " + session.lastError);
            // Do not leave a disconnected player/world mountable. Packet
            // handlers may still be queued when Netty reports the close.
            session.world = null;
            session.player = null;
            session.playerController = null;
            SessionManager.get().resetHandler(session);
            session.releaseReferences();
            LiveViewportRenderer.dispose(session);
            MythosStateSwap.clearSession(session);
            // If the old session held any state swap slots referencing it,
            // the stash is gone — future mounts restore defaults instead.
            // The stale session object itself is still in sessions[] until
            // removeSession runs; that's correct — the user sees it as
            // DISCONNECTED and can remove it, or auto-reconnect replaces it.
            com.mythos.nexus.core.NexusHooks.resolveMinecraft().addScheduledTask(new Runnable() {
                @Override public void run() {
                    if (SessionManager.get().mounted() == session)
                        SessionManager.get().mountHuman();
                    maybeAutoReconnect(session);
                }
            });
        }
    }

    /**
     * Channel-level close: fired from Netty's channelInactive for ANY drop
     * (kick, timeout, socket error). Bots never tick their NetworkManager, so
     * NetHandlerPlayClient.onDisconnect may never run — without this the
     * session stays ONLINE with a dead world/player forever (stale UI, and
     * mounting it tears the shared RenderGlobal mid-frame).
     * Idempotent: onDisconnect may also run and set the same state.
     */
    public static void onChannelClosed(final BotSession session) {
        if (session == null || session.closed) return;
        session.closed = true;
        if (session.state == BotSession.State.ONLINE
                || session.state == BotSession.State.CONNECTING) {
            session.state = BotSession.State.DISCONNECTED;
        }
        // Capture the transport reason so the UI/log show the real kick cause.
        if (session.lastError == null) {
            String reason = null;
            try {
                if (session.connection != null
                        && session.connection.getExitMessage() != null) {
                    reason = session.connection.getExitMessage().getUnformattedText();
                }
            } catch (Throwable ignored) {}
            session.lastError = reason == null ? "connection closed" : reason;
        }
        session.logEvent("WARN", "channel closed: " + session.lastError);
        session.world = null;
        session.player = null;
        session.playerController = null;
        SessionManager.get().resetHandler(session);
        LiveViewportRenderer.dispose(session);
        MythosStateSwap.clearSession(session);
        com.mythos.nexus.core.NexusHooks.resolveMinecraft().addScheduledTask(new Runnable() {
            @Override public void run() {
                if (SessionManager.get().mounted() == session)
                    SessionManager.get().mountHuman();
                maybeAutoReconnect(session);
            }
        });
    }


    private static void maybeAutoReconnect(final BotSession session) {
        if (session.userClosed) return;
        String key = session.username + "@" + session.serverHost + ":" + session.serverPort;
        if (!NexusConfig.bindingFor(key).autoReconnect) return;
        if (session.state == BotSession.State.ONLINE
                || session.state == BotSession.State.CONNECTING) return;
        int maxAtt = NexusConfig.reconnectMaxAttempts(session);
        if (maxAtt > 0 && session.reconnectAttempts >= maxAtt) {
            session.logEvent("WARN", "auto-reconnect 已达上限 " + maxAtt + " 次，停止");
            return;
        }
        int delay = NexusConfig.reconnectDelayMs(session);
        session.reconnectAttempts++;
        session.logEvent("WARN", "auto-reconnect #" + session.reconnectAttempts
                + (maxAtt > 0 ? "/" + maxAtt : "") + " 排队 " + delay + "ms");
        ReconnectManager.get().enqueue(session, delay, "掉线重连");
    }
}
