import { API_URL, tokenStore } from '../api'
import type { Entry, Person } from '../calendar/api'

export interface Draft { scope: 'PERSONAL' | 'SHARED'; title: string; description: string | null; location: string | null; startAt: string; endAt: string; allDay: boolean; attendees: Person[] }
export interface Ambiguity { name: string; candidates: Person[] }
/** CREATE_EVENT: 확인 카드 · LIST_EVENTS: 조회 결과 · CLARIFY: 되묻기 · UNSUPPORTED: 아직 못 하는 요청 */
export interface Reply {
  intent: 'CREATE_EVENT' | 'LIST_EVENTS' | 'CLARIFY' | 'UNSUPPORTED'; message: string; draft: Draft | null
  ambiguous: Ambiguity[]; unresolved: string[]; entries: Entry[]; ai: boolean; source: string
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
