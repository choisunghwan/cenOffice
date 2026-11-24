import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import { stick, zoomCamera } from './input'

/**
 * 휴대폰·태블릿처럼 키보드가 없는 화면에서 3D 오피스를 움직이는 조이스틱과 확대·축소 버튼.
 * 원 안의 손잡이를 끌면 위=앞으로, 아래=뒤로, 옆=방향 돌리기. 많이 밀수록 빨리 걷는다.
 * 값은 input.ts 의 stick 에 넣고, Player 가 키보드 입력과 같은 방식으로 읽는다.
 */
const SIZE = 132 // 조이스틱 바깥 원 지름(px)
const KNOB = 56 // 손잡이 지름(px)
const RANGE = (SIZE - KNOB) / 2 // 손잡이가 움직일 수 있는 거리
const DEAD = 0.18 // 이보다 조금 민 것은 무시(손가락 떨림)

export function TouchControls() {
  const base = useRef<HTMLDivElement>(null)
  const [knob, setKnob] = useState({ x: 0, y: 0 })
  const [dragging, setDragging] = useState(false)
  const active = useRef<number | null>(null)
  // 끄는 도중 패널이 열려 조이스틱이 사라져도 캐릭터가 계속 걷지 않게 멈춘다
  useEffect(() => () => { stick.x = 0; stick.z = 0 }, [])

  const update = (e: ReactPointerEvent) => {
    const r = base.current?.getBoundingClientRect()
    if (!r) return
    let dx = e.clientX - (r.left + r.width / 2)
    let dy = e.clientY - (r.top + r.height / 2)
    const d = Math.hypot(dx, dy)
    if (d > RANGE) { dx = (dx / d) * RANGE; dy = (dy / d) * RANGE }
    setKnob({ x: dx, y: dy })
    const nx = dx / RANGE
    const nz = -dy / RANGE
    stick.x = Math.abs(nx) < DEAD ? 0 : nx
    stick.z = Math.abs(nz) < DEAD ? 0 : nz
  }
  const start = (e: ReactPointerEvent<HTMLDivElement>) => {
    e.preventDefault()
    active.current = e.pointerId
    setDragging(true)
    try { e.currentTarget.setPointerCapture(e.pointerId) } catch { /* 캡처가 안 돼도 움직임은 된다 */ }
    update(e)
  }
  const move = (e: ReactPointerEvent) => { if (active.current === e.pointerId) update(e) }
  const end = (e: ReactPointerEvent) => {
    if (active.current !== e.pointerId) return
    active.current = null
    stick.x = 0
    stick.z = 0
    setDragging(false)
    setKnob({ x: 0, y: 0 })
  }
  const btn = 'glass grid place-items-center rounded-xl text-slate-100 active:bg-sky-500/40 select-none touch-none'

  return (
    <>
      <div ref={base} role="application" aria-label="이동 조이스틱: 위로 밀면 앞으로, 옆으로 밀면 방향 돌리기"
        className="glass absolute left-5 bottom-5 z-10 hidden max-sm:block pointer-coarse:block rounded-full touch-none select-none"
        style={{ width: SIZE, height: SIZE }}
        onPointerDown={start} onPointerMove={move} onPointerUp={end} onPointerCancel={end} onLostPointerCapture={end}
        onContextMenu={(e) => e.preventDefault()}>
        <span aria-hidden="true" className="absolute inset-0 text-[10px] text-slate-400 pointer-events-none">
          <span className="absolute top-1.5 left-1/2 -translate-x-1/2">▲</span><span className="absolute bottom-1.5 left-1/2 -translate-x-1/2">▼</span>
          <span className="absolute left-2 top-1/2 -translate-y-1/2">◀</span><span className="absolute right-2 top-1/2 -translate-y-1/2">▶</span>
        </span>
        <span aria-hidden="true" className="absolute rounded-full bg-sky-500/80 border border-white/40 shadow-lg pointer-events-none"
          style={{ width: KNOB, height: KNOB, left: (SIZE - KNOB) / 2 + knob.x, top: (SIZE - KNOB) / 2 + knob.y, transition: dragging ? 'none' : 'left .12s, top .12s' }} />
      </div>
      <div className="absolute right-4 bottom-24 z-10 hidden max-sm:flex pointer-coarse:flex flex-col gap-1.5" aria-label="확대 축소">
        <button type="button" aria-label="가까이 보기" className={`${btn} w-12 h-12 text-xl`} onClick={() => zoomCamera(-1.5)}>＋</button>
        <button type="button" aria-label="멀리 보기" className={`${btn} w-12 h-12 text-xl`} onClick={() => zoomCamera(1.5)}>－</button>
      </div>
    </>
  )
}
