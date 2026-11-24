package com.cenoffice.common.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 회의 AI 빠른 답: Flash 모델이면 추론(생각) 단계를 끄고 출력을 줄인다. 다른 호출은 그대로 */
class GeminiQuickTest {
    @Test void quickTurnsThinkingOffOnlyForFlash() {
        var flash = new GeminiLlmProvider(new ObjectMapper(), "k", "gemini-2.5-flash");
        assertEquals(Map.of("thinkingBudget", 0), flash.generationConfig(false, true).get("thinkingConfig"));
        assertEquals(400, flash.generationConfig(false, true).get("maxOutputTokens"));
        assertNull(flash.generationConfig(false, false).get("thinkingConfig"), "일반 대화는 그대로");
        assertNull(flash.generationConfig(true, false).get("thinkingConfig"), "회의록 등 JSON 은 그대로");
        assertNull(new GeminiLlmProvider(new ObjectMapper(), "k", "gemini-2.5-pro").generationConfig(false, true).get("thinkingConfig"), "Pro 는 끌 수 없다");
    }
}
