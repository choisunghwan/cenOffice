export const keys = new Set<string>()

export const cameraRig = { yaw: 0, pitch: 0.55, dist: 7, focus: { x: -7, y: 1.4, z: 5.2 } }

export function bindInput(canvas: HTMLElement, onKey: (code: string) => void) {
  const down = (e: KeyboardEvent) => {
    if ((e.target as HTMLElement)?.tagName === 'INPUT' || (e.target as HTMLElement)?.tagName === 'TEXTAREA') return
    keys.add(e.code)
    if (!e.repeat) onKey(e.code)
  }
  const up = (e: KeyboardEvent) => keys.delete(e.code)
  const blur = () => keys.clear()

  let dragging = false
  let lx = 0
  let ly = 0
  const pd = (e: PointerEvent) => {
    dragging = true
    lx = e.clientX
    ly = e.clientY
    canvas.setPointerCapture(e.pointerId)
  }
  const pm = (e: PointerEvent) => {
    if (!dragging) return
    cameraRig.yaw -= (e.clientX - lx) * 0.006
    cameraRig.pitch = Math.min(1.25, Math.max(0.12, cameraRig.pitch + (e.clientY - ly) * 0.005))
    lx = e.clientX
    ly = e.clientY
  }
  const pu = (e: PointerEvent) => {
    dragging = false
    if (canvas.hasPointerCapture(e.pointerId)) canvas.releasePointerCapture(e.pointerId)
  }
  const wheel = (e: WheelEvent) => {
    cameraRig.dist = Math.min(14, Math.max(2.5, cameraRig.dist + e.deltaY * 0.005))
  }

  window.addEventListener('keydown', down)
  window.addEventListener('keyup', up)
  window.addEventListener('blur', blur)
  canvas.addEventListener('pointerdown', pd)
  canvas.addEventListener('pointermove', pm)
  canvas.addEventListener('pointerup', pu)
  canvas.addEventListener('wheel', wheel, { passive: true })
  return () => {
    window.removeEventListener('keydown', down)
    window.removeEventListener('keyup', up)
    window.removeEventListener('blur', blur)
    canvas.removeEventListener('pointerdown', pd)
    canvas.removeEventListener('pointermove', pm)
    canvas.removeEventListener('pointerup', pu)
    canvas.removeEventListener('wheel', wheel)
  }
}
