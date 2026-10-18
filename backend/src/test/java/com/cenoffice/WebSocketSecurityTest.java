package com.cenoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:officetest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "app.seed.admin-password=test-admin-pw-1234",
        "app.seed.user-password=test-user-pw-1234",
        "app.jwt.secret=test-secret-test-secret-test-secret-123456",
        "app.cors.allowed-origins=http://localhost:5173"})
class WebSocketSecurityTest {
    @Value("${local.server.port}") int port;

    String token() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"user\",\"password\":\"test-user-pw-1234\"}")).build(), HttpResponse.BodyHandlers.ofString());
        return new ObjectMapper().readTree(r.body()).get("token").asText();
    }

    static class Listener implements WebSocket.Listener {
        final CompletableFuture<String> first = new CompletableFuture<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();

        @Override public java.util.concurrent.CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) { first.complete(data.toString()); ws.request(1); return null; }
        @Override public java.util.concurrent.CompletionStage<?> onClose(WebSocket ws, int code, String reason) { closed.complete(code); return null; }
        @Override public void onOpen(WebSocket ws) { ws.request(1); }
    }

    @Test
    void handshakeWithoutOrWithInvalidTokenIsRejected() {
        HttpClient c = HttpClient.newHttpClient();
        assertThrows(CompletionException.class, () -> c.newWebSocketBuilder().header("Origin", "http://localhost:5173")
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new Listener()).join());
        assertThrows(CompletionException.class, () -> c.newWebSocketBuilder().header("Origin", "http://localhost:5173")
                .buildAsync(URI.create("ws://localhost:" + port + "/ws?token=not-a-jwt"), new Listener()).join());
    }

    @Test
    void validTokenConnectsAndClientMessagesAreRefused() throws Exception {
        Listener l = new Listener();
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder().header("Origin", "http://localhost:5173")
                .buildAsync(URI.create("ws://localhost:" + port + "/ws?token=" + token()), l).get(5, TimeUnit.SECONDS);
        assertTrue(l.first.get(5, TimeUnit.SECONDS).contains("hello"));
        ws.sendText("{\"type\":\"device.command\"}", true);
        assertEquals(1008, l.closed.get(5, TimeUnit.SECONDS), "클라이언트 메시지는 정책 위반(1008)으로 연결이 닫혀야 한다");
    }
}
