package com.cenoffice.pm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cenoffice.pm.LlmProvider.LlmException;
import com.cenoffice.pm.PmTypes.PlanRequirement;
import com.cenoffice.pm.PmTypes.PlanTask;
import com.cenoffice.pm.PmTypes.PmResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** LLM 이 규칙을 어기는 출력을 내도 서버가 저장/승인 요청 단계에서 막는지 확인한다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pmvalidation;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "app.seed.admin-password=test-admin-pw-1234",
        "app.seed.user-password=test-user-pw-1234",
        "app.jwt.secret=test-secret-test-secret-test-secret-123456"})
@AutoConfigureMockMvc
class PmLlmOutputValidationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PlanValidator validator;
    @MockBean LlmProvider llm;

    String token() throws Exception {
        String b = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"user\",\"password\":\"test-user-pw-1234\"}")).andReturn().getResponse().getContentAsString();
        return json.readTree(b).get("token").asText();
    }

    JsonNode run(String token, PmResult result) throws Exception {
        when(llm.name()).thenReturn("stub");
        when(llm.planRequirements(any())).thenReturn(result);
        return runNow(token);
    }

    JsonNode runNow(String token) throws Exception {
        long pid = json.readTree(mvc.perform(post("/api/projects").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"검증\"}")).andReturn().getResponse().getContentAsString()).get("id").asLong();
        long task = json.readTree(mvc.perform(post("/api/pm/intake").header("Authorization", "Bearer " + token)
                .contentType(new MediaType("application", "json", StandardCharsets.UTF_8))
                .content("{\"projectId\":" + pid + ",\"text\":\"충분히 긴 요구사항 설명입니다. 검증을 위해 작성했습니다.\"}")).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        JsonNode d = null;
        for (int i = 0; i < 50; i++) {
            d = json.readTree(mvc.perform(get("/api/pm/tasks/" + task).header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
            String st = d.get("task").get("status").asText();
            if (!st.equals("QUEUED") && !st.equals("RUNNING")) return d;
            Thread.sleep(100);
        }
        throw new AssertionError("완료 대기 시간 초과");
    }

    static PmResult plan(int tasks, String priority, Integer idx) {
        List<PlanTask> t = new ArrayList<>();
        for (int i = 0; i < tasks; i++) t.add(new PlanTask("작업 " + i, "설명", idx));
        return new PmResult(List.of(), "# 명세\n내용", List.of(new PlanRequirement("요구사항", "설명", priority)), t);
    }

    @Test
    void oversizedPlanIsRejectedAndNeverReachesApproval() throws Exception {
        JsonNode d = run(token(), plan(100, "HIGH", 0));
        assertEquals("FAILED", d.get("task").get("status").asText());
        assertEquals(0, d.get("state").get("approvalId") == null ? 0 : 1, "승인 요청이 만들어지면 안 된다");
        assertTrue(d.get("state").get("error").asText().contains("작업 개수"));
    }

    @Test
    void unknownPriorityOrBadIndexIsRejected() throws Exception {
        String tk = token();
        assertEquals("FAILED", run(tk, plan(3, "URGENT", 0)).get("task").get("status").asText());
        assertEquals("FAILED", run(tk, plan(3, "HIGH", 7)).get("task").get("status").asText());
    }

    @Test
    void llmFailureMarksTaskFailedWithoutSideEffects() throws Exception {
        when(llm.name()).thenReturn("stub");
        when(llm.planRequirements(any())).thenThrow(new LlmException("LLM 호출 실패 (HTTP 500)"));
        JsonNode d = runNow(token());
        assertEquals("FAILED", d.get("task").get("status").asText());
        assertTrue(d.get("state").get("error").asText().contains("LLM"));
    }

    @Test
    void validatorStripsControlCharsAndRequiresSpec() throws Exception {
        PmResult ok = validator.validate(new PmResult(List.of(), "명세\u0000본문", List.of(new PlanRequirement("제\u0007목", "d", "low")), List.of(new PlanTask("t", "", null))));
        assertEquals("명세본문", ok.spec());
        assertEquals("제목", ok.requirements().get(0).title());
        assertEquals("LOW", ok.requirements().get(0).priority());
        assertThrows(PlanValidator.InvalidPlanException.class, () -> validator.validate(new PmResult(List.of(), "", List.of(new PlanRequirement("a", "", "LOW")), List.of(new PlanTask("t", "", null)))));
    }

    @Test
    void anthropicResponseParsingIsDefensive() throws Exception {
        String raw = "여기 결과입니다:\n```json\n{\"questions\":[],\"spec\":\"# s\",\"requirements\":[{\"title\":\"a\",\"description\":\"b\",\"priority\":\"HIGH\"}],"
                + "\"tasks\":[{\"title\":\"t\",\"description\":\"d\",\"requirementIndex\":0}]}\n```";
        PmResult r = AnthropicLlmProvider.parsePmResult(json, raw);
        assertEquals(1, r.tasks().size());
        assertEquals(0, r.tasks().get(0).requirementIndex());
        assertThrows(LlmException.class, () -> AnthropicLlmProvider.parsePmResult(json, "JSON 이 없는 응답"));
    }
}
