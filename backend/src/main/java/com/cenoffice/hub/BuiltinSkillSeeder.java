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

/** Trusted bundled skills, versioned through the existing registry; never overwrite employee content. */
@Component
@Order(11)
public class BuiltinSkillSeeder implements CommandLineRunner {
    public static final String OWNER = "hub-builtin";
    /** 화면의 제작자 칸에 보이는 이름(내장 도구는 계정이 없는 시스템 소유자라 따로 지정) */
    public static final String AUTHOR_NAME = "최성환";
    public static final List<String> NAMES = List.of("cen-fp-estimator", "cen-proposal-writer", "cen-log-analyzer", "cen-sql-reviewer", "cen-meeting-minutes", "cen-weekly-report", "cen-test-case-writer");
    /** 파일 업로드(문서 입력)로 쓰는 도구. 나머지는 텍스트 입력 도구 */
    public static final List<String> DOCUMENT_TOOLS = List.of("cen-fp-estimator", "cen-proposal-writer");
    private final HubSkillRepository skills;
    private final HubSkillVersionRepository versions;
    private final SkillPackageReader reader;
    private final com.cenoffice.common.api.AuditService audit;
    public BuiltinSkillSeeder(HubSkillRepository skills, HubSkillVersionRepository versions, SkillPackageReader reader, com.cenoffice.common.api.AuditService audit) {
        this.skills = skills; this.versions = versions; this.reader = reader; this.audit = audit;
    }
    /** 번들된 최신 SKILL.md (DB 에 저장된 초기 버전보다 최신일 수 있다). 없으면 fallback */
    public static String bundledMd(String name, String fallback) {
        try (var in = BuiltinSkillSeeder.class.getClassLoader().getResourceAsStream("hub-builtins/" + name + "/SKILL.md")) {
            return in == null ? fallback : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) { return fallback; }
    }
    public static boolean trusted(HubSkill s) { return trusted(s.owner, s.name); }
    public static boolean trusted(String owner, String name) { return OWNER.equals(owner) && NAMES.contains(name); }
    @Override @Transactional
    public void run(String... args) throws Exception {
        for (String name : NAMES) {
            if (skills.findByName(name).isPresent()) continue;
            var path = "hub-builtins/" + name + "/SKILL.md";
            SkillPackageReader.Parsed p;
            try (var in = getClass().getClassLoader().getResourceAsStream(path)) {
                if (in == null) throw new IllegalStateException("내장 Skill 파일 누락: " + path);
                p = reader.readMarkdown(in.readAllBytes());
            }
            var now = Instant.now();
            var s = new HubSkill();
            s.name = name; s.description = p.meta().description(); s.category = p.meta().category(); s.icon = p.meta().icon();
            s.visibility = "PUBLIC"; s.status = "APPROVED"; s.owner = OWNER; s.sample = false;
            s.createdBy = OWNER; s.updatedBy = OWNER; s.createdAt = now; s.updatedAt = now;
            s = skills.save(s);
            var v = new HubSkillVersion();
            v.skillId = s.id; v.version = p.meta().version(); v.status = "APPROVED"; v.frontmatter = "{}"; v.skillMd = p.skillMd();
            v.fileList = p.fileListJson(); v.packageKind = "MD"; v.packageData = p.packageData(); v.sha256 = p.sha256(); v.createdBy = OWNER; v.createdAt = now;
            v = versions.save(v); s.publishedVersionId = v.id; skills.save(s);
            audit.log(OWNER, "HUB_BUILTIN_INSTALLED", "hub-skill:" + s.id, name + " v" + v.version);
        }
    }
}
