package com.cenoffice.pm;

import com.cenoffice.api.ApiException;
import com.cenoffice.pm.LlmProvider.LlmException;
import com.cenoffice.pm.PlanValidator.InvalidPlanException;
import com.cenoffice.pm.PmTypes.PmRequest;
import com.cenoffice.pm.PmTypes.PmResult;
import com.cenoffice.pm.PmTypes.PmState;
import com.cenoffice.workspace.*;
import com.cenoffice.workspace.WsDtos.AiTaskRequest;
import com.cenoffice.workspace.WsDtos.AiTaskView;
import com.cenoffice.workspace.WsEnums.AiTaskStatus;
import com.cenoffice.workspace.WsEnums.AiTaskType;
import com.cenoffice.workspace.WsEnums.Environment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * AI PM(아리) 업무 흐름: 요구사항 접수 → 분석 → (부족하면 질문) → 기능 명세 → 작업 목록 → 사용자 승인 요청.
 * 승인 전에는 프로젝트 데이터가 전혀 변경되지 않는다. LLM 은 LlmProvider 뒤에 있고, 출력은 PlanValidator 를 통과해야 저장된다.
 */
@Service
public class PmService {
    private static final Logger log = LoggerFactory.getLogger(PmService.class);
    private static final int MAX_ROUNDS = 2; // 추가 질문 라운드 상한

    private final WorkspaceService workspace;
    private final PmStore store;
    private final LlmProvider llm;
    private final PlanValidator validator;
    private final ProjectRepository projects;
    private final AiAgentRepository agents;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();

    public PmService(WorkspaceService workspace, PmStore store, LlmProvider llm, PlanValidator validator,
                     ProjectRepository projects, AiAgentRepository agents,
                     com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.mapper = mapper;
        this.workspace = workspace;
        this.store = store;
        this.llm = llm;
        this.validator = validator;
        this.projects = projects;
        this.agents = agents;
    }

    public PmTypes.PmInfo info() {
        return new PmTypes.PmInfo(llm.name(), llm.isReal(), llm.model(),
                llm.isReal() ? "외부 LLM 사용 중 — 요구사항 내용이 외부 서비스로 전송됩니다" : "Mock(규칙 기반) — 실제 LLM 이 아닙니다");
    }

    /** 요구사항 접수. 환경/에이전트 권한은 WorkspaceService.createAiTask 가 서버에서 검증한다(PM 작업은 DEV 전용). */
    public AiTaskView intake(Long projectId, String text, String actor) {
        String t = sanitize(text, 10, 4000);
        Project p = projects.findById(projectId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다"));
        AiAgent ari = agents.findByCode("ari").orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AI PM 이 설정되어 있지 않습니다"));
        AiTaskView task = workspace.createAiTask(new AiTaskRequest(projectId, null, ari.getId(), AiTaskType.DRAFT_SPEC, Environment.DEV, t), actor);
        PmState init = new PmState("received", p.getName(), t, List.of(), List.of(), null, List.of(), List.of(), null, null, llm.name(), null);
        store.step(task.id(), AiTaskStatus.QUEUED, "received", "고객 요구사항 접수 (" + t.length() + "자)", init);
        exec.submit(() -> run(task.id()));
        return workspace.aiTaskView(task.id());
    }

    /** 아리의 질문에 대한 답변. 요청자 본인 또는 관리자만 가능. */
    public void answer(Long taskId, String answers, String actor, boolean admin) {
        AiTask t = pmTask(taskId);
        if (!admin && !t.getRequestedBy().equals(actor)) throw new ApiException(HttpStatus.FORBIDDEN, "요청자 또는 관리자만 답변할 수 있습니다");
        if (t.getStatus() != AiTaskStatus.NEEDS_INPUT) throw new ApiException(HttpStatus.CONFLICT, "답변을 기다리는 작업이 아닙니다");
        String a = sanitize(answers, 1, 2000);
        PmState s = store.state(t);
        List<String> list = new ArrayList<>(s.answers());
        list.add(a);
        store.step(taskId, AiTaskStatus.QUEUED, "answered", actor + " 님이 답변했습니다", s.withAnswers(list));
        exec.submit(() -> run(taskId));
    }

    public AiTask pmTask(Long id) {
        AiTask t = store.task(id);
        if (t.getType() != AiTaskType.DRAFT_SPEC || t.getPmState() == null) throw new ApiException(HttpStatus.NOT_FOUND, "AI PM 작업이 아닙니다");
        return t;
    }

    public PmState state(AiTask t) { return store.state(t); }

    private void run(Long id) {
        PmState s = null;
        try {
            s = store.state(store.task(id));
            store.step(id, AiTaskStatus.RUNNING, "analyzing", "요구사항 분석 중 (" + llm.name() + (llm.isReal() ? " / 외부 LLM" : " / Mock") + ")", s);
            PmResult checked = validator.validate(llm.planRequirements(new PmRequest(s.projectName(), s.text(), s.answers())));

            if (!checked.questions().isEmpty()) {
                if (s.answers().size() >= MAX_ROUNDS) throw new InvalidPlanException("추가 정보가 계속 부족해 계획을 만들지 못했습니다");
                store.step(id, AiTaskStatus.NEEDS_INPUT, "needs-input", checked.questions().size() + "개 질문", s.with("needs_input", checked.questions(), null, List.of(), List.of(), null, null, null));
                return;
            }
            String sha = PlanHash.of(mapper, checked.spec(), checked.requirements(), checked.tasks());
            PmState ready = s.with("plan_ready", List.of(), checked.spec(), checked.requirements(), checked.tasks(), sha, null, null);
            store.step(id, AiTaskStatus.RUNNING, "spec-drafted", "기능 명세서 초안 생성", ready);
            store.step(id, AiTaskStatus.RUNNING, "tasks-generated", "요구사항 " + checked.requirements().size() + "건 / 작업 " + checked.tasks().size() + "건 생성", ready);
            String summary = "요구사항 " + checked.requirements().size() + "건, 작업 " + checked.tasks().size() + "건을 프로젝트에 등록";
            Long approvalId = store.createApproval(id, summary, sha);
            store.step(id, AiTaskStatus.WAITING_APPROVAL, "approval-requested", "사용자 승인 요청 #" + approvalId + " — 승인 전에는 아무것도 등록되지 않습니다",
                    ready.with("waiting_approval", List.of(), checked.spec(), checked.requirements(), checked.tasks(), sha, approvalId, null));
        } catch (InvalidPlanException e) {
            fail(id, s, "validation-failed", e.getMessage());
        } catch (LlmException e) {
            log.warn("LLM 오류: {}", e.getMessage());
            fail(id, s, "llm-error", e.getMessage());
        } catch (Exception e) {
            log.error("AI PM 실행 오류", e);
            fail(id, s, "error", "내부 오류로 작업을 완료하지 못했습니다");
        }
    }

    private void fail(Long id, PmState s, String step, String message) {
        try {
            PmState f = s == null ? null : s.with("failed", List.of(), null, List.of(), List.of(), null, null, message);
            store.step(id, AiTaskStatus.FAILED, step, message, f);
        } catch (Exception e) {
            log.error("실패 상태 기록 실패", e);
        }
    }

    private static String sanitize(String raw, int min, int max) {
        String s = raw == null ? "" : raw.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").trim();
        if (s.length() < min) throw new ApiException(HttpStatus.BAD_REQUEST, "내용이 너무 짧습니다 (최소 " + min + "자)");
        if (s.length() > max) throw new ApiException(HttpStatus.BAD_REQUEST, "내용이 너무 깁니다 (최대 " + max + "자)");
        return s;
    }
}
