import { MobileQr, SiteQr } from './MobileQr'
import { useState } from 'react'
import { useOffice } from '../store'
import { Notice } from '../office/DeviceKit'

const SAVED_ID_KEY = 'cen-office-saved-id'
const savedId = (): string | null => { try { return localStorage.getItem(SAVED_ID_KEY) } catch { return null } }
const saveId = (id: string | null) => { try { id ? localStorage.setItem(SAVED_ID_KEY, id) : localStorage.removeItem(SAVED_ID_KEY) } catch { /* 저장하지 못해도 로그인에는 지장 없음 */ } }

const INPUT = 'w-full bg-slate-900/70 border border-white/10 rounded-lg px-3 py-2.5 text-sm outline-none focus:border-sky-400'

export function LoginModal({ onClose }: { onClose: () => void }) {
  const login = useOffice((s) => s.login)
  const signup = useOffice((s) => s.signup)
  const [mode, setMode] = useState<'login' | 'signup'>('login')
  const [remember, setRemember] = useState(() => savedId() !== null) // 아이디 저장(비밀번호는 저장하지 않는다)
  const [username, setUsername] = useState(() => savedId() ?? 'user')
  const [password, setPassword] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [email, setEmail] = useState('')
  const [password2, setPassword2] = useState('')
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const isSignup = mode === 'signup'

  const switchMode = (m: 'login' | 'signup') => {
    setMode(m); setErr(null); setPassword(''); setPassword2('')
    setUsername(m === 'login' ? (savedId() ?? 'user') : '')
  }

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setErr(null)
    if (isSignup && password !== password2) { setErr('비밀번호 확인이 일치하지 않습니다.'); return }
    setBusy(true)
    try {
      if (isSignup) await signup({ username: username.trim(), displayName: displayName.trim(), email: email.trim(), password })
      else { await login(username.trim(), password); saveId(remember ? username.trim() : null) }
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
      <div className="flex items-stretch gap-4">
      <form onSubmit={submit} className="glass slide-in w-[min(380px,calc(100vw-2rem))] rounded-2xl p-6 space-y-4">
        <div className="text-center space-y-1">
          <img src="/itcen-logo.png" alt="ITCEN" className="h-6 mx-auto" draggable={false} />
          <h1 className="font-bold tracking-[0.2em] text-sm pt-1">CEN HUB</h1>
          {isSignup && <p className="text-xs text-slate-400">사번으로 가입하면 바로 사용할 수 있습니다.</p>}
        </div>
        <input autoFocus={!savedId()} value={username} onChange={(e) => setUsername(e.target.value)} placeholder={isSignup ? '사번 (영문·숫자 3~32자)' : '아이디'} autoComplete="username" className={INPUT} />
        {isSignup && <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} placeholder="이름" maxLength={100} autoComplete="name" className={INPUT} />}
        {isSignup && <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="이메일 (회의록·할 일 알림을 받을 주소)" maxLength={254} autoComplete="email" className={INPUT} />}
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder={isSignup ? '비밀번호 (8자 이상)' : '비밀번호'} autoComplete={isSignup ? 'new-password' : 'current-password'} className={INPUT} />
        {isSignup && <input type="password" value={password2} onChange={(e) => setPassword2(e.target.value)} placeholder="비밀번호 확인" autoComplete="new-password" className={INPUT} />}
        {!isSignup && <label className="flex items-center gap-2 text-xs text-slate-300 cursor-pointer w-fit"><input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} className="m-0 w-4 h-4 accent-sky-500" />아이디 저장</label>}
        {err && <Notice tone="error">{err}</Notice>}
        <button disabled={busy || !ready} className="w-full py-2.5 rounded-xl bg-sky-500 text-white font-semibold text-sm disabled:opacity-40">
          {busy ? (isSignup ? '가입 중…' : '로그인 중…') : isSignup ? '가입하고 시작하기' : '로그인'}
        </button>
        <button type="button" onClick={() => switchMode(isSignup ? 'login' : 'signup')} className="w-full text-xs text-sky-300 hover:text-sky-200">
          {isSignup ? '이미 계정이 있나요? 로그인' : '처음이신가요? 회원가입'}
        </button>
        {/* 휴대폰·좁은 화면은 옆 QR 칸 대신 링크로 QR 창을 연다 */}
        <div className="lg:hidden pointer-coarse:block"><MobileQr variant="link" /></div>
      </form>
      {/* PC 로그인 화면: 휴대폰으로 바로 열 수 있게 QR 을 옆에 둔다(휴대폰에서는 숨김) */}
      <aside className="glass slide-in hidden lg:flex pointer-coarse:hidden w-[220px] rounded-2xl p-5 flex-col items-center justify-center text-center gap-3" aria-label="휴대폰으로 열기">
        <p className="font-semibold text-sm">휴대폰으로 열기</p>
        <SiteQr className="login-qr" />
        <p className="text-[11px] text-slate-400 leading-relaxed">휴대폰 카메라로 비추면<br />CEN HUB 가 바로 열립니다.</p>
      </aside>
      </div>
    </div>
  )
}
