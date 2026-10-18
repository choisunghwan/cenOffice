package com.cenoffice.hub;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HubSkillVersionRepository extends JpaRepository<HubSkillVersion, Long> {
    java.util.List<HubSkillVersion> findBySkillIdOrderByIdDesc(Long skillId);
    java.util.Optional<HubSkillVersion> findBySkillIdAndVersion(Long skillId, String version);
    java.util.List<HubSkillVersion> findByStatusOrderByCreatedAtAsc(String status);
}
