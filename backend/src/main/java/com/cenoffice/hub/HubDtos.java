package com.cenoffice.hub;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** AI HUB API 입출력 타입. 엔티티는 외부로 직접 노출하지 않는다. */
public final class HubDtos {
    private HubDtos() {}

    public record PageView<T>(List<T> items, long total, int page, int size) {}

    public record CategoryView(String code, String name) {}

    public record SkillSummary(Long id, String name, String description, String category, String categoryName, String icon, String visibility,
                               String team, String status, String owner, String ownerName, String version, List<String> tags,
                               long executions, long succeeded, double ratingAvg, long ratingCount, boolean sample, Instant updatedAt,
                               String executionState, String title) {}

    public record VersionView(Long id, String version, String status, String createdBy, Instant createdAt, String sha256, String packageKind,
                              List<String> riskFlags, boolean published) {}

    public record VersionContent(VersionView meta, String skillMd, Map<String, Object> frontmatter, List<Map<String, Object>> files,
                                 String usage, String example) {}

    public record SkillDetail(SkillSummary summary, VersionContent current, List<VersionView> versions, boolean canEdit, boolean canApprove,
                              RuntimeInfo runtime) {}

    public record ReviewView(Long id, String reviewer, String reviewerName, int rating, String comment, Instant updatedAt) {}

    public record HistoryView(Long id, String action, String actor, String comment, Long versionId, Instant at) {}

    public record RuntimeInfo(String state, String kind, boolean simulated, String description) {}

    public record RegisterRequest(String skillMd, String category, List<String> tags, String visibility, Boolean submit) {}

    public record MetaUpdate(String category, List<String> tags, String icon, String visibility) {}

    public record CommentRequest(String comment) {}

    public record RollbackRequest(Long versionId, String comment) {}

    public record ReviewRequest(Integer rating, String comment) {}

    // ── Agent ──
    public record AgentSummary(Long id, String name, String description, String duties, String visibility, String team, String status, String owner,
                               String ownerName, int version, int skillCount, boolean sample, Instant updatedAt, String executionState) {}

    public record AgentSkillView(Long skillId, String name, String icon, String status, String version) {}

    public record AgentVersionView(Long id, int version, String createdBy, Instant createdAt) {}

    public record AgentDetail(AgentSummary summary, String config, List<AgentSkillView> skills, List<AgentVersionView> versions, boolean canEdit,
                              boolean canApprove, RuntimeInfo runtime) {}

    public record AgentRequest(String name, String description, String duties, String config, String visibility, List<Long> skillIds, Boolean submit) {}

    // ── 실행 ──
    public record ExecuteRequest(Long skillId, Long agentId, String input, String engine) {
        public ExecuteRequest(Long skillId, Long agentId, String input) { this(skillId, agentId, input, null); }
    }

    public record ExecutionView(String id, String targetType, Long skillId, Long agentId, String targetName, String requester, String input,
                                String status, String runtimeKind, boolean simulated, boolean counted, String result, String error,
                                Instant requestedAt, Instant completedAt) {}

    // ── KPI ──
    public record Overview(long skills, long approvedSkills, long agents, long executionsThisMonth, long activeUsers,
                           List<SkillSummary> popular, List<SkillSummary> recent, Exclusions exclusions) {}

    /** 집계에서 제외한 건수(투명성): 시뮬레이션, 샘플, 제작자 본인 실행 */
    public record Exclusions(long simulated, long creatorSelf, long sample) {}

    public record Counts(long requests, long succeeded, long failed, long uniqueUsers, long repeatUsers, long last7d, long last30d) {}

    public record SkillStats(Long skillId, String name, Counts counts, double ratingAvg, long ratingCount, Map<String, Long> byVersion,
                             List<DailyPoint> daily, Exclusions exclusions) {}

    public record DailyPoint(String date, long requests) {}

    public record MonthlyPoint(String month, long requests, long succeeded) {}

    public record CreatorStats(String creator, String creatorName, long skillsRegistered, long skillsApproved, long agentsRegistered, Counts totals,
                               List<SkillSummary> skills, List<AgentSummary> agents, List<MonthlyPoint> monthly, List<ReviewView> recentFeedback,
                               Exclusions exclusions, String note) {}
}
