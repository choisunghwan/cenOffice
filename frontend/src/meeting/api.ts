import { API_URL, tokenStore } from '../api'

export interface Meeting { id: string; title: string; host: string; code: string; state: 'OPEN' | 'ENDED'; createdAt: string; memberCount: number }
export interface MeetingMessage { id: number; username: string; displayName: string; content: string; sentAt: string }
export interface MediaGrant { url: string; token: string }
async function call<T>(path: string, body?: unknown): Promise<T> {
  const response = await fetch(`${API_URL}/api/meetings${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { Authorization: `Bearer ${tokenStore.get() ?? ''}`, 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  if (!response.ok) { const r = await response.json().catch(() => ({})); throw new Error(r.error ?? '회의 요청을 처리하지 못했습니다') }
  return response.status === 204 ? undefined as T : response.json()
}
export const meetingsApi = {
  list: () => call<Meeting[]>(''),
  status: () => call<{ configured: boolean; available: boolean }>('/media-status'),
  create: (title: string) => call<Meeting>('', { title }),
  join: (code: string) => call<Meeting>('/join', { code }),
  detail: (id: string) => call<Meeting>(`/${id}`),
  grant: (id: string) => call<MediaGrant>(`/${id}/join-token`, {}),
  end: (id: string) => call<void>(`/${id}/end`, {}),
  messages: (id: string) => call<MeetingMessage[]>(`/${id}/messages`),
  send: (id: string, content: string) => call<void>(`/${id}/messages`, { content }),
}
