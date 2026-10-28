package kr.ac.ajou.shima.ratelimit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 요청 횟수 제한 — 키별 슬라이딩 윈도우(최근 요청 시각 목록).
 * 서버 메모리에 보관하므로 서버가 재시작되면 초기화되고, 서버가 여러 대면 각자 센다(PoC는 1대).
 */
@Component
public class RateLimiter {

    private static final long KEEP_MS = Duration.ofDays(1).toMillis(); // 가장 긴 윈도우

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    /** 한도 안이면 이번 요청을 기록, 넘으면 429 예외 */
    public void hit(String key, int limit, Duration window, String message) {
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q, now - window.toMillis());
            if (q.size() >= limit) throw tooMany(q, window, now, message);
            q.addLast(now);
        }
    }

    /** 기록 없이 한도만 확인 (로그인 실패 잠금처럼 "실패했을 때만" 세는 경우) */
    public void checkOnly(String key, int limit, Duration window, String message) {
        Deque<Long> q = hits.get(key);
        if (q == null) return;
        long now = System.currentTimeMillis();
        synchronized (q) {
            prune(q, now - window.toMillis());
            if (q.size() >= limit) throw tooMany(q, window, now, message);
        }
    }

    /** 기록만 (예: 로그인 실패 1회) */
    public void record(String key) {
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) { q.addLast(System.currentTimeMillis()); }
    }

    public void reset(String key) {
        hits.remove(key);
    }

    private static void prune(Deque<Long> q, long cutoff) {
        while (!q.isEmpty() && q.peekFirst() <= cutoff) q.pollFirst();
    }

    private static TooManyRequestsException tooMany(Deque<Long> q, Duration window, long now, String message) {
        long retryMs = q.peekFirst() + window.toMillis() - now;
        return new TooManyRequestsException(message, Math.max(1, retryMs / 1000 + 1));
    }

    /** 오래된 키 정리 — 메모리 누수 방지 */
    @Scheduled(fixedDelay = 10 * 60 * 1000)
    void cleanup() {
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        hits.entrySet().removeIf(e -> {
            Deque<Long> q = e.getValue();
            synchronized (q) {
                prune(q, cutoff);
                return q.isEmpty();
            }
        });
    }
}
