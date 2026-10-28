import { hub } from '../hubApi'
import { templateApi, type FormTarget } from '../hub/templateApi'
import { minutesApi } from '../meeting/minutesApi'

export type DlFormat = 'hwpx' | 'docx' | 'xlsx' | 'pptx' | 'pdf'
/** me: 내 양식 · company: 회사 양식 · basic: 양식 없이 기본 문서 */
export interface DlResult { how: 'me' | 'company' | 'basic'; format: DlFormat; missing: string[] }

export const DL_FORMATS: { key: DlFormat; label: string }[] = [
  { key: 'hwpx', label: '한글' }, { key: 'docx', label: '워드' }, { key: 'xlsx', label: '엑셀' }, { key: 'pptx', label: '파워포인트' }, { key: 'pdf', label: 'PDF' },
]
export const dlLabel = (f: DlFormat) => DL_FORMATS.find((x) => x.key === f)?.label ?? f

/** 회의록 도구(cen-meeting-minutes)에 관리자가 올려 둔 회사 양식의 대상. 없으면 null */
async function companyTarget(): Promise<FormTarget | null> {
  try {
    const page = await hub.skills({ q: 'cen-meeting-minutes', size: 5 })
    return page.items.find((s) => s.name === 'cen-meeting-minutes')?.id ?? null
  } catch { return null }
}

/** 이 대상(내 양식/회사 양식)에서 쓸 형식을 찾는다. 형식을 정했으면 그 형식, 아니면 올려 둔 첫 형식. PDF 는 양식이 없다. */
async function pickForm(target: FormTarget, want: DlFormat | null): Promise<DlFormat | null> {
  if (want === 'pdf') return null
  try {
    const slots = await templateApi.slots(target)
    const hit = slots.find((s) => s.template && (want === null || s.format === want))
    return (hit?.format as DlFormat | undefined) ?? null
  } catch { return null }
}

/**
 * 확정된 회의록을 내려받는다. 양식이 있으면 내 양식 → 회사 양식 순으로 그 양식에 채워 받고, 없으면 기본 문서로 받는다.
 * 양식에서 근거를 못 찾은 항목은 채팅에서 지어내거나 AI 로 채우지 않고 비워 둔 채, 어떤 항목인지만 알려 준다.
 */
export async function downloadMinutes(meetingId: string, want: DlFormat | null): Promise<DlResult> {
  const m = await minutesApi.get(meetingId)
  if (m.status !== 'CONFIRMED') throw new Error('아직 확정되지 않은 회의록입니다. 확정한 뒤에 내려받을 수 있어요.')
  const title = `${m.title} 회의록`
  const company = await companyTarget()
  const candidates: { how: 'me' | 'company'; target: FormTarget }[] = [{ how: 'me', target: 'me' }]
  if (company !== null) candidates.push({ how: 'company', target: company })
  for (const c of candidates) {
    const format = await pickForm(c.target, want)
    if (!format) continue
    const plan = await templateApi.plan(c.target, m.markdown, format)
    const values: Record<string, string> = {}
    for (const i of plan.items) if (i.value && i.source !== 'AI') values[i.key] = i.value // AI 가 뽑은 값은 확인 없이 넣지 않는다
    await templateApi.render(c.target, values, title, format)
    return { how: c.how, format, missing: plan.items.filter((i) => !values[i.key]).map((i) => i.label) }
  }
  const format = want ?? 'hwpx'
  await hub.exportDoc(format, title, m.markdown)
  return { how: 'basic', format, missing: [] }
}
