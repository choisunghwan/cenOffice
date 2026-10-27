import { useEffect, useState, type RefObject } from 'react'

export interface RailItem { id: number; question: string; answer: string }

/** 대화 목차: 질문마다 눈금 하나. 마우스를 올리면 질문과 답 미리보기가 뜨고, 누르면 그 위치로 이동한다. 지금 보고 있는 위치는 긴 눈금으로 보인다. */
export function TurnRail({ items, scrollRef }: { items: RailItem[]; scrollRef: RefObject<HTMLDivElement | null> }) {
  const [active, setActive] = useState<number | null>(null)
  const [hover, setHover] = useState<number | null>(null)

  // 스크롤 위치에서 "지금 읽고 있는 질문"을 구한다: 화면 위쪽 근처에 닿은 마지막 질문
  useEffect(() => {
    const box = scrollRef.current
    if (!box) return
    const update = () => {
      const top = box.getBoundingClientRect().top + 90
      let cur: number | null = null
      for (const it of items) {
        const el = document.getElementById(`ha-t-${it.id}`)
        if (el && el.getBoundingClientRect().top <= top) cur = it.id
      }
      setActive(cur ?? items[0]?.id ?? null)
    }
    update()
    box.addEventListener('scroll', update, { passive: true })
    return () => box.removeEventListener('scroll', update)
  }, [items, scrollRef])

  if (items.length < 2) return null
  const go = (id: number) => {
    const box = scrollRef.current
    const el = document.getElementById(`ha-t-${id}`)
    if (!box || !el) return
    box.scrollTo({ top: el.getBoundingClientRect().top - box.getBoundingClientRect().top + box.scrollTop - 16, behavior: 'smooth' })
  }
  const shown = items.find((i) => i.id === hover)

  return (
    <nav className="ha-rail" aria-label="대화 목차" onMouseLeave={() => setHover(null)}>
      {shown && (
        <div className="ha-rail-pop" role="tooltip">
          <b>{shown.question}</b>
          {shown.answer && <span>{shown.answer}</span>}
        </div>
      )}
      {items.map((it) => (
        <button key={it.id} type="button" className={`ha-tick${it.id === active ? ' on' : ''}`} aria-label={`질문으로 이동: ${it.question}`} aria-current={it.id === active ? 'true' : undefined}
          onMouseEnter={() => setHover(it.id)} onFocus={() => setHover(it.id)} onBlur={() => setHover(null)} onClick={() => go(it.id)}><i /></button>
      ))}
    </nav>
  )
}
