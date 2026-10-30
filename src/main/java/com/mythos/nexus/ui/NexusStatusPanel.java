package com.mythos.nexus.ui;

import com.mythos.nexus.core.NexusRefs;
import com.mythos.nexus.session.BotSession;
import com.mythos.nexus.session.SessionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.monster.EntitySlime;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static com.mythos.nexus.ui.NexusGeometry.hit;
import static com.mythos.nexus.ui.NexusScreen.PAD;

/**
 * Right-panel "status" view: selected bot's player state (HP / food /
 * position / online seconds) plus a scrollable list of nearby entities
 * within a configurable radius.
 */
final class NexusStatusPanel extends NexusPanel {

    /** Configurable nearby-entity radius in blocks (persisted per screen session). */
    int radius = 32;
    /** Scroll inside the entity card container. */
    int entityScroll = 0;
    /** Thumb geometry for drag (like other panels). */
    int entityThumbY = -1, entityThumbH = 0, entityTrackY = -1, entityTrackH = 0;
    /** Entity-scrollbar drag state (panel-local; other panels use s.dragScroll). */
    private boolean entityDrag = false;
    private int entityDragOffY = 0;
    /** Radius [-]/[+] row y from the last draw; -1 when not drawn. */
    private int radiusRowY = -1;
    /** Cached entity list, rebuilt at most every ~250 ms to keep draw cheap. */
    private final List<Ent> cached = new ArrayList<>();
    private long nextRefreshMs = 0;

    NexusStatusPanel(NexusScreen s) { super(s); }

    void draw(int rx, int mx, int my) {
        BotSession sel = s.firstSelected();
        s.font().drawString("状态", rx + PAD, 12, NexusTheme.TEXT_TITLE);
        s.font().drawString(sel != null ? sel.username : "—", rx + PAD, 24,
                NexusTheme.TEXT_SUB);

        int y = 44;
        int w = s.panelW() - PAD * 2;
        if (sel == null || sel == SessionManager.get().humanSession()) {
            radiusRowY = -1;
            s.font().drawString("选择一个账号查看", rx + PAD, y,
                    NexusTheme.TEXT_FAINT);
            return;
        }

        Ui.card(rx + PAD, y, w, 58, NexusTheme.BG_CARD,
                NexusTheme.BORDER_SOFT, 4);
        int ty = y + 6;
        if (sel.player != null) {
            net.minecraft.entity.player.EntityPlayer p = sel.player;
            ty = line(rx + PAD + 6, ty, "HP",
                    String.format("%.0f/%.0f", p.getHealth(), p.getMaxHealth()));
            ty = line(rx + PAD + 6, ty, "位置",
                    String.format("%.0f, %.0f, %.0f", p.posX, p.posY, p.posZ));
            ty = line(rx + PAD + 6, ty, "饥饿",
                    p.getFoodStats() != null
                            ? String.valueOf(p.getFoodStats().getFoodLevel())
                            : "—");
            line(rx + PAD + 6, ty, "在线",
                    sel.onlineSeconds() + "s");
        } else {
            s.font().drawString("未连接 / 未进入世界", rx + PAD + 6, ty,
                    NexusTheme.TEXT_FAINT);
        }
        y += 66;

        int seqH = drawSequenceCard(sel, rx, y, w);
        y += seqH + 8;
        radiusRowY = y;

        s.font().drawString("附近实体 半径", rx + PAD, y + 6,
                NexusTheme.TEXT_LABEL);
        int bw = 16;
        s.drawButton(rx + PAD + w - 70, y, bw, 16, "-", false, mx, my);
        String rv = String.valueOf(radius);
        s.font().drawString(rv, rx + PAD + w - 47 - s.font().getStringWidth(rv) / 2,
                y + 4, NexusTheme.TEXT_TITLE);
        s.drawButton(rx + PAD + w - 24, y, bw, 16, "+", false, mx, my);
        if (hit(mx, my, rx + PAD + w - 70, y, bw, 16)) {
            s.hoverText = "缩小扫描半径 (-4)"; s.hoverX = mx; s.hoverY = my;
        } else if (hit(mx, my, rx + PAD + w - 24, y, bw, 16)) {
            s.hoverText = "扩大扫描半径 (+4)"; s.hoverX = mx; s.hoverY = my;
        }
        y += 22;

        refreshEntities(sel);
        int listTop = y;
        int listBottom = s.height - 24;
        int listH = Math.max(60, listBottom - listTop);

        s.font().drawString("类型", rx + PAD + 6, listTop + 2,
                NexusTheme.TEXT_FAINT);
        s.font().drawString("名称", rx + PAD + 66, listTop + 2,
                NexusTheme.TEXT_FAINT);
        s.font().drawString("距离", rx + PAD + w - 34, listTop + 2,
                NexusTheme.TEXT_FAINT);
        listTop += 12;
        listH -= 12;

        int cardH = 26;
        int totalH = cached.size() * cardH;
        int maxScroll = Math.max(0, totalH - listH);
        entityMaxScroll = maxScroll;
        entityScroll = Math.max(0, Math.min(entityScroll, maxScroll));

        // enable scissor for the list area
        // the screen's fixed scale, not the game's ScaledResolution
        double scale = s.guiScaleFactor;
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL11.glScissor((int) Math.round((rx + PAD) * scale),
                (int) Math.round(s.mc().displayHeight - (listTop + listH) * scale),
                (int) Math.round(w * scale), (int) Math.round(listH * scale));

        int ey = listTop - entityScroll;
        for (Ent e : cached) {
            if (ey + cardH > listTop && ey < listTop + listH) {
                boolean hov = hit(mx, my, rx + PAD, ey, w, cardH - 2);
                Ui.card(rx + PAD, ey, w, cardH - 2,
                        hov ? NexusTheme.BG_CARD_HOV : NexusTheme.BG_CARD,
                        NexusTheme.BORDER_SOFT, 3);
                s.font().drawString(e.typeLabel, rx + PAD + 6, ey + 8,
                        e.typeColor);
                String name = e.name;
                if (s.font().getStringWidth(name) > w - 110) {
                    name = s.font().trimStringToWidth(name,
                            w - 110 - s.font().getStringWidth("…")) + "…";
                }
                s.font().drawString(name, rx + PAD + 66, ey + 8,
                        NexusTheme.TEXT_LABEL);
                s.font().drawString(String.format("%.0fm", e.dist),
                        rx + PAD + w - 34, ey + 8, NexusTheme.TEXT_SUB);
            }
            ey += cardH;
        }
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);

