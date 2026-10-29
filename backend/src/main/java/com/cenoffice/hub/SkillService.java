package com.cenoffice.hub;

import com.cenoffice.hub.domain.HubApprovalHistory;
import com.cenoffice.hub.domain.HubApprovalHistoryRepository;
import com.cenoffice.hub.domain.HubCategoryRepository;
import com.cenoffice.hub.domain.HubExecutionRepository;
import com.cenoffice.hub.domain.HubSkill;
import com.cenoffice.hub.domain.HubSkillRepository;
import com.cenoffice.hub.domain.HubSkillReview;
import com.cenoffice.hub.domain.HubSkillReviewRepository;
import com.cenoffice.hub.domain.HubSkillTag;
import com.cenoffice.hub.domain.HubSkillTagRepository;
import com.cenoffice.hub.domain.HubSkillVersion;
import com.cenoffice.hub.domain.HubSkillVersionRepository;
import com.cenoffice.hub.domain.HubUsageEvent;
import com.cenoffice.hub.domain.HubUsageEventRepository;

import com.cenoffice.api.ApiException;
import com.cenoffice.api.AuditService;
import com.cenoffice.domain.UserAccount;
import com.cenoffice.hub.HubDtos.*;
import com.cenoffice.hub.SkillPackageReader.InvalidSkillException;
import com.cenoffice.hub.SkillPackageReader.Parsed;
import com.cenoffice.hub.runtime.AgentRuntimeAdapter;
import com.cenoffice.hub.runtime.RuntimeRegistry;
import com.cenoffice.repo.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Skill Registry: 등록·버전·승인·검색·다운로드·평가. 실행(런타임)과는 분리되어 있다.
 * 상태: 버전(DRAFT / PENDING_REVIEW / APPROVED / REJECTED), Skill(DRAFT / PENDING_REVIEW / APPROVED / REJECTED / ARCHIVED).
 * 전사 공개는 관리자 승인된 버전(publishedVersionId)이 있을 때만 이뤄지며, 새 버전은 승인 전까지 기존 공개 버전에 영향을 주지 않는다.
 */
@Service
@Transactional
public class SkillService {
    public static final Set<String> VISIBILITIES = Set.of("PUBLIC", "TEAM");
    private static final int REGISTER_LIMIT = 10;
    private static final Duration REGISTER_WINDOW = Duration.ofMinutes(10);

    private final HubSkillRepository skills;
    private final HubSkillVersionRepository versions;
    private final HubSkillTagRepository tags;
    private final HubSkillReviewRepository reviews;
    private final HubApprovalHistoryRepository history;
    private final HubCategoryRepository categories;
    private final HubUsageEventRepository usage;
    private final HubExecutionRepository executions;
    private final UserRepository users;
    private final AuditService audit;
    private final SkillPackageReader reader;
    private final RuntimeRegistry runtime;
    private final ObjectMapper mapper;
    private final EntityManager em;

    private final Map<String, Deque<Instant>> registerLog = new java.util.concurrent.ConcurrentHashMap<>();

    public SkillService(HubSkillRepository skills, HubSkillVersionRepository versions, HubSkillTagRepository tags, HubSkillReviewRepository reviews,
                        HubApprovalHistoryRepository history, HubCategoryRepository categories, HubUsageEventRepository usage,
                        HubExecutionRepository executions, UserRepository users, AuditService audit, SkillPackageReader reader,
                        RuntimeRegistry runtime, ObjectMapper mapper, EntityManager em) {
        this.skills = skills;
        this.versions = versions;
        this.tags = tags;
        this.reviews = reviews;
        this.history = history;
        this.categories = categories;
        this.usage = usage;
        this.executions = executions;
        this.users = users;
        this.audit = audit;
        this.reader = reader;
        this.runtime = runtime;
        this.mapper = mapper;
        this.em = em;
    }

    // ───────────────────────── 파싱/등록 ─────────────────────────

