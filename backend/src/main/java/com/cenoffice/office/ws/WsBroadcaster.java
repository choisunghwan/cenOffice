package com.cenoffice.office.ws;

import com.cenoffice.office.device.DeviceTypes.OfficeEvent;
import com.cenoffice.office.ws.WsEvents.CloseSession;
import com.cenoffice.office.ws.WsEvents.UserEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 인증된 WebSocket 연결 관리와 이벤트 전달.
 *  - 서버→클라이언트: 전체 방송 또는 특정 사용자에게만 전달(UserEvent)
 *  - 클라이언트→서버: 위치(pos)와 생존 신호(hb)만 허용. 그 외 모든 메시지는 연결 종료(1008)
 *  - 사용자 신원은 핸드셰이크에서 검증된 JWT 에서만 얻는다.
 */
@Component
public class WsBroadcaster extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(WsBroadcaster.class);
    public static final int CLOSE_REPLACED = 4001;
    private static final int MAX_MESSAGE_BYTES = 512;

    /** 초당 20건(버스트 40건) 토큰 버킷 */
    private static final class Bucket {
        double tokens = 40;
        long last = System.nanoTime();
        int dropped;

        boolean take() {
            long now = System.nanoTime();
            tokens = Math.min(40, tokens + (now - last) / 1e9 * 20);
            last = now;
            if (tokens >= 1) { tokens -= 1; return true; }
            dropped++;
            return false;
        }
    }

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> userBySession = new ConcurrentHashMap<>();
    private final Map<String, Instant> expiry = new ConcurrentHashMap<>();
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final PresenceService presence;

    public WsBroadcaster(ObjectMapper mapper, PresenceService presence) {
        this.mapper = mapper;
        this.presence = presence;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
        String user = (String) raw.getAttributes().get("user");
        String role = (String) raw.getAttributes().get("role");
        // 사무실 접근 권한: 직원(USER/ADMIN) 역할만 입장 가능
        if (user == null || !("ADMIN".equals(role) || "USER".equals(role))) {
            raw.close(CloseStatus.POLICY_VIOLATION.withReason("no office access"));
            return;
        }
        // 동일 계정 중복 접속 정책: 새 접속이 우선, 이전 접속은 종료한다
        for (Map.Entry<String, String> e : userBySession.entrySet()) {
            if (e.getValue().equals(user)) {
                WebSocketSession old = sessions.get(e.getKey());
                // 먼저 매핑을 지워 두어야, 이전 연결의 종료 콜백이 새 접속의 접속 상태를 지우지 못한다
                forget(e.getKey());
                if (old != null) try { old.close(new CloseStatus(CLOSE_REPLACED, "replaced by a newer session")); } catch (Exception ignored) { }
            }
        }
        WebSocketSession s = new ConcurrentWebSocketSessionDecorator(raw, 5000, 256 * 1024);
        sessions.put(raw.getId(), s);
        userBySession.put(raw.getId(), user);
        buckets.put(raw.getId(), new Bucket());
        if (raw.getAttributes().get("exp") instanceof Instant exp) expiry.put(raw.getId(), exp);

        s.sendMessage(new TextMessage("{\"type\":\"hello\"}"));
        List<PresenceService.Avatar> snapshot = presence.connect(user, raw.getId());
        s.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of("type", "presence.snapshot", "payload", snapshot))));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        String user = userBySession.get(raw.getId());
        forget(raw.getId());
        if (user != null) presence.disconnect(user, raw.getId());
    }

    private void forget(String sessionId) {
        sessions.remove(sessionId);
        userBySession.remove(sessionId);
        expiry.remove(sessionId);
        buckets.remove(sessionId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) throws Exception {
        String user = userBySession.get(raw.getId());
        if (user == null) return;
        if (message.getPayloadLength() > MAX_MESSAGE_BYTES) { raw.close(CloseStatus.TOO_BIG_TO_PROCESS); return; }
        Bucket b = buckets.get(raw.getId());
        if (b != null && !b.take()) {
            if (b.dropped > 400) raw.close(CloseStatus.POLICY_VIOLATION.withReason("rate limit"));
            return;
        }
        JsonNode n;
        try { n = mapper.readTree(message.getPayload()); } catch (Exception e) { raw.close(CloseStatus.POLICY_VIOLATION.withReason("bad message")); return; }
        switch (n.path("type").asText("")) {
            case "pos" -> presence.update(user, raw.getId(), n.path("x").asDouble(Double.NaN), n.path("z").asDouble(Double.NaN),
                    n.path("r").asDouble(Double.NaN), n.path("m").asBoolean(false));
            case "hb" -> presence.heartbeat(user, raw.getId());
            default -> raw.close(CloseStatus.POLICY_VIOLATION.withReason("unsupported message type"));
        }
    }

    /** 토큰이 만료된 연결은 닫는다. */
    @Scheduled(fixedDelay = 30_000)
    void closeExpired() {
        Instant now = Instant.now();
        expiry.forEach((id, exp) -> {
            if (exp.isBefore(now)) close(id, CloseStatus.POLICY_VIOLATION.withReason("token expired"));
        });
    }

    @EventListener
    public void onClose(CloseSession c) { close(c.sessionId(), new CloseStatus(c.code(), c.reason())); }

    private void close(String id, CloseStatus status) {
        WebSocketSession s = sessions.get(id);
        if (s != null) try { s.close(status); } catch (Exception ignored) { }
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void on(OfficeEvent e) { send(null, e.type(), e.payload()); }

    @TransactionalEventListener(fallbackExecution = true)
    public void onUser(UserEvent e) { send(e.recipients(), e.type(), e.payload()); }

    private void send(java.util.Set<String> recipients, String type, Object payload) {
        final String json;
        try {
            json = mapper.writeValueAsString(Map.of("type", type, "payload", payload));
        } catch (Exception ex) {
            log.error("이벤트 직렬화 실패", ex);
            return;
        }
        TextMessage msg = new TextMessage(json);
        sessions.forEach((id, s) -> {
            if (recipients != null && !recipients.contains(userBySession.get(id))) return;
            try {
                if (s.isOpen()) s.sendMessage(msg);
            } catch (Exception ex) {
                log.debug("WS 전송 실패, 세션 제거: {}", id);
                forget(id);
            }
        });
    }
}
