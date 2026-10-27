package com.cenoffice.notify;

import com.cenoffice.ws.WsEvents.UserEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 시스템 알림 (화면 내 알림). 저장 후 해당 사용자에게만 WebSocket 으로 'notification.new' 를 보낸다. */
@Service
@Transactional
public class NotificationService {
    public record Item(long id, String type, String title, String body, String link, Instant createdAt, boolean read) {}
    public record Page(List<Item> items, int unread) {}

    private final JdbcTemplate db;
    private final ApplicationEventPublisher events;

    private final MailService mail;

    public NotificationService(JdbcTemplate db, ApplicationEventPublisher events, MailService mail) { this.db = db; this.events = events; this.mail = mail; }

    public void notify(String username, String type, String title, String body, String link) { notify(username, type, title, body, link, true); }

    /** withMail=false 면 화면 알림만 보낸다(같은 사건에 대해 메일이 여러 통 가지 않게 할 때). */
    public void notify(String username, String type, String title, String body, String link, boolean withMail) {
        db.update("insert into notifications(username,type,title,body,link,created_at) values(?,?,?,?,?,?)", username, type, cut(title, 200), cut(body, 1000), link, Timestamp.from(Instant.now()));
        if (withMail) mail.enqueue(username, type, title, body); // 이메일 주소가 있고 수신을 켠 사용자에게만 대기열에 쌓인다
        events.publishEvent(UserEvent.to(Set.of(username), "notification.new", Map.of("type", type)));
    }

    @Transactional(readOnly = true)
    public Page list(String username) {
        List<Item> items = db.query("select * from notifications where username=? order by id desc limit 50",
            (r, i) -> new Item(r.getLong("id"), r.getString("type"), r.getString("title"), r.getString("body"), r.getString("link"), r.getTimestamp("created_at").toInstant(), r.getTimestamp("read_at") != null), username);
        Integer unread = db.queryForObject("select count(*) from notifications where username=? and read_at is null", Integer.class, username);
        return new Page(items, unread == null ? 0 : unread);
    }

    public void markRead(String username, long id) {
        db.update("update notifications set read_at=? where id=? and username=? and read_at is null", Timestamp.from(Instant.now()), id, username);
    }

    public void markAllRead(String username) {
        db.update("update notifications set read_at=? where username=? and read_at is null", Timestamp.from(Instant.now()), username);
    }

    private static String cut(String s, int n) { return s == null ? null : (s.length() > n ? s.substring(0, n) : s); }
}
