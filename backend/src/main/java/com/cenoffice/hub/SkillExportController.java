package com.cenoffice.hub;

import com.cenoffice.api.ApiException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 승인된 Skill 을 Claude Code / Codex 등이 읽는 폴더 구조(<name>/SKILL.md)의 ZIP 으로 내려준다.
 * 열람 권한이 있는 승인 Skill 만 포함하고, 내장 도구는 항상 최신 번들 SKILL.md 를 사용한다.
 */
@RestController
@RequestMapping("/api/hub/skills")
public class SkillExportController {
    private static final int MAX_FILES = 200;
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private final HubSkillRepository skills;
    private final HubSkillVersionRepository versions;
    private final SkillService service;
    private final com.cenoffice.api.AuditService audit;

    public SkillExportController(HubSkillRepository skills, HubSkillVersionRepository versions, SkillService service, com.cenoffice.api.AuditService audit) {
        this.skills = skills; this.versions = versions; this.service = service; this.audit = audit;
    }

    @GetMapping(value = "/export.zip")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) List<Long> ids, @RequestParam(defaultValue = "false") boolean includeSamples, Authentication a) throws java.io.IOException {
        boolean admin = HubSkillController.admin(a);
        String me = a.getName();
        Set<Long> wanted = ids == null ? null : new HashSet<>(ids);
        var out = new ByteArrayOutputStream();
        List<String> included = new ArrayList<>();
        long total = 0;
        try (var zip = new ZipOutputStream(out)) {
            for (HubSkill s : skills.findAll(org.springframework.data.domain.Sort.by("name"))) {
                if (wanted != null && !wanted.contains(s.id)) continue;
                if (wanted == null && s.sample && !includeSamples) continue;
                if (!"APPROVED".equals(s.status) || s.publishedVersionId == null || !service.canView(s, me, admin)) continue;
                if (!s.name.matches("[a-z0-9][a-z0-9-]{0,63}")) continue; // 폴더 이름으로 안전한 이름만
                var v = versions.findById(s.publishedVersionId).orElse(null);
                if (v == null || !"APPROVED".equals(v.status)) continue;
                String md = BuiltinSkillSeeder.trusted(s) ? BuiltinSkillSeeder.bundledMd(s.name, v.skillMd) : withInternalName(v.skillMd, s.name, s.title);
                total += put(zip, s.name + "/SKILL.md", md.getBytes(StandardCharsets.UTF_8));
                if ("ZIP".equals(v.packageKind) && v.packageData != null) total += extras(zip, s.name, v.packageData);
                included.add(s.name);
                if (included.size() > MAX_FILES || total > MAX_BYTES) throw new ApiException(HttpStatus.BAD_REQUEST, "내보낼 Skill 이 너무 많습니다. ids 로 나누어 받으세요");
            }
            if (included.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "내보낼 수 있는 승인된 Skill 이 없습니다");
            put(zip, "README.md", readme(included).getBytes(StandardCharsets.UTF_8));
        }
        audit.log(me, "HUB_SKILLS_EXPORTED", "hub-skills", included.size() + "개");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("cen-ai-hub-skills.zip").build().toString())
            .header("X-Content-Type-Options", "nosniff").body(out.toByteArray());
    }

    /** frontmatter 의 name 이 내부 이름과 다르면(예: 한글 이름) 내부 이름으로 바꾸고 표시 이름은 title 로 남긴다. */
    static String withInternalName(String md, String name, String title) {
        if (!md.startsWith("---")) return md;
        int end = md.indexOf("\n---", 3);
        if (end < 0) return md;
        String fm = md.substring(0, end), rest = md.substring(end);
        var m = java.util.regex.Pattern.compile("(?m)^name:\\s*(.*)$").matcher(fm);
        if (!m.find() || m.group(1).strip().replaceAll("^[\"']|[\"']$", "").equals(name)) return md;
        String extra = title != null && !java.util.regex.Pattern.compile("(?m)^title:").matcher(fm).find() ? "\ntitle: " + title.replace("\n", " ") : "";
        return fm.substring(0, m.start()) + "name: " + name + extra + fm.substring(m.end()) + rest;
    }

    private static long put(ZipOutputStream zip, String name, byte[] data) throws java.io.IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(data); zip.closeEntry();
        return data.length;
    }

    /** ZIP 패키지의 부속 파일(SKILL.md 제외)을 안전한 상대 경로만 골라 담는다. */
    private static long extras(ZipOutputStream zip, String dir, byte[] pkg) throws java.io.IOException {
        long total = 0;
        try (var in = new ZipInputStream(new ByteArrayInputStream(pkg))) {
            ZipEntry e;
            int n = 0;
            while ((e = in.getNextEntry()) != null && n < 50) {
                String name = e.getName().replace('\\', '/');
                if (e.isDirectory() || name.startsWith("/") || name.contains("..") || name.equalsIgnoreCase("SKILL.md") || name.toLowerCase(Locale.ROOT).endsWith("/skill.md")) continue;
                byte[] data = in.readNBytes(2 * 1024 * 1024);
                total += put(zip, dir + "/" + name, data);
                n++;
            }
        }
        return total;
    }

    private static String readme(List<String> names) {
        return """
            # CEN OFFICE AI HUB — Skill 묶음

            포함된 Skill: %s

            ## Claude Code
            개인용: `~/.claude/skills/` 폴더에 이 ZIP 의 Skill 폴더들을 풀어 넣습니다.
            프로젝트용: 저장소의 `.claude/skills/` 에 풀어 넣고 커밋하면 팀원과 공유됩니다.

            ## Codex
            `~/.codex/skills/` (또는 프로젝트의 `.codex/skills/`) 에 풀어 넣습니다.

            ## 그 밖의 도구
            `<이름>/SKILL.md` 를 해당 도구의 지침/스킬 폴더에 넣거나 내용을 시스템 지침으로 붙여 넣으세요.

            ## 주의
            - 이 파일의 지침은 AI 가 읽고 따르는 문서이며, AI HUB 서버의 승인된 버전입니다.
            - 결과는 검토용 초안입니다. 개인정보·비밀번호·토큰은 입력하지 마세요.
            - 서버에서 바로 실행하려면 AI HUB 화면의 '사용하기'를 이용하세요.
            """.formatted(String.join(", ", names));
    }
}
