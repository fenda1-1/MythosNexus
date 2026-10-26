package com.mythos.nexus.session;

import com.mythos.nexus.core.NexusConfig;
import com.mythos.nexus.core.NexusLog;
import com.mythos.nexus.core.NexusRefs;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.CPacketAnimation;
import net.minecraft.network.play.client.CPacketEntityAction;
import net.minecraft.network.play.client.CPacketHeldItemChange;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.network.play.client.CPacketPlayerDigging;
import net.minecraft.network.play.client.CPacketPlayerTryUseItem;
import net.minecraft.network.play.client.CPacketPlayerTryUseItemOnBlock;
import net.minecraft.network.play.client.CPacketUseEntity;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据包级同步：把主控连接上发出的动作包重放到同组其它账号的连接上。
 *
 * 重放范围：CPacketPlayer、CPacketAnimation、CPacketEntityAction、
 * CPacketHeldItemChange、CPacketPlayerDigging、CPacketPlayerTryUseItem、
 * CPacketPlayerTryUseItemOnBlock、CPacketUseEntity。
 * CPacketUseEntity 携带的是网络实体 id，按 UUID 换算后在各会话线程上重新发出。
 */
public final class PacketSync {

    private PacketSync() {}

    private static final Set<Channel> INSTALLED =
            Collections.newSetFromMap(new ConcurrentHashMap<Channel, Boolean>());
    private static final Map<String, Long> LAST_LOG = new ConcurrentHashMap<>();
    private static final long LOG_INTERVAL_NANOS = 3_000_000_000L;

    /** 在每条连接上安装重放处理器。 */
    public static void tick() {
        install(humanManager());
        for (BotSession s : SessionManager.get().sessions()) install(s.connection);
    }

    private static void install(NetworkManager nm) {
        if (nm == null) return;
        Channel ch = nm.channel();
        if (ch == null || !INSTALLED.add(ch)) return;
        try {
            ch.pipeline().addLast("nexus_pktsync", new Handler(nm));
        } catch (Throwable t) {
            INSTALLED.remove(ch);
            NexusRefs.warnOnce("pktsync:install", t, "packet sync install failed");
        }
    }

