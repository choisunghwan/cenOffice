package com.cenoffice.common.monitor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MockServerMonitor implements ServerMonitor {
    private static final class Srv {
        final String name, role;
        int cpu, mem, downTicks;
        String status = "OK";
        Srv(String name, String role, int cpu, int mem) { this.name = name; this.role = role; this.cpu = cpu; this.mem = mem; }
    }

    private final List<Srv> servers = List.of(
            new Srv("web-01", "웹 서버", 28, 46), new Srv("api-01", "API 서버", 41, 62),
            new Srv("db-01", "데이터베이스", 35, 71), new Srv("batch-01", "배치 서버", 8, 30));
    private final List<ServerEvent> events = new ArrayList<>();

    public MockServerMonitor() {
        Instant now = Instant.now();
        events.add(new ServerEvent(now.minusSeconds(3600), "INFO", "db-01 정기 백업 완료"));
        events.add(new ServerEvent(now.minusSeconds(1800), "INFO", "web-01 배포 완료 (v1.4.2)"));
        events.add(new ServerEvent(now.minusSeconds(600), "WARN", "api-01 응답 지연 감지 후 정상화"));
    }

    @Scheduled(fixedDelay = 3000)
    synchronized void tick() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (Srv s : servers) {
            String before = s.status;
            if (s.downTicks > 0) {
                if (--s.downTicks == 0) s.status = "OK";
            } else if (r.nextDouble() < 0.01) {
                s.downTicks = 4;
                s.status = "DOWN";
            }
            if (!s.status.equals("DOWN")) {
                s.cpu = clamp(s.cpu + r.nextInt(-6, 7), 2, 98);
                s.mem = clamp(s.mem + r.nextInt(-3, 4), 15, 95);
                s.status = (s.cpu > 85 || s.mem > 90) ? "WARN" : "OK";
            }
            if (!before.equals(s.status)) {
                String level = s.status.equals("OK") ? "INFO" : s.status.equals("WARN") ? "WARN" : "ERROR";
                String msg = switch (s.status) {
                    case "DOWN" -> s.name + " 응답 없음";
                    case "WARN" -> s.name + " 자원 사용률 높음 (CPU " + s.cpu + "%, MEM " + s.mem + "%)";
                    default -> s.name + " 정상 상태로 복구";
                };
                events.add(0, new ServerEvent(Instant.now(), level, msg));
                if (events.size() > 50) events.remove(events.size() - 1);
            }
        }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    @Override
    public synchronized Snapshot snapshot() {
        List<ServerStatus> list = servers.stream()
                .map(s -> new ServerStatus(s.name, s.role, s.status, s.status.equals("DOWN") ? 0 : s.cpu, s.status.equals("DOWN") ? 0 : s.mem))
                .toList();
        return new Snapshot("MOCK", Instant.now(), list, List.copyOf(events.subList(0, Math.min(events.size(), 10))));
    }
}
