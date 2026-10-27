import { ConfirmHost } from './ui/confirm'
import { useEffect, useState } from 'react'
import { useOffice, type Conn } from './store'
import { theme } from './theme'
import { AI_STATUS_COLOR, AI_STATUS_LABEL } from './game/AiStaff'
import { Scene } from './game/Scene'
import { AiPanel, InfoPanel, LightPanel } from './ui/Panels'
import { AcPanel, MeetingPanel, ServerPanel } from './ui/DevicePanels'
import { LoginModal } from './ui/LoginModal'
import { ChatDock } from './ui/ChatDock'
import { Workspace } from './workspace/Workspace'
import { useAppearance } from './workspace/useAppearance'
import { NotificationBell } from './meeting/NotificationBell'

const CONN: Record<Conn, { label: string; color: string; pulse?: boolean }> = {
  online: { label: '백엔드 연결됨', color: theme.status.ok },
  connecting: { label: '연결 중…', color: theme.status.working, pulse: true },
  offline: { label: '연결 끊김 · 재연결 중', color: theme.status.error, pulse: true },
  local: { label: '로컬 모드 (백엔드 미연결)', color: theme.status.warn },
  replaced: { label: '다른 곳에서 접속하여 연결 종료', color: theme.status.error },
}

export default function App() {
  const { appearance, toggleAppearance } = useAppearance()
  const mode = useOffice((s) => s.mode)
  const setMode = useOffice((s) => s.setMode)
  const panel = useOffice((s) => s.panel)
  const nearby = useOffice((s) => s.nearby)
  const open = useOffice((s) => s.open)
  const lightOn = useOffice((s) => s.lightOn)
  const conn = useOffice((s) => s.conn)
  const user = useOffice((s) => s.user)
  const authChecked = useOffice((s) => s.authChecked)
  const logout = useOffice((s) => s.logout)
  const light = useOffice((s) => s.devices['light-main'])
  const [loginOpen, setLoginOpen] = useState(false)
  const [asked, setAsked] = useState(false)

  useEffect(() => { void useOffice.getState().bootstrap() }, [])
  // 최초 접속 시 세션이 없으면 로그인 창을 한 번 보여준다
  useEffect(() => {
    if (authChecked && !user && !asked) { setLoginOpen(true); setAsked(true) }
  }, [authChecked, user, asked])
  const c = CONN[conn]
  const lightKnown = conn === 'online' && light ? light.state.power : lightOn
  const aiStatus = useOffice((s) => (s.conn === 'online' && s.serverAiStatus ? s.serverAiStatus : s.aiStatus))

  const menu = [
    { icon: '🏢', label: '공간', action: () => useOffice.getState().close() },
    { icon: '💡', label: '장비', action: () => open('light') },
    { icon: '🤖', label: 'AI 직원', action: () => open('ai') },
    { icon: '📋', label: '업무', action: () => open('desk-1') },
  ]

  return (
    <div className="relative w-full h-full select-none" data-workspace-theme={mode === 'workspace' ? appearance : undefined}>
      <div className="absolute inset-0" style={{ display: mode === 'office' ? 'block' : 'none' }} aria-hidden={mode !== 'office'}>
        <Scene active={mode === 'office'} />
      </div>

      <button type="button" aria-label="홈으로 이동" title="홈으로 이동"
        onClick={() => { setMode('workspace'); useOffice.getState().setWorkspacePage('dashboard'); window.dispatchEvent(new Event('hub-navigation')) }}
        className="glass absolute left-4 top-4 rounded-xl px-4 py-2.5 flex items-center gap-2 z-10 cursor-pointer hover:bg-white/10 transition">
        <img src="/itcen-logo.png" alt="" className="h-5 w-auto" draggable={false} />
        <span className="w-px h-4 bg-white/20" />
        <span className="font-bold tracking-[0.2em] text-sm hidden sm:inline">CEN OFFICE</span>
      </button>

      <div className="glass absolute left-1/2 -translate-x-1/2 top-4 rounded-xl p-1 flex gap-1 z-20 text-xs font-semibold" role="tablist" aria-label="화면 모드">
        {([['workspace', '업무'], ['office', '3D 오피스']] as const).map(([k, l]) => (
          <button key={k} role="tab" aria-selected={mode === k} onClick={() => setMode(k)}
            className={`px-4 py-2 rounded-lg tracking-wide transition ${mode === k ? 'bg-sky-500 text-white' : 'text-slate-300 hover:bg-white/10'}`}>{l}</button>
        ))}
      </div>

      <div className="glass absolute right-4 top-4 rounded-xl px-4 py-2.5 flex items-center gap-3 z-10 text-xs max-w-[30vw] whitespace-nowrap">
        {mode === 'workspace' && <button className="appearance-toggle" onClick={toggleAppearance} aria-label={appearance === 'light' ? '다크 모드로 전환' : '라이트 모드로 전환'} title={appearance === 'light' ? '다크 모드로 전환' : '라이트 모드로 전환'}><span aria-hidden="true">{appearance === 'light' ? '☾' : '☀'}</span></button>}
        <details className="hidden lg:block">
          <summary className="cursor-pointer list-none" title={c.label}><span style={{ color: c.color }}>●</span><span className="sr-only">연결 상태</span></summary>
          <div className="fixed right-4 top-16 rounded-xl bg-slate-900 border border-white/10 p-4 space-y-2 shadow-xl"><p>{c.label}</p><p>조명 {lightKnown ? 'ON' : 'OFF'}</p><p style={{ color: AI_STATUS_COLOR[aiStatus] }}>AI {AI_STATUS_LABEL[aiStatus]}</p></div>
        </details>
        {user ? (
          <span className="flex items-center gap-2">
            <span className="w-6 h-6 rounded-full bg-gradient-to-br from-emerald-400 to-sky-500 grid place-items-center text-[11px] font-bold text-slate-900">{user.displayName.slice(0, 1)}</span>
            <span className="hidden md:inline">{user.displayName}</span>
            <NotificationBell />
            <button onClick={logout} className="text-slate-400 hover:text-white underline-offset-2 hover:underline">로그아웃</button>
          </span>
        ) : (
          <button onClick={() => setLoginOpen(true)} className="px-3 py-1 rounded-lg bg-sky-500 text-white font-semibold">로그인</button>
        )}
      </div>

      {mode === 'office' && <>
      <nav className="glass absolute left-4 top-1/2 -translate-y-1/2 rounded-2xl p-2 flex flex-col gap-1 z-10">
        {menu.map((m) => (
          <button key={m.label} onClick={m.action} className="w-16 py-2.5 rounded-xl hover:bg-white/10 flex flex-col items-center gap-1 text-[11px] text-slate-200 transition">
            <span className="text-xl">{m.icon}</span>{m.label}
          </button>
        ))}
      </nav>

      {panel === 'light' && <LightPanel />}
      {panel === 'ai' && <AiPanel />}
      {panel === 'info' && <InfoPanel />}
      {panel === 'ac' && <AcPanel />}
      {panel === 'server' && <ServerPanel />}
      {panel === 'meeting' && <MeetingPanel />}
      </>}

      {mode === 'workspace' && <Workspace onLogin={() => setLoginOpen(true)} />}
      {loginOpen && <LoginModal onClose={() => setLoginOpen(false)} />}
      <ConfirmHost />

      {mode === 'office' && conn === 'online' && panel === 'none' && <ChatDock />}

      {conn === 'replaced' && (
        <div className="glass absolute top-20 left-1/2 -translate-x-1/2 z-20 rounded-xl px-5 py-3 text-sm flex items-center gap-3" role="alert">
          같은 계정으로 다른 곳에서 접속해 이 연결이 종료되었습니다.
          <button onClick={() => window.location.reload()} className="px-3 py-1 rounded-lg bg-sky-500 text-white font-semibold">여기서 다시 접속</button>
        </div>
      )}

      {mode === 'office' && (
      <div className="absolute bottom-4 left-1/2 -translate-x-1/2 flex flex-col items-center gap-2 z-10 pointer-events-none">
        {nearby && panel === 'none' && (
          <div className="glass slide-in rounded-full px-5 py-2 text-sm flex items-center gap-2.5">
            <kbd className="px-2 py-0.5 rounded bg-sky-500 text-white text-xs font-bold">E</kbd>
            <span>{nearby.label} 상호작용</span>
          </div>
        )}
        <div className="glass rounded-full px-4 py-1.5 text-[11px] text-slate-400 flex gap-4 flex-wrap justify-center">
          <span><b className="text-slate-200">WASD</b> 이동</span>
          <span><b className="text-slate-200">드래그</b> 카메라</span>
          <span><b className="text-slate-200">휠</b> 줌</span>
          <span><b className="text-slate-200">E</b> 상호작용</span>
          <span><b className="text-slate-200">ESC</b> 닫기</span>
          <span>조명 {lightKnown ? 'ON' : 'OFF'}</span>
        </div>
      </div>
      )}
    </div>
  )
}
