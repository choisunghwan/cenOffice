import { useEffect, useState } from 'react'
import { api, type AiTaskItem, type AiTaskStatus } from '../api'
import { useOffice } from '../store'
import { Badge, Btn, Card, Empty, ErrorNote, Select, Textarea, errMsg, fmtTime, useLoad } from './kit'

const STATUS: Record<AiTaskStatus, { label: string; tone: 'ok' | 'warn' | 'error' | 'info' | 'idle' }> = {
  QUEUED: { label: '대기', tone: 'idle' },
  RUNNING: { label: '작업 중', tone: 'info' },
  NEEDS_INPUT: { label: '답변 필요', tone: 'warn' },
  WAITING_APPROVAL: { label: '승인 대기', tone: 'warn' },
  DONE: { label: '등록 완료', tone: 'ok' },
  FAILED: { label: '실패', tone: 'error' },
  REJECTED: { label: '거절됨', tone: 'error' },
}

const STEP_LABEL: Record<string, string> = {
  queued: '접수', received: '요구사항 접수', analyzing: '요구사항 분석', 'needs-input': '추가 질문', answered: '답변 수신',
  'spec-drafted': '기능 명세 초안', 'tasks-generated': '작업 목록 생성', 'approval-requested': '사용자 승인 요청',
  approved: '승인됨', rejected: '거절됨', applied: '프로젝트에 등록', 'validation-failed': '서버 검증 실패', 'llm-error': 'LLM 오류', error: '오류',
}

/** AI PM(아리): 요구사항 접수 → 질문 → 명세/작업 계획 → 사용자 승인 → 프로젝트 등록. 승인 전에는 아무것도 등록되지 않는다. */
export function PmPanel() {
  const user = useOffice((s) => s.user)
  const info = useLoad(() => api.pmInfo(), [])
  const projects = useLoad(() => api.projects(), [])
  const tasks = useLoad(() => api.aiTasks(), [])
  const [pid, setPid] = useState('')
  const [text, setText] = useState('')
  const [selected, setSelected] = useState<number | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const pmTasks = (tasks.data ?? []).filter((t) => t.type === 'DRAFT_SPEC')
  const projectName = (id: number) => projects.data?.find((p) => p.id === id)?.name ?? `#${id}`
  const projectId = pid || String(projects.data?.[0]?.id ?? '')

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!projectId || text.trim().length < 10) return setErr('프로젝트를 선택하고 요구사항을 10자 이상 입력하세요.')
    setBusy(true)
    try {
      const t = await api.pmIntake(Number(projectId), text.trim())
      setText(''); setErr(null); setSelected(t.id); tasks.reload()
    } catch (x) { setErr(errMsg(x)) } finally { setBusy(false) }
  }

  return (
    <Card title="AI PM 아리 — 요구사항 분석 / 계획 수립" action={info.data && (
      <Badge tone={info.data.real ? 'info' : 'warn'}>{info.data.real ? `LLM: ${info.data.provider}` : 'Mock LLM'}</Badge>
    )} className="mb-5">
      {info.data && <p className="text-xs text-slate-400 mb-3">{info.data.note}. 승인 전에는 프로젝트에 어떤 변경도 적용되지 않으며, AI 작업은 DEV 환경에서만 실행됩니다.</p>}
      <ErrorNote message={err} />
      <div className="grid gap-5 xl:grid-cols-[360px_1fr]">
        <div className="space-y-3">
          <form onSubmit={submit} className="space-y-2">
            <Select value={projectId} onChange={(e) => setPid(e.target.value)}>
              {projects.data?.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </Select>
            <Textarea rows={4} value={text} onChange={(e) => setText(e.target.value)} maxLength={4000} placeholder="고객 요구사항을 자연어로 입력하세요 (예: 모바일에서 주문 내역을 조회하고 상태가 바뀌면 알림을 받고 싶어요)" />
            <Btn type="submit" disabled={busy || !projects.data?.length}>{busy ? '접수 중…' : '아리에게 요구사항 전달'}</Btn>
            {!projects.data?.length && <p className="text-[11px] text-slate-500">먼저 Projects 에서 프로젝트를 만드세요.</p>}
          </form>
          <ul className="space-y-1.5 max-h-72 overflow-y-auto">
            {pmTasks.length === 0 && <Empty>접수된 요구사항 분석이 없습니다.</Empty>}
            {pmTasks.map((t) => <TaskRow key={t.id} t={t} active={selected === t.id} name={projectName(t.projectId)} onClick={() => setSelected(t.id)} />)}
          </ul>
        </div>
        <div>
          {selected ? <PmDetail key={selected} id={selected} meName={user?.username} onChanged={() => tasks.reload()} /> : <Empty>왼쪽에서 작업을 선택하면 진행 과정이 표시됩니다.</Empty>}
        </div>
      </div>
    </Card>
  )
}

function TaskRow({ t, active, name, onClick }: { t: AiTaskItem; active: boolean; name: string; onClick: () => void }) {
  const s = STATUS[t.status]
  return (
    <li>
      <button onClick={onClick} className={`w-full text-left rounded-xl border px-3 py-2 text-sm ${active ? 'border-sky-400/60 bg-sky-500/10' : 'border-white/8 bg-[#101c30] hover:bg-[#16253f]'}`}>
        <div className="flex items-center justify-between gap-2"><span className="truncate font-medium">#{t.id} {name}</span><Badge tone={s.tone}>{s.label}</Badge></div>
        <div className="text-[11px] text-slate-400 truncate mt-0.5">{t.input}</div>
        <div className="text-[10px] text-slate-500 mt-0.5">{t.requestedBy} · {fmtTime(t.createdAt)}</div>
      </button>
    </li>
  )
}

