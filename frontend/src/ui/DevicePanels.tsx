import { useCallback, useEffect, useMemo, useState } from 'react'
import { api, ApiError, type MeetingRoom, type Reservation, type ServerSnapshot } from '../api'
import { useOffice } from '../store'
import { theme } from '../theme'
import { CommandBadge, Dot, Notice, Row, latestCommand, resultMessage } from './common'
import { Shell } from './Panels'

const OFFLINE_NOTE = '이 기능은 백엔드 연결이 필요합니다. 로그인하면 사용할 수 있습니다.'

export function AcPanel() {
  const close = useOffice((s) => s.close)
  const conn = useOffice((s) => s.conn)
  const dev = useOffice((s) => s.devices['ac-main'])
  const commands = useOffice((s) => s.commands)
  const control = useOffice((s) => s.control)
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const cmd = latestCommand(commands, 'ac-main')
  const open = cmd?.status === 'REQUESTED' || cmd?.status === 'SENT'
  const st = dev?.state

  const send = async (body: Parameters<typeof control>[1]) => {
    setErr(null)
    setBusy(true)
    const r = await control('ac-main', body)
    setBusy(false)
    setErr(resultMessage(r))
  }
  const disabled = conn !== 'online' || !dev || busy || open

  return (
    <Shell title="❄️ 에어컨 제어" onClose={close}>
      <div className="p-5 space-y-5">
        {conn !== 'online' && <Notice>{OFFLINE_NOTE}</Notice>}
        <div className="text-center">
          <div className="text-xs text-slate-400">현재 실내 온도 (장비 보고값)</div>
          <div className="text-5xl font-semibold mt-1">{st?.currentTemp != null ? st.currentTemp.toFixed(1) : '--'}<span className="text-xl text-slate-400">°C</span></div>
        </div>
        <button
          disabled={disabled}
          onClick={() => send({ power: !st?.power })}
          className={`w-full py-3 rounded-xl font-semibold transition disabled:opacity-50 ${st?.power ? 'bg-sky-500 text-white' : 'bg-slate-700 text-slate-200'}`}
        >
          전원 {st?.power ? 'ON' : 'OFF'}
        </button>
        <div>
          <div className="text-xs text-slate-400 mb-2">목표 온도</div>
          <div className="flex items-center justify-center gap-5">
            <button disabled={disabled || (st?.targetTemp ?? 24) <= 16} onClick={() => send({ targetTemp: (st?.targetTemp ?? 24) - 1 })} className="w-11 h-11 rounded-full bg-slate-700 text-xl disabled:opacity-40">−</button>
            <span className="text-3xl font-semibold w-24 text-center">{st?.targetTemp ?? '--'}°C</span>
            <button disabled={disabled || (st?.targetTemp ?? 24) >= 30} onClick={() => send({ targetTemp: (st?.targetTemp ?? 24) + 1 })} className="w-11 h-11 rounded-full bg-slate-700 text-xl disabled:opacity-40">＋</button>
          </div>
        </div>
        <div className="grid grid-cols-2 gap-2">
          {(['COOL', 'HEAT'] as const).map((m) => (
            <button key={m} disabled={disabled} onClick={() => send({ mode: m })}
              className={`py-2.5 rounded-xl text-sm disabled:opacity-50 ${st?.mode === m ? (m === 'COOL' ? 'bg-sky-500/80' : 'bg-orange-500/80') + ' text-white' : 'bg-slate-700 text-slate-300'}`}>
              {m === 'COOL' ? '❄ 냉방' : '☀ 난방'}
            </button>
          ))}
        </div>
        <dl className="border-t border-white/10 pt-3">
          <Row k="장비">{dev ? `${dev.name} · ${dev.adapterType === 'MOCK' ? '가상(Mock)' : dev.adapterType}` : '-'}</Row>
          <Row k="최근 명령"><CommandBadge cmd={cmd} /></Row>
        </dl>
        {err && <Notice tone="error">{err}</Notice>}
        <p className="text-[11px] text-slate-500">화면의 값은 장비가 확인해 준 상태만 반영합니다.</p>
      </div>
    </Shell>
  )
}

