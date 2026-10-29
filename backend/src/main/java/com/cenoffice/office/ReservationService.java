package com.cenoffice.office;

import com.cenoffice.common.api.ApiException;
import com.cenoffice.common.api.AuditService;

import com.cenoffice.office.device.DeviceTypes.OfficeEvent;
import com.cenoffice.common.domain.MeetingRoom;
import com.cenoffice.common.domain.Reservation;
import com.cenoffice.common.repo.MeetingRoomRepository;
import com.cenoffice.common.repo.ReservationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 회의실 목록과 예약. 겹침 방지는 SERIALIZABLE 트랜잭션으로 지킨다. */
@Service
public class ReservationService {
    public record ReservationView(Long id, Long roomId, String title, String reservedBy, Instant startAt, Instant endAt) {}

    private static final Duration MAX_LENGTH = Duration.ofHours(8);

    private final MeetingRoomRepository rooms;
    private final ReservationRepository reservations;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public ReservationService(MeetingRoomRepository rooms, ReservationRepository reservations, AuditService audit, ApplicationEventPublisher events) {
        this.rooms = rooms;
        this.reservations = reservations;
        this.audit = audit;
        this.events = events;
    }

    public List<MeetingRoom> rooms() { return rooms.findAll(); }

    /** 기간 내 예약 조회. 기본: 지금부터 7일. */
    public List<ReservationView> list(Instant from, Instant to) {
        Instant f = from != null ? from : Instant.now().minus(Duration.ofHours(12));
        Instant t = to != null ? to : f.plus(Duration.ofDays(7));
        return reservations.findBetween(f, t).stream().map(ReservationService::view).toList();
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public ReservationView create(Long roomId, String title, Instant startAt, Instant endAt, String actor) {
        if (!rooms.existsById(roomId)) throw new ApiException(HttpStatus.NOT_FOUND, "회의실을 찾을 수 없습니다");
        if (!endAt.isAfter(startAt)) throw new ApiException(HttpStatus.BAD_REQUEST, "종료 시간은 시작 시간 이후여야 합니다");
        if (Duration.between(startAt, endAt).compareTo(MAX_LENGTH) > 0) throw new ApiException(HttpStatus.BAD_REQUEST, "한 번에 최대 8시간까지 예약할 수 있습니다");
        if (endAt.isBefore(Instant.now())) throw new ApiException(HttpStatus.BAD_REQUEST, "이미 지난 시간은 예약할 수 없습니다");
        if (!reservations.findOverlapping(roomId, startAt, endAt).isEmpty())
            throw new ApiException(HttpStatus.CONFLICT, "해당 시간에 이미 예약이 있습니다");
        Reservation saved = reservations.save(new Reservation(roomId, title.trim(), actor, startAt, endAt));
        audit.log(actor, "RESERVATION_CREATED", "room:" + roomId, title + " " + startAt + "~" + endAt);
        events.publishEvent(new OfficeEvent("reservation.changed", view(saved)));
        return view(saved);
    }

    @Transactional
    public void cancel(Long id, String actor, boolean admin) {
        Reservation r = reservations.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다"));
        if (!admin && !r.getReservedBy().equals(actor)) throw new ApiException(HttpStatus.FORBIDDEN, "본인의 예약만 취소할 수 있습니다");
        reservations.delete(r);
        audit.log(actor, "RESERVATION_CANCELLED", "room:" + r.getRoomId(), r.getTitle());
        events.publishEvent(new OfficeEvent("reservation.changed", view(r)));
    }

    private static ReservationView view(Reservation r) {
        return new ReservationView(r.getId(), r.getRoomId(), r.getTitle(), r.getReservedBy(), r.getStartAt(), r.getEndAt());
    }
}
