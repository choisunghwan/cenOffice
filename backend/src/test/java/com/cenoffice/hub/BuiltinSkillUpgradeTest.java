package com.cenoffice.hub;

import static org.junit.jupiter.api.Assertions.*;

import com.cenoffice.hub.domain.HubSkillRepository;
import com.cenoffice.hub.domain.HubSkillVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 이미 설치된 내장 스킬도, 번들 SKILL.md 의 version 이 올라가면 서버 시작 시 새 버전으로 공개된다 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:builtinupgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.seed.admin-password=test-admin-pw-1234",
        "app.seed.user-password=test-user-pw-1234",
        "app.jwt.secret=test-secret-test-secret-test-secret-123456"})
class BuiltinSkillUpgradeTest {
    @Autowired BuiltinSkillSeeder seeder;
    @Autowired HubSkillRepository skills;
    @Autowired HubSkillVersionRepository versions;

    @Test
    void olderInstalledBuiltinIsUpgradedToBundledVersion() throws Exception {
        var s = skills.findByName("cen-log-analyzer").orElseThrow();
        var current = versions.findById(s.publishedVersionId).orElseThrow();
        String bundled = current.version;
        // 예전 서버처럼 이전 버전만 공개된 상태로 되돌린다
        current.version = "0.9.0"; current.skillMd = current.skillMd.replace("스킬이며", "예전문구이며");
        versions.save(current);

        seeder.run();
        seeder.run(); // 두 번 돌려도 버전이 또 생기지 않는다

        var after = skills.findByName("cen-log-analyzer").orElseThrow();
        var pub = versions.findById(after.publishedVersionId).orElseThrow();
        assertEquals(bundled, pub.version);
        assertTrue(pub.skillMd.contains("규칙 기반 스킬이며"));
        assertNotEquals(current.id, pub.id);
        assertTrue(versions.findBySkillIdAndVersion(after.id, "0.9.0").isPresent(), "이전 버전 기록은 남긴다");
    }

    /** 빼기로 한 내장 스킬(제안서 작성 도우미)은 이미 설치된 서버에서도 시작할 때 보관 처리된다 */
    @Test
    void retiredBuiltinIsArchivedOnStartup() throws Exception {
        var s = new com.cenoffice.hub.domain.HubSkill();
        s.name = "cen-proposal-writer"; s.description = "옛 내장 스킬"; s.category = "doc"; s.visibility = "PUBLIC"; s.status = "APPROVED";
        s.owner = BuiltinSkillSeeder.OWNER; s.sample = false; s.createdBy = s.owner; s.updatedBy = s.owner;
        s.createdAt = java.time.Instant.now(); s.updatedAt = s.createdAt;
        skills.save(s);
        seeder.run();
        assertEquals("ARCHIVED", skills.findByName("cen-proposal-writer").orElseThrow().status);
    }
}
