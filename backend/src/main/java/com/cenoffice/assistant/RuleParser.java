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

    enum Kind { CREATE_EVENT, LIST_EVENTS, UNSUPPORTED }

    /** start/end 가 null 이면 모르는 것. timeKnown=false 면 날짜만 알고 시각은 모름(시작 칸에는 그날 0시가 들어 있음). */
    record Intent(Kind kind, String title, LocalDateTime start, LocalDateTime end, boolean timeKnown, boolean allDay, String location,
                  boolean shared, List<String> attendeeNames, LocalDate rangeFrom, LocalDate rangeTo, String message) {
        static Intent unsupported(String message) { return new Intent(Kind.UNSUPPORTED, null, null, null, false, false, null, false, List.of(), null, null, message); }
    }

    private static final Pattern CREATE = Pattern.compile("등록|잡아|추가|만들|넣어|예약|스케줄|저장|생성");
    private static final Pattern STRONG_CREATE = Pattern.compile("등록|추가|만들|넣어|생성|저장|잡아\\s*(?:줘|주|줄|라|요)|잡자|예약\\s*(?:해|좀|할)");
    private static final Pattern TOPIC = Pattern.compile("일정|약속|스케줄|회의|미팅|예정|뭐\\s*(?:있|해|하)|바[쁘빠]");
    private static final Pattern LIST = Pattern.compile("뭐|뭘|있어|있나|알려|보여|확인|조회|어때|몇\\s*개|언제");
    /** "회의실 예약해줘"는 아직 지원하지 않는 요청이고, "A회의실에서 회의 잡아줘"의 회의실은 장소다 */
    private static final Pattern ROOM_BOOKING = Pattern.compile("회의실\\s*(?:하나|한\\s*개|을|를|좀)?\\s*(?:예약|잡|빌|확보)");
    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})[-./](\\d{1,2})[-./](\\d{1,2})");
    private static final Pattern MD_DATE = Pattern.compile("(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일");
    private static final Pattern REL_DAY = Pattern.compile("오늘|내일|모레|글피");
    private static final Pattern WEEK_DAY = Pattern.compile("(이번|다음|다다음)\\s*주\\s*([월화수목금토일])\\s*요일?");
    private static final Pattern BARE_DAY = Pattern.compile("(?<![가-힣])([월화수목금토일])요일");
    private static final Pattern WEEK_ONLY = Pattern.compile("(이번|다음|다다음)\\s*주(?![가-힣])");
    private static final Pattern MONTH_ONLY = Pattern.compile("(이번|다음)\\s*달");
    private static final Pattern CLOCK = Pattern.compile("(?<!\\d)(\\d{1,2}):(\\d{2})(?!\\d)");
    private static final Pattern KOR_TIME = Pattern.compile("(오전|오후|아침|저녁|밤|새벽)?\\s*(\\d{1,2})\\s*시(?!간)(?:\\s*(?:(\\d{1,2})\\s*분|(반)))?"); // "2시간"은 시각이 아니라 길이
    private static final Pattern DURATION = Pattern.compile("(\\d{1,2})\\s*시간(?:\\s*(?:동안|짜리|정도))?");
    private static final Pattern ALL_DAY = Pattern.compile("하루\\s*종일|종일");
    private static final Pattern SHARED = Pattern.compile("전체\\s*일정|전사|회사\\s*(?:공통|일정)|공통\\s*일정");
    private static final Pattern LOCATION = Pattern.compile("([가-힣A-Za-z0-9]+(?:실|룸|층|관))\\s*에서");

    static Intent parse(String text, LocalDate today, List<String> directoryNames) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) return Intent.unsupported("무엇을 도와드릴까요? 예: “내일 오후 3시에 팀 회의 잡아줘”");
        boolean create = CREATE.matcher(t).find();
        boolean list = LIST.matcher(t).find();
        if (ROOM_BOOKING.matcher(t).find()) return Intent.unsupported("회의실 예약은 아직 입력창에서 할 수 없습니다. 3D 오피스의 회의실 예약 화면을 이용해 주세요. 일정은 입력창으로 등록할 수 있습니다.");

        Dated d = findDate(t, today);
        // 분명한 등록 말(등록·추가·잡아줘…)이 있으면 등록. 묻는 말(뭐 있어·알려줘…)만 있으면 조회. "잡아둔 일정 보여줘"처럼 섞이면 묻는 쪽을 따른다
        boolean strongCreate = STRONG_CREATE.matcher(t).find();
        if (create && (strongCreate || !list)) return createIntent(t, d, directoryNames);
        // 일정과 관련된 말이 없으면 "오늘 날씨 어때"처럼 상관없는 질문을 일정 조회로 오해하지 않는다
        if (TOPIC.matcher(t).find() && (list || d != null)) return listIntent(t, d, today);
        return Intent.unsupported("지금은 일정 등록과 조회만 도와드릴 수 있습니다. 예: “내일 오후 3시에 팀 회의 잡아줘”, “이번 주 내 일정 알려줘”");
    }

    // ───────────────────────── 조회 ─────────────────────────

    private static Intent listIntent(String t, Dated d, LocalDate today) {
        LocalDate from, to;
        if (d == null) { from = today; to = today.plusDays(6); }
        else { from = d.from; to = d.to; }
        return new Intent(Kind.LIST_EVENTS, null, null, null, false, false, null, SHARED.matcher(t).find(), List.of(), from, to, null);
    }

    // ───────────────────────── 등록 ─────────────────────────

    private static Intent createIntent(String t, Dated d, List<String> names) {
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
        while (c.find()) { int h = Integer.parseInt(c.group(1)), m = Integer.parseInt(c.group(2)); if (h < 24 && m < 60) { spans.add(new int[]{c.start(), c.end(), h * 60 + m}); } }
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

        String title = cleanTitle(rest);
        LocalDateTime start = null, end = null;
        boolean timeKnown = false;
        if (date != null) {
            if (allDay) { start = date.atStartOfDay(); end = date.plusDays(1).atStartOfDay(); timeKnown = true; }
            else if (!times.isEmpty()) {
                start = date.atTime(times.get(0)); timeKnown = true;
                if (times.size() >= 2) { end = date.atTime(times.get(1)); if (!end.isAfter(start)) end = start.plusHours(1); }
                else end = start.plusHours(durationH > 0 ? durationH : 1);
            } else { start = date.atStartOfDay(); end = start.plusHours(1); }
        }
        return new Intent(Kind.CREATE_EVENT, title, start, end, timeKnown, allDay, location, shared, attendees, null, null, null);
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

    private static final Pattern COMMAND = Pattern.compile("일정\\s*(?:을|를|좀)?\\s*(?:등록|추가|잡|만들|넣|생성|저장)\\w*|(?:등록|추가|잡아|만들어|넣어|예약|저장|생성)\\s*(?:해|하|줘|주세요|줄래|좀|해줘|해주세요|해줄래|해라)?\\w*|(?:^|\\s)일정(?=\\s|$)|초대\\w*|참석\\w*|함께|같이");

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
