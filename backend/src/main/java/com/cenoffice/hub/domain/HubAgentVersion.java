package com.cenoffice.hub.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** AI HUB 데이터 홀더(테이블 hub_agent_versions). 스키마는 db/migration/V2__ai_hub.sql 이 관리한다. */
@Entity
@Table(name = "hub_agent_versions")
public class HubAgentVersion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long agentId;
    public int version;
    @Column(length = 8000)
    public String snapshot;
    public String createdBy;
    public Instant createdAt;

    public HubAgentVersion() {}
}
