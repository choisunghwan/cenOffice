import { askConfirm, askText } from '../ui/confirm'
import { useState } from 'react'
import {
  api, type AiTaskType, type Environment, type Priority, type RequirementItem, type RequirementStatus, type TaskItem, type TaskStatus,
} from '../api'
import { useOffice } from '../store'
import { ACTION_LABEL, Badge, Btn, Card, Empty, ErrorNote, Input, PageHeader, Select, Textarea, errMsg, fmtTime, useLoad } from './kit'

const PRIORITY: Record<Priority, { label: string; tone: 'error' | 'warn' | 'idle' }> = {
  HIGH: { label: '높음', tone: 'error' }, MEDIUM: { label: '보통', tone: 'warn' }, LOW: { label: '낮음', tone: 'idle' },
}
const REQ_STATUS: Record<RequirementStatus, string> = { DRAFT: '초안', APPROVED: '승인됨', IN_PROGRESS: '진행 중', DONE: '완료' }
const TASK_COLS: { key: TaskStatus; label: string }[] = [
  { key: 'TODO', label: '할 일' }, { key: 'IN_PROGRESS', label: '진행 중' }, { key: 'REVIEW', label: '검토' }, { key: 'DONE', label: '완료' },
]
const AI_TYPES: { v: AiTaskType; l: string }[] = [
  { v: 'STATUS_QUERY', l: '상태 조회' }, { v: 'ANALYZE_REQUIREMENT', l: '요구사항 분석' }, { v: 'DRAFT_SPEC', l: '기능 명세 초안' },
  { v: 'GENERATE_TASKS', l: '작업 목록 생성' }, { v: 'CODE_CHANGE', l: '코드 변경' }, { v: 'RUN_TESTS', l: '테스트 실행' },
]

export function ProjectsPage({ initialProjectId }: { initialProjectId?: number }) {
  const [selected, setSelected] = useState<number | null>(initialProjectId ?? null)
  const [name, setName] = useState('')
  const [desc, setDesc] = useState('')
  const [err, setErr] = useState<string | null>(null)
  const projects = useLoad(() => api.projects(), [])
  const user = useOffice((s) => s.user)

  const create = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!name.trim()) return
    try {
      const p = await api.createProject({ name: name.trim(), description: desc.trim() || undefined })
      setName(''); setDesc(''); setErr(null); setSelected(p.id); projects.reload()
    } catch (x) { setErr(errMsg(x)) }
  }
  const current = projects.data?.find((p) => p.id === selected) ?? null

  const remove = async () => {
    if (!current) return
    if (!await askConfirm({ title: '프로젝트를 삭제할까요?', message: `'${current.name}' 프로젝트와 모든 하위 데이터가 삭제되며 되돌릴 수 없습니다.`, okText: '삭제', danger: true })) return
    try { await api.deleteProject(current.id); setSelected(null); projects.reload() } catch (x) { setErr(errMsg(x)) }
  }

  return (
    <>
      <PageHeader title="Projects" desc="프로젝트를 만들고 요구사항·작업·AI 작업을 한 곳에서 관리합니다." />
      <ErrorNote message={err ?? projects.error} />
      <div className="grid gap-5 xl:grid-cols-[320px_1fr]">
        <div className="space-y-4">
          <Card title="새 프로젝트">
            <form onSubmit={create} className="space-y-2">
              <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="프로젝트 이름" maxLength={120} />
              <Textarea value={desc} onChange={(e) => setDesc(e.target.value)} placeholder="설명 (선택)" rows={2} maxLength={2000} />
              <Btn type="submit" disabled={!name.trim()}>프로젝트 만들기</Btn>
            </form>
          </Card>
          <div className="space-y-2">
            {projects.data?.length === 0 && <Empty>프로젝트가 없습니다. 첫 프로젝트를 만들어 보세요.</Empty>}
            {projects.data?.map((p) => (
              <button key={p.id} onClick={() => setSelected(p.id)}
                className={`w-full text-left rounded-xl border px-4 py-3 transition ${selected === p.id ? 'border-sky-400/60 bg-sky-500/10' : 'border-white/8 bg-[#152238] hover:bg-[#1a2a45]'}`}>
                <div className="flex items-center justify-between gap-2">
                  <span className="font-medium truncate">{p.name}</span>
                  {p.status === 'ARCHIVED' && <Badge>보관됨</Badge>}
                </div>
                <div className="text-[11px] text-slate-400 mt-1">요구사항 {p.requirementCount} · 작업 {p.doneCount}/{p.taskCount} 완료 · {p.owner}</div>
                <div className="h-1 rounded bg-white/10 mt-2 overflow-hidden"><div className="h-full bg-sky-400" style={{ width: `${p.taskCount ? (p.doneCount / p.taskCount) * 100 : 0}%` }} /></div>
              </button>
            ))}
          </div>
        </div>
        <div>
          {current ? (
            <ProjectDetail key={current.id} projectId={current.id} name={current.name} description={current.description}
              canDelete={user?.role === 'ADMIN' || user?.username === current.owner} onDelete={remove} />
          ) : <Card><Empty>왼쪽에서 프로젝트를 선택하세요.</Empty></Card>}
        </div>
      </div>
    </>
  )
}

