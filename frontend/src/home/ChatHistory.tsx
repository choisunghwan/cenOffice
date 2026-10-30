import { useEffect } from 'react'
import { useOffice } from '../store'
import { askConfirm } from '../common/confirm'
import { useChat } from './chatStore'

/** 사이드바의 채팅방 목록: 새 채팅 + 지난 대화. 목록이 길면 이 영역만 스크롤된다. */
export function ChatHistory({ collapsed }: { collapsed: boolean }) {
  const sessions = useChat((s) => s.sessions)
  const currentId = useChat((s) => s.currentId)
  const page = useOffice((s) => s.workspacePage)
  const owner = useOffice((s) => s.user?.username)
  useEffect(() => { if (owner) void useChat.getState().loadList() }, [owner])

  if (collapsed) {
    return <div className="mt-3 border-t border-white/8 pt-3"><button type="button" className="chat-new icon" onClick={() => useChat.getState().startNew()} title="새 채팅" aria-label="새 채팅">＋</button></div>
  }
  return (
    <div className="chat-history">
      <button type="button" className="chat-new" onClick={() => useChat.getState().startNew()}><span aria-hidden="true">＋</span> 새 채팅</button>
      <div className="chat-history-head">
        <p className="chat-history-title">대화 기록</p>
        <button type="button" className="chat-clear" disabled={!sessions.length} title={sessions.length ? '대화 기록 전체 삭제' : '지울 대화가 없습니다'}
          onClick={() => { void askConfirm({ title: '대화 기록을 모두 삭제할까요?', message: `대화 ${sessions.length}개가 모두 사라지며 되돌릴 수 없습니다.`, okText: '모두 삭제', danger: true }).then((ok) => { if (ok) void useChat.getState().removeAll() }) }}>전체 삭제</button>
      </div>
      <ul>
        {sessions.map((s) => (
          <li key={s.id} className={s.id === currentId && page === 'dashboard' ? 'on' : ''}>
            <button type="button" className="chat-item" onClick={() => void useChat.getState().open(s.id)} title={s.title}>{s.title}</button>
            <button type="button" className="chat-del" aria-label={`‘${s.title}’ 대화 삭제`} title="삭제" onClick={() => { void askConfirm({ title: '대화를 삭제할까요?', message: `‘${s.title}’ 대화가 사라지며 되돌릴 수 없습니다.`, okText: '삭제', danger: true }).then((ok) => { if (ok) void useChat.getState().remove(s.id) }) }}>✕</button>
          </li>
        ))}
        {!sessions.length && <li className="chat-empty">아직 대화가 없습니다.</li>}
      </ul>
    </div>
  )
}
