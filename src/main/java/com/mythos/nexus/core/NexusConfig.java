package com.mythos.nexus.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime-persisted Nexus config: proxy pool + per-account bindings
 * (group name, proxy index). One flat JSON file at config/mythosnexus.json,
 * loaded lazily and saved on every mutation.
 *
 * Account key = "username@host:port" — survives restarts for offline-mode
 * bots whose names are stable.
 */
public final class NexusConfig {

    private NexusConfig() {}


    public static class Proxy {
        public String type = "socks5";
        public String host = "";
        public int port = 1080;
        public String user = "";
        public String pass = "";

        public String spec() { return type + "://" + host + ":" + port; }
        public boolean valid() { return host != null && !host.isEmpty() && port > 0; }
    }

    public static class Binding {
        public String group = "默认";
        public int proxy = -1;
        public String note = "";
        public boolean autoReconnect = false;
        public int reconnectDelayMs = -1;
        public int reconnectMaxAttempts = -1;
        public String liveViewSize = "";
        public int tickRatePathing = -1;
        public int tickRateIdle = -1;
    }

    /** Every account ever added — written on create, removed on user delete. */
    public static class Account {
        public String username;
        public String host;
        public int port = 25565;

        public Account() {}
        public Account(String username, String host, int port) {
            this.username = username;
            this.host = host;
            this.port = port;
        }

        /** Same shape as the binding key: "username@host:port". */
        public String key() { return username + "@" + host + ":" + port; }
    }

    private static class Root {
        List<Proxy> proxies = new ArrayList<>();
        Map<String, Binding> bindings = new HashMap<>();
        /** Every account ever added — survives restarts regardless of state. */
        List<Account> accounts = new ArrayList<>();
        int connectDelayMs = 500;
        /** Sessions that were online when the client last shut down —
         *  used to decide which restored accounts auto-join at launch.
         *  Account keys: "username@host:port". */
        List<String> lastOnline = new ArrayList<>();
        /** Random gap between two consecutive joins on the SAME server, ms.
         *  The queue waits rand(min,max) after one bot's attempt finished
         *  before it starts the next — joining a whole roster in one burst
         *  got the bots rate-limited / kicked. */
        int rejoinMinMs = 3000;
        int rejoinMaxMs = 8000;
        /** One join in flight per server. Off = old burst behaviour. */
        boolean rejoinSerialPerServer = true;
        String cardMode = "WATERFALL";
        String liveViewSize = "320x180";
        String cardSort = "DEFAULT";
        String cardDensity = "NORMAL";
        /** group name → 0xRRGGBB color int. */
        Map<String, Integer> groupColors = new HashMap<>();
        /** icon id → pinned to right icon column. */
        Map<String, Boolean> pinnedIcons = new HashMap<>();
        boolean liveFrozen = false;
        int tickRatePathing = 100;
        int tickRateIdle = 20;
        /** Process CPU% ceiling for the whole MC client process — adaptive
         *  scheduler throttles background bots until total process CPU stays
         *  under this. Bots share whatever headroom is left after the
         *  mounted player's own burn. */
        int maxCpuPct = 40;
        /** Adaptive tick scheduler on/off. Off = every bot runs at its manual
         *  pathing/idle rate; on = TickScheduler shrinks rates to stay under
         *  maxCpuPct. */
        boolean adaptiveTick = true;
        /** Per-band tick-rate curve (6 points, one per priority band).
         *  band 0 = highest priority. Values are pct 0-100 — the scheduler
         *  multiplies them by its capacity when adaptive is on. */
        float[] priorityCurve = { 100, 60, 35, 20, 12, 8 };
        /** Absolute floor for EVERY band — no group starves below this even
         *  when capacity is 0. User-facing "最低组 ticks" slider. */
        int minBandPct = 20;
        /** command id → key combos ("ctrl+shift+alt+&lt;keyCode&gt;"); absent = default, empty = unbound. */
        Map<String, List<String>> commandBinds = new HashMap<>();
        /** Active theme preset id ("" = custom / none). */
        String themePresetId = "";
        /** Custom theme colors (key → ARGB int) — used when presetId is "". */
        Map<String, Integer> themeCustomColors = new HashMap<>();
        /** User-created themes the user saved / imported, each a full palette. */
        java.util.List<CustomTheme> customThemes = new java.util.ArrayList<>();
        /** Empty groups the user created — kept even while no account uses them. */
        java.util.List<String> extraGroups = new java.util.ArrayList<>();
        /** 人物同步分组 — user-built groups of accounts kept in lock-step. */
        java.util.List<SyncGroup> syncGroups = new java.util.ArrayList<>();
        /** 聚拢半径（格）— beyond this the followers stop mirroring and path
         *  toward the leader instead. */
        float syncGatherRange = 10f;
        /** 开启同步时自动把落后的 bot 寻路到主控身边。 */
        boolean syncAutoGather = true;
    }

