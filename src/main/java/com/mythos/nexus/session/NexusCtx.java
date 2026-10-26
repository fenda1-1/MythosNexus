package com.mythos.nexus.session;

import com.mythos.nexus.core.NexusRefs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;

/**
 * Thread-local resolution of the per-session context for bytecode-rewritten
 * field access.
 *
 * The NexusTransformer rewrites GETFIELD / PUTFIELD on the 7 singleton
 * Minecraft fields (mc.player, mc.world, mc.playerController,
 * mc.renderViewEntity, mc.currentScreen, RenderGlobal.world,
 * GameSettings.thirdPersonView) inside tick-reachable classes into
 * INVOKESTATIC calls here.  Resolution is *thread based*:
 *
 *   - On the Client thread the real Minecraft fields are returned
 *     (whatever session is mounted).  Render/gui code keeps working with
 *     zero behavioural change and pays no ThreadLocal read beyond a single
 *     volatile load.
 *   - On a bot's dedicated tick thread the session bound to that thread is
 *     returned, so every read of `mc.player`/`mc.world` inside its tick sees
 *     ITS OWN entities instead of the mounted session's.
 *
 * A bot thread registers itself via {@link #bind(BotSession)} once, at thread
 * start; {@link #unbind()} clears it (also called between runs so a pooled
 * thread never leaks the previous session).
 *
 * PUTFIELD sites are rewritten too — e.g. SPacketJoinGame's `mc.player = x`
 * inside a bot dispatch writes back to ctx.player, never the mounted field.
 */
public final class NexusCtx {

    private NexusCtx() {}

    /** The bot session bound to the *calling thread*, or null on the client
     *  thread / any unbound thread.  Read on every resolved access — volatile
     *  is unnecessary (ThreadLocal read is enough). */
    private static final ThreadLocal<BotSession> LOCAL = new ThreadLocal<>();

    /** Bind a session to this thread. Call at bot-tick-thread start. */
    public static void bind(BotSession s) { LOCAL.set(s); }

    /** Clear the binding — used between dispatched tasks on pooled threads. */
    public static void unbind() { LOCAL.remove(); }

    /** The session bound to this thread, or null on the client thread. */
    public static BotSession current() { return LOCAL.get(); }

    /** True when the calling thread is a dedicated bot thread. */
    public static boolean onBotThread() { return LOCAL.get() != null; }

    // GETFIELD accessors.  Each returns the thread-bound session's field when
    // one is bound, otherwise the real Minecraft field.  Minecraft instance
    // is passed in by the rewritten call site (receiver on stack); it is the
    // same singleton either way, so for the bound branch we ignore it and
    // read ctx's own field.

    /** Rewritten GETFIELD Minecraft.player (field_71439_g). */
    public static EntityPlayerSP getPlayer(Minecraft mc) {
        BotSession s = LOCAL.get();
        return s != null ? s.player : mc.player;
    }

    /** Rewritten GETFIELD Minecraft.world (field_71441_e). */
    public static WorldClient getWorld(Minecraft mc) {
        BotSession s = LOCAL.get();
        return s != null ? s.world : mc.world;
    }

    /** Rewritten GETFIELD Minecraft.playerController (field_71442_b). */
    public static PlayerControllerMP getPlayerController(Minecraft mc) {
        BotSession s = LOCAL.get();
        return s != null ? s.playerController : mc.playerController;
    }

