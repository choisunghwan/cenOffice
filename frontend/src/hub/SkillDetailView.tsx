import { askConfirm } from '../ui/confirm'
import { BusinessTools } from './BusinessTools'
import { TextTool, TEXT_TOOLS } from './TextTool'
import { toolName } from './toolNames'
import { ToolIntroduction } from './ToolIntroduction'
import { useEffect, useState } from 'react'
import { hub, type HubExecution } from '../hubApi'
import { Badge, Btn, Card, Empty, ErrorNote, Input, Select, Stat, Textarea, errMsg, fmtTime, useLoad } from '../workspace/kit'
import { ACTION_KO, Bars, RuntimeBanner, StatusBadge } from './shared'

type Tab = 'overview' | 'md' | 'versions' | 'stats' | 'reviews' | 'run'

/** 업무 지침 상세: 개요 / SKILL.md / 버전·승인 / 통계 / 평가 / 실행 */
export function SkillDetailView({ id, onBack, onChanged, startWithRun = false }: { startWithRun?: boolean; id: number; onBack: () => void; onChanged: () => void }) {
  const [versionId, setVersionId] = useState<number | undefined>()
  const d = useLoad(() => hub.skill(id, versionId), [id, versionId])
  const [tab, setTab] = useState<Tab>(startWithRun ? 'run' : 'overview')
  const [err, setErr] = useState<string | null>(null)

  const act = async (fn: () => Promise<unknown>, done?: () => void) => {
    try { await fn(); setErr(null); d.reload(); onChanged(); done?.() } catch (x) { setErr(errMsg(x)) }
  }
  if (!d.data) return <><button className="text-sm text-slate-400 mb-3" onClick={onBack}>← 목록</button><ErrorNote message={d.error} /></>
  const { summary: s, current, versions, canEdit, canApprove, runtime } = d.data
  const ask = (msg: string) => window.prompt(msg)?.trim()

  return (
    <div>
      <button className="text-sm text-slate-400 hover:text-white mb-3" onClick={onBack}>← 목록으로</button>
      {tab !== 'overview' && <h2 className="text-xl font-semibold mb-4">{toolName(s.name, s.title)}</h2>}
      <details className="hub-advanced"><summary>도구 정보 · 파일 다운로드 · 관리</summary><div className="flex items-start gap-4 flex-wrap my-4">
        <div className="w-14 h-14 rounded-2xl bg-white/8 grid place-items-center text-3xl">{s.icon || '🧩'}</div>
        <div className="flex-1 min-w-[16rem]">
          <div className="flex items-center gap-2 flex-wrap">
            <h2 className="text-xl font-bold">{s.name}</h2>
            <StatusBadge status={s.status} />
            {s.sample && <Badge tone="warn">샘플 (KPI 제외)</Badge>}
            {s.visibility === 'TEAM' && <Badge tone="idle">팀 공개 · {s.team}</Badge>}
          </div>
          <p className="text-sm text-slate-300 mt-1">{s.description}</p>
          <div className="text-xs text-slate-400 mt-1">제작자 {s.ownerName} · 현재 버전 v{current?.meta.version ?? '-'} · 카테고리 {s.categoryName} · 수정 {fmtTime(s.updatedAt)}</div>
        </div>
        <div className="flex gap-2 flex-wrap">
          <Btn tone="ghost" onClick={() => act(() => hub.download(id, versionId))}>다운로드</Btn>
          {canEdit && s.status !== 'ARCHIVED' && <Btn tone="ghost" onClick={() => { void askConfirm({ title: '보관할까요?', message: '이 업무 지침을 보관(비공개) 처리합니다. 보관하면 동료들이 더 이상 찾을 수 없습니다.', okText: '보관', danger: true }).then((ok) => { if (ok) void act(() => hub.archive(id), onBack) }) }}>보관</Btn>}
        </div>
      </div>
      <RuntimeBanner runtime={runtime} /></details>
      <ErrorNote message={err ?? d.error} />

      <div className="flex gap-1 my-4 border-b border-white/10 flex-wrap">
        {([['overview', '소개'], ['md', 'SKILL.md'], ['versions', '버전 · 승인'], ['stats', '사용 통계'], ['reviews', `평가 (${s.ratingCount})`], ['run', '사용하기']] as [Tab, string][]).filter(([k]) => canEdit || canApprove || !['versions', 'stats'].includes(k)).map(([k, l]) => (
          <button key={k} onClick={() => setTab(k)} className={`px-4 py-2 text-sm -mb-px border-b-2 ${tab === k ? 'border-sky-400 text-white' : 'border-transparent text-slate-400 hover:text-slate-200'}`}>{l}</button>
        ))}
      </div>

      {tab === 'overview' && <ToolIntroduction name={toolName(s.name, s.title)} description={s.description} owner={s.ownerName} category={s.categoryName} usage={current?.usage} example={current?.example} ready={runtime.state === 'RUNTIME_AVAILABLE' && s.status === 'APPROVED'} simulated={runtime.simulated} onUse={() => setTab('run')} />}

      {tab === 'md' && current && (
        <div className="space-y-4">
          <Card title={`SKILL.md 미리보기 (v${current.meta.version})`} action={<span className="text-[10px] text-slate-500">텍스트로만 표시 · sha256 {current.meta.sha256.slice(0, 12)}…</span>}>
            {/* HTML 해석 없이 텍스트로만 출력 */}
            <pre className="text-xs whitespace-pre-wrap break-words font-mono text-slate-200 max-h-[28rem] overflow-y-auto">{current.skillMd}</pre>
          </Card>
          <Card title={`포함 파일 (${current.files.length}) · ${current.meta.packageKind === 'ZIP' ? 'ZIP 패키지' : 'SKILL.md 단일 파일'}`}>
            <ul className="text-xs text-slate-300 space-y-0.5">{current.files.map((f) => <li key={f.path}>{f.path} <span className="text-slate-500">{f.size}B</span></li>)}</ul>
          </Card>
        </div>
      )}

      {tab === 'versions' && (
        <VersionsTab id={id} skillName={s.name} versions={versions} canEdit={canEdit} canApprove={canApprove} onAct={act} ask={ask} onView={(v) => { setVersionId(v); setTab('md') }} />
      )}
      {tab === 'stats' && <StatsTab id={id} />}
      {tab === 'reviews' && <ReviewsTab id={id} onChanged={d.reload} />}
      {tab === 'run' && (runtime.kind === 'builtin-local' && s.status === 'APPROVED' && TEXT_TOOLS[s.name] ? <TextTool key={id} skillId={id} name={s.name} /> : runtime.kind === 'builtin-local' && s.status === 'APPROVED' ? <BusinessTools key={id} skillId={id} kind={s.name === 'cen-fp-estimator' ? 'fp' : 'proposal'} /> : <RunTab target={{ skillId: id }} runtimeReady={runtime.state === 'RUNTIME_AVAILABLE'} approved={s.status === 'APPROVED'} runtime={runtime} />)}
    </div>
  )
}

