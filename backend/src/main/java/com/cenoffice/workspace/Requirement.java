package com.cenoffice.workspace;

import jakarta.persistence.*;

@Entity
@Table(name = "requirements", indexes = @Index(columnList = "projectId"))
public class Requirement extends Timestamped {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long projectId;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(length = 4000)
    private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private WsEnums.Priority priority = WsEnums.Priority.MEDIUM;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private WsEnums.RequirementStatus status = WsEnums.RequirementStatus.DRAFT;
    @Column(nullable = false, length = 64)
    private String createdBy;

    protected Requirement() {}

    public Requirement(Long projectId, String title, String description, WsEnums.Priority priority, String createdBy) {
        this.projectId = projectId;
        this.title = title;
        this.description = description;
        if (priority != null) this.priority = priority;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public WsEnums.Priority getPriority() { return priority; }
    public WsEnums.RequirementStatus getStatus() { return status; }
    public String getCreatedBy() { return createdBy; }

    public void update(String title, String description, WsEnums.Priority priority, WsEnums.RequirementStatus status) {
        this.title = title;
        this.description = description;
        if (priority != null) this.priority = priority;
        if (status != null) this.status = status;
    }
}
