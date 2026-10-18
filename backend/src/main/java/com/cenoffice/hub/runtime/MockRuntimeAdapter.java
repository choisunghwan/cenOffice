package com.cenoffice.hub.runtime;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 개발/테스트 전용 Mock 런타임. 항상 simulated()=true 이므로 KPI 에 집계되지 않으며 운영(APP_ENV=PROD)에서는 로드되지 않는다.
 * 입력에 "FAIL" 이 있으면 실패, "SLOW" 가 있으면 오래 걸리는 실행(취소 테스트용)을 흉내 낸다.
 */
public class MockRuntimeAdapter implements AgentRuntimeAdapter {
    private record Run(String state, String output, String error) {}

    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService exec = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "hub-mock-runtime");
        t.setDaemon(true);
        return t;
    });

    @Override public String kind() { return "mock"; }

    @Override public State state() { return State.RUNTIME_AVAILABLE; }

    @Override public boolean simulated() { return true; }

    @Override public boolean supportsCancel() { return true; }

    @Override public String description() { return "Mock 런타임(시뮬레이션) — 실제 실행이 아니며 KPI 에 집계되지 않습니다"; }

    @Override
    public String submit(RuntimeRequest r) {
        String id = "mock-" + UUID.randomUUID();
        runs.put(id, new Run("RUNNING", null, null));
        long delay = r.input() != null && r.input().contains("SLOW") ? 5000 : 150;
        exec.schedule(() -> runs.computeIfPresent(id, (k, cur) -> {
            if (!cur.state().equals("RUNNING")) return cur;
            if (r.input() != null && r.input().contains("FAIL")) return new Run("FAILED", null, "Mock 런타임이 실패를 시뮬레이션했습니다");
            return new Run("SUCCEEDED", "[시뮬레이션] " + r.targetName() + " 실행 결과 (지침 " + r.instructions().size() + "건, 입력 " + r.input().length() + "자)", null);
        }), delay, TimeUnit.MILLISECONDS);
        return id;
    }

    @Override
    public RuntimeStatus poll(String id) {
        Run r = runs.get(id);
        return r == null ? new RuntimeStatus("FAILED", null, "알 수 없는 실행입니다") : new RuntimeStatus(r.state(), r.output(), r.error());
    }

    @Override
    public boolean cancel(String id) {
        return runs.computeIfPresent(id, (k, cur) -> cur.state().equals("RUNNING") ? new Run("CANCELLED", null, null) : cur) != null
                && runs.get(id).state().equals("CANCELLED");
    }
}