function VersionsTab({ id, versions, canEdit, canApprove, onAct, ask, onView }: {
  id: number; skillName: string; versions: import('../hubApi').HubVersion[]; canEdit: boolean; canApprove: boolean
  onAct: (fn: () => Promise<unknown>, done?: () => void) => Promise<void>; ask: (m: string) => string | undefined; onView: (v: number) => void
}) {
  const hist = useLoad(() => (canEdit ? hub.history(id) : Promise.resolve([])), [id, canEdit])
  const [md, setMd] = useState('')
  const [submit, setSubmit] = useState(true)
  return (
    <div className="space-y-4">
      <Card title="버전">
        <ul className="divide-y divide-white/8">
          {versions.map((v) => (
            <li key={v.id} className="py-3 flex items-center gap-2 flex-wrap text-sm">
              <b>v{v.version}</b><StatusBadge status={v.status} />{v.published && <Badge tone="ok">공개 중</Badge>}<Badge tone="idle">{v.packageKind}</Badge>
              {v.riskFlags.length > 0 && canEdit && <Badge tone="warn">위험 플래그 {v.riskFlags.length}</Badge>}
              <span className="text-xs text-slate-500">{v.createdBy} · {fmtTime(v.createdAt)} · {v.sha256.slice(0, 8)}</span>
              <span className="flex-1" />
              <Btn small tone="ghost" onClick={() => onView(v.id)}>보기</Btn>
              <Btn small tone="ghost" onClick={() => onAct(() => hub.download(id, v.id))}>다운로드</Btn>
              {canEdit && (v.status === 'DRAFT' || v.status === 'REJECTED') && <Btn small onClick={() => onAct(() => hub.submitVersion(id, v.id))}>검토 요청</Btn>}
              {canApprove && v.status === 'PENDING_REVIEW' && <>
                <Btn small onClick={() => onAct(() => hub.approve(id, v.id, ask('승인 코멘트 (선택)') ?? undefined))}>승인</Btn>
                <Btn small tone="danger" onClick={() => { const c = ask('반려 사유 (필수)'); if (c) void onAct(() => hub.reject(id, v.id, c)) }}>반려</Btn>
              </>}
              {canApprove && v.status === 'APPROVED' && !v.published && <Btn small tone="ghost" onClick={() => onAct(() => hub.rollback(id, v.id, ask('되돌리는 이유 (선택)') ?? undefined))}>이 버전으로 되돌리기</Btn>}
            </li>
          ))}
        </ul>
        {!canApprove && <p className="text-[11px] text-slate-500 mt-2">승인·반려·되돌리기는 관리자만 할 수 있고, 본인이 등록한 업무 지침 은 다른 관리자가 승인해야 합니다.</p>}
      </Card>
      {canEdit && (
        <Card title="새 버전 추가">
          <p className="text-xs text-slate-400 mb-2">SKILL.md 의 name 은 기존과 같아야 하고, version 을 생략하면 자동으로 올라갑니다. 새 버전은 승인 전까지 공개 버전에 영향을 주지 않습니다.</p>
          <Textarea rows={6} value={md} onChange={(e) => setMd(e.target.value)} placeholder="수정한 SKILL.md 전체를 붙여 넣으세요" />
          <div className="flex items-center gap-3 mt-2">
            <label className="text-xs text-slate-300 flex items-center gap-1.5"><input type="checkbox" checked={submit} onChange={(e) => setSubmit(e.target.checked)} />바로 검토 요청</label>
            <Btn disabled={!md.trim()} onClick={() => onAct(() => hub.addVersionMd(id, md, submit), () => setMd(''))}>버전 추가</Btn>
            <label className="text-xs text-sky-300 cursor-pointer">또는 파일(.md/.zip) 선택
              <input type="file" accept=".md,.zip" className="hidden" onChange={(e) => { const f = e.target.files?.[0]; if (f) void onAct(() => hub.addVersionFile(id, f, submit)); e.target.value = '' }} />
            </label>
          </div>
        </Card>
      )}
      {canEdit && (
        <Card title="승인 이력">
          {!hist.data?.length ? <Empty>이력이 없습니다.</Empty> : (
            <ul className="text-xs space-y-1">
              {hist.data.map((h) => <li key={h.id}><span className="text-slate-500">{fmtTime(h.at)}</span> · <b>{h.actor}</b> {ACTION_KO[h.action] ?? h.action}{h.comment ? <span className="text-slate-400"> — {h.comment}</span> : null}</li>)}
            </ul>
          )}
        </Card>
      )}
    </div>
  )
}