    /** 업로드 파일을 검증·파싱한다. 거부 시 사유를 감사 로그에 남기고 400 을 던진다. */
    public Parsed parseUpload(String filename, byte[] bytes, String actor) {
        try {
            return reader.read(filename, bytes);
        } catch (InvalidSkillException e) {
            rejected(actor, e.getMessage());
            throw new ApiException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    public Parsed parseMarkdown(String md, String actor) {
        if (md == null || md.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "SKILL.md 내용을 입력하세요");
        try {
            return reader.readMarkdown(md.getBytes(StandardCharsets.UTF_8));
        } catch (InvalidSkillException e) {
            rejected(actor, e.getMessage());
            throw new ApiException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private void rejected(String actor, String reason) { audit.log(actor, "HUB_UPLOAD_REJECTED", null, reason); }

    public SkillDetail register(String owner, Parsed p, String category, List<String> extraTags, String visibility, boolean submit) {
        throttleRegister(owner);
        String name = p.meta().name();
        if (skills.findByName(name).isPresent()) throw new ApiException(HttpStatus.CONFLICT, "이미 같은 이름의 Skill 이 있습니다: " + name);
        String cat = pickCategory(category != null ? category : p.meta().category());
        String vis = visibility == null ? "PUBLIC" : visibility.toUpperCase(Locale.ROOT);
        if (!VISIBILITIES.contains(vis)) throw new ApiException(HttpStatus.BAD_REQUEST, "공개 범위는 PUBLIC 또는 TEAM 이어야 합니다");
        String team = teamOf(owner);
        if (vis.equals("TEAM") && (team == null || team.isBlank())) throw new ApiException(HttpStatus.BAD_REQUEST, "소속 팀이 없어 팀 공개를 선택할 수 없습니다");

        Instant now = Instant.now();
        HubSkill s = new HubSkill();
        s.name = name; s.title = p.meta().title(); s.description = p.meta().description(); s.category = cat;
        s.icon = p.meta().icon() != null ? p.meta().icon() : "🧩";
        s.visibility = vis; s.team = team; s.status = "DRAFT"; s.owner = owner; s.sample = false;
        s.createdBy = owner; s.createdAt = now; s.updatedBy = owner; s.updatedAt = now;
        s = skills.save(s);
        saveTags(s.id, mergeTags(p.meta().tags(), extraTags));
        HubSkillVersion v = newVersion(s, p, owner, submit ? "PENDING_REVIEW" : "DRAFT", p.meta().version() != null ? p.meta().version() : "1.0.0");
        hist("SKILL", s.id, v.id, "REGISTERED", owner, null);
        if (submit) hist("SKILL", s.id, v.id, "SUBMITTED", owner, null);
        recompute(s);
        audit.log(owner, "HUB_SKILL_REGISTERED", "hub-skill:" + s.id, name + " v" + v.version + (p.riskFlags().isEmpty() ? "" : " (위험 플래그 " + p.riskFlags().size() + "건)"));
        return detail(s.id, owner, false);
    }

    public SkillDetail addVersion(Long skillId, String actor, boolean admin, Parsed p, boolean submit) {
        HubSkill s = visible(skillId, actor, admin);
        requireEdit(s, actor, admin);
        if (!s.name.equals(p.meta().name())) throw new ApiException(HttpStatus.BAD_REQUEST, "SKILL.md 의 name 이 기존 Skill 과 다릅니다 (" + s.name + ")");
        if ("ARCHIVED".equals(s.status)) throw new ApiException(HttpStatus.CONFLICT, "보관된 Skill 에는 버전을 추가할 수 없습니다");
        String label = p.meta().version() != null ? p.meta().version() : nextLabel(skillId);
        if (versions.findBySkillIdAndVersion(skillId, label).isPresent()) throw new ApiException(HttpStatus.CONFLICT, "이미 존재하는 버전입니다: " + label);
        HubSkillVersion v = newVersion(s, p, actor, submit ? "PENDING_REVIEW" : "DRAFT", label);
        s.description = p.meta().description();
        if (p.meta().title() != null) s.title = p.meta().title();
        if (p.meta().icon() != null) s.icon = p.meta().icon();
        touch(s, actor);
        hist("SKILL", s.id, v.id, "VERSION_ADDED", actor, label);
        if (submit) hist("SKILL", s.id, v.id, "SUBMITTED", actor, null);
        recompute(s);
        audit.log(actor, "HUB_SKILL_VERSION_ADDED", "hub-skill:" + s.id, "v" + label);
        return detail(s.id, actor, admin);
    }

    private HubSkillVersion newVersion(HubSkill s, Parsed p, String actor, String status, String label) {
        HubSkillVersion v = new HubSkillVersion();
        v.skillId = s.id; v.version = label; v.status = status;
        try { v.frontmatter = limit(mapper.writeValueAsString(p.meta().raw()), 4000); } catch (Exception e) { v.frontmatter = "{}"; }
        v.skillMd = p.skillMd(); v.fileList = limit(p.fileListJson(), 4000);
        v.packageKind = p.packageKind(); v.packageData = p.packageData(); v.sha256 = p.sha256();
        v.riskFlags = p.riskFlags().isEmpty() ? null : limit(String.join(" | ", p.riskFlags()), 2000);
        v.createdBy = actor; v.createdAt = Instant.now();
        return versions.save(v);
    }

    private String nextLabel(Long skillId) {
        int[] best = {1, -1, 0};
        for (HubSkillVersion x : versions.findBySkillIdOrderByIdDesc(skillId)) {
            String[] a = x.version.split("\\.");
            if (a.length != 3) continue;
            int[] t = {Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2])};
            if (t[0] > best[0] || (t[0] == best[0] && t[1] > best[1])) best = t;
        }
        return best[0] + "." + (best[1] + 1) + ".0";
    }

    // ───────────────────────── 승인/반려/롤백 ─────────────────────────

    public SkillDetail submitVersion(Long skillId, Long versionId, String actor, boolean admin) {
        HubSkill s = visible(skillId, actor, admin);
        requireEdit(s, actor, admin);
        HubSkillVersion v = version(skillId, versionId);
        if (!(v.status.equals("DRAFT") || v.status.equals("REJECTED"))) throw new ApiException(HttpStatus.CONFLICT, "제출할 수 없는 상태입니다: " + v.status);
        v.status = "PENDING_REVIEW";
        versions.save(v);
        hist("SKILL", skillId, versionId, "SUBMITTED", actor, null);
        touch(s, actor);
        recompute(s);
        audit.log(actor, "HUB_SKILL_SUBMITTED", "hub-skill:" + skillId, "v" + v.version);
        return detail(skillId, actor, admin);
    }

    public SkillDetail approve(Long skillId, Long versionId, String admin, String comment) {
        HubSkill s = skill(skillId);
        HubSkillVersion v = version(skillId, versionId);
        if (!v.status.equals("PENDING_REVIEW")) throw new ApiException(HttpStatus.CONFLICT, "검토 대기 상태가 아닙니다: " + v.status);
        if (s.owner.equals(admin)) throw new ApiException(HttpStatus.FORBIDDEN, "본인이 등록한 Skill 은 다른 관리자가 승인해야 합니다");
        v.status = "APPROVED";
        versions.save(v);
        s.publishedVersionId = v.id;
        touch(s, admin);
        hist("SKILL", skillId, versionId, "APPROVED", admin, comment);
        recompute(s);
        audit.log(admin, "HUB_SKILL_APPROVED", "hub-skill:" + skillId, "v" + v.version + (comment == null ? "" : " — " + limit(comment, 200)));
        return detail(skillId, admin, true);
    }

    public SkillDetail reject(Long skillId, Long versionId, String admin, String comment) {
        HubSkill s = skill(skillId);
        HubSkillVersion v = version(skillId, versionId);
        if (!v.status.equals("PENDING_REVIEW")) throw new ApiException(HttpStatus.CONFLICT, "검토 대기 상태가 아닙니다: " + v.status);
        if (comment == null || comment.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "반려 사유를 입력하세요");
        v.status = "REJECTED";
        versions.save(v);
        touch(s, admin);
        hist("SKILL", skillId, versionId, "REJECTED", admin, comment);
        recompute(s);
        audit.log(admin, "HUB_SKILL_REJECTED", "hub-skill:" + skillId, "v" + v.version + " — " + limit(comment, 200));
        return detail(skillId, admin, true);
    }

    /** 이전에 승인된 버전을 공개 버전으로 되돌린다(관리자). */
    public SkillDetail rollback(Long skillId, Long versionId, String admin, String comment) {
        HubSkill s = skill(skillId);
        HubSkillVersion v = version(skillId, versionId);
        if (!v.status.equals("APPROVED")) throw new ApiException(HttpStatus.CONFLICT, "승인된 적이 있는 버전으로만 되돌릴 수 있습니다");
        if (Objects.equals(s.publishedVersionId, v.id)) throw new ApiException(HttpStatus.CONFLICT, "이미 공개 중인 버전입니다");
        s.publishedVersionId = v.id;
        touch(s, admin);
        hist("SKILL", skillId, versionId, "ROLLED_BACK", admin, comment);
        recompute(s);
        audit.log(admin, "HUB_SKILL_ROLLED_BACK", "hub-skill:" + skillId, "→ v" + v.version);
        return detail(skillId, admin, true);
    }

    public SkillDetail updateMeta(Long skillId, String actor, boolean admin, MetaUpdate m) {
        HubSkill s = visible(skillId, actor, admin);
        requireEdit(s, actor, admin);
        if (m.category() != null) s.category = pickCategory(m.category());
        if (m.icon() != null) {
            if (m.icon().codePointCount(0, m.icon().length()) > 4) throw new ApiException(HttpStatus.BAD_REQUEST, "아이콘은 4자 이하여야 합니다");
            s.icon = m.icon();
        }
        if (m.visibility() != null) {
            String vis = m.visibility().toUpperCase(Locale.ROOT);
            if (!VISIBILITIES.contains(vis)) throw new ApiException(HttpStatus.BAD_REQUEST, "공개 범위는 PUBLIC 또는 TEAM 이어야 합니다");
            if (vis.equals("TEAM") && (s.team == null || s.team.isBlank())) throw new ApiException(HttpStatus.BAD_REQUEST, "제작자의 소속 팀이 없어 팀 공개를 선택할 수 없습니다");
            s.visibility = vis;
        }
        if (m.tags() != null) { tags.deleteBySkillId(skillId); saveTags(skillId, mergeTags(m.tags(), null)); }
        touch(s, actor);
        audit.log(actor, "HUB_SKILL_UPDATED", "hub-skill:" + skillId, "메타데이터 수정");
        return detail(skillId, actor, admin);
    }

    public void archive(Long skillId, String actor, boolean admin) {
        HubSkill s = visible(skillId, actor, admin);
        requireEdit(s, actor, admin);
        s.status = "ARCHIVED";
        touch(s, actor);
        hist("SKILL", skillId, null, "ARCHIVED", actor, null);
        audit.log(actor, "HUB_SKILL_ARCHIVED", "hub-skill:" + skillId, s.name);
    }

    // ───────────────────────── 조회 ─────────────────────────

    public record SearchQuery(String text, String category, String owner, String tag, String status, boolean mine, String sort, int page, int size) {}

    @Transactional(readOnly = true)
    public PageView<SkillSummary> search(SearchQuery q, String me, boolean admin) {
        String team = teamOf(me);
        StringBuilder j = new StringBuilder("select s from HubSkill s where s.status <> 'ARCHIVED'");
        Map<String, Object> p = new HashMap<>();
        if (!admin) {
            j.append(" and ((s.status = 'APPROVED' and s.publishedVersionId is not null and (s.visibility = 'PUBLIC' or (s.visibility = 'TEAM' and s.team = :team))) or s.owner = :me)");
            p.put("team", team == null ? "" : team);
            p.put("me", me);
        }
        if (q.mine()) { j.append(" and s.owner = :mine"); p.put("mine", me); }
        if (notBlank(q.status())) { j.append(" and s.status = :st"); p.put("st", q.status().toUpperCase(Locale.ROOT)); }
        if (notBlank(q.category())) { j.append(" and s.category = :cat"); p.put("cat", q.category()); }
        if (notBlank(q.owner())) { j.append(" and s.owner = :own"); p.put("own", q.owner()); }
        if (notBlank(q.tag())) { j.append(" and exists (select 1 from HubSkillTag t where t.skillId = s.id and lower(t.tag) = :tag)"); p.put("tag", q.tag().toLowerCase(Locale.ROOT).trim()); }
        if (notBlank(q.text())) {
            j.append(" and (lower(s.name) like :q escape '!' or lower(s.description) like :q escape '!' or exists (select 1 from HubSkillTag t2 where t2.skillId = s.id and lower(t2.tag) like :q escape '!'))");
            p.put("q", "%" + q.text().toLowerCase(Locale.ROOT).trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        j.append(" order by s.updatedAt desc");
        TypedQuery<HubSkill> tq = em.createQuery(j.toString(), HubSkill.class);
        p.forEach(tq::setParameter);
        tq.setMaxResults(2000);
        List<HubSkill> all = tq.getResultList();
        List<SkillSummary> sums = summaries(all);
        if ("popular".equalsIgnoreCase(q.sort())) sums = sums.stream().sorted(Comparator.comparingLong(SkillSummary::executions).reversed().thenComparing(SkillSummary::updatedAt, Comparator.reverseOrder())).toList();
        int size = Math.min(Math.max(q.size(), 1), 100), page = Math.max(q.page(), 0);
        int from = Math.min(page * size, sums.size());
        return new PageView<>(sums.subList(from, Math.min(from + size, sums.size())), sums.size(), page, size);
    }

    @Transactional(readOnly = true)
    public SkillDetail detail(Long id, String me, boolean admin) { return detail(id, null, me, admin); }

    @Transactional(readOnly = true)
    public SkillDetail detail(Long id, Long versionId, String me, boolean admin) {
        HubSkill s = visible(id, me, admin);
        boolean owner = s.owner.equals(me) || admin;
        List<HubSkillVersion> all = versions.findBySkillIdOrderByIdDesc(id);
        // 일반 사용자에게는 공개(승인) 버전만 보인다. 제작자/관리자는 모든 버전.
        List<HubSkillVersion> visible = owner ? all : all.stream().filter(v -> v.status.equals("APPROVED")).toList();
        HubSkillVersion cur = null;
        if (versionId != null) cur = visible.stream().filter(v -> v.id.equals(versionId)).findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "버전을 찾을 수 없습니다"));
        else if (s.publishedVersionId != null) cur = visible.stream().filter(v -> v.id.equals(s.publishedVersionId)).findFirst().orElse(null);
        if (cur == null && !visible.isEmpty()) cur = visible.get(0);
        return new SkillDetail(summaries(List.of(s)).get(0), cur == null ? null : content(cur, s), visible.stream().map(v -> versionView(v, s)).toList(),
                owner, admin && !s.owner.equals(me), runtimeInfo(s));
    }

    @Transactional(readOnly = true)
    public List<VersionView> versions(Long id, String me, boolean admin) {
        HubSkill s = visible(id, me, admin);
        boolean owner = s.owner.equals(me) || admin;
        return versions.findBySkillIdOrderByIdDesc(id).stream().filter(v -> owner || v.status.equals("APPROVED")).map(v -> versionView(v, s)).toList();
    }

    @Transactional(readOnly = true)
    public List<HistoryView> history(Long id, String me, boolean admin) {
        HubSkill s = visible(id, me, admin);
        if (!(s.owner.equals(me) || admin)) throw new ApiException(HttpStatus.FORBIDDEN, "제작자 또는 관리자만 승인 이력을 볼 수 있습니다");
        return history.findByTargetTypeAndTargetIdOrderByIdDesc("SKILL", id).stream().map(h -> new HistoryView(h.id, h.action, h.actor, h.comment, h.versionId, h.occurredAt)).toList();
    }

    /** 관리자 검토 대기열: 위험 플래그와 함께 검토 대기 버전을 보여준다. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> reviewQueue() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (HubSkillVersion v : versions.findByStatusOrderByCreatedAtAsc("PENDING_REVIEW")) {
            HubSkill s = skills.findById(v.skillId).orElse(null);
            if (s == null || "ARCHIVED".equals(s.status)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("skillId", s.id); m.put("versionId", v.id); m.put("name", s.name); m.put("icon", s.icon); m.put("version", v.version);
            m.put("owner", s.owner); m.put("ownerName", displayName(s.owner)); m.put("submittedAt", v.createdAt);
            m.put("riskFlags", flags(v)); m.put("packageKind", v.packageKind); m.put("sample", s.sample);
            out.add(m);
        }
        return out;
    }

    public record Download(String filename, String contentType, byte[] data) {}

    public Download download(Long skillId, Long versionId, String me, boolean admin) {
        HubSkill s = visible(skillId, me, admin);
        boolean owner = s.owner.equals(me) || admin;
        HubSkillVersion v = versionId != null ? version(skillId, versionId) : versions.findById(Objects.requireNonNullElse(s.publishedVersionId, -1L)).orElse(null);
        if (v == null) throw new ApiException(HttpStatus.NOT_FOUND, "다운로드할 공개 버전이 없습니다");
        if (!owner && !v.status.equals("APPROVED")) throw new ApiException(HttpStatus.NOT_FOUND, "버전을 찾을 수 없습니다");
        boolean zip = v.packageKind.equals("ZIP");
        byte[] data = zip ? v.packageData : v.skillMd.getBytes(StandardCharsets.UTF_8);
        recordEvent(s, v.id, me, "DOWNLOAD");
        audit.log(me, "HUB_SKILL_DOWNLOADED", "hub-skill:" + skillId, "v" + v.version);
        return new Download(s.name + "-" + v.version + (zip ? ".zip" : ".md"), zip ? "application/zip" : "text/markdown; charset=utf-8", data);
    }

    // ───────────────────────── 평가 ─────────────────────────

    @Transactional(readOnly = true)
    public List<ReviewView> reviews(Long skillId, String me, boolean admin) {
        visible(skillId, me, admin);
        return reviews.findBySkillIdOrderByUpdatedAtDesc(skillId).stream().map(this::reviewView).toList();
    }

    public ReviewView review(Long skillId, String me, boolean admin, Integer rating, String comment) {
        HubSkill s = visible(skillId, me, admin);
        if (s.owner.equals(me)) throw new ApiException(HttpStatus.FORBIDDEN, "본인이 만든 Skill 은 평가할 수 없습니다");
        if (!"APPROVED".equals(s.status) || s.publishedVersionId == null) throw new ApiException(HttpStatus.CONFLICT, "승인된 Skill 만 평가할 수 있습니다");
        if (rating == null || rating < 1 || rating > 5) throw new ApiException(HttpStatus.BAD_REQUEST, "평점은 1~5 입니다");
        String c = comment == null ? null : comment.replaceAll("[\\p{Cntrl}&&[^\\n]]", "").trim();
        if (c != null && c.length() > 500) throw new ApiException(HttpStatus.BAD_REQUEST, "의견은 500자 이하여야 합니다");
        HubSkillReview r = reviews.findBySkillIdAndReviewer(skillId, me).orElseGet(() -> {
            HubSkillReview n = new HubSkillReview();
            n.skillId = skillId; n.reviewer = me; n.createdAt = Instant.now();
            return n;
        });
        r.rating = rating; r.comment = (c == null || c.isEmpty()) ? null : c; r.updatedAt = Instant.now();
        r = reviews.save(r);
        audit.log(me, "HUB_SKILL_REVIEWED", "hub-skill:" + skillId, "평점 " + rating);
        return reviewView(r);
    }

    // ───────────────────────── 공용 ─────────────────────────

    public HubSkill skill(Long id) { return skills.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Skill 을 찾을 수 없습니다")); }

    public HubSkillVersion version(Long skillId, Long versionId) {
        HubSkillVersion v = versions.findById(versionId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "버전을 찾을 수 없습니다"));
        if (!v.skillId.equals(skillId)) throw new ApiException(HttpStatus.NOT_FOUND, "버전을 찾을 수 없습니다");
        return v;
    }

    /** 열람 권한: 관리자/제작자 또는 (승인·공개된 Skill 이고 공개 범위가 허용) */
    public boolean canView(HubSkill s, String me, boolean admin) {
        if (admin || s.owner.equals(me)) return true;
        if (!"APPROVED".equals(s.status) || s.publishedVersionId == null) return false;
        if (s.visibility.equals("PUBLIC")) return true;
        String team = teamOf(me);
        return s.visibility.equals("TEAM") && team != null && team.equals(s.team);
    }

    public HubSkill visible(Long id, String me, boolean admin) {
        HubSkill s = skill(id);
        // 접근할 수 없는 Skill 은 존재 여부를 숨긴다
        if (!canView(s, me, admin) || ("ARCHIVED".equals(s.status) && !(admin || s.owner.equals(me)))) throw new ApiException(HttpStatus.NOT_FOUND, "Skill 을 찾을 수 없습니다");
        return s;
    }

    /** 볼 수 있고 수정할 수도 있는 스킬(제작자 또는 관리자). 양식 등록처럼 스킬을 고치는 다른 기능이 쓴다. */
    public HubSkill editable(Long id, String actor, boolean admin) {
        HubSkill s = visible(id, actor, admin);
        requireEdit(s, actor, admin);
        return s;
    }

    private void requireEdit(HubSkill s, String actor, boolean admin) {
        if (!(admin || s.owner.equals(actor))) throw new ApiException(HttpStatus.FORBIDDEN, "제작자 또는 관리자만 수정할 수 있습니다");
    }

    private void recompute(HubSkill s) {
        if ("ARCHIVED".equals(s.status)) return;
        List<HubSkillVersion> all = versions.findBySkillIdOrderByIdDesc(s.id);
        if (s.publishedVersionId != null) s.status = "APPROVED";
        else if (all.stream().anyMatch(v -> v.status.equals("PENDING_REVIEW"))) s.status = "PENDING_REVIEW";
        else if (all.stream().anyMatch(v -> v.status.equals("DRAFT"))) s.status = "DRAFT";
        else s.status = "REJECTED";
        skills.save(s);
    }

    private void touch(HubSkill s, String actor) { s.updatedBy = actor; s.updatedAt = Instant.now(); skills.save(s); }

    private void hist(String type, Long id, Long versionId, String action, String actor, String comment) {
        HubApprovalHistory h = new HubApprovalHistory();
        h.targetType = type; h.targetId = id; h.versionId = versionId; h.action = action; h.actor = actor;
        h.comment = comment == null ? null : limit(comment, 500); h.occurredAt = Instant.now();
        history.save(h);
    }

    void recordEvent(HubSkill s, Long versionId, String actor, String type) {
        HubUsageEvent e = new HubUsageEvent();
        e.skillId = s.id; e.versionId = versionId; e.actor = actor; e.type = type;
        e.counted = !s.sample && !s.owner.equals(actor); // 샘플/제작자 본인 사용은 집계 제외
        e.occurredAt = Instant.now();
        usage.save(e);
    }

    private String pickCategory(String c) {
        String code = c == null || c.isBlank() ? "etc" : c.trim();
        if (!categories.existsById(code)) throw new ApiException(HttpStatus.BAD_REQUEST, "알 수 없는 카테고리입니다: " + code);
        return code;
    }

    private List<String> mergeTags(List<String> a, List<String> b) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (List<String> l : Arrays.asList(a, b)) if (l != null) for (String t : l) {
            String x = t == null ? "" : t.trim();
            if (x.isEmpty()) continue;
            if (!SkillPackageReader.TAG.matcher(x).matches()) throw new ApiException(HttpStatus.BAD_REQUEST, "태그 형식이 올바르지 않습니다: " + limit(x, 30));
            set.add(x);
        }
        if (set.size() > 10) throw new ApiException(HttpStatus.BAD_REQUEST, "태그는 최대 10개입니다");
        return new ArrayList<>(set);
    }

    private void saveTags(Long skillId, List<String> list) { for (String t : list) tags.save(new HubSkillTag(skillId, t)); }

    private void throttleRegister(String user) {
        Deque<Instant> q = registerLog.computeIfAbsent(user, k -> new ArrayDeque<>());
        synchronized (q) {
            Instant now = Instant.now();
            while (!q.isEmpty() && q.peekFirst().isBefore(now.minus(REGISTER_WINDOW))) q.pollFirst();
            if (q.size() >= REGISTER_LIMIT) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "짧은 시간에 너무 많은 Skill 을 등록했습니다. 잠시 후 다시 시도하세요");
            q.addLast(now);
        }
    }

