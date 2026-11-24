import { useEffect, useRef } from 'react'
import { useFrame, useThree } from '@react-three/fiber'
import * as THREE from 'three'
import { Character, type CharacterHandle } from './Character'
import { bindInput, cameraRig, keys, moveTarget } from './input'
import { resolve, raySpan } from './collision'
import { COLLIDERS, INTERACTABLES } from './layout'
import { useOffice } from '../store'
import { localPose } from '../multiplayer'
import { NameTag } from './NameTag'

const MAX_SPEED = 3.6
const ACCEL = 14 // 가속 (m/s²)
const DECEL = 18 // 감속 (m/s²)
const RADIUS = 0.35
const TURN_RATE = 12 // 캐릭터 회전 보간 계수 (1/s)
const CAM_FOLLOW = 10 // 카메라 추적 보간 계수 (1/s)
const HEAD_Y = 1.4
/** true: A/D가 시점(카메라+캐릭터)을 회전, W/S는 바라보는 방향으로 이동. false: A/D 좌우 평행이동 */
const TURN_MODE = true
const TURN_SPEED = 2.4 // rad/s

/** 프레임레이트 독립 보간 계수 */
const damp = (k: number, dt: number) => 1 - Math.exp(-k * dt)

function lerpAngle(a: number, b: number, t: number) {
  const d = Math.atan2(Math.sin(b - a), Math.cos(b - a))
  return a + d * t
}

const _fwd = new THREE.Vector3()
const _ray = new THREE.Raycaster()
const _ndc = new THREE.Vector2()
const _floor = new THREE.Plane(new THREE.Vector3(0, 1, 0), 0)
const _hit = new THREE.Vector3()
const ARRIVE = 0.2 // 이만큼 가까워지면 도착
const SLOW_NEAR = 1.0 // 목표까지 이 거리 안에서는 천천히 멈춘다

