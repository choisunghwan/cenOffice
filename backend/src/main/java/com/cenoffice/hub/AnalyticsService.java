package com.cenoffice.hub;

import com.cenoffice.hub.domain.HubAgent;
import com.cenoffice.hub.domain.HubAgentRepository;
import com.cenoffice.hub.domain.HubExecution;
import com.cenoffice.hub.domain.HubSkill;
import com.cenoffice.hub.domain.HubSkillRepository;
import com.cenoffice.hub.domain.HubSkillReview;
import com.cenoffice.hub.domain.HubSkillReviewRepository;
import com.cenoffice.hub.domain.HubSkillVersionRepository;
import com.cenoffice.hub.domain.HubUsageEvent;

import com.cenoffice.common.api.ApiException;
import com.cenoffice.hub.HubDtos.*;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용 통계. 실제 이벤트(hub_usage_events)만 집계하며, 시뮬레이션·샘플 Skill·제작자 본인 실행은 counted=false 로 제외한다.
 * KPI 점수/보상은 산정하지 않는다(기준 미확정) — 원자료 지표만 제공한다.
 */
@Service
@Transactional(readOnly = true)
public class AnalyticsService {
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final EntityManager em;
    private final HubSkillRepository skills;
    private final HubAgentRepository agents;
    private final HubSkillReviewRepository reviews;
    private final HubSkillVersionRepository versions;
    private final SkillService skillService;
    private final AgentService agentService;

    public AnalyticsService(EntityManager em, HubSkillRepository skills, HubAgentRepository agents, HubSkillReviewRepository reviews,
                            HubSkillVersionRepository versions, SkillService skillService, AgentService agentService) {
        this.em = em;
        this.skills = skills;
        this.agents = agents;
        this.reviews = reviews;
        this.versions = versions;
        this.skillService = skillService;
        this.agentService = agentService;
    }

    private record Ev(Long skillId, Long versionId, String actor, String type, Instant at) {}

    private List<Ev> events(Collection<Long> skillIds) {
        if (skillIds.isEmpty()) return List.of();
        return em.createQuery("select u from HubUsageEvent u where u.counted = true and u.skillId in :ids", HubUsageEvent.class).setParameter("ids", skillIds).getResultList()
                .stream().map(u -> new Ev(u.skillId, u.versionId, u.actor, u.type, u.occurredAt)).toList();
    }

    private static Counts counts(List<Ev> evs) {
        Instant now = Instant.now();
        List<Ev> req = evs.stream().filter(e -> e.type().equals("EXECUTION_REQUESTED")).toList();
        long ok = evs.stream().filter(e -> e.type().equals("EXECUTION_SUCCEEDED")).count();
        long bad = evs.stream().filter(e -> e.type().equals("EXECUTION_FAILED")).count();
        Map<String, Long> perUser = req.stream().collect(Collectors.groupingBy(Ev::actor, Collectors.counting()));
        return new Counts(req.size(), ok, bad, perUser.size(), perUser.values().stream().filter(c -> c >= 2).count(),
                req.stream().filter(e -> e.at().isAfter(now.minusSeconds(7 * 86400L))).count(), req.stream().filter(e -> e.at().isAfter(now.minusSeconds(30 * 86400L))).count());
    }

    /** 집계 제외 건수(투명성) — 실행 단위 */
    private Exclusions exclusions(Collection<Long> skillIds, String requesterFilterOwner) {
        List<HubExecution> all = em.createQuery("select e from HubExecution e where e.counted = false", HubExecution.class).getResultList();
        long sim = 0, self = 0, sample = 0;
        for (HubExecution e : all) {
            if (skillIds != null && e.skillId != null && !skillIds.contains(e.skillId)) continue;
            if (skillIds != null && e.skillId == null) continue;
            if (e.simulated) sim++;
            else if (e.skillId != null && skills.findById(e.skillId).map(s -> s.sample).orElse(false)) sample++;
            else self++;
        }
        return new Exclusions(sim, self, sample);
    }

    public Overview overview(String me, boolean admin) {
        List<HubSkill> real = em.createQuery("select s from HubSkill s where s.sample = false and s.status <> 'ARCHIVED'", HubSkill.class).getResultList();
        long approved = real.stream().filter(s -> "APPROVED".equals(s.status)).count();
        long agentCount = em.createQuery("select count(a) from HubAgent a where a.sample = false and a.status <> 'ARCHIVED'", Long.class).getSingleResult();
        Instant monthStart = YearMonth.now(ZONE).atDay(1).atStartOfDay(ZONE).toInstant();
        Instant since30 = Instant.now().minusSeconds(30 * 86400L);
        long monthExec = em.createQuery("select count(u) from HubUsageEvent u where u.counted = true and u.type = 'EXECUTION_REQUESTED' and u.occurredAt >= :t", Long.class).setParameter("t", monthStart).getSingleResult();
        long active = em.createQuery("select count(distinct u.actor) from HubUsageEvent u where u.counted = true and u.occurredAt >= :t", Long.class).setParameter("t", since30).getSingleResult();
        var popular = skillService.search(new SkillService.SearchQuery(null, null, null, null, "APPROVED", false, "popular", 0, 5), me, admin).items();
        var recent = skillService.search(new SkillService.SearchQuery(null, null, null, null, "APPROVED", false, "recent", 0, 5), me, admin).items();
        return new Overview(real.size(), approved, agentCount, monthExec, active, popular, recent, exclusions(null, null));
    }

