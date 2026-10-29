import { useMemo, useRef } from 'react'
import { useFrame } from '@react-three/fiber'
import { Environment, Html, Lightformer, useTexture } from '@react-three/drei'
import * as THREE from 'three'
import { useOffice } from '../store'
import { theme } from '../theme'
import { cameraRig } from './input'
import { raySpan } from './collision'
import { AC_POS, HUB_POS, AI_DESK, AI_POS, DESKS, LIGHT_SWITCH, MEETING_TABLE, OFFICE, RACKS, WALLS } from './layout'

// ───────────────────────── 텍스처 / 재질 ─────────────────────────

function floorTexture() {
  const c = document.createElement('canvas')
  c.width = c.height = 512
  const g = c.getContext('2d')!
  g.fillStyle = '#ffffff'
  g.fillRect(0, 0, 512, 512)
  // 600mm 타일 느낌의 미세한 톤 차이 + 줄눈
  for (let i = 0; i < 2; i++) {
    for (let j = 0; j < 2; j++) {
      const v = 246 + ((i * 7 + j * 13) % 9)
      g.fillStyle = `rgb(${v},${v},${v + 2})`
      g.fillRect(i * 256, j * 256, 256, 256)
    }
  }
  g.strokeStyle = theme.floorGrid
  g.lineWidth = 3
  g.strokeRect(0, 0, 512, 512)
  g.beginPath()
  g.moveTo(256, 0); g.lineTo(256, 512)
  g.moveTo(0, 256); g.lineTo(512, 256)
  g.stroke()
  const t = new THREE.CanvasTexture(c)
  t.wrapS = t.wrapT = THREE.RepeatWrapping
  t.repeat.set(12, 8)
  t.colorSpace = THREE.SRGBColorSpace
  t.anisotropy = 8
  return t
}

function screenTexture() {
  const c = document.createElement('canvas')
  c.width = 256
  c.height = 160
  const g = c.getContext('2d')!
  const grad = g.createLinearGradient(0, 0, 256, 160)
  grad.addColorStop(0, '#1d2d4f')
  grad.addColorStop(1, '#2b4a86')
  g.fillStyle = grad
  g.fillRect(0, 0, 256, 160)
  g.fillStyle = 'rgba(255,255,255,0.9)'
  g.fillRect(14, 14, 110, 70)
  g.fillStyle = '#5aa2ff'
  g.fillRect(14, 14, 110, 10)
  g.fillStyle = 'rgba(255,255,255,0.35)'
  for (let i = 0; i < 5; i++) g.fillRect(22, 34 + i * 10, 60 + ((i * 17) % 35), 4)
  g.fillStyle = 'rgba(255,255,255,0.18)'
  g.fillRect(136, 14, 106, 130)
  g.fillStyle = '#4fd1a1'
  g.fillRect(146, 100, 14, 36); g.fillStyle = '#5aa2ff'; g.fillRect(166, 76, 14, 60); g.fillStyle = '#ffc857'; g.fillRect(186, 56, 14, 80)
  const t = new THREE.CanvasTexture(c)
  t.colorSpace = THREE.SRGBColorSpace
  return t
}

function blobTexture() {
  const c = document.createElement('canvas')
  c.width = c.height = 128
  const g = c.getContext('2d')!
  const r = g.createRadialGradient(64, 64, 6, 64, 64, 64)
  r.addColorStop(0, 'rgba(20,28,45,0.38)')
  r.addColorStop(1, 'rgba(20,28,45,0)')
  g.fillStyle = r
  g.fillRect(0, 0, 128, 128)
  return new THREE.CanvasTexture(c)
}

const std = (color: string, roughness = 0.6, metalness = 0) => new THREE.MeshStandardMaterial({ color, roughness, metalness })

const M = {
  desk: std(theme.desk, 0.45),
  frame: std(theme.frame, 0.5, 0.2),
  seat: std(theme.chairSeat, 0.85),
  dark: std('#1b2230', 0.35, 0.5),
  key: std('#dfe2e8', 0.5),
  cabinet: std(theme.cabinet, 0.5),
  sofa: std(theme.sofa, 0.95),
  pot: std(theme.plantPot, 0.6),
  leaf: std(theme.plantLeaf, 0.8),
  white: std('#f7f8fa', 0.4),
  rug: std(theme.rug, 1),
  mug: std('#ffffff', 0.3),
  paper: std('#ffffff', 0.9),
  glass: new THREE.MeshPhysicalMaterial({ color: theme.glass, transparent: true, opacity: 0.14, roughness: 0.04, metalness: 0, depthWrite: false, side: THREE.DoubleSide }),
}

