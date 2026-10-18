import { forwardRef, useImperativeHandle, useRef } from 'react'
import { useFrame } from '@react-three/fiber'
import * as THREE from 'three'

export interface CharacterHandle {
  setWalking: (walking: boolean) => void
}

interface Props {
  shirt: string
  pants?: string
  hair?: string
  skin?: string
  seated?: boolean
}

/** 프리미티브 기반 Stylized 캐릭터. 정면은 +Z. */
export const Character = forwardRef<CharacterHandle, Props>(function Character(
  { shirt, pants = '#2c3550', hair = '#2a2230', skin = '#f2c9a5', seated = false },
  ref,
) {
  const walking = useRef(false)
  const phase = useRef(0)
  const body = useRef<THREE.Group>(null)
  const legL = useRef<THREE.Group>(null)
  const legR = useRef<THREE.Group>(null)
  const armL = useRef<THREE.Group>(null)
  const armR = useRef<THREE.Group>(null)

  useImperativeHandle(ref, () => ({ setWalking: (w) => { walking.current = w } }))

  useFrame((_, dt) => {
    const w = walking.current
    phase.current += dt * (w ? 9 : 1.6)
    const s = Math.sin(phase.current)
    const amp = w ? 0.75 : 0
    if (!seated) {
      if (legL.current) legL.current.rotation.x = s * amp
      if (legR.current) legR.current.rotation.x = -s * amp
      if (armL.current) armL.current.rotation.x = -s * amp * 0.8
      if (armR.current) armR.current.rotation.x = s * amp * 0.8
    }
    if (body.current) {
      body.current.position.y = (seated ? 0 : 0) + (w ? Math.abs(Math.sin(phase.current)) * 0.05 : Math.sin(phase.current) * 0.008)
    }
  })

  const mat = (c: string) => <meshStandardMaterial color={c} roughness={0.7} />
  const legRot: [number, number, number] = seated ? [-Math.PI / 2, 0, 0] : [0, 0, 0]
  const yOff = seated ? 0.05 : 0

  return (
    <group ref={body} position={[0, yOff, 0]}>
      {/* 다리 */}
      <group ref={legL} position={[-0.11, 0.5, 0]} rotation={legRot}>
        <mesh position={[0, -0.22, 0]} castShadow><capsuleGeometry args={[0.075, 0.34, 4, 8]} />{mat(pants)}</mesh>
      </group>
      <group ref={legR} position={[0.11, 0.5, 0]} rotation={legRot}>
        <mesh position={[0, -0.22, 0]} castShadow><capsuleGeometry args={[0.075, 0.34, 4, 8]} />{mat(pants)}</mesh>
      </group>
      {/* 몸통 */}
      <mesh position={[0, 0.78, 0]} castShadow><capsuleGeometry args={[0.2, 0.3, 6, 12]} />{mat(shirt)}</mesh>
      {/* 팔 */}
      <group ref={armL} position={[-0.29, 0.98, 0]} rotation={seated ? [-1.2, 0, 0] : [0, 0, 0]}>
        <mesh position={[0, -0.2, 0]} castShadow><capsuleGeometry args={[0.062, 0.3, 4, 8]} />{mat(shirt)}</mesh>
      </group>
      <group ref={armR} position={[0.29, 0.98, 0]} rotation={seated ? [-1.2, 0, 0] : [0, 0, 0]}>
        <mesh position={[0, -0.2, 0]} castShadow><capsuleGeometry args={[0.062, 0.3, 4, 8]} />{mat(shirt)}</mesh>
      </group>
      {/* 머리 */}
      <mesh position={[0, 1.28, 0]} castShadow><sphereGeometry args={[0.2, 20, 20]} />{mat(skin)}</mesh>
      <mesh position={[0, 1.34, -0.02]} castShadow><sphereGeometry args={[0.215, 20, 14, 0, Math.PI * 2, 0, Math.PI * 0.55]} />{mat(hair)}</mesh>
      {/* 눈 */}
      <mesh position={[-0.07, 1.29, 0.185]}><sphereGeometry args={[0.025, 8, 8]} /><meshBasicMaterial color="#1b1b22" /></mesh>
      <mesh position={[0.07, 1.29, 0.185]}><sphereGeometry args={[0.025, 8, 8]} /><meshBasicMaterial color="#1b1b22" /></mesh>
    </group>
  )
})