        if (maxScroll > 0) {
            entityTrackY = listTop;
            entityTrackH = listH;
            entityThumbH = Math.max(24, listH * listH / totalH);
            entityThumbY = listTop + (listH - entityThumbH) * entityScroll
                    / maxScroll;
            int sbx = rx + PAD + w + 2;
            Ui.fill(sbx, entityTrackY, 3, entityTrackH,
                    NexusTheme.BORDER_SOFT);
            Ui.fill(sbx, entityThumbY, 3, entityThumbH, NexusTheme.ACCENT);
        } else {
            entityThumbY = -1;
        }
    }

    /** Draw a "运行序列" status card for the selected bot. Height = 0 when
     *  nothing is running (collapses entirely). Lines match the MythosScript
     *  HUD: "正在前往步骤N, [x,y,z]" while pathing, else
     *  "正在执行步骤N·动作M：当前，下一步：下一个". */
    private int drawSequenceCard(BotSession sel, int rx, int y, int w) {
        String owner = sel != null ? sel.username : "";
        if (owner.isEmpty()) return 0;
        long now = System.currentTimeMillis();
        if (!owner.equals(seqOwner) || now >= seqNextMs) {
            seqOwner = owner;
            seqNextMs = now + 250;
            seqErr = null;
            seqEntries.clear();
            try {
                collectSequenceEntries(owner, seqEntries);
            } catch (Throwable t) {
                seqErr = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
        }
        String errMsg = seqErr;
        List<String> lines = new ArrayList<>();
        int textW = w - 12;
        String indent = "  ";
        for (String[] e : seqEntries) {
            lines.addAll(s.font().listFormattedStringToWidth(e[0], textW));
            for (String part : s.font().listFormattedStringToWidth(e[1],
                    textW - s.font().getStringWidth(indent))) {
                lines.add(indent + part);
            }
        }
        if (lines.isEmpty()) {
            int cardH = 26;
            Ui.card(rx + PAD, y, w, cardH, NexusTheme.BG_CARD,
                    NexusTheme.BORDER_SOFT, 4);
            s.font().drawString("运行序列", rx + PAD + 6, y + 6, NexusTheme.TEXT_SUB);
            String msg = errMsg != null ? errMsg : "空闲";
            s.font().drawString(s.font().trimStringToWidth(msg, w - 58),
                    rx + PAD + 52, y + 6, NexusTheme.TEXT_FAINT);
            return cardH;
        }

        int cardH = 14 + lines.size() * 12;
        Ui.card(rx + PAD, y, w, cardH, NexusTheme.BG_CARD,
                NexusTheme.BORDER_SOFT, 4);
        int ty = y + 6;
        s.font().drawString("运行序列", rx + PAD + 6, ty, NexusTheme.TEXT_SUB);
        ty += 12;
        for (String l : lines) {
            s.font().drawString(l, rx + PAD + 6, ty, NexusTheme.TEXT_LABEL);
            ty += 12;
        }
        return cardH;
    }

    /** {title, status} per running sequence of this owner, refreshed every 250 ms. */
    private final List<String[]> seqEntries = new ArrayList<>();
    private String seqOwner;
    private String seqErr;
    private long seqNextMs;

    private static final java.util.Map<String, java.lang.reflect.Method> METHODS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static Class<?> listenerClass, seqMgrClass;

    private static java.lang.reflect.Method method(Class<?> c, String name, Class<?>... params)
            throws NoSuchMethodException {
        // Key includes the parameter types: a name-only key hands the wrong
        // Method to an overload (and NexusRefs.sig is the same string every
        // failure message uses).
        String key = NexusRefs.sig(c, name, params);
        java.lang.reflect.Method m = METHODS.get(key);
        if (m == null) {
            m = NexusRefs.method(c, name, params);
            METHODS.put(key, m);
        }
        return m;
    }

    private static void collectSequenceEntries(String owner, List<String[]> out) throws Exception {
        if (listenerClass == null) {
            listenerClass = NexusRefs.cls(NexusRefs.PATH_SEQUENCE_EVENT_LISTENER);
            seqMgrClass = NexusRefs.cls(NexusRefs.PATH_SEQUENCE_MANAGER);
        }
        // getAllActiveProgressSnapshots = every session, no mount filter.
        // (getActiveProgressSnapshots only returns the mounted session —
        // background bots showed nothing.)
        java.lang.reflect.Method m;
        try { m = method(listenerClass, "getAllActiveProgressSnapshots"); }
        catch (NoSuchMethodException e) { m = method(listenerClass, "getActiveProgressSnapshots"); }
        @SuppressWarnings("unchecked")
        List<Object> snaps = (List<Object>) m.invoke(null);
        if (snaps == null) return;
        for (Object snap : snaps) {
            if (snap == null) continue;
            Class<?> sc = snap.getClass();
            String snapOwner = String.valueOf(method(sc, "getOwner").invoke(snap));
            if (!owner.equals(snapOwner)) continue;
            String seqName = String.valueOf(method(sc, "getSequenceName").invoke(snap));
            int stepIdx = (int) method(sc, "getStepIndex").invoke(snap);
            int actIdx = (int) method(sc, "getActionIndex").invoke(snap);
            boolean atTarget = (boolean) method(sc, "isAtTarget").invoke(snap);
            boolean bg = (boolean) method(sc, "isBackgroundRunner").invoke(snap);

            Object seq = method(seqMgrClass, "getSequence", String.class).invoke(null, seqName);
            Object step = null;
            if (seq != null) {
                @SuppressWarnings("unchecked")
                List<Object> steps = (List<Object>) method(seq.getClass(), "getSteps").invoke(seq);
                if (steps != null && stepIdx >= 0 && stepIdx < steps.size()) {
                    step = steps.get(stepIdx);
                }
            }

            String status;
            if (step != null) {
                double[] target = (double[]) method(step.getClass(), "getGotoPoint").invoke(step);
                boolean pathing = target != null && target.length >= 3 && !Double.isNaN(target[0])
                        && !atTarget;
                if (pathing) {
                    status = "正在前往步骤" + (stepIdx + 1) + ", " + formatTarget(target);
                } else {
                    @SuppressWarnings("unchecked")
                    List<Object> actions = (List<Object>) method(step.getClass(), "getActions")
                            .invoke(step);
                    int ai = Math.max(0, actIdx);
                    String cur = actions != null && ai < actions.size()
                            ? actionDesc(actions.get(ai)) : "";
                    String next = actions != null && ai + 1 < actions.size()
                            ? actionDesc(actions.get(ai + 1)) : "无";
                    status = "正在执行步骤" + (stepIdx + 1) + "·动作" + (ai + 1)
                            + "：" + cur + "，下一步：" + next;
                }
            } else {
                status = seqName;
            }
            out.add(new String[]{seqName + (bg ? " (后台)" : ""), status});
        }
    }

    private static String actionDesc(Object action) {
        try {
            Object d = method(action.getClass(), "getDescription").invoke(action);
            return d == null ? "" : d.toString().replace('\n', ' ').trim();
        } catch (Throwable t) {
            NexusRefs.warnOnce("statuspanel:actionDesc", t,
                    "NexusStatusPanel: ActionData.getDescription failed");
            return "";
        }
    }

    private static String formatTarget(double[] t) {
        if (t.length < 3 || Double.isNaN(t[1]))
            return "[" + fmtCoord(t[0]) + "," + fmtCoord(t[2]) + "]";
        return "[" + fmtCoord(t[0]) + "," + fmtCoord(t[1]) + "," + fmtCoord(t[2]) + "]";
    }

    private static String fmtCoord(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "?";
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.format("%.1f", v);
    }

    private int line(int x, int y, String k, String v) {
        s.font().drawString(k, x, y, NexusTheme.TEXT_FAINT);
        s.font().drawString(v, x + 40, y, NexusTheme.TEXT_LABEL);
        return y + 12;
    }

    /** Max entity scroll from the last draw (list height known only there). */
    private int entityMaxScroll = 0;

    boolean scrollEntity(int dWheel) {
        if (dWheel == 0) return false;
        // one wheel notch = one entity row
        entityScroll = Math.max(0, Math.min(entityMaxScroll,
                entityScroll - Integer.signum(dWheel) * 26));
        return true;
    }

    //      clicks here — radius steppers plus entity-thumb grab) ----

    @Override
    boolean click(int mx, int my, int btn) {
        entityDrag = false;   // re-armed below only when the thumb is grabbed
        int rx = s.width - s.rightW;
        int w = s.panelW() - PAD * 2;
        // card heights vary (sequence card wraps), so reuse the last drawn row y
        int y = radiusRowY;
        if (y >= 0 && hit(mx, my, rx + PAD + w - 70, y, 16, 16)) {
            radius = Math.max(4, radius - 4);
            entityScroll = 0;
            return true;
        }
        if (y >= 0 && hit(mx, my, rx + PAD + w - 24, y, 16, 16)) {
            radius = Math.min(256, radius + 4);
            entityScroll = 0;
            return true;
        }
        // entity-list scrollbar thumb grab (3px track at content right edge)
        if (entityThumbY > 0 && hit(mx, my, rx + PAD + w - 2,
                entityThumbY - 2, 10, entityThumbH + 4)) {
            entityDrag = true;
            entityDragOffY = my - entityThumbY;
            return true;
        }
        return false;
    }

    @Override
    boolean scroll(int dWheel) { return scrollEntity(dWheel); }

    /** Entity-list scrollbar drag — uses panel-local state since the
     *  screen's dragScroll enum (1..4) never covered this scrollbar. */
    @Override
    boolean drag(int mx, int my) {
        if (!entityDrag) return false;
        if (entityThumbH <= 0 || entityTrackH <= entityThumbH) {
            entityDrag = false;
            return false;
        }
        int listH = entityTrackH;
        int maxS = Math.max(0, cached.size() * 26 - listH);
        int ty = Math.max(entityTrackY, Math.min(my - entityDragOffY,
                entityTrackY + entityTrackH - entityThumbH));
        entityScroll = Math.max(0, Math.min(maxS,
                (ty - entityTrackY) * maxS / Math.max(1, entityTrackH - entityThumbH)));
        return true;
    }

    @Override
    void release() { entityDrag = false; }

    private void refreshEntities(BotSession sel) {
        long now = System.currentTimeMillis();
        if (now < nextRefreshMs && !cached.isEmpty()) return;
        nextRefreshMs = now + 250;
        cached.clear();
        if (sel.world == null || sel.player == null) return;
        UUID selfId = sel.player.getUniqueID();
        double px = sel.player.posX, py = sel.player.posY, pz = sel.player.posZ;
        List<Entity> near = sel.world.getEntitiesWithinAABB(Entity.class,
                sel.player.getEntityBoundingBox().expand(radius, radius, radius),
                e -> e != sel.player && e.isEntityAlive());
        for (Entity e : near) {
            double d = Math.sqrt(e.getDistanceSq(px, py, pz));
            if (d > radius) continue;
            Ent ent = new Ent();
            ent.dist = d;
            ent.name = e.getName();
            if (e instanceof EntityPlayer) {
                // MythosScript convention: NPCs share the player's UUID space
                // but are not the session's own player. A "real" player is any
                // EntityPlayer that isn't this session's own player object.
                boolean isSelf = selfId.equals(e.getUniqueID());
                ent.typeLabel = isSelf ? "NPC" : "玩家";
                ent.typeColor = isSelf ? NexusTheme.TEXT_SUB
                        : NexusTheme.ACCENT;
            } else if (e instanceof EntityMob || e instanceof EntitySlime) {
                ent.typeLabel = "敌人";
                ent.typeColor = NexusTheme.ERR;
            } else if (e instanceof EntityVillager) {
                ent.typeLabel = "NPC";
                ent.typeColor = NexusTheme.TEXT_SUB;
            } else if (e instanceof EntityAnimal || e instanceof EntityCreature) {
                ent.typeLabel = "动物";
                ent.typeColor = NexusTheme.TEXT_SUB;
            } else if (e instanceof EntityItem) {
                ent.typeLabel = "物品";
                ent.typeColor = NexusTheme.TEXT_FAINT;
            } else {
                ent.typeLabel = "其他";
                ent.typeColor = NexusTheme.TEXT_FAINT;
            }
            cached.add(ent);
        }
        cached.sort(Comparator.comparingDouble(a -> a.dist));
    }

    private static final class Ent {
        String name, typeLabel;
        int typeColor;
        double dist;
    }
}
