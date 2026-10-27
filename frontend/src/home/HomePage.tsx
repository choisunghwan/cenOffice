import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useOffice } from '../store'
import { calendarApi, type Entry } from '../calendar/api'
import { addDays, dowLabel, formatRange, isAllDayLike, KIND_LABEL, overlapsDay, pad, startOfDay } from '../calendar/dates'
import { formatDue, isoDate, minutesApi, type MyItem, type PendingMinutes } from '../meeting/minutesApi'
import { AssistantBox } from './AssistantBox'
import '../calendar/calendar.css'
import './home.css'

const timeOf = (e: Entry) => { const s = new Date(e.startAt); return `${pad(s.getHours())}:${pad(s.getMinutes())}` }
const kindClass = (e: Entry) => `cal-ev k-${e.kind === 'EVENT' ? (e.scope === 'SHARED' ? 'shared' : 'personal') : e.kind.toLowerCase()}`

function go(page: string) {
  useOffice.getState().setWorkspacePage(page)
  window.dispatchEvent(new Event('hub-navigation'))
}

function greeting(now: Date) {
  const h = now.getHours()
  return h < 6 ? '늦은 시간까지 수고 많으세요' : h < 12 ? '좋은 아침입니다' : h < 18 ? '좋은 오후입니다' : '오늘도 수고하셨습니다'
}

