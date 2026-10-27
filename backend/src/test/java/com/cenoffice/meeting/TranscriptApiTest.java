package com.cenoffice.meeting;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:transcripttest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "app.seed.admin-password=test-admin-pw-1234", "app.seed.user-password=test-user-pw-1234",
    "app.jwt.secret=test-secret-test-secret-test-secret-123456", "app.llm.provider=mock", "livekit.api-key=", "livekit.api-secret="})
@AutoConfigureMockMvc
class TranscriptApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    String host, guest;

    @BeforeEach void setup() throws Exception {
        host = login("admin", "test-admin-pw-1234");
        guest = login("user", "test-user-pw-1234");
    }

    String login(String u, String p) throws Exception {
        return read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username", u, "password", p))))).get("token").asText();
    }

    ResultActions req(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(b);
    }

    JsonNode read(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }

    JsonNode openMeeting() throws Exception {
        var m = read(req(post("/api/meetings"), host, Map.of("title", "음성 회의 " + UUID.randomUUID())).andExpect(status().isCreated()));
        req(post("/api/meetings/join"), guest, Map.of("code", m.path("code").asText())).andExpect(status().isOk());
        return m;
    }

    @Test void speechIsRecordedOnlyAfterConsentAndOnlyMembersCanRead() throws Exception {
        var m = openMeeting();
        String id = m.path("id").asText(), base = "/api/meetings/" + id + "/transcript";
        // 동의 전에는 기록할 수 없다
        req(post(base), guest, Map.of("content", "안녕하세요", "engine", "browser")).andExpect(status().isForbidden());
        req(post(base + "/consent"), guest, Map.of("engine", "browser")).andExpect(status().isNoContent());
        req(post(base), guest, Map.of("content", "안녕하세요 오늘 안건은 로그인 개선입니다", "engine", "browser")).andExpect(status().isCreated());
        // 동의하지 않은 진행자는 기록 불가, 하지만 전문은 참석자라 읽을 수 있다
        req(post(base), host, Map.of("content", "네 좋습니다")).andExpect(status().isForbidden());
        var list = read(req(get(base), host, null).andExpect(status().isOk()));
        assertEquals(1, list.size());
        assertEquals("user", list.get(0).path("username").asText());
        assertEquals(0, read(req(get(base + "?after=" + list.get(0).path("id").asLong()), host, null)).size());
        assertTrue(read(req(get(base + "/status"), guest, null)).path("consented").asBoolean());
        // 동의를 철회하면 이후 발언은 기록되지 않는다
        req(delete(base + "/consent"), guest, null).andExpect(status().isNoContent());
        req(post(base), guest, Map.of("content", "철회 후 발언")).andExpect(status().isForbidden());
    }

    @Test void outsidersCannotReadOrWriteAndEndedMeetingsRejectNewSpeech() throws Exception {
        var m = read(req(post("/api/meetings"), host, Map.of("title", "비공개 회의 " + UUID.randomUUID())).andExpect(status().isCreated()));
        String id = m.path("id").asText(), base = "/api/meetings/" + id + "/transcript";
        req(get(base), guest, null).andExpect(status().isNotFound());
        req(post(base + "/consent"), guest, Map.of()).andExpect(status().isNotFound());
        req(post(base + "/consent"), host, Map.of()).andExpect(status().isNoContent());
        req(post(base), host, Map.of("content", "시작합니다")).andExpect(status().isCreated());
        req(post("/api/meetings/" + id + "/end"), host, Map.of()).andExpect(status().isNoContent());
        req(post(base), host, Map.of("content", "종료 후")).andExpect(status().isConflict());
        assertEquals(1, read(req(get(base), host, null)).size()); // 종료 후에도 참석자는 전문을 볼 수 있다
    }

    @Test void minutesUseTheTranscriptAsEvidence() throws Exception {
        var m = openMeeting();
        String id = m.path("id").asText(), base = "/api/meetings/" + id + "/transcript";
        req(post(base + "/consent"), host, Map.of()).andExpect(status().isNoContent());
        req(post(base), host, Map.of("content", "배포 일정은 이번 주 금요일로 확정하기로 했습니다")).andExpect(status().isCreated());
        req(post("/api/meetings/" + id + "/end"), host, Map.of()).andExpect(status().isNoContent());
        // 채팅이 없어도 음성 전문만으로 초안을 만들 수 있다
        var draft = read(req(post("/api/meetings/" + id + "/minutes/generate"), host, Map.of("notes", "")).andExpect(status().isOk()));
        assertTrue(draft.path("decisions").toString().contains("확정"));
    }
}
