import { useEffect, useRef, useState } from 'react'
import { useOffice } from '../store'
import { calendarApi, type Entry } from '../calendar/api'
import { formatRange, KIND_LABEL } from '../calendar/dates'
import { EventEditor, type EditorInit } from '../calendar/EventEditor'
import { assistantApi, type Ambiguity, type Draft, type Reply } from './assistantApi'

type Turn =
  | { id: number; role: 'user'; text: string }
  | { id: number; role: 'assistant'; reply: Reply; draft: Draft | null; ambiguous: Ambiguity[]; state: 'open' | 'saving' | 'done' | 'cancelled'; result?: string; error?: string }

const EXAMPLES = ['내일 오후 3시에 팀 회의 잡아줘', '이번 주 내 일정 알려줘', '다음 주 월요일 종일 워크숍 등록해줘']

const asEntry = (d: Draft): Entry => ({ kind: 'EVENT', id: 'draft', eventId: null, scope: d.scope, title: d.title, description: d.description, location: d.location,
  startAt: d.startAt, endAt: d.endAt, allDay: d.allDay, createdBy: '', createdByName: null, attendees: d.attendees, editable: true })

function goCalendar() {
  useOffice.getState().setWorkspacePage('calendar')
  window.dispatchEvent(new Event('hub-navigation'))
}

/** 홈 화면 입력창. 말로 일정을 등록·조회한다. 등록은 AI 가 해석한 초안을 확인 카드로 보여 주고, 사용자가 눌러야 실행된다. */
export function AssistantBox() {
  const [turns, setTurns] = useState<Turn[]>([])
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [ai, setAi] = useState<boolean | null>(null)
  const [editor, setEditor] = useState<{ turnId: number; init: EditorInit } | null>(null)
  const previous = useRef<string | null>(null) // 되묻는 중이면 이전 요청(답을 이어서 해석하는 데 쓴다)
  const nextId = useRef(1)
  const endRef = useRef<HTMLDivElement>(null)

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
      const reply = await assistantApi.interpret(value, previous.current ?? undefined)
      previous.current = reply.intent === 'CLARIFY' ? `${previous.current ? previous.current + ' ' : ''}${value}`.slice(-480) : null
      setAi(reply.ai || ai)
      const bot: Turn = { id: nextId.current++, role: 'assistant', reply, draft: reply.draft, ambiguous: reply.ambiguous, state: 'open' }
      setTurns((cur) => [...cur, bot].slice(-30))
    } catch (e) {
      previous.current = null
      const failed: Turn = { id: nextId.current++, role: 'assistant', draft: null, ambiguous: [], state: 'open',
        reply: { intent: 'UNSUPPORTED', message: (e as Error).message, draft: null, ambiguous: [], unresolved: [], entries: [], ai: false, source: '' } }
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
    <section className="home-assistant" aria-label="입력창으로 일정 관리">
      {turns.length > 0 && (
        <div className="ha-thread" role="log" aria-live="polite">
          {turns.map((t) => t.role === 'user'
            ? <p key={t.id} className="ha-user">{t.text}</p>
            : (
              <div key={t.id} className="ha-bot">
                <p>{t.reply.message}</p>
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
                {t.reply.intent === 'LIST_EVENTS' && t.reply.entries.length > 0 && (
                  <ul className="home-list compact ha-list">
                    {t.reply.entries.slice(0, 8).map((e) => (
                      <li key={e.id}><button type="button" onClick={goCalendar}><b>{e.title}</b><small>{formatRange(e)} · {KIND_LABEL[e.kind]}{e.location ? ` · ${e.location}` : ''}</small></button></li>
                    ))}
                    {t.reply.entries.length > 8 && <li className="cal-muted">외 {t.reply.entries.length - 8}건 · <button type="button" className="cal-link" onClick={goCalendar}>일정에서 모두 보기</button></li>}
                  </ul>
                )}
              </div>
            ))}
          {busy && <p className="ha-bot ha-wait">생각하는 중…</p>}
          <div ref={endRef} />
        </div>
      )}

      <form className="ha-form" onSubmit={(e) => { e.preventDefault(); void send() }}>
        <textarea value={text} rows={1} maxLength={500} placeholder="무엇을 도와드릴까요? 예: 내일 오후 3시에 팀 회의 잡아줘" aria-label="입력창" disabled={busy}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); void send() } }} />
        <button className="cal-primary" disabled={busy || !text.trim()} aria-label="보내기">보내기</button>
      </form>
      {turns.length === 0 && <div className="ha-examples">{EXAMPLES.map((x) => <button type="button" key={x} onClick={() => setText(x)}>{x}</button>)}</div>}
      <p className="ha-note">{ai === null ? '' : ai ? 'AI 연결됨 · 입력한 내용과 동료 이름이 AI 서비스로 전송됩니다. 등록은 확인하신 뒤에만 됩니다.' : 'AI 미연결 · 날짜·시각·제목이 분명한 짧은 문장을 이해합니다. 등록은 확인하신 뒤에만 됩니다.'}</p>

      {editor && <EventEditor init={editor.init} canManageShared={editor.init.draft?.scope === 'SHARED'} onClose={() => setEditor(null)}
        onSaved={(m) => { patch(editor.turnId, { state: 'done', result: m }); setEditor(null); window.dispatchEvent(new Event('cen-calendar-changed')) }} />}
    </section>
  )
}
