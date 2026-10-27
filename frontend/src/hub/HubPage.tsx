import { useEffect, useState } from 'react'
import { useOffice } from '../store'
import { hub } from '../hubApi'
import { Btn, Card, Empty, ErrorNote, Input, Select, Stat, useLoad } from '../workspace/kit'
import { AgentDetailView, AgentForm } from './AgentViews'
import { CreatorDashboard } from './CreatorDashboard'
import { Pager } from './Pager'
import { ReviewQueue } from './ReviewQueue'
import { SkillDetailView } from './SkillDetailView'
import { SkillForm } from './SkillForm'
import { AgentCard, RuntimeBanner, SkillCard } from './shared'
import { useToolFavorites } from './useToolFavorites'
import { toolName } from './toolNames'

type Tab = 'browse' | 'mine' | 'register' | 'review'
type View = { kind: 'skill' | 'agent'; id: number; run?: boolean } | null

/** WORKSPACE > AI HUB: 전사 AI Skill / Agent 둘러보기, 등록, 제작자 대시보드, 관리자 승인 */
export function HubPage({ initialTab = 'browse' }: { initialTab?: Tab }) {
  const user = useOffice((s) => s.user)
  const admin = user?.role === 'ADMIN'
  const tab = initialTab
  const setPage = useOffice((s) => s.setWorkspacePage)
  const [view, setView] = useState<View>(null)
  useEffect(() => {
    const reset = () => setView(null)
    window.addEventListener('hub-navigation', reset)
    return () => window.removeEventListener('hub-navigation', reset)
  }, [])
  const [regKind, setRegKind] = useState<'skill' | 'agent'>('skill')
  const categories = useLoad(() => hub.categories(), [])
  const runtime = useLoad(() => hub.runtime(), [])
  const [tick, setTick] = useState(0)
  const bump = () => setTick((t) => t + 1)

  const open = (v: View) => setView(v)
  const goTab = (page: string) => { setView(null); setPage(page); window.dispatchEvent(new Event('hub-navigation')) }
  const TABS: { tab: Tab; page: string; icon: string; title: string; desc: string }[] = [
    { tab: 'browse', page: 'home', icon: '✧', title: '모든 도구', desc: '승인된 AI 도구를 찾고 바로 써 보세요' },
    { tab: 'mine', page: 'hub-mine', icon: '▤', title: '내 도구', desc: '내가 만든 도구와 동료의 피드백' },
    { tab: 'register', page: 'hub-register', icon: '＋', title: '도구 만들기', desc: '업무 노하우를 도구로 등록하고 나누기' },
    ...(admin ? [{ tab: 'review' as Tab, page: 'hub-review', icon: '✓', title: '공개 승인', desc: '등록된 도구를 검토하고 공개' }] : []),
  ]

  return (
    <>
      {!view && <nav className="hub-switch" aria-label="도구 화면 선택">{TABS.map((t) => <button key={t.tab} type="button" onClick={() => goTab(t.page)} aria-current={tab === t.tab ? 'page' : undefined}><span className="hub-switch-icon" aria-hidden="true">{t.icon}</span><span><b>{t.title}</b><small>{t.desc}</small></span></button>)}</nav>}
      {!view && tab === 'browse' && <header className="hub-hero">
        <div className="hub-eyebrow"><span className="hub-spark">✧</span> ITCEN AI HUB <span className="hub-eyebrow-line" /></div>
        <h1>오늘의 업무,<br /><span>잘하는 AI와 함께.</span></h1>
        <p>자료 정리부터 제안서 작성까지. 결과 예시를 살펴보고,<br />내 업무에 맞는 AI 도구를 찾아보세요.</p>
        <div className="hub-hero-actions"><button onClick={() => { setPage('home'); setView(null); document.getElementById('hub-discover')?.scrollIntoView({ behavior: 'smooth', block: 'start' }) }} className="hub-primary">도구 찾아보기 <span aria-hidden="true">↗</span></button></div>
        <div className="hub-hero-photo" aria-hidden="true"><img src="/images/hub-hero.svg" alt="" /></div>
        <div className="hub-hero-note"><span>01 도구 선택</span><span>02 자료와 요청 입력</span><span>03 결과 확인</span></div>
      </header>}
      {!view && tab !== 'browse' && <header className="hub-page-heading"><p>ITCEN AI HUB</p><h1>{tab === 'mine' ? '내 도구' : tab === 'register' ? '도구 만들기' : '공개 승인'}</h1><span>{tab === 'mine' ? '공유한 도구와 동료들의 피드백을 한곳에서 확인하세요.' : tab === 'register' ? '팀의 업무 노하우를 등록하고, 검토 후 동료들과 나누세요.' : '등록된 도구의 내용과 공개 범위를 확인하세요.'}</span></header>}

      {view?.kind === 'skill' && <SkillDetailView key={`s${view.id}-${view.run}`} id={view.id} startWithRun={view.run} onBack={() => setView(null)} onChanged={bump} />}
      {view?.kind === 'agent' && <AgentDetailView key={`a${view.id}-${view.run}`} id={view.id} startWithRun={view.run} onBack={() => setView(null)} onChanged={bump} />}

      {!view && tab === 'browse' && <><section id="hub-discover" className="scroll-mt-6"><h2 className="text-lg font-semibold mb-2">우리 회사의 AI 도구</h2><p className="text-sm text-slate-400 mb-5">동료들이 공유한 도구를 찾아보고 사용 방법을 확인하세요.</p><Browse key={tick} categories={categories.data ?? []} onOpen={open} /></section></>}
      {!view && tab === 'mine' && <CreatorDashboard key={tick} onOpenSkill={(id) => open({ kind: 'skill', id })} onOpenAgent={(id) => open({ kind: 'agent', id })} />}
      {!view && tab === 'register' && (
        <div>
          {regKind === 'skill'
            ? <SkillForm categories={categories.data ?? []} onDone={(id) => { bump(); open({ kind: 'skill', id }) }} />
            : <AgentForm onDone={(id) => { bump(); open({ kind: 'agent', id }) }} />}
          <details className="hub-advanced"><summary>고급 설정 · 도구 구성 방식</summary><label className="block mt-4 text-sm">구성 방식<Select value={regKind} onChange={(e) => setRegKind(e.target.value as 'skill' | 'agent')}><option value="skill">업무 지침으로 만들기</option><option value="agent">기존 도구를 조합해서 만들기</option></Select></label></details>
        </div>
      )}
      {!view && tab === 'review' && admin && <ReviewQueue key={tick} onOpenSkill={(id) => open({ kind: 'skill', id })} onOpenAgent={(id) => open({ kind: 'agent', id })} onChanged={bump} />}
      <details className="hub-advanced mt-8"><summary>서비스 연결 정보</summary><div className="mt-3"><RuntimeBanner runtime={runtime.data ?? undefined} /></div></details>
    </>
  )
}