type Tab = 'requirements' | 'tasks' | 'ai'

function ProjectDetail({ projectId, name, description, canDelete, onDelete }: {
  projectId: number; name: string; description?: string; canDelete: boolean; onDelete: () => void
}) {
  const [tab, setTab] = useState<Tab>('requirements')
  return (
    <div>
      <div className="flex items-start justify-between gap-3 mb-3">
        <div>
          <h2 className="text-lg font-semibold">{name}</h2>
          {description && <p className="text-sm text-slate-400 mt-0.5">{description}</p>}
        </div>
        {canDelete && <Btn tone="danger" small onClick={onDelete}>삭제</Btn>}
      </div>
      <div className="flex gap-1 mb-4 border-b border-white/10">
        {([['requirements', '요구사항'], ['tasks', '작업 보드'], ['ai', 'AI 작업']] as [Tab, string][]).map(([k, l]) => (
          <button key={k} onClick={() => setTab(k)} className={`px-4 py-2 text-sm -mb-px border-b-2 ${tab === k ? 'border-sky-400 text-white' : 'border-transparent text-slate-400 hover:text-slate-200'}`}>{l}</button>
        ))}
      </div>
      {tab === 'requirements' && <RequirementsTab projectId={projectId} />}
      {tab === 'tasks' && <TasksTab projectId={projectId} />}
      {tab === 'ai' && <AiTasksTab projectId={projectId} />}
    </div>
  )
}

export function RequirementsTab({ projectId }: { projectId: number }) {
  const list = useLoad(() => api.requirements(projectId), [projectId])
  const [title, setTitle] = useState('')
  const [desc, setDesc] = useState('')
  const [priority, setPriority] = useState<Priority>('MEDIUM')
  const [err, setErr] = useState<string | null>(null)
  const [openId, setOpenId] = useState<number | null>(null)

  const run = async (fn: () => Promise<unknown>) => {
    try { await fn(); setErr(null); list.reload() } catch (x) { setErr(errMsg(x)) }
  }
  const create = (e: React.FormEvent) => {
    e.preventDefault()
    if (!title.trim()) return
    void run(async () => { await api.createRequirement(projectId, { title: title.trim(), description: desc.trim() || undefined, priority }); setTitle(''); setDesc('') })
  }
  const patch = (r: RequirementItem, p: Partial<RequirementItem>) =>
    run(() => api.updateRequirement(r.id, { title: r.title, description: r.description, priority: r.priority, status: r.status, ...p }))

  return (
    <div className="space-y-4">
      <ErrorNote message={err ?? list.error} />
      <Card title="요구사항 등록">
        <form onSubmit={create} className="space-y-2">
          <div className="flex gap-2">
            <Input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="요구사항 제목" maxLength={200} />
            <Select value={priority} onChange={(e) => setPriority(e.target.value as Priority)} small>
              {(Object.keys(PRIORITY) as Priority[]).map((p) => <option key={p} value={p}>우선순위 {PRIORITY[p].label}</option>)}
            </Select>
          </div>
          <Textarea value={desc} onChange={(e) => setDesc(e.target.value)} placeholder="상세 설명 (선택)" rows={2} maxLength={4000} />
          <Btn type="submit" disabled={!title.trim()}>등록</Btn>
        </form>
      </Card>
      <Card title={`요구사항 ${list.data?.length ?? 0}건`}>
        {list.data?.length === 0 && <Empty>등록된 요구사항이 없습니다.</Empty>}
        <ul className="divide-y divide-white/8">
          {list.data?.map((r) => (
            <li key={r.id} className="py-3">
              <div className="flex items-center gap-2 flex-wrap">
                <button className="font-medium text-left hover:underline flex-1 min-w-[10rem]" onClick={() => setOpenId(openId === r.id ? null : r.id)}>{r.title}</button>
                <Badge tone={PRIORITY[r.priority].tone}>{PRIORITY[r.priority].label}</Badge>
                <Select small value={r.status} onChange={(e) => void patch(r, { status: e.target.value as RequirementStatus })}>
                  {(Object.keys(REQ_STATUS) as RequirementStatus[]).map((s) => <option key={s} value={s}>{REQ_STATUS[s]}</option>)}
                </Select>
                <Btn tone="ghost" small onClick={() => run(() => api.deleteRequirement(r.id))}>삭제</Btn>
              </div>
              {openId === r.id && (
                <div className="mt-2 text-sm text-slate-300 space-y-2">
                  {r.description && <p className="whitespace-pre-wrap">{r.description}</p>}
                  <div className="text-[11px] text-slate-500">등록 {r.createdBy} · {fmtTime(r.createdAt)}</div>
                  <HistoryList target={`requirement:${r.id}`} />
                </div>
              )}
            </li>
          ))}
        </ul>
      </Card>
    </div>
  )
}

