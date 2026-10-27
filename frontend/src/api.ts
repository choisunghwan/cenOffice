export const API_URL: string = import.meta.env.VITE_API_URL ?? 'http://localhost:8080'
const WS_URL = API_URL.replace(/^http/, 'ws') + '/ws'
const TOKEN_KEY = 'cen.token'

export type CommandStatus = 'REQUESTED' | 'SENT' | 'CONFIRMED' | 'FAILED' | 'TIMEOUT'

export interface StateView {
  power: boolean
  targetTemp?: number
  currentTemp?: number
  mode?: 'COOL' | 'HEAT'
  online: boolean
  updatedAt?: string
}
export interface DeviceView {
  id: number
  code: string
  name: string
  type: 'LIGHT' | 'AIR_CONDITIONER'
  adapterType: 'MOCK' | 'MQTT' | 'HTTP'
  objectId?: string
  state: StateView
}
export interface CommandView {
  id: string
  deviceId: number
  deviceCode: string
  status: CommandStatus
  power?: boolean
  targetTemp?: number
  mode?: string
  requestedBy: string
  requestedAt: string
  completedAt?: string
  error?: string
}
export interface CommandBody {
  power?: boolean
  targetTemp?: number
  mode?: 'COOL' | 'HEAT'
}
export interface UserView {
  username: string
  displayName: string
  role: 'ADMIN' | 'USER'
  team?: string
}
export interface MeetingRoom {
  id: number
  name: string
  capacity: number
}
export interface Reservation {
  id: number
  roomId: number
  title: string
  reservedBy: string
  startAt: string
  endAt: string
}
export interface ServerSnapshot {
  source: string
  at: string
  servers: { name: string; role: string; status: 'OK' | 'WARN' | 'DOWN'; cpu: number; memory: number }[]
  events: { at: string; level: 'INFO' | 'WARN' | 'ERROR'; message: string }[]
}

export type Priority = 'LOW' | 'MEDIUM' | 'HIGH'
export type RequirementStatus = 'DRAFT' | 'APPROVED' | 'IN_PROGRESS' | 'DONE'
export type TaskStatus = 'TODO' | 'IN_PROGRESS' | 'REVIEW' | 'DONE'
export type Environment = 'DEV' | 'STG' | 'PROD'
export type AiTaskType = 'STATUS_QUERY' | 'ANALYZE_REQUIREMENT' | 'DRAFT_SPEC' | 'GENERATE_TASKS' | 'CODE_CHANGE' | 'RUN_TESTS'
export type AiTaskStatus = 'QUEUED' | 'RUNNING' | 'NEEDS_INPUT' | 'WAITING_APPROVAL' | 'DONE' | 'FAILED' | 'REJECTED'

export interface Project {
  id: number; name: string; description?: string; status: 'ACTIVE' | 'ARCHIVED'; owner: string
  createdAt: string; updatedAt: string; requirementCount: number; taskCount: number; doneCount: number
}
export interface RequirementItem {
  id: number; projectId: number; title: string; description?: string; priority: Priority
  status: RequirementStatus; createdBy: string; createdAt: string; updatedAt: string
}
export interface TaskItem {
  id: number; projectId: number; requirementId?: number; title: string; description?: string
  status: TaskStatus; assigneeAgentId?: number; createdBy: string; createdAt: string; updatedAt: string
}
export interface Agent { id: number; code: string; name: string; role: 'PM' | 'DEV' | 'QA'; enabled: boolean; allowedEnvironments: string }
export interface AiTaskItem {
  id: number; projectId: number; taskId?: number; agentId: number; type: AiTaskType; environment: Environment
  status: AiTaskStatus; input?: string; result?: string; requestedBy: string; createdAt: string; updatedAt: string
}
export interface ExecutionItem { id: number; aiTaskId: number; step: string; status: string; detail?: string; startedAt: string }
export interface ApprovalItem {
  id: number; aiTaskId: number; kind: string; summary: string; payload?: string; status: 'PENDING' | 'APPROVED' | 'REJECTED'
  requestedBy: string; decidedBy?: string; decidedAt?: string; comment?: string; createdAt: string
}
export interface HistoryItem { id: number; actor: string; action: string; target?: string; detail?: string; at: string }
export interface WorkspaceSummary { projects: number; openTasks: number; doneTasks: number; pendingApprovals: number; queuedAiTasks: number; recent: HistoryItem[] }

export interface Avatar { username: string; displayName: string; team: string; x: number; z: number; r: number; m: boolean }
export interface MoveItem { u: string; x: number; z: number; r: number; m: boolean }
export type RoomType = 'OFFICE' | 'DIRECT' | 'AI'
export interface ChatRoom {
  id: number; type: RoomType; name?: string; peerUsername?: string; peerDisplayName?: string; peerTeam?: string; peerOnline: boolean; agentCode?: string
}
export interface ChatMessage {
  id: number; roomId: number; senderType: 'USER' | 'AI'; sender: string; senderName: string; content: string; createdAt: string
}
export interface Person { username: string; displayName: string; team: string; online: boolean }

