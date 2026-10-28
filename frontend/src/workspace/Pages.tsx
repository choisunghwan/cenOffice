import { askText } from '../ui/confirm'
import { useEffect, useState } from 'react'
import { api, type ServerSnapshot } from '../api'
import { useOffice } from '../store'
import { theme } from '../theme'
import { ACTION_LABEL, Badge, Btn, Card, Empty, ErrorNote, MOCK_BADGE, PageHeader, SOON_BADGE, Select, Stat, errMsg, fmtTime, useLoad } from './kit'
import { RequirementsTab } from './Projects'
import { PmPanel } from './PmPanel'

export function HomePage({ go }: { go: (k: string) => void }) {
  const s = useLoad(() => api.summary(), [])
  const devices = useOffice((st) => st.devices)
  const user = useOffice((st) => st.user)
  const d = s.data
  return (
    <>
      <PageHeader title={`안녕하세요, ${user?.displayName ?? ''}님`} desc="프로젝트 진행 현황과 AI 직원의 승인 대기 항목을 한눈에 확인합니다." />
      <ErrorNote message={s.error} />
      <div className="grid gap-4 grid-cols-2 xl:grid-cols-4 mb-5">
        <Stat label="프로젝트" value={d?.projects ?? '–'} />
        <Stat label="진행 중 작업" value={d?.openTasks ?? '–'} tone="info" />
        <Stat label="완료 작업" value={d?.doneTasks ?? '–'} tone="ok" />
        <Stat label="승인 대기" value={d?.pendingApprovals ?? '–'} tone={d?.pendingApprovals ? 'warn' : undefined} />
      </div>
      <div className="grid gap-5 xl:grid-cols-[1fr_360px]">
        <Card title="최근 활동" action={<Btn tone="ghost" small onClick={() => go('projects')}>프로젝트 보기</Btn>}>
          {!d?.recent.length && <Empty>아직 활동이 없습니다. Projects 에서 첫 프로젝트를 만들어 보세요.</Empty>}
          <ul className="space-y-2">
            {d?.recent.map((h) => (
              <li key={h.id} className="text-sm flex gap-2">
                <span className="text-slate-500 text-xs w-24 shrink-0 pt-0.5">{fmtTime(h.at)}</span>
                <span><b>{h.actor}</b> {ACTION_LABEL[h.action] ?? h.action}{h.detail ? <span className="text-slate-400"> — {h.detail}</span> : null}</span>
              </li>
            ))}
          </ul>
        </Card>
        <div className="space-y-4">
          <Card title="오피스 장비">
            {Object.values(devices).length === 0 && <Empty>장비 정보가 없습니다.</Empty>}
            <ul className="space-y-2 text-sm">
              {Object.values(devices).map((dv) => (
                <li key={dv.id} className="flex items-center justify-between">
                  <span>{dv.name}</span>
                  <Badge tone={dv.state.power ? 'ok' : 'idle'}>{dv.state.power ? 'ON' : 'OFF'}</Badge>
                </li>
              ))}
            </ul>
          </Card>
          <Card title="바로가기">
            <div className="flex flex-wrap gap-2">
              <Btn tone="ghost" small onClick={() => go('agents')}>AI 승인 대기 {d?.pendingApprovals ? `(${d.pendingApprovals})` : ''}</Btn>
              <Btn tone="ghost" small onClick={() => go('monitoring')}>서버 상태</Btn>
              <Btn tone="ghost" small onClick={() => useOffice.getState().setMode('office')}>3D OFFICE 열기</Btn>
            </div>
          </Card>
        </div>
      </div>
    </>
  )
}

