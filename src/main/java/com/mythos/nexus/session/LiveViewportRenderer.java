package com.mythos.nexus.session;

import com.mythos.nexus.core.NexusHooks;
import com.mythos.nexus.core.NexusPlugin;
import com.mythos.nexus.core.NexusRefs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.culling.ClippingHelperImpl;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.IRenderChunkFactory;
import net.minecraft.client.renderer.chunk.ListedRenderChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.Project;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Offscreen per-session viewport renderer for the account manager's LIVE card
 * mode. Each session gets its own RenderGlobal + ViewFrustum so background
 * bots render their own world into a small framebuffer; the FBO texture is
 * then blitted onto the card like a screenshot.
 *
 * Key constraints discovered from decompiled 1.12.2 sources:
 *  - ONE shared ChunkRenderDispatcher: worker thread count scales with cores,
 *    not bots — each RenderGlobal gets a new dispatcher in loadRenderers, so
 *    we rebind RenderGlobal.renderDispatcher to a shared instance and cap its
 *    private renderDistanceChunks field to keep the RenderChunk matrix small.
 *  - RenderGlobal.renderSky reads mc.player (not renderViewEntity) for the
 *    horizon position — the session context swap already covers this.
 *  - renderEntities → entityOutlineFramebuffer bind yanks back to the main
 *    FBO; we skip passes that hit it (no outlines on preview).
 *  - RenderChunk.setPosition does GL calls — everything below runs on the
 *    render thread inside NexusScreen.drawScreen.
 *
 * ponytail: one RenderGlobal per session with a small renderDistance override
 * is far simpler than a single shared RG + world re-binds; ~1-2MB FBO and a
 * small ViewFrustum per session is cheap against 15-40MB for a full grid.
 */
public final class LiveViewportRenderer {

    private LiveViewportRenderer() {}

    /** Global fallback preview resolution — sessions without a per-bot
     *  override use this. Bounds: min 320×180, max = display resolution. */
    public static int VIEW_W = 320;
    public static int VIEW_H = 180;

    /** Read from config + clamp. Called from NexusScreen.relayout(). */
    public static void applyViewSize(int w, int h) {
        Minecraft mc = Minecraft.getMinecraft();
        int maxW = mc.displayWidth, maxH = mc.displayHeight;
        VIEW_W = Math.max(320, Math.min(w, maxW));
        VIEW_H = Math.max(180, Math.min(h, maxH));
        // Resize all live FBOs — next ensurePreview recreates at the new size.
        for (Preview p : PREVIEWS.values()) p.width = -1;
    }

    /** Per-session view size; falls back to the global VIEW_W/H. */
    public static int viewW(BotSession s) {
        String v = com.mythos.nexus.core.NexusConfig.liveViewSizeFor(sessionKey(s));
        if (v == null) return VIEW_W;
        int[] wh = parseWH(v, VIEW_W, VIEW_H);
        return wh[0];
    }
    public static int viewH(BotSession s) {
        String v = com.mythos.nexus.core.NexusConfig.liveViewSizeFor(sessionKey(s));
        if (v == null) return VIEW_H;
        int[] wh = parseWH(v, VIEW_W, VIEW_H);
        return wh[1];
    }
    private static int[] parseWH(String v, int dw, int dh) {
        try {
            String[] a = v.split("x");
            int w = Integer.parseInt(a[0]), h = Integer.parseInt(a[1]);
            Minecraft mc = Minecraft.getMinecraft();
            return new int[]{Math.max(320, Math.min(w, mc.displayWidth)),
                             Math.max(180, Math.min(h, mc.displayHeight))};
        } catch (Throwable t) { return new int[]{dw, dh}; }
    }
    private static String sessionKey(BotSession s) {
        return s.username + "@" + s.serverHost + ":" + s.serverPort;
    }

    /** Capped view distance for previews (chunks). Small = cheap ViewFrustum,
     *  still enough to see a base. */
    private static final int PREVIEW_RD = 2;