type V3 = [number, number, number]
function Bx({ p, s, m, shadow = true, ry = 0 }: { p: V3; s: V3; m: THREE.Material; shadow?: boolean; ry?: number }) {
  return (
    <mesh position={p} rotation={[0, ry, 0]} material={m} castShadow={shadow} receiveShadow>
      <boxGeometry args={s} />
    </mesh>
  )
}

/** 가구 바닥에 깔리는 부드러운 접촉 그림자(가짜 Ambient Occlusion) */
function AoBlob({ x, z, w, d }: { x: number; z: number; w: number; d: number }) {
  const tex = useMemo(blobTexture, [])
  return (
    <mesh rotation={[-Math.PI / 2, 0, 0]} position={[x, 0.012, z]} renderOrder={1}>
      <planeGeometry args={[w, d]} />
      <meshBasicMaterial map={tex} transparent depthWrite={false} toneMapped={false} />
    </mesh>
  )
}

// ───────────────────────── 소품 / 가구 ─────────────────────────

const SCREEN_TEX = { current: null as THREE.Texture | null }
function Monitor({ x, z, y = 0.8, on = true }: { x: number; z: number; y?: number; on?: boolean }) {
  const tex = useMemo(() => (SCREEN_TEX.current ??= screenTexture()), [])
  return (
    <group position={[x, y, z]}>
      <Bx p={[0, 0.01, 0]} s={[0.26, 0.02, 0.18]} m={M.frame} />
      <Bx p={[0, 0.13, -0.02]} s={[0.04, 0.24, 0.03]} m={M.frame} />
      <Bx p={[0, 0.36, -0.03]} s={[0.62, 0.36, 0.025]} m={M.dark} />
      <mesh position={[0, 0.36, -0.016]}>
        <planeGeometry args={[0.58, 0.32]} />
        <meshStandardMaterial map={tex} emissiveMap={tex} emissive="#ffffff" emissiveIntensity={on ? 0.8 : 0} toneMapped={false} />
      </mesh>
    </group>
  )
}

function Keyboard({ x, z }: { x: number; z: number }) {
  return (
    <group position={[x, 0.805, z]}>
      <Bx p={[0, 0.01, 0]} s={[0.42, 0.02, 0.14]} m={M.key} />
      <Bx p={[0.34, 0.01, 0.01]} s={[0.06, 0.02, 0.1]} m={M.dark} />
    </group>
  )
}

function Mug({ x, z }: { x: number; z: number }) {
  return (
    <mesh position={[x, 0.855, z]} material={M.mug} castShadow>
      <cylinderGeometry args={[0.04, 0.035, 0.1, 14]} />
    </mesh>
  )
}

function Desk({ x, z, w = 2.2, d = 1 }: { x: number; z: number; w?: number; d?: number }) {
  return (
    <group position={[x, 0, z]}>
      <AoBlob x={0} z={0} w={w + 0.8} d={d + 0.8} />
      <Bx p={[0, 0.78, 0]} s={[w, 0.04, d]} m={M.desk} />
      <Bx p={[-(w / 2 - 0.06), 0.38, 0]} s={[0.04, 0.76, d - 0.12]} m={M.frame} />
      <Bx p={[w / 2 - 0.06, 0.38, 0]} s={[0.04, 0.76, d - 0.12]} m={M.frame} />
      <Bx p={[0, 0.5, -d / 2 + 0.1]} s={[w - 0.2, 0.4, 0.02]} m={M.frame} />
      {/* 서랍 유닛 */}
      <Bx p={[w / 2 - 0.36, 0.5, 0.02]} s={[0.5, 0.5, d - 0.25]} m={M.desk} />
      <Bx p={[w / 2 - 0.36, 0.58, d / 2 - 0.12]} s={[0.4, 0.01, 0.01]} m={M.frame} />
      <Bx p={[w / 2 - 0.36, 0.42, d / 2 - 0.12]} s={[0.4, 0.01, 0.01]} m={M.frame} />
    </group>
  )
}

