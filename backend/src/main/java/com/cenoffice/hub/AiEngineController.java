package com.cenoffice.hub;

import com.cenoffice.hub.runtime.RuntimeRegistry;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/hub/ai-engine")
public class AiEngineController {
    public record AiEngineInfo(boolean available, String provider, boolean external, String notice) {}
    private final RuntimeRegistry runtime;
    public AiEngineController(RuntimeRegistry runtime) { this.runtime = runtime; }

    /** AI 모델 실행 사용 가능 여부와 데이터 전송 안내 (화면에서 선택지와 경고 표시에 사용) */
    @GetMapping
    public AiEngineInfo info() {
        if (!runtime.aiAvailable()) return new AiEngineInfo(false, "", false, "AI 모델 실행이 꺼져 있습니다");
        var llm = runtime.llm();
        boolean external = llm.isReal();
        return new AiEngineInfo(true, llm.name(), external,
            external ? "입력 내용이 외부 AI 모델 서비스(" + llm.name() + ")로 전송됩니다. 개인정보·비밀번호·고객 기밀은 입력하지 마세요."
                     : "시뮬레이션 모델입니다. 실제 AI 결과가 아니며 사용 통계에 집계되지 않습니다.");
    }
}
