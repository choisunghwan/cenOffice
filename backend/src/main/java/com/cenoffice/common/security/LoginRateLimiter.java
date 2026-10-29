package com.cenoffice.common.security;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 로그인 실패 횟수 제한 (단일 인스턴스, 메모리 기반).
 * 계정+IP 조합당 5회, IP당 20회 실패 시 10분간 잠금. 다중 인스턴스 운영 시 Redis 등 공유 저장소로 교체해야 한다.
 */
@Component
public class LoginRateLimiter {
    static final int MAX_PER_KEY = 5;
    static final int MAX_PER_IP = 20;
    static final Duration WINDOW = Duration.ofMinutes(10);

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    private static String key(String username, String ip) { return "u:" + username.toLowerCase() + "|" + ip; }

    private static String ipKey(String ip) { return "ip:" + ip; }

    /** 잠겨 있으면 남은 초, 아니면 0 */
    public long lockedSeconds(String username, String ip) {
        return Math.max(remaining(key(username, ip), MAX_PER_KEY), remaining(ipKey(ip), MAX_PER_IP));
    }

    public void recordFailure(String username, String ip) {
        add(key(username, ip));
        add(ipKey(ip));
    }

    public void recordSuccess(String username, String ip) { failures.remove(key(username, ip)); }

    private void add(String k) {
        Deque<Instant> q = failures.computeIfAbsent(k, x -> new ArrayDeque<>());
        synchronized (q) { prune(q); q.addLast(Instant.now()); }
    }

    private long remaining(String k, int max) {
        Deque<Instant> q = failures.get(k);
        if (q == null) return 0;
        synchronized (q) {
            prune(q);
            if (q.size() < max) return 0;
            long s = Duration.between(Instant.now(), q.peekLast().plus(WINDOW)).toSeconds();
            return Math.max(s, 1);
        }
    }

    private static void prune(Deque<Instant> q) {
        Instant cutoff = Instant.now().minus(WINDOW);
        while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) q.pollFirst();
    }

    @Scheduled(fixedDelay = 60_000)
    void cleanup() {
        failures.entrySet().removeIf(e -> { synchronized (e.getValue()) { prune(e.getValue()); return e.getValue().isEmpty(); } });
    }
}
