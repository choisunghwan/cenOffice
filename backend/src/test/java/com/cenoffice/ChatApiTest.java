package com.cenoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cenoffice.domain.Enums.Role;
import com.cenoffice.domain.UserAccount;
import com.cenoffice.repo.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:officetest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "app.seed.admin-password=test-admin-pw-1234",
        "app.seed.user-password=test-user-pw-1234",
        "app.jwt.secret=test-secret-test-secret-test-secret-123456",
        "app.devices.mock.min-latency-ms=150",
        "app.devices.mock.max-latency-ms=200",
        "app.devices.mock.failure-rate=0.0",
        "app.devices.command-timeout-seconds=5"})
@AutoConfigureMockMvc
class ChatApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    /** 테스트마다 새 계정을 만들어 전송 빈도 제한이 서로 영향을 주지 않게 한다. */
    record Acct(String name, String token) {}

    Acct account() throws Exception {
        String name = "t" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount u = new UserAccount(name, encoder.encode("pw-" + name), "테스터-" + name, Role.USER);
        u.setTeam("QA팀");
        users.save(u);
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + name + "\",\"password\":\"pw-" + name + "\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Acct(name, json.readTree(body).get("token").asText());
    }

    ResultActions call(MockHttpServletRequestBuilder b, Acct a, String body) throws Exception {
        b.header("Authorization", "Bearer " + a.token());
        if (body != null) b.contentType(new MediaType("application", "json", java.nio.charset.StandardCharsets.UTF_8)).content(body);
        return mvc.perform(b);
    }

    JsonNode read(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)); }

    long officeRoom(Acct a) throws Exception {
        for (JsonNode r : read(call(get("/api/chat/rooms"), a, null))) if (r.get("type").asText().equals("OFFICE")) return r.get("id").asLong();
        throw new IllegalStateException("no office room");
    }

    String msg(String content) throws Exception { return json.writeValueAsString(java.util.Map.of("content", content)); }

    @Test
    void anonymousCannotUseChat() throws Exception {
        mvc.perform(get("/api/chat/rooms")).andExpect(status().isUnauthorized());
    }

    @Test
    void officeMessageIsStoredWithServerSideSenderAndShownInHistory() throws Exception {
        Acct a = account(), b = account();
        long room = officeRoom(a);
        // 클라이언트가 보낸 sender 값은 무시되고 인증된 계정이 발신자로 기록된다
        JsonNode sent = read(call(post("/api/chat/rooms/" + room + "/messages"), a,
                "{\"content\":\"안녕하세요 모두\",\"sender\":\"admin\",\"senderName\":\"관리자\"}").andExpect(status().isCreated()));
        assertEquals(a.name(), sent.get("sender").asText());
        assertEquals("USER", sent.get("senderType").asText());
        JsonNode hist = read(call(get("/api/chat/rooms/" + room + "/messages?limit=100"), b, null).andExpect(status().isOk()));
        boolean found = false;
        for (JsonNode m : hist) if (m.get("content").asText().equals("안녕하세요 모두") && m.get("sender").asText().equals(a.name())) found = true;
        assertTrue(found);
    }

    @Test
    void directChatIsVisibleOnlyToParticipants() throws Exception {
        Acct a = account(), b = account(), outsider = account();
        long dm = read(call(post("/api/chat/rooms/direct"), a, "{\"username\":\"" + b.name() + "\"}").andExpect(status().isOk())).get("id").asLong();
        // 상대방이 같은 방을 요청하면 동일한 방
        assertEquals(dm, read(call(post("/api/chat/rooms/direct"), b, "{\"username\":\"" + a.name() + "\"}")).get("id").asLong());
        call(post("/api/chat/rooms/" + dm + "/messages"), a, msg("비밀 이야기")).andExpect(status().isCreated());

        assertEquals(1, read(call(get("/api/chat/rooms/" + dm + "/messages"), b, null).andExpect(status().isOk())).size());
        call(get("/api/chat/rooms/" + dm + "/messages"), outsider, null).andExpect(status().isForbidden());
        call(post("/api/chat/rooms/" + dm + "/messages"), outsider, msg("끼어들기")).andExpect(status().isForbidden());
        // 외부인의 방 목록에도 나타나지 않는다
        for (JsonNode r : read(call(get("/api/chat/rooms"), outsider, null))) assertTrue(r.get("id").asLong() != dm);
        call(post("/api/chat/rooms/direct"), a, "{\"username\":\"" + a.name() + "\"}").andExpect(status().isBadRequest());
    }

    @Test
    void aiRoomIsPrivateAndAgentRepliesAsMock() throws Exception {
        Acct a = account(), other = account();
        JsonNode room = read(call(post("/api/chat/rooms/ai"), a, "{\"agentCode\":\"ari\"}").andExpect(status().isOk()));
        long id = room.get("id").asLong();
        call(post("/api/chat/rooms/" + id + "/messages"), a, msg("서버 상태 알려줘")).andExpect(status().isCreated());
        call(get("/api/chat/rooms/" + id + "/messages"), other, null).andExpect(status().isForbidden());
        JsonNode reply = null;
        for (int i = 0; i < 30 && reply == null; i++) {
            Thread.sleep(200);
            for (JsonNode m : read(call(get("/api/chat/rooms/" + id + "/messages"), a, null))) if (m.get("senderType").asText().equals("AI")) reply = m;
        }
        assertTrue(reply != null, "AI 응답이 저장되어야 한다");
        assertEquals("ari", reply.get("sender").asText());
        call(post("/api/chat/rooms/ai"), a, "{\"agentCode\":\"no-such\"}").andExpect(status().isNotFound());
    }

    @Test
    void invalidMessagesAreRejected() throws Exception {
        Acct a = account();
        long room = officeRoom(a);
        call(post("/api/chat/rooms/" + room + "/messages"), a, msg("   ")).andExpect(status().isBadRequest());
        call(post("/api/chat/rooms/" + room + "/messages"), a, msg("가".repeat(501))).andExpect(status().isBadRequest());
        call(post("/api/chat/rooms/" + room + "/messages"), a, msg("가".repeat(500))).andExpect(status().isCreated());
    }

    @Test
    void htmlIsStoredAsPlainTextNotInterpreted() throws Exception {
        Acct a = account();
        long room = officeRoom(a);
        String payload = "<img src=x onerror=alert(1)><script>alert(2)</script>";
        JsonNode sent = read(call(post("/api/chat/rooms/" + room + "/messages"), a, msg(payload)).andExpect(status().isCreated()));
        // 서버는 내용을 변조하지 않고 JSON 문자열로만 돌려준다(렌더링은 클라이언트가 텍스트로만 출력)
        assertEquals(payload, sent.get("content").asText());
        String contentType = call(get("/api/chat/rooms/" + room + "/messages"), a, null).andReturn().getResponse().getContentType();
        assertTrue(contentType.startsWith("application/json"));
    }

    @Test
    void sendingTooFastIsRateLimited() throws Exception {
        Acct a = account();
        long room = officeRoom(a);
        for (int i = 0; i < 5; i++) call(post("/api/chat/rooms/" + room + "/messages"), a, msg("m" + i)).andExpect(status().isCreated());
        call(post("/api/chat/rooms/" + room + "/messages"), a, msg("one more")).andExpect(status().isTooManyRequests());
    }

    @Test
    void deniedRoomAccessIsAudited() throws Exception {
        Acct a = account(), b = account(), outsider = account();
        long dm = read(call(post("/api/chat/rooms/direct"), a, "{\"username\":\"" + b.name() + "\"}")).get("id").asLong();
        call(get("/api/chat/rooms/" + dm + "/messages"), outsider, null).andExpect(status().isForbidden());
        String adminToken = json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"test-admin-pw-1234\"}")).andReturn().getResponse().getContentAsString()).get("token").asText();
        JsonNode logs = read(call(get("/api/audit-logs?limit=200"), new Acct("admin", adminToken), null));
        boolean found = false;
        for (JsonNode l : logs) if (l.get("action").asText().equals("CHAT_ACCESS_DENIED") && l.get("actor").asText().equals(outsider.name())) found = true;
        assertTrue(found, "롤백되는 요청이어도 접근 거부가 감사 로그에 남아야 한다");
    }
}
