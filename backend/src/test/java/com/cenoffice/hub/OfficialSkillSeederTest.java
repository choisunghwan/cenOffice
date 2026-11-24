package com.cenoffice.hub;

import static org.junit.jupiter.api.Assertions.*;

import com.cenoffice.hub.domain.HubSkillRepository;
import com.cenoffice.hub.domain.HubSkillVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 기본 AI 스킬(코드 리뷰, 모던 웹 코드 가이드): 승인·전사 공개로 설치되고, 다시 돌려도 버전이 늘지 않으며, 검토가 필요한 표현이 없다 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:officialseed;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.seed.admin-password=test-admin-pw-1234", "app.seed.user-password=test-user-pw-1234",
        "app.jwt.secret=test-secret-test-secret-test-secret-123456"})
class OfficialSkillSeederTest {
    @Autowired OfficialSkillSeeder seeder;
    @Autowired HubSkillRepository skills;
    @Autowired HubSkillVersionRepository versions;

    @Test void officialAiSkillsAreInstalledOnceAndClean() throws Exception {
        seeder.run();
        var reader = new SkillPackageReader(new ObjectMapper());
        for (String name : OfficialSkillSeeder.NAMES) {
            var s = skills.findByName(name).orElseThrow();
            assertEquals("APPROVED", s.status);
            assertEquals("PUBLIC", s.visibility);
            assertEquals(OfficialSkillSeeder.OWNER, s.owner);
            assertNotNull(s.title);
            assertFalse(BuiltinSkillSeeder.trusted(s), "규칙 내장 스킬이 아니라 AI 로 실행된다");
            var v = versions.findById(s.publishedVersionId).orElseThrow();
            assertEquals(1, versions.findAll().stream().filter(x -> x.skillId.equals(s.id)).count(), "두 번 돌려도 버전이 하나");
            assertEquals(java.util.List.of(), reader.readMarkdown(v.skillMd.getBytes(StandardCharsets.UTF_8)).riskFlags(), name + " 에 검토가 필요한 표현이 없어야 한다");
        }
    }

    /** Claude Code 로 내려받을 때 그대로 쓰이도록 앞머리에 name·description 이 있고, 켜고 끄는 방법이 적혀 있다 */
    @Test void leanModeIsReadyForClaudeCode() throws Exception {
        seeder.run();
        var s = skills.findByName("cen-lean-mode").orElseThrow();
        String md = versions.findById(s.publishedVersionId).orElseThrow().skillMd;
        String front = md.substring(0, md.indexOf("\n---", 3));
        assertTrue(front.contains("\nname: cen-lean-mode"));
        assertTrue(front.contains("\ndescription: "));
        assertEquals(md, SkillExportController.withInternalName(md, s.name, s.title), "내보낼 때 바뀌지 않는다");
        assertTrue(md.contains("/cen-lean-mode") && md.contains("최소 구현 모드 꺼"));
    }
}
