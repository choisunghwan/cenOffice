package com.cenoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

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
class WorkspaceApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    String user;
    String admin;

    @BeforeEach
    void tokens() throws Exception {
        user = login("user", "test-user-pw-1234");
        admin = login("admin", "test-admin-pw-1234");
    }

    String login(String u, String p) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, String token, String body) throws Exception {
        b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(b);
    }

    JsonNode read(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString()); }

    long agentId(String code) throws Exception {
        for (JsonNode a : read(send(get("/api/agents"), user, null))) if (a.get("code").asText().equals(code)) return a.get("id").asLong();
        throw new IllegalStateException(code);
    }

    long newProject(String token) throws Exception {
        return read(send(post("/api/projects"), token, "{\"name\":\"테스트 프로젝트\",\"description\":\"설명\"}").andExpect(status().isCreated())).get("id").asLong();
    }

    @Test
    void projectRequirementTaskLifecycleIsRecordedInHistory() throws Exception {
        long pid = newProject(user);
        long rid = read(send(post("/api/projects/" + pid + "/requirements"), user, "{\"title\":\"로그인 기능\",\"priority\":\"HIGH\"}").andExpect(status().isCreated())).get("id").asLong();
        long ari = agentId("ari");
        long tid = read(send(post("/api/projects/" + pid + "/tasks"), user,
                "{\"title\":\"로그인 API\",\"requirementId\":" + rid + ",\"assigneeAgentId\":" + ari + "}").andExpect(status().isCreated())).get("id").asLong();
        JsonNode updated = read(send(put("/api/tasks/" + tid), user,
                "{\"title\":\"로그인 API\",\"requirementId\":" + rid + ",\"status\":\"IN_PROGRESS\",\"assigneeAgentId\":" + ari + "}").andExpect(status().isOk()));
        assertEquals("IN_PROGRESS", updated.get("status").asText());

        JsonNode history = read(send(get("/api/history?target=task:" + tid), user, null).andExpect(status().isOk()));
        assertTrue(history.size() >= 2, "생성/수정 이력이 모두 남아야 한다");
        send(get("/api/history?target=user:1"), user, null).andExpect(status().isBadRequest());

        JsonNode p = read(send(get("/api/projects/" + pid), user, null));
        assertEquals(1, p.get("requirementCount").asInt());
        assertEquals(1, p.get("taskCount").asInt());
    }

    @Test
    void taskWithRequirementFromAnotherProjectIsRejected() throws Exception {
        long p1 = newProject(user), p2 = newProject(user);
        long r1 = read(send(post("/api/projects/" + p1 + "/requirements"), user, "{\"title\":\"A\"}")).get("id").asLong();
        send(post("/api/projects/" + p2 + "/tasks"), user, "{\"title\":\"X\",\"requirementId\":" + r1 + "}").andExpect(status().isBadRequest());
    }

    @Test
    void onlyOwnerOrAdminCanDeleteProject() throws Exception {
        long pid = newProject(admin);
        send(delete("/api/projects/" + pid), user, null).andExpect(status().isForbidden());
        send(delete("/api/projects/" + pid), admin, null).andExpect(status().isNoContent());
    }

    @Test
    void environmentPolicyIsEnforcedOnServer() throws Exception {
        long pid = newProject(user);
        long ari = agentId("ari"), dev = agentId("dev-bot");
        String base = "{\"projectId\":" + pid + ",\"agentId\":%d,\"type\":\"%s\",\"environment\":\"%s\"}";
        // PROD: 어떤 에이전트도 접근 불가 (읽기 작업이라도)
        send(post("/api/ai-tasks"), user, base.formatted(ari, "STATUS_QUERY", "PROD")).andExpect(status().isForbidden());
        // dev-bot 은 DEV 만 허용
        send(post("/api/ai-tasks"), user, base.formatted(dev, "RUN_TESTS", "STG")).andExpect(status().isForbidden());
        // STG 는 개발 작업 불가, 테스트는 가능
        send(post("/api/ai-tasks"), user, base.formatted(ari, "CODE_CHANGE", "STG")).andExpect(status().isForbidden());
        send(post("/api/ai-tasks"), user, base.formatted(ari, "RUN_TESTS", "STG")).andExpect(status().isCreated());
        // DEV 에서는 개발 작업 가능
        send(post("/api/ai-tasks"), user, base.formatted(dev, "CODE_CHANGE", "DEV")).andExpect(status().isCreated());
        // 배포 계열 타입은 존재하지 않는다
        send(post("/api/ai-tasks"), user, base.formatted(ari, "DEPLOY", "DEV")).andExpect(status().isBadRequest());
    }

    @Test
    void approvalRequiresADifferentApproverAndCannotBeDecidedTwice() throws Exception {
        long pid = newProject(user);
        long ari = agentId("ari");
        long aiTask = read(send(post("/api/ai-tasks"), user,
                "{\"projectId\":" + pid + ",\"agentId\":" + ari + ",\"type\":\"DRAFT_SPEC\",\"environment\":\"DEV\"}").andExpect(status().isCreated())).get("id").asLong();
        long approval = read(send(post("/api/ai-tasks/" + aiTask + "/approval-requests"), user,
                "{\"kind\":\"MANUAL_REVIEW\",\"summary\":\"작업 3건 등록\",\"payload\":\"{}\"}").andExpect(status().isCreated())).get("id").asLong();

        send(post("/api/approvals/" + approval + "/decision"), user, "{\"approve\":true}").andExpect(status().isForbidden());
        JsonNode done = read(send(post("/api/approvals/" + approval + "/decision"), admin, "{\"approve\":true,\"comment\":\"ok\"}").andExpect(status().isOk()));
        assertEquals("APPROVED", done.get("status").asText());
        send(post("/api/approvals/" + approval + "/decision"), admin, "{\"approve\":false}").andExpect(status().isConflict());
    }

    @Test
    void anonymousAndNonAdminAccessAreRejectedAndAudited() throws Exception {
        mvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());
        send(get("/api/audit-logs"), user, null).andExpect(status().isForbidden());
        JsonNode logs = read(send(get("/api/audit-logs?limit=50"), admin, null).andExpect(status().isOk()));
        boolean found = false;
        for (JsonNode l : logs) if (l.get("action").asText().equals("ACCESS_DENIED")) found = true;
        assertTrue(found, "권한 거부가 감사 로그에 남아야 한다");
    }

    @Test
    void duplicateDeviceCommandWhileOneIsPendingIsRejected() throws Exception {
        long ac = 0;
        for (JsonNode d : read(send(get("/api/devices"), user, null))) if (d.get("code").asText().equals("ac-main")) ac = d.get("id").asLong();
        send(post("/api/devices/" + ac + "/commands"), user, "{\"power\":true}").andExpect(status().isAccepted());
        send(post("/api/devices/" + ac + "/commands"), user, "{\"power\":false}").andExpect(status().isConflict());
        Thread.sleep(700); // 장비 확인을 기다려 다른 테스트에 영향이 없도록 정리
    }

    @Test
    void loginIsRateLimitedAfterRepeatedFailures() throws Exception {
        String body = "{\"username\":\"ghost-user\",\"password\":\"bad\"}";
        for (int i = 0; i < 5; i++)
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isTooManyRequests());
        // 다른 계정은 영향을 받지 않는다
        login("user", "test-user-pw-1234");
    }
}
