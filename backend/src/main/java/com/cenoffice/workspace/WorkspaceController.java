package com.cenoffice.workspace;

import com.cenoffice.workspace.WsDtos.*;
import com.cenoffice.workspace.WsEnums.ApprovalStatus;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 조회는 인증된 모든 사용자, 변경은 USER/ADMIN. 삭제 등 민감 작업의 추가 검사는 서비스에서 수행한다. */
@RestController
@RequestMapping("/api")
public class WorkspaceController {
    private static final String WRITE = "hasAnyRole('ADMIN','USER')";

    private final WorkspaceService svc;

    public WorkspaceController(WorkspaceService svc) { this.svc = svc; }

    private static boolean admin(Authentication a) { return a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("ROLE_ADMIN")); }

    @GetMapping("/workspace/summary")
    public Summary summary() { return svc.summary(); }

    // 프로젝트
    @GetMapping("/projects")
    public List<ProjectView> projects() { return svc.listProjects(); }

    @GetMapping("/projects/{id}")
    public ProjectView project(@PathVariable Long id) { return svc.getProject(id); }

    @PostMapping("/projects") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(WRITE)
    public ProjectView createProject(@Valid @RequestBody ProjectRequest r, Authentication a) { return svc.createProject(r, a.getName()); }

    @PutMapping("/projects/{id}") @PreAuthorize(WRITE)
    public ProjectView updateProject(@PathVariable Long id, @Valid @RequestBody ProjectRequest r, Authentication a) { return svc.updateProject(id, r, a.getName()); }

    @DeleteMapping("/projects/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize(WRITE)
    public void deleteProject(@PathVariable Long id, Authentication a) { svc.deleteProject(id, a.getName(), admin(a)); }

    // 요구사항
    @GetMapping("/projects/{id}/requirements")
    public List<RequirementView> requirements(@PathVariable Long id) { return svc.listRequirements(id); }

    @PostMapping("/projects/{id}/requirements") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(WRITE)
    public RequirementView createRequirement(@PathVariable Long id, @Valid @RequestBody RequirementRequest r, Authentication a) { return svc.createRequirement(id, r, a.getName()); }

    @PutMapping("/requirements/{id}") @PreAuthorize(WRITE)
    public RequirementView updateRequirement(@PathVariable Long id, @Valid @RequestBody RequirementRequest r, Authentication a) { return svc.updateRequirement(id, r, a.getName()); }

    @DeleteMapping("/requirements/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize(WRITE)
    public void deleteRequirement(@PathVariable Long id, Authentication a) { svc.deleteRequirement(id, a.getName()); }

    // 작업
    @GetMapping("/projects/{id}/tasks")
    public List<TaskView> tasks(@PathVariable Long id) { return svc.listTasks(id); }

    @PostMapping("/projects/{id}/tasks") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(WRITE)
    public TaskView createTask(@PathVariable Long id, @Valid @RequestBody TaskRequest r, Authentication a) { return svc.createTask(id, r, a.getName()); }

    @PutMapping("/tasks/{id}") @PreAuthorize(WRITE)
    public TaskView updateTask(@PathVariable Long id, @Valid @RequestBody TaskRequest r, Authentication a) { return svc.updateTask(id, r, a.getName()); }

    @DeleteMapping("/tasks/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize(WRITE)
    public void deleteTask(@PathVariable Long id, Authentication a) { svc.deleteTask(id, a.getName()); }

    // AI 에이전트 / AI 작업
    @GetMapping("/agents")
    public List<AgentView> agents() { return svc.listAgents(); }

    @GetMapping("/ai-tasks")
    public List<AiTaskView> aiTasks(@RequestParam(required = false) Long projectId) { return svc.listAiTasks(projectId); }

    @PostMapping("/ai-tasks") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(WRITE)
    public AiTaskView createAiTask(@Valid @RequestBody AiTaskRequest r, Authentication a) { return svc.createAiTask(r, a.getName()); }

    @GetMapping("/ai-tasks/{id}/executions")
    public List<ExecutionView> executions(@PathVariable Long id) { return svc.executions(id); }

    @PostMapping("/ai-tasks/{id}/approval-requests") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(WRITE)
    public ApprovalView requestApproval(@PathVariable Long id, @Valid @RequestBody ApprovalCreateRequest r, Authentication a) { return svc.requestApproval(id, r, a.getName()); }

    // 승인
    @GetMapping("/approvals")
    public List<ApprovalView> approvals(@RequestParam(required = false) ApprovalStatus status) { return svc.listApprovals(status); }

    @PostMapping("/approvals/{id}/decision") @PreAuthorize(WRITE)
    public ApprovalView decide(@PathVariable Long id, @Valid @RequestBody DecisionRequest d, Authentication a) { return svc.decide(id, d, a.getName(), admin(a)); }

    // 이력 (예: target=task:12)
    @GetMapping("/history")
    public List<HistoryView> history(@RequestParam String target, @RequestParam(defaultValue = "50") int limit) { return svc.history(target, limit); }
}
