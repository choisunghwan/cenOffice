import { useCallback, useEffect, useState } from 'react'
import { useOffice } from '../store'
import { minutesApi, type NotificationItem } from './minutesApi'
import { EmailSettings } from './EmailSettings'
import './minutes.css'

/** 헤더의 시스템 알림. 새 알림은 WebSocket 이벤트(cen-notification)로 즉시 갱신하고, 연결이 끊겨도 30초마다 확인한다. */
export function NotificationBell() {
  const [items, setItems] = useState<NotificationItem[]>([])
  const [unread, setUnread] = useState(0)
  const [open, setOpen] = useState(false)
  const reload = useCallback(() => { minutesApi.notifications().then((p) => { setItems(p.items); setUnread(p.unread) }).catch(() => undefined) }, [])
  useEffect(() => {
    reload()
    const t = setInterval(reload, 30000)
    window.addEventListener('cen-notification', reload)
    return () => { clearInterval(t); window.removeEventListener('cen-notification', reload) }
  }, [reload])

  const go = (n: NotificationItem) => {
    void minutesApi.markRead(n.id).then(reload).catch(() => undefined)
    if (n.link?.startsWith('meeting:')) {
      sessionStorage.setItem('cenOpenMinutes', n.link.slice(8))
      const s = useOffice.getState()
      s.setMode('workspace'); s.setWorkspacePage('meetings')
      window.dispatchEvent(new Event('cen-open-minutes'))
    }
    setOpen(false)
  }

  return <span className="notif-wrap">
    <button className="notif-bell" aria-label={`알림 ${unread}건`} aria-expanded={open} onClick={() => { setOpen((v) => !v); reload() }}>🔔{unread > 0 && <span className="notif-badge">{unread > 9 ? '9+' : unread}</span>}</button>
    {open && <div className="notif-panel" role="dialog" aria-label="알림">
      <header><span>알림</span>{unread > 0 && <button className="minutes-link" onClick={() => void minutesApi.markAllRead().then(reload)}>모두 읽음</button>}</header>
      {!items.length ? <p style={{ padding: '14px 10px', fontSize: 12.5, color: '#6b7d91' }}>새 알림이 없습니다.</p>
        : items.map((n) => <button key={n.id} className={`notif-item ${n.read ? '' : 'unread'}`} onClick={() => go(n)}><b>{n.title}</b>{n.body && <span>{n.body}</span>}<span>{new Date(n.createdAt).toLocaleString('ko-KR', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</span></button>)}
      <EmailSettings />
    </div>}
  </span>
}
