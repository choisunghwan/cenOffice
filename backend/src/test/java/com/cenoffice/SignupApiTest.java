package com.cenoffice;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:signuptest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "app.seed.admin-password=test-admin-pw-1234", "app.seed.user-password=test-user-pw-1234",
    "app.jwt.secret=test-secret-test-secret-test-secret-123456", "app.llm.provider=mock", "livekit.api-key=", "livekit.api-secret="})
@AutoConfigureMockMvc
class SignupApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;

    ResultActions signup(String ip, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/auth/signup").with(r -> { r.setRemoteAddr(ip); return r; })
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    @Test
    void signupCreatesPlainUserSavesEmailAndCanLogInWithoutToken() throws Exception {
        var res = signup("10.0.0.1", Map.of("username", "E10001", "displayName", "홍길동", "email", "hong@example.com", "password", "password-1234")).andExpect(status().isOk());
        var body = json.readTree(res.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals("USER", body.path("user").path("role").asText());
        assertFalse(body.path("token").asText().isBlank());
        assertEquals("hong@example.com", db.queryForObject("select email from users where username='E10001'", String.class));
        assertNotEquals("password-1234", db.queryForObject("select password_hash from users where username='E10001'", String.class));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username", "E10001", "password", "password-1234")))).andExpect(status().isOk());
    }

    @Test
    void duplicateAndInvalidInputsAreRejected() throws Exception {
        signup("10.0.0.2", Map.of("username", "E10002", "displayName", "가", "email", "", "password", "password-1234")).andExpect(status().isOk());
        signup("10.0.0.2", Map.of("username", "E10002", "displayName", "나", "email", "", "password", "password-1234")).andExpect(status().isConflict());
        signup("10.0.0.3", Map.of("username", "admin", "displayName", "x", "email", "", "password", "password-1234")).andExpect(status().isConflict()); // 기존 관리자 사번으로는 가입 불가
        signup("10.0.0.4", Map.of("username", "ab", "displayName", "x", "email", "", "password", "password-1234")).andExpect(status().isBadRequest());
        signup("10.0.0.5", Map.of("username", "E10003", "displayName", "x", "email", "", "password", "short")).andExpect(status().isBadRequest());
        signup("10.0.0.6", Map.of("username", "E10004", "displayName", "x", "email", "not-an-email", "password", "password-1234")).andExpect(status().isBadRequest());
    }

    @Test
    void signupIsRateLimitedPerIp() throws Exception {
        for (int i = 0; i < 5; i++) signup("10.0.0.9", Map.of("username", "R1000" + i, "displayName", "r", "email", "", "password", "password-1234")).andExpect(status().isOk());
        signup("10.0.0.9", Map.of("username", "R10009", "displayName", "r", "email", "", "password", "password-1234")).andExpect(status().isTooManyRequests());
    }
}
