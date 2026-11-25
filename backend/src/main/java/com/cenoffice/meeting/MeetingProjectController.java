package com.cenoffice.meeting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasAnyRole('USER','ADMIN')")
public class MeetingProjectController {
    public record Save(@NotBlank @Size(max = 120) String name, @Size(max = 100) List<@NotBlank @Size(max = 64) String> members,
                       Long skillId, Long versionId) {}
    public record Assign(@Size(max = 36) String projectId) {}
    private final MeetingProjectService projects;

    public MeetingProjectController(MeetingProjectService projects) { this.projects = projects; }

    @GetMapping("/meeting-projects")
    public List<MeetingProjectService.Project> list(Authentication a) { return projects.list(a.getName()); }

    @PostMapping("/meeting-projects")
    @ResponseStatus(HttpStatus.CREATED)
    public MeetingProjectService.Project create(Authentication a, @Valid @RequestBody Save r) {
        return projects.create(a.getName(), r.name(), r.members(), r.skillId(), r.versionId());
    }

    @PutMapping("/meeting-projects/{id}")
    public MeetingProjectService.Project update(Authentication a, @PathVariable String id, @Valid @RequestBody Save r) {
        return projects.update(id, a.getName(), r.name(), r.members(), r.skillId(), r.versionId());
    }

    @GetMapping("/meetings/{id}/minutes/project")
    public MeetingProjectService.Settings settings(Authentication a, @PathVariable String id) {
        return projects.settings(id, a.getName());
    }

    @PutMapping("/meetings/{id}/minutes/project")
    public MeetingProjectService.Settings assign(Authentication a, @PathVariable String id, @Valid @RequestBody Assign r) {
        return projects.assign(id, a.getName(), r.projectId());
    }
}
