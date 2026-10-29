package com.cenoffice.home;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 홈 입력창의 채팅방(대화 세션) 저장: 본인 것만 보이고, 새로고침·다른 PC에서도 이어진다. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:chatsessiontest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "app.seed.admin-password=test-admin-pw-1234", "app.seed.user-password=test-user-pw-1234",
    "app.jwt.secret=test-secret-test-secret-test-secret-123456", "app.llm.provider=mock", "livekit.api-key=", "livekit.api-secret="})
@AutoConfigureMockMvc
class ChatSessionApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    String admin, user;

    @BeforeEach void setup() throws Exception {
        admin = login("admin", "test-admin-pw-1234");
        user = login("user", "test-user-pw-1234");
    }

    String login(String u, String p) throws Exception {
        return read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username", u, "password", p))).with(r -> { r.setRemoteAddr("10.9.0.1"); return r; }))).path("token").asText();
    }

    ResultActions req(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(b);
    }

    JsonNode read(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }

    String create(String token, String title) throws Exception {
        return read(req(post("/api/assistant/sessions"), token, Map.of("title", title)).andExpect(status().isCreated())).path("id").asText();
    }

    @Test void createdSessionIsListedAndStartsEmpty() throws Exception {
        String id = create(admin, "내일 일정 잡기");
        var got = read(req(get("/api/assistant/sessions/" + id), admin, null).andExpect(status().isOk()));
        assertEquals("내일 일정 잡기", got.path("title").asText());
        assertEquals(0, got.path("turns").size());
        boolean listed = false;
        for (var s : read(req(get("/api/assistant/sessions"), admin, null).andExpect(status().isOk()))) if (s.path("id").asText().equals(id)) listed = true;
        assertTrue(listed);
    }

    @Test void turnsAndTitleAreSavedAndComeBackSame() throws Exception {
        String id = create(admin, "새 채팅");
        var turns = List.of(Map.of("id", 1, "role", "user", "text", "오늘 브리핑 해줘"), Map.of("id", 2, "role", "assistant", "reply", Map.of("intent", "BRIEFING", "message", "오늘은 일정 2건입니다.")));
        req(put("/api/assistant/sessions/" + id), admin, Map.of("title", "오늘 브리핑", "turns", turns)).andExpect(status().isNoContent());
        var got = read(req(get("/api/assistant/sessions/" + id), admin, null).andExpect(status().isOk()));
        assertEquals("오늘 브리핑", got.path("title").asText());
        assertEquals(2, got.path("turns").size());
        assertEquals("오늘은 일정 2건입니다.", got.path("turns").get(1).path("reply").path("message").asText());
    }

    @Test void recentlyUpdatedSessionComesFirst() throws Exception {
        String a = create(admin, "먼저 만든 방");
        Thread.sleep(20);
        String b = create(admin, "나중에 만든 방");
        Thread.sleep(20);
        req(put("/api/assistant/sessions/" + a), admin, Map.of("turns", List.of())).andExpect(status().isNoContent()); // a 를 다시 쓰면 맨 위로
        var list = read(req(get("/api/assistant/sessions"), admin, null).andExpect(status().isOk()));
        assertEquals(a, list.get(0).path("id").asText());
        assertNotEquals(b, list.get(0).path("id").asText());
    }

    @Test void otherUsersCannotSeeChangeOrDeleteMySession() throws Exception {
        String id = create(admin, "비공개 대화");
        req(get("/api/assistant/sessions/" + id), user, null).andExpect(status().isNotFound());
        req(put("/api/assistant/sessions/" + id), user, Map.of("title", "가로채기")).andExpect(status().isNotFound());
        req(delete("/api/assistant/sessions/" + id), user, null).andExpect(status().isNotFound());
        for (var s : read(req(get("/api/assistant/sessions"), user, null).andExpect(status().isOk()))) assertNotEquals(id, s.path("id").asText());
        assertEquals("비공개 대화", read(req(get("/api/assistant/sessions/" + id), admin, null)).path("title").asText()); // 내 것은 그대로
    }

    @Test void deleteRemovesTheSession() throws Exception {
        String id = create(admin, "지울 방");
        req(delete("/api/assistant/sessions/" + id), admin, null).andExpect(status().isNoContent());
        req(get("/api/assistant/sessions/" + id), admin, null).andExpect(status().isNotFound());
    }

    @Test void invalidTitlesAndTurnsAreRejected() throws Exception {
        String id = create(admin, "검사");
        req(put("/api/assistant/sessions/" + id), admin, Map.of("title", "가".repeat(121))).andExpect(status().isBadRequest());
        req(put("/api/assistant/sessions/" + id), admin, Map.of("turns", "배열이 아님")).andExpect(status().isBadRequest());
        var big = List.of(Map.of("id", 1, "role", "user", "text", "x".repeat(600_000)));
        req(put("/api/assistant/sessions/" + id), admin, Map.of("turns", big)).andExpect(status().isBadRequest());
        req(post("/api/assistant/sessions"), admin, Map.of("title", "")).andExpect(status().isCreated()); // 제목이 비면 기본 이름
    }

    @Test void requiresLogin() throws Exception {
        mvc.perform(get("/api/assistant/sessions")).andExpect(status().isUnauthorized());
    }
}
