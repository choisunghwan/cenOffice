import { useCallback, useEffect, useRef, useState } from 'react'
import { transcriptApi, type Segment, type TranscriptStatus } from './transcriptApi'
import './stt.css'

const clock = (iso: string) => new Date(iso).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit' })

/** 전문을 주기적으로 가져온다(실시간 자막·음성 기록 목록용). live=false 면 한 번만 읽는다. */
export function useTranscript(meetingId: string, live: boolean) {
  const [segments, setSegments] = useState<Segment[]>([])
  const [status, setStatus] = useState<TranscriptStatus>({ consented: false, segmentCount: 0, recording: [] })
  const last = useRef(0)
  const refresh = useCallback(async () => {
    try {
      const [s, fresh] = await Promise.all([transcriptApi.status(meetingId), transcriptApi.list(meetingId, last.current)])
      setStatus(s)
      // 주기 조회와 발언 직후 조회가 겹칠 수 있어 id 로 중복을 걸러낸다
      if (fresh.length) {
        last.current = Math.max(last.current, fresh[fresh.length - 1].id)
        setSegments(cur => { const seen = new Set(cur.map(x => x.id)); return [...cur, ...fresh.filter(x => !seen.has(x.id))] })
      }
    } catch { /* 일시적 오류는 다음 주기에 다시 시도 */ }
  }, [meetingId])
  useEffect(() => {
    void refresh()
    if (!live) return
    const timer = setInterval(() => void refresh(), 2000)
    return () => clearInterval(timer)
  }, [refresh, live])
  return { segments, status, refresh }
}

/** 음성 인식 기록 동의 안내 */
export function SttConsentDialog({ onCancel, onAgree, busy }: { onCancel: () => void; onAgree: () => void; busy: boolean }) {
  return <div className="meeting-confirm"><div role="dialog" aria-modal="true" aria-labelledby="stt-title">
    <h2 id="stt-title">내 발언을 음성 인식으로 기록할까요?</h2>
    <ul className="stt-notice">
      <li>내 마이크 음성을 <b>글자로 바꿔 이 회의의 기록에 저장</b>합니다. 오디오(녹음)는 저장하지 않습니다.</li>
      <li>기록은 <b>이 회의 참석자만</b> 볼 수 있고, 회의록 작성에 사용됩니다.</li>
      <li>이 방식은 브라우저 제공업체(Chrome은 Google, Edge는 Microsoft)의 음성 인식 서버로 음성이 전송되어 처리됩니다. 민감한 내용은 말하지 마세요.</li>
      <li>언제든 끌 수 있고, 마이크를 끄면 기록되지 않습니다. 인식 결과에는 오류가 있을 수 있습니다.</li>
    </ul>
    <button onClick={onCancel} disabled={busy}>취소</button>
    <button className="stt-agree" onClick={onAgree} disabled={busy}>{busy ? '시작 중…' : '동의하고 시작'}</button>
  </div></div>
}

/** 화면 아래 실시간 자막: 가장 최근 확정 발언 + 내가 말하는 중인 문장 */
export function CaptionBar({ segments, interim, meName }: { segments: Segment[]; interim: string; meName: string }) {
  const [now, setNow] = useState(Date.now())
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(t) }, [])
  const recent = segments.filter(s => now - new Date(s.spokenAt).getTime() < 8000).slice(-2)
  if (!recent.length && !interim) return null
  return <div className="stt-caption" aria-live="polite">
    {recent.map(s => <p key={s.id}><b>{s.displayName}</b> {s.content}</p>)}
    {interim && <p className="interim"><b>{meName}</b> {interim}</p>}
  </div>
}

/** 발언자·시각별 전문 목록 (회의 중 사이드 패널과 회의록 화면에서 같이 쓴다) */
export function TranscriptList({ segments, meUsername, empty }: { segments: Segment[]; meUsername?: string; empty: string }) {
  const end = useRef<HTMLDivElement>(null)
  useEffect(() => { end.current?.scrollIntoView({ block: 'nearest' }) }, [segments.length])
  if (!segments.length) return <p className="meeting-chat-empty">{empty}</p>
  return <>{segments.map(s => <div className={`meeting-message stt-line ${s.username === meUsername ? 'mine' : ''}`} key={s.id}>
    <div><b>{s.displayName}</b><time>{clock(s.spokenAt)}</time></div><p>{s.content}</p></div>)}<div ref={end} /></>
}
