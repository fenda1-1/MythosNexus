package com.mythos.nexus.session;

import com.mythos.nexus.core.NexusConfig;
import com.mythos.nexus.core.NexusPlugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 串行加入队列：所有自动（重）连接都从这里走。同一服务器同时只连一个，
 * 前一个尝试结束后等随机间隔再放下一个；不同服务器互不影响。
 */
public final class ReconnectManager {

    private static final ReconnectManager INSTANCE = new ReconnectManager();
    public static ReconnectManager get() { return INSTANCE; }

    public static final class JoinTask {
        public final BotSession session;
        public final String reason;
        public volatile long notBeforeNanos;
        public volatile long fireAtNanos;

        JoinTask(BotSession session, String reason, long notBeforeNanos) {
            this.session = session;
            this.reason = reason;
            this.notBeforeNanos = notBeforeNanos;
        }
    }

    /** One line of the queue's event log (connect attempts, refusals, gaps). */
    public static final class Event {
        public final String time;
        public final String level;   // INFO / WARN
        public final String msg;
        Event(String time, String level, String msg) {
            this.time = time; this.level = level; this.msg = msg;
        }
    }

    private static final class ServerQueue {
        final ArrayDeque<JoinTask> pending = new ArrayDeque<>();
        BotSession inFlight;
        long attemptDeadlineNanos;
        long nextFireNanos;
    }

    public static final class Row {
        public final BotSession session;
        public final String server;
        public final String reason;
        public final boolean inFlight;
        public final int position;
        public final long fireAtNanos;
        Row(BotSession session, String server, String reason, boolean inFlight,
            int position, long fireAtNanos) {
            this.session = session; this.server = server; this.reason = reason;
            this.inFlight = inFlight; this.position = position;
            this.fireAtNanos = fireAtNanos;
        }
    }


    private static final long ATTEMPT_TIMEOUT_NANOS = 35_000_000_000L;
    private static final int MAX_EVENTS = 300;

    private final Object lock = new Object();
    private final Map<String, ServerQueue> queues = new LinkedHashMap<>();
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private volatile boolean started;

    private ReconnectManager() {}

    private static String serverKey(BotSession s) {
        return (s.serverHost == null ? "?" : s.serverHost) + ":" + s.serverPort;
    }

    private ServerQueue queueFor(String server) {
        ServerQueue q = queues.get(server);
        if (q == null) {
            q = new ServerQueue();
            queues.put(server, q);
        }
        return q;
    }

    private void ensureWorker() {
        if (started) return;
        synchronized (lock) {
            if (started) return;
            started = true;
            Thread t = new Thread(new Runnable() {
                @Override public void run() { loop(); }
            }, "Nexus-ReconnectQueue");
            t.setDaemon(true);
            t.start();
        }
    }

