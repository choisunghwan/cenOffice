import { useOffice } from '../store'
import { useUi } from '../ui/uiStore'
import { useChat } from './chatStore'

/**
 * 입력창으로 조작할 수 있는 화면 동작. 이름은 서버의 UiCommand(허용 목록)와 같아야 한다.
 * 새 화면 기능을 만들면 서버 UiCommand 와 여기에 한 줄씩 더하면 채팅에서도 쓸 수 있다.
 */
const goto = (page: string) => () => {
  useOffice.getState().setMode('workspace')
  useOffice.getState().setWorkspacePage(page)
  window.dispatchEvent(new Event('hub-navigation'))
}

export const UI_ACTIONS: Record<string, () => void> = {
  THEME_DARK: () => useUi.getState().setAppearance('dark'),
  THEME_LIGHT: () => useUi.getState().setAppearance('light'),
  THEME_TOGGLE: () => useUi.getState().toggleAppearance(),
  SIDEBAR_COLLAPSE: () => useUi.getState().setNavCollapsed(true),
  SIDEBAR_EXPAND: () => useUi.getState().setNavCollapsed(false),
  NEW_CHAT: () => useChat.getState().startNew(),
  LOGOUT: () => useOffice.getState().logout(),
  MODE_OFFICE: () => useOffice.getState().setMode('office'),
  MODE_WORKSPACE: () => useOffice.getState().setMode('workspace'),
  NAV_HOME: goto('dashboard'),
  NAV_CALENDAR: goto('calendar'),
  NAV_MEETINGS: goto('meetings'),
  NAV_TOOLS: goto('home'),
  NAV_MY_TOOLS: goto('hub-mine'),
  NAV_CREATE_TOOL: goto('hub-register'),
  NAV_REVIEW: goto('hub-review'),
  NAV_SETTINGS: goto('settings'),
}

/** 알려진 화면 동작이면 실행하고 true. 모르는 이름이면 아무것도 하지 않고 false. */
export function runUiAction(id: string): boolean {
  const action = UI_ACTIONS[id]
  if (!action) return false
  action()
  return true
}
