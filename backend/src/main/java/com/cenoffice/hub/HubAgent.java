package com.cenoffice.hub;

import jakarta.persistence.*;
import java.time.Instant;

/** AI HUB 데이터 홀더(테이블 hub_agents). 스키마는 db/migration/V2__ai_hub.sql 이 관리한다. */
@Entity
@Table(name = "hub_agents")
public class HubAgent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String name;
    @Column(length = 1000)
    public String description;
    @Column(length = 1000)
    public String duties;
    @Column(length = 4000)
    public String config;
    public String visibility;
    public String team;
    public String status;
    public String owner;
    public int version;
    public boolean sample;
    public String createdBy;
    public Instant createdAt;
    public String updatedBy;
    public Instant updatedAt;

    public HubAgent() {}
}