    String teamOf(String username) { return users.findByUsername(username).map(UserAccount::getTeam).orElse(null); }

    /** 사용자 번호(개인 양식의 키를 만들 때 쓴다) */
    public long userId(String username) {
        return users.findByUsername(username).map(com.cenoffice.domain.UserAccount::getId).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다"));
    }

    String displayName(String username) {
        if (BuiltinSkillSeeder.OWNER.equals(username)) return BuiltinSkillSeeder.AUTHOR_NAME; // 내장 도구의 제작자 표시
        return users.findByUsername(username).map(UserAccount::getDisplayName).orElse(username);
    }

    RuntimeInfo runtimeInfo() {
        AgentRuntimeAdapter a = runtime.current();
        return new RuntimeInfo(a.state().name(), a.kind(), a.simulated(), a.description());
    }

    private RuntimeInfo runtimeInfo(HubSkill skill) {
        AgentRuntimeAdapter a = runtime.forSkill(skill);
        return new RuntimeInfo(a.state().name(), a.kind(), a.simulated(), a.description());
    }

    private List<String> flags(HubSkillVersion v) { return v.riskFlags == null || v.riskFlags.isBlank() ? List.of() : Arrays.asList(v.riskFlags.split(" \\| ")); }

    private VersionView versionView(HubSkillVersion v, HubSkill s) {
        return new VersionView(v.id, v.version, v.status, v.createdBy, v.createdAt, v.sha256, v.packageKind, flags(v), Objects.equals(s.publishedVersionId, v.id));
    }

