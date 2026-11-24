import { useEffect, useRef, useState } from 'react'
import { askConfirm } from '../common/confirm'
import { templateApi } from './templateApi'
import { hub, type HubCategory } from '../hubApi'
import { Btn, ErrorNote, Input, Select, Textarea, errMsg } from '../workspace/kit'
import { Field } from './shared'

const TEMPLATE = `---
name: my-skill
title: 내 업무 스킬
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

type ChatMsg = { role: 'user' | 'assistant'; text: string }
const GREETING = '어떤 업무를 스킬로 만들까요? 평소 하시는 방법을 편하게 말씀해 주세요.\n예: 매주 쓰는 주간 보고서, 고객 문의 답변 초안, 코드 리뷰 체크리스트'

/** AI 와 대화하며 SKILL.md 초안을 만든다(skill-creator 방식). 초안은 오른쪽(휴대폰은 아래)에서 바로 고칠 수 있다 */
function SkillChat({ md, setMd, hasDraft, setHasDraft, disabled, onNoAi }: {
  md: string; setMd: (s: string) => void; hasDraft: boolean; setHasDraft: (b: boolean) => void; disabled: boolean; onNoAi: () => void
}) {
  const [msgs, setMsgs] = useState<ChatMsg[]>([{ role: 'assistant', text: GREETING }])
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState<{ error: string | null; risks: string[] }>({ error: null, risks: [] })
  const [err, setErr] = useState<string | null>(null)
  const [noAi, setNoAi] = useState(false)
  const end = useRef<HTMLDivElement>(null)
  useEffect(() => { end.current?.scrollIntoView({ block: 'nearest' }) }, [msgs, busy])
  const send = async () => {
    const t = text.trim()
    if (!t || busy) return
    const next: ChatMsg[] = [...msgs, { role: 'user', text: t }]
    setMsgs(next); setText(''); setBusy(true); setErr(null)
    try {
      const r = await hub.assist({ messages: next.slice(1), draft: hasDraft ? md : undefined })
      setMsgs([...next, { role: 'assistant', text: r.reply }])
      if (!r.ai) { setNoAi(true); return }
      if (r.skillMd) { setMd(r.skillMd); setHasDraft(true); setNote({ error: r.error, risks: r.risks ?? [] }) }
    } catch (x) { setErr(errMsg(x)); setText(t); setMsgs(msgs) } finally { setBusy(false) }
  }
  return (
    <div className={`skill-chat ${hasDraft ? 'with-draft' : ''}`}>
      <div className="skill-chat-talk">
        <div className="skill-chat-log" role="log" aria-live="polite">
          {msgs.map((m, i) => <p key={i} className={m.role === 'user' ? 'me' : 'ai'}>{m.text}</p>)}
          {busy && <p className="ai pending">{hasDraft ? '초안을 고치는 중…' : '생각하는 중…'}</p>}
          <div ref={end} />
        </div>
        <ErrorNote message={err} />
        {noAi && <div className="skill-chat-noai"><Btn tone="ghost" onClick={onNoAi}>파일로 등록하기</Btn></div>}
        <form className="skill-chat-input" onSubmit={(e) => { e.preventDefault(); void send() }}>
          {/* 공용 Textarea 는 onKeyDown 을 덮어써서(3D 오피스로 키가 새지 않게) Enter 전송을 못 붙이므로 직접 쓴다 */}
          <textarea className="skill-chat-box" aria-label="스킬 만들기 도우미에게 말하기" rows={2} value={text} disabled={disabled} placeholder={hasDraft ? '고칠 점을 말해 주세요. 예: 결과를 표로 바꿔줘' : '하시는 업무를 설명해 주세요'}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => { e.stopPropagation(); if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); void send() } }} />
          <Btn type="submit" disabled={disabled || busy || !text.trim()}>보내기</Btn>
        </form>
      </div>
      {hasDraft && (
        <div className="skill-chat-draft">
          <div className="skill-chat-draft-head"><strong>스킬 초안 (SKILL.md)</strong><span>직접 고쳐도 됩니다</span></div>
          {note.error && <p className="skill-chat-warn">등록 규칙에 맞지 않는 부분이 있어요: {note.error} 도우미에게 고쳐 달라고 하거나 직접 고쳐 주세요.</p>}
          {note.risks.length > 0 && <p className="skill-chat-warn">검토가 필요한 표현: {note.risks.join(', ')}</p>}
          <Textarea aria-label="스킬 초안" rows={18} value={md} disabled={disabled} onChange={(e) => setMd(e.target.value)} />
        </div>
      )}
    </div>
  )
}

/** Skill 등록: AI 와 대화로 만들기(기본) · 직접 작성 · SKILL.md / ZIP 업로드(고급) */
export function SkillForm({ categories, onDone }: { categories: HubCategory[]; onDone: (id: number) => void }) {
  const [mode, setMode] = useState<'ai' | 'upload' | 'editor'>('ai')
  const [hasDraft, setHasDraft] = useState(false) // AI 대화로 만든 초안이 있는지
  const [file, setFile] = useState<File | null>(null)
  const [md, setMd] = useState(TEMPLATE)
  const [category, setCategory] = useState('')
  const [tags, setTags] = useState('')
  const [visibility, setVisibility] = useState('PUBLIC')
  const [form, setForm] = useState<File | null>(null) // 결과물 양식(선택)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)

  const send = async (submit: boolean) => {
    if (busy || !ready) return
    setBusy(true)
    setErr(null)
    try {
      const common = { category: category || undefined, visibility, submit }
      const d = mode === 'upload'
        ? await hub.upload(file!, { ...common, tags })
        : await hub.registerMd({ skillMd: md, ...common, tags: tags.split(',').map((t) => t.trim()).filter(Boolean) })
      if (form) {
        // 양식은 스킬이 만들어진 뒤에 붙인다. 실패해도 스킬은 저장된 상태라, 알리고 상세 화면에서 다시 올릴 수 있게 한다.
        try { await templateApi.put(d.summary.id, form) } catch (x) {
          await askConfirm({ title: '양식을 올리지 못했어요', message: `스킬은 저장되었습니다. ${(x as Error).message} 스킬 화면에서 양식을 다시 올릴 수 있어요.`, okText: '확인', cancelText: '닫기' })
        }
      }
      onDone(d.summary.id)
    } catch (x) { setErr(errMsg(x)) } finally { setBusy(false) }
  }

  const ready = mode === 'upload' ? !!file : mode === 'ai' ? hasDraft && !!md.trim() : !!md.trim()
  return (
    <div className="skill-registration">
      <div className="skill-register-tabs" aria-label="등록 방법">
          {([['ai', 'AI와 만들기'], ['editor', '직접 작성'], ['upload', '파일 등록 (고급)']] as const).map(([k, l]) => (
            <button key={k} disabled={busy} aria-pressed={mode === k} onClick={() => setMode(k)}>{l}</button>
          ))}
      </div>
      <fieldset disabled={busy} className="skill-register-body">
        {mode === 'ai' ? (
          <SkillChat md={md} setMd={setMd} hasDraft={hasDraft} setHasDraft={setHasDraft} disabled={busy} onNoAi={() => setMode('upload')} />
        ) : mode === 'upload' ? (
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
        {(mode !== 'ai' || hasDraft) && <>
        <label className="skill-upload-area form-upload">
          <span className="skill-upload-symbol" aria-hidden="true">▤</span>
          <strong>{form ? form.name : '결과물 양식 (선택)'}</strong>
          <span>{form ? `${(form.size / 1024).toFixed(1)} KB · 스킬 결과가 이 양식에 맞춰 채워집니다` : '정해진 양식이 있다면 올려 두세요. 결과를 그 양식에 맞춰 받을 수 있어요. (한글·워드·엑셀·파워포인트)'}</span>
          <input aria-label="결과물 양식" type="file" accept=".hwpx,.docx,.xlsx,.pptx" onChange={(e) => { setErr(null); setForm(e.target.files?.[0] ?? null) }} />
          {form && <button type="button" className="form-upload-clear" onClick={(e) => { e.preventDefault(); setForm(null) }}>양식 빼기</button>}
        </label>
        <div className="skill-register-sharing"><Field label="공개 범위"><Select value={visibility} onChange={(e) => setVisibility(e.target.value)}><option value="PUBLIC">전사 공개 (승인 후)</option><option value="TEAM">우리 팀만 (승인 후)</option></Select></Field><p>관리자 검토가 끝나면 선택한 범위에 공개됩니다.</p></div>
        <details className="skill-register-options"><summary>추가 설정 <span>카테고리 · 태그</span></summary><div className="grid gap-4 md:grid-cols-2 mt-5">
          <Field label="카테고리">
            <Select value={category} onChange={(e) => setCategory(e.target.value)}><option value="">자동</option>{categories.map((c) => <option key={c.code} value={c.code}>{c.name}</option>)}</Select>
          </Field>
          <Field label="추가 태그"><Input value={tags} onChange={(e) => setTags(e.target.value)} placeholder="예: java, 리뷰 (쉼표로 구분)" /></Field>
        </div><p>카테고리는 파일에서 자동으로 읽습니다. 지정되지 않으면 ‘기타’로 등록됩니다.</p></details>
        <details className="skill-register-options"><summary>파일 등록 안내</summary><p>SKILL.md 또는 SKILL.md를 포함한 ZIP을 등록할 수 있습니다. 텍스트(md/txt/json/yaml/csv)와 이미지만 허용됩니다. 스크립트·실행 파일·심볼릭 링크·API 키가 포함된 파일은 등록되지 않습니다. 등록한 지침은 자동 실행되지 않습니다.</p></details>
        </>}
      </fieldset>
      {(mode !== 'ai' || hasDraft) && <div className="skill-register-footer">
        <ErrorNote message={err} />
        <div><span>아직 작성 중이라면 임시 저장하세요.</span><div className="flex gap-3"><Btn tone="ghost" disabled={busy || !ready} onClick={() => void send(false)}>임시 저장</Btn><Btn disabled={busy || !ready} onClick={() => void send(true)}>{busy ? '등록 중…' : '등록 및 검토 요청'}</Btn></div></div>
      </div>}
    </div>
  )
}
