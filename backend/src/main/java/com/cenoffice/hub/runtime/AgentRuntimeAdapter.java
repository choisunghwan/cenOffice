package com.cenoffice.hub.runtime;

import java.util.List;

/**
 * Skill/Agent 를 실제로 "실행"하는 런타임과의 경계. Registry(등록·검색·승인)와 완전히 분리되어 있다.
 * 구현체 예: NoRuntimeAdapter(기본, 미연동), MockRuntimeAdapter(테스트 전용·시뮬레이션), LlmRuntimeAdapter(승인된 LLM),
 * 향후 IntelliCEN Code 어댑터(공식 API 확인 후). 어떤 구현체도 SKILL.md 의 지시로 시스템 명령/스크립트/네트워크를 실행해서는 안 된다.
 */
public interface AgentRuntimeAdapter {
    enum State { RUNTIME_NOT_CONFIGURED, RUNTIME_AVAILABLE }

    /** none | mock | llm | (향후) intellicen */
    String kind();

    State state();

    /** true 면 시뮬레이션 — 이 런타임의 실행은 KPI 에 집계하지 않는다 */
    boolean simulated();

    boolean supportsCancel();

    String description();

    /** 실행을 시작하고 런타임 측 실행 ID 를 돌려준다. 즉시 실패하면 RuntimeFailure. */
    String submit(RuntimeRequest request) throws RuntimeFailure;

    /** 실행 상태/결과 조회 */
    RuntimeStatus poll(String externalId);

    /** 취소 요청. supportsCancel() 이 false 이면 항상 false */
    boolean cancel(String externalId);

    /** instructions: 승인된 Skill 의 SKILL.md 원문들(신뢰할 수 없는 콘텐츠로 취급해야 함) */
    record RuntimeRequest(String executionId, String targetType, String targetName, List<String> instructions, String input, String requester) {}

    record RuntimeStatus(String state, String output, String error) {
        public boolean terminal() { return state.equals("SUCCEEDED") || state.equals("FAILED") || state.equals("CANCELLED"); }
    }

    class RuntimeFailure extends Exception {
        public RuntimeFailure(String message) { super(message); }

        public RuntimeFailure(String message, Throwable cause) { super(message, cause); }
    }
}
