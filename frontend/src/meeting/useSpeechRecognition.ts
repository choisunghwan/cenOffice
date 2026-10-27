import { useEffect, useRef, useState } from 'react'

// 브라우저 내장 음성 인식(Web Speech API). Chrome·Edge 에서 동작한다.
// 주의: 이 방식은 브라우저 제공업체(Chrome=Google, Edge=Microsoft)의 서버로 음성이 전송되어 처리된다.
// 사내 STT 서버를 도입하면 이 훅만 교체하면 된다 (onFinal 로 확정된 문장만 넘기는 계약은 동일).
interface RecognitionEvent { resultIndex: number; results: ArrayLike<ArrayLike<{ transcript: string }> & { isFinal: boolean }> }
interface Recognition {
  lang: string; continuous: boolean; interimResults: boolean; maxAlternatives: number
  onresult: ((e: RecognitionEvent) => void) | null; onerror: ((e: { error: string }) => void) | null; onend: (() => void) | null
  start(): void; stop(): void
}
type Ctor = new () => Recognition

const ctor = (): Ctor | null => {
  const w = window as unknown as { SpeechRecognition?: Ctor; webkitSpeechRecognition?: Ctor }
  return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null
}

export const speechSupported = () => ctor() !== null

const FATAL = new Set(['not-allowed', 'service-not-allowed', 'language-not-supported'])

/** active 인 동안 한국어 음성을 인식한다. 확정 문장은 onFinal, 말하는 중인 문장은 interim 으로 돌려준다. */
export function useSpeechRecognition(active: boolean, onFinal: (text: string) => void, lang = 'ko-KR') {
  const [interim, setInterim] = useState('')
  const [error, setError] = useState('')
  const finalRef = useRef(onFinal)
  useEffect(() => { finalRef.current = onFinal }, [onFinal])

  useEffect(() => {
    const Ctor = ctor()
    if (!active || !Ctor) return
    let stopped = false
    let restarts = 0
    const rec = new Ctor()
    rec.lang = lang; rec.continuous = true; rec.interimResults = true; rec.maxAlternatives = 1
    rec.onresult = (e) => {
      restarts = 0
      let partial = ''
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const r = e.results[i]
        const text = r[0].transcript.trim()
        if (r.isFinal) { if (text) finalRef.current(text) } else partial += text + ' '
      }
      setInterim(partial.trim())
    }
    rec.onerror = (e) => {
      if (FATAL.has(e.error)) { stopped = true; setError(e.error === 'not-allowed' || e.error === 'service-not-allowed' ? '브라우저에서 마이크 사용이 허용되지 않아 음성 인식을 할 수 없습니다.' : '이 브라우저는 한국어 음성 인식을 지원하지 않습니다.') }
      else if (e.error === 'network') setError('음성 인식 서비스에 연결하지 못했습니다. 네트워크를 확인하세요.')
    }
    // 브라우저는 침묵이 이어지면 인식을 스스로 끝낸다. 켜져 있는 동안은 다시 시작한다.
    rec.onend = () => {
      setInterim('')
      if (stopped || ++restarts > 50) return
      setTimeout(() => { if (!stopped) { try { rec.start() } catch { /* 이미 시작됨 */ } } }, 250)
    }
    setError('')
    try { rec.start() } catch { setError('음성 인식을 시작하지 못했습니다.') }
    return () => { stopped = true; rec.onend = null; rec.onresult = null; rec.onerror = null; try { rec.stop() } catch { /* ignore */ } setInterim('') }
  }, [active, lang])

  return { interim, error, supported: speechSupported() }
}
