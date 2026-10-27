package com.cenoffice.calendar;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
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

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:calendartest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "app.seed.admin-password=test-admin-pw-1234", "app.seed.user-password=test-user-pw-1234",
    "app.jwt.secret=test-secret-test-secret-test-secret-123456", "app.llm.provider=mock", "livekit.api-key=", "livekit.api-secret="})
@AutoConfigureMockMvc
class CalendarApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    String admin, user, other;
    final Instant day = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
    final String from = day.minus(1, ChronoUnit.DAYS).toString(), to = day.plus(2, ChronoUnit.DAYS).toString();

    @BeforeEach void setup() throws Exception {
        admin = login("admin", "test-admin-pw-1234");
        user = login("user", "test-user-pw-1234");
        String name = "cal" + UUID.randomUUID().toString().substring(0, 8);
        other = read(mvc.perform(post("/api/auth/signup").with(r -> { r.setRemoteAddr("10.9." + (int) (Math.random() * 200) + "." + (int) (Math.random() * 200)); return r; })
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username", name, "displayName", "제삼자", "email", "", "password", "password-1234"))))).path("token").asText();
        setManager("user", false);
    }

    String login(String u, String p) throws Exception {
        return read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username", u, "password", p))).with(r -> { r.setRemoteAddr("10.8.0.1"); return r; }))).path("token").asText();
    }
    ResultActions req(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(b);
    }
    JsonNode read(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    void setManager(String username, boolean on) throws Exception { req(put("/api/admin/users/" + username + "/calendar-manager"), admin, Map.of("enabled", on)).andExpect(status().isOk()); }
    Map<String, Object> event(String scope, String title, List<String> attendees) {
        var m = new LinkedHashMap<String, Object>();
        m.put("scope", scope); m.put("title", title); m.put("startAt", day.toString()); m.put("endAt", day.plus(1, ChronoUnit.HOURS).toString());
        m.put("allDay", false); m.put("attendees", attendees);
        return m;
    }
    JsonNode feed(String token, String scope) throws Exception { return read(req(get("/api/calendar?scope=" + scope + "&from=" + from + "&to=" + to), token, null).andExpect(status().isOk())); }
    boolean has(JsonNode feed, String title) { for (var e : feed.path("entries")) if (e.path("title").asText().equals(title)) return true; return false; }

    @Test void personalEventIsVisibleToCreatorAndInviteeOnlyAndInviteeIsNotified() throws Exception {
        String t = "개인 회의 " + UUID.randomUUID();
        var e = read(req(post("/api/calendar/events"), admin, event("PERSONAL", t, List.of("user"))).andExpect(status().isCreated()));
        assertEquals("EVENT", e.path("kind").asText());
        assertTrue(e.path("editable").asBoolean());
        assertEquals("user", e.path("attendees").get(0).path("username").asText());
        assertTrue(has(feed(admin, "mine"), t));
        var inv = feed(user, "mine");
        assertTrue(has(inv, t));
        for (var x : inv.path("entries")) if (x.path("title").asText().equals(t)) assertFalse(x.path("editable").asBoolean()); // 초대받은 사람은 수정 불가
        assertFalse(has(feed(other, "mine"), t));
        assertFalse(has(feed(admin, "shared"), t)); // 개인 일정은 전체 토글에 나오지 않는다
        var notes = read(req(get("/api/notifications"), user, null));
        boolean notified = false; for (var n : notes.path("items")) if (n.path("title").asText().equals("일정에 초대되었습니다") && n.path("body").asText().contains(t)) notified = true;
        assertTrue(notified);
    }

    @Test void onlyCreatorEditsPersonalAndNewAttendeesAreNotifiedOnce() throws Exception {
        String t = "수정 대상 " + UUID.randomUUID();
        long id = read(req(post("/api/calendar/events"), admin, event("PERSONAL", t, List.of())).andExpect(status().isCreated())).path("eventId").asLong();
        assertTrue(id > 0);
        var upd = event("PERSONAL", t + " v2", List.of("user"));
        req(put("/api/calendar/events/" + id), user, upd).andExpect(status().isForbidden());
        req(put("/api/calendar/events/" + id), admin, upd).andExpect(status().isOk());
        assertTrue(has(feed(user, "mine"), t + " v2"));
        req(delete("/api/calendar/events/" + id), user, null).andExpect(status().isForbidden());
        req(delete("/api/calendar/events/" + id), admin, null).andExpect(status().isNoContent());
        assertFalse(has(feed(user, "mine"), t + " v2"));
        req(delete("/api/calendar/events/" + id), admin, null).andExpect(status().isNotFound());
    }

    @Test void sharedEventsNeedAdminOrCalendarManagerAndEveryoneCanSeeThem() throws Exception {
        String t = "전사 행사 " + UUID.randomUUID();
        req(post("/api/calendar/events"), user, event("SHARED", t, List.of())).andExpect(status().isForbidden());
        var created = read(req(post("/api/calendar/events"), admin, event("SHARED", t, List.of())).andExpect(status().isCreated()));
        long id = created.path("eventId").asLong();
        for (String who : List.of(admin, user, other)) assertTrue(has(feed(who, "shared"), t));
        assertFalse(has(feed(user, "mine"), t));
        assertFalse(feed(user, "shared").path("canManageShared").asBoolean());
        req(put("/api/calendar/events/" + id), user, event("SHARED", t + "x", List.of())).andExpect(status().isForbidden());
        req(delete("/api/calendar/events/" + id), user, null).andExpect(status().isForbidden());

        setManager("user", true); // 팀장 등 일정 관리자로 지정
        assertTrue(feed(user, "shared").path("canManageShared").asBoolean());
        req(put("/api/calendar/events/" + id), user, event("SHARED", t + " (수정)", List.of())).andExpect(status().isOk());
        String t2 = "팀장 일정 " + UUID.randomUUID();
        req(post("/api/calendar/events"), user, event("SHARED", t2, List.of())).andExpect(status().isCreated());
        assertTrue(has(feed(other, "shared"), t2));
        setManager("user", false);
        req(post("/api/calendar/events"), user, event("SHARED", "해제 후", List.of())).andExpect(status().isForbidden());
        req(delete("/api/calendar/events/" + id), admin, null).andExpect(status().isNoContent());
    }

    @Test void normalUserCannotGrantManagerAndSharedEventsTakeNoAttendees() throws Exception {
        req(put("/api/admin/users/user/calendar-manager"), user, Map.of("enabled", true)).andExpect(status().isForbidden());
        req(get("/api/admin/users"), user, null).andExpect(status().isForbidden());
        req(post("/api/calendar/events"), admin, event("SHARED", "참석자 있는 전체 일정", List.of("user"))).andExpect(status().isBadRequest());
        assertTrue(read(req(get("/api/admin/users"), admin, null)).size() >= 2);
    }

    @Test void invalidInputsAreRejected() throws Exception {
        var bad = event("PERSONAL", "시간 역전", List.of()); bad.put("endAt", day.minus(1, ChronoUnit.HOURS).toString());
        req(post("/api/calendar/events"), admin, bad).andExpect(status().isBadRequest());
        req(post("/api/calendar/events"), admin, event("PERSONAL", "없는 참석자", List.of("nobody-here"))).andExpect(status().isBadRequest());
        req(post("/api/calendar/events"), admin, event("PERSONAL", "  ", List.of())).andExpect(status().isBadRequest());
        req(post("/api/calendar/events"), admin, event("WEIRD", "종류 오류", List.of())).andExpect(status().isBadRequest());
        req(get("/api/calendar?scope=mine&from=" + day.minus(40, ChronoUnit.DAYS) + "&to=" + day.plus(40, ChronoUnit.DAYS)), admin, null).andExpect(status().isBadRequest()); // 62일 초과
        mvc.perform(get("/api/calendar?scope=mine&from=" + from + "&to=" + to)).andExpect(status().isUnauthorized());
    }

    @Test void myRoomReservationsAppearInMyCalendarOnly() throws Exception {
        long roomId = read(req(get("/api/meeting-rooms"), user, null)).get(0).path("id").asLong();
        String t = "예약 " + UUID.randomUUID();
        req(post("/api/reservations"), user, Map.of("roomId", roomId, "title", t, "startAt", day.plus(5, ChronoUnit.HOURS).toString(), "endAt", day.plus(6, ChronoUnit.HOURS).toString())).andExpect(status().isCreated());
        var mine = feed(user, "mine");
        assertTrue(has(mine, t));
        for (var x : mine.path("entries")) if (x.path("title").asText().equals(t)) { assertEquals("RESERVATION", x.path("kind").asText()); assertFalse(x.path("editable").asBoolean()); assertFalse(x.path("location").asText().isBlank()); }
        assertFalse(has(feed(other, "mine"), t));
        assertFalse(has(feed(user, "shared"), t));
    }
}
