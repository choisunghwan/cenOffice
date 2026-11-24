import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import { stick, zoomCamera } from './input'

/**
 * 휴대폰·태블릿처럼 키보드가 없는 화면에서 3D 오피스를 움직이는 플로팅 조이스틱과 확대·축소 버튼.
 * 화면 왼쪽 절반 어디든 손가락을 대면 그 자리에 조이스틱이 나타나고, 끄는 방향으로 걷는다
 * (위=앞으로, 아래=뒤로, 옆=방향 돌리기, 많이 밀수록 빠르게). 오른쪽 절반은 지금처럼 끌어서 카메라를 돌린다.
 * 값은 input.ts 의 stick 에 넣고, Player 가 키보드 입력과 같은 방식으로 읽는다.
 */
const SIZE = 128 // 조이스틱 바깥 원 지름(px)
const KNOB = 54 // 손잡이 지름(px)
const RANGE = (SIZE - KNOB) / 2 // 손잡이가 움직일 수 있는 거리
const DEAD = 0.18 // 이보다 조금 민 것은 무시(손가락 떨림)

export function TouchControls() {
  const zone = useRef<HTMLDivElement>(null)
  const active = useRef<number | null>(null)
  // 손가락을 처음 댄 실제 지점. 방향은 늘 여기서부터 잰다(화면 가장자리라 원을 안쪽으로 옮겨 그려도 저절로 움직이지 않게)
  const origin = useRef({ x: 0, y: 0 })
  // 조이스틱 중심(터치 영역 기준 좌표). null 이면 손을 뗀 상태 → 왼쪽 아래에 흐린 안내용 조이스틱만 보인다
  const [center, setCenter] = useState<{ x: number; y: number } | null>(null)
  const [knob, setKnob] = useState({ x: 0, y: 0 })
  // 끄는 도중 패널이 열려 조이스틱이 사라져도 캐릭터가 계속 걷지 않게 멈춘다
  useEffect(() => () => { stick.x = 0; stick.z = 0 }, [])

  const local = (e: ReactPointerEvent) => {
    const r = zone.current!.getBoundingClientRect()
    // 조이스틱 원이 터치 영역 밖으로 잘리지 않게 중심을 안쪽으로 당긴다
    const half = SIZE / 2
    return {
      x: Math.min(Math.max(e.clientX - r.left, half), r.width - half),
      y: Math.min(Math.max(e.clientY - r.top, half), r.height - half),
      rx: e.clientX - r.left,
      ry: e.clientY - r.top,
    }
  }
  const aim = (e: ReactPointerEvent) => {
    const p = local(e)
    let dx = p.rx - origin.current.x
    let dy = p.ry - origin.current.y
    const d = Math.hypot(dx, dy)
    if (d > RANGE) { dx = (dx / d) * RANGE; dy = (dy / d) * RANGE }
    setKnob({ x: dx, y: dy })
    const nx = dx / RANGE
    const nz = -dy / RANGE
    stick.x = Math.abs(nx) < DEAD ? 0 : nx
    stick.z = Math.abs(nz) < DEAD ? 0 : nz
  }
  const start = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (active.current != null) return // 두 번째 손가락은 무시
    e.preventDefault()
    active.current = e.pointerId
    try { e.currentTarget.setPointerCapture(e.pointerId) } catch { /* 캡처가 안 돼도 움직임은 된다 */ }
    const p = local(e)
    origin.current = { x: p.rx, y: p.ry }
    setCenter({ x: p.x, y: p.y })
    aim(e)
  }
  const move = (e: ReactPointerEvent) => { if (active.current === e.pointerId) aim(e) }
  const end = (e: ReactPointerEvent) => {
    if (active.current !== e.pointerId) return
    active.current = null
    stick.x = 0
    stick.z = 0
    setCenter(null)
    setKnob({ x: 0, y: 0 })
  }
  const btn = 'glass grid place-items-center rounded-xl text-slate-100 active:bg-sky-500/40 select-none touch-none'

  return (
    <>
      {/* 왼쪽 절반 터치 영역. 왼쪽 메뉴·상호작용 버튼(z-10)보다 아래에 두어 그 버튼들은 그대로 눌린다 */}
      <div ref={zone} role="application" aria-label="이동 영역: 화면 왼쪽을 누른 채 끌면 그 방향으로 걷습니다"
        className="absolute left-0 bottom-0 top-[68px] w-1/2 z-[5] hidden max-sm:block pointer-coarse:block touch-none select-none"
        onPointerDown={start} onPointerMove={move} onPointerUp={end} onPointerCancel={end} onLostPointerCapture={end}
        onContextMenu={(e) => e.preventDefault()}>
        <div aria-hidden="true" className={`absolute rounded-full pointer-events-none transition-opacity duration-150 ${center ? 'glass opacity-100' : 'border border-white/25 bg-slate-900/20 opacity-70'}`}
          style={center
            ? { width: SIZE, height: SIZE, left: center.x - SIZE / 2, top: center.y - SIZE / 2 }
            : { width: SIZE, height: SIZE, left: 20, bottom: 20 }}>
          <span className="absolute rounded-full bg-sky-500/80 border border-white/40 shadow-lg"
            style={{ width: KNOB, height: KNOB, left: (SIZE - KNOB) / 2 + knob.x, top: (SIZE - KNOB) / 2 + knob.y }} />
          {!center && <span className="absolute inset-x-0 -top-6 text-center text-[11px] text-white/80 [text-shadow:0_1px_3px_#000a] whitespace-nowrap">왼쪽을 눌러 이동</span>}
        </div>
      </div>
      <div className="absolute right-4 bottom-24 z-10 hidden max-sm:flex pointer-coarse:flex flex-col gap-1.5" aria-label="확대 축소">
        <button type="button" aria-label="가까이 보기" className={`${btn} w-12 h-12 text-xl`} onClick={() => zoomCamera(-1.5)}>＋</button>
        <button type="button" aria-label="멀리 보기" className={`${btn} w-12 h-12 text-xl`} onClick={() => zoomCamera(1.5)}>－</button>
      </div>
    </>
  )
}
