package com.mythos.nexus.session;

import com.mythos.nexus.core.NexusConfig;
import com.mythos.nexus.core.NexusLog;
import com.mythos.nexus.core.NexusRefs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 人物同步：以当前被操作的玩家为源，把位置、视角、移动输入和攻击复制给同组账号。
 *
 * 源由 {@link #clientTick()} 在客户端线程发布；后台 bot 在各自线程上
 * {@link #tick(BotSession)} 读取快照并覆盖自身状态。
 * 跟随者离源超过 {@link NexusConfig#syncGatherRange()} 格时改为向源寻路，
 * 进入范围后恢复镜像。
 */
public final class SyncEngine {

    private SyncEngine() {}

    /** 前台玩家的快照槽位 id。 */
    public static final int DRIVER_ID = -1;

    /** 源一 tick 的状态快照。 */
    private static final class Snap {
        double x, y, z;
        float yaw, pitch;
        float moveFwd, moveStrafe;
        boolean jump, sneak, sprinting, onGround;
        long stamp;
    }

    private static final Map<Integer, Snap> MAIL = new ConcurrentHashMap<>();
    private static final Map<Integer, String> GATHER_SENT = new ConcurrentHashMap<>();
    private static final Map<Integer, String> GATHER_REASON = new ConcurrentHashMap<>();
    /** {group, members, online, followers, gathering} */
    private static final List<String[]> ROSTER = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static volatile int mirroredTotal = 0;
    private static volatile String driverName = "";

    private static volatile List<NexusConfig.SyncGroup> cachedGroups = Collections.emptyList();
    private static volatile long cachedAt = 0L;
    private static final long CACHE_NANOS = 200_000_000L;
    /** 快照有效期。 */
    private static final long FRESH_NANOS = 400_000_000L;

    private static final long LOG_INTERVAL_NANOS = 3_000_000_000L;
    private static final Map<String, Long> LAST_LOG = new ConcurrentHashMap<>();

    private static void logThrottled(String tag, String fmt, Object... args) {
        long now = System.nanoTime();
        Long prev = LAST_LOG.get(tag);
        if (prev != null && now - prev < LOG_INTERVAL_NANOS) return;
        LAST_LOG.put(tag, now);
        NexusLog.tick("[sync] " + fmt, args);
    }

    private static String keyOf(BotSession s) {
        return s.username + "@" + s.serverHost + ":" + s.serverPort;
    }

    /** 分组配置缓存。 */
    private static List<NexusConfig.SyncGroup> enabledGroups() {
        long now = System.nanoTime();
        if (now - cachedAt < CACHE_NANOS) return cachedGroups;
        cachedAt = now;
        List<NexusConfig.SyncGroup> g = NexusConfig.enabledSyncGroups();
        cachedGroups = g;
        SyncHooks.ACTIVE = !g.isEmpty();
        return g;
    }

    /** 客户端线程每 tick 发布一次源快照。 */
    public static void clientTick() {
        List<NexusConfig.SyncGroup> groups = enabledGroups();
        if (groups.isEmpty()) {
            if (!MAIL.isEmpty()) clearAll();
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.player == null || mc.world == null) {
            MAIL.remove(DRIVER_ID);
            driverName = "";
            return;
        }
        publish(DRIVER_ID, mc.player);
        driverName = mc.player.getName();
        refreshRoster(groups);
    }

    /** 后台 bot 每 tick 在自己的线程上跟随源。 */
    public static void tick(BotSession me) {
        if (me == null || me.player == null || me.world == null) return;
        if (me.state != BotSession.State.ONLINE) return;

        List<NexusConfig.SyncGroup> groups = enabledGroups();
        if (groups.isEmpty()) return;

        // 前台会话由 clientTick 发布。
        if (me == SessionManager.get().mounted()) return;

        NexusConfig.SyncGroup mine = groupOf(groups, me);
        if (mine == null) return;

        Snap sn = MAIL.get(DRIVER_ID);
        if (sn != null && System.nanoTime() - sn.stamp < FRESH_NANOS) {
            follow(me, sn, mine);
            return;
        }
        // 没有前台源时，由同组 id 最小的在线账号作为主控。
        BotSession lead = lowestId(mine, me);
        if (lead == me) {
            publish(me.id, me.player);
            refreshRoster(groups);
            return;
        }
        Snap ls = lead == null ? null : MAIL.get(lead.id);
        if (ls != null && System.nanoTime() - ls.stamp < FRESH_NANOS) follow(me, ls, mine);
    }
    private static NexusConfig.SyncGroup groupOf(List<NexusConfig.SyncGroup> groups, BotSession s) {
        String key = keyOf(s);
        for (NexusConfig.SyncGroup g : groups) if (g.members.contains(key)) return g;
        return null;
    }

    private static BotSession lowestId(NexusConfig.SyncGroup g, BotSession self) {
        BotSession best = null;
        for (BotSession s : SessionManager.get().sessions()) {
            if (s.player == null || s.state != BotSession.State.ONLINE) continue;
            if (!g.members.contains(keyOf(s))) continue;
            if (s == SessionManager.get().mounted()) continue;
            if (best == null || s.id < best.id) best = s;
        }
        return best == null ? self : best;
    }

    private static void publish(int id, EntityPlayerSP p) {
        Snap sn = new Snap();
        sn.x = p.posX;
        sn.y = p.posY;
        sn.z = p.posZ;
        sn.yaw = p.rotationYaw;
        sn.pitch = p.rotationPitch;
        sn.onGround = p.onGround;
        sn.sprinting = p.isSprinting();
        sn.stamp = System.nanoTime();
        if (p.movementInput != null) {
            sn.moveFwd = p.movementInput.moveForward;
            sn.moveStrafe = p.movementInput.moveStrafe;
            sn.jump = p.movementInput.jump;
            sn.sneak = p.movementInput.sneak;
        }
        MAIL.put(id, sn);
    }

    private static void follow(BotSession me, Snap sn, NexusConfig.SyncGroup g) {
        EntityPlayerSP p = me.player;
        double dist = p.getDistance(sn.x, sn.y, sn.z);

        if (NexusConfig.syncAutoGather() && dist > NexusConfig.syncGatherRange()) {
            gather(me, g, sn);
            return;
        }
        stopGather(me);
        logThrottled("follow:" + me.id, "{} 镜像 {} 距离 {} 格", me.username,
                driverName.isEmpty() ? "同组主控" : driverName, String.format("%.2f", dist));

        p.setPosition(sn.x, sn.y, sn.z);
        p.motionX = 0;
        p.motionY = 0;
        p.motionZ = 0;
        p.onGround = sn.onGround;
        p.lastTickPosX = sn.x;
        p.lastTickPosY = sn.y;
        p.lastTickPosZ = sn.z;

        // 视角连同 prev* 一起覆盖。
        p.rotationYaw = sn.yaw;
        p.rotationPitch = sn.pitch;
        p.prevRotationYaw = sn.yaw;
        p.prevRotationPitch = sn.pitch;
        p.rotationYawHead = sn.yaw;
        p.prevRotationYawHead = sn.yaw;
        p.renderYawOffset = sn.yaw;
        p.prevRenderYawOffset = sn.yaw;

        if (p.movementInput != null) {
            p.movementInput.moveForward = sn.moveFwd;
            p.movementInput.moveStrafe = sn.moveStrafe;
            p.movementInput.jump = sn.jump;
            p.movementInput.sneak = sn.sneak;
        }
        p.setSprinting(sn.sprinting);
        p.setSneaking(sn.sneak);
    }

    private static void gather(BotSession me, NexusConfig.SyncGroup g, Snap sn) {
        int bx = MathHelper.floor(sn.x), by = MathHelper.floor(sn.y), bz = MathHelper.floor(sn.z);
        String goal = bx + "," + by + "," + bz;
        if (!goal.equals(GATHER_SENT.get(me.id))) {
            GATHER_SENT.put(me.id, goal);
            boolean ok = SessionTicker.sendGoto(me, bx, by, bz);
            GATHER_REASON.put(me.id, ok ? "gather" : "walk");
            logThrottled("gather:" + me.id, "{} 聚拢到 {} → {}", me.username, g.name,
                    ok ? "已发送寻路" : "寻路不可用，改直线");
        }
        if ("walk".equals(GATHER_REASON.get(me.id))) {
            // 没有 Baritone 时直接朝源移动。
            me.player.rotationYaw = (float) (Math.toDegrees(Math.atan2(sn.z - me.player.posZ,
                    sn.x - me.player.posX)) - 90.0);
            if (me.player.movementInput != null) {
                me.player.movementInput.moveForward = 1.0f;
                me.player.movementInput.moveStrafe = 0.0f;
                me.player.movementInput.jump = false;
            }
        }
    }

    private static void stopGather(BotSession me) {
        String prev = GATHER_REASON.remove(me.id);
        GATHER_SENT.remove(me.id);
        if ("gather".equals(prev)) SessionTicker.sendStop(me);
    }

    private static void clearAll() {
        MAIL.clear();
        GATHER_SENT.clear();
        GATHER_REASON.clear();
        ROSTER.clear();
        mirroredTotal = 0;
        driverName = "";
    }

    private static void refreshRoster(List<NexusConfig.SyncGroup> groups) {
        List<String[]> rows = new ArrayList<>();
        int mirrored = 0;
        for (NexusConfig.SyncGroup g : groups) {
            int online = 0, followers = 0, gathering = 0;
            for (String key : g.members) {
                BotSession s = findByKey(key);
                if (s == null || s.state != BotSession.State.ONLINE) continue;
                online++;
                if ("gather".equals(GATHER_REASON.get(s.id))) gathering++;
                else { followers++; mirrored++; }
            }
            mirroredTotal = mirrored;
            rows.add(new String[]{g.name, g.members.size() + " 账号",
                    online + " 在线", followers + " 跟随", gathering + " 聚拢"});
        }
        ROSTER.clear();
        ROSTER.addAll(rows);
    }

    private static BotSession findByKey(String key) {
        for (BotSession s : SessionManager.get().sessions()) if (keyOf(s).equals(key)) return s;
        return null;
    }

    /** 每行 {分组, 成员数, 在线数, 跟随数, 聚拢数}。 */
    public static List<String[]> roster() { return new ArrayList<>(ROSTER); }

    public static int mirroredCount() { return mirroredTotal; }

    /** 当前源玩家名。 */
    public static String driver() { return driverName; }

    public static boolean isGathering(int sessionId) { return "walk".equals(GATHER_REASON.get(sessionId))
            || "gather".equals(GATHER_REASON.get(sessionId)); }

    public static int pendingGathers() { return GATHER_REASON.size(); }

    /** 该会话是否在某个已启动的分组里。 */
    public static boolean isSynced(BotSession s) {
        if (s == null) return false;
        return groupOf(enabledGroups(), s) != null;
    }

    /** 第一人称下隐藏与源重叠的跟随者模型。 */
    public static boolean hideNearCamera(EntityPlayer target) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.player == null || target == null) return false;
        if (mc.gameSettings == null || mc.gameSettings.thirdPersonView != 0) return false;
        if (mc.player.getDistanceSq(target) > 4.0) return false;
        String name = target.getName();
        if (name == null) return false;
        for (NexusConfig.SyncGroup g : enabledGroups()) {
            if (hasMember(g, name)) return true;
        }
        return false;
    }

    /**
     * 客户端每帧把本地世界里跟随者的 EntityOtherPlayerMP 对齐到源，
     * 连同 prev*、走路与挥臂相位一起覆盖。
     */
    public static void clientFrame() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.world == null || mc.player == null) return;
        List<NexusConfig.SyncGroup> groups = enabledGroups();
        if (groups.isEmpty()) return;
        EntityPlayerSP drv = mc.player;
        String me = drv.getName();
        for (NexusConfig.SyncGroup g : groups) {
            if (!hasMember(g, me)) continue;
            for (String key : g.members) {
                String name = usernameOf(key);
                if (name.isEmpty() || name.equalsIgnoreCase(me)) continue;
                EntityPlayer other = mc.world.getPlayerEntityByName(name);
                if (other != null) {
                    copyTransform(drv, other);
                    logThrottled("overlay:" + name, "本地世界 {} 已对齐，距离 {} 格", name,
                            String.format("%.3f", other.getDistance(drv)));
                }
            }
        }
    }

    private static void copyTransform(EntityPlayerSP d, EntityPlayer t) {
        t.prevPosX = d.prevPosX;
        t.prevPosY = d.prevPosY;
        t.prevPosZ = d.prevPosZ;
        t.posX = d.posX;
        t.posY = d.posY;
        t.posZ = d.posZ;
        t.lastTickPosX = d.lastTickPosX;
        t.lastTickPosY = d.lastTickPosY;
        t.lastTickPosZ = d.lastTickPosZ;
        t.motionX = d.motionX;
        t.motionY = d.motionY;
        t.motionZ = d.motionZ;
        t.onGround = d.onGround;
        t.setEntityBoundingBox(d.getEntityBoundingBox());

        t.rotationYaw = d.rotationYaw;
        t.rotationPitch = d.rotationPitch;
        t.prevRotationYaw = d.prevRotationYaw;
        t.prevRotationPitch = d.prevRotationPitch;
        t.rotationYawHead = d.rotationYawHead;
        t.prevRotationYawHead = d.prevRotationYawHead;
        t.renderYawOffset = d.renderYawOffset;
        t.prevRenderYawOffset = d.prevRenderYawOffset;

        t.limbSwing = d.limbSwing;
        t.limbSwingAmount = d.limbSwingAmount;
        t.prevLimbSwingAmount = d.prevLimbSwingAmount;
        t.swingProgress = d.swingProgress;
        t.prevSwingProgress = d.prevSwingProgress;
        t.swingProgressInt = d.swingProgressInt;
        t.isSwingInProgress = d.isSwingInProgress;

        t.setSneaking(d.isSneaking());
        t.setSprinting(d.isSprinting());
    }

    private static boolean hasMember(NexusConfig.SyncGroup g, String username) {
        for (String key : g.members) if (usernameOf(key).equalsIgnoreCase(username)) return true;
        return false;
    }

    private static String usernameOf(String key) {
        int at = key.indexOf('@');
        return at < 0 ? key : key.substring(0, at);
    }

    public static void forget(BotSession s) {
        if (s == null) return;
        MAIL.remove(s.id);
        GATHER_SENT.remove(s.id);
        GATHER_REASON.remove(s.id);
    }

}
