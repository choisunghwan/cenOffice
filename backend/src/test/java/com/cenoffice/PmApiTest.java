package com.cenoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cenoffice.domain.Enums.Role;
import com.cenoffice.domain.UserAccount;
import com.cenoffice.repo.UserRepository;
import com.cenoffice.workspace.AiTaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
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
class PmApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired AiTaskRepository aiTasks;

    record Acct(String name, String token) {}

    Acct account() throws Exception {
        String name = "p" + UUID.randomUUID().toString().substring(0, 8);
        users.save(new UserAccount(name, encoder.encode("pw-" + name), "PM테스터-" + name, Role.USER));
        return new Acct(name, loginToken(name, "pw-" + name));
    }

    String loginToken(String u, String p) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    Acct admin() throws Exception { return new Acct("admin", loginToken("admin", "test-admin-pw-1234")); }

    ResultActions call(MockHttpServletRequestBuilder b, Acct a, String body) throws Exception {
        b.header("Authorization", "Bearer " + a.token());
        if (body != null) b.contentType(new MediaType("application", "json", StandardCharsets.UTF_8)).content(body);
        return mvc.perform(b);
    }

    JsonNode read(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }

    long project(Acct a) throws Exception {
        return read(call(post("/api/projects"), a, "{\"name\":\"PM 테스트\"}").andExpect(status().isCreated())).get("id").asLong();
    }

    long intake(Acct a, long pid, String text) throws Exception {
        return read(call(post("/api/pm/intake"), a, json.writeValueAsString(java.util.Map.of("projectId", pid, "text", text))).andExpect(status().isAccepted())).get("id").asLong();
    }

    JsonNode waitFor(Acct a, long taskId, Set<String> statuses) throws Exception {
        JsonNode d = null;
        for (int i = 0; i < 50; i++) {
            d = read(call(get("/api/pm/tasks/" + taskId), a, null).andExpect(status().isOk()));
            if (statuses.contains(d.get("task").get("status").asText())) return d;
            Thread.sleep(100);
        }
        throw new AssertionError("상태 대기 시간 초과: " + d.get("task").get("status"));
    }

    int count(Acct a, long pid, String what) throws Exception {
        return read(call(get("/api/projects/" + pid + "/" + what), a, null)).size();
    }

    static final String LONG_TEXT = "고객은 모바일에서 주문 내역을 조회할 수 있어야 한다. 주문 상태가 바뀌면 푸시 알림을 받아야 한다. 관리자는 주문을 취소할 수 있어야 한다.";

    @Test
    void fullFlowAskQuestionsThenPlanThenApproveRegistersOnlyAfterApproval() throws Exception {
        Acct owner = account(), other = account(), admin = admin();
        long pid = project(owner);
        long task = intake(owner, pid, "로그인 기능이 필요합니다");

        JsonNode d1 = waitFor(owner, task, Set.of("NEEDS_INPUT"));
        assertEquals(3, d1.get("state").get("questions").size(), "부족한 요구사항에 대한 질문을 생성해야 한다");
        assertEquals(0, count(owner, pid, "requirements"));

        call(post("/api/pm/tasks/" + task + "/answers"), other, "{\"answers\":\"끼어들기\"}").andExpect(status().isForbidden());
        call(post("/api/pm/tasks/" + task + "/answers"), owner, "{\"answers\":\"대상: 일반 고객. 완료 기준: 이메일로 로그인되고 실패 시 안내 문구가 나온다. 일정: 다음 달\"}").andExpect(status().isAccepted());

        JsonNode d2 = waitFor(owner, task, Set.of("WAITING_APPROVAL"));
        long approval = d2.get("state").get("approvalId").asLong();
        assertTrue(d2.get("state").get("requirements").size() >= 1);
        assertTrue(d2.get("state").get("spec").asText().length() > 20);
        // 승인 전에는 프로젝트에 어떤 변경도 없다
        assertEquals(0, count(owner, pid, "requirements"));
        assertEquals(0, count(owner, pid, "tasks"));

        JsonNode done = read(call(post("/api/approvals/" + approval + "/decision"), admin, "{\"approve\":true,\"comment\":\"확인\"}").andExpect(status().isOk()));
        assertEquals("APPROVED", done.get("status").asText());
        assertTrue(count(owner, pid, "requirements") >= 1);
        assertTrue(count(owner, pid, "tasks") >= 3);
        assertEquals("DONE", read(call(get("/api/pm/tasks/" + task), owner, null)).get("task").get("status").asText());
        call(post("/api/approvals/" + approval + "/decision"), admin, "{\"approve\":false}").andExpect(status().isConflict());

        // 모든 단계가 DB 에 기록된다
        JsonNode steps = read(call(get("/api/ai-tasks/" + task + "/executions"), owner, null));
        StringBuilder names = new StringBuilder();
        for (JsonNode s : steps) names.append(s.get("step").asText()).append(',');
        for (String expected : new String[]{"received", "needs-input", "answered", "spec-drafted", "tasks-generated", "approval-requested", "applied"})
            assertTrue(names.toString().contains(expected), expected + " 단계가 기록되어야 한다: " + names);
        JsonNode logs = read(call(get("/api/audit-logs?limit=200"), admin, null));
        boolean applied = false;
        for (JsonNode l : logs) if (l.get("action").asText().equals("PLAN_APPLIED") && l.get("target").asText().equals("aitask:" + task)) applied = true;
        assertTrue(applied);
    }

    @Test
    void rejectingThePlanChangesNothing() throws Exception {
        Acct owner = account(), admin = admin();
        long pid = project(owner);
        long task = intake(owner, pid, LONG_TEXT);
        JsonNode d = waitFor(owner, task, Set.of("WAITING_APPROVAL"));
        long approval = d.get("state").get("approvalId").asLong();
        call(post("/api/approvals/" + approval + "/decision"), admin, "{\"approve\":false,\"comment\":\"범위가 다름\"}").andExpect(status().isOk());
        assertEquals("REJECTED", read(call(get("/api/pm/tasks/" + task), owner, null)).get("task").get("status").asText());
        assertEquals(0, count(owner, pid, "requirements"));
        assertEquals(0, count(owner, pid, "tasks"));
    }

    @Test
    void tamperedPlanIsNotAppliedAndApprovalStaysPending() throws Exception {
        Acct owner = account(), admin = admin();
        long pid = project(owner);
        long task = intake(owner, pid, LONG_TEXT);
        long approval = waitFor(owner, task, Set.of("WAITING_APPROVAL")).get("state").get("approvalId").asLong();

        // 검토 이후 계획이 바뀌면(DB 변조/버그) 승인해도 적용되지 않는다
        var t = aiTasks.findById(task).orElseThrow();
        t.setPmState(t.getPmState().replace("주문", "삭제"));
        aiTasks.save(t);

        call(post("/api/approvals/" + approval + "/decision"), admin, "{\"approve\":true}").andExpect(status().isConflict());
        assertEquals(0, count(owner, pid, "requirements"));
        JsonNode pending = read(call(get("/api/approvals?status=PENDING"), admin, null));
        boolean stillPending = false;
        for (JsonNode a : pending) if (a.get("id").asLong() == approval) stillPending = true;
        assertTrue(stillPending, "적용 실패 시 승인도 롤백되어 대기 상태로 남아야 한다");
    }

    @Test
    void manualApprovalCannotCarryApplicablePayload() throws Exception {
        Acct owner = account();
        long pid = project(owner);
        long task = intake(owner, pid, LONG_TEXT);
        waitFor(owner, task, Set.of("WAITING_APPROVAL"));
        call(post("/api/ai-tasks/" + task + "/approval-requests"), owner,
                "{\"kind\":\"REGISTER_PLAN\",\"summary\":\"위조\",\"payload\":\"{\\\"planSha256\\\":\\\"x\\\"}\"}").andExpect(status().isBadRequest());
    }

    @Test
    void pmWorkIsDevOnlyAndAnonymousIsRejected() throws Exception {
        Acct owner = account();
        long pid = project(owner);
        long ari = 0;
        for (JsonNode a : read(call(get("/api/agents"), owner, null))) if (a.get("code").asText().equals("ari")) ari = a.get("id").asLong();
        for (String env : new String[]{"STG", "PROD"})
            call(post("/api/ai-tasks"), owner, "{\"projectId\":" + pid + ",\"agentId\":" + ari + ",\"type\":\"DRAFT_SPEC\",\"environment\":\"" + env + "\"}").andExpect(status().isForbidden());
        mvc.perform(post("/api/pm/intake").contentType(MediaType.APPLICATION_JSON).content("{\"projectId\":1,\"text\":\"익명 요청입니다 익명 요청\"}")).andExpect(status().isUnauthorized());
        call(post("/api/pm/intake"), owner, json.writeValueAsString(java.util.Map.of("projectId", pid, "text", "짧음"))).andExpect(status().isBadRequest());
        // 배포 실행 엔드포인트는 존재하지 않는다
        call(post("/api/deploy"), owner, "{}").andExpect(status().isNotFound());
        assertFalse(read(call(get("/api/pm/info"), owner, null)).get("real").asBoolean(), "테스트 환경은 Mock LLM 이어야 한다");
    }
}
