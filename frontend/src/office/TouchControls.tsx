import type { PointerEvent as ReactPointerEvent } from 'react'
import { cameraRig, keys } from './input'

/**
 * 휴대폰·태블릿처럼 키보드가 없는 화면에서 3D 오피스를 움직이는 버튼.
 * 누르고 있는 동안 WASD 키를 누른 것과 똑같이 keys 에 넣어 Player 의 이동 로직을 그대로 쓴다.
 */
const PAD: { code: string; label: string; icon: string; area: string }[] = [
  { code: 'KeyW', label: '앞으로', icon: '▲', area: '1 / 2' },
  { code: 'KeyA', label: '왼쪽으로', icon: '◀', area: '2 / 1' },
  { code: 'KeyD', label: '오른쪽으로', icon: '▶', area: '2 / 3' },
  { code: 'KeyS', label: '뒤로', icon: '▼', area: '3 / 2' },
]

const zoom = (delta: number) => { cameraRig.dist = Math.min(14, Math.max(2.5, cameraRig.dist + delta)) }

export function TouchControls() {
  const press = (code: string) => (e: ReactPointerEvent<HTMLButtonElement>) => {
    e.preventDefault()
    try { e.currentTarget.setPointerCapture(e.pointerId) } catch { /* 캡처가 안 돼도 누르는 동안 이동은 된다 */ }
    keys.add(code)
  }
  const release = (code: string) => () => keys.delete(code)
  const btn = 'glass grid place-items-center rounded-xl text-slate-100 active:bg-sky-500/40 select-none touch-none'

  return (
    <>
      <div className="touch-pad absolute left-4 bottom-4 z-10 hidden max-sm:grid pointer-coarse:grid grid-cols-3 grid-rows-3 gap-1.5" aria-label="이동 버튼">
        {PAD.map((p) => (
          <button key={p.code} type="button" aria-label={p.label} style={{ gridArea: p.area }} className={`${btn} w-12 h-12 text-base`}
            onPointerDown={press(p.code)} onPointerUp={release(p.code)} onPointerCancel={release(p.code)} onLostPointerCapture={release(p.code)}
            onContextMenu={(e) => e.preventDefault()}>{p.icon}</button>
        ))}
      </div>
      <div className="absolute right-4 bottom-24 z-10 hidden max-sm:flex pointer-coarse:flex flex-col gap-1.5" aria-label="확대 축소">
        <button type="button" aria-label="가까이 보기" className={`${btn} w-11 h-11 text-xl`} onClick={() => zoom(-1.5)}>＋</button>
        <button type="button" aria-label="멀리 보기" className={`${btn} w-11 h-11 text-xl`} onClick={() => zoom(1.5)}>－</button>
      </div>
    </>
  )
}