    /** Per-session preview state — own RenderGlobal + ViewFrustum so the
     *  main world's chunk VBOs never rebuild from camera flips. Built by
     *  hand (no loadRenderers → no stopChunkUpdates join, no burst). */
    private static final class Preview {
        RenderGlobal rg;
        Framebuffer fb;
        int width = -1, height = -1;
    }
    private static final Map<BotSession, Preview> PREVIEWS = new java.util.concurrent.ConcurrentHashMap<>();
    
    /** Shared dispatcher for all previews — one instance avoids OOM from
     *  per-preview builder pools (~100MB direct each). */
    private static ChunkRenderDispatcher sharedDispatcher;

    /** Sessions that failed preview init — skip retrying them or the client
     *  thread loops on OOM forever. */
    private static final java.util.Set<BotSession> FAILED_SESSIONS =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** Max new preview inits per tick — RenderGlobal ctor compiles the
     *  entity-outline shader + ViewFrustum allocs ~256 VBOs. */
    private static final int MAX_INITS_PER_TICK = 1;

    private static Field fRgWorld, fRgDispatcher, fRgViewFrustum, fRgRenderDistance,
            fRgRenderChunkFactory, fRgCountUpdates,
            fMcDisplayWidth, fMcDisplayHeight, fErFarPlane,
            fErFrameCount, fMcRenderGlobal, fMcRenderViewEntity, fMcPlayer, fMcWorld,
            fMcGameSettings, fMcTextureManager, fMcEffectRenderer, fBufIsDrawing;
    private static Method mRgUpdateChunks, mRgSetupTerrain,
            mRgRenderBlockLayer, mRgRenderEntities, mRgRenderSky, mErSetupFog,
            mErUpdateFogColor, mErSetupCameraTransform, mErGetFOVModifier;

