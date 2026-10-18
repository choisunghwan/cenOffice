package com.cenoffice.api;

import com.cenoffice.domain.AuditLog;
import com.cenoffice.monitor.ServerMonitor;
import com.cenoffice.repo.AuditLogRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MiscController {
    private final ServerMonitor monitor;
    private final AuditLogRepository audits;

    public MiscController(ServerMonitor monitor, AuditLogRepository audits) {
        this.monitor = monitor;
        this.audits = audits;
    }

    @GetMapping("/servers")
    public ServerMonitor.Snapshot servers() { return monitor.snapshot(); }

    @GetMapping("/audit-logs")
    @PreAuthorize("hasRole('ADMIN')")
    public List<AuditLog> audit(@RequestParam(defaultValue = "50") int limit) {
        return audits.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.min(Math.max(limit, 1), 200)));
    }
}
