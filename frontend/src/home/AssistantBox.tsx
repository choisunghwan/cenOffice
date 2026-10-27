import { useEffect, useRef, useState } from 'react'
import { useOffice } from '../store'
import { calendarApi, type Entry } from '../calendar/api'
import { formatRange, KIND_LABEL } from '../calendar/dates'
import { EventEditor, type EditorInit } from '../calendar/EventEditor'
import { hub } from '../hubApi'
import { ExportButtons } from '../hub/ExportButtons'
import { assistantApi, type Ambiguity, type Draft, type Reply, type ToolPick } from './assistantApi'

type Turn =
  | { id: number; role: 'user'; text: string }
  | { id: number; role: 'assistant'; reply: Reply; input: string; draft: Draft | null; ambiguous: Ambiguity[]; tool: ToolPick | null; state: 'open' | 'saving' | 'done' | 'cancelled'; result?: string; output?: string; error?: string }

const EXAMPLES = ['내일 오후 3시에 팀 회의 잡아줘', '이번 주 내 일정 알려줘', '다음 주 월요일 종일 워크숍 등록해줘', '회의 메모를 회의록으로 정리해줘']
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

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
  const alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])

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
      const bot: Turn = { id: nextId.current++, role: 'assistant', reply, input: value, draft: reply.draft, ambiguous: reply.ambiguous, tool: reply.tool, state: 'open' }
      setTurns((cur) => [...cur, bot].slice(-30))
    } catch (e) {
      previous.current = null
      const failed: Turn = { id: nextId.current++, role: 'assistant', input: value, draft: null, ambiguous: [], tool: null, state: 'open',
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
        <textarea value={text} rows={1} maxLength={4000} placeholder="무엇을 도와드릴까요? 일정 등록·조회, 회의 메모 정리 같은 업무 도구를 쓸 수 있어요" aria-label="입력창" disabled={busy}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); void send() } }} />
        <button className="cal-primary" disabled={busy || !text.trim()} aria-label="보내기">보내기</button>
      </form>
      {turns.length === 0 && <div className="ha-examples">{EXAMPLES.map((x) => <button type="button" key={x} onClick={() => setText(x)}>{x}</button>)}</div>}
      <p className="ha-note">{ai === null ? '' : ai ? 'AI 연결됨 · 입력한 내용과 동료 이름이 AI 서비스로 전송됩니다. 일정 등록과 도구 실행은 확인하신 뒤에만 됩니다.' : 'AI 미연결 · 날짜·시각·제목이 분명한 짧은 문장과 도구 이름·태그를 이해합니다. 일정 등록과 도구 실행은 확인하신 뒤에만 됩니다.'}</p>

      {editor && <EventEditor init={editor.init} canManageShared={editor.init.draft?.scope === 'SHARED'} onClose={() => setEditor(null)}
        onSaved={(m) => { patch(editor.turnId, { state: 'done', result: m }); setEditor(null); window.dispatchEvent(new Event('cen-calendar-changed')) }} />}
    </section>
  )
}