/** 사무용 의자. 등받이는 -Z 쪽. */
function Chair({ x, z, ry = 0 }: { x: number; z: number; ry?: number }) {
  return (
    <group position={[x, 0, z]} rotation={[0, ry, 0]}>
      <AoBlob x={0} z={0} w={1.1} d={1.1} />
      {[0, 1, 2, 3, 4].map((i) => (
        <Bx key={i} p={[Math.sin((i * 2 * Math.PI) / 5) * 0.14, 0.07, Math.cos((i * 2 * Math.PI) / 5) * 0.14]} s={[0.05, 0.03, 0.3]} m={M.frame} ry={(i * 2 * Math.PI) / 5} />
      ))}
      <mesh position={[0, 0.28, 0]} material={M.frame} castShadow>
        <cylinderGeometry args={[0.03, 0.03, 0.42, 10]} />
      </mesh>
      <Bx p={[0, 0.5, 0.02]} s={[0.5, 0.09, 0.48]} m={M.seat} />
      <Bx p={[0, 0.84, -0.24]} s={[0.46, 0.5, 0.05]} m={M.seat} />
      <Bx p={[0, 0.58, -0.22]} s={[0.05, 0.16, 0.05]} m={M.frame} />
      <Bx p={[-0.28, 0.67, 0]} s={[0.04, 0.04, 0.3]} m={M.frame} />
      <Bx p={[0.28, 0.67, 0]} s={[0.04, 0.04, 0.3]} m={M.frame} />
    </group>
  )
}

function Plant({ x, z }: { x: number; z: number }) {
  return (
    <group position={[x, 0, z]}>
      <AoBlob x={0} z={0} w={1.4} d={1.4} />
      <mesh position={[0, 0.28, 0]} material={M.pot} castShadow>
        <cylinderGeometry args={[0.28, 0.2, 0.56, 20]} />
      </mesh>
      {[0, 1, 2, 3, 4].map((i) => (
        <mesh key={i} position={[Math.sin(i * 1.3) * 0.16, 0.8 + i * 0.12, Math.cos(i * 1.3) * 0.16]} material={M.leaf} castShadow>
          <sphereGeometry args={[0.3 - i * 0.03, 14, 14]} />
        </mesh>
      ))}
    </group>
  )
}

function Rack({ x, z }: { x: number; z: number }) {
  return (
    <group position={[x, 0, z]}>
      <AoBlob x={0} z={0} w={1.8} d={1.8} />
      <Bx p={[0, 1, 0]} s={[0.9, 2, 0.9]} m={M.dark} />
      <Bx p={[0, 1, -0.455]} s={[0.78, 1.86, 0.02]} m={M.frame} />
      {[0, 1, 2, 3, 4, 5, 6].map((j) => (
        <mesh key={j} position={[0, 0.3 + j * 0.25, -0.47]}>
          <boxGeometry args={[0.7, 0.04, 0.01]} />
          <meshStandardMaterial color="#222c3d" emissive={j % 3 === 0 ? theme.status.warn : theme.status.ok} emissiveIntensity={1.4} toneMapped={false} />
        </mesh>
      ))}
    </group>
  )
}

function CeilingLamp({ x, z, on }: { x: number; z: number; on: boolean }) {
  return (
    <mesh position={[x, OFFICE.wallH - 0.04, z]}>
      <boxGeometry args={[1.6, 0.05, 0.4]} />
      <meshStandardMaterial color="#ffffff" emissive={theme.light} emissiveIntensity={on ? 2.4 : 0} toneMapped={false} />
    </mesh>
  )
}

// ───────────────────────── 벽 (시야를 가리면 투명화) ─────────────────────────

function wallMaterial(accent: boolean) {
  const m = std(accent ? theme.accentWall : theme.wall, 0.85)
  // 포인트 벽은 어두운 색이라 조명을 덜 받으므로 약한 자체 발광으로 네이비 톤을 유지
  if (accent) { m.emissive = new THREE.Color(theme.accentWall); m.emissiveIntensity = 0.45 }
  return m
}

