package com.cenoffice.hub.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** AI HUB 데이터 홀더(테이블 hub_executions). 스키마는 db/migration/V2__ai_hub.sql 이 관리한다. */
@Entity
@Table(name = "hub_executions")
public class HubExecution {
    @Id
    public String id;
    public String targetType;
    public Long skillId;
    public Long skillVersionId;
    public Long agentId;
    public String requester;
    public String inputHash;
    @Column(length = 4000)
    public String input;
    public String status;
    public String runtimeKind;
    public boolean simulated;
    public boolean counted;
    @Column(length = 20000)
    public String result;
    @Column(length = 500)
    public String error;
    public String externalId;
    public Instant requestedAt;
    public Instant startedAt;
    public Instant completedAt;
    /** 실행을 시작한 곳: WEB / MCP / CENNAVI. sourceHost 는 CenNavi 에서 선택한 글이 있던 사이트의 도메인(전체 주소는 저장하지 않는다). */
    public String source = "WEB";
    public String sourceHost;

    public HubExecution() {}
}
