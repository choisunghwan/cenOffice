import { API_URL, tokenStore } from '../api'

/** 양식에서 찾은 채울 자리. placeholder 는 본문의 {{이름}}, cell 은 표에서 라벨 칸 오른쪽의 빈 칸 */
export interface TemplateField { key: string; label: string; kind: 'placeholder' | 'cell' | 'choice' | 'datetime' | 'rows'; options?: string[]; rows?: number }
export interface TemplateInfo { fileName: string; format: string; fields: TemplateField[] }
/** 형식 아이콘 한 칸. supported=false 는 아직 채우는 기능이 없는 형식, template 은 올려 둔 양식 */
export interface FormatSlot { format: string; label: string; supported: boolean; template?: TemplateInfo | null }
/** RESULT: 스킬 결과에서 찾음 · AI: AI 가 결과에서 뽑음 · MISSING: 근거를 못 찾아 비워 둠 */
export interface PlanItem { key: string; label: string; value?: string | null; source: 'RESULT' | 'AI' | 'MISSING'; kind?: string; options?: string[]; rows?: number }
export interface TemplatePlan { items: PlanItem[]; aiUsed: boolean }
export interface PreviewCell { text: string; fieldKey?: string | null }
export interface PreviewBlock { type: 'p' | 'table'; text?: string | null; rows?: PreviewCell[][] | null; fieldKey?: string | null }

/** 도구 번호, 또는 'me'(내 양식: 예) 내 회의록 양식) */
export type FormTarget = number | 'me'

async function send(method: string, skillId: FormTarget, path: string, body?: BodyInit | object): Promise<Response> {
  const token = tokenStore.get()
  const isForm = body instanceof FormData
  const res = await fetch(`${API_URL}${skillId === 'me' ? '/api/hub/templates/me' : `/api/hub/skills/${skillId}/template`}${path}`, {
    method,
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body && !isForm ? { 'Content-Type': 'application/json' } : {}) },
    ...(body === undefined ? {} : { body: isForm ? body : JSON.stringify(body) }),
  })
  if (!res.ok) { const r = await res.json().catch(() => ({})); throw new Error(r.error ?? '양식 요청을 처리하지 못했습니다') }
  return res
}

export const templateApi = {
  /** 등록된 양식이 없으면 null */
  info: async (skillId: FormTarget, format = 'hwpx'): Promise<TemplateInfo | null> => {
    const res = await send('GET', skillId, `?format=${format}`)
    return res.status === 204 ? null : res.json()
  },
  slots: async (skillId: FormTarget): Promise<FormatSlot[]> => (await send('GET', skillId, '/formats')).json(),
  put: async (skillId: FormTarget, file: File): Promise<TemplateInfo> => {
    const form = new FormData()
    form.append('file', file)
    return (await send('PUT', skillId, '', form)).json()
  },
  remove: async (skillId: FormTarget, format = 'hwpx'): Promise<void> => { await send('DELETE', skillId, `?format=${format}`) },
  plan: async (skillId: FormTarget, result: string, format = 'hwpx'): Promise<TemplatePlan> => (await send('POST', skillId, '/plan', { format, result })).json(),
  draft: async (skillId: FormTarget, result: string, key: string, format = 'hwpx'): Promise<string> => (await send('POST', skillId, '/draft', { format, result, key })).json().then((r: { value: string }) => r.value),
  preview: async (skillId: FormTarget, values: Record<string, string>, format = 'hwpx'): Promise<PreviewBlock[]> => (await send('POST', skillId, '/preview', { format, values })).json().then((r: { blocks: PreviewBlock[] }) => r.blocks),
  /** 값을 채운 양식 파일(형식에 맞는 확장자)을 내려받는다 */
  render: async (skillId: FormTarget, values: Record<string, string>, title: string, format = 'hwpx'): Promise<void> => {
    const res = await send('POST', skillId, '/render', { format, values, title })
    const cd = res.headers.get('Content-Disposition') ?? ''
    const enc = /filename\*=UTF-8''([^;]+)/.exec(cd)?.[1]
    const url = URL.createObjectURL(await res.blob())
    const a = document.createElement('a')
    a.href = url; a.download = enc ? decodeURIComponent(enc) : `${title}.${format}`; a.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  },
}
