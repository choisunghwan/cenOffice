package com.cenoffice.workspace;

import jakarta.persistence.*;
import java.time.Instant;

/** AI 작업의 실행 단계 이력. 실행 엔진(Phase 3B)이 단계마다 한 줄씩 기록한다. */
@Entity
@Table(name = "agent_executions", indexes = @Index(columnList = "aiTaskId"))
public class AgentExecution {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long aiTaskId;
    @Column(nullable = false, length = 64)
    private String step;
    @Column(nullable = false, length = 16)
    private String status;
    @Column(length = 2000)
    private String detail;
    @Column(nullable = false)
    private Instant startedAt = Instant.now();
    private Instant finishedAt;

    protected AgentExecution() {}

    public AgentExecution(Long aiTaskId, String step, String status, String detail) {
        this.aiTaskId = aiTaskId;
        this.step = step;
        this.status = status;
        this.detail = detail;
    }

    public Long getId() { return id; }
    public Long getAiTaskId() { return aiTaskId; }
    public String getStep() { return step; }
    public String getStatus() { return status; }
    public String getDetail() { return detail; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
}
