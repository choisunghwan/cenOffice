import { useCallback, useEffect, useRef, useState } from 'react'
import { LocalAudioTrack, Room, Track } from 'livekit-client'
import { assistantApi, type AssistantState } from './assistantApi'
import type { Meeting } from './api'
import { useOffice } from '../store'
import './assistant.css'

const active = (state?: string) => ['GENERATING', 'READY', 'PLAYING'].includes(state ?? '')
const labels: Record<string, string> = { GENERATING: '회의 내용을 참고해 답변을 만드는 중…', READY: '목소리를 연결하는 중…', PLAYING: '센이 이야기하고 있어요', DONE: '답변 완료', STOPPED: '답변 중단', FAILED: '응답 실패', TEXT_ONLY: '글로 답변했어요' }

/** One extra, publish-only LiveKit connection sends the synthesized voice to everyone, including the asker. */
export function useMeetingAssistant(meeting: Meeting) {
  const username = useOffice(s => s.user?.username)
  const [state, setState] = useState<AssistantState>({ available: false, enabled: false, reason: '센 연결 확인 중…', turn: null, history: [] })
  const [question, setQuestion] = useState('')
  const [listening, setListening] = useState(false)
  const [error, setError] = useState('')
  const [pending, setPending] = useState(false)
  const mounted = useRef(true)
  const sequence = useRef(0)
  const requested = useRef(new Set<string>())
  const context = useRef<AudioContext | null>(null)
  const playback = useRef<{ id: string; cancel: () => void } | null>(null)
  const latest = useRef(state)
  latest.current = state
  const refresh = useCallback(async () => {
    const seq = ++sequence.current
    try { const s = await assistantApi.state(meeting.id); if (mounted.current && seq === sequence.current) setState(s) }
    catch (e) { if (mounted.current) setError((e as Error).message) }
  }, [meeting.id])
  useEffect(() => {
    mounted.current = true
    void refresh()
    const timer = setInterval(() => void refresh(), 800)
    return () => {
      mounted.current = false; ++sequence.current; clearInterval(timer)
      const own = playback.current
      playback.current = null; own?.cancel()
      const turn = latest.current.turn
      if (turn && turn.username === username && active(turn.state)) void assistantApi.stop(meeting.id).catch(() => {})
      void context.current?.close().catch(() => {}); context.current = null
    }
  }, [meeting.id, refresh, username])
  const stopLocal = useCallback(() => { const p = playback.current; playback.current = null; p?.cancel() }, [])
  useEffect(() => {
    const turn = state.turn
    if (!state.enabled) { stopLocal(); setListening(false); return }
    if (!turn || !active(turn.state)) { stopLocal(); return }
    setListening(false)
    if (turn.state !== 'READY' || turn.username !== username || !requested.current.has(turn.id) || playback.current) return
    requested.current.delete(turn.id)
    const abort = new AbortController()
    let cancelled = false
    let voiceRoom: Room | null = null
    const sources: AudioBufferSourceNode[] = []
    let track: LocalAudioTrack | null = null
    const cleanup = () => {
      cancelled = true; abort.abort()
      for (const src of sources) { src.onended = null; try { src.stop() } catch { /* not started */ }; src.disconnect() }
      track?.stop(); void voiceRoom?.disconnect(true)
    }
    playback.current = { id: turn.id, cancel: cleanup }
    /** index 번째 문장 음성을 받아 푼다. 아직 만드는 중이면 잠깐씩 기다린다 */
    const fetchChunk = async (ctx: AudioContext, index: number): Promise<AudioBuffer | null> => {
      for (;;) {
        const r = await assistantApi.audio(meeting.id, turn.id, index, abort.signal)
        if (r === 'end') return null
        if (r !== 'wait') return ctx.decodeAudioData(r)
        await new Promise(res => setTimeout(res, 250))
        if (cancelled) return null
      }
    }
    void (async () => {
      try {
        const ctx = context.current
        if (!ctx || ctx.state !== 'running') throw new Error('소리 재생이 허용되지 않았습니다. 질문 버튼을 다시 눌러 주세요.')
        // 첫 문장 음성이 오면 바로 회의방에 연결해 틀고, 다음 문장은 재생하는 동안 받아 이어 붙인다
        const first = await fetchChunk(ctx, 0)
        if (!first) throw new Error('empty')
        if (cancelled) return
        const grant = await assistantApi.grant(meeting.id, turn.id)
        if (cancelled) return
        voiceRoom = new Room()
        await voiceRoom.connect(grant.url, grant.token, { autoSubscribe: false })
        if (cancelled) { await voiceRoom.disconnect(true); return }
        const destination = ctx.createMediaStreamDestination()
        track = new LocalAudioTrack(destination.stream.getAudioTracks()[0], undefined, true, ctx)
        await voiceRoom.localParticipant.publishTrack(track, { source: Track.Source.Microphone, name: '센의 답변' })
        if (cancelled) { cleanup(); return }
        await assistantApi.playing(meeting.id, turn.id)
        if (cancelled) { cleanup(); return }
        let at = ctx.currentTime + 0.5, total = 0
        const schedule = (buffer: AudioBuffer) => {
          total += buffer.duration
          if (total > 90) throw new Error('음성 답변이 너무 깁니다')
          const src = ctx.createBufferSource(); src.buffer = buffer; src.connect(destination)
          at = Math.max(at, ctx.currentTime + 0.05) // 다음 문장이 늦게 오면 끊긴 뒤 바로 이어 튼다
          src.start(at); at += buffer.duration
          sources.push(src)
          return src
        }
        let last = schedule(first)
        await refresh()
        for (let i = 1; ; i++) {
          const next = await fetchChunk(ctx, i)
          if (cancelled) return
          if (!next) break
          last = schedule(next)
        }
        const finish = () => {
          if (cancelled) return
          // Leave a little time for the last encoded audio frames to reach subscribers.
          setTimeout(() => {
            if (cancelled) return
            cleanup(); playback.current = null
            void assistantApi.complete(meeting.id, turn.id).then(() => refresh()).catch(() => refresh())
          }, 400)
        }
        // "더 없음"을 받기 전에 마지막 문장이 이미 끝났을 수도 있다
        if (ctx.currentTime >= at - 0.02) finish(); else last.onended = finish
      } catch (e) {
        if (!cancelled && mounted.current) {
          setError((e as Error).message.startsWith('소리 재생이') ? (e as Error).message : '회의에 센의 목소리를 연결하지 못했습니다. 답변 글은 남아 있습니다. 다시 질문해 주세요.')
          void assistantApi.stop(meeting.id).then(() => refresh()).catch(() => {})
        }
        cleanup(); if (playback.current?.id === turn.id) playback.current = null
      }
    })()
  }, [state, username, meeting.id, refresh, stopLocal])
  const mutate = async (fn: () => Promise<AssistantState>) => {
    setPending(true); setError(''); ++sequence.current
    try { await fn(); await refresh() } catch (e) { setError((e as Error).message) } finally { if (mounted.current) setPending(false) }
  }
  const ask = async (value: string) => {
    if (!value.trim() || pending || active(state.turn?.state)) return
    // Resume on the user's click, before asynchronous network operations lose the user gesture.
    try { context.current ??= new AudioContext(); await context.current.resume() }
    catch { setError('브라우저에서 소리 재생을 허용해 주세요.'); return }
    setListening(false)
    await mutate(async () => {
      const s = await assistantApi.ask(meeting.id, value.trim())
      if (s.turn) requested.current.add(s.turn.id)
      setQuestion(''); return s
    })
  }
  return { state, question, setQuestion, listening, setListening, error, pending,
    blocked: active(state.turn?.state),
    pauseTranscription: state.enabled && ['READY', 'PLAYING'].includes(state.turn?.state ?? ''),
    append: (text: string) => setQuestion(q => (q + ' ' + text).trim().slice(0, 1200)),
    ask, toggle: () => mutate(() => state.enabled ? assistantApi.disable(meeting.id) : assistantApi.enable(meeting.id)),
    stop: () => { stopLocal(); return mutate(() => assistantApi.stop(meeting.id)) },
  }
}

