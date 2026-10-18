import { useState } from 'react'
import { useOffice } from '../store'
import { Notice } from './common'

export function LoginModal({ onClose }: { onClose: () => void }) {
  const login = useOffice((s) => s.login)
  const continueLocal = useOffice((s) => s.continueLocal)
  const [username, setUsername] = useState('user')
  const [password, setPassword] = useState('')
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setErr(null)
    try {
      await login(username.trim(), password)
      onClose()
    } catch (x) {
      setErr((x as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="absolute inset-0 z-30 grid place-items-center bg-[#0d1626]/70 backdrop-blur-sm" onKeyDown={(e) => e.stopPropagation()}>
      <form onSubmit={submit} className="glass slide-in w-[min(380px,calc(100vw-2rem))] rounded-2xl p-6 space-y-4">
        <div className="text-center space-y-1">
          <img src="/itcen-logo.png" alt="ITCEN" className="h-6 mx-auto" draggable={false} />
          <h1 className="font-bold tracking-[0.2em] text-sm pt-1">CEN OFFICE</h1>
          <p className="text-xs text-slate-400">로그인하면 장비 제어·회의실 예약·서버 모니터링을 사용할 수 있습니다.</p>
        </div>
        <input autoFocus value={username} onChange={(e) => setUsername(e.target.value)} placeholder="아이디" autoComplete="username"
          className="w-full bg-slate-900/70 border border-white/10 rounded-lg px-3 py-2.5 text-sm outline-none focus:border-sky-400" />
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="비밀번호" autoComplete="current-password"
          className="w-full bg-slate-900/70 border border-white/10 rounded-lg px-3 py-2.5 text-sm outline-none focus:border-sky-400" />
        {err && <Notice tone="error">{err}</Notice>}
        <button disabled={busy || !username || !password} className="w-full py-2.5 rounded-xl bg-sky-500 text-white font-semibold text-sm disabled:opacity-40">
          {busy ? '로그인 중…' : '로그인'}
        </button>
        <button type="button" onClick={() => { continueLocal(); onClose() }} className="w-full text-xs text-slate-400 hover:text-slate-200">
          백엔드 없이 로컬 모드로 둘러보기
        </button>
      </form>
    </div>
  )
}
