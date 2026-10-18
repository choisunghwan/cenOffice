package com.cenoffice.chat;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "chat_messages", indexes = @Index(columnList = "roomId,id"))
public class ChatMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long roomId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8)
    private ChatEntities.SenderType senderType;
    /** 사용자 계정(username) 또는 AI 에이전트 코드. 서버가 인증 정보로 채운다 — 클라이언트 값 사용 안 함. */
    @Column(nullable = false, length = 64)
    private String sender;
    @Column(nullable = false, length = 100)
    private String senderName;
    /** 원문 그대로 저장한다(HTML 이스케이프는 출력 시 클라이언트가 텍스트로만 렌더링). */
    @Column(nullable = false, length = 1000)
    private String content;
    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected ChatMessage() {}

    public ChatMessage(Long roomId, ChatEntities.SenderType senderType, String sender, String senderName, String content) {
        this.roomId = roomId;
        this.senderType = senderType;
        this.sender = sender;
        this.senderName = senderName;
        this.content = content;
    }

    public Long getId() { return id; }
    public Long getRoomId() { return roomId; }
    public ChatEntities.SenderType getSenderType() { return senderType; }
    public String getSender() { return sender; }
    public String getSenderName() { return senderName; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
}
