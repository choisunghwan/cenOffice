package com.cenoffice.device;

import com.cenoffice.config.AppProperties;
import com.cenoffice.device.DeviceTypes.CommandRequest;
import com.cenoffice.device.DeviceTypes.DeviceFeedback;
import com.cenoffice.device.DeviceTypes.DeviceReport;
import com.cenoffice.device.DeviceTypes.DeviceStateSnapshot;
import com.cenoffice.domain.Device;
import com.cenoffice.domain.Enums;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 실제 장비가 없을 때 사용하는 가상 장비. 지연·실패를 흉내 내며 장비 쪽 "실제 상태"를 자체 보관한다. */
@Component
public class MockDeviceAdapter implements DeviceAdapter {
    private static final double AMBIENT = 27.0;

    private record Hw(Enums.DeviceType type, DeviceStateSnapshot state) {}

    private final Map<Long, Hw> hardware = new ConcurrentHashMap<>();
    private final ScheduledExecutorService exec = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "mock-device");
        t.setDaemon(true);
        return t;
    });
    private final AppProperties.Mock cfg;
    private final ApplicationEventPublisher publisher;

    public MockDeviceAdapter(AppProperties props, ApplicationEventPublisher publisher) {
        this.cfg = props.devices().mock();
        this.publisher = publisher;
    }

    @Override
    public Enums.AdapterType type() { return Enums.AdapterType.MOCK; }

    private Hw hw(Device d) {
        return hardware.computeIfAbsent(d.getId(), id -> new Hw(d.getType(), switch (d.getType()) {
            case LIGHT -> new DeviceStateSnapshot(true, null, null, null, true);
            case AIR_CONDITIONER -> new DeviceStateSnapshot(false, 24.0, 26.5, "COOL", true);
        }));
    }

    @Override
    public void send(Device device, CommandRequest cmd, DeviceFeedback feedback) {
        hw(device);
        long latency = ThreadLocalRandom.current().nextLong(cfg.minLatencyMs(), Math.max(cfg.minLatencyMs(), cfg.maxLatencyMs()) + 1);
        exec.schedule(() -> {
            if (ThreadLocalRandom.current().nextDouble() < cfg.failureRate()) {
                feedback.failed("Mock 장비가 응답하지 않았습니다 (시뮬레이션된 오류)");
                return;
            }
            Hw next = hardware.compute(device.getId(), (id, old) -> {
                DeviceStateSnapshot s = old.state();
                return new Hw(old.type(), new DeviceStateSnapshot(
                        cmd.power() != null ? cmd.power() : s.power(),
                        cmd.targetTemp() != null ? cmd.targetTemp() : s.targetTemp(),
                        s.currentTemp(),
                        cmd.mode() != null ? cmd.mode() : s.mode(),
                        true));
            });
            feedback.confirmed(next.state());
        }, latency, TimeUnit.MILLISECONDS);
    }

    @Override
    public Optional<DeviceStateSnapshot> readState(Device device) {
        return Optional.of(hw(device).state());
    }

    /** 에어컨 실내 온도가 목표(또는 외기)를 향해 서서히 변하는 것을 흉내 낸다. */
    @Scheduled(fixedDelay = 5000)
    void tick() {
        hardware.forEach((id, h) -> {
            if (h.type() != Enums.DeviceType.AIR_CONDITIONER) return;
            DeviceStateSnapshot s = h.state();
            double goal = s.power() ? s.targetTemp() : AMBIENT;
            double cur = s.currentTemp();
            double step = s.power() ? 0.5 : 0.2;
            double nextTemp = Math.abs(goal - cur) <= step ? goal : cur + Math.signum(goal - cur) * step;
            nextTemp = Math.round(nextTemp * 10) / 10.0;
            if (nextTemp == cur) return;
            DeviceStateSnapshot ns = new DeviceStateSnapshot(s.power(), s.targetTemp(), nextTemp, s.mode(), true);
            hardware.put(id, new Hw(h.type(), ns));
            publisher.publishEvent(new DeviceReport(id, ns));
        });
    }

    @PreDestroy
    void shutdown() { exec.shutdownNow(); }
}
