package com.cenoffice.pm;

import com.cenoffice.pm.PmTypes.PlanRequirement;
import com.cenoffice.pm.PmTypes.PlanTask;
import com.cenoffice.pm.PmTypes.PmResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * LLM 출력의 서버 측 스키마 검증/정제. 통과한 값만 저장·승인 대상이 된다.
 * 개수/길이 상한, 허용 값(priority), 인덱스 범위를 검사하고 제어 문자를 제거한다.
 */
@Component
public class PlanValidator {
    public static final int MAX_QUESTIONS = 5, MAX_REQUIREMENTS = 10, MAX_TASKS = 30;
    public static final int MAX_SPEC = 6000, MAX_TITLE = 200, MAX_DESC = 1000, MAX_QUESTION = 200;
    private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH");

    public static class InvalidPlanException extends Exception {
        public InvalidPlanException(String m) { super(m); }
    }

    /** 정제된 PmResult 를 돌려준다. 규칙 위반 시 InvalidPlanException. */
    public PmResult validate(PmResult r) throws InvalidPlanException {
        if (r == null) throw new InvalidPlanException("AI 응답이 비어 있습니다");
        List<String> questions = new ArrayList<>();
        for (String q : nz(r.questions())) {
            String c = clean(q);
            if (c.isEmpty()) continue;
            if (c.length() > MAX_QUESTION) throw new InvalidPlanException("질문이 너무 깁니다");
            questions.add(c);
        }
        if (questions.size() > MAX_QUESTIONS) throw new InvalidPlanException("질문이 너무 많습니다 (최대 " + MAX_QUESTIONS + ")");
        if (!questions.isEmpty()) return new PmResult(questions, null, List.of(), List.of()); // 질문 단계에서는 계획을 받지 않는다

        String spec = clean(r.spec());
        if (spec.isEmpty()) throw new InvalidPlanException("기능 명세가 비어 있습니다");
        if (spec.length() > MAX_SPEC) throw new InvalidPlanException("기능 명세가 너무 깁니다");

        List<PlanRequirement> reqs = new ArrayList<>();
        for (PlanRequirement q : nz(r.requirements())) {
            String title = clean(q.title()), desc = clean(q.description());
            String pri = q.priority() == null ? "MEDIUM" : q.priority().trim().toUpperCase();
            if (title.isEmpty() || title.length() > MAX_TITLE) throw new InvalidPlanException("요구사항 제목이 올바르지 않습니다");
            if (desc.length() > MAX_DESC) throw new InvalidPlanException("요구사항 설명이 너무 깁니다");
            if (!PRIORITIES.contains(pri)) throw new InvalidPlanException("알 수 없는 우선순위: " + pri);
            reqs.add(new PlanRequirement(title, desc, pri));
        }
        if (reqs.isEmpty() || reqs.size() > MAX_REQUIREMENTS) throw new InvalidPlanException("요구사항 개수가 올바르지 않습니다 (1~" + MAX_REQUIREMENTS + ")");

        List<PlanTask> tasks = new ArrayList<>();
        for (PlanTask t : nz(r.tasks())) {
            String title = clean(t.title()), desc = clean(t.description());
            if (title.isEmpty() || title.length() > MAX_TITLE) throw new InvalidPlanException("작업 제목이 올바르지 않습니다");
            if (desc.length() > MAX_DESC) throw new InvalidPlanException("작업 설명이 너무 깁니다");
            Integer idx = t.requirementIndex();
            if (idx != null && (idx < 0 || idx >= reqs.size())) throw new InvalidPlanException("작업이 존재하지 않는 요구사항을 가리킵니다");
            tasks.add(new PlanTask(title, desc, idx));
        }
        if (tasks.isEmpty() || tasks.size() > MAX_TASKS) throw new InvalidPlanException("작업 개수가 올바르지 않습니다 (1~" + MAX_TASKS + ")");
        return new PmResult(List.of(), spec, reqs, tasks);
    }

    private static <T> List<T> nz(List<T> l) { return l == null ? List.of() : l; }

    static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").trim();
    }
}
