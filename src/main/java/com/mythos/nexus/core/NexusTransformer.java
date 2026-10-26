package com.mythos.nexus.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Bytecode hooks into net.minecraft.network.NetworkManager:
 *
 *  1. channelRead0(ctx, Packet) — before vanilla dispatch, ask NexusHooks
 *     whether this connection belongs to a bot; if so route the packet
 *     through the owning session's context and RETURN (skip vanilla).
 *
 *  2. setConnectionState(PLAY) — after vanilla sets the handler, ask
 *     NexusHooks to swap in the per-bot handler (bot connections never hit
 *     the vanilla NetHandlerLoginClient path, so we do not need this in v0.1;
 *     kept as a stub for future use).
 *
 * Matching is descriptor-first (stable across srg/mcp) plus a name set.
 */
public class NexusTransformer implements IClassTransformer {

    private static final String NM_CLASS   = "net.minecraft.network.NetworkManager";
    private static final String NHPC_CLASS = "net.minecraft.client.network.NetHandlerPlayClient";
    private static final String MC_CLASS   = "net.minecraft.client.Minecraft";
    private static final String ER_CLASS   = "net.minecraft.client.renderer.EntityRenderer";
    // Constant expression: javac inlines the literal, so referencing NexusRefs
    // from the coremod never loads that class.
    private static final String MGS_CLASS  = NexusRefs.MCP_GAME_SERVICE;
    private static final String PM_CLASS   = "net.minecraft.client.particle.ParticleManager";
    private static final String WC_CLASS   = "net.minecraft.client.multiplayer.WorldClient";
    private static final String LSR_CLASS  = "net.minecraft.client.LoadingScreenRenderer";
    private static final String TER_CLASS  = "net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher";
    private static final String SH_CLASS   = "net.minecraft.client.audio.SoundHandler";
    private static final String GIC_CLASS  = "net.minecraft.client.gui.GuiIngame";
    private static final String FB_CLASS   = "net.minecraft.client.shader.Framebuffer";
    private static final String TESS_CLASS = "net.minecraft.client.renderer.Tessellator";
    private static final String WVBU_CLASS = "net.minecraft.client.renderer.WorldVertexBufferUploader";
    private static final String EPSP_CLASS = "net.minecraft.client.entity.EntityPlayerSP";
    private static final String RM_CLASS   = "net.minecraft.client.renderer.entity.RenderManager";
    private static final String HOOKS = "com/mythos/nexus/core/NexusHooks";
    private static final String HOOKS_INTERNAL = HOOKS.replace('.', '/');

