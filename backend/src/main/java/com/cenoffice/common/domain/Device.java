package com.cenoffice.common.domain;

import jakarta.persistence.*;

/** 실제(또는 가상) 장비. objectId로 3D 오브젝트와 매핑된다. */
@Entity
@Table(name = "devices")
public class Device {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 64)
    private String code;
    @Column(nullable = false, length = 100)
    private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24)
    private Enums.DeviceType type;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private Enums.AdapterType adapterType;
    /** 프론트 3D 오브젝트 ID (layout.ts의 interactable id) */
    @Column(length = 64)
    private String objectId;
    /** 어댑터별 접속 정보(MQTT 토픽, HTTP URL 등). 비밀값은 저장하지 않고 환경변수 참조명만 둔다. */
    @Column(length = 1000)
    private String adapterConfig;

    protected Device() {}

    public Device(String code, String name, Enums.DeviceType type, Enums.AdapterType adapterType, String objectId) {
        this.code = code;
        this.name = name;
        this.type = type;
        this.adapterType = adapterType;
        this.objectId = objectId;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public Enums.DeviceType getType() { return type; }
    public Enums.AdapterType getAdapterType() { return adapterType; }
    public String getObjectId() { return objectId; }
    public String getAdapterConfig() { return adapterConfig; }
}
