import { useEffect, useState } from 'react'
import { TemplateFill } from '../home/TemplateFill'
import { hub } from '../hubApi'
import { TemplateUploader } from '../hub/TemplateUploader'
import { templateApi, type FormTarget } from '../hub/templateApi'

interface Source { key: string; label: string; target: FormTarget }

/**
 * 확정된 회의록을 양식에 채워 받는다. 양식은 두 곳에서 온다:
 *  - 내 양식: 각자 이 화면에서 직접 올린다(사람마다 회의록 양식이 다를 수 있다)
 *  - 회사 양식: 회의록 도구(cen-meeting-minutes)에 관리자가 올려 둔 공통 양식
 * 둘 다 있으면 고를 수 있고, 처음에는 내 양식을 쓴다.
 */
export function MinutesFormFill({ title, markdown }: { title: string; markdown: string }) {
  const [sources, setSources] = useState<Source[] | null>(null)
  const [picked, setPicked] = useState<string | null>(null)
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
      if (alive) { setSources(found); setPicked((cur) => (cur && found.some((f) => f.key === cur) ? cur : found[0]?.key ?? null)) }
    })()
    return () => { alive = false }
  }, [version])

  const current = sources?.find((s) => s.key === picked)
  return (
    <div className="minutes-card">
      <h3>양식으로 받기</h3>
      {sources && sources.length > 1 && (
        <div className="tf-formats" role="radiogroup" aria-label="사용할 양식">
          {sources.map((s) => <button key={s.key} type="button" role="radio" aria-checked={s.key === picked} className={s.key === picked ? 'on' : ''} onClick={() => setPicked(s.key)}>{s.label}</button>)}
        </div>
      )}
      {current && <TemplateFill key={String(current.target)} skillId={current.target} title={title} result={markdown} />}
      {sources && !current && <p className="minutes-muted">아직 등록된 회의록 양식이 없습니다. 아래에서 내 양식을 올리면 회의록을 그 양식에 맞춰 받을 수 있습니다.</p>}
      <TemplateUploader skillId="me" title="내 회의록 양식" onChange={() => setVersion((v) => v + 1)} hint="우리 팀이나 고객이 쓰는 회의록 양식이 있다면 올려 두세요. 나만 볼 수 있고, 확정된 회의록을 이 양식에 맞춰 받을 수 있습니다." />
    </div>
  )
}
