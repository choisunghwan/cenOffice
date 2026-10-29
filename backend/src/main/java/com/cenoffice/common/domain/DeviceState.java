package com.cenoffice.common.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** 장비가 마지막으로 "보고한" 상태. 명령을 보냈다는 사실이 아니라 장비 확인 결과만 반영한다. */
@Entity
@Table(name = "device_states")
public class DeviceState {
    @Id
    private Long deviceId;
    private boolean power;
    private Double targetTemp;
    private Double currentTemp;
    @Column(name = "ac_mode", length = 16)
    private String mode;
    private boolean online;
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected DeviceState() {}

    public DeviceState(Long deviceId) { this.deviceId = deviceId; }

    public Long getDeviceId() { return deviceId; }
    public boolean isPower() { return power; }
    public Double getTargetTemp() { return targetTemp; }
    public Double getCurrentTemp() { return currentTemp; }
    public String getMode() { return mode; }
    public boolean isOnline() { return online; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void apply(boolean power, Double targetTemp, Double currentTemp, String mode, boolean online) {
        this.power = power;
        this.targetTemp = targetTemp;
        this.currentTemp = currentTemp;
        this.mode = mode;
        this.online = online;
        this.updatedAt = Instant.now();
    }
}
