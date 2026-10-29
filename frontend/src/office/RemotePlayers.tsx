import { useRef } from 'react'
import { useFrame } from '@react-three/fiber'
import * as THREE from 'three'
import { useMultiplayer, localPose, type Remote } from '../multiplayer'
import { useOffice } from '../store'
import { Character, type CharacterHandle } from './Character'
import { NameTag, tagStack } from './NameTag'

const SHIRTS = ['#ef6f6c', '#f2a65a', '#6bbf8a', '#5fb0c9', '#a285d8', '#d97cb3', '#c2b34d']
const shirtFor = (name: string) => SHIRTS[[...name].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 7) % SHIRTS.length]

const damp = (k: number, dt: number) => 1 - Math.exp(-k * dt)
const lerpAngle = (a: number, b: number, t: number) => a + Math.atan2(Math.sin(b - a), Math.cos(b - a)) * t

/** 다른 직원 한 명. 서버에서 받은 목표 위치를 향해 부드럽게 보간한다(네트워크 전송은 초당 최대 10회). */
function RemotePlayer({ username }: { username: string }) {
  const group = useRef<THREE.Group>(null)
  const char = useRef<CharacterHandle>(null)
  const init = useRef(false)
  const av: Remote | undefined = useMultiplayer((s) => s.avatars[username])
  const bubble = useMultiplayer((s) => s.bubbles[username]?.text)

  useFrame((_, rawDt) => {
    const g = group.current
    const a = useMultiplayer.getState().avatars[username]
    if (!g || !a) return
    const dt = Math.min(rawDt, 0.1)
    if (!init.current) { g.position.set(a.x, 0, a.z); g.rotation.y = a.r; init.current = true }
    const k = damp(12, dt)
    const dx = a.x - g.position.x
    const dz = a.z - g.position.z
    // 너무 멀면(재접속/순간 이동) 보간 없이 바로 이동
    if (dx * dx + dz * dz > 25) { g.position.set(a.x, 0, a.z) } else { g.position.x += dx * k; g.position.z += dz * k }
    g.rotation.y = lerpAngle(g.rotation.y, a.r, damp(10, dt))
    char.current?.setWalking(a.m || dx * dx + dz * dz > 0.0016)
  })

  if (!av) return null
  return (
    <group ref={group}>
      <Character ref={char} shirt={shirtFor(username)} hair="#2b2320" />
      <NameTag name={av.displayName} sub={av.team} bubble={bubble} tagKey={username} />
    </group>
  )
}

/** 접속 중인 다른 직원 전원 + 이름표 겹침 정리 */
export function RemotePlayers() {
  const names = useMultiplayer((s) => Object.keys(s.avatars).sort().join('|'))
  const me = useOffice((s) => s.user?.username)

  useFrame(() => {
    // 서로 1m 이내에 모인 캐릭터는 이름 순서대로 이름표를 위로 쌓는다
    const avs = useMultiplayer.getState().avatars
    const pts: { k: string; x: number; z: number }[] = Object.values(avs).map((a) => ({ k: a.username, x: a.x, z: a.z }))
    if (me) pts.push({ k: me, x: localPose.x, z: localPose.z })
    pts.sort((p, q) => (p.k < q.k ? -1 : 1))
    for (let i = 0; i < pts.length; i++) {
      let n = 0
      for (let j = 0; j < i; j++) if (Math.hypot(pts[i].x - pts[j].x, pts[i].z - pts[j].z) < 1.0) n++
      tagStack[pts[i].k] = n
    }
    for (const k of Object.keys(tagStack)) if (!pts.some((p) => p.k === k)) delete tagStack[k]
  })

  return <>{names ? names.split('|').map((u) => <RemotePlayer key={u} username={u} />) : null}</>
}
