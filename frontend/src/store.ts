import { create } from 'zustand'
import { INTERACTABLES, type InteractableDef } from './game/layout'
import { clearAssistantMemory } from './home/assistantMemory'
import { localPose,startPresenceSender, stopPresenceSender, useMultiplayer } from './multiplayer'
import {
  api, ApiError, connectRealtime, tokenStore,
  type CommandBody, type CommandStatus, type CommandView, type DeviceView, type ServerEvent, type UserView,
} from './api'

export type Panel = 'none' | 'light' | 'ai' | 'ac' | 'server' | 'meeting' | 'info'
export type AiStatus = 'idle' | 'working' | 'done' | 'error' | 'approval'
/** local: 백엔드 없이 브라우저 안에서만 동작 / online: 인증 + WebSocket 연결됨 */
export type Mode = 'workspace' | 'office'
export type Conn = 'local' | 'connecting' | 'online' | 'offline' | 'replaced'

export interface ChatMessage {
  id: number
  role: 'user' | 'ai' | 'system'
  text: string
  tool?: string
  pending?: { kind: 'light'; on: boolean; state: 'pending' | 'approved' | 'rejected' }
}

export type ControlResult = CommandStatus | 'LOCAL' | 'ERROR'

interface OfficeState {
  mode: Mode
  setMode: (m: Mode) => void
  /** WORKSPACE 의 현재 메뉴 (3D 의 AI LAB 터미널에서도 'aihub' 로 이동) */
  workspacePage: string
  setWorkspacePage: (p: string) => void
  lightOn: boolean
  panel: Panel
  nearby: InteractableDef | null
  infoTarget: InteractableDef | null
  aiStatus: AiStatus
  /** 서버(AI PM)의 실제 작업 상태. 온라인일 때 아리 이름표/상태에 우선 사용 */
  serverAiStatus: AiStatus | null
  messages: ChatMessage[]
  // 백엔드 세션
  user: UserView | null
  conn: Conn
  authChecked: boolean
  devices: Record<string, DeviceView>
  commands: Record<string, CommandView>

  setLight: (on: boolean) => void
  setNearby: (n: InteractableDef | null) => void
  open: (id: string) => void
  close: () => void
  setAiStatus: (s: AiStatus) => void
  addMessage: (m: Omit<ChatMessage, 'id'>) => number
  patchMessage: (id: number, patch: Partial<ChatMessage>) => void

  bootstrap: () => Promise<void>
  login: (username: string, password: string) => Promise<void>
  signup: (b: { username: string; displayName: string; email: string; password: string }) => Promise<void>
  logout: () => void
  continueLocal: () => void
  /** 장비 제어. 온라인이면 명령을 백엔드에 보내고 "장비가 확인한 결과"를 기다린다. */
  control: (code: string, body: CommandBody) => Promise<ControlResult>
}

let msgSeq = 1
let disconnect: (() => void) | null = null
const waiters = new Map<string, (s: CommandStatus) => void>()
const TERMINAL: CommandStatus[] = ['CONFIRMED', 'FAILED', 'TIMEOUT']