    /**
     * 一个同步分组：成员之间攻击 / 移动 / 视角完全一致。
     * 组内第一台在线的是主控（leader），其余跟随。
     */
    public static class SyncGroup {
        public String name = "同步组";
        public boolean enabled = false;
        /** Account keys ("username@host:port"), in join order. */
        public java.util.List<String> members = new java.util.ArrayList<>();
        /** Seconds a follower keeps mirroring after the leader stops moving. */
        public int holdSeconds = 3;

        public SyncGroup() {}
        public SyncGroup(String name) { this.name = name; }
    }

    /** A user-defined theme that lives next to the built-in presets. */
    public static class CustomTheme {
        public String id;
        public String name;
        public Map<String, Integer> colors = new HashMap<>();
    }


    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Root root;

    private static File file() { return new File("config", "mythosnexus.json"); }

    private static synchronized Root root() {
        if (root == null) {
            File f = file();
            if (f.isFile()) {
                try (Reader r = new InputStreamReader(new FileInputStream(f), "UTF-8")) {
                    root = GSON.fromJson(r, Root.class);
                } catch (Throwable t) {
                    NexusPlugin.LOGGER.warn("nexus config load failed: {}", t.toString());
                }
            }
            if (root == null) root = new Root();
            if (root.proxies == null) root.proxies = new ArrayList<>();
            if (root.bindings == null) root.bindings = new HashMap<>();
            if (root.accounts == null) root.accounts = new ArrayList<>();
            if (root.customThemes == null) root.customThemes = new java.util.ArrayList<>();
            if (root.extraGroups == null) root.extraGroups = new java.util.ArrayList<>();
            if (root.syncGroups == null) root.syncGroups = new java.util.ArrayList<>();
            root.syncGroups.removeIf(g -> g == null);
            for (SyncGroup g : root.syncGroups) {
                if (g.name == null || g.name.isEmpty()) g.name = "同步组";
                if (g.members == null) g.members = new java.util.ArrayList<>();
                g.members.removeIf(k -> k == null || k.isEmpty());
                g.holdSeconds = Math.max(0, Math.min(60, g.holdSeconds));
            }
            root.accounts.removeIf(a -> a == null || a.username == null || a.username.isEmpty()
                    || a.host == null || a.host.isEmpty());
        }
        return root;
    }

    private static final long SAVE_DELAY_MS = 500;
    private static java.util.concurrent.ScheduledExecutorService saver;
    private static boolean savePending;

