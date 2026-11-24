package com.cenoffice.hub;

import com.cenoffice.hub.domain.HubSkill;
import com.cenoffice.hub.domain.HubSkillRepository;
import com.cenoffice.hub.domain.HubSkillVersion;
import com.cenoffice.hub.domain.HubSkillVersionRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회사가 기본으로 넣어 두는 AI 스킬(resources/hub-official). 규칙으로 도는 내장 스킬과 달리 AI 로 실행되므로
 * HUB_AI_ENGINE 이 켜져 있어야 실행할 수 있다. 처음 시작할 때 승인·전사 공개로 설치하고,
 * 번들 SKILL.md 의 version 이 DB 에 없으면 새 버전으로 바꾼다. 같은 이름을 직원이 쓰고 있으면 건드리지 않는다.
 */
@Component
@Order(12)
public class OfficialSkillSeeder implements CommandLineRunner {
    public static final String OWNER = "hub-official";
    public static final List<String> NAMES = List.of("cen-code-review", "cen-modern-web", "cen-lean-mode");

    private final HubSkillRepository skills;
    private final HubSkillVersionRepository versions;
    private final SkillPackageReader reader;
    private final com.cenoffice.common.api.AuditService audit;

    public OfficialSkillSeeder(HubSkillRepository skills, HubSkillVersionRepository versions, SkillPackageReader reader, com.cenoffice.common.api.AuditService audit) {
        this.skills = skills; this.versions = versions; this.reader = reader; this.audit = audit;
    }

    @Override @Transactional
    public void run(String... args) throws Exception {
        for (String name : NAMES) {
            var path = "hub-official/" + name + "/SKILL.md";
            SkillPackageReader.Parsed p;
            try (var in = getClass().getClassLoader().getResourceAsStream(path)) {
                if (in == null) throw new IllegalStateException("기본 AI 스킬 파일 누락: " + path);
                p = reader.readMarkdown(in.readAllBytes());
            }
            var existing = skills.findByName(name).orElse(null);
            if (existing != null && !OWNER.equals(existing.owner)) continue; // 직원이 먼저 쓴 이름이면 그대로 둔다
            if (existing != null && versions.findBySkillIdAndVersion(existing.id, p.meta().version()).isPresent()) continue;
            var now = Instant.now();
            HubSkill s = existing;
            if (s == null) {
                s = new HubSkill();
                s.name = name; s.visibility = "PUBLIC"; s.status = "APPROVED"; s.owner = OWNER; s.sample = false;
                s.createdBy = OWNER; s.createdAt = now;
            }
            s.title = p.meta().title(); s.description = p.meta().description(); s.category = p.meta().category(); s.icon = p.meta().icon();
            s.updatedBy = OWNER; s.updatedAt = now;
            s = skills.save(s);
            var v = new HubSkillVersion();
            v.skillId = s.id; v.version = p.meta().version(); v.status = "APPROVED"; v.frontmatter = "{}"; v.skillMd = p.skillMd();
            v.fileList = p.fileListJson(); v.packageKind = "MD"; v.packageData = p.packageData(); v.sha256 = p.sha256(); v.createdBy = OWNER; v.createdAt = now;
            v = versions.save(v); s.publishedVersionId = v.id; skills.save(s);
            audit.log(OWNER, existing == null ? "HUB_OFFICIAL_INSTALLED" : "HUB_OFFICIAL_UPGRADED", "hub-skill:" + s.id, name + " v" + v.version);
        }
    }
}
