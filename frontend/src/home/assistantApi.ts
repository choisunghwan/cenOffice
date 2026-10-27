import { API_URL, tokenStore } from '../api'
import type { Entry, Person } from '../calendar/api'
import type { MyItem, PendingMinutes } from '../meeting/minutesApi'

export interface Draft { scope: 'PERSONAL' | 'SHARED'; title: string; description: string | null; location: string | null; startAt: string; endAt: string; allDay: boolean; attendees: Person[] }
export interface Ambiguity { name: string; candidates: Person[] }
/** 추천한 AI 허브 도구. engine: rule(서버 안에서 처리) | ai(AI 모델로 처리). external=true 면 입력이 AI 서비스로 전송된다. */
export interface ToolPick { skillId: number; title: string; description: string; icon: string | null; category: string | null; engine: 'rule' | 'ai'; external: boolean; /** 결과물 양식이 등록된 도구면 'hwpx' */ templateFormat?: string | null }
export interface ChatSummary { id: string; title: string; updatedAt: string }
export interface Room { id: number; name: string; capacity: number }
/**
 * CREATE_EVENT: 등록 확인 카드 · LIST_EVENTS: 조회 결과 · USE_TOOL: 도구 추천 카드 · BOOK_ROOM: 회의실 예약 카드 ·
 * DELETE_ITEM: 삭제·취소 카드 · MOVE_EVENT: 일정 변경 카드 · CLARIFY: 되묻기 · UNSUPPORTED: 아직 못 하는 요청
 */
export interface Reply {
  intent: 'CREATE_EVENT' | 'LIST_EVENTS' | 'LIST_TASKS' | 'LIST_PENDING' | 'BRIEFING' | 'ANSWER' | 'USE_TOOL' | 'BOOK_ROOM' | 'DELETE_ITEM' | 'MOVE_EVENT' | 'CLARIFY' | 'UNSUPPORTED'; message: string; draft: Draft | null
  ambiguous: Ambiguity[]; unresolved: string[]; entries: Entry[]; ai: boolean; source: string
  tool: ToolPick | null; alternatives: ToolPick[]
  /** BOOK_ROOM: 정해진 방(없으면 rooms 에서 고른다) · DELETE_ITEM/MOVE_EVENT: 대상(삭제는 여러 개면 candidates 에서 고른다) */
  room?: Room | null; rooms?: Room[]; target?: Entry | null; candidates?: Entry[]
  /** LIST_TASKS·BRIEFING: 안 끝난 내 할 일 · LIST_PENDING·BRIEFING: 확정을 기다리는 회의록 */
  tasks?: MyItem[]; pendingMinutes?: PendingMinutes[]
  /** ANSWER: 답의 근거로 AI 에게 보여 준 내 업무 정보(예: '회의록: 주간 회의', '내 할 일') */
  references?: string[]
}

/** 앞서 오간 말. "그건 뭔데"처럼 앞 이야기를 가리키는 질문을 이해하는 데 쓴다. */
export interface HistoryTurn { role: 'user' | 'assistant'; text: string }

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(`${API_URL}/api/assistant${path}`, {
    method,
    headers: { Authorization: `Bearer ${tokenStore.get() ?? ''}`, 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  if (!response.ok) { const r = await response.json().catch(() => ({})); throw new Error(r.error ?? '요청을 처리하지 못했습니다') }
  return response.status === 204 ? undefined as T : response.json()
}

export const assistantApi = {
  /** 홈 입력창 채팅방(대화 세션) */
  sessions: {
    list: () => call<ChatSummary[]>('GET', '/sessions'),
    create: (title?: string) => call<ChatSummary>('POST', '/sessions', { title: title ?? '' }),
    get: (id: string) => call<{ id: string; title: string; turns: unknown[] }>('GET', `/sessions/${id}`),
    save: (id: string, body: { title?: string; turns?: unknown[] }) => call<void>('PUT', `/sessions/${id}`, body),
    remove: (id: string) => call<void>('DELETE', `/sessions/${id}`),
  },
  status: () => call<{ ai: boolean }>('GET', '/status'),
  interpret: (text: string, previous?: string, history?: HistoryTurn[]) =>
    call<Reply>('POST', '/interpret', { text, ...(previous ? { previous } : {}), ...(history && history.length ? { history } : {}) }),
}
