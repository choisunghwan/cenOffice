import { api } from '../api'
import { useOffice } from '../store'

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
const rnd = (a: number, b: number) => Math.round(a + Math.random() * (b - a))

interface ToolResult { tool: string; text: string }

/** Mock Tool: 서버 상태 조회 (Phase 2에서 실제 모니터링 API로 교체) */
async function getServerStatus(): Promise<ToolResult> {
  if (useOffice.getState().conn === 'online') {
    try {
      const snap = await api.servers()
      const icon = { OK: '🟢', WARN: '🟡', DOWN: '🔴' }
      const lines = snap.servers.map((s) => `${icon[s.status]} ${s.name}  CPU ${s.cpu}% · MEM ${s.memory}%${s.status === 'DOWN' ? '  — 응답 없음' : ''}`)
      const bad = snap.servers.filter((s) => s.status !== 'OK').length
      const head = `서버 상태를 조회했습니다. (백엔드 ${snap.source === 'MOCK' ? 'Mock 모니터' : snap.source})`
      const tail = bad ? `⚠ 확인이 필요한 서버 ${bad}대가 있습니다.` : '모든 서버가 정상입니다.'
      return { tool: 'get_server_status', text: `${head}\n${lines.join('\n')}\n\n${tail}` }
    } catch { /* 아래 로컬 Mock 으로 대체 */ }
  }
  return getLocalServerStatus()
}

function getLocalServerStatus(): ToolResult {
  const servers = [
    { name: 'web-01', cpu: rnd(12, 55), mem: rnd(30, 70), ok: true },
    { name: 'api-01', cpu: rnd(20, 70), mem: rnd(40, 80), ok: true },
    { name: 'db-01', cpu: rnd(15, 60), mem: rnd(50, 85), ok: true },
    { name: 'batch-01', cpu: rnd(2, 20), mem: rnd(20, 40), ok: Math.random() > 0.25 },
  ]
  const lines = servers.map(
    (s) => `${s.ok ? '🟢' : '🔴'} ${s.name}  CPU ${s.cpu}% · MEM ${s.mem}%${s.ok ? '' : '  — 응답 없음'}`,
  )
  const bad = servers.filter((s) => !s.ok).length
  return {
    tool: 'get_server_status',
    text: `서버 상태를 조회했습니다. (Mock 데이터)\n${lines.join('\n')}\n\n${bad ? `⚠ 장애 의심 서버 ${bad}대가 있습니다.` : '모든 서버가 정상입니다.'}`,
  }
}

function summarize(input: string): ToolResult {
  const body = input.replace(/(문서|내용)?\s*요약(해\s*줘|해줘|해 주세요)?/g, '').trim()
  const sentences = body.split(/[.!?\n。]/).map((s) => s.trim()).filter(Boolean)
  const text = sentences.length
    ? `핵심 요약:\n${sentences.slice(0, 3).map((s, i) => `${i + 1}. ${s}`).join('\n')}`
    : '요약할 본문을 함께 붙여 넣어 주세요. 예) "요약해줘: 이번 분기 매출은 ..."'
  return { tool: 'summarize_document', text: text + '\n\n(Mock 요약: 실제 LLM 연결 시 품질이 향상됩니다.)' }
}

function report(): ToolResult {
  const d = new Date().toLocaleDateString('ko-KR')
  return {
    tool: 'draft_report',
    text: `[일일 업무 보고서 초안 · ${d}]\n1. 진행 업무: 3D 오피스 Phase 1 점검\n2. 이슈: 없음\n3. 내일 계획: 백엔드 연동 준비\n\n(Mock 초안입니다. 내용을 알려주시면 맞춰 작성합니다.)`,
  }
}

function analyzeCode(): ToolResult {
  return {
    tool: 'analyze_code',
    text: '코드 분석 (Mock): 코드를 붙여 넣어 주시면 구조·복잡도·잠재 버그 후보를 정리합니다. 현재는 LLM 미연결 상태라 정적 규칙 기반 안내만 가능합니다.',
  }
}

