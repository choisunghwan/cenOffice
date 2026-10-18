import { create } from 'zustand'
import { api, ApiError, sendRealtime, type Avatar, type ChatMessage, type ChatRoom, type MoveItem, type Person, type ServerEvent } from './api'

/** 내 캐릭터의 현재 자세. Player 가 매 프레임 갱신하고, 전송 루프가 변경분만 서버로 보낸다. */
export const localPose = { x: -7, z: 5.2, r: Math.PI, m: false }

export type Remote = Avatar
export interface Bubble { text: string; id: number }

const BUBBLE_MS = 6000
const BUBBLE_MAX = 80
let bubbleSeq = 1
let me = ''

interface MultiplayerState {
  avatars: Record<string, Remote>
  bubbles: Record<string, Bubble>
  rooms: ChatRoom[]
  people: Person[]
  messages: Record<number, ChatMessage[]>
  unread: Record<number, number>
  activeRoomId: number | null
  open: boolean
  tab: 'office' | 'direct' | 'ai'
  error: string | null

  setMe: (username: string) => void
  reset: () => void
  handle: (e: ServerEvent) => void
  setOpen: (o: boolean) => void
  setTab: (t: 'office' | 'direct' | 'ai') => void
  init: () => Promise<void>
  openRoom: (id: number) => Promise<void>
  startDirect: (username: string) => Promise<void>
  startAi: (code: string) => Promise<void>
  send: (text: string) => Promise<boolean>
  showBubble: (key: string, text: string) => void
}

const clip = (s: string) => {
  const t = s.replace(/\s+/g, ' ').trim()
  return t.length > BUBBLE_MAX ? t.slice(0, BUBBLE_MAX - 1) + '…' : t
}

