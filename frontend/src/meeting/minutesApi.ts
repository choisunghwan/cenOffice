import { API_URL, tokenStore } from '../api'

export interface MemberRef { username: string; displayName: string }
export interface ActionItem { id?: number | null; content: string; assigneeUsername?: string | null; assigneeName?: string | null; assigneeText?: string | null; dueText?: string | null; done?: boolean }
export interface Minutes {
  meetingId: string; title: string; status: 'DRAFT' | 'CONFIRMED'; summary: string; decisions: string[]; issues: string[]; actionItems: ActionItem[]
  extraNotes?: string | null; source: string; generatedBy: string; generatedAt: string; confirmedBy?: string | null; confirmedAt?: string | null
  canEdit: boolean; members: MemberRef[]; markdown: string
}
export interface MyItem { id: number; meetingId: string; meetingTitle: string; content: string; dueText?: string | null; done: boolean }
export interface NotificationItem { id: number; type: string; title: string; body?: string | null; link?: string | null; createdAt: string; read: boolean }

export class NotFound extends Error {}

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(`${API_URL}/api${path}`, {
    method,
    headers: { Authorization: `Bearer ${tokenStore.get() ?? ''}`, 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  if (!response.ok) {
    const r = await response.json().catch(() => ({}))
    if (response.status === 404) throw new NotFound(r.error ?? '찾을 수 없습니다')
    throw new Error(r.error ?? '요청을 처리하지 못했습니다')
  }
  return response.status === 204 ? (undefined as T) : response.json()
}

export const minutesApi = {
  get: (id: string) => call<Minutes>('GET', `/meetings/${id}/minutes`),
  generate: (id: string, notes: string) => call<Minutes>('POST', `/meetings/${id}/minutes/generate`, { notes }),
  save: (id: string, m: { summary: string; decisions: string[]; issues: string[]; actionItems: ActionItem[] }) => call<Minutes>('PUT', `/meetings/${id}/minutes`, m),
  confirm: (id: string) => call<Minutes>('POST', `/meetings/${id}/minutes/confirm`, {}),
  myItems: () => call<MyItem[]>('GET', '/action-items/mine'),
  setDone: (id: number, done: boolean) => call<void>('POST', `/action-items/${id}/done`, { done }),
  notifications: () => call<{ items: NotificationItem[]; unread: number }>('GET', '/notifications'),
  markRead: (id: number) => call<void>('POST', `/notifications/${id}/read`, {}),
  markAllRead: () => call<void>('POST', '/notifications/read-all', {}),
}
