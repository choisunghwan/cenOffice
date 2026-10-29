package com.cenoffice.hub.domain;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HubExecutionRepository extends JpaRepository<HubExecution, String> {
    java.util.List<HubExecution> findByRequesterOrderByRequestedAtDesc(String requester, org.springframework.data.domain.Pageable p);
    java.util.List<HubExecution> findBySkillIdOrderByRequestedAtDesc(Long skillId, org.springframework.data.domain.Pageable p);
    boolean existsByRequesterAndSkillIdAndAgentIdIsNullAndInputHashAndRequestedAtAfter(String requester, Long skillId, String inputHash, Instant after);
    boolean existsByRequesterAndAgentIdAndInputHashAndRequestedAtAfter(String requester, Long agentId, String inputHash, Instant after);
    long countByRequesterAndRequestedAtAfter(String requester, Instant after);
    java.util.List<HubExecution> findByStatusIn(java.util.Collection<String> statuses);
}
