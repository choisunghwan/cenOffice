export interface Box {
  x: number
  z: number
  w: number
  d: number
  h: number
  /** 유리벽: 충돌은 있지만 카메라 차폐/렌더는 투명 */
  glass?: boolean
  /** 카메라 레이 차폐 대상 */
  occludes?: boolean
}

export const OFFICE = { minX: -12, maxX: 12, minZ: -8, maxZ: 8, wallH: 3 }
const T = 0.3

const wall = (x1: number, z1: number, x2: number, z2: number, glass = false): Box => ({
  x: (x1 + x2) / 2,
  z: (z1 + z2) / 2,
  w: Math.abs(x2 - x1) || T,
  d: Math.abs(z2 - z1) || T,
  h: OFFICE.wallH,
  glass,
  occludes: !glass,
})

export const WALLS: Box[] = [
  // 외벽
  wall(-12, -8, 12, -8),
  wall(-12, 8, 12, 8),
  wall(-12, -8, -12, 8),
  wall(12, -8, 12, 8),
  // 회의실 (유리) — 서쪽 벽에 문 틈(z -5 ~ -3.5)
  wall(4, -8, 4, -5, true),
  wall(4, -3.5, 4, -1, true),
  wall(4, -1, 12, -1, true),
  // 서버실 — 북쪽 벽에 문 틈(x 6 ~ 7.5)
  wall(4, 2, 6, 2),
  wall(7.5, 2, 12, 2),
  wall(4, 2, 4, 8),
]

/** 가구 충돌 박스 (렌더는 Office.tsx에서 동일 좌표로 수행) */
export const DESKS = [
  { id: 'desk-1', x: -9, z: -4 },
  { id: 'desk-2', x: -5, z: -4 },
  { id: 'desk-3', x: -9, z: 0.5 },
  { id: 'desk-4', x: -5, z: 0.5 },
]
export const AI_DESK = { x: 0, z: -5.2 }
export const AI_POS = { x: 0, z: -6.4 }
export const LIGHT_SWITCH = { x: -11.8, z: 5 }
export const AC_POS = { x: -3, z: -7.75 }
export const RACKS = [6, 7.4, 8.8, 10.2].map((x) => ({ x, z: 7.1 }))
export const MEETING_TABLE = { x: 8, z: -4.5 }
/** AI LAB 터미널(AI HUB 진입 키오스크) — 북쪽 벽에 붙여 설치 */
export const HUB_POS = { x: 1.9, z: -7.55 }

export const FURNITURE: Box[] = [
  ...DESKS.map((d) => ({ x: d.x, z: d.z, w: 2.2, d: 1, h: 0.8 })),
  { x: AI_DESK.x, z: AI_DESK.z, w: 2.6, d: 1.1, h: 0.8 },
  { x: MEETING_TABLE.x, z: MEETING_TABLE.z, w: 3.4, d: 1.4, h: 0.8 },
  ...RACKS.map((r) => ({ x: r.x, z: r.z, w: 1, d: 1, h: 2 })),
  // AI LAB 터미널
  { x: HUB_POS.x, z: HUB_POS.z, w: 1.0, d: 0.5, h: 1.5 },
  // 수납장
  { x: -1, z: 7.5, w: 3, d: 0.7, h: 1.8 },
  { x: 11.5, z: 0.5, w: 0.8, d: 1.6, h: 1.8 },
  // 소파
  { x: -9, z: 6.9, w: 2.6, d: 1.0, h: 0.9 },
  // 화분
  { x: -11.2, z: -7.2, w: 0.7, d: 0.7, h: 1.2 },
  { x: 3.2, z: 7.2, w: 0.7, d: 0.7, h: 1.2 },
  { x: 3.2, z: -7.2, w: 0.7, d: 0.7, h: 1.2 },
]

export const COLLIDERS: Box[] = [...WALLS, ...FURNITURE]

export interface InteractableDef {
  id: string
  label: string
  x: number
  z: number
  radius: number
  kind: 'light' | 'ai' | 'ac' | 'server' | 'meeting' | 'hub' | 'info'
  description?: string
}

export const INTERACTABLES: InteractableDef[] = [
  { id: 'light', label: '조명 스위치', x: LIGHT_SWITCH.x, z: LIGHT_SWITCH.z, radius: 2.2, kind: 'light' },
  { id: 'ai', label: 'AI 직원 아리', x: AI_POS.x, z: AI_POS.z, radius: 3, kind: 'ai' },
  { id: 'ac', label: '에어컨', x: AC_POS.x, z: AC_POS.z + 1, radius: 2.5, kind: 'ac' },
  ...DESKS.map<InteractableDef>((d, i) => ({
    id: d.id,
    label: `개인 업무 책상 ${i + 1}`,
    x: d.x,
    z: d.z + 1.2,
    radius: 1.7,
    kind: 'info',
    description: '업무 대시보드(오늘의 업무·문서·알림)는 Phase 3에서 구현됩니다.',
  })),
  { id: 'hub', label: 'AI LAB 터미널 (AI HUB)', x: HUB_POS.x, z: HUB_POS.z + 1.1, radius: 2.2, kind: 'hub' },
  { id: 'meeting', label: '회의실', x: MEETING_TABLE.x, z: MEETING_TABLE.z + 1.6, radius: 2.4, kind: 'meeting' },
  { id: 'server', label: '서버실 모니터링', x: 8.1, z: 5.6, radius: 2.4, kind: 'server' },
]
