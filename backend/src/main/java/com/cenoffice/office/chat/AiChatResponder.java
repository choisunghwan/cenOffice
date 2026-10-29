package com.cenoffice.office.chat;

import com.cenoffice.office.agent.AiAgent;

/**
 * AI 직원의 채팅 응답 생성기. 실제 LLM 연동은 이 인터페이스의 다른 구현체로 교체한다(Phase 3B).
 * 응답 텍스트만으로 DB 변경이나 도구 실행을 하지 않는다 — 채팅 응답은 메시지로만 저장된다.
 */
public interface AiChatResponder {
    String reply(AiAgent agent, String userText);
}
