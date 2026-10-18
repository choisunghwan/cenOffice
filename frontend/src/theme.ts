/**
 * CEN OFFICE 테마 — 3D 재질/조명 색상과 UI 상태 색상을 한 곳에서 관리합니다.
 * 색상만 바꾸면 사무실 전체 분위기가 바뀌도록 모든 3D 컴포넌트는 여기만 참조합니다.
 */
export const theme = {
  // ── 3D 공간 ───────────────────────────────────────────────
  floor: '#D9DDE3',
  floorGrid: '#c3c8d0', // 바닥 타일 줄눈
  wall: '#F4F5F7',
  accentWall: '#253247', // 포인트 벽
  skirting: '#d3d7de', // 걸레받이
  desk: '#F1F2F4',
  frame: '#303846', // 의자·책상 프레임
  chairSeat: '#3b4658',
  light: '#FFF1D6', // 조명 색
  glass: '#cfe6f5',
  rug: '#8c97ab',
  serverFloor: '#2c3647',
  cabinet: '#e4e7ec',
  sofa: '#46556f',
  plantPot: '#eceef1',
  plantLeaf: '#4c8f63',
  screen: '#1b2740',
  // ── 외부/환경 ────────────────────────────────────────────
  sky: { on: '#c3cddc', off: '#2b364b' },
  ground: { on: '#a9b3c4', off: '#222b3c' },
  // ── 조명 세기 ────────────────────────────────────────────
  lighting: {
    on: { ambient: 0.35, hemi: 0.45, sun: 1.1, point: 9, cove: 2.2, env: 0.75 },
    off: { ambient: 0.22, hemi: 0.2, sun: 0.28, point: 0, cove: 0, env: 0.2 },
  },
  // ── UI 상태 색상 (AI 직원/장비/연결 공통) ─────────────────
  status: {
    ok: '#4fd1a1', // 정상·대기·완료
    working: '#5aa2ff', // 작업 중
    warn: '#ffc857', // 승인 대기·주의
    error: '#ff6b6b', // 오류·장애
    idle: '#8a96ad', // 미연결
  },
} as const