export function RequirementsPage() {
  const projects = useLoad(() => api.projects(), [])
  const [pid, setPid] = useState<number | null>(null)
  const id = pid ?? projects.data?.[0]?.id ?? null
  return (
    <>
      <PageHeader title="Requirements" desc="프로젝트별 요구사항을 등록하고 우선순위·상태를 관리합니다.">
        <Select small value={id ?? ''} onChange={(e) => setPid(Number(e.target.value))}>
          {projects.data?.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
        </Select>
      </PageHeader>
      {id ? <RequirementsTab key={id} projectId={id} /> : <Card><Empty>먼저 Projects 에서 프로젝트를 만드세요.</Empty></Card>}
    </>
  )
}

const POLICY: { env: string; caps: string; tone: 'ok' | 'warn' | 'error' }[] = [
  { env: 'DEV', caps: '읽기 · 테스트 · 개발 (AI 개발 작업 허용)', tone: 'ok' },
  { env: 'STG', caps: '읽기 · 테스트 (제한된 검증)', tone: 'warn' },
  { env: 'PROD', caps: '읽기 전용 상태 조회 — AI 에이전트 접근 권한 없음', tone: 'error' },
]

export function AgentsPage() {
  const agents = useLoad(() => api.agents(), [])
  const pending = useLoad(() => api.approvals('PENDING'), [])
  const tasks = useLoad(() => api.aiTasks(), [])
  const user = useOffice((s) => s.user)
  const [err, setErr] = useState<string | null>(null)

  const decide = async (id: number, approve: boolean) => {
    const comment = await askText({ title: approve ? '승인할까요?' : '거절할까요?', message: approve ? '남길 코멘트가 있으면 적어 주세요.' : '거절 사유가 있으면 적어 주세요.', okText: approve ? '승인' : '거절', danger: !approve, placeholder: '코멘트 (선택)' })
    if (comment === null) return
    try { await api.decide(id, approve, comment || undefined); setErr(null); pending.reload(); tasks.reload() } catch (x) { setErr(errMsg(x)) }
  }
  const agentName = (id: number) => agents.data?.find((a) => a.id === id)?.name ?? `#${id}`

  return (
    <>
      <PageHeader title="AI Agents" desc="AI 직원과 환경별 작업 권한, 사용자 승인 대기열을 관리합니다." badge={MOCK_BADGE} />
      <ErrorNote message={err ?? agents.error} />
      <div className="grid gap-4 md:grid-cols-3 mb-5">
        {agents.data?.map((a) => (
          <Card key={a.id}>
            <div className="flex items-center justify-between">
              <div className="font-semibold">{a.name}</div>
              <Badge tone="info">{a.role}</Badge>
            </div>
            <div className="text-xs text-slate-400 mt-2">작업 가능 환경: {a.allowedEnvironments.split(',').join(' · ')}</div>
            <div className="mt-2 flex gap-1.5"><Badge tone="error">PROD 접근 불가</Badge><Badge tone="warn">LLM 미연동(Mock)</Badge></div>
          </Card>
        ))}
      </div>
      <PmPanel />
      <div className="grid gap-5 xl:grid-cols-2">
        <Card title={`승인 대기 ${pending.data?.length ?? 0}건`}>
          {!pending.data?.length && <Empty>승인 대기 중인 요청이 없습니다.</Empty>}
          <ul className="space-y-3">
            {pending.data?.map((a) => (
              <li key={a.id} className="rounded-xl bg-[#101c30] border border-white/8 p-3 text-sm">
                <div className="flex items-center gap-2"><Badge tone="warn">{a.kind}</Badge><span className="text-slate-400 text-xs">AI 작업 #{a.aiTaskId} · 요청 {a.requestedBy} · {fmtTime(a.createdAt)}</span></div>
                <p className="mt-2">{a.summary}</p>
                <div className="mt-2 flex gap-2">
                  <Btn small onClick={() => decide(a.id, true)} disabled={a.requestedBy === user?.username && user?.role !== 'ADMIN'}>승인</Btn>
                  <Btn small tone="ghost" onClick={() => decide(a.id, false)}>거절</Btn>
                  {a.requestedBy === user?.username && user?.role !== 'ADMIN' && <span className="text-[11px] text-slate-500 self-center">본인 요청은 다른 사용자가 승인해야 합니다</span>}
                </div>
              </li>
            ))}
          </ul>
        </Card>
        <Card title="환경별 AI 작업 권한 (서버에서 강제)">
          <ul className="space-y-2.5 text-sm">
            {POLICY.map((p) => <li key={p.env} className="flex items-start gap-3"><Badge tone={p.tone}>{p.env}</Badge><span className="text-slate-300">{p.caps}</span></li>)}
          </ul>
          <p className="text-xs text-slate-500 mt-3">운영 배포 실행 기능은 구현하지 않습니다. 향후 별도 CI/CD 와 운영 담당자 승인으로 분리됩니다.</p>
        </Card>
      </div>
      <Card title="최근 AI 작업" className="mt-5">
        {!tasks.data?.length && <Empty>접수된 AI 작업이 없습니다. Projects → AI 작업 탭에서 접수할 수 있습니다.</Empty>}
        <ul className="divide-y divide-white/8 text-sm">
          {tasks.data?.map((t) => (
            <li key={t.id} className="py-2 flex items-center gap-2 flex-wrap">
              <span className="font-medium">#{t.id} {t.type}</span><Badge tone="info">{t.environment}</Badge>
              <span className="text-slate-400 text-xs">{agentName(t.agentId)} · {t.requestedBy} · {fmtTime(t.createdAt)}</span>
              <span className="flex-1" /><Badge tone={t.status === 'WAITING_APPROVAL' ? 'warn' : t.status === 'REJECTED' ? 'error' : 'idle'}>{t.status}</Badge>
            </li>
          ))}
        </ul>
      </Card>
    </>
  )
}

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
          <div className="mt-4"><Btn tone="ghost" onClick={logout}>로그아웃</Btn></div>
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
