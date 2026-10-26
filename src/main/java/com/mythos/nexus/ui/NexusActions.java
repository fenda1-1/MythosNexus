package com.mythos.nexus.ui;

import com.mythos.nexus.core.NexusConfig;
import com.mythos.nexus.session.BotSession;
import com.mythos.nexus.session.SessionManager;
import net.minecraft.client.gui.GuiScreen;

import java.util.ArrayList;
import java.util.List;

import static com.mythos.nexus.ui.NexusGeometry.hit;
import static com.mythos.nexus.ui.NexusGeometry.parsePort;
import static com.mythos.nexus.ui.NexusGeometry.sessionKey;

/**
 * Business logic extracted from {@link NexusScreen}: persistence, editing,
 * batch ops, import/export, proxy latency test, LIVE size, group rename and
 * card drop. All methods take the screen; its same-named instance methods
 * delegate here so existing callers (s.saveAll() etc.) keep working.
 */
final class NexusActions {

    private NexusActions() {}


    static void saveAll(NexusScreen s) {
        for (BotSession b : SessionManager.get().sessions()) {
            if (b == SessionManager.get().humanSession()) continue;
            NexusConfig.setGroup(sessionKey(b), b.group);
            NexusConfig.setProxy(sessionKey(b), b.proxyIndex);
            NexusConfig.setNote(sessionKey(b),
                    b == s.firstSelected()
                            ? s.fNote.getText()
                            : NexusConfig.bindingFor(sessionKey(b)).note);
        }
        try {
            int v = Integer.parseInt(s.fDelay.getText().trim());
            NexusConfig.setConnectDelayMs(Math.max(50, Math.min(60000, v)));
        } catch (NumberFormatException ignored) {}
        NexusConfig.save();
        s.dirty = false;
    }

    static void applyEdit(NexusScreen s, BotSession b) {
        String oldKey = sessionKey(b);
        String u = s.fName.getText().trim();
        String sv = s.fServer.getText().trim();
        if (!u.isEmpty()) b.username = u;
        if (!sv.isEmpty()) {
            int ci = sv.lastIndexOf(':');
            if (ci > 0) {
                String h = sv.substring(0, ci);
                int p = parsePort(sv.substring(ci + 1));
                if (!h.isEmpty()) b.serverHost = h;
                if (p > 0) b.serverPort = p;
            } else {
                b.serverHost = sv;
            }
        }
        String newKey = sessionKey(b);
        if (!oldKey.equals(newKey)) {
            NexusConfig.rekeyBinding(oldKey, newKey);
            NexusConfig.forgetAccountByKey(oldKey);
            NexusConfig.rememberAccount(b.username, b.serverHost, b.serverPort);
        }
        b.logEvent("edit: " + b.username + "@" + b.serverHost + ":" + b.serverPort);
        s.dirty = true;
    }

    static void deleteSelected(NexusScreen s) {
        List<BotSession> toRemove = new ArrayList<>(s.selectedSessions());
        toRemove.remove(SessionManager.get().humanSession());
        for (BotSession b : toRemove) {

            NexusConfig.forgetAccountByKey(sessionKey(b));
            SessionManager.get().removeSession(b);
        }
        s.selected.clear();
        s.dirty = true;
        s.relayout();
    }

    /** Run a batch operation (connect / disconnect / delete) with a progress
     *  label shown in the status bar. Runs on a daemon thread; UI only reads
     *  batchProgress/batchLabel from the mc thread. */
    static void runBatch(final NexusScreen s, final String label,
                         final List<BotSession> targets,
                         final java.util.function.Consumer<BotSession> op) {
        if (targets.isEmpty()) return;
        s.batchLabel = label;
        s.batchProgress = new int[]{0, targets.size()};
        new Thread(new Runnable() {
            @Override public void run() {
                for (int i = 0; i < targets.size(); i++) {
                    BotSession b = targets.get(i);
                    if (b == null) { s.batchProgress[0] = i + 1; continue; }
                    try { op.accept(b); } catch (Throwable ignored) {}
                    s.batchProgress[0] = i + 1;
                }
                s.batchProgress = null;
                s.batchLabel = null;
            }
        }, "nexus-batch").start();
    }


