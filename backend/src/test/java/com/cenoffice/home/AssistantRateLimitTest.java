package com.cenoffice.home;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 기본 한도(분당 20회)를 그대로 쓰는 환경에서 사용자별로 호출이 제한되는지 확인한다. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:assistantratetest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "app.seed.admin-password=test-admin-pw-1234", "app.seed.user-password=test-user-pw-1234",
    "app.jwt.secret=test-secret-test-secret-test-secret-123456", "app.llm.provider=mock", "livekit.api-key=", "livekit.api-secret="})
@AutoConfigureMockMvc
class AssistantRateLimitTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    String signup(String username, String ip) throws Exception {
        var res = mvc.perform(post("/api/auth/signup").with(r -> { r.setRemoteAddr(ip); return r; }).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("username", username, "displayName", "제한 시험", "email", "", "password", "password-1234")))).andExpect(status().isOk());
        return json.readTree(res.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("token").asText();
    }

    int ask(String token) throws Exception {
        return mvc.perform(post("/api/assistant/interpret").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\":\"일정 보여줘\"}")).andReturn().getResponse().getStatus();
    }

    @Test void callsAreRateLimitedPerUser() throws Exception {
        String a = signup("rate" + UUID.randomUUID().toString().substring(0, 6), "10.3.0.1");
        String b = signup("rate" + UUID.randomUUID().toString().substring(0, 6), "10.3.0.2");
        for (int i = 0; i < AssistantService.DEFAULT_PER_MINUTE; i++) assertEquals(200, ask(a));
        assertEquals(429, ask(a));
        assertEquals(200, ask(b)); // 다른 사용자는 영향을 받지 않는다
    }
}
