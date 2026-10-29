package com.cenoffice.hub.domain;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HubSkillReviewRepository extends JpaRepository<HubSkillReview, Long> {
    java.util.List<HubSkillReview> findBySkillIdOrderByUpdatedAtDesc(Long skillId);
    java.util.Optional<HubSkillReview> findBySkillIdAndReviewer(Long skillId, String reviewer);
}
