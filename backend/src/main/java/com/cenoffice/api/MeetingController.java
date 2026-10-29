package com.cenoffice.api;

import com.cenoffice.domain.MeetingRoom;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class MeetingController {
    public record ReservationRequest(@NotNull Long roomId, @NotBlank @Size(max = 200) String title,
                                     @NotNull Instant startAt, @NotNull Instant endAt) {}

    private final ReservationService service;

    public MeetingController(ReservationService service) { this.service = service; }

    @GetMapping("/meeting-rooms")
    public List<MeetingRoom> rooms() { return service.rooms(); }

    @GetMapping("/reservations")
    public List<ReservationService.ReservationView> list(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        return service.list(from, to);
    }

    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    public ReservationService.ReservationView create(@Valid @RequestBody ReservationRequest r, Principal who) {
        return service.create(r.roomId(), r.title(), r.startAt(), r.endAt(), who.getName());
    }

    @DeleteMapping("/reservations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    public void cancel(@PathVariable Long id, Authentication auth) {
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        service.cancel(id, auth.getName(), admin);
    }
}