export const useOffice = create<OfficeState>((set, get) => {
  const applyDevice = (d: DeviceView) => {
    set((s) => ({
      devices: { ...s.devices, [d.code]: d },
      ...(d.code === 'light-main' ? { lightOn: d.state.power } : {}),
    }))
  }

  const handleEvent = (e: ServerEvent) => {
    if (e.type.startsWith('presence.') || e.type === 'chat.message') useMultiplayer.getState().handle(e)
    else if (e.type === 'device.state') applyDevice(e.payload)
    else if (e.type === 'command.update') {
      set((s) => ({ commands: { ...s.commands, [e.payload.id]: e.payload } }))
      if (TERMINAL.includes(e.payload.status)) waiters.get(e.payload.id)?.(e.payload.status)
    } else if (e.type === 'workspace.changed') {
      if (e.payload.kind === 'aitask' || e.payload.kind === 'approval') void refreshAiStatus()
      window.dispatchEvent(new CustomEvent('workspace-changed', { detail: e.payload }))
    } else if (e.type === 'reservation.changed') {
      window.dispatchEvent(new CustomEvent('reservations-changed'))
    }
  }

  /** 서버의 AI PM 작업 상태로 아리의 상태를 계산한다 (완료되지 않은 작업을 완료로 표시하지 않는다) */
  const refreshAiStatus = async () => {
    try {
      const list = (await api.aiTasks()).filter((t) => t.type === 'DRAFT_SPEC')
      let st: AiStatus = 'idle'
      if (list.some((t) => t.status === 'RUNNING' || t.status === 'QUEUED')) st = 'working'
      else if (list.some((t) => t.status === 'NEEDS_INPUT' || t.status === 'WAITING_APPROVAL')) st = 'approval'
      else if (list[0]?.status === 'FAILED') st = 'error'
      set({ serverAiStatus: st })
    } catch { /* 다음 이벤트에서 갱신 */ }
  }

  const goOnline = async (token: string) => {
    disconnect?.()
    set({ conn: 'connecting' })
    const list = await api.devices()
    list.forEach(applyDevice)
    void refreshAiStatus()
    useMultiplayer.getState().setMe(get().user?.username ?? '')
    disconnect = connectRealtime(token, handleEvent, (c) => {
      set({ conn: c })
      if (c === 'online') { startPresenceSender(); void useMultiplayer.getState().init() }
      else { stopPresenceSender(); useMultiplayer.getState().reset() }
      // 재연결 시 놓친 상태 복구
      if (c === 'online') api.devices().then((l) => l.forEach(applyDevice)).catch(() => undefined)
    })
  }

  const waitTerminal = (cmd: CommandView): Promise<CommandStatus> =>
    new Promise((resolve) => {
      if (TERMINAL.includes(cmd.status)) return resolve(cmd.status)
      let done = false
      const finish = (s: CommandStatus) => {
        if (done) return
        done = true
        waiters.delete(cmd.id)
        clearInterval(poll)
        resolve(s)
      }
      waiters.set(cmd.id, finish)
      // WebSocket 이벤트를 놓쳐도 결과를 확인하도록 폴링 보조
      const started = Date.now()
      const poll = setInterval(async () => {
        try {
          const c = await api.command(cmd.id)
          set((s) => ({ commands: { ...s.commands, [c.id]: c } }))
          if (TERMINAL.includes(c.status)) finish(c.status)
        } catch { /* 다음 주기에 재시도 */ }
        if (Date.now() - started > 30000) finish('TIMEOUT')
      }, 2000)
    })

  return {
    mode: 'workspace',
    setMode: (mode) => set({ mode, panel: 'none' }),
    workspacePage: 'dashboard',
    setWorkspacePage: (workspacePage) => set({ workspacePage }),
    lightOn: true,
    panel: 'none',
    nearby: null,
    infoTarget: null,
    aiStatus: 'idle',
    serverAiStatus: null,
    messages: [
      { id: 0, role: 'ai', text: '안녕하세요, AI 직원 아리입니다. 서버 상태 확인, 문서 요약, 보고서 작성, 코드 분석, 조명 제어 등을 도와드릴게요. (현재 Mock AI 모드)' },
    ],
    user: null,
    conn: 'local',
    authChecked: false,
    devices: {},
    commands: {},

    setLight: (on) => set({ lightOn: on }),
    setNearby: (n) => set({ nearby: n }),
    open: (id) => {
      const def = INTERACTABLES.find((i) => i.id === id)
      if (!def) return
      // AI LAB 터미널: 기존 2D WORKSPACE 의 AI HUB 화면을 연다 (같은 데이터/API 사용)
      if (def.kind === 'hub') { set({ mode: 'workspace', workspacePage: 'aihub', panel: 'none' }); return }
      if (def.kind === 'info') set({ panel: 'info', infoTarget: def })
      else set({ panel: def.kind })
      if (document.pointerLockElement) document.exitPointerLock()
    },
    close: () => set({ panel: 'none' }),
    setAiStatus: (aiStatus) => set({ aiStatus }),
    addMessage: (m) => {
      const id = msgSeq++
      set({ messages: [...get().messages, { ...m, id }] })
      return id
    },
    patchMessage: (id, patch) => set({ messages: get().messages.map((m) => (m.id === id ? { ...m, ...patch } : m)) }),

    bootstrap: async () => {
      const token = tokenStore.get()
      if (!token) return set({ authChecked: true })
      try {
        const user = await api.me()
        set({ user })
        await goOnline(token)
      } catch (e) {
        if (!(e instanceof ApiError) || e.status === 401) tokenStore.set(null)
        set({ user: null, conn: 'local' })
      }
      set({ authChecked: true })
    },

    login: async (username, password) => {
      const res = await api.login(username, password)
      tokenStore.set(res.token)
      set({ user: res.user })
      await goOnline(res.token)
    },

    signup: async (b) => {
      const res = await api.signup(b)
      tokenStore.set(res.token)
      set({ user: res.user })
      await goOnline(res.token)
    },

    logout: () => {
      disconnect?.()
      disconnect = null
      stopPresenceSender()
      useMultiplayer.getState().reset()
      tokenStore.set(null)
      clearAssistantMemory()
      set({ user: null, conn: 'local', devices: {}, commands: {}, serverAiStatus: null, authChecked: true })
    },

    continueLocal: () => set({ authChecked: true, conn: 'local' }),

    control: async (code, body) => {
      const s = get()
      const dev = s.devices[code]
      if (s.conn !== 'online' || !dev) {
        // 로컬 시뮬레이션: 조명만 지원
        if (code === 'light-main' && body.power !== undefined) {
          set({ lightOn: body.power })
          return 'LOCAL'
        }
        return 'ERROR'
      }
      try {
        const cmd = await api.sendCommand(dev.id, body)
        set((st) => ({ commands: { ...st.commands, [cmd.id]: cmd } }))
        return await waitTerminal(cmd)
      } catch {
        return 'ERROR'
      }
    },
  }
})

if (import.meta.env.DEV) {
  const w = window as unknown as { __office: typeof useOffice; __mp: typeof useMultiplayer }
  w.__office = useOffice
  w.__mp = useMultiplayer
  ;(w as unknown as { __pose: typeof localPose }).__pose = localPose
}
