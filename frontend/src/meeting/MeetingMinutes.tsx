import { useCallback, useEffect, useState } from 'react'
import { useOffice } from '../store'
import { MinutesFormFill } from './MinutesFormFill'
import { transcriptApi, type Segment } from './transcriptApi'
import { minutesApi, NotFound, isoDate, formatDue, type ActionItem, type Minutes } from './minutesApi'
import './minutes.css'
import './stt.css'

const lines = (s: string) => s.split('\n').map((x) => x.trim()).filter(Boolean)

/** 종료된 회의의 회의록: 초안 만들기 → 검토·수정 → 확정(알림 발송) → 파일로 받기 */
export function MeetingMinutes({ meetingId, title, host, onBack }: { meetingId: string; title: string; host: string; onBack: () => void }) {
  const user = useOffice((s) => s.user)
  const isHost = user?.username === host
  const [minutes, setMinutes] = useState<Minutes | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [notes, setNotes] = useState('')
  const [busy, setBusy] = useState<'' | 'generate' | 'save' | 'confirm'>('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [askConfirm, setAskConfirm] = useState(false)
  // 편집 상태
  const [summary, setSummary] = useState('')
  const [decisions, setDecisions] = useState('')
  const [issues, setIssues] = useState('')
  const [items, setItems] = useState<ActionItem[]>([])
  const [transcript, setTranscript] = useState<Segment[]>([])
  const [showTranscript, setShowTranscript] = useState(false)

  const apply = useCallback((m: Minutes) => {
    setMinutes(m); setSummary(m.summary); setDecisions(m.decisions.join('\n')); setIssues(m.issues.join('\n')); setItems(m.actionItems)
  }, [])

  useEffect(() => {
    let alive = true
    minutesApi.get(meetingId).then((m) => { if (alive) apply(m) }).catch((e) => { if (alive && !(e instanceof NotFound)) setError((e as Error).message) }).finally(() => { if (alive) setLoaded(true) })
    return () => { alive = false }
  }, [meetingId, apply])

  useEffect(() => { transcriptApi.list(meetingId).then(setTranscript).catch(() => setTranscript([])) }, [meetingId])

  const run = async (kind: 'generate' | 'save' | 'confirm', fn: () => Promise<Minutes>, ok: string) => {
    if (busy) return
    setBusy(kind); setError(''); setNotice('')
    try { apply(await fn()); setNotice(ok) } catch (e) { setError((e as Error).message) } finally { setBusy(''); setAskConfirm(false) }
  }
  const body = () => ({ summary, decisions: lines(decisions), issues: lines(issues), actionItems: items.filter((i) => i.content.trim()) })
  const editing = !!minutes && minutes.status === 'DRAFT' && minutes.canEdit
  const setItem = (idx: number, patch: Partial<ActionItem>) => setItems((cur) => cur.map((it, i) => (i === idx ? { ...it, ...patch } : it)))

  return <div className="minutes-view">
    <button className="minutes-link" onClick={onBack}>← 회의 목록</button>
    <div className="minutes-top"><h2>{title} · 회의록</h2>
      {minutes && <span className={`minutes-badge ${minutes.status === 'CONFIRMED' ? 'confirmed' : 'draft'}`}>{minutes.status === 'CONFIRMED' ? '확정됨' : '초안 · 검토 중'}</span>}</div>
    {error && <div className="meeting-alert" role="alert">{error}</div>}
    {notice && <div className="meeting-notice" role="status">{notice}</div>}

    {!loaded && <p role="status" className="minutes-muted">불러오는 중…</p>}

    {loaded && transcript.length > 0 && <div className="minutes-card"><h3>회의 전문 <span className="minutes-muted">· 음성 인식 {transcript.length}건 · 참석자만 열람</span></h3>
      <button className="minutes-link" onClick={() => setShowTranscript(v => !v)}>{showTranscript ? '접기' : '발언 내용 펼치기'}</button>
      {showTranscript && <div className="stt-transcript-card" style={{ marginTop: 10 }}>{transcript.map(t => <div className="stt-row" key={t.id}>
        <time>{new Date(t.spokenAt).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit' })}</time><b>{t.displayName}</b><span>{t.content}</span></div>)}</div>}
      <p className="minutes-hint">음성 인식 결과에는 오류가 있을 수 있습니다.</p></div>}

    {loaded && !minutes && (isHost ? <div className="minutes-card">
      <h3>회의록 초안 만들기</h3>
      <p>회의 중 나눈 대화를 정리해 요약, 결정 사항, 할 일(담당·기한), 확인 필요 사항으로 나눕니다. 오디오는 저장하지 않으며, <b>회의 채팅, 음성 인식 기록({transcript.length}건), 아래 메모</b>를 기준으로 합니다.</p>
      <label className="minutes-muted" htmlFor="minutes-notes" style={{ display: 'block', margin: '14px 0 6px' }}>추가 메모 (안건, 구두로 정한 내용 등 · 선택)</label>
      <textarea id="minutes-notes" value={notes} maxLength={4000} onChange={(e) => setNotes(e.target.value)} placeholder={'예)\n- 로그인 화면 개선안 확정\n- 김대리 다음주 월요일까지 견적서 작성'} />
      <div className="minutes-warn" role="note" style={{ marginTop: 12 }}>AI 가 정리하는 경우 회의 대화와 메모가 외부 AI 서비스로 전송될 수 있습니다. 민감한 내용이 있다면 메모와 대화를 확인하세요. 결과는 항상 초안이며 확정 전에 직접 검토합니다.</div>
      <div className="minutes-actions" style={{ marginTop: 14 }}><button className="minutes-btn primary" disabled={!!busy} onClick={() => void run('generate', () => minutesApi.generate(meetingId, notes), '초안을 만들었습니다. 내용을 확인하고 수정한 뒤 확정하세요.')}>{busy === 'generate' ? 'AI 가 정리하는 중… (최대 1분)' : '회의록 초안 만들기'}</button></div>
    </div> : <div className="minutes-card"><p>아직 회의록이 없습니다. 회의를 만든 사람이 회의록을 만들면 여기에 표시됩니다.</p></div>)}

    {minutes && <>
      <div className="minutes-warn" role="note">{minutes.source === 'rule-based'
        ? '규칙 기반 초안입니다. 문장의 의미를 이해한 요약이 아니라 결정·할 일 표현을 골라 놓은 것이므로 반드시 검토하세요.'
        : `AI(${minutes.source})가 만든 초안입니다. 사실, 담당자, 기한이 맞는지 확인하세요. AI 는 대화에 없는 내용을 잘못 넣을 수 있습니다.`}
        {minutes.status === 'CONFIRMED' && ` · ${new Date(minutes.confirmedAt ?? '').toLocaleString('ko-KR')} 확정`}</div>

      <div className="minutes-card"><h3>요약</h3>
        {editing ? <textarea aria-label="요약" value={summary} onChange={(e) => setSummary(e.target.value)} maxLength={2000} /> : <p>{minutes.summary}</p>}</div>

      <div className="minutes-card"><h3>결정 사항</h3>
        {editing ? <><textarea aria-label="결정 사항" value={decisions} onChange={(e) => setDecisions(e.target.value)} /><p className="minutes-hint">한 줄에 하나씩 적습니다.</p></>
          : minutes.decisions.length ? <ul>{minutes.decisions.map((d, i) => <li key={i}>{d}</li>)}</ul> : <p className="minutes-muted">없음</p>}</div>

      <div className="minutes-card"><h3>액션 아이템</h3>
        {editing ? <>
          <table className="minutes-items"><thead><tr><th>내용</th><th style={{ width: 160 }}>담당</th><th style={{ width: 210 }}>기한</th><th style={{ width: 40 }} /></tr></thead><tbody>
            {items.map((it, i) => <tr key={i}>
              <td><input aria-label={`할 일 ${i + 1}`} value={it.content} maxLength={500} onChange={(e) => setItem(i, { content: e.target.value })} /></td>
              <td><select aria-label={`담당 ${i + 1}`} value={it.assigneeUsername ?? ''} onChange={(e) => setItem(i, { assigneeUsername: e.target.value || null, assigneeText: null })}>
                <option value="">{it.assigneeText ? `${it.assigneeText} (참석자 아님)` : '미지정'}</option>
                {minutes.members.map((m) => <option key={m.username} value={m.username}>{m.displayName}</option>)}</select></td>
              <td><div className="minutes-due">
                <input aria-label={`기한 ${i + 1}`} value={it.dueText ?? ''} maxLength={60} placeholder="예: 금요일까지" onChange={(e) => setItem(i, { dueText: e.target.value })} />
                <input type="date" className="minutes-datepick" aria-label={`기한 ${i + 1} 달력`} title="달력에서 날짜 고르기" value={isoDate(it.dueText) ?? ''} onChange={(e) => setItem(i, { dueText: e.target.value || null })} />
              </div></td>
              <td><button className="minutes-link" aria-label={`할 일 ${i + 1} 삭제`} onClick={() => setItems((cur) => cur.filter((_, k) => k !== i))}>삭제</button></td>
            </tr>)}
          </tbody></table>
          <button className="minutes-link" style={{ marginTop: 10 }} onClick={() => setItems((cur) => [...cur, { content: '' }])}>＋ 할 일 추가</button>
          <p className="minutes-hint">담당자를 참석자로 지정하면 확정할 때 그 사람에게 알림이 갑니다. 기한은 오른쪽 달력 버튼으로 날짜를 고르거나, AI가 적은 표현(예: 금요일까지)을 그대로 둘 수 있습니다.</p></>
          : minutes.actionItems.length ? <table className="minutes-items"><thead><tr><th>내용</th><th>담당</th><th>기한</th></tr></thead><tbody>
            {minutes.actionItems.map((a, i) => <tr key={a.id ?? i}><td style={a.done ? { textDecoration: 'line-through' } : undefined}>{a.content}</td><td>{a.assigneeName ?? a.assigneeText ?? '미지정'}</td><td>{a.dueText ? formatDue(a.dueText) : '미지정'}</td></tr>)}</tbody></table>
            : <p className="minutes-muted">없음</p>}</div>

      <div className="minutes-card"><h3>이슈 · 확인 필요</h3>
        {editing ? <><textarea aria-label="이슈" value={issues} onChange={(e) => setIssues(e.target.value)} /><p className="minutes-hint">한 줄에 하나씩 적습니다.</p></>
          : minutes.issues.length ? <ul>{minutes.issues.map((d, i) => <li key={i}>{d}</li>)}</ul> : <p className="minutes-muted">없음</p>}</div>

      {editing && <div className="minutes-actions">
        <button className="minutes-btn" disabled={!!busy} onClick={() => void run('save', () => minutesApi.save(meetingId, body()), '저장했습니다.')}>{busy === 'save' ? '저장 중…' : '수정 내용 저장'}</button>
        <button className="minutes-btn primary" disabled={!!busy} onClick={() => setAskConfirm(true)}>확정하고 참석자에게 알리기</button>
        <button className="minutes-link" disabled={!!busy} onClick={() => void run('generate', () => minutesApi.generate(meetingId, minutes.extraNotes ?? ''), '초안을 다시 만들었습니다. 이전 수정 내용은 사라졌습니다.')}>{busy === 'generate' ? '다시 만드는 중…' : '초안 다시 만들기'}</button>
      </div>}
      {minutes.status === 'DRAFT' && !minutes.canEdit && <p className="minutes-muted">진행자가 회의록을 검토하고 있습니다. 확정되면 알림이 갑니다.</p>}

      {minutes.status === 'CONFIRMED' && <MinutesFormFill title={`${minutes.title} 회의록`} markdown={minutes.markdown} />}
    </>}

    {askConfirm && <div className="minutes-dialog-bg"><div className="minutes-dialog" role="dialog" aria-modal="true" aria-labelledby="confirm-title">
      <h2 id="confirm-title">회의록을 확정할까요?</h2>
      <p>확정하면 참석자에게 알림이 가고, 담당자로 지정된 사람에게는 할 일 알림이 갑니다. 확정 후에는 수정할 수 없습니다.</p>
      <div className="minutes-actions"><button className="minutes-btn" onClick={() => setAskConfirm(false)}>취소</button>
        <button className="minutes-btn primary" disabled={!!busy} onClick={() => void run('confirm', async () => { await minutesApi.save(meetingId, body()); return minutesApi.confirm(meetingId) }, '회의록을 확정하고 알림을 보냈습니다.')}>{busy === 'confirm' ? '확정 중…' : '확정하기'}</button></div>
    </div></div>}
  </div>
}
