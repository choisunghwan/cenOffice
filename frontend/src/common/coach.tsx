import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { create } from 'zustand'
import { useOffice } from '../store'
import { TOURS, type CoachStep } from './tours'
import './coach.css'

const seenKey = (user: string, tour: string) => `cen-coach-seen:${user}:${tour}`
function wasSeen(user: string, tour: string): boolean { try { return localStorage.getItem(seenKey(user, tour)) === '1' } catch { return true /* 저장소를 못 쓰면 자꾸 뜨지 않게 본 것으로 친다 */ } }
function markSeen(user: string, tour: string) { try { localStorage.setItem(seenKey(user, tour), '1') } catch { /* 기억하지 못해도 이번에는 문제없다 */ } }

const useCoach = create<{ tour: string | null; start: (id: string) => void; stop: () => void }>((set) => ({
  tour: null,
  start: (tour) => set({ tour }),
  stop: () => set({ tour: null }),
}))

/** 안내를 처음부터 다시 보여 준다. 홈으로 이동한 뒤 시작한다(설정의 "사용법 다시 보기"). */
export function startTour(id = 'welcome', opt?: { stay?: boolean }) {
  if (opt?.stay) { useCoach.getState().start(id); return }
  const o = useOffice.getState()
  o.setMode('workspace'); o.setWorkspacePage('dashboard')
  window.dispatchEvent(new Event('hub-navigation'))
  window.setTimeout(() => useCoach.getState().start(id), 350)
}

/** 지금 화면에서, 이 계정이 처음 보는 안내일 때만 시작한다(화면이 자리 잡은 뒤에). */
export function startTourOnce(id: string) {
  const user = useOffice.getState().user
  if (!user || wasSeen(user.username, id)) return
  window.setTimeout(() => { if (!useCoach.getState().tour) useCoach.getState().start(id) }, 600)
}

/** 같은 이름이 둘(데스크톱 사이드바·모바일 탭)일 수 있어, 실제로 보이는 첫 요소를 고른다 */
function findTarget(name: string): HTMLElement | null {
  for (const el of Array.from(document.querySelectorAll<HTMLElement>(`[data-coach="${name}"]`))) {
    const r = el.getBoundingClientRect()
    if (el.getClientRects().length > 0 && r.width > 0 && r.height > 0) return el
  }
  return null
}

const PAD = 6, GAP = 14, MARGIN = 12

/** 말풍선 위치: 원하는 쪽에 들어가면 거기에, 아니면 반대쪽·다른 쪽 순으로 시도하고 화면 안으로 맞춘다 */
function place(rect: DOMRect, card: { w: number; h: number }, want: CoachStep['placement']) {
  const vw = window.innerWidth, vh = window.innerHeight
  const opposite = { top: 'bottom', bottom: 'top', left: 'right', right: 'left' } as const
  const order: CoachStep['placement'][] = [want, opposite[want], 'bottom', 'top', 'right', 'left']
  for (const side of order) {
    let left = 0, top = 0
    if (side === 'right') { left = rect.right + PAD + GAP; top = rect.top + rect.height / 2 - card.h / 2 }
    if (side === 'left') { left = rect.left - PAD - GAP - card.w; top = rect.top + rect.height / 2 - card.h / 2 }
    if (side === 'bottom') { top = rect.bottom + PAD + GAP; left = rect.left + rect.width / 2 - card.w / 2 }
    if (side === 'top') { top = rect.top - PAD - GAP - card.h; left = rect.left + rect.width / 2 - card.w / 2 }
    // 가로·세로 가운데 정렬로 어긋난 만큼은 화면 안으로 밀어 넣는다
    left = Math.min(Math.max(left, MARGIN), vw - MARGIN - card.w)
    top = Math.min(Math.max(top, MARGIN), vh - MARGIN - card.h)
    const overlaps = left < rect.right + PAD && left + card.w > rect.left - PAD && top < rect.bottom + PAD && top + card.h > rect.top - PAD
    if (!overlaps) return { left, top }
  }
  // 어느 쪽도 안 맞으면 화면 아래쪽 가운데에 둔다
  return { left: Math.max(MARGIN, (vw - card.w) / 2), top: Math.max(MARGIN, vh - card.h - 24) }
}

