package com.cenoffice.hub.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** 개인 액세스 토큰(테이블 hub_tokens, 스키마는 V3__hub_tokens.sql 이 관리). 원문 토큰은 저장하지 않는다. */
@Entity
@Table(name = "hub_tokens")
public class HubToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String owner;
    public String name;
    public String tokenHash;
    public String tokenPrefix;
    public Instant createdAt;
    public Instant expiresAt;
    public Instant lastUsedAt;
    public boolean revoked;
    /** MCP = Claude Code 등(/mcp), NAVI = CenNavi 확장(/api/ext). 다른 범위의 입구에서는 쓸 수 없다. */
    public String scope = "MCP";

    public HubToken() {}
}
