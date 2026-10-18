import { useState } from 'react'
import { hub, type HubCategory } from '../hubApi'
import { Btn, ErrorNote, Input, Select, Textarea, errMsg } from '../workspace/kit'
import { Field } from './shared'

const TEMPLATE = `---
name: my-skill
description: 이 Skill 이 무엇을 하고 언제 쓰는지 한두 문장으로 설명하세요.
version: 1.0.0
category: dev
tags: [예시, 태그]
icon: 🧩
---

# My Skill

## 사용 방법
1. 입력으로 무엇을 주면 되는지 적습니다.
2. 결과로 무엇을 받는지 적습니다.

## 실행 예시
입력: ...
기대 결과: ...
`

/** Skill 등록: SKILL.md / ZIP 업로드 또는 웹 에디터 */
export function SkillForm({ categories, onDone }: { categories: HubCategory[]; onDone: (id: number) => void }) {
  const [mode, setMode] = useState<'upload' | 'editor'>('upload')
  const [file, setFile] = useState<File | null>(null)
  const [md, setMd] = useState(TEMPLATE)
  const [category, setCategory] = useState('')
  const [tags, setTags] = useState('')
  const [visibility, setVisibility] = useState('PUBLIC')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)

  const send = async (submit: boolean) => {
    if (busy || (mode === 'upload' ? !file : !md.trim())) return
    setBusy(true)
    setErr(null)
    try {
      const common = { category: category || undefined, visibility, submit }
      const d = mode === 'upload'
        ? await hub.upload(file!, { ...common, tags })
        : await hub.registerMd({ skillMd: md, ...common, tags: tags.split(',').map((t) => t.trim()).filter(Boolean) })
      onDone(d.summary.id)
    } catch (x) { setErr(errMsg(x)) } finally { setBusy(false) }
  }

  return (
    <div className="skill-registration">
      <div className="skill-register-tabs" aria-label="등록 방법">
          {([['upload', '파일로 등록'], ['editor', '직접 작성']] as const).map(([k, l]) => (
            <button key={k} disabled={busy} aria-pressed={mode === k} onClick={() => setMode(k)}>{l}</button>
          ))}
      </div>
      <fieldset disabled={busy} className="skill-register-body">
        {mode === 'upload' ? (
          <label className="skill-upload-area">
            <span className="skill-upload-symbol" aria-hidden="true">＋</span>
            <strong>{file ? file.name : '스킬 파일을 선택하세요'}</strong>
            <span>{file ? `${(file.size / 1024).toFixed(1)} KB · 다른 파일로 변경할 수 있어요` : 'SKILL.md 또는 스킬 폴더를 압축한 ZIP'}</span>
            <input aria-label="스킬 파일" type="file" accept=".md,.zip" onChange={(e) => { setErr(null); setFile(e.target.files?.[0] ?? null) }} />
            <small>최대 5MB · ZIP 내 파일 50개까지</small>
          </label>
        ) : (
          <div className="skill-editor"><p>아래 예시를 내 업무 지침으로 바꿔주세요.</p><Textarea aria-label="스킬 내용" rows={16} value={md} onChange={(e) => setMd(e.target.value)} /></div>
        )}
        <div className="skill-register-sharing"><Field label="공개 범위"><Select value={visibility} onChange={(e) => setVisibility(e.target.value)}><option value="PUBLIC">전사 공개 (승인 후)</option><option value="TEAM">우리 팀만 (승인 후)</option></Select></Field><p>관리자 검토가 끝나면 선택한 범위에 공개됩니다.</p></div>
        <details className="skill-register-options"><summary>추가 설정 <span>카테고리 · 태그</span></summary><div className="grid gap-4 md:grid-cols-2 mt-5">
          <Field label="카테고리">
            <Select value={category} onChange={(e) => setCategory(e.target.value)}><option value="">자동</option>{categories.map((c) => <option key={c.code} value={c.code}>{c.name}</option>)}</Select>
          </Field>
          <Field label="추가 태그"><Input value={tags} onChange={(e) => setTags(e.target.value)} placeholder="예: java, 리뷰 (쉼표로 구분)" /></Field>
        </div><p>카테고리는 파일에서 자동으로 읽습니다. 지정되지 않으면 ‘기타’로 등록됩니다.</p></details>
        <details className="skill-register-options"><summary>파일 등록 안내</summary><p>SKILL.md 또는 SKILL.md를 포함한 ZIP을 등록할 수 있습니다. 텍스트(md/txt/json/yaml/csv)와 이미지만 허용됩니다. 스크립트·실행 파일·심볼릭 링크·API 키가 포함된 파일은 등록되지 않습니다. 등록한 지침은 자동 실행되지 않습니다.</p></details>
      </fieldset>
      <div className="skill-register-footer">
        <ErrorNote message={err} />
        <div><span>아직 작성 중이라면 임시 저장하세요.</span><div className="flex gap-3"><Btn tone="ghost" disabled={busy || (mode === 'upload' ? !file : !md.trim())} onClick={() => void send(false)}>임시 저장</Btn><Btn disabled={busy || (mode === 'upload' ? !file : !md.trim())} onClick={() => void send(true)}>{busy ? '등록 중…' : '등록 및 검토 요청'}</Btn></div></div>
      </div>
    </div>
  )
}
