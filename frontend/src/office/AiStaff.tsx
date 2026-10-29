import { Character } from './Character'
import { AI_POS } from './layout'
import { NameTag } from './NameTag'
import { useOffice, type AiStatus } from '../store'
import { useMultiplayer } from '../multiplayer'
import { theme } from '../theme'

export const AI_STATUS_LABEL: Record<AiStatus, string> = {
  idle: '대기 중',
  working: '작업 중',
  done: '완료',
  error: '오류',
  approval: '승인 대기',
}
export const AI_STATUS_COLOR: Record<AiStatus, string> = {
  idle: theme.status.ok,
  working: theme.status.working,
  done: theme.status.ok,
  error: theme.status.error,
  approval: theme.status.warn,
}

/** AI 직원 아리. 실제 직원과 달리 접속 계정이 아니며 AI 배지·역할·작업 상태를 표시한다. (현재 Mock AI) */
export function AiStaff() {
  const status = useOffice((s) => s.aiStatus)
  const open = useOffice((s) => s.open)
  const bubble = useMultiplayer((s) => s.bubbles['ai:ari']?.text)
  return (
    <group
      position={[AI_POS.x, 0, AI_POS.z]}
      onClick={(e) => { e.stopPropagation(); open('ai') }}
      onPointerOver={() => (document.body.style.cursor = 'pointer')}
      onPointerOut={() => (document.body.style.cursor = '')}
    >
      <Character shirt="#a06bff" pants="#3a2f5c" hair="#e8e2ff" seated />
      <group position={[0, 0.3, 0]}>
        <NameTag name="아리" tagKey="ai:ari" bubble={bubble} ai={{ role: 'AI 직원', status: AI_STATUS_LABEL[status], statusColor: AI_STATUS_COLOR[status] }} />
      </group>
    </group>
  )
}
