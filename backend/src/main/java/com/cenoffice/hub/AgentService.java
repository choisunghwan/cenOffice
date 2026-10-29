package com.cenoffice.hub;

import com.cenoffice.hub.domain.HubAgent;
import com.cenoffice.hub.domain.HubAgentRepository;
import com.cenoffice.hub.domain.HubAgentSkill;
import com.cenoffice.hub.domain.HubAgentSkillRepository;
import com.cenoffice.hub.domain.HubAgentVersion;
import com.cenoffice.hub.domain.HubAgentVersionRepository;
import com.cenoffice.hub.domain.HubApprovalHistory;
import com.cenoffice.hub.domain.HubApprovalHistoryRepository;
import com.cenoffice.hub.domain.HubSkill;
import com.cenoffice.hub.domain.HubSkillRepository;

import com.cenoffice.api.ApiException;
import com.cenoffice.api.AuditService;
import com.cenoffice.hub.HubDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent: 하나 이상의 승인된 Skill 을 조합한 실행 주체의 "구성 정보"를 관리한다(Skill 과 별도 모델).
 * 구성이 바뀌면 버전이 올라가고 스냅샷이 남으며, 이미 승인된 Agent 는 재승인 전까지 실행할 수 없다(검토 대기로 전환).
 * 실제 실행은 ExecutionService → 런타임 어댑터가 있을 때만 가능하다.
 */
