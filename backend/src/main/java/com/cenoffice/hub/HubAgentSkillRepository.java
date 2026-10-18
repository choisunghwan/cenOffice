package com.cenoffice.hub;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HubAgentSkillRepository extends JpaRepository<HubAgentSkill, HubAgentSkill.Pk> {
    java.util.List<HubAgentSkill> findByAgentIdOrderBySortOrderAsc(Long agentId);
    java.util.List<HubAgentSkill> findBySkillId(Long skillId);
    void deleteByAgentId(Long agentId);
}