const ACCENT_WALLS = new Set([0, 7, 8, 9])
const FADED = 0.1

function Walls() {
  const logo = useTexture('/itcen-logo.png')
  logo.colorSpace = THREE.SRGBColorSpace
  const lightOn = useOffice((s) => s.lightOn)

  const set = useMemo(
    () =>
      WALLS.map((w, i) => {
        if (w.glass) return null
        return {
          wall: wallMaterial(ACCENT_WALLS.has(i)),
          skirt: std(ACCENT_WALLS.has(i) ? '#1b2638' : theme.skirting, 0.7),
          cove: new THREE.MeshStandardMaterial({ color: '#ffffff', emissive: theme.light, emissiveIntensity: 0, toneMapped: false }),
          opacity: 1,
        }
      }),
    [],
  )
  const logoMat = useMemo(() => new THREE.MeshBasicMaterial({ map: logo, transparent: true, toneMapped: false }), [logo])

  const apply = (m: THREE.Material, o: number, alpha = false) => {
    const t = alpha || o < 0.99
    if (m.transparent !== t || m.depthWrite === t) {
      m.transparent = t
      m.depthWrite = !t
      m.needsUpdate = true
    }
    m.opacity = o
  }

  useFrame((state, dt) => {
    const cam = state.camera.position
    const f = cameraRig.focus
    const k = 1 - Math.exp(-10 * dt)
    WALLS.forEach((w, i) => {
      const s = set[i]
      if (!s) return
      const hit = raySpan(cam.x, cam.y, cam.z, f.x, f.y, f.z, w, w.h + 0.2) !== null
      s.opacity += ((hit ? FADED : 1) - s.opacity) * k
      apply(s.wall, s.opacity)
      apply(s.skirt, s.opacity)
      apply(s.cove, s.opacity)
      s.cove.emissiveIntensity = lightOn ? theme.lighting.on.cove : 0
      if (i === 0) apply(logoMat, s.opacity, true)
    })
  })

  return (
    <group>
      {WALLS.map((w, i) => {
        const s = set[i]
        if (w.glass || !s) {
          // 유리벽: 얇은 프레임 + 투명 유리
          const alongX = w.w > w.d
          return (
            <group key={i} position={[w.x, 0, w.z]}>
              <mesh position={[0, w.h / 2, 0]} material={M.glass}>
                <boxGeometry args={[alongX ? w.w : 0.04, w.h, alongX ? 0.04 : w.d]} />
              </mesh>
              <Bx p={[0, 0.05, 0]} s={[alongX ? w.w : 0.1, 0.1, alongX ? 0.1 : w.d]} m={M.frame} />
              <Bx p={[0, w.h - 0.05, 0]} s={[alongX ? w.w : 0.1, 0.1, alongX ? 0.1 : w.d]} m={M.frame} />
              <Bx p={[0, 1, 0]} s={[alongX ? w.w : 0.06, 0.05, alongX ? 0.06 : w.d]} m={M.frame} shadow={false} />
              {[-0.5, 0.5].map((e) => (
                <Bx key={e} p={[alongX ? e * w.w : 0, w.h / 2, alongX ? 0 : e * w.d]} s={[0.1, w.h, 0.1]} m={M.frame} />
              ))}
            </group>
          )
        }
        return (
          <group key={i} position={[w.x, 0, w.z]}>
            <mesh position={[0, w.h / 2, 0]} material={s.wall} castShadow receiveShadow>
              <boxGeometry args={[w.w, w.h, w.d]} />
            </mesh>
            {/* 걸레받이 */}
            <mesh position={[0, 0.07, 0]} material={s.skirt}>
              <boxGeometry args={[w.w + (w.w > w.d ? 0 : 0.04), 0.14, w.d + (w.w > w.d ? 0.04 : 0)]} />
            </mesh>
            {/* 간접조명(코브) 라인 */}
            <mesh position={[0, w.h - 0.14, 0]} material={s.cove}>
              <boxGeometry args={[w.w + (w.w > w.d ? 0 : 0.03), 0.05, w.d + (w.w > w.d ? 0.03 : 0)]} />
            </mesh>
          </group>
        )
      })}
      {/* 포인트 벽 위 로고 */}
      <mesh position={[-7.5, 1.85, -7.83]} material={logoMat}>
        <planeGeometry args={[3.4, 3.4 * (477 / 2250)]} />
      </mesh>
    </group>
  )
}