function Tour({ id, username }: { id: string; username: string }) {
  const steps = TOURS[id] ?? []
  const [i, setI] = useState(0)
  const [rect, setRect] = useState<DOMRect | null>(null)
  const [pos, setPos] = useState<{ left: number; top: number } | null>(null)
  const cardRef = useRef<HTMLDivElement>(null)
  const nextRef = useRef<HTMLButtonElement>(null)
  const stop = useCoach((s) => s.stop)

  const finish = useCallback(() => { markSeen(username, id); stop() }, [username, id, stop])

  /** from 부터 dir 방향으로 화면에 보이는 단계를 찾는다(없으면 -1) */
  const seek = useCallback((from: number, dir: 1 | -1) => {
    for (let k = from; k >= 0 && k < steps.length; k += dir) if (findTarget(steps[k].target)) return k
    return -1
  }, [steps])

  // 처음 보이는 단계로 시작한다. 보이는 단계가 하나도 없으면 조용히 끝낸다.
  useEffect(() => { const first = seek(0, 1); if (first < 0) finish(); else setI(first) }, [seek, finish])

  const measure = useCallback(() => {
    const step = steps[i]
    const el = step ? findTarget(step.target) : null
    if (!el) { setRect(null); return }
    let r = el.getBoundingClientRect()
    if (r.top < 0 || r.bottom > window.innerHeight) { el.scrollIntoView({ block: 'center' }); r = el.getBoundingClientRect() }
    setRect(r)
  }, [steps, i])

  useLayoutEffect(() => { measure() }, [measure])
  useEffect(() => {
    const on = () => measure()
    window.addEventListener('resize', on)
    window.addEventListener('scroll', on, true)
    const el = steps[i] ? findTarget(steps[i].target) : null
    const ro = el && typeof ResizeObserver !== 'undefined' ? new ResizeObserver(on) : null
    if (el && ro) ro.observe(el)
    return () => { window.removeEventListener('resize', on); window.removeEventListener('scroll', on, true); ro?.disconnect() }
  }, [measure, steps, i])

  useLayoutEffect(() => {
    if (!rect || !cardRef.current) return
    const c = cardRef.current.getBoundingClientRect()
    setPos(place(rect, { w: c.width, h: c.height }, steps[i].placement))
  }, [rect, i, steps])

  useEffect(() => { nextRef.current?.focus() }, [i])

  const go = (dir: 1 | -1) => {
    const k = seek(i + dir, dir)
    if (k < 0) { if (dir === 1) finish(); return }
    setPos(null); setI(k)
  }

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); finish() }
      else if (e.key === 'ArrowRight') { e.preventDefault(); go(1) }
      else if (e.key === 'ArrowLeft') { e.preventDefault(); go(-1) }
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  })

  const step = steps[i]
  if (!step || !rect) return null
  const visibleIdx = steps.map((s, k) => (findTarget(s.target) ? k : -1)).filter((k) => k >= 0)
  const order = visibleIdx.indexOf(i), total = visibleIdx.length
  const last = seek(i + 1, 1) < 0
  const first = seek(i - 1, -1) < 0

  return (
    <div className="coach-layer" role="dialog" aria-modal="true" aria-labelledby="coach-title" aria-describedby="coach-body">
      <div className="coach-block" />
      <div className="coach-hole" style={{ left: rect.left - PAD, top: rect.top - PAD, width: rect.width + PAD * 2, height: rect.height + PAD * 2 }} />
      <div ref={cardRef} className="coach-card" style={pos ? { left: pos.left, top: pos.top } : { left: -9999, top: 0 }}>
        <p className="coach-step">{order + 1} / {total}</p>
        <h2 id="coach-title">{step.title}</h2>
        <p id="coach-body">{step.body}</p>
        <div className="coach-actions">
          <button type="button" className="coach-skip" onClick={finish}>건너뛰기</button>
          <span className="coach-spacer" />
          {!first && <button type="button" className="coach-btn" onClick={() => go(-1)}>이전</button>}
          <button type="button" ref={nextRef} className="coach-btn primary" onClick={() => go(1)}>{last ? '시작하기' : '다음'}</button>
        </div>
      </div>
    </div>
  )
}

/** 앱 맨 위에 한 번만 둔다. 업무 화면의 홈에서 처음 로그인한 계정에게 전체 안내를 자동으로 보여 준다. */
export function CoachHost() {
  const user = useOffice((s) => s.user)
  const mode = useOffice((s) => s.mode)
  const page = useOffice((s) => s.workspacePage)
  const tour = useCoach((s) => s.tour)

  useEffect(() => {
    if (!user || mode !== 'workspace' || page !== 'dashboard' || tour || wasSeen(user.username, 'welcome')) return
    const t = window.setTimeout(() => useCoach.getState().start('welcome'), 900) // 화면이 자리 잡은 뒤에 시작한다
    return () => window.clearTimeout(t)
  }, [user, mode, page, tour])

  // 로그아웃하거나 3D 화면으로 바꾸면 안내를 닫는다
  useEffect(() => { if (tour && (!user || mode !== 'workspace')) useCoach.getState().stop() }, [tour, user, mode])

  if (!tour || !user) return null
  return <Tour key={tour} id={tour} username={user.username} />
}
