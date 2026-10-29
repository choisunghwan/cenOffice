package com.cenoffice.common.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "meeting_rooms")
public class MeetingRoom {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100)
    private String name;
    private int capacity;

    protected MeetingRoom() {}

    public MeetingRoom(String name, int capacity) {
        this.name = name;
        this.capacity = capacity;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public int getCapacity() { return capacity; }
}
