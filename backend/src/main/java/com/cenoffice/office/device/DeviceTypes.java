package com.cenoffice.office.device;

import com.cenoffice.common.domain.Enums;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;

/** 장비 제어 요청/상태/뷰 타입 모음. */
public final class DeviceTypes {
    private DeviceTypes() {}

    /** 제어 명령. 장비 종류에 따라 사용하는 필드가 다르다 (조명: power / 에어컨: power, targetTemp, mode). */
    public record CommandRequest(
            Boolean power,
            @DecimalMin("16.0") @DecimalMax("30.0") Double targetTemp,
            @Pattern(regexp = "COOL|HEAT") String mode) {}

    /** 장비가 보고한 상태. */
    public record DeviceStateSnapshot(boolean power, Double targetTemp, Double currentTemp, String mode, boolean online) {}

    /** 어댑터가 장비 응답을 비동기로 돌려주는 콜백. 응답이 오기 전까지는 성공으로 간주하지 않는다. */
    public interface DeviceFeedback {
        void confirmed(DeviceStateSnapshot reported);

        void failed(String reason);
    }

    /** 장비가 자발적으로 상태를 보고(폴링/구독)할 때 발행하는 이벤트. */
    public record DeviceReport(Long deviceId, DeviceStateSnapshot state) {}

    /** WebSocket 등으로 내보낼 도메인 이벤트. */
    public record OfficeEvent(String type, Object payload) {}

    public record StateView(boolean power, Double targetTemp, Double currentTemp, String mode, boolean online, Instant updatedAt) {}

    public record DeviceView(Long id, String code, String name, Enums.DeviceType type, Enums.AdapterType adapterType,
                             String objectId, StateView state) {}

    public record CommandView(UUID id, Long deviceId, String deviceCode, Enums.CommandStatus status, Boolean power,
                              Double targetTemp, String mode, String requestedBy, Instant requestedAt,
                              Instant completedAt, String error) {}
}
