package com.mythos.nexus.ui;

import com.mythos.nexus.session.BotSession;

/** Static pure helpers — hit tests, math, string formatting. No screen state. */
public final class NexusGeometry {
    private NexusGeometry() {}

    static boolean hit(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    static String trim(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    static int blend(int a, int b, float t) {
        int aa = (a >> 24) & 255, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = (b >> 24) & 255, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        int al = (int) (aa + (ba - aa) * t);
        return (al << 24) | (r << 16) | (g << 8) | bl;
    }

    static float clamp01(float f) { return f < 0 ? 0 : f > 1 ? 1 : f; }

    static int parsePort(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    static String stateText(BotSession s) {
        switch (s.state) {
            case ONLINE:     return "在线";
            case CONNECTING: return "连接中";
            case ERROR:      return "错误";
            default:         return "离线";
        }
    }

    public static String sessionKey(BotSession s) {
        return s.username + "@" + s.serverHost + ":" + s.serverPort;
    }

    static String currentServer() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        try {
            net.minecraft.client.multiplayer.ServerData sd = mc.getCurrentServerData();
            if (sd != null && sd.serverIP != null && !sd.serverIP.isEmpty())
                return sd.serverIP;
        } catch (Throwable ignored) {}
        try {
            if (mc.player != null && mc.player.connection != null) {
                java.net.SocketAddress a = mc.player.connection.getNetworkManager()
                        .getRemoteAddress();
                if (a instanceof java.net.InetSocketAddress) {
                    java.net.InetSocketAddress ia = (java.net.InetSocketAddress) a;
                    return ia.getHostString() + ":" + ia.getPort();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
