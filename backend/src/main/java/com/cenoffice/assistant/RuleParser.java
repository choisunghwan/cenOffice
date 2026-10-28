package com.cenoffice.assistant;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 가 연결되지 않았을 때 쓰는 한국어 규칙 해석기. "내일 오후 3시에 팀 회의 잡아줘" 같은 짧고 분명한 문장만 이해하고,
 * 모호하면 추측하지 않고 되묻는다(Intent 의 비어 있는 칸). 결과는 사용자가 확인 카드에서 다시 보고 고칠 수 있다.
 */
final class RuleParser {
    private RuleParser() {}

    /** BOOK_ROOM: 회의실 예약 · DELETE_ITEM: 일정/예약 삭제 · MOVE_EVENT: 일정 시간·제목 변경 */
    enum Kind { CREATE_EVENT, LIST_EVENTS, LIST_TASKS, LIST_PENDING, BRIEFING, BOOK_ROOM, DELETE_ITEM, MOVE_EVENT, START_MEETING, JOIN_MEETING, UNSUPPORTED }

    /** 변경할 값. 비어 있는(null) 칸은 "그대로 둔다". */
    record MoveSpec(LocalDate date, LocalTime start, LocalTime end, String title) {
        boolean empty() { return date == null && start == null && end == null && (title == null || title.isBlank()); }
    }

    /**
     * start/end 가 null 이면 모르는 것. timeKnown=false 면 날짜만 알고 시각은 모름(시작 칸에는 그날 0시가 들어 있음).
     * roomName: 회의실 이름, 또는 "@large"/"@small"(큰/작은 방 선호). targetQuery: 삭제·변경할 일정을 찾을 제목 일부.
     */
    record Intent(Kind kind, String title, LocalDateTime start, LocalDateTime end, boolean timeKnown, boolean allDay, String location,
                  boolean shared, List<String> attendeeNames, LocalDate rangeFrom, LocalDate rangeTo, String message,
                  String roomName, String targetQuery, MoveSpec move) {
        Intent(Kind kind, String title, LocalDateTime start, LocalDateTime end, boolean timeKnown, boolean allDay, String location,
               boolean shared, List<String> attendeeNames, LocalDate rangeFrom, LocalDate rangeTo, String message) {
            this(kind, title, start, end, timeKnown, allDay, location, shared, attendeeNames, rangeFrom, rangeTo, message, null, null, null);
        }
        static Intent unsupported(String message) { return new Intent(Kind.UNSUPPORTED, null, null, null, false, false, null, false, List.of(), null, null, message); }
        /** 별다른 값이 필요 없는 읽기 전용 요청(할 일·확정할 회의록·오늘 브리핑) */
        static Intent of(Kind kind) { return new Intent(kind, null, null, null, false, false, null, false, List.of(), null, null, null); }
    }

