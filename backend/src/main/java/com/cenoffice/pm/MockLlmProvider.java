package com.cenoffice.pm;

import com.cenoffice.pm.PmTypes.PlanRequirement;
import com.cenoffice.pm.PmTypes.PlanTask;
import com.cenoffice.pm.PmTypes.PmRequest;
import com.cenoffice.pm.PmTypes.PmResult;
import java.util.ArrayList;
import java.util.List;

/**
 * 외부 호출이 없는 규칙 기반 Mock. 실제 LLM 이 아니므로 "이해"가 아니라 문장 분리·템플릿으로 결과를 만든다.
 * 흐름(질문 → 명세 → 작업 목록 → 승인)을 end-to-end 로 검증하기 위한 용도다.
 */
public class MockLlmProvider implements LlmProvider {
    @Override public String name() { return "mock"; }

    @Override public boolean isReal() { return false; }

    @Override public String model() { return "rule-based"; }

    @Override
    public PmResult planRequirements(PmRequest req) {
        String text = req.requirementText() == null ? "" : req.requirementText().trim();
        boolean answered = req.answers() != null && !req.answers().isEmpty();

        // 내용이 너무 짧고 답변도 없으면 부족한 정보를 질문한다
        if (!answered && text.length() < 60) {
            return new PmResult(List.of(
                    "이 기능을 사용할 대상 사용자(예: 고객, 내부 직원)는 누구인가요?",
                    "완료되었다고 판단할 수 있는 기준(수용 조건)은 무엇인가요?",
                    "희망 일정이나 우선순위가 있나요?"), null, List.of(), List.of());
        }

        String all = text + (answered ? "\n" + String.join("\n", req.answers()) : "");
        List<String> sentences = new ArrayList<>();
        for (String s : all.split("[.!?\\n。]+")) {
            String t = s.trim();
            if (t.length() >= 6) sentences.add(t.length() > 120 ? t.substring(0, 120) : t);
        }
        if (sentences.isEmpty()) sentences.add(text.isBlank() ? "요구사항 정리" : text.substring(0, Math.min(text.length(), 120)));
        List<String> picked = sentences.subList(0, Math.min(sentences.size(), 5));

        List<PlanRequirement> reqs = new ArrayList<>();
        List<PlanTask> tasks = new ArrayList<>();
        StringBuilder spec = new StringBuilder();
        spec.append("# 기능 명세서 초안 (Mock 생성)\n\n프로젝트: ").append(req.projectName()).append("\n\n## 요구사항\n");
        for (int i = 0; i < picked.size(); i++) {
            String title = picked.get(i);
            reqs.add(new PlanRequirement(title.length() > 80 ? title.substring(0, 80) : title, title, i == 0 ? "HIGH" : "MEDIUM"));
            spec.append(i + 1).append(". ").append(title).append('\n');
            tasks.add(new PlanTask("설계: " + shorten(title), "화면/데이터/API 설계", i));
            tasks.add(new PlanTask("구현: " + shorten(title), "설계에 따른 구현", i));
            tasks.add(new PlanTask("테스트: " + shorten(title), "수용 조건 기준 테스트", i));
        }
        spec.append("\n## 비고\n- 이 문서는 규칙 기반 Mock 이 만든 초안이며 사람이 검토해야 합니다.\n");
        return new PmResult(List.of(), spec.toString(), reqs, tasks);
    }

    private static String shorten(String s) { return s.length() > 40 ? s.substring(0, 40) + "…" : s; }

    @Override
    public String chat(String systemPrompt, String userText) {
        return "(Mock AI) 메시지를 받았습니다. 아직 LLM 이 연결되지 않아서 요청하신 작업을 실제로 수행하지는 않았어요.";
    }
}
