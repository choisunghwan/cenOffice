package com.cenoffice.hub.domain;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HubAgentRepository extends JpaRepository<HubAgent, Long> {
    java.util.Optional<HubAgent> findByName(String name);
    java.util.List<HubAgent> findByOwnerOrderByUpdatedAtDesc(String owner);
}