// ───────────────────────── 사무실 ─────────────────────────

export function Office() {
  const lightOn = useOffice((s) => s.lightOn)
  const acOn = useOffice((s) => s.devices['ac-main']?.state.power ?? false)
  const L = lightOn ? theme.lighting.on : theme.lighting.off
  const tex = useMemo(floorTexture, [])
  const lamps: [number, number][] = [[-8, -2], [-8, 3], [-1, -2], [-1, 4], [8, -4.5], [8, 5.2]]
  const pointLights: [number, number][] = [[-8, -2], [-8, 3], [-1, 1], [8, -4.5], [8, 5.2]]

  return (
    <group>
      <Environment resolution={256} frames={1} environmentIntensity={L.env} background={false}>
        <Lightformer form="rect" intensity={2.5} color="#ffffff" position={[0, 8, 0]} rotation-x={Math.PI / 2} scale={[20, 12, 1]} />
        <Lightformer form="rect" intensity={1.2} color={theme.light} position={[-10, 4, 0]} rotation-y={Math.PI / 2} scale={[12, 4, 1]} />
        <Lightformer form="rect" intensity={1.2} color="#dbe8ff" position={[10, 4, 0]} rotation-y={-Math.PI / 2} scale={[12, 4, 1]} />
      </Environment>

      <ambientLight intensity={L.ambient} color={theme.light} />
      <hemisphereLight args={['#f2f6ff', '#9aa3b4', L.hemi]} />
      <directionalLight
        position={[8, 14, 6]}
        intensity={L.sun}
        color={lightOn ? '#fff6e6' : '#7e96e0'}
        castShadow
        shadow-mapSize={[2048, 2048]}
        shadow-camera-left={-16}
        shadow-camera-right={16}
        shadow-camera-top={12}
        shadow-camera-bottom={-12}
        shadow-bias={-0.0004}
        shadow-radius={5}
      />
      {pointLights.map(([x, z], i) => (
        <pointLight key={i} position={[x, 2.6, z]} intensity={L.point} distance={12} decay={2} color={theme.light} />
      ))}
      {lamps.map(([x, z], i) => <CeilingLamp key={i} x={x} z={z} on={lightOn} />)}

      {/* 바닥 */}
      <mesh rotation={[-Math.PI / 2, 0, 0]} receiveShadow>
        <planeGeometry args={[24, 16]} />
        <meshStandardMaterial color={theme.floor} map={tex} roughness={0.38} metalness={0.05} envMapIntensity={0.9} />
      </mesh>
      <mesh rotation={[-Math.PI / 2, 0, 0]} position={[8, 0.01, 5]} receiveShadow>
        <planeGeometry args={[7.7, 5.9]} />
        <meshStandardMaterial color={theme.serverFloor} roughness={0.55} />
      </mesh>
      <mesh rotation={[-Math.PI / 2, 0, 0]} position={[8, 0.012, -4.5]} receiveShadow>
        <planeGeometry args={[5.4, 3.4]} />
        <primitive object={M.rug} attach="material" />
      </mesh>
      {/* 건물 바깥 지면 */}
      <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, -0.03, 0]} receiveShadow>
        <planeGeometry args={[120, 120]} />
        <meshStandardMaterial color={lightOn ? theme.ground.on : theme.ground.off} roughness={1} />
      </mesh>

      <Walls />

      {/* 출입구 */}
      <group position={[-9.5, 0, 7.8]}>
        <Bx p={[0, 1.1, 0]} s={[1.6, 2.2, 0.06]} m={M.glass} shadow={false} />
        <Bx p={[0, 2.25, 0]} s={[1.8, 0.1, 0.2]} m={M.frame} />
        <Bx p={[-0.85, 1.1, 0]} s={[0.08, 2.2, 0.2]} m={M.frame} />
        <Bx p={[0.85, 1.1, 0]} s={[0.08, 2.2, 0.2]} m={M.frame} />
        <Bx p={[0.55, 1.05, -0.06]} s={[0.03, 0.5, 0.03]} m={M.white} />
        <Html position={[0, 2.6, 0]} center distanceFactor={9} zIndexRange={[10, 0]}>
          <div className="px-2 py-0.5 rounded bg-[#253247]/85 text-[11px] text-slate-100 whitespace-nowrap select-none pointer-events-none">출입구</div>
        </Html>
      </group>

      {/* 개인 책상 4 */}
      {DESKS.map((d) => (
        <group key={d.id}>
          <Desk x={d.x} z={d.z} />
          <Monitor x={d.x - 0.2} z={d.z - 0.2} />
          <Keyboard x={d.x - 0.2} z={d.z + 0.15} />
          <Bx p={[d.x + 0.35, 0.81, d.z + 0.15]} s={[0.06, 0.025, 0.1]} m={M.dark} />
          <Mug x={d.x + 0.8} z={d.z - 0.1} />
          <Bx p={[d.x - 0.85, 0.815, d.z + 0.05]} s={[0.22, 0.02, 0.3]} m={M.paper} ry={0.2} />
          <Chair x={d.x} z={d.z + 1.0} ry={Math.PI} />
        </group>
      ))}

      {/* AI 직원 책상 */}
      <Desk x={AI_DESK.x} z={AI_DESK.z} w={2.6} d={1.1} />
      <Monitor x={AI_DESK.x - 0.65} z={AI_DESK.z - 0.2} />
      <Monitor x={AI_DESK.x + 0.65} z={AI_DESK.z - 0.2} />
      <Keyboard x={AI_DESK.x} z={AI_DESK.z + 0.18} />
      <Chair x={AI_POS.x} z={AI_POS.z - 0.05} ry={0} />

      {/* 회의실 */}
      <Desk x={MEETING_TABLE.x} z={MEETING_TABLE.z} w={3.4} d={1.4} />
      {[-1.2, 0, 1.2].flatMap((dx) => [
        <Chair key={`a${dx}`} x={MEETING_TABLE.x + dx} z={MEETING_TABLE.z - 1.2} ry={0} />,
        <Chair key={`b${dx}`} x={MEETING_TABLE.x + dx} z={MEETING_TABLE.z + 1.2} ry={Math.PI} />,
      ])}
      <Bx p={[MEETING_TABLE.x, 0.82, MEETING_TABLE.z]} s={[0.4, 0.04, 0.4]} m={M.dark} />
      <Html position={[8, 2.4, -7.7]} center distanceFactor={9} zIndexRange={[10, 0]}>
        <div className="px-2 py-0.5 rounded bg-[#253247]/85 text-[11px] text-slate-100 whitespace-nowrap select-none pointer-events-none">회의실 A</div>
      </Html>

      {/* 서버실 */}
      {RACKS.map((r, i) => <Rack key={i} x={r.x} z={r.z} />)}
      <pointLight position={[8, 2.2, 6]} intensity={4} distance={6} color="#8fb8ff" />
      <Html position={[8, 2.4, 2.3]} center distanceFactor={9} zIndexRange={[10, 0]}>
        <div className="px-2 py-0.5 rounded bg-[#253247]/85 text-[11px] text-slate-100 whitespace-nowrap select-none pointer-events-none">서버실</div>
      </Html>

      {/* 수납장, 소파, 화분 */}
      <AoBlob x={-1} z={7.5} w={3.8} d={1.5} />
      <Bx p={[-1, 0.9, 7.5]} s={[3, 1.8, 0.7]} m={M.cabinet} />
      {[-1.9, -1.3, -0.7, -0.1, 0.5].map((dx, i) => (
        <Bx key={i} p={[-1 + 0.1 + dx * 0.9, 0.9, 7.14]} s={[0.01, 1.7, 0.01]} m={M.frame} shadow={false} />
      ))}
      <AoBlob x={11.5} z={0.5} w={1.6} d={2.4} />
      <Bx p={[11.5, 0.9, 0.5]} s={[0.8, 1.8, 1.6]} m={M.cabinet} />
      <group position={[-9, 0, 6.9]}>
        <AoBlob x={0} z={0} w={3.4} d={1.8} />
        <Bx p={[0, 0.27, 0]} s={[2.6, 0.5, 1]} m={M.sofa} />
        <Bx p={[0, 0.7, 0.4]} s={[2.6, 0.5, 0.2]} m={M.sofa} />
        <Bx p={[-1.35, 0.4, 0]} s={[0.15, 0.6, 1]} m={M.sofa} />
        <Bx p={[1.35, 0.4, 0]} s={[0.15, 0.6, 1]} m={M.sofa} />
      </group>
      <Plant x={-11.2} z={-7.2} />
      <Plant x={3.2} z={7.2} />
      <Plant x={3.2} z={-7.2} />

      {/* 에어컨 */}
      <group position={[AC_POS.x, 2.45, AC_POS.z]}>
        <Bx p={[0, 0, 0]} s={[1.6, 0.4, 0.3]} m={M.white} />
        <Bx p={[0, -0.14, 0.14]} s={[1.3, 0.04, 0.04]} m={M.frame} shadow={false} />
        <mesh position={[0.6, 0.06, 0.155]}>
          <circleGeometry args={[0.02, 12]} />
          <meshBasicMaterial color={acOn ? theme.status.ok : theme.status.idle} toneMapped={false} />
        </mesh>
      </group>

      <LightSwitch />
      <HubTerminal />
    </group>
  )
}

