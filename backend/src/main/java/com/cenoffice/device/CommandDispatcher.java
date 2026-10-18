package com.cenoffice.device;

import com.cenoffice.device.DeviceTypes.CommandRequest;
import com.cenoffice.device.DeviceTypes.DeviceFeedback;
import com.cenoffice.device.DeviceTypes.DeviceStateSnapshot;
import com.cenoffice.domain.Device;
import com.cenoffice.domain.DeviceCommand;
import com.cenoffice.repo.DeviceCommandRepository;
import com.cenoffice.repo.DeviceRepository;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 접수된 명령을 요청 스레드 밖에서 어댑터로 전송한다. */
@Component
public class CommandDispatcher {
    private static final Logger log = LoggerFactory.getLogger(CommandDispatcher.class);

    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    private final DeviceCommandRepository commands;
    private final DeviceRepository devices;
    private final AdapterRegistry adapters;
    private final DeviceService service;

    public CommandDispatcher(DeviceCommandRepository commands, DeviceRepository devices, AdapterRegistry adapters, DeviceService service) {
        this.commands = commands;
        this.devices = devices;
        this.adapters = adapters;
        this.service = service;
    }

    public void dispatch(UUID commandId) { exec.submit(() -> run(commandId)); }

    private void run(UUID id) {
        try {
            DeviceCommand c = commands.findById(id).orElseThrow();
            Device d = devices.findById(c.getDeviceId()).orElseThrow();
            CommandRequest req = new CommandRequest(c.getPower(), c.getTargetTemp(), c.getMode());
            adapters.get(d.getAdapterType()).send(d, req, new DeviceFeedback() {
                @Override public void confirmed(DeviceStateSnapshot reported) { safe(() -> service.confirm(id, reported)); }
                @Override public void failed(String reason) { safe(() -> service.fail(id, reason)); }
            });
            service.markSent(id);
        } catch (Exception e) {
            log.warn("명령 {} 전송 실패: {}", id, e.getMessage());
            safe(() -> service.fail(id, e.getMessage() == null ? "전송 실패" : e.getMessage()));
        }
    }

    private void safe(Runnable r) {
        try { r.run(); } catch (Exception e) { log.error("명령 상태 갱신 실패", e); }
    }

    @PreDestroy
    void shutdown() { exec.shutdownNow(); }
}
