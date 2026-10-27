import { useState } from 'react'
import { useOffice } from '../store'
import { Notice } from './common'

const INPUT = 'w-full bg-slate-900/70 border border-white/10 rounded-lg px-3 py-2.5 text-sm outline-none focus:border-sky-400'

export function LoginModal({ onClose }: { onClose: () => void }) {
  const login = useOffice((s) => s.login)
  const signup = useOffice((s) => s.signup)
  const continueLocal = useOffice((s) => s.continueLocal)
  const [mode, setMode] = useState<'login' | 'signup'>('login')
  const [username, setUsername] = useState('user')
  const [password, setPassword] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [email, setEmail] = useState('')
  const [password2, setPassword2] = useState('')
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const isSignup = mode === 'signup'

  const switchMode = (m: 'login' | 'signup') => {
    setMode(m); setErr(null); setPassword(''); setPassword2('')
    setUsername(m === 'login' ? 'user' : '')
  }

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setErr(null)
    if (isSignup && password !== password2) { setErr('비밀번호 확인이 일치하지 않습니다.'); return }
    setBusy(true)
    try {
      if (isSignup) await signup({ username: username.trim(), displayName: displayName.trim(), email: email.trim(), password })
      else await login(username.trim(), password)
      onClose()
    } catch (x) {
      setErr((x as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const ready = username && password && (!isSignup || (displayName.trim() && password2))

  return (
    <div className="absolute inset-0 z-30 grid place-items-center bg-[#0d1626]/70 backdrop-blur-sm" onKeyDown={(e) => e.stopPropagation()}>
      <form onSubmit={submit} className="glass slide-in w-[min(380px,calc(100vw-2rem))] rounded-2xl p-6 space-y-4">
        <div className="text-center space-y-1">
          <img src="/itcen-logo.png" alt="ITCEN" className="h-6 mx-auto" draggable={false} />
          <h1 className="font-bold tracking-[0.2em] text-sm pt-1">CEN OFFICE</h1>
          <p className="text-xs text-slate-400">
            {isSignup ? '사번으로 가입하면 바로 사용할 수 있습니다.' : '로그인하면 장비 제어·회의실 예약·서버 모니터링을 사용할 수 있습니다.'}
          </p>
        </div>
        <input autoFocus value={username} onChange={(e) => setUsername(e.target.value)} placeholder={isSignup ? '사번 (영문·숫자 3~32자)' : '아이디'} autoComplete="username" className={INPUT} />
        {isSignup && <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} placeholder="이름" maxLength={100} autoComplete="name" className={INPUT} />}
        {isSignup && <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="이메일 (회의록·할 일 알림을 받을 주소)" maxLength={254} autoComplete="email" className={INPUT} />}
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder={isSignup ? '비밀번호 (8자 이상)' : '비밀번호'} autoComplete={isSignup ? 'new-password' : 'current-password'} className={INPUT} />
        {isSignup && <input type="password" value={password2} onChange={(e) => setPassword2(e.target.value)} placeholder="비밀번호 확인" autoComplete="new-password" className={INPUT} />}
        {err && <Notice tone="error">{err}</Notice>}
        <button disabled={busy || !ready} className="w-full py-2.5 rounded-xl bg-sky-500 text-white font-semibold text-sm disabled:opacity-40">
          {busy ? (isSignup ? '가입 중…' : '로그인 중…') : isSignup ? '가입하고 시작하기' : '로그인'}
        </button>
        <button type="button" onClick={() => switchMode(isSignup ? 'login' : 'signup')} className="w-full text-xs text-sky-300 hover:text-sky-200">
          {isSignup ? '이미 계정이 있나요? 로그인' : '처음이신가요? 회원가입'}
        </button>
        <button type="button" onClick={() => { continueLocal(); onClose() }} className="w-full text-xs text-slate-400 hover:text-slate-200">
          백엔드 없이 로컬 모드로 둘러보기
        </button>
      </form>
    </div>
  )
}
