package com.cenoffice.common.notify;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:emailtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "app.seed.admin-password=test-admin-pw-1234", "app.seed.user-password=test-user-pw-1234",
    "app.jwt.secret=test-secret-test-secret-test-secret-123456", "app.llm.provider=mock",
    "livekit.api-key=", "livekit.api-secret=",
    "spring.mail.host=smtp.test.local", "app.mail.from=cen@test.local", "app.mail.base-url=https://cen.test.local"})
@AutoConfigureMockMvc
class EmailNotificationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired MailService mail;
    @MockBean JavaMailSender sender;
    String host, guest;

    @BeforeEach void setup() throws Exception {
        reset(sender);
        db.update("delete from email_outbox");
        host = login("admin", "test-admin-pw-1234");
        guest = login("user", "test-user-pw-1234");
        setEmail(guest, "", true);
        setEmail(host, "", true);
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
    void setEmail(String token, String email, boolean notify) throws Exception { req(put("/api/me/email"), token, Map.of("email", email, "enabled", notify)).andExpect(status().isOk()); }
    int outbox(String user) { return db.queryForObject("select count(*) from email_outbox where username=?", Integer.class, user); }

    /** 참석자(user)와 진행자(admin)가 대화한 뒤 종료하고 초안까지 만든 회의 id */
    String draftedMeeting(String title) throws Exception {
        var m = read(req(post("/api/meetings"), host, Map.of("title", title)).andExpect(status().isCreated()));
        String id = m.path("id").asText();
        req(post("/api/meetings/join"), guest, Map.of("code", m.path("code").asText())).andExpect(status().isOk());
        req(post("/api/meetings/" + id + "/messages"), host, Map.of("content", "로그인 화면 개선안은 이번 안으로 확정하기로 했습니다")).andExpect(status().isNoContent());
        req(post("/api/meetings/" + id + "/messages"), guest, Map.of("content", "제가 다음주 월요일까지 견적서 작성하겠습니다")).andExpect(status().isNoContent());
        req(post("/api/meetings/" + id + "/end"), host, Map.of()).andExpect(status().isNoContent());
        req(post("/api/meetings/" + id + "/minutes/generate"), host, Map.of("notes", "")).andExpect(status().isOk());
        return id;
    }

    @Test void settingsAreValidatedAndOnlyForTheOwner() throws Exception {
        req(get("/api/me/email"), guest, null).andExpect(status().isOk());
        mvc.perform(get("/api/me/email")).andExpect(status().isUnauthorized());
        req(put("/api/me/email"), guest, Map.of("email", "not-an-email", "enabled", true)).andExpect(status().isBadRequest());
        req(put("/api/me/email"), guest, Map.of("email", "a@b.com\r\nBcc: x@y.com", "enabled", true)).andExpect(status().isBadRequest());
        setEmail(guest, "guest@example.com", true);
        var s = read(req(get("/api/me/email"), guest, null));
        assertEquals("guest@example.com", s.path("email").asText());
        assertTrue(s.path("mailConfigured").asBoolean());
        assertEquals("", read(req(get("/api/me/email"), host, null)).path("email").asText()); // 다른 사람 설정은 보이지 않는다
    }

    @Test void confirmingMinutesQueuesMailOnlyForUsersWithAnAddressAndSendsIt() throws Exception {
        String id = draftedMeeting("메일 회의 " + UUID.randomUUID());
        setEmail(guest, "guest@example.com", true); // host 는 주소 없음
        req(post("/api/meetings/" + id + "/minutes/confirm"), host, Map.of()).andExpect(status().isOk());
        assertEquals(0, outbox("admin"));
        assertEquals(1, outbox("user"));
        mail.deliverPending();
        var captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, times(1)).send(captor.capture());
        var sent = captor.getValue();
        assertEquals("guest@example.com", sent.getTo()[0]);
        assertEquals("cen@test.local", sent.getFrom());
        assertTrue(sent.getSubject().startsWith("[CEN OFFICE]"));
        assertTrue(sent.getText().contains("https://cen.test.local"));
        assertEquals("SENT", db.queryForObject("select status from email_outbox where username='user'", String.class));
        // 담당자로 지정된 사람은 "확정" 메일이 아니라 "할 일 지정" 메일 한 통만 받는다
        assertEquals("ACTION_ASSIGNED", db.queryForObject("select kind from email_outbox where username='user'", String.class));
        assertTrue(sent.getSubject().contains("할 일"));
        mail.deliverPending();
        verify(sender, times(1)).send(any(SimpleMailMessage.class)); // 이미 보낸 메일은 다시 보내지 않는다
    }

    @Test void usersWhoTurnedNotificationsOffGetNoMail() throws Exception {
        String id = draftedMeeting("수신 거부 회의 " + UUID.randomUUID());
        setEmail(guest, "guest@example.com", false);
        req(post("/api/meetings/" + id + "/minutes/confirm"), host, Map.of()).andExpect(status().isOk());
        assertEquals(0, outbox("user"));
        var n = read(req(get("/api/notifications"), guest, null));
        assertTrue(n.path("unread").asInt() >= 1); // 화면 알림은 그대로 온다
    }

    @Test void aMailServerFailureNeverBreaksTheMinutesAndIsRetriedThenGivenUp() throws Exception {
        String id = draftedMeeting("장애 회의 " + UUID.randomUUID());
        setEmail(guest, "guest@example.com", true);
        doThrow(new org.springframework.mail.MailSendException("smtp down")).when(sender).send(any(SimpleMailMessage.class));
        req(post("/api/meetings/" + id + "/minutes/confirm"), host, Map.of()).andExpect(status().isOk()); // 확정은 성공
        for (int i = 1; i <= MailService.MAX_ATTEMPTS; i++) {
            db.update("update email_outbox set next_attempt_at=now() - interval '1' second where status='PENDING'");
            mail.deliverPending();
            assertEquals(i, db.queryForObject("select attempts from email_outbox where username='user'", Integer.class));
        }
        assertEquals("FAILED", db.queryForObject("select status from email_outbox where username='user'", String.class));
        assertTrue(db.queryForObject("select last_error from email_outbox where username='user'", String.class).contains("smtp down"));
        mail.deliverPending();
        verify(sender, times(MailService.MAX_ATTEMPTS)).send(any(SimpleMailMessage.class)); // 포기한 메일은 더 보내지 않는다
    }

    @Test void testMailGoesOnlyToTheOwnAddressAndIsRateLimited() throws Exception {
        req(post("/api/me/email/test"), guest, null).andExpect(status().isBadRequest()); // 주소 없음
        setEmail(guest, "guest@example.com", true);
        req(post("/api/me/email/test"), guest, null).andExpect(status().isNoContent());
        verify(sender, times(1)).send(any(SimpleMailMessage.class));
        req(post("/api/me/email/test"), guest, null).andExpect(status().isTooManyRequests());
    }

    @Test void subjectsCannotInjectHeaders() {
        assertEquals("제목 Bcc: x@y.com", MailService.oneLine("제목\r\nBcc: x@y.com"));
        assertTrue(MailService.validEmail("a.b+c@sub.example.co.kr"));
        assertFalse(MailService.validEmail("a@b"));
        assertFalse(MailService.validEmail("a b@c.com"));
    }
}
