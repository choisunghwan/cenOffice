import { Canvas } from '@react-three/fiber'
import { Office } from './Office'
import { Player } from './Player'
import { AiStaff } from './AiStaff'
import { RemotePlayers } from './RemotePlayers'
import { useOffice } from '../store'
import { theme } from '../theme'

export function Scene({ active = true }: { active?: boolean }) {
  const lightOn = useOffice((s) => s.lightOn)
  const sky = lightOn ? theme.sky.on : theme.sky.off
  return (
    <Canvas frameloop={active ? 'always' : 'never'} shadows dpr={[1, 1.75]} camera={{ fov: 55, near: 0.1, far: 120, position: [-7, 4, 11] }} gl={{ antialias: true }}>
      <color attach="background" args={[sky]} />
      <fog attach="fog" args={[sky, 35, 80]} />
      <Office />
      <AiStaff />
      <Player />
      <RemotePlayers />
    </Canvas>
  )
}
