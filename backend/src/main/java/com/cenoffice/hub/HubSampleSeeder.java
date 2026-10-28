package com.cenoffice.hub;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 샘플 Skill/Agent(error-detective, requirement-analyst 등)를 등록한다.
 * 실제 등록 리소스와 구분하기 위해 sample=true 이며 소유자는 로그인 불가능한 시스템 계정(hub-sample)이다.
 * app.hub.seed-samples=true 일 때만 동작한다(기본 꺼짐: 실제로 동작하지 않는 예시라 사용자에게 혼란을 준다. 테스트는 켠다).
 * 샘플은 모든 KPI/통계(등록 수, 실행 수, 활성 사용자)에서 제외된다. SKILL.md 는 일반 등록과 같은 검증기를 통과해야 한다.
 */
@Component
@Order(10)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.hub.seed-samples", havingValue = "true")
public class HubSampleSeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(HubSampleSeeder.class);
    public static final String OWNER = "hub-sample";

    private final HubSkillRepository skills;
    private final HubSkillVersionRepository versions;
    private final HubSkillTagRepository tags;
    private final HubAgentRepository agents;
    private final HubAgentSkillRepository links;
    private final HubAgentVersionRepository agentVersions;
    private final SkillPackageReader reader;

    public HubSampleSeeder(HubSkillRepository skills, HubSkillVersionRepository versions, HubSkillTagRepository tags, HubAgentRepository agents,
                           HubAgentSkillRepository links, HubAgentVersionRepository agentVersions, SkillPackageReader reader) {
        this.skills = skills;
        this.versions = versions;
        this.tags = tags;
        this.agents = agents;
        this.links = links;
        this.agentVersions = agentVersions;
        this.reader = reader;
    }

    @Override
    public void run(String... args) {
        try {
            Long ed = seedSkill("hub-samples/error-detective/SKILL.md");
            Long ra = seedSkill("hub-samples/requirement-analyst/SKILL.md");
            seedAgent("개발 지원 Agent (샘플)", "오류 분석을 돕는 샘플 Agent 입니다. 실제 업무용이 아닌 예시 구성입니다.", "Java/Spring/DB 오류 분석", List.of(ed));
            seedAgent("프로젝트 관리 Agent (샘플)", "요구사항 분석과 작업 분해를 돕는 샘플 Agent 입니다. 실제 업무용이 아닌 예시 구성입니다.", "요구사항 분석, 작업 목록 작성", List.of(ra));
        } catch (Exception e) {
            log.warn("AI HUB 샘플 등록을 건너뜁니다: {}", e.getMessage());
        }
    }

    private Long seedSkill(String path) throws Exception {
        byte[] bytes;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("샘플 파일이 없습니다: " + path);
            bytes = in.readAllBytes();
        }
        SkillPackageReader.Parsed p = reader.readMarkdown(bytes);
        HubSkill existing = skills.findByName(p.meta().name()).orElse(null);
        if (existing != null) return existing.id;
        Instant now = Instant.now();
        HubSkill s = new HubSkill();
        s.name = p.meta().name(); s.description = p.meta().description(); s.category = p.meta().category() == null ? "etc" : p.meta().category();
        s.icon = p.meta().icon() == null ? "🧩" : p.meta().icon(); s.visibility = "PUBLIC"; s.status = "APPROVED"; s.owner = OWNER; s.sample = true;
        s.createdBy = OWNER; s.createdAt = now; s.updatedBy = OWNER; s.updatedAt = now;
        s = skills.save(s);
        for (String t : p.meta().tags()) tags.save(new HubSkillTag(s.id, t));
        HubSkillVersion v = new HubSkillVersion();
        v.skillId = s.id; v.version = p.meta().version() == null ? "1.0.0" : p.meta().version(); v.status = "APPROVED";
        v.frontmatter = "{}"; v.skillMd = p.skillMd(); v.fileList = p.fileListJson(); v.packageKind = "MD"; v.packageData = p.packageData(); v.sha256 = p.sha256();
        v.riskFlags = p.riskFlags().isEmpty() ? null : String.join(" | ", p.riskFlags());
        v.createdBy = OWNER; v.createdAt = now;
        v = versions.save(v);
        s.publishedVersionId = v.id;
        skills.save(s);
        return s.id;
    }

    private void seedAgent(String name, String desc, String duties, List<Long> skillIds) {
        if (agents.findByName(name).isPresent()) return;
        Instant now = Instant.now();
        HubAgent a = new HubAgent();
        a.name = name; a.description = desc; a.duties = duties; a.config = "{}"; a.visibility = "PUBLIC"; a.status = "APPROVED"; a.owner = OWNER; a.version = 1; a.sample = true;
        a.createdBy = OWNER; a.createdAt = now; a.updatedBy = OWNER; a.updatedAt = now;
        a = agents.save(a);
        int i = 0;
        for (Long id : skillIds) links.save(new HubAgentSkill(a.id, id, i++));
        HubAgentVersion v = new HubAgentVersion();
        v.agentId = a.id; v.version = 1; v.snapshot = "{\"sample\":true}"; v.createdBy = OWNER; v.createdAt = now;
        agentVersions.save(v);
    }
}
