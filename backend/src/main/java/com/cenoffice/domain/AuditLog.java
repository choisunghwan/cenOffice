package com.cenoffice.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "audit_logs")
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 64)
    private String actor;
    @Column(nullable = false, length = 64)
    private String action;
    @Column(length = 128)
    private String target;
    @Column(length = 1000)
    private String detail;
    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected AuditLog() {}

    public AuditLog(String actor, String action, String target, String detail) {
        this.actor = actor;
        this.action = action;
        this.target = target;
        this.detail = detail == null ? null : detail.substring(0, Math.min(detail.length(), 1000));
    }

    public Long getId() { return id; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getTarget() { return target; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
