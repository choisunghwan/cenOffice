import { API_URL, tokenStore } from '../api'
import type { MediaGrant } from './api'

export interface AssistantTurn { id: string; username: string; question: string; answer: string; state: string; message: string; createdAt: string }
export interface AssistantState { available: boolean; enabled: boolean; reason: string; turn: AssistantTurn | null; history: AssistantTurn[] }
async function request<T>(id: string, path = '', body?: unknown): Promise<T> {
  const r = await fetch(`${API_URL}/api/meetings/${id}/assistant${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { Authorization: `Bearer ${tokenStore.get() ?? ''}`, 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  if (!r.ok) { const e = await r.json().catch(() => ({})); throw new Error(e.error ?? '센에게 연결하지 못했습니다') }
  return r.json()
}
export const assistantApi = {
  state: (id: string) => request<AssistantState>(id),
  enable: (id: string) => request<AssistantState>(id, '/enable', {}),
  disable: (id: string) => request<AssistantState>(id, '/disable', {}),
  stop: (id: string) => request<AssistantState>(id, '/stop', {}),
  ask: (id: string, question: string) => request<AssistantState>(id, '/ask', { question }),
  grant: (id: string, turn: string) => request<MediaGrant>(id, `/turns/${turn}/grant`, {}),
  playing: (id: string, turn: string) => request<AssistantState>(id, `/turns/${turn}/playing`, {}),
  complete: (id: string, turn: string) => request<AssistantState>(id, `/turns/${turn}/complete`, {}),
  audio: async (id: string, turn: string, signal: AbortSignal) => {
    const r = await fetch(`${API_URL}/api/meetings/${id}/assistant/turns/${turn}/audio`, { signal,
      headers: { Authorization: `Bearer ${tokenStore.get() ?? ''}` }, cache: 'no-store' })
    if (!r.ok) throw new Error('음성을 불러오지 못했습니다')
    return r.arrayBuffer()
  },
}