    /** Coalesces writes: the file is written once, ~500 ms after the last change. */
    public static synchronized void save() {
        if (root == null || savePending) return;
        if (saver == null) {
            saver = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Nexus-ConfigSaver");
                t.setDaemon(true);
                return t;
            });
            Runtime.getRuntime().addShutdownHook(new Thread(NexusConfig::flushNow, "Nexus-ConfigFlush"));
        }
        savePending = true;
        saver.schedule(NexusConfig::flushNow, SAVE_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** Writes the config immediately if a save is pending. */
    public static synchronized void flushNow() {
        if (!savePending) return;
        savePending = false;
        if (root == null) return;
        File f = file();
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8")) {
            GSON.toJson(root, w);
        } catch (Throwable t) {
            NexusPlugin.LOGGER.warn("nexus config save failed: {}", t.toString());
        }
    }

    /** Write through now — roster mutations must not wait out 500 ms. */
    public static synchronized void saveNow() {
        save();
        flushNow();
    }


    // ---- account roster -----------------------------------------------------

    /** Add (or refresh) an account in the persistent roster. */
    public static synchronized void rememberAccount(String username, String host, int port) {
        if (username == null || username.isEmpty() || host == null || host.isEmpty()) return;
        for (Account a : root().accounts) {
            if (a.username.equalsIgnoreCase(username)
                    && a.host.equalsIgnoreCase(host) && a.port == port) {
                return;   // already there — no write, no churn
            }
        }
        root().accounts.add(new Account(username, host, port));
        saveNow();
    }

    /** Drop one account from the roster (user deleted it, or it was renamed). */
    public static synchronized void forgetAccount(String username, String host, int port) {
        boolean removed = root().accounts.removeIf(a ->
                a.username.equalsIgnoreCase(username == null ? "" : username)
                        && a.host.equalsIgnoreCase(host == null ? "" : host)
                        && a.port == port);
        if (removed) saveNow();
    }

    /** Drop by "username@host:port" — the form every caller already holds. */
    public static synchronized void forgetAccountByKey(String key) {
        if (key == null) return;
        boolean removed = root().accounts.removeIf(a -> a.key().equalsIgnoreCase(key));
        if (removed) saveNow();
    }

    /** Snapshot of the roster, in the order the accounts were first added. */
    public static synchronized List<Account> accounts() {
        return new ArrayList<>(root().accounts);
    }

    public static synchronized boolean hasAccount(String key) {
        if (key == null) return false;
        for (Account a : root().accounts)
            if (a.key().equalsIgnoreCase(key)) return true;
        return false;
    }

    /** Move a binding to a renamed account key. */
    public static synchronized void rekeyBinding(String oldKey, String newKey) {
        if (oldKey == null || newKey == null || oldKey.equals(newKey)) return;
        Binding b = root().bindings.remove(oldKey);
        if (b != null) root().bindings.put(newKey, b);
        save();
    }


    public static synchronized List<Proxy> proxies() {
        return Collections.unmodifiableList(root().proxies);
    }

    public static synchronized Proxy proxyAt(int i) {
        return (i >= 0 && i < root().proxies.size()) ? root().proxies.get(i) : null;
    }

    public static synchronized void addProxy(Proxy p) {
        root().proxies.add(p);
        save();
    }

    public static synchronized void updateProxy(int i, Proxy p) {
        if (i < 0 || i >= root().proxies.size()) return;
        root().proxies.set(i, p);
        save();
    }

    public static synchronized void removeProxy(int i) {
        if (i < 0 || i >= root().proxies.size()) return;
        root().proxies.remove(i);
        // bindings hold indexes — unbind or shift
        for (Binding b : root().bindings.values()) {
            if (b.proxy == i) b.proxy = -1;
            else if (b.proxy > i) b.proxy--;
        }
        save();
    }


    public static synchronized Binding bindingFor(String key) {
        Binding b = root().bindings.get(key);
        if (b == null) {
            b = new Binding();
            root().bindings.put(key, b);
        }
        return b;
    }

    public static synchronized void setGroup(String key, String group) {
        bindingFor(key).group = group;
        save();
    }

    public static synchronized void setProxy(String key, int idx) {
        bindingFor(key).proxy = idx;
        save();
    }

    public static synchronized void setNote(String key, String note) {
        bindingFor(key).note = note == null ? "" : note;
        save();
    }

    public static synchronized void setAutoReconnect(String key, boolean on) {
        bindingFor(key).autoReconnect = on;
        save();
    }


    public static synchronized void setAllAutoReconnect(boolean on) {
        for (Binding b : root().bindings.values()) b.autoReconnect = on;
        for (Account a : root().accounts) bindingFor(a.key()).autoReconnect = on;
        saveNow();
    }

    /** {on, total} over every account in the roster — drives the switch label. */
    public static synchronized int[] autoReconnectStats() {
        int on = 0, total = 0;
        for (Account a : root().accounts) {
            total++;
            if (bindingFor(a.key()).autoReconnect) on++;
        }
        return new int[]{on, total};
    }

    /** Random gap range (ms) between two joins on the same server. */
    public static synchronized int rejoinMinMs() { return Math.max(0, root().rejoinMinMs); }
    public static synchronized int rejoinMaxMs() {
        return Math.max(rejoinMinMs(), root().rejoinMaxMs);
    }
    public static synchronized void setRejoinMinMs(int ms) {
        root().rejoinMinMs = Math.max(0, Math.min(600_000, ms));
        if (root().rejoinMaxMs < root().rejoinMinMs) root().rejoinMaxMs = root().rejoinMinMs;
        save();
    }
    public static synchronized void setRejoinMaxMs(int ms) {
        root().rejoinMaxMs = Math.max(rejoinMinMs(), Math.min(600_000, ms));
        save();
    }
    /** One join in flight per server; the next one waits its random turn. */
    public static synchronized boolean rejoinSerial() { return root().rejoinSerialPerServer; }
    public static synchronized void setRejoinSerial(boolean on) {
        root().rejoinSerialPerServer = on; save();
    }

    public static final int DEFAULT_RECONNECT_MS = 5000;

    public static synchronized int reconnectDelayMs(com.mythos.nexus.session.BotSession s) {
        int v = bindingFor(com.mythos.nexus.ui.NexusGeometry.sessionKey(s)).reconnectDelayMs;
        return v >= 0 ? v : DEFAULT_RECONNECT_MS;
    }
    public static synchronized void setReconnectDelayMs(com.mythos.nexus.session.BotSession s, int ms) {
        bindingFor(com.mythos.nexus.ui.NexusGeometry.sessionKey(s)).reconnectDelayMs = Math.max(0, ms);
        save();
    }

    /** Per-account max reconnect attempts; 0 = infinite, -1 → default infinite. */
    public static synchronized int reconnectMaxAttempts(com.mythos.nexus.session.BotSession s) {
        return bindingFor(com.mythos.nexus.ui.NexusGeometry.sessionKey(s)).reconnectMaxAttempts;
    }
    public static synchronized void setReconnectMaxAttempts(com.mythos.nexus.session.BotSession s, int n) {
        bindingFor(com.mythos.nexus.ui.NexusGeometry.sessionKey(s)).reconnectMaxAttempts = Math.max(-1, n);
        save();
    }


    /** "WxH" for this account, or null when it follows the global size. */
    public static synchronized String liveViewSizeFor(String key) {
        String v = bindingFor(key).liveViewSize;
        return v == null || v.isEmpty() ? null : v;
    }
    public static synchronized void setLiveViewSizeFor(String key, String size) {
        bindingFor(key).liveViewSize = size == null ? "" : size;
        save();
    }

    /** Tick rate for this account, or -1 when it follows the global value. */
    public static synchronized int tickRatePathingFor(String key) {
        return bindingFor(key).tickRatePathing;
    }
    public static synchronized int tickRateIdleFor(String key) {
        return bindingFor(key).tickRateIdle;
    }
    public static synchronized void setTickRatePathingFor(String key, int pct) {
        bindingFor(key).tickRatePathing = Math.max(-1, Math.min(100, pct));
        save();
    }
    public static synchronized void setTickRateIdleFor(String key, int pct) {
        bindingFor(key).tickRateIdle = Math.max(-1, Math.min(100, pct));
        save();
    }

    public static synchronized int maxCpuPct() { return root().maxCpuPct; }
    public static synchronized void setMaxCpuPct(int pct) {
        root().maxCpuPct = Math.max(5, Math.min(95, pct));
        save();
    }

    public static synchronized boolean adaptiveTick() { return root().adaptiveTick; }
    public static synchronized void setAdaptiveTick(boolean on) {
        root().adaptiveTick = on;
        save();
    }

    /** Live view of the priority curve — copy so callers can't mutate root. */
    public static synchronized float[] priorityCurve() {
        float[] c = root().priorityCurve;
        return c == null ? new float[]{100,60,35,20,12,8} : c.clone();
    }
    /** Update one point; values clamp to 0-100. */
    public static synchronized void setPriorityCurvePoint(int band, int pct) {
        float[] c = root().priorityCurve;
        if (c == null || band < 0 || band >= c.length) return;
        c[band] = Math.max(0, Math.min(100, pct));
        save();
    }

    /** Hard floor for every priority band — "最低组 ticks" in the UI. */
    public static synchronized int minBandPct() { return root().minBandPct; }
    public static synchronized void setMinBandPct(int pct) {
        root().minBandPct = Math.max(0, Math.min(100, pct));
        save();
    }


    public static synchronized int connectDelayMs() {
        return Math.max(0, root().connectDelayMs);
    }

    public static synchronized void setConnectDelayMs(int ms) {
        root().connectDelayMs = Math.max(0, ms);
        save();
    }


    /** Snapshot the currently-online session keys (excluding human). */
    public static synchronized void saveLastOnline(
            java.util.List<com.mythos.nexus.session.BotSession> sessions) {
        List<String> keys = new ArrayList<>();
        for (com.mythos.nexus.session.BotSession s : sessions) {
            if (s == null || s.id == 0) continue;  // skip human pseudo
            if (s.state == com.mythos.nexus.session.BotSession.State.ONLINE
                    && s.username != null && s.serverHost != null)
                keys.add(s.username + "@" + s.serverHost + ":" + s.serverPort);
        }
        root().lastOnline = keys;
        save();
    }

    /** Sessions to re-add + reconnect at next launch. */
    public static synchronized List<String> lastOnline() {
        return Collections.unmodifiableList(root().lastOnline);
    }


    public static synchronized String cardMode() { return root().cardMode; }
    public static synchronized void setCardMode(String mode) {
        root().cardMode = mode == null ? "WATERFALL" : mode;
        save();
    }

    /** "WxH" — e.g. "320x180". */
    public static synchronized String liveViewSize() { return root().liveViewSize; }
    public static synchronized void setLiveViewSize(String size) {
        root().liveViewSize = size == null ? "320x180" : size;
        save();
    }


    public static synchronized String cardSort() { return root().cardSort; }
    public static synchronized void setCardSort(String s) {
        root().cardSort = s == null ? "DEFAULT" : s; save();
    }

    public static synchronized String cardDensity() { return root().cardDensity; }
    public static synchronized void setCardDensity(String d) {
        root().cardDensity = d == null ? "NORMAL" : d; save();
    }

    /** 0xRRGGBB for the group, or null when unset. */
    public static synchronized Integer groupColor(String group) {
        return root().groupColors.get(group);
    }

    public static synchronized boolean iconPinned(String id) {
        // Reserved ids can never be unpinned — unpinning "settings" once
        // locked the user out of the only path back to this overlay, and the
        // persisted flag had to be overridden here.
        if ("settings".equals(id)) {
            return true;
        }
        Boolean v = root().pinnedIcons.get(id);
        return v == null || v;   // default: pinned
    }
    public static synchronized void setIconPinned(String id, boolean pinned) {
        root().pinnedIcons.put(id, pinned); save();
    }

    public static synchronized boolean liveFrozen() { return root().liveFrozen; }
    public static synchronized void setLiveFrozen(boolean frozen) {
        root().liveFrozen = frozen; save();
    }

    /** Background-bot world tick rate when it has an active path, 0-100. */
    public static synchronized int tickRatePathing() { return root().tickRatePathing; }

    /** Background-bot world tick rate when it is parked / idle, 0-100. */
    public static synchronized int tickRateIdle() { return root().tickRateIdle; }

    /** Stored combos for a command, or null when the user never customized it. */
    public static synchronized List<String> commandBinds(String id) {
        if (root().commandBinds == null) root().commandBinds = new HashMap<>();
        List<String> v = root().commandBinds.get(id);
        return v == null ? null : new ArrayList<>(v);
    }
    /** Replace every customized binding at once (null value = back to default). */
    public static synchronized void setAllCommandBinds(Map<String, List<String>> binds) {
        Map<String, List<String>> m = new HashMap<>();
        for (Map.Entry<String, List<String>> e : binds.entrySet())
            if (e.getValue() != null) m.put(e.getKey(), new ArrayList<>(e.getValue()));
        root().commandBinds = m;
        save();
    }


    /** Currently active preset id, "" for a custom palette. */
    public static synchronized String themePresetId() { return root().themePresetId; }
    public static synchronized void setThemePresetId(String id) {
        root().themePresetId = id == null ? "" : id; save();
    }
    /** Custom palette — token name → ARGB. */
    public static synchronized Map<String,Integer> themeCustomColors() {
        return new HashMap<>(root().themeCustomColors);
    }
    public static synchronized void setThemeCustomColors(Map<String,Integer> colors) {
        root().themeCustomColors = colors == null ? new HashMap<>() : new HashMap<>(colors);
        save();
    }

    /** Boot-time: rebuild CUSTOM list + apply saved preset or custom colors. */
    public static synchronized void applySavedTheme() {
        // Rehydrate user-created themes first so preset() finds them.
        com.mythos.nexus.ui.NexusTheme.CUSTOM.clear();
        for (CustomTheme ct : root().customThemes) {
            com.mythos.nexus.ui.NexusTheme.Preset p =
                    new com.mythos.nexus.ui.NexusTheme.Preset(ct.id, ct.name);
            for (Map.Entry<String,Integer> e : ct.colors.entrySet())
                p.colors.put(e.getKey(), e.getValue());
            com.mythos.nexus.ui.NexusTheme.CUSTOM.add(p);
        }
        com.mythos.nexus.ui.NexusTheme.Preset p =
                com.mythos.nexus.ui.NexusTheme.preset(root().themePresetId);
        if (p != null) {
            com.mythos.nexus.ui.NexusTheme.apply(p);
            return;
        }
        Map<String,Integer> custom = root().themeCustomColors;
        if (custom != null && !custom.isEmpty()) {
            for (Map.Entry<String,Integer> e : custom.entrySet())
                com.mythos.nexus.ui.NexusTheme.set(e.getKey(), e.getValue());
        }
    }

    /** Persist a user theme — adds/updates customThemes + sets it active. */
    public static synchronized void addCustomTheme(
            String id, String name, Map<String,Integer> colors) {
        CustomTheme ct = new CustomTheme();
        ct.id = id; ct.name = name;
        ct.colors = colors == null ? new HashMap<>() : new HashMap<>(colors);
        boolean replaced = false;
        for (int i = 0; i < root().customThemes.size(); i++) {
            if (root().customThemes.get(i).id.equals(id)) {
                root().customThemes.set(i, ct); replaced = true; break;
            }
        }
        if (!replaced) root().customThemes.add(ct);
        setThemePresetId(id);
        setThemeCustomColors(ct.colors);
        save();
    }

    /** Remove a user-created theme. */
    public static synchronized void removeCustomTheme(String id) {
        root().customThemes.removeIf(ct -> ct.id.equals(id));
        if (id.equals(root().themePresetId)) setThemePresetId("");
        save();
    }

    /** Snapshot of all user-created themes for the panel. */
    public static synchronized java.util.List<CustomTheme> customThemes() {
        return new java.util.ArrayList<>(root().customThemes);
    }


    /** Groups created by the user that have no members yet — survives until
     *  explicitly deleted. Merged with session-derived groups by the UI. */
    public static synchronized java.util.List<String> extraGroups() {
        return new java.util.ArrayList<>(root().extraGroups);
    }

    /** Track a user-created group so it persists even with zero members. */
    public static synchronized void addGroup(String name) {
        if (name == null || name.isEmpty()) return;
        java.util.List<String> g = root().extraGroups;
        if (!g.contains(name)) { g.add(name); save(); }
    }

    /** Drop a user-created group from the persistent set — the group may
     *  still appear via members that use it; removeGroup only stops
     *  tracking it as an *extra*. */
    public static synchronized void removeGroup(String name) {
        root().extraGroups.remove(name);
        save();
    }

    /** Rename — extraGroups entry is moved too so empty renames keep working. */
    public static synchronized void renameGroup(String oldName, String newName) {
        java.util.List<String> g = root().extraGroups;
        int i = g.indexOf(oldName);
        if (i >= 0) { g.set(i, newName); save(); }
    }


    // ---- 人物同步分组 ------------------------------------------------------

    public static synchronized java.util.List<SyncGroup> syncGroups() {
        java.util.List<SyncGroup> out = new java.util.ArrayList<>();
        for (SyncGroup g : root().syncGroups) out.add(copyOf(g));
        return out;
    }

    /** Defensive copy — callers iterate this off the client thread. */
    private static SyncGroup copyOf(SyncGroup g) {
        SyncGroup c = new SyncGroup(g.name);
        c.enabled = g.enabled;
        c.members = new java.util.ArrayList<>(g.members);
        c.holdSeconds = g.holdSeconds;
        return c;
    }

    public static synchronized SyncGroup syncGroup(String name) {
        for (SyncGroup g : root().syncGroups)
            if (g.name.equals(name)) return copyOf(g);
        return null;
    }

    /** Create a group; returns false when the name is taken. */
    public static synchronized boolean addSyncGroup(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        String n = name.trim();
        for (SyncGroup g : root().syncGroups) if (g.name.equalsIgnoreCase(n)) return false;
        root().syncGroups.add(new SyncGroup(n));
        saveNow();
        return true;
    }

    public static synchronized void removeSyncGroup(String name) {
        boolean removed = root().syncGroups.removeIf(g -> g.name.equals(name));
        if (removed) saveNow();
    }

    public static synchronized void renameSyncGroup(String oldName, String newName) {
        if (newName == null || newName.trim().isEmpty()) return;
        String n = newName.trim();
        for (SyncGroup g : root().syncGroups) {
            if (g.name.equals(oldName)) { g.name = n; saveNow(); return; }
        }
    }

    public static synchronized void setSyncGroupEnabled(String name, boolean on) {
        for (SyncGroup g : root().syncGroups)
            if (g.name.equals(name)) { g.enabled = on; saveNow(); return; }
    }

    public static synchronized void setSyncHoldSeconds(String name, int sec) {
        for (SyncGroup g : root().syncGroups)
            if (g.name.equals(name)) {
                g.holdSeconds = Math.max(0, Math.min(60, sec)); save(); return;
            }
    }

    /** Add accounts to a group; an account lives in at most one group. */
    public static synchronized void syncAddMembers(String name, java.util.List<String> keys) {
        SyncGroup target = null;
        for (SyncGroup g : root().syncGroups) if (g.name.equals(name)) target = g;
        if (target == null) return;
        for (String k : keys) {
            if (k == null || k.isEmpty() || target.members.contains(k)) continue;
            for (SyncGroup g : root().syncGroups)
                if (g != target) g.members.remove(k);
            target.members.add(k);
        }
        saveNow();
    }

    public static synchronized void syncRemoveMember(String name, String key) {
        for (SyncGroup g : root().syncGroups)
            if (g.name.equals(name) && g.members.remove(key)) { saveNow(); return; }
    }

    /** Group an account belongs to, or null. */
    public static synchronized String syncGroupOf(String key) {
        for (SyncGroup g : root().syncGroups)
            if (g.members.contains(key)) return g.name;
        return null;
    }

    /** Every enabled group — the sync engine polls this each tick. */
    public static synchronized java.util.List<SyncGroup> enabledSyncGroups() {
        java.util.List<SyncGroup> out = new java.util.ArrayList<>();
        for (SyncGroup g : root().syncGroups) if (g.enabled) out.add(copyOf(g));
        return out;
    }

    /** {enabled, total} over every group — drives the master switch label. */
    public static synchronized int[] syncGroupStats() {
        int on = 0, total = 0;
        for (SyncGroup g : root().syncGroups) { total++; if (g.enabled) on++; }
        return new int[]{on, total};
    }

    /** Gather radius in blocks — followers outside it path toward the leader. */
    public static synchronized float syncGatherRange() { return root().syncGatherRange; }
    public static synchronized void setSyncGatherRange(float r) {
        root().syncGatherRange = Math.max(2f, Math.min(64f, r)); save();
    }
    public static synchronized boolean syncAutoGather() { return root().syncAutoGather; }
    public static synchronized void setSyncAutoGather(boolean on) {
        root().syncAutoGather = on; save();
    }
}