function PmDetail({ id, meName, onChanged }: { id: number; meName?: string; onChanged: () => void }) {
  const d = useLoad(() => api.pmDetail(id), [id])
  const [answer, setAnswer] = useState('')
  const [err, setErr] = useState<string | null>(null)
  const status = d.data?.task.status
  const role = useOffice((s) => s.user?.role)

  // 진행 중에는 WebSocket 이벤트를 놓쳐도 갱신되도록 짧은 주기로 확인
  useEffect(() => {
    if (status !== 'QUEUED' && status !== 'RUNNING') return
    const t = setInterval(d.reload, 2000)
    return () => clearInterval(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status])

  if (!d.data) return <ErrorNote message={d.error} />
  const { task, executions, state } = d.data
  const s = STATUS[task.status]
  const act = async (fn: () => Promise<unknown>) => { try { await fn(); setErr(null); d.reload(); onChanged() } catch (x) { setErr(errMsg(x)) } }
  const canAnswer = task.requestedBy === meName || role === 'ADMIN'

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2 flex-wrap">
        <h4 className="font-semibold">AI 작업 #{task.id}</h4><Badge tone={s.tone}>{s.label}</Badge>
        <Badge tone="idle">{state?.provider === 'mock' ? 'Mock' : state?.provider}</Badge>
        <span className="text-[11px] text-slate-500">요청 {task.requestedBy}</span>
      </div>
      <ErrorNote message={err ?? d.error} />

      <ol className="text-xs space-y-1 border-l border-white/10 pl-3">
        {executions.map((e) => (
          <li key={e.id}>
            <span className="text-slate-500">{fmtTime(e.startedAt)}</span> · <b className={e.status === 'FAILED' ? 'text-red-300' : 'text-slate-200'}>{STEP_LABEL[e.step] ?? e.step}</b>
            {e.detail && <span className="text-slate-400"> — {e.detail}</span>}
          </li>
        ))}
      </ol>

      {task.status === 'FAILED' && state?.error && <ErrorNote message={`작업이 실패했습니다: ${state.error} (프로젝트는 변경되지 않았습니다)`} />}

      {task.status === 'NEEDS_INPUT' && (
        <div className="rounded-xl bg-amber-400/10 border border-amber-400/30 p-3 space-y-2">
          <div className="text-sm font-medium text-amber-200">아리의 질문 — 정보가 더 필요합니다</div>
          <ul className="list-decimal pl-5 text-sm space-y-1">{state?.questions.map((q, i) => <li key={i}>{q}</li>)}</ul>
          {canAnswer ? (
            <>
              <Textarea rows={3} value={answer} onChange={(e) => setAnswer(e.target.value)} maxLength={2000} placeholder="질문에 대한 답변을 한 번에 적어주세요" />
              <Btn disabled={!answer.trim()} onClick={() => act(async () => { await api.pmAnswer(id, answer.trim()); setAnswer('') })}>답변 보내기</Btn>
            </>
          ) : <p className="text-xs text-slate-400">요청자 또는 관리자만 답변할 수 있습니다.</p>}
        </div>
      )}

      {state && state.spec && (
        <div className="space-y-3">
          <details open className="rounded-xl bg-[#101c30] border border-white/8 p-3">
            <summary className="cursor-pointer text-sm font-medium">기능 명세서 초안</summary>
            {/* 텍스트로만 출력 (HTML 해석 없음) */}
            <pre className="mt-2 text-xs whitespace-pre-wrap break-words text-slate-300 font-sans">{state.spec}</pre>
          </details>
          <div className="grid gap-3 md:grid-cols-2">
            <div className="rounded-xl bg-[#101c30] border border-white/8 p-3">
              <div className="text-sm font-medium mb-2">요구사항 {state.requirements.length}건</div>
              <ul className="text-xs space-y-1.5">{state.requirements.map((r, i) => <li key={i}><Badge tone={r.priority === 'HIGH' ? 'error' : r.priority === 'LOW' ? 'idle' : 'warn'}>{r.priority}</Badge> {r.title}</li>)}</ul>
            </div>
            <div className="rounded-xl bg-[#101c30] border border-white/8 p-3">
              <div className="text-sm font-medium mb-2">작업 {state.tasks.length}건</div>
              <ul className="text-xs space-y-1 max-h-48 overflow-y-auto">{state.tasks.map((t, i) => <li key={i}>• {t.title}{t.requirementIndex != null && <span className="text-slate-500"> (요구사항 {t.requirementIndex + 1})</span>}</li>)}</ul>
            </div>
          </div>
        </div>
      )}

      {task.status === 'WAITING_APPROVAL' && state?.approvalId && (
        <div className="rounded-xl bg-amber-400/10 border border-amber-400/30 p-3 space-y-2">
          <div className="text-sm font-medium text-amber-200">승인 요청 #{state.approvalId}</div>
          <p className="text-xs text-slate-300">승인하면 위 요구사항과 작업이 이 프로젝트에 등록됩니다. 거절하면 아무것도 등록되지 않습니다. 승인 시 서버가 계획이 검토한 내용과 같은지 다시 확인합니다.</p>
          <div className="flex gap-2">
            <Btn onClick={() => act(() => api.decide(state.approvalId!, true))}>승인하고 등록</Btn>
            <Btn tone="ghost" onClick={() => act(() => api.decide(state.approvalId!, false))}>거절</Btn>
          </div>
        </div>
      )}

      {task.status === 'DONE' && task.result && <p className="text-sm text-emerald-300">✔ {task.result}</p>}
    </div>
  )
}
