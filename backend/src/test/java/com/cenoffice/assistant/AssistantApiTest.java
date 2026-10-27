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
    "app.jwt.secret=test-secret-test-secret-test-secret-123456", "app.llm.provider=mock", "livekit.api-key=", "livekit.api-secret="})
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

    @Test void unsupportedRequestsAreAnsweredHonestly() throws Exception {
        var room = ask(admin, "내일 오후 3시에 회의실 예약해줘");
        assertEquals("UNSUPPORTED", room.path("intent").asText());
        assertEquals("UNSUPPORTED", ask(admin, "오늘 날씨 어때").path("intent").asText());
    }

    @Test void inputIsValidatedAndRequiresLogin() throws Exception {
        req(post("/api/assistant/interpret"), admin, Map.of("text", "  ")).andExpect(status().isBadRequest());
        req(post("/api/assistant/interpret"), admin, Map.of("text", "가".repeat(501))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/assistant/interpret").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"내일 일정\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/assistant/status")).andExpect(status().isUnauthorized());
        assertFalse(read(req(get("/api/assistant/status"), admin, null).andExpect(status().isOk())).path("ai").asBoolean());
    }

    @Test void callsAreRateLimitedPerUser() throws Exception {
        String fresh = signup("rate" + UUID.randomUUID().toString().substring(0, 6), "제한 시험");
        assertNotNull(fresh);
        for (int i = 0; i < AssistantService.MAX_PER_MINUTE; i++) req(post("/api/assistant/interpret"), fresh, Map.of("text", "일정 보여줘")).andExpect(status().isOk());
        req(post("/api/assistant/interpret"), fresh, Map.of("text", "일정 보여줘")).andExpect(status().isTooManyRequests());
    }
}
