package com.cenoffice.workspace;

import jakarta.persistence.*;

@Entity
@Table(name = "project_tasks", indexes = @Index(columnList = "projectId"))
public class ProjectTask extends Timestamped {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long projectId;
    private Long requirementId;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(length = 4000)
    private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private WsEnums.TaskStatus status = WsEnums.TaskStatus.TODO;
    private Long assigneeAgentId;
    @Column(nullable = false, length = 64)
    private String createdBy;

    protected ProjectTask() {}

    public ProjectTask(Long projectId, Long requirementId, String title, String description, String createdBy) {
        this.projectId = projectId;
        this.requirementId = requirementId;
        this.title = title;
        this.description = description;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public Long getRequirementId() { return requirementId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public WsEnums.TaskStatus getStatus() { return status; }
    public Long getAssigneeAgentId() { return assigneeAgentId; }
    public String getCreatedBy() { return createdBy; }

    public void update(String title, String description, Long requirementId, WsEnums.TaskStatus status, Long assigneeAgentId) {
        this.title = title;
        this.description = description;
        this.requirementId = requirementId;
        if (status != null) this.status = status;
        this.assigneeAgentId = assigneeAgentId;
    }
}
