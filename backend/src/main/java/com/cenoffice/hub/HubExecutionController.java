package com.cenoffice.hub;

import com.cenoffice.hub.HubDtos.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** AI HUB — 실행 / 런타임 상태 / 통계 API */
@RestController
@RequestMapping("/api/hub")
public class HubExecutionController {
    private static final String WRITE = "hasAnyRole('ADMIN','USER')";

    private final ExecutionService executions;
    private final AnalyticsService analytics;
    private final SkillService skills;

    public HubExecutionController(ExecutionService executions, AnalyticsService analytics, SkillService skills) {
        this.executions = executions;
        this.analytics = analytics;
        this.skills = skills;
    }

    /** 런타임 연동 상태: RUNTIME_NOT_CONFIGURED(기본) / RUNTIME_AVAILABLE */
    @GetMapping("/runtime")
    public RuntimeInfo runtime() { return skills.runtimeInfo(); }

    @PostMapping("/executions") @ResponseStatus(HttpStatus.ACCEPTED) @PreAuthorize(WRITE)
    public ExecutionView execute(@RequestBody ExecuteRequest r, Authentication a) { return executions.request(a.getName(), HubSkillController.admin(a), r); }

    @GetMapping("/executions/{id}")
    public ExecutionView get(@PathVariable String id, Authentication a) { return executions.get(id, a.getName(), HubSkillController.admin(a)); }

    @PostMapping("/executions/{id}/cancel") @PreAuthorize(WRITE)
    public ExecutionView cancel(@PathVariable String id, Authentication a) { return executions.cancel(id, a.getName(), HubSkillController.admin(a)); }

    @GetMapping("/executions")
    public PageView<ExecutionView> list(@RequestParam(required = false) Long skillId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                                        Authentication a) {
        return executions.list(a.getName(), HubSkillController.admin(a), skillId, page, size);
    }

    @GetMapping("/analytics/overview")
    public Overview overview(Authentication a) { return analytics.overview(a.getName(), HubSkillController.admin(a)); }

    @GetMapping("/analytics/popular")
    public List<SkillSummary> popular(@RequestParam(defaultValue = "5") int limit, Authentication a) { return analytics.popular(a.getName(), HubSkillController.admin(a), limit); }

    @GetMapping("/analytics/skills/{id}")
    public SkillStats skillStats(@PathVariable Long id, Authentication a) { return analytics.skillStats(id, a.getName(), HubSkillController.admin(a)); }

    @GetMapping("/analytics/creators/me")
    public CreatorStats myStats(Authentication a) { return analytics.creatorStats(a.getName(), a.getName(), HubSkillController.admin(a)); }

    @GetMapping("/analytics/creators/{username}")
    public CreatorStats creatorStats(@PathVariable String username, Authentication a) { return analytics.creatorStats(username, a.getName(), HubSkillController.admin(a)); }
}
