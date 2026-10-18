import { DocumentContent } from './DocumentContent'
import { useEffect, useRef, useState } from 'react'
import { hub, type HubExecution } from '../hubApi'
import { ExportButtons } from './ExportButtons'
import { Btn, Card, ErrorNote, Textarea, errMsg, useLoad } from '../workspace/kit'

export const TEXT_TOOLS: Record<string, { title: string; label: string; placeholder: string; sample: string }> = {
  'cen-log-analyzer': {
    title: '오류 로그 분석', label: '오류 로그 또는 스택 트레이스를 붙여 넣으세요',
    placeholder: '2026-10-18 09:12:31 ERROR ... Caused by: ...',
    sample: `2026-10-18 09:12:31 ERROR [http-nio-8080-exec-3] c.c.order.OrderService - 주문 처리 실패
org.springframework.dao.DataIntegrityViolationException: could not execute statement; SQL [n/a]; constraint [UK_ORDER_NO]
\tat org.springframework.orm.jpa.vendor.HibernateJpaDialect.convertHibernateAccessException(HibernateJpaDialect.java:276)
\tat com.cen.order.OrderService.create(OrderService.java:88)
\tat com.cen.order.OrderController.create(OrderController.java:41)
Caused by: org.hibernate.exception.ConstraintViolationException: could not execute statement
Caused by: org.postgresql.util.PSQLException: ERROR: duplicate key value violates unique constraint "uk_order_no"`,
  },
  'cen-sql-reviewer': {
    title: 'SQL 점검', label: '점검할 SQL 을 붙여 넣으세요 (여러 문장은 ; 로 구분)',
    placeholder: 'SELECT * FROM orders WHERE ...;',
    sample: `SELECT * FROM orders WHERE customer_name LIKE '%kim';
UPDATE employees SET salary = salary * 1.1;
SELECT o.id FROM orders o WHERE UPPER(o.status) = 'DONE';
DELETE FROM audit_log;`,
  },
  'cen-meeting-minutes': {
    title: '회의록 정리', label: '회의 메모를 붙여 넣으세요',
    placeholder: '회의 제목\n참석: ...\n- 결정: ...\n- 김대리 다음주 월요일까지 ...',
    sample: `주간 개발 회의
일시: 2026-10-16
참석: 김대리, 박과장, 이사원
- 로그인 화면 개선안 확정
- 김대리 다음주 월요일까지 견적서 작성
- 담당: 이사원 금요일까지 테스트 시나리오 공유
- 배포 일정이 아직 미정이라 확인 필요
- 고객사 요청 사항은 다음 회의에서 논의`,
  },
  'cen-weekly-report': {
    title: '주간 업무보고 정리', label: '이번 주 한 일과 다음 주 계획을 붙여 넣으세요',
    placeholder: '[완료]\n- ...\n[진행]\n- ... 60%\n[예정]\n- ...\n[이슈]\n- ...',
    sample: `[완료]
- 로그인 화면 개선 배포
- 고객 목록 조회 속도 개선
[진행]
- 보고서 화면 개발 60%
- 권한 체계 정리
[예정]
- 통합 테스트 시작
[이슈]
- 외부 API 응답 지연으로 연동 일정 불확실`,
  },
  'cen-test-case-writer': {
    title: '테스트 케이스 초안', label: '요구사항을 한 줄에 하나씩 입력하세요',
    placeholder: '사용자는 게시글을 등록할 수 있다',
    sample: `사용자는 아이디와 비밀번호로 로그인할 수 있다
사용자는 게시글을 등록하고 수정할 수 있다
관리자는 게시글 목록을 검색하고 삭제할 수 있다
사용자는 첨부 파일을 업로드할 수 있다`,
  },
}

