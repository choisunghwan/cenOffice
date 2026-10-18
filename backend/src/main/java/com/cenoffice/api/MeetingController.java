package com.cenoffice.api;

import com.cenoffice.device.DeviceTypes.OfficeEvent;
import com.cenoffice.domain.MeetingRoom;
import com.cenoffice.domain.Reservation;
import com.cenoffice.repo.MeetingRoomRepository;
import com.cenoffice.repo.ReservationRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class MeetingController {
    public record ReservationRequest(@NotNull Long roomId, @NotBlank @Size(max = 200) String title,
                                     @NotNull Instant startAt, @NotNull Instant endAt) {}

    public record ReservationView(Long id, Long roomId, String title, String reservedBy, Instant startAt, Instant endAt) {}

    private static final Duration MAX_LENGTH = Duration.ofHours(8);

    private final MeetingRoomRepository rooms;
    private final ReservationRepository reservations;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public MeetingController(MeetingRoomRepository rooms, ReservationRepository reservations, AuditService audit, ApplicationEventPublisher events) {
        this.rooms = rooms;
        this.reservations = reservations;
        this.audit = audit;
        this.events = events;
    }

    @GetMapping("/meeting-rooms")
    public List<MeetingRoom> rooms() { return rooms.findAll(); }

    /** 기간 내 예약 조회. 기본: 지금부터 7일. */
    @GetMapping("/reservations")
    public List<ReservationView> list(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        Instant f = from != null ? from : Instant.now().minus(Duration.ofHours(12));
        Instant t = to != null ? to : f.plus(Duration.ofDays(7));
        return reservations.findBetween(f, t).stream().map(MeetingController::view).toList();
    }

    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.SERIALIZABLE)
    public ReservationView create(@Valid @RequestBody ReservationRequest r, Principal who) {
        if (!rooms.existsById(r.roomId())) throw new ApiException(HttpStatus.NOT_FOUND, "회의실을 찾을 수 없습니다");
        if (!r.endAt().isAfter(r.startAt())) throw new ApiException(HttpStatus.BAD_REQUEST, "종료 시간은 시작 시간 이후여야 합니다");
        if (Duration.between(r.startAt(), r.endAt()).compareTo(MAX_LENGTH) > 0) throw new ApiException(HttpStatus.BAD_REQUEST, "한 번에 최대 8시간까지 예약할 수 있습니다");
        if (r.endAt().isBefore(Instant.now())) throw new ApiException(HttpStatus.BAD_REQUEST, "이미 지난 시간은 예약할 수 없습니다");
        if (!reservations.findOverlapping(r.roomId(), r.startAt(), r.endAt()).isEmpty())
            throw new ApiException(HttpStatus.CONFLICT, "해당 시간에 이미 예약이 있습니다");
        Reservation saved = reservations.save(new Reservation(r.roomId(), r.title().trim(), who.getName(), r.startAt(), r.endAt()));
        audit.log(who.getName(), "RESERVATION_CREATED", "room:" + r.roomId(), r.title() + " " + r.startAt() + "~" + r.endAt());
        events.publishEvent(new OfficeEvent("reservation.changed", view(saved)));
        return view(saved);
    }

    @DeleteMapping("/reservations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Transactional
    public void cancel(@PathVariable Long id, Authentication auth) {
        Reservation r = reservations.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다"));
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!admin && !r.getReservedBy().equals(auth.getName())) throw new ApiException(HttpStatus.FORBIDDEN, "본인의 예약만 취소할 수 있습니다");
        reservations.delete(r);
        audit.log(auth.getName(), "RESERVATION_CANCELLED", "room:" + r.getRoomId(), r.getTitle());
        events.publishEvent(new OfficeEvent("reservation.changed", view(r)));
    }

    private static ReservationView view(Reservation r) {
        return new ReservationView(r.getId(), r.getRoomId(), r.getTitle(), r.getReservedBy(), r.getStartAt(), r.getEndAt());
    }
}
