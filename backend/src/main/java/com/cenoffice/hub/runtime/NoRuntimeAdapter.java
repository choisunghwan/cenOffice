package com.cenoffice.hub.runtime;

/** 기본값: 실행 런타임이 연결되지 않음. 실행 요청은 접수하지 않고 "실행 연동 준비 중"으로 안내한다. */
public class NoRuntimeAdapter implements AgentRuntimeAdapter {
    @Override public String kind() { return "none"; }

    @Override public State state() { return State.RUNTIME_NOT_CONFIGURED; }

    @Override public boolean simulated() { return false; }

    @Override public boolean supportsCancel() { return false; }

    @Override public String description() { return "실행 연동 준비 중 — 실행 런타임(IntelliCEN Code 또는 승인된 사내 LLM)이 설정되지 않았습니다"; }

    @Override public String submit(RuntimeRequest r) throws RuntimeFailure { throw new RuntimeFailure("실행 런타임이 설정되지 않았습니다"); }

    @Override public RuntimeStatus poll(String id) { return new RuntimeStatus("FAILED", null, "실행 런타임이 설정되지 않았습니다"); }

    @Override public boolean cancel(String id) { return false; }
}
