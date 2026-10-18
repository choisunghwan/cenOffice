import { useState } from 'react'
import { hub } from '../hubApi'
import { Btn, ErrorNote, errMsg } from '../workspace/kit'

const FORMATS = [['xlsx', 'Excel'], ['docx', 'Word'], ['pptx', 'PPT'], ['pdf', 'PDF'], ['hwpx', '한글(HWPX)']] as const

/** 결과 문서를 Excel/Word/PPT/PDF 파일로 받기 (서버에서 변환, 저장하지 않음) */
export function ExportButtons({ title, markdown }: { title: string; markdown: string }) {
  const [busy, setBusy] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const go = async (f: typeof FORMATS[number][0]) => {
    if (busy) return
    setBusy(f); setErr(null)
    try { await hub.exportDoc(f, title, markdown) } catch (e) { setErr(errMsg(e)) } finally { setBusy(null) }
  }
  return (
    <div className="flex items-center gap-2 flex-wrap" aria-label="파일로 받기">
      <span className="text-xs text-slate-400">파일로 받기</span>
      {FORMATS.map(([k, l]) => <Btn key={k} small tone="ghost" disabled={!!busy} onClick={() => void go(k)}>{busy === k ? '만드는 중…' : l}</Btn>)}
      <ErrorNote message={err} />
    </div>
  )
}
