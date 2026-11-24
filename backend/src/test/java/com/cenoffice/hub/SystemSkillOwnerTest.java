package com.cenoffice.hub;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** HUB_SKILL_OWNER 로 지정한 계정이 시스템 스킬(내장·기본 AI)의 제작자처럼 다뤄진다. 내장 스킬 실행은 그대로 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:sysowner;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "app.seed.admin-password=test-admin-pw-1234", "app.seed.user-password=test-user-pw-1234",
    "app.jwt.secret=test-secret-test-secret-test-secret-123456", "app.llm.provider=mock", "livekit.api-key=", "livekit.api-secret=",
    "app.hub.system-owner=user"})
@AutoConfigureMockMvc
class SystemSkillOwnerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    String login(String u, String p) throws Exception {
        var r = mvc.perform(post("/api/auth/login").with(x -> { x.setRemoteAddr("10.9.0." + u.length()); return x; }).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("username", u, "password", p)))).andExpect(status().isOk()).andReturn();
        return "Bearer " + json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("token").asText();
    }
    JsonNode fetch(MockHttpServletRequestBuilder b, String t) throws Exception {
        return json.readTree(mvc.perform(b.header("Authorization", t)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test void configuredAccountOwnsSystemSkills() throws Exception {
        String user = login("user", "test-user-pw-1234"), admin = login("admin", "test-admin-pw-1234");
        JsonNode mine = fetch(get("/api/hub/skills?mine=true&size=100"), user).path("items");
        assertTrue(mine.size() >= 11, "내장 8 + 기본 AI 3: " + mine.size());
        for (JsonNode s : mine) assertEquals("user", s.path("owner").asText());
        assertNotEquals("최성환", mine.get(0).path("ownerName").asText(), "고정 글자 대신 계정의 표시 이름");

        long id = mine.get(0).path("id").asLong();
        assertTrue(fetch(get("/api/hub/skills/" + id), user).path("canEdit").asBoolean(), "지정한 계정은 수정할 수 있다");
        mvc.perform(post("/api/hub/skills/" + id + "/reviews").header("Authorization", user).contentType(MediaType.APPLICATION_JSON)
            .content("{\"rating\":5}")).andExpect(status().isForbidden()); // 본인 스킬은 평가할 수 없다

        assertEquals(0, fetch(get("/api/hub/skills?mine=true&size=100"), admin).path("items").size(), "다른 계정의 내 스킬에는 나오지 않는다");
    }
}
