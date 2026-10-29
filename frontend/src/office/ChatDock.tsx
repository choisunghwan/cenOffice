import { useEffect, useRef, useState } from 'react'
import { useMultiplayer } from '../multiplayer'
import { useOffice } from '../store'
import { theme } from '../theme'

const MAX = 500
const time = (iso: string) => new Date(iso).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })

/** 3D OFFICE 우측 하단 채팅. 메시지는 모두 텍스트로만 렌더링한다(HTML 해석 없음 → XSS 방지). */
export function ChatDock() {
  const me = useOffice((s) => s.user?.username)
  const s = useMultiplayer()
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const end = useRef<HTMLDivElement>(null)

  const rooms = s.rooms
  const room = rooms.find((r) => r.id === s.activeRoomId)
  const list = (s.activeRoomId != null && s.messages[s.activeRoomId]) || []
  const totalUnread = Object.values(s.unread).reduce((a, b) => a + b, 0)
  const unreadOf = (type: string) => rooms.filter((r) => r.type === type).reduce((a, r) => a + (s.unread[r.id] ?? 0), 0)

  useEffect(() => { end.current?.scrollIntoView({ block: 'end' }) }, [list.length, s.open, s.activeRoomId])

  // 탭 전환 시 해당 탭의 대화방을 연다
  const pick = (tab: 'office' | 'direct' | 'ai') => {
    s.setTab(tab)
    if (tab === 'office') { const r = rooms.find((x) => x.type === 'OFFICE'); if (r) void s.openRoom(r.id) }
    if (tab === 'ai') void s.startAi('ari')
    if (tab === 'direct') { const r = rooms.find((x) => x.type === 'DIRECT' && x.id === s.activeRoomId) ?? rooms.find((x) => x.type === 'DIRECT'); if (r) void s.openRoom(r.id) }
  }

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    const t = text.trim()
    if (!t || busy) return
    setBusy(true)
    const ok = await s.send(t)
    setBusy(false)
    if (ok) setText('')
  }

  if (!s.open) {
    return (
      <button onClick={() => s.setOpen(true)} aria-label="채팅 열기"
        className="glass absolute right-4 bottom-4 z-10 rounded-full px-5 py-3 text-sm flex items-center gap-2 hover:bg-white/10 transition">
        💬 채팅
        {totalUnread > 0 && <span className="min-w-5 h-5 px-1.5 rounded-full bg-red-500 text-white text-[11px] grid place-items-center font-bold">{totalUnread}</span>}
      </button>
    )
  }

  const directRooms = rooms.filter((r) => r.type === 'DIRECT')
  const otherPeople = s.people.filter((p) => p.username !== me)

  return (
    <aside className="glass absolute right-4 bottom-4 z-10 w-[min(360px,calc(100vw-2rem))] h-[min(460px,60vh)] rounded-2xl flex flex-col shadow-2xl shadow-black/40" aria-label="채팅" onKeyDown={(e) => e.stopPropagation()}>
      <header className="flex items-center px-3 pt-2 gap-1">
        {([['office', '전체', unreadOf('OFFICE')], ['direct', '1:1', unreadOf('DIRECT')], ['ai', 'AI', unreadOf('AI')]] as const).map(([k, l, n]) => (
          <button key={k} onClick={() => pick(k)} className={`px-3 py-1.5 rounded-lg text-xs font-semibold relative ${s.tab === k ? 'bg-sky-500 text-white' : 'text-slate-300 hover:bg-white/10'}`}>
            {l}{n > 0 && <span className="ml-1 text-[10px] bg-red-500 text-white rounded-full px-1.5">{n}</span>}
          </button>
        ))}
        <span className="flex-1" />
        <button onClick={() => s.setOpen(false)} className="text-slate-400 hover:text-white px-2 text-sm" aria-label="채팅 닫기">✕</button>
      </header>

      {s.tab === 'direct' && (
        <div className="px-3 pt-2 flex gap-1.5 flex-wrap max-h-20 overflow-y-auto">
          {otherPeople.length === 0 && <span className="text-[11px] text-slate-500">대화할 직원이 없습니다</span>}
          {otherPeople.map((p) => {
            const dm = directRooms.find((r) => r.peerUsername === p.username)
            const active = dm && dm.id === s.activeRoomId
            return (
              <button key={p.username} onClick={() => void s.startDirect(p.username)}
                className={`px-2.5 py-1 rounded-full text-[11px] flex items-center gap-1.5 border ${active ? 'border-sky-400 bg-sky-500/20' : 'border-white/10 hover:bg-white/10'}`}>
                <span className="w-1.5 h-1.5 rounded-full" style={{ background: p.online ? theme.status.ok : theme.status.idle }} />
                {p.displayName}<span className="text-slate-400">{p.team}</span>
                {dm && (s.unread[dm.id] ?? 0) > 0 && <span className="bg-red-500 text-white rounded-full px-1.5 text-[10px]">{s.unread[dm.id]}</span>}
              </button>
            )
          })}
        </div>
      )}

      <div className="px-3 pt-2 text-[11px] text-slate-400 flex items-center gap-2">
        {room?.type === 'OFFICE' && '전체 사무실 채팅 — 접속한 모든 직원'}
        {room?.type === 'DIRECT' && <>1:1 · {room.peerDisplayName} <span className="text-slate-500">{room.peerTeam}</span></>}
        {room?.type === 'AI' && <><span className="px-1.5 rounded bg-violet-500/80 text-white text-[9px] font-bold">AI</span>{room.name} <span className="text-amber-300">Mock · LLM 미연결</span></>}
        {!room && s.tab === 'direct' && '위에서 대화할 직원을 선택하세요'}
      </div>

      <div className="flex-1 min-h-0 overflow-y-auto px-3 py-2 space-y-2" role="log" aria-live="polite">
        {list.length === 0 && room && <p className="text-xs text-slate-500 text-center pt-6">아직 메시지가 없습니다</p>}
        {list.map((m) => {
          const mine = m.sender === me && m.senderType === 'USER'
          return (
            <div key={m.id} className={`flex flex-col ${mine ? 'items-end' : 'items-start'}`}>
              <div className="text-[10px] text-slate-400 mb-0.5 flex items-center gap-1">
                {m.senderType === 'AI' && <span className="px-1 rounded bg-violet-500/80 text-white font-bold">AI</span>}
                {m.senderName} · {time(m.createdAt)}
              </div>
              {/* 텍스트 노드로만 출력 (dangerouslySetInnerHTML 사용 금지) */}
              <div className={`max-w-[85%] px-3 py-1.5 rounded-2xl text-sm whitespace-pre-wrap break-words ${mine ? 'bg-sky-500 text-white' : m.senderType === 'AI' ? 'bg-violet-500/25 text-slate-100' : 'bg-slate-700/80 text-slate-100'}`}>
                {m.content}
              </div>
            </div>
          )
        })}
        <div ref={end} />
      </div>

      {s.error && <p className="mx-3 mb-1 text-[11px] rounded-lg px-2 py-1" style={{ background: `${theme.status.error}22`, color: theme.status.error }}>{s.error}</p>}
      <form onSubmit={submit} className="p-3 pt-1 flex gap-2">
        <input value={text} onChange={(e) => setText(e.target.value)} maxLength={MAX} disabled={!room}
          placeholder={room ? '메시지 입력…' : '대화를 선택하세요'} aria-label="메시지 입력"
          className="flex-1 min-w-0 bg-slate-900/70 border border-white/10 rounded-xl px-3 py-2 text-sm outline-none focus:border-sky-400 disabled:opacity-50" />
        <button disabled={!room || busy || !text.trim()} className="px-3 rounded-xl bg-sky-500 text-white text-sm font-semibold disabled:opacity-40">전송</button>
      </form>
      {text.length > MAX - 60 && <div className="px-4 pb-2 -mt-1 text-[10px] text-slate-400 text-right">{text.length}/{MAX}</div>}
    </aside>
  )
}
