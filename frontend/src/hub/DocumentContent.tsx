import type { ReactNode } from 'react'

/** Safe headings and tables, with no HTML, URLs or embedded resources. */
export function DocumentContent({ text }: { text: string }) {
  const lines = text.split('\n'), content: ReactNode[] = []
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim().replace(/\*\*/g, '').replace(/`/g, '')
    if (!line) continue
    if (line.startsWith('|')) {
      const rows: string[][] = []
      const start = i
      while (i < lines.length && lines[i].trim().startsWith('|')) {
        const row = lines[i].trim()
        if (!/^\|[- :|]+\|$/.test(row)) rows.push(row.replace(/^\||\|$/g, '').split('|').map((cell) => cell.trim()))
        i++
      }
      i--
      content.push(<div key={start} className="document-table-scroll"><table><thead><tr>{rows[0]?.map((cell, index) => <th key={index}>{cell}</th>)}</tr></thead><tbody>{rows.slice(1).map((row, index) => <tr key={index}>{row.map((cell, column) => <td key={column}>{cell}</td>)}</tr>)}</tbody></table></div>)
    } else if (/^#{1,3}\s/.test(line)) content.push(<h4 key={i}>{line.replace(/^#{1,3}\s+/, '')}</h4>)
    else if (/^[-*•]\s+/.test(line)) {
      const items: string[] = []
      const start = i
      while (i < lines.length && /^[-*•]\s+/.test(lines[i].trim())) { items.push(lines[i].trim().replace(/^[-*•]\s+/, '').replace(/\*\*/g, '').replace(/`/g, '')); i++ }
      i--
      content.push(<ul key={start} className="list-disc pl-5 space-y-1">{items.map((t, k) => <li key={k}>{t}</li>)}</ul>)
    }
    else content.push(<p key={i}>{line}</p>)
  }
  return <div className="document-content">{content}</div>
}
