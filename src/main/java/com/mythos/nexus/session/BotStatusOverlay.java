package com.mythos.nexus.session;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import com.mythos.nexus.core.NexusRefs;
import com.mythos.nexus.ui.NexusTheme;

/**
 * In-world floating panel above each bot's entity head showing the running
 * path-sequence status. Renders on RenderWorldLastEvent — same signal Mythos
 * uses for its own HUD — and pulls per-owner snapshots via reflection so
 * nexus can compile without MythosScript on the classpath.
 */
public final class BotStatusOverlay {

    private static final BotStatusOverlay INSTANCE = new BotStatusOverlay();
    public static BotStatusOverlay get() { return INSTANCE; }

    private BotStatusOverlay() {}

    private static boolean reflTried;
    private static Class<?> pseCls;
    private static Class<?> psmCls;
    private static Method mGetAllSnapshots;
    private static Method mGetSequence;
    private static Method mSnapGetOwner;
    private static Method mSnapGetSeq;
    private static Method mSnapGetStepIdx;
    private static Method mSnapGetActIdx;
    private static Method mSnapIsAtTarget;
    private static Method mSeqGetSteps;
    private static Method mStepGetGoto;
    private static Method mStepGetActions;
    private static Method mActGetDesc;
    /** EntityRenderer.getFOVModifier(float, boolean) — resolved once; the old
     *  code redid getDeclaredMethod + setAccessible per label, per frame. */
    private static Method mEntityRendererFov;

    private static void initRefl() {
        if (reflTried) return;
        reflTried = true;
        try {
            pseCls = NexusRefs.cls(NexusRefs.PATH_SEQUENCE_EVENT_LISTENER);
            psmCls = NexusRefs.cls(NexusRefs.PATH_SEQUENCE_MANAGER);
            mGetAllSnapshots = NexusRefs.method(pseCls, "getAllActiveProgressSnapshots");
            mGetSequence = NexusRefs.method(psmCls, "getSequence", String.class);
            Class<?> snapCls = NexusRefs.cls(NexusRefs.PROGRESS_SNAPSHOT);
            mSnapGetOwner = NexusRefs.method(snapCls, "getOwner");
            mSnapGetSeq = NexusRefs.method(snapCls, "getSequenceName");
            mSnapGetStepIdx = NexusRefs.method(snapCls, "getStepIndex");
            mSnapGetActIdx = NexusRefs.method(snapCls, "getActionIndex");
            mSnapIsAtTarget = NexusRefs.method(snapCls, "isAtTarget");
            Class<?> seqCls = NexusRefs.cls(NexusRefs.PATH_SEQUENCE);
            mSeqGetSteps = NexusRefs.method(seqCls, "getSteps");
            Class<?> stepCls = NexusRefs.cls(NexusRefs.PATH_STEP);
            mStepGetGoto = NexusRefs.method(stepCls, "getGotoPoint");
            mStepGetActions = NexusRefs.method(stepCls, "getActions");
            Class<?> actCls = NexusRefs.cls(NexusRefs.ACTION_DATA);
            mActGetDesc = NexusRefs.method(actCls, "getDescription");
        } catch (Throwable t) {
            NexusRefs.warnOnce("overlay:init", t,
                    "BotStatusOverlay: sequence status labels disabled: %s", t.toString());
            pseCls = null;
        }
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        initRefl();
        if (pseCls == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.world == null || mc.player == null) return;

        List<?> snapshots;
        try {
            snapshots = (List<?>) mGetAllSnapshots.invoke(null);
        } catch (Throwable t) {
            NexusRefs.warnOnce("overlay:snapshots", t,
                    "BotStatusOverlay: PathSequenceEventListener.getAllActiveProgressSnapshots failed");
            return;
        }
        if (snapshots == null || snapshots.isEmpty()) return;

        java.util.Map<String, String> statusByOwner = new java.util.HashMap<>();
        for (Object snap : snapshots) {
            if (snap == null) continue;
            try {
                String owner = (String) mSnapGetOwner.invoke(snap);
                if (owner == null || owner.isEmpty()) continue;
                if (statusByOwner.containsKey(owner)) continue;
                String line = describe(mc, snap);
                if (line != null && !line.isEmpty()) {
                    statusByOwner.put(owner, line);
                }
            } catch (Throwable t) {
                NexusRefs.warnOnce("overlay:owner", t,
                        "BotStatusOverlay: ProgressSnapshot.getOwner failed");
            }
        }
        if (statusByOwner.isEmpty()) return;

        // project each online bot and draw
        float partialTicks = event.getPartialTicks();
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        int sh = sr.getScaledHeight();

        for (BotSession bot : SessionManager.get().sessions()) {
            if (bot == null) continue;
            EntityPlayerSP p = bot.player;
            if (p == null || !p.isEntityAlive()) continue;
            String owner = bot.username;
            String status = statusByOwner.get(owner);
            if (status == null) continue;

            double ix = lerp(p.prevPosX, p.posX, partialTicks);
            double iy = lerp(p.prevPosY, p.posY, partialTicks) + p.height + 0.6;
            double iz = lerp(p.prevPosZ, p.posZ, partialTicks);

            float[] screen = projectToScreen(mc, ix, iy, iz, sw, sh);
            if (screen == null) continue;
            int sx = (int) screen[0];
            int sy = (int) screen[1];
            if (sx < -50 || sx > sw + 50 || sy < -20 || sy > sh + 20) continue;

            drawPanel(mc.fontRenderer, owner, status, sx, sy);
        }
    }

