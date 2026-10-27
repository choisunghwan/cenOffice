import { useEffect, useState } from 'react'
import { API_URL, tokenStore } from '../api'

interface Settings { email: string; enabled: boolean; mailConfigured: boolean }

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(`${API_URL}/api/me/email${path}`, {
    method,
    headers: { Authorization: `Bearer ${tokenStore.get() ?? ''}`, 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  if (!response.ok) { const r = await response.json().catch(() => ({})); throw new Error(r.error ?? '요청을 처리하지 못했습니다') }
  return response.status === 204 ? undefined as T : response.json()
}

/** 알림 패널 아래의 이메일 알림 설정: 주소 저장, 수신 on/off, 시험 메일 */
export function EmailSettings() {
  const [open, setOpen] = useState(false)
  const [s, setS] = useState<Settings | null>(null)
  const [email, setEmail] = useState('')
  const [enabled, setEnabled] = useState(true)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null)

  useEffect(() => {
    if (!open || s) return
    call<Settings>('GET', '').then((v) => { setS(v); setEmail(v.email); setEnabled(v.enabled) }).catch((e) => setMsg({ ok: false, text: (e as Error).message }))
  }, [open, s])

  const run = async (fn: () => Promise<string>) => {
    if (busy) return
    setBusy(true); setMsg(null)
    try { setMsg({ ok: true, text: await fn() }) } catch (e) { setMsg({ ok: false, text: (e as Error).message }) } finally { setBusy(false) }
  }
  const save = () => run(async () => { const v = await call<Settings>('PUT', '', { email: email.trim(), enabled }); setS(v); setEmail(v.email); return v.email ? '저장했습니다.' : '이메일을 해제했습니다.' })
  const test = () => run(async () => { await call<void>('POST', '/test'); return '시험 메일을 보냈습니다. 받은편지함(스팸함 포함)을 확인하세요.' })

  return <div className="email-settings">
    <button className="minutes-link" aria-expanded={open} onClick={() => setOpen((v) => !v)}>✉ 이메일 알림 설정 {open ? '▴' : '▾'}</button>
    {open && <div className="email-form">
      {s && !s.mailConfigured && <p className="email-warn" role="note">메일 서버가 아직 설정되지 않아 지금은 메일이 발송되지 않습니다. 주소는 미리 저장해 둘 수 있습니다.</p>}
      <label htmlFor="email-addr">받을 이메일 주소</label>
      <input id="email-addr" type="email" inputMode="email" autoComplete="email" maxLength={254} placeholder="name@example.com" value={email} onChange={(e) => setEmail(e.target.value)} />
      <label className="email-check"><input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} /> 회의록 확정·할 일 지정을 메일로도 받기</label>
      <p className="email-hint">메일에는 제목과 짧은 요약, 링크만 담깁니다. 회의 전문은 보내지 않습니다.</p>
      <div className="email-actions">
        <button className="minutes-btn primary" disabled={busy} onClick={() => void save()}>저장</button>
        <button className="minutes-btn" disabled={busy || !s?.mailConfigured || !s?.email} title={!s?.email ? '먼저 주소를 저장하세요' : undefined} onClick={() => void test()}>시험 메일 보내기</button>
      </div>
      {msg && <p className={msg.ok ? 'email-ok' : 'email-err'} role={msg.ok ? 'status' : 'alert'}>{msg.text}</p>}
    </div>}
  </div>
}
