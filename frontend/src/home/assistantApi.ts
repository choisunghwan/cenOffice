import { API_URL, tokenStore } from '../api'
import type { Entry, Person } from '../calendar/api'

export interface Draft { scope: 'PERSONAL' | 'SHARED'; title: string; description: string | null; location: string | null; startAt: string; endAt: string; allDay: boolean; attendees: Person[] }
export interface Ambiguity { name: string; candidates: Person[] }
/** 추천한 AI 허브 도구. engine: rule(서버 안에서 처리) | ai(AI 모델로 처리). external=true 면 입력이 AI 서비스로 전송된다. */
export interface ToolPick { skillId: number; title: string; description: string; icon: string | null; category: string | null; engine: 'rule' | 'ai'; external: boolean }
/** CREATE_EVENT: 등록 확인 카드 · LIST_EVENTS: 조회 결과 · USE_TOOL: 도구 추천 카드 · CLARIFY: 되묻기 · UNSUPPORTED: 아직 못 하는 요청 */
export interface Reply {
  intent: 'CREATE_EVENT' | 'LIST_EVENTS' | 'USE_TOOL' | 'CLARIFY' | 'UNSUPPORTED'; message: string; draft: Draft | null
  ambiguous: Ambiguity[]; unresolved: string[]; entries: Entry[]; ai: boolean; source: string
  tool: ToolPick | null; alternatives: ToolPick[]
}

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(`${API_URL}/api/assistant${path}`, {
    method,
    headers: { Authorization: `Bearer ${tokenStore.get() ?? ''}`, 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  if (!response.ok) { const r = await response.json().catch(() => ({})); throw new Error(r.error ?? '요청을 처리하지 못했습니다') }
  return response.json()
}

export const assistantApi = {
  status: () => call<{ ai: boolean }>('GET', '/status'),
  interpret: (text: string, previous?: string) => call<Reply>('POST', '/interpret', { text, ...(previous ? { previous } : {}) }),
}