const SRV_COLOR = { OK: theme.status.ok, WARN: theme.status.warn, DOWN: theme.status.error }
const SRV_LABEL = { OK: '정상', WARN: '주의', DOWN: '장애' }
const LEVEL_COLOR = { INFO: theme.status.idle, WARN: theme.status.warn, ERROR: theme.status.error }

export function ServerPanel() {
  const close = useOffice((s) => s.close)
  const conn = useOffice((s) => s.conn)
  const [snap, setSnap] = useState<ServerSnapshot | null>(null)
  const [err, setErr] = useState<string | null>(null)

  useEffect(() => {
    if (conn !== 'online') return
    let alive = true
    const load = () => api.servers().then((s) => alive && (setSnap(s), setErr(null))).catch((e: Error) => alive && setErr(e.message))
    load()
    const t = setInterval(load, 3000)
    return () => { alive = false; clearInterval(t) }
  }, [conn])

  return (
    <Shell title="🖥️ 서버실 모니터링" onClose={close}>
      <div className="p-5 space-y-4">
        {conn !== 'online' && <Notice>{OFFLINE_NOTE}</Notice>}
        {err && <Notice tone="error">{err}</Notice>}
        {snap && <Notice tone="info">데이터 출처: {snap.source === 'MOCK' ? 'Mock(가상) 모니터' : snap.source} — 실제 모니터링 API 연결 전입니다.</Notice>}
        <div className="space-y-2.5">
          {snap?.servers.map((s) => (
            <div key={s.name} className="rounded-xl bg-white/5 p-3">
              <div className="flex items-center justify-between text-sm">
                <span className="font-medium">{s.name} <span className="text-slate-500 text-xs">{s.role}</span></span>
                <span className="inline-flex items-center gap-1.5 text-xs" style={{ color: SRV_COLOR[s.status] }}><Dot color={SRV_COLOR[s.status]} />{SRV_LABEL[s.status]}</span>
              </div>
              {(['cpu', 'memory'] as const).map((k) => (
                <div key={k} className="mt-2 flex items-center gap-2 text-[11px] text-slate-400">
                  <span className="w-12">{k === 'cpu' ? 'CPU' : '메모리'}</span>
                  <div className="flex-1 h-1.5 rounded bg-white/10 overflow-hidden">
                    <div className="h-full rounded transition-all" style={{ width: `${s[k]}%`, background: s[k] > 85 ? theme.status.warn : theme.status.working }} />
                  </div>
                  <span className="w-9 text-right text-slate-200">{s[k]}%</span>
                </div>
              ))}
            </div>
          ))}
        </div>
        {snap && (
          <div>
            <div className="text-xs text-slate-400 mb-1.5">최근 이벤트</div>
            <ul className="space-y-1">
              {snap.events.map((e, i) => (
                <li key={i} className="text-xs flex gap-2">
                  <span className="text-slate-500 shrink-0">{new Date(e.at).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })}</span>
                  <span style={{ color: LEVEL_COLOR[e.level] }}>{e.message}</span>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </Shell>
  )
}

const fmt = (iso: string) => new Date(iso).toLocaleString('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
const toLocalInput = (d: Date) => new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16)

export function MeetingPanel() {
  const close = useOffice((s) => s.close)
  const conn = useOffice((s) => s.conn)
  const user = useOffice((s) => s.user)
  const [rooms, setRooms] = useState<MeetingRoom[]>([])
  const [list, setList] = useState<Reservation[]>([])
  const [roomId, setRoomId] = useState<number | null>(null)
  const [title, setTitle] = useState('')
  const [start, setStart] = useState(() => {
    const d = new Date()
    d.setMinutes(0, 0, 0)
    d.setHours(d.getHours() + 1)
    return toLocalInput(d)
  })
  const [minutes, setMinutes] = useState(60)
  const [err, setErr] = useState<string | null>(null)

  const reload = useCallback(() => {
    api.reservations().then(setList).catch((e: Error) => setErr(e.message))
  }, [])

  useEffect(() => {
    if (conn !== 'online') return
    api.rooms().then((r) => { setRooms(r); setRoomId((id) => id ?? r[0]?.id ?? null) }).catch((e: Error) => setErr(e.message))
    reload()
    window.addEventListener('reservations-changed', reload)
    return () => window.removeEventListener('reservations-changed', reload)
  }, [conn, reload])

  const mine = useMemo(() => list.filter((r) => r.roomId === roomId), [list, roomId])

  const create = async (e: React.FormEvent) => {
    e.preventDefault()
    if (roomId == null || !title.trim()) return setErr('회의 제목을 입력하세요.')
    setErr(null)
    const s = new Date(start)
    try {
      await api.reserve({ roomId, title: title.trim(), startAt: s.toISOString(), endAt: new Date(s.getTime() + minutes * 60000).toISOString() })
      setTitle('')
      reload()
    } catch (x) {
      setErr(x instanceof ApiError ? x.message : '예약에 실패했습니다.')
    }
  }
  const cancel = async (id: number) => {
    setErr(null)
    try { await api.cancelReservation(id); reload() } catch (x) { setErr(x instanceof ApiError ? x.message : '취소에 실패했습니다.') }
  }

  return (
    <Shell title="📅 회의실 예약" onClose={close}>
      <div className="p-5 space-y-4">
        {conn !== 'online' && <Notice>{OFFLINE_NOTE}</Notice>}
        <div className="flex gap-2">
          {rooms.map((r) => (
            <button key={r.id} onClick={() => setRoomId(r.id)} className={`flex-1 py-2 rounded-xl text-sm ${roomId === r.id ? 'bg-sky-500 text-white' : 'bg-slate-700 text-slate-300'}`}>
              {r.name}<span className="block text-[10px] opacity-70">{r.capacity}인</span>
            </button>
          ))}
        </div>
        <div>
          <div className="text-xs text-slate-400 mb-1.5">예약 현황</div>
          {mine.length === 0 ? <p className="text-sm text-slate-500">예약이 없습니다.</p> : (
            <ul className="space-y-1.5">
              {mine.map((r) => (
                <li key={r.id} className="rounded-lg bg-white/5 px-3 py-2 text-sm flex items-center justify-between gap-2">
                  <div className="min-w-0">
                    <div className="truncate">{r.title}</div>
                    <div className="text-[11px] text-slate-400">{fmt(r.startAt)} ~ {fmt(r.endAt)} · {r.reservedBy}</div>
                  </div>
                  {(user?.role === 'ADMIN' || user?.username === r.reservedBy) && (
                    <button onClick={() => cancel(r.id)} className="text-xs px-2 py-1 rounded bg-slate-700 hover:bg-red-500/70 shrink-0">취소</button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
        <form onSubmit={create} className="space-y-2 border-t border-white/10 pt-4">
          <div className="text-xs text-slate-400">새 예약</div>
          <input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200} placeholder="회의 제목" onKeyDown={(e) => e.stopPropagation()}
            className="w-full bg-slate-900/70 border border-white/10 rounded-lg px-3 py-2 text-sm outline-none focus:border-sky-400" />
          <div className="flex gap-2">
            <input type="datetime-local" value={start} onChange={(e) => setStart(e.target.value)} onKeyDown={(e) => e.stopPropagation()}
              className="flex-1 min-w-0 bg-slate-900/70 border border-white/10 rounded-lg px-2 py-2 text-sm outline-none focus:border-sky-400" />
            <select value={minutes} onChange={(e) => setMinutes(Number(e.target.value))} className="bg-slate-900/70 border border-white/10 rounded-lg px-2 text-sm">
              {[30, 60, 90, 120, 180].map((m) => <option key={m} value={m}>{m}분</option>)}
            </select>
          </div>
          <button disabled={conn !== 'online' || roomId == null} className="w-full py-2.5 rounded-xl bg-sky-500 text-white text-sm font-semibold disabled:opacity-40">예약하기</button>
        </form>
        {err && <Notice tone="error">{err}</Notice>}
      </div>
    </Shell>
  )
}
