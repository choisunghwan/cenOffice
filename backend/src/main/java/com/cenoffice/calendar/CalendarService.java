package com.cenoffice.calendar;

import com.cenoffice.common.api.ApiException;
import com.cenoffice.common.notify.NotificationService;
import com.cenoffice.common.repo.UserRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 달력. 한 화면에서 일정·회의실 예약·내 할 일 기한을 모아서 보여 준다.
 * - 전체(SHARED): 회사 공통 일정. 모두 볼 수 있고, 관리자와 일정 관리자(팀장 등)만 만들고 고친다.
 * - 개인(PERSONAL): 내가 만들었거나 초대받은 일정 + 내가 한 회의실 예약 + 기한이 날짜인 내 할 일(확정된 회의록).
 * 예약과 할 일은 원본이 따로 있으므로 복사하지 않고 조회할 때 합치며, 달력에서는 수정할 수 없다(원래 화면에서 바꾼다).
 */
@Service
@Transactional
public class CalendarService {
    public record Person(String username, String displayName) {}
    /** kind: EVENT | RESERVATION | TASK. id 는 종류별로 겹치지 않는 문자열(e:1, r:2, t:3) */
    public record Entry(String kind, String id, Long eventId, String scope, String title, String description, String location,
                        Instant startAt, Instant endAt, boolean allDay, String createdBy, String createdByName,
                        List<Person> attendees, boolean editable) {}
    public record Feed(List<Entry> entries, boolean canManageShared) {}
    public record Request(String scope, @NotBlank String title, String description, String location,
                          @NotNull Instant startAt, @NotNull Instant endAt, boolean allDay, List<String> attendees) {}

    static final int MAX_ATTENDEES = 50, MAX_RANGE_DAYS = 62, MAX_EVENT_DAYS = 60;
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("M월 d일 HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M월 d일");

    private final JdbcTemplate db;
    private final UserRepository users;
    private final NotificationService notifications;

    public CalendarService(JdbcTemplate db, UserRepository users, NotificationService notifications) {
        this.db = db; this.users = users; this.notifications = notifications;
    }

    public boolean canManageShared(String username, boolean admin) {
        return admin || users.findByUsername(username).map(u -> u.isCalendarManager()).orElse(false);
    }

    // ───────────────────────── 조회 ─────────────────────────

    @Transactional(readOnly = true)
    public Feed feed(String me, boolean admin, String scope, Instant from, Instant to) {
        if (from == null || to == null || !to.isAfter(from)) throw new ApiException(HttpStatus.BAD_REQUEST, "조회 기간이 올바르지 않습니다");
        if (Duration.between(from, to).toDays() > MAX_RANGE_DAYS) throw new ApiException(HttpStatus.BAD_REQUEST, "한 번에 최대 " + MAX_RANGE_DAYS + "일까지 조회할 수 있습니다");
        boolean manage = canManageShared(me, admin);
        boolean shared = "shared".equalsIgnoreCase(scope);
        List<Entry> out = new ArrayList<>();
        if (shared) {
            out.addAll(events("e.scope='SHARED'", List.of(), from, to, me, manage));
        } else {
            out.addAll(events("e.scope='PERSONAL' and (e.created_by=? or exists(select 1 from calendar_attendees a where a.event_id=e.id and a.username=?))",
                List.of(me, me), from, to, me, manage));
            out.addAll(reservations(me, from, to));
            out.addAll(tasks(me, from, to));
        }
        out.sort((a, b) -> { int c = a.startAt().compareTo(b.startAt()); return c != 0 ? c : a.id().compareTo(b.id()); });
        return new Feed(out, manage);
    }

    private List<Entry> events(String cond, List<Object> args, Instant from, Instant to, String me, boolean manage) {
        List<Object> params = new ArrayList<>();
        params.add(Timestamp.from(to)); params.add(Timestamp.from(from)); params.addAll(args);
        record Row(long id, String scope, String title, String description, String location, Instant start, Instant end, boolean allDay, String createdBy) {}
        List<Row> rows = db.query("select e.id,e.scope,e.title,e.description,e.location,e.start_at,e.end_at,e.all_day,e.created_by from calendar_events e where e.start_at<? and e.end_at>? and " + cond + " order by e.start_at, e.id",
            (r, i) -> new Row(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getTimestamp(6).toInstant(), r.getTimestamp(7).toInstant(), r.getBoolean(8), r.getString(9)), params.toArray());
        if (rows.isEmpty()) return List.of();
        Map<Long, List<String>> attendees = attendeesOf(rows.stream().map(Row::id).toList());
        Set<String> all = new LinkedHashSet<>();
        rows.forEach(r -> all.add(r.createdBy()));
        attendees.values().forEach(all::addAll);
        Map<String, String> names = names(all);
        List<Entry> out = new ArrayList<>();
        for (Row r : rows) {
            List<Person> people = attendees.getOrDefault(r.id(), List.of()).stream().map(u -> new Person(u, names.getOrDefault(u, u))).toList();
            boolean editable = "SHARED".equals(r.scope()) ? manage : r.createdBy().equals(me);
            out.add(new Entry("EVENT", "e:" + r.id(), r.id(), r.scope(), r.title(), r.description(), r.location(), r.start(), r.end(), r.allDay(),
                r.createdBy(), names.getOrDefault(r.createdBy(), r.createdBy()), people, editable));
        }
        return out;
    }

