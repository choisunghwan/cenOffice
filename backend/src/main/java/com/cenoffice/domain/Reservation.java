package com.cenoffice.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "reservations", indexes = @Index(columnList = "roomId,startAt"))
public class Reservation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long roomId;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(nullable = false, length = 64)
    private String reservedBy;
    @Column(nullable = false)
    private Instant startAt;
    @Column(nullable = false)
    private Instant endAt;

    protected Reservation() {}

    public Reservation(Long roomId, String title, String reservedBy, Instant startAt, Instant endAt) {
        this.roomId = roomId;
        this.title = title;
        this.reservedBy = reservedBy;
        this.startAt = startAt;
        this.endAt = endAt;
    }

    public Long getId() { return id; }
    public Long getRoomId() { return roomId; }
    public String getTitle() { return title; }
    public String getReservedBy() { return reservedBy; }
    public Instant getStartAt() { return startAt; }
    public Instant getEndAt() { return endAt; }
}
