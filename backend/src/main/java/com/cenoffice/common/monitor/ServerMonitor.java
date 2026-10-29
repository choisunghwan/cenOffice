package com.cenoffice.common.monitor;

import java.time.Instant;
import java.util.List;

/** 서버 모니터링 소스. 현재는 MockServerMonitor, 실제 모니터링 API(Prometheus 등)는 이 인터페이스로 교체한다. */
public interface ServerMonitor {
    record ServerStatus(String name, String role, String status, int cpu, int memory) {}

    record ServerEvent(Instant at, String level, String message) {}

    record Snapshot(String source, Instant at, List<ServerStatus> servers, List<ServerEvent> events) {}

    Snapshot snapshot();
}
