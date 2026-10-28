import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { calendarApi, type Entry, type Feed, type Scope } from './api'
import { addDays, dowLabel, formatRange, isAllDayLike, KIND_LABEL, overlapsDay, pad, sameDay, startOfDay, startOfMonth, startOfWeek } from './dates'
import { EventEditor, type EditorInit } from './EventEditor'
import './calendar.css'

const HOUR = 48 // 주 보기에서 한 시간의 높이(px)
const SCOPE_KEY = 'cen.cal.scope'

const timeOf = (e: Entry) => { const s = new Date(e.startAt); return `${pad(s.getHours())}:${pad(s.getMinutes())}` }
const isWide = isAllDayLike
const chipClass = (e: Entry) => `cal-ev k-${e.kind === 'EVENT' ? (e.scope === 'SHARED' ? 'shared' : 'personal') : e.kind.toLowerCase()}`

function readScope(): Scope { try { return localStorage.getItem(SCOPE_KEY) === 'shared' ? 'shared' : 'mine' } catch { return 'mine' } }

export function CalendarPage() {
  const [scope, setScopeState] = useState<Scope>(readScope)
  const [view, setView] = useState<'month' | 'week'>('month')
  const [cursor, setCursor] = useState(() => startOfDay(new Date()))
  const [selected, setSelected] = useState(() => startOfDay(new Date()))
  const [feed, setFeed] = useState<Feed | null>(null)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [detail, setDetail] = useState<Entry | null>(null)
  const [editor, setEditor] = useState<EditorInit | null>(null)
  const seq = useRef(0)
  const weekScroll = useRef<HTMLDivElement>(null)
  const today = startOfDay(new Date())

  const range = useMemo(() => view === 'month' ? { from: startOfWeek(startOfMonth(cursor)), days: 42 } : { from: startOfWeek(cursor), days: 7 }, [view, cursor])
  const days = useMemo(() => Array.from({ length: range.days }, (_, i) => addDays(range.from, i)), [range])

  const setScope = (s: Scope) => {
    if (s === scope) return
    setFeed(null); setScopeState(s)
    try { localStorage.setItem(SCOPE_KEY, s) } catch { /* 저장이 막힌 브라우저는 이번 접속에서만 유지 */ }
  }

  const load = useCallback(async () => {
    const n = ++seq.current
    try {
      const f = await calendarApi.feed(scope, range.from, addDays(range.from, range.days))
      if (n === seq.current) { setFeed(f); setError('') }
    } catch (e) { if (n === seq.current) setError((e as Error).message) }
  }, [scope, range])

  useEffect(() => {
    void load()
    const t = setInterval(() => void load(), 60_000)
    const onNote = () => void load() // 일정 초대 알림이 오면 바로 반영
    window.addEventListener('cen-notification', onNote)
    return () => { clearInterval(t); window.removeEventListener('cen-notification', onNote) }
  }, [load])

  useEffect(() => { if (view === 'week' && weekScroll.current) weekScroll.current.scrollTop = 7 * HOUR }, [view])
  useEffect(() => { if (!notice) return; const t = setTimeout(() => setNotice(''), 4000); return () => clearTimeout(t) }, [notice])

  const entries = useMemo(() => feed?.entries ?? [], [feed])
  const onDay = useCallback((d: Date) => entries.filter((e) => overlapsDay(e, d)).sort((a, b) => Number(isWide(b)) - Number(isWide(a)) || a.startAt.localeCompare(b.startAt)), [entries])
  const canManage = feed?.canManageShared ?? false
  const canCreate = scope === 'mine' || canManage

  const move = (dir: -1 | 0 | 1) => {
    if (dir === 0) { setCursor(today); setSelected(today); return }
    const next = view === 'month' ? new Date(cursor.getFullYear(), cursor.getMonth() + dir, 1) : addDays(cursor, 7 * dir)
    setCursor(next); setSelected(next)
  }
  const openNew = (day: Date) => { if (canCreate) setEditor({ day, scope }) }
  const saved = (message: string) => { setEditor(null); setDetail(null); setNotice(message); void load() }
  const title = view === 'month' ? `${cursor.getFullYear()}년 ${cursor.getMonth() + 1}월`
    : `${range.from.getFullYear()}년 ${range.from.getMonth() + 1}월 ${range.from.getDate()}일 – ${addDays(range.from, 6).getMonth() + 1}월 ${addDays(range.from, 6).getDate()}일`

  const chip = (e: Entry, withTime = true) => (
    <button key={e.id} type="button" className={chipClass(e)} title={`${e.title} · ${formatRange(e)}`} onClick={(ev) => { ev.stopPropagation(); setDetail(e) }}>
      {withTime && !isWide(e) && <i>{timeOf(e)}</i>}{e.kind === 'TASK' ? '☑ ' : e.kind === 'RESERVATION' ? '▣ ' : ''}{e.title}
    </button>
  )

  return (
    <div className="calendar-page">
      <header className="cal-head">
        <div>
          <p className="cal-eyebrow">CALENDAR</p>
          <h1>{title}</h1>
        </div>
        <div className="cal-tools">
          <div className="cal-seg" role="radiogroup" aria-label="일정 범위">
            <button type="button" role="radio" aria-checked={scope === 'shared'} onClick={() => setScope('shared')}>전체 일정</button>
            <button type="button" role="radio" aria-checked={scope === 'mine'} onClick={() => setScope('mine')}>개인 일정</button>
          </div>
          <div className="cal-seg" role="radiogroup" aria-label="보기">
            <button type="button" role="radio" aria-checked={view === 'month'} onClick={() => setView('month')}>월</button>
            <button type="button" role="radio" aria-checked={view === 'week'} onClick={() => setView('week')}>주</button>
          </div>
          <div className="cal-nav">
            <button type="button" aria-label="이전" onClick={() => move(-1)}>‹</button>
            <button type="button" onClick={() => move(0)}>오늘</button>
            <button type="button" aria-label="다음" onClick={() => move(1)}>›</button>
          </div>
          {/* 권한이 없어도 버튼은 같은 자리에 두고 비활성화한다(토글 전환 때 도구 줄이 밀리지 않게) */}
          <button type="button" className="cal-primary" disabled={!canCreate} title={canCreate ? undefined : '전체 일정은 관리자와 일정 관리자만 만들 수 있습니다'} onClick={() => openNew(selected)}>＋ 일정 만들기</button>
        </div>
      </header>

      {/* 알림·오류는 화면 구석에 띄운다. 본문 안에 끼워 넣으면 나타났다 사라질 때 달력이 위아래로 흔들린다 */}
      {(notice || error) && <div className="cal-toasts" aria-live="polite">
        {notice && <p className="cal-toast" role="status">{notice}</p>}
        {error && <p className="cal-toast err" role="alert">{error}</p>}
      </div>}
      <p className="cal-scope-note">{scope === 'mine'
        ? '내가 만들었거나 초대받은 일정, 내 회의실 예약, 기한이 날짜인 내 할 일이 보입니다.'
        : canManage ? '회사 공통 일정입니다. 관리자와 일정 관리자만 만들고 고칠 수 있습니다. (지금 계정은 가능)' : '회사 공통 일정입니다. 모든 사용자에게 보이고, 등록은 관리자와 일정 관리자가 합니다.'}</p>

      <div className="cal-layout">
        <section className="cal-board" aria-label="달력" aria-busy={feed === null}>
          {view === 'month' ? (
            <div className="cal-month">
              {[0, 1, 2, 3, 4, 5, 6].map((i) => <div key={i} className={`cal-dow ${i === 0 ? 'sun' : i === 6 ? 'sat' : ''}`}>{dowLabel(i)}</div>)}
              {days.map((d) => {
                const list = onDay(d)
                return (
                  <div key={d.toISOString()} role="button" tabIndex={0} aria-label={`${d.getMonth() + 1}월 ${d.getDate()}일 일정 ${list.length}건`}
                    className={`cal-cell ${d.getMonth() !== cursor.getMonth() ? 'out' : ''} ${sameDay(d, today) ? 'today' : ''} ${sameDay(d, selected) ? 'sel' : ''}`}
                    onClick={() => setSelected(d)} onDoubleClick={() => openNew(d)}
                    onKeyDown={(ev) => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); setSelected(d) } }}>
                    <span className={`cal-num ${d.getDay() === 0 ? 'sun' : d.getDay() === 6 ? 'sat' : ''}`}>{d.getDate()}</span>
                    {list.slice(0, 3).map((e) => chip(e))}
                    {list.length > 3 && <span className="cal-more">+{list.length - 3}개 더</span>}
                  </div>
                )
              })}
            </div>
          ) : (
            <div className="cal-week">
              <div className="cal-week-head">
                <span />
                {days.map((d) => (
                  <button type="button" key={d.toISOString()} className={`${sameDay(d, today) ? 'today' : ''} ${sameDay(d, selected) ? 'sel' : ''}`} onClick={() => setSelected(d)} onDoubleClick={() => openNew(d)}>
                    <small className={d.getDay() === 0 ? 'sun' : d.getDay() === 6 ? 'sat' : ''}>{dowLabel(d.getDay())}</small><b>{d.getDate()}</b>
                  </button>
                ))}
              </div>
              <div className="cal-week-wide">
                <span>종일</span>
                {days.map((d) => <div key={d.toISOString()}>{onDay(d).filter(isWide).map((e) => chip(e, false))}</div>)}
              </div>
              <div className="cal-week-scroll" ref={weekScroll}>
                <div className="cal-week-body" style={{ height: 24 * HOUR }}>
                  <div className="cal-hours">{Array.from({ length: 24 }, (_, h) => <span key={h} style={{ top: h * HOUR }}>{h === 0 ? '' : `${pad(h)}:00`}</span>)}</div>
                  {days.map((d) => <DayColumn key={d.toISOString()} day={d} entries={onDay(d).filter((e) => !isWide(e))} now={sameDay(d, today)} onPick={setDetail} onNew={() => openNew(d)} />)}
                </div>
              </div>
            </div>
          )}
        </section>

        <aside className="cal-side" aria-label="선택한 날 일정">
          <h2>{selected.getMonth() + 1}월 {selected.getDate()}일 ({dowLabel(selected.getDay())}){sameDay(selected, today) && <em>오늘</em>}</h2>
          {onDay(selected).length === 0 && <p className="cal-muted">{feed === null ? '불러오는 중…' : '이 날은 일정이 없습니다.'}</p>}
          <ul>
            {onDay(selected).map((e) => (
              <li key={e.id}><button type="button" className={chipClass(e)} onClick={() => setDetail(e)}>
                <i>{isWide(e) ? '종일' : timeOf(e)}</i><span>{e.title}</span><small>{KIND_LABEL[e.kind]}{e.location ? ` · ${e.location}` : ''}</small>
              </button></li>
            ))}
          </ul>
          <p className="cal-muted cal-side-hint">{canCreate ? ' ' : '전체 일정은 관리자와 일정 관리자만 만들 수 있습니다.'}</p>
        </aside>
      </div>

      {detail && <DetailDialog entry={detail} onClose={() => setDetail(null)} onEdit={() => { setEditor({ entry: detail, day: new Date(detail.startAt), scope }); setDetail(null) }} />}
      {editor && <EventEditor init={editor} canManageShared={canManage} onClose={() => setEditor(null)} onSaved={saved} />}
    </div>
  )
}

