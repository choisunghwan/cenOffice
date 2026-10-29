import { useEffect, useRef, useState } from 'react'
import { useOffice } from '../store'
import { resolveApproval, runMockAgent } from './mockAgent'
import { AI_STATUS_COLOR, AI_STATUS_LABEL } from './AiStaff'
import { theme } from '../theme'
import { CommandBadge, Notice, Row, latestCommand, resultMessage } from './DeviceKit'

export function Shell({ title, children, onClose }: { title: string; children: React.ReactNode; onClose: () => void }) {
  return (
    <aside className="glass slide-in absolute right-4 top-20 bottom-20 w-[min(380px,calc(100vw-2rem))] rounded-2xl flex flex-col shadow-2xl shadow-black/40 z-20">
      <header className="flex items-center justify-between px-5 py-4 border-b border-white/10">
        <h2 className="font-semibold tracking-wide">{title}</h2>
        <button onClick={onClose} className="text-slate-400 hover:text-white text-sm px-2 py-1 rounded hover:bg-white/10">ESC ✕</button>
      </header>
      <div className="flex-1 min-h-0 overflow-y-auto">{children}</div>
    </aside>
  )
}

export function LightPanel() {
  const conn = useOffice((s) => s.conn)
  const lightOn = useOffice((s) => s.lightOn)
  const dev = useOffice((s) => s.devices['light-main'])
  const commands = useOffice((s) => s.commands)
  const control = useOffice((s) => s.control)
  const close = useOffice((s) => s.close)
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const cmd = latestCommand(commands, 'light-main')
  const open = cmd?.status === 'REQUESTED' || cmd?.status === 'SENT'
  const online = conn === 'online'
  const on = online && dev ? dev.state.power : lightOn

  const toggle = async () => {
    setErr(null)
    setBusy(true)
    const r = await control('light-main', { power: !on })
    setBusy(false)
    setErr(resultMessage(r))
  }

  return (
    <Shell title="💡 조명 제어" onClose={close}>
      <div className="p-5 space-y-5">
        <button
          onClick={toggle}
          disabled={busy || open}
          className={`w-full py-4 rounded-xl font-semibold text-lg transition disabled:opacity-60 ${on ? 'text-slate-900 shadow-lg' : 'bg-slate-700 text-slate-200'}`}
          style={on ? { background: theme.light, boxShadow: `0 8px 24px ${theme.light}40` } : undefined}
        >
          {busy || open ? '장비 응답 대기 중…' : on ? '조명 ON  (눌러서 끄기)' : '조명 OFF  (눌러서 켜기)'}
        </button>
        <dl>
          <Row k="장비">{dev ? dev.name : '메인 오피스 조명 (가상)'}</Row>
          <Row k="상태 (장비 보고)"><span style={{ color: on ? theme.light : theme.status.idle }}>{on ? 'ON' : 'OFF'}</span></Row>
          <Row k="연결">{online ? `백엔드 · ${dev?.adapterType === 'MOCK' ? 'Mock 어댑터' : dev?.adapterType}` : '로컬 시뮬레이션'}</Row>
          {online && <Row k="최근 명령"><CommandBadge cmd={cmd} /></Row>}
        </dl>
        {err && <Notice tone="error">{err}</Notice>}
        {!online && <Notice>백엔드에 연결되지 않아 브라우저 안에서만 동작합니다. 로그인하면 서버를 거쳐 장비 확인 후 반영됩니다.</Notice>}
      </div>
    </Shell>
  )
}

export function InfoPanel() {
  const t = useOffice((s) => s.infoTarget)
  const close = useOffice((s) => s.close)
  return (
    <Shell title={t?.label ?? ''} onClose={close}>
      <div className="p-5 text-sm text-slate-300 leading-relaxed">
        <span className="inline-block mb-3 text-[11px] px-2 py-0.5 rounded-full bg-amber-400/15 text-amber-300">준비 중</span>
        <p>{t?.description}</p>
      </div>
    </Shell>
  )
}