function Browse({ categories, onOpen }: { categories: { code: string; name: string }[]; onOpen: (v: View) => void }) {
  const overview = useLoad(() => hub.overview(), [])
  const [q, setQ] = useState('')
  const [applied, setApplied] = useState('')
  const [type, setType] = useState<'all' | 'skill' | 'agent'>('all')
  const [category, setCategory] = useState('')
  const [owner, setOwner] = useState('')
  const [tag, setTag] = useState('')
  const [sort, setSort] = useState('recent')
  const [page, setPage] = useState(0)
  const skills = useLoad(() => (type === 'agent' ? Promise.resolve(null) : hub.skills({ q: applied, category, owner, tag, sort, page, size: 12, status: 'APPROVED' })), [applied, type, category, owner, tag, sort, page])
  const agents = useLoad(() => (type === 'skill' || category ? Promise.resolve(null) : hub.agents({ q: applied, owner, page, size: 12 })), [applied, type, category, owner, page])
  const ov = overview.data
  const user = useOffice((s) => s.user)
  const { favorites } = useToolFavorites()
  const saved = useLoad(async () => {
    const results = await Promise.allSettled(favorites.slice(-12).reverse().map(async (key) => {
      const [kind, value] = key.split(':'); const id = Number(value)
      if (!Number.isSafeInteger(id) || id <= 0) return null
      if (kind === 'skill') { const d = await hub.skill(id); return { key, name: toolName(d.summary.name, d.summary.title), view: { kind: 'skill' as const, id }, ready: d.summary.executionState === 'RUNTIME_AVAILABLE' && d.summary.status === 'APPROVED' } }
      if (kind === 'agent') { const d = await hub.agent(id); return { key, name: d.summary.name, view: { kind: 'agent' as const, id }, ready: d.summary.executionState === 'RUNTIME_AVAILABLE' && d.summary.status === 'APPROVED' } }
      return null
    }))
    return results.flatMap((r) => r.status === 'fulfilled' && r.value ? [r.value] : [])
  }, [JSON.stringify(favorites)])
  const history = useLoad(() => hub.executions(), [])
  const recent = (history.data?.items ?? []).filter((e) => e.requester === user?.username).filter((e, i, all) => all.findIndex((x) => x.skillId === e.skillId && x.agentId === e.agentId) === i).slice(0, 4)
  const hot = (ov?.popular ?? []).filter((s) => s.executions > 0 && s.executionState === 'RUNTIME_AVAILABLE' && s.status === 'APPROVED').slice(0, 3)

  const search = (e: React.FormEvent) => { e.preventDefault(); setApplied(q.trim()); setPage(0) }
  const total = Math.max(skills.data?.total ?? 0, agents.data?.total ?? 0)
  const pages = Math.max(1, Math.ceil(total / 12))

  return (
    <div className="space-y-5">
      <div className="hub-return-shelves">
        <section><h3>★ 즐겨찾는 도구</h3>{saved.loading ? <p role="status">불러오는 중…</p> : !saved.data?.length ? <p>도구 카드의 ☆을 누르면 여기에 모아둘 수 있어요.</p> : <div className="hub-return-links">{saved.data.map((s) => <button key={s.key} onClick={() => onOpen({ ...s.view, run: s.ready })}>{s.name}<span>→</span></button>)}</div>}</section>
        <section><h3>↺ 최근 사용</h3>{!recent.length ? <p>처음 사용한 도구부터 여기에 기록됩니다.</p> : <div className="hub-return-links">{recent.map((e) => <button key={e.id} onClick={() => onOpen(e.skillId ? { kind: 'skill', id: e.skillId, run: true } : { kind: 'agent', id: e.agentId!, run: true })}>{toolName(e.targetName)}<span>다시 사용 →</span></button>)}</div>}</section>
      </div>
      {!!hot.length && <section className="hub-hot"><div className="hub-hot-heading"><h3>HOT · 동료들이 많이 쓰는 도구</h3><span>누적 실제 실행 기준</span></div><div className="hub-tool-grid">{hot.map((s) => <SkillCard key={s.id} s={s} onClick={() => onOpen({ kind: 'skill', id: s.id })} onUse={() => onOpen({ kind: 'skill', id: s.id, run: true })} />)}</div></section>}
      <ErrorNote message={overview.error} />
      <details className="hub-advanced"><summary>활용 통계 보기</summary><div className="grid gap-3 grid-cols-2 lg:grid-cols-5 mt-4"><Stat label="등록된 업무 지침" value={ov?.skills ?? '–'} /><Stat label="공개된 업무 지침" value={ov?.approvedSkills ?? '–'} /><Stat label="등록된 조합 도구" value={ov?.agents ?? '–'} /><Stat label="이번 달 실행" value={ov?.executionsThisMonth ?? '–'} /><Stat label="활성 사용자 (30일)" value={ov?.activeUsers ?? '–'} /></div></details>
      <Card>
        <form onSubmit={search} className="flex gap-2 flex-wrap items-end">
          <div className="flex-1 min-w-[14rem]"><Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="어떤 업무를 도와드릴까요? 이름이나 설명으로 검색" aria-label="검색" /></div>

          <Select small value={category} onChange={(e) => { setCategory(e.target.value); setPage(0) }} aria-label="카테고리"><option value="">모든 카테고리</option>{categories.map((c) => <option key={c.code} value={c.code}>{c.name}</option>)}</Select>
<Btn type="submit">검색</Btn><details className="w-full text-xs text-slate-400 pt-2"><summary className="cursor-pointer w-fit">상세 필터</summary><div className="flex flex-wrap gap-3 pt-3">          <Select small value={type} onChange={(e) => { setType(e.target.value as typeof type); setPage(0) }} aria-label="유형"><option value="all">모든 구성</option><option value="skill">업무 지침형</option><option value="agent">도구 조합형</option></Select>          <div className="w-32"><Input value={owner} onChange={(e) => setOwner(e.target.value)} onBlur={() => setPage(0)} placeholder="제작자 ID" aria-label="제작자" /></div>
          <div className="w-28"><Input value={tag} onChange={(e) => setTag(e.target.value)} onBlur={() => setPage(0)} placeholder="#태그" aria-label="태그" /></div>
          <Select small value={sort} onChange={(e) => setSort(e.target.value)} aria-label="정렬"><option value="recent">최신순</option><option value="popular">인기순</option></Select>
</div></details>

        </form>
      </Card>

      <ErrorNote message={skills.error ?? agents.error} />
      {(skills.loading || agents.loading) ? <p role="status" className="py-12 text-center text-slate-400">도구를 불러오고 있습니다…</p> : <>
        <div className="hub-tool-grid">
          {skills.data?.items.map((s) => <SkillCard key={`s${s.id}`} s={s} onClick={() => onOpen({ kind: 'skill', id: s.id })} onUse={() => onOpen({ kind: 'skill', id: s.id, run: true })} />)}
          {agents.data?.items.map((a) => <AgentCard key={`a${a.id}`} a={a} onClick={() => onOpen({ kind: 'agent', id: a.id })} onUse={() => onOpen({ kind: 'agent', id: a.id, run: true })} />)}
        </div>
        {!skills.error && !agents.error && !skills.data?.items.length && !agents.data?.items.length && <Card><Empty>아직 표시할 도구가 없습니다. 검색 조건을 바꾸거나 ‘도구 만들기’로 첫 도구를 공유해 보세요.</Empty></Card>}
      </>}
      <Pager page={page} pages={pages} total={total} onChange={(p) => { setPage(p); document.getElementById('hub-discover')?.scrollIntoView({ behavior: 'smooth', block: 'start' }) }} />
    </div>
  )
}
