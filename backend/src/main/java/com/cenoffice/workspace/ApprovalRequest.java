package com.cenoffice.workspace;

import jakarta.persistence.*;
import java.time.Instant;

/** AI 가 제안한 변경을 사람이 승인/거절하기 위한 요청. 승인 전에는 어떤 변경도 적용되지 않는다. */
@Entity
@Table(name = "approval_requests", indexes = @Index(columnList = "status"))
public class ApprovalRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long aiTaskId;
    @Column(nullable = false, length = 64)
    private String kind;
    @Column(nullable = false, length = 500)
    private String summary;
    /** 승인 시 서버가 적용할 변경 내용(JSON 문자열). 적용 전 서버에서 스키마를 다시 검증한다. */
    @Column(length = 4000)
    private String payload;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private WsEnums.ApprovalStatus status = WsEnums.ApprovalStatus.PENDING;
    @Column(nullable = false, length = 64)
    private String requestedBy;
    @Column(length = 64)
    private String decidedBy;
    private Instant decidedAt;
    @Column(length = 500)
    private String comment;
    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected ApprovalRequest() {}

    public ApprovalRequest(Long aiTaskId, String kind, String summary, String payload, String requestedBy) {
        this.aiTaskId = aiTaskId;
        this.kind = kind;
        this.summary = summary;
        this.payload = payload;
        this.requestedBy = requestedBy;
    }

    public Long getId() { return id; }
    public Long getAiTaskId() { return aiTaskId; }
    public String getKind() { return kind; }
    public String getSummary() { return summary; }
    public String getPayload() { return payload; }
    public WsEnums.ApprovalStatus getStatus() { return status; }
    public String getRequestedBy() { return requestedBy; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getComment() { return comment; }
    public Instant getCreatedAt() { return createdAt; }

    public void decide(boolean approve, String by, String comment) {
        this.status = approve ? WsEnums.ApprovalStatus.APPROVED : WsEnums.ApprovalStatus.REJECTED;
        this.decidedBy = by;
        this.decidedAt = Instant.now();
        this.comment = comment == null ? null : comment.substring(0, Math.min(comment.length(), 500));
    }
}
