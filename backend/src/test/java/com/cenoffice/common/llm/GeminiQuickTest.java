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
        assertEquals(Map.of("thinkingBudget", 1024), flash.generationConfig(true, false).get("thinkingConfig"), "JSON(긴 초안)은 생각을 짧게 묶어 답이 잘리지 않게");
        assertEquals(16384, flash.generationConfig(true, false).get("maxOutputTokens"));
        assertNull(new GeminiLlmProvider(new ObjectMapper(), "k", "gemini-2.5-pro").generationConfig(false, true).get("thinkingConfig"), "Pro 는 끌 수 없다");
    }
}
