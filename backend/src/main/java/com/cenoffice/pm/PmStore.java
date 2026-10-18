package com.cenoffice.pm;

import com.cenoffice.api.ApiException;
import com.cenoffice.api.AuditService;
import com.cenoffice.device.DeviceTypes.OfficeEvent;
import com.cenoffice.pm.PmTypes.PmState;
import com.cenoffice.workspace.AgentExecution;
import com.cenoffice.workspace.AgentExecutionRepository;
import com.cenoffice.workspace.AiTask;
import com.cenoffice.workspace.AiTaskRepository;
import com.cenoffice.workspace.ApprovalRequest;
import com.cenoffice.workspace.ApprovalRequestRepository;
import com.cenoffice.workspace.WsEnums.AiTaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** AI PM 작업 상태 저장소. 모든 AI 작업 단계는 DB(AgentExecution/AiTask.pmState)에 기록된다. */
@Service
public class PmStore {
    public static final String AGENT_ACTOR = "ai:ari";

    private final AiTaskRepository aiTasks;
    private final AgentExecutionRepository executions;
    private final ApprovalRequestRepository approvals;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final ApplicationEventPublisher events;

    public PmStore(AiTaskRepository aiTasks, AgentExecutionRepository executions, ApprovalRequestRepository approvals,
                   AuditService audit, ObjectMapper mapper, ApplicationEventPublisher events) {
        this.aiTasks = aiTasks;
        this.executions = executions;
        this.approvals = approvals;
        this.audit = audit;
        this.mapper = mapper;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public AiTask task(Long id) {
        return aiTasks.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AI 작업을 찾을 수 없습니다"));
    }

    public PmState state(AiTask t) {
        try {
            return t.getPmState() == null ? null : mapper.readValue(t.getPmState(), PmState.class);
        } catch (Exception e) {
            throw new IllegalStateException("PM 상태를 읽을 수 없습니다", e);
        }
    }

    /** 상태 전이 + 실행 단계 기록 + (선택) PM 상태 저장 */
    @Transactional
    public void step(Long id, AiTaskStatus status, String step, String detail, PmState state) {
        AiTask t = task(id);
        if (status != null) t.setStatus(status);
        if (state != null) {
            try { t.setPmState(mapper.writeValueAsString(state)); } catch (Exception e) { throw new IllegalStateException(e); }
        }
        executions.save(new AgentExecution(id, step, status == AiTaskStatus.FAILED ? "FAILED" : "OK", detail));
        aiTasks.save(t);
        events.publishEvent(new OfficeEvent("workspace.changed", Map.of("kind", "aitask", "id", id, "projectId", t.getProjectId())));
    }

    @Transactional
    public Long createApproval(Long aiTaskId, String summary, String sha) {
        AiTask t = task(aiTaskId);
        // payload 에는 계획 본문을 넣지 않고 "무엇을 승인하는지"(작업 ID + 해시)만 넣는다. 본문은 서버가 pmState 에서 읽어 해시를 대조한다.
        ApprovalRequest a = approvals.save(new ApprovalRequest(aiTaskId, "REGISTER_PLAN", summary,
                "{\"aiTaskId\":" + aiTaskId + ",\"planSha256\":\"" + sha + "\"}", AGENT_ACTOR));
        audit.log(AGENT_ACTOR, "APPROVAL_REQUESTED", "approval:" + a.getId(), summary);
        events.publishEvent(new OfficeEvent("workspace.changed", Map.of("kind", "approval", "id", a.getId(), "projectId", t.getProjectId())));
        return a.getId();
    }
}
