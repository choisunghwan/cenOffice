import { useOffice } from '../store'
import { Btn } from './kit'
import { AgentsPage, ComingSoonPage, MonitoringPage, OfficeManagementPage, RequirementsPage, SettingsPage } from './Pages'
import { ProjectsPage } from './Projects'
import { HubPage } from '../hub/HubPage'
import { lazy, Suspense, useState } from 'react'
const MeetingsPage = lazy(() => import('../meeting/MeetingsPage').then(m => ({ default: m.MeetingsPage })))

const CalendarPage = lazy(() => import('../calendar/CalendarPage').then(m => ({ default: m.CalendarPage })))

const HomePage = lazy(() => import('../home/HomePage').then(m => ({ default: m.HomePage })))

const MENU = [
  { key: 'dashboard', label: '홈', icon: '⌂' },
  { key: 'calendar', label: '일정', icon: '▦' },
  { key: 'meetings', label: '회의', icon: '◉' },
  { key: 'home', label: '도구', icon: '✧' },
]
const MORE = [
  { key: 'settings', label: '설정' },
]

export function Workspace({ onLogin }: { onLogin: () => void }) {
  const user = useOffice((s) => s.user)
  const page = useOffice((s) => s.workspacePage)
  const setPage = useOffice((s) => s.setWorkspacePage)
  const [collapsed, setCollapsed] = useState(() => {
    try { return localStorage.getItem('workspace-nav-collapsed') === '1' } catch { return false }
  })
  const toggleNav = () => setCollapsed((c) => {
    try { localStorage.setItem('workspace-nav-collapsed', c ? '0' : '1') } catch { /* 저장 못 해도 동작에는 지장 없음 */ }
    return !c
  })

  // 도구 관련 화면(내 도구·도구 만들기·공개 승인)은 사이드바의 '도구' 아래에 있는 것으로 본다
  const isOn = (key: string) => (key === 'home' ? page === 'home' || page.startsWith('hub-') : page === key)

  const body = () => {
    switch (page) {
      case 'dashboard': return <Suspense fallback={<p role="status">홈을 준비하고 있습니다…</p>}><HomePage /></Suspense>
      case 'calendar': return <Suspense fallback={<p role="status">일정을 준비하고 있습니다…</p>}><CalendarPage /></Suspense>
      case 'home': return <HubPage key="browse" />
      case 'meetings': return <Suspense fallback={<p role="status">회의 화면을 준비하고 있습니다…</p>}><MeetingsPage /></Suspense>
      case 'hub-mine': return <HubPage key="mine" initialTab="mine" />
      case 'hub-register': return <HubPage key="register" initialTab="register" />
      case 'hub-review': return user?.role === 'ADMIN' ? <HubPage key="review" initialTab="review" /> : <HubPage />
      case 'projects': return <ProjectsPage />
      case 'requirements': return <RequirementsPage />
      case 'agents': return <AgentsPage />
      case 'aihub': return <HubPage />
      case 'monitoring': return <MonitoringPage />
      case 'office': return <OfficeManagementPage />
      case 'settings': return <SettingsPage />
      case 'development':
        return <ComingSoonPage title="Development" desc="AI 개발자와 사람이 함께 코드 작업을 진행하는 영역입니다." items={['AI 개발 작업(DEV 환경 한정) 실행 및 결과 리뷰', '변경 제안 → 사용자 승인 → 적용 흐름', '코드 저장소 연동']} />
      case 'testing':
        return <ComingSoonPage title="Testing" desc="테스트 실행과 결과 리포트." items={['AI 테스터의 테스트 실행(DEV/STG 한정)', '결과 요약 및 실패 케이스 분석', 'CI 연동']} />
      case 'deployment':
        return <ComingSoonPage title="Deployment" desc="배포 이력과 승인 상태 조회 영역입니다." items={['배포 이력 조회 (읽기 전용)', '운영 배포는 이 시스템에서 실행하지 않습니다 — 별도 CI/CD 와 운영 담당자 승인으로 분리 예정']} />
      default: return null
    }
  }

  return (
    <div className="hub-shell absolute inset-x-0 bottom-0 top-[72px] flex border-t border-white/8 z-[5]">
      <nav className={`${collapsed ? 'w-14' : 'w-52'} shrink-0 border-r border-white/8 p-2 space-y-0.5 overflow-y-auto overflow-x-hidden hidden md:block transition-[width] duration-150`}>
        <button onClick={toggleNav} aria-label={collapsed ? '사이드바 펼치기' : '사이드바 접기'} title={collapsed ? '사이드바 펼치기' : '사이드바 접기'}
          className={`w-full flex items-center ${collapsed ? 'justify-center' : 'justify-between px-3'} py-2 mb-2 rounded-lg text-slate-400 hover:bg-white/5 hover:text-white`}>
          {!collapsed && <span className="text-[9px] tracking-[0.18em] text-slate-500">YOUR AI WORKSPACE</span>}
          <span className="text-base">{collapsed ? '»' : '«'}</span>
        </button>
        {MENU.map((m) => (
          <button key={m.key} onClick={() => { setPage(m.key); window.dispatchEvent(new Event('hub-navigation')) }} aria-current={isOn(m.key) ? 'page' : undefined}
            title={collapsed ? m.label : undefined} aria-label={m.label}
            className={`w-full text-left ${collapsed ? 'justify-center px-0' : 'px-3'} py-2 rounded-lg text-sm flex items-center gap-2.5 transition ${isOn(m.key) ? 'bg-sky-500/15 text-white' : 'text-slate-400 hover:bg-white/5 hover:text-slate-200'}`}>
            <span className="text-base">{m.icon}</span>
            {!collapsed && <span className="flex-1">{m.label}</span>}

          </button>
        ))}
        <div className="pt-6 mt-6 border-t border-white/8">
          {MORE.map((m) => <button key={m.key} onClick={() => setPage(m.key)} aria-current={page === m.key ? 'page' : undefined} title={m.label} aria-label={m.label} className={`w-full ${collapsed ? 'text-center px-0' : 'text-left px-3'} py-2 rounded-lg text-sm ${page === m.key ? 'bg-white/10 text-white' : 'text-slate-400 hover:text-white'}`}>{collapsed ? '⚙' : m.label}</button>)}        </div>
      </nav>
      <nav aria-label="업무 메뉴" className="md:hidden border-b border-white/8 px-3 py-2 absolute top-0 inset-x-0 bg-[#0d1626] z-10 flex gap-1.5 overflow-x-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
        {[...MENU, ...MORE].map((m) => (
          <button key={m.key} onClick={() => { setPage(m.key); window.dispatchEvent(new Event('hub-navigation')) }} aria-current={isOn(m.key) ? 'page' : undefined}
            className={`shrink-0 whitespace-nowrap rounded-full px-3.5 py-1.5 text-sm transition ${isOn(m.key) ? 'bg-sky-500/15 text-white font-semibold' : 'text-slate-400 hover:bg-white/5 hover:text-slate-200'}`}>{m.label}</button>
        ))}
      </nav>
      <main className={`flex-1 ${user && page === 'dashboard' ? 'overflow-hidden md:pt-0 pt-14' : 'overflow-y-auto p-6 md:pt-6 pt-16'}`} onKeyDown={(e) => e.stopPropagation()}>
        {user ? <div className={user && page === 'dashboard' ? 'h-full' : 'max-w-[1200px] mx-auto'}>{body()}</div> : (
          <div className="h-full grid place-items-center">
            <div className="text-center space-y-3">
              <h2 className="text-3xl font-semibold">우리 팀의 AI를 만나보세요</h2>
              <p className="text-sm text-slate-400">로그인하고 사내 AI 도구를 둘러보세요.<br />직접 만든 업무 도구를 동료와 공유할 수도 있습니다.</p>
              <div className="flex gap-2 justify-center"><Btn onClick={onLogin}>로그인</Btn><Btn tone="ghost" onClick={() => useOffice.getState().setMode('office')}>3D OFFICE 로컬 모드</Btn></div>
            </div>
          </div>
        )}
      </main>
    </div>
  )
}
