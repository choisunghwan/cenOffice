package com.cenoffice.pm;

import com.cenoffice.pm.PmTypes.PmInfo;
import com.cenoffice.pm.PmTypes.PmState;
import com.cenoffice.workspace.AiTask;
import com.cenoffice.workspace.WorkspaceService;
import com.cenoffice.workspace.WsDtos.AiTaskView;
import com.cenoffice.workspace.WsDtos.ExecutionView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pm")
public class PmController {
    private static final String WRITE = "hasAnyRole('ADMIN','USER')";

    public record IntakeRequest(@NotNull Long projectId, @NotBlank @Size(max = 4000) String text) {}
    public record AnswerRequest(@NotBlank @Size(max = 2000) String answers) {}
    public record PmDetail(AiTaskView task, List<ExecutionView> executions, PmState state) {}

    private final PmService pm;
    private final WorkspaceService workspace;

    public PmController(PmService pm, WorkspaceService workspace) {
        this.pm = pm;
        this.workspace = workspace;
    }

    @GetMapping("/info")
    public PmInfo info() { return pm.info(); }

    @PostMapping("/intake") @ResponseStatus(HttpStatus.ACCEPTED) @PreAuthorize(WRITE)
    public AiTaskView intake(@Valid @RequestBody IntakeRequest r, Authentication a) { return pm.intake(r.projectId(), r.text(), a.getName()); }

    @GetMapping("/tasks/{id}")
    public PmDetail detail(@PathVariable Long id) {
        AiTask t = pm.pmTask(id);
        return new PmDetail(workspace.aiTaskView(id), workspace.executions(id), pm.state(t));
    }

    @PostMapping("/tasks/{id}/answers") @ResponseStatus(HttpStatus.ACCEPTED) @PreAuthorize(WRITE)
    public void answer(@PathVariable Long id, @Valid @RequestBody AnswerRequest r, Authentication a) {
        pm.answer(id, r.answers(), a.getName(), a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("ROLE_ADMIN")));
    }
}
