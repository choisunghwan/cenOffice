import { useEffect, useMemo, useRef, useState } from 'react'
import { askConfirm } from '../ui/confirm'
import { FormatIcon } from '../hub/FormatIcon'
import { templateApi, type FormatSlot, type FormTarget, type PlanItem, type PreviewBlock } from '../hub/templateApi'

/**
 * 양식이 등록된 도구의 결과를 양식에 채워 미리보고 그 형식(한글·워드·엑셀·파워포인트)의 파일로 받는다. 양식이 여러 형식으로 등록돼 있으면 고른다.
 * 근거를 못 찾은 항목은 임의로 채우지 않고 목록으로 보여 주며, 항목마다 직접 입력 / AI 초안 / 빈칸(기본)을 고른다.
 * AI 가 쓴 칸은 미리보기에서 색으로 구분되고, 받는 파일에는 표시를 넣지 않는다.
 */
export function TemplateFill({ skillId, title, result }: { skillId: FormTarget; title: string; result: string }) {
  const [formats, setFormats] = useState<FormatSlot[] | null>(null) // 이 도구에 양식이 등록된 형식들
  const [format, setFormat] = useState<string | null>(null)
  const [items, setItems] = useState<PlanItem[] | null>(null)
  const [values, setValues] = useState<Record<string, string>>({})
  const [aiKeys, setAiKeys] = useState<Set<string>>(new Set())
  const [blocks, setBlocks] = useState<PreviewBlock[]>([])
  const [aiUsed, setAiUsed] = useState(false)
  const [busyKey, setBusyKey] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [err, setErr] = useState<string | null>(null)

  // 0) 등록된 형식을 알아낸다(처음 하나를 고른다)
  useEffect(() => {
    let alive = true
    templateApi.slots(skillId).then((r) => {
      if (!alive) return
      const have = r.filter((x) => x.template)
      setFormats(have); setFormat((cur) => (cur && have.some((x) => x.format === cur) ? cur : have[0]?.format ?? null))
      if (have.length === 0) setErr('이 도구에는 등록된 양식이 없습니다.')
    }).catch((e) => alive && setErr((e as Error).message))
    return () => { alive = false }
  }, [skillId])

  // 1) 결과에서 항목별 값을 찾는다
  useEffect(() => {
    if (!format) return
    let alive = true
    setItems(null); setErr(null); setBlocks([])
    templateApi.plan(skillId, result, format).then((p) => {
      if (!alive) return
      setItems(p.items); setAiUsed(p.aiUsed)
      setValues(Object.fromEntries(p.items.filter((i) => i.value).map((i) => [i.key, i.value as string])))
      setAiKeys(new Set(p.items.filter((i) => i.source === 'AI').map((i) => i.key)))
    }).catch((e) => alive && setErr((e as Error).message))
    return () => { alive = false }
  }, [skillId, result, format])

  // 2) 값이 바뀔 때마다(잠깐 모아서) 미리보기를 새로 만든다
  const seq = useRef(0)
  useEffect(() => {
    if (!items || !format) return
    const my = ++seq.current
    const t = setTimeout(() => {
      templateApi.preview(skillId, values, format).then((b) => { if (my === seq.current) setBlocks(b) }).catch((e) => { if (my === seq.current) setErr((e as Error).message) })
    }, 300)
    return () => clearTimeout(t)
  }, [items, values, skillId, format])

  const missing = useMemo(() => (items ?? []).filter((i) => i.source === 'MISSING'), [items]) // 입력해도 줄이 사라지지 않게 처음 모습으로 고정
  const unfilled = missing.filter((m) => !values[m.key]).length
  const aiCount = useMemo(() => [...aiKeys].filter((k) => values[k]).length, [aiKeys, values])

  const setValue = (key: string, v: string) => {
    setValues((cur) => { const n = { ...cur }; if (v) n[key] = v; else delete n[key]; return n })
    setAiKeys((cur) => { if (!cur.has(key)) return cur; const n = new Set(cur); n.delete(key); return n }) // 직접 고친 칸은 더 이상 AI 가 쓴 칸이 아니다
  }
  const draft = async (key: string) => {
    if (busyKey) return
    setBusyKey(key); setErr(null)
    try {
      const v = await templateApi.draft(skillId, result, key, format ?? 'hwpx')
      setValues((cur) => ({ ...cur, [key]: v }))
      setAiKeys((cur) => new Set(cur).add(key))
    } catch (e) { setErr((e as Error).message) } finally { setBusyKey(null) }
  }
  const download = async () => {
    if (saving) return
    if (aiCount > 0 && !await askConfirm({ title: 'AI가 쓴 칸을 확인하셨나요?', message: `AI가 채운 칸이 ${aiCount}개 있습니다. 미리보기에서 보라색으로 표시된 칸이 맞는지 확인한 뒤 받아 주세요. (받는 파일에는 표시가 들어가지 않습니다)`, okText: '확인했습니다, 받기' })) return
    setSaving(true); setErr(null)
    try { await templateApi.render(skillId, values, title, format ?? 'hwpx') } catch (e) { setErr((e as Error).message) } finally { setSaving(false) }
  }

  const cls = (key?: string | null) => (key ? `tf-field${aiKeys.has(key) && values[key] ? ' ai' : ''}${key && !values[key] ? ' empty' : ''}` : undefined)

  const label = formats?.find((f) => f.format === format)?.label ?? ''
  if (err && !items) return <div className="tf-box"><p className="ha-error" role="alert">{err}</p></div>
  if (!items || !format) return <div className="tf-box"><div className="tf-preview" aria-busy="true"><p role="status" className="tf-wait">양식에 맞춰 채우는 중…</p></div></div>

  return (
    <div className="tf-box">
      <h4 className="tf-head">양식에 채우기 · {label}({(format ?? '').toUpperCase()})</h4>
      {formats && formats.length > 1 && (
        <div className="tf-formats" role="radiogroup" aria-label="양식 형식">
          {formats.map((f) => (
            <button key={f.format} type="button" role="radio" aria-checked={f.format === format} className={f.format === format ? 'on' : ''} onClick={() => setFormat(f.format)}>
              <FormatIcon format={f.format} on size={20} />{f.label}
            </button>
          ))}
        </div>
      )}
      {missing.length > 0 && (
        <div className="tf-missing" role="group" aria-label="채우지 못한 항목">
          <p className="tf-missing-title">채우지 못한 항목 {unfilled}개 <small>— 근거를 찾지 못해 비워 두었습니다. 직접 입력하거나 AI 초안을 받을 수 있습니다.</small></p>
          <div className={missing.length > 6 ? 'tf-missing-list long' : 'tf-missing-list'}>
          {missing.map((m) => (
            <div key={m.key} className="tf-row">
              <label htmlFor={`tf-${m.key}`}>{m.label}</label>
              <input id={`tf-${m.key}`} value={values[m.key] ?? ''} placeholder="비워 두기 (직접 입력할 수 있습니다)" maxLength={2000} onChange={(e) => setValue(m.key, e.target.value)} />
              <button type="button" className="cal-ghost" disabled={!!busyKey} onClick={() => void draft(m.key)} title={aiUsed ? undefined : 'AI 가 연결되어 있지 않으면 쓸 수 없습니다'}>{busyKey === m.key ? '쓰는 중…' : 'AI 초안'}</button>
            </div>
          ))}
          </div>
        </div>
      )}
      {err && <p className="ha-error" role="alert">{err}</p>}
      <div className="tf-preview" aria-label="미리보기">
        {blocks.map((b, i) => b.type === 'table'
          ? <table key={i}><tbody>{(b.rows ?? []).map((r, ri) => <tr key={ri}>{r.map((c, ci) => <td key={ci} className={cls(c.fieldKey)}>{c.text || (c.fieldKey ? '(비어 있음)' : '')}</td>)}</tr>)}</tbody></table>
          : <p key={i} className={cls(b.fieldKey)}>{b.text}</p>)}
      </div>
      <p className="tf-legend"><span className="tf-field">채울 칸</span> <span className="tf-field ai">AI가 쓴 칸</span> <span className="tf-field empty">비어 있는 칸</span></p>
      <div className="ha-actions"><button type="button" className="cal-primary" disabled={saving} onClick={() => void download()}>{saving ? '만드는 중…' : `${label}(${(format ?? '').toUpperCase()})로 받기`}</button></div>
    </div>
  )
}