export function HistoryList({ target }: { target: string }) {
  const h = useLoad(() => api.history(target), [target])
  if (!h.data?.length) return <div className="text-xs text-slate-500">이력이 없습니다.</div>
  return (
    <ul className="text-xs space-y-1 border-l border-white/10 pl-3">
      {h.data.map((x) => (
        <li key={x.id}><span className="text-slate-500">{fmtTime(x.at)}</span> · <b className="text-slate-300">{x.actor}</b> {ACTION_LABEL[x.action] ?? x.action}{x.detail ? <span className="text-slate-500"> — {x.detail}</span> : null}</li>
      ))}
    </ul>
  )
}

function TasksTab({ projectId }: { projectId: number }) {
  const tasks = useLoad(() => api.tasks(projectId), [projectId])
  const reqs = useLoad(() => api.requirements(projectId), [projectId])
  const agents = useLoad(() => api.agents(), [])
  const [title, setTitle] = useState('')
  const [reqId, setReqId] = useState<string>('')
  const [err, setErr] = useState<string | null>(null)
  const [histId, setHistId] = useState<number | null>(null)

  const run = async (fn: () => Promise<unknown>) => {
    try { await fn(); setErr(null); tasks.reload() } catch (x) { setErr(errMsg(x)) }
  }
  const create = (e: React.FormEvent) => {
    e.preventDefault()
    if (!title.trim()) return
    void run(async () => { await api.createTask(projectId, { title: title.trim(), requirementId: reqId ? Number(reqId) : null }); setTitle('') })
  }
  const patch = (t: TaskItem, p: Partial<TaskItem>) =>
    run(() => api.updateTask(t.id, { title: t.title, description: t.description, requirementId: t.requirementId ?? null, status: t.status, assigneeAgentId: t.assigneeAgentId ?? null, ...p }))
  const reqTitle = (id?: number) => reqs.data?.find((r) => r.id === id)?.title

  return (
    <div className="space-y-4">
      <ErrorNote message={err ?? tasks.error} />
      <Card>
        <form onSubmit={create} className="flex gap-2 flex-wrap">
          <div className="flex-1 min-w-[12rem]"><Input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="새 작업 제목" maxLength={200} /></div>
          <Select small value={reqId} onChange={(e) => setReqId(e.target.value)}>
            <option value="">요구사항 연결 없음</option>
            {reqs.data?.map((r) => <option key={r.id} value={r.id}>{r.title}</option>)}
          </Select>
          <Btn type="submit" disabled={!title.trim()}>작업 추가</Btn>
        </form>
      </Card>
      <div className="grid gap-3 md:grid-cols-2 2xl:grid-cols-4">
        {TASK_COLS.map((col) => (
          <div key={col.key} className="rounded-2xl bg-[#101c30] border border-white/6 p-3 min-h-[8rem]">
            <div className="flex items-center justify-between text-xs text-slate-400 px-1 mb-2">
              <span>{col.label}</span><span>{tasks.data?.filter((t) => t.status === col.key).length ?? 0}</span>
            </div>
            <div className="space-y-2">
              {tasks.data?.filter((t) => t.status === col.key).map((t) => (
                <div key={t.id} className="rounded-xl bg-[#18283f] border border-white/8 p-3 text-sm space-y-2">
                  <div className="font-medium break-words">{t.title}</div>
                  {t.requirementId && <div className="text-[11px] text-slate-400 truncate">↳ {reqTitle(t.requirementId) ?? `요구사항 #${t.requirementId}`}</div>}
                  <div className="flex gap-1.5 flex-wrap">
                    <Select small value={t.status} onChange={(e) => void patch(t, { status: e.target.value as TaskStatus })}>
                      {TASK_COLS.map((c) => <option key={c.key} value={c.key}>{c.label}</option>)}
                    </Select>
                    <Select small value={t.assigneeAgentId ?? ''} onChange={(e) => void patch(t, { assigneeAgentId: e.target.value ? Number(e.target.value) : undefined })}>
                      <option value="">담당 AI 없음</option>
                      {agents.data?.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
                    </Select>
                  </div>
                  <div className="flex gap-1.5">
                    <Btn tone="ghost" small onClick={() => setHistId(histId === t.id ? null : t.id)}>이력</Btn>
                    <Btn tone="ghost" small onClick={() => run(() => api.deleteTask(t.id))}>삭제</Btn>
                  </div>
                  {histId === t.id && <HistoryList target={`task:${t.id}`} />}
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

function AiTasksTab({ projectId }: { projectId: number }) {
  const list = useLoad(() => api.aiTasks(projectId), [projectId])
  const agents = useLoad(() => api.agents(), [])
  const [agentId, setAgentId] = useState('')
  const [type, setType] = useState<AiTaskType>('ANALYZE_REQUIREMENT')
  const [env, setEnv] = useState<Environment>('DEV')
  const [input, setInput] = useState('')
  const [err, setErr] = useState<string | null>(null)
  const [openId, setOpenId] = useState<number | null>(null)
  const agent = (id: number) => agents.data?.find((a) => a.id === id)

  const create = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!agentId) return setErr('담당 AI 에이전트를 선택하세요.')
    try {
      await api.createAiTask({ projectId, agentId: Number(agentId), type, environment: env, input: input.trim() || undefined })
      setInput(''); setErr(null); list.reload()
    } catch (x) { setErr(errMsg(x)) }
  }
  const askApproval = async (id: number) => {
    const summary = await askText({ title: '승인을 요청할까요?', message: '승인 요청 내용을 적어 주세요.', okText: '요청', placeholder: '예: 작업 3건을 프로젝트에 등록', required: true })
    if (!summary) return
    try { await api.requestApproval(id, { kind: 'MANUAL_REVIEW', summary: summary.trim() }); list.reload() } catch (x) { setErr(errMsg(x)) }
  }

  return (
    <div className="space-y-4">
      <Card title="AI 작업 접수" action={<Badge tone="warn">실행 엔진은 Phase 3B</Badge>}>
        <form onSubmit={create} className="space-y-2">
          <div className="flex gap-2 flex-wrap">
            <Select small value={agentId} onChange={(e) => setAgentId(e.target.value)}>
              <option value="">에이전트 선택</option>
              {agents.data?.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
            </Select>
            <Select small value={type} onChange={(e) => setType(e.target.value as AiTaskType)}>{AI_TYPES.map((t) => <option key={t.v} value={t.v}>{t.l}</option>)}</Select>
            <Select small value={env} onChange={(e) => setEnv(e.target.value as Environment)}>
              {(['DEV', 'STG', 'PROD'] as Environment[]).map((x) => <option key={x} value={x}>{x}</option>)}
            </Select>
          </div>
          <Textarea value={input} onChange={(e) => setInput(e.target.value)} rows={2} maxLength={4000} placeholder="지시 내용 (선택)" />
          <ErrorNote message={err} />
          <Btn type="submit">접수</Btn>
          <p className="text-[11px] text-slate-500">환경/권한 검사는 서버가 수행합니다. PROD 는 어떤 에이전트도 접근할 수 없고, STG 는 읽기·테스트만 허용됩니다.</p>
        </form>
      </Card>
      <Card title={`AI 작업 ${list.data?.length ?? 0}건`}>
        {list.data?.length === 0 && <Empty>접수된 AI 작업이 없습니다.</Empty>}
        <ul className="divide-y divide-white/8">
          {list.data?.map((t) => (
            <li key={t.id} className="py-3 text-sm">
              <div className="flex items-center gap-2 flex-wrap">
                <button className="font-medium hover:underline" onClick={() => setOpenId(openId === t.id ? null : t.id)}>#{t.id} {AI_TYPES.find((x) => x.v === t.type)?.l}</button>
                <Badge tone="info">{t.environment}</Badge>
                <span className="text-slate-400 text-xs">{agent(t.agentId)?.name}</span>
                <Badge tone={t.status === 'WAITING_APPROVAL' ? 'warn' : t.status === 'REJECTED' || t.status === 'FAILED' ? 'error' : t.status === 'DONE' ? 'ok' : 'idle'}>{t.status}</Badge>
                <span className="flex-1" />
                {(t.status === 'QUEUED' || t.status === 'RUNNING') && <Btn tone="ghost" small onClick={() => askApproval(t.id)}>승인 요청</Btn>}
              </div>
              {openId === t.id && <HistoryList target={`aitask:${t.id}`} />}
            </li>
          ))}
        </ul>
      </Card>
    </div>
  )
}
