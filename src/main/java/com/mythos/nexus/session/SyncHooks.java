package com.mythos.nexus.session;

import com.mythos.nexus.core.NexusConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

/**
 * ASM 注入目标：Entity.canBePushed / collideWithNearbyEntities / canBeCollidedWith
 * 的头部调用这里，决定是否直接返回。
 */
public final class SyncHooks {

    private SyncHooks() {}

    /** 是否有任何同步分组处于启动状态。 */
    public static volatile boolean ACTIVE = false;

    public static boolean noPush(Entity self) {
        if (!ACTIVE || self == null) return false;
        if (!(self instanceof EntityPlayer)) return false;
        String name = self.getName();
        if (name == null) return false;
        String host = self.getEntityWorld() == null ? null
                : (self.getEntityWorld().isRemote ? "c" : "s");
        if (host == null) return false;
        return isSyncedName(name);
    }

    /**
     * 注入到 Entity.canBeCollidedWith 的头部：同步中的 EntityOtherPlayerMP
     * 不参与射线选中。
     */
    public static boolean noCollide(Object self) {
        if (!ACTIVE || !(self instanceof net.minecraft.client.entity.EntityOtherPlayerMP)) {
            return false;
        }
        String name = ((Entity) self).getName();
        return name != null && isSyncedName(name);
    }

    private static boolean isSyncedName(String name) {
        for (NexusConfig.SyncGroup g : NexusConfig.enabledSyncGroups()) {
            for (String key : g.members) {
                if (usernameOf(key).equals(name)) return true;
            }
        }
        return false;
    }

    private static String usernameOf(String key) {
        int at = key.indexOf('@');
        return at <= 0 ? key : key.substring(0, at);
    }
}
