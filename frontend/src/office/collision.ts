import type { Box } from './layout'

/** 원(반지름 r) vs AABB 밀어내기 */
export function resolve(pos: { x: number; z: number }, r: number, boxes: Box[]) {
  for (let iter = 0; iter < 3; iter++) {
    for (const b of boxes) {
      const hx = b.w / 2
      const hz = b.d / 2
      const cx = Math.max(b.x - hx, Math.min(pos.x, b.x + hx))
      const cz = Math.max(b.z - hz, Math.min(pos.z, b.z + hz))
      let dx = pos.x - cx
      let dz = pos.z - cz
      const d2 = dx * dx + dz * dz
      if (d2 >= r * r) continue
      if (d2 > 1e-8) {
        const d = Math.sqrt(d2)
        pos.x += (dx / d) * (r - d)
        pos.z += (dz / d) * (r - d)
      } else {
        // 박스 내부: 가장 가까운 면으로 탈출
        dx = pos.x - b.x
        dz = pos.z - b.z
        if (hx - Math.abs(dx) < hz - Math.abs(dz)) pos.x = b.x + Math.sign(dx || 1) * (hx + r)
        else pos.z = b.z + Math.sign(dz || 1) * (hz + r)
      }
    }
  }
}

/** 선분(p→q)이 박스(높이 height)와 만나는 구간 [t0,t1] (0~1). 없으면 null */
export function raySpan(
  px: number, py: number, pz: number,
  qx: number, qy: number, qz: number,
  b: Box, height = b.h,
): [number, number] | null {
  const d = [qx - px, qy - py, qz - pz]
  const o = [px, py, pz]
  const min = [b.x - b.w / 2, 0, b.z - b.d / 2]
  const max = [b.x + b.w / 2, height, b.z + b.d / 2]
  let t0 = 0
  let t1 = 1
  for (let i = 0; i < 3; i++) {
    if (Math.abs(d[i]) < 1e-8) {
      if (o[i] < min[i] || o[i] > max[i]) return null
    } else {
      let a = (min[i] - o[i]) / d[i]
      let c = (max[i] - o[i]) / d[i]
      if (a > c) [a, c] = [c, a]
      t0 = Math.max(t0, a)
      t1 = Math.min(t1, c)
      if (t0 > t1) return null
    }
  }
  return [t0, t1]
}
