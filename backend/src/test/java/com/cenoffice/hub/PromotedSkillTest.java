package com.cenoffice.hub;

import static org.junit.jupiter.api.Assertions.*;

import com.cenoffice.hub.domain.HubSkill;
import com.cenoffice.hub.domain.HubSkillRepository;
import com.cenoffice.hub.domain.HubSkillVersion;
import com.cenoffice.hub.domain.HubSkillVersionRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 규칙 내장 스킬이던 주간 업무보고·테스트 케이스가 이미 설치된 서버에서는 같은 스킬을 이어받아 AI 스킬 2.0.0 이 된다. 모든 기본 스킬에는 예시가 있다 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:promoted;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.seed.admin-password=test-admin-pw-1234", "app.seed.user-password=test-user-pw-1234",
        "app.jwt.secret=test-secret-test-secret-test-secret-123456"})
class PromotedSkillTest {
    @Autowired OfficialSkillSeeder official;
    @Autowired BuiltinSkillSeeder builtin;
    @Autowired HubSkillRepository skills;
    @Autowired HubSkillVersionRepository versions;

    @Test void oldRuleSkillBecomesAiSkillKeepingItsId() throws Exception {
        // 시작 시 이미 설치된 AI 스킬을 지우고, 예전 서버처럼 규칙 내장 1.0.0 만 있는 상태로 만든다
        skills.findByName("cen-weekly-report").ifPresent(x -> {
            x.publishedVersionId = null; skills.saveAndFlush(x);
            versions.findAll().stream().filter(y -> y.skillId.equals(x.id)).forEach(versions::delete);
            versions.flush(); skills.delete(x); skills.flush();
        });
        var old = new HubSkill();
        old.name = "cen-weekly-report"; old.title = "주간 업무보고 정리"; old.description = "규칙 기반 주간 보고"; old.category = "doc"; old.icon = "📅"; old.owner = BuiltinSkillSeeder.OWNER; old.status = "APPROVED"; old.visibility = "PUBLIC";
        old.createdBy = BuiltinSkillSeeder.OWNER; old.createdAt = Instant.now(); old.updatedBy = old.createdBy; old.updatedAt = old.createdAt;
        old = skills.save(old);
        var v = new HubSkillVersion();
        v.skillId = old.id; v.version = "1.0.0"; v.status = "APPROVED"; v.frontmatter = "{}"; v.skillMd = "# old";
        v.packageKind = "MD"; v.packageData = "# old".getBytes(StandardCharsets.UTF_8); v.sha256 = "x"; v.createdBy = old.owner; v.createdAt = Instant.now();
        v = versions.save(v); old.publishedVersionId = v.id; skills.save(old);

        official.run();
        var s = skills.findByName("cen-weekly-report").orElseThrow();
        assertEquals(old.id, s.id, "같은 스킬(기록·평가)을 이어받는다");
        assertEquals(OfficialSkillSeeder.OWNER, s.owner);
        assertFalse(BuiltinSkillSeeder.trusted(s));
        var pub = versions.findById(s.publishedVersionId).orElseThrow();
        assertEquals("2.0.0", pub.version);
        assertEquals("ZIP", pub.packageKind);
    }

    @Test void everyBuiltinSkillHasAnExample() throws Exception {
        builtin.run(); official.run();
        for (String name : BuiltinSkillSeeder.NAMES) {
            var s = skills.findByName(name).orElseThrow();
            String md = versions.findById(s.publishedVersionId).orElseThrow().skillMd;
            assertNotNull(SkillService.section(md, "실행 예시|예시|example|examples"), name + " 예시");
        }
    }
}