export const useMultiplayer = create<MultiplayerState>((set, get) => ({
  avatars: {},
  bubbles: {},
  rooms: [],
  people: [],
  messages: {},
  unread: {},
  activeRoomId: null,
  open: false,
  tab: 'office',
  error: null,

  setMe: (u) => { me = u },
  reset: () => set({ avatars: {}, bubbles: {}, rooms: [], people: [], messages: {}, unread: {}, activeRoomId: null, open: false, error: null }),

  showBubble: (key, text) => {
    const id = bubbleSeq++
    set((s) => ({ bubbles: { ...s.bubbles, [key]: { text: clip(text), id } } }))
    // 일정 시간이 지나면 자동으로 사라진다 (그 사이 새 말풍선이 생겼으면 건드리지 않음)
    setTimeout(() => {
      const b = get().bubbles[key]
      if (b && b.id === id) set((s) => { const next = { ...s.bubbles }; delete next[key]; return { bubbles: next } })
    }, BUBBLE_MS)
  },

  handle: (e) => {
    switch (e.type) {
      case 'presence.snapshot': {
        const avatars: Record<string, Remote> = {}
        e.payload.forEach((a) => { if (a.username !== me) avatars[a.username] = a })
        set({ avatars })
        break
      }
      case 'presence.join':
      case 'presence.move':
        if (e.payload.username !== me) set((s) => ({ avatars: { ...s.avatars, [e.payload.username]: e.payload } }))
        break
      case 'presence.moves': {
        const moves = e.payload as MoveItem[]
        set((s) => {
          const next = { ...s.avatars }
          for (const m of moves) {
            const a = next[m.u]
            if (a && m.u !== me) next[m.u] = { ...a, x: m.x, z: m.z, r: m.r, m: m.m }
          }
          return { avatars: next }
        })
        break
      }
      case 'presence.leave':
        set((s) => {
          const avatars = { ...s.avatars }
          delete avatars[e.payload.username]
          const bubbles = { ...s.bubbles }
          delete bubbles[e.payload.username]
          return { avatars, bubbles }
        })
        break
      case 'chat.message': {
        const m = e.payload
        const s = get()
        const list = s.messages[m.roomId]
        if (list && !list.some((x) => x.id === m.id)) set({ messages: { ...s.messages, [m.roomId]: [...list, m] } })
        const viewing = s.open && s.activeRoomId === m.roomId
        if (!viewing && m.sender !== me) set((st) => ({ unread: { ...st.unread, [m.roomId]: (st.unread[m.roomId] ?? 0) + 1 } }))
        // 방에 없던 새 대화(상대가 먼저 건 1:1)면 목록을 갱신
        if (!s.rooms.some((r) => r.id === m.roomId)) void api.chatRooms().then((rooms) => set({ rooms })).catch(() => undefined)
        get().showBubble(m.senderType === 'AI' ? `ai:${m.sender}` : m.sender, m.content)
        break
      }
    }
  },

  setOpen: (open) => {
    set({ open })
    const id = get().activeRoomId
    if (open && id != null) set((s) => ({ unread: { ...s.unread, [id]: 0 } }))
  },
  setTab: (tab) => set({ tab }),

  init: async () => {
    try {
      const [rooms, people] = await Promise.all([api.chatRooms(), api.chatPeople()])
      set({ rooms, people })
      const office = rooms.find((r) => r.type === 'OFFICE')
      if (office && get().activeRoomId == null) await get().openRoom(office.id)
    } catch (x) { set({ error: x instanceof ApiError ? x.message : '채팅을 불러오지 못했습니다' }) }
  },

  openRoom: async (id) => {
    set((s) => ({ activeRoomId: id, unread: { ...s.unread, [id]: 0 }, error: null }))
    try {
      const msgs = await api.chatMessages(id)
      set((s) => ({ messages: { ...s.messages, [id]: msgs } }))
    } catch (x) { set({ error: x instanceof ApiError ? x.message : '대화를 불러오지 못했습니다' }) }
  },

  startDirect: async (username) => {
    try {
      const room = await api.directRoom(username)
      set((s) => ({ rooms: s.rooms.some((r) => r.id === room.id) ? s.rooms : [...s.rooms, room] }))
      await get().openRoom(room.id)
    } catch (x) { set({ error: x instanceof ApiError ? x.message : '대화를 시작하지 못했습니다' }) }
  },

  startAi: async (code) => {
    try {
      const room = await api.aiRoom(code)
      set((s) => ({ rooms: s.rooms.some((r) => r.id === room.id) ? s.rooms : [...s.rooms, room] }))
      await get().openRoom(room.id)
    } catch (x) { set({ error: x instanceof ApiError ? x.message : 'AI 대화를 시작하지 못했습니다' }) }
  },

  send: async (text) => {
    const id = get().activeRoomId
    if (id == null) return false
    try {
      await api.sendChat(id, text)
      set({ error: null })
      return true // 화면 반영은 서버가 보내는 chat.message 이벤트로 (저장 성공 후에만 표시)
    } catch (x) {
      set({ error: x instanceof ApiError ? x.message : '전송에 실패했습니다' })
      return false
    }
  },
}))

// ───────── 내 위치 전송 루프 ─────────
let timer: ReturnType<typeof setInterval> | null = null
let last = { x: NaN, z: NaN, r: NaN, m: false }
let lastSentAt = 0

export function startPresenceSender() {
  stopPresenceSender()
  last = { x: NaN, z: NaN, r: NaN, m: false }
  timer = setInterval(() => {
    const p = localPose
    const now = Date.now()
    const changed = Math.hypot(p.x - last.x, p.z - last.z) > 0.03 || Math.abs(p.r - last.r) > 0.05 || p.m !== last.m || Number.isNaN(last.x)
    if (changed) {
      if (sendRealtime({ type: 'pos', x: round(p.x), z: round(p.z), r: round(p.r), m: p.m })) { last = { ...p }; lastSentAt = now }
    } else if (now - lastSentAt > 4000) {
      if (sendRealtime({ type: 'hb' })) lastSentAt = now // 변화가 없어도 4초마다 생존 신호
    }
  }, 100)
}

export function stopPresenceSender() {
  if (timer) clearInterval(timer)
  timer = null
}

const round = (v: number) => Math.round(v * 100) / 100
