package com.mythos.nexus.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one table of MythosScript class names, plus the reflection helpers that
 * name the failing member.
 *
 * Why strings and not imports: Nexus is an IFMLLoadingPlugin, so
 * {@link NexusPlugin} / {@link NexusTransformer} run while MythosScript.jar is
 * still absent from the LaunchClassLoader classpath — importing any
 * com.mythos.mythosScriptMod type would NoClassDefFoundError at coremod load.
 * Every cross-mod reference therefore has to stay a string; keeping them here
 * means a package rename is a one-file edit instead of a grep across the tree.
 *
 * Coremod safety: the String constants below are compile-time constant
 * expressions, so javac inlines them into the referencing class — using one
 * from the transformer loads nothing. The helpers are plain
 * java.lang.reflect + {@link NexusLog}, both of which are safe before Forge
 * finishes booting.
 */
public final class NexusRefs {

    private NexusRefs() {}

    public static final String MYTHOS = "com.mythos.mythosScriptMod";

    // path/ — sequence scripting bridge (MythosScripts, BotStatusOverlay,
    // NexusStatusPanel all drive the same handful of classes).
    public static final String PATH_SEQUENCE_MANAGER = MYTHOS + ".path.PathSequenceManager";
    public static final String PATH_SEQUENCE       = PATH_SEQUENCE_MANAGER + "$PathSequence";
    public static final String PATH_STEP           = PATH_SEQUENCE_MANAGER + "$PathStep";
    public static final String ACTION_DATA         = PATH_SEQUENCE_MANAGER + "$ActionData";
    public static final String PATH_SEQUENCE_EVENT_LISTENER =
            MYTHOS + ".path.PathSequenceEventListener";
    public static final String PROGRESS_SNAPSHOT =
            PATH_SEQUENCE_EVENT_LISTENER + "$ProgressSnapshot";

    // mcp/ — the JSON-RPC service and its per-session world view.
    public static final String NEXUS_SESSIONS  = MYTHOS + ".mcp.NexusSessions";
    public static final String MCP_GAME_SERVICE = MYTHOS + ".mcp.McpGameService";
    public static final String MCP_WORLD_VIEW  = MYTHOS + ".mcp.McpWorldView";

    // system/
    public static final String SIMULATED_KEY_INPUT_MANAGER =
            MYTHOS + ".system.SimulatedKeyInputManager";
    public static final String PROFILE_MANAGER = MYTHOS + ".system.ProfileManager";

    public static final String BARITONE_API =
            MYTHOS + ".shadowbaritone.api.BaritoneAPI";

    // handlers/ + otherfeatures/ — MythosStateSwap mirrors their statics.
    public static final String KILL_AURA_HANDLER        = MYTHOS + ".handlers.KillAuraHandler";
    public static final String FLY_HANDLER              = MYTHOS + ".handlers.FlyHandler";
    public static final String AUTO_EAT_HANDLER         = MYTHOS + ".handlers.AutoEatHandler";
    public static final String AUTO_FISHING_HANDLER     = MYTHOS + ".handlers.AutoFishingHandler";
    public static final String AUTO_PICKUP_HANDLER      = MYTHOS + ".handlers.AutoPickupHandler";
    public static final String CONDITIONAL_EXECUTION_HANDLER =
            MYTHOS + ".handlers.ConditionalExecutionHandler";
    public static final String AUTO_ESCAPE_HANDLER      = MYTHOS + ".handlers.AutoEscapeHandler";
    public static final String AUTO_FOLLOW_HANDLER      = MYTHOS + ".handlers.AutoFollowHandler";
    public static final String AUTO_USE_ITEM_HANDLER    = MYTHOS + ".handlers.AutoUseItemHandler";
    public static final String BARITONE_PARKOUR_HELPER  = MYTHOS + ".config.BaritoneParkourSettingsHelper";
    public static final String EMBEDDED_NAVIGATION_HANDLER =
            MYTHOS + ".handlers.EmbeddedNavigationHandler";
    public static final String SPEED_HANDLER =
            MYTHOS + ".otherfeatures.handler.movement.SpeedHandler";
    public static final String MOVEMENT_FEATURE_MANAGER =
            MYTHOS + ".otherfeatures.handler.movement.MovementFeatureManager";
    public static final String MISC_FEATURE_MANAGER =
            MYTHOS + ".otherfeatures.handler.misc.MiscFeatureManager";
    public static final String RENDER_FEATURE_MANAGER =
            MYTHOS + ".otherfeatures.handler.render.RenderFeatureManager";
    public static final String WORLD_FEATURE_MANAGER =
            MYTHOS + ".otherfeatures.handler.world.WorldFeatureManager";
    public static final String ITEM_FEATURE_MANAGER =
            MYTHOS + ".otherfeatures.handler.item.ItemFeatureManager";
    public static final String BLOCK_FEATURE_MANAGER =
            MYTHOS + ".otherfeatures.handler.block.BlockFeatureManager";

