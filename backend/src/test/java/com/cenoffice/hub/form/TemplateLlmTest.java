package com.cenoffice.hub.form;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cenoffice.hub.form.DocumentExporter.Block;
import com.cenoffice.pm.LlmProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** AI 가 연결된 환경에서 양식 채우기. AI 의 답은 믿지 않고 서버가 다시 거른다. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:templatellmtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "app.seed.admin-password=test-admin-pw-1234", "app.seed.user-password=test-user-pw-1234",
    "app.jwt.secret=test-secret-test-secret-test-secret-123456", "app.llm.provider=mock", "livekit.api-key=", "livekit.api-secret="})
@AutoConfigureMockMvc
class TemplateLlmTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean LlmProvider llm;
    String admin;
    long skillId;

    @BeforeEach void setup() throws Exception {
        when(llm.isReal()).thenReturn(true);
        when(llm.name()).thenReturn("fake");
        when(llm.model()).thenReturn("m1");
        admin = read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username", "admin", "password", "test-admin-pw-1234"))).with(r -> { r.setRemoteAddr("10.8.1.1"); return r; }))).path("token").asText();
        for (var s : read(req(get("/api/hub/skills?status=APPROVED&size=100"), null).andExpect(status().isOk())).path("items")) if (s.path("name").asText().equals("cen-sql-reviewer")) skillId = s.path("id").asLong();
        var rows = List.of(List.of("항목", "내용"), List.of("성명", ""), List.of("소속", ""));
        byte[] form = HwpxWriter.write("양식", List.of(new Block("table", 0, null, rows)));
        mvc.perform(multipart(HttpMethod.PUT, url()).file(new MockMultipartFile("file", "a.hwpx", "application/octet-stream", form)).header("Authorization", "Bearer " + admin)).andExpect(status().isOk());
        reset(llm);
        when(llm.isReal()).thenReturn(true);
        when(llm.name()).thenReturn("fake");
        when(llm.model()).thenReturn("m1");
    }

    String url() { return "/api/hub/skills/" + skillId + "/template"; }

    ResultActions req(MockHttpServletRequestBuilder b, Object body) throws Exception {
        b.header("Authorization", "Bearer " + admin);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(b);
    }

    JsonNode read(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }

    JsonNode item(JsonNode plan, String key) {
        for (var i : plan.path("items")) if (i.path("key").asText().equals(key)) return i;
        fail("항목이 없습니다: " + key);
        return null;
    }

    @Test void planUsesAiForRemainingFieldsAndIgnoresUnknownKeys() throws Exception {
        when(llm.generateJson(anyString(), anyString())).thenReturn("{\"values\":{\"소속\":\"플랫폼팀\",\"없는항목\":\"x\",\"성명\":null}}");
        var plan = read(req(post(url() + "/plan"), Map.of("result", "홍길동 님이 플랫폼팀에서 일합니다.")).andExpect(status().isOk()));
        assertTrue(plan.path("aiUsed").asBoolean());
        assertEquals("플랫폼팀", item(plan, "소속").path("value").asText());
        assertEquals("AI", item(plan, "소속").path("source").asText());
        assertEquals("MISSING", item(plan, "성명").path("source").asText()); // 근거를 못 찾은 항목은 채우지 않는다
        assertEquals(2, plan.path("items").size()); // 없는 항목은 만들어지지 않는다
    }

    @Test void planSurvivesBrokenAiAnswers() throws Exception {
        when(llm.generateJson(anyString(), anyString())).thenReturn("이건 JSON 이 아닙니다");
        var plan = read(req(post(url() + "/plan"), Map.of("result", "내용")).andExpect(status().isOk()));
        for (var i : plan.path("items")) assertEquals("MISSING", i.path("source").asText());
        when(llm.generateJson(anyString(), anyString())).thenThrow(new LlmProvider.LlmException("timeout"));
        req(post(url() + "/plan"), Map.of("result", "내용")).andExpect(status().isOk());
    }

    @Test void longResultsAreTruncatedBeforeGoingToTheAi() throws Exception {
        when(llm.generateJson(anyString(), anyString())).thenReturn("{\"values\":{}}");
        req(post(url() + "/plan"), Map.of("result", "가".repeat(20000))).andExpect(status().isOk());
        var sent = ArgumentCaptor.forClass(String.class);
        verify(llm).generateJson(anyString(), sent.capture());
        assertTrue(sent.getValue().length() < 13000, "AI 로 보내는 글자 수: " + sent.getValue().length());
    }

    @Test void draftReturnsTheAiText() throws Exception {
        when(llm.generateJson(anyString(), anyString())).thenReturn("{\"value\":\"초안 내용\"}");
        var res = read(req(post(url() + "/draft"), Map.of("result", "회의 내용", "key", "성명")).andExpect(status().isOk()));
        assertEquals("초안 내용", res.path("value").asText());
        req(post(url() + "/draft"), Map.of("result", "회의 내용", "key", "없는항목")).andExpect(status().isBadRequest());
    }
}
