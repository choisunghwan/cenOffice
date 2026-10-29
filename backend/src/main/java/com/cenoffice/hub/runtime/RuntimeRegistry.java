package com.cenoffice.hub.runtime;

import com.cenoffice.hub.domain.HubSkill;

import com.cenoffice.common.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 설정(HUB_RUNTIME)에 따라 런타임 어댑터를 고른다. 기본은 none(미연동).
 * 운영(APP_ENV=PROD)에서는 Mock 을 로드하지 않는다. IntelliCEN Code 어댑터는 공식 API 가 확인되면 여기에 추가한다.
 */
@Component
public class RuntimeRegistry {
    private static final Logger log = LoggerFactory.getLogger(RuntimeRegistry.class);

    private final AgentRuntimeAdapter adapter;
    private final LlmRuntimeAdapter ai;
    private final LlmProvider llmProvider;
    private final AgentRuntimeAdapter builtin = new BuiltinRuntimeAdapter();

    public RuntimeRegistry(@Value("${app.hub.runtime:none}") String kind, @Value("${app.env:DEV}") String env, @Value("${app.hub.ai-engine:false}") boolean aiEngine, LlmProvider llm) {
        this.llmProvider = llm;
        this.ai = aiEngine ? new LlmRuntimeAdapter(llm) : null;
        String k = kind == null ? "none" : kind.trim().toLowerCase();
        AgentRuntimeAdapter a = new NoRuntimeAdapter();
        switch (k) {
            case "mock" -> {
                if ("PROD".equalsIgnoreCase(env)) log.warn("APP_ENV=PROD 에서는 Mock 런타임을 사용할 수 없어 '실행 연동 준비 중' 상태로 둡니다.");
                else { a = new MockRuntimeAdapter(); log.warn("AI HUB Mock 런타임이 활성화되었습니다(시뮬레이션, KPI 집계 제외)."); }
            }
            case "llm" -> {
                a = new LlmRuntimeAdapter(llm);
                log.warn("AI HUB LLM 런타임이 활성화되었습니다 ({}).", a.description());
            }
            case "none", "" -> { }
            default -> log.warn("알 수 없는 HUB_RUNTIME 값 '{}' — 실행 런타임 없음으로 둡니다.", k);
        }
        // AI 엔진이 켜져 있고 별도 런타임이 없으면 일반 Skill 은 AI 엔진으로 실행한다
        this.adapter = a instanceof NoRuntimeAdapter && ai != null ? ai : a;
        if (ai != null) log.warn("AI HUB AI 엔진 활성화: {}", ai.description());
    }

    public boolean aiAvailable() { return ai != null; }
    public LlmProvider llm() { return llmProvider; }

    public AgentRuntimeAdapter current() { return adapter; }
    /** engine=ai 요청이면 (켜져 있을 때) AI 엔진, 아니면 기본 선택 */
    public AgentRuntimeAdapter forSkill(com.cenoffice.hub.domain.HubSkill skill, boolean useAi) {
        return useAi && ai != null ? ai : forSkill(skill);
    }
    public AgentRuntimeAdapter forSkill(com.cenoffice.hub.domain.HubSkill skill) {
        return com.cenoffice.hub.BuiltinSkillSeeder.trusted(skill) ? builtin : adapter;
    }
}
