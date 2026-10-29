import { askText } from '../common/confirm'
import { ToolIntroduction } from './ToolIntroduction'
import { useState } from 'react'
import { hub } from '../hubApi'
import { Badge, Btn, Card, Empty, ErrorNote, Input, Select, Textarea, errMsg, fmtTime, useLoad } from '../workspace/kit'
import { Field, RuntimeBanner, StatusBadge } from './shared'
import { RunTab } from './SkillDetailView'

export function AgentDetailView({ id, onBack, onChanged, startWithRun = false }: { startWithRun?: boolean; id: number; onBack: () => void; onChanged: () => void }) {
  const d = useLoad(() => hub.agent(id), [id])
  const approvedSkills = useLoad(() => hub.skills({ status: 'APPROVED', size: 100 }), [])
  const [err, setErr] = useState<string | null>(null)
  const [pick, setPick] = useState('')
  const [tab, setTab] = useState<'info' | 'run'>(startWithRun ? 'run' : 'info')
  const act = async (fn: () => Promise<unknown>, done?: () => void) => { try { await fn(); setErr(null); d.reload(); onChanged(); done?.() } catch (x) { setErr(errMsg(x)) } }
  if (!d.data) return <><button className="text-sm text-slate-400 mb-3" onClick={onBack}>← 목록</button><ErrorNote message={d.error} /></>
  const { summary: a, skills, versions, canEdit, canApprove, runtime } = d.data
  const linked = new Set(skills.map((s) => s.skillId))
  const candidates = (approvedSkills.data?.items ?? []).filter((s) => !linked.has(s.id))
  return (
    <div>
      <button className="text-sm text-slate-400 hover:text-white mb-3" onClick={onBack}>← 목록으로</button>
      <details className="hub-advanced"><summary>도구 정보 · 관리</summary><div className="flex items-start gap-4 flex-wrap my-4">
        <div className="w-14 h-14 rounded-2xl bg-violet-500/20 grid place-items-center text-3xl">🤖</div>
        <div className="flex-1 min-w-[16rem]">
          <div className="flex items-center gap-2 flex-wrap"><h2 className="text-xl font-bold">{a.name}</h2><StatusBadge status={a.status} /><Badge tone="idle">v{a.version}</Badge>{a.sample && <Badge tone="warn">샘플 (KPI 제외)</Badge>}</div>
          <p className="text-sm text-slate-300 mt-1">{a.description}</p>
          <div className="text-xs text-slate-400 mt-1">제작자 {a.ownerName} · 담당 업무: {a.duties || '-'} · 수정 {fmtTime(a.updatedAt)}</div>
        </div>
        <div className="flex gap-2 flex-wrap">
          {canEdit && (a.status === 'DRAFT' || a.status === 'REJECTED') && <Btn onClick={() => act(() => hub.submitAgent(id))}>검토 요청</Btn>}
          {canApprove && a.status === 'PENDING_REVIEW' && <>
            <Btn onClick={() => act(() => hub.approveAgent(id))}>승인</Btn>
            <Btn tone="danger" onClick={async () => { const c = await askText({ title: '반려할까요?', message: '반려 사유를 적어 주세요.', okText: '반려', danger: true, placeholder: '사유를 입력해 주세요', required: true }); if (c) void act(() => hub.rejectAgent(id, c)) }}>반려</Btn>
          </>}
        </div>
      </div>
      <RuntimeBanner runtime={runtime} /></details>
      <ErrorNote message={err ?? d.error} />
      <div className="flex gap-1 my-4 border-b border-white/10">
        {([['info', '소개'], ['run', '사용하기']] as const).map(([k, l]) => <button key={k} onClick={() => setTab(k)} className={`px-4 py-2 text-sm -mb-px border-b-2 ${tab === k ? 'border-sky-400 text-white' : 'border-transparent text-slate-400'}`}>{l}</button>)}
      </div>
      {tab === 'run' ? <RunTab target={{ agentId: id }} runtimeReady={runtime.state === 'RUNTIME_AVAILABLE'} approved={a.status === 'APPROVED'} runtime={runtime} /> : (
        <><ToolIntroduction name={a.name} description={a.description} owner={a.ownerName} category="업무 자동화" usage={a.duties} ready={runtime.state === 'RUNTIME_AVAILABLE' && a.status === 'APPROVED'} simulated={runtime.simulated} onUse={() => setTab('run')} /><details className="hub-advanced"><summary>연결된 도구와 구성 정보</summary><div className="grid gap-4 lg:grid-cols-2 mt-4">
          <Card title={`연결된 업무 지침 (${skills.length})`}>
            {skills.length === 0 && <Empty>연결된 업무 지침 이 없습니다.</Empty>}
            <ul className="divide-y divide-white/8 text-sm">{skills.map((s) => (
              <li key={s.skillId} className="py-2 flex items-center gap-2"><span>{s.icon || '🧩'}</span><b className="flex-1 truncate">{s.name}</b><StatusBadge status={s.status} />
                {canEdit && <Btn small tone="ghost" onClick={() => act(() => hub.unlinkSkill(id, s.skillId))}>해제</Btn>}</li>
            ))}</ul>
            {canEdit && (
              <div className="flex gap-2 mt-3">
                <Select small value={pick} onChange={(e) => setPick(e.target.value)}><option value="">승인된 업무 지침 선택</option>{candidates.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}</Select>
                <Btn small disabled={!pick} onClick={() => act(() => hub.linkSkill(id, Number(pick)), () => setPick(''))}>연결</Btn>
              </div>
            )}
            <p className="text-[11px] text-slate-500 mt-2">승인된 업무 지침 만 연결할 수 있고, 구성을 바꾸면 버전이 올라가며 재승인이 필요합니다(승인 전에는 실행 불가).</p>
          </Card>
          <Card title="실행 설정 / 버전">
            {canEdit ? <pre className="text-xs font-mono bg-[#101c30] rounded-lg p-3 whitespace-pre-wrap">{d.data.config || '{}'}</pre> : <Empty>제작자와 관리자만 볼 수 있습니다.</Empty>}
            {canEdit && <ul className="text-xs text-slate-400 mt-3 space-y-0.5">{versions.map((v) => <li key={v.id}>v{v.version} · {v.createdBy} · {fmtTime(v.createdAt)}</li>)}</ul>}
          </Card>
        </div></details></>
      )}
    </div>
  )
}