    private static double lerp(double a, double b, float t) { return a + (b - a) * t; }

    /** Project world pos → screen px. Returns null if behind camera. */
    private static float[] projectToScreen(Minecraft mc, double wx, double wy, double wz,
            int screenW, int screenH) {
        Entity view = mc.getRenderViewEntity();
        if (view == null) view = mc.player;
        double cx = view.lastTickPosX + (view.posX - view.lastTickPosX) * mc.getRenderPartialTicks();
        double cy = view.lastTickPosY + (view.posY - view.lastTickPosY) * mc.getRenderPartialTicks()
                + view.getEyeHeight();
        double cz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * mc.getRenderPartialTicks();
        double dx = wx - cx, dy = wy - cy, dz = wz - cz;
        float yaw = view.prevRotationYaw + (view.rotationYaw - view.prevRotationYaw)
                * mc.getRenderPartialTicks();
        float pitch = view.prevRotationPitch + (view.rotationPitch - view.prevRotationPitch)
                * mc.getRenderPartialTicks();
        double yr = Math.toRadians(yaw);
        double pr = Math.toRadians(pitch);
        // forward = (-sin(yaw)cos(pitch), -sin(pitch), cos(yaw)cos(pitch))
        double fx = -Math.sin(yr) * Math.cos(pr);
        double fy = -Math.sin(pr);
        double fz = Math.cos(yr) * Math.cos(pr);
        double depth = dx * fx + dy * fy + dz * fz;
        if (depth < 0.05) return null;  // behind
        // right = (cos(yaw), 0, sin(yaw))  up = forward × right
        double rx = Math.cos(yr), rz = Math.sin(yr);
        double rightDot = dx * rx + dz * rz;
        double ux = -fz, uz = fx;  // up = cross(fwd, right)
        double uy = 0;
        // proper up = fwd × right gives (−fz,0,fx) horizontal; add pitch
        double upDot = dx * (-fz) + dy * (Math.cos(pr) != 0 ? 0 : 0) + dz * fx;
        // simpler: vertical screen axis = world up minus projection on fwd
        double vertDot = dy - depth * fy;
        double horizDot = rightDot;
        double fovDeg = 70.0;
        try {
            Object er = mc.entityRenderer;
            Method m = mEntityRendererFov;
            if (m == null) {
                m = er.getClass().getDeclaredMethod("func_78481_a");
                m.setAccessible(true);
                mEntityRendererFov = m;
            }
            fovDeg = ((Number) m.invoke(er, mc.getRenderPartialTicks(), true)).doubleValue();
        } catch (Throwable t) {
            NexusRefs.warnOnce("overlay:fov", t,
                    "BotStatusOverlay: EntityRenderer.func_78481_a unavailable, using gameSettings.fovSetting");
            try { fovDeg = mc.gameSettings.fovSetting; } catch (Throwable ignored) {}
        }
        double fovRad = Math.toRadians(fovDeg);
        double halfH = Math.tan(fovRad / 2.0) * depth;
        double halfW = halfH * ((double) screenW / screenH);
        if (halfH <= 0 || halfW <= 0) return null;
        float sx = (float) (screenW / 2.0 - (horizDot / halfW) * (screenW / 2.0));
        float sy = (float) (screenH / 2.0 - (vertDot / halfH) * (screenH / 2.0));
        return new float[]{ sx, sy };
    }

