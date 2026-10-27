package com.cenoffice.meeting;

import com.cenoffice.api.ApiException;
import com.cenoffice.api.AuditService;
import com.cenoffice.repo.UserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회의 음성 인식 전문. 오디오는 저장하지 않고 텍스트만 저장하며, 동의한 참석자의 발언만 기록한다. */
@Service
@Transactional
public class TranscriptService {
    public static final int MAX_CONTENT = 600;
    private final JdbcTemplate db;
    private final UserRepository users;
    private final AuditService audit;

    public TranscriptService(JdbcTemplate db, UserRepository users, AuditService audit) {
        this.db = db; this.users = users; this.audit = audit;
    }

    public record Segment(long id, String username, String displayName, String content, Instant spokenAt) {}
    /** recording: 지금 음성 기록에 동의해 기록 중인 참석자 이름(모든 참석자에게 공개해 누가 기록 중인지 알린다) */
    public record Status(boolean consented, int segmentCount, List<String> recording) {}

    @Transactional(readOnly = true)
    public Status status(String meetingId, String actor) {
        requireMember(meetingId, actor);
        int n = db.queryForObject("select count(*) from meeting_transcript where meeting_id=?", Integer.class, meetingId);
        List<String> recording = db.queryForList("select u.display_name from meeting_stt_consent c join users u on u.username=c.username where c.meeting_id=? order by c.consented_at", String.class, meetingId);
        return new Status(consented(meetingId, actor), n, recording);
    }

    /** 참석자가 본인 발언의 음성 인식·기록에 동의한다. 진행 중인 회의에서만 가능. */
    public void consent(String meetingId, String actor, String engine) {
        requireMember(meetingId, actor);
        requireOpen(meetingId);
        String eng = engineName(engine);
        if (!consented(meetingId, actor))
            db.update("insert into meeting_stt_consent values(?,?,?,?)", meetingId, actor, eng, Timestamp.from(Instant.now()));
        audit.log(actor, "MEETING_STT_CONSENT", "meeting:" + meetingId, "음성 인식 기록 동의(" + eng + ")");
    }

    /** 동의를 철회한다. 이미 기록된 발언은 남고, 이후 발언은 기록되지 않는다. */
    public void withdraw(String meetingId, String actor) {
        requireMember(meetingId, actor);
        db.update("delete from meeting_stt_consent where meeting_id=? and username=?", meetingId, actor);
        audit.log(actor, "MEETING_STT_WITHDRAW", "meeting:" + meetingId, "음성 인식 기록 중단");
    }

    public Segment add(String meetingId, String actor, String content, String engine) {
        requireMember(meetingId, actor);
        requireOpen(meetingId);
        if (!consented(meetingId, actor)) throw new ApiException(HttpStatus.FORBIDDEN, "음성 인식 기록에 동의한 참석자만 기록할 수 있습니다");
        String text = content == null ? "" : content.replaceAll("\\p{Cntrl}", " ").strip();
        if (text.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "내용이 비어 있습니다");
        if (text.length() > MAX_CONTENT) text = text.substring(0, MAX_CONTENT);
        if (db.queryForObject("select count(*) from meeting_transcript where meeting_id=? and username=? and spoken_at>?", Integer.class,
                meetingId, actor, Timestamp.from(Instant.now().minusSeconds(10))) >= 20)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 보내 주세요");
        var user = users.findByUsername(actor).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다"));
        Instant now = Instant.now();
        db.update("insert into meeting_transcript(meeting_id,username,display_name,content,engine,spoken_at) values(?,?,?,?,?,?)",
            meetingId, actor, user.getDisplayName(), text, engineName(engine), Timestamp.from(now));
        long id = db.queryForObject("select max(id) from meeting_transcript where meeting_id=? and username=?", Long.class, meetingId, actor);
        return new Segment(id, actor, user.getDisplayName(), text, now);
    }

    /** 참석자만 조회한다. after 이후 새 발언만 가져올 수 있다(실시간 자막용). */
    @Transactional(readOnly = true)
    public List<Segment> list(String meetingId, String actor, long after) {
        requireMember(meetingId, actor);
        return db.query("select id,username,display_name,content,spoken_at from meeting_transcript where meeting_id=? and id>? order by id limit 500",
            (r, i) -> new Segment(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getTimestamp(5).toInstant()), meetingId, after);
    }

    private boolean consented(String meetingId, String actor) {
        return db.queryForObject("select count(*) from meeting_stt_consent where meeting_id=? and username=?", Integer.class, meetingId, actor) > 0;
    }

    private static String engineName(String engine) {
        String e = engine == null ? "" : engine.strip();
        return e.isEmpty() ? "browser" : e.substring(0, Math.min(40, e.length()));
    }

    private void requireMember(String id, String actor) {
        if (db.queryForObject("select count(*) from collaboration_members where meeting_id=? and username=?", Integer.class, id, actor) == 0)
            throw new ApiException(HttpStatus.NOT_FOUND, "참여 가능한 회의를 찾을 수 없습니다");
    }

    private void requireOpen(String id) {
        var state = db.queryForList("select state from collaboration_meetings where id=?", String.class, id);
        if (state.isEmpty() || !"OPEN".equals(state.getFirst())) throw new ApiException(HttpStatus.CONFLICT, "종료된 회의입니다");
    }
}