    private List<Entry> reservations(String me, Instant from, Instant to) {
        return db.query("select r.id,r.title,r.start_at,r.end_at,m.name from reservations r left join meeting_rooms m on m.id=r.room_id where r.reserved_by=? and r.start_at<? and r.end_at>? order by r.start_at",
            (r, i) -> new Entry("RESERVATION", "r:" + r.getLong(1), null, "PERSONAL", r.getString(2), null, r.getString(5),
                r.getTimestamp(3).toInstant(), r.getTimestamp(4).toInstant(), false, me, null, List.of(), false),
            me, Timestamp.from(to), Timestamp.from(from));
    }

    /** 확정된 회의록의 내 할 일 중 기한이 날짜(yyyy-mm-dd)이고 아직 안 끝난 것. 글자로 적힌 기한("금요일까지")은 날짜가 아니라 제외한다. */
    private List<Entry> tasks(String me, Instant from, Instant to) {
        record Row(long id, String content, String due, String meeting) {}
        List<Row> rows = db.query("select a.id,a.content,a.due_text,m.title from meeting_action_items a join collaboration_meetings m on m.id=a.meeting_id "
                + "join meeting_minutes mm on mm.meeting_id=a.meeting_id and mm.status='CONFIRMED' where a.assignee_username=? and a.done=false and a.due_text is not null",
            (r, i) -> new Row(r.getLong(1), r.getString(2), r.getString(3), r.getString(4)), me);
        List<Entry> out = new ArrayList<>();
        for (Row r : rows) {
            LocalDate day;
            try { day = LocalDate.parse(r.due().strip()); } catch (RuntimeException e) { continue; }
            Instant start = day.atStartOfDay(ZONE).toInstant(), end = day.plusDays(1).atStartOfDay(ZONE).toInstant();
            if (!start.isBefore(to) || !end.isAfter(from)) continue;
            out.add(new Entry("TASK", "t:" + r.id(), null, "PERSONAL", r.content(), "회의: " + r.meeting(), null, start, end, true, me, null, List.of(), false));
        }
        return out;
    }

    /** 일정을 만들 때 참석자로 고를 수 있는 사용자 목록 */
    @Transactional(readOnly = true)
    public List<Person> directory() {
        return db.query("select username, display_name from users order by display_name, username", (r, i) -> new Person(r.getString(1), r.getString(2)));
    }

    // ───────────────────────── 등록·수정·삭제 ─────────────────────────

    public Entry create(String me, boolean admin, Request r) {
        Valid v = validate(me, admin, r, null);
        var keys = new GeneratedKeyHolder();
        Timestamp now = Timestamp.from(Instant.now());
        db.update(con -> {
            var ps = con.prepareStatement("insert into calendar_events(scope,title,description,location,start_at,end_at,all_day,created_by,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?)", new String[]{"id"});
            ps.setString(1, v.scope); ps.setString(2, v.title); ps.setString(3, v.description); ps.setString(4, v.location);
            ps.setTimestamp(5, Timestamp.from(r.startAt())); ps.setTimestamp(6, Timestamp.from(r.endAt())); ps.setBoolean(7, r.allDay());
            ps.setString(8, me); ps.setTimestamp(9, now); ps.setTimestamp(10, now);
            return ps;
        }, keys);
        long id = keys.getKey().longValue();
        for (String u : v.attendees) db.update("insert into calendar_attendees(event_id,username) values(?,?)", id, u);
        invite(v.attendees, id, v.title, r);
        return load(id, me, admin);
    }

    public Entry update(String me, boolean admin, long id, Request r) {
        var cur = existing(id);
        requireEditable(cur, me, admin);
        Valid v = validate(me, admin, new Request(cur.scope, r.title(), r.description(), r.location(), r.startAt(), r.endAt(), r.allDay(), r.attendees()), cur.scope);
        db.update("update calendar_events set title=?,description=?,location=?,start_at=?,end_at=?,all_day=?,updated_at=? where id=?",
            v.title, v.description, v.location, Timestamp.from(r.startAt()), Timestamp.from(r.endAt()), r.allDay(), Timestamp.from(Instant.now()), id);
        Set<String> before = new LinkedHashSet<>(attendeesOf(List.of(id)).getOrDefault(id, List.of()));
        db.update("delete from calendar_attendees where event_id=?", id);
        for (String u : v.attendees) db.update("insert into calendar_attendees(event_id,username) values(?,?)", id, u);
        invite(v.attendees.stream().filter(u -> !before.contains(u)).toList(), id, v.title, r); // 새로 추가된 사람에게만 알린다
        return load(id, me, admin);
    }

    public void delete(String me, boolean admin, long id) {
        requireEditable(existing(id), me, admin);
        db.update("delete from calendar_events where id=?", id); // 참석자는 ON DELETE CASCADE
    }

    // ───────────────────────── 내부 ─────────────────────────