    static {
        try {
            fRgWorld            = pick(RenderGlobal.class, "world", "field_72769_h");
            fRgDispatcher       = pick(RenderGlobal.class, "renderDispatcher", "field_174995_M");
            fRgViewFrustum      = pick(RenderGlobal.class, "viewFrustum", "field_175008_n");
            fRgRenderDistance   = pick(RenderGlobal.class, "renderDistanceChunks", "field_72739_F");
            fRgRenderChunkFactory = pick(RenderGlobal.class, "renderChunkFactory", "field_175007_a");
            fRgCountUpdates     = pick(RenderGlobal.class, "renderEntitiesStartupCounter", "field_72740_G");
            mRgUpdateChunks     = pickM(RenderGlobal.class, "updateChunks", "func_174967_a", long.class);
            mRgSetupTerrain     = pickM(RenderGlobal.class, "setupTerrain", "func_174970_a",
                                        Entity.class, double.class, ICamera.class, int.class, boolean.class);
            mRgRenderBlockLayer = pickM(RenderGlobal.class, "renderBlockLayer", "func_174977_a",
                                        BlockRenderLayer.class, double.class, int.class, Entity.class);
            mRgRenderEntities   = pickM(RenderGlobal.class, "renderEntities", "func_180446_a",
                                        Entity.class, ICamera.class, float.class);
            mRgRenderSky        = pickM(RenderGlobal.class, "renderSky", "func_174976_a", float.class, int.class);

            fMcDisplayWidth  = pick(Minecraft.class, "displayWidth", "field_71443_c");
            fMcDisplayHeight = pick(Minecraft.class, "displayHeight", "field_71440_d", "e");
            fMcRenderGlobal  = pick(Minecraft.class, "renderGlobal", "field_71438_f", "g");
            fMcRenderViewEntity = pick(Minecraft.class, "renderViewEntity", "field_175622_Z", "ad");
            fMcPlayer        = pick(Minecraft.class, "player", "field_71439_g", "h");
            fMcWorld         = pick(Minecraft.class, "world", "field_71441_e", "f");
            fMcGameSettings  = pick(Minecraft.class, "gameSettings", "field_71474_y", "t");
            fMcTextureManager = pick(Minecraft.class, "renderEngine", "field_71446_o", "q");
            fMcEffectRenderer = pick(Minecraft.class, "effectRenderer", "field_71452_i", "k");

            fErFarPlane  = pick(net.minecraft.client.renderer.EntityRenderer.class,
                                "farPlaneDistance", "field_78530_s");
            fErFrameCount = pick(net.minecraft.client.renderer.EntityRenderer.class,
                                 "frameCount", "field_175084_ae");
            mErSetupFog          = pickM(net.minecraft.client.renderer.EntityRenderer.class,
                                         "setupFog", "func_78468_a", int.class, float.class);
            mErUpdateFogColor    = pickM(net.minecraft.client.renderer.EntityRenderer.class,
                                         "updateFogColor", "func_78466_h", float.class);
            mErSetupCameraTransform = pickM(net.minecraft.client.renderer.EntityRenderer.class,
                                         "setupCameraTransform", "func_78479_a", float.class, int.class);
            mErGetFOVModifier    = pickM(net.minecraft.client.renderer.EntityRenderer.class,
                                         "getFOVModifier", "func_78481_a", float.class, boolean.class);

            // BufferBuilder.isDrawing — used by the finally teardown to detect a
            // half-built batch so it can flush instead of leaving the shared
            // Tessellator stuck in "Already building!" for the next main-frame blit.
            fBufIsDrawing = pick(net.minecraft.client.renderer.BufferBuilder.class,
                    "isDrawing", "field_179010_r");
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }
    private static Field pick(Class<?> c, String mcp, String srg) throws Exception {
        return pick(c, mcp, srg, null);
    }
    private static Field pick(Class<?> c, String mcp, String srg, String obf) throws Exception {
        Field f;
        try { f = c.getDeclaredField(mcp); }
        catch (NoSuchFieldException e) {
            try { f = c.getDeclaredField(srg); }
            catch (NoSuchFieldException e2) {
                if (obf == null) {
                    throw new NoSuchFieldException("no field " + c.getName() + "#" + mcp
                            + " (tried " + srg + ")");
                }
                try { f = c.getDeclaredField(obf); }
                catch (NoSuchFieldException e3) {
                    // The bare name only says which spelling failed last; the
                    // class + every name tried is what makes a crash report
                    // (ExceptionInInitializerError below) actionable.
                    throw new NoSuchFieldException("no field " + c.getName() + "#" + mcp
                            + " (tried " + srg + ", " + obf + ")");
                }
            }
        }
        f.setAccessible(true);
        return f;
    }
    private static Method pickM(Class<?> c, String mcp, String srg, Class<?>... sig) throws Exception {
        Method m;
        try { m = c.getDeclaredMethod(mcp, sig); }
        catch (NoSuchMethodException e) {
            try { m = c.getDeclaredMethod(srg, sig); }
            catch (NoSuchMethodException e2) {
                throw new NoSuchMethodException(
                        "no method " + NexusRefs.sig(c, mcp, sig) + " (tried " + srg + ")");
            }
        }
        m.setAccessible(true);
        return m;
    }

    //  Public API — called from NexusCenter.draw while CardMode.LIVE is set.

    /**
     * Render every ONLINE, non-mounted session's world into its preview FBO
     * (mounted session owns the real screen — its card shows a placeholder).
     * Must be called on the render thread inside drawScreen before card blits.
     */
    /** Grid-mode render cap per call. At 30 bots a cap of 2 needs ~750ms to
     *  cover all — frames look stale for ~15 ticks, causing the
     *  disappear/reappear flicker the user saw. 4 is still bounded.
     *  Fullscreen mode bypasses this cap entirely (see renderAllTick). */
    private static final int MAX_RENDERS_PER_CALL = 4;
    /** Budget per renderAllTick call — bail early so the tick stays fast.
     *  Fullscreen skips this budget too. */
    private static final long BUDGET_NANOS = 40_000_000L; // 40ms
    /** Min ticks between preview inits — loadRenderers is expensive. */
    private static final int INIT_COOLDOWN_TICKS = 10;
    /** Round-robin cursor for fair scheduling. */
    private static int rrCursor = 0;
    /** Ticks since last preview init. */
    /** Which sessions the UI wants rendered — set by drawScreen each frame. */
    private static volatile java.util.List<BotSession> pendingSessions = java.util.Collections.emptyList();

    /** The session currently shown in LIVE fullscreen — its world.tick is
     *  forced to 100% so the preview actually animates. Set by NexusCenter
     *  during draw; cleared on mode switch / card closed. Volatile because
     *  draw (render thread) writes it and SessionTicker reads it. */
    public static volatile BotSession fullscreenSession = null;

    /**
     * Called from NexusCenter.draw inside drawScreen. Just records which
     * sessions the UI wants rendered — actual GL work happens in
     * renderAllTick on the next runSessionTicks tick.
     */
    public static void renderAll(java.util.List<BotSession> sessions) {
        pendingSessions = java.util.Collections.unmodifiableList(sessions);
    }

    /**
     * Called from NexusHooks.runSessionTicks on the client thread — has
     * full GL context and no competing GUI draw. Does the actual
     * ensurePreview + renderIntoFramebuffer for all visible sessions.
     */
    public static void renderAllTick() {
        // Skip previews while a mount is swapping the shared mc singleton —
        // renderOne's withContext swap + displayWidth override would either
        // run on half-swapped fields or leave the shared Tessellator in
        // "Already building!" state when updateFramebufferSize fires.
        if (SessionManager.isMountInProgress()) return;
        java.util.List<BotSession> sessions = pendingSessions;
        if (sessions.isEmpty()) return;
        Minecraft mc = NexusHooks.resolveMinecraft();
        if (mc == null || mc.renderGlobal == null) return;
        // Fullscreen live = one session every tick, no round-robin cap.
        // That's what keeps the fullscreen view smooth — same cadence as
        // the real renderer. Grid still caps at MAX_RENDERS_PER_CALL.
        boolean fullscreen = (sessions.size() == 1);
        // Pin the actually-rendered session — NexusCenter's fullscreenSession
        // only covers the context-menu fullscreen; a 1×1 grid takes the same
        // "sessions.size()==1" path here and must pin too, or adaptive leaves
        // that bot throttled and the preview freezes.
        fullscreenSession = fullscreen ? sessions.get(0) : null;
        int inits = 0, renders = 0;
        long deadline = System.nanoTime() + (fullscreen ? Long.MAX_VALUE : BUDGET_NANOS);
        int n = sessions.size();
        for (int i = 0; i < n; i++) {
            if (!fullscreen && renders >= MAX_RENDERS_PER_CALL) break;
            if (System.nanoTime() > deadline) break;
            BotSession s = sessions.get(fullscreen ? i : (rrCursor + i) % n);
            if (FAILED_SESSIONS.contains(s)) continue;   // already failed, don't retry
            if (s.state != BotSession.State.ONLINE || s.world == null || s.player == null)
                continue;
            if (SessionManager.get().isForeground(s)) continue;
            Preview p = PREVIEWS.get(s);
            if (p == null || p.rg == null) {
                if (inits >= MAX_INITS_PER_TICK) continue;
                inits++;
            }
            renders++;
            // A preview must never take the main frame down with it — a throw here
            // propagates into Minecraft.runTick and crashes the whole client.
            try {
                renderOne(mc, s);
            } catch (Throwable t) {
                NexusPlugin.LOGGER.warn("live-viewport: renderOne failed for {}", s.username, t);
            }
        }
        if (!fullscreen) rrCursor = (rrCursor + 1) % Math.max(1, n);
    }

    /** The preview FBO for this session, or null when it can't render. */
    public static Framebuffer framebufferFor(BotSession s) {
        Preview p = PREVIEWS.get(s);
        return p == null ? null : p.fb;
    }

    /** Release GL resources for a session that disconnected or was removed. */
    public static void dispose(BotSession s) {
        FAILED_SESSIONS.remove(s);  // clear failure flag — give it another shot
        // deleteFramebuffer() is a GL call — it must run on the render/client
        // thread, not the Netty IO thread that fires onChannelClosed. Removing
        // the entry from PREVIEWS here is safe (ConcurrentHashMap), so a render
        // pass in flight simply skips this session; the actual GL teardown is
        // deferred to the client thread below.
        final Preview p = PREVIEWS.remove(s);
        if (p == null) return;
        Minecraft mc = NexusHooks.resolveMinecraft();
        if (mc == null || mc.isCallingFromMinecraftThread()) {
            deletePreview(p);
            return;
        }
        try {
            mc.addScheduledTask(() -> deletePreview(p));
        } catch (Throwable t) {
            deletePreview(p); // fallback — better a leaked FBO than a lost dispose
        }
    }

    /** Drop everything (screen closed / mode switched off / world swap). */
    public static void disposeAll() {
        for (Preview p : PREVIEWS.values()) deletePreview(p);
        PREVIEWS.clear();
    }


    private static void deletePreview(Preview p) {
        // ponytail: skip setWorldAndLoadRenderers(null) — it joins all
        // worker threads in stopChunkUpdates which can block the client
        // thread for seconds during a mount switch. Dropping the ref is
        // enough; the dispatcher threads die with the RenderGlobal.
        try {
            if (p.fb != null) p.fb.deleteFramebuffer();
        } catch (Throwable ignored) {}
    }

    /**
     * Render one session's world into its offscreen framebuffer. We swap the
     * session context in (world/player/viewEntity/renderGlobal), point the
     * render pipeline at the session's own RenderGlobal + small display size,
     * run the terrain+entity passes into the FBO, then restore everything.
     */
    private static void renderOne(Minecraft mc, BotSession s) {
        Preview p = PREVIEWS.computeIfAbsent(s, k -> new Preview());
        try {
            ensurePreview(mc, s, p);
        } catch (Throwable t) {
            NexusPlugin.LOGGER.warn("live-viewport: preview init failed for {}", s.username, t);
            FAILED_SESSIONS.add(s);                     // mark failed — don't retry
            PREVIEWS.remove(s);
            return;
        }
        if (p.rg == null || p.fb == null) return;
        //      proven push/pop + foreign-entity guard + renderGlobal restore) ----
        s.withContext(new Runnable() {
            @Override public void run() {
                try {
                    renderIntoFramebuffer(mc, s, p);
                } catch (Throwable t) {
                    NexusPlugin.LOGGER.warn("live-viewport: render failed for {}", s.username, t);
                }
            }
        });
    }

    /**
     * Build the session's own RenderGlobal + ViewFrustum. loadRenderers
     * on OUR dispatcher is safe — stopChunkUpdates joins instantly (no
     * queued work on a fresh dispatcher). The ViewFrustum is bound to
     * this session's world only, so the main world never flips.
     */
    private static void ensurePreview(Minecraft mc, BotSession s, Preview p) throws Exception {
        if (p.rg == null) {
            RenderGlobal rg = new RenderGlobal(mc);
            fRgWorld.set(rg, s.world);
            // Share ONE ChunkRenderDispatcher across all previews.
            // Each dispatcher allocates ~builderCount × 2.5MB direct memory;
            // one per preview OOMs. Sharing is safe because we set
            // P = gameSettings.renderDistanceChunks below, so setupTerrain
            // never calls loadRenderers → stopChunkUpdates on it.
            if (sharedDispatcher == null) sharedDispatcher = new ChunkRenderDispatcher();
            fRgDispatcher.set(rg, sharedDispatcher);
            // P MUST equal gameSettings.renderDistanceChunks or setupTerrain
            // re-calls loadRenderers → stopChunkUpdates → deadlock.
            fRgRenderDistance.setInt(rg, mc.gameSettings.renderDistanceChunks);
            fRgRenderChunkFactory.set(rg, (IRenderChunkFactory)(w, r, i) -> new ListedRenderChunk(w, r, i));
            ViewFrustum vf = new ViewFrustum(s.world, PREVIEW_RD, rg,
                    (w, r, i) -> new ListedRenderChunk(w, r, i));
            vf.updateChunkPositions(s.player.posX, s.player.posZ);
            fRgViewFrustum.set(rg, vf);
            fRgCountUpdates.setInt(rg, 0);
            p.rg = rg;
        }
        int pw = viewW(s), ph = viewH(s);
        if (p.fb == null || p.width != pw || p.height != ph) {
            if (p.fb != null) p.fb.deleteFramebuffer();
            p.fb = new Framebuffer(pw, ph, true);
            p.width = pw;
            p.height = ph;
        }
        if (fRgWorld.get(p.rg) != s.world) fRgWorld.set(p.rg, s.world);
    }

    /**
     * Run the world-render passes into p.fb. Mirrors
     * EntityRenderer.renderWorldPass minus the parts that yank the FBO back
     * (entity outlines) or need the real display (hand, debug, clouds).
     */
    private static void renderIntoFramebuffer(Minecraft mc, BotSession s, Preview p) throws Exception {
        WorldClient world = s.world;
        EntityPlayerSP player = s.player;
        if (world == null || player == null) return;
        if (Boolean.getBoolean("nexus.debug"))
            NexusPlugin.LOGGER.info("live-vp {}: player={} pos={},{},{} rve={}",
                    s.username, player.getClass().getSimpleName(),
                    player.posX, player.posY, player.posZ,
                    mc.getRenderViewEntity() == null ? "null"
                            : mc.getRenderViewEntity().getClass().getSimpleName());
        RenderGlobal rg = p.rg;
        if (rg == null) return;

        Object prevRg       = fMcRenderGlobal.get(mc);
        Object prevWidth    = fMcDisplayWidth.get(mc);
        Object prevHeight   = fMcDisplayHeight.get(mc);
        fMcRenderGlobal.set(mc, rg);
        int vw = p.width, vh = p.height;
        fMcDisplayWidth.setInt(mc, vw);
        fMcDisplayHeight.setInt(mc, vh);
        fRgWorld.set(rg, world);
        // farPlaneDistance must match our small render distance or the
        // projection far plane still assumes 12-chunk fog.
        float prevFar = fErFarPlane.getFloat(mc.entityRenderer);
        fErFarPlane.setFloat(mc.entityRenderer, (float) (PREVIEW_RD * 16));

        try {
            Framebuffer fb = p.fb;
            fb.framebufferClear();
            fb.bindFramebuffer(false);
            GlStateManager.enableCull();
            GlStateManager.viewport(0, 0, vw, vh);
            mErUpdateFogColor.invoke(mc.entityRenderer, 1.0F);
            GlStateManager.clear(16640);                       // GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT
            mErSetupCameraTransform.invoke(mc.entityRenderer, 1.0F, 0);
            ActiveRenderInfo.updateRenderInfo(player, false);
            ClippingHelperImpl.getInstance();
            ICamera icamera = new Frustum();
            // lastTickPos updates only inside the session's world.tick — at
            // low adaptive rates (5%/20Hz = 1Hz) it's stale for hundreds of
            // ms, freezing the preview. Use the live interpolated position
            // so the camera tracks the bot even while its tick is throttled.
            double d0 = player.posX;
            double d1 = player.posY + player.getEyeHeight();
            double d2 = player.posZ;
            icamera.setPosition(d0, d1, d2);

            float fov = (Float) mErGetFOVModifier.invoke(mc.entityRenderer, 1.0F, true);
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.loadIdentity();
            Project.gluPerspective(fov, (float) vw / vh, 0.05F,
                    (float) (PREVIEW_RD * 16) * 2.0F);
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);

            mErSetupFog.invoke(mc.entityRenderer, -1, 1.0F);
            mRgRenderSky.invoke(rg, 1.0F, 0);
            mErSetupFog.invoke(mc.entityRenderer, 0, 1.0F);

            mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
            RenderHelper.disableStandardItemLighting();
            int savedFrame = fErFrameCount.getInt(mc.entityRenderer);
            mRgSetupTerrain.invoke(rg, player, 1.0D, icamera,
                    savedFrame, player.isSpectator());
            // restore frameCount — incrementing shared entityRenderer state
            // corrupts the main pass's occlusion tracking
            fErFrameCount.setInt(mc.entityRenderer, savedFrame);
            mRgUpdateChunks.invoke(rg, Long.MAX_VALUE);

            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.pushMatrix();
            GlStateManager.disableAlpha();
            mRgRenderBlockLayer.invoke(rg, BlockRenderLayer.SOLID, 1.0D, 0, player);
            GlStateManager.enableAlpha();
            mc.getTextureManager().getTexture(TextureMap.LOCATION_BLOCKS_TEXTURE)
                    .setBlurMipmap(false, mc.gameSettings.mipmapLevels > 0);
            mRgRenderBlockLayer.invoke(rg, BlockRenderLayer.CUTOUT_MIPPED, 1.0D, 0, player);
            mc.getTextureManager().getTexture(TextureMap.LOCATION_BLOCKS_TEXTURE)
                    .restoreLastBlurMipmap();
            mc.getTextureManager().getTexture(TextureMap.LOCATION_BLOCKS_TEXTURE)
                    .setBlurMipmap(false, false);
            mRgRenderBlockLayer.invoke(rg, BlockRenderLayer.CUTOUT, 1.0D, 0, player);
            mc.getTextureManager().getTexture(TextureMap.LOCATION_BLOCKS_TEXTURE)
                    .restoreLastBlurMipmap();
            GlStateManager.shadeModel(GL11.GL_FLAT);
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);

            // entities — pass 0 (solid). Skip outline path: entityOutline
            // framebuffer binds the MAIN fbo; previews don't need outlines.
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.popMatrix();
            GlStateManager.pushMatrix();
            RenderHelper.enableStandardItemLighting();
            ForgeHooksClient.setRenderPass(0);
            mRgRenderEntities.invoke(rg, player, icamera, 1.0F);
            ForgeHooksClient.setRenderPass(0);
            // enableLightmap inside renderEntities switches the active texture
            // unit to the lightmap — restore it so GUI fonts bind correctly.
            mc.entityRenderer.disableLightmap();
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableAlpha();
            GlStateManager.disableBlend();
            GlStateManager.disableFog();
            GlStateManager.popMatrix();

            fb.unbindFramebuffer();
        } finally {
            // restore the real render targets + world binding
            fMcRenderGlobal.set(mc, prevRg);
            fMcDisplayWidth.set(mc, prevWidth);
            fMcDisplayHeight.set(mc, prevHeight);
            fErFarPlane.setFloat(mc.entityRenderer, prevFar);
            // A throw mid-render leaves the shared Tessellator in "building" state
            // (its draw() never ran) — the next frame's framebufferRenderExt would then
            // hit "Already building!" and crash. Flush it so the batch closes.
            try {
                net.minecraft.client.renderer.Tessellator tess =
                        net.minecraft.client.renderer.Tessellator.getInstance();
                if (fBufIsDrawing.getBoolean(tess.getBuffer())) tess.draw();
            } catch (Throwable ignored) {}
            // fb.unbindFramebuffer() is only on the success path — on a throw the
            // small preview FBO stays bound, so the main pass blits into it. Always
            // unbind, then rebind the real framebuffer.
            try { if (p.fb != null) p.fb.unbindFramebuffer(); } catch (Throwable ignored) {}
            try { mc.getFramebuffer().bindFramebuffer(true); } catch (Throwable ignored) {}
            GlStateManager.viewport(0, 0, mc.displayWidth, mc.displayHeight);
            
            // Restore GUI ortho projection — renderIntoFramebuffer leaves a
            // perspective projection matrix (L336 gluPerspective) that makes GUI
            // text quads project offscreen → black bars. GUI expects ortho.
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.loadIdentity();
            GlStateManager.ortho(0.0D, mc.displayWidth, mc.displayHeight, 0.0D, 1000.0D, 3000.0D);
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.loadIdentity();
            
            // Restore active texture unit to default — renderBlockLayer/renderEntities
            // may leave it on unit 1 (lightmap). GL_TEXTURE0 is in GL13, not GL11.
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
            GlStateManager.bindTexture(0);
        }
    }
}
