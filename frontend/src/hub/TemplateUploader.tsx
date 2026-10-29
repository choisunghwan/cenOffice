import { useEffect, useRef, useState } from 'react'
import { askConfirm } from '../common/confirm'
import { Btn, Card, ErrorNote } from '../workspace/kit'
import { FormatIcon } from './FormatIcon'
import { templateApi, type FormatSlot, type FormTarget } from './templateApi'

/** 제작자용: 이 도구의 결과물 양식을 형식별(한글·워드·엑셀·파워포인트)로 올린다. 올린 형식은 켜지고, 안 올린 형식은 흐리게 보인다. */
export function TemplateUploader({ skillId, title = '결과물 양식', hint, onChange }: { skillId: FormTarget; title?: string; hint?: string; onChange?: () => void }) {
  const [slots, setSlots] = useState<FormatSlot[] | undefined>(undefined) // undefined: 불러오는 중
  const [picked, setPicked] = useState<string | null>(null) // 자세히 보는 형식
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const target = useRef('hwpx') // 파일 선택창이 열린 형식
  const input = useRef<HTMLInputElement>(null)

  const load = () => templateApi.slots(skillId).then(setSlots).catch((e) => { setSlots([]); setErr((e as Error).message) })
  useEffect(() => { let alive = true; templateApi.slots(skillId).then((r) => alive && setSlots(r)).catch((e) => { if (alive) { setSlots([]); setErr((e as Error).message) } }); return () => { alive = false } }, [skillId])

  const choose = (s: FormatSlot) => {
    if (busy || !s.supported) return
    setErr(null)
    if (s.template) { setPicked(picked === s.format ? null : s.format); return }
    pickFile(s.format)
  }
  const pickFile = (format: string) => { target.current = format; if (input.current) { input.current.accept = `.${format}`; input.current.click() } }

  const upload = async (file: File | undefined) => {
    if (!file || busy) return
    setBusy(true); setErr(null)
    try { await templateApi.put(skillId, file); await load(); setPicked(target.current); onChange?.() } catch (e) { setErr((e as Error).message) } finally { setBusy(false); if (input.current) input.current.value = '' }
  }
  const remove = async (s: FormatSlot) => {
    if (busy || !await askConfirm({ title: `${s.label} 양식을 삭제할까요?`, message: '삭제하면 이 형식으로는 결과를 받을 수 없습니다.', okText: '삭제', danger: true })) return
    setBusy(true); setErr(null)
    try { await templateApi.remove(skillId, s.format); await load(); setPicked(null); onChange?.() } catch (e) { setErr((e as Error).message) } finally { setBusy(false) }
  }

  const detail = slots?.find((s) => s.format === picked && s.template)
  return (
    <Card title={title}>
      <p className="text-sm text-slate-400 mb-4">{hint ?? '정해진 양식이 있다면 형식에 맞는 아이콘을 눌러 올려 두세요. 이 도구를 쓰는 사람이 결과를 그 양식에 맞춰 채운 파일로 받습니다.'} 본문에 <code>{'{{항목}}'}</code> 자리표시자를 넣거나, 표에 ‘항목 이름 칸 + 빈 칸’을 만들어 두면 됩니다.</p>
      <input ref={input} type="file" hidden onChange={(e) => void upload(e.target.files?.[0])} />
      {slots === undefined ? <p role="status" className="text-sm text-slate-400">불러오는 중…</p> : (
        <div className="tpl-slots" role="group" aria-label="양식 형식">
          {slots.map((s) => {
            const on = !!s.template
            const state = on ? '등록됨' : s.supported ? '올리기' : '준비 중'
            return (
              <button key={s.format} type="button" className={`tpl-slot${on ? ' on' : ''}${picked === s.format ? ' picked' : ''}`} disabled={busy || !s.supported}
                aria-pressed={on ? picked === s.format : undefined} title={s.supported ? (on ? `${s.label} 양식 보기` : `${s.label} 양식 올리기 (.${s.format})`) : `${s.label} 양식은 곧 지원합니다`} onClick={() => choose(s)}>
                <FormatIcon format={s.format} on={on} />
                <strong>{s.label}</strong>
                <small>{busy && target.current === s.format && !on ? '올리는 중…' : state}</small>
              </button>
            )
          })}
        </div>
      )}
      {detail?.template && (
        <div className="tpl-detail space-y-3">
          <p className="text-sm"><b>{detail.template.fileName}</b> · 채울 항목 {detail.template.fields.length}개</p>
          <div className="flex flex-wrap gap-1.5">{detail.template.fields.map((f) => <span key={f.key} className="text-xs rounded-full px-2.5 py-1 bg-sky-500/15 border border-sky-500/30" title={f.kind === 'placeholder' ? '본문 자리표시자' : '표의 빈 칸'}>{f.label}</span>)}</div>
          <div className="flex gap-2"><Btn small tone="ghost" disabled={busy} onClick={() => pickFile(detail.format)}>{busy ? '올리는 중…' : '양식 교체'}</Btn><Btn small tone="ghost" disabled={busy} onClick={() => void remove(detail)}>삭제</Btn></div>
        </div>
      )}
      <ErrorNote message={err} />
    </Card>
  )
}
