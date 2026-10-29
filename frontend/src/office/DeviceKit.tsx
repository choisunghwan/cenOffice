import type { ReactNode } from 'react'
import type { CommandStatus, CommandView } from '../api'
import { theme } from '../theme'

export const CMD_LABEL: Record<CommandStatus, string> = {
  REQUESTED: '요청됨',
  SENT: '장비로 전송됨',
  CONFIRMED: '장비 확인 완료',
  FAILED: '실패',
  TIMEOUT: '시간 초과',
}
export const CMD_COLOR: Record<CommandStatus, string> = {
  REQUESTED: theme.status.working,
  SENT: theme.status.working,
  CONFIRMED: theme.status.ok,
  FAILED: theme.status.error,
  TIMEOUT: theme.status.error,
}

export function Dot({ color, pulse = false }: { color: string; pulse?: boolean }) {
  return <span className={`inline-block w-2 h-2 rounded-full ${pulse ? 'pulse-dot' : ''}`} style={{ background: color }} />
}

export function CommandBadge({ cmd }: { cmd?: CommandView }) {
  if (!cmd) return <span className="text-slate-500 text-xs">명령 이력 없음</span>
  const open = cmd.status === 'REQUESTED' || cmd.status === 'SENT'
  return (
    <span className="inline-flex items-center gap-1.5 text-xs" style={{ color: CMD_COLOR[cmd.status] }}>
      <Dot color={CMD_COLOR[cmd.status]} pulse={open} />
      {CMD_LABEL[cmd.status]}
      {cmd.error && <span className="text-slate-400">· {cmd.error}</span>}
    </span>
  )
}

export function latestCommand(commands: Record<string, CommandView>, deviceCode: string): CommandView | undefined {
  let best: CommandView | undefined
  for (const c of Object.values(commands)) {
    if (c.deviceCode === deviceCode && (!best || c.requestedAt > best.requestedAt)) best = c
  }
  return best
}

export function Row({ k, children }: { k: string; children: ReactNode }) {
  return (
    <div className="flex justify-between items-center gap-3 py-1.5 text-sm">
      <dt className="text-slate-400">{k}</dt>
      <dd className="text-slate-100 text-right">{children}</dd>
    </div>
  )
}

export function Notice({ children, tone = 'warn' }: { children: ReactNode; tone?: 'warn' | 'error' | 'info' }) {
  const c = tone === 'error' ? theme.status.error : tone === 'info' ? theme.status.working : theme.status.warn
  return (
    <p className="text-xs leading-relaxed rounded-lg p-3" style={{ background: `${c}1f`, color: c }}>
      {children}
    </p>
  )
}

export function resultMessage(r: string): string | null {
  if (r === 'CONFIRMED' || r === 'LOCAL') return null
  if (r === 'FAILED') return '장비가 명령을 처리하지 못했습니다.'
  if (r === 'TIMEOUT') return '장비 응답 시간이 초과되었습니다. 실제 상태는 확인되지 않았습니다.'
  return '명령을 보낼 수 없습니다. 연결 상태와 권한을 확인하세요.'
}
