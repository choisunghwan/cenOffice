/**
 * 입력창 자동완성(Claude Code 방식): 입력창에 회색으로 이어질 말을 보여 주고, Tab 으로 받아들인다.
 * 후보 순서: 직전 답변의 추천 → 내가 전에 한 말 → 자주 쓰는 명령 → 첫 화면 예시.
 */
export const COMMON_PROMPTS = [
  '오늘 일정 알려줘', '내일 일정 알려줘', '이번 주 일정 알려줘', '다음 주 일정 알려줘',
  '오늘 브리핑 해줘', '내 할 일 보여줘', '확정할 회의록 보여줘', '회의록 다운로드해줘',
  '회의 시작해줘', '내일 오후 3시에 팀 회의 잡아줘', '내일 오후 2시에 회의실 예약해줘',
  '쓸 만한 스킬 뭐 있어?', '스킬 화면 열어줘', '내 스킬 화면 열어줘', '스킬 만들기 화면 열어줘', '일정 화면 열어줘', '회의 화면 열어줘',
]

const norm = (s: string) => s.replace(/\s+/g, ' ').trimStart()

/** 지금 입력에 이어 붙일 제안 전체 문장. 없으면 null. 빈 입력이면 다음에 할 말(whenEmpty)을 준다 */
export function suggestion(text: string, pool: string[], whenEmpty: string | null): string | null {
  const t = norm(text)
  if (!t) return whenEmpty
  if (/\n/.test(text)) return null
  const lower = t.toLowerCase()
  for (const c of pool) {
    const n = norm(c)
    if (n.length > t.length && n.toLowerCase().startsWith(lower)) return text + n.slice(t.length)
  }
  return null
}

/** 후보 목록을 순서대로 합치고 중복을 뺀다 */
export function buildPool(next: string[], history: string[], examples: string[]): string[] {
  const seen = new Set<string>(), out: string[] = []
  for (const c of [...next, ...history, ...COMMON_PROMPTS, ...examples]) {
    const k = norm(c).trim()
    if (!k || k.length > 80 || seen.has(k)) continue
    seen.add(k); out.push(k)
  }
  return out
}