/** 첫 화면. 오늘 내 일정·할 일·확정할 회의록을 한눈에 보여 주고 자주 가는 곳으로 안내한다. */
export function HomePage() {
  const user = useOffice((s) => s.user)
  const [entries, setEntries] = useState<Entry[] | null>(null)
  const [items, setItems] = useState<MyItem[] | null>(null)
  const [pending, setPending] = useState<PendingMinutes[] | null>(null)
  const [error, setError] = useState('')
  const seq = useRef(0)
  const now = new Date()
  const today = startOfDay(now)

  const load = useCallback(async () => {
    const n = ++seq.current
    const from = startOfDay(new Date())
    // 세 가지는 서로 독립이라 하나가 실패해도 나머지는 보여 준다
    const [cal, tasks, pend] = await Promise.allSettled([calendarApi.feed('mine', from, addDays(from, 8)), minutesApi.myItems(), minutesApi.pending()])
    if (n !== seq.current) return
    if (cal.status === 'fulfilled') setEntries(cal.value.entries)
    if (tasks.status === 'fulfilled') setItems(tasks.value)
    if (pend.status === 'fulfilled') setPending(pend.value)
    const failed = [cal, tasks, pend].find((r) => r.status === 'rejected') as PromiseRejectedResult | undefined
    setError(failed ? ((failed.reason as Error)?.message ?? '일부 정보를 불러오지 못했습니다') : '')
  }, [])

  useEffect(() => {
    void load()
    const t = setInterval(() => void load(), 60_000)
    window.addEventListener('cen-notification', load)
    window.addEventListener('cen-calendar-changed', load) // 입력창으로 일정을 등록하면 바로 반영
    return () => { clearInterval(t); window.removeEventListener('cen-notification', load); window.removeEventListener('cen-calendar-changed', load) }
  }, [load])

  const todays = useMemo(() => (entries ?? []).filter((e) => overlapsDay(e, today)).sort((a, b) => Number(isAllDayLike(b)) - Number(isAllDayLike(a)) || a.startAt.localeCompare(b.startAt)), [entries, today])
  const upcoming = useMemo(() => (entries ?? []).filter((e) => !overlapsDay(e, today) && new Date(e.startAt) >= addDays(today, 1)).slice(0, 5), [entries, today])
  const todo = useMemo(() => (items ?? []).filter((i) => !i.done)
    .sort((a, b) => (isoDate(a.dueText) ? 0 : 1) - (isoDate(b.dueText) ? 0 : 1) || (isoDate(a.dueText) ?? '9').localeCompare(isoDate(b.dueText) ?? '9')), [items])
  const overdue = (i: MyItem) => { const d = isoDate(i.dueText); return !!d && d < `${today.getFullYear()}-${pad(today.getMonth() + 1)}-${pad(today.getDate())}` }

  const openMinutes = (id: string) => { sessionStorage.setItem('cenOpenMinutes', id); go('meetings'); window.dispatchEvent(new Event('cen-open-minutes')) }

  return (
    <div className="calendar-page home-page">
      <header className="home-hero">
        <p className="cal-eyebrow">HOME</p>
        <h1>{user?.displayName}님, {greeting(now)}</h1>
        <p>{now.getMonth() + 1}월 {now.getDate()}일 {dowLabel(now.getDay())}요일 · 오늘 일정 {entries === null ? '…' : `${todays.length}건`}{todo.length > 0 ? ` · 할 일 ${todo.length}건` : ''}</p>
        <AssistantBox />
        <div className="home-quick">
          <button type="button" className="cal-primary" onClick={() => go('meetings')}>◉ 회의 시작·참여</button>
          <button type="button" className="cal-ghost" onClick={() => go('calendar')}>▦ 일정 보기</button>
          <button type="button" className="cal-ghost" onClick={() => go('home')}>✧ 도구 둘러보기</button>
          <button type="button" className="cal-ghost" onClick={() => useOffice.getState().setMode('office')}>3D 오피스 ↗</button>
        </div>
      </header>

      {error && <p className="cal-error" role="alert">{error}</p>}

      <div className="home-grid">
        <section className="home-card wide" aria-label="오늘 일정">
          <header><h2>오늘 일정</h2><button type="button" className="cal-link" onClick={() => go('calendar')}>일정 열기 →</button></header>
          {entries === null ? <p className="cal-muted">불러오는 중…</p> : todays.length === 0
            ? <p className="home-empty">오늘은 잡힌 일정이 없습니다.<br /><button type="button" className="cal-link" onClick={() => go('calendar')}>＋ 일정 만들기</button></p>
            : <ul className="home-list">{todays.map((e) => (
              <li key={e.id}><button type="button" onClick={() => go('calendar')}>
                <span className={kindClass(e)}>{isAllDayLike(e) ? '종일' : timeOf(e)}</span>
                <b>{e.title}</b><small>{KIND_LABEL[e.kind]}{e.location ? ` · ${e.location}` : ''}{!isAllDayLike(e) ? ` · ${formatRange(e).split(' ').slice(-3).join(' ')}` : ''}</small>
              </button></li>))}</ul>}
          {upcoming.length > 0 && <>
            <h3>다가오는 일정</h3>
            <ul className="home-list compact">{upcoming.map((e) => {
              const s = new Date(e.startAt)
              return <li key={e.id}><button type="button" onClick={() => go('calendar')}><span className="home-date">{s.getMonth() + 1}/{s.getDate()} ({dowLabel(s.getDay())})</span><b>{e.title}</b><small>{isAllDayLike(e) ? '종일' : timeOf(e)}</small></button></li>
            })}</ul></>}
        </section>

        <section className="home-card" aria-label="내 할 일">
          <header><h2>내 할 일</h2><button type="button" className="cal-link" onClick={() => go('meetings')}>전체 보기 →</button></header>
          {items === null ? <p className="cal-muted">불러오는 중…</p> : todo.length === 0
            ? <p className="home-empty">처리할 할 일이 없습니다. 👍</p>
            : <ul className="home-list compact">{todo.slice(0, 6).map((i) => (
              <li key={i.id}><button type="button" onClick={() => openMinutes(i.meetingId)}>
                <b>{i.content}</b><small className={overdue(i) ? 'late' : ''}>{i.dueText ? `${formatDue(i.dueText)}${overdue(i) ? ' · 기한 지남' : ''}` : '기한 없음'} · {i.meetingTitle}</small>
              </button></li>))}</ul>}
          {todo.length > 6 && <p className="cal-muted">외 {todo.length - 6}건</p>}
        </section>

        <section className="home-card" aria-label="확정할 회의록">
          <header><h2>확정할 회의록</h2></header>
          {pending === null ? <p className="cal-muted">불러오는 중…</p> : pending.length === 0
            ? <p className="home-empty">확정을 기다리는 회의록이 없습니다.</p>
            : <ul className="home-list compact">{pending.map((p) => (
              <li key={p.meetingId}><button type="button" onClick={() => openMinutes(p.meetingId)}>
                <b>{p.meetingTitle}</b><small>{p.status === 'DRAFT' ? '초안 검토 중' : '초안 만들기 전'} · {new Date(p.endedAt).getMonth() + 1}월 {new Date(p.endedAt).getDate()}일 종료</small>
              </button></li>))}</ul>}
        </section>
      </div>
    </div>
  )
}
