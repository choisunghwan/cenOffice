import { useCallback, useEffect, useRef, useState } from 'react'
import { useOffice } from '../store'
import { meetingsApi, type Meeting, type MediaGrant } from './api'
import { Pager } from '../hub/Pager'
import { MeetingCall } from './MeetingCall'
import { MeetingMinutes } from './MeetingMinutes'
import './meeting.css'

const PAGE_SIZE = 10

export function MeetingsPage() {
  const user = useOffice(s => s.user)
  const [list, setList] = useState<Meeting[]>([])
  const [total, setTotal] = useState(0)
  const [openCount, setOpenCount] = useState(0)
  const [query, setQuery] = useState('')
  const [applied, setApplied] = useState('')
  const [pageNo, setPageNo] = useState(0)
  const view = useRef({ q: '', page: 0 }) // 10초마다 새로고침할 때 지금 보고 있는 검색어·쪽을 그대로 쓴다
  view.current = { q: applied, page: pageNo }
  const [selected, setSelected] = useState<Meeting | null>(null)
  const [grant, setGrant] = useState<MediaGrant | null>(null)
  const [title, setTitle] = useState('팀 협업 회의')
  const [code, setCode] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [available, setAvailable] = useState<boolean | null>(null)
  const [mic, setMic] = useState(false)
  const [camera, setCamera] = useState(false)
  const [notice, setNotice] = useState('')
  const [minutesFor, setMinutesFor] = useState<Meeting | null>(null)
  const preview = useRef<HTMLVideoElement>(null)
  const stream = useRef<MediaStream | null>(null)
  const stopPreview = useCallback(() => { stream.current?.getTracks().forEach(t => t.stop()); stream.current = null }, [])
  const refresh = useCallback(async () => {
    try {
      const r = await meetingsApi.page(view.current.q, view.current.page, PAGE_SIZE)
      setList(r.items); setTotal(r.total)
      if (r.items.length === 0 && r.total > 0 && view.current.page > 0) setPageNo(Math.max(0, Math.ceil(r.total / PAGE_SIZE) - 1)) // 마지막 쪽이 비었으면 앞 쪽으로
    } catch (e) { setError((e as Error).message) }
    try { setOpenCount((await meetingsApi.page('', 0, 50)).items.filter((m) => m.state === 'OPEN').length) } catch { /* 진행 중 개수는 보조 정보 */ }
  }, [])
  useEffect(() => { void refresh() }, [applied, pageNo, refresh])
  // 알림에서 넘어온 회의록 열기
  useEffect(() => {
    const open = () => {
      const id = sessionStorage.getItem('cenOpenMinutes')
      if (!id) return
      sessionStorage.removeItem('cenOpenMinutes')
      meetingsApi.detail(id).then(setMinutesFor).catch((e) => setError((e as Error).message))
    }
    open()
    window.addEventListener('cen-open-minutes', open)
    return () => window.removeEventListener('cen-open-minutes', open)
  }, [])
  useEffect(() => {
    let alive = true
    void refresh()
    const status = () => meetingsApi.status().then(s => alive && setAvailable(s.available)).catch(() => alive && setAvailable(false))
    void status()
    const t = setInterval(() => { void refresh(); void status() }, 10000)
    return () => { alive = false; clearInterval(t); stopPreview() }
  }, [refresh, stopPreview])
  useEffect(() => {
    if (!selected || grant || !camera) { stopPreview(); return }
    let cancelled = false
    navigator.mediaDevices?.getUserMedia({ video: { width: 640, height: 360 }, audio: false })
      .then(s => { if (cancelled) s.getTracks().forEach(t => t.stop()); else { stream.current = s; if (preview.current) preview.current.srcObject = s } })
      .catch(() => { if (!cancelled) { setCamera(false); setError('카메라를 사용할 수 없습니다. 권한을 확인하거나 카메라 없이 참여해 주세요.') } })
    return () => { cancelled = true; stopPreview() }
  }, [selected, grant, camera, stopPreview])
  const action = async (fn: () => Promise<Meeting>) => {
    if (busy) return
    setBusy(true); setError(''); setNotice('')
    try { setSelected(await fn()); void refresh() } catch (e) { setError((e as Error).message) } finally { setBusy(false) }
  }
  const enter = async () => {
    if (!selected || busy) return
    setBusy(true); setError(''); stopPreview()
    try { setGrant(await meetingsApi.grant(selected.id)) } catch (e) { setError((e as Error).message); setCamera(false) } finally { setBusy(false) }
  }
  const leave = (message = '회의에서 나왔습니다. 필요하면 다시 참여할 수 있습니다.') => {
    setGrant(null); setSelected(null); setCamera(false); setMic(false); setError(''); setNotice(message); void refresh()
  }
  if (minutesFor) return <section className="meetings-page"><MeetingMinutes key={minutesFor.id} meetingId={minutesFor.id} title={minutesFor.title} host={minutesFor.host} onBack={() => { setMinutesFor(null); void refresh() }} /></section>
  if (selected && grant) return <MeetingCall key={selected.id} meeting={selected} grant={grant} mic={mic} camera={camera} onLeave={leave} />

  return <section className="meetings-page">
    <header className="meeting-page-heading"><div><p className="meeting-eyebrow">CEN OFFICE · MEETINGS</p><h1>함께 모이면, 일이 시작됩니다.</h1><p>동료를 초대하고, 얼굴을 보며 이야기하세요.</p></div><span className={`meeting-server ${available ? 'ready' : ''}`}><i />{available === null ? '연결 확인 중' : available ? '화상회의 준비 완료' : '화상회의 서버 연결 필요'}</span></header>
    {error && <div className="meeting-alert" role="alert">{error}</div>}
    {notice && <div className="meeting-notice" role="status">{notice}</div>}
    {selected ? <div className="meeting-lobby">
      <div className="meeting-preview"><video ref={preview} muted autoPlay playsInline hidden={!camera} />{!camera && <div className="meeting-avatar"><span>{user?.displayName.slice(0, 1)}</span><p>카메라가 꺼져 있습니다</p></div>}<span className="preview-name">{user?.displayName} · 나</span></div>
      <div className="meeting-lobby-info"><p className="meeting-eyebrow">입장 전 확인</p><h2>{selected.title}</h2><p>준비되셨나요?<br />마이크와 카메라는 참여 후에도 바꿀 수 있어요.</p>
        <div className="meeting-device-options"><label><input type="checkbox" checked={mic} onChange={e => setMic(e.target.checked)} /> 마이크 켜고 참여</label><label><input type="checkbox" checked={camera} onChange={e => setCamera(e.target.checked)} /> 카메라 켜고 참여</label></div>
        <small>한 PC에서 두 브라우저를 사용할 때는 한쪽 마이크를 꺼 주세요. 카메라 권한이 없어도 참여할 수 있습니다.</small>
        <button className="meeting-primary" disabled={busy || available === false} onClick={() => void enter()}>{busy ? '연결 중…' : '회의 참여하기'} <span>↗</span></button>
        <button className="meeting-text-button" onClick={() => { stopPreview(); setSelected(null); setCamera(false) }}>목록으로 돌아가기</button>
      </div>
    </div> : <>
      <div className="meeting-start-grid">
        <form className="meeting-start-card primary-card" onSubmit={e => { e.preventDefault(); void action(() => meetingsApi.create(title)) }}>
          <div className="meeting-card-icon">＋</div><h2>새 회의 시작</h2><p>회의를 만들고 동료에게 참여 코드를 보내세요.</p>
          <label htmlFor="meeting-title">회의 이름</label><input id="meeting-title" maxLength={120} value={title} onChange={e => setTitle(e.target.value)} placeholder="어떤 이야기를 나눌까요?" required />
          <button className="meeting-primary" disabled={busy || !title.trim()}>{busy ? '준비 중…' : '회의 만들기'} <span>→</span></button>
        </form>
        <form className="meeting-start-card" onSubmit={e => { e.preventDefault(); void action(() => meetingsApi.join(code.trim().toLowerCase())) }}>
          <div className="meeting-card-icon">↗</div><h2>초대받은 회의 참여</h2><p>동료에게 받은 코드를 붙여 넣으면 연결됩니다.</p>
          <label htmlFor="meeting-code">참여 코드</label><input id="meeting-code" maxLength={32} value={code} onChange={e => setCode(e.target.value)} placeholder="참여 코드 붙여 넣기" autoComplete="off" required />
          <button className="meeting-secondary" disabled={busy || code.trim().length !== 32}>참여하기 <span>→</span></button>
        </form>
      </div>
      <div className="meeting-list-heading"><h2>내 회의</h2><span>{openCount}개 진행 중</span></div>
      <form className="meeting-search" role="search" onSubmit={e => { e.preventDefault(); setPageNo(0); setApplied(query.trim()) }}><input value={query} onChange={e => setQuery(e.target.value)} placeholder="회의 제목으로 검색" aria-label="회의 제목 검색" maxLength={120} /><button type="submit">검색</button>{applied && <button type="button" className="meeting-search-clear" onClick={() => { setQuery(''); setApplied(''); setPageNo(0) }}>전체 보기</button>}</form>
      {!list.length ? (applied ? <div className="meeting-empty"><span>◎</span><h3>‘{applied}’에 맞는 회의가 없습니다</h3><p>제목의 일부만 입력해 보세요.</p></div> : <div className="meeting-empty"><span>◎</span><h3>첫 회의를 시작해 보세요</h3><p>만들거나 참여한 회의가 여기에 모입니다.</p></div>) : <div className="meeting-list">{list.map(m => <button key={m.id} disabled={busy} onClick={() => (m.state === 'ENDED' ? setMinutesFor(m) : void action(() => meetingsApi.detail(m.id)))} className="meeting-list-row"><div className="meeting-row-icon">{m.state === 'OPEN' ? '◉' : '✓'}</div><div><strong>{m.title}</strong><p>{new Date(m.createdAt).toLocaleString('ko-KR', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })} · 참여 멤버 {m.memberCount}명 {m.host === user?.username ? '· 내가 만든 회의' : ''}</p></div><span className={m.state === 'OPEN' ? 'meeting-open' : ''}>{m.state === 'OPEN' ? '참여하기 ↗' : '회의록 보기 →'}</span></button>)}</div>}
      <Pager page={pageNo} pages={Math.max(1, Math.ceil(total / PAGE_SIZE))} total={total} onChange={setPageNo} />
      <footer className="meeting-page-note">영상·음성은 로컬 회의 서버로 연결되며 녹음하지 않습니다. 회의록은 회의 채팅과 메모를 기준으로 만들어집니다.</footer>
    </>}
  </section>
}
