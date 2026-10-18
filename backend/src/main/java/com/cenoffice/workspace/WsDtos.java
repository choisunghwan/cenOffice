package com.cenoffice.workspace;

import com.cenoffice.workspace.WsEnums.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class WsDtos {
    private WsDtos() {}

    // ── 요청 ──
    public record ProjectRequest(@NotBlank @Size(max = 120) String name, @Size(max = 2000) String description, ProjectStatus status) {}

    public record RequirementRequest(@NotBlank @Size(max = 200) String title, @Size(max = 4000) String description,
                                     Priority priority, RequirementStatus status) {}

    public record TaskRequest(@NotBlank @Size(max = 200) String title, @Size(max = 4000) String description,
                              Long requirementId, TaskStatus status, Long assigneeAgentId) {}

    public record AiTaskRequest(@NotNull Long projectId, Long taskId, @NotNull Long agentId, @NotNull AiTaskType type,
                                @NotNull Environment environment, @Size(max = 4000) String input) {}

    public record ApprovalCreateRequest(@NotBlank @Size(max = 64) String kind, @NotBlank @Size(max = 500) String summary,
                                        @Size(max = 4000) String payload) {}

    public record DecisionRequest(@NotNull Boolean approve, @Size(max = 500) String comment) {}

    // ── 응답 ──
    public record ProjectView(Long id, String name, String description, ProjectStatus status, String owner,
                              Instant createdAt, Instant updatedAt, long requirementCount, long taskCount, long doneCount) {}

    public record RequirementView(Long id, Long projectId, String title, String description, Priority priority,
                                  RequirementStatus status, String createdBy, Instant createdAt, Instant updatedAt) {}

    public record TaskView(Long id, Long projectId, Long requirementId, String title, String description, TaskStatus status,
                           Long assigneeAgentId, String createdBy, Instant createdAt, Instant updatedAt) {}

    public record AgentView(Long id, String code, String name, AgentRole role, boolean enabled, String allowedEnvironments) {}

    public record AiTaskView(Long id, Long projectId, Long taskId, Long agentId, AiTaskType type, Environment environment,
                             AiTaskStatus status, String input, String result, String requestedBy, Instant createdAt, Instant updatedAt) {}

    public record ExecutionView(Long id, Long aiTaskId, String step, String status, String detail, Instant startedAt, Instant finishedAt) {}

    public record ApprovalView(Long id, Long aiTaskId, String kind, String summary, String payload, ApprovalStatus status,
                               String requestedBy, String decidedBy, Instant decidedAt, String comment, Instant createdAt) {}

    public record HistoryView(Long id, String actor, String action, String target, String detail, Instant at) {}

    public record Summary(long projects, long openTasks, long doneTasks, long pendingApprovals, long queuedAiTasks,
                          java.util.List<HistoryView> recent) {}
}
