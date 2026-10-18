package com.cenoffice.workspace;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, Long> {
    List<ApprovalRequest> findByStatusOrderByCreatedAtDesc(WsEnums.ApprovalStatus status);
    List<ApprovalRequest> findTop50ByOrderByCreatedAtDesc();
    long countByStatus(WsEnums.ApprovalStatus status);
    void deleteByAiTaskId(Long aiTaskId);
}