    private void loop() {
        while (true) {
            try {
                tick();
            } catch (Throwable t) {
                NexusPlugin.LOGGER.warn("reconnect queue tick failed", t);
            }
            try { Thread.sleep(100L); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }


    /**
     * Queue a join for this session.
     *
     * @param notBeforeMs extra wait before the request is even considered
     *                    (the per-bot reconnect delay / backoff)
     * @param reason      shown in the queue + event log
     */
    public void enqueue(BotSession session, int notBeforeMs, String reason) {
        if (session == null) return;
        ensureWorker();
        long notBefore = System.nanoTime() + Math.max(0, notBeforeMs) * 1_000_000L;
        String server = serverKey(session);
        synchronized (lock) {
            ServerQueue q = queueFor(server);
            for (JoinTask t : q.pending) {
                if (t.session == session) {
                    t.notBeforeNanos = Math.max(t.notBeforeNanos, notBefore);
                    t.fireAtNanos = 0;            // tick recomputes from notBefore
                    session.reconnectAtNanos = t.notBeforeNanos;
                    eventLocked("INFO", session.username + " 已在队列中，等待时间已更新");
                    return;
                }
            }
            JoinTask task = new JoinTask(session, reason, notBefore);
            q.pending.addLast(task);
            session.reconnectAtNanos = notBefore;   // live countdown in the card
            eventLocked("INFO", session.username + " 加入队列 → " + server
                    + " (" + reason + ")");
        }
    }

    /** Queue a batch (startup restore) — order is preserved per server. */
    public void enqueueAll(List<BotSession> sessions, String reason) {
        if (sessions == null) return;
        for (BotSession s : sessions) enqueue(s, 0, reason);
    }

    /** Forget every pending request for this session (deleted / manually closed). */
    public void cancel(BotSession session) {
        if (session == null) return;
        synchronized (lock) {
            for (ServerQueue q : queues.values()) {
                if (q.pending.removeIf(t -> t.session == session)) {
                    eventLocked("INFO", session.username + " 已退出队列");
                }
            }
            session.reconnectAtNanos = 0;
        }
    }

    /** Drop everything queued. In-flight attempts are left alone. */
    public void clearAll() {
        synchronized (lock) {
            int n = 0;
            for (ServerQueue q : queues.values()) {
                for (JoinTask t : q.pending) {
                    t.session.reconnectAtNanos = 0;
                    n++;
                }
                q.pending.clear();
            }
            eventLocked("INFO", "队列已清空（" + n + " 个）");
        }
    }

    /** Requests still waiting (not counting the in-flight ones). */
    public int pendingCount() {
        synchronized (lock) {
            int n = 0;
            for (ServerQueue q : queues.values()) n += q.pending.size();
            return n;
        }
    }

    /** This bot's queue state, or null when it is not queued and not joining. */
    public Row rowFor(BotSession session) {
        if (session == null) return null;
        synchronized (lock) {
            for (Map.Entry<String, ServerQueue> e : queues.entrySet()) {
                ServerQueue q = e.getValue();
                if (q.inFlight == session)
                    return new Row(session, e.getKey(), "", true, 0, 0);
                int pos = 0;
                for (JoinTask t : q.pending) {
                    pos++;
                    if (t.session == session)
                        return new Row(session, e.getKey(), t.reason, false, pos,
                                t.fireAtNanos);
                }
            }
        }
        return null;
    }

    /** Rolling event log, oldest first. */
    public List<Event> events() {
        synchronized (lock) {
            return new ArrayList<>(events);
        }
    }


    private void eventLocked(String level, String msg) {
        events.addLast(new Event(new java.text.SimpleDateFormat("HH:mm:ss")
                .format(new java.util.Date()), level, msg));
        while (events.size() > MAX_EVENTS) events.pollFirst();
        NexusPlugin.LOGGER.info("[reconnect] {}", msg);
    }

    private static int randomGapMs() {
        int min = NexusConfig.rejoinMinMs();
        int max = NexusConfig.rejoinMaxMs();
        if (max <= min) return min;
        return min + (int) (Math.random() * (max - min + 1));
    }

    private void tick() {
        long now = System.nanoTime();
        boolean serial = NexusConfig.rejoinSerial();
        List<JoinTask> toFire = new ArrayList<>();
        synchronized (lock) {
            for (Map.Entry<String, ServerQueue> e : queues.entrySet()) {
                ServerQueue q = e.getValue();

                if (q.inFlight != null) {
                    BotSession s = q.inFlight;
                    boolean settled = s.state == BotSession.State.ONLINE
                            || s.state == BotSession.State.DISCONNECTED
                            || s.state == BotSession.State.ERROR;
                    boolean timedOut = now > q.attemptDeadlineNanos;
                    if (!settled && !timedOut) continue;   // still joining
                    q.inFlight = null;
                    s.reconnectAtNanos = 0;
                    int gap = randomGapMs();
                    q.nextFireNanos = serial ? now + gap * 1_000_000L : now;
                    if (settled && s.state == BotSession.State.ONLINE) {
                        eventLocked("INFO", s.username + " 已加入 " + e.getKey()
                                + (q.pending.isEmpty() ? "" : "，下一个 " + gap / 1000 + "s 后"));
                    } else {
                        eventLocked("WARN", s.username + " 加入失败（"
                                + (timedOut ? "超时" : String.valueOf(s.state)) + "）"
                                + (q.pending.isEmpty() ? "" : "，下一个 " + gap / 1000 + "s 后"));
                    }
                }

                JoinTask head;
                while ((head = q.pending.peekFirst()) != null) {
                    String why = null;
                    if (head.session.userClosed) why = "用户已关闭";
                    else if (head.session.state == BotSession.State.ONLINE) why = "已在线";
                    else if (head.session.state == BotSession.State.CONNECTING) why = "已在连接";
                    if (why == null) break;
                    q.pending.pollFirst();
                    head.session.reconnectAtNanos = 0;
                    eventLocked("INFO", head.session.username + " 出队（" + why + "）");
                }
                if (q.pending.isEmpty()) continue;

                if (serial && q.inFlight != null) continue;

                head = q.pending.peekFirst();
                long gate = serial ? Math.max(head.notBeforeNanos, q.nextFireNanos)
                        : head.notBeforeNanos;
                if (head.fireAtNanos == 0) head.fireAtNanos = gate;
                head.session.reconnectAtNanos = head.fireAtNanos;
                if (now < head.fireAtNanos) continue;

                q.pending.pollFirst();
                q.inFlight = head.session;
                q.attemptDeadlineNanos = now + ATTEMPT_TIMEOUT_NANOS;
                toFire.add(head);
            }
        }
        for (JoinTask t : toFire) {
            t.session.reconnectAtNanos = 0;
            synchronized (lock) {
                eventLocked("INFO", t.session.username + " 开始加入 " + serverKey(t.session)
                        + (t.reason.isEmpty() ? "" : " (" + t.reason + ")"));
            }
            try {
                BotConnection.connect(t.session);
            } catch (Throwable th) {
                NexusPlugin.LOGGER.warn("queued connect failed for {}", t.session.username, th);
            }
        }
    }
}
