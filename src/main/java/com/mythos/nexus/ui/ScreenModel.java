package com.mythos.nexus.ui;

import com.mythos.nexus.core.NexusConfig;
import com.mythos.nexus.session.BotSession;
import com.mythos.nexus.session.SessionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Shared UI state queries for {@link NexusScreen}: group list, card filtering
 * and sorting, selection helpers, density-driven card height.
 * Stateless static helpers — the mutable state itself (selected, groupFilter,
 * scroll offsets...) stays as package-private fields on NexusScreen because the
 * panels read them directly via s.field.
 */
final class ScreenModel {

    private ScreenModel() {}

    static List<String> allGroups() {
        java.util.LinkedHashSet<String> g = new java.util.LinkedHashSet<>();
        for (BotSession s : SessionManager.get().sessions()) {
            if (s != SessionManager.get().humanSession())
                g.add(s.group);
        }
        // user-created empty groups — merged in so they show even with 0 members
        g.addAll(NexusConfig.extraGroups());
        return new ArrayList<>(g);
    }

    private static List<BotSession> cacheOut;
    private static List<BotSession> cacheAll;
    private static String cacheGroup, cacheQuery, cacheSort;
    private static long cacheUntilMs;

    /** Sessions visible in the center grid after group + search filtering,
     *  sorted per the configured card sort (human pseudo-session pinned first).
     *  Called several times per frame; reused for 50 ms while inputs match. */
    static List<BotSession> filteredSessions(String groupFilter, String query) {
        List<BotSession> all = SessionManager.get().sessions();
        String sort = NexusConfig.cardSort();
        long now = System.currentTimeMillis();
        if (cacheOut != null && now < cacheUntilMs && sameElements(all, cacheAll)
                && java.util.Objects.equals(groupFilter, cacheGroup)
                && java.util.Objects.equals(query, cacheQuery)
                && java.util.Objects.equals(sort, cacheSort)) {
            return cacheOut;
        }
        List<BotSession> out = computeFiltered(all, groupFilter, query, sort);
        cacheOut = java.util.Collections.unmodifiableList(out);
        cacheAll = all;
        cacheGroup = groupFilter;
        cacheQuery = query;
        cacheSort = sort;
        cacheUntilMs = now + 50;
        return cacheOut;
    }

    private static boolean sameElements(List<BotSession> a, List<BotSession> b) {
        if (b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    private static List<BotSession> computeFiltered(List<BotSession> all, String groupFilter,
                                                    String query, String sort) {
        String q = query == null ? "" : query.trim().toLowerCase();
        List<BotSession> out = new ArrayList<>(all.size());
        for (BotSession s : all) {
            // filter includes the whole subtree — "PVE" shows "PVE/小怪" too
            if (groupFilter != null
                    && !groupFilter.equals(s.group)
                    && !s.group.startsWith(groupFilter + "/")) continue;
            if (!q.isEmpty()
                    && !s.username.toLowerCase().contains(q)
                    && !(s.serverHost + ":" + s.serverPort).contains(q)
                    && !s.group.toLowerCase().contains(q)) continue;
            out.add(s);
        }
        // Sort — human pseudo-session always stays at index 0
        if (!"DEFAULT".equals(sort) && out.size() > 1) {
            out.sort((a, b) -> {
                if (a == SessionManager.get().humanSession()) return -1;
                if (b == SessionManager.get().humanSession()) return 1;
                switch (sort) {
                    case "NAME":   return a.username.compareToIgnoreCase(b.username);
                    case "UPTIME": return Long.compare(b.onlineSinceNanos, a.onlineSinceNanos);
                    case "STATE":  return Integer.compare(a.state.ordinal(), b.state.ordinal());
                    default:       return 0;
                }
            });
        }
        return out;
    }

    static BotSession firstSelected(Set<Integer> selected, List<BotSession> filtered) {
        if (selected.isEmpty()) return null;
        int first = selected.stream().min(Integer::compare).orElse(-1);
        return (first >= 0 && first < filtered.size()) ? filtered.get(first) : null;
    }

    static List<BotSession> selectedSessions(Set<Integer> selected, List<BotSession> filtered) {
        List<BotSession> out = new ArrayList<>();
        for (int i : selected)
            if (i >= 0 && i < filtered.size()) out.add(filtered.get(i));
        return out;
    }

    /** Card height for the current density. */
    static int cardHeight() {
        switch (NexusConfig.cardDensity()) {
            case "COMPACT":  return 30;
            case "DETAILED": return 80;
            default:         return 70;
        }
    }

    static NexusScreen.CardMode restoreCardMode() {
        try { return NexusScreen.CardMode.valueOf(NexusConfig.cardMode()); }
        catch (Throwable t) { return NexusScreen.CardMode.WATERFALL; }
    }

    static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); }
        catch (Throwable t) { return def; }
    }
}
