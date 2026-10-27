package com.cenoffice.meeting;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:minutestest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "app.seed.admin-password=test-admin-pw-1234", "app.seed.user-password=test-user-pw-1234",
    "app.jwt.secret=test-secret-test-secret-test-secret-123456", "app.llm.provider=mock", "livekit.api-key=", "livekit.api-secret="})
@AutoConfigureMockMvc
class MinutesApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    String host, guest;

    @BeforeEach void setup() throws Exception {
        // 확정 테스트들이 남긴 알림·할 일이 다른 테스트의 "없음"/"첫 항목" 확인에 섞이지 않게 한다
        db.update("delete from notifications");
        db.update("delete from meeting_action_items");
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

    /** 진행자(admin)가 회의를 만들고 참석자(user)가 들어와 대화한 뒤 종료한 회의 id */
    String endedMeetingWithChat() throws Exception {
        var m = read(req(post("/api/meetings"), host, Map.of("title", "주간 회의 " + UUID.randomUUID())).andExpect(status().isCreated()));
        String id = m.path("id").asText();
        req(post("/api/meetings/join"), guest, Map.of("code", m.path("code").asText())).andExpect(status().isOk());
        req(post("/api/meetings/" + id + "/messages"), host, Map.of("content", "로그인 화면 개선안은 이번 안으로 확정하기로 했습니다")).andExpect(status().isNoContent());
        req(post("/api/meetings/" + id + "/messages"), guest, Map.of("content", "제가 다음주 월요일까지 견적서 작성하겠습니다")).andExpect(status().isNoContent());
        req(post("/api/meetings/" + id + "/messages"), host, Map.of("content", "배포 일정은 아직 미정이라 확인 필요합니다")).andExpect(status().isNoContent());
        req(post("/api/meetings/" + id + "/end"), host, Map.of()).andExpect(status().isNoContent());
        return id;
    }

    @Test void onlyHostCanGenerateAndOnlyAfterTheMeetingEnded() throws Exception {
        var m = read(req(post("/api/meetings"), host, Map.of("title", "진행 중 회의")).andExpect(status().isCreated()));
        String id = m.path("id").asText();
        req(post("/api/meetings/" + id + "/minutes/generate"), host, Map.of("notes", "메모")).andExpect(status().isConflict());
        req(post("/api/meetings/join"), guest, Map.of("code", m.path("code").asText())).andExpect(status().isOk());
        req(post("/api/meetings/" + id + "/end"), host, Map.of()).andExpect(status().isNoContent());
        req(post("/api/meetings/" + id + "/minutes/generate"), guest, Map.of("notes", "메모")).andExpect(status().isForbidden());
        req(get("/api/meetings/" + id + "/minutes"), host, null).andExpect(status().isNotFound()); // 아직 없음
    }

    boolean pendingHas(String token, String id) throws Exception {
        for (var p : read(req(get("/api/minutes/pending"), token, null).andExpect(status().isOk()))) if (p.path("meetingId").asText().equals(id)) return true;
        return false;
    }

    @Test void pendingListShowsOnlyTheHostsUnconfirmedEndedMeetings() throws Exception {
        String id = endedMeetingWithChat();
        assertTrue(pendingHas(host, id));
        assertFalse(pendingHas(guest, id)); // 참석자는 확정 권한이 없으므로 대기 목록에 나오지 않는다
        req(post("/api/meetings/" + id + "/minutes/generate"), host, Map.of("notes", "메모")).andExpect(status().isOk());
        assertTrue(pendingHas(host, id)); // 초안만 있어도 아직 대기
        req(post("/api/meetings/" + id + "/minutes/confirm"), host, Map.of()).andExpect(status().isOk());
        assertFalse(pendingHas(host, id));
        var open = read(req(post("/api/meetings"), host, Map.of("title", "진행 중 " + UUID.randomUUID())).andExpect(status().isCreated()));
        assertFalse(pendingHas(host, open.path("id").asText())); // 진행 중인 회의는 제외
    }

    @Test void draftReviewConfirmCreatesActionItemsAndNotifiesOnlyAfterConfirm() throws Exception {
        String id = endedMeetingWithChat();
        var draft = read(req(post("/api/meetings/" + id + "/minutes/generate"), host, Map.of("notes", "안건: 로그인 화면 개선")).andExpect(status().isOk()));
        assertEquals("DRAFT", draft.path("status").asText());
        assertEquals("rule-based", draft.path("source").asText());
        assertTrue(draft.path("decisions").toString().contains("확정"));
        assertTrue(draft.path("markdown").asText().contains("회의록"));
        // 확정 전에는 알림이 없다
        assertEquals(0, read(req(get("/api/notifications"), guest, null)).path("unread").asInt());

        // 진행자가 담당자를 참석자로 지정해 수정
        var items = draft.path("actionItems");
        assertTrue(items.size() >= 1);
        var body = Map.of("summary", draft.path("summary").asText(), "decisions", List.of("로그인 화면 개선안 확정"), "issues", List.of("배포 일정 미정"),
            "actionItems", List.of(Map.of("content", "견적서 작성", "assigneeUsername", "user", "dueText", "다음주 월요일")));
        var edited = read(req(put("/api/meetings/" + id + "/minutes"), host, body).andExpect(status().isOk()));
        assertEquals("user", edited.path("actionItems").get(0).path("assigneeUsername").asText());
        // 참석자 아닌 사람은 담당자가 될 수 없다
        req(put("/api/meetings/" + id + "/minutes"), host, Map.of("summary", "요약", "decisions", List.of(), "issues", List.of(),
            "actionItems", List.of(Map.of("content", "x", "assigneeUsername", "nobody")))).andExpect(status().isBadRequest());
        // 참석자는 수정/확정 불가
        req(put("/api/meetings/" + id + "/minutes"), guest, body).andExpect(status().isForbidden());
        req(post("/api/meetings/" + id + "/minutes/confirm"), guest, Map.of()).andExpect(status().isForbidden());

        var confirmed = read(req(post("/api/meetings/" + id + "/minutes/confirm"), host, Map.of()).andExpect(status().isOk()));
        assertEquals("CONFIRMED", confirmed.path("status").asText());
        var notes = read(req(get("/api/notifications"), guest, null));
        assertEquals(2, notes.path("unread").asInt()); // 회의록 확정 + 할 일 지정
        var mine = read(req(get("/api/action-items/mine"), guest, null));
        assertEquals("견적서 작성", mine.get(0).path("content").asText());
        long itemId = mine.get(0).path("id").asLong();
        req(post("/api/action-items/" + itemId + "/done"), guest, Map.of("done", true)).andExpect(status().isNoContent());
        assertTrue(read(req(get("/api/action-items/mine"), guest, null)).get(0).path("done").asBoolean());

        // 확정 후에는 수정·재생성 불가, 참석자도 열람 가능
        req(put("/api/meetings/" + id + "/minutes"), host, body).andExpect(status().isConflict());
        req(post("/api/meetings/" + id + "/minutes/generate"), host, Map.of("notes", "다시")).andExpect(status().isConflict());
        req(get("/api/meetings/" + id + "/minutes"), guest, null).andExpect(status().isOk());
        // 알림 읽음 처리
        long nid = notes.path("items").get(0).path("id").asLong();
        req(post("/api/notifications/" + nid + "/read"), guest, Map.of()).andExpect(status().isNoContent());
        assertEquals(1, read(req(get("/api/notifications"), guest, null)).path("unread").asInt());
    }

    @Test void nonMembersCannotSeeMinutesAndEmptyMeetingsAreRejected() throws Exception {
        var m = read(req(post("/api/meetings"), host, Map.of("title", "비공개 회의")).andExpect(status().isCreated()));
        String id = m.path("id").asText();
        req(post("/api/meetings/" + id + "/end"), host, Map.of()).andExpect(status().isNoContent());
        req(post("/api/meetings/" + id + "/minutes/generate"), host, Map.of()).andExpect(status().isBadRequest()); // 대화·메모 없음
        req(post("/api/meetings/" + id + "/minutes/generate"), host, Map.of("notes", "- 결정: 예산 확정")).andExpect(status().isOk());
        req(get("/api/meetings/" + id + "/minutes"), guest, null).andExpect(status().isNotFound());
        mvc.perform(get("/api/meetings/" + id + "/minutes")).andExpect(status().isUnauthorized());
    }

    // ── AI 응답 해석(신뢰하지 않는 출력) ──
    final MinutesService svc = new MinutesService(null, null, new ObjectMapper(), null, null);
    final List<MinutesService.MemberRef> members = List.of(new MinutesService.MemberRef("kim", "김대리"), new MinutesService.MemberRef("park", "박과장"));

    @Test void aiReplyIsParsedFromFencedJsonAndAssigneesAreMappedToMembers() {
        String reply = "```json\n{\"summary\":\"요약입니다\",\"decisions\":[\"A 확정\"],\"issues\":[],\"actionItems\":["
            + "{\"content\":\"견적서 작성\",\"assignee\":\"김대리\",\"due\":\"금요일까지\"},{\"content\":\"검토\",\"assignee\":\"외부인\",\"due\":\"\"}]}\n```";
        var d = svc.parse(reply, members);
        assertEquals("kim", d.actionItems().get(0).assigneeUsername());
        assertNull(d.actionItems().get(1).assigneeUsername());
        assertEquals("외부인", d.actionItems().get(1).assigneeText());
        assertNull(d.actionItems().get(1).dueText());
    }

    @Test void malformedOrOversizedAiOutputIsRejectedOrBounded() {
        assertThrows(IllegalArgumentException.class, () -> svc.parse("그냥 텍스트", members));
        assertThrows(IllegalArgumentException.class, () -> svc.parse("{\"decisions\":[]}", members));
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 80; i++) items.append(i == 0 ? "" : ",").append("{\"content\":\"할 일 ").append(i).append("\"}");
        var d = svc.parse("{\"summary\":\"s\",\"actionItems\":[" + items + "]}", members);
        assertEquals(MinutesService.MAX_ITEMS, d.actionItems().size());
        var injected = svc.parse("{\"summary\":\"" + "가".repeat(5000) + "\",\"decisions\":[\"x\\u0000y\"]}", members);
        assertEquals(2000, injected.summary().length());
    }
}
