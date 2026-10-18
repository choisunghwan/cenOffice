package com.cenoffice.workspace;

import com.cenoffice.api.ApiException;
import com.cenoffice.api.AuditService;
import com.cenoffice.device.DeviceTypes.OfficeEvent;
import com.cenoffice.domain.AuditLog;
import com.cenoffice.repo.AuditLogRepository;
import com.cenoffice.workspace.WsDtos.*;
import com.cenoffice.workspace.WsEnums.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 프로젝트·요구사항·작업·AI 작업·승인 관리. 모든 변경은 감사 로그와 WebSocket 이벤트를 남긴다. */
@Service
@Transactional
public class WorkspaceService {
    private static final Set<String> HISTORY_PREFIXES = Set.of("project", "requirement", "task", "aitask", "approval");

    private final ProjectRepository projects;
    private final RequirementRepository requirements;
    private final ProjectTaskRepository tasks;
    private final AiAgentRepository agents;
    private final AiTaskRepository aiTasks;
    private final AgentExecutionRepository executions;
    private final ApprovalRequestRepository approvals;
    private final AuditLogRepository audits;
    private final AuditService audit;
    private final EnvironmentPolicy policy;
    private final ApplicationEventPublisher events;
    private final com.cenoffice.pm.PlanApplier planApplier;

    public WorkspaceService(ProjectRepository projects, RequirementRepository requirements, ProjectTaskRepository tasks,
                            AiAgentRepository agents, AiTaskRepository aiTasks, AgentExecutionRepository executions,
                            ApprovalRequestRepository approvals, AuditLogRepository audits, AuditService audit,
                            EnvironmentPolicy policy, ApplicationEventPublisher events, com.cenoffice.pm.PlanApplier planApplier) {
        this.planApplier = planApplier;
        this.projects = projects;
        this.requirements = requirements;
        this.tasks = tasks;
        this.agents = agents;
        this.aiTasks = aiTasks;
        this.executions = executions;
        this.approvals = approvals;
        this.audits = audits;
        this.audit = audit;
        this.policy = policy;
        this.events = events;
    }

    // ───────────────── 프로젝트 ─────────────────
    @Transactional(readOnly = true)
    public List<ProjectView> listProjects() { return projects.findAllByOrderByUpdatedAtDesc().stream().map(this::view).toList(); }

    @Transactional(readOnly = true)
    public ProjectView getProject(Long id) { return view(project(id)); }

    public ProjectView createProject(ProjectRequest r, String actor) {
        Project p = projects.save(new Project(r.name().trim(), r.description(), actor));
        audit.log(actor, "PROJECT_CREATED", "project:" + p.getId(), p.getName());
        changed("project", p.getId(), p.getId());
        return view(p);
    }

    public ProjectView updateProject(Long id, ProjectRequest r, String actor) {
        Project p = project(id);
        p.update(r.name().trim(), r.description(), r.status());
        audit.log(actor, "PROJECT_UPDATED", "project:" + id, p.getName() + " / " + p.getStatus());
        changed("project", id, id);
        return view(p);
    }

    /** 삭제는 소유자 또는 관리자만 가능. 하위 데이터(요구사항/작업/AI 작업/승인)도 함께 삭제한다. */
    public void deleteProject(Long id, String actor, boolean admin) {
        Project p = project(id);
        if (!admin && !p.getOwner().equals(actor)) throw new ApiException(HttpStatus.FORBIDDEN, "프로젝트 소유자 또는 관리자만 삭제할 수 있습니다");
        for (AiTask t : aiTasks.findByProjectId(id)) {
            approvals.deleteByAiTaskId(t.getId());
            executions.deleteByAiTaskId(t.getId());
        }
        aiTasks.deleteAll(aiTasks.findByProjectId(id));
        tasks.deleteByProjectId(id);
        requirements.deleteByProjectId(id);
        projects.delete(p);
        audit.log(actor, "PROJECT_DELETED", "project:" + id, p.getName());
        changed("project", id, id);
    }

    // ───────────────── 요구사항 ─────────────────
    @Transactional(readOnly = true)
    public List<RequirementView> listRequirements(Long projectId) {
        project(projectId);
        return requirements.findByProjectIdOrderByCreatedAtDesc(projectId).stream().map(WorkspaceService::view).toList();
    }

    public RequirementView createRequirement(Long projectId, RequirementRequest r, String actor) {
        project(projectId);
        Requirement q = requirements.save(new Requirement(projectId, r.title().trim(), r.description(), r.priority(), actor));
        if (r.status() != null) q.update(q.getTitle(), q.getDescription(), null, r.status());
        audit.log(actor, "REQUIREMENT_CREATED", "requirement:" + q.getId(), q.getTitle());
        changed("requirement", q.getId(), projectId);
        return view(q);
    }

