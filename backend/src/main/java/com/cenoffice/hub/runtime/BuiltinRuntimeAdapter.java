package com.cenoffice.hub.runtime;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Real local document transformation, explicitly rule-based, without an LLM or external calls. */
public class BuiltinRuntimeAdapter implements AgentRuntimeAdapter {
    private final Map<String, RuntimeStatus> results = new ConcurrentHashMap<>();
    public String kind() { return "builtin-local"; }
    public State state() { return State.RUNTIME_AVAILABLE; }
    public boolean simulated() { return false; }
    public boolean supportsCancel() { return false; }
    public boolean cancel(String id) { return false; }
    public String description() { return "내장 문서 도구 · 규칙 기반 초안 생성 · 외부 전송 없음 · 결과 검토 필요"; }
    public String submit(RuntimeRequest request) throws RuntimeFailure {
        String output = switch (request.targetName()) {
            case "cen-fp-estimator" -> fp(request.input());
            case "cen-proposal-writer" -> proposal(request.input());
            case "cen-log-analyzer" -> BuiltinTextTools.logAnalysis(request.input());
            case "cen-sql-reviewer" -> BuiltinTextTools.sqlReview(request.input());
            case "cen-meeting-minutes" -> BuiltinTextTools.meetingMinutes(request.input());
            case "cen-weekly-report" -> BuiltinTextTools.weeklyReport(request.input());
            case "cen-test-case-writer" -> BuiltinTextTools.testCases(request.input());
            default -> throw new RuntimeFailure("지원하지 않는 내장 도구입니다");
        };
        String id = UUID.randomUUID().toString();
        results.put(id, new RuntimeStatus("SUCCEEDED", output, null));
        return id;
    }
    public RuntimeStatus poll(String id) {
        var result = results.remove(id);
        return result == null ? new RuntimeStatus("FAILED", null, "결과가 없거나 이미 수신되었습니다") : result;
    }
    private List<String> lines(String input) {
        return input.lines().map(String::trim).filter(s -> !s.isBlank() && !s.startsWith("[자료:") && !s.startsWith("[추가 요청]") && !s.startsWith("[처리 안내]"))
                .map(s -> s.replace('|', '／')).toList();
    }
    private String fp(String input) {
        var rows = lines(input.split("\\[추가 요청\\]", 2)[0]);
        StringBuilder out = new StringBuilder("# 기능 분류 검토표\n\n규칙 기반 분류 후보입니다. 정식 FP 산정이나 확정 견적이 아닙니다.\n\n| 원문 기능 | 유형 후보 | 분류 근거 / 검토 사항 |\n|---|---|---|\n");
        for (String row : rows) {
            String type = "확인 필요", reason = "기능 경계와 사용자 관점의 단위 프로세스 확인";
            if (row.matches(".*(등록|입력|수정|삭제|저장|생성).*")) { type = "EI"; reason = "입력·데이터 변경 표현 탐지. 처리 경계와 ILF 변경 여부 확인"; }
            else if (row.matches(".*(보고|집계|통계|계산|산출).*")) { type = "EO"; reason = "가공·집계 출력 표현 탐지. 파생 데이터 및 계산 로직 확인"; }
            else if (row.matches(".*(조회|검색|목록|열람).*")) { type = "EQ"; reason = "조회 표현 탐지. 추가 가공 여부와 입출력 요소 확인"; }
            out.append("| ").append(row).append(" | ").append(type).append(" | ").append(reason).append(" |\n");
        }
        out.append("\n## 추가 확인\n- ILF/EIF는 논리적 데이터 그룹 및 시스템 경계 검토 후 별도 식별해야 합니다.\n- DET/FTR/RET와 복잡도가 확정되지 않아 FP 합계는 산출하지 않았습니다.\n- 헤더·중복 행·복합 기능은 사람이 검토해 제외하거나 분리하세요.\n");
        appendRequest(out, input);
        return out.toString();
    }
    private String proposal(String input) {
        var rows = lines(input.split("\\[추가 요청\\]", 2)[0]);
        StringBuilder out = new StringBuilder("# 제안서 구성 초안\n\n규칙 기반 자료 정리 결과입니다. LLM 생성 문서가 아니며 제출 전 검토·보완이 필요합니다.\n\n## 1. 사업 이해\n아래 제공 자료를 기준으로 제안 범위를 확인합니다. 고객명·예산·일정·실적은 임의로 만들지 않았습니다.\n\n## 2. 요구사항 대응 초안\n| 자료 근거 | 제안서 반영 방향 |\n|---|---|\n");
        for (String row : rows) out.append("| ").append(row).append(" | 해당 요구사항의 수행 방안, 검증 기준 및 산출물 구체화 필요 |\n");
        out.append("\n## 3. 수행 계획\n1. 착수: 요구사항과 업무 범위 확정\n2. 분석·설계: 기능별 대응 방안 및 검증 기준 작성\n3. 구현·검증: 산출물 검토 및 인수 기준 확인\n\n## 4. 작성 전 확인 사항\n- 고객명, 사업 목적, 예산 및 일정\n- 실제 수행 조직, 보유 역량 및 증빙 자료\n- 대상 시스템, 연계 범위 및 보안 조건\n\n## 5. 산출물 목록 초안\n요구사항 대응표, 수행 계획, 검증 계획 (계약 범위에 맞춰 확인 필요)\n");
        appendRequest(out, input);
        return out.toString();
    }
    private void appendRequest(StringBuilder out, String input) {
        String[] parts = input.split("\\[추가 요청\\]", 2);
        if (parts.length > 1 && !parts[1].isBlank()) out.append("\n## 추가 요청 검토 메모\n").append(parts[1]).append("\n위 요청은 작성자가 검토할 항목이며 규칙 기반 엔진이 자유문 지시를 실행하지는 않습니다.\n");
    }
}
