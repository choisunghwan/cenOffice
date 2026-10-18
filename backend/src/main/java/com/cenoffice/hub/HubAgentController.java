package com.cenoffice.hub;

import com.cenoffice.hub.HubDtos.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** AI HUB — Agent API (구성/조회/승인). 실행은 /api/hub/executions. */
@RestController
@RequestMapping("/api/hub/agents")
public class HubAgentController {
    private static final String WRITE = "hasAnyRole('ADMIN','USER')";
    private static final String ADMIN = "hasRole('ADMIN')";

    private final AgentService agents;

    public HubAgentController(AgentService agents) { this.agents = agents; }

    @GetMapping
    public PageView<AgentSummary> list(@RequestParam(required = false) String q, @RequestParam(required = false) String owner, @RequestParam(required = false) String status,
                                       @RequestParam(defaultValue = "false") boolean mine, @RequestParam(defaultValue = "recent") String sort,
                                       @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "12") int size, Authentication a) {
        return agents.search(new AgentService.SearchQuery(q, owner, status, mine, sort, page, size), a.getName(), HubSkillController.admin(a));
    }

    @GetMapping("/{id}")
    public AgentDetail get(@PathVariable Long id, Authentication a) { return agents.detail(id, a.getName(), HubSkillController.admin(a)); }

    @PostMapping @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(WRITE)
    public AgentDetail create(@RequestBody AgentRequest r, Authentication a) { return agents.create(a.getName(), r); }

    @PutMapping("/{id}") @PreAuthorize(WRITE)
    public AgentDetail update(@PathVariable Long id, @RequestBody AgentRequest r, Authentication a) { return agents.update(id, a.getName(), HubSkillController.admin(a), r); }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize(WRITE)
    public void archive(@PathVariable Long id, Authentication a) { agents.archive(id, a.getName(), HubSkillController.admin(a)); }

    @PostMapping("/{id}/skills/{skillId}") @PreAuthorize(WRITE)
    public AgentDetail link(@PathVariable Long id, @PathVariable Long skillId, Authentication a) { return agents.link(id, skillId, a.getName(), HubSkillController.admin(a)); }

    @DeleteMapping("/{id}/skills/{skillId}") @PreAuthorize(WRITE)
    public AgentDetail unlink(@PathVariable Long id, @PathVariable Long skillId, Authentication a) { return agents.unlink(id, skillId, a.getName(), HubSkillController.admin(a)); }

    @PostMapping("/{id}/submit") @PreAuthorize(WRITE)
    public AgentDetail submit(@PathVariable Long id, Authentication a) { return agents.submit(id, a.getName(), HubSkillController.admin(a)); }

    @PostMapping("/{id}/approve") @PreAuthorize(ADMIN)
    public AgentDetail approve(@PathVariable Long id, @RequestBody(required = false) CommentRequest c, Authentication a) { return agents.approve(id, a.getName(), c == null ? null : c.comment()); }

    @PostMapping("/{id}/reject") @PreAuthorize(ADMIN)
    public AgentDetail reject(@PathVariable Long id, @RequestBody(required = false) CommentRequest c, Authentication a) { return agents.reject(id, a.getName(), c == null ? null : c.comment()); }

    @GetMapping("/{id}/history")
    public List<HistoryView> history(@PathVariable Long id, Authentication a) { return agents.history(id, a.getName(), HubSkillController.admin(a)); }

    @GetMapping("/review-queue") @PreAuthorize(ADMIN)
    public List<AgentSummary> reviewQueue() { return agents.reviewQueue(); }
}
