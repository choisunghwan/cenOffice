package com.cenoffice.api;

import com.cenoffice.domain.AuditLog;
import com.cenoffice.monitor.ServerMonitor;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MiscController {
    private final ServerMonitor monitor;
    private final AuditService audits;

    public MiscController(ServerMonitor monitor, AuditService audits) {
        this.monitor = monitor;
        this.audits = audits;
    }

    @GetMapping("/servers")
    public ServerMonitor.Snapshot servers() { return monitor.snapshot(); }

    @GetMapping("/audit-logs")
    @PreAuthorize("hasRole('ADMIN')")
    public List<AuditLog> audit(@RequestParam(defaultValue = "50") int limit) {
        return audits.recent(limit);
    }
}
