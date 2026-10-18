package com.cenoffice.chat;

import jakarta.persistence.*;

@Entity
@Table(name = "chat_room_members", uniqueConstraints = @UniqueConstraint(columnNames = {"roomId", "username"}))
public class ChatRoomMember {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long roomId;
    @Column(nullable = false, length = 64)
    private String username;

    protected ChatRoomMember() {}

    public ChatRoomMember(Long roomId, String username) {
        this.roomId = roomId;
        this.username = username;
    }

    public Long getId() { return id; }
    public Long getRoomId() { return roomId; }
    public String getUsername() { return username; }
}