    public List<SkillSummary> popular(String me, boolean admin, int limit) {
        return skillService.search(new SkillService.SearchQuery(null, null, null, null, "APPROVED", false, "popular", 0, Math.min(Math.max(limit, 1), 50)), me, admin).items();
    }

    public SkillStats skillStats(Long skillId, String me, boolean admin) {
        HubSkill s = skillService.visible(skillId, me, admin);
        List<Ev> evs = events(List.of(skillId));
        Map<Long, String> label = versions.findBySkillIdOrderByIdDesc(skillId).stream().collect(Collectors.toMap(v -> v.id, v -> v.version));
        Map<String, Long> byVersion = new TreeMap<>();
        evs.stream().filter(e -> e.type().equals("EXECUTION_REQUESTED")).forEach(e -> byVersion.merge(label.getOrDefault(e.versionId(), "?"), 1L, Long::sum));
        LocalDate today = LocalDate.now(ZONE);
        Map<LocalDate, Long> perDay = evs.stream().filter(e -> e.type().equals("EXECUTION_REQUESTED"))
                .collect(Collectors.groupingBy(e -> e.at().atZone(ZONE).toLocalDate(), Collectors.counting()));
        List<DailyPoint> daily = new ArrayList<>();
        for (int i = 13; i >= 0; i--) { LocalDate d = today.minusDays(i); daily.add(new DailyPoint(d.toString(), perDay.getOrDefault(d, 0L))); }
        List<HubSkillReview> rv = reviews.findBySkillIdOrderByUpdatedAtDesc(skillId);
        double avg = rv.isEmpty() ? 0 : Math.round(rv.stream().mapToInt(r -> r.rating).average().orElse(0) * 10) / 10.0;
        return new SkillStats(skillId, s.name, counts(evs), avg, rv.size(), byVersion, daily, exclusions(Set.of(skillId), null));
    }

    /** 제작자별 통계: 본인 또는 관리자만. 사용자 개인 식별 정보(누가 썼는지)는 포함하지 않는다. */
    public CreatorStats creatorStats(String creator, String me, boolean admin) {
        if (!(admin || creator.equals(me))) throw new ApiException(HttpStatus.FORBIDDEN, "본인 또는 관리자만 제작자 통계를 볼 수 있습니다");
        List<HubSkill> mine = skills.findByOwnerOrderByUpdatedAtDesc(creator).stream().filter(s -> !s.sample && !"ARCHIVED".equals(s.status)).toList();
        List<HubAgent> myAgents = agents.findByOwnerOrderByUpdatedAtDesc(creator).stream().filter(a -> !a.sample && !"ARCHIVED".equals(a.status)).toList();
        Set<Long> ids = mine.stream().map(s -> s.id).collect(Collectors.toSet());
        List<Ev> evs = events(ids);
        Map<YearMonth, long[]> byMonth = new TreeMap<>();
        for (Ev e : evs) {
            YearMonth ym = YearMonth.from(e.at().atZone(ZONE));
            long[] a = byMonth.computeIfAbsent(ym, k -> new long[2]);
            if (e.type().equals("EXECUTION_REQUESTED")) a[0]++;
            if (e.type().equals("EXECUTION_SUCCEEDED")) a[1]++;
        }
        List<MonthlyPoint> monthly = new ArrayList<>();
        YearMonth now = YearMonth.now(ZONE);
        for (int i = 5; i >= 0; i--) { YearMonth m = now.minusMonths(i); long[] a = byMonth.getOrDefault(m, new long[2]); monthly.add(new MonthlyPoint(m.toString(), a[0], a[1])); }
        List<ReviewView> feedback = new ArrayList<>();
        for (HubSkill s : mine) for (HubSkillReview r : reviews.findBySkillIdOrderByUpdatedAtDesc(s.id)) feedback.add(new ReviewView(r.id, r.reviewer, skillService.displayName(r.reviewer), r.rating, r.comment, r.updatedAt));
        feedback.sort(Comparator.comparing(ReviewView::updatedAt).reversed());
        var sums = skillService.search(new SkillService.SearchQuery(null, null, creator, null, null, false, "popular", 0, 100), me, admin).items().stream().filter(x -> !x.sample()).toList();
        return new CreatorStats(creator, skillService.displayName(creator), mine.size(), mine.stream().filter(s -> "APPROVED".equals(s.status)).count(), myAgents.size(),
                counts(evs), sums, myAgents.stream().map(agentService::summary).toList(), monthly, feedback.stream().limit(5).toList(), exclusions(ids, creator),
                "KPI 산정 기준이 확정되지 않아 점수·보상은 계산하지 않고 원자료 지표만 제공합니다. 시뮬레이션·샘플·제작자 본인 실행은 제외됩니다.");
    }
}
