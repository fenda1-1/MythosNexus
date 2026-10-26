package com.mythos.nexus;

import com.mythos.nexus.session.BotConnection;
import com.mythos.nexus.session.BotSession;
import com.mythos.nexus.session.SessionManager;
import com.mythos.nexus.ui.NexusScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.input.Keyboard;

@Mod(modid = "mythosnexus", name = "Mythos Nexus", version = "0.1",
     clientSideOnly = true, acceptableRemoteVersions = "*")
public class MythosNexusMod {

    public static final String MODID = "mythosnexus";
    public static KeyBinding KEY_MANAGER;
    public static KeyBinding KEY_MOUNT_NEXT;
    public static KeyBinding KEY_MOUNT_PREV;
    public static KeyBinding KEY_MOUNT_HUMAN;

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        KEY_MANAGER = new KeyBinding("key.mythosnexus.manager",
                Keyboard.KEY_Z, "key.categories.mythosnexus");
        ClientRegistry.registerKeyBinding(KEY_MANAGER);
        KEY_MOUNT_NEXT = new KeyBinding("key.mythosnexus.mountNext",
                Keyboard.KEY_NONE, "key.categories.mythosnexus");
        KEY_MOUNT_PREV = new KeyBinding("key.mythosnexus.mountPrev",
                Keyboard.KEY_NONE, "key.categories.mythosnexus");
        KEY_MOUNT_HUMAN = new KeyBinding("key.mythosnexus.mountHuman",
                Keyboard.KEY_NONE, "key.categories.mythosnexus");
        ClientRegistry.registerKeyBinding(KEY_MOUNT_NEXT);
        ClientRegistry.registerKeyBinding(KEY_MOUNT_PREV);
        ClientRegistry.registerKeyBinding(KEY_MOUNT_HUMAN);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(com.mythos.nexus.session.BotStatusOverlay.get());
        // Automation hook: -Dnexus.auto=host:port,user1,user2,...
        // When the title screen first appears, connect each listed account.
        String auto = System.getProperty("nexus.auto");
        if (auto != null && !auto.isEmpty()) autoConnectSpec = auto;
        // -Dnexus.human=host:port — headless: the client itself joins this
        // server, making its real session the human pseudo-session. Without it
        // a headless run sits on the title screen with mc.world==null forever.
        String human = System.getProperty("nexus.human");
        if (human != null && !human.isEmpty()) humanJoinSpec = human;
        // Self-test: -Dnexus.test mounts each online session in turn and
        // dumps field hashes so mount isolation can be verified headlessly.
        // Set to "true" (or "mount") to enable; defaults to off.
        String testProp = System.getProperty("nexus.test", "false");
        testMode = testProp.equalsIgnoreCase("true")
                || testProp.equalsIgnoreCase("mount");
        // UI smoke test: -Dnexus.openUi opens the manager screen once the
        // title screen appears, so it can be screenshotted headlessly.
        openUi = Boolean.parseBoolean(System.getProperty("nexus.openUi", "false"));
        // Session-set restore: if -Dnexus.auto didn't specify the roster,
        // rebuild every persisted account (not just the ones that happened to
        // be ONLINE at the last shutdown) and let the join queue bring back
        // the ones that were online / have auto-reconnect on.
        if (autoConnectSpec == null || autoConnectSpec.isEmpty()) {
            restorePending = !com.mythos.nexus.core.NexusConfig.accounts().isEmpty()
                    || !com.mythos.nexus.core.NexusConfig.lastOnline().isEmpty();
            // Capture the "was online last time" set NOW: the per-tick online
            // snapshot rewrites lastOnline as soon as it differs from its hash
            // (and it starts at -1), so by the time the restore tick runs it
            // may already have been replaced by an empty list.
            restoreOnlineSnapshot = new java.util.ArrayList<>(
                    com.mythos.nexus.core.NexusConfig.lastOnline());
        }
    }

    private boolean restorePending;
    private boolean restoreFired;
    private java.util.List<String> restoreOnlineSnapshot = new java.util.ArrayList<>();

    private boolean openUi;
    private boolean openUiFired;

    private String autoConnectSpec;
    private boolean autoConnectFired;
    private String humanJoinSpec;
    private boolean humanJoinFired;
    private boolean testMode;
    private int testStep = -1;
    private long testNextNanos;


    private static void restoreRoster(java.util.List<String> onlineSnapshot) {
        java.util.List<com.mythos.nexus.core.NexusConfig.Account> accts =
                com.mythos.nexus.core.NexusConfig.accounts();
        java.util.Set<String> wasOnline = new java.util.HashSet<>();
        for (String k : onlineSnapshot)
            wasOnline.add(k.toLowerCase(java.util.Locale.ROOT));

        java.util.List<BotSession> toJoin = new java.util.ArrayList<>();
        int restored = 0;
        for (com.mythos.nexus.core.NexusConfig.Account a : accts) {
            if (SessionManager.get().hasSessionNamed(a.username)) continue;
            BotSession s = SessionManager.get().createSession(a.username, a.host, a.port);
            restored++;
            boolean online = wasOnline.contains(a.key().toLowerCase(java.util.Locale.ROOT));
            boolean autoOn = com.mythos.nexus.core.NexusConfig
                    .bindingFor(a.key()).autoReconnect;
            if (online || autoOn) toJoin.add(s);
        }

        // Legacy migration: configs written before the roster existed only have
        // lastOnline. Re-add those too so an upgrade doesn't drop the bots that
        // were running. (createSession writes them into the roster.)
        for (String key : onlineSnapshot) {
            int at = key.indexOf('@');
            if (at <= 0) continue;
            String user = key.substring(0, at);
            String hp = key.substring(at + 1);
            int ci = hp.lastIndexOf(':');
            if (ci <= 0) continue;
            String host = hp.substring(0, ci);
            if (host.isEmpty() || "null".equalsIgnoreCase(host)) continue;
            int port = 25565;
            try { port = Integer.parseInt(hp.substring(ci + 1)); }
            catch (NumberFormatException ignored) { continue; }
            if (SessionManager.get().hasSessionNamed(user)) continue;
            BotSession s = SessionManager.get().createSession(user, host, port);
            restored++;
            toJoin.add(s);
        }

        com.mythos.nexus.core.NexusPlugin.LOGGER.info(
                "nexus.restore: {} 个账号已恢复，{} 个进入加入队列", restored, toJoin.size());
        if (!toJoin.isEmpty())
            com.mythos.nexus.session.ReconnectManager.get().enqueueAll(toJoin, "启动恢复");
    }

    @SubscribeEvent
    public void onGuiOpen(net.minecraftforge.client.event.GuiOpenEvent ev) {
        if (ev.getGui() instanceof net.minecraft.client.gui.GuiMultiplayer
                && com.mythos.nexus.core.NexusHooks.consumeQuitSuppress()
                && net.minecraft.client.Minecraft.getMinecraft().world != null) {
            ev.setGui(null);
            return;
        }
        BotSession off = SessionManager.get().offlineMounted();
        if (off == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (ev.getGui() instanceof net.minecraft.client.multiplayer.GuiConnecting) {
            redirectJoinToBot(off, (net.minecraft.client.multiplayer.GuiConnecting) ev.getGui(), ev);
        } else if (ev.getGui() instanceof net.minecraft.client.gui.GuiWorldSelection) {
            // Singleplayer always runs as the real account.
            mc.addScheduledTask(SessionManager.get()::mountHuman);
        }
    }

    /** Joining a server while an offline bot is the view: log that bot in instead of the human. */
    private static void redirectJoinToBot(BotSession bot, net.minecraft.client.multiplayer.GuiConnecting gc,
                                          net.minecraftforge.client.event.GuiOpenEvent ev) {
        Minecraft mc = Minecraft.getMinecraft();
        net.minecraft.client.multiplayer.ServerData sd = mc.getCurrentServerData();
        try {
            net.minecraftforge.fml.relauncher.ReflectionHelper.setPrivateValue(
                    net.minecraft.client.multiplayer.GuiConnecting.class, gc, true, "cancel", "field_146373_h");
        } catch (Throwable t) {
            com.mythos.nexus.core.NexusPlugin.LOGGER.warn("GuiConnecting cancel failed", t);
        }
        // Vanilla's connect thread is already running as the human; close it once it exists.
        Thread killer = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                try {
                    net.minecraft.network.NetworkManager nm =
                            net.minecraftforge.fml.relauncher.ReflectionHelper.getPrivateValue(
                                    net.minecraft.client.multiplayer.GuiConnecting.class, gc,
                                    "networkManager", "field_146371_g");
                    if (nm != null) {
                        nm.closeChannel(new net.minecraft.util.text.TextComponentString("Nexus: redirected to bot"));
                        return;
                    }
                    Thread.sleep(100);
                } catch (Throwable t) {
                    return;
                }
            }
        }, "nexus-cancel-join");
        killer.setDaemon(true);
        killer.start();
        net.minecraft.client.gui.GuiScreen parent = null;
        try {
            parent = net.minecraftforge.fml.relauncher.ReflectionHelper.getPrivateValue(
                    net.minecraft.client.multiplayer.GuiConnecting.class, gc,
                    "previousGuiScreen", "field_146374_i");
        } catch (Throwable ignored) {}
        if (sd != null && sd.serverIP != null) {
            net.minecraft.client.multiplayer.ServerAddress addr =
                    net.minecraft.client.multiplayer.ServerAddress.fromString(sd.serverIP);
            bot.serverHost = addr.getIP();
            bot.serverPort = addr.getPort();
        }
        mc.setServerData(null);
        com.mythos.nexus.core.NexusPlugin.LOGGER.info("join redirected to offline-mounted bot {} -> {}:{}",
                bot.username, bot.serverHost, bot.serverPort);
        mc.addScheduledTask(() -> BotConnection.connect(bot));
        ev.setGui(parent != null ? parent : new net.minecraft.client.gui.GuiMainMenu());
    }

    /** Poll for the title screen each tick to run -Dnexus.auto once. */
    @SubscribeEvent
    public void onTick(net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent ev) {
        if (restorePending && !restoreFired) {
            try { restoreTick(); }
            catch (Throwable t) { com.mythos.nexus.core.NexusPlugin.LOGGER.error("[nexus.restore]", t); }
        }
        if (testMode) {
            try { testTick(); }
            catch (Throwable t) { com.mythos.nexus.core.NexusPlugin.LOGGER.error("[nexus.test]", t); }
        }
        try { openUiTick(); }
        catch (Throwable t) { com.mythos.nexus.core.NexusPlugin.LOGGER.error("[nexus.openUi]", t); }
        try { humanJoinTick(); }
        catch (Throwable t) { com.mythos.nexus.core.NexusPlugin.LOGGER.error("[nexus.human]", t); }
        if (autoConnectSpec == null || autoConnectFired) return;
        Minecraft mc = Minecraft.getMinecraft();
        // Fire once the client has booted — either sitting on a screen with no
        // world, or already in a world (--server auto-join skips the title
        // screen entirely so world!=null is the only signal that fires there).
        if ((mc.world == null && mc.currentScreen != null) || mc.world != null) {
            autoConnectFired = true;
            com.mythos.nexus.core.NexusPlugin.LOGGER.info("nexus.auto firing: {}", autoConnectSpec);
            String spec = autoConnectSpec;
            autoConnectSpec = null;
            // spec: host:port,user1,user2,...   connect each
            // launchers may leave shell quotes on the JVM arg - strip them
            String cleaned = spec.replaceAll("[\"']", "");
            String[] parts = cleaned.split(",");
            String host = parts[0].trim();
            for (int i = 1; i < parts.length; i++) {
                String user = parts[i].trim().replaceAll("^[\"']+|[\"']+$", "");
                if (user.isEmpty()) continue;
                int port = 25565;
                String h = host;
                int ci = h.lastIndexOf(':');
                if (ci > 0) {
                    try { port = Integer.parseInt(h.substring(ci + 1)); }
                    catch (NumberFormatException ignored) {}
                    h = h.substring(0, ci);
                }
                BotSession s = SessionManager.get().createSession(user, h, port);
                // CLI-launched bots run unattended — enable auto-reconnect so a
                // keepalive timeout / transient drop comes back instead of
                // silently sitting DISCONNECTED.
                com.mythos.nexus.core.NexusConfig.setAutoReconnect(
                        com.mythos.nexus.ui.NexusGeometry.sessionKey(s), true);
                com.mythos.nexus.core.NexusPlugin.LOGGER.info("nexus.auto queueing {} to {}:{}", user, h, port);

                com.mythos.nexus.session.ReconnectManager.get()
                        .enqueue(s, 0, "启动(auto)");
            }
        }
    }

    /** Fire the persisted roster restore once the client is up. */
    private void restoreTick() {
        if (restoreFired) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null && mc.currentScreen == null) return;
        restoreFired = true;
        restorePending = false;
        restoreRoster(restoreOnlineSnapshot);
    }

    /** -Dnexus.openUi: open the manager once the title screen is up. */
    private void openUiTick() {
        if (!openUi || openUiFired) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (!(mc.currentScreen instanceof net.minecraft.client.gui.GuiMainMenu) || mc.world != null) return;
        openUiFired = true;
        com.mythos.nexus.core.NexusPlugin.LOGGER.info("nexus.openUi firing");
        mc.displayGuiScreen(new NexusScreen());
    }

    /** -Dnexus.human=host:port — client joins that server so its real session
     *  (the human) is online headless. GuiConnecting does the whole vanilla
     *  join; the screen never draws under -lwjgl. */
    private void humanJoinTick() {
        if (humanJoinSpec == null || humanJoinFired) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (!(mc.currentScreen instanceof net.minecraft.client.gui.GuiMainMenu)
                || mc.world != null) return;
        humanJoinFired = true;
        String spec = humanJoinSpec.replaceAll("[\"']", "");
        humanJoinSpec = null;
        String host = spec; int port = 25565;
        int ci = host.lastIndexOf(':');
        if (ci > 0) {
            try { port = Integer.parseInt(host.substring(ci + 1)); }
            catch (NumberFormatException ignored) {}
            host = host.substring(0, ci);
        }
        com.mythos.nexus.core.NexusPlugin.LOGGER.info("nexus.human joining {}:{}", host, port);
        // Warm the shared netty event loop on the client thread first —
        // GuiConnecting's Server Connector thread races the lazy
        // CLIENT_NIO_EVENTLOOP init and can NPE at AbstractBootstrap.group
        // (the same race BotConnection serializes around for bots).
        try { net.minecraft.network.NetworkManager.CLIENT_NIO_EVENTLOOP.getValue(); }
        catch (Throwable ignored) {}
        mc.displayGuiScreen(new net.minecraft.client.multiplayer.GuiConnecting(
                mc.currentScreen, mc, host, port));
    }

    /**
     * -Dnexus.test: wait for every session to be ONLINE, then cycle mounts
     * (each bot 4s, human last) logging the live mc field identities at
     * each step. Lets headless runs verify mount isolation end-to-end.
     */
    private void testTick() {
        long now = System.nanoTime();
        if (now < testNextNanos) return;
        java.util.List<BotSession> list = SessionManager.get().sessions();
        if (list.isEmpty()) return;
        if (testStep < 0) {
            // debug: log once why we're not starting
            boolean allOnline = true;
            for (BotSession s : list) {
                if (s.state != BotSession.State.ONLINE) allOnline = false;
            }
            if (!allOnline) {
                StringBuilder sb = new StringBuilder();
                for (BotSession s : list) sb.append(s.username).append('=').append(s.state).append(' ');
                com.mythos.nexus.core.NexusPlugin.LOGGER.info("[nexus.test] waiting: {}", sb);
                testNextNanos = now + 2_000_000_000L;
                return;
            }
        }

        if (testStep < 0) {
            boolean allOnline = true;
            for (BotSession s : list) {
                if (s.state != BotSession.State.ONLINE) allOnline = false;
            }
            if (!allOnline) return;
            testStep = 0;
        }
        SessionManager mgr = SessionManager.get();
        Minecraft mc = Minecraft.getMinecraft();
        if (testStep < list.size()) {
            BotSession s = list.get(testStep);
            logMountState("mount->" + s.username, s);
            mgr.mount(s);
            logMcState("after");
            testNextNanos = now + 4_000_000_000L;
            testStep++;
        } else if (testStep == list.size()) {
            logMountState("mount->human", mgr.humanSession());
            mgr.mountHuman();
            logMcState("after");
            testNextNanos = now + 4_000_000_000L;
            testStep++;
        } else {
            testMode = false; // done
            com.mythos.nexus.core.NexusPlugin.LOGGER.info("[nexus.test] done");
        }
    }

    private static void logMountState(String tag, BotSession s) {
        com.mythos.nexus.core.NexusPlugin.LOGGER.info(
            "[nexus.test] {} session#{} w={} p={} c={}", tag, s.id,
            id(s.world), id(s.player), id(s.playerController));
    }
    private static void logMcState(String tag) {
        Minecraft mc = Minecraft.getMinecraft();
        com.mythos.nexus.core.NexusPlugin.LOGGER.info(
            "[nexus.test] {} mc.world={} mc.player={} screen={}",
            tag, id(mc.world), id(mc.player),
            mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName());
    }
    private static String id(Object o) {
        return o == null ? "null" : Integer.toHexString(System.identityHashCode(o));
    }

    // Edge-detect on raw keyboard state. Forge's KeyInputEvent is gated by
    // "no GUI open" (GuiScreen.handleKeyboardInput consumes the key first),
    // so it never fires on the title screen or inside other GUIs. Polling
    // isKeyDown each client tick works in every state.
    private boolean prevCombo = false;

    @SubscribeEvent
    public void onClientTick(net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent ev) {
        if (ev.phase != net.minecraftforge.fml.common.gameevent.TickEvent.Phase.END) return;

        // When runTickKeyboard is guarded off (title screen — no world/player),
        // Keyboard.next() is never pumped so KeyInputEvent never fires and
        // Forge keybinds (F, etc.) are dead. Pump it here instead — but only
        // when the guard would have skipped runTickKeyboard (world==null),
        // otherwise we'd double-pump and eat half the keypresses.
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null && mc.currentScreen != null) {
            while (Keyboard.next()) {
                int key = Keyboard.getEventKey();
                boolean pressed = Keyboard.getEventKeyState();
                if (key != 0) {
                    // Keep KeyBinding's pressed table in sync so Forge
                    // keybinds (F menu, etc.) still fire on the title screen.
                    KeyBinding.setKeyBindState(key, pressed);
                }
                MinecraftForge.EVENT_BUS.post(new InputEvent.KeyInputEvent());
            }
        }

        // Poll the bound keycode directly — KeyBinding.isKeyDown() only goes
        // true when the pressed table is updated inside runTickKeyboard, which
        // tickGuard skips entirely on the title screen. Raw isKeyDown on the
        // configured code works in every UI state.
        int bound = KEY_MANAGER != null ? KEY_MANAGER.getKeyCode() : Keyboard.KEY_NONE;
        // isKeyDown indexes into a 256-entry DirectByteBuffer — negative
        // (unbound / mouse buttons) or >=256 keycodes crash with
        // IndexOutOfBoundsException. Guard the range first.
        boolean combo = bound > Keyboard.KEY_NONE && bound < 256
                && Keyboard.isKeyDown(bound);
        if (combo && !prevCombo) toggleManager();
        prevCombo = combo;

        // Mount cycle hotkeys — same raw-isKeyDown pattern.
        pollMountKey(KEY_MOUNT_NEXT, 1);
        pollMountKey(KEY_MOUNT_PREV, -1);
        pollMountHuman(KEY_MOUNT_HUMAN);
    }

    // Edge-detect state for the three mount keys (indexed by slot 0=next,1=prev,2=human).
    private final boolean[] prevMountKey = new boolean[3];

    private void pollMountKey(KeyBinding kb, int direction) {
        int slot = direction > 0 ? 0 : 1;
        int code = kb != null ? kb.getKeyCode() : Keyboard.KEY_NONE;
        boolean down = code > Keyboard.KEY_NONE && code < 256
                && Keyboard.isKeyDown(code);
        if (down && !prevMountKey[slot]) mountCycle(direction);
        prevMountKey[slot] = down;
    }

    private void pollMountHuman(KeyBinding kb) {
        int code = kb != null ? kb.getKeyCode() : Keyboard.KEY_NONE;
        boolean down = code > Keyboard.KEY_NONE && code < 256
                && Keyboard.isKeyDown(code);
        if (down && !prevMountKey[2]) SessionManager.get().mountHuman();
        prevMountKey[2] = down;
    }

    /** Cycle mount to the next (+1) or previous (-1) online session. */
    private static void mountCycle(int direction) {
        SessionManager mgr = SessionManager.get();
        java.util.List<BotSession> online = new java.util.ArrayList<>();
        for (BotSession s : mgr.sessions()) {
            if (s.state == BotSession.State.ONLINE
                    && s.world != null && s.player != null)
                online.add(s);
        }
        if (online.isEmpty()) return;
        BotSession cur = mgr.mounted();
        int idx = cur == null ? -1 : online.indexOf(cur);
        int next = idx < 0 ? 0 : (idx + direction + online.size()) % online.size();
        mgr.mount(online.get(next));
    }

    private void toggleManager() { openManager(); }

    /** Open/close the account manager — callable from MCP or keybind. */
    public static void openManager() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen instanceof NexusScreen) {
            mc.displayGuiScreen(null);      // hide → back to game/title
        } else {
            mc.displayGuiScreen(new NexusScreen());
        }
    }

    // convenience used by UI buttons
    public static void addAndConnectBot(String username, String server) {
        String host = server;
        int port = 25565;
        int i = server.lastIndexOf(':');
        if (i > 0) {
            host = server.substring(0, i);
            try { port = Integer.parseInt(server.substring(i + 1)); }
            catch (NumberFormatException ignored) {}
        }
        BotSession s = SessionManager.get().createSession(username, host, port);
        BotConnection.connect(s);
    }

    public static void switchTo(BotSession s) {
        SessionManager.get().mount(s);   // persists fields; no pop
    }


    /** Mount a session by username (case-insensitive). "human"/"<you>" → human.
     *  Returns the mounted session's username, or null if the name is unknown.
     *  Must run on the client thread — callers on netty/http threads should
     *  go through mountByNameAsync. */
    public static String mountByName(String name) {
        SessionManager mgr = SessionManager.get();
        if (name == null) return null;
        if (name.equalsIgnoreCase("human") || name.equals("<you>")
                || name.equalsIgnoreCase(mgr.humanSession().username)) {
            mgr.mountHuman();
            return mgr.humanSession().username;
        }
        for (BotSession s : mgr.sessions()) {
            if (s.username.equalsIgnoreCase(name)) { mgr.mount(s); return s.username; }
        }
        return null;
    }

    /** Thread-safe mount: schedule mountByName on the client thread. */
    public static void mountByNameAsync(final String name) {
        net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(new Runnable() {
            @Override public void run() { mountByName(name); }
        });
    }
}
