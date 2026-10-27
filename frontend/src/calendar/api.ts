import { API_URL, tokenStore } from '../api'

export type Scope = 'mine' | 'shared'
export interface Person { username: string; displayName: string }
/** EVENT: 내가 만든/초대받은/전체 일정, RESERVATION: 내 회의실 예약(읽기 전용), TASK: 기한이 날짜인 내 할 일(읽기 전용) */
export interface Entry {
  kind: 'EVENT' | 'RESERVATION' | 'TASK'; id: string; eventId: number | null; scope: 'PERSONAL' | 'SHARED'
  title: string; description: string | null; location: string | null; startAt: string; endAt: string; allDay: boolean
  createdBy: string; createdByName: string | null; attendees: Person[]; editable: boolean
}
export interface Feed { entries: Entry[]; canManageShared: boolean }
export interface EventBody {
  scope: 'PERSONAL' | 'SHARED'; title: string; description: string; location: string
  startAt: string; endAt: string; allDay: boolean; attendees: string[]
}

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(`${API_URL}/api/calendar${path}`, {
    method,
    headers: { Authorization: `Bearer ${tokenStore.get() ?? ''}`, 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  if (!response.ok) { const r = await response.json().catch(() => ({})); throw new Error(r.error ?? '일정 요청을 처리하지 못했습니다') }
  return response.status === 204 ? undefined as T : response.json()
}

export const calendarApi = {
  feed: (scope: Scope, from: Date, to: Date) => call<Feed>('GET', `?scope=${scope}&from=${encodeURIComponent(from.toISOString())}&to=${encodeURIComponent(to.toISOString())}`),
  people: () => call<Person[]>('GET', '/people'),
  create: (b: EventBody) => call<Entry>('POST', '/events', b),
  update: (id: number, b: EventBody) => call<Entry>('PUT', `/events/${id}`, b),
  remove: (id: number) => call<void>('DELETE', `/events/${id}`),
}
