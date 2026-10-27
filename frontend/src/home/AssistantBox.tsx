import { useEffect, useRef, useState } from 'react'
import { assistantMemory, clearAssistantMemory } from './assistantMemory'
import { useOffice } from '../store'
import { calendarApi, type Entry } from '../calendar/api'
import { formatRange, KIND_LABEL } from '../calendar/dates'
import { EventEditor, type EditorInit } from '../calendar/EventEditor'
import { api } from '../api'
import { hub } from '../hubApi'
import { ExportButtons } from '../hub/ExportButtons'
import { formatDue, isoDate, type MyItem, type PendingMinutes } from '../meeting/minutesApi'
import { assistantApi, type Ambiguity, type Draft, type HistoryTurn, type Reply, type Room, type ToolPick } from './assistantApi'

type Turn =
  | { id: number; role: 'user'; text: string }
  | { id: number; role: 'assistant'; reply: Reply; input: string; draft: Draft | null; ambiguous: Ambiguity[]; tool: ToolPick | null; room: Room | null; rooms: Room[]; target: Entry | null; candidates: Entry[]; state: 'open' | 'saving' | 'done' | 'cancelled'; result?: string; output?: string; error?: string }

const EXAMPLES = ['오늘 브리핑 해줘', '내일 오후 3시에 팀 회의 잡아줘', '내일 오후 2시에 회의실 예약해줘', '내 할 일 보여줘', '회의 메모를 회의록으로 정리해줘', '쓸 만한 도구 뭐 있어?']
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

const asEntry = (d: Draft): Entry => ({ kind: 'EVENT', id: 'draft', eventId: null, scope: d.scope, title: d.title, description: d.description, location: d.location,
  startAt: d.startAt, endAt: d.endAt, allDay: d.allDay, createdBy: '', createdByName: null, attendees: d.attendees, editable: true })

function openMinutes(id: string) {
  sessionStorage.setItem('cenOpenMinutes', id)
  useOffice.getState().setWorkspacePage('meetings')
  window.dispatchEvent(new Event('hub-navigation'))
  window.dispatchEvent(new Event('cen-open-minutes'))
}

const todayIso = () => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }

