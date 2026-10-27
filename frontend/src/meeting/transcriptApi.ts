import { API_URL, tokenStore } from '../api'

export interface Segment { id: number; username: string; displayName: string; content: string; spokenAt: string }
export interface TranscriptStatus { consented: boolean; segmentCount: number; recording: string[] }

async function call<T>(method: string, meetingId: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(`${API_URL}/api/meetings/${meetingId}/transcript${path}`, {
    method,
    headers: { Authorization: `Bearer ${tokenStore.get() ?? ''}`, 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  if (!response.ok) { const r = await response.json().catch(() => ({})); throw new Error(r.error ?? '음성 기록 요청을 처리하지 못했습니다') }
  return response.status === 204 ? undefined as T : response.json()
}

export const ENGINE = 'browser-webspeech'

export const transcriptApi = {
  status: (id: string) => call<TranscriptStatus>('GET', id, '/status'),
  list: (id: string, after = 0) => call<Segment[]>('GET', id, `?after=${after}`),
  consent: (id: string) => call<void>('POST', id, '/consent', { engine: ENGINE }),
  withdraw: (id: string) => call<void>('DELETE', id, '/consent'),
  add: (id: string, content: string) => call<Segment>('POST', id, '', { content, engine: ENGINE }),
}