/** AI 도구 등록 */
export function AgentForm({ onDone }: { onDone: (id: number) => void }) {
  const skills = useLoad(() => hub.skills({ status: 'APPROVED', size: 100 }), [])
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [duties, setDuties] = useState('')
  const [config, setConfig] = useState('{\n  "maxSteps": 5,\n  "outputFormat": "markdown"\n}')
  const [visibility, setVisibility] = useState('PUBLIC')
  const [sel, setSel] = useState<number[]>([])
  const [submit, setSubmit] = useState(true)
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const toggle = (id: number) => setSel((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]))
  const send = async () => {
    setBusy(true)
    try { onDone((await hub.createAgent({ name, description, duties, config, visibility, skillIds: sel, submit })).summary.id) } catch (x) { setErr(errMsg(x)) } finally { setBusy(false) }
  }
  return (
    <div className="space-y-4 max-w-3xl">
      <Card title="AI 도구 등록">
        <div className="grid gap-3 md:grid-cols-2">
          <Field label="이름"><Input value={name} onChange={(e) => setName(e.target.value)} maxLength={63} placeholder="예: 개발 지원 AI 도구" /></Field>
          <Field label="공개 범위"><Select value={visibility} onChange={(e) => setVisibility(e.target.value)}><option value="PUBLIC">전사 공개 (승인 후)</option><option value="TEAM">우리 팀만 (승인 후)</option></Select></Field>
          <div className="md:col-span-2"><Field label="설명"><Textarea rows={2} value={description} onChange={(e) => setDescription(e.target.value)} maxLength={1000} /></Field></div>
          <div className="md:col-span-2"><Field label="담당 업무"><Input value={duties} onChange={(e) => setDuties(e.target.value)} maxLength={1000} placeholder="예: 오류 분석, 코드 리뷰, 테스트 케이스 작성" /></Field></div>
          <div className="md:col-span-2"><Field label="실행 설정 (JSON · 허용 키: maxSteps, temperature, outputFormat, notes)"><Textarea rows={4} value={config} onChange={(e) => setConfig(e.target.value)} className="font-mono" /></Field></div>
        </div>
      </Card>
      <Card title={`업무 지침 선택 (${sel.length}/10) — 승인된 업무 지침 만 연결할 수 있습니다`}>
        {!skills.data?.items.length ? <Empty>연결할 수 있는 승인된 업무 지침 이 없습니다.</Empty> : (
          <div className="grid gap-1.5 md:grid-cols-2 max-h-60 overflow-y-auto">
            {skills.data.items.map((s) => (
              <label key={s.id} className="flex items-center gap-2 text-sm rounded-lg bg-white/5 px-3 py-1.5 cursor-pointer"><input type="checkbox" checked={sel.includes(s.id)} onChange={() => toggle(s.id)} />
                <span>{s.icon || '🧩'}</span><span className="truncate">{s.name}</span></label>
            ))}
          </div>
        )}
        <label className="text-sm text-slate-300 flex items-center gap-2 mt-3"><input type="checkbox" checked={submit} onChange={(e) => setSubmit(e.target.checked)} />등록과 동시에 관리자 검토 요청</label>
        <ErrorNote message={err} />
        <div className="mt-3"><Btn disabled={busy || !name.trim() || !description.trim()} onClick={send}>{busy ? '등록 중…' : 'AI 도구 등록'}</Btn></div>
        <p className="text-[11px] text-slate-500 mt-2">AI 도구 실행은 실행 런타임이 연결된 경우에만 활성화됩니다.</p>
      </Card>
    </div>
  )
}