export interface PmInfo { provider: string; real: boolean; model: string; note: string }
export interface PmState {
  phase: string; projectName: string; text: string; answers: string[]; questions: string[]; spec?: string
  requirements: { title: string; description: string; priority: Priority }[]
  tasks: { title: string; description: string; requirementIndex?: number }[]
  planSha256?: string; approvalId?: number; provider?: string; error?: string
}
export interface PmDetail { task: AiTaskItem; executions: ExecutionItem[]; state: PmState | null }

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message)
  }
}

export const tokenStore = {
  get: (): string | null => {
    try { return sessionStorage.getItem(TOKEN_KEY) } catch { return null }
  },
  set: (t: string | null) => {
    try { t ? sessionStorage.setItem(TOKEN_KEY, t) : sessionStorage.removeItem(TOKEN_KEY) } catch { /* ignore */ }
  },
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = tokenStore.get()
  let res: Response
  try {
    res = await fetch(API_URL + path, {
      ...init,
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...init.headers },
    })
  } catch {
    throw new ApiError(0, '백엔드 서버에 연결할 수 없습니다')
  }
  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    throw new ApiError(res.status, body.error ?? res.statusText)
  }
  // 202/204 처럼 본문이 없는 응답도 정상 처리한다
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

export const api = {
  login: (username: string, password: string) =>
    request<{ token: string; user: UserView }>('/api/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) }),
  me: () => request<UserView>('/api/auth/me'),
  devices: () => request<DeviceView[]>('/api/devices'),
  sendCommand: (deviceId: number, body: CommandBody) =>
    request<CommandView>(`/api/devices/${deviceId}/commands`, { method: 'POST', body: JSON.stringify(body) }),
  command: (id: string) => request<CommandView>(`/api/commands/${id}`),
  servers: () => request<ServerSnapshot>('/api/servers'),
  rooms: () => request<MeetingRoom[]>('/api/meeting-rooms'),
  reservations: () => request<Reservation[]>('/api/reservations'),
  reserve: (b: { roomId: number; title: string; startAt: string; endAt: string }) =>
    request<Reservation>('/api/reservations', { method: 'POST', body: JSON.stringify(b) }),
  cancelReservation: (id: number) => request<void>(`/api/reservations/${id}`, { method: 'DELETE' }),
  // 채팅
  chatPeople: () => request<Person[]>('/api/chat/people'),
  chatRooms: () => request<ChatRoom[]>('/api/chat/rooms'),
  directRoom: (username: string) => request<ChatRoom>('/api/chat/rooms/direct', { method: 'POST', body: JSON.stringify({ username }) }),
  aiRoom: (agentCode: string) => request<ChatRoom>('/api/chat/rooms/ai', { method: 'POST', body: JSON.stringify({ agentCode }) }),
  chatMessages: (roomId: number, limit = 50) => request<ChatMessage[]>(`/api/chat/rooms/${roomId}/messages?limit=${limit}`),
  sendChat: (roomId: number, content: string) =>
    request<ChatMessage>(`/api/chat/rooms/${roomId}/messages`, { method: 'POST', body: JSON.stringify({ content }) }),
  // AI PM
  pmInfo: () => request<PmInfo>('/api/pm/info'),
  pmIntake: (projectId: number, text: string) => request<AiTaskItem>('/api/pm/intake', { method: 'POST', body: JSON.stringify({ projectId, text }) }),
  pmDetail: (id: number) => request<PmDetail>(`/api/pm/tasks/${id}`),
  pmAnswer: (id: number, answers: string) => request<void>(`/api/pm/tasks/${id}/answers`, { method: 'POST', body: JSON.stringify({ answers }) }),
  // WORKSPACE
  summary: () => request<WorkspaceSummary>('/api/workspace/summary'),
  projects: () => request<Project[]>('/api/projects'),
  createProject: (b: { name: string; description?: string }) => request<Project>('/api/projects', { method: 'POST', body: JSON.stringify(b) }),
  updateProject: (id: number, b: { name: string; description?: string; status?: string }) => request<Project>(`/api/projects/${id}`, { method: 'PUT', body: JSON.stringify(b) }),
  deleteProject: (id: number) => request<void>(`/api/projects/${id}`, { method: 'DELETE' }),
  requirements: (pid: number) => request<RequirementItem[]>(`/api/projects/${pid}/requirements`),
  createRequirement: (pid: number, b: { title: string; description?: string; priority?: Priority }) =>
    request<RequirementItem>(`/api/projects/${pid}/requirements`, { method: 'POST', body: JSON.stringify(b) }),
  updateRequirement: (id: number, b: { title: string; description?: string; priority?: Priority; status?: RequirementStatus }) =>
    request<RequirementItem>(`/api/requirements/${id}`, { method: 'PUT', body: JSON.stringify(b) }),
  deleteRequirement: (id: number) => request<void>(`/api/requirements/${id}`, { method: 'DELETE' }),
  tasks: (pid: number) => request<TaskItem[]>(`/api/projects/${pid}/tasks`),
  createTask: (pid: number, b: { title: string; description?: string; requirementId?: number | null; assigneeAgentId?: number | null }) =>
    request<TaskItem>(`/api/projects/${pid}/tasks`, { method: 'POST', body: JSON.stringify(b) }),
  updateTask: (id: number, b: { title: string; description?: string; requirementId?: number | null; status?: TaskStatus; assigneeAgentId?: number | null }) =>
    request<TaskItem>(`/api/tasks/${id}`, { method: 'PUT', body: JSON.stringify(b) }),
  deleteTask: (id: number) => request<void>(`/api/tasks/${id}`, { method: 'DELETE' }),
  agents: () => request<Agent[]>('/api/agents'),
  aiTasks: (projectId?: number) => request<AiTaskItem[]>(`/api/ai-tasks${projectId ? `?projectId=${projectId}` : ''}`),
  createAiTask: (b: { projectId: number; taskId?: number | null; agentId: number; type: AiTaskType; environment: Environment; input?: string }) =>
    request<AiTaskItem>('/api/ai-tasks', { method: 'POST', body: JSON.stringify(b) }),
  executions: (id: number) => request<ExecutionItem[]>(`/api/ai-tasks/${id}/executions`),
  requestApproval: (aiTaskId: number, b: { kind: string; summary: string; payload?: string }) =>
    request<ApprovalItem>(`/api/ai-tasks/${aiTaskId}/approval-requests`, { method: 'POST', body: JSON.stringify(b) }),
  approvals: (status?: string) => request<ApprovalItem[]>(`/api/approvals${status ? `?status=${status}` : ''}`),
  decide: (id: number, approve: boolean, comment?: string) =>
    request<ApprovalItem>(`/api/approvals/${id}/decision`, { method: 'POST', body: JSON.stringify({ approve, comment }) }),
  history: (target: string) => request<HistoryItem[]>(`/api/history?target=${encodeURIComponent(target)}`),
  auditLogs: () => request<{ id: number; actor: string; action: string; target?: string; detail?: string; createdAt: string }[]>('/api/audit-logs?limit=30'),
}

export type ServerEvent =
  | { type: 'device.state'; payload: DeviceView }
  | { type: 'command.update'; payload: CommandView }
  | { type: 'reservation.changed'; payload: Reservation }
  | { type: 'workspace.changed'; payload: { kind: string; id: number; projectId: number } }
  | { type: 'presence.snapshot'; payload: Avatar[] }
  | { type: 'presence.join'; payload: Avatar }
  | { type: 'presence.move'; payload: Avatar }
  | { type: 'presence.moves'; payload: MoveItem[] }
  | { type: 'presence.leave'; payload: { username: string } }
  | { type: 'chat.message'; payload: ChatMessage }
  | { type: 'hello' }

let current: WebSocket | null = null

/** 현재 연결로 메시지를 보낸다. 연결되어 있지 않으면 false */
export function sendRealtime(msg: unknown): boolean {
  if (!current || current.readyState !== WebSocket.OPEN) return false
  current.send(JSON.stringify(msg))
  return true
}

/** 인증된 WebSocket 연결. 끊기면 지수 백오프로 재연결한다. 반환값은 연결 해제 함수. */
export function connectRealtime(
  token: string,
  onEvent: (e: ServerEvent) => void,
  onState: (s: 'connecting' | 'online' | 'offline' | 'replaced') => void,
): () => void {
  let ws: WebSocket | null = null
  let closed = false
  let retry = 0
  let timer: ReturnType<typeof setTimeout> | undefined

  const open = () => {
    onState('connecting')
    ws = new WebSocket(`${WS_URL}?token=${encodeURIComponent(token)}`)
    current = ws
    ws.onopen = () => { retry = 0; onState('online') }
    ws.onmessage = (m) => {
      try {
        const event = JSON.parse(m.data)
        if (event.type === 'meeting.changed') window.dispatchEvent(new Event('cen-meeting-changed'))
        if (event.type === 'notification.new') window.dispatchEvent(new Event('cen-notification'))
        onEvent(event)
      } catch { /* ignore malformed */ }
    }
    ws.onclose = (ev) => {
      if (current === ws) current = null
      // 같은 계정으로 다른 곳에서 접속하면 서버가 4001 로 이 연결을 종료한다 → 자동 재접속하지 않는다(서로 쫓아내기 방지)
      if (ev.code === 4001) { closed = true; onState('replaced'); return }
      onState('offline')
      if (!closed) timer = setTimeout(open, Math.min(10000, 500 * 2 ** retry++))
    }
    ws.onerror = () => ws?.close()
  }
  open()
  return () => {
    closed = true
    if (timer) clearTimeout(timer)
    ws?.close()
  }
}