function StatsTab({ id }: { id: number }) {
  const st = useLoad(() => hub.skillStats(id), [id])
  if (!st.data) return <ErrorNote message={st.error} />
  const c = st.data.counts
  return (
    <div className="space-y-4">
      <div className="grid gap-3 grid-cols-2 lg:grid-cols-4">
        <Stat label="실행 요청" value={c.requests} /><Stat label="성공" value={c.succeeded} tone="ok" /><Stat label="실패" value={c.failed} tone={c.failed ? 'error' : undefined} />
        <Stat label="고유 사용자 / 재사용자" value={`${c.uniqueUsers} / ${c.repeatUsers}`} />
      </div>
      <div className="grid gap-4 lg:grid-cols-2">
        <Card title={`최근 14일 요청 (7일 ${c.last7d} · 30일 ${c.last30d})`}><Bars data={st.data.daily.map((d) => ({ label: d.date, value: d.requests }))} /></Card>
        <Card title="버전별 요청">
          {Object.keys(st.data.byVersion).length === 0 ? <Empty>아직 집계된 사용이 없습니다.</Empty> : (
            <ul className="text-sm space-y-1">{Object.entries(st.data.byVersion).map(([v, n]) => <li key={v} className="flex justify-between"><span>v{v}</span><b>{n}</b></li>)}</ul>
          )}
        </Card>
      </div>
      <p className="text-[11px] text-slate-500">집계 제외: 시뮬레이션 {st.data.exclusions.simulated}건 · 제작자 본인 실행 {st.data.exclusions.creatorSelf}건 · 샘플 {st.data.exclusions.sample}건 (실제 이벤트만 집계, 점수·보상은 산정하지 않음)</p>
    </div>
  )
}

