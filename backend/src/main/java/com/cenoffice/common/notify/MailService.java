package com.cenoffice.common.notify;

import com.cenoffice.common.api.ApiException;
import com.cenoffice.common.api.AuditService;
import com.cenoffice.common.repo.UserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이메일 알림. 화면 알림과 같은 트랜잭션에서 대기열(email_outbox)에 쌓고, 별도 작업이 SMTP 로 보낸다.
 * - 받는 사람은 항상 "그 사용자가 등록한 본인 주소"뿐이다 (임의 수신자 지정 없음 → 중계 악용 불가).
 * - 본문은 제목·짧은 요약·링크만 담는 일반 텍스트다. 회의 전문 같은 민감한 내용은 메일로 보내지 않는다.
 * - SMTP 서버 정보가 없으면 기능이 꺼지고(화면 알림만 동작), 메일 서버 장애는 본 작업에 영향을 주지 않는다.
 */
@Service
public class MailService {
    private static final Logger log = LoggerFactory.getLogger(MailService.class);
    static final int MAX_ATTEMPTS = 3, BATCH = 20;
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9\\-]+(\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,}$");

    private final JdbcTemplate db;
    private final UserRepository users;
    private final AuditService audit;
    private final ObjectProvider<JavaMailSender> sender;
    private final String host, from, baseUrl;
    private final boolean enabled;
    private final AtomicBoolean delivering = new AtomicBoolean();
    private final Map<String, Instant> lastTest = new ConcurrentHashMap<>();

    public MailService(JdbcTemplate db, UserRepository users, AuditService audit, ObjectProvider<JavaMailSender> sender,
                       @Value("${spring.mail.host:}") String host,
                       @Value("${app.mail.from:}") String from,
                       @Value("${spring.mail.username:}") String username,
                       @Value("${app.mail.base-url:http://localhost:5180}") String baseUrl,
                       @Value("${app.mail.enabled:true}") boolean enabled) {
        this.db = db; this.users = users; this.audit = audit; this.sender = sender;
        this.host = host == null ? "" : host.strip();
        this.from = from == null || from.isBlank() ? (username == null ? "" : username.strip()) : from.strip();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.enabled = enabled;
    }

    public record Settings(String email, boolean enabled, boolean mailConfigured) {}

    /** SMTP 서버가 설정되어 실제로 보낼 수 있는 상태인가 */
    public boolean configured() { return enabled && !host.isEmpty() && !from.isEmpty() && sender.getIfAvailable() != null; }

    public static boolean validEmail(String s) { return s != null && s.length() <= 254 && EMAIL.matcher(s).matches(); }

    // ───────────────────────── 설정 ─────────────────────────

    @Transactional(readOnly = true)
    public Settings settings(String username) {
        var u = users.findByUsername(username).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다"));
        return new Settings(u.getEmail() == null ? "" : u.getEmail(), u.isEmailNotify(), configured());
    }

    @Transactional
    public Settings update(String username, String email, boolean enabled) {
        var u = users.findByUsername(username).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다"));
        String e = email == null ? "" : email.strip();
        if (!e.isEmpty() && !validEmail(e)) throw new ApiException(HttpStatus.BAD_REQUEST, "이메일 주소 형식이 올바르지 않습니다");
        u.setEmail(e.isEmpty() ? null : e);
        u.setEmailNotify(enabled);
        users.save(u);
        audit.log(username, "EMAIL_SETTINGS", null, e.isEmpty() ? "이메일 해제" : "이메일 등록, 수신=" + enabled);
        return new Settings(e, enabled, configured());
    }

    // ───────────────────────── 대기열 ─────────────────────────

    /** 화면 알림과 같은 트랜잭션에서 호출한다. 주소가 없거나 수신을 껐거나 메일이 꺼져 있으면 아무것도 하지 않는다. */
    @Transactional
    public void enqueue(String username, String kind, String title, String body) {
        if (!configured()) return;
        var u = users.findByUsername(username).orElse(null);
        if (u == null || u.getEmail() == null || !validEmail(u.getEmail()) || !u.isEmailNotify()) return;
        String text = oneLine(title) + "\n\n" + (body == null || body.isBlank() ? "" : oneLine(body) + "\n\n")
            + "CEN OFFICE 에서 확인하세요: " + baseUrl + "\n\n"
            + "이 메일은 CEN OFFICE 알림 설정에 따라 발송되었습니다. 받고 싶지 않으면 CEN OFFICE 의 알림(종 모양) > 이메일 설정에서 끌 수 있습니다.";
        Instant now = Instant.now();
        db.update("insert into email_outbox(username,to_email,kind,subject,body,status,attempts,next_attempt_at,created_at) values(?,?,?,?,?,'PENDING',0,?,?)",
            username, u.getEmail(), cut(kind, 32), cut("[CEN OFFICE] " + oneLine(title), 300), text, Timestamp.from(now), Timestamp.from(now));
    }

    /** 대기 중인 메일을 보낸다. 실패하면 점점 늦춰 최대 3번까지 다시 시도하고, 그래도 안 되면 FAILED 로 남긴다. */
    @Scheduled(fixedDelay = 10_000, initialDelay = 15_000)
    public void deliverPending() {
        if (!configured() || !delivering.compareAndSet(false, true)) return;
        try {
            List<Row> due = db.query("select id,to_email,subject,body,attempts from email_outbox where status='PENDING' and next_attempt_at<=? order by id limit " + BATCH,
                (r, i) -> new Row(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getInt(5)), Timestamp.from(Instant.now()));
            for (Row row : due) deliver(row);
        } catch (RuntimeException e) {
            log.warn("메일 대기열 처리 중 오류: {}", e.toString());
        } finally { delivering.set(false); }
    }

    private record Row(long id, String to, String subject, String body, int attempts) {}

    private void deliver(Row row) {
        try {
            send(row.to(), row.subject(), row.body());
            db.update("update email_outbox set status='SENT', attempts=?, sent_at=?, last_error=null where id=?", row.attempts() + 1, Timestamp.from(Instant.now()), row.id());
        } catch (RuntimeException e) {
            int attempts = row.attempts() + 1;
            boolean giveUp = attempts >= MAX_ATTEMPTS;
            db.update("update email_outbox set status=?, attempts=?, next_attempt_at=?, last_error=? where id=?", giveUp ? "FAILED" : "PENDING", attempts,
                Timestamp.from(Instant.now().plusSeconds(60L * attempts)), cut(describe(e), 500), row.id());
            log.warn("메일 발송 실패(id={}, {}회): {}", row.id(), attempts, describe(e));
        }
    }

    // ───────────────────────── 시험 발송 ─────────────────────────

    /** 설정이 맞는지 확인하도록 본인 주소로 즉시 한 통 보낸다. 30초에 한 번만 가능. */
    public void sendTest(String username) {
        if (!configured()) throw new ApiException(HttpStatus.CONFLICT, "메일 서버가 설정되지 않았습니다. 관리자에게 SMTP 설정을 요청하세요");
        var u = users.findByUsername(username).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다"));
        if (u.getEmail() == null || !validEmail(u.getEmail())) throw new ApiException(HttpStatus.BAD_REQUEST, "먼저 이메일 주소를 저장하세요");
        Instant now = Instant.now(), prev = lastTest.get(username);
        if (prev != null && now.isBefore(prev.plusSeconds(30))) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 시도하세요");
        lastTest.put(username, now);
        try {
            send(u.getEmail(), "[CEN OFFICE] 시험 메일", "CEN OFFICE 이메일 알림이 정상적으로 설정되었습니다.\n\n회의록이 확정되거나 할 일이 지정되면 이 주소로 알려 드립니다.\n" + baseUrl);
        } catch (RuntimeException e) {
            log.warn("시험 메일 발송 실패({}): {}", username, describe(e));
            throw new ApiException(HttpStatus.BAD_GATEWAY, "메일을 보내지 못했습니다. SMTP 서버 주소·계정·앱 비밀번호 설정을 확인하세요");
        }
        audit.log(username, "EMAIL_TEST", null, "시험 메일 발송");
    }

    // ───────────────────────── 내부 ─────────────────────────

    private void send(String to, String subject, String text) {
        var msg = new SimpleMailMessage();
        msg.setFrom(from); msg.setTo(to); msg.setSubject(oneLine(subject)); msg.setText(text);
        sender.getObject().send(msg);
    }

    /** 줄바꿈 제거(메일 헤더 주입 방지) + 앞뒤 공백 정리 */
    static String oneLine(String s) { return s == null ? "" : s.replaceAll("[\\p{Cntrl}]+", " ").strip(); }
    private static String cut(String s, int n) { return s == null ? null : (s.length() > n ? s.substring(0, n) : s); }
    private static String describe(Throwable e) {
        Throwable root = e; while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return e.getClass().getSimpleName() + ": " + oneLine(root.getMessage());
    }
}
