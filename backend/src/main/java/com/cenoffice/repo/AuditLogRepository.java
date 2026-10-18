package com.cenoffice.repo;

import com.cenoffice.domain.AuditLog;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findAllByOrderByCreatedAtDesc(Pageable page);

    List<AuditLog> findByTargetOrderByCreatedAtDesc(String target, Pageable page);

    @Query("select a from AuditLog a where a.target like 'project:%' or a.target like 'requirement:%' or a.target like 'task:%' or a.target like 'aitask:%' or a.target like 'approval:%' order by a.createdAt desc")
    List<AuditLog> findRecentWorkspace(Pageable page);
}