/** AI LAB 터미널: 클릭/E 로 WORKSPACE 의 AI HUB 를 연다 */
function HubTerminal() {
  const open = useOffice((s) => s.open)
  const near = useOffice((s) => s.nearby?.id === 'hub')
  return (
    <group
      position={[HUB_POS.x, 0, HUB_POS.z]}
      onClick={(e) => { e.stopPropagation(); open('hub') }}
      onPointerOver={() => (document.body.style.cursor = 'pointer')}
      onPointerOut={() => (document.body.style.cursor = '')}
    >
      <Bx p={[0, 0.5, 0]} s={[0.9, 1.0, 0.4]} m={M.frame} />
      <mesh position={[0, 1.22, 0.04]} rotation={[-0.25, 0, 0]} castShadow>
        <boxGeometry args={[0.92, 0.6, 0.06]} />
        <meshStandardMaterial color="#10192b" roughness={0.4} />
      </mesh>
      <mesh position={[0, 1.22, 0.075]} rotation={[-0.25, 0, 0]}>
        <planeGeometry args={[0.82, 0.5]} />
        <meshStandardMaterial color="#241a52" emissive={near ? '#a78bfa' : '#7c5cff'} emissiveIntensity={near ? 1.4 : 0.8} toneMapped={false} />
      </mesh>
      <Html position={[0, 1.9, 0.1]} center distanceFactor={9} zIndexRange={[10, 0]}>
        <div className="px-2 py-0.5 rounded bg-[#4c3a9e]/90 text-[11px] text-white whitespace-nowrap select-none pointer-events-none">🧩 AI LAB · AI HUB</div>
      </Html>
    </group>
  )
}

