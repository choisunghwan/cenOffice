package com.cenoffice.office.ws;

import java.util.Set;

/** WebSocket 으로 내보내는 이벤트 타입. */
public final class WsEvents {
    private WsEvents() {}

    /** recipients 가 null 이면 접속한 전원, 아니면 해당 사용자(username)에게만 전달한다. */
    public record UserEvent(Set<String> recipients, String type, Object payload) {
        public static UserEvent all(String type, Object payload) { return new UserEvent(null, type, payload); }

        public static UserEvent to(Set<String> users, String type, Object payload) { return new UserEvent(users, type, payload); }
    }

    /** 서버가 특정 세션을 닫도록 요청 (예: 하트비트 끊김). */
    public record CloseSession(String sessionId, int code, String reason) {}
}
