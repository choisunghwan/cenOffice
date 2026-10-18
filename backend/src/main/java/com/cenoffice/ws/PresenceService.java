package com.cenoffice.ws;

import com.cenoffice.domain.UserAccount;
import com.cenoffice.repo.UserRepository;
import com.cenoffice.ws.WsEvents.CloseSession;
import com.cenoffice.ws.WsEvents.UserEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 사무실 접속자와 아바타 위치. 빈번히 바뀌는 값이라 DB 에 저장하지 않고 서버 메모리에만 둔다.
 * 사용자 식별(username)은 항상 인증된 세션에서 오며, 클라이언트 메시지의 어떤 필드로도 바꿀 수 없다.
 */
@Component
public class PresenceService {
    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);

    static final double MAX_X = 12.5, MAX_Z = 8.5;
    static final double MAX_SPEED = 8.0; // m/s (정상 이동 3.6 m/s 의 2배 이상 여유)
    static final long STALE_MS = 15_000;

    public record Avatar(String username, String displayName, String team, double x, double z, double r, boolean m) {}

    private static final class Entry {
        final String username, displayName, team;
        volatile String sessionId;
        double x = -7, z = 5.2, r = Math.PI;
        boolean moving, dirty, hasPos;
        long lastSeen = System.currentTimeMillis(), lastPosAt;
        int violations;

        Entry(String username, String displayName, String team, String sessionId) {
            this.username = username; this.displayName = displayName; this.team = team; this.sessionId = sessionId;
        }

        Avatar view() { return new Avatar(username, displayName, team, round(x), round(z), round(r), moving); }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final UserRepository users;
    private final ApplicationEventPublisher events;

    public PresenceService(UserRepository users, ApplicationEventPublisher events) {
        this.users = users;
        this.events = events;
    }

    private static double round(double v) { return Math.round(v * 100) / 100.0; }

    /** 접속 처리. 같은 계정의 기존 항목은 새 세션으로 교체된다(새 접속 우선). 현재 접속자 스냅샷을 돌려준다. */
    public List<Avatar> connect(String username, String sessionId) {
        UserAccount u = users.findByUsername(username).orElse(null);
        String display = u != null ? u.getDisplayName() : username;
        String team = u != null && u.getTeam() != null ? u.getTeam() : "";
        Entry e = new Entry(username, display, team, sessionId);
        boolean existed = entries.put(username, e) != null;
        if (!existed) events.publishEvent(UserEvent.all("presence.join", e.view()));
        else events.publishEvent(UserEvent.all("presence.move", e.view())); // 재접속: 위치 초기화 알림
        return entries.values().stream().map(Entry::view).toList();
    }

    public void disconnect(String username, String sessionId) {
        Entry e = entries.get(username);
        // 교체된(이전) 세션의 종료는 현재 접속에 영향을 주지 않는다
        if (e == null || !e.sessionId.equals(sessionId)) return;
        if (entries.remove(username, e)) events.publishEvent(UserEvent.all("presence.leave", Map.of("username", username)));
    }

    public void heartbeat(String username, String sessionId) {
        Entry e = entries.get(username);
        if (e != null && e.sessionId.equals(sessionId)) e.lastSeen = System.currentTimeMillis();
    }

    /** 위치 갱신. 범위/속도 검증에 실패한 값은 버린다(순간이동 방지). */
    public void update(String username, String sessionId, double x, double z, double r, boolean moving) {
        Entry e = entries.get(username);
        if (e == null || !e.sessionId.equals(sessionId)) return;
        if (!Double.isFinite(x) || !Double.isFinite(z) || !Double.isFinite(r)) return;
        if (Math.abs(x) > MAX_X || Math.abs(z) > MAX_Z || Math.abs(r) > 100) return;
        synchronized (e) {
            long now = System.currentTimeMillis();
            e.lastSeen = now;
            if (e.hasPos) {
                double dt = Math.max(now - e.lastPosAt, 50) / 1000.0;
                double dist = Math.hypot(x - e.x, z - e.z);
                if (dist > MAX_SPEED * dt + 2.0) {
                    if (++e.violations == 5) log.warn("비정상적인 이동 속도 감지: {}", username);
                    return;
                }
            }
            e.x = x; e.z = z; e.r = r; e.moving = moving; e.hasPos = true; e.lastPosAt = now; e.dirty = true;
        }
    }

    public boolean isOnline(String username) { return entries.containsKey(username); }

    /** 변경된 아바타만 모아 100ms 마다 한 번에 전송 → 네트워크 전송 최소화 */
    @Scheduled(fixedDelay = 100)
    void flushMoves() {
        List<Map<String, Object>> batch = null;
        for (Entry e : entries.values()) {
            synchronized (e) {
                if (!e.dirty) continue;
                e.dirty = false;
                if (batch == null) batch = new ArrayList<>();
                batch.add(Map.of("u", e.username, "x", round(e.x), "z", round(e.z), "r", round(e.r), "m", e.moving));
            }
        }
        if (batch != null) events.publishEvent(UserEvent.all("presence.moves", batch));
    }

    /** 하트비트가 끊긴 연결(네트워크 단절 등)을 정리한다. */
    @Scheduled(fixedDelay = 5000)
    void sweepStale() {
        long limit = System.currentTimeMillis() - STALE_MS;
        for (Entry e : entries.values()) {
            if (e.lastSeen < limit) {
                String sid = e.sessionId;
                disconnect(e.username, sid);
                events.publishEvent(new CloseSession(sid, 1001, "heartbeat timeout"));
            }
        }
    }
}