type Assistant = ReturnType<typeof useMeetingAssistant>
export function MeetingAssistant({ assistant: a, host, mic, supported, interim }: { assistant: Assistant; host: boolean; mic: boolean; supported: boolean; interim: string }) {
  const { state } = a
  return <section className="meeting-assistant" aria-label="회의 AI 센">
    <div className="meeting-assistant-heading"><strong>✦ 센 <span>AI 회의 참여자</span></strong>
      {host && <button disabled={a.pending || (!state.enabled && !state.available)} onClick={() => void a.toggle()}>{state.enabled ? '센 끄기' : 'AI 센 켜기'}</button>}</div>
    {!state.enabled ? <p>{state.available ? (host ? '센을 켜고 회의 중 의견을 물어보세요.' : '진행자가 센을 켜면 질문할 수 있어요.') : state.reason}</p> : <>
      <p className="meeting-assistant-notice">질문과 기록에 동의한 참석자의 발언을 외부 AI가 참고합니다. 답변 음성은 모두에게 들립니다.</p>
      <p className="meeting-assistant-notice">회의 내용을 참고하려면 하단의 ‘음성 기록’을 켜 주세요. 기록이 없어도 질문 자체에 대한 의견은 물을 수 있어요.</p>
      <div className="meeting-assistant-status" role="status">{a.listening ? '질문을 듣고 있어요…' : a.blocked ? labels[state.turn?.state ?? ''] : '질문하면 회의 내용을 참고해 의견을 드려요.'}</div>
      {a.blocked ? <button className="meeting-assistant-stop" onClick={() => void a.stop()} disabled={a.pending}>말 멈추기</button> : <>
        <textarea aria-label="센에게 할 질문" placeholder="센아, 지금 나온 방안에 대한 의견은 어때?" maxLength={1200} value={a.question} onChange={e => a.setQuestion(e.target.value)} disabled={a.pending || a.listening} />
        {a.listening && <p className="meeting-assistant-interim">{interim || '마이크에 질문해 주세요.'}</p>}
        <div className="meeting-assistant-actions">{a.listening ? <>
          <button disabled={a.pending} onClick={() => { const text = [a.question, interim].filter(Boolean).join(' ').slice(0, 1200); a.setQuestion(text); void a.ask(text) }}>질문 완료</button>
          <button onClick={() => a.setListening(false)}>듣기 취소</button>
        </> : <>
          {supported && <button disabled={!mic || a.pending} title={!mic ? '마이크를 먼저 켜 주세요' : ''} onClick={() => a.setListening(true)}>센에게 질문</button>}
          <button disabled={!a.question.trim() || a.pending} onClick={() => void a.ask(a.question)}>질문 보내기</button>
        </>}</div>
        {!mic && supported && <small>음성 질문은 마이크를 켠 뒤 사용할 수 있어요. 글로도 질문할 수 있습니다.</small>}
      </>}
    </>}
    {a.error && <p role="alert" className="meeting-assistant-error">{a.error}</p>}
    {state.turn?.message && <p className="meeting-assistant-notice">{state.turn.message}</p>}
    {state.history.filter(t => t.answer || t.state === 'FAILED').slice(-3).map(t => <div className="meeting-assistant-answer" key={t.id}><small>질문 · {t.question}</small><b>센의 의견</b><p>{t.answer || '답변을 만들지 못했습니다.'}</p></div>)}
  </section>
}
