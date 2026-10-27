package com.cenoffice.assistant;

import com.cenoffice.api.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 홈 입력창의 채팅방(대화 세션) 저장. 모든 조회·수정은 만든 사람 본인만 할 수 있고, 남의 방은 없는 것처럼 404 로 답한다. */
@Service
public class ChatSessionService {
    public record Summary(String id, String title, Instant updatedAt) {}
    public record Detail(String id, String title, JsonNode turns) {}

    static final String DEFAULT_TITLE = "새 채팅";
    static final int MAX_TITLE = 120;
    static final int MAX_TURNS = 100;
    static final int MAX_TURNS_CHARS = 500_000;
    static final int MAX_SESSIONS = 200;

    private final JdbcTemplate db;
    private final ObjectMapper json;

    public ChatSessionService(JdbcTemplate db, ObjectMapper json) { this.db = db; this.json = json; }

    public Summary create(String owner, String title) {
        String t = cleanTitle(title);
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        db.update("INSERT INTO assistant_sessions (id, owner, title, turns, created_at, updated_at) VALUES (?, ?, ?, '[]', ?, ?)", id, owner, t, Timestamp.from(now), Timestamp.from(now));
        // 방이 너무 많이 쌓이지 않게 가장 오래된 것부터 정리한다
        db.update("DELETE FROM assistant_sessions WHERE owner = ? AND id NOT IN (SELECT id FROM (SELECT id FROM assistant_sessions WHERE owner = ? ORDER BY updated_at DESC LIMIT ?) keep)", owner, owner, MAX_SESSIONS);
        return new Summary(id, t, now);
    }

    public List<Summary> list(String owner) {
        return db.query("SELECT id, title, updated_at FROM assistant_sessions WHERE owner = ? ORDER BY updated_at DESC LIMIT 100",
            (rs, i) -> new Summary(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant()), owner);
    }

    public Detail get(String owner, String id) {
        var rows = db.query("SELECT id, title, turns FROM assistant_sessions WHERE id = ? AND owner = ?", (rs, i) -> {
            try {
                return new Detail(rs.getString(1), rs.getString(2), json.readTree(rs.getString(3)));
            } catch (JsonProcessingException e) {
                return new Detail(rs.getString(1), rs.getString(2), json.createArrayNode()); // 깨진 기록은 빈 대화로 연다
            }
        }, id, owner);
        if (rows.isEmpty()) throw notFound();
        return rows.get(0);
    }

    /** title 또는 turns 가 null 이면 그 부분은 그대로 둔다. 저장할 때마다 맨 위로 올라온다. */
    public void save(String owner, String id, String title, JsonNode turns) {
        get(owner, id); // 내 방인지 확인(아니면 404)
        if (title != null) db.update("UPDATE assistant_sessions SET title = ? WHERE id = ? AND owner = ?", cleanTitle(title), id, owner);
        if (turns != null) {
            if (!turns.isArray() || turns.size() > MAX_TURNS) throw bad("대화 기록 형식이 올바르지 않습니다.");
            String text = turns.toString();
            if (text.length() > MAX_TURNS_CHARS) throw bad("대화가 너무 깁니다. 새 채팅을 시작해 주세요.");
            db.update("UPDATE assistant_sessions SET turns = ? WHERE id = ? AND owner = ?", text, id, owner);
        }
        db.update("UPDATE assistant_sessions SET updated_at = ? WHERE id = ? AND owner = ?", Timestamp.from(Instant.now()), id, owner);
    }

    public void delete(String owner, String id) {
        if (db.update("DELETE FROM assistant_sessions WHERE id = ? AND owner = ?", id, owner) == 0) throw notFound();
    }

    private static String cleanTitle(String title) {
        String t = title == null ? "" : title.replaceAll("\\p{Cntrl}", " ").strip();
        if (t.isEmpty()) return DEFAULT_TITLE;
        if (t.length() > MAX_TITLE) throw bad("제목은 " + MAX_TITLE + "자 이하여야 합니다.");
        return t;
    }

    private static ApiException bad(String m) { return new ApiException(HttpStatus.BAD_REQUEST, m); }

    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "대화를 찾을 수 없습니다."); }
}
