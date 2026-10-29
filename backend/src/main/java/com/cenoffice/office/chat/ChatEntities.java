package com.cenoffice.office.chat;

/** 채팅 도메인 공통 타입. 엔티티는 각각 별도 파일입니다. */
public final class ChatEntities {
    private ChatEntities() {}

    public enum RoomType { OFFICE, DIRECT, AI }

    public enum SenderType { USER, AI }
}
