import { useUi } from '../ui/uiStore'

/** 다크/라이트 모드. 값은 공통 화면 설정 저장소(uiStore)에 있어서 홈 입력창에서도 같이 바뀐다. */
export function useAppearance() {
  const appearance = useUi((s) => s.appearance)
  return { appearance, toggleAppearance: () => useUi.getState().toggleAppearance() }
}
