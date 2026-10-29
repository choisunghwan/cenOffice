package com.cenoffice.common.api;

import com.cenoffice.common.domain.AuditLog;
import com.cenoffice.common.repo.AuditLogRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    private final AuditLogRepository repo;

    public AuditService(AuditLogRepository repo) { this.repo = repo; }

    /** 호출한 트랜잭션이 예외로 롤백되어도 감사 기록은 남도록 별도 트랜잭션으로 저장한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String actor, String action, String target, String detail) {
        repo.save(new AuditLog(actor, action, target, detail));
    }

    /** 최근 감사 기록. 한 번에 최대 200건. */
    public List<AuditLog> recent(int limit) {
        return repo.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.min(Math.max(limit, 1), 200)));
    }
}
