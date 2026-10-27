package com.cenoffice.assistant;

import static org.junit.jupiter.api.Assertions.*;

import com.cenoffice.assistant.RuleParser.Intent;
import com.cenoffice.assistant.RuleParser.Kind;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleParserTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 27); // 화요일
    static final List<String> NAMES = List.of("김대리", "박과장");

    Intent p(String s) { return RuleParser.parse(s, TODAY, NAMES); }
    static LocalDateTime at(int mo, int d, int h, int mi) { return LocalDateTime.of(2026, mo, d, h, mi); }

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
        var room = p("내일 오후 3시에 회의실 예약해줘");
        assertEquals(Kind.UNSUPPORTED, room.kind());
        assertTrue(room.message().contains("3D 오피스"));
        assertEquals(Kind.UNSUPPORTED, p("안녕").kind());
        assertEquals(Kind.UNSUPPORTED, p("   ").kind());
    }

    @Test void invalidDatesDoNotCrash() {
        assertNull(p("2월 31일 오후 3시 회의 잡아줘").start());
        assertNull(p("2026-13-45 회의 잡아줘").start());
    }
}
