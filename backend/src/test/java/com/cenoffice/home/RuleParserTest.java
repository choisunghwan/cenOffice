package com.cenoffice.home;

import static org.junit.jupiter.api.Assertions.*;

import com.cenoffice.home.RuleParser.Intent;
import com.cenoffice.home.RuleParser.Kind;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleParserTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 27); // 화요일
    static final List<String> NAMES = List.of("김대리", "박과장");

    Intent p(String s) { return RuleParser.parse(s, TODAY, NAMES); }
    static LocalDateTime at(int mo, int d, int h, int mi) { return LocalDateTime.of(2026, mo, d, h, mi); }

    @Test void startingAMeetingNowIsNotACalendarEvent() {
        assertEquals(Kind.START_MEETING, p("회의 시작해줘").kind());
        assertNull(p("회의 시작해줘").title()); // 이름이 없으면 서비스가 기본 이름을 쓴다
        var named = p("주간 회의 시작해줘");
        assertEquals(Kind.START_MEETING, named.kind());
        assertEquals("주간 회의", named.title());
        assertEquals(Kind.START_MEETING, p("화상회의 열어줘").kind());
    }

    @Test void meetingsWithADateOrTimeStayCalendarEvents() {
        assertEquals(Kind.CREATE_EVENT, p("내일 오후 3시에 팀 회의 잡아줘").kind());
        assertEquals(Kind.CREATE_EVENT, p("내일 회의 만들어줘").kind());
        assertEquals(Kind.BOOK_ROOM, p("회의실 예약해줘").kind());
    }

    @Test void minutesDownloadReadsNameFormatAndDate() {
        var plain = p("주간 팀 회의 회의록 다운로드해줘");
        assertEquals(Kind.DOWNLOAD_MINUTES, plain.kind());
        assertEquals("주간 팀 회의", plain.title());
        assertNull(plain.targetQuery()); // 형식을 말하지 않았다 → 화면이 내 양식 → 회사 양식 → 기본 순으로 정한다
        var word = p("지난주 팀 회의 회의록 워드로 받아줘");
        assertEquals(Kind.DOWNLOAD_MINUTES, word.kind());
        assertEquals("docx", word.targetQuery());
        assertEquals("팀 회의", word.title());
        assertEquals(LocalDate.of(2026, 10, 19), word.rangeFrom()); // 지난주 월요일
        assertEquals(LocalDate.of(2026, 10, 25), word.rangeTo());
        var bare = p("회의록 다운로드");
        assertEquals(Kind.DOWNLOAD_MINUTES, bare.kind());
        assertNull(bare.title()); // 이름을 모르면 서비스가 카드로 묻는다
        assertEquals("pdf", p("어제 회의록 pdf로 다운받고 싶어").targetQuery());
        assertEquals(LocalDate.of(2026, 10, 26), p("어제 회의록 pdf로 다운받고 싶어").rangeFrom());
    }

    @Test void minutesWordsWithoutDownloadStayOtherIntents() {
        assertEquals(Kind.LIST_PENDING, p("확정할 회의록 있어?").kind()); // 다운로드 말이 없으면 기존 동작
        assertNotEquals(Kind.DOWNLOAD_MINUTES, p("내일 오후 3시에 회의 잡아줘").kind());
    }

    @Test void joinCodeStartsAJoin() {
        var i = p("회의 참여해줘 0123456789ABCDEF0123456789abcdef");
        assertEquals(Kind.JOIN_MEETING, i.kind());
        assertEquals("0123456789abcdef0123456789abcdef", i.targetQuery()); // 코드는 소문자로 통일
    }

    @Test void tomorrowAfternoonMeeting() {
        var i = p("내일 오후 3시에 팀 회의 잡아줘");
        assertEquals(Kind.CREATE_EVENT, i.kind());
        assertEquals("팀 회의", i.title());
        assertEquals(at(10, 28, 15, 0), i.start());
        assertEquals(at(10, 28, 16, 0), i.end());
        assertTrue(i.timeKnown());
    }

    @Test void timeRangeAndNextWeekday() {
        var i = p("다음 주 월요일 오전 10시부터 11시 30분까지 주간 보고 등록해줘");
        assertEquals("주간 보고", i.title());
        assertEquals(at(11, 2, 10, 0), i.start());
        assertEquals(at(11, 2, 11, 30), i.end());
    }

    @Test void meridiemDefaultsFollowBusinessHours() {
        assertEquals(at(10, 28, 15, 0), p("내일 3시 회의 잡아줘").start()); // 1~6시는 오후로 본다
        assertEquals(at(10, 28, 9, 0), p("내일 9시 회의 잡아줘").start());
        assertEquals(at(10, 28, 19, 0), p("내일 저녁 7시 회식 잡아줘").start());
        assertEquals(at(10, 28, 14, 30), p("내일 2시 반 면담 잡아줘").start());
        assertEquals(at(10, 28, 16, 45), p("내일 16:45 면담 잡아줘").start());
    }

    @Test void durationHours() {
        var i = p("내일 오전 10시 2시간 워크숍 등록해줘");
        assertEquals(at(10, 28, 12, 0), i.end());
    }

    @Test void allDayAndBareWeekday() {
        var i = p("금요일 종일 워크숍 등록해줘");
        assertTrue(i.allDay());
        assertEquals(at(10, 30, 0, 0), i.start());
        assertEquals(at(10, 31, 0, 0), i.end());
        assertEquals("워크숍", i.title());
    }

    @Test void explicitDates() {
        assertEquals(at(11, 3, 14, 0), p("11월 3일 오후 2시 창립기념 행사 등록해줘").start());
        assertEquals(at(12, 25, 10, 0), p("2026-12-25 오전 10시 송년회 추가해줘").start());
    }

    @Test void missingPiecesAreLeftEmptyInsteadOfGuessed() {
        var noTime = p("내일 팀 회의 잡아줘");
        assertEquals(at(10, 28, 0, 0), noTime.start());
        assertFalse(noTime.timeKnown());
        assertNull(p("팀 회의 잡아줘").start());
        assertTrue(p("내일 오후 3시에 잡아줘").title().isBlank());
    }

    @Test void attendeesAreTakenFromTheDirectoryAndRemovedFromTheTitle() {
        var i = p("내일 오후 2시에 김대리랑 박과장님과 면담 잡아줘");
        assertEquals(List.of("김대리", "박과장"), i.attendeeNames());
        assertEquals("면담", i.title());
    }

    @Test void locationBeforeEseo() {
        var i = p("내일 A회의실에서 3시 주간회의 잡아줘");
        assertEquals("A회의실", i.location());
        assertEquals("주간회의", i.title());
    }

    @Test void sharedEventWording() {
        assertTrue(p("전체 일정으로 11월 3일 오후 2시 창립기념 행사 등록해줘").shared());
        assertFalse(p("내일 오후 3시 팀 회의 잡아줘").shared());
    }

    @Test void listQueries() {
        var week = p("이번 주 내 일정 알려줘");
        assertEquals(Kind.LIST_EVENTS, week.kind());
        assertEquals(LocalDate.of(2026, 10, 26), week.rangeFrom());
        assertEquals(LocalDate.of(2026, 11, 1), week.rangeTo());
        var tomorrow = p("내일 일정 뭐 있어?");
        assertEquals(Kind.LIST_EVENTS, tomorrow.kind());
        assertEquals(LocalDate.of(2026, 10, 28), tomorrow.rangeFrom());
        assertEquals(tomorrow.rangeFrom(), tomorrow.rangeTo());
        var none = p("일정 보여줘");
        assertEquals(TODAY, none.rangeFrom());
        assertEquals(TODAY.plusDays(6), none.rangeTo());
        assertTrue(p("전체 일정 보여줘").shared());
        assertEquals(Kind.LIST_EVENTS, p("잡아둔 일정 뭐 있는지 알려줘").kind()); // 묻는 말이 섞이면 조회
    }

    @Test void unsupportedRequestsGetHonestAnswers() {
        assertEquals(Kind.UNSUPPORTED, p("안녕").kind());
        assertEquals(Kind.UNSUPPORTED, p("   ").kind());
    }

    static final List<String> ROOMS = List.of("회의실 A", "회의실 B");
    Intent r(String s) { return RuleParser.parse(s, TODAY, NAMES, ROOMS); }

    @Test void roomBookingWithoutARoomNameLeavesTheChoiceToTheServer() {
        var i = r("내일 오후 2시에 회의실 예약해줘");
        assertEquals(Kind.BOOK_ROOM, i.kind());
        assertEquals(at(10, 28, 14, 0), i.start());
        assertNull(i.roomName());
        assertTrue(i.title().isBlank());
    }

    @Test void roomNameTitleAndDurationAreRead() {
        var i = r("내일 오후 2시 회의실 A 예약해줘 팀 주간회의");
        assertEquals("회의실 A", i.roomName());
        assertEquals("팀 주간회의", i.title());
        var j = r("A회의실 모레 오전 10시 2시간 예약");
        assertEquals("회의실 A", j.roomName());
        assertEquals(at(10, 29, 12, 0), j.end());
        assertEquals("@large", r("큰 회의실 모레 오전 10시 예약해줘").roomName());
        assertEquals("@small", r("작은 회의실 모레 오전 10시 예약해줘").roomName());
    }

    @Test void roomAsAPlaceStillCreatesAnEventNotAReservation() {
        var i = r("내일 A회의실에서 3시 주간회의 잡아줘");
        assertEquals(Kind.CREATE_EVENT, i.kind());
        assertEquals("A회의실", i.location());
    }

    @Test void deleteFindsTheTargetByTitleAndDate() {
        var i = r("내일 팀 회의 삭제해줘");
        assertEquals(Kind.DELETE_ITEM, i.kind());
        assertEquals("팀 회의", i.targetQuery());
        assertEquals(LocalDate.of(2026, 10, 28), i.rangeFrom());
        var res = r("회의실 예약 취소해줘");
        assertEquals(Kind.DELETE_ITEM, res.kind());
        assertTrue(res.targetQuery().isBlank());
        assertEquals("주간 보고", r("주간 보고 일정 지워줘").targetQuery());
    }

    @Test void moveReadsTheNewTimeAndKeepsTheTitleAsTheSearchText() {
        var i = r("팀 회의 4시로 옮겨줘");
        assertEquals(Kind.MOVE_EVENT, i.kind());
        assertEquals("팀 회의", i.targetQuery());
        assertEquals(java.time.LocalTime.of(16, 0), i.move().start());
        assertNull(i.move().date());
        var j = r("팀 회의 모레 오후 3시로 미뤄줘");
        assertEquals(LocalDate.of(2026, 10, 29), j.move().date());
        assertEquals(java.time.LocalTime.of(15, 0), j.move().start());
        assertTrue(r("팀 회의 옮겨줘").move().empty()); // 언제로 옮길지 없으면 되묻는다
    }

    @Test void deleteAndMoveWordsDoNotHijackNormalCreation() {
        assertEquals(Kind.CREATE_EVENT, r("내일 오후 3시에 취소 정책 검토 회의 잡아줘").kind()); // 등록 말이 있으면 등록
    }

    @Test void statusQuestionsAreAnsweredReadOnly() {
        assertEquals(Kind.LIST_TASKS, r("내 할 일 보여줘").kind());
        assertEquals(Kind.LIST_TASKS, r("해야 할 일 알려줘").kind());
        assertEquals(Kind.LIST_PENDING, r("확정할 회의록 있어?").kind());
        assertEquals(Kind.LIST_PENDING, r("아직 확정 안 된 회의록 보여줘").kind());
        assertEquals(Kind.BRIEFING, r("오늘 브리핑 해줘").kind());
        assertEquals(Kind.BRIEFING, r("오늘 뭐 해야 돼?").kind());
        assertEquals(Kind.LIST_EVENTS, r("오늘 일정 알려줘").kind()); // 일정만 물으면 일정 조회
    }

    @Test void addingAPersonalTaskIsAnsweredHonestly() {
        var i = r("할 일 추가해줘 견적서 작성");
        assertEquals(Kind.UNSUPPORTED, i.kind());
        assertTrue(i.message().contains("회의록"));
    }

    @Test void invalidDatesDoNotCrash() {
        assertNull(p("2월 31일 오후 3시 회의 잡아줘").start());
        assertNull(p("2026-13-45 회의 잡아줘").start());
    }
}
