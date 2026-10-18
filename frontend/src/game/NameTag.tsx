import { useRef } from 'react'
import { useFrame } from '@react-three/fiber'
import { Html } from '@react-three/drei'
import * as THREE from 'three'
import { theme } from '../theme'

/** 이름표 겹침 완화용: 같은 자리에 모인 캐릭터의 이름표를 위로 쌓는다 (key → 쌓임 단계) */
export const tagStack: Record<string, number> = {}

const _wp = new THREE.Vector3()

interface Props {
  name: string
  sub?: string
  /** AI 직원: 배지와 역할/상태 표시 */
  ai?: { role: string; status: string; statusColor: string }
  online?: boolean
  bubble?: string
  self?: boolean
  /** tagStack 조회용 키 */
  tagKey: string
}

/** 캐릭터 머리 위 이름표 + 말풍선. 항상 화면(카메라)을 향하고 캐릭터와 함께 움직인다. */
export function NameTag({ name, sub, ai, online = true, bubble, self, tagKey }: Props) {
  const anchor = useRef<THREE.Group>(null)
  const box = useRef<HTMLDivElement>(null)

  useFrame((state) => {
    const el = box.current
    if (!el || !anchor.current) return
    anchor.current.getWorldPosition(_wp)
    const d = state.camera.position.distanceTo(_wp)
    // 가까우면 선명, 멀어지면 투명해지다가 사라진다
    const o = d < 12 ? 1 : d > 22 ? 0 : 1 - (d - 12) / 10
    el.style.opacity = String(o)
    el.style.visibility = o <= 0.02 ? 'hidden' : 'visible'
    el.style.transform = `translateY(${-(tagStack[tagKey] ?? 0) * 44}px)`
  })

  return (
    <group ref={anchor} position={[0, 1.65, 0]}>
      <Html center distanceFactor={8} zIndexRange={[30, 0]} style={{ pointerEvents: 'none' }}>
        <div ref={box} className="flex flex-col items-center gap-1 select-none" style={{ transition: 'opacity .2s' }}>
          {bubble && (
            <div className="relative max-w-[220px] px-3 py-1.5 rounded-xl bg-white text-slate-900 text-[12px] leading-snug shadow-lg break-words text-center">
              {bubble}
              <span className="absolute left-1/2 -bottom-1 w-2 h-2 bg-white rotate-45 -translate-x-1/2" />
            </div>
          )}
          <div
            className="flex items-center gap-1.5 px-2.5 py-1 rounded-full border text-[11px] whitespace-nowrap bg-[#253247]/90"
            style={{ borderColor: self ? theme.status.working : 'rgba(255,255,255,0.12)' }}
          >
            <span className={`inline-block w-2 h-2 rounded-full ${ai?.status === '작업 중' ? 'pulse-dot' : ''}`} style={{ background: ai ? ai.statusColor : online ? theme.status.ok : theme.status.idle }} />
            <span className="text-slate-50 font-medium">{name}</span>
            {ai && <span className="px-1.5 rounded bg-violet-500/80 text-white text-[9px] font-bold tracking-wide">AI</span>}
            {ai ? <span className="text-slate-300">{ai.role} · {ai.status}</span> : sub ? <span className="text-slate-300">{sub}</span> : null}
          </div>
        </div>
      </Html>
    </group>
  )
}