function LightSwitch() {
  const lightOn = useOffice((s) => s.lightOn)
  const open = useOffice((s) => s.open)
  const nearby = useOffice((s) => s.nearby)
  const ref = useRef<THREE.Mesh>(null)
  useFrame((_, dt) => {
    if (ref.current) ref.current.rotation.z = THREE.MathUtils.lerp(ref.current.rotation.z, lightOn ? 0.35 : -0.35, Math.min(1, dt * 12))
  })
  return (
    <group position={[LIGHT_SWITCH.x + 0.07, 1.3, LIGHT_SWITCH.z]} rotation={[0, Math.PI / 2, 0]}>
      <mesh
        onClick={(e) => { e.stopPropagation(); open('light') }}
        onPointerOver={() => (document.body.style.cursor = 'pointer')}
        onPointerOut={() => (document.body.style.cursor = '')}
      >
        <boxGeometry args={[0.22, 0.34, 0.05]} />
        <meshStandardMaterial color="#f5f6f8" emissive={nearby?.id === 'light' ? theme.status.working : '#000'} emissiveIntensity={0.6} />
      </mesh>
      <mesh ref={ref} position={[0, 0, 0.04]}>
        <boxGeometry args={[0.07, 0.14, 0.04]} />
        <meshStandardMaterial color={lightOn ? theme.light : '#8a93a4'} emissive={lightOn ? theme.light : '#000'} emissiveIntensity={1} />
      </mesh>
    </group>
  )
}
