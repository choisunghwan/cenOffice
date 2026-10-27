package com.cenoffice.meeting;

import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 회의록(초안 생성 → 수정 → 확정)과 액션 아이템. 회의 참석자만 접근한다. */
@RestController
@RequestMapping("/api")
@PreAuthorize("hasAnyRole('USER','ADMIN')")
public class MinutesController {
    public record GenerateRequest(@Size(max = 4000) String notes) {}
    public record DoneRequest(boolean done) {}
    private final MinutesService minutes;
    public MinutesController(MinutesService minutes) { this.minutes = minutes; }

    @GetMapping("/meetings/{id}/minutes") public MinutesService.Minutes get(@PathVariable String id, Authentication a) { return minutes.get(id, a.getName()); }

    @PostMapping("/meetings/{id}/minutes/generate")
    public MinutesService.Minutes generate(@PathVariable String id, @RequestBody(required = false) GenerateRequest r, Authentication a) {
        return minutes.generate(id, a.getName(), r == null ? null : r.notes());
    }

    @PutMapping("/meetings/{id}/minutes")
    public MinutesService.Minutes update(@PathVariable String id, @RequestBody MinutesService.Draft body, Authentication a) { return minutes.update(id, a.getName(), body); }

    @PostMapping("/meetings/{id}/minutes/confirm")
    public MinutesService.Minutes confirm(@PathVariable String id, Authentication a) { return minutes.confirm(id, a.getName()); }

    @GetMapping("/action-items/mine") public List<MinutesService.MyItem> mine(Authentication a) { return minutes.myItems(a.getName()); }

    @PostMapping("/action-items/{id}/done") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void done(@PathVariable long id, @RequestBody DoneRequest r, Authentication a) { minutes.setDone(id, r.done(), a.getName()); }
}
