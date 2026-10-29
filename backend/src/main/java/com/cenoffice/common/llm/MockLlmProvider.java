package com.cenoffice.common.llm;


/**
 * 외부 호출이 없는 규칙 기반 Mock. 실제 LLM 이 아니므로 "이해"가 아니라 문장 분리·템플릿으로 결과를 만든다.
 * 흐름(질문 → 명세 → 작업 목록 → 승인)을 end-to-end 로 검증하기 위한 용도다.
 */
public class MockLlmProvider implements LlmProvider {
    @Override public String name() { return "mock"; }

    @Override public boolean isReal() { return false; }

    @Override public String model() { return "rule-based"; }

    @Override
    public String chat(String systemPrompt, String userText) {
        return "(Mock AI) 메시지를 받았습니다. 아직 LLM 이 연결되지 않아서 요청하신 작업을 실제로 수행하지는 않았어요.";
    }
}
