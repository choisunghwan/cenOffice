package com.cenoffice.pm;

import com.cenoffice.api.ApiException;
import com.cenoffice.api.AuditService;
import com.cenoffice.device.DeviceTypes.OfficeEvent;
import com.cenoffice.pm.PlanValidator.InvalidPlanException;
import com.cenoffice.pm.PmTypes.PlanRequirement;
import com.cenoffice.pm.PmTypes.PlanTask;
import com.cenoffice.pm.PmTypes.PmResult;
import com.cenoffice.pm.PmTypes.PmState;
import com.cenoffice.workspace.*;
import com.cenoffice.workspace.WsEnums.AiTaskStatus;
import com.cenoffice.workspace.WsEnums.Capability;
import com.cenoffice.workspace.WsEnums.Priority;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 승인된 AI PM 계획을 프로젝트에 등록한다. AI 의 응답 텍스트가 아니라, 서버가 검증해 저장한 계획만 적용한다.
 * 적용 직전에 (1) 작업 상태 (2) 승인된 해시와 현재 계획의 일치 (3) 스키마 재검증 (4) 환경/에이전트 권한을 다시 확인한다.
 * 어느 하나라도 실패하면 예외로 승인 트랜잭션 전체가 롤백된다.
 */
@Component
public class PlanApplier {
    private final AiTaskRepository aiTasks;
    private final ProjectRepository projects;
    private final RequirementRepository requirements;
    private final ProjectTaskRepository tasks;
    private final AiAgentRepository agents;
    private final AgentExecutionRepository executions;
    private final PlanValidator validator;
    private final EnvironmentPolicy policy;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final ApplicationEventPublisher events;

    public PlanApplier(AiTaskRepository aiTasks, ProjectRepository projects, RequirementRepository requirements, ProjectTaskRepository tasks,
                       AiAgentRepository agents, AgentExecutionRepository executions, PlanValidator validator, EnvironmentPolicy policy,
                       AuditService audit, ObjectMapper mapper, ApplicationEventPublisher events) {
        this.aiTasks = aiTasks;
        this.projects = projects;
        this.requirements = requirements;
        this.tasks = tasks;
        this.agents = agents;
        this.executions = executions;
        this.validator = validator;
        this.policy = policy;
        this.audit = audit;
        this.mapper = mapper;
        this.events = events;
    }

    public void apply(ApprovalRequest approval, String approver) {
        AiTask t = aiTasks.findById(approval.getAiTaskId()).orElseThrow(() -> refuse(approver, approval, "AI 작업을 찾을 수 없습니다"));
        if (t.getStatus() != AiTaskStatus.WAITING_APPROVAL) throw refuse(approver, approval, "승인 대기 상태의 작업이 아닙니다");
        PmState s;
        String approvedSha;
        try {
            s = mapper.readValue(t.getPmState(), PmState.class);
            approvedSha = mapper.readTree(approval.getPayload()).path("planSha256").asText("");
        } catch (Exception e) {
            throw refuse(approver, approval, "계획 정보를 읽을 수 없습니다");
        }
        String current = PlanHash.of(mapper, s.spec(), s.requirements(), s.tasks());
        if (!current.equals(approvedSha) || !current.equals(s.planSha256()))
            throw refuse(approver, approval, "승인된 계획과 현재 계획이 달라 적용을 중단했습니다");

        PmResult checked;
        try {
            checked = validator.validate(new PmResult(List.of(), s.spec(), s.requirements(), s.tasks()));
        } catch (InvalidPlanException e) {
            throw refuse(approver, approval, "계획 검증 실패: " + e.getMessage());
        }
        AiAgent agent = agents.findById(t.getAgentId()).orElseThrow(() -> refuse(approver, approval, "에이전트를 찾을 수 없습니다"));
        if (!agent.isEnabled() || !agent.environments().contains(t.getEnvironment()) || !policy.allows(t.getEnvironment(), Capability.DEVELOP))
            throw refuse(approver, approval, t.getEnvironment() + " 환경에서는 프로젝트 변경을 적용할 수 없습니다");
        if (!projects.existsById(t.getProjectId())) throw refuse(approver, approval, "프로젝트가 존재하지 않습니다");

        List<Long> reqIds = new ArrayList<>();
        for (PlanRequirement r : checked.requirements()) {
            Requirement q = requirements.save(new Requirement(t.getProjectId(), r.title(), r.description(), Priority.valueOf(r.priority()), PmStore.AGENT_ACTOR));
            reqIds.add(q.getId());
        }
        for (PlanTask p : checked.tasks()) {
            Long reqId = p.requirementIndex() == null ? null : reqIds.get(p.requirementIndex());
            tasks.save(new ProjectTask(t.getProjectId(), reqId, p.title(), p.description(), PmStore.AGENT_ACTOR));
        }
        String summary = "요구사항 " + reqIds.size() + "건, 작업 " + checked.tasks().size() + "건을 프로젝트에 등록했습니다";
        try {
            t.setPmState(mapper.writeValueAsString(s.with("applied", List.of(), s.spec(), s.requirements(), s.tasks(), s.planSha256(), s.approvalId(), null)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        t.setStatus(AiTaskStatus.DONE);
        t.setResult(summary);
        executions.save(new AgentExecution(t.getId(), "applied", "OK", "승인자 " + approver + ": " + summary));
        audit.log(approver, "PLAN_APPLIED", "aitask:" + t.getId(), summary);
        events.publishEvent(new OfficeEvent("workspace.changed", Map.of("kind", "project", "id", t.getProjectId(), "projectId", t.getProjectId())));
    }

    private ApiException refuse(String approver, ApprovalRequest a, String why) {
        audit.log(approver, "PLAN_APPLY_REFUSED", "approval:" + a.getId(), why); // REQUIRES_NEW 라 롤백되어도 남는다
        return new ApiException(HttpStatus.CONFLICT, why);
    }
}