    private static final Pattern CREATE = Pattern.compile("등록|잡아|추가|만들|넣어|예약|스케줄|저장|생성");
    private static final Pattern STRONG_CREATE = Pattern.compile("등록|추가|만들|넣어|생성|저장|잡아\\s*(?:줘|주|줄|라|요)|잡자|예약\\s*(?:해|좀|할)");
    private static final Pattern TOPIC = Pattern.compile("일정|약속|스케줄|회의|미팅|예정|뭐\\s*(?:있|해|하)|바[쁘빠]");
    private static final Pattern LIST = Pattern.compile("뭐|뭘|있어|있나|알려|보여|확인|조회|어때|몇\\s*개|언제");
    /** "회의실 예약해줘"는 방을 잡는 요청이고, "A회의실에서 회의 잡아줘"의 회의실은 장소다 */
    private static final Pattern ROOM_BOOKING = Pattern.compile("회의실\\s*(?:[A-Za-z0-9가-힣]\\s*)?(?:하나|한\\s*개|을|를|좀|도)?\\s*(?:예약|잡|빌|확보)");
    private static final Pattern BRIEF = Pattern.compile("브리핑|오늘\\s*(?:뭐|무엇)\\s*(?:해야|할|있)|오늘\\s*(?:해야|할\\s*일)|오늘\\s*현황|하루\\s*요약");
    private static final Pattern TASKS = Pattern.compile("할\\s*일|할일|해야\\s*(?:할|돼|되|하)|투두|todo");
    private static final Pattern PENDING_MINUTES = Pattern.compile("회의록.*(?:확정|대기|남은|남았|미확정|검토|안\\s*된)|(?:확정|대기|남은|미확정).*회의록");
    private static final Pattern DELETE =Pattern.compile("삭제|지워|지우|취소|없애");
    private static final Pattern MOVE = Pattern.compile("옮겨|옮기|미뤄|미루|당겨|앞당|연기|변경|바꿔|바꾸|수정");
    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})[-./](\\d{1,2})[-./](\\d{1,2})");
    private static final Pattern MD_DATE = Pattern.compile("(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일");
    private static final Pattern REL_DAY = Pattern.compile("오늘|내일|모레|글피");
    private static final Pattern WEEK_DAY = Pattern.compile("(이번|다음|다다음)\\s*주\\s*([월화수목금토일])\\s*요일?");
    private static final Pattern BARE_DAY = Pattern.compile("(?<![가-힣])([월화수목금토일])요일");
    private static final Pattern WEEK_ONLY = Pattern.compile("(이번|다음|다다음)\\s*주(?![가-힣])");
    /** 화상회의 참여 코드(32자리 16진수) */
    private static final Pattern MEETING_CODE = Pattern.compile("(?<![0-9a-fA-F])[0-9a-fA-F]{32}(?![0-9a-fA-F])");
    /** "회의 시작해줘", "화상회의 열어줘": 지금 모이는 회의를 만드는 말(날짜·시각이 없을 때만 일정이 아니라 회의 시작으로 본다) */
    private static final Pattern START_MEETING = Pattern.compile("(?:회의|미팅)\\s*(?:를|을|좀|하나)?\\s*(?:시작|열어|열자|열기|개설)|화상\\s*(?:회의|미팅)\\s*(?:를|을|좀)?\\s*(?:만들|열|시작)");
    private static final Pattern START_COMMAND = Pattern.compile("\\s*(?:을|를|좀|하나)?\\s*(?:시작|열어|열자|열기|개설|만들|열)\\S*\\s*$");
    private static final Pattern MONTH_ONLY =Pattern.compile("(이번|다음)\\s*달");
    private static final Pattern CLOCK = Pattern.compile("(?<!\\d)(\\d{1,2}):(\\d{2})(?!\\d)");
    private static final Pattern KOR_TIME = Pattern.compile("(오전|오후|아침|저녁|밤|새벽)?\\s*(\\d{1,2})\\s*시(?!간)(?:\\s*(?:(\\d{1,2})\\s*분|(반)))?"); // "2시간"은 시각이 아니라 길이
    private static final Pattern DURATION = Pattern.compile("(\\d{1,2})\\s*시간(?:\\s*(?:동안|짜리|정도))?");
    private static final Pattern ALL_DAY = Pattern.compile("하루\\s*종일|종일");
    private static final Pattern SHARED = Pattern.compile("전체\\s*일정|전사|회사\\s*(?:공통|일정)|공통\\s*일정");
    private static final Pattern LOCATION = Pattern.compile("([가-힣A-Za-z0-9]+(?:실|룸|층|관))\\s*에서");
    private static final Pattern LARGE_ROOM = Pattern.compile("(?:큰|넓은|대)\\s*회의실");
    private static final Pattern SMALL_ROOM = Pattern.compile("(?:작은|좁은|소)\\s*회의실");

    static Intent parse(String text, LocalDate today, List<String> directoryNames) { return parse(text, today, directoryNames, List.of()); }

    static Intent parse(String text, LocalDate today, List<String> directoryNames, List<String> roomNames) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) return Intent.unsupported("무엇을 도와드릴까요? 예: “내일 오후 3시에 팀 회의 잡아줘”");
        var code = MEETING_CODE.matcher(t);
        if (code.find()) return new Intent(Kind.JOIN_MEETING, null, null, null, false, false, null, false, List.of(), null, null, null, null, code.group().toLowerCase(), null);
        boolean create = CREATE.matcher(t).find();
        boolean list = LIST.matcher(t).find();
        boolean strongCreate = STRONG_CREATE.matcher(t).find();
        Dated d = findDate(t, today);

        // 삭제·변경은 등록 말이 없을 때만. "회의실 예약 취소해줘"는 삭제, "회의 3시로 옮겨줘"는 변경
        boolean booking = ROOM_BOOKING.matcher(t).find() || (t.contains("예약") && mentionsRoom(t, roomNames)); // "A회의실 내일 3시 예약"처럼 말이 떨어져 있어도
        if (DELETE.matcher(t).find() && !strongCreate) return deleteIntent(t, d);
        if (MOVE.matcher(t).find() && !strongCreate && !booking) return moveIntent(t, d, directoryNames);
        if (booking) return bookRoomIntent(t, d, directoryNames, roomNames);
        if (START_MEETING.matcher(t).find() && d == null && !KOR_TIME.matcher(t).find() && !CLOCK.matcher(t).find()) return startMeetingIntent(t);

        // 내 상황을 묻는 말: 오늘 브리핑 · 확정할 회의록 · 할 일 (읽기 전용 답변)
        if (!strongCreate) {
            if (BRIEF.matcher(t).find()) return Intent.of(Kind.BRIEFING);
            if (PENDING_MINUTES.matcher(t).find()) return Intent.of(Kind.LIST_PENDING);
            if (TASKS.matcher(t).find()) return Intent.of(Kind.LIST_TASKS);
        } else if (TASKS.matcher(t).find() && !TOPIC.matcher(t).find()) {
            return Intent.unsupported("할 일은 회의록을 확정할 때 담당자로 지정되어 생깁니다. 개인 할 일을 직접 등록하는 기능은 아직 없습니다. 날짜가 있는 일은 일정으로 등록해 드릴 수 있어요.");
        }

        // 분명한 등록 말(등록·추가·잡아줘…)이 있으면 등록. 묻는 말(뭐 있어·알려줘…)만 있으면 조회. "잡아둔 일정 보여줘"처럼 섞이면 묻는 쪽을 따른다
        if (create && (strongCreate || !list)) return createIntent(t, d, directoryNames);
        // 일정과 관련된 말이 없으면 "오늘 날씨 어때"처럼 상관없는 질문을 일정 조회로 오해하지 않는다
        if (TOPIC.matcher(t).find() && (list || d != null)) return listIntent(t, d, today);
        return Intent.unsupported(""); // 안내 문구는 서비스가 상황(AI 연결 여부)에 맞게 붙인다
    }

    /** 말에서 명령 부분을 떼고 남은 말이 회의 이름이다. "회의"·"화상회의"처럼 이름이 아닌 말이면 이름 없음(서비스가 기본 이름을 쓴다). */
    private static Intent startMeetingIntent(String t) {
        String name = START_COMMAND.matcher(t).replaceFirst("").strip();
        if (name.matches("(?:화상\\s*)?(?:회의|미팅)?")) name = "";
        return new Intent(Kind.START_MEETING, name.isEmpty() ? null : name, null, null, false, false, null, false, List.of(), null, null, null);
    }

    private static boolean mentionsRoom(String t, List<String> roomNames) {
        if (LARGE_ROOM.matcher(t).find() || SMALL_ROOM.matcher(t).find()) return true;
        for (String rn : roomNames) {
            String key = rn == null ? "" : rn.replace("회의실", "").strip();
            if (!key.isEmpty() && Pattern.compile("(?i)(?:회의실\\s*" + Pattern.quote(key) + "|" + Pattern.quote(key) + "\\s*회의실)").matcher(t).find()) return true;
        }
        return false;
    }

    // ───────────────────────── 조회 ─────────────────────────

    private static Intent listIntent(String t, Dated d, LocalDate today) {
        LocalDate from, to;
        if (d == null) { from = today; to = today.plusDays(6); }
        else { from = d.from; to = d.to; }
        return new Intent(Kind.LIST_EVENTS, null, null, null, false, false, null, SHARED.matcher(t).find(), List.of(), from, to, null);
    }

    // ───────────────────────── 삭제·변경 ─────────────────────────

    private static final Pattern DELETE_WORDS = Pattern.compile("(?<![가-힣A-Za-z0-9])(?:삭제|지워|지우|취소|없애)\\S*|(?<![가-힣])(?:일정|예약|전부|모두|다)(?![가-힣])|(?:해줘|해주세요|해|줘|주세요)(?=\\s|$)");

    private static Intent deleteIntent(String t, Dated d) {
        String rest = d != null ? t.replace(d.matched, " ") : t;
        rest = DELETE_WORDS.matcher(rest).replaceAll(" ");
        String query = cleanTitle(KOR_TIME.matcher(CLOCK.matcher(rest).replaceAll(" ")).replaceAll(" "));
        return new Intent(Kind.DELETE_ITEM, null, null, null, false, false, null, SHARED.matcher(t).find(), List.of(), d == null ? null : d.from, d == null ? null : d.to, null, null, query, null);
    }

    private static final Pattern MOVE_WORDS = Pattern.compile("(?<![가-힣A-Za-z0-9])(?:옮겨|옮기|미뤄|미루|당겨|앞당|연기|변경|바꿔|바꾸|수정)\\S*|(?<![가-힣])(?:일정|으로|로)(?![가-힣])");

    private static Intent moveIntent(String t, Dated d, List<String> names) {
        Parts p = parts(t, d, names, List.of(), false);
        // 새 값은 문장 속 날짜·시각. 나머지 낱말이 일정을 찾는 제목이다
        String rest = MOVE_WORDS.matcher(p.rest).replaceAll(" ");
        String query = cleanTitle(rest);
        LocalTime s = p.times.isEmpty() ? null : p.times.get(0), e = p.times.size() >= 2 ? p.times.get(1) : null;
        MoveSpec move = new MoveSpec(p.date, s, e, null);
        return new Intent(Kind.MOVE_EVENT, null, null, null, false, p.allDay, null, false, List.of(), null, null, null, null, query, move);
    }

    // ───────────────────────── 등록·회의실 ─────────────────────────

    private static Intent createIntent(String t, Dated d, List<String> names) {
        Parts p = parts(t, d, names, List.of(), true);
        return build(Kind.CREATE_EVENT, p, null);
    }

    private static Intent bookRoomIntent(String t, Dated d, List<String> names, List<String> roomNames) {
        Parts p = parts(t, d, names, roomNames, true);
        return build(Kind.BOOK_ROOM, p, p.room);
    }

    private static Intent build(Kind kind, Parts p, String room) {
        LocalDateTime start = null, end = null;
        boolean timeKnown = false;
        if (p.date != null) {
            if (p.allDay) { start = p.date.atStartOfDay(); end = p.date.plusDays(1).atStartOfDay(); timeKnown = true; }
            else if (!p.times.isEmpty()) {
                start = p.date.atTime(p.times.get(0)); timeKnown = true;
                if (p.times.size() >= 2) { end = p.date.atTime(p.times.get(1)); if (!end.isAfter(start)) end = start.plusHours(1); }
                else end = start.plusHours(p.durationH > 0 ? p.durationH : 1);
            } else { start = p.date.atStartOfDay(); end = start.plusHours(1); }
        }
        return new Intent(kind, p.title, start, end, timeKnown, p.allDay, p.location, p.shared, p.attendees, null, null, null, room, null, null);
    }

    /** 문장에서 뽑아낸 조각들 */
    private record Parts(LocalDate date, boolean allDay, List<LocalTime> times, int durationH, String location, boolean shared, List<String> attendees, String room, String title, String rest) {}

    private static Parts parts(String t, Dated d, List<String> names, List<String> roomNames, boolean wantTitle) {
        String rest = t;
        LocalDate date = d != null && !d.range ? d.from : null;
        if (d != null) rest = rest.replace(d.matched, " ");
        boolean allDay = ALL_DAY.matcher(rest).find();
        rest = ALL_DAY.matcher(rest).replaceAll(" ");

        // 시각: 한글 "오후 3시 30분", "3시 반", 숫자 "15:30". 두 개 이상이면 앞이 시작, 뒤가 종료
        List<LocalTime> times = new ArrayList<>();
        StringBuilder cleaned = new StringBuilder();
        List<int[]> spans = new ArrayList<>();
        Matcher c = CLOCK.matcher(rest);
        while (c.find()) { int h = Integer.parseInt(c.group(1)), m = Integer.parseInt(c.group(2)); if (h < 24 && m < 60) spans.add(new int[]{c.start(), c.end(), h * 60 + m}); }
        Matcher k = KOR_TIME.matcher(rest);
        while (k.find()) {
            int h = Integer.parseInt(k.group(2)); if (h > 24) continue;
            int m = k.group(3) != null ? Integer.parseInt(k.group(3)) : (k.group(4) != null ? 30 : 0); if (m > 59) continue;
            spans.add(new int[]{k.start(), k.end(), to24(k.group(1), h) * 60 + m});
        }
        spans.sort((a, b) -> Integer.compare(a[0], b[0]));
        int cursor = 0;
        for (int[] s : spans) { if (s[0] < cursor) continue; times.add(LocalTime.of(s[2] / 60 % 24, s[2] % 60)); cleaned.append(rest, cursor, s[0]).append(' '); cursor = s[1]; }
        cleaned.append(rest.substring(cursor));
        rest = cleaned.toString();

        int durationH = 0;
        Matcher du = DURATION.matcher(rest);
        if (du.find()) { durationH = Integer.parseInt(du.group(1)); rest = du.replaceFirst(" "); }

        // 회의실: 이름이 문장에 있으면 방을 가리키는 말, 큰/작은 방 선호도 읽는다
        String room = null;
        for (String rn : roomNames) {
            String key = rn == null ? "" : rn.replace("회의실", "").strip();
            if (key.isEmpty()) continue;
            Matcher rm = Pattern.compile("(?i)(?:회의실\\s*" + Pattern.quote(key) + "|" + Pattern.quote(key) + "\\s*회의실)").matcher(rest);
            if (rm.find()) { room = rn; rest = rm.replaceFirst(" "); break; }
        }
        if (room == null) {
            Matcher lg = LARGE_ROOM.matcher(rest), sm = SMALL_ROOM.matcher(rest);
            if (lg.find()) { room = "@large"; rest = lg.replaceFirst(" "); }
            else if (sm.find()) { room = "@small"; rest = sm.replaceFirst(" "); }
        }

        String location = null;
        Matcher lo = LOCATION.matcher(rest);
        if (lo.find()) { location = lo.group(1); rest = lo.replaceFirst(" "); }

        boolean shared = SHARED.matcher(rest).find();
        rest = SHARED.matcher(rest).replaceAll(" ");

        // 참석자: 디렉터리 이름이 문장에 들어 있으면 참석자로 보고 제목에서는 뺀다
        List<String> attendees = new ArrayList<>();
        for (String n : names) {
            if (n == null || n.strip().length() < 2) continue;
            Matcher nm = Pattern.compile(Pattern.quote(n.strip()) + "\\s*(님|씨)?").matcher(rest);
            if (nm.find()) { attendees.add(n.strip()); rest = nm.replaceAll(" "); }
        }
        return new Parts(date, allDay, times, durationH, location, shared, attendees, room, wantTitle ? cleanTitle(rest) : null, rest);
    }

    /** 오전/오후 표시가 없으면 업무 시간 기준으로 본다: 1~6시는 오후, 7~11시는 오전 */
    private static int to24(String marker, int h) {
        if (marker == null) return h >= 1 && h <= 6 ? h + 12 : h;
        return switch (marker) {
            case "오후", "저녁" -> h < 12 ? h + 12 : h;
            case "밤" -> h < 12 ? (h >= 6 ? h + 12 : h) : h;
            default -> h == 12 ? 0 : h; // 오전·아침·새벽
        };
    }

    private static final Pattern COMMAND = Pattern.compile("일정\\s*(?:을|를|좀)?\\s*(?:등록|추가|잡|만들|넣|생성|저장)\\w*|(?<![가-힣A-Za-z0-9])(?:(?:등록|추가|잡아|만들어|넣어|예약|저장|생성)\\s*(?:해|하|줘|주세요|줄래|좀|해줘|해주세요|해줄래|해라)?\\w*|초대\\w*|참석\\w*)|(?:^|\\s)일정(?=\\s|$)|함께|같이|회의실(?:을|를|도|은|는|이)?|(?<![가-힣])(?:하나|한\\s*개)(?![가-힣])");

    private static String cleanTitle(String s) {
        String r = COMMAND.matcher(s).replaceAll(" ");
        r = r.replaceAll("[\\p{Punct}&&[^()\\-]]", " ");
        r = r.replaceAll("(^|\\s)(에|에서|로|으로|까지|부터|을|를|이|가|은|는|랑|이랑|하고|와|과|에게|한테|좀|해|해줘|줘)(?=\\s|$)", " ");
        r = r.replaceAll("\\s+", " ").strip();
        r = r.replaceAll("^(에|에서|로|으로|까지|부터|을|를|와|과|랑|하고)\\s+", "");
        return r;
    }

    // ───────────────────────── 날짜 ─────────────────────────

    /** range=true 면 여러 날(이번 주·이번 달)을 뜻한다. matched 는 문장에서 지울 원문 */
    private record Dated(LocalDate from, LocalDate to, boolean range, String matched) {}

    private static Dated findDate(String t, LocalDate today) {
        Matcher m = ISO_DATE.matcher(t);
        if (m.find()) { LocalDate d = safe(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))); if (d != null) return one(d, m.group()); }
        m = MD_DATE.matcher(t);
        if (m.find()) {
            int mo = Integer.parseInt(m.group(1)), da = Integer.parseInt(m.group(2));
            LocalDate d = safe(today.getYear(), mo, da);
            if (d != null && d.isBefore(today.minusDays(30))) d = safe(today.getYear() + 1, mo, da); // 한참 지난 날짜는 내년으로 본다
            if (d != null) return one(d, m.group());
        }
        m = WEEK_DAY.matcher(t);
        if (m.find()) {
            int add = switch (m.group(1)) { case "이번" -> 0; case "다음" -> 7; default -> 14; };
            LocalDate monday = today.with(DayOfWeek.MONDAY).plusDays(add);
            return one(monday.plusDays(dow(m.group(2)).getValue() - 1), m.group());
        }
        m = REL_DAY.matcher(t);
        if (m.find()) return one(today.plusDays(switch (m.group()) { case "오늘" -> 0; case "내일" -> 1; case "모레" -> 2; default -> 3; }), m.group());
        m = BARE_DAY.matcher(t);
        if (m.find()) {
            LocalDate d = today;
            while (d.getDayOfWeek() != dow(m.group(1))) d = d.plusDays(1);
            return one(d, m.group());
        }
        m = WEEK_ONLY.matcher(t);
        if (m.find()) {
            int add = switch (m.group(1)) { case "이번" -> 0; case "다음" -> 7; default -> 14; };
            LocalDate monday = today.with(DayOfWeek.MONDAY).plusDays(add);
            return new Dated(monday, monday.plusDays(6), true, m.group());
        }
        m = MONTH_ONLY.matcher(t);
        if (m.find()) {
            LocalDate first = today.withDayOfMonth(1).plusMonths(m.group(1).equals("다음") ? 1 : 0);
            return new Dated(first, first.plusMonths(1).minusDays(1), true, m.group());
        }
        return null;
    }

    private static Dated one(LocalDate d, String matched) { return new Dated(d, d, false, matched); }

    private static LocalDate safe(int y, int mo, int d) { try { return LocalDate.of(y, mo, d); } catch (RuntimeException e) { return null; } }

    private static DayOfWeek dow(String k) {
        return switch (k) { case "월" -> DayOfWeek.MONDAY; case "화" -> DayOfWeek.TUESDAY; case "수" -> DayOfWeek.WEDNESDAY; case "목" -> DayOfWeek.THURSDAY;
            case "금" -> DayOfWeek.FRIDAY; case "토" -> DayOfWeek.SATURDAY; default -> DayOfWeek.SUNDAY; };
    }
}
