import { ConfirmHost } from './common/confirm'
import { CoachHost } from './common/coach'
import { useEffect, useState } from 'react'
import { useOffice } from './store'
import { Scene } from './office/Scene'
import { AiPanel, InfoPanel, LightPanel } from './office/Panels'
import { AcPanel, MeetingPanel, ServerPanel } from './office/DevicePanels'
import { LoginModal } from './common/LoginModal'
import { ChatDock } from './office/ChatDock'
import { TouchControls } from './office/TouchControls'
import { ChatToast } from './office/ChatToast'
import { useMultiplayer } from './multiplayer'
import { Workspace } from './workspace/Workspace'
import { useAppearance } from './workspace/useAppearance'
import { NotificationBell } from './meeting/NotificationBell'
import { MobileQr } from './common/MobileQr'


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
  const chatUnread = useMultiplayer((s) => Object.values(s.unread).reduce((a, b) => a + b, 0))
  const [asked, setAsked] = useState(false)

  useEffect(() => { void useOffice.getState().bootstrap() }, [])
  // 최초 접속 시 세션이 없으면 로그인 창을 한 번 보여준다
  useEffect(() => {
    if (authChecked && !user && !asked) { setLoginOpen(true); setAsked(true) }
  }, [authChecked, user, asked])
  const lightKnown = conn === 'online' && light ? light.state.power : lightOn

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

      {/* 상단 바: 폰에서는 한 줄로 나란히, 넓은 화면에서는 탭을 가운데에 띄운다 */}
      <div className="absolute inset-x-3 sm:inset-x-4 top-4 z-20 flex items-center justify-between gap-1.5 sm:gap-2 pointer-events-none">
      <button type="button" aria-label="홈으로 이동" title="홈으로 이동"
        onClick={() => { setMode('workspace'); useOffice.getState().setWorkspacePage('dashboard'); window.dispatchEvent(new Event('hub-navigation')) }}
        className="glass pointer-events-auto shrink-0 h-[42px] rounded-xl px-2.5 sm:px-4 flex items-center gap-2 cursor-pointer hover:bg-white/10 transition">
        <img src="/favicon.png" alt="" className="h-5 w-5 sm:hidden" draggable={false} />
        <span className="font-bold text-[12px] tracking-[0.06em] whitespace-nowrap sm:hidden">CEN HUB</span>
        <img src="/itcen-logo.png" alt="" className="h-5 w-auto hidden sm:block" draggable={false} />
        <span className="w-px h-4 bg-white/20 hidden sm:block" />
        <span className="font-bold tracking-[0.2em] text-sm hidden sm:inline">CEN HUB</span>
      </button>

      <div className="glass pointer-events-auto shrink-0 sm:absolute sm:left-1/2 sm:-translate-x-1/2 sm:top-0 h-[42px] rounded-xl p-1 flex items-center gap-1 text-xs font-semibold" role="tablist" aria-label="화면 모드">
        {([['workspace', '업무'], ['office', '3D 오피스']] as const).map(([k, l]) => (
          <button key={k} role="tab" aria-selected={mode === k} onClick={() => setMode(k)}
            aria-label={l} className={`px-2.5 sm:px-4 py-2 rounded-lg tracking-wide whitespace-nowrap transition ${mode === k ? 'bg-sky-500 text-white' : 'text-slate-300 hover:bg-white/10'} relative`}>
            {/* 휴대폰은 폭이 좁아 로고 글자 자리를 위해 '3D 오피스'를 '3D'로 줄인다 */}
            {k === 'office' ? <><span className="sm:hidden">3D</span><span className="hidden sm:inline">{l}</span></> : l}
            {/* 채팅은 3D 오피스 안에 있으므로, 업무 화면에 있을 때 안 읽은 채팅 수를 3D 오피스 탭에 표시한다 */}
            {k === 'office' && mode !== 'office' && chatUnread > 0 && <span className="absolute -top-1.5 -right-1.5 min-w-[18px] h-[18px] px-1 rounded-full bg-red-500 text-white text-[10px] font-bold grid place-items-center" aria-label={`안 읽은 채팅 ${chatUnread}건`}>{chatUnread > 99 ? '99+' : chatUnread}</span>}</button>
        ))}
      </div>

      <div data-coach="topbar-right" className="glass pointer-events-auto min-w-0 h-[42px] rounded-xl px-1.5 sm:px-4 flex items-center gap-1 sm:gap-3 text-xs whitespace-nowrap">
        <MobileQr />
        {mode === 'workspace' && <button className="appearance-toggle" onClick={toggleAppearance} aria-label={appearance === 'light' ? '다크 모드로 전환' : '라이트 모드로 전환'} title={appearance === 'light' ? '다크 모드로 전환' : '라이트 모드로 전환'}><span aria-hidden="true">{appearance === 'light' ? '☾' : '☀'}</span></button>}
        {user ? (
          <span className="flex items-center gap-2">
            <span className="w-6 h-6 rounded-full hidden sm:grid bg-gradient-to-br from-emerald-400 to-sky-500 place-items-center text-[11px] font-bold text-slate-900">{user.displayName.slice(0, 1)}</span>
            <span className="hidden md:inline">{user.displayName}</span>
            <NotificationBell />
            <button onClick={logout} aria-label="로그아웃" title="로그아웃" className="text-slate-400 hover:text-white underline-offset-2 hover:underline">
              <span className="hidden sm:inline">로그아웃</span>
              <svg className="sm:hidden w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" /><path d="m16 17 5-5-5-5" /><path d="M21 12H9" /></svg>
            </button>
          </span>
        ) : (
          <button onClick={() => setLoginOpen(true)} className="px-3 py-1 rounded-lg bg-sky-500 text-white font-semibold">로그인</button>
        )}
      </div>
      </div>

      {mode === 'office' && <>
      {/* 휴대폰: 화면을 가리지 않게 왼쪽 아래의 작은 아이콘 줄로 둔다(오른쪽 아래는 채팅 버튼) */}
      <nav aria-label="3D 오피스 메뉴" className="glass absolute left-4 max-sm:left-3 top-1/2 -translate-y-1/2 max-sm:top-auto max-sm:translate-y-0 max-sm:bottom-4 rounded-2xl max-sm:rounded-full p-2 max-sm:p-1 flex flex-col max-sm:flex-row gap-1 max-sm:gap-0.5 z-10">
        {menu.map((m) => (
          <button key={m.label} onClick={m.action} aria-label={m.label} title={m.label} className="w-16 max-sm:w-11 py-2.5 max-sm:py-0 max-sm:h-11 rounded-xl max-sm:rounded-full hover:bg-white/10 active:bg-white/15 flex flex-col items-center justify-center gap-1 text-[11px] text-slate-200 transition">
            <span className="text-xl max-sm:text-lg" aria-hidden="true">{m.icon}</span><span className="max-sm:sr-only">{m.label}</span>
          </button>
        ))}
      </nav>

      {panel === 'none' && <TouchControls />}
      {panel === 'light' && <LightPanel />}
      {panel === 'ai' && <AiPanel />}
      {panel === 'info' && <InfoPanel />}
      {panel === 'ac' && <AcPanel />}
      {panel === 'server' && <ServerPanel />}
      {panel === 'meeting' && <MeetingPanel />}
      </>}

      {mode === 'workspace' && <Workspace onLogin={() => setLoginOpen(true)} />}
      {loginOpen && <LoginModal onClose={() => setLoginOpen(false)} />}
      {user && <ChatToast />}
      <ConfirmHost />
      <CoachHost />

      {mode === 'office' && conn === 'online' && panel === 'none' && <ChatDock />}

      {conn === 'replaced' && (
        <div className="glass absolute top-20 left-1/2 -translate-x-1/2 max-sm:left-3 max-sm:right-3 max-sm:translate-x-0 max-sm:flex-wrap max-sm:justify-center max-sm:text-center z-20 rounded-xl px-5 py-3 text-sm flex items-center gap-3" role="alert">
          같은 계정으로 다른 곳에서 접속해 이 연결이 종료되었습니다.
          <button onClick={() => window.location.reload()} className="px-3 py-1 rounded-lg bg-sky-500 text-white font-semibold">여기서 다시 접속</button>
        </div>
      )}

      {mode === 'office' && (
      <div className="absolute bottom-4 max-sm:bottom-auto max-sm:top-20 left-1/2 -translate-x-1/2 flex flex-col items-center gap-2 z-10 pointer-events-none">
        {/* 키보드가 없는 화면에서도 누를 수 있게 상호작용 안내 자체를 버튼으로 둔다 */}
        {nearby && panel === 'none' && (
          <button type="button" onClick={() => open(nearby.id)} className="glass slide-in pointer-events-auto whitespace-nowrap rounded-full px-5 py-2 max-sm:py-3 text-sm flex items-center gap-2.5 hover:bg-white/10">
            <kbd className="px-2 py-0.5 rounded bg-sky-500 text-white text-xs font-bold pointer-coarse:hidden">E</kbd>
            <span>{nearby.label} 상호작용</span>
          </button>
        )}
        <div className="glass rounded-full px-4 py-1.5 text-[11px] text-slate-400 flex gap-4 flex-wrap justify-center max-sm:hidden pointer-coarse:hidden">
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
