import { useEffect, useState } from 'react'
import { TemplateFill } from '../home/TemplateFill'
import { ExportButtons } from '../hub/ExportButtons'
import { hub } from '../hubApi'
import { TemplateUploader } from '../hub/TemplateUploader'
import { templateApi, type FormTarget } from '../hub/templateApi'

interface Source { key: string; label: string; target: FormTarget }
type Tab = 'basic' | 'me' | 'company' | 'upload'

/**
 * 확정된 회의록을 파일로 내보내는 곳. 한 카드 안에서 탭으로 고른다.
 *  - 기본 문서: 양식 없이 한글·워드·엑셀·파워포인트·PDF 로 바로 받기
 *  - 내 양식: 내가 올린 회의록 양식에 맞춰 받기(사람마다 양식이 다를 수 있다)
 *  - 회사 양식: 회의록 도구(cen-meeting-minutes)에 관리자가 올려 둔 공통 양식에 맞춰 받기
 *  - ＋ 양식 올리기: 내 회의록 양식 등록·교체·삭제
 * 처음에는 쓸 수 있는 양식이 있으면 내 양식 → 회사 양식 순으로, 없으면 기본 문서를 보여 준다.
 */
export function MinutesFormFill({ title, markdown }: { title: string; markdown: string }) {
  const [sources, setSources] = useState<Source[] | null>(null)
  const [tab, setTab] = useState<Tab | null>(null)
  const [version, setVersion] = useState(0) // 내 양식을 올리거나 지우면 다시 확인한다

  useEffect(() => {
    let alive = true
    ;(async () => {
      const found: Source[] = []
      try { if ((await templateApi.slots('me')).some((s) => s.template)) found.push({ key: 'me', label: '내 양식', target: 'me' }) } catch { /* 내 양식을 못 읽어도 나머지는 보여 준다 */ }
      try {
        const page = await hub.skills({ q: 'cen-meeting-minutes', size: 5 })
        const skill = page.items.find((s) => s.name === 'cen-meeting-minutes')
        if (skill && (await templateApi.slots(skill.id)).some((s) => s.template)) found.push({ key: 'company', label: '회사 양식', target: skill.id })
      } catch { /* 회사 양식을 못 찾으면 없는 것으로 본다 */ }
      if (!alive) return
      setSources(found)
      setTab((cur) => {
        if (cur === 'upload' || cur === 'basic') return cur
        if (cur && found.some((f) => f.key === cur)) return cur
        return (found[0]?.key as Tab | undefined) ?? 'basic'
      })
    })()
    return () => { alive = false }
  }, [version])

  if (!sources || !tab) return <div className="minutes-card"><h3>회의록 내보내기</h3><p className="minutes-muted" role="status">불러오는 중…</p></div>

  const tabs: { key: Tab; label: string }[] = [
    ...sources.map((s) => ({ key: s.key as Tab, label: s.label })),
    { key: 'basic', label: '기본 문서' },
    { key: 'upload', label: '＋ 내 양식 올리기' },
  ]
  const current = sources.find((s) => s.key === tab)

  return (
    <div className="minutes-card minutes-export">
      <h3>회의록 내보내기</h3>
      <div className="export-tabs" role="tablist" aria-label="내보내기 방법">
        {tabs.map((t) => <button key={t.key} type="button" role="tab" aria-selected={t.key === tab} className={t.key === tab ? 'on' : ''} onClick={() => setTab(t.key)}>{t.label}</button>)}
      </div>
      <div className="export-body" role="tabpanel">
        {tab === 'basic' && (
          <>
            <p className="minutes-muted">양식 없이 회의록을 그대로 문서로 받습니다.</p>
            <ExportButtons showLabel={false} title={title} markdown={markdown} />
          </>
        )}
        {current && <TemplateFill key={String(current.target)} skillId={current.target} title={title} result={markdown} />}
        {tab === 'upload' && (
          <TemplateUploader skillId="me" title="내 회의록 양식" onChange={() => { setTab('me'); setVersion((v) => v + 1) }}
            hint="우리 팀이나 고객이 쓰는 회의록 양식이 있다면 올려 두세요. 나만 볼 수 있고, 올리면 바로 이 양식에 맞춰 회의록을 받을 수 있습니다." />
        )}
      </div>
    </div>
  )
}
