import { useEffect, useState } from 'react'
import { startTour } from '../common/coach'
import { api, type ServerSnapshot } from '../api'
import { useOffice } from '../store'
import { theme } from '../theme'
import { Badge, Btn, Card, Empty, ErrorNote, MOCK_BADGE, PageHeader, SOON_BADGE, fmtTime, useLoad } from './kit'

export function MonitoringPage() {
  const conn = useOffice((s) => s.conn)
  const [snap, setSnap] = useState<ServerSnapshot | null>(null)
  const [err, setErr] = useState<string | null>(null)
  useEffect(() => {
    if (conn !== 'online') return
    let alive = true
    const load = () => api.servers().then((x) => alive && (setSnap(x), setErr(null))).catch((e: Error) => alive && setErr(e.message))
    load()
    const t = setInterval(load, 3000)
    return () => { alive = false; clearInterval(t) }
  }, [conn])
  const color = { OK: theme.status.ok, WARN: theme.status.warn, DOWN: theme.status.error }
  return (
    <>
      <PageHeader title="Monitoring" desc="서버 상태와 최근 이벤트. 실제 모니터링 API 연결 전이라 Mock 데이터입니다." badge={MOCK_BADGE} />
      <ErrorNote message={err} />
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4 mb-5">
        {snap?.servers.map((s) => (
          <Card key={s.name}>
            <div className="flex items-center justify-between"><b>{s.name}</b><span className="text-xs" style={{ color: color[s.status] }}>● {s.status}</span></div>
            <div className="text-xs text-slate-400">{s.role}</div>
            {(['cpu', 'memory'] as const).map((k) => (
              <div key={k} className="mt-2 text-[11px] text-slate-400 flex items-center gap-2">
                <span className="w-10">{k === 'cpu' ? 'CPU' : 'MEM'}</span>
                <div className="flex-1 h-1.5 rounded bg-white/10"><div className="h-full rounded" style={{ width: `${s[k]}%`, background: s[k] > 85 ? theme.status.warn : theme.status.working }} /></div>
                <span className="w-8 text-right text-slate-200">{s[k]}%</span>
              </div>
            ))}
          </Card>
        ))}
      </div>
      <Card title="최근 이벤트">
        <ul className="space-y-1.5 text-sm">
          {snap?.events.map((e, i) => <li key={i}><span className="text-slate-500 text-xs mr-2">{fmtTime(e.at)}</span><span style={{ color: e.level === 'ERROR' ? theme.status.error : e.level === 'WARN' ? theme.status.warn : undefined }}>{e.message}</span></li>)}
        </ul>
      </Card>
    </>
  )
}

export function OfficeManagementPage() {
  const devices = useOffice((s) => s.devices)
  const user = useOffice((s) => s.user)
  const rooms = useLoad(() => api.rooms(), [])
  const reservations = useLoad(() => api.reservations(), [])
  const audit = useLoad(() => api.auditLogs(), [], user?.role === 'ADMIN')
  return (
    <>
      <PageHeader title="Office Management" desc="장비 상태, 회의실 예약, 감사 로그. 장비 제어와 회의실 예약은 3D OFFICE 에서 합니다.">
        <Btn onClick={() => useOffice.getState().setMode('office')}>3D OFFICE 열기</Btn>
      </PageHeader>
      <div className="grid gap-5 xl:grid-cols-2">
        <Card title="장비 (장비가 확인한 상태)">
          <ul className="divide-y divide-white/8 text-sm">
            {Object.values(devices).map((d) => (
              <li key={d.id} className="py-2.5 flex items-center justify-between">
                <div><div>{d.name}</div><div className="text-[11px] text-slate-500">{d.adapterType === 'MOCK' ? 'Mock 어댑터(가상 장비)' : d.adapterType}</div></div>
                <div className="text-right"><Badge tone={d.state.power ? 'ok' : 'idle'}>{d.state.power ? 'ON' : 'OFF'}</Badge>
                  {d.type === 'AIR_CONDITIONER' && <div className="text-[11px] text-slate-400 mt-1">{d.state.currentTemp}°C → {d.state.targetTemp}°C {d.state.mode}</div>}</div>
              </li>
            ))}
          </ul>
        </Card>
        <Card title="회의실 예약 현황">
          {!reservations.data?.length && <Empty>예정된 예약이 없습니다.</Empty>}
          <ul className="space-y-2 text-sm">
            {reservations.data?.slice(0, 8).map((r) => (
              <li key={r.id}><b>{rooms.data?.find((x) => x.id === r.roomId)?.name}</b> · {r.title}<div className="text-[11px] text-slate-500">{fmtTime(r.startAt)} ~ {fmtTime(r.endAt)} · {r.reservedBy}</div></li>
            ))}
          </ul>
        </Card>
        {user?.role === 'ADMIN' && (
          <Card title="감사 로그 (관리자)" className="xl:col-span-2">
            <ul className="space-y-1 text-xs">
              {audit.data?.map((l) => <li key={l.id}><span className="text-slate-500">{fmtTime(l.createdAt)}</span> · <b>{l.actor}</b> {l.action} {l.target ?? ''} <span className="text-slate-500">{l.detail ?? ''}</span></li>)}
            </ul>
          </Card>
        )}
      </div>
    </>
  )
}

export function SettingsPage() {
  const user = useOffice((s) => s.user)
  const conn = useOffice((s) => s.conn)
  const logout = useOffice((s) => s.logout)
  return (
    <>
      <PageHeader title="Settings" desc="계정과 연결 정보." />
      <div className="grid gap-5 md:grid-cols-2">
        <Card title="계정">
          <dl className="text-sm space-y-2">
            <div className="flex justify-between"><dt className="text-slate-400">이름</dt><dd>{user?.displayName}</dd></div>
            <div className="flex justify-between"><dt className="text-slate-400">아이디</dt><dd>{user?.username}</dd></div>
            <div className="flex justify-between"><dt className="text-slate-400">권한</dt><dd>{user?.role === 'ADMIN' ? '관리자' : '일반 사용자'}</dd></div>
          </dl>
          <div className="mt-4 flex gap-2 flex-wrap"><Btn tone="ghost" onClick={() => startTour()}>사용법 다시 보기</Btn><Btn tone="ghost" onClick={logout}>로그아웃</Btn></div>
        </Card>
        <Card title="연결 / 환경">
          <dl className="text-sm space-y-2">
            <div className="flex justify-between"><dt className="text-slate-400">백엔드</dt><dd>{conn === 'online' ? '연결됨' : conn}</dd></div>
            <div className="flex justify-between"><dt className="text-slate-400">장비 어댑터</dt><dd>Mock (MQTT/HTTP 는 Phase 4)</dd></div>
            <div className="flex justify-between"><dt className="text-slate-400">AI</dt><dd>Mock (LLM 은 Phase 3B)</dd></div>
          </dl>
        </Card>
      </div>
    </>
  )
}

export function ComingSoonPage({ title, desc, items }: { title: string; desc: string; items: string[] }) {
  return (
    <>
      <PageHeader title={title} desc={desc} badge={SOON_BADGE} />
      <Card title="예정 기능">
        <ul className="list-disc pl-5 text-sm text-slate-300 space-y-1.5">{items.map((i) => <li key={i}>{i}</li>)}</ul>
        <p className="text-xs text-slate-500 mt-4">이 메뉴는 아직 구현되지 않았습니다. 화면에 보이는 데이터가 없는 것이 정상입니다.</p>
      </Card>
    </>
  )
}
