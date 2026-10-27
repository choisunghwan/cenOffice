package com.cenoffice.assistant;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** AI 가 연결되지 않은 기본 환경(규칙 해석)에서 입력창 API 를 확인한다. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:assistanttest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "app.seed.admin-password=test-admin-pw-1234", "app.seed.user-password=test-user-pw-1234",
    "app.jwt.secret=test-secret-test-secret-test-secret-123456", "app.llm.provider=mock", "livekit.api-key=", "livekit.api-secret=", "app.assistant.max-per-minute=1000"})
@AutoConfigureMockMvc
class AssistantApiTest {
    static final ZoneId ZONE = ZoneId.systemDefault();
    static final AtomicInteger IP = new AtomicInteger(1);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    String admin, user;

    @BeforeEach void setup() throws Exception {
        admin = login("admin", "test-admin-pw-1234");
        user = login("user", "test-user-pw-1234");
        for (var u : new String[][]{{"kim01", "김대리"}, {"park01", "박과장"}, {"lee01", "이중복"}, {"lee02", "이중복"}}) signup(u[0], u[1]); // 이미 있으면 409 — 무시
    }

    String login(String u, String p) throws Exception {
        return read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username", u, "password", p))).with(r -> { r.setRemoteAddr("10.7.0.1"); return r; }))).path("token").asText();
    }
    String signup(String username, String name) throws Exception {
        var res = mvc.perform(post("/api/auth/signup").with(r -> { r.setRemoteAddr("10.6." + IP.getAndIncrement() / 200 + "." + IP.get() % 200); return r; })
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username", username, "displayName", name, "email", "", "password", "password-1234"))));
        return res.andReturn().getResponse().getStatus() == 200 ? read(res).path("token").asText() : null;
    }
    ResultActions req(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(b);
    }
    JsonNode read(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    JsonNode ask(String token, String text) throws Exception { return read(req(post("/api/assistant/interpret"), token, Map.of("text", text)).andExpect(status().isOk())); }
    boolean hasEvent(String token, String title) throws Exception {
        LocalDate d = LocalDate.now(ZONE);
        var feed = read(req(get("/api/calendar?scope=mine&from=" + d.atStartOfDay(ZONE).toInstant() + "&to=" + d.plusDays(10).atStartOfDay(ZONE).toInstant()), token, null).andExpect(status().isOk()));
        for (var e : feed.path("entries")) if (e.path("title").asText().equals(title)) return true;
        return false;
    }

    @Test void proposalIsADraftAndNothingIsCreatedUntilTheUserConfirms() throws Exception {
        String title = "팀 회의 " + UUID.randomUUID().toString().substring(0, 6);
        var r = ask(admin, "내일 오후 3시에 김대리랑 " + title + " 잡아줘");
        assertEquals("CREATE_EVENT", r.path("intent").asText());
        assertFalse(r.path("ai").asBoolean());
        assertEquals("rule-based", r.path("source").asText());
        var d = r.path("draft");
        assertEquals(title, d.path("title").asText());
        assertEquals("kim01", d.path("attendees").get(0).path("username").asText());
        var start = Instant.parse(d.path("startAt").asText()).atZone(ZONE);
        assertEquals(LocalDate.now(ZONE).plusDays(1), start.toLocalDate());
        assertEquals(15, start.getHour());
        assertFalse(hasEvent(admin, title), "확인하기 전에는 일정이 만들어지면 안 된다");

        // 확인 카드의 [등록]은 기존 일정 API 를 같은 초안으로 호출한다
        var body = new LinkedHashMap<String, Object>();
        body.put("scope", d.path("scope").asText()); body.put("title", d.path("title").asText()); body.put("description", ""); body.put("location", "");
        body.put("startAt", d.path("startAt").asText()); body.put("endAt", d.path("endAt").asText()); body.put("allDay", d.path("allDay").asBoolean());
        body.put("attendees", java.util.List.of("kim01"));
        req(post("/api/calendar/events"), admin, body).andExpect(status().isCreated());
        assertTrue(hasEvent(admin, title));
    }

    @Test void sameNameForSeveralPeopleAsksTheUserToPick() throws Exception {
        var r = ask(admin, "내일 오후 2시에 이중복이랑 면담 잡아줘");
        assertEquals("CREATE_EVENT", r.path("intent").asText());
        assertEquals(0, r.path("draft").path("attendees").size());
        assertEquals(1, r.path("ambiguous").size());
        assertEquals(2, r.path("ambiguous").get(0).path("candidates").size());
    }

    @Test void missingInfoIsAskedBackAndTheAnswerIsCombinedWithThePreviousRequest() throws Exception {
        var first = ask(admin, "주간 보고 잡아줘");
        assertEquals("CLARIFY", first.path("intent").asText());
        var second = read(req(post("/api/assistant/interpret"), admin, Map.of("text", "내일 오전 10시", "previous", "주간 보고 잡아줘")).andExpect(status().isOk()));
        assertEquals("CREATE_EVENT", second.path("intent").asText());
        assertEquals("주간 보고", second.path("draft").path("title").asText());
        var noTime = ask(admin, "내일 주간 보고 잡아줘");
        assertEquals("CLARIFY", noTime.path("intent").asText());
        assertTrue(noTime.path("message").asText().contains("몇 시"));
    }

    @Test void listAnswersFromTheCallersOwnCalendarOnly() throws Exception {
        String title = "조회 대상 " + UUID.randomUUID().toString().substring(0, 6);
        var tomorrow = LocalDate.now(ZONE).plusDays(1).atTime(10, 0).atZone(ZONE).toInstant();
        req(post("/api/calendar/events"), admin, Map.of("scope", "PERSONAL", "title", title, "description", "", "location", "", "startAt", tomorrow.toString(),
            "endAt", tomorrow.plusSeconds(3600).toString(), "allDay", false, "attendees", java.util.List.of())).andExpect(status().isCreated());
        var mine = ask(admin, "내일 일정 뭐 있어?");
        assertEquals("LIST_EVENTS", mine.path("intent").asText());
        boolean found = false; for (var e : mine.path("entries")) if (e.path("title").asText().equals(title)) found = true;
        assertTrue(found);
        var others = ask(user, "내일 일정 뭐 있어?");
        for (var e : others.path("entries")) assertNotEquals(title, e.path("title").asText()); // 남의 개인 일정은 보이지 않는다
    }

    @Test void sharedWordingIsDowngradedForNormalUsersAndHonoredForAdmins() throws Exception {
        var normal = ask(user, "전체 일정으로 내일 오후 2시 창립기념 행사 등록해줘");
        assertEquals("PERSONAL", normal.path("draft").path("scope").asText());
        assertTrue(normal.path("message").asText().contains("개인 일정으로"));
        var boss = ask(admin, "전체 일정으로 내일 오후 2시 창립기념 행사 등록해줘");
        assertEquals("SHARED", boss.path("draft").path("scope").asText());
    }

    @Test void withoutAiGeneralQuestionsGetAHelpfulMessageNotADeadEnd() throws Exception {
        var r = ask(admin, "견적서 작성 주제가 뭔데");
        assertEquals("UNSUPPORTED", r.path("intent").asText());
        assertTrue(r.path("message").asText().contains("AI가 연결되어 있지 않아"));
        assertTrue(r.path("message").asText().contains("내 할 일 보여줘")); // 할 수 있는 일을 예시로 알려 준다
    }

    @Test void unsupportedRequestsAreAnsweredHonestly() throws Exception {
        assertEquals("UNSUPPORTED", ask(admin, "오늘 날씨 어때").path("intent").asText());
    }

    // ── 회의실 예약 ──

    JsonNode createEvent(String token, String title, int plusDays, int hour) throws Exception {
        var s = LocalDate.now(ZONE).plusDays(plusDays).atTime(hour, 0).atZone(ZONE).toInstant();
        return read(req(post("/api/calendar/events"), token, Map.of("scope", "PERSONAL", "title", title, "description", "", "location", "", "startAt", s.toString(),
            "endAt", s.plusSeconds(3600).toString(), "allDay", false, "attendees", java.util.List.of())).andExpect(status().isCreated()));
    }

    @Test void roomBookingFindsFreeRoomsAndReservesOnlyAfterTheUserConfirms() throws Exception {
        String day = "2027-03-1" + (1 + (int) (Math.random() * 8)); // 다른 테스트와 시간이 겹치지 않게
        var open = ask(admin, day + " 오전 10시에 회의실 예약해줘 점검A");
        assertEquals("BOOK_ROOM", open.path("intent").asText(), open.toString());
        assertEquals("점검A", open.path("draft").path("title").asText());
        assertTrue(open.path("room").isNull() || open.path("room").isMissingNode());
        assertEquals(2, open.path("rooms").size()); // 방을 정하지 않았으면 빈 방 중에서 고르게 한다

        var picked = ask(admin, day + " 오전 10시 회의실 A 예약해줘 점검A");
        assertEquals("회의실 A", picked.path("room").path("name").asText());
        assertEquals(0, picked.path("rooms").size());
        // 카드의 [예약하기]는 기존 예약 API 를 호출한다
        var d = picked.path("draft");
        req(post("/api/reservations"), admin, Map.of("roomId", picked.path("room").path("id").asLong(), "title", "점검A", "startAt", d.path("startAt").asText(), "endAt", d.path("endAt").asText())).andExpect(status().isCreated());

        // 같은 시간에 같은 방을 다시 부르면 차 있다고 알리고 빈 방을 제안한다
        var busy = ask(user, day + " 오전 10시 회의실 A 예약해줘 점검B");
        assertTrue(busy.path("message").asText().contains("이미 예약"));
        assertEquals(1, busy.path("rooms").size());
        assertEquals("회의실 B", busy.path("rooms").get(0).path("name").asText());
        // 빈 방이 하나뿐이고 방을 정하지 않았으면 그 방을 바로 제안한다
        var only = ask(user, day + " 오전 10시에 회의실 예약해줘 점검C");
        assertEquals("회의실 B", only.path("room").path("name").asText());
        // 큰 방을 원했는데 이미 찼다면 알린다
        var large = ask(user, day + " 오전 10시 큰 회의실 예약해줘");
        assertEquals("BOOK_ROOM", large.path("intent").asText());
        assertTrue(large.path("room").isNull() || large.path("room").isMissingNode() || !large.path("room").path("name").asText().equals("회의실 A"));
    }

    @Test void roomBookingAsksForMissingOrInvalidTimes() throws Exception {
        assertEquals("CLARIFY", ask(admin, "회의실 예약해줘").path("intent").asText());
        assertTrue(ask(admin, "내일 회의실 예약해줘").path("message").asText().contains("몇 시"));
        assertEquals("CLARIFY", ask(admin, "내일 종일 회의실 예약해줘").path("intent").asText());
        assertEquals("CLARIFY", ask(admin, "내일 오전 9시부터 오후 8시까지 회의실 예약해줘").path("intent").asText()); // 8시간 초과
        assertEquals("CLARIFY", ask(admin, "2020-01-01 오전 10시 회의실 예약해줘").path("intent").asText()); // 지난 시간
    }

    // ── 삭제·취소·변경 ──

    @Test void deleteTargetsOnlyMyOwnEditableItemsAndAsksWhenSeveralMatch() throws Exception {
        createEvent(admin, "주간점검Q", 2, 9);
        createEvent(admin, "주간점검Q", 4, 9);
        var several = ask(admin, "주간점검Q 삭제해줘");
        assertEquals("DELETE_ITEM", several.path("intent").asText());
        assertTrue(several.path("target").isNull() || several.path("target").isMissingNode());
        assertEquals(2, several.path("candidates").size());

        var one = ask(admin, LocalDate.now(ZONE).plusDays(2) + " 주간점검Q 삭제해줘");
        assertEquals("DELETE_ITEM", one.path("intent").asText());
        assertEquals("주간점검Q", one.path("target").path("title").asText());
        long id = one.path("target").path("eventId").asLong();
        // 남이 만든 개인 일정은 대상이 아니다
        assertEquals("CLARIFY", ask(user, "주간점검Q 삭제해줘").path("intent").asText());
        req(delete("/api/calendar/events/" + id), admin, null).andExpect(status().isNoContent());
        assertEquals("DELETE_ITEM", ask(admin, "주간점검Q 삭제해줘").path("intent").asText()); // 남은 하나
        assertTrue(ask(admin, "주간점검Q 삭제해줘").path("target").path("title").asText().equals("주간점검Q"));
        assertEquals("CLARIFY", ask(admin, "존재안함ZZZ 삭제해줘").path("intent").asText());
        assertEquals("CLARIFY", ask(admin, "삭제해줘").path("intent").asText());
    }

    @Test void roomReservationsCanBeCancelledThroughTheSameFlow() throws Exception {
        var start = LocalDate.now(ZONE).plusDays(40).atTime(15, 0).atZone(ZONE).toInstant();
        long roomId = read(req(get("/api/meeting-rooms"), admin, null)).get(0).path("id").asLong();
        long rid = read(req(post("/api/reservations"), admin, Map.of("roomId", roomId, "title", "점검R9X", "startAt", start.toString(), "endAt", start.plusSeconds(3600).toString())).andExpect(status().isCreated())).path("id").asLong();
        var r = ask(admin, "점검R9X 예약 취소해줘");
        assertEquals("DELETE_ITEM", r.path("intent").asText(), r.toString());
        assertEquals("RESERVATION", r.path("target").path("kind").asText());
        assertTrue(r.path("message").asText().contains("취소할까요"));
        assertEquals("r:" + rid, r.path("target").path("id").asText());
        req(delete("/api/reservations/" + rid), admin, null).andExpect(status().isNoContent());
    }

    @Test void moveBuildsTheChangedDraftKeepingAttendeesAndDuration() throws Exception {
        var made = createEvent(admin, "이동대상Q7", 3, 10);
        var r = ask(admin, "이동대상Q7 오후 4시로 옮겨줘");
        assertEquals("MOVE_EVENT", r.path("intent").asText(), r.toString());
        var d = r.path("draft");
        var s = Instant.parse(d.path("startAt").asText()).atZone(ZONE);
        assertEquals(16, s.getHour());
        assertEquals(LocalDate.now(ZONE).plusDays(3), s.toLocalDate());
        assertEquals(3600, java.time.Duration.between(Instant.parse(d.path("startAt").asText()), Instant.parse(d.path("endAt").asText())).getSeconds()); // 길이 유지
        assertEquals(made.path("eventId").asLong(), r.path("target").path("eventId").asLong());
        // 카드의 [옮기기]는 기존 일정 수정 API 를 같은 초안으로 호출한다
        var body = new LinkedHashMap<String, Object>();
        body.put("scope", d.path("scope").asText()); body.put("title", d.path("title").asText()); body.put("description", ""); body.put("location", "");
        body.put("startAt", d.path("startAt").asText()); body.put("endAt", d.path("endAt").asText()); body.put("allDay", false); body.put("attendees", java.util.List.of());
        req(put("/api/calendar/events/" + made.path("eventId").asLong()), admin, body).andExpect(status().isOk());
        assertTrue(hasEvent(admin, "이동대상Q7"));

        assertEquals("CLARIFY", ask(admin, "이동대상Q7 옮겨줘").path("intent").asText()); // 언제로 옮길지 없음
        assertEquals("CLARIFY", ask(user, "이동대상Q7 오후 4시로 옮겨줘").path("intent").asText()); // 남의 일정
    }

    @Test void sharedEventsCanBeDeletedOnlyByThoseWhoMayEditThem() throws Exception {
        var s = LocalDate.now(ZONE).plusDays(5).atTime(9, 0).atZone(ZONE).toInstant();
        long id = read(req(post("/api/calendar/events"), admin, Map.of("scope", "SHARED", "title", "전사행사ST", "description", "", "location", "", "startAt", s.toString(),
            "endAt", s.plusSeconds(3600).toString(), "allDay", false, "attendees", java.util.List.of())).andExpect(status().isCreated())).path("eventId").asLong();
        assertEquals("CLARIFY", ask(user, "전체 일정 전사행사ST 삭제해줘").path("intent").asText());
        var mine = ask(admin, "전체 일정 전사행사ST 삭제해줘");
        assertEquals("DELETE_ITEM", mine.path("intent").asText());
        req(delete("/api/calendar/events/" + id), admin, null).andExpect(status().isNoContent());
    }

    // ── 할 일 · 확정할 회의록 · 브리핑 (물으면 대화로 보여 준다) ──

    @Test void statusQuestionsShowTasksPendingMinutesAndTheDayBriefing() throws Exception {
        var made = createEvent(admin, "브리핑점검V1", 0, 23); // 오늘 일정 (자정 가까이 잡아 시간 경과와 무관하게 오늘에 속한다)
        var tasks = ask(admin, "내 할 일 보여줘");
        assertEquals("LIST_TASKS", tasks.path("intent").asText(), tasks.toString());
        assertTrue(tasks.path("tasks").isArray());
        var pending = ask(admin, "확정할 회의록 있어?");
        assertEquals("LIST_PENDING", pending.path("intent").asText());
        assertTrue(pending.path("pendingMinutes").isArray());
        var brief = ask(admin, "오늘 브리핑 해줘");
        assertEquals("BRIEFING", brief.path("intent").asText());
        boolean found = false; for (var e : brief.path("entries")) if (e.path("title").asText().equals("브리핑점검V1")) found = true;
        assertTrue(found);
        assertTrue(brief.path("message").asText().contains("오늘 일정"));
        // 남의 개인 일정은 브리핑에 나오지 않는다
        for (var e : ask(user, "오늘 브리핑 해줘").path("entries")) assertNotEquals("브리핑점검V1", e.path("title").asText());
        req(delete("/api/calendar/events/" + made.path("eventId").asLong()), admin, null).andExpect(status().isNoContent());
    }

    @Test void personalTasksCannotBeAddedByTypingYet() throws Exception {
        assertEquals("UNSUPPORTED", ask(admin, "할 일 추가해줘 견적서 작성").path("intent").asText());
    }

    JsonNode builtinTool(String name) throws Exception {
        var page = read(req(get("/api/hub/skills?status=APPROVED&size=100"), admin, null).andExpect(status().isOk()));
        for (var s : page.path("items")) if (s.path("owner").asText().equals("hub-builtin") && s.path("name").asText().equals(name)) return s;
        fail("기본 제공 도구가 없습니다: " + name);
        return null;
    }

    @Test void approvedHubToolIsRecommendedAndThenRunThroughTheNormalHubApi() throws Exception {
        var tool = builtinTool("cen-sql-reviewer");
        String sample = "SELECT * FROM orders WHERE customer_name LIKE '%kim';";
        var r = ask(admin, "SQL 점검 부탁해\n" + sample);
        assertEquals("USE_TOOL", r.path("intent").asText(), r.toString());
        assertEquals("SQL 점검", r.path("tool").path("title").asText()); // 영문 식별자가 아니라 한글 제목
        assertEquals(tool.path("id").asLong(), r.path("tool").path("skillId").asLong());
        assertEquals("rule", r.path("tool").path("engine").asText()); // 기본 제공 도구는 서버 안에서 처리 → 외부 전송 없음
        assertFalse(r.path("tool").path("external").asBoolean());
        assertFalse(r.path("ai").asBoolean());
        // 추천만으로는 아무 것도 실행되지 않는다. 카드의 [실행]은 기존 허브 실행 API 를 같은 사용자 권한으로 호출한다.
        var exec = read(req(post("/api/hub/executions"), admin, Map.of("skillId", r.path("tool").path("skillId").asLong(), "input", sample, "engine", r.path("tool").path("engine").asText())).andExpect(status().isAccepted()));
        String id = exec.path("id").asText();
        String state = "";
        for (int i = 0; i < 50 && !state.equals("SUCCEEDED") && !state.equals("FAILED"); i++) { Thread.sleep(200); state = read(req(get("/api/hub/executions/" + id), admin, null)).path("status").asText(); }
        assertEquals("SUCCEEDED", state);
    }

    @Test void textWithNoRelatedToolIsNotForcedOntoOne() throws Exception {
        assertEquals("UNSUPPORTED", ask(admin, "오늘 점심 뭐 먹을까 고민이야").path("intent").asText());
    }

    @Test void longPastedTextIsAcceptedForTools() throws Exception {
        String longText = "회의록 정리 부탁해\n" + "회의 내용입니다. ".repeat(200);
        assertTrue(longText.length() > 500 && longText.length() <= 4000);
        assertEquals("USE_TOOL", ask(admin, longText).path("intent").asText());
    }

    @Test void inputIsValidatedAndRequiresLogin() throws Exception {
        req(post("/api/assistant/interpret"), admin, Map.of("text", "  ")).andExpect(status().isBadRequest());
        req(post("/api/assistant/interpret"), admin, Map.of("text", "가".repeat(4001))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/assistant/interpret").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"내일 일정\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/assistant/status")).andExpect(status().isUnauthorized());
        assertFalse(read(req(get("/api/assistant/status"), admin, null).andExpect(status().isOk())).path("ai").asBoolean());
    }

}
