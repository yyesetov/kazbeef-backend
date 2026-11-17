package kz.kazbeef.kb;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Ограничение частоты вопросов с одного адреса: не больше N за скользящую минуту. В памяти процесса. */
@Component
public class RateLimiter {

    private static final long WINDOW_MS = 60_000;

    private final int limit;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public RateLimiter(KbProperties props) {
        this.limit = Math.max(1, props.ratePerMinute());
    }

    /** true — запрос можно выполнить и он учтён. */
    public boolean tryAcquire(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) q.pollFirst();
            if (q.size() >= limit) return false;
            q.addLast(now);
            return true;
        }
    }

    /** Секунд до освобождения места. */
    public long retryAfterSeconds(String key) {
        Deque<Long> q = hits.get(key);
        if (q == null) return 0;
        synchronized (q) {
            Long first = q.peekFirst();
            return first == null ? 0 : Math.max(1, (WINDOW_MS - (System.currentTimeMillis() - first)) / 1000);
        }
    }

    @Scheduled(fixedDelay = 300_000)
    public void cleanup() {
        long now = System.currentTimeMillis();
        hits.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                Long last = e.getValue().peekLast();
                return last == null || now - last > WINDOW_MS;
            }
        });
    }
}