    private VersionContent content(HubSkillVersion v, HubSkill s) {
        Map<String, Object> fm = Map.of();
        List<Map<String, Object>> files = List.of();
        try {
            if (v.frontmatter != null) fm = mapper.readValue(v.frontmatter, new TypeReference<Map<String, Object>>() {});
            if (v.fileList != null) files = mapper.readValue(v.fileList, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception ignored) { /* 표시용 데이터 — 파싱 실패 시 비움 */ }
        return new VersionContent(versionView(v, s), v.skillMd, fm, files, section(v.skillMd, "사용 방법|사용법|usage|how to use"), section(v.skillMd, "실행 예시|예시|example|examples"));
    }

    /** SKILL.md 본문에서 제목(#~###)이 일치하는 섹션을 뽑는다. 없으면 null. */
    static String section(String md, String namesRegex) {
        Matcher m = Pattern.compile("(?im)^(#{1,3})\\s*(?:" + namesRegex + ")\\s*:?\\s*$").matcher(md);
        if (!m.find()) return null;
        int level = m.group(1).length();
        int start = m.end();
        Matcher next = Pattern.compile("(?m)^#{1," + level + "}\\s+\\S").matcher(md);
        int end = next.find(start) ? next.start() : md.length();
        String body = md.substring(start, end).trim();
        return body.isEmpty() ? null : (body.length() > 4000 ? body.substring(0, 4000) : body);
    }

    private List<SkillSummary> summaries(List<HubSkill> list) {
        if (list.isEmpty()) return List.of();
        List<Long> ids = list.stream().map(s -> s.id).toList();
        Map<Long, List<String>> tagMap = new HashMap<>();
        em.createQuery("select t from HubSkillTag t where t.skillId in :ids", HubSkillTag.class).setParameter("ids", ids).getResultList()
                .forEach(t -> tagMap.computeIfAbsent(t.skillId, k -> new ArrayList<>()).add(t.tag));
        Map<Long, long[]> stat = new HashMap<>(); // [requests, succeeded]
        for (Object[] r : em.createQuery("select u.skillId, u.type, count(u) from HubUsageEvent u where u.counted = true and u.skillId in :ids group by u.skillId, u.type", Object[].class)
                .setParameter("ids", ids).getResultList()) {
            long[] a = stat.computeIfAbsent((Long) r[0], k -> new long[2]);
            if ("EXECUTION_REQUESTED".equals(r[1])) a[0] = (Long) r[2];
            if ("EXECUTION_SUCCEEDED".equals(r[1])) a[1] = (Long) r[2];
        }
        Map<Long, double[]> rate = new HashMap<>();
        for (Object[] r : em.createQuery("select r.skillId, avg(r.rating), count(r) from HubSkillReview r where r.skillId in :ids group by r.skillId", Object[].class)
                .setParameter("ids", ids).getResultList()) rate.put((Long) r[0], new double[]{((Number) r[1]).doubleValue(), ((Number) r[2]).doubleValue()});
        Map<String, String> catNames = categories.findAll().stream().collect(Collectors.toMap(c -> c.code, c -> c.name));
        Map<String, String> names = new HashMap<>();
        Map<Long, String> vlabel = new HashMap<>();
        for (HubSkillVersion v : em.createQuery("select v from HubSkillVersion v where v.skillId in :ids order by v.id asc", HubSkillVersion.class).setParameter("ids", ids).getResultList()) {
            HubSkill s = list.stream().filter(x -> x.id.equals(v.skillId)).findFirst().orElse(null);
            if (s == null) continue;
            if (Objects.equals(s.publishedVersionId, v.id) || (s.publishedVersionId == null)) vlabel.put(s.id, v.version);
        }
        String state = runtimeInfo().state();
        List<SkillSummary> out = new ArrayList<>();
        for (HubSkill s : list) {
            long[] st = stat.getOrDefault(s.id, new long[2]);
            double[] r = rate.getOrDefault(s.id, new double[]{0, 0});
            String exec = s.publishedVersionId == null ? "REGISTRY_ONLY" : BuiltinSkillSeeder.trusted(s) ? runtimeInfo(s).state() : state;
            out.add(new SkillSummary(s.id, s.name, s.description, s.category, catNames.getOrDefault(s.category, s.category), s.icon, s.visibility, s.team, s.status,
                    s.owner, names.computeIfAbsent(s.owner, this::displayName), vlabel.get(s.id), tagMap.getOrDefault(s.id, List.of()).stream().sorted().toList(),
                    st[0], st[1], Math.round(r[0] * 10) / 10.0, (long) r[1], s.sample, s.updatedAt, exec, s.title));
        }
        return out;
    }

    private ReviewView reviewView(HubSkillReview r) { return new ReviewView(r.id, r.reviewer, displayName(r.reviewer), r.rating, r.comment, r.updatedAt); }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    static String limit(String s, int n) { return s == null ? null : (s.length() > n ? s.substring(0, n) : s); }

    // ── 컨트롤러가 저장소를 직접 쓰지 않도록 여는 조회 ──

    public List<CategoryView> categories() {
        return categories.findAll().stream().sorted(java.util.Comparator.comparingInt(c -> c.sortOrder)).map(c -> new CategoryView(c.code, c.name)).toList();
    }

    /** 로그인한 사용자의 AI HUB 권한 요약 */
    public Map<String, Object> profile(String username, boolean admin) {
        var u = users.findByUsername(username).orElse(null);
        return Map.of("username", username, "displayName", u == null ? username : u.getDisplayName(), "team", u == null || u.getTeam() == null ? "" : u.getTeam(),
                "role", admin ? "ADMIN" : "USER", "canRegister", true, "canApprove", admin);
    }

    public Optional<HubSkill> findByName(String name) { return skills.findByName(name); }

    /** 내장 문서 도구 중 이 사용자에게 보이는 것의 상세 */
    public List<SkillDetail> builtinDocumentTools(String username, boolean admin) {
        return BuiltinSkillSeeder.DOCUMENT_TOOLS.stream().map(skills::findByName).flatMap(Optional::stream).filter(BuiltinSkillSeeder::trusted)
            .map(s -> detail(s.id, null, username, admin)).toList();
    }

    /** 이름순 전체 도구(내보내기용) */
    public List<HubSkill> allByName() { return skills.findAll(org.springframework.data.domain.Sort.by("name")); }

    public Optional<HubSkillVersion> versionById(Long id) { return versions.findById(id); }
}