    static {
        // Force-load every nested/anonymous visitor class NOW, on the main
        // thread, before any transform() runs. Anonymous ASM visitors are
        // lazily loaded on first `new`; when that first `new` happens inside a
        // transform() that is itself mid-defineClass, the JVM flags the class
        // with ClassCircularityError and it stays broken forever. Preloading
        // them here makes each nested class resolve on the coremod's own load,
        // outside any transform call stack.
        try {
            for (Class<?> c : NexusTransformer.class.getDeclaredClasses()) {
                Class.forName(c.getName(), true, NexusTransformer.class.getClassLoader());
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || transformedName == null) return basicClass;
        // Never transform our own package. Loading an anonymous MethodVisitor
        // (the $N$1 classes below) inside a transform triggers a class-load of
        // that anonymous class, which calls back into transform() — if our own
        // classes were eligible the JVM would report ClassCircularityError
        // (com/mythos/nexus/core/NexusTransformer$10$1) and silently skip the
        // Tessellator redirect for 38 classes at startup. Skipping the package
        // both fixes that and avoids pointless work — we never need to rewrite
        // ourselves.
        if (transformedName.startsWith("com.mythos.nexus.")
                || name.startsWith("com/mythos/nexus/")) {
            return basicClass;
        }
        try {
            // transformedName is the MCP/deobf name when FML has already
            // remapped the class identity, but METHOD names inside the
            // bytecode may still be obfuscated at our transform point (we
            // run before FMLDeobfTweaker). Match on all three spellings.
            if (NM_CLASS.equals(transformedName) || "gw".equals(name)) {
                return pipeGetInstance(transformNetworkManager(basicClass));
            }
            if (NHPC_CLASS.equals(transformedName) || "brz".equals(name)) {
                return pipeGetInstance(transformNetHandlerPlayClient(basicClass));
            }
            if (MC_CLASS.equals(transformedName) || "bib".equals(name)) {
                return pipeGetInstance(transformMinecraft(basicClass));
            }
            if (ER_CLASS.equals(transformedName) || "buq".equals(name)) {
                NexusPlugin.LOGGER.info("[xform] EntityRenderer name={} transformedName={}", name, transformedName);
                return pipeGetInstance(transformEntityRenderer(basicClass));
            }
            // RenderManager.renderEntity 头部守卫：保证 renderViewEntity 非 null。
            if (RM_CLASS.equals(transformedName) || "bzf".equals(name)) {
                return pipeGetInstance(transformRenderManager(basicClass));
            }
            if (PM_CLASS.equals(transformedName) || "btg".equals(name)) {
                return pipeGetInstance(transformParticleManager(basicClass));
            }
            if (FB_CLASS.equals(transformedName) || "bvd".equals(name)) {
                return pipeGetInstance(transformFramebuffer(basicClass));
            }
            // WorldVertexBufferUploader.draw(BufferBuilder) is the single
            // funnel through which every Tessellator draw issues gl*Pointer +
            // glDrawArrays. Head-guard it so a bound bot thread resets its
            // private buffer and returns before touching GL (no context).
            if (WVBU_CLASS.equals(transformedName) || "bul".equals(name)) {
                return transformVertexUpload(basicClass);
            }
            // GL / shared-singleton no-op guards for background bot threads.
            // loadWorld → loadingScreen.* reaches glClear with no GL context on
            // a bot thread (killed all 30 joins in launch8); TER.setWorld and
            // SoundHandler.stopSounds mutate shared singletons that the
            // mounted session renders/reads → cross-bot corruption. Head-guard
            // each method so a bound bot thread returns before touching GL or
            // the shared object.
            if (LSR_CLASS.equals(transformedName) || "bie".equals(name)) {
                return pipeGetInstance(transformBotNoop(basicClass, LSR_METHODS, "LoadingScreenRenderer"));
            }
            if (TER_CLASS.equals(transformedName) || "bwx".equals(name)) {
                return pipeGetInstance(transformBotNoop(basicClass, TER_METHODS, "TileEntityRendererDispatcher"));
            }
            if (SH_CLASS.equals(transformedName) || "cho".equals(name)) {
                return pipeGetInstance(transformBotNoop(basicClass, SH_METHODS, "SoundHandler"));
            }
            // GuiIngame.addChatMessage → NormalChatListener → CustomGuiNewChat
            // mutates the shared chatLines ArrayList — 30 bot threads racing it
            // throws ConcurrentModificationException (launch _accept.log). Chat
            // is pure visual output; a background bot has nothing to print.
            if (GIC_CLASS.equals(transformedName) || "biq".equals(name)) {
                return pipeGetInstance(transformBotNoop(basicClass, GIC_METHODS, "GuiIngame"));
            }
            if (WC_CLASS.equals(transformedName)) {
                return pipeGetInstance(transformWorldClient(basicClass, transformedName));
            }
            if (MGS_CLASS.equals(transformedName)) {
                return pipeGetInstance(transformMcpGameService(basicClass));
            }
            // EntityPlayerSP.isCurrentViewEntity does
            // `mc.getRenderViewEntity() == this` — a VIRTUAL call that fieldCtx
            // cannot reach, so on a bot thread it resolves to the *mounted*
            // session's camera and always returns false. Then
            // updateEntityActionState never copies movementInput into
            // moveStrafing/moveForward/isJumping and Baritone's pressed
            // MOVE_FORWARD is dropped: "attacks but never chases" in
            // background sessions. Rewriting the virtual call to
            // NexusCtx.getRenderViewEntity makes the bot's own tick see its
            // session player as the view entity. FIELD_CTX still applies for
            // the class's other field accesses.
            if (EPSP_CLASS.equals(transformedName) || "bud".equals(name)) {
                byte[] out = transformEntityPlayerSP(basicClass);
                return pipeGetInstance(out != null ? out : basicClass);
            }
            if (ENTITY_CLASS.equals(transformedName) || ENTITY_CLASS.equals(name)) {
                return pipeGetInstance(transformEntity(basicClass, true));
            }
            // 子类如果覆写了 canBeCollidedWith，父类上的注入就不会生效，所以
            // 这条链上定义过它的类都要打一遍。
            if (ENTITY_LIVING_CLASS.equals(transformedName) || ENTITY_LIVING_CLASS.equals(name)
                    || ENTITY_PLAYER_CLASS.equals(transformedName)
                    || ENTITY_PLAYER_CLASS.equals(name)) {
                return pipeGetInstance(transformEntity(basicClass, false));
            }
            // Tick-reachable field-context rewrite: every class that a bot's
            // dedicated tick thread can reach and that reads/writes the 7
            // per-session mc fields gets its GETFIELD/PUTFIELD swapped for
            // INVOKESTATIC NexusCtx.<accessor>.  Resolution is thread-based —
            // on the client thread the accessor returns the real field, on a
            // bound bot thread the session's own object.
            if (FIELD_CTX_CLASSES.contains(transformedName)
                    || FIELD_CTX_CLASSES.contains(name)) {
                byte[] out = transformFieldContext(basicClass, transformedName);
                return pipeGetInstance(out != null ? out : basicClass);
            }
            // Universal Tessellator.getInstance() redirect: every INVOKESTATIC
            // getInstance() on the singleton is rewritten to
            // NexusHooks.tessellatorForThread(), which hands a bound bot thread
            // its own ThreadLocal Tessellator instead of the process-wide
            // buffer. This removes the concurrent "Already building!" begin()
            // race. It is applied as a post-pass via pipeGetInstance() on every
            // dedicated transform above (ParticleManager and friends are
            // bot-reachable through packet handlers) AND as this fallback for
            // every other class. Excluded: Tessellator itself (rewriting inside
            // it would recurse) — NexusHooks is guarded inside
            // redirectTessellatorGetInstance for the same reason.
            if (!TESS_CLASS.equals(transformedName) && !"bve".equals(name)) {
                byte[] out = redirectTessellatorGetInstance(basicClass);
                if (out != null) return out;
            }
        } catch (Throwable t) {
            NexusPlugin.LOGGER.error("NexusTransformer failed on {}", transformedName, t);
        }
        return basicClass;
    }

    //
    // The 7 singleton fields SessionContext swaps in/out today only work
    // because the whole tick ran serially on the client thread. With per-bot
    // tick THREADS the swap can't work — each thread would fight over the
    // same mc.player field. Instead we rewrite every bytecode access to those
    // fields inside classes a bot tick can reach, routing it through
    // NexusCtx which resolves the thread-bound session.
    //
    // Only classes reachable during a bot's world tick / packet dispatch /
    // entity update are listed. Render + GUI classes (EntityRenderer,
    // GuiIngame, RenderGlobal, ItemRenderer, containers) stay untouched —
    // they only ever run on the client thread where the real field is
    // already correct, and rewriting their hot loops would add ThreadLocal
    // cost for no benefit.

    private static final String CTX_INTERNAL =
            "com/mythos/nexus/session/NexusCtx";

    /** Static entry point for the no-push guard injected into Entity. */
    private static final String SYNC_INTERNAL =
            "com/mythos/nexus/session/SyncHooks";

    private static final String ENTITY_CLASS = "net.minecraft.entity.Entity";
    private static final String ENTITY_LIVING_CLASS = "net.minecraft.entity.EntityLivingBase";
    private static final String ENTITY_PLAYER_CLASS = "net.minecraft.entity.player.EntityPlayer";

    /**
     * Classes whose GETFIELD/PUTFIELD on the 7 fields is rewritten.
     * Internal names (with package). Minecraft itself is NOT rewritten —
     * it must keep storing/returning the real mounted-session fields.
     *
     * Included: the whole entity tick path (WorldClient, EntityPlayerSP,
     * Entity, EntityLivingBase, EntityPlayer, PlayerControllerMP, entity
     * subclasses that override onUpdate or read mc), the network dispatch
     * classes (NetHandlerPlayClient and its packet handlers — they mutate
     * mc.world/mc.player via loadWorld + setPlayer spawn), chunk/world
     * provider, and World/WorldProvider/WorldServer-adjacent helpers.
     */
    private static final Set<String> FIELD_CTX_CLASSES = buildFieldCtxSet();

    private static Set<String> buildFieldCtxSet() {
        // Manifest-driven allowlist (see _nexus_read/FIELD_REWRITE_MANIFEST.md).
        // The audit found the bot-tick path reads mc.player/mc.world/
        // mc.playerController/mc.currentScreen in only a handful of classes —
        // everything else that touches those fields is render-only and stays
        // on the client thread. We rewrite the tick set PLUS the SPacket
        // handlers (they execute inside the bot's packet pump on its own
        // thread and can reach the same fields) for safety; a render class
        // is never in this list, so it always resolves to the real field.
        String[] names = {
            "net.minecraft.client.multiplayer.WorldClient",
            "net.minecraft.client.multiplayer.PlayerControllerMP",
            "net.minecraft.client.entity.EntityPlayerSP",
            "net.minecraft.client.network.NetHandlerPlayClient",
            // edge: read world/playerController for null/type checks in tick
            "net.minecraft.client.tutorial.Tutorial",
            "net.minecraft.server.integrated.IntegratedServer",
            "net.minecraft.client.entity.EntityOtherPlayerMP",
            "net.minecraft.entity.player.EntityPlayer",
            "net.minecraft.entity.Entity",
            "net.minecraft.entity.EntityLivingBase",
            "net.minecraft.entity.EntityLiving",
            "net.minecraft.world.World",
            "net.minecraft.world.chunk.Chunk",
            "net.minecraft.client.multiplayer.ChunkProviderClient",
            "net.minecraft.util.MovementInput",
            "net.minecraft.util.MovementInputFromOptions",
            //      can dispatch. Most touch no mc field at all (own fields),
            //      but rewriting them costs nothing and covers the rare
            //      handler that does read mc.player/mc.world mid-dispatch. ----
            "net.minecraft.network.play.server.SPacketPlayerPosLook",
            "net.minecraft.network.play.server.SPacketJoinGame",
            "net.minecraft.network.play.server.SPacketRespawn",
            "net.minecraft.network.play.server.SPacketEntity",
            "net.minecraft.network.play.server.SPacketEntityTeleport",
            "net.minecraft.network.play.server.SPacketSpawnPlayer",
            "net.minecraft.network.play.server.SPacketEntityVelocity",
            "net.minecraft.network.play.server.SPacketEntityMetadata",
            "net.minecraft.network.play.server.SPacketEntityEquipment",
            "net.minecraft.network.play.server.SPacketEntityStatus",
            "net.minecraft.network.play.server.SPacketEntityEffect",
            "net.minecraft.network.play.server.SPacketEntityProperties",
            "net.minecraft.network.play.server.SPacketEntityAttach",
            "net.minecraft.network.play.server.SPacketSetPassengers",
            "net.minecraft.network.play.server.SPacketMoveVehicle",
            "net.minecraft.network.play.server.SPacketSpawnMob",
            "net.minecraft.network.play.server.SPacketSpawnObject",
            "net.minecraft.network.play.server.SPacketSpawnExperienceOrb",
            "net.minecraft.network.play.server.SPacketSpawnPainting",
            "net.minecraft.network.play.server.SPacketDestroyEntities",
            "net.minecraft.network.play.server.SPacketRemoveEntityEffect",
            "net.minecraft.network.play.server.SPacketChangeGameState",
            "net.minecraft.network.play.server.SPacketPlayerAbilities",
            "net.minecraft.network.play.server.SPacketHeldItemChange",
            "net.minecraft.network.play.server.SPacketPlayerListItem",
            "net.minecraft.network.play.server.SPacketTeams",
            "net.minecraft.network.play.server.SPacketScoreboardObjective",
            "net.minecraft.network.play.server.SPacketUpdateScore",
            "net.minecraft.network.play.server.SPacketDisplayObjective",
            "net.minecraft.network.play.server.SPacketChunkData",
            "net.minecraft.network.play.server.SPacketBlockChange",
            "net.minecraft.network.play.server.SPacketMultiBlockChange",
            "net.minecraft.network.play.server.SPacketBlockAction",
            "net.minecraft.network.play.server.SPacketBlockBreakAnim",
            "net.minecraft.network.play.server.SPacketUpdateTileEntity",
            "net.minecraft.network.play.server.SPacketUnloadChunk",
            "net.minecraft.network.play.server.SPacketTimeUpdate",
            "net.minecraft.network.play.server.SPacketChat",
            "net.minecraft.network.play.server.SPacketTitle",
            "net.minecraft.network.play.server.SPacketSignEditorOpen",
            "net.minecraft.network.play.server.SPacketOpenWindow",
            "net.minecraft.network.play.server.SPacketCloseWindow",
            "net.minecraft.network.play.server.SPacketSetSlot",
            "net.minecraft.network.play.server.SPacketWindowItems",
            "net.minecraft.network.play.server.SPacketWindowProperty",
            "net.minecraft.network.play.server.SPacketConfirmTransaction",
            "net.minecraft.network.play.server.SPacketExplosion",
            "net.minecraft.network.play.server.SPacketEffect",
            "net.minecraft.network.play.server.SPacketSoundEffect",
            "net.minecraft.network.play.server.SPacketParticles",
            "net.minecraft.network.play.server.SPacketCombatEvent",
            "net.minecraft.network.play.server.SPacketUpdateHealth",
            "net.minecraft.network.play.server.SPacketUpdateExperience",
            "net.minecraft.network.play.server.SPacketSetExperience",
            "net.minecraft.network.play.server.SPacketEntityHeadLook",
            "net.minecraft.network.play.server.SPacketSpawnGlobalEntity",
            "net.minecraft.network.play.server.SPacketCamera",
            "net.minecraft.network.play.server.SPacketUseBed",
            "net.minecraft.network.play.server.SPacketAnimation",
            "net.minecraft.network.play.server.SPacketCollectItem",
            "net.minecraft.network.play.server.SPacketEntityLeash",
            "net.minecraft.network.play.server.SPacketEntityPosition",
            "net.minecraft.network.play.server.SPacketEntityPositionRotation",
            "net.minecraft.network.play.server.SPacketEntityRotation",
            "net.minecraft.network.play.server.SPacketUpdateBossInfo",
            "net.minecraft.network.play.server.SPacketMaps",
            "net.minecraft.network.play.server.SPacketStatistics",
            "net.minecraft.network.play.server.SPacketTabComplete",
            "net.minecraft.network.play.server.SPacketServerDifficulty",
            "net.minecraft.network.play.server.SPacketRecipeBook",
            "net.minecraft.network.play.server.SPacketUnlockRecipes",
            "net.minecraft.network.play.server.SPacketCooldown",
            "net.minecraft.network.play.server.SPacketCustomPayload",
            "net.minecraft.network.play.server.SPacketResourcePackSend",
            "net.minecraft.network.play.server.SPacketDisconnect",
            "net.minecraft.network.play.server.SPacketKeepAlive",
            "net.minecraft.network.play.server.SPacketSelectAdvancementsTab",
            "net.minecraft.network.play.server.SPacketAdvancementInfo",
            "net.minecraft.network.play.server.SPacketPlaceGhostRecipe",
            "net.minecraft.network.play.server.SPacketUpdateRecipes",
            "net.minecraft.network.play.server.SPacketPlayerListHeaderFooter",
            "net.minecraft.network.play.server.SPacketUpdateSelectedAdvancements",
            NexusRefs.MCP_WORLD_VIEW,
            NexusRefs.MCP_GAME_SERVICE,
            NexusRefs.SIMULATED_KEY_INPUT_MANAGER,
            //      ctor runs on the bot's packet thread (inside displayGUIChest,
            //      BEFORE displayGuiScreen's guiGuard can stash). Without this
            //      a background bot's chest GUI overwrites the MOUNTED
            //      player's openContainer and its own stays inventoryContainer
            //      (windowId 0) — bulk item moves then click a dead container.
            "net.minecraft.client.gui.inventory.GuiContainer",
            "net.minecraft.client.gui.inventory.GuiChest",
        };
        Set<String> s = new HashSet<>();
        java.util.Collections.addAll(s, names);
        return s;
    }

    /**
     * The 7 fields we rewrite, keyed by owner class internal name → field SRG
     * name → accessor (get/set) on NexusCtx. Names must match what javac/proguard
     * emitted — for a vanilla-dev jar these are the SRG names; in MCP-runtime
     * (the dev environment) they're the MCP names. We accept both spellings per
     * field via the lookup below.
     */
    private static final class FieldRoute {
        final String ownerPrefix;      // class internal-name prefix to match (SRG)
        final String ownerObf;         // obfuscated internal name at runtime, or null
        final String[] fieldNames;     // {mcpName, srgName, obfName?}
        final String getterName;
        final String getterDesc;       // (Lowner;)Lrtype;
        final String setterName;
        final String setterDesc;       // (Lowner;Lrtype;)V
        FieldRoute(String owner, String obfOwner, String[] names, String getter, String getDesc,
                   String setter, String setDesc) {
            ownerPrefix = owner;
            ownerObf = obfOwner;
            fieldNames = names;
            getterName = getter; getterDesc = getDesc;
            setterName = setter; setterDesc = setDesc;
        }
    }

    private static final FieldRoute[] FIELD_ROUTES = {
        // net.minecraft.client.Minecraft fields
        // NOTE on field names: the coremod transforms the *obfuscated* runtime
        // jar (versions/…/1.12.2-Forge….jar uses 1-letter names like bib.h), so
        // every route lists MCP name + SRG name + OBF name. A match on any of
        // the three routes the access; the OBF name is the one that actually
        // fires in-game. Verified against the runtime jar via javap:
        //   Minecraft.player           = bib.h  (bud h)
        //   Minecraft.world            = bib.f  (bsb f)
        //   Minecraft.playerController = bib.c  (bsa c)
        //   Minecraft.renderViewEntity = bib.ad (vg  ad)
        //   Minecraft.currentScreen    = bib.m  (blk m)
        //   RenderGlobal.world         = buy.k  (bsb k)
        //   GameSettings.thirdPersonView = bid.aw (int aw)
        new FieldRoute("net/minecraft/client/Minecraft", "bib",
                new String[]{"player", "field_71439_g", "h"},
                "getPlayer", "(Lnet/minecraft/client/Minecraft;)Lnet/minecraft/client/entity/EntityPlayerSP;",
                "setPlayer", "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/entity/EntityPlayerSP;)V"),
        new FieldRoute("net/minecraft/client/Minecraft", "bib",
                new String[]{"world", "field_71441_e", "f"},
                "getWorld", "(Lnet/minecraft/client/Minecraft;)Lnet/minecraft/client/multiplayer/WorldClient;",
                "setWorld", "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/WorldClient;)V"),
        new FieldRoute("net/minecraft/client/Minecraft", "bib",
                new String[]{"playerController", "field_71442_b", "c"},
                "getPlayerController", "(Lnet/minecraft/client/Minecraft;)Lnet/minecraft/client/multiplayer/PlayerControllerMP;",
                "setPlayerController", "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/PlayerControllerMP;)V"),
        new FieldRoute("net/minecraft/client/Minecraft", "bib",
                new String[]{"renderViewEntity", "field_175622_Z", "ad"},
                "getRenderViewEntity", "(Lnet/minecraft/client/Minecraft;)Lnet/minecraft/entity/Entity;",
                "setRenderViewEntity", "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/entity/Entity;)V"),
        new FieldRoute("net/minecraft/client/Minecraft", "bib",
                new String[]{"currentScreen", "field_71462_r", "m"},
                "getCurrentScreen", "(Lnet/minecraft/client/Minecraft;)Lnet/minecraft/client/gui/GuiScreen;",
                "setCurrentScreen", "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/GuiScreen;)V"),
        // net.minecraft.client.renderer.RenderGlobal.world
        new FieldRoute("net/minecraft/client/renderer/RenderGlobal", "buy",
                new String[]{"world", "field_72769_h", "k"},
                "getRenderGlobalWorld", "(Ljava/lang/Object;)Lnet/minecraft/client/multiplayer/WorldClient;",
                "setRenderGlobalWorld", "(Ljava/lang/Object;Lnet/minecraft/client/multiplayer/WorldClient;)V"),
        // net.minecraft.client.settings.GameSettings.thirdPersonView
        new FieldRoute("net/minecraft/client/settings/GameSettings", "bid",
                new String[]{"thirdPersonView", "field_74320_O", "aw"},
                "getThirdPersonView", "(Ljava/lang/Object;)I",
                "setThirdPersonView", "(Ljava/lang/Object;I)V"),
    };

    /**
     * Scan a class for GETFIELD/PUTFIELD touching any of the 7 routed fields
     * and replace each with the matching NexusCtx INVOKESTATIC. Returns the
     * transformed bytes, or null when nothing matched (caller keeps original).
     */
    private byte[] transformFieldContext(byte[] in, String className) {
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = writer(cr);
        final int[] rewrites = {0};
        cr.accept(new ClassVisitor(Opcodes.ASM5, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String mname, String mdesc,
                                             String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, mname, mdesc, sig, ex);
                return new MethodVisitor(Opcodes.ASM5, mv) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name,
                                               String descriptor) {
                        FieldRoute r = routeFor(owner, name);
                        if (r != null && opcode == Opcodes.GETFIELD) {
                            // Stack: ..., ref  ->  ..., value
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, CTX_INTERNAL,
                                    r.getterName, r.getterDesc, false);
                            rewrites[0]++;
                            return;
                        }
                        if (r != null && opcode == Opcodes.PUTFIELD) {
                            // Stack: ..., ref, value  ->  ...
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, CTX_INTERNAL,
                                    r.setterName, r.setterDesc, false);
                            rewrites[0]++;
                            return;
                        }
                        super.visitFieldInsn(opcode, owner, name, descriptor);
                    }
                };
            }
        }, 0);
        if (rewrites[0] == 0) return null;
        NexusPlugin.LOGGER.info("[xform] fieldctx {} rewrites={}", className, rewrites[0]);
        return cw.toByteArray();
    }

    /**
     * Wrap a MethodVisitor so every GETFIELD/PUTFIELD on the 7 routed fields
     * becomes an INVOKESTATIC NexusCtx accessor. Shared by transformFieldContext
     * and by every dedicated transform whose early return would otherwise skip
     * the generic FIELD_CTX pass (NetHandlerPlayClient, McpGameService,
     * WorldClient — they return before the FIELD_CTX_CLASSES check).
     */
    private MethodVisitor fieldCtxVisitor(MethodVisitor mv, final int[] rewrites) {
        return new MethodVisitor(Opcodes.ASM5, mv) {
            @Override
            public void visitFieldInsn(int opcode, String owner, String name,
                                       String descriptor) {
                FieldRoute r = routeFor(owner, name);
                if (r != null && opcode == Opcodes.GETFIELD) {
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, CTX_INTERNAL,
                            r.getterName, r.getterDesc, false);
                    rewrites[0]++;
                    return;
                }
                if (r != null && opcode == Opcodes.PUTFIELD) {
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, CTX_INTERNAL,
                            r.setterName, r.setterDesc, false);
                    rewrites[0]++;
                    return;
                }
                super.visitFieldInsn(opcode, owner, name, descriptor);
            }
        };
    }

    /** Find the route for an owner/field-name pair, or null. */
    private static FieldRoute routeFor(String owner, String name) {
        for (FieldRoute r : FIELD_ROUTES) {
            // The bytecode owner is obf at runtime (bib) but SRG in the dev/MCP
            // environment — accept either spelling.
            if (!(owner.equals(r.ownerPrefix)
                    || (r.ownerObf != null && owner.equals(r.ownerObf)))) continue;
            for (String fn : r.fieldNames) {
                if (fn.equals(name)) return r;
            }
        }
        return null;
    }


    /**
     * ClassWriter whose frame merge NEVER triggers class loading. The default
     * getCommonSuperClass calls Class.forName, which re-enters
     * LaunchClassLoader's transformer chain while Mixin is mid-transform —
     * Mixin detects the re-entrance, throws ReEntrantTransformerError, and the
     * whole launch dies. We walk superclass chains off raw .class resources
     * instead (getResourceAsStream does not run transformers).
     */
    private static ClassWriter writer(ClassReader cr) {
        return new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                Set<String> chain = new HashSet<>();
                for (String t = type1; t != null; t = superName(t)) chain.add(t);
                for (String t = type2; t != null; t = superName(t))
                    if (chain.contains(t)) return t;
                return "java/lang/Object";
            }
        };
    }

    /** Direct superclass internal name from raw class bytes, or null. */
    private static String superName(String internal) {
        if (internal == null || "java/lang/Object".equals(internal)) return null;
        try {
            InputStream is = NexusTransformer.class.getClassLoader()
                    .getResourceAsStream(internal + ".class");
            if (is == null)
                is = ClassLoader.getSystemResourceAsStream(internal + ".class");
            if (is == null) return null;
            try {
                return new ClassReader(is).getSuperName();
            } finally {
                is.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private byte[] transformNetworkManager(byte[] in) {
        ClassReader cr = new ClassReader(in);
        // COMPUTE_FRAMES: our injected branch needs a stackmap frame at the
        // merge label on Java 8 classfiles; letting ASM recompute is simpler
        // and safer than hand-writing frames.
        ClassWriter cw = writer(cr);
        final boolean[] patched = {false};
        cr.accept(new ClassVisitor(Opcodes.ASM5, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String mname, String mdesc,
                                             String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, mname, mdesc, sig, ex);
                // channelRead0(ChannelHandlerContext, Packet):  mcp  (LChannelHandlerContext;Lnet/minecraft/network/Packet;)V
                //                                               srg  same, method func_148833_a / channelRead0
                //                                               obf  (LChannelHandlerContext;Lht;)V, method 'a'
                if (("(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/Packet;)V".equals(mdesc)
                        && (mname.equals("channelRead0") || mname.equals("func_148833_a")))
                    || ("(Lio/netty/channel/ChannelHandlerContext;Lht;)V".equals(mdesc)
                        && mname.equals("a"))) {
                    patched[0] = true;
                    return new MethodVisitor(Opcodes.ASM5, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            // if (NexusHooks.onPacketRead(this, packet)) return;
                            mv.visitVarInsn(Opcodes.ALOAD, 0);
                            mv.visitVarInsn(Opcodes.ALOAD, 2);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_INTERNAL,
                                    "onPacketRead",
                                    "(Ljava/lang/Object;Lnet/minecraft/network/Packet;)Z",
                                    false);
                            Label cont = new Label();
                            mv.visitJumpInsn(Opcodes.IFEQ, cont);
                            mv.visitInsn(Opcodes.RETURN);
                            mv.visitLabel(cont);
                        }
                    };
                }
                // exceptionCaught(ChannelHandlerContext,Throwable)V:
                //   the ONLY place that closes a socket on a pipeline fault.
                //   Vanilla logs it at DEBUG (filtered out) — surface the real
                //   throwable so a mass-disconnect has a name.
                if ("(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Throwable;)V".equals(mdesc)
                        && mname.equals("exceptionCaught")) {
                    return new MethodVisitor(Opcodes.ASM5, mv) {
                        @Override public void visitCode() {
                            super.visitCode();
                            mv.visitVarInsn(Opcodes.ALOAD, 0);
                            mv.visitVarInsn(Opcodes.ALOAD, 2);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_INTERNAL,
                                    "onNettyError",
                                    "(Lnet/minecraft/network/NetworkManager;Ljava/lang/Throwable;)V",
                                    false);
                        }
                    };
                }
                // closeChannel(ITextComponent)V — every local close funnels here;
                // record the reason + calling thread so a silent mass-close is
                // attributable.
                if ("(Lnet/minecraft/util/text/ITextComponent;)V".equals(mdesc)
                        && (mname.equals("closeChannel") || mname.equals("func_150718_a")
                            || mname.equals("a"))) {
                    return new MethodVisitor(Opcodes.ASM5, mv) {
                        @Override public void visitCode() {
                            super.visitCode();
                            mv.visitVarInsn(Opcodes.ALOAD, 0);
                            mv.visitVarInsn(Opcodes.ALOAD, 1);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_INTERNAL,
                                    "onChannelClose",
                                    "(Lnet/minecraft/network/NetworkManager;Ljava/lang/Object;)V",
                                    false);
                        }
                    };
                }
                // channelInactive(ChannelHandlerContext)V:
                //   mcp/srg: channelInactive — bots never run NetworkManager.tick(),
                //   so without this a dropped connection leaves the session ONLINE.
                if ("(Lio/netty/channel/ChannelHandlerContext;)V".equals(mdesc)
                        && mname.equals("channelInactive")) {
                    patched[0] = true;
                    return new MethodVisitor(Opcodes.ASM5, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            mv.visitVarInsn(Opcodes.ALOAD, 0);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_INTERNAL,
                                    "onChannelInactive",
                                    "(Lnet/minecraft/network/NetworkManager;)V",
                                    false);
                        }
                    };
                }
                return mv;
            }
        }, 0);
        if (!patched[0]) NexusPlugin.LOGGER.warn("NetworkManager.channelRead0 not found — bot routing disabled");
        return patched[0] ? cw.toByteArray() : in;
    }


    /**
     * Hook the tail of the 4-arg constructor so every NHPC created anywhere
     * (including ours) registers itself with the session map.
     */
    private byte[] transformNetHandlerPlayClient(byte[] in) {
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = writer(cr);
        final boolean[] patched = {false, false};
        final int[] rewrites = {0};
        cr.accept(new ClassVisitor(Opcodes.ASM5, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String mname, String mdesc,
                                             String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, mname, mdesc, sig, ex);
                // NetHandlerPlayClient is ALSO in FIELD_CTX_CLASSES but this
                // dedicated dispatch returns early — so the generic
                // transformFieldContext pass never sees it. Every handle*
                // (handlePlayerPosLook, handleJoinGame, handleEntity*,
                // handleChunkData, …) reads mc.player/mc.world/mc.playerController
                // directly; on a bound bot thread those MUST resolve to the
                // session's own fields or the bot reads the mounted session.
                // Wrap the base visitor so every GETFIELD/PUTFIELD routes
                // through NexusCtx — same rule transformFieldContext applies.
                final int[] rw = rewrites;
                mv = fieldCtxVisitor(mv, rw);
                // desc is obf at our transform point: (Lbib;Lbxx;Lgw;Lcom/mojang/authlib/GameProfile;)V
                // but GameProfile is not obfuscated, and the arg shape is unique — match on it.
                if (mname.equals("<init>")
                        && mdesc != null && mdesc.endsWith("Lcom/mojang/authlib/GameProfile;)V")
                        && mdesc.startsWith("(L")) {
                    patched[0] = true;
                    return new MethodVisitor(Opcodes.ASM5, mv) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN) {
                                // NexusHooks.onClientHandlerCreated(networkManager, this)
                                // obf ctor desc: (Lbib;Lbmg;Lnm;Lcom/mojang/authlib/GameProfile;)V
                                // = (Minecraft arg1, GuiScreen arg2, NetworkManager arg3, GameProfile arg4)
                                // ALOAD 3 = NetworkManager (arg index 3).
                                mv.visitVarInsn(Opcodes.ALOAD, 3);
                                mv.visitVarInsn(Opcodes.ALOAD, 0);
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_INTERNAL,
                                        "onClientHandlerCreated",
                                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
                // onDisconnect(ITextComponent): mcp func_147231_a, obf a(Lhh;)V.
                // Vanilla body does mc.loadWorld(null)+GuiDisconnected — a bot
                // disconnect would nuke the MOUNTED session's world and freeze
                // the client. Wrap the body inside the owning session's
                // context; the session captures the cleared fields and the
                // mounted screen is restored on pop.
                if (("(Lnet/minecraft/util/text/ITextComponent;)V".equals(mdesc) &&
                        (mname.equals("onDisconnect") || mname.equals("func_147231_a")))
                    || ("(Lhh;)V".equals(mdesc) && mname.equals("a"))) {
                    patched[1] = true;
                    return new MethodVisitor(Opcodes.ASM5, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            // if (NexusHooks.pushDisconnectCtx(this)) return;
                            mv.visitVarInsn(Opcodes.ALOAD, 0);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_INTERNAL,
                                    "pushDisconnectCtx", "(Ljava/lang/Object;)Z", false);
                            org.objectweb.asm.Label run = new org.objectweb.asm.Label();
                            mv.visitJumpInsn(Opcodes.IFEQ, run);
                            mv.visitInsn(Opcodes.RETURN);
                            mv.visitLabel(run);
                        }
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN) {
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_INTERNAL,
                                        "popDisconnectCtx", "()V", false);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
                return mv;
            }
        }, 0);
        if (!patched[0]) NexusPlugin.LOGGER.warn("NetHandlerPlayClient.<init> not patched — sessions won't auto-register");
        if (!patched[1]) NexusPlugin.LOGGER.warn("NetHandlerPlayClient.onDisconnect not patched — bot disconnects will corrupt the mounted session");
        NexusPlugin.LOGGER.info("[xform] NetHandlerPlayClient fieldctx rewrites={}", rewrites[0]);
        return (patched[0] || patched[1] || rewrites[0] > 0) ? cw.toByteArray() : in;
    }


    /**
     * Guard the three per-tick methods that NPE when the mounted session has
     * no live context (bot not in a world yet, or human disconnected while a
     * bot is mounted). Insert at head:
     *   if (NexusHooks.tickGuard()) return;
     * tickGuard returns true exactly when the current singleton state is one
     * vanilla itself treats as "no game" — world null AND player null —
     * which is the state a just-connected bot sits in until JoinGame arrives.
     * A human at the title screen has the same combination, so vanilla code
     * already handles it; the NPE paths assume world+player together and
     * only null-check one.
     */
    private byte[] transformMinecraft(byte[] in) {
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = writer(cr);
        final int[] patched = {0};
        cr.accept(new ClassVisitor(Opcodes.ASM5, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String mname, String mdesc,
                                             String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, mname, mdesc, sig, ex);
                // Route renderViewEntity field access inside every method
                // through NexusCtx (bot threads see ctx.player, client the
                // real field). Wrapping the base mv means the guards below
                // still inject their head code unaffected.
                mv = renderViewEntityRoute(mv);

                // mcp runTick  srg func_71407_l  obf t  desc ()V
                if ("()V".equals(mdesc) &&
                        (mname.equals("runTick") || mname.equals("func_71407_l") || mname.equals("t"))) {
                    patched[0]++;
                    return new MethodVisitor(Opcodes.ASM5, mv) {
                        @Override public void visitCode() {
                            super.visitCode();
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_INTERNAL,
                                    "runSessionTicks", "()V", false);
                        }
                    };
                }

                // runTickMouse    ()V   mcp runTickMouse     srg func_184118_az  obf aD
                // processKeyBinds ()V   mcp processKeyBinds  srg func_184117_aA  obf aE
                // middleClickMouse()V   mcp middleClickMouse srg func_147112_ai  obf aH
                // clickMouse      ()V   mcp clickMouse       srg func_147116_af  obf aA
                // rightClickMouse ()V   mcp rightClickMouse  srg func_147121_ag  obf aB
                // runTickKeyboard ()V   mcp runTickKeyboard  srg func_184124_aB  obf aG
                // dispatchKeypresses is deliberately NOT guarded: it only pumps
                // Keyboard.next() and fires KeyInputEvent — no world/player
                // dereference, and guarding it at the title screen kills every
                // Forge keybinding (MythosScript's F menu key included).
                if ("()V".equals(mdesc) &&
                        (mname.equals("runTickMouse")      || mname.equals("func_184118_az") || mname.equals("aD") ||
                         mname.equals("processKeyBinds")   || mname.equals("func_184117_aA") || mname.equals("aE") ||
                         mname.equals("runTickKeyboard")   || mname.equals("func_184124_aB") || mname.equals("aG") ||
                         mname.equals("middleClickMouse")  || mname.equals("func_147112_ai") || mname.equals("aH") ||
                         mname.equals("clickMouse")        || mname.equals("func_147116_af") || mname.equals("aA") ||
                         mname.equals("rightClickMouse")   || mname.equals("func_147121_ag") || mname.equals("aB"))) {
                    patched[0]++;
                    return guardMethod(mv);
                }

                // mcp displayGuiScreen  srg func_147108_a  desc (Lnet/minecraft/client/gui/GuiScreen;)V
                // obf: method 'a' with single GuiScreen arg — match by obf desc (Lblk;)V
                if (("(Lnet/minecraft/client/gui/GuiScreen;)V".equals(mdesc) &&
                        (mname.equals("displayGuiScreen") || mname.equals("func_147108_a")))
                    || ("(Lblk;)V".equals(mdesc) && mname.equals("a"))) {
                    patched[0]++;
                    return guiGuardMethod(mv);
                }

                // Vanilla loadWorld calls renderGlobal.setWorldAndLoadRenderers(wc)
                // which frees viewFrustum + restarts chunk workers = one black
                // frame on the mounted session's screen. The rest of loadWorld
                // (mc.world=wc, renderViewEntity, displayGuiScreen(null)) is
                // wanted — the bot's world must land. Replace just the
                // setWorldAndLoadRenderers call with a ctx-aware guard.
                // mcp loadWorld  srg func_71403_a  desc (Lnet/minecraft/client/multiplayer/WorldClient;Ljava/lang/String;)V
                // obf 'a' with (Lbsb;Ljava/lang/String;)V — bsb = WorldClient
                if ("(Lnet/minecraft/client/multiplayer/WorldClient;Ljava/lang/String;)V".equals(mdesc)
                        && (mname.equals("loadWorld") || mname.equals("func_71403_a") || mname.equals("func_71353_a"))
                        || ("(Lbsb;Ljava/lang/String;)V".equals(mdesc) && mname.equals("a"))) {
                    patched[0]++;
                    return new MethodVisitor(Opcodes.ASM5, mv) {
                        // Head-guard: on a background session's context (bot
                        // tick thread) run NexusHooks.loadWorldForBot instead of
                        // the vanilla body — the vanilla method writes shared
                        // mc.world/player/controller and calls GL/sound/shared
                        // render objects, stomping the mounted session AND
                        // never landing the bot's world in s.world.
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            mv.visitVarInsn(Opcodes.ALOAD, 0);           // this (Minecraft)
                            mv.visitVarInsn(Opcodes.ALOAD, 1);           // wc (WorldClient)
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_INTERNAL,
                                    "loadWorldForBot",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                            Label runVanilla = new Label();
                            mv.visitJumpInsn(Opcodes.IFEQ, runVanilla);
                            mv.visitInsn(Opcodes.RETURN);
                            mv.visitLabel(runVanilla);
                        }
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name,
                                                    String desc, boolean itf) {
                            // stack before call: [mc renderGlobal, wc]
                            // mcp/srg: setWorldAndLoadRenderers  func_72732_a
                            // obf: a(Lbsb;)V on buy RenderGlobal
                            boolean isSetWorld =
                                ("(Lnet/minecraft/client/multiplayer/WorldClient;)V".equals(desc)
                                    && (name.equals("setWorldAndLoadRenderers") || name.equals("func_72732_a")))
                                || ("(Lbsb;)V".equals(desc) && name.equals("a")
                                    && (owner.endsWith("RenderGlobal") || owner.equals("buy")));
                            if (isSetWorld && opcode == Opcodes.INVOKEVIRTUAL) {
                                // Rewrite to: NexusHooks.loadWorldGuard(renderGlobal, wc)
                                // — returns silently (no rebuild) while a
                                // background session dispatches, otherwise
                                // delegates to the real method.
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_INTERNAL,
                                        "loadWorldGuard",
                                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                                return;
                            }
                            super.visitMethodInsn(opcode, owner, name, desc, itf);
                        }
                    };
                }

                // NetHandlerPlayClient.handleRespawn calls this on the bot's
                // tick thread for a same-dimension respawn (the loadWorld branch
                // only fires when the dimension CHANGED). The vanilla method
                // writes shared mc.world/player/renderViewEntity — on a bot
                // thread that stomps the mounted session and never rebuilds the
                // bot's own world entity. Route it to NexusHooks.respawnForBot
                // so the respawn lands on the bound session instead.
                // mcp setDimensionAndSpawnPlayer  srg func_71354_a  desc (I)V
                // obf 'a' with (I)V — same name/signature family as above.
                if ("(I)V".equals(mdesc)
                        && (mname.equals("setDimensionAndSpawnPlayer")
                                || mname.equals("func_71354_a")
                                || mname.equals("a"))) {
                    patched[0]++;
                    return new MethodVisitor(Opcodes.ASM5, mv) {
                        @Override public void visitCode() {
                            super.visitCode();
                            mv.visitVarInsn(Opcodes.ALOAD, 0);           // this (Minecraft)
                            mv.visitVarInsn(Opcodes.ILOAD, 1);           // dimension
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_INTERNAL,
                                    "respawnForBot",
                                    "(Ljava/lang/Object;I)Z", false);
                            Label runVanilla = new Label();
                            mv.visitJumpInsn(Opcodes.IFEQ, runVanilla);
                            mv.visitInsn(Opcodes.RETURN);
                            mv.visitLabel(runVanilla);
                        }
                    };
                }

                //     a legal "play" thread so checkThreadAndEnqueue doesn't
                //     bounce every server packet back onto the client thread ---
                // Vanilla: return Thread.currentThread() == this.mcThread.
                // Every NetHandlerPlayClient.handleX starts with
                // PacketThreadUtil.checkThreadAndEnqueue(packet, this, mc) which
                // calls this — on a bot tick thread that check is false, so the
                // packet body (incl. SPacketJoinGame, which builds the bot's
                // WorldClient/player) is re-scheduled via mc.addScheduledTask
                // onto the CLIENT thread where no NexusCtx is bound: the world
                // then lands in the shared/mounted session and the handler
                // NPEs on this.gameController.player.dimension.
                // Returning true on a bound bot thread makes the handler run
                // inline there — every mc.player/mc.world/mc.playerController
                // write inside resolves through NexusCtx to that session. It
                // also makes Minecraft.addScheduledTask take its immediate
                // (same-thread) branch for bot-side tasks, keeping them off
                // the render thread.
                // mcp isCallingFromMinecraftThread  srg func_152345_ab  obf aF  ()Z
                if ("()Z".equals(mdesc) &&
                        (mname.equals("isCallingFromMinecraftThread")
                                || mname.equals("func_152345_ab")
                                || mname.equals("aF"))) {
                    patched[0]++;
                    return new MethodVisitor(Opcodes.ASM5, mv) {
                        @Override public void visitCode() {
                            super.visitCode();
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, CTX_INTERNAL,
                                    "onBotThread", "()Z", false);
                            Label cont = new Label();
                            mv.visitJumpInsn(Opcodes.IFEQ, cont);
                            mv.visitInsn(Opcodes.ICONST_1);
                            mv.visitInsn(Opcodes.IRETURN);
                            mv.visitLabel(cont);
                        }
                    };
                }
                return mv;
            }

            private MethodVisitor guardMethod(MethodVisitor mv) {
                return new MethodVisitor(Opcodes.ASM5, mv) {
                    @Override public void visitCode() {
                        super.visitCode();
                        mv.visitVarInsn(Opcodes.ALOAD, 0);              // this
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_INTERNAL,
                                "tickGuard", "(Ljava/lang/Object;)Z", false);
                        Label cont = new Label();
                        mv.visitJumpInsn(Opcodes.IFEQ, cont);
                        mv.visitInsn(Opcodes.RETURN);
                        mv.visitLabel(cont);
                    }
                };
            }

            private MethodVisitor guiGuardMethod(MethodVisitor mv) {
                return new MethodVisitor(Opcodes.ASM5, mv) {
                    @Override public void visitCode() {
                        super.visitCode();
                        mv.visitVarInsn(Opcodes.ALOAD, 0);              // this (Minecraft)
                        mv.visitVarInsn(Opcodes.ALOAD, 1);              // GuiScreen arg
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_INTERNAL,
                                "guiGuard", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                        Label cont = new Label();
                        mv.visitJumpInsn(Opcodes.IFEQ, cont);
                        mv.visitInsn(Opcodes.RETURN);
                        mv.visitLabel(cont);
                    }
                };
            }

            /**
             * Rewrite every direct access to the private `renderViewEntity`
             * field inside Minecraft itself (getRenderViewEntity's read +
             * setRenderViewEntity/loadWorld writes). External callers only
             * use the get/set methods, so routing inside the methods gives
             * per-session resolution for free. On a bot thread these become
             * NexusCtx reads of ctx.player; on the client thread the real
             * field.
             */
            private MethodVisitor renderViewEntityRoute(MethodVisitor mv) {
                return new MethodVisitor(Opcodes.ASM5, mv) {
                    @Override public void visitFieldInsn(int opcode, String owner,
                            String name, String descriptor) {
                        boolean isRVE = "net/minecraft/client/Minecraft".equals(owner)
                                && ("renderViewEntity".equals(name)
                                        || "field_175622_Z".equals(name)
                                        || "ad".equals(name)); // obf of renderViewEntity (bib.ad; bib.i is pointedEntity)
                        if (isRVE && opcode == Opcodes.GETFIELD) {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, CTX_INTERNAL,
                                    "getRenderViewEntity",
                                    "(Lnet/minecraft/client/Minecraft;)Lnet/minecraft/entity/Entity;",
                                    false);
                            return;
                        }
                        if (isRVE && opcode == Opcodes.PUTFIELD) {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, CTX_INTERNAL,
                                    "setRenderViewEntity",
                                    "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/entity/Entity;)V",
                                    false);
                            return;
                        }
                        super.visitFieldInsn(opcode, owner, name, descriptor);
                    }
                };
            }
        }, 0);
        if (patched[0] == 0)
            NexusPlugin.LOGGER.warn("Minecraft guards not patched");
        return patched[0] > 0 ? cw.toByteArray() : in;
    }

    /**
     * Guard EntityRenderer.updateRenderer — dereferences mc.world and
     * getRenderViewEntity() unconditionally while a mounted session that
     * isn't in a world yet keeps both null.
     */
    private byte[] transformEntityRenderer(byte[] in) {
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = writer(cr);
        final boolean[] patched = {false};
        cr.accept(new ClassVisitor(Opcodes.ASM5, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String mname, String mdesc,
                                             String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, mname, mdesc, sig, ex);
                if ("()V".equals(mdesc) &&
                        (mname.equals("updateRenderer") || mname.equals("func_181022_b") || mname.equals("b"))) {
                    patched[0] = true;
                    return new MethodVisitor(Opcodes.ASM5, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            mv.visitVarInsn(Opcodes.ALOAD, 0);          // this (EntityRenderer)
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_INTERNAL,
                                    "tickGuard", "(Ljava/lang/Object;)Z", false);
                            Label cont = new Label();
                            mv.visitJumpInsn(Opcodes.IFEQ, cont);
                            mv.visitInsn(Opcodes.RETURN);
                            mv.visitLabel(cont);
                        }
                    };
                }
                // getMouseOver(F)V — the crosshair raytrace. It dereferences
                // mc.world.getEntitiesInAABBexcluding with no null check; on
                // the client thread during a mount/disconnect window mc.world
                // is transiently null → NPE at EntityRenderer.java:356 and the
                // whole client dies ("Unreported exception" crash). mouseGuard
                // skips the trace whenever world or the view entity is absent
                // — a one-tick stale crosshair is invisible to the user.
                if ("(F)V".equals(mdesc) &&
                        (mname.equals("getMouseOver") || mname.equals("func_78473_a")
                                || mname.equals("a"))) {
                    patched[0] = true;
                    return new MethodVisitor(Opcodes.ASM5, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            mv.visitVarInsn(Opcodes.ALOAD, 0);          // this (EntityRenderer)
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_INTERNAL,
                                    "mouseGuard", "(Ljava/lang/Object;)Z", false);
                            Label cont = new Label();
                            mv.visitJumpInsn(Opcodes.IFEQ, cont);
                            mv.visitInsn(Opcodes.RETURN);
                            mv.visitLabel(cont);
                        }
                    };
                }
                // a(FJ)V = func_181560_a = updateCameraAndRender — the ENTIRE
                // render entry (world AND GuiScreen). tickGuard would black the
                // screen at the title (world==null). renderGuard only skips in
                // the inconsistent state (world torn down but a stale
                // renderViewEntity still set) that caused the original
                // func_181560_a:1039 NPE.
                // ALSO: intercept every `bib.F()` call inside this method —
                // getRenderViewEntity() can return null between the head guard
                // and the isSpectator() call at line 1039 (another thread
                // nulls it during persistIntoMinecraft / disconnect). DUP the
                // result, if null → RETURN (skip frame entirely).
                if ("(FJ)V".equals(mdesc) &&
                        (mname.equals("renderWorld") || mname.equals("func_181560_a") || mname.equals("a"))) {
                    patched[0] = true;
                    return new MethodVisitor(Opcodes.ASM5, mv) {
                        private final Label tryStart = new Label();
                        private final Label tryEnd = new Label();
                        private final Label npeHandler = new Label();

                        @Override
                        public void visitCode() {
                            super.visitCode();
                            mv.visitTryCatchBlock(tryStart, tryEnd, npeHandler,
                                    "java/lang/NullPointerException");
                            // Mounting a session whose channel already closed
                            // tears down the shared RenderGlobal mid-frame and
                            // leaves BufferBuilder "Already building!" — skip
                            // that frame instead of crashing the whole client.
                            mv.visitTryCatchBlock(tryStart, tryEnd, npeHandler,
                                    "java/lang/IllegalStateException");
                            mv.visitLabel(tryStart);
                            mv.visitVarInsn(Opcodes.ALOAD, 0);          // this (EntityRenderer)
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_INTERNAL,
                                    "renderGuard", "(Ljava/lang/Object;)Z", false);
                            Label cont = new Label();
                            mv.visitJumpInsn(Opcodes.IFEQ, cont);
                            mv.visitInsn(Opcodes.RETURN);
                            mv.visitLabel(cont);
                            // 每帧开头补一次 RenderManager.renderViewEntity，
                            // 已非 null 时只做一次反射读。
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_INTERNAL,
                                    "fillRenderViewEntity", "()V", false);
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name,
                                                    String desc, boolean itf) {
                            // The vanilla sequence is
                            //   mc.getIntegratedServer().isServerRunning()
                            // and multiplayer always has a null integrated
                            // server. Replace the second call itself so a
                            // null receiver never reaches invokevirtual.
                            if (opcode == Opcodes.INVOKEVIRTUAL
                                    && (("chd".equals(owner) && "y".equals(name)
                                         && "()Z".equals(desc))
                                     || ("net/minecraft/server/integrated/IntegratedServer".equals(owner)
                                         && "func_184106_y".equals(name)
                                         && "()Z".equals(desc)))) {
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_INTERNAL,
                                        "safeIntegratedServerReady",
                                        "(Ljava/lang/Object;)Z", false);
                                return;
                            }
                            super.visitMethodInsn(opcode, owner, name, desc, itf);
                            // Keep the old defensive guard for any other
                            // getIntegratedServer use in this method.
                            // SRG name: net/minecraft/client/Minecraft.func_71401_C()Lchd;
                            if (("bib".equals(owner)
                                    && "F".equals(name)
                                    && "()Lchd;".equals(desc))
                                || ("net/minecraft/client/Minecraft".equals(owner)
                                    && "func_71401_C".equals(name)
                                    && "()Lnet/minecraft/server/integrated/IntegratedServer;".equals(desc))) {
                                Label nonNull = new Label();
                                mv.visitInsn(Opcodes.DUP);
                                mv.visitJumpInsn(Opcodes.IFNONNULL, nonNull);
                                mv.visitInsn(Opcodes.POP);
                                mv.visitInsn(Opcodes.RETURN);
                                mv.visitLabel(nonNull);
                            }
                        }

                        @Override
                        public void visitMaxs(int maxStack, int maxLocals) {
                            mv.visitLabel(tryEnd);
                            mv.visitLabel(npeHandler);
                            mv.visitInsn(Opcodes.POP);
                            mv.visitInsn(Opcodes.RETURN);
                            super.visitMaxs(maxStack, maxLocals);
                        }
                    };
                }
                return mv;
            }
        }, 0);
        NexusPlugin.LOGGER.info("[xform] EntityRenderer patched={}", patched[0]);
        if (!patched[0]) NexusPlugin.LOGGER.warn("EntityRenderer guard methods not patched");
        return patched[0] ? cw.toByteArray() : in;
    }

    /**
     * Framebuffer.func_178038_a (framebufferRenderExt) begins a Tessellator batch
     * for the main-FBO blit every frame. Any path that leaves the shared
     * Tessellator mid-build (a Nexus preview aborting, a render branch dying
     * inside a swallowed exception) crashes the NEXT frame at
     * "Already building!" — the renderGuard flush inside
     * updateCameraAndRender can't cover calls that run AFTER it in runGameLoop.
     * Flush at the head of both framebufferRender paths so a dirty batch is
     * drained before the blit's own begin().
     */
    private byte[] transformFramebuffer(byte[] in) {
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = writer(cr);
        final boolean[] patched = {false};
        cr.accept(new ClassVisitor(Opcodes.ASM5, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String mname, String mdesc,
                                             String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, mname, mdesc, sig, ex);
                // framebufferRender(int,int)      ()V-sig (II)V  mcp framebufferRender      srg func_147615_c  obf c
                // framebufferRenderExt(int,int,b) (IIZ)V         mcp framebufferRenderExt   srg func_178038_a  obf a
                if (("(II)V".equals(mdesc) && (mname.equals("framebufferRender")    || mname.equals("func_147615_c") || mname.equals("c")))
                 || ("(IIZ)V".equals(mdesc) && (mname.equals("framebufferRenderExt") || mname.equals("func_178038_a") || mname.equals("a")))) {
                    patched[0] = true;
                    return new MethodVisitor(Opcodes.ASM5, mv) {
                        @Override public void visitCode() {
                            super.visitCode();
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_INTERNAL,
                                    "flushTessellatorIfBuilding", "()V", false);
                        }
                    };
                }
                return mv;
            }
        }, 0);
        NexusPlugin.LOGGER.info("[xform] Framebuffer patched={}", patched[0]);
        if (!patched[0]) NexusPlugin.LOGGER.warn("Framebuffer flush not patched");
        return patched[0] ? cw.toByteArray() : in;
    }

    /**
     * WorldVertexBufferUploader.draw(BufferBuilder) issues the fixed-function
     * vertex upload: per-attribute ForgeHooksClient.preDraw (glVertexPointer /
     * glEnableClientState) + glDrawArrays + postDraw. On a bound bot thread
     * there is no current GL context, so any of these crashes the JVM. The
     * caller's own finishDrawing() already cleared isDrawing before this runs;
     * injecting `if (vertexUploadGuard(buf)) return;` at the head resets the
     * CPU buffer and skips all GL — the bot's begin..draw becomes a no-op.
     * draw() desc: (Lnet/minecraft/client/renderer/BufferBuilder;)V  mcp draw
     * srg func_181679_a  obf a(Lbuk;)V
     */
    private byte[] transformVertexUpload(byte[] in) {
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = writer(cr);
        final boolean[] patched = {false};
        cr.accept(new ClassVisitor(Opcodes.ASM5, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String mname, String mdesc,
                                             String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, mname, mdesc, sig, ex);
                boolean isDraw =
                    ("(Lnet/minecraft/client/renderer/BufferBuilder;)V".equals(mdesc)
                            && (mname.equals("draw") || mname.equals("func_181679_a")))
                    || ("(Lbuk;)V".equals(mdesc) && mname.equals("a"));
                if (!isDraw) return mv;
                patched[0] = true;
                return new MethodVisitor(Opcodes.ASM5, mv) {
                    @Override public void visitCode() {
                        super.visitCode();
                        mv.visitVarInsn(Opcodes.ALOAD, 1);              // BufferBuilder arg
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_INTERNAL,
                                "vertexUploadGuard", "(Ljava/lang/Object;)Z", false);
                        Label cont = new Label();
                        mv.visitJumpInsn(Opcodes.IFEQ, cont);
                        mv.visitInsn(Opcodes.RETURN);
                        mv.visitLabel(cont);
                    }
                };
            }
        }, 0);
        NexusPlugin.LOGGER.info("[xform] WorldVertexBufferUploader.draw guarded={}", patched[0]);
        if (!patched[0]) NexusPlugin.LOGGER.warn("WorldVertexBufferUploader.draw not patched");
        return patched[0] ? cw.toByteArray() : in;
    }

    /**
     * Rewrite every `INVOKESTATIC Tessellator.getInstance()` call in a class to
     * `INVOKESTATIC NexusHooks.tessellatorForThread()`. getInstance() is the
     * only producer of the process-wide Tessellator singleton; routing it
     * through the hook lets a bound bot thread receive its own ThreadLocal
     * Tessellator so two threads can never begin() the same BufferBuilder.
     * Returns the rewritten bytes when at least one call site was replaced,
     * else null so the caller can fall through to the original bytes.
     *
     * Uses COMPUTE_MAXS (not COMPUTE_FRAMES): the rewrite swaps one
     * INVOKESTATIC ()L…/Tessellator; for another with the identical return
     * type, so the stack shape is unchanged and the ORIGINAL stackmap frames
     * stay valid. Recomputing frames would be actively harmful here — this
     * transform runs on obfuscated jars whose callers reference mcp-named
     * types (Entity/EntityLivingBase) that are NOT on the transform-time
     * classpath, so getCommonSuperClass can't resolve their hierarchy and
     * falls back to java/lang/Object. The emitted frame then declares a
     * local as Object where the bytecode later does `invokevirtual Entity.x()`
     * → VerifyError: Bad type on operand stack (KillAuraHandler.f crash).
     */
    /**
     * Rewrites INVOKESTATIC Tessellator.getInstance() → NexusHooks.tessellatorForThread()
     * inside a single method. Named static class (not an anonymous MethodVisitor)
     * so its bytecode is resolved when NexusTransformer itself loads — an anonymous
     * class first-instantiated *inside* transform() triggers a class load mid-
     * transform and the JVM marks it ClassCircularityError, permanently breaking
     * the redirect for every later class (the launch-log spam).
     */
    private static final class TessGetInsnVisitor extends MethodVisitor {
        private final int[] rewrites;

        TessGetInsnVisitor(MethodVisitor mv, int[] rewrites) {
            super(Opcodes.ASM5, mv);
            this.rewrites = rewrites;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                                    String desc, boolean itf) {
            // getInstance() ()Lnet/.../Tessellator;  mcp getInstance
            // srg func_178181_a  obf a()Lbve;
            boolean isGetInstance = opcode == Opcodes.INVOKESTATIC
                && ("net/minecraft/client/renderer/Tessellator".equals(owner)
                        || "bve".equals(owner))
                && ("()Lnet/minecraft/client/renderer/Tessellator;".equals(desc)
                        || "()Lbve;".equals(desc))
                && (name.equals("getInstance") || name.equals("func_178181_a")
                        || name.equals("a"));
            if (isGetInstance) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_INTERNAL,
                        "tessellatorForThread",
                        "()Lnet/minecraft/client/renderer/Tessellator;", false);
                rewrites[0]++;
                return;
            }
            super.visitMethodInsn(opcode, owner, name, desc, itf);
        }
    }

    /** ClassVisitor feeding {@link TessGetInsnVisitor} into every method. */
    private static final class TessGetClassVisitor extends ClassVisitor {
        private final int[] rewrites;

        TessGetClassVisitor(ClassWriter cw, int[] rewrites) {
            super(Opcodes.ASM5, cw);
            this.rewrites = rewrites;
        }

        @Override
        public MethodVisitor visitMethod(int access, String mname, String mdesc,
                                         String sig, String[] ex) {
            MethodVisitor mv = super.visitMethod(access, mname, mdesc, sig, ex);
            return new TessGetInsnVisitor(mv, rewrites);
        }
    }

    private byte[] redirectTessellatorGetInstance(byte[] in) {
        ClassReader cr = new ClassReader(in);
        // Never rewrite inside the hook provider — tessellatorForThread() calls
        // Tessellator.getInstance() on the client-thread path and redirecting
        // it back into itself would recurse forever.
        if (HOOKS_INTERNAL.equals(cr.getClassName())) return null;
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        final int[] rewrites = {0};
        cr.accept(new TessGetClassVisitor(cw, rewrites), 0);
        if (rewrites[0] > 0) {
            NexusPlugin.LOGGER.info("[xform] Tessellator.getInstance redirected x{} in {}",
                    rewrites[0], cr.getClassName());
        }
        return rewrites[0] > 0 ? cw.toByteArray() : null;
    }
    /**
     * Post-pass: run the Tessellator.getInstance() redirect over bytes that a
     * dedicated transform already produced (or the raw input when it produced
     * none). Dedicated transforms return early, so without this wrapper their
     * getInstance call sites — ParticleManager's among them — would stay on the
     * shared singleton even though bot threads reach them via packet handlers.
     * Returns the redirected bytes when a rewrite happened, else `in`.
     */
    private byte[] pipeGetInstance(byte[] in) {
        if (in == null) return null;
        byte[] out = redirectTessellatorGetInstance(in);
        return out != null ? out : in;
    }

    /**
     * Guard McpGameService.tick — MythosScript's ClientTickEvent handler that
     * drives McpWorldView.tick. When a bot session mounts with no world yet
     * (connecting, or a fresh session whose join packets haven't arrived),
     * McpWorldView dereferences mc.player/mc.world and NPEs the whole client.
     * Skip the tick entirely for a world-less mounted session.
     */
    private byte[] transformMcpGameService(byte[] in) {
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = writer(cr);
        final boolean[] patched = {false};
        final int[] rewrites = {0};
        cr.accept(new ClassVisitor(Opcodes.ASM5, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String mname, String mdesc,
                                             String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, mname, mdesc, sig, ex);
                // McpGameService is in FIELD_CTX_CLASSES but this dedicated
                // dispatch returns early — apply the field rewrite inline so a
                // bot thread's mc.player/mc.world reads resolve per-session.
                mv = fieldCtxVisitor(mv, rewrites);
                // tick(TickEvent$ClientTickEvent) — the Forge event handler.
                // Signature: (Lnet/minecraftforge/fml/common/gameevent/TickEvent$ClientTickEvent;)V
                if ("(Lnet/minecraftforge/fml/common/gameevent/TickEvent$ClientTickEvent;)V".equals(mdesc)
                        && mname.equals("tick")) {
                    patched[0] = true;
                    return new MethodVisitor(Opcodes.ASM5, mv) {
                        @Override public void visitCode() {
                            super.visitCode();
                            mv.visitVarInsn(Opcodes.ALOAD, 0);          // this (McpGameService)
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_INTERNAL,
                                    "tickGuard", "(Ljava/lang/Object;)Z", false);
                            Label cont = new Label();
                            mv.visitJumpInsn(Opcodes.IFEQ, cont);
                            mv.visitInsn(Opcodes.RETURN);
                            mv.visitLabel(cont);
                        }
                    };
                }
                return mv;
            }
        }, 0);
        if (!patched[0]) NexusPlugin.LOGGER.warn("McpGameService.tick not patched");
        NexusPlugin.LOGGER.info("[xform] McpGameService fieldctx rewrites={}", rewrites[0]);
        return (patched[0] || rewrites[0] > 0) ? cw.toByteArray() : in;
    }

    /**
     * WorldClient: combine the field-context rewrite (it's in FIELD_CTX_CLASSES
     * but its dedicated dispatch returns early, so we apply transformFieldContext
     * style rewrites inline here) WITH a head guard on updateBlocks.
     *
     * updateBlocks() drives client ambience — randomDisplayTick particles,
     * mood sounds, void-fog, barrier particles. Every one of those ends in
     * mc.effectRenderer / mc.getSoundHandler → GL + OpenAL. On a background
     * bot's tick thread there is no GL context, so the call throws
     * "No OpenGL context found in the current thread" and the whole
     * world.tick() aborts. A background world is never rendered, so skipping
     * its ambience is correct AND removes the crash. backgroundWorldGuard
     * returns true only when running on a bound bot thread; the mounted/
     * foreground world (client thread) keeps the vanilla behaviour.
     */
    private byte[] transformWorldClient(byte[] in, String className) {
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = writer(cr);
        final int[] rewrites = {0};
        final boolean[] guarded = {false};
        cr.accept(new ClassVisitor(Opcodes.ASM5, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String mname, String mdesc,
                                             String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, mname, mdesc, sig, ex);
                final boolean isUpdateBlocks = "()V".equals(mdesc) &&
                        (mname.equals("updateBlocks") || mname.equals("func_147456_g")
                                || mname.equals("j"));
                return new MethodVisitor(Opcodes.ASM5, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        if (isUpdateBlocks) {
                            guarded[0] = true;
                            mv.visitVarInsn(Opcodes.ALOAD, 0);          // this (WorldClient)
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_INTERNAL,
                                    "backgroundWorldGuard", "(Ljava/lang/Object;)Z", false);
                            Label cont = new Label();
                            mv.visitJumpInsn(Opcodes.IFEQ, cont);
                            mv.visitInsn(Opcodes.RETURN);
                            mv.visitLabel(cont);
                        }
                    }
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name,
                                               String descriptor) {
                        FieldRoute r = routeFor(owner, name);
                        if (r != null && opcode == Opcodes.GETFIELD) {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, CTX_INTERNAL,
                                    r.getterName, r.getterDesc, false);
                            rewrites[0]++;
                            return;
                        }
                        if (r != null && opcode == Opcodes.PUTFIELD) {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, CTX_INTERNAL,
                                    r.setterName, r.setterDesc, false);
                            rewrites[0]++;
                            return;
                        }
                        super.visitFieldInsn(opcode, owner, name, descriptor);
                    }
                };
            }
        }, 0);
        NexusPlugin.LOGGER.info("[xform] WorldClient rewrites={} updateBlocksGuarded={}",
                rewrites[0], guarded[0]);
        return (rewrites[0] > 0 || guarded[0]) ? cw.toByteArray() : in;
    }

    /**
     * Wrap ParticleManager.updateEffects in a try/catch(NPE) — particles
     * spawned during a mount window (ParticleManager.world transiently null)
     * carry a null world field and NPE inside onUpdate. Vanilla never sees
     * this because it never holds a particle across a world swap.
     */
    private byte[] transformParticleManager(byte[] in) {
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = writer(cr);
        final boolean[] patched = {false};
        cr.accept(new ClassVisitor(Opcodes.ASM5, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String mname, String mdesc,
                                             String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, mname, mdesc, sig, ex);
                // clearEffects / setWorld (func_78870_a, a(Lamu;)V) and
                // addEffect(Particle) mutate the shared ParticleManager. On a
                // bot thread that is a cross-bot write of the mounted session's
                // renderer — no-op all of them before they touch its queues.
                boolean particleWorldMutation =
                        ("(Lnet/minecraft/world/World;)V".equals(mdesc) || "(Lamu;)V".equals(mdesc))
                                && (mname.equals("clearEffects") || mname.equals("func_78870_a") || mname.equals("a"));
                boolean particleQueueMutation =
                        ("(Lnet/minecraft/client/particle/Particle;)V".equals(mdesc) || "(Lbtf;)V".equals(mdesc))
                                && (mname.equals("addEffect") || mname.equals("func_78873_a") || mname.equals("a"));
                if (particleWorldMutation || particleQueueMutation) {
                    patched[0] = true;
                    return new MethodVisitor(Opcodes.ASM5, mv) {
                        @Override public void visitCode() {
                            super.visitCode();
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, CTX_INTERNAL,
                                    "onBotThread", "()Z", false);
                            Label cont = new Label();
                            mv.visitJumpInsn(Opcodes.IFEQ, cont);
                            mv.visitInsn(Opcodes.RETURN);
                            mv.visitLabel(cont);
                        }
                    };
                }
                // func_78874_a / renderParticles — renders all particles, calls
                // a mount window carry null world → NPE at Particle.java:344.
                // func_78868_a / updateEffects — ticks all particles; the same
                // null-world particle NPEs at ParticleManager.java:203 inside
                // its private per-particle tick helper. Both entry points must
                // be guarded; wrapping only one leaves the other to crash.
                if (("(Lnet/minecraft/entity/Entity;F)V".equals(mdesc) &&
                        (mname.equals("renderParticles") || mname.equals("func_78874_a")))
                    || ("(Lvg;F)V".equals(mdesc) && mname.equals("a"))
                    || ("()V".equals(mdesc) &&
                        (mname.equals("updateEffects") || mname.equals("func_78868_a") || mname.equals("a")))
                    || ("(I)V".equals(mdesc) &&
                        (mname.equals("func_178922_a") || mname.equals("a")))
                    // func_180533_a / addBlockDestroyEffects — spawns break
                    // particles via state.getActualState(world, pos); when the
                    // session world is being torn down (disconnect/mount swap)
                    // ParticleManager.world can transiently be null or stale,
                    // NPE inside BlockTripWire.getActualState etc.
                    || ("(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;)V".equals(mdesc)
                        || "(Let;Latc;)V".equals(mdesc))
                        && (mname.equals("addBlockDestroyEffects") || mname.equals("func_180533_a") || mname.equals("a"))
                    // func_180534_a / addBlockHitEffects — dig particles; same
                    // transient null `world` during session swap → NPE at
                    // ParticleManager:500 inside BlockDust getActualState.
                    || ("(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/EnumFacing;)V".equals(mdesc)
                        || "(Let;Lawt;)V".equals(mdesc))
                        && (mname.equals("addBlockHitEffects") || mname.equals("func_180534_a") || mname.equals("a"))) {
                    patched[0] = true;
                    return new MethodVisitor(Opcodes.ASM5, mv) {
                        private final Label tryStart = new Label();
                        private final Label tryEnd = new Label();
                        private final Label npeHandler = new Label();
                        @Override public void visitCode() {
                            super.visitCode();
                            mv.visitTryCatchBlock(tryStart, tryEnd, npeHandler,
                                    "java/lang/NullPointerException");
                            mv.visitLabel(tryStart);
                        }
                        @Override public void visitMaxs(int maxStack, int maxLocals) {
                            mv.visitLabel(tryEnd);
                            mv.visitLabel(npeHandler);
                            mv.visitInsn(Opcodes.POP);
                            mv.visitInsn(Opcodes.RETURN);
                            super.visitMaxs(maxStack, maxLocals);
                        }
                    };
                }
                return mv;
            }
        }, 0);
        if (!patched[0]) NexusPlugin.LOGGER.warn("ParticleManager.updateEffects not patched");
        return patched[0] ? cw.toByteArray() : in;
    }

    /**
     * RenderManager: head-guard `renderEntity` (func_188391_a, obf
     * a(Entity,DDDFFZ)V) — 实体渲染的唯一入口，vanilla 的 renderEntityStatic
     * 和外部 mod 都走这里，注入一处即可覆盖两条路径。
     */
    private byte[] transformRenderManager(byte[] in) {
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = writer(cr);
        final boolean[] patched = {false};
        cr.accept(new ClassVisitor(Opcodes.ASM5, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String mname, String mdesc,
                                             String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, mname, mdesc, sig, ex);
                // 描述符是唯一稳定的锚点：obf 下方法名就是 a/b/c，名字不可靠。
                boolean isRenderEntity = ("(Lnet/minecraft/entity/Entity;DDDFFZ)V".equals(mdesc)
                        || "(Lvg;DDDFFZ)V".equals(mdesc))
                        && (mname.equals("renderEntity") || mname.equals("func_188391_a") || mname.equals("a"));
                if (!isRenderEntity) return mv;
                patched[0] = true;
                return new MethodVisitor(Opcodes.ASM5, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        mv.visitVarInsn(Opcodes.ALOAD, 0);          // this (RenderManager)
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_INTERNAL,
                                "renderViewEntityGuard", "(Ljava/lang/Object;)Z", false);
                        Label cont = new Label();
                        mv.visitJumpInsn(Opcodes.IFEQ, cont);
                        mv.visitInsn(Opcodes.RETURN);
                        mv.visitLabel(cont);
                    }
                };
            }
        }, 0);
        if (!patched[0]) NexusPlugin.LOGGER.warn("RenderManager.renderEntity not patched");
        else NexusPlugin.LOGGER.info("[xform] RenderManager.renderEntity guarded");
        return patched[0] ? cw.toByteArray() : in;
    }

    // Background-thread GL / shared-singleton no-op guards.
    //
    // Each entry is a (mcp, srg, obf) name triple plus the method descriptor.
    // A bound bot thread must never reach GL state or a shared singleton;
    // the head guard returns early so the real body only ever runs on the
    // client thread (LOCAL==null → onBotThread false → fall through).

    /** LoadingScreenRenderer (bie): all GL entry points used by loadWorld. */
    private static final String[][] LSR_METHODS = {
        // displaySavingString  func_73720_a  a(Ljava/lang/String;)V
        {"displaySavingString", "func_73720_a", "a", "(Ljava/lang/String;)V"},
        // resetProgressAndMessage func_73721_b b(Ljava/lang/String;)V
        {"resetProgressAndMessage", "func_73721_b", "b", "(Ljava/lang/String;)V"},
        // displayLoadingString  func_73719_c  c(Ljava/lang/String;)V
        {"displayLoadingString", "func_73719_c", "c", "(Ljava/lang/String;)V"},
        // displayString (private, does the glClear) func_73722_d d(Ljava/lang/String;)V
        {"displayString", "func_73722_d", "d", "(Ljava/lang/String;)V"},
    };

    /** TileEntityRendererDispatcher (bwx): setWorld mutates shared `instance`. */
    private static final String[][] TER_METHODS = {
        // setWorld  func_147543_a  a(Lamu;)V  (Lnet/minecraft/world/World;)V
        {"setWorld", "func_147543_a", "a", "(Lnet/minecraft/world/World;)V"},
        {"setWorld", "func_147543_a", "a", "(Lamu;)V"},
    };

    /** SoundHandler (cho): every `()V` lifecycle method mutates the shared
     *  sound engine (stop/pause/resume/unload/update).  Guard them all on bot
     *  threads; the exact srg→mcp pairing is irrelevant — matching any of
     *  these no-arg void methods keeps a bot from touching OpenAL. */
    private static final String[][] SH_METHODS = {
        {"a", "func_147689_b", "a", "()V"},
        {"b", "func_147690_c", "b", "()V"},
        {"c", "func_147685_d", "c", "()V"},
        {"d", "func_147687_e", "d", "()V"},
        {"e", "func_73660_a",  "e", "()V"},
        // belt-and-braces: also match by MCP names in case the runtime jar
        // is the deobf one (dev-env runs), where methods keep mcp names.
        {"stopSounds",  "func_147685_d", "c", "()V"},
        {"pauseSounds", "func_147687_e", "d", "()V"},
        {"resumeSounds","func_147690_c", "b", "()V"},
        {"unloadSounds","func_147689_b", "a", "()V"},
        {"update",      "func_73660_a",  "e", "()V"},
        // Bot-side world/entity handlers can reach these shared OpenAL entry
        // points indirectly, so guard both immediate and delayed playback as
        // well as listener/stop mutations.
        {"playSound",        "func_147682_a", "a", "(Lnet/minecraft/client/audio/ISound;)V"},
        {"playSound",        "func_147682_a", "a", "(Lcgt;)V"},
        {"playDelayedSound", "func_147681_a", "b", "(Lnet/minecraft/client/audio/ISound;I)V"},
        {"playDelayedSound", "func_147681_a", "b", "(Lcgt;I)V"},
        {"setListener",      "func_147691_a", "a", "(Lnet/minecraft/entity/player/EntityPlayer;F)V"},
        {"setListener",      "func_147691_a", "a", "(Laed;F)V"},
        {"setListener",      "func_147691_a", "a", "(Lnet/minecraft/entity/Entity;F)V"},
        {"setListener",      "func_147691_a", "a", "(Lvg;F)V"},
        {"stopSound",        "func_147683_b", "b", "(Lnet/minecraft/client/audio/ISound;)V"},
        {"stopSound",        "func_147683_b", "b", "(Lcgt;)V"},
    };

    /** GuiIngame (biq): addChatMessage(ChatType, ITextComponent) dispatches to
     *  every registered IChatListener — NormalChatListener reaches the shared
     *  CustomGuiNewChat singleton whose chatLines ArrayList is not thread-safe.
     *  Signature: func_191742_a(Lnet/minecraft/util/text/ChatType;
     *  Lnet/minecraft/util/text/ITextComponent;)V → obf (Lhf;Lhh;)V. */
    private static final String[][] GIC_METHODS = {
        {"addChatMessage", "func_191742_a", "a",
         "(Lnet/minecraft/util/text/ChatType;Lnet/minecraft/util/text/ITextComponent;)V"},
        {"addChatMessage", "func_191742_a", "a", "(Lhf;Lhh;)V"},
    };

    /**
     * Inject `if (NexusCtx.onBotThread()) return;` at the head of every
     * method whose (name, desc) matches an entry in `methods`.
     * All target methods are `()V`-returning so a bare RETURN is always the
     * correct epilogue for the guard.
     */
    private byte[] transformBotNoop(byte[] in, String[][] methods, String tag) {
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = writer(cr);
        final int[] patched = {0};
        cr.accept(new ClassVisitor(Opcodes.ASM5, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String mname, String mdesc,
                                             String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, mname, mdesc, sig, ex);
                boolean match = false;
                for (String[] m : methods) {
                    // m = {mcp, srg, obf, desc}
                    if (mdesc.equals(m[3]) &&
                            (mname.equals(m[0]) || mname.equals(m[1]) || mname.equals(m[2]))) {
                        match = true;
                        break;
                    }
                }
                if (!match) return mv;
                patched[0]++;
                return new MethodVisitor(Opcodes.ASM5, mv) {
                    @Override public void visitCode() {
                        super.visitCode();
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, CTX_INTERNAL,
                                "onBotThread", "()Z", false);
                        Label cont = new Label();
                        mv.visitJumpInsn(Opcodes.IFEQ, cont);
                        mv.visitInsn(Opcodes.RETURN);
                        mv.visitLabel(cont);
                    }
                };
            }
        }, 0);
        if (patched[0] > 0) {
            NexusPlugin.LOGGER.info("[xform] {} botNoop methods patched={}", tag, patched[0]);
        }
        return patched[0] > 0 ? cw.toByteArray() : in;
    }

    /**
     * Entity: no-push guard for 人物同步. Two accounts stacked on the same
     * block would otherwise shove each other apart every tick, which is the
     * single most visible tell that they are separate players.
     *
     * canBePushed()  → push a `false` return when the group is stacked.
     * collideWithNearbyEntities() → skip the whole push resolution.
     *
     * The decision is a pure lookup (SyncHooks.noPush), so this costs one
     * static call per query when nothing is synced.
     */
    private byte[] transformEntity(byte[] in, boolean pushTargets) {
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = writer(cr);
        final int[] patched = {0};
        final boolean withPush = pushTargets;
        cr.accept(new ClassVisitor(Opcodes.ASM5, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String mname, String mdesc,
                                             String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, mname, mdesc, sig, ex);
                boolean canPush = withPush && mdesc.equals("()Z")
                        && (mname.equals("canBePushed") || mname.equals("func_70104_M")
                            || mname.equals("M"));
                boolean collide = withPush && mdesc.equals("()V")
                        && (mname.equals("collideWithNearbyEntities")
                            || mname.equals("func_82167_n") || mname.equals("n"));
                // 同步跟随者贴在主控身上，如果还能被选中，左键打面前的怪会先落到
                // 自己人身上；还能参与碰撞就会互相挤。让它不可被碰撞/选中。
                boolean pickable = mdesc.equals("()Z")
                        && (mname.equals("canBeCollidedWith") || mname.equals("func_70067_L"));
                if (!canPush && !collide && !pickable) return mv;
                patched[0]++;
                return new MethodVisitor(Opcodes.ASM5, mv) {
                    @Override public void visitCode() {
                        super.visitCode();
                        mv.visitVarInsn(Opcodes.ALOAD, 0);
                        if (pickable) {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, SYNC_INTERNAL,
                                    "noCollide", "(Ljava/lang/Object;)Z", false);
                        } else {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, SYNC_INTERNAL,
                                    "noPush", "(Lnet/minecraft/entity/Entity;)Z", false);
                        }
                        Label cont = new Label();
                        mv.visitJumpInsn(Opcodes.IFEQ, cont);
                        if (canPush || pickable) {
                            mv.visitInsn(Opcodes.ICONST_0);
                            mv.visitInsn(Opcodes.IRETURN);
                        } else {
                            mv.visitInsn(Opcodes.RETURN);
                        }
                        mv.visitLabel(cont);
                    }
                };
            }
        }, 0);
        if (patched[0] > 0) {
            NexusPlugin.LOGGER.info("[xform] {} sync guards patched={}", cr.getClassName(), patched[0]);
        }
        return patched[0] > 0 ? cw.toByteArray() : in;
    }

    /**
     * EntityPlayerSP: rewrite every INVOKEVIRTUAL/INVOKESPECIAL call to
     * Minecraft.getRenderViewEntity() (SRG func_175606_aa, obf bib.aa) to
     * INVOKESTATIC NexusCtx.getRenderViewEntity(Minecraft). The vanilla call
     * always resolves to the shared (mounted) camera entity; on a bound bot
     * thread that makes isCurrentViewEntity() false and updateEntityActionState
     * drops movementInput into the floor — background bots attack but never
     * chase. Uses COMPUTE_MAXS: the rewrite swaps an invokevirtual for an
     * invokestatic with an identical stack shape (receiver consumed as arg),
     * so original frames stay valid.
     */
    private byte[] transformEntityPlayerSP(byte[] in) {
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        final int[] patched = {0};
        cr.accept(new ClassVisitor(Opcodes.ASM5, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String mname, String mdesc,
                                             String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, mname, mdesc, sig, ex);
                return new MethodVisitor(Opcodes.ASM5, mv) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        boolean isRVE = (opcode == Opcodes.INVOKEVIRTUAL
                                        || opcode == Opcodes.INVOKESPECIAL)
                                && ("net/minecraft/client/Minecraft".equals(owner)
                                        || "bib".equals(owner))
                                && ("getRenderViewEntity".equals(name)
                                        || "func_175606_aa".equals(name)
                                        || "aa".equals(name))
                                && ("()Lnet/minecraft/entity/Entity;".equals(descriptor)
                                        || "()Lvg;".equals(descriptor));
                        if (isRVE) {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, CTX_INTERNAL,
                                    "getRenderViewEntity",
                                    "(Lnet/minecraft/client/Minecraft;)Lnet/minecraft/entity/Entity;",
                                    false);
                            patched[0]++;
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }
                };
            }
        }, 0);
        if (patched[0] > 0) {
            NexusPlugin.LOGGER.info("[xform] EntityPlayerSP getRenderViewEntity rerouted sites={}", patched[0]);
        }
        return patched[0] > 0 ? cw.toByteArray() : in;
    }
}
