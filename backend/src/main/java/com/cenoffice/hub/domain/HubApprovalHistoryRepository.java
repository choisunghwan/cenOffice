package com.cenoffice.hub.domain;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HubApprovalHistoryRepository extends JpaRepository<HubApprovalHistory, Long> {
    java.util.List<HubApprovalHistory> findByTargetTypeAndTargetIdOrderByIdDesc(String targetType, Long targetId);
}
