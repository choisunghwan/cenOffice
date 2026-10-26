import type { ReactNode } from 'react'
import type { HubAgentSummary, HubRuntime, HubSkillSummary } from '../hubApi'
import { theme } from '../theme'
import { Badge } from '../workspace/kit'
import { ToolPreview } from './ToolPreview'
import { useToolFavorites } from './useToolFavorites'
import { toolName } from './toolNames'

export const STATUS: Record<string, { label: string; tone: 'ok' | 'warn' | 'error' | 'idle' }> = {
  DRAFT: { label: '임시 저장', tone: 'idle' },
  PENDING_REVIEW: { label: '검토 대기', tone: 'warn' },
  APPROVED: { label: '승인됨', tone: 'ok' },
  REJECTED: { label: '반려', tone: 'error' },
  ARCHIVED: { label: '보관됨', tone: 'idle' },
}

export const EXEC_STATE: Record<string, { label: string; tone: 'ok' | 'warn' | 'idle' | 'error' }> = {
  REGISTRY_ONLY: { label: '등록만 (미승인)', tone: 'idle' },
  RUNTIME_NOT_CONFIGURED: { label: '실행 연동 준비 중', tone: 'warn' },
  RUNTIME_AVAILABLE: { label: '실행 가능', tone: 'ok' },
  EXECUTION_FAILED: { label: '실행 실패', tone: 'error' },
}

export const ACTION_KO: Record<string, string> = {
  REGISTERED: '등록', SUBMITTED: '검토 요청', APPROVED: '승인', REJECTED: '반려', ROLLED_BACK: '이전 버전으로 되돌림', VERSION_ADDED: '새 버전 추가', ARCHIVED: '보관',
}

export function StatusBadge({ status }: { status: string }) {
  const s = STATUS[status] ?? { label: status, tone: 'idle' as const }
  return <Badge tone={s.tone}>{s.label}</Badge>
}

export function Stars({ avg, count }: { avg: number; count: number }) {
  if (!count) return <span className="text-[11px] text-slate-500">평가 없음</span>
  return <span className="text-[11px] text-amber-300">★ {avg.toFixed(1)} <span className="text-slate-500">({count})</span></span>
}

/** 런타임 연동 상태 안내 — 연동이 없으면 "실행 연동 준비 중"을 명확히 표시한다 */
export function RuntimeBanner({ runtime }: { runtime?: HubRuntime }) {
  if (!runtime) return null
  const ready = runtime.state === 'RUNTIME_AVAILABLE'
  const c = ready ? (runtime.simulated ? theme.status.warn : theme.status.ok) : theme.status.warn
  return (
    <div className="rounded-xl px-3 py-2 text-xs flex items-start gap-2" style={{ background: `${c}1f`, color: c }}>
      <span className="font-semibold shrink-0">{ready ? (runtime.simulated ? '시뮬레이션 런타임' : '실행 가능') : '실행 연동 준비 중'}</span>
      <span className="opacity-90">{runtime.description}</span>
    </div>
  )
}

export function SkillCard({ s, onClick, onUse }: { s: HubSkillSummary; onClick: () => void; onUse?: () => void }) {
  return <ToolCard id={s.id} kind="skill" name={s.name} title={s.title} description={s.description} category={s.categoryName} state={s.executionState} status={s.status} sample={s.sample} onClick={onClick} onUse={onUse} />
}

export function AgentCard({ a, onClick, onUse }: { a: HubAgentSummary; onClick: () => void; onUse?: () => void }) {
  return <ToolCard id={a.id} kind="agent" name={a.name} description={a.duties || a.description} category="업무 자동화" state={a.executionState} status={a.status} sample={a.sample} onClick={onClick} onUse={onUse} />
}

function ToolCard({ id, kind, name, title: titleProp, description, category, state, status, sample, onClick, onUse }: {
  id: number; kind: 'skill' | 'agent'; name: string; title?: string | null; description: string; category: string; state: string; status: string; sample: boolean; onClick: () => void; onUse?: () => void
}) {
  const ready = state === 'RUNTIME_AVAILABLE' && status === 'APPROVED'
  const { favorites, toggle } = useToolFavorites()
  const favoriteKey = `${kind}:${id}`
  const saved = favorites.includes(favoriteKey)
  const title = toolName(name, titleProp)
  return <article className="tool-gallery-card">
    <div className="relative"><button className="tool-preview-button" onClick={onClick} aria-label={`${title} 미리보기 및 상세 설명`}><ToolPreview id={id} kind={kind} name={title} /></button><button className="tool-favorite" aria-label={`${title} 즐겨찾기`} aria-pressed={saved} onClick={() => toggle(favoriteKey)}>{saved ? '★' : '☆'}</button></div>
    <div className="tool-gallery-body">
      <div className="tool-gallery-meta"><span>{category}</span>{sample ? <span>샘플 도구</span> : !ready && <span>{EXEC_STATE[state]?.label || '실행 준비 중'}</span>}</div>
      <h3>{title}</h3>
      <p className="tool-gallery-description">{description}</p>
      <div className="tool-gallery-actions"><button className="tool-use-button" onClick={ready ? onUse ?? onClick : onClick}>{ready ? '사용하기' : '미리보기'} <span aria-hidden="true">→</span></button><button className="tool-detail-button" onClick={onClick}>자세히 보기</button></div>
    </div>
  </article>
}

/** 간단한 막대 그래프 (외부 라이브러리 없이) */
export function Bars({ data, height = 56 }: { data: { label: string; value: number }[]; height?: number }) {
  const max = Math.max(1, ...data.map((d) => d.value))
  return (
    <div className="flex items-end gap-1" style={{ height }}>
      {data.map((d) => (
        <div key={d.label} className="flex-1 flex flex-col items-center justify-end h-full" title={`${d.label}: ${d.value}`}>
          <div className="w-full rounded-t bg-sky-400/80" style={{ height: `${(d.value / max) * 100}%`, minHeight: d.value ? 3 : 1, opacity: d.value ? 1 : 0.25 }} />
        </div>
      ))}
    </div>
  )
}

export function Field({ label, children }: { label: string; children: ReactNode }) {
  return <label className="block"><span className="text-xs text-slate-400 mb-1 block">{label}</span>{children}</label>
}
