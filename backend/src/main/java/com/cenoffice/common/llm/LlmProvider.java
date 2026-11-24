package com.cenoffice.common.llm;


/**
 * LLM 연동 경계. 구현체는 "텍스트/JSON 을 돌려주는 것"만 하며, DB 변경·스킬 실행·권한 판단은 절대 하지 않는다.
 * 반환값은 호출 측이 스키마·권한을 서버에서 다시 검증한 뒤에만 사용한다.
 */
public interface LlmProvider {
    /** mock | anthropic */
    String name();

    /** 실제 외부 LLM 을 호출하는 구현인지 */
    boolean isReal();

    String model();

    /** 일반 대화 응답 (채팅용). 실행 권한이 없는 순수 텍스트 */
    String chat(String systemPrompt, String userText) throws LlmException;

    /**
     * 음성으로 바로 들려줄 짧은 답(예: 회의 AI 센). 기본은 chat 과 같고, 구현체가 추론(생각) 단계를 끄고
     * 출력 길이를 줄여 응답을 빠르게 할 수 있다.
     */
    default String chatQuick(String systemPrompt, String userText) throws LlmException { return chat(systemPrompt, userText); }

    /** 긴 구조화(JSON) 결과가 필요한 호출(예: 회의록). 기본은 chat 과 같고, 구현체가 출력 한도를 늘려 덮어쓸 수 있다. */
    default String generateJson(String systemPrompt, String userText) throws LlmException { return chat(systemPrompt, userText); }

    class LlmException extends Exception {
        public LlmException(String message) { super(message); }

        public LlmException(String message, Throwable cause) { super(message, cause); }
    }
}
