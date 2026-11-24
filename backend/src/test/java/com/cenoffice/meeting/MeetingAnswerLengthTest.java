package com.cenoffice.meeting;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 소리로 들려줄 답은 300자 안에서 문장 끝으로 자른다 */
class MeetingAnswerLengthTest {
    @Test void longAnswersAreCutAtASentenceEnd() {
        String s = MeetingAssistantService.shorten("첫 문장입니다. ".repeat(60), MeetingAssistantService.MAX_ANSWER);
        assertTrue(s.length() <= MeetingAssistantService.MAX_ANSWER);
        assertTrue(s.endsWith("."));
        assertEquals("짧은 답입니다.", MeetingAssistantService.shorten("짧은 답입니다.", 300));
        assertEquals("가".repeat(300), MeetingAssistantService.shorten("가".repeat(500), 300), "문장 끝이 없으면 한도에서 자른다");
    }
}
