package com.cenoffice.hub;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HubSkillRepository extends JpaRepository<HubSkill, Long> {
    java.util.Optional<HubSkill> findByName(String name);
    long countByStatusAndSample(String status, boolean sample);
    java.util.List<HubSkill> findByOwnerOrderByUpdatedAtDesc(String owner);
}
