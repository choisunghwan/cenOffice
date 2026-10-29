package com.cenoffice.hub;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cenoffice.common.domain.Enums.Role;
import com.cenoffice.common.domain.UserAccount;
import com.cenoffice.common.repo.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
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

/** Mock 런타임(HUB_RUNTIME=mock, 테스트 전용) — 모든 실행은 시뮬레이션이며 KPI 에 집계되지 않아야 한다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:hubruntime;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.seed.admin-password=test-admin-pw-1234",
        "app.seed.user-password=test-user-pw-1234",
        "app.jwt.secret=test-secret-test-secret-test-secret-123456",
        "app.hub.runtime=mock"})
@AutoConfigureMockMvc
class HubRuntimeTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    record Acct(String name, String token) {}

    Acct account() throws Exception {
        String name = "r" + UUID.randomUUID().toString().substring(0, 8);
        users.save(new UserAccount(name, encoder.encode("pw-" + name), "런타임-" + name, Role.USER));
        return new Acct(name, login(name, "pw-" + name));
    }

    String login(String u, String p) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}"))
                .andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    Acct admin() throws Exception { return new Acct("admin", login("admin", "test-admin-pw-1234")); }

    ResultActions call(MockHttpServletRequestBuilder b, Acct a, String body) throws Exception {
        b.header("Authorization", "Bearer " + a.token());
        if (body != null) b.contentType(new MediaType("application", "json", StandardCharsets.UTF_8)).content(body);
        return mvc.perform(b);
    }

    JsonNode read(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }

    long approvedSkill(Acct owner, Acct admin) throws Exception {
        String md = "---\nname: sk-" + UUID.randomUUID().toString().substring(0, 8) + "\ndescription: 런타임 테스트\n---\n본문";
        JsonNode d = read(call(post("/api/hub/skills"), owner, json.writeValueAsString(Map.of("skillMd", md, "submit", true))).andExpect(status().isCreated()));
        long id = d.at("/summary/id").asLong();
        call(post("/api/hub/skills/" + id + "/versions/" + d.at("/current/meta/id").asLong() + "/approve"), admin, "{}").andExpect(status().isOk());
        return id;
    }

    JsonNode execute(Acct a, long skillId, String input) throws Exception {
        return read(call(post("/api/hub/executions"), a, json.writeValueAsString(Map.of("skillId", skillId, "input", input))).andExpect(status().isAccepted()));
    }

    JsonNode waitFor(Acct a, String id, Set<String> states) throws Exception {
        JsonNode d = null;
        for (int i = 0; i < 60; i++) {
            d = read(call(get("/api/hub/executions/" + id), a, null).andExpect(status().isOk()));
            if (states.contains(d.get("status").asText())) return d;
            Thread.sleep(100);
        }
        throw new AssertionError("실행 상태 대기 초과: " + d);
    }

    @Test
    void mockRuntimeExecutionIsSimulatedAndExcludedFromKpi() throws Exception {
        Acct owner = account(), user = account(), admin = admin();
        long skill = approvedSkill(owner, admin);
        JsonNode rt = read(call(get("/api/hub/runtime"), user, null));
        assertEquals("RUNTIME_AVAILABLE", rt.get("state").asText());
        assertEquals("mock", rt.get("kind").asText());
        assertTrue(rt.get("simulated").asBoolean());

        JsonNode e = execute(user, skill, "안녕하세요");
        assertEquals("REQUESTED", e.get("status").asText());
        assertTrue(e.get("simulated").asBoolean());
        assertFalse(e.get("counted").asBoolean(), "시뮬레이션 실행은 집계 대상이 아니다");
        JsonNode done = waitFor(user, e.get("id").asText(), Set.of("SUCCEEDED", "FAILED"));
        assertEquals("SUCCEEDED", done.get("status").asText());
        assertTrue(done.get("result").asText().startsWith("[시뮬레이션]"));

        // KPI: 요청/성공 모두 0 (제외됨), 제외 건수에는 시뮬레이션이 잡힌다
        JsonNode st = read(call(get("/api/hub/analytics/skills/" + skill), user, null));
        assertEquals(0, st.at("/counts/requests").asInt());
        assertEquals(0, st.at("/counts/succeeded").asInt());
        assertTrue(st.at("/exclusions/simulated").asInt() >= 1);
        assertEquals("RUNTIME_AVAILABLE", read(call(get("/api/hub/skills/" + skill), user, null)).at("/summary/executionState").asText());

        // 실행 이력 접근 통제: 요청자/관리자만
        Acct stranger = account();
        call(get("/api/hub/executions/" + e.get("id").asText()), stranger, null).andExpect(status().isNotFound());
        call(get("/api/hub/executions/" + e.get("id").asText()), admin, null).andExpect(status().isOk());
        assertEquals(1, read(call(get("/api/hub/executions"), user, null)).get("total").asInt());
        assertEquals(0, read(call(get("/api/hub/executions"), stranger, null)).get("total").asInt());
    }

    @Test
    void failuresAreRecordedAndCancellationWorksOnlyOnce() throws Exception {
        Acct owner = account(), user = account(), admin = admin();
        long skill = approvedSkill(owner, admin);
        JsonNode failed = waitFor(user, execute(user, skill, "FAIL 해 주세요").get("id").asText(), Set.of("FAILED", "SUCCEEDED"));
        assertEquals("FAILED", failed.get("status").asText());
        assertTrue(failed.get("error").asText().contains("실패"));

        String id = execute(user, skill, "SLOW 작업").get("id").asText();
        waitFor(user, id, Set.of("RUNNING"));
        call(post("/api/hub/executions/" + id + "/cancel"), account(), null).andExpect(status().isNotFound()); // 남의 실행은 취소 불가
        assertEquals("CANCELLED", read(call(post("/api/hub/executions/" + id + "/cancel"), user, null).andExpect(status().isOk())).get("status").asText());
        call(post("/api/hub/executions/" + id + "/cancel"), user, null).andExpect(status().isConflict());
    }

    @Test
    void duplicateRateLimitAndTargetValidation() throws Exception {
        Acct owner = account(), user = account(), admin = admin();
        long skill = approvedSkill(owner, admin);
        execute(user, skill, "같은 입력");
        call(post("/api/hub/executions"), user, json.writeValueAsString(Map.of("skillId", skill, "input", "같은 입력"))).andExpect(status().isConflict()); // 10초 이내 중복
        call(post("/api/hub/executions"), user, json.writeValueAsString(Map.of("input", "대상 없음"))).andExpect(status().isBadRequest());
        call(post("/api/hub/executions"), user, json.writeValueAsString(Map.of("skillId", skill, "agentId", 1, "input", "둘 다"))).andExpect(status().isBadRequest());
        call(post("/api/hub/executions"), user, json.writeValueAsString(Map.of("skillId", skill, "input", "  "))).andExpect(status().isBadRequest());
        call(post("/api/hub/executions"), user, json.writeValueAsString(Map.of("skillId", skill, "input", "가".repeat(4001)))).andExpect(status().isBadRequest());
        for (int i = 0; i < 9; i++) execute(user, skill, "입력 " + i);
        call(post("/api/hub/executions"), user, json.writeValueAsString(Map.of("skillId", skill, "input", "초과"))).andExpect(status().isTooManyRequests());

        // 승인되지 않았거나 보이지 않는 Skill 은 실행 불가
        String md = "---\nname: sk-" + UUID.randomUUID().toString().substring(0, 8) + "\ndescription: 미승인\n---\n본문";
        JsonNode pending = read(call(post("/api/hub/skills"), owner, json.writeValueAsString(Map.of("skillMd", md, "submit", true))));
        Acct fresh = account();
        call(post("/api/hub/executions"), fresh, json.writeValueAsString(Map.of("skillId", pending.at("/summary/id").asLong(), "input", "실행"))).andExpect(status().isNotFound());
    }

    @Test
    void agentExecutionRequiresApprovalAndUsesOnlyVisibleApprovedSkills() throws Exception {
        Acct owner = account(), user = account(), admin = admin();
        long skill = approvedSkill(owner, admin);
        JsonNode agent = read(call(post("/api/hub/agents"), owner, json.writeValueAsString(Map.of("name", "런타임 Agent " + UUID.randomUUID().toString().substring(0, 6),
                "description", "테스트", "skillIds", java.util.List.of(skill), "submit", true))).andExpect(status().isCreated()));
        long aid = agent.at("/summary/id").asLong();
        call(post("/api/hub/executions"), owner, json.writeValueAsString(Map.of("agentId", aid, "input", "승인 전"))).andExpect(status().isConflict());
        call(post("/api/hub/agents/" + aid + "/approve"), admin, "{}").andExpect(status().isOk());
        JsonNode e = read(call(post("/api/hub/executions"), user, json.writeValueAsString(Map.of("agentId", aid, "input", "Agent 실행"))).andExpect(status().isAccepted()));
        assertEquals("AGENT", e.get("targetType").asText());
        assertEquals("SUCCEEDED", waitFor(user, e.get("id").asText(), Set.of("SUCCEEDED", "FAILED")).get("status").asText());
    }
}
