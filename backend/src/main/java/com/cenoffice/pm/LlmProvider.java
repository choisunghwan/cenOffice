package com.cenoffice.pm;

import com.cenoffice.pm.PmTypes.PmRequest;
import com.cenoffice.pm.PmTypes.PmResult;

/**
 * LLM 연동 경계. 구현체는 "텍스트/JSON 을 돌려주는 것"만 하며, DB 변경·도구 실행·권한 판단은 절대 하지 않는다.
 * 반환값은 호출 측(PmService)이 스키마·권한을 서버에서 다시 검증한 뒤에만 사용한다.
 */
public interface LlmProvider {
    /** mock | anthropic */
    String name();

    /** 실제 외부 LLM 을 호출하는 구현인지 */
    boolean isReal();

    String model();

    /** 요구사항 분석 → (추가 질문 | 기능 명세 + 요구사항 + 작업 목록) */
    PmResult planRequirements(PmRequest request) throws LlmException;

    /** 일반 대화 응답 (채팅용). 실행 권한이 없는 순수 텍스트 */
    String chat(String systemPrompt, String userText) throws LlmException;

    class LlmException extends Exception {
        public LlmException(String message) { super(message); }

        public LlmException(String message, Throwable cause) { super(message, cause); }
    }
}
