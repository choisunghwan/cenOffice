import { ApiError, API_URL, tokenStore } from './api'

/** AI HUB API 클라이언트 (기존 api.ts 의 토큰/오류 규칙을 재사용) */

export interface HubPage<T> { items: T[]; total: number; page: number; size: number }
export interface HubToken { id: number; name: string; prefix: string; createdAt: string; expiresAt: string; lastUsedAt?: string; revoked: boolean; expired: boolean }
export interface HubCategory { code: string; name: string }
export interface HubSkillSummary {
  id: number; name: string; description: string; category: string; categoryName: string; icon?: string; visibility: 'PUBLIC' | 'TEAM'; team?: string
  status: string; owner: string; ownerName: string; version?: string; tags: string[]; executions: number; succeeded: number
  ratingAvg: number; ratingCount: number; sample: boolean; updatedAt: string; executionState: string
}
export interface HubVersion {
  id: number; version: string; status: string; createdBy: string; createdAt: string; sha256: string; packageKind: 'MD' | 'ZIP'; riskFlags: string[]; published: boolean
}
export interface HubVersionContent {
  meta: HubVersion; skillMd: string; frontmatter: Record<string, unknown>; files: { path: string; size: number }[]; usage?: string; example?: string
}
export interface HubRuntime { state: 'RUNTIME_NOT_CONFIGURED' | 'RUNTIME_AVAILABLE'; kind: string; simulated: boolean; description: string }
export interface HubSkillDetail {
  summary: HubSkillSummary; current?: HubVersionContent; versions: HubVersion[]; canEdit: boolean; canApprove: boolean; runtime: HubRuntime
}
export interface HubReview { id: number; reviewer: string; reviewerName: string; rating: number; comment?: string; updatedAt: string }
export interface HubHistory { id: number; action: string; actor: string; comment?: string; versionId?: number; at: string }
export interface HubAgentSummary {
  id: number; name: string; description: string; duties?: string; visibility: string; team?: string; status: string; owner: string; ownerName: string
  version: number; skillCount: number; sample: boolean; updatedAt: string; executionState: string
}
export interface HubAgentDetail {
  summary: HubAgentSummary; config?: string; skills: { skillId: number; name: string; icon?: string; status: string }[]
  versions: { id: number; version: number; createdBy: string; createdAt: string }[]; canEdit: boolean; canApprove: boolean; runtime: HubRuntime
}
export interface HubExecution {
  id: string; targetType: 'SKILL' | 'AGENT'; skillId?: number; agentId?: number; targetName: string; requester: string; input?: string
  status: string; runtimeKind: string; simulated: boolean; counted: boolean; result?: string; error?: string; requestedAt: string; completedAt?: string
}
export interface HubExclusions { simulated: number; creatorSelf: number; sample: number }
export interface HubCounts { requests: number; succeeded: number; failed: number; uniqueUsers: number; repeatUsers: number; last7d: number; last30d: number }
export interface HubOverview {
  skills: number; approvedSkills: number; agents: number; executionsThisMonth: number; activeUsers: number
  popular: HubSkillSummary[]; recent: HubSkillSummary[]; exclusions: HubExclusions
}
export interface HubSkillStats {
  skillId: number; name: string; counts: HubCounts; ratingAvg: number; ratingCount: number; byVersion: Record<string, number>
  daily: { date: string; requests: number }[]; exclusions: HubExclusions
}
export interface HubCreatorStats {
  creator: string; creatorName: string; skillsRegistered: number; skillsApproved: number; agentsRegistered: number; totals: HubCounts
  skills: HubSkillSummary[]; agents: HubAgentSummary[]; monthly: { month: string; requests: number; succeeded: number }[]
  recentFeedback: HubReview[]; exclusions: HubExclusions; note: string
}
export interface HubQueueItem {
  skillId: number; versionId: number; name: string; icon?: string; version: string; owner: string; ownerName: string; submittedAt: string
  riskFlags: string[]; packageKind: string; sample: boolean
}
export interface HubMe { username: string; displayName: string; team: string; role: 'ADMIN' | 'USER'; canRegister: boolean; canApprove: boolean }

async function req<T>(path: string, init: RequestInit = {}, json = true): Promise<T> {
  const token = tokenStore.get()
  let res: Response
  try {
    res = await fetch(API_URL + path, {
      ...init,
      headers: { ...(json ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}), ...init.headers },
    })
  } catch {
    throw new ApiError(0, '백엔드 서버에 연결할 수 없습니다')
  }
  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    throw new ApiError(res.status, body.error ?? res.statusText)
  }
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

const qs = (o: Record<string, string | number | boolean | undefined | null>) => {
  const p = new URLSearchParams()
  Object.entries(o).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== '' && v !== false) p.set(k, String(v)) })
  const s = p.toString()
  return s ? `?${s}` : ''
}

