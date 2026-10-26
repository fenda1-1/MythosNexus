package com.mythos.nexus.session;

import com.mythos.nexus.core.NexusPlugin;
import com.mythos.nexus.core.NexusRefs;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-session snapshot/restore of MythosScript's shared static state.
 *
 * MythosScript keeps every feature toggle + runtime tick state as
 * `public static`/`private static` fields on singleton classes
 * (KillAuraHandler.INSTANCE etc.).  Those fields belong to *whichever
 * session is mounted* — when the user mounts a different bot, the fields
 * must swap so bot1's kill-aura stays on for bot1 while bot2 runs clean.
 *
 * Stash granularity is the Nexus *group*, not the session: bots bound to
 * the same Binding.group share one stash, so a config edit on any member
 * is what every other member restores on next mount ("one edit, whole
 * group").  The human pseudo-session and ungrouped bots each keep their
 * own stash.  Runtime state (world/player/entities) never enters the
 * stash — isSkippable excludes it — and the few statics that DO hold
 * session-private runtime state (PER_SESSION_CLASSES, e.g. McpWorldView's
 * webview progress) are stashed per session, not per group.
 *
 * Approach: on mount(), snapshot the live values of the registered fields
 * into this session's group stash; on unmount(), restore the *previous*
 * session's group stash (or defaults).  Reflection-driven, no MythosScript
 * compile dep.
 */
public final class MythosStateSwap {

    /** One registered static field + the stash slot for it. */
    private static final class Slot {
        final Object holder;      // the singleton INSTANCE or null for pure-static class
        final Field field;
        final String label;       // "Class.field" for logs
        final boolean contents;   // final container: swap contents not reference
        final boolean perSession; // session-private runtime state: stash by session, not group
        final int stashIndex;     // position within its own bucket (config or session)
        Slot(Object h, Field f, String l, boolean c, boolean ps, int idx) {
            holder = h; field = f; label = l; contents = c; perSession = ps; stashIndex = idx;
        }
    }

    /** All fields we swap.  Built lazily once MythosScript classes exist. */
    private static volatile List<Slot> SLOTS;
    /** Bucket counters during ensureBuilt: config slots index into the shared
     *  group stash; perSession slots index into the session's own stash. Each
     *  bucket keeps its own dense index so two buckets never share slot i. */
    private static int configSlotCount;
    private static int sessionSlotCount;
    private static volatile boolean built = false;
    private static final Object BUILD_LOCK = new Object();

    /**
     * Classes whose entire static field set gets mirrored.  The manager
     * FeatureState maps are handled specially (nested mutable objects).
     */
    private static final String[] SINGLETON_CLASSES = {
        NexusRefs.KILL_AURA_HANDLER,
        NexusRefs.FLY_HANDLER,
        NexusRefs.AUTO_EAT_HANDLER,
        NexusRefs.AUTO_FISHING_HANDLER,
        NexusRefs.AUTO_PICKUP_HANDLER,
        NexusRefs.CONDITIONAL_EXECUTION_HANDLER,
        NexusRefs.AUTO_ESCAPE_HANDLER,
        NexusRefs.AUTO_FOLLOW_HANDLER,
        NexusRefs.EMBEDDED_NAVIGATION_HANDLER,
        NexusRefs.SPEED_HANDLER,
        NexusRefs.MOVEMENT_FEATURE_MANAGER,
        NexusRefs.MISC_FEATURE_MANAGER,
        NexusRefs.RENDER_FEATURE_MANAGER,
        NexusRefs.WORLD_FEATURE_MANAGER,
        NexusRefs.ITEM_FEATURE_MANAGER,
        NexusRefs.BLOCK_FEATURE_MANAGER,
        NexusRefs.SIMULATED_KEY_INPUT_MANAGER,
    };

    /**
     * Classes stashed per session, never by group: their statics hold
     * session-private runtime state (MCP webview's world view, targets,
     * chase progress, camera) that must survive unmount for its own
     * session but must not bleed into group-mates.
     */
    private static final String[] PER_SESSION_CLASSES = {
        NexusRefs.MCP_WORLD_VIEW,
    };

    /**
     * Individual static fields that are runtime toggles, not config: a bot
     * turning its kill-aura / fly / auto-eat on must not switch the feature
     * on for group-mates.  These are stashed per session (never shared) so
     * "bot2 enabled=true" stays bot2's own.  Plain config fields
     * (attackRange, huntRadius, lists) remain group-shared.
     * Key = "fully.qualified.ClassName.fieldName".
     */
    private static final java.util.Set<String> PER_SESSION_FIELDS =
        new java.util.HashSet<>();
    // All runtime toggle fields have been boxed into each handler's
    // RuntimeState (non-static, keyed by session inside MythosScript), so
    // every former entry here pointed at a field that no longer exists as
    // a static — dead entries. Stash now only manages static config fields;
    // the set stays (empty) in case a future static runtime toggle needs
    // per-session treatment again.

    /** The MythosScript ProfileManager static we redirect per-session. */
    private static final String PROFILE_MANAGER = NexusRefs.PROFILE_MANAGER;

    /**
     * Static config stash, keyed by sharing group:
     *  - bot session  -> its NexusConfig group name (all bots in one group
     *    share a single stash: change one, the whole group sees it)
     *  - human pseudo-session -> the session object itself (independent)
     *  - bot with no group set -> the session object itself (independent)
     * A mounted bot's live statics are ALSO shared with the group's stash —
     * a background group's config edits apply to whatever its members see on
     * next mount, and a mounted group member's edits are what the rest of the
     * group will restore.
     */
    private static final Map<Object, List<Object>> STASH = new java.util.HashMap<>();
    /** Per-session stash for PER_SESSION_CLASSES slots, keyed by the session
     *  itself — group sharing never applies to these. Indexed parallel to
     *  SLOTS like the group stash. */
    private static final Map<BotSession, List<Object>> SESSION_STASH = new IdentityHashMap<>();
    /** session -> the stash key it last joined, so a group rename moves it. */
    private static final Map<BotSession, Object> SESSION_KEY = new IdentityHashMap<>();
    /** stash key -> number of sessions currently joined to it. The stash is
     *  dropped only when the last session leaves (clearSession). */
    private static final Map<Object, Integer> REFS = new java.util.HashMap<>();
    private static final Object STASH_LOCK = new Object();

    /** The session whose state is currently live in the statics. */
    private static volatile BotSession liveSession;


    /** Build the slot table once (first mount call). Safe to call again. */
    private static void ensureBuilt() {
        if (built) return;
        synchronized (BUILD_LOCK) {
            if (built) return;
            List<Slot> slots = new ArrayList<>();
            for (String cn : SINGLETON_CLASSES) {
                addClassStatics(cn, slots, false);
            }
            for (String cn : PER_SESSION_CLASSES) {
                addClassStatics(cn, slots, true);
            }
            // ProfileManager: only activeProfileName + profileMappings change
            // per account. The rest are read-once constants.
            addNamedField(PROFILE_MANAGER, "activeProfileName", null, slots);
            addNamedField(PROFILE_MANAGER, "profileMappings", null, slots);
            SLOTS = slots;
            built = true;
            NexusPlugin.LOGGER.info("MythosStateSwap: {} slots registered", slots.size());
        }
    }

    /** Mirror every non-constant static field on a singleton class. */
    private static void addClassStatics(String className, List<Slot> slots, boolean perSession) {
        try {
            Class<?> c = NexusRefs.cls(className);
            Object holder = null;
            try {
                java.lang.reflect.Field inst = c.getDeclaredField("INSTANCE");
                inst.setAccessible(true);
                holder = inst.get(null);
            }
            catch (NoSuchFieldException ignored) {} // pure-static class
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                boolean fin = Modifier.isFinal(f.getModifiers());
                if (fin && !isMutableHolder(f)) continue;
                // skip constants/logger/GSON/Field-reflection-handles and
                // non-per-session registry maps (STATES/FEATURES) — these are
                // owner-keyed or shared-by-design and must not be repopulated
                // from an empty stash on a fresh mount.
                if (isSkippable(f, perSession)) continue;
                f.setAccessible(true);
                boolean contents = fin && !isImmutableInstance(f.get(holder));
                if (fin && isMutableHolder(f) && !contents) continue; // immutable const
                boolean ps = perSession
                        || PER_SESSION_FIELDS.contains(className + "." + f.getName());
                // PER_SESSION_FIELDS marks runtime toggles — stash them by the
                // session itself so enabling kill-aura on bot2 does not flip
                // it on for group-mates. Config stays group-shared.
                int idx = ps ? sessionSlotCount++ : configSlotCount++;
                slots.add(new Slot(holder, f, className + "." + f.getName(), contents, ps, idx));
            }
        } catch (ClassNotFoundException e) {
            NexusPlugin.LOGGER.warn("MythosStateSwap: {} not present", className);
        } catch (Throwable t) {
            NexusPlugin.LOGGER.error("MythosStateSwap scan {} failed", className, t);
        }
    }

    private static void addNamedField(String className, String fieldName,
                                       Object holder, List<Slot> slots) {
        try {
            Class<?> c = NexusRefs.cls(className);
            Field f = NexusRefs.field(c, fieldName);
            f.setAccessible(true);
            boolean fin = Modifier.isFinal(f.getModifiers());
            slots.add(new Slot(holder, f, className + "." + fieldName,
                    fin && isMutableHolder(f), false, configSlotCount++));
        } catch (Throwable t) {
            NexusPlugin.LOGGER.warn("MythosStateSwap field {}.{} missing: {}",
                    className, fieldName, t.toString());
        }
    }

    private static boolean isMutableHolder(Field f) {
        // final containers (Map/List/Set) still get swapped — their contents
        // change per session even though the reference is fixed.
        Class<?> t = f.getType();
        return java.util.Map.class.isAssignableFrom(t)
            || java.util.Collection.class.isAssignableFrom(t)
            || java.util.Queue.class.isAssignableFrom(t);
    }

    /** True for Collections.unmodifiable*, Arrays.asList, Guava Immutable*. */
    private static boolean isImmutableInstance(Object o) {
        if (o == null) return false;
        String n = o.getClass().getName();
        return n.contains("Unmodifiable") || n.contains("Arrays$ArrayList")
            || n.contains("Immutable") || n.contains("Singleton")
            || n.startsWith("java.util.Collections$Empty");
    }

    private static boolean isSkippable(Field f, boolean perSession) {
        Class<?> t = f.getType();
        String n = f.getName();
        if (t == org.apache.logging.log4j.Logger.class) return true;
        if (t == com.google.gson.Gson.class) return true;
        if (java.lang.reflect.Field.class.isAssignableFrom(t)) return true;
        if (t == java.lang.Class.class) return true;
        if (t == java.lang.ThreadLocal.class) return true;
        // Skip fields that hold live Minecraft objects — these are already
        // managed by BotSession.world/player/playerController and swapping
        // them leaks the entire WorldClient (~25MB each) into the stash.
        if (net.minecraft.client.multiplayer.WorldClient.class.isAssignableFrom(t)) return true;
        if (net.minecraft.client.entity.EntityPlayerSP.class.isAssignableFrom(t)) return true;
        if (net.minecraft.client.multiplayer.PlayerControllerMP.class.isAssignableFrom(t)) return true;
        if (net.minecraft.world.World.class.isAssignableFrom(t)) return true;
        if (net.minecraft.entity.Entity.class.isAssignableFrom(t)) return true;
        // String/int/long/boolean constants already excluded by !isMutableHolder
        if (n.equals("INSTANCE") || n.equals("LOGGER") || n.equals("GSON")) return true;
        // Registry / owner-keyed maps must never be repopulated from a stash.
        // ProGuard renames the private fields (FEATURES->a, STATES->d), so a
        // name check misses them in the shipped jar — match by type instead.
        // SINGLETON_CLASSES' `static final Map` fields are either a per-owner
        // RuntimeState index (STATES) or a feature registry (FEATURES) whose
        // FeatureState.enabled is itself owner-keyed inside FeatureState
        // .enabledByOwner. Repopulating them via repopulate() clears the live
        // map (m.clear()) then refills from an EMPTY stash on a fresh mount,
        // leaving isManagedFeature()/isEnabled() to report nothing — exactly
        // why a bot's feature buttons do nothing. Config that is genuinely
        // group-shared is held in List/Set fields (rules/presets/categories),
        // which are still stashed. Per-session classes (McpWorldView) keep
        // their Maps stashed normally (perSession=true skips this rule).
        if (!perSession && java.util.Map.class.isAssignableFrom(t)) return true;
        return false;
    }


    /**
     * Snapshot the live statics for `outgoing` (the session being unmounted),
     * then restore `incoming`'s stash (or defaults for a fresh session).
     * Called on the client thread inside SessionManager.mount() AFTER the
     * Minecraft fields are already swapped.
     */
    public static void swapTo(BotSession outgoing, BotSession incoming) {
        ensureBuilt();
        if (SLOTS == null || SLOTS.isEmpty()) return;

        if (outgoing != null) {
            Object key = joinKey(outgoing);
            synchronized (STASH_LOCK) {
                for (Slot s : SLOTS) {
                    try {
                        Object cur = s.field.get(s.holder);
                        stashPut(outgoing, key, s, s.stashIndex, deepCopy(cur));
                    } catch (Throwable t) {
                        NexusPlugin.LOGGER.warn("stash fail {}", s.label, t);
                    }
                }
            }
        }
        if (incoming != null) {
            Object key = joinKey(incoming);
            for (Slot s : SLOTS) {
                try {
                    Object val;
                    synchronized (STASH_LOCK) {
                        val = stashGet(incoming, key, s, s.stashIndex);
                    }
                    // A null stash entry means "this session has never mounted
                    // before" — there is nothing to restore, so leave the live
                    // value alone (it already holds the file-loaded config).
                    // Writing the field-type default here was the "config reset
                    // to 0 on first mount" bug: defaultFor(float)=0f overwrote
                    // attackRange/huntRadius/etc. before the GUI ever read them.
                    if (val == null) continue;
                    if (s.contents) repopulate(s, val);
                    else            s.field.set(s.holder, val);
                } catch (Throwable t) {
                    NexusPlugin.LOGGER.warn("restore fail {}", s.label, t);
                }
            }
            liveSession = incoming;
        }
    }

    /** Store one slot value into the group's stash (or the session's own
     *  stash for perSession slots). Caller holds STASH_LOCK. */
    private static void stashPut(BotSession session, Object groupKey, Slot s,
                                 int i, Object val) {
        List<Object> stash = stashFor(session, groupKey, s, true);
        while (stash.size() <= i) stash.add(null); // sparse-safe: keep dense index
        stash.set(i, val);
    }

    /** Read one slot value back; absent → null (caller skips restore so the
     *  live field keeps its file-loaded value instead of getting zeroed).
     *  Caller holds STASH_LOCK. */
    private static Object stashGet(BotSession session, Object groupKey, Slot s,
                                   int i) {
        List<Object> stash = stashFor(session, groupKey, s, false);
        return (stash != null && i < stash.size()) ? stash.get(i) : null;
    }

    /** Pick the stash list a slot belongs to: perSession slots are keyed by
     *  the session itself (invisible to group-mates), everything else by
     *  the sharing-group key. Caller holds STASH_LOCK. */
    private static List<Object> stashFor(BotSession session, Object groupKey,
                                         Slot s, boolean create) {
        Map<?, List<Object>> m = s.perSession ? SESSION_STASH : STASH;
        @SuppressWarnings("unchecked")
        Map<Object, List<Object>> map = (Map<Object, List<Object>>) m;
        Object k = s.perSession ? session : groupKey;
        List<Object> stash = map.get(k);
        if (stash == null && create) {
            stash = new ArrayList<>(SLOTS.size());
            map.put(k, stash);
        }
        return stash;
    }

    /**
     * Resolve the stash key a session joins and (re)register it there:
     * group name for grouped bots, the session itself for the human
     * pseudo-session (id 0) and ungrouped bots. On a group rename the
     * session migrates: it leaves the old key (last-out drops that stash
     * only if it had never been joined by anyone else) and joins the new
     * one, inheriting whatever the new group has already stashed.
     */
    private static Object joinKey(BotSession s) {
        String g = s.group;
        Object key = (s.id == 0 || g == null || g.isEmpty()) ? s : (Object) g;
        synchronized (STASH_LOCK) {
            Object prev = SESSION_KEY.get(s);
            if (key.equals(prev)) return key;
            if (prev != null) leaveKey(s, prev);
            SESSION_KEY.put(s, key);
            REFS.merge(key, 1, Integer::sum);
        }
        return key;
    }

    /** Remove s's membership from `key`; drop the group's stash when its last
     *  member leaves. Caller holds STASH_LOCK. */
    private static void leaveKey(BotSession s, Object key) {
        SESSION_KEY.remove(s);
        Integer n = REFS.merge(key, -1, Integer::sum);
        if (n == null || n <= 0) {
            REFS.remove(key);
            STASH.remove(key);
        }
    }

    /** Replace contents of a final mutable holder (Map/Collection) in place —
     *  final statics can't be re-seated, but their contents can be swapped. */
    @SuppressWarnings("unchecked")
    private static void repopulate(Slot s, Object val) throws Exception {
        Object cur = s.field.get(s.holder);
        if (cur == null) return;
        if (cur instanceof java.util.Map) {
            java.util.Map<Object,Object> m = (java.util.Map<Object,Object>) cur;
            m.clear();
            if (val instanceof java.util.Map) m.putAll((java.util.Map<?,?>) val);
        } else if (cur instanceof java.util.Collection) {
            java.util.Collection<Object> c = (java.util.Collection<Object>) cur;
            c.clear();
            if (val instanceof java.util.Collection) c.addAll((java.util.Collection<?>) val);
        } else if (cur instanceof java.util.Queue) {
            java.util.Queue<Object> q = (java.util.Queue<Object>) cur;
            q.clear();
            if (val instanceof java.util.Collection) q.addAll((java.util.Collection<?>) val);
        }
    }

    /** Deep-copy mutable containers so the stash isn't aliased to live state. */
    private static Object deepCopy(Object v) {
        if (v instanceof java.util.concurrent.CopyOnWriteArrayList)
            return new java.util.concurrent.CopyOnWriteArrayList<>((java.util.Collection<?>) v);
        if (v instanceof java.util.ArrayList)
            return new java.util.ArrayList<>((java.util.Collection<?>) v);
        if (v instanceof java.util.Map)
            return new java.util.concurrent.ConcurrentHashMap<>((java.util.Map<?, ?>) v);
        return v;
    }

    /** The session currently live in MythosScript's statics. */
    public static BotSession liveSession() { return liveSession; }

    /** Release a session's stash — call on disconnect/removal to prevent
     *  WorldClient/EntityPlayerSP references from leaking through the
     *  stashed slot values (maps/lists copied from the live statics).
     *  For a grouped bot the shared stash survives while other sessions
     *  are still joined to the same group key; only the last member's
     *  departure drops it. A session that never swapped (never mounted /
     *  never ran a scoped statics call) holds no membership — the group
     *  stash then persists untouched. */
    public static void clearSession(BotSession s) {
        if (s == null) return;
        synchronized (STASH_LOCK) {
            Object key = SESSION_KEY.get(s);
            if (key != null) leaveKey(s, key);
            SESSION_STASH.remove(s);
        }
        if (liveSession == s) liveSession = null;
    }

    /**
     * Run r with `target`'s statics installed, then restore whoever was live.
     * Used for scoped calls (mcp session=X) where we borrow a session's
     * statics for the duration of one operation without changing the mount.
     * Reentrant-safe: if target is already live, just runs r.
     */
    public static void withStatics(BotSession target, Runnable r) {
        ensureBuilt();
        if (SLOTS == null || SLOTS.isEmpty() || target == liveSession) {
            r.run();
            return;
        }
        BotSession prev = liveSession;
        swapTo(prev, target);
        try {
            r.run();
        } finally {
            swapTo(target, prev);
        }
    }

    /**
     * Push the live statics of `className` into every stash that will later
     * restore them — every group stash in STASH and every session stash in
     * SESSION_STASH. Called after a deliberate config save so a GUI edit made
     * while mounted on one session is not stomped back to an old value when
     * the user mounts a different session whose stash predates the save.
     * Deep-copies mutable containers like stashPut does.
     */
    public static void pushLiveToAllStashes(String className) {
        ensureBuilt();
        if (SLOTS == null || SLOTS.isEmpty()) return;
        synchronized (STASH_LOCK) {
            for (Slot s : SLOTS) {
                if (!s.label.startsWith(className + ".")) continue;
                try {
                    Object cur = s.field.get(s.holder);
                    Object val = deepCopy(cur);
                    if (s.perSession) {
                        for (List<Object> stash : SESSION_STASH.values()) {
                            while (stash.size() <= s.stashIndex) stash.add(null);
                            stash.set(s.stashIndex, val);
                        }
                    } else {
                        for (List<Object> stash : STASH.values()) {
                            while (stash.size() <= s.stashIndex) stash.add(null);
                            stash.set(s.stashIndex, val);
                        }
                    }
                } catch (Throwable t) {
                    NexusPlugin.LOGGER.warn("pushLive stash fail {}", s.label, t);
                }
            }
        }
    }
}
