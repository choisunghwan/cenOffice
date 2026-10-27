import { useEffect, useRef, useState } from 'react'
import { askConfirm } from '../ui/confirm'
import { Btn, Card, ErrorNote } from '../workspace/kit'
import { templateApi, type TemplateInfo } from './templateApi'

/** 제작자용: 이 도구의 결과물 양식(한글 hwpx)을 등록한다. 등록하면 사용하는 사람이 결과를 이 양식에 채워 받는다. */
export function TemplateUploader({ skillId }: { skillId: number }) {
  const [info, setInfo] = useState<TemplateInfo | null | undefined>(undefined) // undefined: 불러오는 중
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const input = useRef<HTMLInputElement>(null)

  useEffect(() => {
    let alive = true
    templateApi.info(skillId).then((i) => alive && setInfo(i)).catch((e) => { if (alive) { setInfo(null); setErr((e as Error).message) } })
    return () => { alive = false }
  }, [skillId])

  const upload = async (file: File | undefined) => {
    if (!file || busy) return
    setBusy(true); setErr(null)
    try { setInfo(await templateApi.put(skillId, file)) } catch (e) { setErr((e as Error).message) } finally { setBusy(false); if (input.current) input.current.value = '' }
  }
  const remove = async () => {
    if (busy || !await askConfirm({ title: '양식을 삭제할까요?', message: '삭제하면 이 도구의 결과는 다시 글로만 나옵니다.', okText: '삭제', danger: true })) return
    setBusy(true); setErr(null)
    try { await templateApi.remove(skillId); setInfo(null) } catch (e) { setErr((e as Error).message) } finally { setBusy(false) }
  }

  return (
    <Card title="결과물 양식 (한글 hwpx)">
      <p className="text-sm text-slate-400 mb-3">양식을 등록하면, 이 도구를 쓰는 사람이 결과를 이 양식에 맞춰 채운 파일로 받습니다. 본문에 <code>{'{{항목}}'}</code> 자리표시자를 넣거나, 표에 ‘라벨 칸 + 빈 칸’을 만든 <b>.hwpx</b> 파일을 올려 주세요.</p>
      <input ref={input} type="file" accept=".hwpx" hidden onChange={(e) => void upload(e.target.files?.[0])} />
      {info === undefined ? <p role="status" className="text-sm text-slate-400">불러오는 중…</p> : info ? (
        <div className="space-y-3">
          <p className="text-sm"><b>{info.fileName}</b> · 채울 항목 {info.fields.length}개</p>
          <div className="flex flex-wrap gap-1.5">{info.fields.map((f) => <span key={f.key} className="text-xs rounded-full px-2.5 py-1 bg-sky-500/15 border border-sky-500/30" title={f.kind === 'placeholder' ? '본문 자리표시자' : '표의 빈 칸'}>{f.label}</span>)}</div>
          <div className="flex gap-2"><Btn small tone="ghost" disabled={busy} onClick={() => input.current?.click()}>{busy ? '올리는 중…' : '양식 교체'}</Btn><Btn small tone="ghost" disabled={busy} onClick={() => void remove()}>삭제</Btn></div>
        </div>
      ) : <Btn disabled={busy} onClick={() => input.current?.click()}>{busy ? '올리는 중…' : '양식 올리기 (.hwpx)'}</Btn>}
      <ErrorNote message={err} />
    </Card>
  )
}
