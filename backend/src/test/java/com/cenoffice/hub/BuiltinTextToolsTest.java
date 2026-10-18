package com.cenoffice.hub;

import static org.junit.jupiter.api.Assertions.*;
import com.cenoffice.hub.runtime.AgentRuntimeAdapter.RuntimeRequest;
import com.cenoffice.hub.runtime.BuiltinRuntimeAdapter;
import java.util.List;
import org.junit.jupiter.api.Test;

class BuiltinTextToolsTest {
    String run(String tool, String input) throws Exception {
        var rt = new BuiltinRuntimeAdapter();
        var r = rt.poll(rt.submit(new RuntimeRequest("1", "SKILL", tool, List.of(), input, "u")));
        assertEquals("SUCCEEDED", r.state());
        return r.output();
    }

    @Test void logAnalyzerFindsRootCauseAndAppFrame() throws Exception {
        String out = run("cen-log-analyzer", """
            2026-10-18 09:12:31 ERROR failed
            java.lang.IllegalStateException: boom
            \tat org.springframework.x.Y.z(Y.java:1)
            \tat com.cen.order.OrderService.create(OrderService.java:88)
            Caused by: java.lang.NullPointerException: x is null
            """);
        assertTrue(out.contains("NullPointerException"));
        assertTrue(out.contains("com.cen.order.OrderService.create"));
        assertFalse(out.contains("org.springframework.x.Y.z"));
        assertTrue(out.contains("null 참조"));
    }

    @Test void logAnalyzerSaysSoWhenNoException() throws Exception {
        assertTrue(run("cen-log-analyzer", "그냥 텍스트").contains("찾지 못했습니다"));
    }

    @Test void sqlReviewerFlagsDangerousStatements() throws Exception {
        String out = run("cen-sql-reviewer", "DELETE FROM orders; SELECT * FROM t WHERE name LIKE '%a'; UPDATE t SET a=1;");
        assertTrue(out.contains("WHERE 없는 DELETE"));
        assertTrue(out.contains("WHERE 없는 UPDATE"));
        assertTrue(out.contains("SELECT *"));
        assertTrue(out.contains("앞부분 와일드카드"));
    }

    @Test void sqlReviewerDoesNotFlagCleanQuery() throws Exception {
        assertTrue(run("cen-sql-reviewer", "SELECT id FROM t WHERE id = 1").contains("걸린 항목이 없습니다"));
    }

    @Test void meetingMinutesSplitsDecisionsAndActionsWithoutInventing() throws Exception {
        String out = run("cen-meeting-minutes", "주간 회의\n참석: 김대리, 박과장\n- 화면 개선안 확정\n- 김대리 다음주 월요일까지 견적서 작성\n- 배포 일정 미정");
        assertTrue(out.contains("화면 개선안 확정"));
        assertTrue(out.contains("다음주 월요일"));
        assertTrue(out.contains("| 김대리 | 다음주 월요일 |"));
        assertTrue(out.contains("일시 | 원문에 없음"));
    }

    @Test void meetingMinutesHandlesExplicitOwnerLine() throws Exception {
        String out = run("cen-meeting-minutes", "- 담당: 이사원 금요일까지 테스트 시나리오 공유\n- 확정: 배포");
        assertTrue(out.contains("이사원"));
        assertTrue(out.contains("금요일까지"));
    }

    @Test void weeklyReportClassifiesAndAveragesProgress() throws Exception {
        String out = run("cen-weekly-report", "[완료]\n- A 배포\n[진행]\n- B 개발 40%\n- C 개발 80%\n[이슈]\n- 지연");
        assertTrue(out.contains("60%"));
        assertTrue(out.contains("A 배포"));
        assertTrue(out.contains("이슈 1건"));
    }

    @Test void testCaseWriterUsesRequirementKeywordsAndEscapesPipes() throws Exception {
        String out = run("cen-test-case-writer", "사용자는 게시글을 등록 | 삭제할 수 있다");
        assertTrue(out.contains("TC-001"));
        assertTrue(out.contains("필수 항목을 비우고 저장"));
        assertTrue(out.contains("삭제"));
        assertFalse(out.contains("등록 | 삭제"));
    }
}
