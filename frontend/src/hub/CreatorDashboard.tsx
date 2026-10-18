import { hub } from '../hubApi'
import { Badge, Card, Empty, ErrorNote, Stat, fmtTime, useLoad } from '../workspace/kit'
import { AgentCard, Bars, SkillCard } from './shared'

/** 제작자 대시보드: 내가 만든 Skill/Agent, 사용 추이, 평가, 월별 실적 (원자료 지표만 — 점수/보상은 산정하지 않음) */
export function CreatorDashboard({ onOpenSkill, onOpenAgent }: { onOpenSkill: (id: number) => void; onOpenAgent: (id: number) => void }) {
  const st = useLoad(() => hub.myStats(), [])
  if (!st.data) return <ErrorNote message={st.error} />
  const d = st.data
  return (
    <div className="space-y-5">
      <div className="hub-tool-grid">
        {d.skills.map((s) => <SkillCard key={`s${s.id}`} s={s} onClick={() => onOpenSkill(s.id)} />)}
        {d.agents.map((a) => <AgentCard key={`a${a.id}`} a={a} onClick={() => onOpenAgent(a.id)} />)}
      </div>
      {!d.skills.length && !d.agents.length && <Card><Empty>아직 만든 도구가 없습니다. 왼쪽 ‘도구 만들기’에서 업무 노하우를 공유해 보세요.</Empty></Card>}
      <details className="hub-advanced"><summary>사용 통계와 동료들의 피드백 보기</summary><div className="space-y-5 mt-5">
      <div className="grid gap-3 grid-cols-2 lg:grid-cols-5">
        <Stat label="등록한 업무 지침" value={d.skillsRegistered} />
        <Stat label="공개된 업무 지침" value={d.skillsApproved} tone="ok" />
        <Stat label="등록한 조합 도구" value={d.agentsRegistered} />
        <Stat label="실행 요청 (집계)" value={d.totals.requests} tone="info" />
        <Stat label="성공 / 실패" value={`${d.totals.succeeded} / ${d.totals.failed}`} />
      </div>
      <div className="grid gap-3 grid-cols-2 lg:grid-cols-4">
        <Stat label="고유 사용자" value={d.totals.uniqueUsers} />
        <Stat label="재사용자" value={d.totals.repeatUsers} />
        <Stat label="최근 7일" value={d.totals.last7d} />
        <Stat label="최근 30일" value={d.totals.last30d} />
      </div>
      <div className="grid gap-4 lg:grid-cols-2">
        <Card title="월별 활용 실적 (최근 6개월 · 실행 요청)">
          <Bars data={d.monthly.map((m) => ({ label: m.month, value: m.requests }))} height={80} />
          <div className="flex justify-between text-[10px] text-slate-500 mt-1">{d.monthly.map((m) => <span key={m.month}>{m.month.slice(5)}월<br />{m.requests}</span>)}</div>
        </Card>
        <Card title="최근 사용자 피드백">
          {!d.recentFeedback.length ? <Empty>아직 평가가 없습니다.</Empty> : (
            <ul className="text-sm divide-y divide-white/8">{d.recentFeedback.map((r) => (
              <li key={r.id} className="py-2"><span className="text-amber-300">{'★'.repeat(r.rating)}</span> <b className="ml-1">{r.reviewerName}</b> <span className="text-xs text-slate-500">{fmtTime(r.updatedAt)}</span>
                {r.comment && <p className="text-slate-300 text-xs">{r.comment}</p>}</li>
            ))}</ul>
          )}
        </Card>
      </div>
      <div className="text-[11px] text-slate-500 space-y-1">
        <p><Badge tone="idle">집계 제외</Badge> 시뮬레이션 {d.exclusions.simulated}건 · 제작자 본인 실행 {d.exclusions.creatorSelf}건 · 샘플 {d.exclusions.sample}건</p>
        <p>{d.note}</p>
      </div>
      </div></details>
    </div>
  )
}