export function AiPanel() {
  const messages = useOffice((s) => s.messages)
  const status = useOffice((s) => s.aiStatus)
  const close = useOffice((s) => s.close)
  const addMessage = useOffice((s) => s.addMessage)
  const [text, setText] = useState('')
  const end = useRef<HTMLDivElement>(null)
  const input = useRef<HTMLInputElement>(null)
  const busy = status === 'working' || status === 'approval'

  useEffect(() => { end.current?.scrollIntoView({ behavior: 'smooth' }) }, [messages])
  useEffect(() => { input.current?.focus() }, [])

  const send = (t: string) => {
    const v = t.trim()
    if (!v || status === 'working') return
    addMessage({ role: 'user', text: v })
    setText('')
    void runMockAgent(v)
  }

  return (
    <aside className="glass slide-in absolute right-4 top-20 bottom-20 w-[min(420px,calc(100vw-2rem))] rounded-2xl flex flex-col shadow-2xl shadow-black/40 z-20">
      <header className="flex items-center justify-between px-5 py-4 border-b border-white/10">
        <div>
          <h2 className="font-semibold">🤖 AI 직원 · 아리</h2>
          <div className="flex items-center gap-1.5 text-xs mt-0.5">
            <span className={`w-2 h-2 rounded-full ${status === 'working' ? 'pulse-dot' : ''}`} style={{ background: AI_STATUS_COLOR[status] }} />
            <span className="text-slate-300">{AI_STATUS_LABEL[status]}</span>
            <span className="text-slate-500">· Mock AI 모드</span>
          </div>
        </div>
        <button onClick={close} className="text-slate-400 hover:text-white text-sm px-2 py-1 rounded hover:bg-white/10">ESC ✕</button>
      </header>
      <div className="flex-1 min-h-0 overflow-y-auto p-4 space-y-3">
        {messages.map((m) => (
          <div key={m.id} className={`flex ${m.role === 'user' ? 'justify-end' : 'justify-start'}`}>
            <div className={`max-w-[88%] rounded-2xl px-3.5 py-2.5 text-sm whitespace-pre-wrap leading-relaxed ${m.role === 'user' ? 'bg-blue-500 text-white' : 'bg-white/8 bg-slate-800/80 text-slate-100'}`}>
              {m.tool && m.tool !== 'chat' && <div className="text-[10px] text-sky-300 mb-1 font-mono">🔧 {m.tool}</div>}
              {m.text}
              {m.pending && (
                <div className="mt-2.5 flex gap-2 items-center">
                  {m.pending.state === 'pending' ? (
                    <>
                      <button onClick={() => resolveApproval(m.id, true)} className="px-3 py-1 rounded-lg bg-emerald-500 text-white text-xs font-semibold">승인</button>
                      <button onClick={() => resolveApproval(m.id, false)} className="px-3 py-1 rounded-lg bg-slate-600 text-white text-xs">거절</button>
                    </>
                  ) : (
                    <span className="text-xs text-slate-400">{m.pending.state === 'approved' ? '✔ 승인됨' : '✖ 거절됨'}</span>
                  )}
                </div>
              )}
            </div>
          </div>
        ))}
        {status === 'working' && <div className="text-xs text-sky-300 pulse-dot">아리가 작업 중…</div>}
        <div ref={end} />
      </div>
      <div className="px-4 pt-1 flex flex-wrap gap-1.5">
        {['서버 상태 확인해 줘', '보고서 작성해 줘', '조명 꺼줘'].map((q) => (
          <button key={q} disabled={busy} onClick={() => send(q)} className="text-[11px] px-2.5 py-1 rounded-full bg-white/8 bg-slate-800/70 hover:bg-slate-700 disabled:opacity-40">{q}</button>
        ))}
      </div>
      <form onSubmit={(e) => { e.preventDefault(); send(text) }} className="p-4 flex gap-2">
        <input
          ref={input}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Escape') useOffice.getState().close(); e.stopPropagation() }}
          placeholder="아리에게 업무를 지시하세요…"
          className="flex-1 bg-slate-900/70 border border-white/10 rounded-xl px-3 py-2 text-sm outline-none focus:border-sky-400"
        />
        <button disabled={status === 'working'} className="px-4 rounded-xl bg-sky-500 text-white text-sm font-semibold disabled:opacity-40">전송</button>
      </form>
    </aside>
  )
}
