import { askConfirm } from '../ui/confirm'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useOffice } from '../store'
import { calendarApi, type Entry, type EventBody, type Person, type Scope } from './api'
import { addDays, fromInputs, toDateInput, toTimeInput } from './dates'

/** 입력창(AI)이 만든 초안으로 미리 채워서 열 때 쓴다 */
export interface EditorDraft { scope: 'PERSONAL' | 'SHARED'; title: string; description: string; location: string; startAt: string; endAt: string; allDay: boolean; attendees: string[] }
export interface EditorInit { entry?: Entry; draft?: EditorDraft; day: Date; scope: Scope }

/** 일정 만들기/고치기. 개인 일정은 참석자를 초대할 수 있고, 전체 일정은 관리자·일정 관리자만 만든다. */
export function EventEditor({ init, canManageShared, onClose, onSaved }: { init: EditorInit; canManageShared: boolean; onClose: () => void; onSaved: (message: string) => void }) {
  const me = useOffice((s) => s.user?.username)
  const editing = init.entry
  const firstRef = useRef<HTMLInputElement>(null)

  const defaults = useMemo(() => {
    if (editing) {
      const s = new Date(editing.startAt), e = new Date(editing.endAt)
      return { scope: editing.scope, title: editing.title, allDay: editing.allDay, startDate: toDateInput(s), startTime: toTimeInput(s),
        endDate: toDateInput(editing.allDay ? addDays(e, -1) : e), endTime: toTimeInput(e), location: editing.location ?? '', description: editing.description ?? '',
        attendees: editing.attendees.map((p) => p.username) }
    }
    if (init.draft) {
      const s = new Date(init.draft.startAt), e = new Date(init.draft.endAt)
      return { scope: init.draft.scope, title: init.draft.title, allDay: init.draft.allDay, startDate: toDateInput(s), startTime: toTimeInput(s),
        endDate: toDateInput(init.draft.allDay ? addDays(e, -1) : e), endTime: toTimeInput(e), location: init.draft.location, description: init.draft.description, attendees: init.draft.attendees }
    }
    const now = new Date()
    const start = new Date(init.day.getFullYear(), init.day.getMonth(), init.day.getDate(), 9, 0)
    if (toDateInput(init.day) === toDateInput(now)) { start.setHours(Math.min(now.getHours() + 1, 22), 0) }
    const end = new Date(start.getTime() + 3600_000)
    return { scope: (init.scope === 'shared' && canManageShared ? 'SHARED' : 'PERSONAL') as 'PERSONAL' | 'SHARED', title: '', allDay: false,
      startDate: toDateInput(start), startTime: toTimeInput(start), endDate: toDateInput(end), endTime: toTimeInput(end), location: '', description: '', attendees: [] as string[] }
  }, [editing, init.day, init.draft, init.scope, canManageShared])

  const [scope, setScope] = useState(defaults.scope)
  const [title, setTitle] = useState(defaults.title)
  const [allDay, setAllDay] = useState(defaults.allDay)
  const [startDate, setStartDate] = useState(defaults.startDate)
  const [startTime, setStartTime] = useState(defaults.startTime)
  const [endDate, setEndDate] = useState(defaults.endDate)
  const [endTime, setEndTime] = useState(defaults.endTime)
  const [location, setLocation] = useState(defaults.location)
  const [description, setDescription] = useState(defaults.description)
  const [attendees, setAttendees] = useState<string[]>(defaults.attendees)
  const [people, setPeople] = useState<Person[]>([])
  const [filter, setFilter] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => { firstRef.current?.focus() }, [])
  useEffect(() => {
    if (scope === 'SHARED') return
    let alive = true
    calendarApi.people().then((p) => { if (alive) setPeople(p) }).catch(() => undefined)
    return () => { alive = false }
  }, [scope])
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const names = useMemo(() => new Map(people.map((p) => [p.username, p.displayName])), [people])
  const candidates = people.filter((p) => p.username !== me && !attendees.includes(p.username)
    && (!filter.trim() || p.displayName.includes(filter.trim()) || p.username.toLowerCase().includes(filter.trim().toLowerCase())))

  // 시작을 바꾸면 길이는 그대로 두고 종료도 같이 옮긴다 (종료가 시작보다 앞서는 실수를 막는다)
  const shiftStart = (nextDate: string, nextTime: string) => {
    const before = fromInputs(startDate, allDay ? '00:00' : startTime), after = fromInputs(nextDate, allDay ? '00:00' : nextTime)
    if (!Number.isNaN(before.getTime()) && !Number.isNaN(after.getTime())) {
      const end = new Date(fromInputs(endDate, allDay ? '00:00' : endTime).getTime() + (after.getTime() - before.getTime()))
      if (!Number.isNaN(end.getTime())) { setEndDate(toDateInput(end)); setEndTime(toTimeInput(end)) }
    }
    setStartDate(nextDate); setStartTime(nextTime)
  }

  const submit = async (ev: React.FormEvent) => {
    ev.preventDefault()
    if (busy) return
    let startAt: Date, endAt: Date
    if (allDay) {
      startAt = fromInputs(startDate, '00:00'); endAt = addDays(fromInputs(endDate, '00:00'), 1)
    } else { startAt = fromInputs(startDate, startTime); endAt = fromInputs(endDate, endTime) }
    if (Number.isNaN(startAt.getTime()) || Number.isNaN(endAt.getTime())) { setError('날짜와 시간을 입력하세요.'); return }
    if (!(endAt > startAt)) { setError('종료는 시작 이후여야 합니다.'); return }
    const body: EventBody = { scope, title: title.trim(), description: description.trim(), location: location.trim(), startAt: startAt.toISOString(), endAt: endAt.toISOString(), allDay, attendees: scope === 'SHARED' ? [] : attendees }
    setBusy(true); setError('')
    try {
      if (editing?.eventId != null) await calendarApi.update(editing.eventId, body); else await calendarApi.create(body)
      onSaved(editing ? '일정을 수정했습니다.' : attendees.length && scope === 'PERSONAL' ? `일정을 만들고 ${attendees.length}명에게 알렸습니다.` : '일정을 만들었습니다.')
    } catch (e) { setError((e as Error).message); setBusy(false) }
  }

  const remove = async () => {
    if (!editing?.eventId || busy) return
    if (!await askConfirm({ title: '일정을 삭제할까요?', message: '삭제한 일정은 되돌릴 수 없습니다.', okText: '삭제', danger: true })) return
    setBusy(true); setError('')
    try { await calendarApi.remove(editing.eventId); onSaved('일정을 삭제했습니다.') } catch (e) { setError((e as Error).message); setBusy(false) }
  }

  return (
    <div className="cal-modal" role="dialog" aria-modal="true" aria-label={editing ? '일정 수정' : '일정 만들기'} onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <form className="cal-dialog" onSubmit={submit}>
        <h2>{editing ? '일정 수정' : '일정 만들기'}</h2>
        {!editing && canManageShared && (
          <div className="cal-seg" role="radiogroup" aria-label="일정 종류">
            <button type="button" role="radio" aria-checked={scope === 'PERSONAL'} onClick={() => setScope('PERSONAL')}>개인 일정</button>
            <button type="button" role="radio" aria-checked={scope === 'SHARED'} onClick={() => setScope('SHARED')}>전체 일정</button>
          </div>
        )}
        {scope === 'SHARED' && <p className="cal-hint">전체 일정은 모든 사용자의 달력(전체)에 보입니다.</p>}
        <label>제목<input ref={firstRef} value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200} placeholder="예: 주간 팀 회의" required /></label>
        <label className="cal-check"><input type="checkbox" checked={allDay} onChange={(e) => setAllDay(e.target.checked)} /> 종일</label>
        <div className="cal-row">
          <label>시작<input type="date" value={startDate} onChange={(e) => shiftStart(e.target.value, startTime)} required /></label>
          {!allDay && <label>&nbsp;<input type="time" value={startTime} onChange={(e) => shiftStart(startDate, e.target.value)} required /></label>}
        </div>
        <div className="cal-row">
          <label>종료<input type="date" value={endDate} onChange={(e) => setEndDate(e.target.value)} required /></label>
          {!allDay && <label>&nbsp;<input type="time" value={endTime} onChange={(e) => setEndTime(e.target.value)} required /></label>}
        </div>
        <label>장소<input value={location} onChange={(e) => setLocation(e.target.value)} maxLength={200} placeholder="회의실, 화상회의 등 (선택)" /></label>
        <label>설명<textarea value={description} onChange={(e) => setDescription(e.target.value)} maxLength={2000} rows={3} placeholder="선택" /></label>
        {scope === 'PERSONAL' && (
          <div className="cal-attendees">
            <span className="cal-label">참석자 <small>({attendees.length}명 · 초대하면 알림이 가고 상대 달력에도 보입니다)</small></span>
            {attendees.length > 0 && <div className="cal-chips">{attendees.map((u) => <span key={u} className="cal-chip">{names.get(u) ?? u}<button type="button" aria-label={`${names.get(u) ?? u} 제외`} onClick={() => setAttendees((c) => c.filter((x) => x !== u))}>×</button></span>)}</div>}
            <input value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="이름이나 사번으로 찾기" aria-label="참석자 찾기" />
            <div className="cal-people" role="listbox" aria-label="참석자 후보">
              {candidates.slice(0, 30).map((p) => <button type="button" key={p.username} role="option" aria-selected="false" onClick={() => setAttendees((c) => [...c, p.username])}>{p.displayName}<small>{p.username}</small></button>)}
              {candidates.length === 0 && <p className="cal-muted">{people.length ? '추가할 수 있는 사용자가 없습니다.' : '불러오는 중…'}</p>}
            </div>
          </div>
        )}
        {error && <p className="cal-error" role="alert">{error}</p>}
        <div className="cal-actions">
          {editing && <button type="button" className="cal-danger" onClick={remove} disabled={busy}>삭제</button>}
          <span style={{ flex: 1 }} />
          <button type="button" className="cal-ghost" onClick={onClose}>취소</button>
          <button className="cal-primary" disabled={busy || !title.trim()}>{busy ? '저장 중…' : editing ? '수정' : '만들기'}</button>
        </div>
      </form>
    </div>
  )
}