    /** Read the W/H fields, clamp, persist, apply to the renderer.
     *  With a bot selected, writes the per-bot override; otherwise writes
     *  the global default. */
    static void applyLiveSize(NexusScreen s) {
        int w = ScreenModel.parseIntSafe(s.fLiveW.getText(), 320);
        int h = ScreenModel.parseIntSafe(s.fLiveH.getText(), 180);
        BotSession selB = s.firstSelected();
        if (selB != null && selB != SessionManager.get().humanSession()) {
            NexusConfig.setLiveViewSizeFor(sessionKey(selB), w + "x" + h);
        } else {
            com.mythos.nexus.session.LiveViewportRenderer.applyViewSize(w, h);
            NexusConfig.setLiveViewSize(
                    com.mythos.nexus.session.LiveViewportRenderer.VIEW_W + "x"
                    + com.mythos.nexus.session.LiveViewportRenderer.VIEW_H);
            // Sync fields back to clamped values (in case input was out of range).
            s.fLiveW.setText(String.valueOf(com.mythos.nexus.session.LiveViewportRenderer.VIEW_W));
            s.fLiveH.setText(String.valueOf(com.mythos.nexus.session.LiveViewportRenderer.VIEW_H));
        }
    }


    static void commitGroupRename(NexusScreen s) {
        if (s.renamingGroup == null) return;
        String old = s.renamingGroup;
        String name = s.groupEditField.getText().trim();
        // Keep the slash-prefix so renames stay inside their parent —
        // "PVE/子1" renamed to "战士" should become "PVE/战士" not "战士".
        if (!name.isEmpty() && !name.equals(old)) {
            int slash = old.lastIndexOf('/');
            String newPath = slash < 0 ? name
                    : old.substring(0, slash + 1) + name;
            if (!newPath.equals(old)) {
                for (BotSession b : SessionManager.get().sessions()) {
                    if (old.equals(b.group)) {
                        b.group = newPath;
                        NexusConfig.setGroup(sessionKey(b), newPath);
                        b.logEvent("group " + old + " -> " + newPath);
                    } else if (b.group.startsWith(old + "/")) {
                        String sub = newPath + b.group.substring(old.length());
                        b.group = sub;
                        NexusConfig.setGroup(sessionKey(b), sub);
                        b.logEvent("group " + old + "* -> " + sub);
                    }
                }
                // keep empty-group bookkeeping in sync — a renamed extra stays extra
                NexusConfig.renameGroup(old, newPath);
                if (old.equals(s.groupFilter)) s.groupFilter = newPath;
                else if (s.groupFilter != null && s.groupFilter.startsWith(old + "/"))
                    s.groupFilter = newPath + s.groupFilter.substring(old.length());
                s.collapsedGroups.remove(old);
            }
        }
        s.renamingGroup = null;
        s.groupEditField.setFocused(false);
    }

    /** If released over a rail group row, move the dragged card's session there. */
    static void dropCardOnGroup(NexusScreen s, int mx, int my) {
        if (s.railCollapsed) return;
        List<BotSession> list = s.filteredSessions();
        if (s.dragCardIdx >= list.size()) return;
        List<NexusRail.Row> rows = NexusRail.visibleRows(s);
        int listBottom = s.height - NexusRail.GROUP_BOTTOM;
        String target = null;
        int gy = NexusRail.GROUP_Y0;
        for (int i = s.groupScroll; i < rows.size(); i++) {
            if (hit(mx, my, NexusLayout.PAD - 6, gy,
                    s.railW - NexusLayout.PAD * 2 + 12, 18)) {
                target = rows.get(i).path; break;
            }
            gy += NexusRail.GROUP_ROW;
            if (gy > listBottom) break;
        }
        if (target == null) return;
        BotSession bs = list.get(s.dragCardIdx);
        if (bs == SessionManager.get().humanSession()) return;
        bs.group = target;
        NexusConfig.setGroup(sessionKey(bs), target);
        bs.logEvent("drag -> group " + target);
        s.dirty = true;
        s.relayout();
    }


    static void exportAccounts(boolean csv) {
        if (csv) {
            StringBuilder sb = new StringBuilder("username,host,port,group,proxy,note\n");
            for (BotSession b : SessionManager.get().sessions()) {
                if (b == SessionManager.get().humanSession()) continue;
                sb.append(csvField(b.username)).append(',')
                  .append(csvField(b.serverHost)).append(',')
                  .append(b.serverPort).append(',')
                  .append(csvField(b.group)).append(',')
                  .append(b.proxyIndex).append(',')
                  .append(csvField(NexusConfig.bindingFor(sessionKey(b)).note)).append('\n');
            }
            GuiScreen.setClipboardString(sb.toString());
            return;
        }
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (BotSession b : SessionManager.get().sessions()) {
            if (b == SessionManager.get().humanSession()) continue;
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("username", b.username);
            o.addProperty("host", b.serverHost);
            o.addProperty("port", b.serverPort);
            o.addProperty("group", b.group);
            o.addProperty("proxy", b.proxyIndex);
            o.addProperty("note", NexusConfig.bindingFor(sessionKey(b)).note);
            arr.add(o);
        }
        GuiScreen.setClipboardString(arr.toString());
    }