/** 입력 → 결과 한 화면으로 쓰는 내장 텍스트 도구 (로컬 규칙 기반, 외부 전송 없음) */
export function TextTool({ skillId, name }: { skillId: number; name: string }) {
  const cfg = TEXT_TOOLS[name]
  const [input, setInput] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [execution, setExecution] = useState<HubExecution | null>(null)
  const flight = useRef(false)
  const ai = useLoad(() => hub.aiEngine().catch(() => null), [])
  const [engine, setEngine] = useState<'rule' | 'ai'>('rule')
  const aiInfo = ai.data && ai.data.available ? ai.data : null
  const running = !!execution && !['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(execution.status)

  useEffect(() => {
    if (!execution || !running) return
    let active = true, attempts = 0
    const timer = setInterval(() => {
      hub.execution(execution.id).then((n) => { if (active) { setExecution(n); setError(null) } }).catch((e) => { if (active) setError(errMsg(e)) })
      if (++attempts >= 150) { clearInterval(timer); if (active) setError('결과 확인 시간이 초과됐습니다. 잠시 후 다시 시도하세요.') }
    }, 600)
    return () => { active = false; clearInterval(timer) }
  }, [execution?.id, running])

  if (!cfg) return null
  const run = async () => {
    if (flight.current || !input.trim()) return
    flight.current = true; setBusy(true); setError(null)
    try { setExecution(await hub.execute({ skillId, input, engine: aiInfo ? engine : 'rule' })) } catch (e) { setError(errMsg(e)) } finally { flight.current = false; setBusy(false) }
  }
  const download = () => {
    if (!execution?.result) return
    const url = URL.createObjectURL(new Blob([execution.result], { type: 'text/markdown;charset=utf-8' }))
    const a = document.createElement('a')
    a.href = url; a.download = `${cfg.title}.md`; a.click()
    URL.revokeObjectURL(url)
  }

  return (
    <div className="space-y-4">
      <Card title={`1. ${cfg.label}`}>
        <Textarea aria-label="입력" rows={9} value={input} maxLength={15000} placeholder={cfg.placeholder} onChange={(e) => setInput(e.target.value)} />
        {aiInfo && (
          <fieldset className="mt-3 text-sm" aria-label="처리 방식">
            <div className="flex gap-4 flex-wrap">
              <label className="flex items-center gap-2"><input type="radio" name={`engine-${skillId}`} checked={engine === 'rule'} onChange={() => setEngine('rule')} /> 규칙 기반 <span className="text-xs text-slate-400">(빠르고 외부 전송 없음)</span></label>
              <label className="flex items-center gap-2"><input type="radio" name={`engine-${skillId}`} checked={engine === 'ai'} onChange={() => setEngine('ai')} /> AI 모델 <span className="text-xs text-slate-400">(더 자연스러운 결과)</span></label>
            </div>
            {engine === 'ai' && <p role="note" className="mt-2 text-xs text-amber-600">{aiInfo.notice}</p>}
          </fieldset>
        )}
        <div className="flex items-center gap-2 mt-3 flex-wrap">
          <Btn disabled={busy || running || !input.trim()} onClick={() => void run()}>{busy || running ? '처리 중…' : '정리하기'}</Btn>
          <Btn tone="ghost" disabled={busy || running} onClick={() => { setInput(cfg.sample); setExecution(null) }}>예시 입력 넣기</Btn>
          <span className="text-xs text-slate-400">{input.length.toLocaleString()} / 15,000자 {engine === 'ai' && aiInfo ? '· AI 모델로 처리합니다.' : '· 서버 안에서만 처리되며 외부로 전송되지 않습니다.'}</span>
        </div>
        <ErrorNote message={error} />
      </Card>
      {execution && (
        <Card title="2. 결과를 확인하세요" action={execution.result && <div className="flex gap-2 items-center flex-wrap"><ExportButtons title={cfg.title} markdown={execution.result} /><Btn small tone="ghost" onClick={download}>Markdown</Btn></div>}>
          <p role="status" className="text-sm text-sky-200 mb-4">{running ? '처리 중' : execution.status === 'SUCCEEDED' ? (execution.runtimeKind === 'llm' ? '초안 생성 완료 · AI 가 만든 결과이므로 사실과 수치를 검토하세요.' : '초안 생성 완료 · 규칙 기반 결과이므로 검토가 필요합니다.') : '처리하지 못했습니다.'}</p>
          {execution.result && <div className="builtin-result"><DocumentContent text={execution.result} /></div>}
          {execution.error && <p role="alert" className="text-red-300">{execution.error}</p>}
        </Card>
      )}
    </div>
  )
}
