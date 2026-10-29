package com.cenoffice.hub.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** AI HUB 데이터 홀더(테이블 hub_approval_history). 스키마는 db/migration/V2__ai_hub.sql 이 관리한다. */
@Entity
@Table(name = "hub_approval_history")
public class HubApprovalHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String targetType;
    public Long targetId;
    public Long versionId;
    public String action;
    public String actor;
    @Column(length = 500)
    public String comment;
    public Instant occurredAt;

    public HubApprovalHistory() {}
}
