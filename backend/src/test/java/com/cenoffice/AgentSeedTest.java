package com.cenoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cenoffice.common.seed.DataSeeder;
import com.cenoffice.office.agent.AiAgent;
import com.cenoffice.office.agent.AiAgentRepository;
import com.cenoffice.office.agent.WsEnums;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 예전 AI PM 시절의 AI 직원 데이터가 새 이름으로 정리되는지 확인한다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agentseedtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "app.seed.admin-password=test-admin-pw-1234",
        "app.seed.user-password=test-user-pw-1234",
        "app.jwt.secret=test-secret-test-secret-test-secret-123456"})
class AgentSeedTest {
    @Autowired AiAgentRepository agents;
    @Autowired DataSeeder seeder;

    @Test
    void oldAgentNameIsRenamedAndUnusedAgentsAreDisabled() throws Exception {
        AiAgent ari = agents.findByCode("ari").orElseThrow();
        ari.rename("아리 (AI PM)");
        agents.save(ari);
        agents.save(new AiAgent("dev-bot", "데브 (AI 개발자)", WsEnums.AgentRole.DEV, Set.of(WsEnums.Environment.DEV)));

        seeder.run();

        assertEquals("아리", agents.findByCode("ari").orElseThrow().getName());
        assertTrue(agents.findByCode("ari").orElseThrow().isEnabled());
        assertFalse(agents.findByCode("dev-bot").orElseThrow().isEnabled());
        assertTrue(agents.findByCode("qa-bot").isEmpty() || !agents.findByCode("qa-bot").get().isEnabled());
    }
}
