package com.cenoffice.hub;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HubSkillTagRepository extends JpaRepository<HubSkillTag, HubSkillTag.Pk> {
    java.util.List<HubSkillTag> findBySkillId(Long skillId);
    void deleteBySkillId(Long skillId);
}
