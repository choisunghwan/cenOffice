package com.cenoffice.pm;

import com.cenoffice.workspace.WsEnums.AiTaskStatus;
import java.util.List;

/** AI PM 입출력 타입. LLM 이 만든 값은 모두 "신뢰할 수 없는 입력"으로 취급하고 PlanValidator 로 검증한다. */
public final class PmTypes {
    private PmTypes() {}

    public record PmRequest(String projectName, String requirementText, List<String> answers) {}

    public record PlanRequirement(String title, String description, String priority) {}

    /** requirementIndex: PmResult.requirements 의 0 기반 위치 (없으면 null) */
    public record PlanTask(String title, String description, Integer requirementIndex) {}

    /** questions 가 비어 있지 않으면 추가 정보가 필요한 상태(명세/계획은 비어 있을 수 있음) */
    public record PmResult(List<String> questions, String spec, List<PlanRequirement> requirements, List<PlanTask> tasks) {}

    /** AiTask.pmState 에 JSON 으로 저장되는 진행 상태 */
    public record PmState(String phase, String projectName, String text, List<String> answers, List<String> questions, String spec,
                          List<PlanRequirement> requirements, List<PlanTask> tasks, String planSha256, Long approvalId,
                          String provider, String error) {
        public PmState with(String phase, List<String> questions, String spec, List<PlanRequirement> reqs, List<PlanTask> tasks,
                            String sha, Long approvalId, String error) {
            return new PmState(phase, projectName, text, answers, questions, spec, reqs, tasks, sha, approvalId, provider, error);
        }

        public PmState withAnswers(List<String> a) {
            return new PmState(phase, projectName, text, a, questions, spec, requirements, tasks, planSha256, approvalId, provider, error);
        }
    }

    public record PmInfo(String provider, boolean real, String model, String note) {}

    public record PmTaskDetail(Object task, Object executions, PmState state, AiTaskStatus status) {}
}
