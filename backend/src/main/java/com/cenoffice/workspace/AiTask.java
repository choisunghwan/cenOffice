package com.cenoffice.workspace;

import jakarta.persistence.*;

@Entity
@Table(name = "ai_tasks", indexes = @Index(columnList = "projectId"))
public class AiTask extends Timestamped {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long projectId;
    private Long taskId;
    @Column(nullable = false)
    private Long agentId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    private WsEnums.AiTaskType type;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8)
    private WsEnums.Environment environment;
    @Convert(converter = AiTaskStatusConverter.class) @Column(nullable = false, length = 24)
    private WsEnums.AiTaskStatus status = WsEnums.AiTaskStatus.QUEUED;
    @Column(length = 4000)
    private String input;
    @Column(length = 4000)
    private String result;
    @Column(nullable = false, length = 64)
    private String requestedBy;
    /** AI PM 작업의 진행 상태(JSON): 질문/명세/작업 계획/승인 ID 등. 결과 컬럼(4000자)보다 크게 저장한다. */
    @Column(length = 20000)
    private String pmState;

    protected AiTask() {}

    public AiTask(Long projectId, Long taskId, Long agentId, WsEnums.AiTaskType type, WsEnums.Environment env, String input, String requestedBy) {
        this.projectId = projectId;
        this.taskId = taskId;
        this.agentId = agentId;
        this.type = type;
        this.environment = env;
        this.input = input;
        this.requestedBy = requestedBy;
    }

    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public Long getTaskId() { return taskId; }
    public Long getAgentId() { return agentId; }
    public WsEnums.AiTaskType getType() { return type; }
    public WsEnums.Environment getEnvironment() { return environment; }
    public WsEnums.AiTaskStatus getStatus() { return status; }
    public String getInput() { return input; }
    public String getResult() { return result; }
    public String getRequestedBy() { return requestedBy; }

    public String getPmState() { return pmState; }
    public void setPmState(String s) { this.pmState = s; }

    public void setStatus(WsEnums.AiTaskStatus s) { this.status = s; }
    public void setResult(String r) { this.result = r == null ? null : r.substring(0, Math.min(r.length(), 4000)); }
}
