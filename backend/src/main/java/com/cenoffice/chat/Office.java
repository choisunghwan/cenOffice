package com.cenoffice.chat;

import jakarta.persistence.*;

/** 사무실. 현재는 단일 사무실(본사)만 사용하며, 채팅방/접속 범위의 기준이 된다. */
@Entity
@Table(name = "offices")
public class Office {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100)
    private String name;

    protected Office() {}

    public Office(String name) { this.name = name; }

    public Long getId() { return id; }
    public String getName() { return name; }
}
