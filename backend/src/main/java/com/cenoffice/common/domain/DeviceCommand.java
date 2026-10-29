package com.cenoffice.common.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "device_commands", indexes = @Index(columnList = "deviceId,requestedAt"))
public class DeviceCommand {
    @Id
    private UUID id = UUID.randomUUID();
    @Column(nullable = false)
    private Long deviceId;
    private Boolean power;
    private Double targetTemp;
    @Column(name = "ac_mode", length = 16)
    private String mode;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private Enums.CommandStatus status = Enums.CommandStatus.REQUESTED;
    @Column(nullable = false, length = 64)
    private String requestedBy;
    @Column(nullable = false)
    private Instant requestedAt = Instant.now();
    private Instant sentAt;
    private Instant completedAt;
    @Column(length = 500)
    private String error;

    protected DeviceCommand() {}

    public DeviceCommand(Long deviceId, Boolean power, Double targetTemp, String mode, String requestedBy) {
        this.deviceId = deviceId;
        this.power = power;
        this.targetTemp = targetTemp;
        this.mode = mode;
        this.requestedBy = requestedBy;
    }

    public UUID getId() { return id; }
    public Long getDeviceId() { return deviceId; }
    public Boolean getPower() { return power; }
    public Double getTargetTemp() { return targetTemp; }
    public String getMode() { return mode; }
    public Enums.CommandStatus getStatus() { return status; }
    public String getRequestedBy() { return requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getSentAt() { return sentAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getError() { return error; }

    public boolean isOpen() {
        return status == Enums.CommandStatus.REQUESTED || status == Enums.CommandStatus.SENT;
    }

    public void markSent() {
        status = Enums.CommandStatus.SENT;
        sentAt = Instant.now();
    }

    public void finish(Enums.CommandStatus s, String err) {
        status = s;
        completedAt = Instant.now();
        error = err == null ? null : err.substring(0, Math.min(err.length(), 500));
    }
}
