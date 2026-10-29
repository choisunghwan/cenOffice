package com.cenoffice.office.chat;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "chat_rooms", uniqueConstraints = @UniqueConstraint(columnNames = "roomKey"))
public class ChatRoom {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private ChatEntities.RoomType type;
    @Column(nullable = false)
    private Long officeId;
    /** 방 식별 키: office:{id} / dm:{a}|{b}(정렬) / ai:{agentCode}|{user} — 중복 생성을 막는다. */
    @Column(nullable = false, length = 200)
    private String roomKey;
    @Column(length = 100)
    private String name;
    /** AI 방일 때 상대 에이전트 코드 (AiAgent.code) */
    @Column(length = 32)
    private String agentCode;
    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected ChatRoom() {}

    public ChatRoom(ChatEntities.RoomType type, Long officeId, String roomKey, String name, String agentCode) {
        this.type = type;
        this.officeId = officeId;
        this.roomKey = roomKey;
        this.name = name;
        this.agentCode = agentCode;
    }

    public Long getId() { return id; }
    public ChatEntities.RoomType getType() { return type; }
    public Long getOfficeId() { return officeId; }
    public String getRoomKey() { return roomKey; }
    public String getName() { return name; }
    public String getAgentCode() { return agentCode; }
}