function ReviewsTab({ id, onChanged }: { id: number; onChanged: () => void }) {
  const list = useLoad(() => hub.reviews(id), [id])
  const [rating, setRating] = useState(5)
  const [comment, setComment] = useState('')
  const [err, setErr] = useState<string | null>(null)
  const send = async () => {
    try { await hub.review(id, rating, comment.trim() || undefined); setComment(''); setErr(null); list.reload(); onChanged() } catch (x) { setErr(errMsg(x)) }
  }
  return (
    <div className="space-y-4">
      <Card title="평가 남기기">
        <div className="flex gap-2 flex-wrap">
          <Select small value={rating} onChange={(e) => setRating(Number(e.target.value))}>{[5, 4, 3, 2, 1].map((n) => <option key={n} value={n}>{'★'.repeat(n)} ({n})</option>)}</Select>
          <div className="flex-1 min-w-[12rem]"><Input value={comment} onChange={(e) => setComment(e.target.value)} maxLength={500} placeholder="사용 후기를 남겨 주세요 (선택)" /></div>
          <Btn onClick={send}>등록</Btn>
        </div>
        <ErrorNote message={err} />
        <p className="text-[11px] text-slate-500 mt-2">한 사람당 하나의 평가만 남길 수 있고(다시 남기면 수정), 본인이 만든 업무 지침 은 평가할 수 없습니다.</p>
      </Card>
      <Card title="사용자 평가">
        {!list.data?.length ? <Empty>아직 평가가 없습니다.</Empty> : (
          <ul className="divide-y divide-white/8 text-sm">{list.data.map((r) => (
            <li key={r.id} className="py-2"><span className="text-amber-300">{'★'.repeat(r.rating)}</span> <b className="ml-1">{r.reviewerName}</b> <span className="text-xs text-slate-500">{fmtTime(r.updatedAt)}</span>
              {r.comment && <p className="text-slate-300 whitespace-pre-wrap">{r.comment}</p>}</li>
          ))}</ul>
        )}
      </Card>
    </div>
  )
}

/** 실행 패널: 런타임이 없으면 요청을 보내지 않고 안내만 표시한다 */
export function RunTab({ target, runtimeReady, approved, runtime }: { target: { skillId?: number; agentId?: number }; runtimeReady: boolean; approved: boolean; runtime: import('../hubApi').HubRuntime }) {
  const [input, setInput] = useState('')
  const [exec, setExec] = useState<HubExecution | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const hist = useLoad(() => (runtimeReady && target.skillId ? hub.executions(target.skillId) : Promise.resolve(null)), [target.skillId, exec?.status])

  useEffect(() => {
    if (!exec || ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(exec.status)) return
    const t = setInterval(() => { hub.execution(exec.id).then(setExec).catch(() => undefined) }, 800)
    return () => clearInterval(t)
  }, [exec])

  if (!runtimeReady) return <Card><RuntimeBanner runtime={runtime} /><p className="text-sm text-slate-400 mt-3">실행 런타임이 연결되면 이 화면에서 바로 실행할 수 있습니다. 지금은 등록·검색·다운로드·평가만 가능합니다.</p></Card>
  if (!approved) return <Card><Empty>승인된 항목만 실행할 수 있습니다.</Empty></Card>
  const run = async () => {
    try { setExec(await hub.execute({ ...target, input })); setErr(null) } catch (x) { setErr(errMsg(x)) }
  }
  return (
    <div className="space-y-4">
      <RuntimeBanner runtime={runtime} />
      <Card title="실행">
        <Textarea rows={4} value={input} onChange={(e) => setInput(e.target.value)} maxLength={4000} placeholder="입력(요청 내용)을 적어 주세요. 개인정보·비밀번호는 입력하지 마세요." />
        <div className="flex gap-2 mt-2"><Btn disabled={!input.trim()} onClick={run}>실행</Btn>
          {exec && exec.status === 'RUNNING' && <Btn tone="ghost" onClick={() => hub.cancelExecution(exec.id).then(setExec).catch((x) => setErr(errMsg(x)))}>취소</Btn>}</div>
        <ErrorNote message={err} />
        {exec && (
          <div className="mt-3 rounded-xl bg-[#101c30] border border-white/8 p-3 text-sm space-y-1">
            <div className="flex items-center gap-2"><Badge tone={exec.status === 'SUCCEEDED' ? 'ok' : exec.status === 'FAILED' ? 'error' : 'info'}>{exec.status}</Badge>
              {exec.simulated && <Badge tone="warn">시뮬레이션 — KPI 제외</Badge>}</div>
            {exec.result && <pre className="whitespace-pre-wrap break-words font-sans text-slate-200">{exec.result}</pre>}
            {exec.error && <p className="text-red-300">{exec.error}</p>}
          </div>
        )}
      </Card>
      {hist.data && hist.data.items.length > 0 && (
        <Card title="내 실행 이력"><ul className="text-xs space-y-1">{hist.data.items.map((e) => <li key={e.id}><span className="text-slate-500">{fmtTime(e.requestedAt)}</span> · {e.status}{e.simulated ? ' (시뮬레이션)' : ''} — {e.input?.slice(0, 50)}</li>)}</ul></Card>
      )}
    </div>
  )
}
