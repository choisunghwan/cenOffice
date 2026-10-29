import { create } from 'zustand'

/** 화면 모양 설정(다크/라이트, 사이드바 접힘). 화면의 버튼과 홈 입력창이 같은 값을 바꾼다. 브라우저에 기억해 둔다. */
const APPEARANCE_KEY = 'cen-office-appearance'
const NAV_KEY = 'workspace-nav-collapsed'

function read(key: string): string | null {
  try { return localStorage.getItem(key) } catch { return null }
}
function write(key: string, value: string) {
  try { localStorage.setItem(key, value) } catch { /* 저장하지 못해도 이번 화면에서는 그대로 동작한다 */ }
}

interface UiState {
  appearance: 'light' | 'dark'
  navCollapsed: boolean
  setAppearance: (a: 'light' | 'dark') => void
  toggleAppearance: () => void
  setNavCollapsed: (c: boolean) => void
}

export const useUi = create<UiState>((set, get) => ({
  appearance: read(APPEARANCE_KEY) === 'dark' ? 'dark' : 'light',
  navCollapsed: read(NAV_KEY) === '1',
  setAppearance: (appearance) => { write(APPEARANCE_KEY, appearance); set({ appearance }) },
  toggleAppearance: () => get().setAppearance(get().appearance === 'light' ? 'dark' : 'light'),
  setNavCollapsed: (navCollapsed) => { write(NAV_KEY, navCollapsed ? '1' : '0'); set({ navCollapsed }) },
}))

// 다른 탭에서 바꾼 모드도 따라간다
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (e) => {
    if (e.key === APPEARANCE_KEY) useUi.setState({ appearance: e.newValue === 'dark' ? 'dark' : 'light' })
  })
}
