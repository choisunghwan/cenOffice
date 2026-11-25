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
            // 스킬 폴더(SKILL.md + references·templates·examples)를 ZIP 패키지로 묶어 직원 업로드와 같은 검사를 거친다
            SkillPackageReader.Parsed p = reader.readZip(bundle(name));
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
            v.fileList = p.fileListJson(); v.packageKind = p.packageKind(); v.packageData = p.packageData(); v.sha256 = p.sha256(); v.createdBy = OWNER; v.createdAt = now;
            v = versions.save(v); s.publishedVersionId = v.id; skills.save(s);
            audit.log(OWNER, existing == null ? "HUB_OFFICIAL_INSTALLED" : "HUB_OFFICIAL_UPGRADED", "hub-skill:" + s.id, name + " v" + v.version);
        }
    }

    /** resources/hub-official/{name}/ 아래 파일을 '{name}/상대경로' 로 묶은 ZIP. 시각을 고정해 매번 같은 바이트가 나온다 */
    static byte[] bundle(String name) throws java.io.IOException {
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver(OfficialSkillSeeder.class.getClassLoader());
        String base = "hub-official/" + name + "/";
        var files = new java.util.TreeMap<String, byte[]>();
        for (var r : resolver.getResources("classpath*:" + base + "**/*")) {
            if (!r.isReadable()) continue;
            String url = r.getURL().toString();
            int i = url.lastIndexOf(base);
            if (i < 0) continue;
            String rel = url.substring(i + base.length());
            if (rel.isEmpty() || rel.endsWith("/")) continue;
            try (var in = r.getInputStream()) { files.put(rel, in.readAllBytes()); }
        }
        if (!files.containsKey("SKILL.md")) throw new IllegalStateException("기본 AI 스킬 파일 누락: " + base + "SKILL.md");
        var out = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(out)) {
            for (var e : files.entrySet()) {
                var entry = new java.util.zip.ZipEntry(name + "/" + e.getKey());
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
