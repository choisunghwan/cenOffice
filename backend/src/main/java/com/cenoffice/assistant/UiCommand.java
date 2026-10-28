package com.cenoffice.assistant;

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 입력창으로 조작할 수 있는 화면 동작의 허용 목록. 서버는 이 목록에 있는 이름만 화면(프런트)에 돌려주고, 실제 실행은 화면이 한다.
 * 새 화면 기능을 만들면 여기와 프런트의 uiActions.ts 에 한 줄씩 더하면 입력창에서도 쓸 수 있다.
 * 모양이 분명한 말은 규칙으로 바로 알아보고(AI 호출 없이 처리), 그 밖의 말은 AI 가 이 목록에서 고르게 한다.
 */
enum UiCommand {
    THEME_DARK("다크 모드로 바꿨습니다.", false, "다크 모드로 바꾸기", "다크\\s*(?:모드|테마)|어둡게|어두운\\s*(?:모드|테마|화면)|야간\\s*모드"),
    THEME_LIGHT("라이트 모드로 바꿨습니다.", false, "라이트 모드로 바꾸기", "라이트\\s*(?:모드|테마)|밝게|밝은\\s*(?:모드|테마|화면)|주간\\s*모드"),
    THEME_TOGGLE("화면 모드를 바꿨습니다.", false, "다크/라이트 모드 서로 바꾸기", "(?:모드|테마)\\s*(?:를|을)?\\s*(?:전환|바꿔|바꾸|변경)"),
    SIDEBAR_COLLAPSE("사이드바를 접었습니다.", false, "왼쪽 사이드바 접기", "사이드\\s*바.*(?:접|숨|줄여|닫)"),
    SIDEBAR_EXPAND("사이드바를 펼쳤습니다.", false, "왼쪽 사이드바 펼치기", "사이드\\s*바.*(?:펼|펴|열|보여|늘려)"),
    NEW_CHAT("새 채팅을 시작합니다.", false, "새 채팅(대화) 시작", "새\\s*(?:채팅|대화)"),
    LOGOUT("로그아웃합니다.", false, "로그아웃", "로그\\s*아웃"),
    MODE_OFFICE("3D 오피스로 전환했습니다.", false, "3D 오피스 화면으로 전환", "(?:3\\s*[dD]|삼디)\\s*오피스.*(?:열|이동|가|보여|전환|켜)|(?:3\\s*[dD]|삼디)\\s*오피스\\s*(?:으로|로)"),
    MODE_WORKSPACE("업무 화면으로 돌아왔습니다.", false, "업무(워크스페이스) 화면으로 전환", "업무\\s*(?:화면|모드)\\s*(?:으로|로)?\\s*(?:이동|가|전환|돌아|열)"),
    NAV_REVIEW("공개 승인 화면을 열었습니다.", true, "공개 승인 화면 열기(관리자 전용)", "공개\\s*승인\\s*(?:화면|페이지|메뉴)?\\s*(?:을|를|으로|로)?\\s*" + VERB()),
    NAV_CREATE_TOOL("도구 만들기 화면을 열었습니다.", false, "도구 만들기 화면 열기", "도구\\s*만들기\\s*(?:화면|페이지)?\\s*(?:을|를|으로|로)?\\s*" + VERB()),
    NAV_MY_TOOLS("내 도구 화면을 열었습니다.", false, "내 도구 화면 열기", "내\\s*도구\\s*(?:화면|페이지|목록)?\\s*(?:을|를|으로|로)?\\s*" + VERB()),
    NAV_TOOLS("도구 화면을 열었습니다.", false, "도구(모든 도구) 화면 열기", "도구\\s*(?:화면|페이지|메뉴|탭)\\s*(?:을|를|으로|로)?\\s*" + VERB()),
    NAV_CALENDAR("일정 화면을 열었습니다.", false, "일정(캘린더) 화면 열기", "(?:일정|캘린더|달력)\\s*(?:화면|페이지|메뉴|탭)\\s*(?:을|를|으로|로)?\\s*" + VERB() + "|(?:캘린더|달력)\\s*(?:으로|로)?\\s*" + VERB()),
    NAV_MEETINGS("회의 화면을 열었습니다.", false, "회의 화면 열기", "회의\\s*(?:화면|페이지|메뉴|탭|목록)\\s*(?:을|를|으로|로)?\\s*" + VERB()),
    NAV_SETTINGS("설정 화면을 열었습니다.", false, "설정 화면 열기", "설정\\s*(?:화면|페이지|메뉴)?\\s*(?:을|를|으로|로)?\\s*" + VERB()),
    NAV_HOME("홈으로 이동했습니다.", false, "홈(이 채팅 화면)으로 이동", "(?:홈|메인)\\s*(?:화면|페이지)?\\s*(?:으로|로)\\s*(?:이동|가|돌아)");

    /** "…열어줘, 이동해줘, 가줘, 보여줘" 같은 이동 말. "설정 가능한"처럼 다른 낱말의 일부와 섞이지 않도록 어미까지 적는다. */
    private static String VERB() { return "(?:열어|열고|이동|가줘|가자|가봐|보여|띄워|켜줘)"; }

    private final String done;
    private final boolean adminOnly;
    private final String description;
    private final Pattern pattern;

    UiCommand(String done, boolean adminOnly, String description, String regex) {
        this.done = done; this.adminOnly = adminOnly; this.description = description; this.pattern = Pattern.compile(regex);
    }

    String done() { return done; }
    boolean adminOnly() { return adminOnly; }
    String description() { return description; }

    /** 말이 화면 동작을 가리키면 그 동작(위에서부터 먼저 맞는 것) */
    static Optional<UiCommand> match(String text) {
        if (text == null) return Optional.empty();
        return Arrays.stream(values()).filter(c -> c.pattern.matcher(text).find()).findFirst();
    }

    static Optional<UiCommand> byId(String id) {
        return Arrays.stream(values()).filter(c -> c.name().equals(id)).findFirst();
    }

    /** AI 에게 보여 줄 선택지: "이름: 설명" 줄들 */
    static String forPrompt() {
        StringBuilder sb = new StringBuilder();
        for (UiCommand c : values()) sb.append("  - ").append(c.name()).append(": ").append(c.description()).append('\n');
        return sb.toString();
    }
}
