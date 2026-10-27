package com.cenoffice.assistant;

import static org.junit.jupiter.api.Assertions.*;

import com.cenoffice.assistant.ToolCatalog.Tool;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolCatalogTest {
    static Tool tool(long id, String title, String desc, String... tags) { return new Tool(id, title, "t" + id, desc, null, null, List.of(tags), false); }

    final Tool minutes = tool(1, "회의록 정리", "회의 메모를 결정 사항과 할 일로 정리합니다", "회의", "회의록");
    final Tool sql = tool(2, "SQL 점검", "SQL 문장의 위험한 패턴을 찾아 알려 줍니다", "sql", "데이터베이스");
    final Tool report = tool(3, "주간 보고 작성", "이번 주 한 일을 주간 보고 형식으로 만듭니다", "보고", "주간");

    @Test void bestMatchComesFirst() {
        var r = ToolCatalog.rank("이 회의 메모 회의록으로 정리해줘", List.of(sql, report, minutes));
        assertEquals(1L, r.get(0).tool().id());
        assertTrue(r.get(0).score() >= ToolCatalog.PICK_THRESHOLD);
    }

    @Test void tagsAndTitlesBothCount() {
        assertEquals(2L, ToolCatalog.rank("SELECT * FROM orders 이거 sql 점검해줘", List.of(minutes, sql, report)).get(0).tool().id());
        assertEquals(3L, ToolCatalog.rank("주간 보고 써줘", List.of(minutes, sql, report)).get(0).tool().id());
    }

    @Test void unrelatedTextMatchesNothingAboveTheThreshold() {
        var r = ToolCatalog.rank("오늘 점심 뭐 먹을까", List.of(minutes, sql, report));
        assertTrue(r.isEmpty() || r.get(0).score() < ToolCatalog.PICK_THRESHOLD);
    }

    @Test void commonWordsAreIgnored() {
        assertTrue(ToolCatalog.words("도구 작성 처리 해줘").isEmpty());
        assertTrue(ToolCatalog.words("a 1 가").isEmpty()); // 한 글자는 제외
        assertEquals(2, ToolCatalog.words("회의록, SQL!").size());
    }

    @Test void promptCandidatesAreBoundedAndPutTheBestMatchesFirst() {
        List<Tool> many = new ArrayList<>();
        for (int i = 0; i < 100; i++) many.add(tool(100 + i, "도구" + i, "설명" + i));
        many.add(minutes);
        var picked = ToolCatalog.forPrompt("회의록 정리해줘", many);
        assertEquals(ToolCatalog.MAX_IN_PROMPT, picked.size());
        assertEquals(1L, picked.get(0).id());
    }

    @Test void builtinToolsRunOnTheServerAndOthersUseAi() {
        assertEquals("ai", minutes.engine());
        assertEquals("rule", new Tool(9L, "t", "n", "", null, null, List.of(), true).engine());
    }
}
