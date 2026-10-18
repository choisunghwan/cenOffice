package com.cenoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:presencetest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "app.seed.admin-password=test-admin-pw-1234",
        "app.seed.user-password=test-user-pw-1234",
        "app.jwt.secret=test-secret-test-secret-test-secret-123456",
        "app.cors.allowed-origins=http://localhost:5173"})
class PresenceWsTest {
    @Value("${local.server.port}") int port;
    final ObjectMapper om = new ObjectMapper();

    String token(String user, String pw) throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"" + user + "\",\"password\":\"" + pw + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
        return om.readTree(r.body()).get("token").asText();
    }

    static class Client implements WebSocket.Listener {
        final BlockingQueue<JsonNode> msgs = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        WebSocket ws;
        final ObjectMapper om = new ObjectMapper();

        @Override public void onOpen(WebSocket w) { ws = w; w.request(1); }
        @Override public CompletionStage<?> onText(WebSocket w, CharSequence d, boolean last) {
            try { msgs.add(om.readTree(d.toString())); } catch (Exception ignored) { }
            w.request(1);
            return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket w, int code, String reason) { closed.complete(code); return null; }

        void send(String json) { ws.sendText(json, true).join(); }

        /** type 이 일치하고 조건을 만족하는 메시지를 기다린다 */
        JsonNode await(String type, java.util.function.Predicate<JsonNode> cond, long ms) throws Exception {
            long end = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < end) {
                JsonNode m = msgs.poll(100, TimeUnit.MILLISECONDS);
                if (m != null && type.equals(m.path("type").asText()) && cond.test(m)) return m;
            }
            return null;
        }
    }

    Client connect(String token) throws Exception {
        Client c = new Client();
        HttpClient.newHttpClient().newWebSocketBuilder().header("Origin", "http://localhost:5173")
                .buildAsync(URI.create("ws://localhost:" + port + "/ws?token=" + token), c).get(5, TimeUnit.SECONDS);
        return c;
    }

    static boolean has(JsonNode snapshotPayload, String user) {
        for (JsonNode a : snapshotPayload) if (a.get("username").asText().equals(user)) return true;
        return false;
    }

    @Test
    void multiplayerPresenceLifecycle() throws Exception {
        Client admin = connect(token("admin", "test-admin-pw-1234"));
        JsonNode s1 = admin.await("presence.snapshot", m -> true, 3000);
        assertNotNull(s1);
        assertTrue(has(s1.get("payload"), "admin"));

        // 두 번째 직원 접속 → 스냅샷에 둘 다, 기존 접속자는 join 이벤트 수신. 이름/팀은 계정 정보에서 온다.
        Client user = connect(token("user", "test-user-pw-1234"));
        JsonNode s2 = user.await("presence.snapshot", m -> true, 3000);
        assertTrue(has(s2.get("payload"), "admin") && has(s2.get("payload"), "user"));
        JsonNode join = admin.await("presence.join", m -> m.get("payload").get("username").asText().equals("user"), 3000);
        assertNotNull(join);
        assertEquals("일반 사용자", join.get("payload").get("displayName").asText());
        assertEquals("개발팀", join.get("payload").get("team").asText());

        // 위치 동기화 + 위장 불가: 메시지에 다른 username 을 넣어도 발신 세션의 계정으로 처리된다
        user.send("{\"type\":\"pos\",\"x\":-5,\"z\":3,\"r\":1.5,\"m\":true,\"username\":\"admin\",\"u\":\"admin\"}");
        JsonNode moves = admin.await("presence.moves", m -> m.get("payload").size() > 0, 3000);
        assertNotNull(moves);
        JsonNode mv = moves.get("payload").get(0);
        assertEquals("user", mv.get("u").asText());
        assertEquals(-5.0, mv.get("x").asDouble(), 0.01);
        assertTrue(mv.get("m").asBoolean());

        // 범위 밖 좌표와 순간이동은 전파되지 않는다
        user.send("{\"type\":\"pos\",\"x\":99,\"z\":0,\"r\":0,\"m\":false}");
        assertNull(admin.await("presence.moves", m -> m.get("payload").get(0).get("x").asDouble() > 50, 600));
        user.send("{\"type\":\"pos\",\"x\":11,\"z\":-7,\"r\":0,\"m\":false}");
        assertNull(admin.await("presence.moves", m -> m.get("payload").get(0).get("x").asDouble() > 10, 600));

        // 동일 계정 재접속: 새 접속 우선, 이전 연결은 4001 로 종료, 다른 직원에게는 퇴장 알림이 가지 않는다
        Client user2 = connect(token("user", "test-user-pw-1234"));
        assertEquals(4001, user.closed.get(3, TimeUnit.SECONDS));
        assertNotNull(user2.await("presence.snapshot", m -> true, 3000));
        assertNull(admin.await("presence.leave", m -> m.get("payload").get("username").asText().equals("user"), 800));

        // 허용되지 않은 메시지 타입 → 연결 종료
        Client rogue = connect(token("user", "test-user-pw-1234")); // user2 를 다시 대체
        assertEquals(4001, user2.closed.get(3, TimeUnit.SECONDS));
        rogue.send("{\"type\":\"chat\",\"text\":\"hi\"}");
        assertEquals(1008, rogue.closed.get(3, TimeUnit.SECONDS));
        // 비정상 종료한 사용자는 퇴장 처리
        assertNotNull(admin.await("presence.leave", m -> m.get("payload").get("username").asText().equals("user"), 3000));

        // 재접속하면 다시 스냅샷을 받고 admin 에게 join 이 온다
        Client back = connect(token("user", "test-user-pw-1234"));
        assertNotNull(back.await("presence.snapshot", m -> true, 3000));
        assertNotNull(admin.await("presence.join", m -> m.get("payload").get("username").asText().equals("user"), 3000));

        // 정상 종료 → leave
        back.ws.sendClose(1000, "bye").join();
        assertNotNull(admin.await("presence.leave", m -> m.get("payload").get("username").asText().equals("user"), 3000));
        assertFalse(admin.closed.isDone());
        admin.ws.sendClose(1000, "bye").join();
    }
}
