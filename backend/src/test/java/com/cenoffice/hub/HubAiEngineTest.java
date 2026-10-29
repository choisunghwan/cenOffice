package com.cenoffice.hub;

import com.cenoffice.hub.domain.HubSkillRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cenoffice.domain.Enums.Role;
import com.cenoffice.domain.UserAccount;
import com.cenoffice.repo.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/** AI 엔진(HUB_AI_ENGINE=true, 테스트는 Mock LLM): 내장 도구를 engine=ai 로 실행하면 시뮬레이션으로 표시되어 KPI 에서 제외된다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:hubaiengine;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.seed.admin-password=test-admin-pw-1234",
        "app.seed.user-password=test-user-pw-1234",
        "app.jwt.secret=test-secret-test-secret-test-secret-123456",
        "app.hub.ai-engine=true"})
@AutoConfigureMockMvc
class HubAiEngineTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired HubSkillRepository skills;

    String token() throws Exception {
        String name = "a" + UUID.randomUUID().toString().substring(0, 8);
        users.save(new UserAccount(name, encoder.encode("pw-" + name), "엔진-" + name, Role.USER));
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + name + "\",\"password\":\"pw-" + name + "\"}"))
                .andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    @Test void aiEngineInfoAndBuiltinRunViaAiAreSimulatedWithMockLlm() throws Exception {
        String t = token();
        var info = json.readTree(mvc.perform(get("/api/hub/ai-engine").header("Authorization", "Bearer " + t)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(info.get("available").asBoolean());
        assertFalse(info.get("external").asBoolean());
        long id = skills.findByName("cen-log-analyzer").orElseThrow().id;
        String body = "{\"skillId\":" + id + ",\"input\":\"java.lang.NullPointerException: x " + UUID.randomUUID() + "\",\"engine\":\"ai\"}";
        JsonNode e = json.readTree(mvc.perform(post("/api/hub/executions").header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals("llm", e.get("runtimeKind").asText());
        assertTrue(e.get("simulated").asBoolean());
        assertFalse(e.get("counted") != null && e.get("counted").asBoolean());
    }

    @Test void ruleEngineStaysDefaultForBuiltinTools() throws Exception {
        String t = token();
        long id = skills.findByName("cen-sql-reviewer").orElseThrow().id;
        String body = "{\"skillId\":" + id + ",\"input\":\"DELETE FROM t; -- " + UUID.randomUUID() + "\"}";
        JsonNode e = json.readTree(mvc.perform(post("/api/hub/executions").header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals("builtin-local", e.get("runtimeKind").asText());
        assertFalse(e.get("simulated").asBoolean());
    }
}
