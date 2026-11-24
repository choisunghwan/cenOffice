package com.cenoffice;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** 관리자의 사용자 삭제: 삭제하면 그 사람의 토큰이 바로 막히고, 개인 데이터가 지워지며, 내 계정·마지막 관리자는 지울 수 없다 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:useradmintest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "app.seed.admin-password=test-admin-pw-1234", "app.seed.user-password=test-user-pw-1234",
    "app.jwt.secret=test-secret-test-secret-test-secret-123456", "app.llm.provider=mock", "livekit.api-key=", "livekit.api-secret="})
@AutoConfigureMockMvc
class UserAdminApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;

    String token(String path, Map<String, Object> body, String ip) throws Exception {
        var r = mvc.perform(post(path).with(x -> { x.setRemoteAddr(ip); return x; }).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
            .andExpect(status().isOk()).andReturn();
        return "Bearer " + json.readTree(r.getResponse().getContentAsString()).path("token").asText();
    }

    @Test
    void adminDeletesUserAndTheirTokenStopsWorking() throws Exception {
        String admin = token("/api/auth/login", Map.of("username", "admin", "password", "test-admin-pw-1234"), "10.1.0.1");
        String victim = token("/api/auth/signup", Map.of("username", "T90001", "displayName", "테스트", "email", "", "password", "password-1234"), "10.1.0.2");
        db.update("INSERT INTO notifications(username, type, title, created_at) VALUES ('T90001', 'TEST', 't', CURRENT_TIMESTAMP)");
        mvc.perform(get("/api/assistant/status").header("Authorization", victim)).andExpect(status().isOk());

        mvc.perform(delete("/api/admin/users/T90001").header("Authorization", victim)).andExpect(status().isForbidden()); // 일반 사용자는 못 지운다
        mvc.perform(delete("/api/admin/users/T90001").header("Authorization", admin)).andExpect(status().isNoContent());

        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM users WHERE username = 'T90001'", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM notifications WHERE username = 'T90001'", Integer.class));
        mvc.perform(get("/api/assistant/status").header("Authorization", victim)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/admin/users/T90001").header("Authorization", admin)).andExpect(status().isNotFound());

        mvc.perform(delete("/api/admin/users/admin").header("Authorization", admin)).andExpect(status().isBadRequest()); // 내 계정
    }
}
