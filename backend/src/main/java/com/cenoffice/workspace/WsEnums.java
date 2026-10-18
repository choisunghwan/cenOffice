package com.cenoffice.workspace;

public final class WsEnums {
    private WsEnums() {}

    public enum ProjectStatus { ACTIVE, ARCHIVED }

    public enum Priority { LOW, MEDIUM, HIGH }

    public enum RequirementStatus { DRAFT, APPROVED, IN_PROGRESS, DONE }

    public enum TaskStatus { TODO, IN_PROGRESS, REVIEW, DONE }

    public enum AgentRole { PM, DEV, QA }

    /** 실행 환경. AI 에이전트의 작업 범위는 서버에서 환경별로 제한한다. */
    public enum Environment { DEV, STG, PROD }

    /** AI 작업이 필요로 하는 권한 수준. DEPLOY 는 어떤 환경에서도 AI 에게 허용하지 않는다. */
    public enum Capability { READ, TEST, DEVELOP, DEPLOY }

    public enum AiTaskType {
        STATUS_QUERY(Capability.READ),
        ANALYZE_REQUIREMENT(Capability.READ),
        DRAFT_SPEC(Capability.DEVELOP),
        GENERATE_TASKS(Capability.DEVELOP),
        CODE_CHANGE(Capability.DEVELOP),
        RUN_TESTS(Capability.TEST);

        private final Capability capability;

        AiTaskType(Capability c) { this.capability = c; }

        public Capability capability() { return capability; }
    }

    public enum AiTaskStatus { QUEUED, RUNNING, NEEDS_INPUT, WAITING_APPROVAL, DONE, FAILED, REJECTED }

    public enum ApprovalStatus { PENDING, APPROVED, REJECTED }
}