/** 사용자 메시지를 처리한다. 장비 제어는 항상 승인 단계를 거친다. */
export async function runMockAgent(input: string) {
  const s = useOffice.getState()
  s.setAiStatus('working')
  await sleep(700 + Math.random() * 500)
  try {
    const lower = input.toLowerCase()
    const wantsLight = /조명|불|라이트|light/.test(lower)
    if (wantsLight && /(켜|on|끄|off)/.test(lower)) {
      const on = /(켜|on)/.test(lower) && !/(꺼|끄|off)/.test(lower)
      const id = s.addMessage({
        role: 'ai',
        text: `사무실 조명을 ${on ? '켜' : '끄'}려고 합니다. 장비를 제어하는 작업이라 승인이 필요합니다.`,
        tool: 'set_light',
        pending: { kind: 'light', on, state: 'pending' },
      })
      void id
      useOffice.getState().setAiStatus('approval')
      return
    }
    let r: ToolResult
    if (/서버|server|cpu|메모리/.test(lower)) r = await getServerStatus()
    else if (/요약/.test(lower)) r = summarize(input)
    else if (/보고서|리포트|report/.test(lower)) r = report()
    else if (/코드|code|버그/.test(lower)) r = analyzeCode()
    else {
      r = {
        tool: 'chat',
        text: '요청을 이해했어요. 현재 Mock AI는 "서버 상태 확인", "문서 요약", "보고서 작성", "코드 분석", "조명 켜줘/꺼줘"를 처리할 수 있습니다.',
      }
    }
    useOffice.getState().addMessage({ role: 'ai', text: r.text, tool: r.tool })
    useOffice.getState().setAiStatus('done')
    await sleep(2500)
    if (useOffice.getState().aiStatus === 'done') useOffice.getState().setAiStatus('idle')
  } catch (e) {
    useOffice.getState().addMessage({ role: 'ai', text: `작업 중 오류가 발생했습니다: ${(e as Error).message}` })
    useOffice.getState().setAiStatus('error')
  }
}

export async function resolveApproval(messageId: number, approve: boolean) {
  const s = useOffice.getState()
  const msg = s.messages.find((m) => m.id === messageId)
  if (!msg?.pending || msg.pending.state !== 'pending') return
  if (!approve) {
    s.patchMessage(messageId, { pending: { ...msg.pending, state: 'rejected' } })
    s.addMessage({ role: 'ai', text: '요청을 취소했습니다. 장비는 변경되지 않았습니다.' })
    s.setAiStatus('idle')
    return
  }
  s.patchMessage(messageId, { pending: { ...msg.pending, state: 'approved' } })
  s.setAiStatus('working')
  const on = msg.pending.on
  const result = await s.control('light-main', { power: on })
  const st = useOffice.getState()
  const word = on ? '켰' : '껐'
  if (result === 'CONFIRMED') {
    st.addMessage({ role: 'ai', text: `장비가 확인했습니다. 조명을 ${word}습니다.`, tool: 'set_light' })
    st.setAiStatus('done')
  } else if (result === 'LOCAL') {
    st.addMessage({ role: 'ai', text: `조명을 ${word}습니다. (로컬 시뮬레이션 — 백엔드에 연결되지 않아 실제 장비는 제어되지 않았습니다)`, tool: 'set_light' })
    st.setAiStatus('done')
  } else {
    st.addMessage({ role: 'ai', text: result === 'TIMEOUT' ? '장비 응답이 없어 조명 상태를 확인하지 못했습니다.' : '조명 제어에 실패했습니다.', tool: 'set_light' })
    st.setAiStatus('error')
  }
  setTimeout(() => ['done', 'error'].includes(useOffice.getState().aiStatus) && useOffice.getState().setAiStatus('idle'), 2500)
}