export function Player() {
  const group = useRef<THREE.Group>(null)
  const char = useRef<CharacterHandle>(null)
  const pos = useRef({ x: -7, z: 5.2 })
  const vel = useRef({ x: 0, z: 0 })
  const heading = useRef(Math.PI) // 모델 전방 = +Z, 초기엔 북쪽(-Z)을 바라봄
  const focus = useRef(new THREE.Vector3(-7, HEAD_Y, 5.2)) // 카메라가 바라보는 (보간된) 지점
  const camDist = useRef(cameraRig.dist)
  const { gl, camera } = useThree()
  const spawned = useRef(false)
  const marker = useRef<THREE.Mesh>(null)
  const stuck = useRef(0) // 벽에 막혀 거의 못 움직인 시간(s). 길이 막혔으면 이동을 그만둔다

  useEffect(
    () =>
      bindInput(gl.domElement, (code) => {
        const s = useOffice.getState()
        if (s.mode !== 'office') return
        if (code === 'Escape') s.close()
        else if (code === 'KeyE' && s.panel === 'none' && s.nearby) s.open(s.nearby.id)
      }, (cx, cy) => {
        // 톡 누른 화면 지점을 바닥(y=0)에 비춰 그곳을 걸어갈 목표로 삼는다
        const s = useOffice.getState()
        if (s.mode !== 'office' || s.panel !== 'none') return
        const r = gl.domElement.getBoundingClientRect()
        _ndc.set(((cx - r.left) / r.width) * 2 - 1, -((cy - r.top) / r.height) * 2 + 1)
        _ray.setFromCamera(_ndc, camera)
        if (_ray.ray.intersectPlane(_floor, _hit)) { moveTarget.current = { x: _hit.x, z: _hit.z }; stuck.current = 0 }
      }),
    [gl, camera],
  )

  useFrame((_, rawDt) => {
    const dt = Math.min(rawDt, 0.05)
    const st = useOffice.getState()
    // 같은 위치에서 여러 명이 겹쳐 시작하지 않도록 계정별로 출발 지점을 조금씩 달리한다
    if (!spawned.current && st.user) {
      const h = [...st.user.username].reduce((a, c) => (a * 31 + c.charCodeAt(0)) >>> 0, 7)
      pos.current.x = -7 + ((h % 5) - 2) * 0.8
      pos.current.z = 5.2 - (Math.floor(h / 5) % 2) * 1.2
      spawned.current = true
    }
    const paused = st.panel !== 'none' || st.mode !== 'office'

    // ── 입력 → 카메라 기준 이동 방향 ──────────────────────────
    let ix = 0 // 오른쪽(+)
    let iz = 0 // 앞(+)
    if (!paused) {
      iz = (keys.has('KeyW') || keys.has('ArrowUp') ? 1 : 0) - (keys.has('KeyS') || keys.has('ArrowDown') ? 1 : 0)
      ix = (keys.has('KeyD') || keys.has('ArrowRight') ? 1 : 0) - (keys.has('KeyA') || keys.has('ArrowLeft') ? 1 : 0)
      if (ix !== 0 || iz !== 0) moveTarget.current = null // 키보드로 움직이면 톡 이동은 그만둔다
    } else moveTarget.current = null
    // 톡 누른 곳으로 걷기: 카메라 방향과 상관없이 목표 쪽으로 곧장 걷고, 가까워지면 천천히 멈춘다
    const goal = moveTarget.current
    let toGoal: { x: number; z: number; d: number } | null = null
    if (goal) {
      const gx = goal.x - pos.current.x, gz = goal.z - pos.current.z, gd = Math.hypot(gx, gz)
      if (gd < ARRIVE) moveTarget.current = null
      else toGoal = { x: gx / gd, z: gz / gd, d: gd }
    }
    const throttle = toGoal ? Math.min(1, Math.max(0.35, toGoal.d / SLOW_NEAR)) : Math.min(1, Math.abs(iz) || Math.abs(ix))
    if (TURN_MODE) {
      cameraRig.yaw -= ix * TURN_SPEED * dt
      ix = 0
    }
    // 카메라 월드 방향에서 Y 제거 후 정규화 → 수평 전방, right = 전방 × up
    camera.getWorldDirection(_fwd)
    _fwd.y = 0
    if (_fwd.lengthSq() < 1e-6) _fwd.set(-Math.sin(cameraRig.yaw), 0, -Math.cos(cameraRig.yaw))
    _fwd.normalize()
    const rx = -_fwd.z
    const rz = _fwd.x

    let wx = toGoal ? toGoal.x : _fwd.x * iz + rx * ix
    let wz = toGoal ? toGoal.z : _fwd.z * iz + rz * ix
    const wl = Math.hypot(wx, wz)
    if (wl > 0) { wx /= wl; wz /= wl } // 대각선 속도 증가 방지

    // ── 가속/감속 ─────────────────────────────────────────────
    const v = vel.current
    const tx = wx * MAX_SPEED * throttle
    const tz = wz * MAX_SPEED * throttle
    const rate = (wl > 0 ? ACCEL : DECEL) * dt
    const dvx = tx - v.x
    const dvz = tz - v.z
    const dl = Math.hypot(dvx, dvz)
    if (dl <= rate) { v.x = tx; v.z = tz } else { v.x += (dvx / dl) * rate; v.z += (dvz / dl) * rate }

    const speed = Math.hypot(v.x, v.z)
    if (speed > 0.001) {
      pos.current.x += v.x * dt
      pos.current.z += v.z * dt
      const px = pos.current.x
      const pz = pos.current.z
      resolve(pos.current, RADIUS, COLLIDERS)
      // 벽에 막혔으면 해당 방향 속도 제거
      if (Math.abs(pos.current.x - px) > 1e-4) v.x = 0
      if (Math.abs(pos.current.z - pz) > 1e-4) v.z = 0
    }
    // 톡 이동 중인데 벽·가구에 막혀 거의 움직이지 못하면 잠시 뒤 멈춘다
    if (toGoal) { stuck.current = Math.hypot(v.x, v.z) < 0.4 ? stuck.current + dt : 0; if (stuck.current > 0.6) { moveTarget.current = null; stuck.current = 0 } }
    if (marker.current) {
      const g = moveTarget.current
      marker.current.visible = !!g
      if (g) { marker.current.position.set(g.x, 0.03, g.z); const k = 1 + 0.12 * Math.sin(performance.now() / 160); marker.current.scale.set(k, k, k) }
    }
    // ── 캐릭터 회전: 실제 이동 방향을 부드럽게 바라봄 ──────────
    // 톡 이동 중에는 걷는 방향을 바라보고, 그 밖에는 기존처럼 카메라 방향을 바라본다
    const target = TURN_MODE && !toGoal ? cameraRig.yaw + Math.PI : Math.atan2(v.x, v.z)
    if ((TURN_MODE && !toGoal) || speed > 0.3) heading.current = lerpAngle(heading.current, target, damp(TURN_RATE, dt))
    char.current?.setWalking(speed > 0.3)

    localPose.x = pos.current.x
    localPose.z = pos.current.z
    localPose.r = heading.current
    localPose.m = speed > 0.3

    if (group.current) {
      group.current.position.set(pos.current.x, 0, pos.current.z)
      group.current.rotation.y = heading.current
    }

    // ── 상호작용 대상 탐색 ────────────────────────────────────
    let best: (typeof INTERACTABLES)[number] | null = null
    let bestD = Infinity
    for (const it of INTERACTABLES) {
      const d = Math.hypot(it.x - pos.current.x, it.z - pos.current.z)
      if (d < it.radius && d < bestD) { best = it; bestD = d }
    }
    if ((best?.id ?? null) !== (st.nearby?.id ?? null)) st.setNearby(best)

    // ── 3인칭 추적 카메라 ─────────────────────────────────────
    // 카메라 방향(yaw/pitch)은 마우스 드래그로만 변경 → 캐릭터 방향 전환에 흔들리지 않음
    focus.current.x += (pos.current.x - focus.current.x) * damp(CAM_FOLLOW, dt)
    focus.current.z += (pos.current.z - focus.current.z) * damp(CAM_FOLLOW, dt)
    focus.current.y = HEAD_Y

    const { yaw, pitch, dist } = cameraRig
    const f = focus.current
    const dir = [Math.sin(yaw) * Math.cos(pitch), Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)]
    // 카메라가 벽/가구 안쪽(또는 바로 앞)에 놓이게 되면 그 앞으로 당긴다.
    // 벽 너머에 있는 경우는 관통이 아니라 "시야 가림"이므로 Office의 벽 투명화가 처리한다.
    const pad = 0.4 / dist
    let wanted = dist
    for (const b of COLLIDERS) {
      if (b.glass) continue
      const s = raySpan(f.x, f.y, f.z, f.x + dir[0] * dist, f.y + dir[1] * dist, f.z + dir[2] * dist, b)
      if (s && s[0] > 0 && 1 >= s[0] - pad && 1 <= s[1] + pad) wanted = Math.min(wanted, Math.max(1.2, dist * s[0] - 0.3))
    }
    // 벽에 가까워지면 즉시 당기고(관통 방지), 멀어질 땐 부드럽게 복귀
    camDist.current = wanted < camDist.current ? wanted : camDist.current + (wanted - camDist.current) * damp(6, dt)
    const d = camDist.current
    cameraRig.focus.x = f.x
    cameraRig.focus.y = f.y
    cameraRig.focus.z = f.z
    camera.position.set(f.x + dir[0] * d, f.y + dir[1] * d, f.z + dir[2] * d)
    camera.lookAt(f.x, 1.1, f.z)
  })

  return (
    <>
      <group ref={group}>
        <Character ref={char} shirt="#4f8cff" hair="#2a1d1a" />
        <SelfTag />
      </group>
      {/* 톡 누른 목적지 표시 */}
      <mesh ref={marker} rotation={[-Math.PI / 2, 0, 0]} visible={false} renderOrder={2}>
        <ringGeometry args={[0.22, 0.34, 32]} />
        <meshBasicMaterial color="#38bdf8" transparent opacity={0.85} depthWrite={false} />
      </mesh>
    </>
  )
}

function SelfTag() {
  const user = useOffice((s) => s.user)
  const bubble = useMultiplayerBubble(user?.username)
  if (!user) return null
  return <NameTag name={user.displayName} sub={user.team} bubble={bubble} self tagKey={user.username} />
}

import { useMultiplayer } from '../multiplayer'
function useMultiplayerBubble(key?: string) {
  return useMultiplayer((s) => (key ? s.bubbles[key]?.text : undefined))
}