/** 주 보기의 하루 칸. 겹치는 일정은 가로로 나눠 배치한다. */
function DayColumn({ day, entries, now, onPick, onNew }: { day: Date; entries: Entry[]; now: boolean; onPick: (e: Entry) => void; onNew: () => void }) {
  const dayStart = startOfDay(day).getTime(), dayEnd = addDays(day, 1).getTime()
  const placed = useMemo(() => {
    const items = entries.map((e) => {
      const s = Math.max(new Date(e.startAt).getTime(), dayStart), en = Math.min(new Date(e.endAt).getTime(), dayEnd)
      return { e, s, en }
    }).sort((a, b) => a.s - b.s)
    const laneEnd: number[] = []
    const withLane = items.map((it) => {
      let lane = laneEnd.findIndex((end) => end <= it.s)
      if (lane < 0) { lane = laneEnd.length; laneEnd.push(it.en) } else laneEnd[lane] = it.en
      return { ...it, lane }
    })
    return { withLane, lanes: Math.max(1, laneEnd.length) }
  }, [entries, dayStart, dayEnd])
  const [tick, setTick] = useState(() => Date.now())
  useEffect(() => { if (!now) return; const t = setInterval(() => setTick(Date.now()), 60_000); return () => clearInterval(t) }, [now])
  return (
    <div className="cal-daycol" onDoubleClick={onNew}>
      {now && <div className="cal-nowline" style={{ top: ((tick - dayStart) / 3600_000) * HOUR }} />}
      {placed.withLane.map(({ e, s, en, lane }) => (
        <button type="button" key={e.id} className={chipClass(e)} onClick={(ev) => { ev.stopPropagation(); onPick(e) }} title={`${e.title} · ${formatRange(e)}`}
          style={{ top: ((s - dayStart) / 3600_000) * HOUR, height: Math.max(((en - s) / 3600_000) * HOUR - 2, 20), left: `${(lane / placed.lanes) * 100}%`, width: `${100 / placed.lanes}%` }}>
          <i>{timeOf(e)}</i> {e.title}
        </button>
      ))}
    </div>
  )
}