    private static String csvField(String v) {
        if (v == null) return "";
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0)
            return '"' + v.replace("\"", "\"\"") + '"';
        return v;
    }

    static void importAccounts(NexusScreen s) {
        String clip = GuiScreen.getClipboardString();
        if (clip == null || clip.trim().isEmpty()) return;
        String t = clip.trim();
        // CSV when it is not JSON
        if (!t.startsWith("[") && !t.startsWith("{")) {
            importAccountsCsv(s, t);
            return;
        }
        try {
            com.google.gson.JsonParser p = new com.google.gson.JsonParser();
            com.google.gson.JsonElement el = p.parse(t);
            com.google.gson.JsonArray arr = el.isJsonArray() ? el.getAsJsonArray()
                    : el.isJsonObject() && el.getAsJsonObject().has("accounts")
                    ? el.getAsJsonObject().getAsJsonArray("accounts") : null;
            if (arr == null) return;
            for (com.google.gson.JsonElement e : arr) {
                com.google.gson.JsonObject o = e.getAsJsonObject();
                String u = o.has("username") ? o.get("username").getAsString() : "";
                String h = o.has("host") ? o.get("host").getAsString() : "";
                int port = o.has("port") ? o.get("port").getAsInt() : 25565;
                if (u.isEmpty() || h.isEmpty()) continue;
                BotSession b = SessionManager.get().createSession(u, h, port > 0 ? port : 25565);
                if (o.has("group")) {
                    b.group = o.get("group").getAsString();
                    NexusConfig.setGroup(sessionKey(b), b.group);
                }
                if (o.has("proxy")) {
                    b.proxyIndex = o.get("proxy").getAsInt();
                    NexusConfig.setProxy(sessionKey(b), b.proxyIndex);
                }
                if (o.has("note")) NexusConfig.setNote(sessionKey(b), o.get("note").getAsString());
            }
            s.dirty = true;
            s.relayout();
        } catch (Throwable t2) {
            com.mythos.nexus.core.NexusPlugin.LOGGER.warn("import failed: {}", t2.toString());
        }
    }

    /** CSV: username,host,port,group,proxy,note — header optional, quoted fields supported. */
    private static void importAccountsCsv(NexusScreen s, String text) {
        try {
            String[] lines = text.replace("\r", "").split("\n");
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                List<String> f = splitCsv(line);
                if (f.size() < 2) continue;
                String u = f.get(0).trim();
                String h = f.get(1).trim();
                if (u.equalsIgnoreCase("username") || u.isEmpty() || h.isEmpty()) continue;
                int port = 25565;
                if (f.size() > 2 && !f.get(2).trim().isEmpty()) {
                    try { port = Integer.parseInt(f.get(2).trim()); } catch (Throwable ignored) {}
                }
                BotSession b = SessionManager.get().createSession(u, h, port > 0 ? port : 25565);
                if (f.size() > 3 && !f.get(3).trim().isEmpty()) {
                    b.group = f.get(3).trim();
                    NexusConfig.setGroup(sessionKey(b), b.group);
                }
                if (f.size() > 4 && !f.get(4).trim().isEmpty()) {
                    try {
                        b.proxyIndex = Integer.parseInt(f.get(4).trim());
                        NexusConfig.setProxy(sessionKey(b), b.proxyIndex);
                    } catch (Throwable ignored) {}
                }
                if (f.size() > 5) NexusConfig.setNote(sessionKey(b), f.get(5));
            }
            s.dirty = true;
            s.relayout();
        } catch (Throwable t2) {
            com.mythos.nexus.core.NexusPlugin.LOGGER.warn("csv import failed: {}", t2.toString());
        }
    }

    private static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (q) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else q = false;
                } else cur.append(c);
            } else if (c == '"') q = true;
            else if (c == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }


    /** Kick off a background TCP connect test for proxy i; result lands in proxyTestResult. */
    static void testProxy(final NexusScreen s, final int i) {
        final NexusConfig.Proxy px = NexusConfig.proxyAt(i);
        if (px == null) return;
        s.proxyTestResult.put(i, "…");
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                long start = System.nanoTime();
                java.net.Socket sock = new java.net.Socket();
                try {
                    sock.connect(new java.net.InetSocketAddress(px.host, px.port), 3000);
                    long ms = (System.nanoTime() - start) / 1_000_000L;
                    s.proxyTestResult.put(i, ms + "ms");
                } catch (Throwable e) {
                    s.proxyTestResult.put(i, "失败");
                } finally {
                    try { sock.close(); } catch (Throwable ignored) {}
                }
            }
        }, "nexus-proxytest-" + i);
        t.setDaemon(true);
        t.start();
    }
}
