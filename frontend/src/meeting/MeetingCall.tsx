import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { LiveKitRoom, RoomAudioRenderer, ParticipantTile, useTracks, useLocalParticipant, useParticipants, useConnectionState, StartAudio } from '@livekit/components-react'
import { Room, Track, ConnectionState } from 'livekit-client'
import '@livekit/components-styles'
import { useOffice } from '../store'
import { meetingsApi, type MediaGrant, type Meeting, type MeetingMessage } from './api'
import { transcriptApi } from './transcriptApi'
import { useSpeechRecognition } from './useSpeechRecognition'
import { CaptionBar, SttConsentDialog, TranscriptList, useTranscript } from './Transcript'

export function MeetingCall({ meeting, grant, mic, camera, onLeave }: { meeting: Meeting; grant: MediaGrant; mic: boolean; camera: boolean; onLeave: (message?: string) => void }) {
  const [error, setError] = useState('')
  const room = useMemo(() => new Room({ adaptiveStream: true, dynacast: true, videoCaptureDefaults: { resolution: { width: 640, height: 360, frameRate: 24 } } }), [])
  const user = useOffice(s => s.user)
  useEffect(() => () => { void room.disconnect(true) }, [room])
  useEffect(() => { if (!user) void room.disconnect(true) }, [user, room])
  return createPortal(<LiveKitRoom room={room} serverUrl={grant.url} token={grant.token} connect audio={mic} video={camera}
    onError={e => setError(`연결 또는 장치 오류: ${e.message}`)}
    onMediaDeviceFailure={() => setError('장치를 사용할 수 없습니다. 브라우저 권한을 확인해 주세요. 카메라·마이크 없이도 회의에 참여할 수 있습니다.')}
    onDisconnected={() => onLeave('회의 연결이 종료되었습니다. 회의가 진행 중이면 다시 참여할 수 있습니다.')}
    data-lk-theme="default" className="meeting-call">
    <CallContent meeting={meeting} room={room} error={error} setError={setError} onLeave={onLeave} />
  </LiveKitRoom>, document.body)
}