    private static NetworkManager humanManager() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getConnection() == null) return null;
        return mc.getConnection().getNetworkManager();
    }

    /** 判断这条连接是不是当前主控连接。 */
    private static boolean isDriver(NetworkManager nm) {
        BotSession mounted = SessionManager.get().mounted();
        if (mounted != null && mounted.connection != null) return mounted.connection == nm;
        return humanManager() == nm;
    }

    private static final class Handler extends ChannelDuplexHandler {
        private final NetworkManager nm;

        Handler(NetworkManager nm) { this.nm = nm; }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            try {
                if (msg instanceof Packet) onOutbound(nm, (Packet<?>) msg);
            } catch (Throwable t) {
                NexusRefs.warnOnce("pktsync:write", t, "packet sync replay failed");
            }
            super.write(ctx, msg, promise);
        }
    }

    private static void onOutbound(NetworkManager nm, Packet<?> packet) {
        if (!isDriver(nm)) return;
        if (!isMirrored(packet)) return;
        BotSession mounted = SessionManager.get().mounted();
        String key = mounted != null ? mounted.username + "@" + mounted.serverHost + ":" + mounted.serverPort
                : humanKey();
        if (key == null) return;
        List<NexusConfig.SyncGroup> groups = NexusConfig.enabledSyncGroups();
        if (groups.isEmpty()) return;
        NexusConfig.SyncGroup group = null;
        for (NexusConfig.SyncGroup g : groups) {
            if (g.members.contains(key)) { group = g; break; }
        }
        if (group == null) return;

        if (packet instanceof CPacketUseEntity) {
            final String driverKey = key;
            final CPacketUseEntity use = (CPacketUseEntity) packet;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) return;
            // 实体表只允许客户端线程访问，解析放到客户端线程上执行。
            mc.addScheduledTask(new Runnable() {
                @Override public void run() { replayAttackAll(driverKey, use); }
            });
            return;
        }

        for (String member : group.members) {
            if (member.equals(key)) continue;
            BotSession target = findByKey(member);
            if (target == null || target.connection == null) continue;
            if (target.state != BotSession.State.ONLINE) continue;
            // 跟随者离主控过远时跳过重放。
            if (!nearDriver(target)) continue;
            send(target, packet);
        }
        Long prev = LAST_LOG.get("send:" + key + ":" + packet.getClass().getSimpleName());
        long now = System.nanoTime();
        if (prev == null || now - prev > LOG_INTERVAL_NANOS) {
            LAST_LOG.put("send:" + key + ":" + packet.getClass().getSimpleName(), now);
            NexusLog.tick("[sync] 主控 {} 动作包 {} 已重放到 {} 个账号",
                    usernameOf(key), packet.getClass().getSimpleName(), group.members.size() - 1);
        }
    }

    private static boolean nearDriver(BotSession target) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.player == null) return false;
        if (target.player == null) return false;
        return target.player.getDistance(mc.player.posX, mc.player.posY, mc.player.posZ) <= 8.0;
    }

    private static void send(BotSession target, Packet<?> packet) {
        try {
            target.connection.sendPacket(packet);
        } catch (Throwable t) {
            NexusRefs.warnOnce("pktsync:send:" + target.username, t, "packet replay failed");
        }
    }

    /**
     * 把攻击包里的实体 id 换成目标账号世界里的同一个实体，再交回各会话线程执行。
     */
    private static void replayAttackAll(String driverKey, CPacketUseEntity packet) {
        if (packet.getAction() != CPacketUseEntity.Action.ATTACK) {
            drop("非攻击动作 " + packet.getAction());
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.world == null) { drop("本地世界为空"); return; }
        Entity hit = packet.getEntityFromWorld(mc.world);
        if (hit == null) { drop("主控世界找不到该实体"); return; }
        if (hit == mc.player) return;
        final UUID id = hit.getUniqueID();
        final String name = hit.getName();

        List<NexusConfig.SyncGroup> groups = NexusConfig.enabledSyncGroups();
        NexusConfig.SyncGroup group = null;
        for (NexusConfig.SyncGroup g : groups) {
            if (g.members.contains(driverKey)) { group = g; break; }
        }
        if (group == null) return;

        for (String member : group.members) {
            if (member.equals(driverKey)) continue;
            final BotSession target = findByKey(member);
            if (target == null || target.connection == null) continue;
            if (target.state != BotSession.State.ONLINE) continue;
            if (!nearDriver(target)) { drop(target.username + " 离主控太远"); continue; }
            target.runOnTick(new Runnable() {
                @Override public void run() {
                    if (target.player == null || target.world == null || target.playerController == null) return;
                    Entity same = byUuid(target, id);
                    if (same == null) { drop(target.username + " 世界没有 " + name); return; }
                    if (same == target.player) return;
                    target.playerController.attackEntity(target.player, same);
                    NexusLog.tick("[sync] {} 同步攻击 {}", target.username, name);
                }
            });
        }
    }

    private static void drop(String why) {
        long now = System.nanoTime();
        Long prev = LAST_LOG.get("drop:" + why);
        if (prev != null && now - prev < LOG_INTERVAL_NANOS) return;
        LAST_LOG.put("drop:" + why, now);
        NexusLog.tick("[sync] 攻击包未重放：{}", why);
    }

    private static Entity byUuid(BotSession s, UUID id) {
        List<Entity> list = s.world.loadedEntityList;
        for (int i = 0; i < list.size(); i++) {
            Entity e = list.get(i);
            if (e != null && id.equals(e.getUniqueID())) return e;
        }
        return null;
    }

    private static boolean isMirrored(Packet<?> p) {
        return p instanceof CPacketPlayer
                || p instanceof CPacketAnimation
                || p instanceof CPacketEntityAction
                || p instanceof CPacketHeldItemChange
                || p instanceof CPacketPlayerDigging
                || p instanceof CPacketPlayerTryUseItem
                || p instanceof CPacketPlayerTryUseItemOnBlock
                || p instanceof CPacketUseEntity;
    }

    private static BotSession findByKey(String key) {
        for (BotSession s : SessionManager.get().sessions()) {
            if (key.equals(s.username + "@" + s.serverHost + ":" + s.serverPort)) return s;
        }
        return null;
    }

    private static String humanKey() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.player == null || mc.getConnection() == null) return null;
        if (mc.getCurrentServerData() == null) return null;
        String host = mc.getCurrentServerData().serverIP;
        int port = 25565;
        int at = host.lastIndexOf(':');
        if (at > 0) {
            try { port = Integer.parseInt(host.substring(at + 1)); } catch (NumberFormatException ignored) {}
            host = host.substring(0, at);
        }
        return mc.player.getName() + "@" + host + ":" + port;
    }

    private static String usernameOf(String key) {
        int at = key.indexOf('@');
        return at > 0 ? key.substring(0, at) : key;
    }
}
