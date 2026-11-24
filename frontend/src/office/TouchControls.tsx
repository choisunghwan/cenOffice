import { useEffect, useState } from 'react'

/**
 * 휴대폰·태블릿 3D 오피스 조작 안내. 조작은 화면 자체로 한다:
 * 바닥을 톡 누르면 그곳으로 걷고(input.ts 의 moveTarget), 끌면 카메라 회전, 두 손가락으로 벌리거나 오므리면 확대·축소.
 * 좁은 화면을 가리지 않게 처음 몇 초만 보여 주고 사라진다.
 */
export function TouchControls() {
  const [show, setShow] = useState(true)
  useEffect(() => { const t = setTimeout(() => setShow(false), 4500); return () => clearTimeout(t) }, [])
  if (!show) return null
  return (
    <div role="note" onClick={() => setShow(false)}
      className="absolute left-1/2 -translate-x-1/2 top-[128px] z-10 hidden max-sm:block pointer-coarse:block glass rounded-full px-4 py-2 text-[12px] text-slate-200 whitespace-nowrap slide-in">
      톡 눌러 이동 · 끌어서 둘러보기 · 두 손가락으로 확대
    </div>
  )
}
