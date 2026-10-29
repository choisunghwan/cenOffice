package com.cenoffice.home;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 입력창으로 조작하는 화면 동작: 모양이 분명한 말은 규칙으로 바로 알아본다. */
class UiCommandTest {
    static String id(String text) { return UiCommand.match(text).map(Enum::name).orElse(null); }

    @Test void themeCommands() {
        assertEquals("THEME_DARK", id("다크 모드 전환"));
        assertEquals("THEME_DARK", id("다크모드로 바꿔줘"));
        assertEquals("THEME_DARK", id("화면 어둡게 해줘"));
        assertEquals("THEME_LIGHT", id("라이트 모드로 전환해줘"));
        assertEquals("THEME_LIGHT", id("화면 밝게"));
        assertEquals("THEME_TOGGLE", id("화면 모드 바꿔줘"));
    }

    @Test void sidebarAndChatCommands() {
        assertEquals("SIDEBAR_COLLAPSE", id("사이드바 접어줘"));
        assertEquals("SIDEBAR_EXPAND", id("사이드바 펼쳐줘"));
        assertEquals("NEW_CHAT", id("새 채팅 시작해줘"));
        assertEquals("LOGOUT", id("로그아웃 해줘"));
    }

    @Test void navigationCommands() {
        assertEquals("NAV_CALENDAR", id("일정 화면 열어줘"));
        assertEquals("NAV_CALENDAR", id("캘린더로 이동해줘"));
        assertEquals("NAV_MEETINGS", id("회의 화면으로 이동"));
        assertEquals("NAV_TOOLS", id("도구 화면 열어줘"));
        assertEquals("NAV_MY_TOOLS", id("내 도구 화면 보여줘"));
        assertEquals("NAV_CREATE_TOOL", id("도구 만들기 화면 열어줘"));
        assertEquals("NAV_SETTINGS", id("설정 열어줘"));
        assertEquals("NAV_HOME", id("홈으로 이동해줘"));
        assertEquals("NAV_REVIEW", id("공개 승인 화면 열어줘"));
        assertEquals("MODE_OFFICE", id("3D 오피스로 전환해줘"));
        assertEquals("MODE_WORKSPACE", id("업무 화면으로 돌아가줘"));
    }

    @Test void dataRequestsAreNotScreenCommands() {
        assertNull(id("이번 주 일정 보여줘"));            // 일정을 묻는 말이지 화면 이동이 아니다
        assertNull(id("내일 오후 3시에 팀 회의 잡아줘"));
        assertNull(id("내 할 일 보여줘"));
        assertNull(id("오늘 브리핑 해줘"));
        assertNull(id("쓸 만한 도구 뭐 있어?"));
        assertNull(id("회의 시작해줘"));
    }

    @Test void adminOnlyCommandsAreMarked() {
        assertTrue(UiCommand.NAV_REVIEW.adminOnly());
        assertFalse(UiCommand.THEME_DARK.adminOnly());
        assertEquals(Optional.of(UiCommand.THEME_DARK), UiCommand.byId("THEME_DARK"));
        assertEquals(Optional.empty(), UiCommand.byId("FORMAT_DISK")); // 목록에 없는 동작은 받아들이지 않는다
    }
}
