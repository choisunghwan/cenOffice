import { useEffect, useState } from 'react'
import { hub, type HubSkillSummary, type HubSkillDetail } from '../hubApi'
import { minutesApi, type MeetingProject, type ProjectSettings } from './minutesApi'

export function MeetingProjectSettings({ meetingId, username, disabled }: { meetingId: string; username: string; disabled: boolean }) {
  const [settings, setSettings] = useState<ProjectSettings>()
  const [editor, setEditor] = useState<MeetingProject | 'new' | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  useEffect(() => {
    let active = true
    minutesApi.projectSettings(meetingId).then(value => { if (active) setSettings(value) })
      .catch(e => { if (active) setError((e as Error).message) })
    return () => { active = false }
  }, [meetingId])

  const assign = async (id: string) => {
    setBusy(true); setError('')
    try { setSettings(await minutesApi.assignProject(meetingId, id || null)) }
    catch (e) { setError((e as Error).message) }
    finally { setBusy(false) }
  }
  const selected = settings?.projects.find(project => project.id === settings.projectId)
  return <section className="minutes-card">
    <h3>프로젝트 회의록</h3>
    {error && <p role="alert">{error}</p>}
    {settings && <>
      <label>소속 프로젝트 <select value={settings.projectId ?? ''} disabled={busy || disabled || !!editor}
        onChange={e => void assign(e.target.value)}>
        <option value="">프로젝트 없음 · 기본 회의록</option>
        {settings.projectId && !selected && <option value={settings.projectId} disabled>접근할 수 없는 프로젝트</option>}
        {settings.projects.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
      </select></label>
      {!selected && <p className="minutes-hint">프로젝트를 만들거나 목록에서 선택해 이 회의에 연결하세요.</p>}
      {selected && <p className="minutes-hint">{selected.skillId ? '프로젝트에 지정된 HUB 스킬로 초안을 만듭니다.' : '기본 회의록 작성 방식을 사용합니다.'}</p>}
      {!editor && <div className="minutes-actions">
        <button type="button" className="minutes-link" disabled={busy || disabled} onClick={() => setEditor('new')}>프로젝트 만들기</button>
        {selected?.owner === username &&
          <button type="button" className="minutes-link" disabled={busy || disabled} onClick={() => setEditor(selected)}>프로젝트 설정</button>}
      </div>}
      {editor && <ProjectEditor key={editor === 'new' ? 'new' : editor.id}
        project={editor === 'new' ? undefined : editor} disabled={disabled}
        onClose={() => setEditor(null)} onSaved={project => {
          setSettings(current => current && {
            ...current,
            projects: [...current.projects.filter(item => item.id !== project.id), project]
              .sort((a, b) => a.name.localeCompare(b.name, 'ko')),
          })
          setEditor(null)
        }} />}
    </>}
  </section>
}

function ProjectEditor({ project, disabled, onClose, onSaved }: {
  project?: MeetingProject; disabled: boolean; onClose: () => void; onSaved: (project: MeetingProject) => void
}) {
  const [name, setName] = useState(project?.name ?? '')
  const [members, setMembers] = useState(project?.members.filter(m => m !== project.owner).join(', ') ?? '')
  const [skillId, setSkillId] = useState(project?.skillId)
  const [versionId, setVersionId] = useState(project?.versionId)
  const [query, setQuery] = useState('')
  const [skills, setSkills] = useState<HubSkillSummary[]>([])
  const [detail, setDetail] = useState<HubSkillDetail>()
  const [copy, setCopy] = useState<{ name: string; contents: string; visibility: 'TEAM' | 'PUBLIC' }>()
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  useEffect(() => {
    let active = true
    hub.skills({ q: query, status: 'APPROVED', size: 50 }).then(page => { if (active) setSkills(page.items.filter(skill => !skill.sample)) })
      .catch(e => { if (active) setError((e as Error).message) })
    return () => { active = false }
  }, [query])
  useEffect(() => {
    let active = true
    if (skillId) hub.skill(skillId).then(value => { if (active) setDetail(value) })
      .catch(e => { if (active) setError((e as Error).message) })
    return () => { active = false }
  }, [skillId])

  const save = async () => {
    setBusy(true); setError('')
    try {
      const saved = await minutesApi.saveProject(project?.id, { name, members: members.split(',').map(m => m.trim()).filter(Boolean), skillId, versionId })
      onSaved(saved)
    } catch (e) { setError((e as Error).message) }
    finally { setBusy(false) }
  }
  const openCopy = async () => {
    if (!skillId || !versionId) return
    setBusy(true); setError(''); setNotice('')
    try {
      const exact = await hub.skill(skillId, versionId)
      if (!exact.current) throw new Error('스킬 내용을 불러오지 못했습니다')
      const contents = exact.current.skillMd.replace(/^(---\r?\n)([\s\S]*?)(\r?\n---)/, (_match, open, meta, close) =>
        open + meta.replace(/^version:[^\r\n]*/m, 'version: 1.0.0') + close)
      setCopy({ name: '', contents, visibility: 'TEAM' })
    } catch (e) { setError((e as Error).message) }
    finally { setBusy(false) }
  }
  const submitCopy = async () => {
    if (!copy) return
    setBusy(true); setError(''); setNotice('')
    try {
      const slug = copy.name.trim()
      const match = /^(---\r?\n)([\s\S]*?)(\r?\n---)/.exec(copy.contents)
      if (!match || !/^name:[^\r\n]*/m.test(match[2])) throw new Error('SKILL.md의 이름 항목을 확인하세요')
      const skillMd = match[1] + match[2].replace(/^name:[^\r\n]*/m, 'name: ' + slug) + match[3] + copy.contents.slice(match[0].length)
      await hub.registerMd({ skillMd, visibility: copy.visibility, submit: true, originProjectId: project?.id })
      setCopy(undefined)
      setNotice('복제한 스킬의 검토를 요청했습니다. 승인 후 이 프로젝트에 지정할 수 있습니다.')
    } catch (e) { setError((e as Error).message) }
    finally { setBusy(false) }
  }
  const versions = detail?.summary.id === skillId ? detail?.versions.filter(v => v.status === 'APPROVED' && v.packageKind === 'MD') ?? [] : []
  return <fieldset disabled={busy || disabled} className="minutes-project-editor">
    <legend>{project ? '프로젝트 설정' : '새 프로젝트'}</legend>
    <label>프로젝트 이름 <input maxLength={120} value={name} onChange={e => setName(e.target.value)} /></label>
    <label>참여자 아이디 <input value={members} onChange={e => setMembers(e.target.value)} placeholder="쉼표로 구분" /></label>
    <label>HUB 스킬 검색 <input value={query} onChange={e => setQuery(e.target.value)} placeholder="이름 또는 설명" /></label>
    <label>회의록 스킬 <select value={skillId ?? ''} onChange={e => {
      setSkillId(Number(e.target.value) || undefined); setVersionId(undefined); setDetail(undefined)
    }}>
      <option value="">기본 회의록 작성</option>
      {skillId && !skills.some(s => s.id === skillId) && <option value={skillId}>{detail?.summary.title ?? '현재 지정 스킬'}</option>}
      {skills.map(s => <option key={s.id} value={s.id}>{s.title || s.name}</option>)}
    </select></label>
    {skillId && <label>승인 버전 <select value={versionId ?? ''} onChange={e => setVersionId(Number(e.target.value) || undefined)}>
      <option value="">버전 선택</option>
      {versionId && !versions.some(v => v.id === versionId) && <option value={versionId}>현재 지정 버전</option>}
      {versions.map(v => <option key={v.id} value={v.id}>{v.version}</option>)}
    </select></label>}
    {project && skillId && versionId && !copy && <button type="button" className="minutes-link" onClick={() => void openCopy()}>이 버전 복제해 수정</button>}
    {copy && <div className="minutes-project-editor">
      <label>새 스킬 키 <input value={copy.name} maxLength={64} placeholder="예: customer-meeting-minutes"
        onChange={e => setCopy({ ...copy, name: e.target.value })} /></label>
      <label>스킬 지침 <textarea rows={8} value={copy.contents} onChange={e => setCopy({ ...copy, contents: e.target.value })} /></label>
      <label>승인 후 공개 범위 <select value={copy.visibility} onChange={e => setCopy({ ...copy, visibility: e.target.value as 'TEAM' | 'PUBLIC' })}>
        <option value="TEAM">내 팀</option><option value="PUBLIC">전사</option>
      </select></label>
      <div className="minutes-actions">
        <button type="button" className="minutes-btn" onClick={() => setCopy(undefined)}>복제 취소</button>
        <button type="button" className="minutes-btn" disabled={!/^[a-z0-9][a-z0-9-]{1,63}$/.test(copy.name.trim())} onClick={() => void submitCopy()}>등록 및 검토 요청</button>
      </div>
    </div>}
    {notice && <p role="status">{notice}</p>}
    <p className="minutes-hint">승인된 단일 SKILL.md 버전을 지정할 수 있습니다. 적용하려면 HUB AI 모델 연결이 필요합니다.</p>
    {error && <p role="alert">{error}</p>}
    <div className="minutes-actions">
      <button type="button" className="minutes-btn" onClick={onClose}>닫기</button>
      <button type="button" className="minutes-btn primary" disabled={!!copy || !name.trim() || (!!skillId && !versionId)} onClick={() => void save()}>저장</button>
    </div>
  </fieldset>
}
