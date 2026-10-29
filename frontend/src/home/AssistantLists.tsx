import { useState } from 'react'
import { dlLabel, DL_FORMATS, downloadMinutes, type DlFormat } from './minutesDownload'
import { useOffice } from '../store'
import { formatRange, KIND_LABEL } from '../calendar/dates'
import type { Entry } from '../calendar/api'
import { formatDue, isoDate, type MyItem, type PendingMinutes } from '../meeting/minutesApi'

export function openMinutes(id: string) {
  sessionStorage.setItem('cenOpenMinutes', id)
  useOffice.getState().setWorkspacePage('meetings')
  window.dispatchEvent(new Event('hub-navigation'))
  window.dispatchEvent(new Event('cen-open-minutes'))
}

export const todayIso = () => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }

/** 답변 속 "내 할 일" 목록. 기한이 지난 것은 빨갛게, 누르면 그 회의록이 열린다. */
export function TaskList({ items }: { items: MyItem[] }) {
  const today = todayIso()
  return (
    <ul className="home-list compact ha-list">
      {items.map((i) => {
        const iso = isoDate(i.dueText), late = !!iso && iso < today
        return (
          <li key={i.id}><button type="button" onClick={() => openMinutes(i.meetingId)}>
            <b>{i.content}</b><small className={late ? 'late' : ''}>{i.dueText ? `${formatDue(i.dueText)}${late ? ' · 기한 지남' : ''}` : '기한 없음'} · {i.meetingTitle}</small>
          </button></li>
        )
      })}
    </ul>
  )
}

/** 확정된 회의록 내려받기 카드: 어떤 회의록·어떤 형식인지 여기서 고르고, 누르면 받는다. */
export function MinutesDownloadList({ items, initial }: { items: PendingMinutes[]; initial: string | null }) {
  const [format, setFormat] = useState<DlFormat | null>(DL_FORMATS.some((f) => f.key === initial) ? (initial as DlFormat) : null)
  const [busy, setBusy] = useState<string | null>(null)
  const [done, setDone] = useState<Record<string, string>>({})
  const [err, setErr] = useState<string | null>(null)
  const go = async (id: string) => {
    if (busy) return
    setBusy(id); setErr(null)
    try {
      const r = await downloadMinutes(id, format)
      const how = r.how === 'me' ? '내 양식' : r.how === 'company' ? '공용 양식' : '양식 없이 받기'
      setDone((cur) => ({ ...cur, [id]: `${how}(${dlLabel(r.format)})으로 받았습니다.${r.missing.length ? ` 비어 있는 항목: ${r.missing.join(', ')}` : ''}` }))
    } catch (e) { setErr((e as Error).message) } finally { setBusy(null) }
  }
  return (
    <div className="ha-dl">
      <div className="tf-formats" role="radiogroup" aria-label="받을 형식">
        <button type="button" role="radio" aria-checked={format === null} className={format === null ? 'on' : ''} onClick={() => setFormat(null)} title="내 양식 → 회사 양식 → 기본 문서 순으로 정합니다">자동</button>
        {DL_FORMATS.map((f) => <button key={f.key} type="button" role="radio" aria-checked={format === f.key} className={format === f.key ? 'on' : ''} onClick={() => setFormat(f.key)}>{f.label}</button>)}
      </div>
      <ul className="ha-dl-list">
        {items.map((p) => {
          const e = new Date(p.endedAt)
          return (
            <li key={p.meetingId} className="ha-dl-row">
              <div className="ha-dl-info"><b>{p.meetingTitle}</b><small>{e.getMonth() + 1}월 {e.getDate()}일 확정{done[p.meetingId] ? ` · ${done[p.meetingId]}` : ''}</small></div>
              <button type="button" className="cal-primary" disabled={!!busy} onClick={() => void go(p.meetingId)}>{busy === p.meetingId ? '만드는 중…' : done[p.meetingId] ? '다시 받기' : '받기'}</button>
            </li>
          )
        })}
      </ul>
      {err && <p className="ha-error" role="alert">{err}</p>}
    </div>
  )
}

export function PendingList({ items }: { items: PendingMinutes[] }) {
  return (
    <ul className="home-list compact ha-list">
      {items.map((p) => {
        const e = new Date(p.endedAt)
        return (
          <li key={p.meetingId}><button type="button" onClick={() => openMinutes(p.meetingId)}>
            <b>{p.meetingTitle}</b><small>{p.status === 'DRAFT' ? '초안 검토 중' : '초안 만들기 전'} · {e.getMonth() + 1}월 {e.getDate()}일 종료 · 눌러서 열기</small>
          </button></li>
        )
      })}
    </ul>
  )
}

export function EventList({ entries }: { entries: Entry[] }) {
  return (
    <ul className="home-list compact ha-list">
      {entries.slice(0, 8).map((e) => (
        <li key={e.id}><button type="button" onClick={goCalendar}><b>{e.title}</b><small>{formatRange(e)} · {KIND_LABEL[e.kind]}{e.location ? ` · ${e.location}` : ''}</small></button></li>
      ))}
      {entries.length > 8 && <li className="cal-muted">외 {entries.length - 8}건 · <button type="button" className="cal-link" onClick={goCalendar}>일정에서 모두 보기</button></li>}
    </ul>
  )
}

/** 회의 화면으로 가서 그 회의의 입장 준비 화면을 연다 */
export function openMeeting(id: string) {
  sessionStorage.setItem('cenOpenMeeting', id)
  useOffice.getState().setWorkspacePage('meetings')
  window.dispatchEvent(new Event('hub-navigation'))
  window.dispatchEvent(new Event('cen-open-meeting'))
}

export function goCalendar() {
  useOffice.getState().setWorkspacePage('calendar')
  window.dispatchEvent(new Event('hub-navigation'))
}
