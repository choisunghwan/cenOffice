export const keys = new Set<string>()

/** 휴대폰처럼 좁은 화면인지. 좁은 화면은 카메라를 더 멀리 두고 시야각을 넓혀 너무 확대돼 보이지 않게 한다. */
export const isNarrowScreen = typeof window !== 'undefined' && window.matchMedia('(max-width: 640px)').matches

export const CAMERA_MIN_DIST = 2.5
export const CAMERA_MAX_DIST = 14
export const cameraRig = { yaw: 0, pitch: isNarrowScreen ? 0.75 : 0.55, dist: isNarrowScreen ? 10.5 : 7, focus: { x: -7, y: 1.4, z: 5.2 } }
export const zoomCamera = (delta: number) => { cameraRig.dist = Math.min(CAMERA_MAX_DIST, Math.max(CAMERA_MIN_DIST, cameraRig.dist + delta)) }

export function bindInput(canvas: HTMLElement, onKey: (code: string) => void) {
  const down = (e: KeyboardEvent) => {
    if ((e.target as HTMLElement)?.tagName === 'INPUT' || (e.target as HTMLElement)?.tagName === 'TEXTAREA') return
    keys.add(e.code)
    if (!e.repeat) onKey(e.code)
  }
  const up = (e: KeyboardEvent) => keys.delete(e.code)
  const blur = () => keys.clear()

  // 손가락(포인터) 하나로 끌면 카메라 회전, 두 손가락을 벌리거나 오므리면(핀치) 확대·축소
  canvas.style.touchAction = 'none'
  const pointers = new Map<number, { x: number; y: number }>()
  let lx = 0
  let ly = 0
  let pinch = 0
  const spread = () => { const [a, b] = [...pointers.values()]; return Math.hypot(a.x - b.x, a.y - b.y) }
  const pd = (e: PointerEvent) => {
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY })
    lx = e.clientX
    ly = e.clientY
    if (pointers.size === 2) pinch = spread()
    try { canvas.setPointerCapture(e.pointerId) } catch { /* 이미 끝난 포인터 */ }
  }
  const pm = (e: PointerEvent) => {
    if (!pointers.has(e.pointerId)) return
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY })
    if (pointers.size >= 2) {
      const d = spread()
      if (pinch > 0) zoomCamera((pinch - d) * 0.03)
      pinch = d
      return
    }
    cameraRig.yaw -= (e.clientX - lx) * 0.006
    cameraRig.pitch = Math.min(1.25, Math.max(0.12, cameraRig.pitch + (e.clientY - ly) * 0.005))
    lx = e.clientX
    ly = e.clientY
  }
  const pu = (e: PointerEvent) => {
    pointers.delete(e.pointerId)
    pinch = 0
    // 핀치 뒤 남은 손가락으로 이어서 돌릴 때 카메라가 튀지 않게 기준점을 다시 잡는다
    const rest = [...pointers.values()][0]
    if (rest) { lx = rest.x; ly = rest.y }
    if (canvas.hasPointerCapture(e.pointerId)) canvas.releasePointerCapture(e.pointerId)
  }
  const wheel = (e: WheelEvent) => zoomCamera(e.deltaY * 0.005)

  window.addEventListener('keydown', down)
  window.addEventListener('keyup', up)
  window.addEventListener('blur', blur)
  canvas.addEventListener('pointerdown', pd)
  canvas.addEventListener('pointermove', pm)
  canvas.addEventListener('pointerup', pu)
  canvas.addEventListener('pointercancel', pu)
  canvas.addEventListener('wheel', wheel, { passive: true })
  return () => {
    window.removeEventListener('keydown', down)
    window.removeEventListener('keyup', up)
    window.removeEventListener('blur', blur)
    canvas.removeEventListener('pointerdown', pd)
    canvas.removeEventListener('pointermove', pm)
    canvas.removeEventListener('pointerup', pu)
    canvas.removeEventListener('pointercancel', pu)
    canvas.removeEventListener('wheel', wheel)
  }
}
