import { DocumentContent } from './DocumentContent'
import { useEffect, useRef, useState } from 'react'
import { hub } from '../hubApi'
import { useLoad } from '../workspace/kit'

/** Only shows stored examples; missing examples are never replaced with invented results. */
export function ToolPreview({ id, kind, name }: { id: number; kind: 'skill' | 'agent'; name: string }) {
  const ref = useRef<HTMLDivElement>(null)
  const [visible, setVisible] = useState(false)
  useEffect(() => {
    const observer = new IntersectionObserver(([entry]) => {
      if (entry.isIntersecting) { setVisible(true); observer.disconnect() }
    }, { rootMargin: '150px' })
    if (ref.current) observer.observe(ref.current)
    return () => observer.disconnect()
  }, [])
  const preview = useLoad(async () => {
    if (kind === 'skill') {
      const detail = await hub.skill(id)
      return { example: detail.current?.example, description: detail.summary.description, items: [] as string[] }
    }
    const detail = await hub.agent(id)
    return { example: undefined, description: detail.summary.duties || detail.summary.description, items: detail.skills.map((s) => s.name) }
  }, [id, kind], visible)
  const data = preview.data
  return <div ref={ref} className={`tool-preview tool-preview-${kind}`}>
    <div className="tool-preview-label"><span>{data?.example ? '등록된 사용 예시' : '도구 소개'}</span><span aria-hidden="true">↗</span></div>
    <div className="tool-preview-sheet">
      <div className="tool-preview-sheet-heading"><span aria-hidden="true">{kind === 'skill' ? '▤' : '✧'}</span><strong>{name}</strong></div>
      {preview.loading || !visible ? <div className="tool-preview-loading" role="status">미리보기 불러오는 중…</div> : preview.error ? <p>미리보기를 불러오지 못했습니다. 자세히 보기에서 다시 확인해 주세요.</p> : data?.example ? <DocumentContent text={data.example.slice(0, 700)} /> : <><p>{data?.description}</p>{!!data?.items.length && <div className="tool-preview-chips">{data.items.slice(0, 3).map((item, index) => <span key={index}>{item}</span>)}</div>}<small>결과 예시가 아직 등록되지 않았어요</small></>}
    </div>
  </div>
}
