package com.cenoffice.home;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 서비스 사용법 도움말 검색: 질문과 맞는 문서를 찾고, 상관없는 말에는 아무것도 내밀지 않는다 */
class HelpCatalogTest {
    final HelpCatalog help = new HelpCatalog();

    String topId(String q) {
        var hits = help.search(q, 3);
        return hits.isEmpty() ? null : hits.get(0).doc().id();
    }

    @Test void documentsAreLoaded() {
        assertTrue(help.all().size() >= 10, "도움말 문서가 읽혀야 한다: " + help.all().size());
        assertFalse(help.overview().isBlank());
        for (var d : help.all()) {
            assertFalse(d.title().isBlank(), d.id());
            assertFalse(d.body().isBlank(), d.id());
            assertFalse(d.id().equals("overview") ? false : d.question().isBlank(), d.id() + " 에는 예시 질문이 있어야 한다");
        }
    }

    @Test void findsTheRightGuideForTypicalQuestions() {
        assertEquals("form-upload", topId("양식은 어떻게 올려요?"));
        assertEquals("minutes-export", topId("회의록을 우리 양식으로 받으려면 어떻게 해요"));
        assertEquals("screen-control", topId("다크 모드로 바꾸는 방법 알려줘"));
        assertEquals("chat-history", topId("지난 대화는 어디서 봐?"));
        assertEquals("account", topId("아이디 저장은 어디서 해?"));
        assertEquals("tools", topId("도구를 직접 만들려면?"));
    }

    @Test void particlesAttachedToWordsStillMatch() {
        var hits = help.search("회의록을 확정하려면", 3);
        assertFalse(hits.isEmpty());
        assertTrue(hits.stream().anyMatch(h -> h.doc().id().equals("minutes")), hits.toString());
    }

    @Test void unrelatedOrEmptyQuestionsFindNothing() {
        assertTrue(help.search("오늘 날씨 어때", 3).isEmpty(), help.search("오늘 날씨 어때", 3).toString());
        assertTrue(help.search("", 3).isEmpty());
        assertTrue(help.search(null, 3).isEmpty());
        assertTrue(help.search("ㅋㅋㅋ", 3).isEmpty());
    }

    @Test void followUpsAreExampleQuestionsOfOtherRelatedGuides() {
        var hits = help.search("회의록 양식으로 받기", 3);
        assertTrue(hits.size() >= 2);
        List<String> next = help.followUps(hits, 1, 3);
        assertFalse(next.isEmpty());
        assertFalse(next.contains(hits.get(0).doc().question())); // 방금 답한 문서의 질문은 다시 권하지 않는다
    }

    @Test void parsesTheDocumentFormat() {
        var doc = HelpCatalog.parse("x", "# 제목\n질문: 예시 질문\n키워드: 가나, 다라\n\n본문 한 줄\n둘째 줄\n").orElseThrow();
        assertEquals("제목", doc.title());
        assertEquals("예시 질문", doc.question());
        assertEquals(List.of("가나", "다라"), doc.keywords());
        assertEquals("본문 한 줄\n둘째 줄", doc.body());
        assertTrue(HelpCatalog.parse("y", "제목 없음\n\n본문").isEmpty());
    }
}
