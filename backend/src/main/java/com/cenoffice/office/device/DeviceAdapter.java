package com.cenoffice.office.device;

import com.cenoffice.office.device.DeviceTypes.CommandRequest;
import com.cenoffice.office.device.DeviceTypes.DeviceFeedback;
import com.cenoffice.office.device.DeviceTypes.DeviceStateSnapshot;
import com.cenoffice.common.domain.Device;
import com.cenoffice.common.domain.Enums;
import java.util.Optional;

/**
 * 실제 장비와 통신하는 계층. MockDeviceAdapter(현재), MqttDeviceAdapter / HttpDeviceAdapter(Phase 4)가 구현한다.
 * send()는 "장비로 전송을 접수"했을 때 반환하고, 장비의 실제 응답은 feedback으로 비동기 전달한다.
 */
public interface DeviceAdapter {
    Enums.AdapterType type();

    void send(Device device, CommandRequest command, DeviceFeedback feedback) throws DeviceException;

    /** 장비의 현재 상태를 직접 조회. 알 수 없으면 empty. */
    Optional<DeviceStateSnapshot> readState(Device device);

    class DeviceException extends Exception {
        public DeviceException(String message) { super(message); }
        public DeviceException(String message, Throwable cause) { super(message, cause); }
    }
}
