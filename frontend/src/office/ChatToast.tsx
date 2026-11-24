import { useEffect } from 'react'
import { useMultiplayer } from '../multiplayer'
import { useOffice } from '../store'

const SHOW_MS = 6000

/**
 * 새 채팅 알림. 채팅창은 3D 오피스 안에만 있으므로, 업무 화면에 있거나 다른 패널이 채팅창을 가릴 때
 * 새 메시지가 오면 화면 위쪽에 잠깐 띄우고, 누르면 3D 오피스의 그 대화로 바로 데려간다.
 * 메시지는 텍스트로만 출력한다(HTML 해석 없음).
 */
export function ChatToast() {
  const m = useMultiplayer((s) => s.incoming)
  const room = useMultiplayer((s) => (m ? s.rooms.find((r) => r.id === m.roomId) : undefined))

  useEffect(() => {
    if (!m) return
    const t = setTimeout(() => { if (useMultiplayer.getState().incoming === m) useMultiplayer.getState().dismissIncoming() }, SHOW_MS)
    return () => clearTimeout(t)
  }, [m])

  if (!m) return null

  const go = () => {
    const office = useOffice.getState()
    office.setMode('office') // 3D 오피스로 바꾸면서 열려 있던 패널도 닫힌다
    const chat = useMultiplayer.getState()
    chat.setTab(room?.type === 'DIRECT' ? 'direct' : room?.type === 'AI' ? 'ai' : 'office')
    void chat.openRoom(m.roomId)
    chat.setOpen(true)
  }
  const where = room?.type === 'DIRECT' ? '1:1' : room?.type === 'AI' ? 'AI' : '전체'

  return (
    <div className="glass slide-in absolute z-30 top-[68px] right-3 left-3 sm:left-auto sm:right-4 sm:top-20 sm:w-[340px] rounded-xl shadow-2xl shadow-black/40 flex items-stretch" role="status" aria-live="polite">
      <button type="button" onClick={go} className="flex-1 min-w-0 text-left px-4 py-3 rounded-l-xl hover:bg-white/10" aria-label={`${m.senderName}의 새 채팅 메시지 보기`}>
        <span className="flex items-center gap-2 text-[11px] text-sky-300 font-semibold">💬 새 채팅 · {where}<span className="text-slate-400 font-normal">눌러서 보기</span></span>
        <span className="mt-1 block text-sm text-slate-100 truncate"><b className="font-semibold">{m.senderName}</b> {m.content}</span>
      </button>
      <button type="button" onClick={() => useMultiplayer.getState().dismissIncoming()} aria-label="알림 닫기" className="w-11 shrink-0 grid place-items-center text-slate-400 hover:text-white rounded-r-xl hover:bg-white/10">✕</button>
    </div>
  )
}
