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
@RequestMapping("/api/meetings/{id}/transcript")
@PreAuthorize("hasAnyRole('USER','ADMIN')")
public class TranscriptController {
    private final TranscriptService transcript;
    public TranscriptController(TranscriptService transcript) { this.transcript = transcript; }

    public record Consent(@Size(max = 40) String engine) {}
    public record Add(@NotBlank @Size(max = 600) String content, @Size(max = 40) String engine) {}

    @GetMapping("/status") public TranscriptService.Status status(@PathVariable String id, Authentication a) { return transcript.status(id, a.getName()); }
    @GetMapping public List<TranscriptService.Segment> list(@PathVariable String id, @RequestParam(defaultValue = "0") long after, Authentication a) { return transcript.list(id, a.getName(), after); }
    @PostMapping("/consent") @ResponseStatus(HttpStatus.NO_CONTENT) public void consent(@PathVariable String id, @Valid @RequestBody Consent r, Authentication a) { transcript.consent(id, a.getName(), r.engine()); }
    @DeleteMapping("/consent") @ResponseStatus(HttpStatus.NO_CONTENT) public void withdraw(@PathVariable String id, Authentication a) { transcript.withdraw(id, a.getName()); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public TranscriptService.Segment add(@PathVariable String id, @Valid @RequestBody Add r, Authentication a) { return transcript.add(id, a.getName(), r.content(), r.engine()); }
}
