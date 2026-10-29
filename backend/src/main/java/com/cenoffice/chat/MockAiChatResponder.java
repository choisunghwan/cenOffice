package com.cenoffice.chat;

import com.cenoffice.monitor.ServerMonitor;
import com.cenoffice.llm.LlmProvider;
import com.cenoffice.workspace.AiAgent;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** LLM 이 연결되지 않은 환경용 Mock 응답. 어떤 작업도 실제로 수행하지 않으며, 그렇게 안내한다. */
@Component
public class MockAiChatResponder implements AiChatResponder {
    private static final String SYSTEM = "당신은 CEN OFFICE 의 AI PM '아리'입니다. 대화만 할 수 있으며 어떤 작업도 실행하거나 시스템을 변경할 수 없습니다. "
            + "작업을 했다고 말하지 마세요. 요구사항 분석 요청은 WORKSPACE 의 AI Agents 화면에서 접수하도록 안내하세요. 300자 이내 한국어로 답하세요.";

    private final ServerMonitor monitor;
    private final LlmProvider llm;

    public MockAiChatResponder(ServerMonitor monitor, LlmProvider llm) {
        this.monitor = monitor;
        this.llm = llm;
    }

    @Override
    public String reply(AiAgent agent, String text) {
        if (llm.isReal()) {
            try { return llm.chat(SYSTEM, text); } catch (Exception e) { return "(AI) 지금은 응답을 만들 수 없습니다. 잠시 후 다시 시도해 주세요."; }
        }
        String t = text.toLowerCase();
        if (t.contains("서버") || t.contains("server")) {
            var snap = monitor.snapshot();
            String lines = snap.servers().stream().map(s -> s.name() + " " + s.status() + " (CPU " + s.cpu() + "%, MEM " + s.memory() + "%)").collect(Collectors.joining("\n"));
            return "서버 상태를 조회했어요 (Mock 모니터 데이터):\n" + lines;
        }
        if (t.contains("안녕") || t.contains("hello") || t.contains("hi")) {
            return "안녕하세요, " + agent.getName() + "입니다. 지금은 Mock 모드라 간단한 안내와 서버 상태 조회만 가능해요.";
        }
        return "(Mock AI) 메시지를 받았습니다. 아직 LLM 이 연결되지 않아서 요청하신 작업을 실제로 수행하지는 않았어요. "
                + "\"서버 상태\"를 물어보시면 조회 결과를 알려드릴 수 있고, 요구사항 분석은 WORKSPACE > AI Agents 에서 요청하실 수 있어요.";
    }
}