function CallContent({ meeting, room, error, setError, onLeave }: { meeting: Meeting; room: Room; error: string; setError: (s: string) => void; onLeave: (s?: string) => void }) {
  const user = useOffice(s => s.user)
  const participants = useParticipants()
  const connection = useConnectionState()
  const tracks = useTracks([{ source: Track.Source.Camera, withPlaceholder: true }, { source: Track.Source.ScreenShare, withPlaceholder: false }])
  const { localParticipant, isMicrophoneEnabled, isCameraEnabled, isScreenShareEnabled } = useLocalParticipant()
  const [messages, setMessages] = useState<MeetingMessage[]>([])
  const [text, setText] = useState('')
  const [copied, setCopied] = useState(false)
  const [busy, setBusy] = useState(false)
  const [sending, setSending] = useState(false)
  const [ending, setEnding] = useState(false)
  const [seconds, setSeconds] = useState(0)
  const [tab, setTab] = useState<'chat' | 'voice'>('chat')
  const [askStt, setAskStt] = useState(false)
  const [sttBusy, setSttBusy] = useState(false)
  const { segments, status: stt, refresh: refreshStt } = useTranscript(meeting.id, true)
  const end = useRef<HTMLDivElement>(null)
  const speech = useSpeechRecognition(stt.consented && isMicrophoneEnabled, useCallback((text: string) => {
    transcriptApi.add(meeting.id, text).then(() => refreshStt()).catch(e => setError((e as Error).message))
  }, [meeting.id, refreshStt, setError]))
  const toggleStt = async (on: boolean) => {
    setSttBusy(true); setError('')
    try { if (on) await transcriptApi.consent(meeting.id); else await transcriptApi.withdraw(meeting.id); await refreshStt(); setAskStt(false); if (on) setTab('voice') }
    catch (e) { setError((e as Error).message) } finally { setSttBusy(false) }
  }
  const shares = tracks.filter(t => t.source === Track.Source.ScreenShare)
  const cameras = tracks.filter(t => t.source === Track.Source.Camera)
  const reload = useCallback(async () => {
    try {
      const [m, msgs] = await Promise.all([meetingsApi.detail(meeting.id), meetingsApi.messages(meeting.id)])
      if (m.state === 'ENDED') { await room.disconnect(true); onLeave('회의가 종료되었습니다.'); return }
      setMessages(msgs)
    } catch (e) { setError((e as Error).message) }
  }, [meeting.id, room, onLeave, setError])
  useEffect(() => {
    void reload()
    const poll = setInterval(() => void reload(), 3000)
    const event = () => void reload()
    window.addEventListener('cen-meeting-changed', event)
    return () => { clearInterval(poll); window.removeEventListener('cen-meeting-changed', event) }
  }, [reload])
  useEffect(() => { const timer = setInterval(() => setSeconds(n => n + 1), 1000); return () => clearInterval(timer) }, [])
  useEffect(() => { end.current?.scrollIntoView({ block: 'nearest' }) }, [messages.length])
  const device = async (fn: () => Promise<unknown>) => {
    setBusy(true); setError('')
    try { await fn() } catch (e) { setError(`장치를 켜지 못했습니다: ${(e as Error).message}`) } finally { setBusy(false) }
  }
  const copy = async () => { try { await navigator.clipboard.writeText(meeting.code); setCopied(true); setTimeout(() => setCopied(false), 2000) } catch { setError(`참여 코드: ${meeting.code}`) } }
  const send = async () => {
    if (!text.trim() || sending) return
    setSending(true)
    try { await meetingsApi.send(meeting.id, text.trim()); setText(''); await reload() } catch (e) { setError((e as Error).message) } finally { setSending(false) }
  }
  const finish = async () => {
    setBusy(true)
    try { await meetingsApi.end(meeting.id); await room.disconnect(true); onLeave('회의를 종료했습니다.') } catch (e) { setError((e as Error).message); setEnding(false) } finally { setBusy(false) }
  }
  return <>
    <header className="meeting-call-header"><div><span className="meeting-live"><i />{connection === ConnectionState.Connected ? 'LIVE' : connection === ConnectionState.Reconnecting ? '재연결 중' : '연결 중'}</span><h2>{meeting.title}</h2><span className="meeting-clock">{String(Math.floor(seconds / 60)).padStart(2, '0')}:{String(seconds % 60).padStart(2, '0')}</span></div><button onClick={() => void copy()}>{copied ? '✓ 코드 복사됨' : '＋ 동료 초대'}</button></header>
    {error && <div className="meeting-call-error" role="alert">{error}<button aria-label="알림 닫기" onClick={() => setError('')}>×</button></div>}
    {speech.error && <div className="meeting-call-error" role="alert">{speech.error}</div>}
    <div className="meeting-call-body"><section className={`meeting-stage ${shares.length ? 'with-share' : ''}`}>
      {shares.length > 0 && <div className="meeting-share-stage">{shares.map(t => <ParticipantTile key={t.participant.identity + t.source} trackRef={t} />)}</div>}
      <div className="meeting-video-grid" data-count={Math.min(cameras.length, 4)}>{cameras.map(t => <ParticipantTile key={t.participant.identity} trackRef={t} />)}</div>
      {participants.length < 2 && <div className="meeting-waiting" role="status"><span>아직 나 혼자예요. 코드를 공유하면 동료가 여기에 나타납니다.</span><button onClick={() => void copy()}>{copied ? '✓ 복사됨' : '참여 코드 복사'}</button></div>}
      <RoomAudioRenderer /><StartAudio label="클릭해서 회의 소리 듣기" />
      {stt.recording.length > 0 && <CaptionBar segments={segments} interim={speech.interim} meName={user?.displayName ?? ''} />}
    </section><aside className="meeting-chat"><div className="meeting-chat-heading"><h3>함께하는 사람들 <span>{participants.length}</span></h3><div className="meeting-people">{participants.map(p => <span key={p.identity} title={p.name || p.identity}>{(p.name || p.identity).slice(0, 1)}</span>)}</div></div>
      <div className="meeting-chat-tabs" role="tablist"><button role="tab" aria-selected={tab === 'chat'} onClick={() => setTab('chat')}>회의 대화</button><button role="tab" aria-selected={tab === 'voice'} onClick={() => setTab('voice')}>음성 기록{segments.length ? ` ${segments.length}` : ''}</button></div>
      {stt.recording.length > 0 && <div className="stt-banner" role="note"><span className="stt-rec-dot" />음성 기록 중: {stt.recording.join(', ')}<br />오디오는 저장하지 않고 글자만 저장합니다.</div>}
      {tab === 'voice' ? <div className="meeting-chat-messages"><TranscriptList segments={segments} meUsername={user?.username} empty={stt.recording.length ? '말씀하시면 여기에 기록됩니다.' : '아래 "음성 기록" 버튼으로 내 발언 기록을 켤 수 있습니다.'} /></div> : <div className="meeting-chat-messages">{messages.length === 0 && <p className="meeting-chat-empty">링크나 의견을 남겨 보세요.<br />대화는 이 회의의 참여자에게만 보입니다.</p>}{messages.map(m => <div className={`meeting-message ${m.username === user?.username ? 'mine' : ''}`} key={m.id}><div><b>{m.displayName}</b><time>{new Date(m.sentAt).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })}</time></div><p>{m.content}</p></div>)}<div ref={end} /></div>}
      {tab === 'chat' && <form className="meeting-chat-compose" onSubmit={e => { e.preventDefault(); void send() }}><input aria-label="회의 메시지" placeholder="메시지 입력" maxLength={1000} value={text} onChange={e => setText(e.target.value)} /><button disabled={!text.trim() || sending} aria-label="메시지 전송">↑</button></form>}
    </aside></div>
    <footer className="meeting-controls"><span className="meeting-controls-note">{stt.recording.length ? '음성 인식 기록 중 · 오디오 저장 안 함' : '로컬 회의 · 녹음 안 함'}</span><div>
      <button disabled={busy} aria-pressed={isMicrophoneEnabled} onClick={() => void device(() => localParticipant.setMicrophoneEnabled(!isMicrophoneEnabled))}><span>{isMicrophoneEnabled ? '◉' : '⊘'}</span>{isMicrophoneEnabled ? '마이크 끄기' : '마이크 켜기'}</button>
      {speech.supported && <button className={stt.consented ? 'stt-on' : ''} disabled={busy || sttBusy} aria-pressed={stt.consented} onClick={() => (stt.consented ? void toggleStt(false) : setAskStt(true))}><span>{stt.consented ? '●' : '◌'}</span>{stt.consented ? '음성 기록 끄기' : '음성 기록'}</button>}
      <button disabled={busy} aria-pressed={isCameraEnabled} onClick={() => void device(() => localParticipant.setCameraEnabled(!isCameraEnabled))}><span>▣</span>{isCameraEnabled ? '카메라 끄기' : '카메라 켜기'}</button>
      <button disabled={busy} aria-pressed={isScreenShareEnabled} onClick={() => void device(() => localParticipant.setScreenShareEnabled(!isScreenShareEnabled, { audio: false }))}><span>↥</span>{isScreenShareEnabled ? '공유 중지' : '화면 공유'}</button>
      <button className="meeting-leave" onClick={() => { void room.disconnect(true); onLeave() }}><span>↗</span>나가기</button>
    </div>{meeting.host === user?.username && <button className="meeting-end" onClick={() => setEnding(true)}>전체 종료</button>}</footer>
    {askStt && <SttConsentDialog busy={sttBusy} onCancel={() => setAskStt(false)} onAgree={() => void toggleStt(true)} />}
    {ending && <div className="meeting-confirm"><div role="dialog" aria-modal="true" aria-labelledby="end-title"><h2 id="end-title">모두의 회의를 종료할까요?</h2><p>참여한 동료의 영상과 음성 연결도 종료됩니다.<br />회의 대화는 내 회의에 저장됩니다.</p><button onClick={() => setEnding(false)} disabled={busy}>계속 회의하기</button><button className="meeting-leave" disabled={busy} onClick={() => void finish()}>{busy ? '종료 중…' : '전체 종료'}</button></div></div>}
  </>
}