@Service
@Transactional
public class AgentService {
    static final Pattern NAME = Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} _-]{1,62}$");
    static final int MAX_SKILLS = 10;
    private static final Set<String> CONFIG_KEYS = Set.of("maxSteps", "temperature", "outputFormat", "notes");

    private final HubAgentRepository agents;
    private final HubAgentSkillRepository links;
    private final HubAgentVersionRepository agentVersions;
    private final HubApprovalHistoryRepository history;
    private final HubSkillRepository skills;
    private final SkillService skillService;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final EntityManager em;

    public AgentService(HubAgentRepository agents, HubAgentSkillRepository links, HubAgentVersionRepository agentVersions,
                        HubApprovalHistoryRepository history, HubSkillRepository skills, SkillService skillService, AuditService audit,
                        ObjectMapper mapper, EntityManager em) {
        this.agents = agents;
        this.links = links;
        this.agentVersions = agentVersions;
        this.history = history;
        this.skills = skills;
        this.skillService = skillService;
        this.audit = audit;
        this.mapper = mapper;
        this.em = em;
    }

    public AgentDetail create(String owner, AgentRequest r) {
        String name = validateName(r.name());
        if (agents.findByName(name).isPresent()) throw new ApiException(HttpStatus.CONFLICT, "이미 같은 이름의 Agent 가 있습니다: " + name);
        String vis = visibility(r.visibility());
        String team = skillService.teamOf(owner);
        if (vis.equals("TEAM") && (team == null || team.isBlank())) throw new ApiException(HttpStatus.BAD_REQUEST, "소속 팀이 없어 팀 공개를 선택할 수 없습니다");
        List<Long> skillIds = checkSkills(r.skillIds(), owner, false);
        Instant now = Instant.now();
        HubAgent a = new HubAgent();
        a.name = name; a.description = text(r.description(), "설명", 1, 1000); a.duties = r.duties() == null ? null : text(r.duties(), "담당 업무", 0, 1000);
        a.config = validateConfig(r.config()); a.visibility = vis; a.team = team; a.owner = owner; a.version = 1; a.sample = false;
        a.status = Boolean.TRUE.equals(r.submit()) ? "PENDING_REVIEW" : "DRAFT";
        a.createdBy = owner; a.createdAt = now; a.updatedBy = owner; a.updatedAt = now;
        a = agents.save(a);
        setSkills(a, skillIds);
        snapshot(a, owner);
        hist(a.id, "REGISTERED", owner, null);
        if (a.status.equals("PENDING_REVIEW")) hist(a.id, "SUBMITTED", owner, null);
        audit.log(owner, "HUB_AGENT_REGISTERED", "hub-agent:" + a.id, a.name);
        return detail(a.id, owner, false);
    }

    public AgentDetail update(Long id, String actor, boolean admin, AgentRequest r) {
        HubAgent a = visible(id, actor, admin);
        requireEdit(a, actor, admin);
        if ("ARCHIVED".equals(a.status)) throw new ApiException(HttpStatus.CONFLICT, "보관된 Agent 는 수정할 수 없습니다");
        if (r.name() != null && !r.name().equals(a.name)) {
            String n = validateName(r.name());
            if (agents.findByName(n).isPresent()) throw new ApiException(HttpStatus.CONFLICT, "이미 같은 이름의 Agent 가 있습니다: " + n);
            a.name = n;
        }
        if (r.description() != null) a.description = text(r.description(), "설명", 1, 1000);
        if (r.duties() != null) a.duties = text(r.duties(), "담당 업무", 0, 1000);
        if (r.config() != null) a.config = validateConfig(r.config());
        if (r.visibility() != null) {
            String vis = visibility(r.visibility());
            if (vis.equals("TEAM") && (a.team == null || a.team.isBlank())) throw new ApiException(HttpStatus.BAD_REQUEST, "제작자의 소속 팀이 없어 팀 공개를 선택할 수 없습니다");
            a.visibility = vis;
        }
        if (r.skillIds() != null) setSkills(a, checkSkills(r.skillIds(), a.owner, false));
        bump(a, actor, Boolean.TRUE.equals(r.submit()));
        audit.log(actor, "HUB_AGENT_UPDATED", "hub-agent:" + id, "v" + a.version);
        return detail(id, actor, admin);
    }

    /** Skill 연결(구성 변경 → 버전 증가) */
    public AgentDetail link(Long agentId, Long skillId, String actor, boolean admin) {
        HubAgent a = visible(agentId, actor, admin);
        requireEdit(a, actor, admin);
        List<Long> ids = new ArrayList<>(links.findByAgentIdOrderBySortOrderAsc(agentId).stream().map(l -> l.skillId).toList());
        if (ids.contains(skillId)) throw new ApiException(HttpStatus.CONFLICT, "이미 연결된 Skill 입니다");
        ids.add(skillId);
        setSkills(a, checkSkills(ids, a.owner, false));
        bump(a, actor, false);
        audit.log(actor, "HUB_AGENT_SKILL_LINKED", "hub-agent:" + agentId, "skill " + skillId);
        return detail(agentId, actor, admin);
    }

    public AgentDetail unlink(Long agentId, Long skillId, String actor, boolean admin) {
        HubAgent a = visible(agentId, actor, admin);
        requireEdit(a, actor, admin);
        List<Long> ids = new ArrayList<>(links.findByAgentIdOrderBySortOrderAsc(agentId).stream().map(l -> l.skillId).toList());
        if (!ids.remove(skillId)) throw new ApiException(HttpStatus.NOT_FOUND, "연결되지 않은 Skill 입니다");
        setSkills(a, ids);
        bump(a, actor, false);
        audit.log(actor, "HUB_AGENT_SKILL_UNLINKED", "hub-agent:" + agentId, "skill " + skillId);
        return detail(agentId, actor, admin);
    }

    public AgentDetail submit(Long id, String actor, boolean admin) {
        HubAgent a = visible(id, actor, admin);
        requireEdit(a, actor, admin);
        if (!(a.status.equals("DRAFT") || a.status.equals("REJECTED"))) throw new ApiException(HttpStatus.CONFLICT, "제출할 수 없는 상태입니다: " + a.status);
        a.status = "PENDING_REVIEW";
        touch(a, actor);
        hist(id, "SUBMITTED", actor, null);
        audit.log(actor, "HUB_AGENT_SUBMITTED", "hub-agent:" + id, "v" + a.version);
        return detail(id, actor, admin);
    }

    public AgentDetail approve(Long id, String admin, String comment) {
        HubAgent a = agent(id);
        if (!a.status.equals("PENDING_REVIEW")) throw new ApiException(HttpStatus.CONFLICT, "검토 대기 상태가 아닙니다: " + a.status);
        if (a.owner.equals(admin)) throw new ApiException(HttpStatus.FORBIDDEN, "본인이 등록한 Agent 는 다른 관리자가 승인해야 합니다");
        // 연결된 Skill 이 모두 공개(승인) 상태인지 승인 시점에 다시 확인
        for (HubAgentSkill l : links.findByAgentIdOrderBySortOrderAsc(id)) {
            HubSkill s = skills.findById(l.skillId).orElse(null);
            if (s == null || !"APPROVED".equals(s.status) || s.publishedVersionId == null)
                throw new ApiException(HttpStatus.CONFLICT, "승인되지 않은 Skill 이 연결되어 있어 승인할 수 없습니다");
        }
        a.status = "APPROVED";
        touch(a, admin);
        hist(id, "APPROVED", admin, comment);
        audit.log(admin, "HUB_AGENT_APPROVED", "hub-agent:" + id, "v" + a.version);
        return detail(id, admin, true);
    }

    public AgentDetail reject(Long id, String admin, String comment) {
        HubAgent a = agent(id);
        if (!a.status.equals("PENDING_REVIEW")) throw new ApiException(HttpStatus.CONFLICT, "검토 대기 상태가 아닙니다: " + a.status);
        if (comment == null || comment.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "반려 사유를 입력하세요");
        a.status = "REJECTED";
        touch(a, admin);
        hist(id, "REJECTED", admin, comment);
        audit.log(admin, "HUB_AGENT_REJECTED", "hub-agent:" + id, SkillService.limit(comment, 200));
        return detail(id, admin, true);
    }

    public void archive(Long id, String actor, boolean admin) {
        HubAgent a = visible(id, actor, admin);
        requireEdit(a, actor, admin);
        a.status = "ARCHIVED";
        touch(a, actor);
        hist(id, "ARCHIVED", actor, null);
        audit.log(actor, "HUB_AGENT_ARCHIVED", "hub-agent:" + id, a.name);
    }

    // ───────────── 조회 ─────────────

    public record SearchQuery(String text, String owner, String status, boolean mine, String sort, int page, int size) {}

    @Transactional(readOnly = true)
    public PageView<AgentSummary> search(SearchQuery q, String me, boolean admin) {
        String team = skillService.teamOf(me);
        StringBuilder j = new StringBuilder("select a from HubAgent a where a.status <> 'ARCHIVED'");
        Map<String, Object> p = new HashMap<>();
        if (!admin) {
            j.append(" and ((a.status = 'APPROVED' and (a.visibility = 'PUBLIC' or (a.visibility = 'TEAM' and a.team = :team))) or a.owner = :me)");
            p.put("team", team == null ? "" : team);
            p.put("me", me);
        }
        if (q.mine()) { j.append(" and a.owner = :mine"); p.put("mine", me); }
        if (q.status() != null && !q.status().isBlank()) { j.append(" and a.status = :st"); p.put("st", q.status().toUpperCase(Locale.ROOT)); }
        if (q.owner() != null && !q.owner().isBlank()) { j.append(" and a.owner = :own"); p.put("own", q.owner()); }
        if (q.text() != null && !q.text().isBlank()) {
            j.append(" and (lower(a.name) like :q escape '!' or lower(a.description) like :q escape '!' or lower(a.duties) like :q escape '!')");
            p.put("q", "%" + q.text().toLowerCase(Locale.ROOT).trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        j.append(" order by a.updatedAt desc");
        TypedQuery<HubAgent> tq = em.createQuery(j.toString(), HubAgent.class);
        p.forEach(tq::setParameter);
        tq.setMaxResults(1000);
        List<AgentSummary> all = tq.getResultList().stream().map(this::summary).toList();
        int size = Math.min(Math.max(q.size(), 1), 100), page = Math.max(q.page(), 0);
        int from = Math.min(page * size, all.size());
        return new PageView<>(all.subList(from, Math.min(from + size, all.size())), all.size(), page, size);
    }

    @Transactional(readOnly = true)
    public AgentDetail detail(Long id, String me, boolean admin) {
        HubAgent a = visible(id, me, admin);
        boolean owner = a.owner.equals(me) || admin;
        List<AgentSkillView> sk = new ArrayList<>();
        for (HubAgentSkill l : links.findByAgentIdOrderBySortOrderAsc(id)) {
            HubSkill s = skills.findById(l.skillId).orElse(null);
            if (s == null) continue;
            sk.add(new AgentSkillView(s.id, s.name, s.icon, s.status, null));
        }
        List<AgentVersionView> vs = agentVersions.findByAgentIdOrderByVersionDesc(id).stream().map(v -> new AgentVersionView(v.id, v.version, v.createdBy, v.createdAt)).toList();
        return new AgentDetail(summary(a), owner ? a.config : null, sk, owner ? vs : List.of(), owner, admin && !a.owner.equals(me), skillService.runtimeInfo());
    }

    @Transactional(readOnly = true)
    public List<HistoryView> history(Long id, String me, boolean admin) {
        HubAgent a = visible(id, me, admin);
        if (!(a.owner.equals(me) || admin)) throw new ApiException(HttpStatus.FORBIDDEN, "제작자 또는 관리자만 승인 이력을 볼 수 있습니다");
        return history.findByTargetTypeAndTargetIdOrderByIdDesc("AGENT", id).stream().map(h -> new HistoryView(h.id, h.action, h.actor, h.comment, h.versionId, h.occurredAt)).toList();
    }

    @Transactional(readOnly = true)
    public List<AgentSummary> reviewQueue() {
        return em.createQuery("select a from HubAgent a where a.status = 'PENDING_REVIEW' order by a.updatedAt asc", HubAgent.class).getResultList().stream().map(this::summary).toList();
    }

    // ───────────── 공용 ─────────────

    public HubAgent agent(Long id) { return agents.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Agent 를 찾을 수 없습니다")); }

    public boolean canView(HubAgent a, String me, boolean admin) {
        if (admin || a.owner.equals(me)) return true;
        if (!"APPROVED".equals(a.status)) return false;
        if (a.visibility.equals("PUBLIC")) return true;
        String team = skillService.teamOf(me);
        return a.visibility.equals("TEAM") && team != null && team.equals(a.team);
    }

    public HubAgent visible(Long id, String me, boolean admin) {
        HubAgent a = agent(id);
        if (!canView(a, me, admin) || ("ARCHIVED".equals(a.status) && !(admin || a.owner.equals(me)))) throw new ApiException(HttpStatus.NOT_FOUND, "Agent 를 찾을 수 없습니다");
        return a;
    }

    public List<Long> skillIds(Long agentId) { return links.findByAgentIdOrderBySortOrderAsc(agentId).stream().map(l -> l.skillId).toList(); }

    private void requireEdit(HubAgent a, String actor, boolean admin) {
        if (!(admin || a.owner.equals(actor))) throw new ApiException(HttpStatus.FORBIDDEN, "제작자 또는 관리자만 수정할 수 있습니다");
    }

    /** 구성 변경: 버전 증가 + 스냅샷. 승인된 Agent 는 재승인 전까지 실행 불가(검토 대기). */
    private void bump(HubAgent a, String actor, boolean submit) {
        a.version++;
        if (a.status.equals("APPROVED") || submit) a.status = "PENDING_REVIEW";
        touch(a, actor);
        snapshot(a, actor);
        if (a.status.equals("PENDING_REVIEW")) hist(a.id, "SUBMITTED", actor, "구성 변경 v" + a.version);
    }

    private void setSkills(HubAgent a, List<Long> ids) {
        links.deleteByAgentId(a.id);
        links.flush();
        int i = 0;
        for (Long id : ids) links.save(new HubAgentSkill(a.id, id, i++));
    }

    /** 연결 대상 Skill 검증: 존재, 승인(공개 버전 보유), 제작자(owner)에게 열람 가능, 중복/개수 제한 */
    private List<Long> checkSkills(List<Long> ids, String ownerName, boolean admin) {
        if (ids == null) return List.of();
        LinkedHashSet<Long> set = new LinkedHashSet<>(ids);
        if (set.size() != ids.size()) throw new ApiException(HttpStatus.BAD_REQUEST, "중복된 Skill 이 있습니다");
        if (set.size() > MAX_SKILLS) throw new ApiException(HttpStatus.BAD_REQUEST, "Agent 에는 Skill 을 최대 " + MAX_SKILLS + "개까지 연결할 수 있습니다");
        for (Long id : set) {
            HubSkill s = skills.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Skill 을 찾을 수 없습니다: " + id));
            if (!"APPROVED".equals(s.status) || s.publishedVersionId == null) throw new ApiException(HttpStatus.BAD_REQUEST, "승인된 Skill 만 연결할 수 있습니다: " + s.name);
            if (!skillService.canView(s, ownerName, admin)) throw new ApiException(HttpStatus.BAD_REQUEST, "접근 권한이 없는 Skill 은 연결할 수 없습니다: " + s.name);
        }
        return new ArrayList<>(set);
    }

    private void snapshot(HubAgent a, String actor) {
        HubAgentVersion v = new HubAgentVersion();
        v.agentId = a.id; v.version = a.version; v.createdBy = actor; v.createdAt = Instant.now();
        try {
            v.snapshot = SkillService.limit(mapper.writeValueAsString(Map.of("name", a.name, "description", a.description, "duties", a.duties == null ? "" : a.duties,
                    "config", a.config == null ? "{}" : a.config, "visibility", a.visibility, "skillIds", skillIds(a.id))), 8000);
        } catch (Exception e) { v.snapshot = "{}"; }
        agentVersions.save(v);
    }

    private void touch(HubAgent a, String actor) { a.updatedBy = actor; a.updatedAt = Instant.now(); agents.save(a); }

    private void hist(Long id, String action, String actor, String comment) {
        HubApprovalHistory h = new HubApprovalHistory();
        h.targetType = "AGENT"; h.targetId = id; h.action = action; h.actor = actor; h.comment = comment == null ? null : SkillService.limit(comment, 500); h.occurredAt = Instant.now();
        history.save(h);
    }

    AgentSummary summary(HubAgent a) {
        String exec = !"APPROVED".equals(a.status) ? "REGISTRY_ONLY" : skillService.runtimeInfo().state();
        return new AgentSummary(a.id, a.name, a.description, a.duties, a.visibility, a.team, a.status, a.owner, skillService.displayName(a.owner), a.version,
                links.findByAgentIdOrderBySortOrderAsc(a.id).size(), a.sample, a.updatedAt, exec);
    }

    private static String validateName(String n) {
        String name = n == null ? "" : n.strip();
        if (!NAME.matcher(name).matches()) throw new ApiException(HttpStatus.BAD_REQUEST, "Agent 이름은 2~63자의 글자/숫자/공백/-/_ 여야 합니다");
        return name;
    }

    private static String text(String s, String label, int min, int max) {
        String t = s == null ? "" : s.replaceAll("[\\p{Cntrl}&&[^\\n]]", "").trim();
        if (t.length() < min) throw new ApiException(HttpStatus.BAD_REQUEST, label + "을(를) 입력하세요");
        if (t.length() > max) throw new ApiException(HttpStatus.BAD_REQUEST, label + "은(는) " + max + "자 이하여야 합니다");
        return t;
    }

    private static String visibility(String v) {
        String vis = v == null ? "PUBLIC" : v.toUpperCase(Locale.ROOT);
        if (!SkillService.VISIBILITIES.contains(vis)) throw new ApiException(HttpStatus.BAD_REQUEST, "공개 범위는 PUBLIC 또는 TEAM 이어야 합니다");
        return vis;
    }

    /** 실행 설정: 허용된 키/값 범위만 저장한다(알 수 없는 키 거부). 실제 적용은 런타임 연동 시점. */
    private String validateConfig(String cfg) {
        if (cfg == null || cfg.isBlank()) return "{}";
        if (cfg.length() > 2000) throw new ApiException(HttpStatus.BAD_REQUEST, "실행 설정이 너무 깁니다");
        try {
            JsonNode n = mapper.readTree(cfg);
            if (!n.isObject()) throw new ApiException(HttpStatus.BAD_REQUEST, "실행 설정은 JSON 객체여야 합니다");
            Iterator<String> it = n.fieldNames();
            while (it.hasNext()) if (!CONFIG_KEYS.contains(it.next())) throw new ApiException(HttpStatus.BAD_REQUEST, "허용되지 않는 실행 설정 키입니다 (허용: " + CONFIG_KEYS + ")");
            if (n.has("maxSteps") && !(n.get("maxSteps").isInt() && n.get("maxSteps").asInt() >= 1 && n.get("maxSteps").asInt() <= 20)) throw new ApiException(HttpStatus.BAD_REQUEST, "maxSteps 는 1~20 정수입니다");
            if (n.has("temperature") && !(n.get("temperature").isNumber() && n.get("temperature").asDouble() >= 0 && n.get("temperature").asDouble() <= 1)) throw new ApiException(HttpStatus.BAD_REQUEST, "temperature 는 0~1 입니다");
            if (n.has("outputFormat") && !Set.of("text", "markdown", "json").contains(n.get("outputFormat").asText())) throw new ApiException(HttpStatus.BAD_REQUEST, "outputFormat 은 text|markdown|json 입니다");
            if (n.has("notes") && (!n.get("notes").isTextual() || n.get("notes").asText().length() > 500)) throw new ApiException(HttpStatus.BAD_REQUEST, "notes 는 500자 이하 문자열입니다");
            return mapper.writeValueAsString(n);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "실행 설정이 올바른 JSON 이 아닙니다");
        }
    }
}
