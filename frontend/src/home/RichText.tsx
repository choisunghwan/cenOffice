import type { ReactNode } from 'react'

/**
 * AI 답변을 보기 좋게 그린다: 빈 줄 = 문단, "- "·"• "·"* " = 글머리 목록, "1. "·"1) " = 번호 목록,
 * "## 제목" = 소제목, **굵게**, `코드`. HTML 을 해석하지 않고 React 요소로만 만든다(입력은 신뢰하지 않음).
 */
export function RichText({ text, className }: { text: string; className?: string }) {
  const blocks: ReactNode[] = []
  const lines = text.replace(/\r\n?/g, '\n').split('\n')
  let para: string[] = []
  let list: { ordered: boolean; items: string[] } | null = null
  let table: string[][] | null = null
  const flushTable = () => {
    if (!table) return
    const [head, ...rows] = table
    blocks.push(<div key={blocks.length} className="rt-table"><table><thead><tr>{head.map((c, i) => <th key={i}>{inline(c)}</th>)}</tr></thead>
      <tbody>{rows.map((r, i) => <tr key={i}>{head.map((_, j) => <td key={j}>{inline(r[j] ?? '')}</td>)}</tr>)}</tbody></table></div>)
    table = null
  }
  const flushPara = () => { if (para.length) { blocks.push(<p key={blocks.length}>{inline(para.join('\n'))}</p>); para = [] } }
  const flushList = () => {
    if (!list) return
    const items = list.items.map((it, i) => <li key={i}>{inline(it)}</li>)
    blocks.push(list.ordered ? <ol key={blocks.length}>{items}</ol> : <ul key={blocks.length}>{items}</ul>)
    list = null
  }
  for (const raw of lines) {
    const line = raw.trimEnd()
    // | 칸 | 칸 | 표: 구분 줄(|---|)은 건너뛴다
    if (/^\s*\|.*\|\s*$/.test(line)) {
      flushPara(); flushList()
      if (/^\s*\|[\s:|-]+\|\s*$/.test(line)) continue
      ;(table ??= []).push(line.trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim()))
      continue
    }
    flushTable()
    const bullet = /^\s*(?:[-*•·])\s+(.*)$/.exec(line)
    const num = /^\s*\d{1,2}[.)]\s+(.*)$/.exec(line)
    const head = /^\s*#{1,4}\s+(.*)$/.exec(line)
    if (!line.trim()) { flushPara(); flushList(); continue }
    if (head) { flushPara(); flushList(); blocks.push(<p key={blocks.length} className="rt-head">{inline(head[1].replace(/\*\*/g, ''))}</p>); continue }
    if (bullet || num) {
      flushPara()
      const ordered = !!num
      if (list && list.ordered !== ordered) flushList()
      if (!list) list = { ordered, items: [] }
      list.items.push((bullet ?? num)![1])
      continue
    }
    if (list && /^\s{2,}\S/.test(raw)) { list.items[list.items.length - 1] += ' ' + line.trim(); continue } // 목록 항목의 이어진 줄
    flushList()
    para.push(line)
  }
  flushPara(); flushList(); flushTable()
  return <div className={`rt ${className ?? ''}`}>{blocks}</div>
}

/** **굵게** 와 `코드` 만 해석한다 */
function inline(s: string): ReactNode[] {
  const out: ReactNode[] = []
  const re = /\*\*([^*\n]+)\*\*|`([^`\n]+)`/g
  let last = 0, m: RegExpExecArray | null
  while ((m = re.exec(s))) {
    if (m.index > last) out.push(s.slice(last, m.index))
    out.push(m[1] !== undefined ? <strong key={m.index}>{m[1]}</strong> : <code key={m.index}>{m[2]}</code>)
    last = m.index + m[0].length
  }
  if (last < s.length) out.push(s.slice(last))
  return out
}
