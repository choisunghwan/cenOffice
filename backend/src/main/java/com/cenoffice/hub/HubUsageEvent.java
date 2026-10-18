package com.cenoffice.hub;

import jakarta.persistence.*;
import java.time.Instant;

/** AI HUB 데이터 홀더(테이블 hub_usage_events). 스키마는 db/migration/V2__ai_hub.sql 이 관리한다. */
@Entity
@Table(name = "hub_usage_events")
public class HubUsageEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long skillId;
    public Long versionId;
    public Long agentId;
    public String actor;
    public String type;
    public String executionId;
    public boolean counted;
    public Instant occurredAt;

    public HubUsageEvent() {}
}
