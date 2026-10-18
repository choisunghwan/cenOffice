package com.cenoffice.device;

import com.cenoffice.repo.DeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 기동 시 각 장비의 실제 상태를 읽어 DB 상태를 맞춘다 (장비 상태가 기준). */
@Component
public class StateSynchronizer {
    private static final Logger log = LoggerFactory.getLogger(StateSynchronizer.class);

    private final DeviceRepository devices;
    private final AdapterRegistry adapters;
    private final DeviceService service;

    public StateSynchronizer(DeviceRepository devices, AdapterRegistry adapters, DeviceService service) {
        this.devices = devices;
        this.adapters = adapters;
        this.service = service;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void sync() {
        devices.findAll().forEach(d -> {
            try {
                adapters.get(d.getAdapterType()).readState(d).ifPresent(s -> service.reportState(d.getId(), s));
            } catch (Exception e) {
                log.warn("장비 {} 초기 상태 동기화 실패: {}", d.getCode(), e.getMessage());
            }
        });
    }
}
