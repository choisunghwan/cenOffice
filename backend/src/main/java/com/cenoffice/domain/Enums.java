package com.cenoffice.domain;

public final class Enums {
    private Enums() {}

    public enum Role { ADMIN, USER }

    public enum DeviceType { LIGHT, AIR_CONDITIONER }

    public enum AdapterType { MOCK, MQTT, HTTP }

    /** 요청됨 → 전송됨 → (장비 상태 확인) 확정 / 실패 / 시간초과. 전송만으로 성공 처리하지 않는다. */
    public enum CommandStatus { REQUESTED, SENT, CONFIRMED, FAILED, TIMEOUT }
}
