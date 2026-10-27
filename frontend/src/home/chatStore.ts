import { create } from 'zustand'
import { useOffice } from '../store'
import { assistantApi, type ChatSummary } from './assistantApi'
import { assistantMemory, clearAssistantMemory } from './assistantMemory'

/**
 * 홈 입력창의 채팅방(대화 세션) 목록과 지금 열린 방. 대화 내용은 서버에 저장되고, 화면이 닫혀 있어도 저장은 이어진다.
 * rev 는 "열린 방이 바뀌었으니 화면은 메모리의 대화를 다시 읽어라"는 신호다.
 */
interface ChatState {
  sessions: ChatSummary[]
  currentId: string | null
  rev: number
  loadList: () => Promise<void>
  /** 첫 말을 보낼 때 방이 없으면 만든다(제목은 첫 말). 이 말이 속한 방의 id 를 돌려준다. */
  ensureSession: (firstText: string) => Promise<string>
  /** 지금 열려 있지 않은 방에 뒤늦게 도착한 대화를 그 방에 이어 붙인다(답을 기다리는 사이 다른 방으로 옮긴 경우) */
  appendTurns: (id: string, f: (cur: unknown[]) => unknown[]) => Promise<void>
  /** 대화가 바뀔 때마다 부른다. 잠깐 모아서 서버에 저장한다. */
  saveSoon: () => void
  startNew: () => void
  open: (id: string) => Promise<void>
  remove: (id: string) => Promise<void>
}

let timer: ReturnType<typeof setTimeout> | undefined
let pendingId: string | null = null

function goHome() {
  useOffice.getState().setWorkspacePage('dashboard')
  window.dispatchEvent(new Event('hub-navigation'))
}

export const useChat = create<ChatState>((set, get) => {
  async function doSave() {
    timer = undefined
    const id = pendingId
    pendingId = null
    if (!id) return
    const turns = assistantMemory.turns // 방을 바꾸기 전에 동기적으로 읽는다
    try {
      await assistantApi.sessions.save(id, { turns })
      set((s) => ({ sessions: [...s.sessions.filter((x) => x.id === id).map((x) => ({ ...x, updatedAt: new Date().toISOString() })), ...s.sessions.filter((x) => x.id !== id)] }))
    } catch { /* 저장이 안 돼도 대화는 계속 쓸 수 있다. 다음 변경 때 다시 저장한다. */ }
  }
  const flush = () => { if (timer) { clearTimeout(timer); return doSave() } return Promise.resolve() }
  const swap = (id: string | null, turns: unknown[], nextId: number) => {
    assistantMemory.turns = turns
    assistantMemory.nextId = nextId
    assistantMemory.previous = null
    set((s) => ({ currentId: id, rev: s.rev + 1 }))
  }

  return {
    sessions: [],
    currentId: null,
    rev: 0,
    loadList: async () => { try { set({ sessions: await assistantApi.sessions.list() }) } catch { /* 목록은 보조 정보 */ } },
    ensureSession: async (firstText) => {
      const cur = get().currentId
      if (cur) return cur
      const rev0 = get().rev
      const s = await assistantApi.sessions.create(firstText.replace(/\s+/g, ' ').trim().slice(0, 30))
      // 만드는 사이에 사용자가 다른 방으로 옮겼다면 그 방을 열어 두지 않는다(방은 목록에만 추가)
      set((st) => ({ currentId: st.rev === rev0 ? s.id : st.currentId, sessions: [s, ...st.sessions] }))
      return s.id
    },
    appendTurns: async (id, f) => {
      try {
        const d = await assistantApi.sessions.get(id)
        await assistantApi.sessions.save(id, { turns: f(Array.isArray(d.turns) ? d.turns : []) })
        await get().loadList()
      } catch { /* 저장하지 못해도 다른 방의 대화에는 영향이 없다 */ }
    },
    saveSoon: () => {
      const id = get().currentId
      if (!id) return
      pendingId = id
      if (timer) clearTimeout(timer)
      timer = setTimeout(() => { void doSave() }, 700)
    },
    startNew: () => {
      void flush()
      swap(null, [], 1)
      goHome()
    },
    open: async (id) => {
      await flush()
      try {
        const d = await assistantApi.sessions.get(id)
        const turns = Array.isArray(d.turns) ? d.turns : []
        const maxId = turns.reduce<number>((m, t) => Math.max(m, Number((t as { id?: number }).id) || 0), 0)
        swap(d.id, turns, maxId + 1)
        goHome()
      } catch {
        await get().loadList() // 지워졌거나 열 수 없는 방이면 목록만 새로 읽는다
      }
    },
    remove: async (id) => {
      try { await assistantApi.sessions.remove(id) } catch { return }
      set((s) => ({ sessions: s.sessions.filter((x) => x.id !== id) }))
      if (get().currentId === id) { pendingId = null; if (timer) { clearTimeout(timer); timer = undefined }; swap(null, [], 1) }
    },
  }
})

// 다른 사용자로 로그인하거나 로그아웃하면 방 목록과 열린 방을 비운다
useOffice.subscribe((s, prev) => {
  if (s.user?.username === prev.user?.username) return
  if (timer) { clearTimeout(timer); timer = undefined }
  pendingId = null
  clearAssistantMemory()
  useChat.setState({ sessions: [], currentId: null, rev: useChat.getState().rev + 1 })
})