    private static void drawPanel(FontRenderer fr, String owner, String status, int cx, int cy) {
        int pad = 4;
        int lh = fr.FONT_HEIGHT;
        int w1 = fr.getStringWidth(owner);
        int w2 = fr.getStringWidth(status);
        int pw = Math.max(w1, w2) + pad * 2;
        int ph = lh * 2 + pad * 3;
        int x = cx - pw / 2;
        int y = cy - ph - 6;
        net.minecraft.client.gui.Gui.drawRect(x, y, x + pw, y + ph, 0xC0000000);
        net.minecraft.client.gui.Gui.drawRect(x, y, x + pw, y + 1, NexusTheme.ACCENT);
        // owner line (accent)
        fr.drawStringWithShadow(owner, x + pad, y + pad, NexusTheme.ACCENT);
        // status line (text)
        fr.drawStringWithShadow(status, x + pad, y + pad + lh + pad, 0xFFFFFFFF);
    }

    /** Build the Mythos-style status line for one snapshot. */
    private static String describe(Minecraft mc, Object snap) {
        try {
            String seqName = (String) mSnapGetSeq.invoke(snap);
            if (seqName == null || seqName.isEmpty()) return "";
            Object seq = mGetSequence.invoke(null, seqName);
            int stepIdx = (Integer) mSnapGetStepIdx.invoke(snap);
            Object step = null;
            if (seq != null) {
                List<?> steps = (List<?>) mSeqGetSteps.invoke(seq);
                if (steps != null && stepIdx >= 0 && stepIdx < steps.size()) {
                    step = steps.get(stepIdx);
                }
            }
            if (step == null) {
                return seqName;
            }
            double[] goto_ = (double[]) mStepGetGoto.invoke(step);
            boolean pathing = goto_ != null && goto_.length >= 3
                    && !Double.isNaN(goto_[0])
                    && !((Boolean) mSnapIsAtTarget.invoke(snap));
            if (pathing) {
                return "前往步骤" + (stepIdx + 1) + " [" + fmtC(goto_[0]) + ","
                        + (Double.isNaN(goto_[1]) ? fmtC(goto_[2]) : fmtC(goto_[1]) + "," + fmtC(goto_[2]))
                        + "]";
            }
            List<?> actions = (List<?>) mStepGetActions.invoke(step);
            int ai = Math.max(0, (Integer) mSnapGetActIdx.invoke(snap));
            String cur = "";
            String next = "无";
            if (actions != null) {
                if (ai < actions.size()) cur = safeDesc(actions.get(ai));
                if (ai + 1 < actions.size()) next = safeDesc(actions.get(ai + 1));
            }
            String line = "步骤" + (stepIdx + 1) + "·动作" + (ai + 1);
            if (!cur.isEmpty()) line += "：" + cur;
            line += " → " + next;
            return line;
        } catch (Throwable t) {
            NexusRefs.warnOnce("overlay:describe", t,
                    "BotStatusOverlay: cannot render sequence status: %s", t.toString());
            return "";
        }
    }

    private static String safeDesc(Object action) {
        try {
            Object d = mActGetDesc.invoke(action);
            return d == null ? "" : d.toString().replace('\n', ' ').trim();
        } catch (Throwable t) {
            NexusRefs.warnOnce("overlay:desc", t,
                    "BotStatusOverlay: ActionData.getDescription failed");
            return "";
        }
    }

    private static String fmtC(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "?";
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
