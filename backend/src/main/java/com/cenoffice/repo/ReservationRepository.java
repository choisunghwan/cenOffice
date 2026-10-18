package com.cenoffice.repo;

import com.cenoffice.domain.Reservation;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    @Query("select r from Reservation r where r.roomId = :roomId and r.startAt < :end and r.endAt > :start")
    List<Reservation> findOverlapping(@Param("roomId") Long roomId, @Param("start") Instant start, @Param("end") Instant end);

    @Query("select r from Reservation r where r.endAt > :from and r.startAt < :to order by r.startAt")
    List<Reservation> findBetween(@Param("from") Instant from, @Param("to") Instant to);
}
