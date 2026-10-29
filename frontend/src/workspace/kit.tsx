import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { ApiError } from '../api'
import { theme } from '../theme'

/** 데이터 로더: 마운트/의존성 변경/서버의 workspace.changed 이벤트 시 다시 불러온다. */
export function useLoad<T>(fn: () => Promise<T>, deps: unknown[], enabled = true) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const fnRef = useRef(fn)
  fnRef.current = fn
  const seq = useRef(0)
  const reload = useCallback(() => {
    if (!enabled) return
    const my = ++seq.current // 늦게 도착한 이전 요청의 응답이 최신 응답을 덮어쓰지 않도록 한다
    setLoading(true)
    fnRef.current()
      .then((d) => { if (my === seq.current) { setData(d); setError(null) } })
      .catch((e: Error) => { if (my === seq.current) setError(e.message) })
      .finally(() => { if (my === seq.current) setLoading(false) })
  }, [enabled])
  useEffect(() => {
    reload()
    window.addEventListener('workspace-changed', reload)
    window.addEventListener('reservations-changed', reload)
    return () => {
      window.removeEventListener('workspace-changed', reload)
      window.removeEventListener('reservations-changed', reload)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reload, ...deps])
  return { data, error, loading, reload }
}

export const errMsg = (e: unknown) => (e instanceof ApiError || e instanceof Error ? e.message : '요청에 실패했습니다')

export function Card({ title, action, children, className = '' }: { title?: ReactNode; action?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`rounded-2xl border border-white/8 bg-[#152238] ${className}`}>
      {(title || action) && (
        <header className="flex items-center justify-between px-5 pt-4 pb-2">
          <h3 className="text-sm font-semibold text-slate-100">{title}</h3>
          {action}
        </header>
      )}
      <div className="px-5 pb-5 pt-2">{children}</div>
    </section>
  )
}

export function PageHeader({ title, desc, badge, children }: { title: string; desc?: string; badge?: ReactNode; children?: ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-4 mb-5 flex-wrap">
      <div>
        <h1 className="text-xl font-bold flex items-center gap-2">{title}{badge}</h1>
        {desc && <p className="text-sm text-slate-400 mt-1 max-w-2xl">{desc}</p>}
      </div>
      <div className="flex gap-2">{children}</div>
    </div>
  )
}

const TONES = {
  ok: theme.status.ok, warn: theme.status.warn, error: theme.status.error, info: theme.status.working, idle: theme.status.idle,
}
export function Badge({ tone = 'idle', children }: { tone?: keyof typeof TONES; children: ReactNode }) {
  const c = TONES[tone]
  return <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-medium whitespace-nowrap" style={{ background: `${c}22`, color: c }}>{children}</span>
}

export const MOCK_BADGE = <Badge tone="warn">Mock</Badge>
export const SOON_BADGE = <Badge tone="idle">준비 중</Badge>

export function Btn({ children, onClick, tone = 'primary', disabled, type = 'button', small }: {
  children: ReactNode; onClick?: () => void; tone?: 'primary' | 'ghost' | 'danger'; disabled?: boolean; type?: 'button' | 'submit'; small?: boolean
}) {
  const cls = tone === 'primary' ? 'bg-sky-500 hover:bg-sky-400 text-white' : tone === 'danger' ? 'bg-red-500/80 hover:bg-red-500 text-white' : 'bg-white/8 hover:bg-white/15 text-slate-200'
  return (
    <button type={type} onClick={onClick} disabled={disabled}
      className={`rounded-lg font-medium transition disabled:opacity-40 ${small ? 'px-2.5 py-1 text-xs' : 'px-3.5 py-2 text-sm'} ${cls}`}>
      {children}
    </button>
  )
}

const field = 'w-full bg-[#0e1a2e] border border-white/10 rounded-lg px-3 py-2 text-sm outline-none focus:border-sky-400 placeholder:text-slate-500'
const stop = (e: React.KeyboardEvent) => e.stopPropagation()

export function Input(p: React.InputHTMLAttributes<HTMLInputElement>) { return <input {...p} onKeyDown={stop} className={field} /> }
export function Textarea(p: React.TextareaHTMLAttributes<HTMLTextAreaElement>) { return <textarea {...p} onKeyDown={stop} className={field + ' resize-none'} /> }
export function Select({ children, small, ...p }: React.SelectHTMLAttributes<HTMLSelectElement> & { small?: boolean }) {
  return <select {...p} className={small ? `${field.replace('w-full ', '')} !py-1.5 !px-2 text-xs w-auto` : field}>{children}</select>
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="text-sm text-slate-500 text-center py-8">{children}</div>
}

export function ErrorNote({ message }: { message: string | null }) {
  if (!message) return null
  return <p className="text-xs rounded-lg p-2.5 mb-3" style={{ background: `${theme.status.error}1f`, color: theme.status.error }}>{message}</p>
}

export function Stat({ label, value, tone }: { label: string; value: ReactNode; tone?: keyof typeof TONES }) {
  return (
    <div className="rounded-2xl border border-white/8 bg-[#152238] px-5 py-4">
      <div className="text-xs text-slate-400">{label}</div>
      <div className="text-3xl font-semibold mt-1" style={tone ? { color: TONES[tone] } : undefined}>{value}</div>
    </div>
  )
}

export const fmtTime = (iso: string) => new Date(iso).toLocaleString('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
