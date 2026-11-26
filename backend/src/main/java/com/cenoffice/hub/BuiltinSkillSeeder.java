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
    /** 화면의 제작자 칸에 보이는 이름(내장 스킬은 계정이 없는 시스템 소유자라 따로 지정) */
    public static final String AUTHOR_NAME = "최성환";
    public static final List<String> NAMES = List.of("cen-fp-estimator", "cen-log-analyzer", "cen-sql-reviewer", "cen-meeting-minutes", "skill-creator");
    /** 파일 업로드(문서 입력)로 쓰는 스킬. 나머지는 텍스트 입력 스킬 */
    public static final List<String> DOCUMENT_TOOLS = List.of("cen-fp-estimator");
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
    /** 쓸모가 없어 빼기로 한 내장 스킬. 이미 설치된 서버에서는 시작할 때 보관(ARCHIVED) 처리해 목록·검색·실행에서 뺀다(기록은 남긴다) */
    public static final List<String> RETIRED = List.of("cen-proposal-writer");
    /** 대화로 진행하는 내장 스킬(한 번 실행해서 끝나지 않는다). 홈 입력창과 스킬 만들기 화면이 대화로 연다 */
    public static final String CREATOR = "skill-creator";
    public static boolean trusted(HubSkill s) { return trusted(s.owner, s.name); }
    public static boolean trusted(String owner, String name) { return OWNER.equals(owner) && NAMES.contains(name); }
    @Override @Transactional
    public void run(String... args) throws Exception {
        for (String name : RETIRED) {
            skills.findByName(name).filter(s -> OWNER.equals(s.owner) && !"ARCHIVED".equals(s.status)).ifPresent(s -> {
                s.status = "ARCHIVED"; s.updatedBy = OWNER; s.updatedAt = Instant.now();
                skills.save(s);
                audit.log(OWNER, "HUB_BUILTIN_RETIRED", "hub-skill:" + s.id, name);
            });
        }
        for (String name : NAMES) {
            var path = "hub-builtins/" + name + "/SKILL.md";
            SkillPackageReader.Parsed p;
            try (var in = getClass().getClassLoader().getResourceAsStream(path)) {
                if (in == null) throw new IllegalStateException("내장 Skill 파일 누락: " + path);
                p = reader.readMarkdown(in.readAllBytes());
            }
            var existing = skills.findByName(name);
            if (existing.isPresent()) {
                // 예전에 화면 이름(title) 없이 설치된 내장 스킬은 SKILL.md 제목으로 채운다(CenNavi·목록이 title 을 쓴다)
                var cur = existing.get();
                if (trusted(cur) && (cur.title == null || cur.title.isBlank())) { cur.title = titleOf(p); skills.save(cur); }
                upgrade(cur, p);
                continue;
            }
            var now = Instant.now();
            var s = new HubSkill();
            s.name = name; s.title = titleOf(p); s.description = p.meta().description(); s.category = p.meta().category(); s.icon = p.meta().icon();
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

    /**
     * 번들된 SKILL.md 의 version 이 DB 에 없는 새 버전이면 그 버전을 추가하고 공개 버전으로 바꾼다.
     * 내장 소유자(hub-builtin)의 스킬만 대상이며, 같은 이름을 직원이 쓰고 있으면 건드리지 않는다.
     */
    /** 화면 이름: 앞머리 title, 없으면 SKILL.md 첫 제목(# …) */
    static String titleOf(SkillPackageReader.Parsed p) {
        if (p.meta().title() != null && !p.meta().title().isBlank()) return p.meta().title().strip();
        for (String line : p.skillMd().split("\\R")) if (line.startsWith("# ") && line.length() > 2) return line.substring(2).strip();
        return null;
    }

    private void upgrade(HubSkill s, SkillPackageReader.Parsed p) {
        if (!trusted(s)) return;
        String ver = p.meta().version();
        if (versions.findBySkillIdAndVersion(s.id, ver).isPresent()) return;
        var now = Instant.now();
        var v = new HubSkillVersion();
        v.skillId = s.id; v.version = ver; v.status = "APPROVED"; v.frontmatter = "{}"; v.skillMd = p.skillMd();
        v.fileList = p.fileListJson(); v.packageKind = "MD"; v.packageData = p.packageData(); v.sha256 = p.sha256(); v.createdBy = OWNER; v.createdAt = now;
        v = versions.save(v);
        s.description = p.meta().description(); s.category = p.meta().category(); s.icon = p.meta().icon();
        s.publishedVersionId = v.id; s.updatedBy = OWNER; s.updatedAt = now;
        skills.save(s);
        audit.log(OWNER, "HUB_BUILTIN_UPGRADED", "hub-skill:" + s.id, s.name + " v" + ver);
    }
}
