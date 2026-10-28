import type { Turn } from './AssistantBox'

type State = 'done' | 'active' | 'ready' | 'wait' | 'fail'
interface Step { label: string; state: State; note?: string }
type Bot = Extract<Turn, { role: 'assistant' }>

/**
 * 한 요청이 지금 어느 단계인지 단계 막대로 보여 준다.
 * done: 끝남 · active: 처리 중 · ready: 내가 확인할 차례 · wait: 아직 · fail: 안 됨.
 * 단계는 저장된 대화(응답과 카드 상태)에서 매번 계산하므로 따로 저장하지 않고, 지난 대화를 열어도 같은 모양으로 보인다.
 */
export function stepsFor(t: Bot): Step[] {
  const understood: Step = { label: '요청 이해', state: 'done', note: t.reply.ai ? 'AI' : '규칙' }
  const i = t.reply.intent
  const confirmStep = (draftLabel: string): Step[] => [
    understood,
    { label: draftLabel, state: 'done' },
    { label: '내 확인', state: t.state === 'cancelled' ? 'fail' : t.state === 'done' ? 'done' : t.state === 'saving' ? 'active' : 'ready', note: t.state === 'cancelled' ? '그만둠' : t.state === 'saving' ? '반영 중' : undefined },
    { label: '반영', state: t.error ? 'fail' : t.state === 'done' ? 'done' : 'wait', note: t.error ? '실패' : undefined },
  ]
  switch (i) {
    case 'CREATE_EVENT': return confirmStep('일정 초안')
    case 'BOOK_ROOM': return confirmStep('예약 초안')
    case 'DELETE_ITEM': return confirmStep('대상 확인')
    case 'START_MEETING': return confirmStep('회의 정보')
    case 'JOIN_MEETING': return confirmStep('참여 코드')
    case 'MOVE_EVENT': return confirmStep('변경안')
    case 'USE_TOOL': {
      const hasOutput = t.output !== undefined
      const run: Step = { label: '도구 실행', state: t.error ? 'fail' : hasOutput ? 'done' : t.state === 'saving' ? 'active' : 'ready', note: t.error ? '실패' : t.state === 'saving' ? '실행 중' : hasOutput ? undefined : '실행 대기' }
      const steps: Step[] = [understood, { label: '도구 선택', state: 'done', note: t.tool?.title }, run]
      if (t.tool?.templateFormat) steps.push({ label: '양식 채우기', state: hasOutput ? 'ready' : 'wait', note: hasOutput ? '미리보기 확인' : undefined }, { label: '파일 받기', state: 'wait' })
      else steps.push({ label: '결과 확인', state: hasOutput ? 'ready' : 'wait' })
      return steps
    }
    case 'ANSWER': return [understood, { label: '내 업무 정보 확인', state: 'done', note: t.reply.references?.length ? `${t.reply.references.length}건` : undefined }, { label: 'AI 답변', state: 'done' }]
    case 'CLARIFY': return [understood, { label: '추가 정보', state: 'ready', note: '더 알려 주세요' }]
    case 'UNSUPPORTED': return [understood, { label: '처리', state: 'fail', note: '할 수 없는 요청' }]
    default: return [understood, { label: '정보 조회', state: 'done' }, { label: '답변', state: 'done' }]
  }
}

/** 요청을 보내고 서버 답을 기다리는 동안의 단계 */
const PENDING: Step[] = [{ label: '요청 이해', state: 'active', note: '해석 중' }, { label: '처리 선택', state: 'wait' }, { label: '실행', state: 'wait' }]

export function Flow({ turn }: { turn?: Bot }) {
  const steps = turn ? stepsFor(turn) : PENDING
  return (
    <ol className="ha-flow" aria-label="처리 단계">
      {steps.map((s, idx) => (
        <li key={idx} className={s.state} aria-current={s.state === 'active' || s.state === 'ready' ? 'step' : undefined}>
          <span className="ha-flow-dot" aria-hidden="true">{s.state === 'done' ? '✓' : s.state === 'fail' ? '!' : ''}</span>
          <span className="ha-flow-label">{s.label}{s.note && <small>{s.note}</small>}</span>
        </li>
      ))}
    </ol>
  )
}