    public RequirementView updateRequirement(Long id, RequirementRequest r, String actor) {
        Requirement q = requirement(id);
        q.update(r.title().trim(), r.description(), r.priority(), r.status());
        audit.log(actor, "REQUIREMENT_UPDATED", "requirement:" + id, q.getTitle() + " / " + q.getStatus());
        changed("requirement", id, q.getProjectId());
        return view(q);
    }

    public void deleteRequirement(Long id, String actor) {
        Requirement q = requirement(id);
        tasks.findByRequirementId(id).forEach(t -> t.update(t.getTitle(), t.getDescription(), null, t.getStatus(), t.getAssigneeAgentId()));
        requirements.delete(q);
        audit.log(actor, "REQUIREMENT_DELETED", "requirement:" + id, q.getTitle());
        changed("requirement", id, q.getProjectId());
    }

    // ───────────────── 작업 ─────────────────
    @Transactional(readOnly = true)
    public List<TaskView> listTasks(Long projectId) {
        project(projectId);
        return tasks.findByProjectIdOrderByCreatedAtAsc(projectId).stream().map(WorkspaceService::view).toList();
    }

    public TaskView createTask(Long projectId, TaskRequest r, String actor) {
        project(projectId);
        checkRequirement(projectId, r.requirementId());
        checkAssignee(r.assigneeAgentId());
        ProjectTask t = tasks.save(new ProjectTask(projectId, r.requirementId(), r.title().trim(), r.description(), actor));
        t.update(t.getTitle(), t.getDescription(), r.requirementId(), r.status(), r.assigneeAgentId());
        audit.log(actor, "TASK_CREATED", "task:" + t.getId(), t.getTitle());
        changed("task", t.getId(), projectId);
        return view(t);
    }

    public TaskView updateTask(Long id, TaskRequest r, String actor) {
        ProjectTask t = task(id);
        checkRequirement(t.getProjectId(), r.requirementId());
        checkAssignee(r.assigneeAgentId());
        TaskStatus before = t.getStatus();
        Long beforeAssignee = t.getAssigneeAgentId();
        t.update(r.title().trim(), r.description(), r.requirementId(), r.status(), r.assigneeAgentId());
        audit.log(actor, "TASK_UPDATED", "task:" + id,
                "status " + before + "→" + t.getStatus() + ", assignee " + beforeAssignee + "→" + t.getAssigneeAgentId());
        changed("task", id, t.getProjectId());
        return view(t);
    }

    public void deleteTask(Long id, String actor) {
        ProjectTask t = task(id);
        tasks.delete(t);
        audit.log(actor, "TASK_DELETED", "task:" + id, t.getTitle());
        changed("task", id, t.getProjectId());
    }

    // ───────────────── AI 에이전트 / AI 작업 ─────────────────
    @Transactional(readOnly = true)
    public List<AgentView> listAgents() {
        return agents.findAll().stream().map(a -> new AgentView(a.getId(), a.getCode(), a.getName(), a.getRole(), a.isEnabled(), a.getAllowedEnvironments())).toList();
    }

    @Transactional(readOnly = true)
    public List<AiTaskView> listAiTasks(Long projectId) {
        List<AiTask> list = projectId == null ? aiTasks.findTop50ByOrderByCreatedAtDesc() : aiTasks.findByProjectIdOrderByCreatedAtDesc(projectId);
        return list.stream().map(WorkspaceService::view).toList();
    }

    @Transactional(readOnly = true)
    public AiTaskView aiTaskView(Long id) { return view(aiTask(id)); }

    @Transactional(readOnly = true)
    public List<ExecutionView> executions(Long aiTaskId) {
        aiTask(aiTaskId);
        return executions.findByAiTaskIdOrderByStartedAtAsc(aiTaskId).stream()
                .map(e -> new ExecutionView(e.getId(), e.getAiTaskId(), e.getStep(), e.getStatus(), e.getDetail(), e.getStartedAt(), e.getFinishedAt())).toList();
    }