export interface SkillQuery { q?: string; category?: string; owner?: string; tag?: string; status?: string; mine?: boolean; sort?: string; page?: number; size?: number }

export const hub = {
  builtins: () => req<HubSkillDetail[]>('/api/hub/builtins'),
  executeDocuments: (id: number, files: File[], instructions: string) => {
    const body = new FormData()
    files.forEach((file) => body.append('files', file))
    body.append('instructions', instructions)
    return req<HubExecution>(`/api/hub/builtins/${id}/documents`, { method: 'POST', body }, false)
  },
  me: () => req<HubMe>('/api/hub/me'),
  categories: () => req<HubCategory[]>('/api/hub/categories'),
  runtime: () => req<HubRuntime>('/api/hub/runtime'),
  overview: () => req<HubOverview>('/api/hub/analytics/overview'),
  skills: (q: SkillQuery) => req<HubPage<HubSkillSummary>>('/api/hub/skills' + qs({ ...q })),
  skill: (id: number, versionId?: number) => req<HubSkillDetail>(`/api/hub/skills/${id}` + qs({ versionId })),
  registerMd: (b: { skillMd: string; category?: string; tags?: string[]; visibility?: string; submit?: boolean }) =>
    req<HubSkillDetail>('/api/hub/skills', { method: 'POST', body: JSON.stringify(b) }),
  upload: (file: File, o: { category?: string; tags?: string; visibility?: string; submit?: boolean }) => {
    const f = new FormData()
    f.append('file', file)
    if (o.category) f.append('category', o.category)
    if (o.tags) f.append('tags', o.tags)
    if (o.visibility) f.append('visibility', o.visibility)
    f.append('submit', String(!!o.submit))
    return req<HubSkillDetail>('/api/hub/skills/upload', { method: 'POST', body: f }, false)
  },
  addVersionMd: (id: number, skillMd: string, submit: boolean) =>
    req<HubSkillDetail>(`/api/hub/skills/${id}/versions`, { method: 'POST', body: JSON.stringify({ skillMd, submit }) }),
  addVersionFile: (id: number, file: File, submit: boolean) => {
    const f = new FormData()
    f.append('file', file)
    f.append('submit', String(submit))
    return req<HubSkillDetail>(`/api/hub/skills/${id}/versions/upload`, { method: 'POST', body: f }, false)
  },
  updateMeta: (id: number, b: { category?: string; tags?: string[]; icon?: string; visibility?: string }) =>
    req<HubSkillDetail>(`/api/hub/skills/${id}`, { method: 'PUT', body: JSON.stringify(b) }),
  archive: (id: number) => req<void>(`/api/hub/skills/${id}`, { method: 'DELETE' }),
  submitVersion: (id: number, vid: number) => req<HubSkillDetail>(`/api/hub/skills/${id}/versions/${vid}/submit`, { method: 'POST' }),
  approve: (id: number, vid: number, comment?: string) => req<HubSkillDetail>(`/api/hub/skills/${id}/versions/${vid}/approve`, { method: 'POST', body: JSON.stringify({ comment }) }),
  reject: (id: number, vid: number, comment: string) => req<HubSkillDetail>(`/api/hub/skills/${id}/versions/${vid}/reject`, { method: 'POST', body: JSON.stringify({ comment }) }),
  rollback: (id: number, versionId: number, comment?: string) => req<HubSkillDetail>(`/api/hub/skills/${id}/rollback`, { method: 'POST', body: JSON.stringify({ versionId, comment }) }),
  history: (id: number) => req<HubHistory[]>(`/api/hub/skills/${id}/history`),
  reviews: (id: number) => req<HubReview[]>(`/api/hub/skills/${id}/reviews`),
  review: (id: number, rating: number, comment?: string) => req<HubReview>(`/api/hub/skills/${id}/reviews`, { method: 'POST', body: JSON.stringify({ rating, comment }) }),
  queue: () => req<HubQueueItem[]>('/api/hub/review-queue'),
  agentQueue: () => req<HubAgentSummary[]>('/api/hub/agents/review-queue'),
  /** 인증 헤더가 필요하므로 blob 으로 받아 저장한다 */
  download: async (id: number, versionId?: number) => {
    const token = tokenStore.get()
    const res = await fetch(`${API_URL}/api/hub/skills/${id}/download` + qs({ versionId }), { headers: token ? { Authorization: `Bearer ${token}` } : {} })
    if (!res.ok) throw new ApiError(res.status, (await res.json().catch(() => ({}))).error ?? '다운로드에 실패했습니다')
    const name = /filename="?([^";]+)"?/.exec(res.headers.get('Content-Disposition') ?? '')?.[1] ?? `skill-${id}`
    const url = URL.createObjectURL(await res.blob())
    const a = document.createElement('a')
    a.href = url
    a.download = name
    a.click()
    URL.revokeObjectURL(url)
  },
  /** 승인된 Skill 을 Claude Code/Codex 용 폴더 구조 ZIP 으로 받는다 */
  exportSkills: async (includeSamples = false) => {
    const token = tokenStore.get()
    const res = await fetch(`${API_URL}/api/hub/skills/export.zip` + qs({ includeSamples: includeSamples || undefined }), { headers: token ? { Authorization: `Bearer ${token}` } : {} })
    if (!res.ok) throw new ApiError(res.status, (await res.json().catch(() => ({}))).error ?? '내려받기에 실패했습니다')
    const url = URL.createObjectURL(await res.blob())
    const a = document.createElement('a')
    a.href = url; a.download = 'cen-ai-hub-skills.zip'; a.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  },
  /** 도구 결과(Markdown)를 xlsx/docx/pptx/pdf 파일로 변환해 저장한다 */
  exportDoc: async (format: 'xlsx' | 'docx' | 'pptx' | 'pdf' | 'hwpx', title: string, markdown: string) => {
    const token = tokenStore.get()
    const res = await fetch(`${API_URL}/api/hub/documents/export`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify({ format, title, markdown }),
    })
    if (!res.ok) throw new ApiError(res.status, (await res.json().catch(() => ({}))).error ?? '파일을 만들지 못했습니다')
    const cd = res.headers.get('Content-Disposition') ?? ''
    const enc = /filename\*=UTF-8''([^;]+)/.exec(cd)?.[1]
    const name = enc ? decodeURIComponent(enc) : `${title}.${format}`
    const url = URL.createObjectURL(await res.blob())
    const a = document.createElement('a')
    a.href = url; a.download = name; a.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  },
  agents: (q: { q?: string; owner?: string; status?: string; mine?: boolean; page?: number; size?: number }) => req<HubPage<HubAgentSummary>>('/api/hub/agents' + qs({ ...q })),
  agent: (id: number) => req<HubAgentDetail>(`/api/hub/agents/${id}`),
  createAgent: (b: { name: string; description: string; duties?: string; config?: string; visibility?: string; skillIds?: number[]; submit?: boolean }) =>
    req<HubAgentDetail>('/api/hub/agents', { method: 'POST', body: JSON.stringify(b) }),
  updateAgent: (id: number, b: { name?: string; description?: string; duties?: string; config?: string; visibility?: string; submit?: boolean }) =>
    req<HubAgentDetail>(`/api/hub/agents/${id}`, { method: 'PUT', body: JSON.stringify(b) }),
  linkSkill: (agentId: number, skillId: number) => req<HubAgentDetail>(`/api/hub/agents/${agentId}/skills/${skillId}`, { method: 'POST' }),
  unlinkSkill: (agentId: number, skillId: number) => req<HubAgentDetail>(`/api/hub/agents/${agentId}/skills/${skillId}`, { method: 'DELETE' }),
  submitAgent: (id: number) => req<HubAgentDetail>(`/api/hub/agents/${id}/submit`, { method: 'POST' }),
  approveAgent: (id: number, comment?: string) => req<HubAgentDetail>(`/api/hub/agents/${id}/approve`, { method: 'POST', body: JSON.stringify({ comment }) }),
  rejectAgent: (id: number, comment: string) => req<HubAgentDetail>(`/api/hub/agents/${id}/reject`, { method: 'POST', body: JSON.stringify({ comment }) }),
  tokens: () => req<HubToken[]>('/api/hub/tokens'),
  createToken: (name: string, days: number) => req<{ token: HubToken; secret: string }>('/api/hub/tokens', { method: 'POST', body: JSON.stringify({ name, days }) }),
  revokeToken: (id: number) => req<void>(`/api/hub/tokens/${id}`, { method: 'DELETE' }),
  aiEngine: () => req<{ available: boolean; provider: string; external: boolean; notice: string }>('/api/hub/ai-engine'),
  execute: (b: { skillId?: number; agentId?: number; input: string; engine?: 'rule' | 'ai' }) => req<HubExecution>('/api/hub/executions', { method: 'POST', body: JSON.stringify(b) }),
  execution: (id: string) => req<HubExecution>(`/api/hub/executions/${id}`),
  cancelExecution: (id: string) => req<HubExecution>(`/api/hub/executions/${id}/cancel`, { method: 'POST' }),
  executions: (skillId?: number) => req<HubPage<HubExecution>>('/api/hub/executions' + qs({ skillId, size: 10 })),
  skillStats: (id: number) => req<HubSkillStats>(`/api/hub/analytics/skills/${id}`),
  myStats: () => req<HubCreatorStats>('/api/hub/analytics/creators/me'),
}
