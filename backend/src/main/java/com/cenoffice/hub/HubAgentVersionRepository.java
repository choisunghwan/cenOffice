package com.cenoffice.hub;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HubAgentVersionRepository extends JpaRepository<HubAgentVersion, Long> {
    java.util.List<HubAgentVersion> findByAgentIdOrderByVersionDesc(Long agentId);
}
