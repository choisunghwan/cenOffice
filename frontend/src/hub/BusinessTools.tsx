import { DocumentContent } from './DocumentContent'
import { useEffect, useRef, useState } from 'react'
import { hub, type HubExecution } from '../hubApi'
import { ExportButtons } from './ExportButtons'
import { Btn, Card, ErrorNote, errMsg } from '../workspace/kit'

/** Actual authenticated file execution for bundled tools, not a separate demo catalogue. */
export function BusinessTools({ skillId, kind }: { skillId: number; kind: 'fp' | 'proposal' }) {
  const [files, setFiles] = useState<File[]>([])
  const [instructions, setInstructions] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [execution, setExecution] = useState<HubExecution | null>(null)
  const flight = useRef(false)
  const running = !!execution && !['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(execution.status)
  useEffect(() => {
    if (!execution || !running) return
    let active = true, attempts = 0
    const poll = async () => {
      try { const next = await hub.execution(execution.id); if (active) { setExecution(next); setError(null) } }
      catch (e) { if (active) setError(errMsg(e)) }
      if (++attempts >= 150) { clearInterval(timer); if (active) setError('결과 확인 시간이 초과됐습니다. 실행 이력에서 확인하세요.') }
    }
    const timer = setInterval(() => { void poll() }, 800)
    return () => { active = false; clearInterval(timer) }
  }, [execution?.id, running])
  const run = async () => {
    if (flight.current || running || !files.length) return
    flight.current = true; setBusy(true); setError(null)
    try { setExecution(await hub.executeDocuments(skillId, files, instructions)) }
    catch (e) { setError(errMsg(e)) } finally { flight.current = false; setBusy(false) }
  }
  const download = () => {
    if (!execution?.result) return
    const csv = kind === 'fp'
    const content = csv ? '\uFEFF' + execution.result.split('\n').filter((line) => line.startsWith('|') && !/^\|[- :|]+\|$/.test(line)).map((line) => line.slice(1, -1).split('|').map((cell) => { const text = cell.trim(); return '"' + (/^[=+@-]/.test(text) ? "'" : '') + text.replaceAll('"', '""') + '"' }).join(',')).join('\r\n') : execution.result
    const url = URL.createObjectURL(new Blob([content], { type: csv ? 'text/csv;charset=utf-8' : 'text/markdown;charset=utf-8' }))
    const link = document.createElement('a'); link.href = url; link.download = `${kind}-result.${csv ? 'csv' : 'md'}`; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
  return <div className="builtin-workflow"><Card title="1. 자료를 넣어주세요">
    <p className="text-sm text-slate-400 mb-4">{kind === 'fp' ? '기능 목록이 있는 요구사항 엑셀을 선택하세요.' : '프로젝트 개요와 요구사항을 선택하세요.'} 내장 규칙이 원문을 바탕으로 검토용 초안을 만듭니다.</p>
    <label className="block text-sm mb-2" htmlFor="builtin-files">분석할 자료</label><input id="builtin-files" type="file" multiple accept={kind === 'fp' ? '.xlsx,.txt,.md' : '.xlsx,.docx,.txt,.md'} disabled={busy || running} className="builtin-file-input" onChange={(e) => {
      const picked = Array.from(e.target.files ?? [])
      const extensions = kind === 'fp' ? ['.xlsx', '.txt', '.md'] : ['.xlsx', '.docx', '.txt', '.md']
      if (picked.length > 3 || picked.reduce((sum, f) => sum + f.size, 0) > 5 * 1024 * 1024 || picked.some((f) => !extensions.some((ext) => f.name.toLowerCase().endsWith(ext)))) { setError('지원 형식의 파일을 최대 3개, 합계 5MB 이하로 선택하세요.'); setFiles([]); e.target.value = ''; return }
      setFiles(picked); setError(null); setExecution(null)
    }} />
    <p className="text-xs text-slate-400 mt-3">최대 3개·합계 5MB·텍스트 80행/12,000자. PDF·이미지·소스 ZIP은 지원하지 않습니다. 수식은 계산하지 않습니다.</p>
    {!!files.length && <ul className="text-sm text-sky-200 mt-4 space-y-2">{files.map((f, i) => <li key={i}>▤ {f.name} <span className="text-xs text-slate-400">{Math.ceil(f.size / 1024)} KB</span></li>)}</ul>}
    <details className="hub-advanced mt-5"><summary>검토 메모 남기기 · 선택</summary><textarea aria-label="검토 메모" rows={3} maxLength={2000} value={instructions} onChange={(e) => setInstructions(e.target.value)} className="w-full rounded-lg p-3 bg-slate-950/40 mt-3" placeholder="초안 작성 후 사람이 확인할 사항을 적어주세요. 자유문 지시를 실행하는 기능은 아닙니다." /></details>
  </Card><Card title="2. 초안을 만들어보세요"><p className="text-sm text-slate-400 mb-4">파일 원본은 보관하지 않습니다. 추출한 텍스트와 결과는 본인의 실행 이력에 저장되며 관리자도 조회할 수 있습니다. 외부 LLM으로 전송하지 않습니다.</p><Btn disabled={!files.length || busy || running} onClick={run}>{busy || running ? '자료를 처리하고 있어요…' : kind === 'fp' ? '기능 분류표 만들기' : '제안서 초안 만들기'}</Btn><ErrorNote message={error} /></Card>
    {execution && <Card title="3. 결과를 확인하세요" action={execution.result && <div className="flex gap-2 items-center flex-wrap"><ExportButtons title={kind === 'fp' ? 'FP 산정 검토표' : '제안서 구성 초안'} markdown={execution.result} /><Btn small tone="ghost" onClick={download}>{kind === 'fp' ? 'CSV' : 'Markdown'}</Btn></div>}><p role="status" className="text-sm text-sky-200 mb-4">{running ? '처리 중' : execution.status === 'SUCCEEDED' ? '초안 생성 완료 · 규칙 기반 결과이므로 검토가 필요합니다.' : '처리하지 못했습니다.'}</p>{execution.result && <div className="builtin-result"><DocumentContent text={execution.result} /></div>}{execution.error && <p role="alert" className="text-red-300">{execution.error}</p>}</Card>}
  </div>
}
