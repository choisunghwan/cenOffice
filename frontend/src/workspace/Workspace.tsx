import { useOffice } from '../store'
import { Btn } from './kit'
import { AgentsPage, ComingSoonPage, MonitoringPage, OfficeManagementPage, RequirementsPage, SettingsPage } from './Pages'
import { ProjectsPage } from './Projects'
import { HubPage } from '../hub/HubPage'
import { ConnectPage } from '../hub/ConnectPage'

const MENU = [
  { key: 'home', label: '도구 둘러보기', icon: '✧' },
  { key: 'hub-mine', label: '내 도구', icon: '▤' },
  { key: 'hub-register', label: '도구 만들기', icon: '＋' },
  { key: 'hub-connect', label: 'AI 도구에서 쓰기', icon: '⇄' },
]
const MORE = [
  { key: 'settings', label: '설정' },
]

export function Workspace({ onLogin }: { onLogin: () => void }) {
  const user = useOffice((s) => s.user)
  const page = useOffice((s) => s.workspacePage)
  const setPage = useOffice((s) => s.setWorkspacePage)

  const body = () => {
    switch (page) {
      case 'home': return <HubPage key="browse" />
      case 'hub-mine': return <HubPage key="mine" initialTab="mine" />
      case 'hub-register': return <HubPage key="register" initialTab="register" />
      case 'hub-connect': return <ConnectPage />
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
      <nav className="w-52 shrink-0 border-r border-white/8 p-3 space-y-0.5 overflow-y-auto hidden md:block">
        <p className="text-[9px] tracking-[0.18em] text-slate-500 px-3 pt-4 pb-6">YOUR AI WORKSPACE</p>
        {[...MENU, ...(user?.role === 'ADMIN' ? [{ key: 'hub-review', label: '공개 승인', icon: '✓' }] : [])].map((m) => (
          <button key={m.key} onClick={() => { setPage(m.key); window.dispatchEvent(new Event('hub-navigation')) }} aria-current={page === m.key ? 'page' : undefined}
            className={`w-full text-left px-3 py-2 rounded-lg text-sm flex items-center gap-2.5 transition ${page === m.key ? 'bg-sky-500/15 text-white' : 'text-slate-400 hover:bg-white/5 hover:text-slate-200'}`}>
            <span className="text-base">{m.icon}</span>
            <span className="flex-1">{m.label}</span>

          </button>
        ))}
        <div className="pt-6 mt-6 border-t border-white/8">
          {MORE.map((m) => <button key={m.key} onClick={() => setPage(m.key)} aria-current={page === m.key ? 'page' : undefined} className={`w-full text-left px-3 py-2 rounded-lg text-sm ${page === m.key ? 'bg-white/10 text-white' : 'text-slate-400 hover:text-white'}`}>{m.label}</button>)}
          <button onClick={() => useOffice.getState().setMode('office')} className="w-full text-left px-3 py-3 text-sm text-slate-400 hover:text-white">3D 오피스 ↗</button>
        </div>
      </nav>
      <div className="md:hidden border-b border-white/8 p-2 absolute top-0 inset-x-0 bg-[#0d1626] z-10">
        <select value={page} onChange={(e) => setPage(e.target.value)} className="w-full bg-[#152238] rounded-lg px-3 py-2 text-sm">
          {[...MENU, ...MORE, ...(user?.role === 'ADMIN' ? [{ key: 'hub-review', label: '공개 승인' }] : [])].map((m) => <option key={m.key} value={m.key}>{m.label}</option>)}
        </select>
      </div>
      <main className="flex-1 overflow-y-auto p-6 md:pt-6 pt-16" onKeyDown={(e) => e.stopPropagation()}>
        {user ? <div className="max-w-[1200px] mx-auto">{body()}</div> : (
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