    /** Rewritten GETFIELD Minecraft.renderViewEntity (field_175622_Z).
     *  For a bot we return its own player — the renderViewEntity only matters
     *  to the mounted session, and a background bot's reads
     *  (WorldClient.getRenderViewEntity for distance/particle math) must see
     *  its own position, not the mounted session's camera entity. */
    /** renderViewEntity is private in Minecraft AND its accessor methods were
     *  rewritten to route back through this class — calling them recurses to
     *  StackOverflow. Read/write the field reflectively, resolved once. */
    private static java.lang.reflect.Field RVE;
    private static java.lang.reflect.Field rveField() {
        java.lang.reflect.Field f = RVE;
        if (f == null) {
            try {
                Class<?> c = net.minecraft.client.Minecraft.class;
                try { f = c.getDeclaredField("renderViewEntity"); }
                catch (NoSuchFieldException e) {
                    try { f = c.getDeclaredField("field_175622_Z"); }
                    catch (NoSuchFieldException e2) { f = c.getDeclaredField("ad"); }
                }
                f.setAccessible(true);
            } catch (Throwable t) {
                throw new IllegalStateException("Minecraft.renderViewEntity", t);
            }
            RVE = f;
        }
        return f;
    }

    private static java.lang.reflect.Field PLAYER;
    private static java.lang.reflect.Field playerField() {
        java.lang.reflect.Field f = PLAYER;
        if (f == null) {
            try {
                Class<?> c = net.minecraft.client.Minecraft.class;
                try { f = c.getDeclaredField("player"); }
                catch (NoSuchFieldException e) {
                    try { f = c.getDeclaredField("field_71439_g"); }
                    catch (NoSuchFieldException e2) { f = c.getDeclaredField("h"); }
                }
                f.setAccessible(true);
            } catch (Throwable t) {
                throw new IllegalStateException("Minecraft.player", t);
            }
            PLAYER = f;
        }
        return f;
    }

    public static Entity getRenderViewEntity(Minecraft mc) {
        BotSession s = LOCAL.get();
        if (s != null) return s.player;
        try {
            Entity rve = (Entity) rveField().get(mc);
            // mounted==none leaves the real renderViewEntity null while
            // mc.world/mc.player still point at a live session — the camera
            // deref in updateCameraAndRender then NPEs and the whole frame
            // (including drawScreen) is swallowed → permanent blue UI. Fall
            // back to the session player as the camera entity; it is in the
            // same world, so the frame renders and the GUI paints.
            if (rve == null) rve = (Entity) playerField().get(mc);
            return rve;
        } catch (Throwable t) {
            NexusRefs.warnOnce("ctx:getRenderViewEntity", t,
                    "NexusCtx.getRenderViewEntity failed, falling back to Minecraft.player");
            try { return (Entity) playerField().get(mc); } catch (Throwable t2) { return null; }
        }
    }

    /** Rewritten GETFIELD Minecraft.currentScreen (field_71462_r).
     *  Must return GuiScreen, not Object — rewritten call sites feed the
     *  result into casts/invocations typed to GuiScreen, and an Object
     *  return leaves 'Bad type on operand stack' (VerifyError) at runtime. */
    public static net.minecraft.client.gui.GuiScreen getCurrentScreen(Minecraft mc) {
        BotSession s = LOCAL.get();
        // Bot threads read the session's own saved screen; real field
        // otherwise (mounted GUI must never be touched by a background bot).
        return s != null ? s.savedScreen : mc.currentScreen;
    }

    /** Rewritten PUTFIELD Minecraft.player. */
    public static void setPlayer(Minecraft mc, EntityPlayerSP v) {
        BotSession s = LOCAL.get();
        if (s != null) s.player = v; else mc.player = v;
    }

    /** Rewritten PUTFIELD Minecraft.world. */
    public static void setWorld(Minecraft mc, WorldClient v) {
        BotSession s = LOCAL.get();
        if (s != null) s.world = v; else mc.world = v;
    }

    /** Rewritten PUTFIELD Minecraft.playerController. */
    public static void setPlayerController(Minecraft mc, PlayerControllerMP v) {
        BotSession s = LOCAL.get();
        if (s != null) s.playerController = v; else mc.playerController = v;
    }