    private record Existing(String scope, String createdBy) {}
    private record Valid(String scope, String title, String description, String location, List<String> attendees) {}

    private Existing existing(long id) {
        var rows = db.query("select scope, created_by from calendar_events where id=?", (r, i) -> new Existing(r.getString(1), r.getString(2)), id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "일정을 찾을 수 없습니다");
        return rows.getFirst();
    }

    private void requireEditable(Existing e, String me, boolean admin) {
        boolean ok = "SHARED".equals(e.scope) ? canManageShared(me, admin) : e.createdBy.equals(me);
        if (!ok) throw new ApiException(HttpStatus.FORBIDDEN, "SHARED".equals(e.scope) ? "전체 일정은 관리자와 일정 관리자만 고칠 수 있습니다" : "본인이 만든 일정만 고칠 수 있습니다");
    }

    private Valid validate(String me, boolean admin, Request r, String fixedScope) {
        String scope = fixedScope != null ? fixedScope : (r.scope() == null ? "PERSONAL" : r.scope().strip().toUpperCase());
        if (!scope.equals("PERSONAL") && !scope.equals("SHARED")) throw new ApiException(HttpStatus.BAD_REQUEST, "일정 종류가 올바르지 않습니다");
        if (scope.equals("SHARED") && !canManageShared(me, admin)) throw new ApiException(HttpStatus.FORBIDDEN, "전체 일정은 관리자와 일정 관리자만 만들 수 있습니다");
        String title = r.title() == null ? "" : r.title().replaceAll("\\p{Cntrl}", " ").strip();
        if (title.isEmpty() || title.length() > 200) throw new ApiException(HttpStatus.BAD_REQUEST, "제목은 1~200자로 입력하세요");
        String desc = blankToNull(r.description()), loc = blankToNull(r.location() == null ? null : r.location().replaceAll("\\p{Cntrl}", " "));
        if (desc != null && desc.length() > 2000) throw new ApiException(HttpStatus.BAD_REQUEST, "설명은 2000자까지 입력할 수 있습니다");
        if (loc != null && loc.length() > 200) throw new ApiException(HttpStatus.BAD_REQUEST, "장소는 200자까지 입력할 수 있습니다");
        if (!r.endAt().isAfter(r.startAt())) throw new ApiException(HttpStatus.BAD_REQUEST, "종료 시각은 시작 시각 이후여야 합니다");
        if (Duration.between(r.startAt(), r.endAt()).toDays() > MAX_EVENT_DAYS) throw new ApiException(HttpStatus.BAD_REQUEST, "일정은 최대 " + MAX_EVENT_DAYS + "일까지 잡을 수 있습니다");
        List<String> attendees = new ArrayList<>(new LinkedHashSet<>(r.attendees() == null ? List.<String>of() : r.attendees().stream().filter(u -> u != null).map(String::strip).filter(u -> !u.isEmpty() && !u.equals(me)).toList()));
        if (scope.equals("SHARED") && !attendees.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "전체 일정에는 참석자를 지정하지 않습니다. 모두에게 보입니다");
        if (attendees.size() > MAX_ATTENDEES) throw new ApiException(HttpStatus.BAD_REQUEST, "참석자는 최대 " + MAX_ATTENDEES + "명까지 지정할 수 있습니다");
        if (!attendees.isEmpty()) {
            Set<String> known = names(attendees).keySet();
            for (String u : attendees) if (!known.contains(u)) throw new ApiException(HttpStatus.BAD_REQUEST, "존재하지 않는 사용자가 참석자에 있습니다: " + u);
        }
        return new Valid(scope, title, desc, loc, attendees);
    }

    private void invite(List<String> usernames, long id, String title, Request r) {
        if (usernames.isEmpty()) return;
        String when = r.allDay() ? DAY.format(r.startAt().atZone(ZONE)) + " (종일)" : WHEN.format(r.startAt().atZone(ZONE));
        for (String u : usernames) notifications.notify(u, "EVENT_INVITED", "일정에 초대되었습니다", title + " · " + when, "calendar:" + id);
    }

    private Entry load(long id, String me, boolean admin) {
        List<Entry> found = events("e.id=?", List.of(id), Instant.EPOCH, Instant.parse("9999-01-01T00:00:00Z"), me, canManageShared(me, admin));
        if (found.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "일정을 찾을 수 없습니다");
        return found.getFirst();
    }

    private Map<Long, List<String>> attendeesOf(Collection<Long> ids) {
        String in = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        Map<Long, List<String>> out = new LinkedHashMap<>();
        db.query("select event_id, username from calendar_attendees where event_id in (" + in + ") order by username",
            rs -> { out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2)); }, ids.toArray());
        return out;
    }

    private Map<String, String> names(Collection<String> usernames) {
        Map<String, String> out = new LinkedHashMap<>();
        if (usernames.isEmpty()) return out;
        String in = usernames.stream().map(x -> "?").collect(Collectors.joining(","));
        db.query("select username, display_name from users where username in (" + in + ")", rs -> { out.put(rs.getString(1), rs.getString(2)); }, usernames.toArray());
        return out;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.strip(); }
}