    /**
     * AI 작업 접수. 에이전트 활성 여부, 에이전트에 부여된 환경, 환경별 허용 작업을 서버에서 모두 검증한다.
     * (실행 엔진은 Phase 3B — 현재는 QUEUED 로 기록만 한다.)
     */
    public AiTaskView createAiTask(AiTaskRequest r, String actor) {
        project(r.projectId());
        if (r.taskId() != null && task(r.taskId()).getProjectId() != null && !task(r.taskId()).getProjectId().equals(r.projectId()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "작업이 해당 프로젝트에 속해 있지 않습니다");
        AiAgent agent = agents.findById(r.agentId()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AI 에이전트를 찾을 수 없습니다"));
        if (!agent.isEnabled()) throw new ApiException(HttpStatus.BAD_REQUEST, "비활성화된 에이전트입니다");
        if (!agent.environments().contains(r.environment()) || !policy.agentMayBeGrantedEnvironment(r.environment())) {
            deny(actor, agent, r, "agent has no access to environment");
            throw new ApiException(HttpStatus.FORBIDDEN, "이 에이전트는 " + r.environment() + " 환경에 접근할 수 없습니다");
        }
        if (!policy.allows(r.environment(), r.type())) {
            deny(actor, agent, r, "environment policy");
            throw new ApiException(HttpStatus.FORBIDDEN, r.environment() + " 환경에서는 " + r.type() + " 작업을 실행할 수 없습니다");
        }
        AiTask t = aiTasks.save(new AiTask(r.projectId(), r.taskId(), agent.getId(), r.type(), r.environment(), r.input(), actor));
        executions.save(new AgentExecution(t.getId(), "queued", "OK", "작업이 접수되었습니다 (자동 실행은 AI PM 요구사항 분석(DRAFT_SPEC)만 지원, 그 외 유형은 대기 상태로 기록만 됩니다)"));
        audit.log(actor, "AI_TASK_CREATED", "aitask:" + t.getId(), agent.getCode() + " " + r.type() + " @" + r.environment());
        changed("aitask", t.getId(), r.projectId());
        return view(t);
    }

    private void deny(String actor, AiAgent agent, AiTaskRequest r, String reason) {
        audit.log(actor, "AI_TASK_DENIED", "agent:" + agent.getCode(), r.type() + " @" + r.environment() + " — " + reason);
    }

    // ───────────────── 승인 ─────────────────
    @Transactional(readOnly = true)
    public List<ApprovalView> listApprovals(ApprovalStatus status) {
        List<ApprovalRequest> list = status == null ? approvals.findTop50ByOrderByCreatedAtDesc() : approvals.findByStatusOrderByCreatedAtDesc(status);
        return list.stream().map(WorkspaceService::view).toList();
    }

    /** 사용자(또는 3B 의 AI PM)가 AI 작업에 대한 승인을 요청한다. 승인 전에는 payload 가 적용되지 않는다. */
    public ApprovalView requestApproval(Long aiTaskId, ApprovalCreateRequest r, String actor) {
        AiTask t = aiTask(aiTaskId);
        if (t.getStatus() == AiTaskStatus.DONE || t.getStatus() == AiTaskStatus.REJECTED || t.getStatus() == AiTaskStatus.FAILED)
            throw new ApiException(HttpStatus.CONFLICT, "이미 종료된 AI 작업입니다");
        // 수동 승인 요청은 "검토 요청"일 뿐 어떤 변경도 적용하지 않는다. 적용 가능한 종류(REGISTER_PLAN)는 서버 내부 흐름에서만 생성된다.
        if (!"MANUAL_REVIEW".equals(r.kind().trim())) throw new ApiException(HttpStatus.BAD_REQUEST, "수동 승인 요청은 MANUAL_REVIEW 종류만 가능합니다");
        ApprovalRequest a = approvals.save(new ApprovalRequest(aiTaskId, r.kind().trim(), r.summary().trim(), r.payload(), actor));
        t.setStatus(AiTaskStatus.WAITING_APPROVAL);
        executions.save(new AgentExecution(aiTaskId, "approval-requested", "OK", r.summary()));
        audit.log(actor, "APPROVAL_REQUESTED", "approval:" + a.getId(), r.kind() + ": " + r.summary());
        changed("approval", a.getId(), t.getProjectId());
        return view(a);
    }

    /** 승인/거절. 요청자 본인은 승인할 수 없다(관리자 제외). 결정 후 적용은 서버가 payload 를 재검증하여 수행한다(3B). */
    public ApprovalView decide(Long id, DecisionRequest d, String actor, boolean admin) {
        ApprovalRequest a = approvals.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "승인 요청을 찾을 수 없습니다"));
        if (a.getStatus() != ApprovalStatus.PENDING) throw new ApiException(HttpStatus.CONFLICT, "이미 처리된 요청입니다");
        if (a.getRequestedBy().equals(actor) && !admin)
            throw new ApiException(HttpStatus.FORBIDDEN, "본인이 요청한 승인은 직접 승인할 수 없습니다 (다른 사용자 또는 관리자 필요)");
        a.decide(d.approve(), actor, d.comment());
        AiTask t = aiTask(a.getAiTaskId());
        executions.save(new AgentExecution(t.getId(), d.approve() ? "approved" : "rejected", "OK", actor + (d.comment() == null ? "" : ": " + d.comment())));
        if (d.approve() && "REGISTER_PLAN".equals(a.getKind())) {
            // AI PM 계획은 승인 직후 서버가 검증을 다시 거쳐 적용한다(작업은 DONE 으로). 실패하면 예외로 이 트랜잭션(승인 포함)이 롤백된다.
            planApplier.apply(a, actor);
        } else {
            t.setStatus(d.approve() ? AiTaskStatus.QUEUED : AiTaskStatus.REJECTED);
        }
        audit.log(actor, d.approve() ? "APPROVAL_APPROVED" : "APPROVAL_REJECTED", "approval:" + id, a.getSummary());
        changed("approval", id, t.getProjectId());
        return view(a);
    }

