import { useEffect, useMemo, useState } from 'react'
import { hub } from '../hubApi'

type Node = { name: string; path: string; size?: number; children?: Node[] }

/** 경로 목록("SKILL.md", "references/guide.md" …)을 폴더·파일 트리로 만든다. 폴더 먼저, SKILL.md 는 맨 위 */
function buildTree(files: { path: string; size: number }[]): Node[] {
  const root: Node = { name: '', path: '', children: [] }
  for (const f of files) {
    const parts = f.path.split('/').filter(Boolean)
    let cur = root
    parts.forEach((part, i) => {
      const path = parts.slice(0, i + 1).join('/')
      const leaf = i === parts.length - 1
      let next = cur.children!.find((c) => c.name === part && !!c.children === !leaf)
      if (!next) { next = leaf ? { name: part, path, size: f.size } : { name: part, path, children: [] }; cur.children!.push(next) }
      cur = next
    })
  }
  const sort = (ns: Node[]) => {
    ns.sort((a, b) => (a.name === 'SKILL.md' ? -1 : b.name === 'SKILL.md' ? 1 : !!b.children === !!a.children ? a.name.localeCompare(b.name) : a.children ? -1 : 1))
    ns.forEach((n) => n.children && sort(n.children))
  }
  sort(root.children!)
  return root.children!
}

const size = (b?: number) => (b == null ? '' : b < 1024 ? `${b}B` : `${(b / 1024).toFixed(1)}KB`)
const icon = (name: string) => (/\.(png|jpe?g|gif|webp)$/i.test(name) ? '🖼️' : /\.(json|ya?ml|csv|tsv)$/i.test(name) ? '🧾' : '📄')

/** 스킬 파일 트리: 왼쪽에 폴더 구조, 오른쪽에 고른 파일 내용(텍스트는 글자로만, 이미지는 그림으로) */
export function SkillFileTree({ skillId, skillName, versionId, files, skillMd }: {
  skillId: number; skillName: string; versionId: number; files: { path: string; size: number }[]; skillMd: string
}) {
  const tree = useMemo(() => buildTree(files.length ? files : [{ path: 'SKILL.md', size: skillMd.length }]), [files, skillMd])
  const [open, setOpen] = useState<Set<string>>(() => new Set(files.map((f) => f.path.split('/').slice(0, -1).join('/')).filter(Boolean)))
  const [sel, setSel] = useState('SKILL.md')
  const [view, setView] = useState<{ text: string } | { image: string } | { error: string } | null>({ text: skillMd })
  useEffect(() => { setSel('SKILL.md'); setView({ text: skillMd }) }, [versionId, skillMd])
  useEffect(() => {
    if (sel === 'SKILL.md') { setView({ text: skillMd }); return }
    let alive = true
    setView(null)
    hub.file(skillId, versionId, sel).then((r) => alive && setView(r)).catch((e: Error) => alive && setView({ error: e.message }))
    return () => { alive = false }
  }, [sel, skillId, versionId, skillMd])
  useEffect(() => () => { if (view && 'image' in view) URL.revokeObjectURL(view.image) }, [view])

  const toggle = (p: string) => setOpen((cur) => { const n = new Set(cur); if (n.has(p)) n.delete(p); else n.add(p); return n })
  const render = (ns: Node[], depth: number) => ns.map((n) => n.children ? (
    <li key={n.path}>
      <button type="button" className="sft-row" style={{ paddingLeft: 8 + depth * 14 }} aria-expanded={open.has(n.path)} onClick={() => toggle(n.path)}>
        <span className="sft-caret" aria-hidden="true">{open.has(n.path) ? '▾' : '▸'}</span><span aria-hidden="true">📁</span><span className="sft-name">{n.name}</span>
      </button>
      {open.has(n.path) && <ul>{render(n.children, depth + 1)}</ul>}
    </li>
  ) : (
    <li key={n.path}>
      <button type="button" className={`sft-row ${sel === n.path ? 'on' : ''}`} style={{ paddingLeft: 22 + depth * 14 }} aria-current={sel === n.path} onClick={() => setSel(n.path)}>
        <span aria-hidden="true">{icon(n.name)}</span><span className="sft-name">{n.name}</span><span className="sft-size">{size(n.size)}</span>
      </button>
    </li>
  ))

  return (
    <div className="sft">
      <nav className="sft-tree" aria-label="스킬 파일">
        <div className="sft-root"><span aria-hidden="true">📦</span> {skillName}/</div>
        <ul>{render(tree, 1)}</ul>
      </nav>
      <section className="sft-view" aria-label={`${sel} 내용`}>
        <div className="sft-path">{skillName}/{sel}</div>
        {view === null && <p className="sft-muted">불러오는 중…</p>}
        {view && 'error' in view && <p className="sft-muted">{view.error}</p>}
        {/* HTML 을 해석하지 않고 글자로만 보여 준다 */}
        {view && 'text' in view && <pre className="sft-text">{view.text}</pre>}
        {view && 'image' in view && <img className="sft-img" src={view.image} alt={sel} />}
      </section>
    </div>
  )
}