    /** Rewritten PUTFIELD Minecraft.renderViewEntity — bot writes update its
     *  own stored view entity is meaningless (it's the player); store back to
     *  player which is the same thing a bot's code expects. */
    public static void setRenderViewEntity(Minecraft mc, Entity v) {
        BotSession s = LOCAL.get();
        if (s != null) {
            // renderViewEntity for a bot is always its player; a write means
            // "adopt this entity as the camera/player". Store to player when
            // the type fits so future reads stay consistent.
            if (v instanceof EntityPlayerSP) s.player = (EntityPlayerSP) v;
        } else {
            // Field write, not mc.setRenderViewEntity — that method's body
            // was rewritten to call back into this accessor (recursion).
            try { rveField().set(mc, v); }
            catch (Throwable t) {
                NexusRefs.warnOnce("ctx:setRenderViewEntity", t,
                        "NexusCtx.setRenderViewEntity failed, write dropped");
            }
        }
    }

    /** Rewritten PUTFIELD Minecraft.currentScreen. */
    public static void setCurrentScreen(Minecraft mc, net.minecraft.client.gui.GuiScreen v) {
        BotSession s = LOCAL.get();
        if (s != null) s.savedScreen = v;
        else mc.currentScreen = v;
    }

    //
    // RenderGlobal.world is private — accessed reflectively (same approach as
    // SessionContext). The field handle is resolved lazily once.

    private static java.lang.reflect.Field RG_WORLD;
    private static java.lang.reflect.Field rgWorldField() {
        java.lang.reflect.Field f = RG_WORLD;
        if (f == null) {
            try {
                Class<?> c = net.minecraft.client.renderer.RenderGlobal.class;
                try { f = c.getDeclaredField("world"); }
                catch (NoSuchFieldException e) { f = c.getDeclaredField("field_72769_h"); }
                f.setAccessible(true);
            } catch (Throwable t) {
                throw new IllegalStateException("RenderGlobal.world", t);
            }
            RG_WORLD = f;
        }
        return f;
    }

    /** Rewritten GETFIELD RenderGlobal.world (field_72769_h).
     *  Receiver is the RenderGlobal instance (the single shared one). For a
     *  bound bot we return its world; a bg bot must never be pointed at the
     *  real RenderGlobal's world — it owns no render resources there. */
    public static WorldClient getRenderGlobalWorld(Object renderGlobal) {
        BotSession s = LOCAL.get();
        if (s != null) return s.world;
        try { return (WorldClient) rgWorldField().get(renderGlobal); }
        catch (Throwable t) {
            NexusRefs.warnOnce("ctx:getRenderGlobalWorld", t,
                    "NexusCtx.getRenderGlobalWorld failed, returning null");
            return null;
        }
    }

    /** Rewritten PUTFIELD RenderGlobal.world — a bg bot must never repoint the
     *  shared RenderGlobal. Writes on a bot thread are dropped; the mounted
     *  client thread writes through normally. */
    public static void setRenderGlobalWorld(Object renderGlobal, WorldClient v) {
        BotSession s = LOCAL.get();
        if (s == null) {
            try { rgWorldField().set(renderGlobal, v); }
            catch (Throwable t) {
                NexusRefs.warnOnce("ctx:setRenderGlobalWorld", t,
                        "NexusCtx.setRenderGlobalWorld failed, write dropped");
            }
        }
        // else: drop — background bots never mutate the shared RenderGlobal.
    }

    /** Rewritten GETFIELD GameSettings.thirdPersonView (field_74320_O). */
    public static int getThirdPersonView(Object gameSettings) {
        BotSession s = LOCAL.get();
        return s != null ? s.savedThirdPersonView
                : ((net.minecraft.client.settings.GameSettings) gameSettings).thirdPersonView;
    }

    /** Rewritten PUTFIELD GameSettings.thirdPersonView. */
    public static void setThirdPersonView(Object gameSettings, int v) {
        BotSession s = LOCAL.get();
        if (s != null) s.savedThirdPersonView = v;
        else ((net.minecraft.client.settings.GameSettings) gameSettings).thirdPersonView = v;
    }
}