    // ---------------------------------------------------------------- lookup

    /** Resolved classes, keyed by name.  A hit skips the classloader walk;
     *  misses are deliberately NOT cached — a class that is absent during the
     *  coremod phase becomes visible once FML adds the mod jars. */
    private static final Map<String, Class<?>> CLASSES = new ConcurrentHashMap<>();

    /**
     * Class.forName with a hit cache. Throws the same ClassNotFoundException
     * the call sites already catch, so control flow is unchanged; the warning
     * is emitted once per class name instead of never.
     */
    public static Class<?> cls(String fqcn) throws ClassNotFoundException {
        Class<?> c = CLASSES.get(fqcn);
        if (c != null) return c;
        try {
            c = Class.forName(fqcn);
        } catch (ClassNotFoundException e) {
            warnOnce("class:" + fqcn, "MythosScript class missing: %s", fqcn);
            throw e;
        }
        CLASSES.put(fqcn, c);
        return c;
    }

    /** Class.getMethod, but a miss names class + member + parameter types
     *  instead of the bare method name. */
    public static Method method(Class<?> c, String name, Class<?>... params)
            throws NoSuchMethodException {
        try {
            return c.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodException("no method " + sig(c, name, params));
        }
    }

    /** Class.getDeclaredField, same diagnostic contract as {@link #method}. */
    public static Field field(Class<?> c, String name) throws NoSuchFieldException {
        try {
            return c.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new NoSuchFieldException("no field " + c.getName() + "#" + name);
        }
    }

    /** "com.x.Y#member(java.lang.String,int)" — used in every failure message. */
    public static String sig(Class<?> c, String name, Class<?>... params) {
        StringBuilder sb = new StringBuilder(c == null ? "?" : c.getName());
        sb.append('#').append(name).append('(');
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(params[i] == null ? "?" : params[i].getName());
        }
        return sb.append(')').toString();
    }

    // ------------------------------------------------------------- warn once

    /** Keys already warned about. Reflection failures sit on per-tick and
     *  per-frame paths, so an unconditional log would bury error.log. */
    private static final Map<String, Boolean> WARNED = new ConcurrentHashMap<>();

    /** Emit one warning per key, through NexusLog (which mirrors to log4j's
     *  warn channel when file logging is off). */
    public static void warnOnce(String key, String fmt, Object... args) {
        if (WARNED.putIfAbsent(key, Boolean.TRUE) != null) return;
        NexusLog.error(fmt, args);
    }

    /** Same, with the cause attached. */
    public static void warnOnce(String key, Throwable t, String fmt, Object... args) {
        if (WARNED.putIfAbsent(key, Boolean.TRUE) != null) return;
        NexusLog.error(fmt, t, args);
    }

    /** True the first time it is called for this key — for call sites that
     *  want to log their own message rather than go through warnOnce. */
    public static boolean firstTime(String key) {
        return WARNED.putIfAbsent(key, Boolean.TRUE) == null;
    }
}
