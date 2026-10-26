const NAMES: Record<string, string> = {
  'cen-fp-estimator': 'FP 산정 도우미',
  'cen-proposal-writer': '제안서 작성 도우미',
  'cen-log-analyzer': '오류 로그 분석',
  'cen-sql-reviewer': 'SQL 점검',
  'cen-meeting-minutes': '회의록 정리',
  'cen-weekly-report': '주간 업무보고 정리',
  'cen-test-case-writer': '테스트 케이스 초안',
  'requirement-analyst': '요구사항 분석 도우미',
  'error-detective': '오류 원인 추적 도우미',
}

/** 화면 표시 이름: 직접 지정한 title > 내장 도구 이름 > 내부 이름 */
export function toolName(name: string, title?: string | null) {
  return title || NAMES[name] || name
}