/** 답변 속 "내 할 일" 목록. 기한이 지난 것은 빨갛게, 누르면 그 회의록이 열린다. */
function TaskList({ items }: { items: MyItem[] }) {
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

function PendingList({ items }: { items: PendingMinutes[] }) {
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

function EventList({ entries }: { entries: Entry[] }) {
  return (
    <ul className="home-list compact ha-list">
      {entries.slice(0, 8).map((e) => (
        <li key={e.id}><button type="button" onClick={goCalendar}><b>{e.title}</b><small>{formatRange(e)} · {KIND_LABEL[e.kind]}{e.location ? ` · ${e.location}` : ''}</small></button></li>
      ))}
      {entries.length > 8 && <li className="cal-muted">외 {entries.length - 8}건 · <button type="button" className="cal-link" onClick={goCalendar}>일정에서 모두 보기</button></li>}
    </ul>
  )
}

function goCalendar() {
  useOffice.getState().setWorkspacePage('calendar')
  window.dispatchEvent(new Event('hub-navigation'))
}

/** 홈 화면 입력창. 말로 일정을 등록·조회한다. 등록은 AI 가 해석한 초안을 확인 카드로 보여 주고, 사용자가 눌러야 실행된다. */
export function AssistantBox() {
  const name = useOffice((s) => s.user?.displayName) ?? ''
  const owner = useOffice((s) => s.user?.username) ?? ''
  // 다른 화면에 다녀와도 대화가 이어지도록 메모리에 있는 같은 사용자의 대화로 시작한다
  if (assistantMemory.owner !== owner) { clearAssistantMemory(); assistantMemory.owner = owner }
  const [turns, setTurnsState] = useState<Turn[]>(assistantMemory.turns as Turn[])
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [ai, setAi] = useState<boolean | null>(null)
  const [editor, setEditor] = useState<{ turnId: number; init: EditorInit } | null>(null)
  const previous = useRef<string | null>(assistantMemory.previous) // 되묻는 중이면 이전 요청(답을 이어서 해석하는 데 쓴다)
  const nextId = useRef(assistantMemory.nextId)
  const endRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false; assistantMemory.nextId = nextId.current; assistantMemory.previous = previous.current } }, [])
  // 화면이 닫힌 뒤에 도착한 답도 잃지 않도록, 화면이 없으면 메모리에 바로 쌓는다
  const setTurns = (f: (cur: Turn[]) => Turn[]) => {
    assistantMemory.turns = f(assistantMemory.turns as Turn[])
    if (alive.current) setTurnsState(assistantMemory.turns as Turn[])
  }

  useEffect(() => { assistantApi.status().then((s) => setAi(s.ai)).catch(() => setAi(null)) }, [])
  useEffect(() => { endRef.current?.scrollIntoView({ block: 'nearest' }) }, [turns, busy])

  const patch = (id: number, p: Partial<Extract<Turn, { role: 'assistant' }>>) =>
    setTurns((cur) => cur.map((t) => (t.id === id && t.role === 'assistant' ? { ...t, ...p } : t)))

  const send = async (raw?: string) => {
    const value = (raw ?? text).trim()
    if (!value || busy) return
    setText('')
    setBusy(true)
    const mine: Turn = { id: nextId.current++, role: 'user', text: value }
    setTurns((cur) => [...cur, mine].slice(-30))
    try {
      // 최근 대화를 함께 보내서 "그건 뭔데", "왜 못해"처럼 앞 이야기를 가리키는 말을 이해하게 한다
      const history: HistoryTurn[] = turns.slice(-8).map((t) => ({ role: t.role, text: (t.role === 'user' ? t.text : t.reply.message).slice(0, 800) })).filter((h) => h.text.trim())
      const reply = await assistantApi.interpret(value, previous.current ?? undefined, history)
      previous.current = reply.intent === 'CLARIFY' ? `${previous.current ? previous.current + ' ' : ''}${value}`.slice(-480) : null
      setAi(reply.ai || ai)
      const bot: Turn = { id: nextId.current++, role: 'assistant', reply, input: value, draft: reply.draft, ambiguous: reply.ambiguous, tool: reply.tool, room: reply.room ?? null, rooms: reply.rooms ?? [], target: reply.target ?? null, candidates: reply.candidates ?? [], state: 'open' }
      setTurns((cur) => [...cur, bot].slice(-30))
    } catch (e) {
      previous.current = null
      const failed: Turn = { id: nextId.current++, role: 'assistant', input: value, draft: null, ambiguous: [], tool: null, room: null, rooms: [], target: null, candidates: [], state: 'open',
        reply: { intent: 'UNSUPPORTED', message: (e as Error).message, draft: null, ambiguous: [], unresolved: [], entries: [], ai: false, source: '', tool: null, alternatives: [] } }
      setTurns((cur) => [...cur, failed])
    } finally { setBusy(false) }
  }

  const confirm = async (t: Extract<Turn, { role: 'assistant' }>) => {
    const d = t.draft
    if (!d || t.state !== 'open') return
    patch(t.id, { state: 'saving', error: undefined })
    try {
      await calendarApi.create({ scope: d.scope, title: d.title.trim(), description: d.description ?? '', location: d.location ?? '', startAt: d.startAt, endAt: d.endAt, allDay: d.allDay, attendees: d.attendees.map((p) => p.username) })
      patch(t.id, { state: 'done', result: d.attendees.length && d.scope === 'PERSONAL' ? `일정을 등록하고 ${d.attendees.length}명에게 알렸습니다.` : '일정을 등록했습니다.' })
      window.dispatchEvent(new Event('cen-calendar-changed'))
    } catch (e) { patch(t.id, { state: 'open', error: (e as Error).message }) }
  }

  /** 확인 카드의 [예약하기]: 기존 예약 API 가 겹침·시간 한도·권한을 다시 확인한다 */
  const confirmRoom = async (t: Extract<Turn, { role: 'assistant' }>) => {
    const d = t.draft
    if (!d || !t.room || t.state !== 'open') return
    patch(t.id, { state: 'saving', error: undefined })
    try {
      await api.reserve({ roomId: t.room.id, title: d.title.trim(), startAt: d.startAt, endAt: d.endAt })
      patch(t.id, { state: 'done', result: `${t.room.name}을(를) 예약했습니다.` })
      window.dispatchEvent(new Event('cen-calendar-changed'))
    } catch (e) { patch(t.id, { state: 'open', error: (e as Error).message }) }
  }

  /** 삭제·취소: 일정은 기존 일정 API, 회의실 예약은 기존 예약 취소 API (본인 것만 가능) */
  const confirmDelete = async (t: Extract<Turn, { role: 'assistant' }>) => {
    const target = t.target
    if (!target || t.state !== 'open') return
    patch(t.id, { state: 'saving', error: undefined })
    try {
      if (target.kind === 'RESERVATION') await api.cancelReservation(Number(target.id.replace('r:', '')))
      else if (target.eventId != null) await calendarApi.remove(target.eventId)
      else throw new Error('삭제할 수 없는 항목입니다.')
      patch(t.id, { state: 'done', result: target.kind === 'RESERVATION' ? '예약을 취소했습니다.' : '일정을 삭제했습니다.' })
      window.dispatchEvent(new Event('cen-calendar-changed'))
    } catch (e) { patch(t.id, { state: 'open', error: (e as Error).message }) }
  }

  /** 일정 변경: 변경 후 모습(draft)을 기존 일정 수정 API 로 보낸다 */
  const confirmMove = async (t: Extract<Turn, { role: 'assistant' }>) => {
    const d = t.draft, target = t.target
    if (!d || !target || target.eventId == null || t.state !== 'open') return
    patch(t.id, { state: 'saving', error: undefined })
    try {
      await calendarApi.update(target.eventId, { scope: d.scope, title: d.title.trim(), description: d.description ?? '', location: d.location ?? '', startAt: d.startAt, endAt: d.endAt, allDay: d.allDay, attendees: d.attendees.map((p) => p.username) })
      patch(t.id, { state: 'done', result: '일정을 옮겼습니다.' })
      window.dispatchEvent(new Event('cen-calendar-changed'))
    } catch (e) { patch(t.id, { state: 'open', error: (e as Error).message }) }
  }

  /** 추천한 허브 도구를 실행한다. 권한·승인 상태·호출 제한은 기존 허브 실행 API 가 다시 확인한다. */
  const runTool = async (t: Extract<Turn, { role: 'assistant' }>, chosen: ToolPick) => {
    if (t.state === 'saving') return
    patch(t.id, { state: 'saving', tool: chosen, error: undefined, output: undefined })
    try {
      let ex = await hub.execute({ skillId: chosen.skillId, input: t.input, engine: chosen.engine })
      const deadline = Date.now() + 120_000
      while (alive.current && !['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(ex.status)) {
        if (Date.now() > deadline) throw new Error('처리가 오래 걸리고 있습니다. 도구 둘러보기의 실행 기록에서 결과를 확인해 주세요.')
        await sleep(1000)
        ex = await hub.execution(ex.id)
      }
      if (!alive.current) return
      if (ex.status === 'SUCCEEDED') patch(t.id, { state: 'done', output: ex.result ?? '', result: `‘${chosen.title}’ 도구로 처리했습니다.` })
      else patch(t.id, { state: 'open', error: ex.error ?? (ex.status === 'CANCELLED' ? '취소되었습니다.' : '도구를 실행하지 못했습니다.') })
    } catch (e) { if (alive.current) patch(t.id, { state: 'open', error: (e as Error).message }) }
  }

  const pick = (t: Extract<Turn, { role: 'assistant' }>, a: Ambiguity, username: string) => {
    if (!t.draft) return
    const who = a.candidates.find((c) => c.username === username)
    if (!who) return
    patch(t.id, { draft: { ...t.draft, attendees: t.draft.attendees.some((p) => p.username === username) ? t.draft.attendees : [...t.draft.attendees, who] }, ambiguous: t.ambiguous.filter((x) => x !== a) })
  }

  const openEditor = (t: Extract<Turn, { role: 'assistant' }>) => {
    const d = t.draft
    if (!d) return
    setEditor({ turnId: t.id, init: { day: new Date(d.startAt), scope: 'mine', draft: { scope: d.scope, title: d.title, description: d.description ?? '', location: d.location ?? '', startAt: d.startAt, endAt: d.endAt, allDay: d.allDay, attendees: d.attendees.map((p) => p.username) } } })
  }

  return (
    <section className="home-assistant" aria-label="입력창으로 업무 처리">
      <div className="ha-scroll">
      <div className="ha-thread" role="log" aria-live="polite">
        {turns.length === 0 && (
          <div className="ha-empty">
            <h1 className="ha-hello">{name}님, 무엇을 도와드릴까요?</h1>
            <div className="ha-examples">{EXAMPLES.map((x) => <button type="button" key={x} onClick={() => { setText(x); inputRef.current?.focus() }}>{x}</button>)}</div>
          </div>
        )}
          {turns.map((t) => t.role === 'user'
            ? <p key={t.id} className="ha-user">{t.text}</p>
            : (
              <div key={t.id} className="ha-bot">
                <p className={t.reply.intent === 'ANSWER' ? 'ha-answer' : undefined}>{t.reply.message}</p>
                {t.reply.intent === 'ANSWER' && (t.reply.references ?? []).length > 0 && <p className="ha-refs">참고한 정보: {(t.reply.references ?? []).join(' · ')}</p>}
                {t.reply.intent === 'CREATE_EVENT' && t.draft && (
                  <div className={`ha-card ${t.state}`}>
                    <input className="ha-title" aria-label="일정 제목" value={t.draft.title} maxLength={200} disabled={t.state !== 'open'} onChange={(e) => patch(t.id, { draft: { ...t.draft!, title: e.target.value } })} />
                    <dl>
                      <dt>시간</dt><dd>{formatRange(asEntry(t.draft))}</dd>
                      {t.draft.location && <><dt>장소</dt><dd>{t.draft.location}</dd></>}
                      <dt>종류</dt><dd>{t.draft.scope === 'SHARED' ? '전체 일정 (모두에게 보임)' : '개인 일정'}</dd>
                      {t.draft.scope === 'PERSONAL' && <><dt>참석자</dt><dd>{t.draft.attendees.length ? t.draft.attendees.map((p) => p.displayName).join(', ') : '없음'}</dd></>}
                    </dl>
                    {t.ambiguous.map((a) => (
                      <div key={a.name} className="ha-pick"><span>‘{a.name}’ 누구인가요?</span>
                        {a.candidates.map((c) => <button type="button" key={c.username} disabled={t.state !== 'open'} onClick={() => pick(t, a, c.username)}>{c.displayName} <small>{c.username}</small></button>)}
                      </div>
                    ))}
                    {t.error && <p className="ha-error" role="alert">{t.error}</p>}
                    {t.state === 'done' ? <p className="ha-done">✓ {t.result} <button type="button" className="cal-link" onClick={goCalendar}>일정에서 보기 →</button></p>
                      : t.state === 'cancelled' ? <p className="cal-muted">취소했습니다.</p>
                      : (
                        <div className="ha-actions">
                          <button type="button" className="cal-primary" disabled={t.state === 'saving' || t.ambiguous.length > 0 || !t.draft.title.trim()} onClick={() => void confirm(t)}>{t.state === 'saving' ? '등록 중…' : '등록하기'}</button>
                          <button type="button" className="cal-ghost" disabled={t.state === 'saving'} onClick={() => openEditor(t)}>자세히 수정</button>
                          <button type="button" className="cal-link" disabled={t.state === 'saving'} onClick={() => patch(t.id, { state: 'cancelled' })}>취소</button>
                        </div>
                      )}
                  </div>
                )}
                {t.reply.intent === 'BOOK_ROOM' && t.draft && (
                  <div className={`ha-card ${t.state}`}>
                    <input className="ha-title" aria-label="예약 제목" value={t.draft.title} maxLength={200} disabled={t.state !== 'open'} onChange={(e) => patch(t.id, { draft: { ...t.draft!, title: e.target.value } })} />
                    <dl>
                      <dt>시간</dt><dd>{formatRange(asEntry(t.draft))}</dd>
                      <dt>회의실</dt><dd>{t.room ? `${t.room.name} (${t.room.capacity}명)` : '아래에서 골라 주세요'}</dd>
                    </dl>
                    {t.rooms.length > 0 && t.state === 'open' && (
                      <div className="ha-pick"><span>빈 회의실</span>
                        {t.rooms.map((r) => <button type="button" key={r.id} className={t.room?.id === r.id ? 'on' : ''} onClick={() => patch(t.id, { room: r })}>{r.name} <small>{r.capacity}명</small></button>)}
                      </div>
                    )}
                    {t.error && <p className="ha-error" role="alert">{t.error}</p>}
                    {t.state === 'done' ? <p className="ha-done">✓ {t.result} <button type="button" className="cal-link" onClick={goCalendar}>일정에서 보기 →</button></p>
                      : t.state === 'cancelled' ? <p className="cal-muted">취소했습니다.</p>
                      : (
                        <div className="ha-actions">
                          <button type="button" className="cal-primary" disabled={t.state === 'saving' || !t.room || !t.draft.title.trim()} onClick={() => void confirmRoom(t)}>{t.state === 'saving' ? '예약 중…' : '예약하기'}</button>
                          <button type="button" className="cal-link" disabled={t.state === 'saving'} onClick={() => patch(t.id, { state: 'cancelled' })}>취소</button>
                        </div>
                      )}
                  </div>
                )}
                {t.reply.intent === 'DELETE_ITEM' && (t.target || t.candidates.length > 0) && (
                  <div className={`ha-card ${t.state}`}>
                    {t.target ? (
                      <dl>
                        <dt>{t.target.kind === 'RESERVATION' ? '예약' : '일정'}</dt><dd><b>{t.target.title}</b></dd>
                        <dt>시간</dt><dd>{formatRange(t.target)}</dd>
                        {t.target.location && <><dt>장소</dt><dd>{t.target.location}</dd></>}
                        {t.target.attendees.length > 0 && <><dt>참석자</dt><dd>{t.target.attendees.map((p) => p.displayName).join(', ')}</dd></>}
                      </dl>
                    ) : (
                      <div className="ha-pick" style={{ flexDirection: 'column', alignItems: 'stretch' }}>
                        {t.candidates.map((c) => <button type="button" key={c.id} disabled={t.state !== 'open'} onClick={() => patch(t.id, { target: c })}><b>{c.title}</b> <small>{formatRange(c)} · {KIND_LABEL[c.kind]}</small></button>)}
                      </div>
                    )}
                    {t.target && t.target.attendees.length > 0 && t.state === 'open' && <p className="cal-muted">삭제하면 초대한 사람의 달력에서도 사라집니다.</p>}
                    {t.error && <p className="ha-error" role="alert">{t.error}</p>}
                    {t.state === 'done' ? <p className="ha-done">✓ {t.result}</p>
                      : t.state === 'cancelled' ? <p className="cal-muted">취소했습니다.</p>
                      : (
                        <div className="ha-actions">
                          {t.target && <button type="button" className="cal-danger" disabled={t.state === 'saving'} onClick={() => void confirmDelete(t)}>{t.state === 'saving' ? '처리 중…' : t.target.kind === 'RESERVATION' ? '예약 취소하기' : '삭제하기'}</button>}
                          {t.target && t.candidates.length > 1 && <button type="button" className="cal-ghost" disabled={t.state === 'saving'} onClick={() => patch(t.id, { target: null })}>다시 고르기</button>}
                          <button type="button" className="cal-link" disabled={t.state === 'saving'} onClick={() => patch(t.id, { state: 'cancelled' })}>그만두기</button>
                        </div>
                      )}
                  </div>
                )}
                {t.reply.intent === 'MOVE_EVENT' && t.draft && t.target && (
                  <div className={`ha-card ${t.state}`}>
                    <dl>
                      <dt>일정</dt><dd><b>{t.draft.title}</b></dd>
                      <dt>변경 전</dt><dd>{formatRange(t.target)}</dd>
                      <dt>변경 후</dt><dd><b>{formatRange(asEntry(t.draft))}</b></dd>
                      {t.draft.attendees.length > 0 && t.draft.scope === 'PERSONAL' && <><dt>참석자</dt><dd>{t.draft.attendees.map((p) => p.displayName).join(', ')}</dd></>}
                    </dl>
                    {t.error && <p className="ha-error" role="alert">{t.error}</p>}
                    {t.state === 'done' ? <p className="ha-done">✓ {t.result} <button type="button" className="cal-link" onClick={goCalendar}>일정에서 보기 →</button></p>
                      : t.state === 'cancelled' ? <p className="cal-muted">취소했습니다.</p>
                      : (
                        <div className="ha-actions">
                          <button type="button" className="cal-primary" disabled={t.state === 'saving'} onClick={() => void confirmMove(t)}>{t.state === 'saving' ? '옮기는 중…' : '옮기기'}</button>
                          <button type="button" className="cal-link" disabled={t.state === 'saving'} onClick={() => patch(t.id, { state: 'cancelled' })}>취소</button>
                        </div>
                      )}
                  </div>
                )}
                {t.reply.intent === 'USE_TOOL' && t.tool && (
                  <div className={`ha-card ${t.state}`}>
                    <p className="ha-toolname"><span aria-hidden="true">{t.tool.icon ?? '🧰'}</span> <b>{t.tool.title}</b>{t.tool.category && <small>{t.tool.category}</small>}</p>
                    {t.tool.description && <p className="cal-muted">{t.tool.description}</p>}
                    <p className="cal-muted">{t.tool.external ? '이 도구는 AI 서비스로 처리하며, 입력한 내용이 AI 서비스로 전송됩니다.' : '서버 안에서 처리하며 입력한 내용이 밖으로 나가지 않습니다.'}</p>
                    {t.error && <p className="ha-error" role="alert">{t.error}</p>}
                    {t.state === 'saving' && <p className="cal-muted" role="status">처리하는 중… (최대 2분)</p>}
                    {t.state === 'done' && <>
                      <p className="ha-done">✓ {t.result}</p>
                      <pre className="ha-output">{t.output}</pre>
                      <div className="ha-actions">
                        <button type="button" className="cal-ghost" onClick={() => void navigator.clipboard?.writeText(t.output ?? '')}>복사</button>
                        {t.output && <ExportButtons title={t.tool.title} markdown={t.output} />}
                      </div>
                    </>}
                    {t.state === 'cancelled' && <p className="cal-muted">취소했습니다.</p>}
                    {t.state === 'open' && (
                      <div className="ha-actions">
                        <button type="button" className="cal-primary" onClick={() => void runTool(t, t.tool!)}>실행하기</button>
                        {t.reply.alternatives.map((a) => <button type="button" key={a.skillId} className="cal-ghost" title={a.description} onClick={() => void runTool(t, a)}>‘{a.title}’로 하기</button>)}
                        <button type="button" className="cal-link" onClick={() => patch(t.id, { state: 'cancelled' })}>취소</button>
                      </div>
                    )}
                  </div>
                )}
                {t.reply.intent === 'LIST_EVENTS' && t.reply.entries.length > 0 && <EventList entries={t.reply.entries} />}
                {t.reply.intent === 'LIST_TASKS' && (t.reply.tasks ?? []).length > 0 && <TaskList items={t.reply.tasks ?? []} />}
                {t.reply.intent === 'LIST_PENDING' && (t.reply.pendingMinutes ?? []).length > 0 && <PendingList items={t.reply.pendingMinutes ?? []} />}
                {t.reply.intent === 'BRIEFING' && <>
                  {t.reply.entries.length > 0 && <><p className="ha-sub">오늘 일정</p><EventList entries={t.reply.entries} /></>}
                  {(t.reply.tasks ?? []).length > 0 && <><p className="ha-sub">할 일</p><TaskList items={t.reply.tasks ?? []} /></>}
                  {(t.reply.pendingMinutes ?? []).length > 0 && <><p className="ha-sub">확정할 회의록</p><PendingList items={t.reply.pendingMinutes ?? []} /></>}
                </>}
              </div>
            ))}
          {busy && <p className="ha-bot ha-wait">생각하는 중…</p>}
          <div ref={endRef} />
      </div>
      </div>

      <div className="ha-dock">
      <form className="ha-form" onSubmit={(e) => { e.preventDefault(); void send() }}>
        <textarea ref={inputRef} value={text} rows={2} maxLength={4000} placeholder="일정·회의실 예약, 할 일 확인, 회의 메모 정리까지 무엇이든 말씀하세요" aria-label="입력창" disabled={busy}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); void send() } }} />
        <button className="cal-primary" disabled={busy || !text.trim()} aria-label="보내기">보내기</button>
      </form>
      <p className="ha-note">{ai === null ? ' ' : ai ?'AI 연결됨 · 입력한 내용과 동료 이름이 AI 서비스로 전송됩니다. 일정·예약·삭제와 도구 실행은 확인하신 뒤에만 됩니다.' : 'AI 미연결 · 날짜·시각·제목이 분명한 짧은 문장과 도구 이름·태그를 이해합니다. 일정·예약·삭제와 도구 실행은 확인하신 뒤에만 됩니다.'}</p>
      </div>

      {editor && <EventEditor init={editor.init} canManageShared={editor.init.draft?.scope === 'SHARED'} onClose={() => setEditor(null)}
        onSaved={(m) => { patch(editor.turnId, { state: 'done', result: m }); setEditor(null); window.dispatchEvent(new Event('cen-calendar-changed')) }} />}
    </section>
  )
}
