import { useState } from 'react'
import { hub } from '../hubApi'
import { ErrorNote, errMsg } from '../workspace/kit'
import { FormatIcon } from './FormatIcon'

const FORMATS = [['hwpx', '한글'], ['docx', '워드'], ['xlsx', '엑셀'], ['pptx', '파워포인트'], ['pdf', 'PDF']] as const

/** 결과 문서를 Excel/Word/PPT/PDF 파일로 받기 (서버에서 변환, 저장하지 않음) */
export function ExportButtons({ title, markdown, showLabel = true }: { title: string; markdown: string; showLabel?: boolean }) {
  const [busy, setBusy] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const go = async (f: typeof FORMATS[number][0]) => {
    if (busy) return
    setBusy(f); setErr(null)
    try { await hub.exportDoc(f, title, markdown) } catch (e) { setErr(errMsg(e)) } finally { setBusy(null) }
  }
  return (
    <div className="flex items-center gap-2 flex-wrap" aria-label="파일로 받기">
      {showLabel && <span className="text-xs text-slate-400">파일로 받기</span>}
      {FORMATS.map(([k, l]) => (
        <button key={k} type="button" className="export-chip" disabled={!!busy} title={`${l} 파일로 받기`} onClick={() => void go(k)}>
          <FormatIcon format={k} on size={18} />{busy === k ? '만드는 중…' : l}
        </button>
      ))}
      <ErrorNote message={err} />
    </div>
  )
}