function DetailDialog({ entry, onClose, onEdit }: { entry: Entry; onClose: () => void; onEdit: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="cal-modal" role="dialog" aria-modal="true" aria-label="일정 상세" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <div className="cal-dialog">
        <p className="cal-kind"><span className={chipClass(entry)}>{entry.kind === 'EVENT' ? (entry.scope === 'SHARED' ? '전체 일정' : '개인 일정') : KIND_LABEL[entry.kind]}</span></p>
        <h2>{entry.title}</h2>
        <dl className="cal-detail">
          <dt>시간</dt><dd>{formatRange(entry)}</dd>
          {entry.location && <><dt>장소</dt><dd>{entry.location}</dd></>}
          {entry.kind === 'EVENT' && entry.createdByName && <><dt>만든 사람</dt><dd>{entry.createdByName}</dd></>}
          {entry.attendees.length > 0 && <><dt>참석자</dt><dd>{entry.attendees.map((p) => p.displayName).join(', ')}</dd></>}
          {entry.description && <><dt>설명</dt><dd className="pre">{entry.description}</dd></>}
        </dl>
        {entry.kind === 'RESERVATION' && <p className="cal-hint">회의실 예약은 3D 오피스의 회의실 예약 화면에서 바꾸거나 취소할 수 있습니다.</p>}
        {entry.kind === 'TASK' && <p className="cal-hint">회의록에서 지정된 할 일입니다. 완료 처리는 회의 메뉴의 &lsquo;내 할 일&rsquo;에서 합니다.</p>}
        {entry.kind === 'EVENT' && !entry.editable && <p className="cal-hint">{entry.scope === 'SHARED' ? '전체 일정은 관리자와 일정 관리자만 고칠 수 있습니다.' : '초대받은 일정은 만든 사람만 고칠 수 있습니다.'}</p>}
        <div className="cal-actions">
          <span style={{ flex: 1 }} />
          <button type="button" className="cal-ghost" onClick={onClose}>닫기</button>
          {entry.kind === 'EVENT' && entry.editable && <button type="button" className="cal-primary" onClick={onEdit}>수정·삭제</button>}
        </div>
      </div>
    </div>
  )
}
