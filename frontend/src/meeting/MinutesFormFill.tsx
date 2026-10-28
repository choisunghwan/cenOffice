import { useEffect, useState } from 'react'
import { TemplateFill } from '../home/TemplateFill'
import { hub } from '../hubApi'
import { templateApi } from '../hub/templateApi'

/** 회의록 도구(cen-meeting-minutes)에 회사 회의록 양식이 등록되어 있으면, 확정된 회의록을 그 양식에 채워 받는다. 양식이 없으면 아무것도 보이지 않는다. */
export function MinutesFormFill({ title, markdown }: { title: string; markdown: string }) {
  const [skillId, setSkillId] = useState<number | null>(null)

  useEffect(() => {
    let alive = true
    hub.skills({ q: 'cen-meeting-minutes', size: 5 })
      .then(async (page) => {
        const skill = page.items.find((s) => s.name === 'cen-meeting-minutes')
        if (!skill) return
        const slots = await templateApi.slots(skill.id)
        if (alive && slots.some((s) => s.template)) setSkillId(skill.id)
      })
      .catch(() => { /* 양식을 못 찾으면 일반 파일 받기만 보여 준다 */ })
    return () => { alive = false }
  }, [])

  if (skillId === null) return null
  return <div className="minutes-card"><h3>회사 양식으로 받기</h3><TemplateFill skillId={skillId} title={title} result={markdown} /></div>
}