    // ───────────────── 이력 / 요약 ─────────────────
    @Transactional(readOnly = true)
    public List<HistoryView> history(String target, int limit) {
        String prefix = target == null ? "" : target.split(":", 2)[0];
        if (!HISTORY_PREFIXES.contains(prefix) || !target.contains(":")) throw new ApiException(HttpStatus.BAD_REQUEST, "조회할 수 없는 대상입니다");
        return audits.findByTargetOrderByCreatedAtDesc(target, PageRequest.of(0, Math.min(Math.max(limit, 1), 100))).stream().map(WorkspaceService::view).toList();
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        long open = 0, done = 0;
        for (Project p : projects.findAll()) {
            long d = tasks.countByProjectIdAndStatus(p.getId(), TaskStatus.DONE);
            done += d;
            open += tasks.countByProjectId(p.getId()) - d;
        }
        long queued = aiTasks.findTop50ByOrderByCreatedAtDesc().stream().filter(t -> t.getStatus() == AiTaskStatus.QUEUED).count();
        List<HistoryView> recent = audits.findRecentWorkspace(PageRequest.of(0, 8)).stream().map(WorkspaceService::view).toList();
        return new Summary(projects.count(), open, done, approvals.countByStatus(ApprovalStatus.PENDING), queued, recent);
    }

    // ───────────────── 내부 ─────────────────
    private Project project(Long id) { return projects.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다")); }
    private Requirement requirement(Long id) { return requirements.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "요구사항을 찾을 수 없습니다")); }
    private ProjectTask task(Long id) { return tasks.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "작업을 찾을 수 없습니다")); }
    private AiTask aiTask(Long id) { return aiTasks.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AI 작업을 찾을 수 없습니다")); }

    private void checkRequirement(Long projectId, Long requirementId) {
        if (requirementId != null && !requirement(requirementId).getProjectId().equals(projectId))
            throw new ApiException(HttpStatus.BAD_REQUEST, "요구사항이 해당 프로젝트에 속해 있지 않습니다");
    }

    private void checkAssignee(Long agentId) {
        if (agentId != null && !agents.existsById(agentId)) throw new ApiException(HttpStatus.BAD_REQUEST, "존재하지 않는 AI 에이전트입니다");
    }

    private void changed(String kind, Long id, Long projectId) {
        events.publishEvent(new OfficeEvent("workspace.changed", Map.of("kind", kind, "id", id, "projectId", projectId)));
    }

    private ProjectView view(Project p) {
        return new ProjectView(p.getId(), p.getName(), p.getDescription(), p.getStatus(), p.getOwner(), p.getCreatedAt(), p.getUpdatedAt(),
                requirements.countByProjectId(p.getId()), tasks.countByProjectId(p.getId()), tasks.countByProjectIdAndStatus(p.getId(), TaskStatus.DONE));
    }

    private static RequirementView view(Requirement q) {
        return new RequirementView(q.getId(), q.getProjectId(), q.getTitle(), q.getDescription(), q.getPriority(), q.getStatus(), q.getCreatedBy(), q.getCreatedAt(), q.getUpdatedAt());
    }

    private static TaskView view(ProjectTask t) {
        return new TaskView(t.getId(), t.getProjectId(), t.getRequirementId(), t.getTitle(), t.getDescription(), t.getStatus(), t.getAssigneeAgentId(), t.getCreatedBy(), t.getCreatedAt(), t.getUpdatedAt());
    }

    private static AiTaskView view(AiTask t) {
        return new AiTaskView(t.getId(), t.getProjectId(), t.getTaskId(), t.getAgentId(), t.getType(), t.getEnvironment(), t.getStatus(), t.getInput(), t.getResult(), t.getRequestedBy(), t.getCreatedAt(), t.getUpdatedAt());
    }

    private static ApprovalView view(ApprovalRequest a) {
        return new ApprovalView(a.getId(), a.getAiTaskId(), a.getKind(), a.getSummary(), a.getPayload(), a.getStatus(), a.getRequestedBy(), a.getDecidedBy(), a.getDecidedAt(), a.getComment(), a.getCreatedAt());
    }

    private static HistoryView view(AuditLog l) {
        return new HistoryView(l.getId(), l.getActor(), l.getAction(), l.getTarget(), l.getDetail(), l.getCreatedAt());
    }
}
